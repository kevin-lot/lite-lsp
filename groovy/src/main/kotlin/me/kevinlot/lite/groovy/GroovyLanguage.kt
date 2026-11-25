package me.kevinlot.lite.groovy

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

/** Groovy 2 to 4: scripts, classes, Gradle build scripts and Jenkins pipelines are one language. */
object GroovyLanguage : Language("LiteGroovy") {
    private fun readResolve(): Any = GroovyLanguage
    override fun getDisplayName(): String = "Groovy"
}

object GroovyFileType : LanguageFileType(GroovyLanguage) {
    override fun getName(): String = "Groovy (Lite)"
    override fun getDisplayName(): String = "Groovy (Lite)"
    override fun getDescription(): String = "Groovy source, Gradle build script or Jenkins pipeline"
    override fun getDefaultExtension(): String = "groovy"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/groovy.svg", GroovyFileType::class.java) }
}

class GroovyFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GroovyLanguage) {
    override fun getFileType() = GroovyFileType
    override fun toString(): String = "Groovy file"
}

class GroovyTokenType(debugName: String) : IElementType(debugName, GroovyLanguage)

object GroovyTokens {
    private fun t(name: String) = GroovyTokenType(name)
    val IDENT = t("IDENT")
    val NUMBER = t("NUMBER")
    val STRING = t("STRING")                 // '…', '''…''', and "…", """…""", /…/, $/…/$ without interpolation
    val GSTRING_BEGIN = t("GSTRING_BEGIN")   // opening delimiter of an interpolated string
    val GSTRING_TEXT = t("GSTRING_TEXT")
    val GSTRING_END = t("GSTRING_END")
    val DOLLAR = t("$")                      // starts `$name`, `$a.b` or `${expr}` inside a string
    val LINE_COMMENT = t("LINE_COMMENT")
    val BLOCK_COMMENT = t("BLOCK_COMMENT")
    val DOC_COMMENT = t("DOC_COMMENT")
    val SHEBANG = t("SHEBANG")
    val LPAREN = t("("); val RPAREN = t(")"); val LBRACE = t("{"); val RBRACE = t("}"); val LBRACKET = t("["); val RBRACKET = t("]")
    val DOT = t("."); val SAFE_DOT = t("?."); val SAFE_CHAIN_DOT = t("??."); val SPREAD_DOT = t("*."); val METHOD_POINTER = t(".&")
    val ATTRIBUTE_DOT = t(".@"); val METHOD_REF = t("::")
    val COMMA = t(","); val SEMI = t(";"); val COLON = t(":"); val AT = t("@"); val ARROW = t("->"); val ELLIPSIS = t("...")
    val QUESTION = t("?"); val ELVIS = t("?:"); val RANGE = t(".."); val RANGE_EXCLUSIVE = t("..<")
    val ASSIGN = t("="); val PLUS_ASSIGN = t("+="); val MINUS_ASSIGN = t("-="); val MUL_ASSIGN = t("*="); val DIV_ASSIGN = t("/=")
    val MOD_ASSIGN = t("%="); val POW_ASSIGN = t("**="); val AND_ASSIGN = t("&="); val OR_ASSIGN = t("|="); val XOR_ASSIGN = t("^=")
    val SHL_ASSIGN = t("<<="); val ELVIS_ASSIGN = t("?=")
    val PLUS = t("+"); val MINUS = t("-"); val MUL = t("*"); val DIV = t("/"); val MOD = t("%"); val POW = t("**"); val INC = t("++"); val DEC = t("--")
    val NOT = t("!"); val BNOT = t("~"); val AND = t("&&"); val OR = t("||"); val BAND = t("&"); val BOR = t("|"); val XOR = t("^"); val SHL = t("<<")
    val EQ = t("=="); val NE = t("!="); val IDENTICAL = t("==="); val NOT_IDENTICAL = t("!=="); val SPACESHIP = t("<=>")
    val REGEX_FIND = t("=~"); val REGEX_MATCH = t("==~")
    val LT = t("<"); val LE = t("<="); val GT = t(">"); val GE = t(">=")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    val KEYWORDS: Map<String, GroovyTokenType> = listOf(
        "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "def", "default", "do",
        "double", "else", "enum", "extends", "false", "final", "finally", "float", "for", "goto", "if", "implements", "import", "in",
        "instanceof", "int", "interface", "long", "native", "new", "null", "package", "private", "protected", "public", "return", "short",
        "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "true", "try", "void", "volatile",
        "while",
    ).associateWith { t(it.uppercase()) }
    fun kw(text: String): GroovyTokenType = KEYWORDS.getValue(text)

