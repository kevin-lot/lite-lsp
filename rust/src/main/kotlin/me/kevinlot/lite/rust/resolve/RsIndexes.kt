package me.kevinlot.lite.rust.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import me.kevinlot.lite.rust.RsFile
import me.kevinlot.lite.rust.RsFileType
import me.kevinlot.lite.rust.psi.*
import me.kevinlot.lite.rust.psi.ext.RsNamedElement

abstract class RsKeysIndex : me.kevinlot.lite.core.LiteKeysIndex<RsFile>(RsFileType, RsFile::class.java)

/** Names of items declared anywhere in a file (for imports and name-based fallbacks). */
class RsItemNameIndex : RsKeysIndex() {
    override fun getName(): ID<String, Void> = KEY
    override fun keys(file: RsFile): Collection<String> = indexedItems(file).mapNotNull { it.name }.toSet()

    companion object {
        val KEY = ID.create<String, Void>("lite.rust.itemName")

        fun indexedItems(file: RsFile): List<RsNamedElement> =
            PsiTreeUtil.findChildrenOfAnyType(
                file, RsFunction::class.java, RsStructItem::class.java, RsEnumItem::class.java, RsTraitItem::class.java,
                RsTypeAlias::class.java, RsConstant::class.java, RsMacro::class.java, RsEnumVariant::class.java,
                RsModItem::class.java,
            ).filterIsInstance<RsNamedElement>()

        /** Items named [name] visible from [place]'s project (its files + libraries), see [me.kevinlot.lite.core.ProjectLayout]. */
        fun find(place: PsiElement, name: String): List<RsNamedElement> = find(place.project, name, me.kevinlot.lite.rust.cargo.CargoProjects.scopeOf(place))

        fun find(project: Project, name: String, scope: GlobalSearchScope): List<RsNamedElement> {
            val pm = PsiManager.getInstance(project)
            return FileBasedIndex.getInstance().getContainingFiles(KEY, name, scope).flatMap { vf ->
                (pm.findFile(vf) as? RsFile)?.let { f -> itemsByName(f)[name] }.orEmpty()
            }
        }

        private val ITEMS_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<Map<String, List<RsNamedElement>>>>("lite.rs.items")

        private fun itemsByName(file: RsFile): Map<String, List<RsNamedElement>> =
            ModuleTree.cached(file, ITEMS_KEY) { indexedItems(file).groupBy { it.name.orEmpty() } }
    }
}

/** Last segment of the self type of every `impl` block: `impl<T> Foo<T>` -> "Foo". */
class RsImplIndex : RsKeysIndex() {
    override fun getName(): ID<String, Void> = KEY
    override fun keys(file: RsFile): Collection<String> =
        PsiTreeUtil.findChildrenOfType(file, RsImplItem::class.java).mapNotNull { implSelfName(it) }.toSet()

    companion object {
        val KEY = ID.create<String, Void>("lite.rust.impl")

        fun implSelfName(impl: RsImplItem): String? = typeName(impl.typeReference)

        fun typeName(ty: RsTypeReference?): String? = when (ty) {
            is RsPathType -> ty.path.identifier?.text ?: ty.path.cself?.text
            is RsRefLikeType -> typeName(ty.typeReference)
            is RsArrayType -> "[]"
            is RsTupleType -> "()"
            is RsUnitType -> "()"
            else -> null
        }

        /** Impl blocks for [selfName]; [library] selects dependency/std files or project files. */
        fun find(project: Project, selfName: String, library: Boolean? = null): List<RsImplItem> {
            val pm = PsiManager.getInstance(project)
            val scope = GlobalSearchScope.allScope(project)
            return FileBasedIndex.getInstance().getContainingFiles(KEY, selfName, scope).flatMap { vf ->
                val f = pm.findFile(vf) as? RsFile ?: return@flatMap emptyList()
                if (library != null && ModuleTree.isLibrary(f) != library) return@flatMap emptyList()
                implsByName(f)[selfName].orEmpty()
            }
        }

        private val IMPLS_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<Map<String, List<RsImplItem>>>>("lite.rs.impls")

        private fun implsByName(file: RsFile): Map<String, List<RsImplItem>> = ModuleTree.cached(file, IMPLS_KEY) {
            PsiTreeUtil.findChildrenOfType(file, RsImplItem::class.java).groupBy { implSelfName(it).orEmpty() }
        }
    }
}
