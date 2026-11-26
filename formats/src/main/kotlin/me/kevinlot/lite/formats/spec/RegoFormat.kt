package me.kevinlot.lite.formats.spec

import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import java.util.concurrent.ConcurrentHashMap
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Fmt
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.Offsets
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule
import me.kevinlot.lite.formats.range

/**
 * Rego, the policy language of Open Policy Agent: a name → the variable of its rule (`x := …`, `some x`, `every x`,
 * arguments), else the import of that name, else the rule of that name in the package (any file of it);
 * `data.a.b.rule` and `alias.rule` → the rule in package `a.b`, each package segment → a `package` line.
 * Statements are told apart by their first column, as `opa fmt` writes them.
 */
object Rego : Format("Rego", "Rego", "rego") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.DECL, Cat.FUNCTION, Cat.WORD)

    private const val NAME = """[A-Za-z_]\w*"""
    private val KEYWORDS = listOf("and", "as", "contains", "default", "else", "every", "if", "import", "in", "not", "or", "package", "some", "with")
    private val RESERVED = KEYWORDS.toSet() + setOf("_", "false", "input", "null", "true")

    private val CHAIN = Regex("""(?<![\w.])$NAME(?:\.$NAME)*""")
    private val DEAD_TEXT = Regex("""${R.DQ}|`[^`]*`?|#[^\n]*""")
    private val FIRST_COLUMN = Regex("""(?m)^(?:default[ \t]+)?($NAME)""")
    private val HEAD_NAMES = listOf(Regex("""^\(([^)]*)\)"""), Regex("""^\[([^\]]*)]"""), Regex("""^[^\n{]*?\bcontains[ \t]+($NAME)"""))
    private val IMPORT = Regex("""(?m)^import[ \t]+data\.($NAME(?:\.$NAME)*)(?:[ \t]+as[ \t]+($NAME))?""")
    private val PACKAGE = Regex("""(?m)^package[ \t]+($NAME(?:\.$NAME)*)""")
    private val SEGMENT = Regex(NAME)
    /** `x = …` unifies: it declares `x` unless a rule of the file bears that name. */
    private val UNIFICATION = Regex("""(?m)^[ \t]*($NAME)[ \t]*=(?!=)""")
    private val WRITES = listOf(
        Regex("""(?<![\w.])($NAME)[ \t]*:="""),
        Regex("""\[([\w\s,]+)][ \t]*:="""),
        Regex("""\bsome[ \t]+($NAME(?:[ \t]*,[ \t]*$NAME)*)"""),
        Regex("""\bevery[ \t]+($NAME(?:[ \t]*,[ \t]*$NAME)*)[ \t]+in\b"""),
    )

    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT),
        Rule(R.DQ, Cat.STRING),
        Rule("""`[^`]*`?""", Cat.STRING),
        Rule("""(?<![\w.])(?:${KEYWORDS.joinToString("|")})(?!\w)""", Cat.KEYWORD),
        Rule("""(?<![\w.])(?:false|null|true)(?!\w)""", Cat.CONSTANT),
        Rule("""(?<![\w.])(?:data|input)(?!\w)""", Cat.META),
        Rule("""(?<![^\n])$NAME""", Cat.DECL),
        Rule("""$NAME(?=\()""", Cat.FUNCTION),
        Rule(R.NUMBER, Cat.NUMBER),
        Rule(NAME, Cat.WORD),
    )

    /** A rule or function definition: from its first column to the next statement. */
    private class Statement(val name: MatchGroup, val end: Int) {
        val start: Int get() = name.range.first
        val bodyStart: Int get() = name.range.last + 1
    }

    fun packageOf(text: CharSequence): String = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val dead = Offsets(DEAD_TEXT.findAll(text).map { it.range })
            val pkg = packageOf(text)
            val packages = PACKAGE.find(text)?.groups?.get(1)?.let { segments(it, "pkg:") }.orEmpty()
            val imports = IMPORT.findAll(text).filter { it.range.first !in dead }.toList()
            val aliases = imports.associate { alias(it).second to it.groupValues[1] }

            val firstColumn = FIRST_COLUMN.findAll(text).filter { it.range.first !in dead }.mapNotNull { it.groups[1] }.toList()
            val statements = firstColumn.mapIndexedNotNull { index, name ->
                Statement(name, firstColumn.getOrNull(index + 1)?.range?.first ?: text.length).takeIf { name.value !in KEYWORDS }
            }
            val definitions = statements.map { range(it.name) to "rule:$pkg.${it.name.value}" }
            val firstDefinitions = definitions.distinctBy { it.second }.toSet()

            val ruleNames = statements.map { it.name.value }.toSet()
            val locals = statements.associateWith { locals(it, text, dead, ruleNames) }
            val writes = locals.flatMap { (statement, names) -> names.map { (range, name) -> range to "local:$name@${statement.start}" } }
                .sortedBy { it.first.startOffset }
            val firstWrites = writes.distinctBy { it.second }.toSet()
            val taken = (writes + definitions).map { it.first.startOffset }.toSet()

            val uses = statements.flatMap { statement ->
                val declared = locals.getValue(statement).map { it.second }.toSet()
                CHAIN.findAll(text.subSequence(statement.start, statement.end)).filter { statement.start + it.range.first !in dead }.flatMap { chain ->
                    uses(chain, statement, pkg, aliases, declared, taken)
                }
            }
            return Links(
                decls = packages.takeLast(1).map { (range, key) -> Decl(range, key) } +
                    imports.map { Decl(alias(it).first, "import:${alias(it).second}") } +
                    (firstDefinitions + firstWrites).map { (range, key) -> Decl(range, key) },
                refs = packages.dropLast(1).map { (range, key) -> Ref(range, listOf(key)) } +
                    imports.flatMap { import -> import.groups[1]?.let { segments(it, "data:") }.orEmpty().map { (range, key) -> Ref(range, listOf(key)) } } +
                    (definitions.filterNot { it in firstDefinitions } + writes.filterNot { it in firstWrites })
                        .map { (range, key) -> Ref(range, listOf(key)) } +
                    uses,
            )
        }

        override fun scope(file: PsiFile): List<PsiFile> = listOf(file)

        /** A variable or an import lives in the file; a rule in any file of its package. */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? = ref.keys.firstNotNullOfOrNull { key ->
            val found = when (key.substringBefore(':')) {
                "data" -> dataPath(file, key.removePrefix("data:"))
                "pkg" -> packageLines(file, key.removePrefix("pkg:"))
                "rule" -> rule(file, key)
                else -> Fmt.declarations(listOf(file), Rego, key)
            }
            found.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private fun range(group: MatchGroup, base: Int = 0) = TextRange(base + group.range.first, base + group.range.last + 1)

    /** Each segment of a dotted path, with the key of the path up to it. */
    private fun segments(path: MatchGroup, prefix: String, base: Int = 0, parent: String = ""): List<Pair<TextRange, String>> {
        val names = SEGMENT.findAll(path.value).toList()
        return names.indices.map { index ->
            val start = base + path.range.first + names[index].range.first
            val key = (listOf(parent).filter { it.isNotEmpty() } + names.take(index + 1).map { it.value }).joinToString(".")
            TextRange(start, start + names[index].value.length) to prefix + key
        }
    }

    /** The name an import binds: its `as` name, else the last segment of its path. */
    private fun alias(import: MatchResult): Pair<TextRange, String> {
        import.groups[2]?.let { return range(it) to it.value }
        val path = import.groups[1] ?: return TextRange.EMPTY_RANGE to ""
        val last = path.value.substringAfterLast('.')
        return TextRange(path.range.last + 1 - last.length, path.range.last + 1) to last
    }

    /** The variables of a statement: its arguments and keys, then every `x :=`, `some x` and `every x`. */
    private fun locals(statement: Statement, text: CharSequence, dead: Offsets, ruleNames: Set<String>): List<Pair<TextRange, String>> {
        val body = text.subSequence(statement.bodyStart, statement.end)
        val head = HEAD_NAMES.mapNotNull { it.find(body)?.groups?.get(1) }
        val written = WRITES.flatMap { pattern -> pattern.findAll(body).mapNotNull { it.groups[1] }.toList() }
        val unified = UNIFICATION.findAll(body).mapNotNull { it.groups[1] }.filter { it.value !in ruleNames }.toList()
        return (head + written + unified).flatMap { group ->
            SEGMENT.findAll(group.value).filter { it.value !in RESERVED && !it.value.first().isDigit() }.map { name ->
                val start = statement.bodyStart + group.range.first + name.range.first
                TextRange(start, start + name.value.length) to name.value
            }
        }.filter { (range, _) -> range.startOffset !in dead }.distinctBy { it.first.startOffset }
    }

    private fun uses(chain: MatchResult, statement: Statement, pkg: String, aliases: Map<String, String>, declared: Set<String>, taken: Set<Int>): List<Ref> {
        val offset = statement.start + chain.range.first
        val head = chain.value.substringBefore('.')
        val headRange = TextRange(offset, offset + head.length)
        val rest = chain.groups[0]?.takeIf { '.' in chain.value }?.let { MatchPath(it.value.substringAfter('.'), offset + head.length + 1) }
        val imported = aliases[head]
        return when {
            head == "data" -> rest?.segments("").orEmpty()
            head in RESERVED -> emptyList()
            head in declared -> listOf(Ref(headRange, listOf("local:$head@${statement.start}"))).filter { offset !in taken }
            imported != null -> listOf(Ref(headRange, listOf("import:$head"))) + rest?.segments(imported).orEmpty()
            offset in taken -> emptyList()
            else -> listOf(Ref(headRange, listOf("rule:$pkg.$head")))
        }
    }

    /** The part of a chain after its head, at [base] in the file. */
    private class MatchPath(private val path: String, private val base: Int) {
        fun segments(parent: String): List<Ref> {
            val names = SEGMENT.findAll(path).toList()
            return names.indices.map { index ->
                val start = base + names[index].range.first
                val key = (listOf(parent).filter { it.isNotEmpty() } + names.take(index + 1).map { it.value }).joinToString(".")
                Ref(TextRange(start, start + names[index].value.length), listOf("data:$key"))
            }
        }
    }

    /** `a.b.rule`: the rule of package `a.b`, else the `package a.b.rule` lines. */
    private fun dataPath(file: PsiFile, path: String): List<PsiElement> {
        val pkg = path.substringBeforeLast('.', "")
        return rule(file, "rule:$pkg.${path.substringAfterLast('.')}").ifEmpty { packageLines(file, path) }
    }

    private fun rule(file: PsiFile, key: String): List<PsiElement> {
        val qualified = key.removePrefix("rule:")
        val pkg = qualified.substringBeforeLast('.', "")
        val own = Fmt.declarations(listOf(file).filter { packageOf(it.text) == pkg }, Rego, key)
        return own.ifEmpty { Fmt.declarations(RegoPackages.files(file.project, pkg, qualified.substringAfterLast('.')), Rego, key) }
    }

    private fun packageLines(file: PsiFile, pkg: String): List<PsiElement> =
        Fmt.declarations(RegoPackages.files(file.project, pkg, pkg.substringAfterLast('.')), Rego, "pkg:$pkg")
}