    /** Identifiers that act as keywords in one position only (`trait T {}`, `var x = 1`): the parser reads their text. */
    val CONTEXTUAL_KEYWORDS = setOf("permits", "record", "sealed", "trait", "var", "yield")

    val KEYWORD_SET = TokenSet.create(*KEYWORDS.values.toTypedArray())
    val PRIMITIVES = TokenSet.create(*listOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void").map(::kw).toTypedArray())
    val COMMENTS = TokenSet.create(BLOCK_COMMENT, DOC_COMMENT, LINE_COMMENT, SHEBANG)
    val STRINGS = TokenSet.create(GSTRING_TEXT, STRING)
    val MEMBER_SELECTORS = TokenSet.create(ATTRIBUTE_DOT, DOT, METHOD_POINTER, METHOD_REF, SAFE_CHAIN_DOT, SAFE_DOT, SPREAD_DOT)
    val ASSIGNMENTS = TokenSet.create(
        AND_ASSIGN, ASSIGN, DIV_ASSIGN, ELVIS_ASSIGN, MINUS_ASSIGN, MOD_ASSIGN, MUL_ASSIGN, OR_ASSIGN, PLUS_ASSIGN, POW_ASSIGN, SHL_ASSIGN, XOR_ASSIGN,
    )
    val OPERATORS = TokenSet.orSet(
        ASSIGNMENTS,
        TokenSet.create(
            AND, ARROW, BAND, BNOT, BOR, DEC, DIV, ELVIS, EQ, GE, GT, IDENTICAL, INC, LE, LT, MINUS, MOD, MUL, NE, NOT, NOT_IDENTICAL, OR, PLUS,
            POW, QUESTION, RANGE, RANGE_EXCLUSIVE, REGEX_FIND, REGEX_MATCH, SHL, SPACESHIP, XOR,
        ),
    )

    /** Keywords after which an expression starts: a `/` there opens a slashy string (rule of the official lexer). */
    private val EXPRESSION_INTRODUCERS = TokenSet.create(*listOf("assert", "case", "do", "else", "in", "return", "throw").map(::kw).toTypedArray())

    /** Tokens that end a value: a `/` after them divides. */
    val VALUE_ENDS = TokenSet.orSet(
        TokenSet.create(DEC, GSTRING_END, IDENT, INC, NUMBER, RBRACE, RBRACKET, RPAREN, STRING),
        TokenSet.andNot(KEYWORD_SET, EXPRESSION_INTRODUCERS),
    )

    // `>>`, `>>>` and `>>=` are left as single `>`: the parser needs them apart to close nested generics
    private val SYMBOLS: List<Pair<String, GroovyTokenType>> = listOf(
        "..." to ELLIPSIS, "..<" to RANGE_EXCLUSIVE, ".." to RANGE, ".&" to METHOD_POINTER, ".@" to ATTRIBUTE_DOT, "." to DOT,
        "??." to SAFE_CHAIN_DOT, "?." to SAFE_DOT, "?:" to ELVIS, "?=" to ELVIS_ASSIGN, "?" to QUESTION,
        "**=" to POW_ASSIGN, "**" to POW, "*." to SPREAD_DOT, "*=" to MUL_ASSIGN, "*" to MUL,
        "::" to METHOD_REF, ":" to COLON,
        "->" to ARROW, "--" to DEC, "-=" to MINUS_ASSIGN, "-" to MINUS,
        "++" to INC, "+=" to PLUS_ASSIGN, "+" to PLUS,
        "==~" to REGEX_MATCH, "===" to IDENTICAL, "==" to EQ, "=~" to REGEX_FIND, "=" to ASSIGN,
        "!==" to NOT_IDENTICAL, "!=" to NE, "!" to NOT,
        "<=>" to SPACESHIP, "<<=" to SHL_ASSIGN, "<<" to SHL, "<=" to LE, "<" to LT,
        ">=" to GE, ">" to GT,
        "&&" to AND, "&=" to AND_ASSIGN, "&" to BAND,
        "||" to OR, "|=" to OR_ASSIGN, "|" to BOR,
        "^=" to XOR_ASSIGN, "^" to XOR, "/=" to DIV_ASSIGN, "/" to DIV, "%=" to MOD_ASSIGN, "%" to MOD, "~" to BNOT,
        "(" to LPAREN, ")" to RPAREN, "{" to LBRACE, "}" to RBRACE, "[" to LBRACKET, "]" to RBRACKET,
        "," to COMMA, ";" to SEMI, "@" to AT,
    )
    private val SYMBOLS_BY_FIRST_CHAR = SYMBOLS.groupBy { (s, _) -> s[0] }
    internal fun symbolAt(buf: CharSequence, pos: Int, end: Int): Pair<String, GroovyTokenType>? =
        SYMBOLS_BY_FIRST_CHAR[buf[pos]]?.firstOrNull { (s, _) -> pos + s.length <= end && s.indices.all { buf[pos + it] == s[it] } }
}

private typealias T = GroovyTokens

/** The strings that interpolate; a single-quoted string never does and is one token. */
private enum class Quote(val open: String, val close: String, val multiline: Boolean, val escape: (Char, Char) -> Boolean) {
    DOUBLE("\"", "\"", false, { c, _ -> c == '\\' }),
    TRIPLE_DOUBLE("\"\"\"", "\"\"\"", true, { c, _ -> c == '\\' }),
    SLASHY("/", "/", true, { c, next -> c == '\\' && next == '/' }),
    DOLLAR_SLASHY("$/", "/$", true, { c, next -> c == '$' && (next == '$' || next == '/') }),
}

/**
 * Groovy lexer. A frame stack follows the nesting of interpolated strings: the text of a string, a `$a.b` path
 * and a `${ … }` expression, whose tokens are those of code. A `/` opens a slashy string unless the previous
 * token ends a value, as in the official lexers.
 *
 * State: 0 = code outside any string, where a `/` would open a slashy string and a keyword is a keyword (a restart
 * point); 1 = anywhere else.
 */
class GroovyLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null
    private var state = 0
    private var afterValue = false
    private var afterSelector = false
    private val stack = ArrayList<Frame>()

