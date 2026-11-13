package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.util.indexing.ID
import me.kevinlot.lite.core.LibraryOracle
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.core.LiteKeysIndex
import me.kevinlot.lite.kotlin.KtFile
import me.kevinlot.lite.kotlin.KtFileType
import me.kevinlot.lite.kotlin.gradle.GradleProjects
import me.kevinlot.lite.kotlin.psi.KtNamedDeclaration
import me.kevinlot.lite.kotlin.psi.KtPackageDirective

/** package name -> files declaring it (`""` for the default package). */
class KtPackageIndex : LiteKeysIndex<KtFile>(KtFileType, KtFile::class.java) {
    override fun getName(): ID<String, Void> = KEY
    override fun keys(file: KtFile): Collection<String> = listOf(KtFiles.packageName(file))

    companion object {
        val KEY = ID.create<String, Void>("lite.kotlin.package")
    }
}

/** short name -> files with a top-level declaration of that name (extension lookup). */
class KtTopLevelNameIndex : LiteKeysIndex<KtFile>(KtFileType, KtFile::class.java) {
    override fun getName(): ID<String, Void> = KEY
    override fun keys(file: KtFile): Collection<String> = KtFiles.topLevel(file).mapNotNull { it.name }.toSet()

    companion object {
        val KEY = ID.create<String, Void>("lite.kotlin.topLevelName")
    }
}

object KtFiles {
    fun packageName(file: KtFile): String =
        file.children.filterIsInstance<KtPackageDirective>().firstOrNull()?.packageName.orEmpty()

    fun topLevel(file: KtFile): List<KtNamedDeclaration> = cached(file, TOP_LEVEL) {
        file.children.filterIsInstance<KtNamedDeclaration>() +
            // `.kts` scripts wrap declarations in a SCRIPT/BLOCK node
            file.children.filter { it !is KtNamedDeclaration }.flatMap { c -> c.children.flatMap { it.children.filterIsInstance<KtNamedDeclaration>() } }
    }

    /** Kotlin files of [pkg] visible from [place] (its Gradle project and libraries, see [KtProjectScope]). */
    fun filesInPackage(place: PsiElement, pkg: String): List<KtFile> {
        val pm = PsiManager.getInstance(place.project)
        return KtPackageIndex().files(place.project, pkg, KtProjectScope.of(place)).mapNotNull { pm.findFile(it) as? KtFile }
    }

    fun topLevelInPackage(place: PsiElement, pkg: String, name: String): List<KtNamedDeclaration> =
        filesInPackage(place, pkg).flatMap { f -> topLevel(f).filter { it.name == name } }

    fun topLevelNamed(place: PsiElement, name: String): List<KtNamedDeclaration> {
        val pm = PsiManager.getInstance(place.project)
        return KtTopLevelNameIndex().files(place.project, name, KtProjectScope.of(place)).mapNotNull { pm.findFile(it) as? KtFile }
            .flatMap { f -> topLevel(f).filter { it.name == name } }
    }

    private val TOP_LEVEL = Key.create<CachedValue<List<KtNamedDeclaration>>>("lite.kt.topLevel")

    fun <T> cached(e: PsiElement, key: Key<CachedValue<T>>, compute: () -> T): T = LiteCache.cached(e, key, KtLibraryOracle, compute)

    fun <T> cachedNonRecursive(e: PsiElement, key: Key<CachedValue<T>>, onCycle: T, compute: () -> T): T =
        LiteCache.cachedNonRecursive(e, key, KtLibraryOracle, onCycle, compute)
}

/** Jars (libraries, JDK) never change during a session; a new classpath invalidates them. */
object KtLibraryOracle : LibraryOracle {
    override fun isLibrary(e: PsiElement): Boolean {
        val vf = e.containingFile?.originalFile?.virtualFile ?: return false
        return vf.fileSystem is com.intellij.openapi.vfs.JarFileSystem ||
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(e.project).isInLibrary(vf)
    }

    override fun tracker(e: PsiElement): ModificationTracker = GradleProjects.getInstance(e.project).modificationTracker
}
