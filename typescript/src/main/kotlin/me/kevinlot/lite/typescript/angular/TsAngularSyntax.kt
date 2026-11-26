package me.kevinlot.lite.typescript.angular

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType

/** A stretch of the host read by TypeScript, with the text standing before and after it. */
class AngularPlace(val range: TextRange, val prefix: String, val suffix: String)

object AngularSyntax {
    private class Edit(val range: TextRange, val text: String)

    private val ALIAS = Regex("""^\s*as\s+([A-Za-z_$][\w$]*)\s*$""")
    private val BINDING = Regex("""([\[(][^\s=<>"'`]+[\])])\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val FOR_ITEM = Regex("""^(\s*)([A-Za-z_$][\w$]*)(\s+of\s+)(?=\S)""")
    // `(click)="delete.emit()"`, `{{ class }}`: a member may be named like a keyword, which TypeScript only reads after `this.`
    private val KEYWORD_MEMBER = Regex(
        """^\s*(?:break|case|catch|class|continue|default|delete|do|export|import|return|switch|throw|try|var|while|with)\s*(?:[.(]|$)""",
    )
    private val WHEN = Regex("""^\s*(?:(?:hydrate|prefetch)\s+)?when\s+(?=\S)""")
    private val TRACK = Regex("""^\s*track\s+(?=\S)""")
    private val VARIABLES = Regex("""^\s*let\s+\S""")
    val KEYWORD = Regex("""@(?:case|default|defer|else(?:\s+if)?|empty|error|for|if|let|loading|placeholder|switch)(?![\w-])""")
    private val WITH_HEADER = setOf(AngularTokens.HEADER, AngularTokens.LOOP)

    /** The fragments of a piece of template syntax of [kind]. */
    fun fragments(kind: IElementType, text: String): List<List<AngularPlace>> {
        val all = TextRange(0, text.length)
        return when (kind) {
            AngularTokens.DECLARATION -> listOfNotNull(declaration(text, all))
            AngularTokens.EXPRESSION -> listOfNotNull(expression(text, all))
            AngularTokens.TRIGGERS -> parts(text, all).mapNotNull { part ->
                WHEN.find(part.substring(text))?.let { expression(text, TextRange(part.startOffset + it.value.length, part.endOffset)) }
            }
            in WITH_HEADER -> header(text, all, kind == AngularTokens.LOOP)
            else -> emptyList()
        }
    }

    /** The fragments of a template written in [range] of [text] (a string of TypeScript): its template syntax and its bindings. */
    fun inline(text: String, range: TextRange): List<List<AngularPlace>> {
        val lexer = AngularLexer()
        lexer.start(text, range.startOffset, range.endOffset, 0)
        val found = ArrayList<List<AngularPlace>>()
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            val token = text.substring(start, lexer.tokenEnd)
            val fragments = when (val kind = lexer.tokenType) {
                AngularTokens.CONTENT -> BINDING.findAll(token).flatMap { attribute(it.groupValues[1], token, range(0, it.groups[2] ?: it.groups[3])) }.toList()
                else -> fragments(kind ?: AngularTokens.CONTENT, token)
            }
            found += fragments.map { places -> places.map { AngularPlace(it.range.shiftRight(start), it.prefix, it.suffix) } }
            lexer.advance()
        }
        return found
    }

    /** The fragment of an attribute value: all of it, for a binding. */
    fun attribute(name: String, text: String, value: TextRange): List<List<AngularPlace>> = when {
        value.isEmpty -> emptyList()
        name.startsWith("[") -> listOfNotNull(expression(text, value))
        name.startsWith("(") -> listOf(listOf(AngularPlace(value, "this.".takeIf { KEYWORD_MEMBER.containsMatchIn(value.substring(text)) }.orEmpty(), "\n")))
        else -> emptyList()
    }

    /** `item of items; track item.id; let i = $index`, `cond; as alias`, or one expression. */
    private fun header(text: String, range: TextRange, loop: Boolean): List<List<AngularPlace>> =
        parts(text, range).mapIndexedNotNull { index, part ->
            val source = part.substring(text)
            val item = FOR_ITEM.find(source)?.takeIf { loop && index == 0 }
            val alias = ALIAS.find(source)?.takeIf { !loop && index > 0 }
            val track = TRACK.find(source)?.takeIf { loop }
            when {
                item != null -> iteration(text, part, item)
                alias != null -> listOf(AngularPlace(range(part.startOffset, alias.groups[1]), "const ", " = 0;\n"))
                track != null -> expression(text, TextRange(part.startOffset + track.value.length, part.endOffset))
                loop && VARIABLES.containsMatchIn(source) -> listOf(AngularPlace(part, "", ";\n"))
                loop || index > 0 -> null
                else -> expression(text, part)
            }
        }

    private fun iteration(text: String, part: TextRange, item: MatchResult): List<AngularPlace>? {
        val name = range(part.startOffset, item.groups[2])
        val items = expression(text, TextRange(part.startOffset + item.value.length, part.endOffset)) ?: return null
        val last = items.last()
        return listOf(AngularPlace(name, "for (const ", " of ")) + items.dropLast(1) +
            AngularPlace(last.range, last.prefix, last.suffix.replace(");\n", ")) {}\n"))
    }

    /** `name = expression` of a `@let`. */
    private fun declaration(text: String, range: TextRange): List<AngularPlace>? {
        val equals = (range.startOffset until range.endOffset).firstOrNull { text[it] == '=' } ?: return null
        val value = expression(text, TextRange(equals + 1, range.endOffset)) ?: return null
        return listOf(AngularPlace(TextRange(range.startOffset, equals + 1), "const ", "")) + value
    }

    /** [range] as one expression in parentheses, its pipes written as calls; null when it is blank. */
    fun expression(text: String, range: TextRange): List<AngularPlace>? {
        val source = range.substring(text).ifBlank { return null }
        return places(range, pipes(text, range), "(" + "this.".takeIf { KEYWORD_MEMBER.containsMatchIn(source) }.orEmpty(), ");\n")
    }

    /** `| name: a : b` → `| name( a , b)`: the colons of a pipe become the punctuation of a call, in each pair of brackets. */
    private fun pipes(text: String, range: TextRange): List<Edit> {
        val edits = ArrayList<Edit>()
        val open = ArrayDeque(listOf(Group()))
        fun close(at: Int) {
            if (open.last().arguments) edits += Edit(TextRange(at, at), ")")
            open.last().arguments = false
            open.last().ternaries = 0
        }
        var quote: Char? = null
        var at = range.startOffset
        while (at < range.endOffset) {
            val c = text[at]
            val group = open.last()
            val single = c == '|' && text.getOrNull(at + 1) != '|' && text.getOrNull(at - 1) != '|'
            val question = c == '?' && text.getOrNull(at + 1) != '.' && text.getOrNull(at + 1) != '?' && text.getOrNull(at - 1) != '?'
            when {
                quote != null && c == '\\' -> at++
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' -> open += Group()
                c == ')' || c == ']' || c == '}' -> { close(at); if (open.size > 1) open.removeLast() }
                c == ',' -> { close(at); group.piped = false }
                single -> { close(at); group.piped = true }
                question && group.piped -> group.ternaries++
                c == ':' && group.piped && group.ternaries > 0 -> group.ternaries--
                c == ':' && group.piped -> { edits += Edit(TextRange(at, at + 1), when { group.arguments -> ","; else -> "(" }); group.arguments = true }
            }
            at++
        }
        close(range.endOffset)
        return edits
    }

    /** The brackets open at a point of an expression (or the expression itself): each has its own pipes. */
    private class Group {
        var arguments = false
        var piped = false
        var ternaries = 0
    }

    /** [range] cut at its top-level `;`. */
    private fun parts(text: String, range: TextRange): List<TextRange> {
        val cuts = ArrayList<Int>()
        scan(text, range) { at, c -> if (c == ';') cuts += at }
        return (listOf(range.startOffset) + cuts.map { it + 1 }).zip(cuts + range.endOffset).map { (from, to) -> TextRange(from, to) }
    }

    /** The offset of the [close] that ends what starts at [from], outside strings and brackets. */
    fun closing(text: String, from: Int, close: Char): Int? {
        var found: Int? = null
        scan(text, TextRange(from, text.length)) { at, c -> if (c == close && found == null) found = at }
        return found
    }

    /** Calls [visit] for each character of [range] outside strings and nested brackets; stops at a closing bracket of its own. */
    private fun scan(text: String, range: TextRange, visit: (Int, Char) -> Unit) {
        var depth = 0
        var quote: Char? = null
        var at = range.startOffset
        while (at < range.endOffset) {
            val c = text[at]
            when {
                quote != null && c == '\\' -> at++
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                (c == ')' || c == ']' || c == '}') && depth == 0 -> { visit(at, c); return }
                c == ')' || c == ']' || c == '}' -> depth--
                depth == 0 -> visit(at, c)
            }
            at++
        }
    }

    /** [range] without its [edits]: the text of an edit ends the place before it, or starts the first one. */
    private fun places(range: TextRange, edits: List<Edit>, prefix: String, suffix: String): List<AngularPlace>? {
        val starts = listOf(range.startOffset) + edits.map { it.range.endOffset }
        val ends = edits.map { it.range.startOffset } + range.endOffset
        val kept = starts.indices.filter { starts[it] < ends[it] }.ifEmpty { return null }
        return kept.mapIndexed { order, index ->
            val leading = prefix.takeIf { order == 0 }.orEmpty() + edits.take(index).joinToString("") { it.text }.takeIf { order == 0 }.orEmpty()
            val until = kept.getOrNull(order + 1) ?: edits.size
            val trailing = edits.subList(index, until).joinToString("") { it.text } + suffix.takeIf { order == kept.lastIndex }.orEmpty()
            AngularPlace(TextRange(starts[index], ends[index]), leading, trailing)
        }
    }

    private fun range(base: Int, group: MatchGroup?): TextRange = group?.range?.let { TextRange(base + it.first, base + it.last + 1) } ?: TextRange.EMPTY_RANGE
}
