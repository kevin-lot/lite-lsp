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
        val dirs = generateSequence(file.takeIf { it.isDirectory } ?: file.parent) { it.parent }
            .takeWhile { bound == null || it.path == bound || it.path.startsWith("$bound/") }.toList()
        val root = dirs.firstOrNull { dir -> rootManifests.any { dir.findChild(it) != null } }
        val module = dirs.firstOrNull { dir -> moduleManifests.any { dir.findChild(it) != null } }
        val marked = dirs.lastOrNull { dir -> dir.path != bound && MARKERS.any { dir.findChild(it)?.isDirectory == true } }
        return (root ?: module ?: marked)?.path ?: bound
    }

    /**
     * Lookup scope of [e]'s project (cached on its file; the provider only captures the file).
     * [oracle] adds what the language itself treats as libraries (e.g. Rust's std and Cargo crates).
     */
    fun scopeOf(e: PsiElement, key: Key<CachedValue<GlobalSearchScope>>, oracle: LibraryOracle? = null): GlobalSearchScope {
        val file = e.containingFile?.originalFile ?: return GlobalSearchScope.allScope(e.project)
        return CachedValuesManager.getCachedValue(file, key) {
            val project = file.project
            val extra: (VirtualFile) -> Boolean = { vf -> oracle?.isLibraryFile(project, vf) == true }
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
            return LiteProjectScope(project, root, ownOutput(root, file?.path, layout.outputDirs), layout.outputDirs, extraLibrary)
        }

        /** The output dir of [root] that [path] is in, when it is below one. */
        private fun ownOutput(root: String?, path: String?, outputDirs: Set<String>): String? {
            if (root == null || path == null || !path.startsWith("$root/")) return null
            val segments = path.substring(root.length + 1).split('/')
            val i = segments.indexOfFirst { it in outputDirs }
            if (i < 0 || i >= segments.size - 1) return null
            return root + "/" + segments.take(i + 1).joinToString("/")
        }
    }
}
