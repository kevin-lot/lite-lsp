package me.kevinlot.lite.lua

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** Lua 5.1–5.4 (and LuaJIT): one language. */
object LuaLanguage : Language("LiteLua") {
    private fun readResolve(): Any = LuaLanguage
    override fun getDisplayName(): String = "Lua"
}

object LuaFileType : LanguageFileType(LuaLanguage) {
    override fun getName(): String = "Lua (Lite)"
    override fun getDisplayName(): String = "Lua"
    override fun getDescription(): String = "Lua script"
    override fun getDefaultExtension(): String = "lua"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/lua.svg", LuaFileType::class.java) }
}

class LuaFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, LuaLanguage) {
    override fun getFileType() = LuaFileType
    override fun toString(): String = "Lua file"
    val block: LuaBlock? get() = findChildByClass(LuaBlock::class.java)
}

class LuaTokenType(debugName: String) : IElementType(debugName, LuaLanguage)

object LuaTokens {
    private fun t(name: String) = LuaTokenType(name)
    val IDENT = t("IDENT")
    val NUMBER = t("NUMBER")
    val STRING = t("STRING")            // '…', "…", [[…]], [==[…]==]
    val COMMENT = t("COMMENT")          // -- … and --[[ … ]]
    val SHEBANG = t("SHEBANG")
    val LPAREN = t("("); val RPAREN = t(")"); val LBRACE = t("{"); val RBRACE = t("}"); val LBRACKET = t("["); val RBRACKET = t("]")
    val DOT = t("."); val COLON = t(":"); val DOUBLE_COLON = t("::"); val COMMA = t(","); val SEMI = t(";"); val ASSIGN = t("=")
    val CONCAT = t(".."); val ELLIPSIS = t("...")
    val PLUS = t("+"); val MINUS = t("-"); val MUL = t("*"); val DIV = t("/"); val IDIV = t("//"); val MOD = t("%"); val POW = t("^"); val LEN = t("#")
    val BAND = t("&"); val BXOR = t("~"); val BOR = t("|"); val SHL = t("<<"); val SHR = t(">>")
    val EQ = t("=="); val NE = t("~="); val LT = t("<"); val LE = t("<="); val GT = t(">"); val GE = t(">=")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    val KEYWORDS: Map<String, LuaTokenType> = listOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while",
    ).associateWith { t(it.uppercase()) }
    fun kw(text: String): LuaTokenType = KEYWORDS.getValue(text)

    val KEYWORD_SET = TokenSet.create(*KEYWORDS.values.toTypedArray())
    val COMMENTS = TokenSet.create(COMMENT, SHEBANG)
    val STRINGS = TokenSet.create(STRING)
    val OPERATORS = TokenSet.create(PLUS, MINUS, MUL, DIV, IDIV, MOD, POW, LEN, BAND, BXOR, BOR, SHL, SHR, EQ, NE, LT, LE, GT, GE, CONCAT, ASSIGN)

    private val SYMBOLS: List<Pair<String, LuaTokenType>> = listOf(
        "..." to ELLIPSIS, ".." to CONCAT, "::" to DOUBLE_COLON, "//" to IDIV, "<<" to SHL, ">>" to SHR, "==" to EQ, "~=" to NE, "<=" to LE, ">=" to GE,
        "(" to LPAREN, ")" to RPAREN, "{" to LBRACE, "}" to RBRACE, "[" to LBRACKET, "]" to RBRACKET, "." to DOT, ":" to COLON, "," to COMMA,
        ";" to SEMI, "=" to ASSIGN, "+" to PLUS, "-" to MINUS, "*" to MUL, "/" to DIV, "%" to MOD, "^" to POW, "#" to LEN, "&" to BAND, "~" to BXOR,
        "|" to BOR, "<" to LT, ">" to GT,
    )
    internal fun symbolAt(buf: CharSequence, pos: Int, end: Int): Pair<String, LuaTokenType>? =
        SYMBOLS.firstOrNull { (s, _) -> pos + s.length <= end && buf.regionMatches(pos, s, 0, s.length) }
}

