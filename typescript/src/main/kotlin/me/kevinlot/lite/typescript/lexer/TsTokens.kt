package me.kevinlot.lite.typescript.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.typescript.TsLanguage

class TsTokenType(debugName: String) : IElementType(debugName, TsLanguage)

/**
 * Tokens. Reserved words are keywords; contextual ones (`type`, `interface`, `as`, `async`…) are lexed as
 * [IDENTIFIER] and remapped to [SOFT_KEYWORD] by the parser where they act as keywords.
 * `>` is always a single token (generics close with `>>`): the parser glues `>>`, `>=`… back together.
 */
object TsTokens {
    val IDENTIFIER = TsTokenType("IDENTIFIER")
    val PRIVATE_NAME = TsTokenType("PRIVATE_NAME")
    val SOFT_KEYWORD = TsTokenType("SOFT_KEYWORD")

    val NUMBER = TsTokenType("NUMBER")
    val STRING = TsTokenType("STRING")
    val REGEX = TsTokenType("REGEX")
    val TEMPLATE_FULL = TsTokenType("TEMPLATE_FULL")     // `...`
    val TEMPLATE_HEAD = TsTokenType("TEMPLATE_HEAD")     // `...${
    val TEMPLATE_MIDDLE = TsTokenType("TEMPLATE_MIDDLE") // }...${
    val TEMPLATE_TAIL = TsTokenType("TEMPLATE_TAIL")     // }...`

    val LINE_COMMENT = TsTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = TsTokenType("BLOCK_COMMENT")
    val DOC_COMMENT = TsTokenType("DOC_COMMENT")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    val JSX_TEXT = TsTokenType("JSX_TEXT")
    /** JSX tag/attribute name with `-` or `:` (`aria-label`, `xlink:href`). */
    val JSX_NAME = TsTokenType("JSX_NAME")

    val LBRACE = TsTokenType("{"); val RBRACE = TsTokenType("}")
    val LPAR = TsTokenType("("); val RPAR = TsTokenType(")")
    val LBRACKET = TsTokenType("["); val RBRACKET = TsTokenType("]")
    val SEMICOLON = TsTokenType(";"); val COMMA = TsTokenType(",")
    val DOT = TsTokenType("."); val QUEST_DOT = TsTokenType("?."); val ELLIPSIS = TsTokenType("...")
    val LT = TsTokenType("<"); val GT = TsTokenType(">")
    val EQ = TsTokenType("="); val ARROW = TsTokenType("=>")
    val QUEST = TsTokenType("?"); val COLON = TsTokenType(":")
    val EXCL = TsTokenType("!"); val TILDE = TsTokenType("~")
    val OR = TsTokenType("|"); val AND = TsTokenType("&")
    val MUL = TsTokenType("*"); val DIV = TsTokenType("/")
    val PLUS = TsTokenType("+"); val MINUS = TsTokenType("-")
    val PLUSPLUS = TsTokenType("++"); val MINUSMINUS = TsTokenType("--")
    val AT = TsTokenType("@")
    /** Every other operator (`===`, `&&`, `+=`, `<=`, `<<`, `**`…): only their position matters. */
    val OPERATOR = TsTokenType("OPERATOR")

    val KEYWORDS: Map<String, TsTokenType> = listOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null",
        "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "let", "await", "yield",
    ).associateWith { TsTokenType(it.uppercase() + "_KEYWORD") }

    fun kw(text: String): TsTokenType = KEYWORDS.getValue(text)

    val KEYWORD_SET: TokenSet = TokenSet.create(*KEYWORDS.values.toTypedArray())
    val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    val WHITESPACES = TokenSet.create(TokenType.WHITE_SPACE)
    val STRINGS = TokenSet.create(STRING, TEMPLATE_FULL, TEMPLATE_HEAD, TEMPLATE_MIDDLE, TEMPLATE_TAIL)
    val TEMPLATES = TokenSet.create(TEMPLATE_FULL, TEMPLATE_HEAD, TEMPLATE_MIDDLE, TEMPLATE_TAIL)
    val OPERATORS = TokenSet.create(LT, GT, EQ, ARROW, QUEST, COLON, EXCL, TILDE, OR, AND, MUL, DIV, PLUS, MINUS, PLUSPLUS, MINUSMINUS, OPERATOR, QUEST_DOT, ELLIPSIS)

    /** Names usable after `.` and as property keys (keywords included). */
    val NAME_LIKE: TokenSet = TokenSet.orSet(TokenSet.create(IDENTIFIER, SOFT_KEYWORD), KEYWORD_SET)
}
