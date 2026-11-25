package me.kevinlot.lite.rust.resolve

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.rust.RsFile
import me.kevinlot.lite.rust.cargo.CargoProjects
import me.kevinlot.lite.rust.cargo.CrateInfo
import me.kevinlot.lite.rust.psi.RsLitExpr
import me.kevinlot.lite.rust.psi.RsMetaItem
import me.kevinlot.lite.rust.psi.RsModDeclItem
import me.kevinlot.lite.rust.psi.RsModItem
import me.kevinlot.lite.rust.psi.RsOuterAttr

/**
 * File <-> module mapping (`mod foo;` -> `foo.rs` / `foo/mod.rs`) and crate roots.
 * A module is either an [RsFile] or an inline [RsModItem].
 */
object ModuleTree {
    private val MOD_RS_NAMES = setOf("mod.rs", "lib.rs", "main.rs")
    private val STD_CRATES = listOf("std", "core", "alloc", "proc_macro", "test")

    fun isModule(e: PsiElement): Boolean = e is RsFile || e is RsModItem

    /** Nearest enclosing module (the element itself if it is one). */
    fun containingMod(e: PsiElement): PsiElement =
        PsiTreeUtil.getParentOfType(e, RsModItem::class.java, false)
            ?.takeIf { PsiTreeUtil.isAncestor(it, e, false) }
            ?: e.containingFile

    fun parentOfMod(mod: PsiElement): PsiElement? = when (mod) {
        is RsModItem -> containingMod(mod.parent)
        is RsFile -> declOf(mod)?.let { containingMod(it) }
        else -> null
    }

    fun modName(mod: PsiElement): String? = when (mod) {
        is RsModItem -> mod.name
        is RsFile -> declOf(mod)?.name
        else -> null
    }

    fun crateRoot(e: PsiElement): RsFile? {
        var file = containingMod(e).containingFile as? RsFile ?: return null
        val seen = HashSet<RsFile>()
        while (seen.add(file)) {
            val decl = declOf(file) ?: return file
            file = decl.containingFile as? RsFile ?: return file
        }
        return file
    }

    fun resolveModDecl(decl: RsModDeclItem): RsFile? = cached(decl, MOD_DECL_KEY) {
        val name = decl.name ?: return@cached null
        val file = decl.containingFile.originalFile.virtualFile ?: return@cached null
        val target: VirtualFile? = pathAttr(decl)?.let { file.parent?.findFileByRelativePath(it) }
            ?: modDir(containingMod(decl))?.let { dir -> dir.findChild("$name.rs") ?: dir.findChild(name)?.findChild("mod.rs") }
        target?.let { PsiManager.getInstance(decl.project).findFile(it) as? RsFile }
    }

    /** The `mod x;` declaration that includes [file], or null for a crate root. */
    fun declOf(file: RsFile): RsModDeclItem? = cached(file, DECL_OF_KEY) {
        val vf = file.originalFile.virtualFile ?: return@cached null
        if (crateInfo(file) != null) return@cached null
        val (stem, base) = when {
            vf.name == "mod.rs" -> vf.parent?.name to vf.parent?.parent
            else -> vf.nameWithoutExtension to vf.parent
        }
        if (stem == null || base == null) return@cached null
        val candidates = buildList {
            MOD_RS_NAMES.forEach { base.findChild(it)?.let(::add) }
            base.parent?.findChild("${base.name}.rs")?.let(::add)
        }.filter { it != vf }
        val psiManager = PsiManager.getInstance(file.project)
        candidates.asSequence().mapNotNull { psiManager.findFile(it) as? RsFile }.flatMap { modDecls(it) }
            .firstOrNull { it.name == stem && resolveModDecl(it)?.originalFile?.virtualFile == vf }
    }

    private fun modDecls(file: RsFile): List<RsModDeclItem> =
        cached(file, MOD_DECLS_KEY) { PsiTreeUtil.findChildrenOfType(file, RsModDeclItem::class.java).toList() }

    /** Directory holding the files of [mod]'s child modules. */
    fun modDir(mod: PsiElement): VirtualFile? = when (mod) {
        is RsModItem -> modDir(containingMod(mod.parent))?.findChild(mod.name ?: return null)
        is RsFile -> {
            val vf = mod.originalFile.virtualFile
            when {
                vf == null -> null
                vf.name in MOD_RS_NAMES || declOf(mod) == null -> vf.parent
                else -> vf.parent?.findChild(vf.nameWithoutExtension)
            }
        }
        else -> null
    }

    /** Cargo crate whose root is [root], or a standard library crate. */
    fun crateInfo(root: RsFile): CrateInfo? {
        val path = root.originalFile.virtualFile?.url ?: return null
        val ws = CargoProjects.getInstance(root.project).workspace
        ws.byRoot[path]?.let { return it }
        val std = ws.stdlibDir ?: return null
        return STD_CRATES.firstOrNull { path == "$std/$it/src/lib.rs" }?.let { CrateInfo(it, path, emptyMap(), "2021", false) }
    }

