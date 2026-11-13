package me.kevinlot.lite.rust.ide

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
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
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.rust.parser.RsLexer
import me.kevinlot.lite.rust.parser.RustParserDefinition
import me.kevinlot.lite.rust.psi.*
import me.kevinlot.lite.rust.psi.RsElementTypes.*

/** Color keys, mapped onto the theme's defaults so every color scheme works out of the box. */
object RsColors {
    val KEYWORD = key("RS_KEYWORD", C.KEYWORD)
    val IDENTIFIER = key("RS_IDENTIFIER", C.IDENTIFIER)
    val NUMBER = key("RS_NUMBER", C.NUMBER)
    val STRING = key("RS_STRING", C.STRING)
    val CHAR = key("RS_CHAR", C.STRING)
    val LINE_COMMENT = key("RS_LINE_COMMENT", C.LINE_COMMENT)
    val BLOCK_COMMENT = key("RS_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val DOC_COMMENT = key("RS_DOC_COMMENT", C.DOC_COMMENT)
    val OPERATOR = key("RS_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("RS_BRACES", C.BRACES)
    val BRACKETS = key("RS_BRACKETS", C.BRACKETS)
    val PARENTHESES = key("RS_PARENTHESES", C.PARENTHESES)
    val SEMICOLON = key("RS_SEMICOLON", C.SEMICOLON)
    val COMMA = key("RS_COMMA", C.COMMA)
    val DOT = key("RS_DOT", C.DOT)
    val LIFETIME = key("RS_LIFETIME", C.LABEL)
    val BAD_CHAR = key("RS_BAD_CHAR", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER)

    // Semantic (annotator)
    val FUNCTION_DECL = key("RS_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val METHOD_CALL = key("RS_METHOD_CALL", C.INSTANCE_METHOD)
    val FUNCTION_CALL = key("RS_FUNCTION_CALL", C.FUNCTION_CALL)
    val TYPE = key("RS_TYPE", C.CLASS_NAME)
    val TYPE_PARAMETER = key("RS_TYPE_PARAMETER", C.CLASS_REFERENCE)
    val FIELD = key("RS_FIELD", C.INSTANCE_FIELD)
    val CONSTANT = key("RS_CONSTANT", C.CONSTANT)
    val ENUM_VARIANT = key("RS_ENUM_VARIANT", C.STATIC_FIELD)
    val MACRO = key("RS_MACRO", C.METADATA)
    val ATTRIBUTE = key("RS_ATTRIBUTE", C.METADATA)
    val SELF = key("RS_SELF", C.KEYWORD)
    val MUT_BINDING = key("RS_MUT_BINDING", C.LOCAL_VARIABLE)
    val PARAMETER = key("RS_PARAMETER", C.PARAMETER)

    private fun key(name: String, fallback: TextAttributesKey) = createTextAttributesKey(name, fallback)
}

class RsSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = RsLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(colorFor(tokenType))

    private fun colorFor(t: IElementType): TextAttributesKey? = when (t) {
        IDENTIFIER -> RsColors.IDENTIFIER
        QUOTE_IDENTIFIER -> RsColors.LIFETIME
        INTEGER_LITERAL, FLOAT_LITERAL -> RsColors.NUMBER
        CHAR_LITERAL, BYTE_LITERAL -> RsColors.CHAR
        STRING_LITERAL, BYTE_STRING_LITERAL, CSTRING_LITERAL,
        RAW_STRING_LITERAL, RAW_BYTE_STRING_LITERAL, RAW_CSTRING_LITERAL -> RsColors.STRING
        BOOL_LITERAL -> RsColors.KEYWORD
        RustParserDefinition.EOL_COMMENT -> RsColors.LINE_COMMENT
        RustParserDefinition.BLOCK_COMMENT -> RsColors.BLOCK_COMMENT
        in RS_DOC_COMMENTS -> RsColors.DOC_COMMENT
        LBRACE, RBRACE -> RsColors.BRACES
        LBRACK, RBRACK -> RsColors.BRACKETS
        LPAREN, RPAREN -> RsColors.PARENTHESES
        SEMICOLON -> RsColors.SEMICOLON
        COMMA -> RsColors.COMMA
        DOT -> RsColors.DOT
        in RS_KEYWORDS -> RsColors.KEYWORD
        in RS_OPERATORS -> RsColors.OPERATOR
        TokenType.BAD_CHARACTER -> RsColors.BAD_CHAR
        else -> null
    }
}

class RsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = RsSyntaxHighlighter()
}

/**
 * Syntax-based coloring that the lexer cannot do (declarations, calls, types, macros, attributes).
 * Purely structural: no resolve, so it stays cheap on big files.
 */
class RsHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val (target, color) = when (element) {
            is RsFunction -> element.nameIdentifier to RsColors.FUNCTION_DECL
            is RsStructItem, is RsEnumItem, is RsTraitItem, is RsTypeAlias ->
                (element as RsNamedElementLike).nameIdentifier to RsColors.TYPE
            is RsTypeParameter -> element.nameIdentifier to RsColors.TYPE_PARAMETER
            is RsEnumVariant -> element.nameIdentifier to RsColors.ENUM_VARIANT
            is RsConstant -> element.nameIdentifier to RsColors.CONSTANT
            is RsNamedFieldDecl -> element.nameIdentifier to RsColors.FIELD
            is RsFieldLookup -> element.identifier to RsColors.FIELD
            is RsStructLiteralField -> element.identifier to RsColors.FIELD
            is RsMethodCall -> element.identifier to RsColors.METHOD_CALL
            is RsMacroCall -> element.path to RsColors.MACRO
            is RsMacro -> element.nameIdentifier to RsColors.MACRO
            is RsOuterAttr, is RsInnerAttr -> element to RsColors.ATTRIBUTE
            is RsSelfParameter -> element.self to RsColors.SELF
            is RsPath -> pathColor(element)
            else -> return
        }
        target ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(target).textAttributes(color).create()
    }

    /** Type-like paths (`Vec<T>`, `Foo::new`) and calls (`foo()`), from syntax only. */
    private fun pathColor(path: RsPath): Pair<PsiElement?, TextAttributesKey> {
        val id = path.identifier ?: return null to RsColors.TYPE
        val parent = path.parent
        return when {
            parent is RsPathType -> id to RsColors.TYPE
            parent is RsPathExpr && parent.parent is RsCallExpr && (parent.parent as RsCallExpr).expr == parent ->
                id to if (id.text.first().isUpperCase()) RsColors.TYPE else RsColors.FUNCTION_CALL
            parent is RsPath -> if (id.text.first().isUpperCase()) id to RsColors.TYPE else null to RsColors.TYPE
            else -> if (id.text.length > 1 && id.text.all { it.isUpperCase() || it == '_' || it.isDigit() })
                id to RsColors.CONSTANT else null to RsColors.TYPE
        }
    }
}

/** Common shape of the generated named PSI (they all implement PsiNameIdentifierOwner). */
private typealias RsNamedElementLike = com.intellij.psi.PsiNameIdentifierOwner

class RsBraceMatcher : me.kevinlot.lite.core.LiteBraceMatcher(
    Triple(LBRACE, RBRACE, true), Triple(LBRACK, RBRACK, false), Triple(LPAREN, RPAREN, false),
)

class RsCommenter : me.kevinlot.lite.core.LiteCStyleCommenter()
