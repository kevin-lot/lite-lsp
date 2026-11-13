package me.kevinlot.lite.typescript.resolve

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
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
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import me.kevinlot.lite.core.ProjectLayout
import java.io.File
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * A JavaScript/TypeScript project: its root and the dependencies it declares, found in its own `node_modules`
 * (nothing is installed or downloaded): every declared package (+ their dependencies), every `@types` package, and
 * the TypeScript standard library (the `.d.ts` files of `typescript/lib`, else the one of the global Node installation).
 */
data class TsProjectEnv(val root: String, val packages: List<String>, val stdlib: String?) {
    fun roots(): List<String> = packages + listOfNotNull(stdlib)
}

@Service(Service.Level.PROJECT)
class TsEnvironments(private val project: Project) {
    val modificationTracker = SimpleModificationTracker()
    private val envs = ConcurrentHashMap<String, TsProjectEnv>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun envFor(file: VirtualFile?): TsProjectEnv? = file?.let { projectRoot(it) }?.let { envs[it] }
    fun all(): Collection<TsProjectEnv> = envs.values

    @org.jetbrains.annotations.TestOnly
    fun setForTests(env: TsProjectEnv) {
        envs[env.root] = env
        modificationTracker.incModificationCount()
    }

    fun ensureLoadedFor(file: VirtualFile) {
        if (file.extension !in EXTENSIONS) return
        val root = projectRoot(file) ?: return
        if (envs.containsKey(root) || !loading.add(root)) return
        ApplicationManager.getApplication().executeOnPooledThread { load(root) }
    }

    /** A manifest or lock file changed (`npm install`): the dependencies of its project are read again. */
    fun reloadContaining(path: String) {
        val root = envs.keys.firstOrNull { path == it || path.startsWith("$it/") } ?: return
        if (!loading.add(root)) return
        ApplicationManager.getApplication().executeOnPooledThread { load(root) }
    }

    fun projectRoot(file: VirtualFile): String? {
        if (file.fileSystem != LocalFileSystem.getInstance() && file.fileSystem.protocol != "temp") return null
        return runReadAction { LAYOUT.rootOf(project, file) }
    }

    private fun load(root: String) {
        try {
            val env = TsProjectEnv(root, packagesOf(File(root)), stdlibOf(File(root)))
            envs[root] = env
            LOG.info("lite: js project $root, ${env.packages.size} packages, stdlib=${env.stdlib}")
            publish()
        } catch (e: Exception) {
            LOG.warn("lite: cannot inspect js project $root", e)
        } finally {
            loading.remove(root)
        }
    }

