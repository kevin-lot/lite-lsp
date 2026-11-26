package me.kevinlot.lite.formats

import com.intellij.psi.tree.IElementType

private val HEREDOC = Regex("""<<-?([A-Za-z_]\w*)[ \t]*\n""")

fun Char.isHclIdentifierPart(): Boolean = this == '_' || this == '-' || isLetterOrDigit()

fun hclNonCodeRanges(text: CharSequence): List<IntRange> =
    buildList { scanCode(at = 0, depth = 0, inTemplate = false, out = this, text = text) }

fun hclCodeMask(text: CharSequence): BooleanArray {
    val mask = BooleanArray(text.length) { true }
    hclNonCodeRanges(text).forEach { range -> range.forEach { mask[it] = false } }
    return mask
}

// After the closing quote of the string opened at `at`, or where its line ends when it has none.
fun hclStringEnd(at: Int, text: CharSequence): Int = templateEnd(at = at + 1, chunkStart = at, limit = null, out = mutableListOf(), text = text)

// A string is one token up to its own closing quote: a quote inside its `${…}` or `%{…}` does not end it.
class HclLexer(format: Format) : FmtLexer(format) {
    override fun next(): IElementType {
        if (buf[pos] != '"') return super.next()
        pos = minOf(end, hclStringEnd(at = pos, text = buf))
        return token(Cat.STRING)
    }
}

private tailrec fun scanCode(at: Int, depth: Int, inTemplate: Boolean, out: MutableList<IntRange>, text: CharSequence): Int {
    if (at >= text.length) return text.length
    val char = text[at]
    if (inTemplate && char == '}' && depth == 0) return at
    if (inTemplate && char == '{') return scanCode(at = at + 1, depth = depth + 1, inTemplate = true, out = out, text = text)
    if (inTemplate && char == '}') return scanCode(at = at + 1, depth = depth - 1, inTemplate = true, out = out, text = text)
    if (char == '"') {
        val end = templateEnd(at = at + 1, chunkStart = at, limit = null, out = out, text = text)
        return scanCode(at = end, depth = depth, inTemplate = inTemplate, out = out, text = text)
    }
    val heredoc = heredocBody(at, text)
    if (heredoc != null) {
        val bodyStart = text.indexOf('\n', at) + 1
        templateEnd(at = bodyStart, chunkStart = bodyStart, limit = heredoc.last + 1, out = out, text = text)
        return scanCode(at = heredoc.last + 1, depth = depth, inTemplate = inTemplate, out = out, text = text)
    }
    val end = commentEnd(at, text) ?: return scanCode(at = at + 1, depth = depth, inTemplate = inTemplate, out = out, text = text)
    out.add(at until end)
    return scanCode(at = end, depth = depth, inTemplate = inTemplate, out = out, text = text)
}

// The text is non-code, each `${expr}` and `%{directive}` in it is code. A here-document ends at `limit`, a string at its quote.
private tailrec fun templateEnd(at: Int, chunkStart: Int, limit: Int?, out: MutableList<IntRange>, text: CharSequence): Int {
    val stop = limit ?: text.length
    if (at >= stop) {
        out.add(chunkStart until stop)
        return stop
    }
    val char = text[at]
    if (limit == null && char == '\\') return templateEnd(at = at + 2, chunkStart = chunkStart, limit = null, out = out, text = text)
    if (limit == null && char == '\n') {
        out.add(chunkStart until at)
        return at
    }
    if (limit == null && char == '"') {
        out.add(chunkStart..at)
        return at + 1
    }
    val escaped = text.startsWith("\$\${", at) || text.startsWith("%%{", at)
    if (escaped) return templateEnd(at = at + 3, chunkStart = chunkStart, limit = limit, out = out, text = text)
    if (!text.startsWith("\${", at) && !text.startsWith("%{", at)) {
        return templateEnd(at = at + 1, chunkStart = chunkStart, limit = limit, out = out, text = text)
    }
    out.add(chunkStart until at + 2)
    val close = scanCode(at = at + 2, depth = 0, inTemplate = true, out = out, text = text)
    return templateEnd(at = close + 1, chunkStart = close, limit = limit, out = out, text = text)
}

private fun commentEnd(at: Int, text: CharSequence): Int? = when {
    text[at] == '#' || text.startsWith("//", at) -> text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length
    text.startsWith("/*", at) -> text.indexOf("*/", at + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
    else -> null
}

// From `<<EOF` to the end of the line holding the terminator alone.
private fun heredocBody(at: Int, text: CharSequence): IntRange? {
    if (text[at] != '<') return null
    val opening = HEREDOC.matchAt(text, at) ?: return null
    val delimiter = opening.groupValues[1]
    val terminator = generateSequence(opening.range.last + 1) { lineEnd(it, text) + 1 }
        .takeWhile { it < text.length }
        .firstOrNull { text.subSequence(it, lineEnd(it, text)).trim() == delimiter }
    return at until (terminator?.let { lineEnd(it, text) } ?: text.length)
}

internal fun lineEnd(at: Int, text: CharSequence): Int = text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length
