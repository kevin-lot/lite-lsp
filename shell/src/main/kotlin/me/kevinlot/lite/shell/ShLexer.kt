package me.kevinlot.lite.shell

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.shell.ShTokens as T

/**
 * Shell lexer. The shell is context-sensitive; a frame stack follows the nesting the grammar needs:
 * `$( … )`, backquotes, `"…"`, `${ … }`, `$(( … ))` and here-documents (whose body starts at the next
 * line and ends at the delimiter line; unquoted delimiters keep `$x` and `$( … )` expansions in the body).
 *
 * Reserved words, `{`, `}`, `[[` and `]]` are keywords only as whole words; the parser treats them as plain
 * words where a command is not expected (`echo done`).
 *
 * State: 0 = top level (a restart point), 1 = inside a nesting or before a pending here-document body.
 */
class ShLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null
    private var state = 0

    private class Frame(val kind: Int, var depth: Int = 0, val delim: String = "", val expand: Boolean = true, val stripTabs: Boolean = false) {
        var nameNext = true
    }

    private val stack = ArrayList<Frame>()
    private val pendingHeredocs = ArrayList<Frame>()
    private var heredocOp: Boolean? = null // after `<<` (true: `<<-`): the next word is a delimiter

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        stack.clear(); pendingHeredocs.clear(); heredocOp = null
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
        state = if (stack.isEmpty() && pendingHeredocs.isEmpty()) 0 else 1
        if (pos >= end) { type = null; return }
        type = when (stack.lastOrNull()?.kind) {
            DQUOTE -> dquote()
            PARAM -> param()
            ARITH -> arith()
            HEREDOC -> heredoc()
            else -> normal()
        }
    }

    private fun ch(i: Int = 0): Char = if (pos + i < end) buf[pos + i] else '\u0000'
    private fun prev(): Char = if (pos > 0) buf[pos - 1] else '\n'
    private fun isBlank(c: Char) = c == ' ' || c == '\t'
    private fun isDelimiter(c: Char) = c == '\u0000' || c.isWhitespace() || c in ";&|()<>`"
    private fun atWordStart() = pos == 0 || isDelimiter(prev())
    private fun isNameStart(c: Char) = c.isLetter() || c == '_'
    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

    // ---------------------------------------------------------------- commands

    private fun normal(): IElementType {
        val c = ch()
        if (isBlank(c) || (c == '\\' && ch(1) == '\n')) {
            while (pos < end && (isBlank(ch()) || (ch() == '\\' && ch(1) == '\n'))) pos += if (ch() == '\\') 2 else 1
            return T.WHITE_SPACE
        }
        heredocOp?.let { strip -> heredocOp = null; return heredocDelimiter(strip) }
        if (c == '\n' || c == '\r') {
            pos++
            if (c == '\r' && ch() == '\n') pos++
            // pending here-documents start on the next line, first one first
            for (h in pendingHeredocs.asReversed()) stack += h
            pendingHeredocs.clear()
            return T.NEWLINE
        }
        if (c == '#' && atWordStart()) { while (pos < end && ch() != '\n') pos++; return T.COMMENT }
        when (c) {
            '\'' -> return singleQuoted(1)
            '"' -> { pos++; stack += Frame(DQUOTE); return T.DQUOTE_START }
            '`' -> {
                pos++
                if (stack.lastOrNull()?.kind == BACKTICK) { stack.removeAt(stack.size - 1); return T.BACKTICK_END }
                stack += Frame(BACKTICK); return T.BACKTICK_START
            }
            '$' -> return dollar()
            ';' -> return when {
                ch(1) == ';' -> { pos += if (ch(2) == '&') 3 else 2; T.CASE_END }
                ch(1) == '&' -> { pos += 2; T.CASE_END }
                else -> { pos++; T.SEMI }
            }
            '&' -> return when {
                ch(1) == '&' -> { pos += 2; T.AND_AND }
                ch(1) == '>' -> { pos += if (ch(2) == '>') 3 else 2; T.REDIRECT }
                else -> { pos++; T.AMP }
            }
            '|' -> return when {
                ch(1) == '|' -> { pos += 2; T.OR_OR }
                ch(1) == '&' -> { pos += 2; T.PIPE }
                else -> { pos++; T.PIPE }
            }
            '(' -> {
                if (ch(1) == '(' && atWordStart()) { pos += 2; stack += Frame(ARITH); return T.ARITH_START }
                pos++
                stack.lastOrNull()?.takeIf { it.kind == SUBST }?.let { it.depth++ }
                return T.LPAR
            }
            ')' -> {
                pos++
                val top = stack.lastOrNull()
                if (top != null && top.kind == SUBST) {
                    if (top.depth == 0) { stack.removeAt(stack.size - 1); return T.SUBST_END }
                    top.depth--
                }
                return T.RPAR
            }
            '<', '>' -> return redirect()
        }
        if (c.isDigit() && atWordStart()) {
            var i = 0
            while (ch(i).isDigit()) i++
            if (ch(i) == '<' || ch(i) == '>') { pos += i; return redirect() }
        }
        if (atWordStart()) wholeWord()?.let { return it }
        return word()
    }

    /** `{`, `}`, `[[`, `]]`, reserved words and `NAME=` at the start of a word. */
    private fun wholeWord(): IElementType? {
        val c = ch()
        if ((c == '{' || c == '}') && isDelimiter(ch(1))) { pos++; return if (c == '{') T.LBRACE else T.RBRACE }
        if (c == '[' && ch(1) == '[' && isDelimiter(ch(2))) { pos += 2; return T.TEST_START }
        if (c == ']' && ch(1) == ']' && isDelimiter(ch(2))) { pos += 2; return T.TEST_END }
        if (isNameStart(c)) {
            var i = 0
            while (isNameChar(ch(i))) i++
            val name = buf.subSequence(pos, pos + i).toString()
            if (isDelimiter(ch(i))) T.KEYWORDS[name]?.let { pos += i; return it }
            var j = i
            if (ch(j) == '[') { while (j < end - pos && ch(j) != ']' && ch(j) != '\n') j++; if (ch(j) == ']') j++ else return null }
            if (ch(j) == '+' && ch(j + 1) == '=') j++
            if (ch(j) == '=') { pos += j + 1; return T.ASSIGN_NAME }
        }
        return null
    }

    private fun word(): IElementType {
        val s = pos
        while (pos < end) {
            val c = ch()
            if (isDelimiter(c) || c == '\'' || c == '"' || c == '$') break
            if (c == '\\') { pos += 2; continue }
            pos++
        }
        if (pos > end) pos = end
        if (pos == s) { pos++; return T.WORD }
        val text = buf.subSequence(s, pos)
        return if (text.all { it.isDigit() }) T.NUMBER else T.WORD
    }

    private fun redirect(): IElementType {
        val c = ch()
        if (c == '<' && ch(1) == '<' && ch(2) != '<') {
            val strip = ch(2) == '-'
            pos += if (strip) 3 else 2
            heredocOp = strip
            return T.HEREDOC_OP
        }
        if ((c == '<' || c == '>') && ch(1) == '(') { pos += 2; stack += Frame(SUBST); return T.SUBST_START } // process substitution
        pos++
        while (ch() == '<' || ch() == '>' || ch() == '&' || ch() == '|') pos++
        return T.REDIRECT
    }

    /** `EOF`, `'EOF'`, `"EOF"`, `\EOF` after `<<`: quoted delimiters disable expansions in the body. */
    private fun heredocDelimiter(stripTabs: Boolean): IElementType {
        val s = pos
        var quoted = false
        val text = StringBuilder()
        while (pos < end && !isDelimiter(ch())) {
            val c = ch()
            when (c) {
                '\'', '"' -> { quoted = true; pos++; while (pos < end && ch() != c) { text.append(ch()); pos++ }; if (pos < end) pos++ }
                '\\' -> { quoted = true; pos++; if (pos < end) { text.append(ch()); pos++ } }
                else -> { text.append(c); pos++ }
            }
        }
        if (pos == s) return normal()
        pendingHeredocs += Frame(HEREDOC, delim = text.toString(), expand = !quoted, stripTabs = stripTabs)
        return T.HEREDOC_DELIM
    }

    private fun singleQuoted(skip: Int): IElementType {
        pos += skip
        while (pos < end && ch() != '\'') { if (skip == 2 && ch() == '\\') pos++; pos++ }
        if (pos < end) pos++
        if (pos > end) pos = end
        return T.STRING_SINGLE
    }

    // ---------------------------------------------------------------- expansions

    /** `$name`, `$1`, `$@`, `${…}`, `$(…)`, `$((…))`, `$'…'`, `$"…"`. */
    private fun dollar(): IElementType {
        val c = ch(1)
        return when {
            c == '(' && ch(2) == '(' -> { pos += 3; stack += Frame(ARITH); T.ARITH_START }
            c == '(' -> { pos += 2; stack += Frame(SUBST); T.SUBST_START }
            c == '{' -> { pos += 2; stack += Frame(PARAM); T.PARAM_START }
            c == '\'' -> singleQuoted(2)
            c == '"' -> { pos += 2; stack += Frame(DQUOTE); T.DQUOTE_START }
            isNameStart(c) -> { pos += 2; while (isNameChar(ch())) pos++; T.VAR }
            c.isDigit() || c in "@*#?-$!" -> { pos += 2; T.VAR }
            else -> { pos++; T.WORD }
        }
    }

    private fun dquote(): IElementType {
        when (ch()) {
            '"' -> { pos++; stack.removeAt(stack.size - 1); return T.DQUOTE_END }
            '$' -> if (ch(1) != '"' && ch(1) != '\'' && !isBlank(ch(1)) && ch(1) != '\n' && ch(1) != '\u0000') return dollar()
            '`' -> { pos++; stack += Frame(BACKTICK); return T.BACKTICK_START }
        }
        val s = pos
        while (pos < end) {
            val c = ch()
            if (c == '"' || c == '`' || (c == '$' && pos > s && ch(1) != '"')) break
            if (c == '$' && pos == s) { pos++; continue }
            if (c == '\\') { pos += 2; continue }
            pos++
        }
        if (pos > end) pos = end
        return T.STRING_TEXT
    }

    /** `${#name}`, `${name:-default}`, `${name//a/b}`, `${array[@]}`, zsh `${(f)name}`. */
    private fun param(): IElementType {
        val frame = stack.last()
        val c = ch()
        if (frame.nameNext) {
            if (c == '(' ) { while (pos < end && ch() != ')') pos++; if (pos < end) pos++; return T.PARAM_OP } // zsh flags
            if (c == '#' || c == '!' || c == '=' || c == '^' || c == '~') {
                if (ch(1) != '}') { pos++; return T.PARAM_OP }
            }
            frame.nameNext = false
            if (isNameStart(c)) { while (isNameChar(ch())) pos++; return T.VAR_NAME }
            if (c.isDigit()) { while (ch().isDigit()) pos++; return T.VAR_NAME }
            if (c in "@*#?-$!") { pos++; return T.VAR_NAME }
        }
        when (c) {
            '}' -> {
                pos++
                if (frame.depth == 0) { stack.removeAt(stack.size - 1); return T.PARAM_END }
                frame.depth--; return T.PARAM_TEXT
            }
            '{' -> { pos++; frame.depth++; return T.PARAM_TEXT }
            '\'' -> return singleQuoted(1)
            '"' -> { pos++; stack += Frame(DQUOTE); return T.DQUOTE_START }
            '$' -> return dollar()
            '`' -> { pos++; stack += Frame(BACKTICK); return T.BACKTICK_START }
        }
        while (pos < end && ch() !in "}{'\"$`") { if (ch() == '\\') pos++; pos++ }
        if (pos > end) pos = end
        return T.PARAM_TEXT
    }

    /** `$(( i + 1 ))`, `(( n > 0 ))`: names are variables. */
    private fun arith(): IElementType {
        val frame = stack.last()
        val c = ch()
        if (c.isWhitespace()) { while (pos < end && ch().isWhitespace()) pos++; return T.WHITE_SPACE }
        if (isNameStart(c)) { while (isNameChar(ch())) pos++; return T.VAR_NAME }
        if (c.isDigit()) { while (ch().isLetterOrDigit() || ch() == '#') pos++; return T.NUMBER }
        when (c) {
            '$' -> return dollar()
            '(' -> { pos++; frame.depth++; return T.ARITH_OP }
            ')' -> {
                if (frame.depth == 0 && ch(1) == ')') { pos += 2; stack.removeAt(stack.size - 1); return T.ARITH_END }
                pos++; if (frame.depth > 0) frame.depth--; return T.ARITH_OP
            }
            '"' -> { pos++; stack += Frame(DQUOTE); return T.DQUOTE_START }
            '\'' -> return singleQuoted(1)
        }
        pos++
        return T.ARITH_OP
    }

    /** Body lines up to the delimiter line (leading tabs ignored with `<<-`). */
    private fun heredoc(): IElementType {
        val frame = stack.last()
        if (pos == 0 || buf[pos - 1] == '\n') {
            var lineEnd = pos
            while (lineEnd < end && buf[lineEnd] != '\n') lineEnd++
            var line = buf.subSequence(pos, lineEnd).toString().removeSuffix("\r")
            if (frame.stripTabs) line = line.trimStart('\t')
            if (line == frame.delim) { pos = lineEnd; stack.removeAt(stack.size - 1); return T.HEREDOC_END }
        }
        if (frame.expand) {
            if (ch() == '$' && (isNameStart(ch(1)) || ch(1) == '{' || ch(1) == '(' || ch(1).isDigit())) return dollar()
            if (ch() == '`') { pos++; stack += Frame(BACKTICK); return T.BACKTICK_START }
        }
        while (pos < end) {
            val c = ch()
            pos++
            if (c == '\n') break
            // `\$`, `\`` are literal in an expanding body
            if (c == '\\' && frame.expand && pos < end && ch() != '\n') { pos++; continue }
            if (frame.expand && (ch() == '$' || ch() == '`')) break
        }
        if (pos > end) pos = end
        return T.HEREDOC_TEXT
    }

    private companion object {
        const val SUBST = 1
        const val BACKTICK = 2
        const val DQUOTE = 3
        const val PARAM = 4
        const val ARITH = 5
        const val HEREDOC = 6
    }
}
