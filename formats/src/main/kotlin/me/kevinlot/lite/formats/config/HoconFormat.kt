package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Fmt
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.PathLink
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule
import me.kevinlot.lite.formats.ranges

/**
 * HOCON (Typesafe/Lightbend config): `${a.b.c}` → the key `a.b.c` (nested blocks included), `include "x.conf"`.
 * Each segment of a path is a name of its own: `a.b.c = 1` declares `a`, `a.b` and `a.b.c`, and `${a.b.c}` refers to each.
 * A quoted segment is one name, dots included: `a."b.c"` has two.
 */
object Hocon : Format("Hocon", "HOCON", "conf") {
    override val lineComment = "#"
    private val BLOCK_OPENERS = listOf("{", "={", ":{", "= {", ": {")
    private val INCLUDE = Regex("""\binclude\s+(?:required\(\s*)?(?:(?:file|classpath)\(\s*)?"([^"]+)"""")
    private val KEY_END = Regex("""\s*(=|:|\+=|\{)""")
    private const val PATH_TAIL = """(?=(?:\.(?:[\w-]++|"[^"\n]*+"))*+\s*(?:=|:|\+=|\{))"""
    private val SEGMENT = Regex("""[\w-]+|"[^"\n]*"""")
    private val SUBSTITUTION = Regex("""\$\{\??\s*([^}\n]*?)\s*}""")
    override val rules = listOf(
        Rule("""(?:#|//)[^\n]*""", Cat.COMMENT),
        Rule(""""{3}[\s\S]*?(?:"{3}|\z)""", Cat.STRING),
        Rule("""\$\{\??[^}\n]*}""", Cat.VARIABLE),
        Rule("""\binclude\b""", Cat.KEYWORD),
        Rule("""\b(?:file|url|classpath|required)(?=\()""", Cat.FUNCTION),
        // one token per path segment, so that each one can be a name of its own
        Rule("""(?:[\w-]+|"[^"\n]*")$PATH_TAIL""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule("""\b(?:true|false|null|yes|no|on|off)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val stack = ArrayDeque<String>()
            var pendingBlock: String? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '#' || (c == '/' && i + 1 < text.length && text[i + 1] == '/') -> { while (i < text.length && text[i] != '\n') i++; continue }
                    c == '"' && text.startsWith("\"\"\"", i) -> { i = text.indexOf("\"\"\"", i + 3).takeIf { it >= 0 }?.plus(3) ?: text.length; continue }
                    c == '$' && i + 1 < text.length && text[i + 1] == '{' -> { i = text.indexOf('}', i).takeIf { it >= 0 }?.plus(1) ?: text.length; continue }
                    c == '{' -> { stack.addLast(pendingBlock ?: stack.lastOrNull() ?: ""); pendingBlock = null }
                    c == '}' -> { stack.removeLastOrNull() }
                    c == '"' || c.isLetterOrDigit() || c == '_' -> {
                        val path = keyPath(text, i)
                        val m = path.lastOrNull()?.let { KEY_END.matchAt(text, it.end) }
                        if (m != null) {
                            val names = stack.lastOrNull()?.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty() + path.map { it.name }
                            val parents = names.size - path.size
                            path.indices.forEach { index -> decls += Decl(path[index].range, "key:" + names.take(parents + index + 1).joinToString(".")) }
                            val after = (m.range.first until text.length).firstOrNull { !text[it].isWhitespace() } ?: text.length
                            if (m.groupValues[1] == "{" || BLOCK_OPENERS.any { text.startsWith(it, after) }) pendingBlock = names.joinToString(".")
                            i = m.range.first
                            continue
                        }
                        if (c == '"') { i = text.indexOf('"', i + 1).takeIf { it >= 0 }?.plus(1) ?: text.length; continue }
                    }
                }
                i++
            }
            val refs = SUBSTITUTION.findAll(text).mapNotNull { it.groups[1] }.flatMap { g ->
                val path = SEGMENT.findAll(g.value).map { segment(it, shift = g.range.first) }.toList()
                path.indices.map { index -> Ref(path[index].range, listOf("key:" + path.take(index + 1).joinToString(".") { it.name })) }
            }.toList()
            val paths = INCLUDE.ranges(text).map { (r, p) -> PathLink(r, p) }.toList()
            return Links(decls, refs, paths)
        }

        /** `include "base"` may leave the extension out; a classpath resource is any file of the project ending with that path. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            val candidates = listOf(path, "$path.conf")
            candidates.firstNotNullOfOrNull { super.resolvePath(file, it) }?.let { return it }
            val suffixes = candidates.map { "/" + it.trimStart('/') }
            val resource = Fmt.projectFiles(file.project, Hocon).firstOrNull { vf -> suffixes.any(vf.path::endsWith) } ?: return null
            return PsiManager.getInstance(file.project).findFile(resource)
        }

        /** A key is looked up where it is used, then in the included files, then in every HOCON file of the project (`reference.conf`, overrides). */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? {
            val manager = PsiManager.getInstance(file.project)
            val everywhere by lazy { Fmt.projectFiles(file.project, Hocon).mapNotNull(manager::findFile) }
            // a tier is listed only when the ones before it declare nothing: most keys are found in the file
            val tiers = sequenceOf({ listOf(file) }, { Fmt.scope(file) }, { everywhere })
            return ref.keys.firstNotNullOfOrNull { key -> tiers.map { Fmt.declarations(it(), Hocon, key) }.firstOrNull { it.isNotEmpty() } }.orEmpty()
        }
    }

    /** One level of a path: its name without the quotes, where that name is, and where the segment ends. */
    private data class Segment(val end: Int, val name: String, val range: TextRange)

    private fun segment(match: MatchResult, shift: Int = 0): Segment {
        val quote = 1.takeIf { match.value.startsWith('"') } ?: 0
        val end = shift + match.range.last + 1
        return Segment(end = end, name = match.value.trim('"'), range = TextRange(shift + match.range.first + quote, end - quote))
    }

    /** The levels of the path starting at [at]: `a."b.c".d` has three. */
    private fun keyPath(text: CharSequence, at: Int): List<Segment> =
        generateSequence(SEGMENT.matchAt(text, at)) { previous ->
            (previous.range.last + 1).takeIf { text.getOrNull(it) == '.' }?.let { SEGMENT.matchAt(text, it + 1) }
        }.map { segment(it) }.toList()
}
