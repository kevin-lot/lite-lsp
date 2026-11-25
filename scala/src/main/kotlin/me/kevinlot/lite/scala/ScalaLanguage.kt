package me.kevinlot.lite.scala

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

/** Scala 2 and 3: sources, worksheets / scripts and sbt build files are one language. */
object ScalaLanguage : Language("LiteScala") {
    private fun readResolve(): Any = ScalaLanguage
    override fun getDisplayName(): String = "Scala"
}

object ScalaFileType : LanguageFileType(ScalaLanguage) {
    override fun getName(): String = "Scala (Lite)"
    override fun getDisplayName(): String = "Scala (Lite)"
    override fun getDescription(): String = "Scala source, script or sbt build file"
    override fun getDefaultExtension(): String = "scala"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/scala.svg", ScalaFileType::class.java) }
}

class ScalaFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ScalaLanguage) {
    override fun getFileType() = ScalaFileType
    override fun toString(): String = "Scala file"
}

class ScalaTokenType(debugName: String) : IElementType(debugName, ScalaLanguage)

object ScalaTokens {
    private fun t(name: String) = ScalaTokenType(name)
    val IDENT = t("IDENT")                   // plain or backquoted name
    val OP = t("OP")                         // operator name: `+`, `->`, `::`, `|`
    val SOFT_KEYWORD = t("SOFT_KEYWORD")     // a Scala 3 keyword the parser recognised at its place; the lexer says IDENT
    val NUMBER = t("NUMBER")
    val CHAR = t("CHAR")
    val SYMBOL = t("SYMBOL")                 // 'name
    val STRING = t("STRING")                 // "…" and """…""" without interpolation
    val INTERPOLATOR = t("INTERPOLATOR")     // the `s` of s"…"
    val ISTRING_BEGIN = t("ISTRING_BEGIN")
    val ISTRING_TEXT = t("ISTRING_TEXT")
    val ISTRING_END = t("ISTRING_END")
    val DOLLAR = t("$")                      // starts `$name` or `${expr}` inside an interpolated string
    val LINE_COMMENT = t("LINE_COMMENT")
    val BLOCK_COMMENT = t("BLOCK_COMMENT")
    val DOC_COMMENT = t("DOC_COMMENT")
    val SHEBANG = t("SHEBANG")
    val LPAREN = t("("); val RPAREN = t(")"); val LBRACE = t("{"); val RBRACE = t("}"); val LBRACKET = t("["); val RBRACKET = t("]")
    val DOT = t("."); val COMMA = t(","); val SEMI = t(";"); val UNDERSCORE = t("_"); val QUOTE = t("'")
    val COLON = t(":"); val ASSIGN = t("="); val ARROW = t("=>"); val LARROW = t("<-")
    val UPPER_BOUND = t("<:"); val LOWER_BOUND = t(">:"); val VIEW_BOUND = t("<%"); val HASH = t("#"); val AT = t("@")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    val KEYWORDS: Map<String, ScalaTokenType> = listOf(
        "abstract", "case", "catch", "class", "def", "do", "else", "extends", "false", "final", "finally", "for", "forSome", "if", "implicit",
        "import", "lazy", "macro", "match", "new", "null", "object", "override", "package", "private", "protected", "return", "sealed", "super",
        "this", "throw", "trait", "true", "try", "type", "val", "var", "while", "with", "yield",
    ).associateWith { t(it.uppercase()) }
    fun kw(text: String): ScalaTokenType = KEYWORDS.getValue(text)

    /** Scala 3 keywords. All are names in Scala 2 sources, so the parser reads their text where the grammar allows one. */
    val SOFT_KEYWORDS = setOf(
        "as", "derives", "end", "enum", "export", "extension", "given", "infix", "inline", "opaque", "open", "then", "transparent", "using",
    )

    private val RESERVED_OPS: Map<String, ScalaTokenType> = mapOf(
        ":" to COLON, "=" to ASSIGN, "=>" to ARROW, "⇒" to ARROW, "<-" to LARROW, "←" to LARROW,
        "<:" to UPPER_BOUND, ">:" to LOWER_BOUND, "<%" to VIEW_BOUND, "#" to HASH, "@" to AT,
    )
    internal fun operator(text: String): ScalaTokenType = RESERVED_OPS[text] ?: OP

    private val PUNCTUATION: Map<Char, ScalaTokenType> = mapOf(
        '(' to LPAREN, ')' to RPAREN, '{' to LBRACE, '}' to RBRACE, '[' to LBRACKET, ']' to RBRACKET, '.' to DOT, ',' to COMMA, ';' to SEMI,
    )
    internal fun punctuation(c: Char): ScalaTokenType? = PUNCTUATION[c]

    val KEYWORD_SET = TokenSet.create(*KEYWORDS.values.toTypedArray())
    val COMMENTS = TokenSet.create(BLOCK_COMMENT, DOC_COMMENT, LINE_COMMENT, SHEBANG)
    val STRINGS = TokenSet.create(CHAR, ISTRING_TEXT, STRING)
    val NAMES = TokenSet.create(IDENT, OP)
    val OPERATORS = TokenSet.create(ARROW, ASSIGN, AT, COLON, HASH, LARROW, LOWER_BOUND, OP, UPPER_BOUND, VIEW_BOUND)
}

