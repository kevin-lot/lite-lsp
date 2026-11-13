package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import me.kevinlot.lite.kotlin.KtFileType

/**
 * Kotlin reads Java accessors as properties (`p.name` for `getName()`/`isName()`/`setName()`), so the
 * default word search ("getName") misses those usages. This adds a search for the property name in
 * Kotlin files, restricted to the scope of the request (the one picked in the Find Usages dialog).
 */
class KtJavaPropertySearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = params.elementToSearch
        val name = when (target) {
            is PsiMethod -> propertyName(target) ?: return
            is PsiField -> return // field name == property name: the default search already finds it
            else -> return
        }
        val scope = when (val s = params.effectiveSearchScope) {
            is GlobalSearchScope -> GlobalSearchScope.getScopeRestrictedByFileTypes(s, KtFileType)
            else -> s
        }
        params.optimizer.searchWord(name, scope, UsageSearchContext.IN_CODE, true, target)
    }

    /** `getName` -> `name`, `isActive` -> `isActive`, `setName` -> `name` (Kotlin synthetic property). */
    private fun propertyName(m: PsiMethod): String? {
        val n = m.name
        val params = m.parameterList.parametersCount
        return when {
            n.startsWith("get") && n.length > 3 && n[3].isUpperCase() && params == 0 -> n.substring(3).replaceFirstChar { it.lowercase() }
            n.startsWith("is") && n.length > 2 && n[2].isUpperCase() && params == 0 -> n
            n.startsWith("set") && n.length > 3 && n[3].isUpperCase() && params == 1 -> n.substring(3).replaceFirstChar { it.lowercase() }
            else -> null
        }
    }
}
