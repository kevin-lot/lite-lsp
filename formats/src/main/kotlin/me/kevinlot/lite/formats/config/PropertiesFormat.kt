package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/** `.properties`, as `java.util.Properties` reads it: `${key}` → `key=` in the file or in a `.properties` file beside it. */
object Properties : Format("Properties", "Properties", "properties") {
    private val PLACEHOLDER = Regex("""\$\{([^}:\s]+)""")
    override val lineComment = "#"
    override val rules = emptyList<Rule>()
    override fun lexer(): FmtLexer = PropertiesLexer(this)

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val entries = PropertyLine.all(text).filter { !it.isComment }.toList()
            val values = Offsets(entries.asSequence().filter { it.valueStart < it.end }.map { it.valueStart until it.end })
            return Links(
                decls = entries.filter { it.start < it.keyEnd }.map { Decl(TextRange(it.start, it.keyEnd), "prop:${text.subSequence(it.start, it.keyEnd)}") },
                refs = PLACEHOLDER.ranges(text).filter { (r, _) -> r.startOffset in values }.map { (r, n) -> Ref(r, listOf("prop:$n")) }.toList(),
            )
        }

        /** Spring profiles (`application-dev.properties`) share their keys with the files beside them. */
        override fun scope(file: PsiFile): List<PsiFile> =
            listOf(file) + file.originalFile.containingDirectory?.files.orEmpty().filter { it != file && (it as? FmtFile)?.format == Properties }
    }
}

/** A logical line: `key`, a separator (`=`, `:` or blanks), then a value that a trailing `\` continues on the next line. */
internal class PropertyLine(val start: Int, val keyEnd: Int, val valueStart: Int, val end: Int, val isComment: Boolean) {
    companion object {
        private const val BLANKS = " \t\u000C"
        private const val SEPARATORS = "=:"

        fun all(text: CharSequence): Sequence<PropertyLine> =
            generateSequence(parse(text, 0, text.length)) { line -> (line.end + 1).takeIf { it < text.length }?.let { parse(text, it, text.length) } }

        /** The logical line holding [offset], which is not in a line break. */
        fun at(text: CharSequence, offset: Int, limit: Int): PropertyLine {
            val first = generateSequence(lineStart(text, offset)) { lineStart(text, it - 1) }.first { !continuesPrevious(text, it) }
            return generateSequence(parse(text, first, limit)) { parse(text, it.end + 1, limit) }.first { it.end >= offset }
        }

        private fun parse(text: CharSequence, from: Int, limit: Int): PropertyLine {
            val start = skipBlanks(text, from, limit)
            if (start < limit && text[start] in "#!") {
                val end = minOf(lineEnd(start, text), limit)
                return PropertyLine(start, start, end, end, isComment = true)
            }
            val keyEnd = scan(text, start, limit, stops = BLANKS + SEPARATORS)
            val separator = skipBlanks(text, keyEnd, limit)
            val valueStart = separator.takeIf { it >= limit || text[it] !in SEPARATORS } ?: skipBlanks(text, separator + 1, limit)
            return PropertyLine(start, keyEnd, valueStart, scan(text, valueStart, limit, stops = ""), isComment = false)
        }

        /** The first offset holding a line break or one of [stops]: a backslash escapes the character after it. */
        private tailrec fun scan(text: CharSequence, at: Int, limit: Int, stops: String): Int {
            if (at >= limit || text[at] == '\n' || text[at] in stops) return minOf(at, limit)
            if (text[at] == '\\') return scan(text, at + 2, limit, stops)
            return scan(text, at + 1, limit, stops)
        }

        private fun skipBlanks(text: CharSequence, from: Int, limit: Int): Int =
            (from until limit).firstOrNull { text[it] !in BLANKS } ?: maxOf(from, limit)

        private fun lineStart(text: CharSequence, offset: Int): Int = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1

        private fun continuesPrevious(text: CharSequence, lineStart: Int): Boolean {
            val breakAt = lineStart - 1
            if (breakAt < 1) return false
            val backslashes = (breakAt - 1 downTo 0).takeWhile { text[it] == '\\' }.count()
            return backslashes % 2 == 1
        }
    }
}

/** The role of a token comes from its place in the logical line, found again from the text at any offset. */
class PropertiesLexer(format: Format) : FmtLexer(format) {
    private var line: PropertyLine? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        line = null
        super.start(buffer, startOffset, endOffset, initialState)
    }

    override fun next(): IElementType {
        if (buf[pos].isWhitespace()) return super.next()
        val current = line?.takeIf { pos in it.start..it.end } ?: PropertyLine.at(buf, pos, end).also { line = it }
        if (current.isComment) return token(Cat.COMMENT).also { pos = current.end }
        if (pos < current.keyEnd) return token(Cat.KEY).also { pos = current.keyEnd }
        if (pos < current.valueStart) return token(Cat.OPERATOR).also { pos++ }
        return value(current.end)
    }

    private fun value(lineEnd: Int): IElementType {
        if (buf[pos] == '\\') return token(Cat.CONSTANT).also { pos = escapeEnd(lineEnd) }
        val close = placeholderEnd(lineEnd)
        if (close != null) return token(Cat.VARIABLE).also { pos = close }
        pos = (pos + 1 until lineEnd).firstOrNull { buf[it].isWhitespace() || buf[it] == '\\' || isPlaceholder(it) } ?: lineEnd
        return token(Cat.STRING)
    }

    private fun escapeEnd(lineEnd: Int): Int {
        val next = pos + 1
        if (next >= lineEnd || buf[next] == '\n') return next
        if (buf[next] == 'u') return minOf(next + 5, lineEnd)
        return next + 1
    }

    private fun isPlaceholder(at: Int): Boolean = buf[at] == '$' && at + 1 < end && buf[at + 1] == '{'

    private fun placeholderEnd(lineEnd: Int): Int? {
        if (!isPlaceholder(pos)) return null
        return (pos + 2 until lineEnd).firstOrNull { buf[it] == '}' || buf[it] == '\n' }?.takeIf { buf[it] == '}' }?.plus(1)
    }
}
