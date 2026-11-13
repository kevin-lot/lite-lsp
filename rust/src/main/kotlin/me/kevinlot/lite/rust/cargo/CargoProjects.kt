package me.kevinlot.lite.rust.cargo

import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import java.io.File
import javax.swing.Icon

/** A compilation unit: its root file and the crates it can name (`extern prelude`). */
data class CrateInfo(
    val name: String,
    /** VFS url of the crate root file */
    val rootUrl: String,
    /** extern name -> crate root url */
    val dependencies: Map<String, String>,
    val edition: String,
    val isMember: Boolean,
)

/** All locations are VFS urls (`file://…`), so tests can point at an in-memory standard library. */
data class CargoWorkspace(val crates: List<CrateInfo>, val packageDirs: List<String>, val stdlibDir: String?) {
    val byRoot: Map<String, CrateInfo> = crates.associateBy { it.rootUrl }

    companion object {
        val EMPTY = CargoWorkspace(emptyList(), emptyList(), null)
    }
}

/**
 * Runs `cargo metadata` / `rustc --print sysroot` in the background and keeps the result.
 * Resolution never blocks on it: until it is loaded, crates are inferred from the file layout.
 */
@Service(Service.Level.PROJECT)
class CargoProjects(private val project: Project) {
    @Volatile var workspace: CargoWorkspace = CargoWorkspace.EMPTY
        private set(value) {
            field = value
            modificationTracker.incModificationCount()
        }

    /** Bumped whenever the crate graph changes: invalidates long-lived (library) caches. */
    val modificationTracker = com.intellij.openapi.util.SimpleModificationTracker()

    /** Manifest path -> crates of its workspace (`cargo metadata` output), loaded on demand. */
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, CargoWorkspace>()
    /** Manifests already covered by a loaded workspace (members), or currently loading. */
    private val known = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var stdlib: String? = null
    @Volatile private var stdlibLoaded = false

    /** Replaces the workspace without running cargo (tests). */
    @org.jetbrains.annotations.TestOnly
    fun setWorkspaceForTests(ws: CargoWorkspace) {
        workspace = ws
        PsiManager.getInstance(project).dropPsiCaches()
    }

    /**
     * Loads the crate that owns [file]: its own `Cargo.toml` (nearest one above the file, never
     * above the opened folder), then only the dependencies that crate declares. Sibling projects
     * of the opened folder are not looked at.
     */
    fun ensureLoadedFor(file: VirtualFile) {
        if (file.extension != "rs" && file.name != "Cargo.toml") return
        val manifest = owningManifest(file) ?: return
        if (!known.add(manifest.path)) return
        ApplicationManager.getApplication().executeOnPooledThread { load(manifest) }
    }

    /** Re-runs `cargo metadata` for workspaces whose manifest or lock file changed. */
    fun reload(changedPaths: Collection<String>) {
        val dirs = changedPaths.map { File(it).parent }
        val stale = loaded.keys.filter { m -> dirs.any { d -> File(m).parent == d } }
        stale.forEach { m ->
            known.removeIf { File(it).parent.startsWith(File(m).parent) }
            loaded.remove(m)
            known.add(m)
            ApplicationManager.getApplication().executeOnPooledThread { load(File(m)) }
        }
    }

    /** Nearest `Cargo.toml` above [file] (same project rule as every language, see [LAYOUT]). */
    private fun owningManifest(file: VirtualFile): File? =
        com.intellij.openapi.application.runReadAction { LAYOUT.rootOf(project, file) }
            ?.let { File(it, "Cargo.toml") }?.takeIf { it.isFile }

    private fun load(manifest: File) {
        try {
            if (!stdlibLoaded) {
                stdlib = sysrootLibrary(manifest.parent)
                stdlibLoaded = true
            }
            val json = runCargoMetadata(manifest.parent) ?: return
            val ws = parseMetadata(json, stdlib)
            loaded[manifest.path] = ws
            known += memberManifests(json)
            LOG.info("lite: loaded ${manifest.path}: ${ws.crates.count { it.isMember }} crates, ${ws.packageDirs.size} dependencies, std=${stdlib != null}")
            publish()
        } catch (e: Exception) {
            LOG.warn("lite: cannot load ${manifest.path}", e)
        }
    }

