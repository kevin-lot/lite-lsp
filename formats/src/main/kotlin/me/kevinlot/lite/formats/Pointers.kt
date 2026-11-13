package me.kevinlot.lite.formats

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/*
 * JSON references (`$ref`) as OpenAPI (v1/Swagger 1.2, v2/Swagger 2.0, v3.x), AsyncAPI and JSON Schema use them:
 * `#/components/schemas/Pet`, `common.yaml#/Error`, `./pet.yaml`, and the bare `Pet` of Swagger 1.2 models.
 * Targets are keys of YAML files (read here) or of JSON files (through [PointerTargets], with the JSON plugin).
 */

/** Finds the element a JSON pointer (`/a/b`, escaped segments) designates in a file of another language. */
interface PointerTargets {
    fun find(file: PsiFile, pointer: String): PsiElement?
}

object Pointers {
    val EP: ExtensionPointName<PointerTargets> = ExtensionPointName.create("me.kevinlot.lite.pointerTargets")

    fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")
    fun unescape(segment: String) = segment.replace("~1", "/").replace("~0", "~")

    private val FILE = Regex("""\.(?:ya?ml|json)$""", RegexOption.IGNORE_CASE)
    /** Where Swagger 1.2 models, Swagger 2.0 definitions and OpenAPI 3 schemas live (for a bare `Pet`). */
    private val BARE = listOf("/components/schemas/", "/definitions/", "/models/")

    /** What a rename of the target replaces in a reference [value]: its last pointer segment, else its file name. */
    fun nameRange(value: String): IntRange {
        val hash = value.indexOf('#')
        if (hash >= 0 && value.length > hash + 1) {
            val start = value.lastIndexOf('/').let { if (it > hash) it + 1 else hash + 1 }
            return start until value.length
        }
        val path = if (hash >= 0) value.substring(0, hash) else value
        return (path.lastIndexOf('/') + 1) until path.length
    }

    /** Targets of a reference [value] written in [from]. */
    fun resolve(from: PsiFile, value: String, linker: Linker): List<PsiElement> {
        val hash = value.indexOf('#')
        val path = if (hash >= 0) value.substring(0, hash) else value
        val pointer = if (hash >= 0) decode(value.substring(hash + 1)) else null
        if (hash < 0 && '/' !in value && !FILE.containsMatchIn(value)) {
            return BARE.firstNotNullOfOrNull { find(from, it + escape(value)) }?.let(::listOf).orEmpty()
        }
        val file = if (path.isEmpty()) from else linker.resolvePath(from, path) as? PsiFile ?: return emptyList()
        if (pointer.isNullOrEmpty() || pointer == "/") return listOf(file)
        return listOfNotNull(find(file, if (pointer.startsWith("/")) pointer else "/$pointer"))
    }

    fun find(file: PsiFile, pointer: String): PsiElement? {
        if (file is FmtFile && file.format == Yaml) {
            val decl = Fmt.links(file).decls.firstOrNull { it.key == "key:$pointer" } ?: return null
            val leaf = file.findElementAt(decl.range.startOffset)
            return PsiTreeUtil.getParentOfType(leaf, FmtWord::class.java, false) ?: leaf
        }
        return EP.extensionList.firstNotNullOfOrNull { it.find(file, pointer) }
    }

    private fun decode(s: String): String =
        if ('%' !in s) s else runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(s)

    // ---------------------------------------------------------------- YAML

    private val REF = Regex("""(?:^|[\s{,])['"]?(?:\${'$'}ref|operationRef)['"]?[ \t]*:[ \t]*(?:'([^'\n]*)'|"([^"\n]*)"|((?:[^\s'"#,{}\[\]]|\{[^}\s]*})(?:[^\s,{}\[\]]|\{[^}\s]*})*))""", RegexOption.MULTILINE)
    /** Any quoted value that is a pointer (`mapping: { dog: '#/components/schemas/Dog' }`). */
    private val POINTER_VALUE = Regex(""":[ \t]*(?:'((?:[\w./-]+\.(?:ya?ml|json))?#/[^'\n]*)'|"((?:[\w./-]+\.(?:ya?ml|json))?#/[^"\n]*)")""")

