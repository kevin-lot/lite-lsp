package me.kevinlot.lite.formats

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.TreeSet

/**
 * Gherkin (`.feature`, English and French keywords): a step → its definition in the code (Cucumber for Rust, JVM,
 * JavaScript, Go, .NET; behave and pytest-bdd), matched as a Cucumber expression or a regular expression;
 * `<name>` of a scenario outline (in a step, its doc string or its table) → its column in `Examples`.
 */
object Gherkin : Format("Gherkin", "Gherkin", "feature") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.KEY, Cat.STRING, Cat.VARIABLE, Cat.WORD)

    private val SCENARIOS = listOf(
        "Background", "Contexte", "Example", "Exemple", "Plan du scénario", "Plan du Scénario", "Scenario", "Scenario Outline", "Scenario Template",
        "Scénario",
    )
    private val TABLES = listOf("Examples", "Exemples", "Scenarios")
    private val BLOCKS = (SCENARIOS + TABLES + listOf("Ability", "Business Need", "Feature", "Fonctionnalité", "Règle", "Rule"))
        .sortedByDescending { it.length }.joinToString("|")
    /** The step keywords of `en` and `fr` in Cucumber's `gherkin-languages.json`; one ending with an apostrophe takes no space after it. */
    internal val STEP_KEYWORDS = listOf(
        "Alors", "And", "But", "Donc", "Et", "Et qu'", "Et que", "Etant donné", "Etant donné qu'", "Etant donné que", "Etant donnée", "Etant données",
        "Etant donnés", "Given", "Lorsqu'", "Lorsque", "Mais", "Mais qu'", "Mais que", "Quand", "Sachant", "Sachant qu'", "Sachant que", "Soit", "Then",
        "When", "Étant donné", "Étant donné qu'", "Étant donné que", "Étant donnée", "Étant données", "Étant donnés",
    )
    private val STEPS = STEP_KEYWORDS.sortedByDescending { it.length }.joinToString("|")
    private const val CELL = """(?<=\|[ \t]{0,60})(?:\\[^\n]|[^|\s])[^|\\\n]*?(?:\\[^\n][^|\\\n]*?)*?(?=[ \t]*\|)"""
    private const val DOC_STRING = "\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|```[\\s\\S]*?(?:```|\\z)"

    private val DEAD_TEXT = Regex("""(?m)^[ \t]*(?:#[^\n]*|$DOC_STRING)""")
    private val EXAMPLES = Regex("""(?m)^[ \t]*(?:${TABLES.joinToString("|")})[ \t]*:[^\n]*\n(?:[ \t]*(?:#[^\n]*)?\n)*+([ \t]*\|[^\n]*)(?:\n([ \t]*\|[^\n]*))?""")
    private val PLACEHOLDER = Regex("""<([^<>\n]+)>""")
    private val SCENARIO = Regex("""(?m)^[ \t]*(?:${SCENARIOS.sortedByDescending { it.length }.joinToString("|")})[ \t]*:""")
    private val STEP = Regex("""(?m)^[ \t]*(?:(?:$STEPS)(?<=')|(?:$STEPS)[ \t]+|\*[ \t]+)([^\n]*?)[ \t]*$""")
    private val TABLE_CELL = Regex(CELL)
    private val WORD = Regex("""[\p{L}\p{N}_]+""")

    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT, lineStart = true),
        Rule(DOC_STRING, Cat.STRING, lineStart = true),
        Rule("""@[^\s@]+""", Cat.META),
        Rule("""(?:$BLOCKS)(?=[ \t]*:)""", Cat.KEYWORD, lineStart = true),
        Rule("""(?:$STEPS)(?:(?<=')|(?![\p{L}\p{N}_]))|\*(?=[ \t])""", Cat.KEYWORD, lineStart = true),
        Rule("""<[^<>\n]+>""", Cat.VARIABLE),
        Rule(CELL, Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule(R.NUMBER, Cat.NUMBER),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val deadText = DEAD_TEXT.findAll(text).toList()
            val dead = Offsets(deadText.asSequence().map { it.range })
            val comments = Offsets(deadText.asSequence().filter { it.value.trimStart().startsWith("#") }.map { it.range })
            val live = { offset: Int -> offset !in dead }
            val starts = SCENARIO.findAll(text).filter { live(it.range.first) }.mapTo(TreeSet()) { it.range.first }
            val scenarioOf = { offset: Int -> starts.floor(offset) ?: 0 }

            // the header row of each `Examples` table, and its first data row to try the steps with real values
            val tables = EXAMPLES.findAll(text).filter { live(it.range.first) }.toList()
            val columns = tables.flatMap { table ->
                val header = table.groups[1] ?: return@flatMap emptyList()
                TABLE_CELL.findAll(header.value).map { cell ->
                    val start = header.range.first + cell.range.first
                    Decl(TextRange(start, start + cell.value.length), "column:${cell.value}@${scenarioOf(start)}")
                }.toList()
            }.distinctBy { it.key }
            val samples = tables.flatMap { table ->
                val names = TABLE_CELL.findAll(table.groupValues[1]).map { it.value }.toList()
                val values = TABLE_CELL.findAll(table.groupValues[2]).map { it.value }.toList()
                names.zip(values).map { (name, value) -> "$name@${scenarioOf(table.range.first)}" to value }
            }.toMap()

            val steps = STEP.findAll(text).filter { live(it.range.first) }.mapNotNull { it.groups[1] }.toList()
            val placeholders = PLACEHOLDER.ranges(text).filter { (range, _) -> range.startOffset !in comments }
                .map { (range, name) -> Ref(range, listOf("column:$name@${scenarioOf(range.startOffset)}")) }.toList()
            val definitions = steps.flatMap { step ->
                val scenario = scenarioOf(step.range.first)
                val concrete = PLACEHOLDER.replace(step.value) { samples["${it.groupValues[1]}@$scenario"] ?: it.value }
                WORD.findAll(PLACEHOLDER.replace(step.value) { " ".repeat(it.value.length) }).map { word ->
                    val start = step.range.first + word.range.first
                    Ref(TextRange(start, start + word.value.length), listOf("step:$concrete"))
                }
            }
            return Links(decls = columns, refs = placeholders + definitions)
        }

        override fun scope(file: PsiFile): List<PsiFile> = listOf(file)

        /** A word of a step leads to the pattern of its definition, wherever the code is. */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? {
            val step = ref.keys.firstOrNull { it.startsWith("step:") }?.removePrefix("step:") ?: return null
            val manager = PsiManager.getInstance(file.project)
            return StepDefinitions.of(file.project).filter { it.matches(step) }
                .mapNotNull { definition -> manager.findFile(definition.file)?.let { StepPattern(it, definition.offset, definition.length) } }.distinct()
        }
    }
}

