package me.kevinlot.lite.groovy

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
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteCStyleCommenter
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.LiteNamesValidator
import me.kevinlot.lite.core.color
import me.kevinlot.lite.groovy.GroovyElementTypes as E
import me.kevinlot.lite.groovy.GroovyTokens as T

object GroovyColors {
    val KEYWORD = key("GROOVY_KEYWORD", C.KEYWORD)
    val LINE_COMMENT = key("GROOVY_LINE_COMMENT", C.LINE_COMMENT)
    val BLOCK_COMMENT = key("GROOVY_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val DOC_COMMENT = key("GROOVY_DOC_COMMENT", C.DOC_COMMENT)
    val STRING = key("GROOVY_STRING", C.STRING)
    val INTERPOLATION = key("GROOVY_INTERPOLATION", C.VALID_STRING_ESCAPE)
    val NUMBER = key("GROOVY_NUMBER", C.NUMBER)
    val OPERATOR = key("GROOVY_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("GROOVY_BRACES", C.BRACES)
    val PARENS = key("GROOVY_PARENS", C.PARENTHESES)
    val BRACKETS = key("GROOVY_BRACKETS", C.BRACKETS)
    val ANNOTATION = key("GROOVY_ANNOTATION", C.METADATA)
    val CLASS = key("GROOVY_CLASS", C.CLASS_NAME)
    val TYPE = key("GROOVY_TYPE", C.CLASS_REFERENCE)
    val FUNCTION_DECL = key("GROOVY_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("GROOVY_FUNCTION_CALL", C.FUNCTION_CALL)
    val PARAMETER = key("GROOVY_PARAMETER", C.PARAMETER)
    val FIELD = key("GROOVY_FIELD", C.INSTANCE_FIELD)
    val CONSTANT = key("GROOVY_CONSTANT", C.CONSTANT)
    val LABEL = key("GROOVY_LABEL", C.LABEL)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class GroovySyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GroovyLexer()
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when (t) {
            in T.KEYWORD_SET -> GroovyColors.KEYWORD
            T.LINE_COMMENT, T.SHEBANG -> GroovyColors.LINE_COMMENT
            T.BLOCK_COMMENT -> GroovyColors.BLOCK_COMMENT
            T.DOC_COMMENT -> GroovyColors.DOC_COMMENT
            T.STRING, T.GSTRING_BEGIN, T.GSTRING_TEXT, T.GSTRING_END -> GroovyColors.STRING
            T.DOLLAR -> GroovyColors.INTERPOLATION
            T.NUMBER -> GroovyColors.NUMBER
            in T.OPERATORS -> GroovyColors.OPERATOR
            T.LBRACE, T.RBRACE -> GroovyColors.BRACES
            T.LPAREN, T.RPAREN -> GroovyColors.PARENS
            T.LBRACKET, T.RBRACKET -> GroovyColors.BRACKETS
            else -> null
        },
    )
}

class GroovySyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = GroovySyntaxHighlighter()
}

/** Structure only (no resolve): declarations, called names, types, annotations, labels and the keywords the lexer reads as names. */
class GroovyHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is GroovyClass -> {
                holder.color(element.nameIdentifier, GroovyColors.CLASS)
                holder.color(element.nameIdentifier?.let(::contextualKeywordBefore), GroovyColors.KEYWORD)
            }
            is GroovyMethod -> holder.color(element.nameIdentifier, GroovyColors.FUNCTION_DECL)
            is GroovyEnumConstant -> holder.color(element.nameIdentifier, GroovyColors.CONSTANT)
            is GroovyVariable -> when {
                element.isParameter -> holder.color(element.nameIdentifier, GroovyColors.PARAMETER)
                element.isField -> holder.color(element.nameIdentifier, GroovyColors.FIELD)
            }
            is GroovyType -> holder.color(element.nameElement, GroovyColors.TYPE)
            is GroovyNameRef -> if (element.isCallee) holder.color(element, GroovyColors.FUNCTION_CALL)
            is GroovyMember -> holder.color(element.nameElement, when {
                element.isCallee -> GroovyColors.FUNCTION_CALL
                else -> GroovyColors.FIELD
            })
            else -> annotateByType(element, holder)
        }
    }

    private fun annotateByType(element: PsiElement, holder: AnnotationHolder) {
        when (element.node.elementType) {
            E.ANNOTATION -> holder.color(element, GroovyColors.ANNOTATION)
            E.ENTRY -> holder.color(element.firstChild?.takeIf { it.node.elementType == T.IDENT }, GroovyColors.LABEL)
            E.LABELED -> holder.color(element.firstChild, GroovyColors.LABEL)
            E.MODIFIERS -> holder.color(element.node.findChildByType(T.IDENT)?.psi, GroovyColors.KEYWORD)
        }
    }

    /** `trait` / `record` before a class name. */
    private fun contextualKeywordBefore(name: PsiElement): PsiElement? =
        generateSequence(name.prevSibling) { it.prevSibling }.firstOrNull { it.node.elementType == T.IDENT && it.text in T.CONTEXTUAL_KEYWORDS }
}

class GroovyBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, true), Triple(T.LPAREN, T.RPAREN, false), Triple(T.LBRACKET, T.RBRACKET, false))

class GroovyCommenter : LiteCStyleCommenter()

class GroovyFindUsagesProvider : LiteFindUsagesProvider(::GroovyLexer, TokenSet.create(T.IDENT), T.COMMENTS, T.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is GroovyNamed || (element is GroovyNameRef && element.isBindingDefinition)
    override fun kindOf(element: PsiElement) = when {
        element is GroovyClass -> "class"
        element is GroovyMethod -> "method"
        element is GroovyEnumConstant -> "enum constant"
        element is GroovyVariable && element.isParameter -> "parameter"
        element is GroovyVariable && element.isField -> "field"
        element is GroovyNameRef -> "script binding"
        else -> "variable"
    }
}

class GroovyRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is GroovyVariable && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is GroovyNamed || element is GroovyNameRef
}

class GroovyNamesValidator : LiteNamesValidator(T.KEYWORDS.keys)