    private fun publish() {
        modificationTracker.incModificationCount()
        val fs = LocalFileSystem.getInstance()
        val roots = envs.values.flatMap { it.roots() }.mapNotNull { fs.refreshAndFindFileByPath(it) }
        ApplicationManager.getApplication().invokeLater({
            WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "JavaScript", emptyList(), roots, "lite-js")
            }
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }, project.disposed)
    }

    companion object {
        private val LOG = logger<TsEnvironments>()
        val EXTENSIONS = setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs")

        /** Lock files mark the install root (also of a monorepo); a package or a config marks a project. */
        val LAYOUT = ProjectLayout(
            rootManifests = listOf("pnpm-workspace.yaml", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb", "lerna.json"),
            moduleManifests = listOf("package.json", "tsconfig.json", "jsconfig.json"),
            outputDirs = setOf("node_modules", "dist", "build", "out", "coverage", ".next", ".nuxt", ".svelte-kit", ".turbo", ".idea"),
        )
        val WATCHED = setOf("package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb")

        fun getInstance(project: Project): TsEnvironments = project.service()

        private const val MAX_PACKAGES = 500

        /** Declared dependencies (root + workspace packages), their own dependencies, and every `@types` package. */
        fun packagesOf(root: File): List<String> {
            val manifests = listOf(root) + workspaces(root)
            val found = LinkedHashMap<String, File>()
            val queue = ArrayDeque<Pair<String, File>>()
            for (dir in manifests) {
                val json = readJson(File(dir, "package.json")) ?: continue
                for (dep in deps(json, dev = true)) queue += dep to dir
            }
            File(root, "node_modules/@types").listFiles()?.filter { it.isDirectory }?.forEach { queue += "@types/${it.name}" to root }
            while (queue.isNotEmpty() && found.size < MAX_PACKAGES) {
                val (name, from) = queue.removeFirst()
                if (name in found) continue
                val dir = locate(name, from, root) ?: continue
                found[name] = dir
                readJson(File(dir, "package.json"))?.let { json -> deps(json, dev = false).forEach { queue += it to dir } }
            }
            return found.values.map { it.canonicalPath }.distinct()
        }

        /** `node_modules/<name>` next to [from], then in its parents up to [root] (never above). */
        private fun locate(name: String, from: File, root: File): File? {
            var dir: File? = from
            while (dir != null) {
                File(dir, "node_modules/$name").takeIf { it.isDirectory }?.let { return it }
                if (dir.path == root.path) return null
                dir = dir.parentFile
            }
            return null
        }

        private fun deps(json: JsonObject, dev: Boolean): List<String> =
            listOfNotNull("dependencies", "peerDependencies", "optionalDependencies", "devDependencies".takeIf { dev })
                .flatMap { json.getAsJsonObject(it)?.keySet().orEmpty() }

        /** `"workspaces"` patterns (or `{ "packages": [...] }`): one level of `*` is expanded. */
        private fun workspaces(root: File): List<File> {
            val json = readJson(File(root, "package.json")) ?: return emptyList()
            val ws = json.get("workspaces") ?: return emptyList()
            val patterns = (if (ws.isJsonObject) ws.asJsonObject.get("packages") else ws)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
            return patterns.flatMap { p ->
                if (p.endsWith("/*")) File(root, p.removeSuffix("/*")).listFiles()?.filter { File(it, "package.json").isFile }.orEmpty()
                else listOf(File(root, p)).filter { File(it, "package.json").isFile }
            }
        }

        /** The project's `typescript/lib`, else the one installed globally next to `node`. */
        fun stdlibOf(root: File): String? {
            File(root, "node_modules/typescript/lib").takeIf { File(it, "lib.es5.d.ts").isFile }?.let { return it.canonicalPath }
            val node = com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath("node") ?: return null
            val prefix = node.canonicalFile.parentFile?.parentFile ?: return null
            return File(prefix, "lib/node_modules/typescript/lib").takeIf { File(it, "lib.es5.d.ts").isFile }?.canonicalPath
        }

        fun readJson(file: File): JsonObject? = try {
            if (file.isFile) parseJson(file.readText()) else null
        } catch (_: Exception) { null }

        private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

        /** JSON with comments and trailing commas (tsconfig.json). */
        fun parseJson(text: String): JsonObject? = try {
            val reader = JsonReader(StringReader(text.replace(TRAILING_COMMA, "$1"))).apply { isLenient = true }
            JsonParser.parseReader(reader).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) { null }

        // ------------------------------------------------------------ scope

        private val SCOPE = Key.create<CachedValue<GlobalSearchScope>>("lite.ts.scope")

        /**
         * Lookups of [e]'s project: its files (minus build outputs) plus the libraries of *its* environment
         * (another project's `node_modules` or TypeScript version is never mixed in).
         */
        fun scopeOf(e: PsiElement): GlobalSearchScope {
            val file = e.containingFile?.originalFile ?: return GlobalSearchScope.allScope(e.project)
            return CachedValuesManager.getCachedValue(file) {
                val project = file.project
                val vf = file.virtualFile
                val envs = getInstance(project)
                // a library file sees the libraries (and sources) of the projects that use it
                val users = if (vf == null) emptyList() else envs.all().filter { e -> e.roots().any { vf.path.startsWith("$it/") } }
                val scope = if (users.isNotEmpty()) TsLookupScope(project, GlobalSearchScope.allScope(project), users.flatMap { it.roots() }.distinct(), users.map { it.root })
                else TsLookupScope(project, LAYOUT.scopeOf(file, SCOPE, TsLibraryOracle), envs.envFor(vf)?.roots().orEmpty(), null)
                CachedValueProvider.Result.create<GlobalSearchScope>(
                    scope, PsiModificationTracker.MODIFICATION_COUNT, envs.modificationTracker,
                )
            }
        }
    }
}

