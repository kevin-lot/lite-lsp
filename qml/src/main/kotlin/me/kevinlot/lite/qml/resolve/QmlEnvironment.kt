package me.kevinlot.lite.qml.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import me.kevinlot.lite.core.LibraryOracle
import me.kevinlot.lite.qml.QmlFile
import me.kevinlot.lite.qml.psi.QmlObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Where QML modules live: Qt's import paths (`QML_IMPORT_PATH`, then the system Qt), the Quickshell config
 * root for `qs.*` (the folder of `shell.qml`, never above the project), and the project itself.
 */
object QmlEnvironment {
    private val SYSTEM_PATHS = listOf("/usr/lib64/qt6/qml", "/usr/lib/qt6/qml", "/usr/lib/x86_64-linux-gnu/qt6/qml", "/usr/lib/aarch64-linux-gnu/qt6/qml", "/usr/local/lib/qt6/qml", "/opt/homebrew/share/qt/qml")

    val importPaths: List<VirtualFile> by lazy {
        val env = listOf("QML_IMPORT_PATH", "QML2_IMPORT_PATH").flatMap { System.getenv(it)?.split(':').orEmpty() }.filter { it.isNotBlank() }
        val fs = LocalFileSystem.getInstance()
        (env + SYSTEM_PATHS).distinct().mapNotNull { fs.findFileByPath(it) }.filter { it.isDirectory }
    }

    fun isLibrary(vf: VirtualFile) = importPaths.any { VfsUtilCore.isAncestor(it, vf, false) }

    /** `QtQuick.Layouts` → `<qt>/qml/QtQuick/Layouts`; `qs.Widgets` → `<shell root>/Widgets`; `MyModule` → `<project>/MyModule`. */
    fun moduleDirs(uri: String, from: VirtualFile, project: Project): List<VirtualFile> {
        val rel = uri.replace('.', '/')
        if (uri == "qs" || uri.startsWith("qs.")) {
            val root = quickshellRoot(from, project) ?: return emptyList()
            return listOfNotNull(if (uri == "qs") root else root.findFileByRelativePath(rel.removePrefix("qs/")))
        }
        val content = ProjectFileIndex.getInstance(project).getContentRootForFile(from)
        return (importPaths + listOfNotNull(content)).mapNotNull { it.findFileByRelativePath(rel)?.takeIf { d -> d.isDirectory } }
    }

    /** The folder holding `shell.qml` above [from], bounded by its content root. */
    fun quickshellRoot(from: VirtualFile, project: Project): VirtualFile? {
        val bound = ProjectFileIndex.getInstance(project).getContentRootForFile(from)
        var dir: VirtualFile? = if (from.isDirectory) from else from.parent
        while (dir != null) {
            if (dir.findChild("shell.qml") != null) return dir
            if (dir == bound) return null
            dir = dir.parent
        }
        return null
    }

    fun builtins(project: Project): QmlFile? = typeInfoFile(project, "builtins.qmltypes")
    fun jsRoot(project: Project): QmlFile? = typeInfoFile(project, "jsroot.qmltypes")

    private fun typeInfoFile(project: Project, name: String): QmlFile? =
        importPaths.firstNotNullOfOrNull { it.findChild(name) }?.let { PsiManager.getInstance(project).findFile(it) as? QmlFile }
}

/** Qt's own files never change during a session. */
object QmlLibraryOracle : LibraryOracle {
    override fun isLibrary(e: PsiElement): Boolean = e.containingFile?.originalFile?.virtualFile?.let { QmlEnvironment.isLibrary(it) } == true
    override fun tracker(e: PsiElement): ModificationTracker = ModificationTracker.NEVER_CHANGED
}

/** A module's `qmldir`: its QML types, singletons, implied imports and type-info files. */
data class QmlDir(val types: Map<String, String>, val singletons: Set<String>, val imports: List<String>, val typeInfo: List<String>)

object QmlDirs {
    private val cache = ConcurrentHashMap<String, Pair<Long, QmlDir>>()
    private val TYPE_LINE = Regex("^(singleton\\s+|internal\\s+)?([A-Z]\\w*)\\s+(?:[\\d.]+\\s+)?(\\S+\\.(?:qml|js|mjs))$")

    fun read(dir: VirtualFile): QmlDir? {
        val file = dir.findChild("qmldir") ?: return null
        cache[file.path]?.let { (stamp, d) -> if (stamp == file.modificationStamp) return d }
        val types = LinkedHashMap<String, String>(); val singletons = HashSet<String>(); val imports = mutableListOf<String>(); val typeInfo = mutableListOf<String>()
        String(file.contentsToByteArray(), Charsets.UTF_8).lineSequence().map { it.trim() }.forEach { line ->
            val words = line.split(Regex("\\s+"))
            when (words.firstOrNull()) {
                "typeinfo" -> words.getOrNull(1)?.let { typeInfo += it }
                "import" -> words.getOrNull(1)?.let { imports += it }
                // QtQuick.Controls: `default import QtQuick.Controls.Basic` (the `optional` styles are alternatives)
                "default" -> if (words.getOrNull(1) == "import") words.getOrNull(2)?.let { imports += it }
                else -> TYPE_LINE.find(line)?.let { m ->
                    val name = m.groupValues[2]
                    if (name !in types) types[name] = m.groupValues[3]
                    if (m.groupValues[1].startsWith("singleton")) singletons += name
                }
            }
        }
        return QmlDir(types, singletons, imports, typeInfo).also { cache[file.path] = file.modificationStamp to it }
    }
}

/**
 * C++ types of `.qmltypes` files by C++ name (`QQuickItem`), for prototypes, attached types and property types.
 * Built once from the text of every `.qmltypes` under the import paths (Component names sit at 8 spaces).
 */
object QmlTypeInfo {
    private val COMPONENT_NAME = Regex("^ {8}name: \"([^\"]+)\"", RegexOption.MULTILINE)
    @Volatile private var byName: Map<String, String>? = null

    private fun registry(): Map<String, String> = byName ?: synchronized(this) {
        byName ?: HashMap<String, String>().also { out ->
            // plain files: the import paths hold thousands of files the VFS does not need to know
            for (root in QmlEnvironment.importPaths) {
                java.io.File(root.path).walkTopDown().filter { it.isFile && it.name.endsWith(".qmltypes") }.forEach { f ->
                    COMPONENT_NAME.findAll(f.readText()).forEach { out.putIfAbsent(it.groupValues[1], f.path) }
                }
            }
        }.also { byName = it }
    }

    /** The `Component { name: "[cppName]" }` of the Qt type descriptions. */
    fun component(cppName: String, project: Project): QmlObject? {
        val path = registry()[cppName] ?: return null
        val file = LocalFileSystem.getInstance().findFileByPath(path)?.let { PsiManager.getInstance(project).findFile(it) } as? QmlFile ?: return null
        return QmlTypes.components(file)[cppName]
    }
}
