package me.kevinlot.lite.dart

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

/** `import`, `export` or `part`, as written: read from the text, so that following a chain of exports parses nothing. */
class DartDirective(val kind: String, val uri: String, val prefix: String?, private val show: Set<String>, private val hide: Set<String>) {
    fun allows(name: String): Boolean = (show.isEmpty() || name in show) && name !in hide
    val isRestricted: Boolean get() = show.isNotEmpty() || hide.isNotEmpty()
}

/** Libraries: the files a file imports, what they export, and where `package:` and `dart:` URIs lead. */
object DartLibraries {
    private val COMBINATOR = Regex("""\b(show|hide)\s+([\w\s,$]+?)(?=\bshow\b|\bhide\b|$)""")
    private val DIRECTIVE = Regex("""(?m)^[ \t]*(import|export|part)\s+(?:augment\s+)?(['"])([^'"\n]+)\2([^;]*);""")
    private val DIRECTIVES = Key.create<CachedValue<List<DartDirective>>>("lite.dart.directives")
    private val EXPORTS = Key.create<CachedValue<Map<DartFile, List<List<DartDirective>>>>>("lite.dart.exports")
    private const val MAX_CHAINS = 4
    private val LIBRARY = Key.create<CachedValue<List<DartFile>>>("lite.dart.library")
    private val PART_OF = Regex("""(?m)^[ \t]*part\s+of\s+(?:(['"])([^'"\n]+)\1|[\w.]+)\s*;""")
    private val PREFIX = Regex("""\bas\s+([\w$]+)""")
    private val TOP_LEVEL = Key.create<CachedValue<Map<String, List<DartName>>>>("lite.dart.top")

    private val CONTAINERS = setOf(
        T.CLASS_DEFINITION, T.ENUM_DEFINITION, T.EXTENSION_TYPE_DECLARATION, T.FUNCTION_DECLARATION_WITH_BODY_OR_NATIVE, T.FUNCTION_TYPE_ALIAS,
        T.GETTER_DECLARATION, T.MIXIN_DECLARATION, T.SETTER_DECLARATION,
    )

    fun directives(file: PsiFile): List<DartDirective> = CachedValuesManager.getCachedValue(file, DIRECTIVES) {
        val found = DIRECTIVE.findAll(file.text).map { m ->
            val tail = m.groupValues[4]
            val names = COMBINATOR.findAll(tail).groupBy({ it.groupValues[1] }, { it.groupValues[2].split(',').map(String::trim) })
            DartDirective(m.groupValues[1], m.groupValues[3], PREFIX.find(tail.substringBefore("show").substringBefore("hide"))?.groupValues?.get(1),
                names["show"].orEmpty().flatten().toSet(), names["hide"].orEmpty().flatten().toSet())
        }.toList()
        CachedValueProvider.Result.create(found, file)
    }

    /** The file a URI of [context] names: relative, `package:` or `dart:`. */
    fun resolveUri(context: PsiFile, uri: String): DartFile? {
        val file = context.originalFile.virtualFile ?: return null
        val target = when {
            uri.startsWith("dart:") -> DartPackages.sdkLibrary(context, uri.removePrefix("dart:"))
            uri.startsWith("package:") -> DartPackages.packageFile(context, uri.removePrefix("package:"))
            else -> file.parent?.findFileByRelativePath(uri)
        } ?: return null
        return PsiManager.getInstance(context.project).findFile(target) as? DartFile
    }