/** The Rego files of a package, found through the word index and each file's `package` line. */
object RegoPackages {
    private val CACHE = Key.create<ConcurrentHashMap<VirtualFile, Pair<Long, String>>>("lite.rego.packages")
    private const val MAX_FILES = 5000

    /** The files of [pkg] holding [word], in path order. */
    fun files(project: Project, pkg: String, word: String): List<PsiFile> {
        val candidates = linkedSetOf<VirtualFile>()
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(GlobalSearchScope.projectScope(project), UsageSearchContext.ANY, true, word) {
            if (it.extension == Rego.extension) candidates.add(it)
            candidates.size < MAX_FILES
        }
        val manager = PsiManager.getInstance(project)
        return candidates.filter { packageOf(project, it) == pkg }.sortedBy { it.path }.mapNotNull { manager.findFile(it) }
    }

    private fun packageOf(project: Project, file: VirtualFile): String {
        val known = project.getUserData(CACHE) ?: ConcurrentHashMap<VirtualFile, Pair<Long, String>>().also { project.putUserData(CACHE, it) }
        known[file]?.takeIf { it.first == file.modificationStamp }?.let { return it.second }
        val pkg = Rego.packageOf(LoadTextUtil.loadText(file))
        known[file] = file.modificationStamp to pkg
        return pkg
    }
}
