package me.kevinlot.lite.rust.debug

import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PsiDumpTest : BasePlatformTestCase() {
    fun `test dump`() {
        val src = System.getenv("RS_DUMP") ?: return
        val f = myFixture.configureByText("a.rs", java.io.File(src).readText())
        java.io.File("$src.psi").writeText(DebugUtil.psiToString(f, true, false))
    }
}
