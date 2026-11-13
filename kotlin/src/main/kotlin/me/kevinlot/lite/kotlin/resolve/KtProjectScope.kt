package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import me.kevinlot.lite.core.ProjectLayout

/** Kotlin's project boundaries ([ProjectLayout]): Gradle settings (multi-module), else build script. */
object KtProjectScope {
    val LAYOUT = ProjectLayout(
        rootManifests = listOf("settings.gradle.kts", "settings.gradle"),
        moduleManifests = listOf("build.gradle.kts", "build.gradle"),
        outputDirs = setOf("bin", "build", "out", ".gradle", ".idea", "node_modules"),
    )
    private val KEY = Key.create<CachedValue<GlobalSearchScope>>("lite.kt.scope")

    fun of(e: PsiElement): GlobalSearchScope = LAYOUT.scopeOf(e, KEY, KtLibraryOracle)
    fun rootOf(project: Project, file: VirtualFile?): String? = LAYOUT.rootOf(project, file)
}
