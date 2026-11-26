package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager

/**
 * fish: a command word → `function name`; `$x` → its first `set x` / `for x in` / `read x` / `function -a x`;
 * `source file` → the file. A variable declared with `set -l`, as a function argument or by a `for` inside a function
 * belongs to that function. Functions and global variables are shared by every fish file of the project (fish
 * autoloads `functions/name.fish`), the sourced files first.
 */
object Fish : Format("Fish", "fish", "fish") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.VARIABLE, Cat.WORD, Cat.DECL, Cat.STRING, Cat.KEY)

    private const val NAME = """[A-Za-z_]\w*"""
    private const val COMMAND = """[A-Za-z_][\w:.-]*"""
    private const val FLAGS = """(?:-{1,2}[\w-]+[ \t]+)*"""
    private const val FLAG = """-{1,2}[\w-]{1,24}"""
    private val KEYWORDS = listOf(
        "and", "begin", "break", "builtin", "case", "command", "continue", "else", "end", "exec", "for", "function", "if", "in", "not", "or",
        "return", "set", "source", "switch", "while",
    )
    private val ARGUMENT_NAMES = Regex("""(?:-a|--argument-names)((?:[ \t]+$NAME)+)""")
    private val ASSIGNMENT = Regex("""\bset[ \t]+($FLAGS)($NAME)""")
    private val BLOCK_WORD = Regex("""(?m)(?:^|;)[ \t]*(begin|end|for|function|if|switch|while)\b""")
    private val COMMAND_WORD = Regex("""(?m)(?:^|[;|&(]|\b(?:and|begin|command|else|if|not|or|while)\b)[ \t]*($COMMAND)(?![\w/=])""")
    private val DEAD_TEXT = Regex(""""[^"\\]*+(?:\\.[^"\\]*+)*+"|'[^'\\]*+(?:\\.[^'\\]*+)*+'|(?<![\w$])#[^\n]*""")
    private val DECLARATIONS = listOf(
        ASSIGNMENT,
        Regex("""\bfor[ \t]+()($NAME)[ \t]+in\b"""),
        Regex("""\bread[ \t]+($FLAGS)($NAME)"""),
    )
    private val FUNCTION = Regex("""(?m)^[ \t]*function[ \t]+($COMMAND)""")
    private val FUNCTION_HEAD = Regex("""(?m)^[ \t]*function[ \t]+$COMMAND((?:[ \t]+[^\n;#]+)?)""")
    private val LOCAL_FLAG = Regex("""(?:^|[ \t])(?:-[a-zA-Z]*l[a-zA-Z]*|--local)(?=[ \t])""")
    private val QUERY_FLAG = Regex("""(?:^|[ \t])(?:-[a-zA-Z]*[qe][a-zA-Z]*|--query|--erase)(?=[ \t])""")
    private val SOURCE = Regex("""(?m)(?:^|[;|&(])[ \t]*(?:source|\.)[ \t]+([^\s;|&"'$()]+)""")
    private val VARIABLE = Regex("""\$+($NAME)""")
    private val WORD = Regex(NAME)

    override val rules = listOf(
        Rule("""(?<![\w$])#[^\n]*""", Cat.COMMENT),
        Rule("""'[^'\\]*+(?:\\.[^'\\]*+)*+'?""", Cat.STRING),
        Rule(""""[^"\\]*+(?:\\.[^"\\]*+)*+"?""", Cat.STRING),
        Rule("""\$+$NAME""", Cat.VARIABLE),
        Rule("""(?<=\bfunction[ \t])$COMMAND""", Cat.DECL),
        // the name written by `set`/`for`/`read`, after up to two flags (a look-behind cannot repeat a group)
        Rule(
            """(?:(?<=\b(?:set|for|read) )|(?<=\b(?:set|read) $FLAG )|(?<=\b(?:set|read) $FLAG $FLAG ))$NAME(?=[ \t]|$)""",
            Cat.KEY, flags = java.util.regex.Pattern.MULTILINE,
        ),
        Rule("""(?:${KEYWORDS.joinToString("|")})(?![\w:.-])""", Cat.KEYWORD),
        Rule("""--?[A-Za-z][\w-]*""", Cat.ATTRIBUTE),
        // paths (`./lib.fish`, `~/x/y.fish`, `conf.d/a.fish`): one token, so that they can be followed
        Rule("""(?<![\w$(.-])(?:~|[\w-]+|\.{1,2})?(?:/[\w.-]+)+/?|(?<![\w$(.-])[\w-]+(?:\.[\w-]+)+""", Cat.WORD),
        Rule(COMMAND, Cat.WORD),
        Rule(R.NUMBER, Cat.NUMBER),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val dead = Offsets(DEAD_TEXT.findAll(text).filterNot { it.value.startsWith('"') }.map { it.range })
            val live = { offset: Int -> offset !in dead }
            val functions = functionRanges(text, live)
            val scoped = { name: String, offset: Int -> functions.lastOrNull { offset in it }?.let { "var:$name@${it.first}" } }

            // every place a variable is written, with the function it is local to (null: global)
            val writes = DECLARATIONS.flatMap { pattern -> pattern.findAll(text).toList() }
                .filter { live(it.range.first) && !QUERY_FLAG.containsMatchIn(it.groupValues[1]) }
                .mapNotNull { m ->
                    val name = m.groups[2] ?: return@mapNotNull null
                    val local = m.value.startsWith("for") || LOCAL_FLAG.containsMatchIn(" " + m.groupValues[1])
                    Triple(TextRange(name.range.first, name.range.last + 1), name.value, scoped(name.value, name.range.first).takeIf { local })
                } +
                FUNCTION_HEAD.findAll(text).filter { live(it.range.first) }.flatMap { m ->
                    val arguments = m.groups[1] ?: return@flatMap emptyList()
                    val names = ARGUMENT_NAMES.find(arguments.value)?.groups?.get(1) ?: return@flatMap emptyList()
                    WORD.findAll(names.value).map { n ->
                        val start = arguments.range.first + names.range.first + n.range.first
                        Triple(TextRange(start, start + n.value.length), n.value, "var:${n.value}@${m.range.first}")
                    }.toList()
                }
            // the first write of a name in its scope declares it; the later ones refer to it
            val ordered = writes.sortedBy { it.first.startOffset }.map { (range, name, local) -> range to (local ?: "var:$name") }
            val firsts = ordered.distinctBy { it.second }
            val declaring = firsts.toSet()
            val decls = firsts.map { (range, key) -> Decl(range, key) } +
                FUNCTION.ranges(text).filter { (r, _) -> live(r.startOffset) }.map { (r, n) -> Decl(r, "fn:$n") }
            val declaredLocally = firsts.map { it.second }.filter { '@' in it }.toSet()
            val keysOf = { name: String, offset: Int -> listOfNotNull(scoped(name, offset)?.takeIf { it in declaredLocally }, "var:$name") }
            val refs = ordered.filterNot { it in declaring }.map { (range, key) -> Ref(range, listOf(key)) } +
                VARIABLE.ranges(text).filter { (r, _) -> live(r.startOffset) }.map { (r, n) -> Ref(r, keysOf(n, r.startOffset)) } +
                ASSIGNMENT.findAll(text).filter { live(it.range.first) && QUERY_FLAG.containsMatchIn(it.groupValues[1]) }
                    .mapNotNull { it.groups[2] }.map { Ref(TextRange(it.range.first, it.range.last + 1), keysOf(it.value, it.range.first)) } +
                COMMAND_WORD.ranges(text).filter { (r, n) -> live(r.startOffset) && n !in KEYWORDS }.map { (r, n) -> Ref(r, listOf("fn:$n")) }
            val paths = SOURCE.ranges(text).filter { (r, _) -> live(r.startOffset) }.map { (r, p) -> PathLink(r, p) }
            return Links(decls.toList(), refs.toList(), paths.toList())
        }

        /** `~/…` is the home folder. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            if (!path.startsWith("~/")) return super.resolvePath(file, path)
            val target = LocalFileSystem.getInstance().findFileByPath(System.getProperty("user.home") + path.removePrefix("~")) ?: return null
            return PsiManager.getInstance(file.project).findItem(target)
        }

        /** The file, what it sources, then the other fish files: one definition is enough, the nearest wins. */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? {
            val manager = PsiManager.getInstance(file.project)
            val project by lazy { Fmt.projectFiles(file.project, Fish).mapNotNull(manager::findFile) }
            val autoloaded = ref.keys.filter { it.startsWith("fn:") }.map { it.removePrefix("fn:") + ".fish" }
            // a tier is listed only when the ones before it declare nothing: most names are found in the file
            val tiers = sequenceOf({ listOf(file) }, { Fmt.scope(file) }, { project.filter { it.name in autoloaded } }, { project })
            return ref.keys.firstNotNullOfOrNull { key -> tiers.map { Fmt.declarations(it(), Fish, key) }.firstOrNull { it.isNotEmpty() } }.orEmpty()
        }
    }

    /** From `function` to its `end`, counting the blocks opened in between. */
    private fun functionRanges(text: CharSequence, live: (Int) -> Boolean): List<IntRange> {
        val words = BLOCK_WORD.findAll(text).mapNotNull { it.groups[1] }.filter { live(it.range.first) }.toList()
        return words.indices.filter { words[it].value == "function" }.mapNotNull { start ->
            val close = (start until words.size).asSequence()
                .runningFold(0) { depth, index -> depth + depthChange(words[index].value) }
                .drop(1).indexOfFirst { it == 0 }
            words.getOrNull(start + close)?.takeIf { close >= 0 }?.let { words[start].range.first..it.range.last }
        }
    }

    private fun depthChange(word: String): Int = when (word) {
        "end" -> -1
        else -> 1
    }
}
