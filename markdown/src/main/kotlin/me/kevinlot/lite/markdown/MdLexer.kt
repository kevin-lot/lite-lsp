package me.kevinlot.lite.markdown

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.markdown.MdTokens as T

/**
 * Line-oriented Markdown lexer: block markers where only indentation precedes them on the line (headers,
 * fences, quotes, list items, rules, link definitions), inline tokens elsewhere (code spans, links,
 * emphasis, autolinks, HTML). A fenced block's lines are single CODE_TEXT tokens.
 *
 * State: 0 = outside a fence and a link destination (a restart point), 1 otherwise.
 */
class MdLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null
    private var state = 0

    private var fenceChar = '\u0000'
    private var fenceLen = 0
    private var fenceInfoNext = false
    private var fenceQuoted = false     // the fence opened in a block quote: each of its lines starts with `>`
    private var fenceQuoteDone = false  // that `>` is read, the code of the line follows
    private var destNext = false     // after `](`: the destination
    private var inLinkParens = false // a `)` closes the link
    private var defLine = false      // `[label]: url` line: label, then `]`, `:`, destination
    private var defStage = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        fenceChar = '\u0000'; fenceLen = 0; fenceInfoNext = false; fenceQuoted = false; fenceQuoteDone = false; destNext = false; inLinkParens = false; defLine = false; pendingParen = false
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
            fenceLen == 0 && !destNext && !inLinkParens && !defLine && !pendingParen -> 0
            else -> 1
        }
        if (pos >= end) { type = null; return }
        type = next()
    }

    private fun ch(i: Int = 0): Char = when { pos + i < end -> buf[pos + i]; else -> '\u0000' }
    private fun atLineStart() = pos == 0 || buf[pos - 1] == '\n'
    /** Only blanks and quote markers between the line start and here: a block marker may follow. */
    private fun onlyIndentBefore(): Boolean {
        var i = pos - 1
        while (i >= 0 && buf[i] != '\n') { if (buf[i] !in INDENT) return false; i-- }
        return true
    }
    private fun lineStart(from: Int): Int { var i = from; while (i > 0 && buf[i - 1] != '\n') i--; return i }
    private fun lineEnd(from: Int = pos): Int { var i = from; while (i < end && buf[i] != '\n') i++; return i }
    private fun restOfLine() = buf.subSequence(pos, lineEnd())

    private fun next(): IElementType {
        if (fenceLen > 0) return fenced()
        if (atLineStart() && pos == 0 && restOfLine().toString() == "---") frontMatter()?.let { return it }
        val c = ch()
        if (c == '\n') { pos++; destNext = false; inLinkParens = false; defLine = false; pendingParen = false; return T.NEWLINE }
        if (c == ' ' || c == '\t' || c == '\r') { while (ch() == ' ' || ch() == '\t' || ch() == '\r') pos++; return T.WHITE_SPACE }
        if (defLine) definition()?.let { return it }
        if (onlyIndentBefore()) block()?.let { return it }
        return inline()
    }

    // ---------------------------------------------------------------- blocks

    private fun frontMatter(): IElementType? {
        var i = lineEnd() + 1
        while (i < end) {
            val e = lineEnd(i)
            val line = buf.subSequence(i, e).toString().trimEnd()
            if (line == "---" || line == "...") { pos = e; return T.FRONT_MATTER }
            i = e + 1
        }
        return null
    }

    private fun fenced(): IElementType {
        if (fenceInfoNext) {
            fenceInfoNext = false
            val e = lineEnd()
            if (buf.subSequence(pos, e).isNotBlank()) { pos = e; return T.FENCE_INFO }
        }
        if (ch() == '\n' && !atLineStart() && !fenceQuoteDone) { pos++; return T.NEWLINE }
        if (fenceQuoted && !fenceQuoteDone) return quotedFenceLine()
        fenceQuoteDone = false
        val e = lineEnd()
        // any indentation: fences inside list items are indented like their item
        var runStart = pos
        while (runStart < e && (buf[runStart] == ' ' || buf[runStart] == '\t')) runStart++
        var runEnd = runStart
        while (runEnd < e && buf[runEnd] == fenceChar) runEnd++
        if (runEnd - runStart >= fenceLen && buf.subSequence(runEnd, e).isBlank()) { pos = e; fenceLen = 0; return T.FENCE_END }
        pos = minOf(e + 1, end)
        return T.CODE_TEXT
    }

    /** The `>` of a line of a quoted fence; a line without it ends the quote, and the fence with it. */
    private fun quotedFenceLine(): IElementType {
        var i = pos
        while (i < end && (buf[i] == ' ' || buf[i] == '\t')) i++
        if (i >= end || buf[i] != '>') { fenceLen = 0; return next() }
        i++
        if (i < end && buf[i] == ' ') i++
        pos = i
        fenceQuoteDone = true
        return T.QUOTE_MARKER
    }

    private fun block(): IElementType? {
        val c = ch()
        val line = restOfLine().toString()
        if (c == '`' || c == '~') {
            val run = line.takeWhile { it == c }.length
            // a backquote in the info string: an inline code span, not a fence
            if (run >= 3 && !(c == '`' && line.indexOf('`', run) >= 0)) {
                fenceChar = c; fenceLen = run; fenceInfoNext = true
                fenceQuoted = buf.subSequence(lineStart(pos), pos).contains('>')
                pos += fenceLen
                return T.FENCE_START
            }
        }
        if (c == '#') {
            val n = line.takeWhile { it == '#' }.length
            if (n in 1..6 && (line.length == n || line[n] == ' ' || line[n] == '\t')) { pos += n; return T.HEADER_MARKER }
        }
        if (c == '>') { pos++; return T.QUOTE_MARKER }
        if ((c == '=' || c == '-') && line.trimEnd().all { it == c } && paragraphAbove()) { pos += line.length; return T.SETEXT_MARKER }
        if ((c == '-' || c == '*' || c == '_') && isRule(line, c)) { pos += line.length; return T.HR }
        if ((c == '-' || c == '*' || c == '+') && (ch(1) == ' ' || ch(1) == '\t')) { pos++; return T.LIST_MARKER }
        if (c.isDigit()) {
            val m = ORDERED.find(line)
            if (m != null) { pos += m.value.length; return T.LIST_MARKER }
        }
        if (c == '[' && (line.startsWith("[ ]") || line.startsWith("[x]") || line.startsWith("[X]")) && isListItemLine()) { pos += 3; return T.CHECKBOX }
        if (c == '[' && DEFINITION.containsMatchIn(line)) { defLine = true; defStage = 0; pos++; return T.LBRACKET }
        return null
    }

    /** `---`, `* * *`, `___`: three or more of the same marker, spaces allowed around and between. */
    private fun isRule(line: String, marker: Char) = line.all { it == marker || it == ' ' } && line.count { it == marker } >= 3

    /** The line above is paragraph text, read from the buffer alone so that a restart here decides the same. */
    private fun paragraphAbove(): Boolean {
        val start = lineStart(pos)
        if (start == 0) return false
        val above = buf.subSequence(lineStart(start - 1), start - 1).trim().toString()
        return above.isNotEmpty() && !NOT_PARAGRAPH.containsMatchIn(above) && !above.endsWith("-->") &&
            !above.all { it == '=' } && RULE_MARKERS.none { isRule(above, it) }
    }

    private fun isListItemLine(): Boolean {
        var i = pos - 1
        while (i >= 0 && (buf[i] == ' ' || buf[i] == '\t')) i--
        return i >= 0 && buf[i] in "-*+.)"
    }

    /** `[label]: destination "title"`. */
    private fun definition(): IElementType? {
        when (defStage) {
            0 -> { defStage = 1; while (pos < end && ch() != ']' && ch() != '\n') pos++; return T.LINK_LABEL }
            1 -> { defStage = 2; pos++; return T.RBRACKET }
            2 -> { defStage = 3; pos++; return T.DEF_COLON }
            3 -> { defStage = 4; destination(); return T.LINK_DEST }
        }
        defLine = false
        return null
    }

    private fun destination() {
        if (ch() == '<') { while (pos < end && ch() != '>' && ch() != '\n') pos++; if (ch() == '>') pos++; return }
        var depth = 0
        while (pos < end) {
            val c = ch()
            if (c.isWhitespace()) break
            if (c == '(') depth++
            if (c == ')') { if (depth == 0) break; depth-- }
            if (c == '\\' && pos + 1 < end) pos++
            pos++
        }
    }

    // ---------------------------------------------------------------- inline

    private fun inline(): IElementType {
        val c = ch()
        if (destNext) { destNext = false; val s = pos; destination(); if (pos > s) return T.LINK_DEST }
        when (c) {
            '\\' -> {
                pos += when { ch(1) == '\n' || ch(1) == '\u0000' -> 1; else -> 2 }
                return T.TEXT
            }
            '`' -> return codeSpan()
            '!' -> if (ch(1) == '[') { pos += 2; return T.IMAGE_START }
            '[' -> { pos++; return T.LBRACKET }
            ']' -> { pos++; pendingParen = ch() == '('; return T.RBRACKET }
            '(' -> if (pendingParen) { pendingParen = false; pos++; destNext = true; inLinkParens = true; return T.LPAREN }
            ')' -> if (inLinkParens) { inLinkParens = false; pos++; return T.RPAREN }
            '<' -> angle()?.let { return it }
            '*', '_', '~' -> emphasis()?.let { return it }
            '|' -> { pos++; return T.PIPE }
            'h' -> if (startsUrl()) { while (pos < end && !ch().isWhitespace() && ch() !in "<>)]\"'") pos++; trimUrl(); return T.URL }
        }
        pos++
        while (pos < end && ch() !in SPECIAL && !(ch() == 'h' && startsUrl())) pos++
        return T.TEXT
    }

    private var pendingParen = false

    private fun startsUrl() = (pos == 0 || !buf[pos - 1].isLetterOrDigit()) &&
        (buf.startsWith("https://", pos) || buf.startsWith("http://", pos))

    /** A trailing `.`/`,` ends the sentence, not the URL. */
    private fun trimUrl() { while (pos > start && buf[pos - 1] in ".,;:") pos-- }

    private fun codeSpan(): IElementType {
        val s = pos
        var n = 0
        while (ch() == '`') { n++; pos++ }
        val e = lineEnd()
        var i = pos
        while (i < e) {
            if (buf[i] != '`') { i++; continue }
            var m = 0
            while (i + m < e && buf[i + m] == '`') m++
            if (m == n) { pos = i + m; return T.CODE_SPAN }
            i += m
        }
        pos = s + n
        return T.TEXT
    }

    private fun angle(): IElementType? {
        val rest = restOfLine()
        if (rest.startsWith("<!--")) {
            val close = buf.indexOf("-->", pos)
            pos = when { close < 0 -> end; else -> close + 3 }
            return T.HTML_COMMENT
        }
        AUTOLINK.find(rest)?.let { pos += it.value.length; return T.AUTOLINK }
        HTML_TAG.find(rest)?.let { pos += it.value.length; return T.HTML }
        return null
    }

    private fun emphasis(): IElementType? {
        val c = ch()
        if (c == '~') {
            if (ch(1) != '~') return null
            pos += 2
            return T.STRIKE
        }
        // snake_case and 2*3 are not emphasis
        val before = when { pos > 0 -> buf[pos - 1]; else -> ' ' }
        val doubled = ch(1) == c
        val length = when { doubled -> 2; else -> 1 }
        val after = ch(length)
        if (c == '_' && before.isLetterOrDigit() && after.isLetterOrDigit()) return null
        if (before.isWhitespace() && after.isWhitespace()) return null
        pos += length
        if (doubled) return T.STRONG
        return T.EMPH
    }

    private companion object {
        const val INDENT = " \t>"
        const val RULE_MARKERS = "-*_"
        const val SPECIAL = "\n \t\r\\`![]()<*_~|"
        val NOT_PARAGRAPH = Regex("^(#{1,6}([ \\t]|$)|[>|<]|```|~~~|[-*+][ \\t]|\\d{1,9}[.)][ \\t]|\\[[^\\]]+\\]:)")
        val ORDERED = Regex("^\\d{1,9}[.)](?=[ \\t])")
        val DEFINITION = Regex("^\\[[^\\]]+\\]:[ \\t]*\\S")
        val AUTOLINK = Regex("^<([a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^<>\\s]*|[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+)>")
        val HTML_TAG = Regex("^</?[a-zA-Z][a-zA-Z0-9-]*(\\s[^<>]*)?/?>")
    }
}
