package me.kevinlot.lite.typescript.ide

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
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
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.lexer.TsLexer
import me.kevinlot.lite.typescript.psi.*
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as E

object TsColors {
    val KEYWORD = key("TS_KEYWORD", C.KEYWORD)
    val NUMBER = key("TS_NUMBER", C.NUMBER)
    val STRING = key("TS_STRING", C.STRING)
    val REGEX = key("TS_REGEX", C.VALID_STRING_ESCAPE)
    val LINE_COMMENT = key("TS_LINE_COMMENT", C.LINE_COMMENT)
    val BLOCK_COMMENT = key("TS_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val DOC_COMMENT = key("TS_DOC_COMMENT", C.DOC_COMMENT)
    val OPERATOR = key("TS_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("TS_BRACES", C.BRACES)
    val BRACKETS = key("TS_BRACKETS", C.BRACKETS)
    val PARENTHESES = key("TS_PARENTHESES", C.PARENTHESES)
    val COMMA = key("TS_COMMA", C.COMMA)
    val SEMICOLON = key("TS_SEMICOLON", C.SEMICOLON)
    val DOT = key("TS_DOT", C.DOT)
    val CLASS = key("TS_CLASS", C.CLASS_NAME)
    val INTERFACE = key("TS_INTERFACE", C.INTERFACE_NAME)
    val TYPE_PARAMETER = key("TS_TYPE_PARAMETER", C.CLASS_REFERENCE)
    val FUNCTION_DECL = key("TS_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("TS_FUNCTION_CALL", C.FUNCTION_CALL)
    val METHOD_DECL = key("TS_METHOD_DECL", C.INSTANCE_METHOD)
    val PROPERTY = key("TS_PROPERTY", C.INSTANCE_FIELD)
    val PARAMETER = key("TS_PARAMETER", C.PARAMETER)
    val DECORATOR = key("TS_DECORATOR", C.METADATA)
    val JSX_TAG = key("TS_JSX_TAG", C.MARKUP_TAG)
    val JSX_ATTRIBUTE = key("TS_JSX_ATTRIBUTE", C.MARKUP_ATTRIBUTE)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class TsSyntaxHighlighter(private val jsx: Boolean) : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = TsLexer(jsx)
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when (t) {
            in T.KEYWORD_SET -> TsColors.KEYWORD
            T.NUMBER -> TsColors.NUMBER
            T.STRING, T.TEMPLATE_FULL, T.TEMPLATE_HEAD, T.TEMPLATE_MIDDLE, T.TEMPLATE_TAIL -> TsColors.STRING
            T.REGEX -> TsColors.REGEX
            T.LINE_COMMENT -> TsColors.LINE_COMMENT
            T.BLOCK_COMMENT -> TsColors.BLOCK_COMMENT
            T.DOC_COMMENT -> TsColors.DOC_COMMENT
            T.LBRACE, T.RBRACE -> TsColors.BRACES
            T.LBRACKET, T.RBRACKET -> TsColors.BRACKETS
            T.LPAR, T.RPAR -> TsColors.PARENTHESES
            T.COMMA -> TsColors.COMMA
            T.SEMICOLON -> TsColors.SEMICOLON
            T.DOT -> TsColors.DOT
            in T.OPERATORS -> TsColors.OPERATOR
            else -> null
        },
    )
}

class TsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        TsSyntaxHighlighter(TsFile.hasJsx(virtualFile?.name))
}

/** Structural coloring: contextual keywords, declarations, calls, JSX. No resolve, cheap. */
class TsHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val t = element.node.elementType
        when {
            t == T.SOFT_KEYWORD -> holder.color(element, TsColors.KEYWORD)
            t == E.CLASS_DECLARATION || t == E.CLASS_EXPRESSION || t == E.ENUM_DECLARATION || t == E.TYPE_ALIAS || t == E.NAMESPACE_DECLARATION ->
                holder.color((element as TsNamedDecl).nameIdentifier, TsColors.CLASS)
            t == E.INTERFACE_DECLARATION -> holder.color((element as TsNamedDecl).nameIdentifier, TsColors.INTERFACE)
            t == E.TYPE_PARAMETER -> holder.color((element as TsNamedDecl).nameIdentifier, TsColors.TYPE_PARAMETER)
            t == E.FUNCTION_DECLARATION || t == E.FUNCTION_EXPRESSION -> holder.color((element as TsNamedDecl).nameIdentifier, TsColors.FUNCTION_DECL)
            t == E.METHOD -> holder.color((element as TsNamedDecl).nameIdentifier, TsColors.METHOD_DECL)
            t == E.PROPERTY -> holder.color((element as TsNamedDecl).nameIdentifier, TsColors.PROPERTY)
            t == E.PARAMETER -> holder.color((element as TsNamedDecl).nameIdentifier, TsColors.PARAMETER)
            t == E.DECORATOR -> holder.color(element, TsColors.DECORATOR)
            t == E.JSX_ATTRIBUTE -> holder.color(element.firstChild, TsColors.JSX_ATTRIBUTE)
            element is TsReferenceExpr -> reference(element, holder)
        }
    }

    private fun reference(ref: TsReferenceExpr, holder: AnnotationHolder) {
        val parent = ref.parent?.node?.elementType
        val name = ref.referencedName ?: return
        when {
            parent == E.TYPE_REFERENCE -> holder.color(ref.nameElement, if (name in PRIMITIVE_TYPES) TsColors.KEYWORD else TsColors.CLASS)
            parent == E.JSX_ELEMENT -> holder.color(ref.nameElement, if (name.first().isUpperCase() || ref.qualifier != null) TsColors.CLASS else TsColors.JSX_TAG)
            parent == E.CALL_EXPRESSION && ref.parent.firstChild == ref -> holder.color(ref.nameElement, TsColors.FUNCTION_CALL)
            parent == E.NEW_EXPRESSION -> holder.color(ref.nameElement, TsColors.CLASS)
        }
    }

    private companion object {
        val PRIMITIVE_TYPES = setOf("string", "number", "boolean", "bigint", "symbol", "object", "any", "unknown", "never", "undefined")
    }
}

class TsBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, true), Triple(T.LBRACKET, T.RBRACKET, false), Triple(T.LPAR, T.RPAR, false))

class TsCommenter : LiteCStyleCommenter()

class TsFindUsagesProvider : LiteFindUsagesProvider({ TsLexer() }, TokenSet.create(T.IDENTIFIER, T.PRIVATE_NAME), T.COMMENTS, T.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is TsNamedDecl && element !is TsSpecifier
    override fun kindOf(element: PsiElement) = when (element.node.elementType) {
        E.CLASS_DECLARATION, E.CLASS_EXPRESSION -> "class"
        E.INTERFACE_DECLARATION -> "interface"
        E.TYPE_ALIAS -> "type"
        E.ENUM_DECLARATION -> "enum"
        E.ENUM_MEMBER -> "enum member"
        E.NAMESPACE_DECLARATION, E.AMBIENT_MODULE -> "namespace"
        E.FUNCTION_DECLARATION, E.FUNCTION_EXPRESSION -> "function"
        E.METHOD -> "method"
        E.PROPERTY, E.PROPERTY_ASSIGNMENT, E.SHORTHAND_PROPERTY -> "property"
        E.PARAMETER -> "parameter"
        E.TYPE_PARAMETER -> "type parameter"
        else -> "variable"
    }
}

class TsRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is TsNamedDecl && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is TsNamedDecl
}

open class TsNamesValidator : LiteNamesValidator(T.KEYWORDS.keys) {
    override fun isIdentifier(name: String, project: Project?) = super.isIdentifier(name, project) || (name.isNotEmpty() && name.all { it == '$' || it == '_' || it.isLetterOrDigit() } && !name[0].isDigit() && name !in T.KEYWORDS)
}
