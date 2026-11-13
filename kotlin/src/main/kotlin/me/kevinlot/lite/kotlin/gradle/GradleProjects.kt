package me.kevinlot.lite.kotlin.gradle

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
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/** A dependency declared as `"group:artifact:version"` (or the Kotlin stdlib of the Kotlin plugin version). */
data class Coordinates(val group: String, val artifact: String, val version: String)

/**
 * Libraries of a Gradle build without the Gradle plugin: the coordinates *declared* in the build
 * script of the opened file's project, matched against jars already in the Gradle cache.
 * Nothing is downloaded and sibling projects are never read.
 */
@Service(Service.Level.PROJECT)
class GradleProjects(private val project: Project) {
    val modificationTracker = SimpleModificationTracker()
    private val loaded = ConcurrentHashMap<String, List<String>>() // build script path -> jar urls
    @Volatile var jarUrls: List<String> = emptyList()
        private set

    /** Attaches jars exactly like a real load does (synthetic library), for tests. */
    @org.jetbrains.annotations.TestOnly
    fun setJarsForTests(jars: List<File>) {
        loaded["test"] = jars.map { "jar://${it.path}!/" }
        val old = jarUrls
        jarUrls = loaded.values.flatten().distinct()
        modificationTracker.incModificationCount()
        val vfm = VirtualFileManager.getInstance()
        WriteAction.run<RuntimeException> {
            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "Kotlin", old.mapNotNull { vfm.findFileByUrl(it) },
                jarUrls.mapNotNull { vfm.findFileByUrl(it) }, "lite-kotlin")
        }
    }

    fun ensureLoadedFor(file: VirtualFile) {
        if (file.extension != "kt" && file.extension != "kts") return
        // No build script (loose sources): still give the project a Kotlin stdlib.
        val script = owningBuildScript(file)
        val key = script?.path ?: me.kevinlot.lite.kotlin.resolve.KtProjectScope.rootOf(project, file) ?: return
        if (loaded.putIfAbsent(key, emptyList()) != null) return
        ApplicationManager.getApplication().executeOnPooledThread { load(key, script) }
    }

    fun reload(changed: Collection<String>) {
        changed.filter { loaded.containsKey(it) }.forEach { path ->
            ApplicationManager.getApplication().executeOnPooledThread { load(path, File(path)) }
        }
    }

    private fun owningBuildScript(file: VirtualFile): File? {
        val bound = runReadAction { ProjectFileIndex.getInstance(project).getContentRootForFile(file) }?.path
            ?: project.guessProjectDir()?.path ?: return null
        var dir: File? = File(file.path).parentFile
        while (dir != null && (dir.path == bound || dir.path.startsWith("$bound/"))) {
            BUILD_FILES.map { File(dir, it) }.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun load(key: String, script: File?) {
        try {
            val jars = jarsFor(script).map { "jar://${it.path}!/" }
            loaded[key] = jars
            LOG.info("lite: ${script?.path ?: "$key (no build script)"}: ${jars.size} jars found in the Gradle cache")
            publish()
        } catch (e: Exception) {
            LOG.warn("lite: cannot read ${script?.path ?: key}", e)
        }
    }

    @Synchronized
    private fun publish() {
        val old = jarUrls
        val new = loaded.values.flatten().distinct()
        if (new == old) return
        jarUrls = new
        modificationTracker.incModificationCount()
        val vfm = VirtualFileManager.getInstance()
        ApplicationManager.getApplication().invokeLater({
            WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, "Kotlin", old.mapNotNull { vfm.findFileByUrl(it) }, new.mapNotNull { vfm.findFileByUrl(it) }, "lite-kotlin",
                )
            }
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }, project.disposed)
    }

    companion object {
        private val LOG = logger<GradleProjects>()
        private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle")
        private val COORDINATE = Regex("""["']([\w.\-]+):([\w.\-]+):([\w.\-+]+)["']""")
        private val KOTLIN_MODULE = Regex("""kotlin\(\s*"([\w-]+)"\s*\)""")
        private val KOTLIN_PLUGIN = Regex("""(?:kotlin\(\s*"jvm"\s*\)|id\(\s*"org\.jetbrains\.kotlin\.jvm"\s*\))\s*version\s*"([^"]+)"""")
        private val CATALOG_LIB = Regex("""(?m)^\s*[\w.\-]+\s*=\s*\{[^}]*module\s*=\s*"([\w.\-]+):([\w.\-]+)"[^}]*version(?:\.ref)?\s*=\s*"([^"]+)"""")
        private val CATALOG_VERSION = Regex("""(?m)^\s*([\w.\-]+)\s*=\s*"([^"]+)"""")

        fun getInstance(project: Project): GradleProjects = project.service()

        /**
         * Jars of a build: declared coordinates (+ POM dependencies) found in the Gradle cache. Without a
         * script, or when no Kotlin version is declared, the newest cached Kotlin stdlib is used.
         */
        fun jarsFor(script: File?, gradleHome: File = GRADLE_HOME): List<File> {
            val text = script?.readText().orEmpty()
            val catalog = script?.let { File(it.parentFile, "gradle/libs.versions.toml") }?.takeIf { it.isFile }?.readText()
            val declared = declaredCoordinates(text, catalog)
            val coords = withTransitive(declared).toMutableList()
            if (declared.none { it.group == "org.jetbrains.kotlin" && it.artifact == "kotlin-stdlib" }) newestStdlib(gradleHome)?.let { coords += it }
            return coords.mapNotNull { cachedJar(it, gradleHome) }.distinct()
        }

        private fun newestStdlib(gradleHome: File): Coordinates? {
            val dir = File(gradleHome, "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
            val versions = dir.listFiles()?.map { it.name }.orEmpty()
            val newest = versions.maxWithOrNull(compareBy<String>({ it.split('.', '-').mapNotNull(String::toIntOrNull).let { p -> p.getOrElse(0) { 0 } } },
                { it.split('.', '-').mapNotNull(String::toIntOrNull).getOrElse(1) { 0 } }, { it.split('.', '-').mapNotNull(String::toIntOrNull).getOrElse(2) { 0 } }))
            return newest?.let { Coordinates("org.jetbrains.kotlin", "kotlin-stdlib", it) }
        }

        /** Visible for tests. */
        fun declaredCoordinates(script: String, catalog: String? = null): List<Coordinates> {
            val out = COORDINATE.findAll(script).map { Coordinates(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toMutableList()
            KOTLIN_PLUGIN.find(script)?.let { m ->
                val v = m.groupValues[1]
                out += Coordinates("org.jetbrains.kotlin", "kotlin-stdlib", v)
                // `testImplementation(kotlin("test"))` -> org.jetbrains.kotlin:kotlin-test:<plugin version>
                KOTLIN_MODULE.findAll(script).map { it.groupValues[1] }.filter { it != "jvm" }
                    .forEach { out += Coordinates("org.jetbrains.kotlin", "kotlin-$it", v) }
            }
            if (catalog != null) {
                val versions = CATALOG_VERSION.findAll(catalog.substringAfter("[versions]", "").substringBefore("\n["))
                    .associate { it.groupValues[1] to it.groupValues[2] }
                CATALOG_LIB.findAll(catalog).forEach { m ->
                    val v = m.groupValues[3]
                    out += Coordinates(m.groupValues[1], m.groupValues[2], versions[v] ?: v)
                }
            }
            return out.distinct()
        }

        private val GRADLE_HOME = File(System.getenv("GRADLE_USER_HOME") ?: (System.getProperty("user.home") + "/.gradle"))

        /** `files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>.<ext>`; multiplatform libs publish `<artifact>-jvm`. */
        private fun cachedFile(c: Coordinates, ext: String, gradleHome: File = GRADLE_HOME): File? {
            for (artifact in listOf(c.artifact, "${c.artifact}-jvm")) {
                val dir = File(gradleHome, "caches/modules-2/files-2.1/${c.group}/$artifact/${c.version}")
                dir.listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }?.firstOrNull { it.name == "$artifact-${c.version}.$ext" }?.let { return it }
            }
            return null
        }

        fun cachedJar(c: Coordinates, gradleHome: File = GRADLE_HOME): File? = cachedFile(c, "jar", gradleHome)

        private val POM_DEP = Regex("""<dependency>(.*?)</dependency>""", RegexOption.DOT_MATCHES_ALL)
        private fun tag(xml: String, name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(xml)?.groupValues?.get(1)

        /** Runtime/compile dependencies from the cached POMs (no download, depth-limited). */
        fun withTransitive(roots: List<Coordinates>, maxDepth: Int = 4): List<Coordinates> {
            val seen = LinkedHashSet<Coordinates>()
            var frontier = roots
            repeat(maxDepth) {
                val next = mutableListOf<Coordinates>()
                for (c in frontier) {
                    if (!seen.add(c)) continue
                    val pom = cachedFile(c, "pom")?.readText() ?: continue
                    for (m in POM_DEP.findAll(pom)) {
                        val d = m.groupValues[1]
                        val scope = tag(d, "scope")
                        if (scope == "test" || scope == "provided" || tag(d, "optional") == "true") continue
                        val g = tag(d, "groupId") ?: continue
                        val a = tag(d, "artifactId") ?: continue
                        val v = tag(d, "version")?.takeIf { !it.contains("$") } ?: continue
                        next += Coordinates(g, a, v.trim('[', ']', '(', ')').substringBefore(','))
                    }
                }
                frontier = next
            }
            seen += frontier
            return seen.toList()
        }
    }
}

/** Declared Gradle dependencies become a library: Java PSI can then open their (compiled) classes. */
class KtLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val jfs = JarFileSystem.getInstance()
        val roots = GradleProjects.getInstance(project).jarUrls.mapNotNull { url ->
            jfs.findFileByPath(url.removePrefix("jar://"))
        }
        return if (roots.isEmpty()) emptyList() else listOf(GradleLibrary(roots))
    }
}

private class GradleLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = emptyList()
    override fun getBinaryRoots(): Collection<VirtualFile> = roots
    override fun equals(other: Any?) = other is GradleLibrary && other.roots == roots
    override fun hashCode() = roots.hashCode()
    override fun getPresentableText() = "Gradle dependencies"
    override fun getIcon(unused: Boolean): Icon? = null
}

class GradleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val gradle = GradleProjects.getInstance(project)
        val connection = project.messageBus.connect(project)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = gradle.ensureLoadedFor(file)
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val changed = events.map { it.path }.filter { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }
                if (changed.isNotEmpty()) gradle.reload(changed)
            }
        })
        FileEditorManager.getInstance(project).openFiles.forEach { gradle.ensureLoadedFor(it) }
    }
}