private fun CharSequence.regionMatches(start: Int, other: String, otherStart: Int, len: Int): Boolean {
    for (i in 0 until len) if (this[start + i] != other[otherStart + i]) return false
    return true
}

/** Stateless: long strings and comments are single tokens, so every token start is a restart point. */
class LuaLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        advance()
    }
    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = pos
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        start = pos
        type = if (pos >= end) null else next()
    }

    private fun at(i: Int) = if (i < end) buf[i] else '\u0000'

    /** `[==[` at [i]: the level (number of `=`), or -1. */
    private fun longOpen(i: Int): Int {
        if (at(i) != '[') return -1
        var j = i + 1
        while (at(j) == '=') j++
        return if (at(j) == '[') j - i - 1 else -1
    }

    private fun skipLong(level: Int) {
        pos += level + 2
        while (pos < end) {
            if (buf[pos] == ']') {
                var j = pos + 1
                var n = 0
                while (at(j) == '=') { j++; n++ }
                if (n == level && at(j) == ']') { pos = j + 1; return }
            }
            pos++
        }
    }

    private fun next(): IElementType {
        val c = buf[pos]
        if (c.isWhitespace()) { while (pos < end && buf[pos].isWhitespace()) pos++; return LuaTokens.WHITE_SPACE }
        if (pos == 0 && c == '#' && at(1) == '!') { while (pos < end && buf[pos] != '\n') pos++; return LuaTokens.SHEBANG }
        if (c == '-' && at(pos + 1) == '-') {
            val level = longOpen(pos + 2)
            if (level >= 0) { pos += 2; skipLong(level) } else while (pos < end && buf[pos] != '\n') pos++
            return LuaTokens.COMMENT
        }
        if (c == '[') { val level = longOpen(pos); if (level >= 0) { skipLong(level); return LuaTokens.STRING } }
        if (c == '"' || c == '\'') {
            pos++
            while (pos < end && buf[pos] != c && buf[pos] != '\n') {
                if (buf[pos] == '\\' && pos + 1 < end) {
                    pos++
                    if (buf[pos] == 'z') { pos++; while (pos < end && buf[pos].isWhitespace()) pos++; continue }
                }
                pos++
            }
            if (pos < end && buf[pos] == c) pos++
            return LuaTokens.STRING
        }
        if (c.isDigit() || (c == '.' && at(pos + 1).isDigit())) { number(); return LuaTokens.NUMBER }
        if (c.isLetter() || c == '_') {
            while (pos < end && (buf[pos].isLetterOrDigit() || buf[pos] == '_')) pos++
            return LuaTokens.KEYWORDS[buf.subSequence(start, pos).toString()] ?: LuaTokens.IDENT
        }
        LuaTokens.symbolAt(buf, pos, end)?.let { (s, t) -> pos += s.length; return t }
        pos++
        return LuaTokens.BAD_CHARACTER
    }

    private fun number() {
        val hex = buf[pos] == '0' && (at(pos + 1) == 'x' || at(pos + 1) == 'X')
        if (hex) pos += 2
        val digit: (Char) -> Boolean = if (hex) { ch -> ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F' } else { ch -> ch.isDigit() }
        while (pos < end && (digit(buf[pos]) || buf[pos] == '.' && at(pos + 1) != '.')) pos++
        val exp = if (hex) "pP" else "eE"
        if (pos < end && buf[pos] in exp) {
            pos++
            if (pos < end && (buf[pos] == '+' || buf[pos] == '-')) pos++
            while (pos < end && buf[pos].isDigit()) pos++
        }
        // LuaJIT suffixes: 1ULL, 2LL, 3i
        while (pos < end && buf[pos] in "uUlLi") pos++
    }
}
