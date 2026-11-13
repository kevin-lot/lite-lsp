package me.kevinlot.lite.kotlin.ide

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteCStyleCommenter
import me.kevinlot.lite.core.color
import me.kevinlot.lite.kotlin.upstream.lexer.KotlinLexer
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N
import me.kevinlot.lite.kotlin.psi.*

object KtColors {
    val KEYWORD = key("KT_KEYWORD", C.KEYWORD)
    val NUMBER = key("KT_NUMBER", C.NUMBER)
    val STRING = key("KT_STRING", C.STRING)
    val ESCAPE = key("KT_ESCAPE", C.VALID_STRING_ESCAPE)
    val TEMPLATE = key("KT_TEMPLATE", C.KEYWORD)
    val LINE_COMMENT = key("KT_LINE_COMMENT", C.LINE_COMMENT)
    val BLOCK_COMMENT = key("KT_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val DOC_COMMENT = key("KT_DOC_COMMENT", C.DOC_COMMENT)
    val OPERATOR = key("KT_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("KT_BRACES", C.BRACES)
    val BRACKETS = key("KT_BRACKETS", C.BRACKETS)
    val PARENTHESES = key("KT_PARENTHESES", C.PARENTHESES)
    val COMMA = key("KT_COMMA", C.COMMA)
    val DOT = key("KT_DOT", C.DOT)
    val SEMICOLON = key("KT_SEMICOLON", C.SEMICOLON)
    val BAD_CHAR = key("KT_BAD_CHAR", HighlighterColors.BAD_CHARACTER)
    val LABEL = key("KT_LABEL", C.LABEL)

    val CLASS = key("KT_CLASS", C.CLASS_NAME)
    val TYPE_PARAMETER = key("KT_TYPE_PARAMETER", C.CLASS_REFERENCE)
    val FUNCTION_DECL = key("KT_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("KT_FUNCTION_CALL", C.FUNCTION_CALL)
    val PROPERTY = key("KT_PROPERTY", C.INSTANCE_FIELD)
    val PARAMETER = key("KT_PARAMETER", C.PARAMETER)
    val ANNOTATION = key("KT_ANNOTATION", C.METADATA)
    val CONSTANT = key("KT_CONSTANT", C.CONSTANT)

    private fun key(name: String, fallback: TextAttributesKey) = createTextAttributesKey(name, fallback)
}

class KtSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = KotlinLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(colorFor(tokenType))

    private fun colorFor(t: IElementType): TextAttributesKey? = when (t) {
        in KtTokens.KEYWORDS -> KtColors.KEYWORD
        KtTokens.INTEGER_LITERAL, KtTokens.FLOAT_LITERAL -> KtColors.NUMBER
        KtTokens.CHARACTER_LITERAL, KtTokens.OPEN_QUOTE, KtTokens.CLOSING_QUOTE, KtTokens.REGULAR_STRING_PART -> KtColors.STRING
        KtTokens.ESCAPE_SEQUENCE -> KtColors.ESCAPE
        KtTokens.SHORT_TEMPLATE_ENTRY_START, KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.LONG_TEMPLATE_ENTRY_END -> KtColors.TEMPLATE
        KtTokens.EOL_COMMENT, KtTokens.SHEBANG_COMMENT -> KtColors.LINE_COMMENT
        KtTokens.BLOCK_COMMENT -> KtColors.BLOCK_COMMENT
        KtTokens.DOC_COMMENT -> KtColors.DOC_COMMENT
        KtTokens.LBRACE, KtTokens.RBRACE -> KtColors.BRACES
        KtTokens.LBRACKET, KtTokens.RBRACKET -> KtColors.BRACKETS
        KtTokens.LPAR, KtTokens.RPAR -> KtColors.PARENTHESES
        KtTokens.COMMA -> KtColors.COMMA
        KtTokens.DOT, KtTokens.SAFE_ACCESS -> KtColors.DOT
        KtTokens.SEMICOLON -> KtColors.SEMICOLON
        TokenType.BAD_CHARACTER -> KtColors.BAD_CHAR
        in KtTokens.OPERATIONS -> KtColors.OPERATOR
        else -> null
    }
}

class KtSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = KtSyntaxHighlighter()
}

/** Structural coloring (declarations, calls, types, annotations, soft keywords): no resolve, stays cheap. */
class KtHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val type = element.node.elementType
        when {
            // Soft and modifier keywords are identifiers for the lexer (`data`, `override`, `get`, `by`…).
            type in SOFT_KEYWORDS && element.parent?.node?.elementType != N.REFERENCE_EXPRESSION -> holder.color(element, KtColors.KEYWORD)
            element is KtClass || element is KtObjectDeclaration || element is KtTypeAlias -> holder.color((element as KtNamedDeclaration).nameIdentifier, KtColors.CLASS)
            element is KtTypeParameter -> holder.color(element.nameIdentifier, KtColors.TYPE_PARAMETER)
            element is KtFunction -> holder.color(element.nameIdentifier, KtColors.FUNCTION_DECL)
            element is KtProperty && !element.isLocal -> holder.color(element.nameIdentifier,
                if (element.hasModifier(KtTokens.CONST_KEYWORD)) KtColors.CONSTANT else KtColors.PROPERTY)
            element is KtParameter && element.isProperty -> holder.color(element.nameIdentifier, KtColors.PROPERTY)
            element is KtEnumEntry -> holder.color(element.nameIdentifier, KtColors.CONSTANT)
            type == N.ANNOTATION_ENTRY -> holder.color(element, KtColors.ANNOTATION)
            type == N.LABEL -> holder.color(element, KtColors.LABEL)
            element is KtNameReferenceExpression -> reference(element, holder)
        }
    }

    private fun reference(ref: KtNameReferenceExpression, holder: AnnotationHolder) {
        val parent = ref.parent ?: return
        val name = ref.referencedName ?: return
        when {
            parent.node.elementType == N.USER_TYPE -> holder.color(ref.identifier, KtColors.CLASS)
            parent.node.elementType == N.CALL_EXPRESSION && parent.firstChild == ref ->
                holder.color(ref.identifier, if (name.first().isUpperCase()) KtColors.CLASS else KtColors.FUNCTION_CALL)
        }
    }

    private companion object {
        val SOFT_KEYWORDS = TokenSet.orSet(KtTokens.SOFT_KEYWORDS, KtTokens.MODIFIER_KEYWORDS)
    }
}

class KtBraceMatcher : LiteBraceMatcher(
    Triple(KtTokens.LBRACE, KtTokens.RBRACE, true), Triple(KtTokens.LBRACKET, KtTokens.RBRACKET, false),
    Triple(KtTokens.LPAR, KtTokens.RPAR, false),
)

class KtCommenter : LiteCStyleCommenter()
