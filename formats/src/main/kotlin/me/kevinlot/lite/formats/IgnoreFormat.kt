package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange

/**
 * `.gitignore` and relatives (`.dockerignore`, `.npmignore`, `.gitattributes`…): a literal pattern (no `*`, `?`, `[`)
 * leads to the file or folder it names, from the folder of the ignore file.
 */
object Ignore : Format("Ignore", "Ignore", "gitignore") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.KEY)
    // possessive: a greedy repetition of an alternation recurses once per character (see R)
    private val PATTERN = Regex("""(?m)^!?((?:\\.|[^\s\\#])(?:\\.|[^\s\\])*+)""")
    override val rules = listOf(
        Rule("""#[^\n]*""", Cat.COMMENT, lineStart = true),
        Rule("""!""", Cat.OPERATOR, lineStart = true),
        Rule("""(?<![^\n!])(?:\\.|[^\s\\])++""", Cat.KEY),
        Rule("""-?[\w.-]+(?:=[^\s]*)?""", Cat.ATTRIBUTE),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String) = Links(
            paths = PATTERN.findAll(text).mapNotNull { m ->
                val g = m.groups[1] ?: return@mapNotNull null
                val pattern = g.value
                if (pattern.any { it in "*?[" }) return@mapNotNull null
                val path = pattern.trim('/').replace("\\", "")
                if (path.isEmpty()) return@mapNotNull null
                val start = g.range.first + (pattern.length - pattern.trimStart('/').length)
                PathLink(TextRange(start, start + pattern.trim('/').length), path)
            }.toList(),
        )
    }
}
