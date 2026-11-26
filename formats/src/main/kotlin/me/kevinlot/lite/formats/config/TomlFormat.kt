package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import java.util.regex.Pattern
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.FmtFile
import me.kevinlot.lite.formats.FmtToken
import me.kevinlot.lite.formats.FmtWord
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Rule
import me.kevinlot.lite.formats.ScriptRules
import me.kevinlot.lite.formats.Scripts

object Toml : Format("Toml", "TOML", "toml") {
    override val lineComment = "#"
    // a table header is alone on its line: `["a", "b"],` is a row of an array
    private const val HEADER_END = """(?=[ \t]*(?:#|$))"""
    private const val KEY_PART = """(?:[\w-]+|"[^"\n]*"|'[^'\n]*')"""
    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT),
        Rule("""\[\[[^\]\n,]*]]$HEADER_END""", Cat.SECTION, lineStart = true, flags = Pattern.MULTILINE),
        Rule("""\[[^\]\n,]*]$HEADER_END""", Cat.SECTION, lineStart = true, flags = Pattern.MULTILINE),
        Rule(""""{3}[\s\S]*?(?:"{3}|\z)""", Cat.STRING),
        Rule("""'{3}[\s\S]*?(?:'{3}|\z)""", Cat.STRING),
        Rule("""$KEY_PART(?:\s*\.\s*$KEY_PART)*(?=\s*=)""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule("""'[^'\n]*'?""", Cat.STRING),
        Rule("""\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})?)?|\d{2}:\d{2}:\d{2}(?:\.\d+)?""", Cat.CONSTANT),
        Rule("""\b(?:true|false|inf|nan)\b""", Cat.KEYWORD),
        Rule(R.NUMBER, Cat.NUMBER),
    )

    override val linker: Linker = TomlLinks

    /** The `run` of a mise task (`[tasks.x]`, or a table of a `tasks` file): a string, or the strings of an array. */
    override val scripts = object : ScriptRules() {
        private val runKey = Regex("""(?:^|\n)[ \t]*run[ \t]*=[ \t]*(?:\[[^\]]*)?$""")
        private val taskTable = Regex("""(?m)^\[\[?(?:tasks\b[^\]\n]*|"?[\w:.-]+"?)]""")
        private val quotes = listOf("\"\"\"", "'''", "\"", "'")

        /** mise renders its templates (`{{arg(name="x")}}`) before the shell reads the script. */
        override val substitutions = listOf(Regex("""\{\{.*?}}""") to Scripts.PLACEHOLDER)

        override fun lines(word: FmtWord, file: FmtFile): List<TextRange> {
            if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat != Cat.STRING) return emptyList()
            val contents = file.viewProvider.contents
            val start = word.textRange.startOffset
            // the key is on the same line, or a few lines above when the value is an item of an array
            if (!runKey.containsMatchIn(contents.subSequence((start - 400).coerceAtLeast(0), start))) return emptyList()
            if (!inTask(file.name, contents.subSequence(0, start))) return emptyList()
            val text = word.text
            val quote = quotes.firstOrNull { text.startsWith(it) && text.endsWith(it) && text.length >= 2 * it.length } ?: return emptyList()
            // TOML drops a line break right after the opening quotes of a multi-line string
            val from = quote.length + (1.takeIf { quote.length == 3 && text.getOrNull(3) == '\n' } ?: 0)
            return Scripts.lines(text.substring(0, text.length - quote.length), from = from)
        }

        /** A task sits under `[tasks…]`; a file of tasks (`tasks.toml`, `x.tasks.toml`) names each one by its table. */
        private fun inTask(fileName: String, before: CharSequence): Boolean {
            val table = taskTable.findAll(before).lastOrNull()?.value ?: return false
            return table.trimStart('[').startsWith("tasks") || fileName.endsWith("tasks.toml")
        }
    }
}