private typealias T = ScalaTokens

/**
 * Scala lexer, after the lexical syntax of the language specification. A frame stack follows the nesting of
 * interpolated strings: the text of a string, a `$name` and a `${ … }` block, whose tokens are those of code.
 *
 * State: 0 = code outside any string (a restart point); 1 = anywhere inside an interpolated string.
 */
class ScalaLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null
    private var state = 0
    private val stack = ArrayList<Frame>()

    private enum class Mode { TEXT, NAME, CODE }

    /** [triple]: the string closes on `"""`; [opened]: its opening quote is read; [depth]: open braces of a `${ }` block. */
    private class Frame(val mode: Mode, val triple: Boolean = false, var opened: Boolean = false, var depth: Int = 0)

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        stack.clear()
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
        state = minOf(stack.size, 1)
        if (pos >= end) { type = null; return }
        type = next()
    }

    private fun at(i: Int) = when { i < end -> buf[i]; else -> '\u0000' }
    private fun matches(i: Int, s: String) = i + s.length <= end && s.indices.all { buf[i + it] == s[it] }
    private fun pop() { stack.removeAt(stack.size - 1) }

    private fun next(): IElementType {
        while (true) {
            val top = stack.lastOrNull()
            val token = when (top?.mode) {
                Mode.TEXT -> text(top)
                Mode.NAME -> interpolatedName()
                else -> code()
            }
            if (token != null) return token
        }
    }

    // ---------------------------------------------------------------- code

    private fun code(): IElementType {
        val c = buf[pos]
        return when {
            c.isWhitespace() -> { while (pos < end && buf[pos].isWhitespace()) pos++; T.WHITE_SPACE }
            pos == 0 && c == '#' && at(1) == '!' -> shebang()
            c == '/' && at(pos + 1) == '/' -> { skipLine(); T.LINE_COMMENT }
            c == '/' && at(pos + 1) == '*' -> blockComment()
            c == '"' -> string()
            c == '\'' -> quote()
            c == '`' -> backquoted()
            c.isDigit() || (c == '.' && at(pos + 1).isDigit()) -> { number(); T.NUMBER }
            isNameStart(c) -> word()
            isOpChar(c) -> operator()
            else -> punctuation(c)
        }
    }

    private fun skipLine() { while (pos < end && buf[pos] != '\n') pos++ }

    /** A script header runs to `!#` when there is one, else to the end of its line. */
    private fun shebang(): IElementType {
        val close = buf.indexOf("\n!#", pos).takeIf { it in 0 until end }
        pos = close?.plus(3) ?: pos
        skipLine()
        return T.SHEBANG
    }

    /** Block comments nest. */
    private fun blockComment(): IElementType {
        val doc = at(pos + 2) == '*' && at(pos + 3) != '/'
        pos += 2
        var depth = 1
        while (pos < end && depth > 0) {
            when {
                matches(pos, "/*") -> { depth++; pos += 2 }
                matches(pos, "*/") -> { depth--; pos += 2 }
                else -> pos++
            }
        }
        return when { doc -> T.DOC_COMMENT; else -> T.BLOCK_COMMENT }
    }

    private fun punctuation(c: Char): IElementType {
        pos++
        val token = T.punctuation(c) ?: return T.BAD_CHARACTER
        val braces = stack.lastOrNull() ?: return token
        if (token == T.LBRACE) braces.depth++
        if (token == T.RBRACE && --braces.depth <= 0) pop()
        return token
    }

    /** A run of operator characters; it stops before a comment. */
    private fun operator(): IElementType {
        skipOperator()
        return T.operator(buf.subSequence(start, pos).toString())
    }

    private fun skipOperator() { while (pos < end && isOpChar(buf[pos]) && !(buf[pos] == '/' && (at(pos + 1) == '/' || at(pos + 1) == '*'))) pos++ }

    /** `name`, `name_+` (an operator may follow an underscore), a keyword, `_` alone, or the prefix of an interpolated string. */
    private fun word(): IElementType {
        pos++
        while (pos < end && isNamePart(buf[pos])) pos++
        if (buf[pos - 1] == '_' && pos - start > 1) skipOperator()
        if (at(pos) == '"') { stack += Frame(Mode.TEXT, triple = matches(pos, TRIPLE)); return T.INTERPOLATOR }
        val text = buf.subSequence(start, pos).toString()
        if (text == "_") return T.UNDERSCORE
        return T.KEYWORDS[text] ?: T.IDENT
    }

    private fun backquoted(): IElementType {
        val close = (pos + 1 until end).firstOrNull { buf[it] == '`' || buf[it] == '\n' }
        if (close == null || buf[close] == '\n') { pos++; return T.BAD_CHARACTER }
        pos = close + 1
        return T.IDENT
    }

    private fun number() {
        val radix = at(pos + 1).lowercaseChar().takeIf { buf[pos] == '0' && (it == 'x' || it == 'b') }
        if (radix != null) pos += 2
        val digit: (Char) -> Boolean = { ch -> ch.isDigit() || ch == '_' || (radix == 'x' && ch.lowercaseChar() in 'a'..'f') }
        while (pos < end && digit(buf[pos])) pos++
        if (radix != null) { suffix("lL"); return }
        if (at(pos) == '.' && at(pos + 1).isDigit()) { pos++; while (pos < end && digit(buf[pos])) pos++ }
        val exponent = at(pos).lowercaseChar() == 'e' && (at(pos + 1).isDigit() || (at(pos + 1) in "+-" && at(pos + 2).isDigit()))
        if (exponent) { pos += 2; while (pos < end && buf[pos].isDigit()) pos++ }
        suffix("lLdDfF")
    }

    private fun suffix(letters: String) { if (at(pos) in letters && !isNamePart(at(pos + 1))) pos++ }

    /** `'a'`, `'\n'`, `'A'` are characters, `'name` is a symbol, a lone `'` opens a Scala 3 quote. */
    private fun quote(): IElementType {
        if (at(pos + 1) == '\\') { charEscape(); return T.CHAR }
        if (at(pos + 2) == '\'' && at(pos + 1) != '\n') { pos += 3; return T.CHAR }
        pos++
        if (isNameStart(at(pos))) { while (pos < end && isNamePart(buf[pos])) pos++; return T.SYMBOL }
        if (isOpChar(at(pos))) { skipOperator(); return T.SYMBOL }
        return T.QUOTE
    }

    private fun charEscape() {
        pos += 3
        while (pos < end && buf[pos] != '\'' && buf[pos] != '\n' && pos - start < MAX_CHAR_LENGTH) pos++
        if (at(pos) == '\'') pos++
    }

    // ---------------------------------------------------------------- strings

    /** A plain string is one token: `"…"` with escapes, or `"""…"""` where only the last three quotes close. */
    private fun string(): IElementType {
        if (matches(pos, TRIPLE)) { pos = tripleEnd(pos + 3); return T.STRING }
        pos++
        while (pos < end && buf[pos] != '"' && buf[pos] != '\n') pos += when { buf[pos] == '\\' -> 2; else -> 1 }
        pos = minOf(pos, end)
        if (at(pos) == '"') pos++
        return T.STRING
    }

    private fun tripleEnd(from: Int): Int {
        val close = buf.indexOf(TRIPLE, from).takeIf { it in 0..end - 3 } ?: return end
        return (close + 3..end).first { at(it) != '"' }
    }

    private fun interpolates(i: Int) = buf[i] == '$' && (at(i + 1) == '{' || isInterpolatedNameStart(at(i + 1)))
    private fun isInterpolatedNameStart(c: Char) = c.isLetter() || c == '_'

    /** Whether the string of [frame] closes at [i]: a `"""` followed by more quotes is still text. */
    private fun closes(frame: Frame, i: Int): Boolean {
        if (!frame.triple) return buf[i] == '"'
        return matches(i, TRIPLE) && at(i + 3) != '"'
    }

    private fun textEnd(frame: Frame, from: Int): Int {
        var i = from
        while (i < end) {
            if (closes(frame, i) || interpolates(i) || (!frame.triple && buf[i] == '\n')) return i
            i += when {
                buf[i] == '$' && at(i + 1) == '$' -> 2
                buf[i] == '\\' && !frame.triple -> 2
                else -> 1
            }
        }
        return end
    }

    private fun text(frame: Frame): IElementType? {
        if (!frame.opened) { frame.opened = true; pos += quoteLength(frame); return T.ISTRING_BEGIN }
        if (closes(frame, pos)) { pos += quoteLength(frame); pop(); return T.ISTRING_END }
        if (interpolates(pos)) {
            pos++
            stack += Frame(when { buf[pos] == '{' -> Mode.CODE; else -> Mode.NAME })
            return T.DOLLAR
        }
        if (!frame.triple && buf[pos] == '\n') { pop(); return null }
        pos = minOf(textEnd(frame, pos), end)
        return T.ISTRING_TEXT
    }

    private fun quoteLength(frame: Frame) = when { frame.triple -> 3; else -> 1 }

    /** `$name`: one name, never a keyword except `this`. */
    private fun interpolatedName(): IElementType {
        while (pos < end && (buf[pos].isLetterOrDigit() || buf[pos] == '_')) pos++
        pop()
        return T.kw("this").takeIf { buf.subSequence(start, pos).toString() == "this" } ?: T.IDENT
    }

    companion object {
        private const val TRIPLE = "\"\"\""
        private const val MAX_CHAR_LENGTH = 12
        private const val OP_CHARS = "!#%&*+-/:<=>?@\\^|~"

        fun isNameStart(c: Char) = c.isLetter() || c == '_' || c == '$'
        fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        fun isOpChar(c: Char) = c in OP_CHARS || (c.code > 127 && isSymbol(c))
        private fun isSymbol(c: Char) = Character.getType(c).let { it == Character.MATH_SYMBOL.toInt() || it == Character.OTHER_SYMBOL.toInt() }
    }
}
