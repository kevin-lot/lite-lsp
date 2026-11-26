package me.kevinlot.lite.dart

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

/**
 * Flutter localization: `l10n.helloWorld` is the message `helloWorld` of the `.arb` files, which the generated getter stands for.
 * A key and its getter match whatever their case and underscores: some generators turn `Button_Cancel` into `buttonCancel`.
 */
object DartLocalizations {
    private const val EXTENSION = "arb"
    private val FILES = Key.create<CachedValue<List<VirtualFile>>>("lite.dart.arb.files")
    private val KEYS = Key.create<CachedValue<Map<String, List<Int>>>>("lite.dart.arb.keys")
    private const val SUFFIX = "Localizations"
    private val RECEIVER = Regex("""(?i)l10n|localizations|\bS\.(?:of|current)\b""")
    private val TOKEN = Regex(""""((?:[^"\\\n]|\\.)*)"(\s*:)?|[{}\[\]]""")

    fun isEntry(element: PsiElement?): Boolean = element?.containingFile?.originalFile?.virtualFile?.extension == EXTENSION

    /** The name of the getter generated for [key]. */
    fun getter(key: String): String {
        val words = key.split('_').filter { it.isNotEmpty() }
        if (words.isEmpty()) return key
        return words.first().replaceFirstChar(Char::lowercase) + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }

    /**
     * The messages [ref] names, instead of [found]: when it is a member of a localizations class (`AppLocalizations`, `S`), or,
     * the generated code being absent, of something named like one (`context.l10n.title`).
     */
    fun entries(ref: DartRef, found: List<PsiElement>): List<PsiElement> {
        val qualifier = ref.parent.takeIf { it.isA(T.REFERENCE_EXPRESSION) && it.firstChild != ref }?.firstChild ?: return found
        val owner = found.firstNotNullOfOrNull { target -> PsiTreeUtil.findFirstParent(target) { it.elementType in DartResolver.CLASSES } }
        val names = owner?.let { listOf(DartType(it)) + DartTypes.supertypes(DartType(it)) }.orEmpty().mapNotNull { it.name }
        val bundle = names.firstOrNull { SUFFIX in it }?.substringBefore(SUFFIX)?.lowercase()
        val localized = bundle != null || "S" in names || (found.isEmpty() && RECEIVER.containsMatchIn(qualifier.text))
        if (!localized || DumbService.isDumb(ref.project)) return found
        val anchor = (owner ?: ref).containingFile?.originalFile?.virtualFile ?: return found
        return messages(ref.project, anchor, ref.referencedName, bundle?.takeIf { it.isNotEmpty() }).ifEmpty { found }
    }

    /**
     * The entries named [name] in the `.arb` files of the package of [anchor], else of the project. Several bundles may hold
     * the key: the ones named like the class win (`app_en.arb` or a folder `app` for `AppLocalizations`).
     */
    private fun messages(project: Project, anchor: VirtualFile, name: String, bundle: String?): List<PsiElement> {
        val root = packageOf(anchor)
        val all = files(project)
        val manager = PsiManager.getInstance(project)
        val hits = all.filter { root != null && packageOf(it) == root }.ifEmpty { all }.mapNotNull(manager::findFile).flatMap { file ->
            keys(file)[normalized(name)].orEmpty().mapNotNull { file.findElementAt(it) }.map { leaf -> leaf.parent.takeIf { it !is PsiFile } ?: leaf }
        }
        return hits.filter { bundle != null && isNamed(it.containingFile.virtualFile, bundle) }.ifEmpty { hits }
    }

    private fun isNamed(file: VirtualFile, bundle: String): Boolean = file.name.startsWith("${bundle}_") || bundle in file.path.split('/')

    private fun packageOf(file: VirtualFile): VirtualFile? = generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild("pubspec.yaml") != null }

    private fun files(project: Project): List<VirtualFile> = CachedValuesManager.getManager(project).getCachedValue(project, FILES, {
        val found = FilenameIndex.getAllFilesByExt(project, EXTENSION, GlobalSearchScope.projectScope(project))
        CachedValueProvider.Result.create(found.sortedBy { it.path }, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }, false)

    /** Normalized key -> where the top-level keys of that name start; `@key` and `@@locale` are no messages. */
    private fun keys(file: PsiFile): Map<String, List<Int>> = CachedValuesManager.getCachedValue(file, KEYS) {
        val tokens = TOKEN.findAll(file.text).toList()
        val depths = tokens.runningFold(0) { depth, token ->
            when (token.value) {
                "{", "[" -> depth + 1
                "}", "]" -> depth - 1
                else -> depth
            }
        }
        val messages = tokens.zip(depths).filter { (token, depth) -> depth == 1 && token.groups[2] != null && !token.groupValues[1].startsWith("@") }
        CachedValueProvider.Result.create(messages.groupBy({ normalized(it.first.groupValues[1]) }, { it.first.range.first + 1 }), file)
    }

    private fun normalized(name: String): String = name.replace("_", "").lowercase()
}

/** A message is read through its getter (`Button_Cancel` → `buttonCancel`), which a search for its key misses. */
class DartLocalizationSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = params.elementToSearch.takeIf(DartLocalizations::isEntry) as? PsiNamedElement ?: return
        val getter = target.name?.let(DartLocalizations::getter)?.takeIf { it != target.name } ?: return
        val scope = when (val s = params.effectiveSearchScope) {
            is GlobalSearchScope -> GlobalSearchScope.getScopeRestrictedByFileTypes(s, DartFileType)
            else -> s
        }
        params.optimizer.searchWord(getter, scope, UsageSearchContext.IN_CODE, true, target)
    }
}
