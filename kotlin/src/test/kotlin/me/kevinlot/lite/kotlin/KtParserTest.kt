package me.kevinlot.lite.kotlin

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class KtParserTest : BasePlatformTestCase() {

    private fun errors(file: PsiFile): List<String> = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { err ->
        val line = file.text.substring(0, err.textOffset).count { it == '\n' } + 1
        "${file.name}:$line ${err.errorDescription}"
    }

    fun `test sample parses without errors`() {
        val f = myFixture.configureByText("sample.kt", File("src/test/testData/parser/sample.kt").readText())
        assertEquals(KtFile::class.java, f.javaClass)
        assertEquals("", errors(f).joinToString("\n"))
    }

    fun `test script parses top level statements`() {
        val f = myFixture.configureByText("build.gradle.kts", "plugins { kotlin(\"jvm\") }\nval x = 1\nprintln(x)\n")
        assertEquals("", errors(f).joinToString("\n"))
    }

    /** LITE_KOTLIN_CORPUS=<dir>: every .kt/.kts below it must parse (read-only). */
    fun `test corpus parses`() {
        val dir = System.getenv("LITE_KOTLIN_CORPUS")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val files = dir.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", "node_modules", ".git") }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }.toList()
        val broken = files.flatMap { f ->
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!
            errors(psiManager.findFile(vf)!!).take(1)
        }
        println("kotlin corpus: ${files.size - broken.size}/${files.size} files without errors\n" + broken.joinToString("\n"))
        assertEmpty(broken)
    }
}
