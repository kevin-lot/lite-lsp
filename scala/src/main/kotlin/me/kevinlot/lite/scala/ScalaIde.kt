package me.kevinlot.lite.scala

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteCStyleCommenter
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.LiteNamesValidator
import me.kevinlot.lite.core.color
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.scala.ScalaElementTypes as E
import me.kevinlot.lite.scala.ScalaTokens as T

object ScalaColors {
    val KEYWORD = key("SCALA_KEYWORD", C.KEYWORD)
    val LINE_COMMENT = key("SCALA_LINE_COMMENT", C.LINE_COMMENT)
    val BLOCK_COMMENT = key("SCALA_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val DOC_COMMENT = key("SCALA_DOC_COMMENT", C.DOC_COMMENT)
    val STRING = key("SCALA_STRING", C.STRING)
    val INTERPOLATION = key("SCALA_INTERPOLATION", C.VALID_STRING_ESCAPE)
    val NUMBER = key("SCALA_NUMBER", C.NUMBER)
    val OPERATOR = key("SCALA_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("SCALA_BRACES", C.BRACES)
    val PARENS = key("SCALA_PARENS", C.PARENTHESES)
    val BRACKETS = key("SCALA_BRACKETS", C.BRACKETS)
    val ANNOTATION = key("SCALA_ANNOTATION", C.METADATA)
    val CLASS = key("SCALA_CLASS", C.CLASS_NAME)
    val TYPE = key("SCALA_TYPE", C.CLASS_REFERENCE)
    val FUNCTION_DECL = key("SCALA_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("SCALA_FUNCTION_CALL", C.FUNCTION_CALL)
    val PARAMETER = key("SCALA_PARAMETER", C.PARAMETER)
    val FIELD = key("SCALA_FIELD", C.INSTANCE_FIELD)
    val CONSTANT = key("SCALA_CONSTANT", C.CONSTANT)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class ScalaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ScalaLexer()
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when (t) {
            in T.KEYWORD_SET -> ScalaColors.KEYWORD
            T.LINE_COMMENT, T.SHEBANG -> ScalaColors.LINE_COMMENT
            T.BLOCK_COMMENT -> ScalaColors.BLOCK_COMMENT
            T.DOC_COMMENT -> ScalaColors.DOC_COMMENT
            T.STRING, T.CHAR, T.SYMBOL, T.ISTRING_BEGIN, T.ISTRING_TEXT, T.ISTRING_END -> ScalaColors.STRING
            T.DOLLAR, T.INTERPOLATOR -> ScalaColors.INTERPOLATION
            T.NUMBER -> ScalaColors.NUMBER
            in T.OPERATORS -> ScalaColors.OPERATOR
            T.LBRACE, T.RBRACE -> ScalaColors.BRACES
            T.LPAREN, T.RPAREN -> ScalaColors.PARENS
            T.LBRACKET, T.RBRACKET -> ScalaColors.BRACKETS
            else -> null
        },
    )
}

class ScalaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ScalaSyntaxHighlighter()
}

/** Structure only (no resolve): declarations, called names, types, annotations and the Scala 3 keywords the lexer reads as names. */
class ScalaHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is ScalaClass -> holder.color(element.nameIdentifier, ScalaColors.CLASS)
            is ScalaEnumCase -> holder.color(element.nameIdentifier, ScalaColors.CONSTANT)
            is ScalaFunction -> holder.color(element.nameIdentifier, ScalaColors.FUNCTION_DECL)
            is ScalaMember -> colorMember(element, holder)
            is ScalaNamedArg -> holder.color(element.nameElement, ScalaColors.PARAMETER)
            is ScalaRef -> if (element.isCallee) holder.color(element, ScalaColors.FUNCTION_CALL)
            is ScalaType -> holder.color(element.nameElement, when { element.parent.isA(E.ANNOTATION) -> ScalaColors.ANNOTATION; else -> ScalaColors.TYPE })
            is ScalaTypeAlias, is ScalaTypeParam -> holder.color((element as ScalaNamed).nameIdentifier, ScalaColors.TYPE)
            is ScalaVariable -> colorVariable(element, holder)
            else -> colorToken(element, holder)
        }
    }

    private fun colorMember(member: ScalaMember, holder: AnnotationHolder) {
        if (PATH_OWNERS.any { member.parent.isA(it) }) return
        holder.color(member.nameElement?.takeIf { !member.isThis }, when { member.isCallee -> ScalaColors.FUNCTION_CALL; else -> ScalaColors.FIELD })
    }

    private fun colorVariable(variable: ScalaVariable, holder: AnnotationHolder) {
        when {
            variable.isParam -> holder.color(variable.nameIdentifier, ScalaColors.PARAMETER)
            variable.isMember -> holder.color(variable.nameIdentifier, ScalaColors.FIELD)
        }
    }

    private fun colorToken(element: PsiElement, holder: AnnotationHolder) {
        when {
            element.isA(T.SOFT_KEYWORD) -> holder.color(element, ScalaColors.KEYWORD)
            element.isA(T.AT) && element.parent.isA(E.ANNOTATION) -> holder.color(element, ScalaColors.ANNOTATION)
        }
    }

    private companion object {
        val PATH_OWNERS = listOf(E.IMPORT_EXPR, E.MEMBER)
    }
}

class ScalaBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, true), Triple(T.LPAREN, T.RPAREN, false), Triple(T.LBRACKET, T.RBRACKET, false))

class ScalaCommenter : LiteCStyleCommenter()

class ScalaFindUsagesProvider : LiteFindUsagesProvider(::ScalaLexer, TokenSet.create(T.IDENT), T.COMMENTS, T.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is ScalaNamed
    override fun kindOf(element: PsiElement) = when {
        element is ScalaClass && element.isObject -> "object"
        element is ScalaClass && element.isTrait -> "trait"
        element is ScalaClass && element.isEnum -> "enum"
        element is ScalaClass -> "class"
        element is ScalaEnumCase -> "enum case"
        element is ScalaFunction -> "method"
        element is ScalaGiven -> "given"
        element is ScalaTypeAlias -> "type"
        element is ScalaTypeParam -> "type parameter"
        element is ScalaVariable && element.isParam -> "parameter"
        element is ScalaVariable && element.isMember -> "field"
        else -> "variable"
    }
}

class ScalaRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is ScalaVariable && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is ScalaNamed
}

/** A class and its companion object share their name: renaming one renames the other. */
class ScalaRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is ScalaClass

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        (element as? ScalaClass)?.companion?.let { allRenames[it] = newName }
    }
}

class ScalaNamesValidator : LiteNamesValidator(T.KEYWORDS.keys) {
    override fun unescape(name: String): String = unquoted(name)
    override fun isEscaped(name: String): Boolean = name.length > 2 && name.startsWith('`') && name.endsWith('`')
}
