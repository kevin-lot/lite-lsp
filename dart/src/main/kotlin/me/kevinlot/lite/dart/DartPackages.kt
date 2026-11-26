package me.kevinlot.lite.dart

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Where `package:` and `dart:` URIs lead: the `lib` folder of each package, the SDK and the Flutter engine. */
class DartPackages(val packages: Map<String, VirtualFile>, val sdk: VirtualFile?, val engine: VirtualFile?) {
    val isValid: Boolean get() = (packages.values + listOfNotNull(sdk, engine)).all { it.isValid }

    companion object {
        private val KNOWN = Key.create<DartPackages>("lite.dart.packages.known")
        private val NAME_LINE = Regex("""(?m)^name:\s*['"]?([\w.]+)""")
        private val NO_PACKAGES = DartPackages(emptyMap(), null, null)
        private val PACKAGES = Key.create<CachedValue<DartPackages>>("lite.dart.packages")

        fun packageFile(context: PsiFile, path: String): VirtualFile? =
            of(context).packages[path.substringBefore('/')]?.findFileByRelativePath(path.substringAfter('/', ""))

        /** `dart:core` is `lib/core/core.dart` of the SDK; `dart:ui` comes with the Flutter engine. */
        fun sdkLibrary(context: PsiFile, name: String): VirtualFile? {
            val packages = of(context)
            val roots = listOfNotNull(packages.sdk?.findFileByRelativePath("lib"), packages.engine)
            return roots.firstNotNullOfOrNull { it.findFileByRelativePath("$name/$name.dart") }
        }

        /**
         * The packages of the nearest `.dart_tool/package_config.json` above [context] (written by `pub get`), plus the packages of the
         * project itself: a `pubspec.yaml` beside or above the file names its `lib` folder.
         */
        fun of(context: PsiFile): DartPackages {
            val dir = context.originalFile.containingDirectory ?: return NO_PACKAGES
            return CachedValuesManager.getCachedValue(dir, PACKAGES) {
                val folders = generateSequence(dir.virtualFile) { it.parent }.take(12).toList()
                val config = folders.firstNotNullOfOrNull { it.findFileByRelativePath(".dart_tool/package_config.json") }
                val own = folders.mapNotNull { folder ->
                    val name = folder.findChild("pubspec.yaml")?.let { NAME_LINE.find(VfsUtilCore.loadText(it))?.groupValues?.get(1) }
                    folder.findChild("lib")?.let { lib -> name?.let { it to lib } }
                }
                // a package of the pub cache or the SDK has no configuration of its own, or one without the SDK: the project's last one fills in
                val known = dir.project.getUserData(KNOWN)?.takeIf { it.isValid }
                val read = config?.let(::read)
                val merged = DartPackages(known?.packages.orEmpty() + own + read?.packages.orEmpty(), read?.sdk ?: known?.sdk, read?.engine ?: known?.engine)
                if (read?.sdk != null) dir.project.putUserData(KNOWN, merged)
                CachedValueProvider.Result.create(merged, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, PsiModificationTracker.MODIFICATION_COUNT)
            }
        }

        private fun read(config: VirtualFile): DartPackages {
            val json = runCatching { JsonParser.parseString(VfsUtilCore.loadText(config)) as? JsonObject }.getOrNull() ?: return NO_PACKAGES
            val base = config.parent
            fun folder(uri: String?): VirtualFile? = when {
                uri == null -> null
                uri.startsWith("file:") ->
                    java.net.URI(uri).path.let { config.fileSystem.findFileByPath(it) ?: LocalFileSystem.getInstance().findFileByPath(it) }
                else -> base.findFileByRelativePath(uri)
            }
            val packages = json.getAsJsonArray("packages")?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { entry ->
                val root = folder(entry.get("rootUri")?.asString) ?: return@mapNotNull null
                val lib = root.findFileByRelativePath(entry.get("packageUri")?.asString?.trimEnd('/') ?: "") ?: return@mapNotNull null
                entry.get("name")?.asString?.let { it to lib }
            }.toMap()
            val flutter = folder(json.get("flutterRoot")?.asString)
            val sdk = flutter?.findFileByRelativePath("bin/cache/dart-sdk") ?: packages["sky_engine"]?.parent?.parent?.parent?.findChild("dart-sdk")
                ?: sdkFromPath(config)
            return DartPackages(packages, sdk, flutter?.findFileByRelativePath("bin/cache/pkg/sky_engine/lib") ?: packages["sky_engine"])
        }

        /** Without Flutter: the SDK of `DART_SDK`, else the one the `dart` of the `PATH` belongs to. */
        private fun sdkFromPath(config: VirtualFile): VirtualFile? {
            val home = System.getenv("DART_SDK") ?: System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it, "dart") }
                .firstOrNull { it.isFile }?.let { runCatching { it.canonicalFile.parentFile?.parentFile?.path }.getOrNull() } ?: return null
            return config.fileSystem.findFileByPath(home)?.takeIf { it.findFileByRelativePath("lib/core/core.dart") != null }
        }
    }
}