    /**
     * Crate settings for [root]. A file outside every module tree (e.g. its `mod x;` is commented out)
     * borrows the dependencies of the member crate whose root sits in the same or a parent directory.
     */
    fun effectiveCrateInfo(root: RsFile): CrateInfo? {
        crateInfo(root)?.let { return it }
        val url = root.originalFile.virtualFile?.url ?: return null
        return CargoProjects.getInstance(root.project).workspace.crates
            .filter { it.isMember && url.startsWith(it.rootUrl.substringBeforeLast('/') + "/") }
            .maxByOrNull { it.rootUrl.substringBeforeLast('/').length }
    }

    /** Crates nameable from [root] without `extern crate`: dependencies + std, core, alloc. */
    fun externPrelude(root: RsFile): Map<String, RsFile> = cached(root, EXTERN_PRELUDE_KEY) {
        val ws = CargoProjects.getInstance(root.project).workspace
        val deps = effectiveCrateInfo(root)?.dependencies.orEmpty()
        val std = ws.stdlibDir?.let { dir -> STD_CRATES.take(3).associateWith { "$dir/$it/src/lib.rs" } }.orEmpty()
        val vfm = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
        val pm = PsiManager.getInstance(root.project)
        (std + deps).mapNotNull { (name, url) ->
            vfm.findFileByUrl(url)?.let { pm.findFile(it) as? RsFile }?.let { name to it }
        }.toMap()
    }

    fun edition(root: RsFile): String = effectiveCrateInfo(root)?.edition ?: "2021"

    private fun pathAttr(decl: RsModDeclItem): String? =
        PsiTreeUtil.getChildrenOfTypeAsList(decl, RsOuterAttr::class.java).firstNotNullOfOrNull { attr ->
            val meta = PsiTreeUtil.findChildOfType(attr, RsMetaItem::class.java) ?: return@firstNotNullOfOrNull null
            if (meta.firstChild?.text != "path") return@firstNotNullOfOrNull null
            PsiTreeUtil.findChildOfType(meta, RsLitExpr::class.java)?.text?.trim('"')
        }

    private val MOD_DECL_KEY = Key.create<com.intellij.psi.util.CachedValue<RsFile?>>("lite.rs.modDecl")
    private val DECL_OF_KEY = Key.create<com.intellij.psi.util.CachedValue<RsModDeclItem?>>("lite.rs.declOf")
    private val MOD_DECLS_KEY = Key.create<com.intellij.psi.util.CachedValue<List<RsModDeclItem>>>("lite.rs.modDecls")
    private val EXTERN_PRELUDE_KEY = Key.create<com.intellij.psi.util.CachedValue<Map<String, RsFile>>>("lite.rs.externPrelude")

    internal fun <T> cached(e: PsiElement, key: Key<com.intellij.psi.util.CachedValue<T>>, compute: () -> T): T =
        me.kevinlot.lite.core.LiteCache.cached(e, key, RsLibraryOracle, compute)

    /** Like [cached], but a value on library code is also recomputed after an edit: it may depend on project code. */
    internal fun <T> cachedUntilEdit(e: PsiElement, key: Key<com.intellij.psi.util.CachedValue<T>>, compute: () -> T): T =
        com.intellij.psi.util.CachedValuesManager.getCachedValue(e, key) {
            val deps = arrayOf<Any>(com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT, *dependencies(e))
            com.intellij.psi.util.CachedValueProvider.Result.create(compute(), *deps)
        }

    fun dependencies(e: PsiElement): Array<Any> = me.kevinlot.lite.core.LiteCache.dependencies(e, RsLibraryOracle)

    internal fun <T> cachedNonRecursive(e: PsiElement, key: Key<com.intellij.psi.util.CachedValue<T>>, onCycle: T, compute: () -> T): T =
        me.kevinlot.lite.core.LiteCache.cachedNonRecursive(e, key, RsLibraryOracle, onCycle, compute)

    /** Standard library or a Cargo dependency (read-only for the session). */
    fun isLibrary(e: PsiElement): Boolean {
        val vf = e.containingFile?.originalFile?.virtualFile ?: return false
        return isLibraryUrl(e.project, vf.url)
    }

    fun isLibraryUrl(project: com.intellij.openapi.project.Project, url: String): Boolean =
        CargoProjects.getInstance(project).workspace.libraryPrefixes.any { url.startsWith(it) }
}

/** Std and Cargo dependencies are read-only for the session; the crate graph invalidates them. */
object RsLibraryOracle : me.kevinlot.lite.core.LibraryOracle {
    override fun isLibrary(e: PsiElement): Boolean = ModuleTree.isLibrary(e)
    override fun tracker(e: PsiElement): com.intellij.openapi.util.ModificationTracker =
        CargoProjects.getInstance(e.project).modificationTracker

    override fun isLibraryFile(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile): Boolean =
        ModuleTree.isLibraryUrl(project, file.url)
}
