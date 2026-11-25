package me.kevinlot.lite.formats

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** CSV / TSV: each column in its own color; the delimiter is guessed from the first line. */
object Csv : Format("Csv", "CSV", "csv") {
    override val rules = emptyList<Rule>()
    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        private var delimiter = ','
        private var column = 0
        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
            val first = buffer.subSequence(0, lineEnd(0, buffer))
            delimiter = listOf('\t', ';', ',', '|').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.contains(d) } ?: ','
            column = 0
            var i = startOffset - 1
            while (i >= 0 && buffer[i] != '\n') { if (buffer[i] == delimiter) column++; i-- }
            super.start(buffer, startOffset, endOffset, initialState)
        }
        override fun next(): IElementType {
            val c = buf[pos]
            if (c == '\n' || c == '\r') { while (pos < end && (buf[pos] == '\n' || buf[pos] == '\r')) pos++; column = 0; return TokenType.WHITE_SPACE }
            if (c == delimiter) { pos++; column++; return token(Cat.PUNCT) }
            val quote = (pos until end).firstOrNull { !isBlank(buf[it]) }?.takeIf { buf[it] == '"' }
            if (quote != null) { pos = quote; skipQuoted() }
            while (pos < end && buf[pos] != delimiter && buf[pos] != '\n' && buf[pos] != '\r') pos++
            return token(COLUMNS[column % COLUMNS.size])
        }

        private fun isBlank(c: Char) = c != delimiter && (c == ' ' || c == '\t')

        /** To the closing quote: a doubled one (`""`) is a quote of the value. */
        private fun skipQuoted() {
            pos++
            while (pos < end) {
                val quote = buf[pos] == '"'
                val doubled = quote && pos + 1 < end && buf[pos + 1] == '"'
                pos += 2.takeIf { doubled } ?: 1
                if (quote && !doubled) return
            }
        }
    }
    private val COLUMNS = listOf(Cat.COL0, Cat.COL1, Cat.COL2, Cat.COL3, Cat.COL4, Cat.COL5)
}
