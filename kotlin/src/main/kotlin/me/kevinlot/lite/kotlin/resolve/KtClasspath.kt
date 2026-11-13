package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import me.kevinlot.lite.kotlin.gradle.GradleProjects
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything derived from the classpath (JDK, Gradle jars), memoized for the project and dropped as a
 * whole when roots or jars change. Lookups by name are hot (every unresolved name tries the default
 * imports), so each answer — including "not found" — is computed once.
 */
class KtClasspath private constructor(private val project: Project) {
    private val facade = JavaPsiFacade.getInstance(project)
    private val psiManager = PsiManager.getInstance(project)

    /** Roots of the Gradle jars (the synthetic library is not on [JavaPsiFacade]'s classpath). */
    private val jarRoots: List<VirtualFile> by lazy {
        val jfs = JarFileSystem.getInstance()
        GradleProjects.getInstance(project).jarUrls.mapNotNull { jfs.findFileByPath(it.removePrefix("jar://")) }
    }
    private val classes = ConcurrentHashMap<String, Optional<PsiClass>>()
    private val packageDirs = ConcurrentHashMap<String, List<VirtualFile>>()
    private val jvm = ConcurrentHashMap<String, List<PsiClass>>()
    private val facades = ConcurrentHashMap<String, Map<String, List<PsiMember>>>()

    /** `a.b.C` (or nested `a.b.C.D`) in [scope], then in the Gradle jars. */
    fun findClass(fqName: String, scope: GlobalSearchScope): PsiClass? =
        classes.getOrPut("$scope|$fqName") { Optional.ofNullable(facade.findClass(fqName, scope) ?: jarClass(fqName)) }.orElse(null)

    /** Directories of package [pkg] inside the Gradle jars. */
    fun jarDirectories(pkg: String): List<VirtualFile> = packageDirs.getOrPut(pkg) {
        jarRoots.mapNotNull { root -> if (pkg.isEmpty()) root else root.findFileByRelativePath(pkg.replace('.', '/')) }.filter { it.isDirectory }
    }

    /**
     * JVM view of a class file. IntelliJ decompiles Kotlin-compiled classes into a Kotlin file exposing no
     * Java classes; the Java class finder (by qualified name) always gives the JVM classes and methods.
     */
    fun jvmClasses(vf: VirtualFile): List<PsiClass> = jvm.getOrPut(vf.url) {
        (psiManager.findFile(vf) as? PsiClassOwner)?.classes?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(vf.path.substringAfter("!/", "").removeSuffix(".class").replace('/', '.').takeIf { it.isNotEmpty() }
                ?.let { facade.findClass(it, GlobalSearchScope.allScope(project)) })
    }

    /** Static functions of the compiled Kotlin facades (`CollectionsKt`, `StandardKt__StandardKt`…) of [pkg], by name. */
    fun facadeFunctions(pkg: String, scope: GlobalSearchScope): Map<String, List<PsiMember>> = facades.getOrPut("$scope|$pkg") {
        val dirs = facade.findPackage(pkg)?.getDirectories(scope).orEmpty().map { it.virtualFile } + jarDirectories(pkg)
        dirs.distinct()
            .flatMap { dir -> dir.children.filter { it.extension == "class" && it.nameWithoutExtension.contains("Kt") } }
            .flatMap { jvmClasses(it) }
            .flatMap { cls -> cls.methods.filter { it.hasModifierProperty(PsiModifier.STATIC) } }
            .groupBy { it.name }
    }

    /** `a/b/C.class`, then nested `a/b/C$D.class`, in the Gradle jars. */
    private fun jarClass(fqName: String): PsiClass? {
        val parts = fqName.split('.')
        for (split in parts.size - 1 downTo 1) {
            val names = parts.drop(split)
            for (dir in jarDirectories(parts.take(split).joinToString("."))) {
                val file = dir.findChild(names.joinToString("$") + ".class") ?: continue
                val top = jvmClasses(file).firstOrNull() ?: continue
                return names.drop(1).fold(top as PsiClass?) { c, n -> c?.findInnerClassByName(n, false) }
            }
        }
        return null
    }

    companion object {
        private val KEY = Key.create<CachedValue<KtClasspath>>("lite.kt.classpath")

        fun of(project: Project): KtClasspath = CachedValuesManager.getManager(project).getCachedValue(project, KEY, {
            CachedValueProvider.Result.create(KtClasspath(project), ProjectRootModificationTracker.getInstance(project),
                GradleProjects.getInstance(project).modificationTracker)
        }, false)
    }
}
