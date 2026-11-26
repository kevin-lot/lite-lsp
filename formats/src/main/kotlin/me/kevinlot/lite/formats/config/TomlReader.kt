package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/** A key of a path (`a."b.c"` has two), without its quotes. */
internal data class TomlKey(val name: String, val range: TextRange)

internal sealed class TomlValue {
    class Array(val items: List<TomlValue>) : TomlValue()
    class Scalar(val text: String) : TomlValue()
    class Text(val range: TextRange, val text: String) : TomlValue()
    class Table(val entries: List<Pair<List<TomlKey>, TomlValue>>) : TomlValue()
}

/** A value with its whole path: table header, dotted key and inline tables together. */
internal class TomlEntry(val path: List<TomlKey>, val value: TomlValue) {
    val names: List<String> = path.map { it.name }
}

/** Reads the tables and keys of a TOML text; what it cannot read is skipped up to the end of the line. */
internal class TomlReader(private val text: CharSequence) {
    private var at = 0

    fun entries(): List<TomlEntry> {
        val out = mutableListOf<TomlEntry>()
        var table = emptyList<TomlKey>()
        while (true) {
            blank()
            if (at >= text.length) return out
            val start = at
            when {
                text[at] == '[' -> table = header()
                else -> entry()?.let { (path, value) -> flatten(table + path, value, out) }
            }
            if (at == start) line()
        }
    }

    private fun flatten(path: List<TomlKey>, value: TomlValue, out: MutableList<TomlEntry>) {
        if (value !is TomlValue.Table) { out += TomlEntry(path, value); return }
        if (value.entries.isEmpty()) out += TomlEntry(path, value)
        value.entries.forEach { (keys, inner) -> flatten(path + keys, inner, out) }
    }

    private fun header(): List<TomlKey> {
        while (at < text.length && text[at] == '[') at++
        val keys = keys()
        line()
        return keys
    }

    private fun entry(): Pair<List<TomlKey>, TomlValue>? {
        val keys = keys().ifEmpty { return null }
        spaces()
        if (text.getOrNull(at) != '=') return null
        at++
        spaces()
        return keys to value()
    }

    private fun keys(): List<TomlKey> {
        val out = mutableListOf<TomlKey>()
        while (true) {
            spaces()
            out += key() ?: return out
            spaces()
            if (text.getOrNull(at) != '.') return out
            at++
        }
    }

    private fun key(): TomlKey? {
        val start = at
        val quote = text.getOrNull(at)?.takeIf { it == '"' || it == '\'' }
        if (quote != null) {
            val end = (at + 1 until text.length).firstOrNull { text[it] == quote || text[it] == '\n' }?.takeIf { text[it] == quote } ?: return null
            at = end + 1
            return TomlKey(text.substring(start + 1, end), TextRange(start + 1, end))
        }
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_' || text[at] == '-')) at++
        return TomlKey(text.substring(start, at), TextRange(start, at)).takeIf { at > start }
    }

    private fun value(): TomlValue {
        val c = text.getOrNull(at) ?: return TomlValue.Scalar("")
        return when {
            c == '"' || c == '\'' -> string(c)
            c == '[' -> array()
            c == '{' -> table()
            else -> scalar()
        }
    }

    private fun string(quote: Char): TomlValue {
        val long = "$quote$quote$quote"
        val start = at
        if (text.startsWith(long, at)) {
            val end = text.indexOf(long, at + 3).takeIf { it >= 0 } ?: text.length
            at = (end + 3).coerceAtMost(text.length)
            return TomlValue.Text(TextRange(start + 3, end), text.substring(start + 3, end))
        }
        var end = at + 1
        while (end < text.length && text[end] != quote && text[end] != '\n') end += 1 + (1.takeIf { quote == '"' && text[end] == '\\' } ?: 0)
        end = end.coerceAtMost(text.length)
        at = (end + 1).coerceAtMost(text.length)
        return TomlValue.Text(TextRange(start + 1, end), text.substring(start + 1, end))
    }

    private fun array(): TomlValue {
        at++
        val items = mutableListOf<TomlValue>()
        while (true) {
            separators()
            if (at >= text.length) return TomlValue.Array(items)
            if (text[at] == ']') { at++; return TomlValue.Array(items) }
            val start = at
            items += value()
            if (at == start) at++
        }
    }

    private fun table(): TomlValue {
        at++
        val entries = mutableListOf<Pair<List<TomlKey>, TomlValue>>()
        while (true) {
            separators()
            if (at >= text.length) return TomlValue.Table(entries)
            if (text[at] == '}') { at++; return TomlValue.Table(entries) }
            val start = at
            entry()?.let { entries += it }
            if (at == start) at++
        }
    }

    private fun scalar(): TomlValue {
        val start = at
        while (at < text.length && text[at] !in ",]}\n#") at++
        return TomlValue.Scalar(text.substring(start, at).trim())
    }

    private fun spaces() { while (at < text.length && (text[at] == ' ' || text[at] == '\t')) at++ }
    private fun line() { while (at < text.length && text[at] != '\n') at++ }
    private fun blank() { skip(" \t\r\n") }
    private fun separators() { skip(" \t\r\n,") }

    private fun skip(chars: String) {
        while (at < text.length) {
            when {
                text[at] == '#' -> line()
                text[at] in chars -> at++
                else -> return
            }
        }
    }
}
