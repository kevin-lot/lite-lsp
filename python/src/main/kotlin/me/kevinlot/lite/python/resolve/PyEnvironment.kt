package me.kevinlot.lite.python.resolve

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * A Python project: its root (nearest `pyproject.toml`/`setup.py`/`setup.cfg`/`requirements.txt`, else the
 * highest folder with `src/` or `.git` below the content root), its import roots (`root`, `root/src`) and its
 * interpreter libraries: the venv's `site-packages` (`.venv`/`venv`) and the standard library.
 */
data class PyProjectEnv(val root: String, val sourceRoots: List<String>, val libraryRoots: List<String>)

@Service(Service.Level.PROJECT)
class PyEnvironments(private val project: Project) {
    val modificationTracker = SimpleModificationTracker()
    private val envs = ConcurrentHashMap<String, PyProjectEnv>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    /** Import roots for [file], in lookup order: project sources first, then venv, then stdlib. */
    fun rootsFor(file: VirtualFile?): List<String> {
        file ?: return emptyList()
        val root = projectRoot(file) ?: return emptyList()
        val env = envs[root] ?: return listOf(root) + listOf("$root/src").filter { file.fileSystem.findFileByPath(it)?.isDirectory == true }
        return env.sourceRoots + env.libraryRoots
    }

    fun libraryRoots(): List<String> = envs.values.flatMap { it.libraryRoots }.distinct()

    @org.jetbrains.annotations.TestOnly
    fun setForTests(env: PyProjectEnv) {
        envs[env.root] = env
        modificationTracker.incModificationCount()
    }

    fun ensureLoadedFor(file: VirtualFile) {
        if (file.extension != "py") return
        val root = projectRoot(file) ?: return
        if (envs.containsKey(root) || !loading.add(root)) return
        ApplicationManager.getApplication().executeOnPooledThread { load(root) }
    }

    fun projectRoot(file: VirtualFile): String? {
        if (file.fileSystem != LocalFileSystem.getInstance() && file.fileSystem.protocol != "temp") return null
        return runReadAction { LAYOUT.rootOf(project, file) }
    }

    private fun load(root: String) {
        try {
            val venv = listOf(".venv", "venv", "env").map { File(root, it) }.firstOrNull { File(it, "pyvenv.cfg").isFile }
            val site = venv?.let { v -> File(v, "lib").listFiles()?.map { File(it, "site-packages") }?.filter { it.isDirectory } }.orEmpty()
            val python = venv?.let { File(it, "bin/python") }?.takeIf { it.canExecute() }?.path
                ?: PathEnvironmentVariableUtil.findInPath("python3")?.path
            val stdlib = python?.let { stdlibOf(it, root) }
            val sources = listOf(root) + listOf("$root/src").filter { File(it).isDirectory }
            val env = PyProjectEnv(root, sources, site.map { it.path } + listOfNotNull(stdlib))
            envs[root] = env
            LOG.info("lite: python project $root, venv=${venv?.name}, stdlib=$stdlib, ${site.size} site-packages")
            publish()
        } catch (e: Exception) {
            LOG.warn("lite: cannot inspect python project $root", e)
        } finally {
            loading.remove(root)
        }
    }

    private fun stdlibOf(python: String, root: String): String? {
        val out = ExecUtil.execAndGetOutput(
            GeneralCommandLine(python, "-c", "import sysconfig; print(sysconfig.get_paths()['stdlib'])").withWorkDirectory(root), 20_000,
        )
        return out.stdout.trim().takeIf { out.exitCode == 0 && File(it, "os.py").isFile }
    }

    private fun publish() {
        modificationTracker.incModificationCount()
        val fs = LocalFileSystem.getInstance()
        val roots = libraryRoots().mapNotNull { fs.refreshAndFindFileByPath(it) }
        ApplicationManager.getApplication().invokeLater({
            WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "Python", emptyList(), roots, "lite-python")
            }
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }, project.disposed)
    }

    companion object {
        private val LOG = logger<PyEnvironments>()
        /** Python's project boundaries (same rule as every language, see [me.kevinlot.lite.core.ProjectLayout]). */
        val LAYOUT = me.kevinlot.lite.core.ProjectLayout(
            rootManifests = listOf("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile"),
            outputDirs = setOf("build", "dist", "__pycache__", ".tox", ".nox", ".mypy_cache", ".pytest_cache", ".idea", "node_modules"),
        )
        private val SCOPE = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<com.intellij.psi.search.GlobalSearchScope>>("lite.py.scope")
        fun scopeOf(e: com.intellij.psi.PsiElement) = LAYOUT.scopeOf(e, SCOPE, PyLibraryOracle)
        fun getInstance(project: Project): PyEnvironments = project.service()
    }
}

/** Standard library and venv packages are indexed as a library (sources: navigation shows real code). */
class PyLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val fs = LocalFileSystem.getInstance()
        val roots = PyEnvironments.getInstance(project).libraryRoots().mapNotNull { fs.findFileByPath(it) }
        return if (roots.isEmpty()) emptyList() else listOf(PyLibrary(roots))
    }
}

private class PyLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = roots
    override fun equals(other: Any?) = other is PyLibrary && other.roots == roots
    override fun hashCode() = roots.hashCode()
    override fun getPresentableText() = "Python interpreter"
    override fun getIcon(unused: Boolean): Icon? = null
}

class PyStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val envs = PyEnvironments.getInstance(project)
        project.messageBus.connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = envs.ensureLoadedFor(file)
        })
        FileEditorManager.getInstance(project).openFiles.forEach { envs.ensureLoadedFor(it) }
    }
}
