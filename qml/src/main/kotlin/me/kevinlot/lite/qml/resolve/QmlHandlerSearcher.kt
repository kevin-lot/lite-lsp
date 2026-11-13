package me.kevinlot.lite.qml.resolve

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import me.kevinlot.lite.qml.QmlFileType
import me.kevinlot.lite.qml.psi.QmlNamed
import me.kevinlot.lite.qml.parser.QmlElementTypes as Q

/**
 * A property `count` is also used as `onCountChanged:` and a signal `moved` as `onMoved:`: words the default
 * search ("count", "moved") does not see. Searched in QML files of the requested scope.
 */
class QmlHandlerSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = params.elementToSearch as? QmlNamed ?: return
        val words = ReadAction.compute<List<String>, RuntimeException> {
            val cap = target.name?.replaceFirstChar { it.uppercase() } ?: return@compute emptyList()
            when (target.type) {
                Q.PROPERTY -> listOf("on${cap}Changed")
                Q.SIGNAL -> listOf("on$cap")
                else -> emptyList()
            }
        }
        val scope = when (val s = params.effectiveSearchScope) {
            is GlobalSearchScope -> GlobalSearchScope.getScopeRestrictedByFileTypes(s, QmlFileType)
            else -> s
        }
        words.forEach { params.optimizer.searchWord(it, scope, UsageSearchContext.IN_CODE, true, target) }
    }
}
