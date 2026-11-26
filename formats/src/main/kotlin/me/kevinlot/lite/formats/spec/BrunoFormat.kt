package me.kevinlot.lite.formats.spec

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
import me.kevinlot.lite.formats.config.Env
import me.kevinlot.lite.formats.ranges

/**
 * Bruno requests (`.bru`): `{{name}}` → the variable of that name in a `vars` block or a `bru.setVar("name", …)` of the
 * request, of the `folder.bru` and `collection.bru` above it, and of every environment of the collection;
 * `{{process.env.NAME}}` → `NAME=` in the `.env` file of the collection.
 */
object Bruno : Format("Bruno", "Bruno", "bru") {
    override val nameCategories = setOf(Cat.KEY, Cat.STRING, Cat.VARIABLE, Cat.WORD)

    private const val MAX_DEPTH = 8
    private const val PROCESS_ENV = "process.env."
    private const val QUOTED_KEY = """"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+""""
    private val COLLECTION_FILES = listOf("collection.bru", "folder.bru")
    private val COLLECTION_MARKERS = listOf("bruno.json", "collection.bru", "environments")
    /** A key, quoted or not, without the `~` of a disabled entry and the `@` of a local variable. */
    private val ENTRY = Regex("""(?m)^[ \t]*~?(?:"([^"\\\n]*+(?:\\.[^"\\\n]*+)*+)"|@?([^\s:{}~]+))[ \t]*:""")
    private val MULTILINE_VALUE = Regex("""'''[\s\S]*?(?:'''|\z)""")
    private val SCRIPT_VARIABLE = Regex("""\bbru\.set(?:Collection|Env|GlobalEnv)?Var\(\s*["']([^"'\n]+)["']""")
    private val SECRET_LIST = Regex("""(?m)^vars:secret[ \t]*\[([^\]]*)]""")
    private val SECRET_NAME = Regex("""[^\s,\[\]]+""")
    private val USE = Regex("""\{\{\s*([\w.-]+)\s*}}""")
    private val VARIABLES = Regex("""(?m)^vars(?::[\w-]+)?[ \t]*\{[ \t]*\n([\s\S]*?)^}""")

    override val rules = listOf(
        Rule("""[a-z][\w:-]*(?=[ \t]*[{\[][ \t]*$)""", Cat.KEYWORD, lineStart = true, flags = java.util.regex.Pattern.MULTILINE),
        Rule("""~?(?:$QUOTED_KEY|[^\s:{}~]+)(?=[ \t]*:(?:\s|$))""", Cat.KEY, lineStart = true, flags = java.util.regex.Pattern.MULTILINE),
        Rule("""\{\{[^{}\n]*}}""", Cat.VARIABLE),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""(?:false|null|true)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val blocks = VARIABLES.findAll(text).mapNotNull { it.groups[1] }.flatMap { block ->
                val values = Offsets(MULTILINE_VALUE.findAll(block.value).map { it.range })
                ENTRY.findAll(block.value).filter { it.range.first !in values }.mapNotNull { it.groups[1] ?: it.groups[2] }
                    .map { name(block.range.first, it) }
            }
            val secrets = SECRET_LIST.findAll(text).mapNotNull { it.groups[1] }.flatMap { list ->
                SECRET_NAME.findAll(list.value).mapNotNull { it.groups[0] }.map { name(list.range.first, it) }
            }
            val scripted = SCRIPT_VARIABLE.findAll(text).mapNotNull { it.groups[1] }.map { name(0, it) }
            return Links(
                decls = (blocks + secrets + scripted).map { (range, name) -> Decl(range, "var:$name") }.toList(),
                refs = USE.ranges(text).map { (range, name) -> use(range, name) }.toList(),
            )
        }

        /** The request, the folders above it up to the collection, and the collection's environments. */
        override fun scope(file: PsiFile): List<PsiFile> {
            val folders = folders(file)
            val root = folders.lastOrNull() ?: return listOf(file)
            val shared = folders.flatMap { dir -> COLLECTION_FILES.mapNotNull { dir.findChild(it) } }
            val environments = root.findChild("environments")?.children.orEmpty().filter { it.extension == extension }.sortedBy { it.name }
            val manager = PsiManager.getInstance(file.project)
            return (listOf(file) + (shared + environments).mapNotNull { manager.findFile(it) }).distinct()
        }

        /** Bruno adds the `.env` file at the root of the collection to `process.env`. */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? {
            val name = ref.keys.firstOrNull { it.startsWith("env:") }?.removePrefix("env:") ?: return null
            val dotenv = folders(file).lastOrNull()?.findChild(".env")?.let { PsiManager.getInstance(file.project).findFile(it) } ?: return emptyList()
            return Fmt.declarations(listOf(dotenv), Env, "var:$name")
        }
    }

    /** The folders from the file's up to the root of its collection. */
    private fun folders(file: PsiFile): List<VirtualFile> {
        val folder = file.originalFile.virtualFile?.parent ?: return emptyList()
        val above = generateSequence(folder) { it.parent }.take(MAX_DEPTH).toList()
        val root = above.firstOrNull { dir -> COLLECTION_MARKERS.any { dir.findChild(it) != null } } ?: folder
        return above.take(above.indexOf(root) + 1)
    }

    private fun name(base: Int, group: MatchGroup): Pair<TextRange, String> =
        TextRange(base + group.range.first, base + group.range.last + 1) to group.value

    private fun use(range: TextRange, name: String): Ref {
        if (!name.startsWith(PROCESS_ENV)) return Ref(range, listOf("var:$name"))
        return Ref(range, listOf("env:${name.removePrefix(PROCESS_ENV)}"), TextRange(range.startOffset + PROCESS_ENV.length, range.endOffset))
    }
}