/** The pattern of a step definition: where it is written, and what it accepts. */
class StepDefinition(val file: VirtualFile, val offset: Int, val length: Int, val matches: (String) -> Boolean)

/** Where a click on a step lands: the pattern itself, in a file of any language (a plain-text file has no element there). */
class StepPattern(private val file: PsiFile, private val offset: Int, private val length: Int) : FakePsiElement() {
    override fun equals(other: Any?) = other is StepPattern && other.file == file && other.offset == offset
    override fun getName(): String = file.text.substring(offset, offset + length)
    override fun getParent(): PsiElement = file
    override fun getText(): String = name
    override fun getTextOffset() = offset
    override fun getTextRange() = TextRange(offset, offset + length)
    override fun hashCode() = 31 * file.hashCode() + offset
    override fun isValid() = file.isValid
}

object StepDefinitions {
    private val CACHE = Key.create<CachedValue<List<StepDefinition>>>("lite.gherkin.steps")
    /** A word found in every file defining steps, whatever the library. */
    private val LIBRARIES = listOf("behave", "cucumber", "godog", "pytest_bdd", "Reqnroll", "TechTalk")
    private const val MAX_FILES = 2000
    private const val STRING = """"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"|'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'|`[^`\n]*+`|/[^/\\\n]*+(?:\\.[^/\\\n]*+)*+/"""
    /** Cucumber-JVM names an annotation after its keyword, without spaces and apostrophes (`@Etantdonnéque`). */
    private val FUNCTIONS = (Gherkin.STEP_KEYWORDS.map { keyword -> keyword.filterNot { it.isWhitespace() || it == '\'' } } + listOf("defineStep", "step"))
        .distinct().sortedByDescending { it.length }.joinToString("|")
    private val DEFINITION = Regex(
        """(?i)(?<!\w)(?:$FUNCTIONS)\s*\(\s*(regex\s*=\s*|expr\s*=\s*|parsers?\.\w+\(\s*)?([a-z@]{0,2}#{0,3})($STRING)""",
    )
    private val EXPRESSION_PART = Regex("""\{([^}]*)}|\(([^)]+)\)|\\([\s\S])|(/)|(\s+)|[^{(\\/\s]+|[{(\\]""")
    private val RAW_PREFIX = Regex("""(?i)r|@""")

