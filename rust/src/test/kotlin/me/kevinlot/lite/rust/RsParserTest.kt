package me.kevinlot.lite.rust

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Upstream intellij-rust "complete" fixtures: valid Rust that must parse without any error. */
class RsParserTest : BasePlatformTestCase() {

    fun `test upstream fixtures parse without errors`() {
        val dir = File("src/test/testData/parser")
        // Upstream expects a parse error in these two (invalid on purpose).
        val expectedErrors = setOf("block_cast_expr.rs", "trait_aliases.rs")
        val files = dir.listFiles { f -> f.extension == "rs" && f.name !in expectedErrors }!!.sortedBy { it.name }
        assertTrue(files.size > 50)

        val failures = files.mapNotNull { f ->
            val psi = myFixture.configureByText(f.name, f.readText())
            assertEquals(RsFile::class.java, psi.javaClass)
            val err = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java) ?: return@mapNotNull null
            val line = psi.text.substring(0, err.textOffset).count { it == '\n' } + 1
            "${f.name}:$line ${err.errorDescription}"
        }
        assertEquals("", failures.joinToString("\n"))
    }

    fun `test file type is registered for rs`() {
        val psi = myFixture.configureByText("main.rs", "fn main() {}")
        assertEquals(RsFileType, psi.fileType)
    }
}
