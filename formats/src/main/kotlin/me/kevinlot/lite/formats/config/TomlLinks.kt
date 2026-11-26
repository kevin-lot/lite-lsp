package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.PathLink
import me.kevinlot.lite.formats.Ref

/**
 * The names of the TOML files that have some: a `Cargo.toml` (workspace dependencies and package keys, features, paths)
 * and a Gradle version catalog (`*.versions.toml`: `version.ref`, the libraries of a bundle).
 */
internal object TomlLinks : Linker() {
    private val DEPENDENCIES = setOf("dependencies", "dev-dependencies", "build-dependencies")
    private val FILES = setOf("build", "license-file", "readme")
    private val GLOB = Regex("""[*?\[{]""")
    private val MEMBERS = setOf("default-members", "exclude", "members")
    private val SECTIONS = mapOf("bundles" to "bundle", "libraries" to "library", "plugins" to "plugin", "versions" to "version")
    private val WORKSPACE = Regex("""(?m)^\s*\[workspace[.\]]""")

    override fun links(text: CharSequence, fileName: String): Links = when {
        fileName == "Cargo.toml" -> cargo(TomlReader(text).entries())
        fileName.endsWith(".versions.toml") -> catalog(TomlReader(text).entries())
        else -> Links()
    }

    /** A crate shares the names of its workspace: the nearest `Cargo.toml` above it with a `[workspace]` table. */
    override fun scope(file: PsiFile): List<PsiFile> {
        if (file.name != "Cargo.toml") return listOf(file)
        val manager = PsiManager.getInstance(file.project)
        val root = generateSequence(file.originalFile.virtualFile?.parent?.parent) { it.parent }.take(8)
            .mapNotNull { it.findChild("Cargo.toml")?.let(manager::findFile) }.firstOrNull { WORKSPACE.containsMatchIn(it.text) }
        return listOfNotNull(file, root)
    }

    private fun cargo(entries: List<TomlEntry>): Links {
        val decls = LinkedHashMap<TextRange, Decl>()
        val refs = LinkedHashMap<TextRange, Ref>()
        val paths = mutableListOf<PathLink>()
        val inherited = entries.filter { it.names.lastOrNull() == "workspace" && (it.value as? TomlValue.Scalar)?.text == "true" }
            .mapNotNull { it.path.dropLast(1).lastOrNull()?.range }.toSet()
        for (entry in entries) {
            val names = entry.names
            val dependency = dependencyAt(names)
            when {
                dependency == null -> Unit
                names.first() == "workspace" -> decls.declare(entry.path[dependency], "wsdep")
                entry.path[dependency].range in inherited -> refs.refer(entry.path[dependency], "wsdep")
                else -> decls.declare(entry.path[dependency], "dep")
            }
            if (names.size >= 3 && names[0] == "workspace" && names[1] == "package") decls.declare(entry.path[2], "wspkg")
            if (names.size == 3 && names[0] == "package" && entry.path[1].range in inherited) refs.refer(entry.path[1], "wspkg")
            if (names.size == 2 && names[0] == "features") {
                decls.declare(entry.path[1], "feature")
                texts(entry.value).forEach { item -> feature(item).let { refs.putIfAbsent(it.range, it) } }
            }
            val file = names.last() == "path" || (names.size == 2 && names[0] == "package" && names[1] in FILES)
            val member = names.size == 2 && names[0] == "workspace" && names[1] in MEMBERS
            if (!file && !member) continue
            paths += texts(entry.value).filter { it.text.isNotEmpty() && !GLOB.containsMatchIn(it.text) }.map { PathLink(it.range, it.text) }
        }
        return Links(decls.values.toList(), refs.values.toList(), paths)
    }

    /** The index of the dependency's name in a path: `dependencies.x`, `target.'cfg(unix)'.dependencies.x`, `workspace.dependencies.x`. */
    private fun dependencyAt(names: List<String>): Int? {
        val table = when {
            names.first() == "target" -> 2
            names.first() == "workspace" -> 1
            else -> 0
        }
        return (table + 1).takeIf { names.getOrNull(table) in DEPENDENCIES && names.size > it }
    }

    /** `a` is a feature or an optional dependency, `dep:a` a dependency, `a/f` and `a?/f` the feature `f` of the dependency `a`. */
    private fun feature(item: TomlValue.Text): Ref {
        val explicit = item.text.startsWith("dep:")
        val start = item.range.startOffset + (4.takeIf { explicit } ?: 0)
        val name = item.text.substring(start - item.range.startOffset).substringBefore('/').removeSuffix("?")
        val keys = listOf("feature:$name", "dep:$name", "wsdep:$name").drop(1.takeIf { explicit || '/' in item.text } ?: 0)
        return Ref(TextRange(start, start + name.length), keys)
    }

    private fun catalog(entries: List<TomlEntry>): Links {
        val decls = LinkedHashMap<TextRange, Decl>()
        val refs = mutableListOf<Ref>()
        for (entry in entries) {
            val names = entry.names
            val kind = SECTIONS[names.first()] ?: continue
            if (names.size < 2) continue
            decls.declare(entry.path[1], kind)
            if (names.takeLast(2) == listOf("version", "ref")) refs += texts(entry.value).map { Ref(it.range, listOf("version:${it.text}")) }
            if (kind == "bundle") refs += texts(entry.value).map { Ref(it.range, listOf("library:${it.text}")) }
        }
        return Links(decls.values.toList(), refs)
    }

    private fun MutableMap<TextRange, Decl>.declare(key: TomlKey, kind: String) { putIfAbsent(key.range, Decl(key.range, "$kind:${key.name}")) }

    private fun MutableMap<TextRange, Ref>.refer(key: TomlKey, kind: String) { putIfAbsent(key.range, Ref(key.range, listOf("$kind:${key.name}"))) }

    private fun texts(value: TomlValue): List<TomlValue.Text> = when (value) {
        is TomlValue.Array -> value.items.flatMap(::texts)
        is TomlValue.Text -> listOf(value)
        else -> emptyList()
    }
}