    fun of(project: Project): List<StepDefinition> = CachedValuesManager.getManager(project).getCachedValue(project, CACHE, {
        CachedValueProvider.Result.create(collect(project), PsiModificationTracker.MODIFICATION_COUNT)
    }, false)

    private fun collect(project: Project): List<StepDefinition> {
        val files = linkedSetOf<VirtualFile>()
        val scope = GlobalSearchScope.projectScope(project)
        val search = PsiSearchHelper.getInstance(project)
        LIBRARIES.forEach { word ->
            search.processCandidateFilesForText(scope, UsageSearchContext.ANY, true, word) { files.add(it); files.size < MAX_FILES }
        }
        val manager = PsiManager.getInstance(project)
        return files.filterNot { it.extension == Gherkin.extension }.flatMap { file ->
            val text = manager.findFile(file)?.text ?: return@flatMap emptyList()
            DEFINITION.findAll(text).mapNotNull { definition(file, it) }.toList()
        }
    }

    private fun definition(file: VirtualFile, match: MatchResult): StepDefinition? {
        val literal = match.groups[3] ?: return null
        val introducer = match.groupValues[1].lowercase()
        val raw = literal.value.startsWith("/") || literal.value.startsWith("`") || RAW_PREFIX.containsMatchIn(match.groupValues[2])
        val written = literal.value.substring(1, literal.value.length - 1)
        val content = written.takeIf { raw } ?: written.replace("\\\\", "\\").replace("\\\"", "\"")
        val offset = literal.range.first + 1
        val regular = literal.value.startsWith("/") || introducer.startsWith("regex") || introducer.contains(".re") ||
            content.startsWith("^") || content.endsWith("$")
        if (regular) return runCatching { Regex(content) }.getOrNull()?.let { pattern -> StepDefinition(file, offset, written.length) { pattern.containsMatchIn(it) } }
        return runCatching { expression(content) }.getOrNull()?.let { pattern -> StepDefinition(file, offset, written.length) { pattern.matches(it) } }
    }

    private enum class Kind { BOUNDARY, SLASH, TEXT }
    private class Part(val regex: String, val kind: Kind = Kind.TEXT)

    /** A Cucumber expression (`{string}`, `{int}`, `cucumber(s)`, `cat/dog`) or a `parse` format (`{name:d}`) as a regular expression. */
    private fun expression(text: String): Regex {
        val parts = EXPRESSION_PART.findAll(text).map(::part).toList()
        val boundaries = parts.filter { it.kind == Kind.BOUNDARY }.map { it.regex } + ""
        return Regex(parts.runs(Kind.BOUNDARY).zip(boundaries) { word, boundary -> alternation(word) + boundary }.joinToString(""))
    }

    private fun part(match: MatchResult): Part {
        val parameter = match.groups[1]?.value?.substringBefore(':')
        val optional = match.groups[2]?.value
        val escaped = match.groups[3]?.value
        return when {
            optional != null -> Part("(?:${Regex.escape(optional)})?")
            escaped != null -> Part(Regex.escape(escaped))
            match.groups[4] != null -> Part(Regex.escape(match.value), Kind.SLASH)
            match.groups[5] != null -> Part(Regex.escape(match.value), Kind.BOUNDARY)
            parameter == null -> Part(Regex.escape(match.value))
            parameter == "string" -> Part("""(?:"[^"]*"|'[^']*')""", Kind.BOUNDARY)
            parameter in setOf("biginteger", "byte", "int", "long", "short") -> Part("""-?\d+""", Kind.BOUNDARY)
            parameter in setOf("bigdecimal", "double", "float") -> Part("""-?\d*[.,]?\d+""", Kind.BOUNDARY)
            parameter == "word" -> Part("""\S+""", Kind.BOUNDARY)
            else -> Part(".*?", Kind.BOUNDARY)
        }
    }

    /** The alternatives of a word (`cat/dog`), or the word as written: a `parse` format has no alternation. */
    private fun alternation(word: List<Part>): String {
        val written = word.joinToString("") { it.regex }
        if (word.none { it.kind == Kind.SLASH }) return written
        return (word.runs(Kind.SLASH).map { alternative -> alternative.joinToString("") { it.regex } } + written).joinToString("|", "(?:", ")")
    }

    /** The runs of parts between those of [kind], which are left out. */
    private fun List<Part>.runs(kind: Kind): List<List<Part>> {
        val cuts = listOf(-1) + indices.filter { this[it].kind == kind } + size
        return cuts.zipWithNext { from, to -> subList(from + 1, to) }
    }
}