    /** [quote] = null: code inside `${ }` ([depth] of braces) or a `$a.b` path ([depth] 0 before a name, 1 after one). */
    private class Frame(val quote: Quote?, val path: Boolean = false, var depth: Int = 0)

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        stack.clear(); afterValue = false; afterSelector = false
        advance()
    }

    override fun getState() = state
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = pos
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        start = pos
        state = when {
            stack.isEmpty() && !afterValue && !afterSelector -> 0
            else -> 1
        }
        if (pos >= end) { type = null; return }
        val token = next()
        type = token
        if (token == T.WHITE_SPACE || token in T.COMMENTS) return
        afterValue = token in T.VALUE_ENDS
        afterSelector = token in T.MEMBER_SELECTORS
    }

    private fun at(i: Int) = when { i < end -> buf[i]; else -> '\u0000' }
    private fun matches(i: Int, s: String) = i + s.length <= end && s.indices.all { buf[i + it] == s[it] }
    private fun isNameStart(c: Char) = c.isLetter() || c == '_'
    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_'
    private fun pop() { stack.removeAt(stack.size - 1) }

    private fun next(): IElementType {
        while (true) {
            val top = stack.lastOrNull()
            val token = when {
                top == null -> code()
                top.quote != null -> text(top.quote)
                top.path -> path(top)
                else -> code()
            }
            if (token != null) return token
        }
    }

    // ---------------------------------------------------------------- code

    private fun code(): IElementType {
        val c = buf[pos]
        if (c.isWhitespace()) return whitespace()
        if (c == '\\' && at(pos + 1) == '\n') { pos += 2; return T.WHITE_SPACE }
        if (pos == 0 && c == '#' && at(1) == '!') { skipLine(); return T.SHEBANG }
        if (c == '/') return slash()
        if (c == '\'') return singleQuoted()
        if (c == '"') return quoted(when { matches(pos, Quote.TRIPLE_DOUBLE.open) -> Quote.TRIPLE_DOUBLE; else -> Quote.DOUBLE }) ?: symbol()
        if (c == '$' && at(pos + 1) == '/' && !afterValue) quoted(Quote.DOLLAR_SLASHY)?.let { return it }
        if (c.isDigit()) { number(); return T.NUMBER }
        if (isNameStart(c) || c == '$') return word()
        return symbol()
    }

    private fun whitespace(): IElementType {
        while (pos < end && buf[pos].isWhitespace()) {
            if (buf[pos] == '\n') afterValue = false
            pos++
        }
        return T.WHITE_SPACE
    }

    private fun skipLine() { while (pos < end && buf[pos] != '\n') pos++ }

    private fun slash(): IElementType {
        if (at(pos + 1) == '/') { skipLine(); return T.LINE_COMMENT }
        if (at(pos + 1) == '*') return blockComment()
        if (!afterValue) quoted(Quote.SLASHY)?.let { return it }
        return symbol()
    }

    private fun blockComment(): IElementType {
        val doc = at(pos + 2) == '*' && at(pos + 3) != '/'
        val close = buf.indexOf("*/", pos + 2).takeIf { it in 0 until end - 1 }
        pos = close?.plus(2) ?: end
        return when { doc -> T.DOC_COMMENT; else -> T.BLOCK_COMMENT }
    }

    private fun symbol(): IElementType {
        val (text, token) = T.symbolAt(buf, pos, end) ?: run { pos++; return T.BAD_CHARACTER }
        pos += text.length
        val braces = stack.lastOrNull() ?: return token
        if (token == T.LBRACE) braces.depth++
        if (token == T.RBRACE && --braces.depth <= 0) pop()
        return token
    }

    private fun word(): IElementType {
        while (pos < end && (isNamePart(buf[pos]) || buf[pos] == '$')) pos++
        if (afterSelector) return T.IDENT
        return T.KEYWORDS[buf.subSequence(start, pos).toString()] ?: T.IDENT
    }

    private fun number() {
        val radix = at(pos + 1).lowercaseChar().takeIf { buf[pos] == '0' && (it == 'x' || it == 'b') }
        if (radix != null) pos += 2
        val digit: (Char) -> Boolean = { ch -> ch.isDigit() || ch == '_' || (radix == 'x' && ch.lowercaseChar() in 'a'..'f') }
        while (pos < end && digit(buf[pos])) pos++
        if (radix != null) { suffix("gGlLiI"); return }
        if (at(pos) == '.' && at(pos + 1).isDigit()) { pos++; while (pos < end && digit(buf[pos])) pos++ }
        val exponent = at(pos).lowercaseChar() == 'e' && (at(pos + 1).isDigit() || (at(pos + 1) in "+-" && at(pos + 2).isDigit()))
        if (exponent) { pos += 2; while (pos < end && buf[pos].isDigit()) pos++ }
        suffix("gGlLiIdDfF")
    }

    private fun suffix(letters: String) { if (at(pos) in letters && !isNamePart(at(pos + 1))) pos++ }

    // ---------------------------------------------------------------- strings

    private fun singleQuoted(): IElementType {
        val triple = matches(pos, "'''")
        pos += when { triple -> 3; else -> 1 }
        while (pos < end) {
            if (triple && matches(pos, "'''")) { pos += 3; return T.STRING }
            if (!triple && buf[pos] == '\'') { pos++; return T.STRING }
            if (!triple && buf[pos] == '\n') return T.STRING
            pos += when { buf[pos] == '\\' -> 2; else -> 1 }
        }
        pos = end
        return T.STRING
    }

    private fun closes(q: Quote, i: Int) = matches(i, q.close)
    private fun interpolates(i: Int) = buf[i] == '$' && (at(i + 1) == '{' || isNameStart(at(i + 1)))

    /** Where the literal text from [from] stops: at the closing delimiter, an interpolation, the line end of a one-line string, or the end. */
    private fun textEnd(q: Quote, from: Int): Int {
        var i = from
        while (i < end) {
            if (closes(q, i) || interpolates(i) || (!q.multiline && buf[i] == '\n')) return i
            i += when { q.escape(buf[i], at(i + 1)) -> 2; else -> 1 }
        }
        return end
    }

    /** One STRING token without interpolation, else the opening delimiter. Null: an unclosed `/` or `$/`, which is no string. */
    private fun quoted(q: Quote): IElementType? {
        val textStart = pos + q.open.length
        val stop = textEnd(q, textStart)
        if (stop < end && closes(q, stop)) { pos = stop + q.close.length; return T.STRING }
        if (stop < end && interpolates(stop)) { pos = textStart; stack += Frame(q); return T.GSTRING_BEGIN }
        if (q == Quote.SLASHY || q == Quote.DOLLAR_SLASHY) return null
        pos = stop
        return T.STRING
    }

    private fun text(q: Quote): IElementType? {
        if (closes(q, pos)) { pos += q.close.length; pop(); return T.GSTRING_END }
        if (interpolates(pos)) {
            pos++
            stack += Frame(null, path = buf[pos] != '{')
            return T.DOLLAR
        }
        if (!q.multiline && buf[pos] == '\n') { pop(); return null }
        pos = textEnd(q, pos)
        return T.GSTRING_TEXT
    }

    /** `$a.b.c`: names (never keywords, `this` excepted) joined by dots; a dot not followed by a name is text again. */
    private fun path(frame: Frame): IElementType? {
        if (frame.depth == 0 && isNameStart(buf[pos])) {
            while (pos < end && isNamePart(buf[pos])) pos++
            frame.depth = 1
            return T.kw("this").takeIf { buf.subSequence(start, pos).toString() == "this" } ?: T.IDENT
        }
        if (frame.depth == 1 && buf[pos] == '.' && isNameStart(at(pos + 1))) { pos++; frame.depth = 0; return T.DOT }
        pop()
        return null
    }
}