    /** The files of the library [file] is part of: the library file first, then its parts. */
    fun library(file: DartFile): List<DartFile> = CachedValuesManager.getCachedValue(file, LIBRARY) {
        val uri = PART_OF.find(file.text)?.groupValues?.get(2)
        val owner = uri?.let { it.takeIf(String::isNotEmpty)?.let { written -> resolveUri(file, written) } ?: siblingOwner(file) } ?: file
        val parts = directives(owner).filter { it.kind == "part" }.mapNotNull { resolveUri(owner, it.uri) }
        CachedValueProvider.Result.create((listOf(owner) + parts + file).distinct(), PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** `part of name;` does not say where the library is: it is the file of the folder (or the one above) naming this one as a part. */
    private fun siblingOwner(file: DartFile): DartFile? {
        val folders = listOfNotNull(file.originalFile.containingDirectory, file.originalFile.containingDirectory?.parentDirectory)
        return folders.flatMap { it.files.filterIsInstance<DartFile>() }
            .firstOrNull { candidate -> directives(candidate).any { it.kind == "part" && resolveUri(candidate, it.uri) == file.originalFile } }
    }

    /** The declarations at the top of [file], by name. */
    fun topLevel(file: DartFile): Map<String, List<DartName>> = CachedValuesManager.getCachedValue(file, TOP_LEVEL) {
        val names = file.children.flatMap { declaration ->
            when (declaration.elementType) {
                T.VAR_DECLARATION_LIST -> DartResolver.variables(declaration)
                in CONTAINERS -> listOfNotNull(declaration.child(T.COMPONENT_NAME) as? DartName)
                else -> emptyList()
            }
        }
        CachedValueProvider.Result.create(names.groupBy { it.name }, file)
    }

    /** What the library of [file] declares as [name]. A file whose text lacks the name is not parsed. */
    fun declared(file: DartFile, name: String): List<DartName> = library(file).filter { name in it.text }.flatMap { topLevel(it)[name].orEmpty() }

    /**
     * The libraries [file] gives to who imports it: itself and what it exports, each with the chains of exports leading to it.
     * A library is often reached twice (`export 'a.dart' show A;` and, further, a plain `export 'a.dart';`): a name passes when one chain lets it.
     */
    private fun exports(file: DartFile): Map<DartFile, List<List<DartDirective>>> = CachedValuesManager.getCachedValue(file, EXPORTS) {
        val seen = LinkedHashMap<DartFile, MutableList<List<DartDirective>>>()
        fun visit(library: DartFile, chain: List<DartDirective>) {
            val chains = seen.getOrPut(library) { ArrayList() }
            // an open chain makes the others useless; a few restricted ones are kept, which also ends a cycle of exports
            if (chains.any { it.isEmpty() } || chains.size >= MAX_CHAINS) return
            chains += chain
            directives(library).filter { it.kind == "export" }.forEach { export ->
                resolveUri(library, export.uri)?.let { target -> visit(target, chain + listOfNotNull(export.takeIf { it.isRestricted })) }
            }
        }
        visit(file, emptyList())
        CachedValueProvider.Result.create(seen, PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }

    /** [name] as an import of [file] sees it: public, declared by the library or one it exports. */
    fun exported(file: DartFile, name: String): List<DartName> {
        if (name.startsWith("_")) return emptyList()
        return exports(file).filter { (_, chains) -> chains.any { chain -> chain.all { it.allows(name) } } }.keys.flatMap { declared(it, name) }.distinct()
    }

    /** [name] through the imports of the library of [file]: those under [prefix], or the ones without a prefix and `dart:core`. */
    fun imported(file: DartFile, name: String, prefix: String? = null): List<DartName> {
        val owner = library(file).first()
        val imports = directives(owner).filter { it.kind == "import" && it.prefix == prefix && it.allows(name) }.mapNotNull { resolveUri(owner, it.uri) }
        val core = listOfNotNull(resolveUri(owner, "dart:core").takeIf { prefix == null })
        return (imports + core).distinct().flatMap { exported(it, name) }.distinct()
    }

    /** The `as x` names of the imports of [file]'s library. */
    fun prefixes(file: DartFile, name: String): List<PsiElement> =
        library(file).first().children.filter { it.elementType == T.IMPORT_STATEMENT }.mapNotNull { it.child(T.COMPONENT_NAME) }.filter { it.text == name }
}