    @Synchronized
    private fun publish() {
        val parts = loaded.values
        val new = CargoWorkspace(
            parts.flatMap { it.crates }.distinctBy { it.rootUrl },
            parts.flatMap { it.packageDirs }.distinct(),
            stdlib,
        )
        val old = workspace
        if (new != old) {
            workspace = new
            onChanged(old, new)
        }
    }

    private fun runCargoMetadata(dir: String): String? {
        val cargo = findTool("cargo") ?: return null.also { LOG.warn("lite: cargo not found (PATH, \$CARGO_HOME/bin, ~/.cargo/bin)") }
        // `--locked` first: never rewrite the user's Cargo.lock; without it only if the lock is stale.
        val attempts = listOf(
            listOf("metadata", "--format-version", "1", "--locked"),
            listOf("metadata", "--format-version", "1"),
            listOf("metadata", "--format-version", "1", "--no-deps"),
        )
        for (args in attempts) {
            val cmd = GeneralCommandLine(listOf(cargo) + args).withWorkDirectory(dir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            val out = ExecUtil.execAndGetOutput(cmd, 120_000)
            if (out.exitCode == 0) return out.stdout
            LOG.info("lite: cargo ${args.joinToString(" ")} failed: ${out.stderr.take(500)}")
        }
        return null
    }

    private fun sysrootLibrary(dir: String): String? {
        val rustc = findTool("rustc") ?: return null.also { LOG.warn("lite: rustc not found (PATH, \$CARGO_HOME/bin, ~/.cargo/bin)") }
        val out = ExecUtil.execAndGetOutput(
            GeneralCommandLine(rustc, "--print", "sysroot").withWorkDirectory(dir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE), 30_000,
        )
        if (out.exitCode != 0) return null
        val lib = File(out.stdout.trim(), "lib/rustlib/src/rust/library")
        return lib.takeIf { File(it, "std/src/lib.rs").exists() }?.let { VfsUtilCore.pathToUrl(it.path) }
            .also { if (it == null) LOG.info("lite: rust-src missing (rustup component add rust-src)") }
    }

    private fun onChanged(old: CargoWorkspace, new: CargoWorkspace) {
        val vfm = VirtualFileManager.getInstance()
        fun roots(w: CargoWorkspace) = libraryRootPaths(w).mapNotNull { vfm.refreshAndFindFileByUrl(it) }
        val oldRoots = roots(old)
        val newRoots = roots(new)
        ApplicationManager.getApplication().invokeLater({
            WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "Rust", oldRoots, newRoots, "lite-rust")
            }
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }, project.disposed)
    }

    companion object {
        /** Rust's project boundaries: the crate's `Cargo.toml`; `target/` holds build outputs. */
        val LAYOUT = me.kevinlot.lite.core.ProjectLayout(rootManifests = listOf("Cargo.toml"), outputDirs = setOf("target", ".idea"))
        private val SCOPE = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<com.intellij.psi.search.GlobalSearchScope>>("lite.rs.scope")
        fun scopeOf(e: com.intellij.psi.PsiElement) = LAYOUT.scopeOf(e, SCOPE, me.kevinlot.lite.rust.resolve.RsLibraryOracle)
        private val LOG = logger<CargoProjects>()
        fun getInstance(project: Project): CargoProjects = project.service()

        private fun url(path: String): String = VfsUtilCore.pathToUrl(path)

        /**
         * IDEs started from the desktop often miss the shell PATH (rustup puts tools in ~/.cargo/bin):
         * look there too, like the official plugin does.
         */
        fun findTool(name: String): String? {
            PathEnvironmentVariableUtil.findInPath(name)?.let { return it.path }
            val dirs = listOfNotNull(
                System.getenv("CARGO_HOME")?.let { "$it/bin" },
                System.getProperty("user.home") + "/.cargo/bin",
            )
            return dirs.map { File(it, name) }.firstOrNull { it.canExecute() }?.path
        }

        /** Manifests of the workspace members described by a `cargo metadata` output. */
        fun memberManifests(json: String): Set<String> {
            val root = JsonParser.parseString(json).asJsonObject
            val members = root.getAsJsonArray("workspace_members").map { it.asString }.toSet()
            return root.getAsJsonArray("packages").map { it.asJsonObject }
                .filter { it.get("id").asString in members }
                .map { it.get("manifest_path").asString }.toSet()
        }

        fun libraryRootPaths(w: CargoWorkspace): List<String> =
            w.packageDirs + listOfNotNull(w.stdlibDir)

        /** Visible for tests. */
        fun parseMetadata(json: String, stdlibDir: String?): CargoWorkspace {
            val root = JsonParser.parseString(json).asJsonObject
            val members = root.getAsJsonArray("workspace_members").map { it.asString }.toSet()
            data class Pkg(val id: String, val dir: String, val edition: String, val libRoot: String?, val libName: String?, val targets: List<Pair<String, String>>)
            val pkgs = root.getAsJsonArray("packages").map { p ->
                val o = p.asJsonObject
                val targets = o.getAsJsonArray("targets").map { t ->
                    val to = t.asJsonObject
                    val kinds = to.getAsJsonArray("kind").map { it.asString }
                    Triple(kinds, to.get("name").asString.replace('-', '_'), url(to.get("src_path").asString))
                }
                val lib = targets.firstOrNull { (k, _, _) -> k.any { it in LIB_KINDS } }
                Pkg(
                    id = o.get("id").asString,
                    dir = url(File(o.get("manifest_path").asString).parent),
                    edition = o.get("edition")?.asString ?: "2021",
                    libRoot = lib?.third,
                    libName = lib?.second,
                    targets = targets.filter { (k, _, _) -> k.none { it == "custom-build" } }.map { it.second to it.third },
                )
            }.associateBy { it.id }

            val depsByPkg = mutableMapOf<String, Map<String, String>>()
            root.getAsJsonObject("resolve")?.getAsJsonArray("nodes")?.forEach { n ->
                val no = n.asJsonObject
                val deps = no.getAsJsonArray("deps")?.mapNotNull { d ->
                    val dobj = d.asJsonObject
                    val target = pkgs[dobj.get("pkg").asString]?.libRoot ?: return@mapNotNull null
                    dobj.get("name").asString to target
                }?.toMap().orEmpty()
                depsByPkg[no.get("id").asString] = deps
            }

            val crates = pkgs.values.flatMap { p ->
                val deps = depsByPkg[p.id].orEmpty().toMutableMap()
                // Binaries, tests, examples can use the package's own library by name.
                p.targets.map { (name, src) ->
                    val d = if (p.libRoot != null && src != p.libRoot) deps + (p.libName!! to p.libRoot) else deps
                    CrateInfo(name, src, d, p.edition, p.id in members)
                }
            }
            val external = pkgs.values.filter { it.id !in members }.map { it.dir }
            return CargoWorkspace(crates, external, stdlibDir)
        }

        private val LIB_KINDS = setOf("lib", "rlib", "dylib", "proc-macro", "cdylib", "staticlib")
    }
}

/** Indexes dependency sources and the standard library so they can be searched and navigated. */
class RsLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val vfm = VirtualFileManager.getInstance()
        val roots = CargoProjects.libraryRootPaths(CargoProjects.getInstance(project).workspace).mapNotNull { vfm.findFileByUrl(it) }
        return if (roots.isEmpty()) emptyList() else listOf(RsLibrary(roots))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = emptyList()
}

private class RsLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = roots
    override fun equals(other: Any?): Boolean = other is RsLibrary && other.roots == roots
    override fun hashCode(): Int = roots.hashCode()
    override fun getPresentableText(): String = "Rust dependencies"
    override fun getIcon(unused: Boolean): Icon? = null
}

class CargoStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val cargo = CargoProjects.getInstance(project)
        val connection = project.messageBus.connect(project)
        connection.subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) =
                    cargo.ensureLoadedFor(file)
            })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val changed = events.map { it.path }.filter { it.endsWith("/Cargo.toml") || it.endsWith("/Cargo.lock") }
                if (changed.isNotEmpty()) cargo.reload(changed)
            }
        })
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.forEach { cargo.ensureLoadedFor(it) }
    }
}
