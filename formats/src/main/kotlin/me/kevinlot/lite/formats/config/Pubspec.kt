package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.PathLink

/** `pubspec.yaml` and `pubspec_overrides.yaml` (Dart, Flutter): the folders and files their values name. */
object Pubspec {
    private val ASSET = Regex("""^\s*(?:-\s*)?(?:asset|path):\s*(\S.*)$""")
    private val DEPENDENCIES = setOf("dependencies", "dependency_overrides", "dev_dependencies")
    private val FILES = setOf("pubspec.yaml", "pubspec_overrides.yaml")
    private val GLOB = Regex("""[*?\[{]""")
    private val ITEM = Regex("""^\s*-\s*(\S.*)$""")
    private val KEY = Regex("""^(\s*)([\w-]+):\s*$""")
    private val PATH = Regex("""^\s*path:\s*(\S.*)$""")
    private val TOP = Regex("""^([\w-]+):""")
    private val VALUE = Regex("""^(['"]?)([^'"#]*?)\1\s*(?:#.*)?$""")

    /**
     * `path:` of a dependency, the members of a `workspace:`, and under `flutter:` the items of `assets:` and the `asset:` of a font.
     * Null for any other file.
     */
    fun links(text: CharSequence, fileName: String): Links? {
        if (fileName !in FILES) return null
        val paths = ArrayList<PathLink>()
        var section = ""
        var list = ""
        var offset = 0
        for (line in text.lineSequence()) {
            TOP.find(line)?.let { section = it.groupValues[1]; list = "" }
            KEY.find(line)?.takeIf { it.groupValues[1].isNotEmpty() }?.let { list = it.groupValues[2] }
            val value = when {
                section in DEPENDENCIES -> PATH.find(line)
                section == "workspace" -> ITEM.find(line)
                section == "flutter" -> ASSET.find(line) ?: ITEM.find(line)?.takeIf { list == "assets" }
                else -> null
            }?.groups?.get(1)
            value?.let { path(it, offset) }?.let(paths::add)
            offset += line.length + 1
        }
        return Links(paths = paths)
    }

    private fun path(value: MatchGroup, lineStart: Int): PathLink? {
        val written = VALUE.find(value.value)?.groups?.get(2)?.takeIf { it.value.isNotBlank() && !GLOB.containsMatchIn(it.value) } ?: return null
        val start = lineStart + value.range.first + written.range.first
        return PathLink(TextRange(start, start + written.value.length), written.value.trimEnd('/'))
    }
}
