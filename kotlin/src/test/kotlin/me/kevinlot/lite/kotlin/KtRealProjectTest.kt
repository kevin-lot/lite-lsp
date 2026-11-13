package me.kevinlot.lite.kotlin

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression
import java.io.File

/** Dev tool: LITE_KOTLIN_PROJECT=<dir> loads all its .kt files together (read-only) and lists unresolved names. */
class KtRealProjectTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    fun `test whole project`() {
        val dir = System.getenv("LITE_KOTLIN_PROJECT")?.let(::File) ?: return
        val files = dir.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git", "bin") }.filter { it.isFile && it.extension == "kt" }.toList()
        // Same classpath as the plugin: jars declared by the build script, found in the Gradle cache.
        val gradleHome = File(System.getProperty("user.home"), ".gradle")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, gradleHome.path)
        val jars = me.kevinlot.lite.kotlin.gradle.GradleProjects.jarsFor(File(dir, "build.gradle.kts").takeIf { it.isFile })
        jars.forEach { com.intellij.testFramework.PsiTestUtil.addLibrary(module, it.nameWithoutExtension, it.parent, it.name) }
        val added = files.map { myFixture.addFileToProject(it.relativeTo(dir).path, it.readText()) }
        val samples = StringBuilder()
        var total = 0
        val unresolved = mutableMapOf<String, Int>()
        for (f in added) {
            val psi = PsiManager.getInstance(project).findFile(f.virtualFile)!!
            PsiTreeUtil.findChildrenOfType(psi, KtNameReferenceExpression::class.java).filter { it.reference != null }.forEach { r ->
                total++
                if (r.reference?.resolve() == null) {
                    val n = r.referencedName.orEmpty()
                    if (unresolved.merge(n, 1, Int::plus) == 1 && n in (System.getenv("LITE_SHOW") ?: "").split(',')) {
                        val line = psi.text.substring(0, r.textOffset).count { it == '\n' }
                        samples.append("  [$n] ${f.name}:${line + 1}: ${psi.text.lines()[line].trim()}  (parent=${r.parent.node.elementType})\n")
                    }
                }
            }
        }
        val missing = unresolved.values.sum()
        println("PROJECT ${dir.name}: ${jars.size} jars, ${files.size} files, ${total - missing}/$total resolved; unresolved: " +
            unresolved.entries.sortedByDescending { it.value }.take(40).joinToString { "${it.key}×${it.value}" } + "\nSAMPLES\n" + samples)
    }
}