/**
 * Project sources (of [projects], or those of [base]) plus only the given [libraries]: never typings other
 * plugins index (HTTP Client's…), another project's `node_modules`, or another TypeScript version.
 */
private class TsLookupScope(
    project: Project, private val base: GlobalSearchScope, private val libraries: List<String>, private val projects: List<String>?,
) : GlobalSearchScope(project) {
    private val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)

    override fun contains(file: VirtualFile): Boolean {
        val path = file.path
        if (libraries.any { path.startsWith("$it/") }) return true
        if (!index.isInContent(file) || index.isInLibrary(file)) return false
        return projects?.any { path.startsWith("$it/") } ?: base.contains(file)
    }
    override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true
    override fun isSearchInLibraries() = true
    override fun equals(other: Any?) = other is TsLookupScope && other.base == base && other.libraries == libraries && other.projects == projects
    override fun hashCode() = (base.hashCode() * 31 + libraries.hashCode()) * 31 + projects.hashCode()
}

/** Packages are indexed as libraries (sources), without their nested `node_modules`; TypeScript's lib: `.d.ts` only. */
class TsLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val fs = LocalFileSystem.getInstance()
        val envs = TsEnvironments.getInstance(project).all()
        val packages = envs.flatMap { it.packages }.distinct().mapNotNull { fs.findFileByPath(it) }
        val stdlib = envs.mapNotNull { it.stdlib }.distinct().mapNotNull { fs.findFileByPath(it) }
        return listOfNotNull(
            packages.takeIf { it.isNotEmpty() }?.let { TsLibrary("node_modules", it) { dir, name -> dir && name == "node_modules" } },
            stdlib.takeIf { it.isNotEmpty() }?.let { TsLibrary("TypeScript library", it) { dir, name -> !dir && !name.endsWith(".d.ts") } },
        )
    }
}

private class TsLibrary(private val title: String, private val roots: List<VirtualFile>, exclude: (Boolean, String) -> Boolean) :
    SyntheticLibrary("lite-ts-$title", ExcludeFileCondition { isDir, name, _, _, _ -> exclude(isDir, name) }), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = roots
    override fun equals(other: Any?) = other is TsLibrary && other.title == title && other.roots == roots
    override fun hashCode() = roots.hashCode()
    override fun getPresentableText() = title
    override fun getIcon(unused: Boolean): Icon? = null
}

/** `node_modules` of every package under the content roots is excluded (only declared dependencies are indexed). */
class TsExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> {
        val out = mutableListOf<String>()
        fun walk(dir: VirtualFile, depth: Int) {
            if (dir.findChild("package.json") != null) dir.findChild("node_modules")?.takeIf { it.isDirectory }?.let { out += it.url }
            if (depth == 0) return
            for (c in dir.children) if (c.isDirectory && c.name != "node_modules" && !c.name.startsWith(".")) walk(c, depth - 1)
        }
        ProjectRootManager.getInstance(project).contentRoots.forEach { walk(it, 3) }
        return out.toTypedArray()
    }
}

class TsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val envs = TsEnvironments.getInstance(project)
        val connection = project.messageBus.connect(project)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = envs.ensureLoadedFor(file)
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.forEach { e -> if (e.path.substringAfterLast('/') in TsEnvironments.WATCHED) envs.reloadContaining(e.path.substringBeforeLast('/')) }
            }
        })
        FileEditorManager.getInstance(project).openFiles.forEach { envs.ensureLoadedFor(it) }
    }
}

/** Parsed `package.json` / `tsconfig.json`, cached by modification stamp. */
object TsJsonFiles {
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonObject?>>()

    fun read(file: VirtualFile): JsonObject? {
        val stamp = file.modificationStamp
        cache[file.path]?.let { (s, json) -> if (s == stamp) return json }
        val json = try { TsEnvironments.parseJson(String(file.contentsToByteArray(), Charsets.UTF_8)) } catch (_: Exception) { null }
        cache[file.path] = stamp to json
        return json
    }

    fun string(json: JsonObject?, key: String): String? = json?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
    fun obj(json: JsonElement?, key: String): JsonObject? = json?.takeIf { it.isJsonObject }?.asJsonObject?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
