// Token sets derived from intellij-rust (MIT, see THIRD_PARTY_LICENSE-intellij-rust.txt).
package me.kevinlot.lite.rust.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.rust.RsLanguage
import me.kevinlot.lite.rust.parser.RustParserDefinition.Companion.BLOCK_COMMENT
import me.kevinlot.lite.rust.parser.RustParserDefinition.Companion.EOL_COMMENT
import me.kevinlot.lite.rust.parser.RustParserDefinition.Companion.INNER_BLOCK_DOC_COMMENT
import me.kevinlot.lite.rust.parser.RustParserDefinition.Companion.INNER_EOL_DOC_COMMENT
import me.kevinlot.lite.rust.parser.RustParserDefinition.Companion.OUTER_BLOCK_DOC_COMMENT
import me.kevinlot.lite.rust.parser.RustParserDefinition.Companion.OUTER_EOL_DOC_COMMENT
import me.kevinlot.lite.rust.psi.RsElementTypes.*

open class RsTokenType(debugName: String) : IElementType(debugName, RsLanguage)
class RsElementType(debugName: String) : IElementType(debugName, RsLanguage)

/** Marker for elements that directly contain items (file, inline `mod`, block). */
interface RsItemsOwner : PsiElement

fun tokenSetOf(vararg tokens: IElementType): TokenSet = TokenSet.create(*tokens)

val RS_KEYWORDS = tokenSetOf(
    AS, ASYNC, AUTO, BOX, BREAK, CONST, CONTINUE, CRATE, CSELF, DEFAULT, DYN, ELSE, ENUM, EXTERN,
    FN, FOR, IF, IMPL, IN, MACRO_KW, LET, LOOP, MATCH, MOD, MOVE, MUT, PUB, RAW, REF, RETURN,
    SELF, STATIC, STRUCT, SUPER, TRAIT, TYPE_KW, UNION, UNSAFE, USE, WHERE, WHILE, YIELD,
)

val RS_OPERATORS = tokenSetOf(
    AND, ANDEQ, ARROW, FAT_ARROW, SHA, COLON, COLONCOLON, COMMA, DIV, DIVEQ, DOT, DOTDOT, DOTDOTDOT,
    DOTDOTEQ, EQ, EQEQ, EXCL, EXCLEQ, GT, LT, MINUS, MINUSEQ, MUL, MULEQ, OR, OREQ, PLUS, PLUSEQ,
    REM, REMEQ, SEMICOLON, XOR, XOREQ, Q, AT, DOLLAR, GTGTEQ, GTGT, GTEQ, LTLTEQ, LTLT, LTEQ, OROR, ANDAND,
)

val RS_DOC_COMMENTS = tokenSetOf(INNER_BLOCK_DOC_COMMENT, INNER_EOL_DOC_COMMENT, OUTER_BLOCK_DOC_COMMENT, OUTER_EOL_DOC_COMMENT)
val RS_REGULAR_COMMENTS = tokenSetOf(BLOCK_COMMENT, EOL_COMMENT)
val RS_COMMENTS: TokenSet = TokenSet.orSet(RS_REGULAR_COMMENTS, RS_DOC_COMMENTS)

val RS_ALL_STRING_LITERALS = tokenSetOf(
    STRING_LITERAL, BYTE_STRING_LITERAL, CSTRING_LITERAL,
    RAW_STRING_LITERAL, RAW_BYTE_STRING_LITERAL, RAW_CSTRING_LITERAL,
)
val RS_BLOCK_LIKE_EXPRESSIONS = tokenSetOf(WHILE_EXPR, IF_EXPR, FOR_EXPR, LOOP_EXPR, MATCH_EXPR, BLOCK_EXPR)

/** Brace kinds of token trees. */
enum class MacroBraces(val openText: String, val closeText: String, val openToken: IElementType, val closeToken: IElementType) {
    PARENS("(", ")", LPAREN, RPAREN),
    BRACKS("[", "]", LBRACK, RBRACK),
    BRACES("{", "}", LBRACE, RBRACE);

    val needsSemicolon: Boolean get() = this != BRACES

    companion object {
        fun fromToken(token: IElementType): MacroBraces? = when (token) {
            LPAREN, RPAREN -> PARENS
            LBRACK, RBRACK -> BRACKS
            LBRACE, RBRACE -> BRACES
            else -> null
        }

        fun fromOpenToken(token: IElementType): MacroBraces? = when (token) {
            LPAREN -> PARENS
            LBRACK -> BRACKS
            LBRACE -> BRACES
            else -> null
        }

        fun fromTokenOrFail(token: IElementType): MacroBraces = fromToken(token) ?: error("Not a brace: $token")
    }
}
