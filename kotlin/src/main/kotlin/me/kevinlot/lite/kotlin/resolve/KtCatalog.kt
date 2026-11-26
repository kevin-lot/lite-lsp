package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.Processor
import me.kevinlot.lite.kotlin.KtFile
import me.kevinlot.lite.kotlin.KtFileType
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression

/**
 * The accessors Gradle generates for `gradle/libs.versions.toml`, in a build script: `libs.foo.bar` is the library
 * `foo-bar` (also written `foo_bar` or `foo.bar`), `libs.versions.x`, `libs.plugins.x` and `libs.bundles.x` the others.
 */
object KtCatalog {
    private const val FILE = "libs.versions.toml"
    private val ACCESSOR = Regex("""libs(?:\.\w+)*""")
    private val ENTRIES = Key.create<CachedValue<Map<List<String>, List<Int>>>>("lite.kt.catalog")
    private val LINE = Regex("""(?m)^[ \t]*(?:\[(\w+)(?:\.("[^"\n]+"|[\w-]+))?[.\]]|("[^"\n]+"|[\w-]+)[ \t]*[.=])""")
    private val SECTIONS = mapOf("bundles" to listOf("bundles"), "libraries" to emptyList(), "plugins" to listOf("plugins"), "versions" to listOf("versions"))
    private val SEPARATOR = Regex("[-_.]")

    fun isEntry(element: PsiElement?): Boolean = element?.containingFile?.name == FILE

    /** The entries [ref] names, or null when it is no accessor: an inner segment (`libs.foo`) names every entry under it. */
    fun resolve(ref: KtNameReferenceExpression, name: String): List<PsiElement>? {
        val path = path(ref, name) ?: return null
        val file = catalog(ref) ?: return null
        val entries = entries(file)
        val offsets = entries[path] ?: entries.filterKeys { it.size > path.size && it.subList(0, path.size) == path }.values.flatten()
            .takeIf { path !in SECTIONS.values }.orEmpty()
        return offsets.mapNotNull { offset -> file.findElementAt(offset)?.let { leaf -> leaf.parent.takeIf { it !is PsiFile } ?: leaf } }.ifEmpty { null }
    }

    /** The last segment of the accessor once its entry is named [newName], or null when the rename moves it elsewhere. */
    fun renamed(ref: KtNameReferenceExpression, newName: String): String? {
        val path = path(ref, ref.referencedName.orEmpty()) ?: return null
        val alias = newName.split(SEPARATOR)
        return alias.last().takeIf { path.takeLast(alias.size).dropLast(1) == alias.dropLast(1) }
    }

    /** Accessor path -> where the entries of that path start (several when two aliases differ by their separators only). */
    internal fun entries(file: PsiFile): Map<List<String>, List<Int>> = CachedValuesManager.getCachedValue(file, ENTRIES) {
        val out = LinkedHashMap<List<String>, MutableList<Int>>()
        var section: List<String>? = null
        for (line in LINE.findAll(file.text)) {
            val table = line.groups[1]
            if (table != null) section = SECTIONS[table.value]
            val key = (line.groups[2] ?: line.groups[3].takeIf { table == null }) ?: continue
            val quoted = 1.takeIf { key.value.startsWith('"') } ?: 0
            section?.let { out.getOrPut(it + key.value.trim('"').split(SEPARATOR)) { mutableListOf() } += key.range.first + quoted }
        }
        CachedValueProvider.Result.create(out, file)
    }

    private fun path(ref: KtNameReferenceExpression, name: String): List<String>? {
        if ((ref.containingFile as? KtFile)?.isScript != true) return null
        val receiver = KtResolver.receiverOf(ref)?.text?.filterNot(Char::isWhitespace) ?: return null
        return (receiver.split('.').drop(1) + name).takeIf { ACCESSOR.matches(receiver) }
    }

    private fun catalog(ref: PsiElement): PsiFile? {
        val dir = ref.containingFile?.originalFile?.virtualFile?.parent ?: return null
        val file = generateSequence(dir) { it.parent }.take(6).firstNotNullOfOrNull { it.findFileByRelativePath("gradle/$FILE") } ?: return null
        return PsiManager.getInstance(ref.project).findFile(file)
    }
}

/** A catalog entry is used under the last segment of its alias (`foo-bar` → `libs.foo.bar`), which a search for its name misses. */
class KtCatalogSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = params.elementToSearch.takeIf(KtCatalog::isEntry) as? PsiNamedElement ?: return
        val segment = target.name?.split(Regex("[-_.]"))?.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val scope = when (val s = params.effectiveSearchScope) {
            is GlobalSearchScope -> GlobalSearchScope.getScopeRestrictedByFileTypes(s, KtFileType)
            else -> s
        }
        params.optimizer.searchWord(segment, scope, UsageSearchContext.IN_CODE, true, target)
    }
}