    fun yamlRefs(text: CharSequence): List<Ref> {
        val out = linkedMapOf<Int, Ref>()
        for (m in REF.findAll(text) + POINTER_VALUE.findAll(text)) {
            val g = m.groups.drop(1).firstOrNull { it != null } ?: continue
            if (g.value.isEmpty()) continue
            val start = g.range.first
            val name = nameRange(g.value)
            out.putIfAbsent(start, Ref(TextRange(start, g.range.last + 1), listOf("ref:" + g.value), TextRange(start + name.first, start + name.last + 1)))
        }
        return out.values.toList()
    }

    private val KEY = Regex("""^("(?:\\.|[^"\\])*"|'(?:''|[^'])*'|[^\s#'"{\[\]},&*!|>%@`-][^:#\n]*?|-[^\s:#][^:#\n]*?)[ \t]*:(?=\s|$)""")

    /** Block-mapping keys of a YAML text with their JSON pointer (`/paths/~1pets/get`, `/tags/0/name`). */
    fun yamlKeys(text: CharSequence): List<Pair<TextRange, String>> {
        class Frame(val indent: Int, val path: String, val item: Boolean)
        val out = mutableListOf<Pair<TextRange, String>>()
        val stack = ArrayDeque<Frame>()
        val counters = HashMap<String, Int>()
        var blockIndent = -1   // inside a `|` / `>` scalar: lines indented deeper than this
        var offset = 0
        for (line in text.split('\n')) {
            val lineStart = offset
            offset += line.length + 1
            var col = line.indexOfFirst { it != ' ' }
            if (col < 0 || line[col] == '#') continue
            if (blockIndent >= 0) { if (col > blockIndent) continue else blockIndent = -1 }
            if (col == 0 && (line.startsWith("---") || line.startsWith("..."))) { stack.clear(); counters.clear(); continue }
            var rest = line.substring(col)
            // `- ` items (possibly nested: `- - a`)
            while (rest == "-" || rest.startsWith("- ")) {
                while (stack.isNotEmpty() && (stack.last().indent > col || (stack.last().indent == col && stack.last().item))) stack.removeLast()
                val parent = stack.lastOrNull()?.path ?: ""
                val index = counters.merge(parent, 1, Int::plus)!! - 1
                stack.addLast(Frame(col, "$parent/$index", true))
                val skip = rest.drop(1).indexOfFirst { it != ' ' }.let { if (it < 0) rest.length else it + 1 }
                col += skip
                rest = rest.substring(minOf(skip, rest.length))
            }
            if (rest.isEmpty() || rest.startsWith("#")) continue
            val m = KEY.find(rest) ?: continue
            val raw = m.groups[1]!!
            while (stack.isNotEmpty() && stack.last().indent >= col && !(stack.last().item && stack.last().indent < col)) stack.removeLast()
            val name = when {
                raw.value.startsWith("\"") -> raw.value.removeSurrounding("\"").replace("\\\"", "\"")
                raw.value.startsWith("'") -> raw.value.removeSurrounding("'").replace("''", "'")
                else -> raw.value
            }
            val quoted = raw.value.length != name.length && (raw.value.startsWith("\"") || raw.value.startsWith("'"))
            val nameStart = lineStart + col + raw.range.first + if (quoted) 1 else 0
            val path = (stack.lastOrNull()?.path ?: "") + "/" + escape(name)
            out += TextRange(nameStart, nameStart + name.length) to path
            val value = rest.substring(m.range.last + 1).trim()
            when {
                value.isEmpty() || value.startsWith("#") || Regex("""^(?:&\S+|!\S+)(?:\s+#.*)?$""").matches(value) -> stack.addLast(Frame(col, path, false))
                Regex("""^(?:&\S+\s+|!\S+\s+)*[|>][-+0-9]*(?:\s+#.*)?$""").matches(value) -> blockIndent = col
            }
        }
        return out
    }

    /** A YAML linker part: every key is a target, every `$ref` a reference. */
    fun resolveYaml(file: PsiFile, ref: Ref, linker: Linker): List<PsiElement>? {
        val key = ref.keys.singleOrNull()?.takeIf { it.startsWith("ref:") } ?: return null
        return resolve(file, key.removePrefix("ref:"), linker)
    }
}
