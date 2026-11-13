package me.kevinlot.lite.core

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * How a language recognises "a project" on disk. The same rule is used by every language:
 * nearest [rootManifests] (returned at once), else nearest [moduleManifests], else the highest folder
 * with `src/` or `.git` below the content root, else the content root itself (a project opened directly).
 * Never above the attached content root, so sibling projects of a workspace stay apart.
 */
data class ProjectLayout(
    val rootManifests: List<String>,
    val moduleManifests: List<String> = emptyList(),
    /** Build outputs and caches inside a project (may hold copies of sources): ignored by lookups. */
    val outputDirs: Set<String>,
) {
    fun rootOf(project: Project, file: VirtualFile?): String? {
        file ?: return null
        if (file.fileSystem is JarFileSystem) return null
        val bound = ProjectFileIndex.getInstance(project).getContentRootForFile(file)?.path
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        var module: String? = null
        var marked: String? = null
        while (dir != null && (bound == null || dir.path == bound || dir.path.startsWith("$bound/"))) {
            if (rootManifests.any { dir!!.findChild(it) != null }) return dir.path
            if (module == null && moduleManifests.any { dir!!.findChild(it) != null }) module = dir.path
            if (dir.path == bound) break
            if (MARKERS.any { dir!!.findChild(it)?.isDirectory == true }) marked = dir.path
            dir = dir.parent
        }
        return module ?: marked ?: bound
    }

    /**
     * Lookup scope of [e]'s project (cached on its file; the provider only captures the file).
     * [oracle] adds what the language itself treats as libraries (e.g. Rust's std and Cargo crates).
     */
    fun scopeOf(e: PsiElement, key: Key<CachedValue<GlobalSearchScope>>, oracle: LibraryOracle? = null): GlobalSearchScope {
        val file = e.containingFile?.originalFile ?: return GlobalSearchScope.allScope(e.project)
        return CachedValuesManager.getCachedValue(file, key) {
            val project = file.project
            val extra: (VirtualFile) -> Boolean = if (oracle == null) { _ -> false } else { vf -> oracle.isLibraryFile(project, vf) }
            val deps = listOfNotNull(PsiModificationTracker.MODIFICATION_COUNT, oracle?.tracker(file)).toTypedArray()
            CachedValueProvider.Result.create(LiteProjectScope.create(file.project, this, file.virtualFile, extra), *deps)
        }
    }

    private companion object {
        val MARKERS = listOf("src", ".git")
    }
}

/** Files of one project (minus its output dirs) plus every library (JDK, jars, std, venv…). */
class LiteProjectScope private constructor(
    project: Project, private val root: String?, private val ownOutput: String?, private val outputs: Set<String>,
    private val extraLibrary: (VirtualFile) -> Boolean,
) : GlobalSearchScope(project) {
    private val index = ProjectFileIndex.getInstance(project)

    override fun contains(file: VirtualFile): Boolean {
        if (file.fileSystem is JarFileSystem || index.isInLibrary(file) || extraLibrary(file)) return true
        val base = root ?: return true
        val path = file.path
        if (path != base && !path.startsWith("$base/")) return false
        // A file opened inside an output dir still sees its siblings there.
        if (ownOutput != null && path.startsWith("$ownOutput/")) return true
        return path.substring(base.length).split('/').none { it in outputs }
    }

    override fun isSearchInModuleContent(aModule: Module) = true
    override fun isSearchInLibraries() = true
    override fun equals(other: Any?) = other is LiteProjectScope && other.root == root && other.ownOutput == ownOutput && other.project == project
    override fun hashCode() = root.hashCode() * 31 + ownOutput.hashCode()
    override fun toString() = "Lite project ${root ?: "<whole project>"}"

    companion object {
        fun create(project: Project, layout: ProjectLayout, file: VirtualFile?, extraLibrary: (VirtualFile) -> Boolean = { false }): LiteProjectScope {
            val root = layout.rootOf(project, file)
            val path = file?.path
            val ownOutput = if (root != null && path != null && path.startsWith("$root/")) {
                val segments = path.substring(root.length + 1).split('/')
                val i = segments.indexOfFirst { it in layout.outputDirs }
                if (i >= 0 && i < segments.size - 1) root + "/" + segments.take(i + 1).joinToString("/") else null
            } else null
            return LiteProjectScope(project, root, ownOutput, layout.outputDirs, extraLibrary)
        }
    }
}
