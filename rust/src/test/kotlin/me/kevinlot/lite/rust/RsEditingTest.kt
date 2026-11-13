package me.kevinlot.lite.rust

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class RsEditingTest : BasePlatformTestCase() {

    // ---------------------------------------------------------------- rename

    fun `test rename function updates calls`() {
        myFixture.configureByText("lib.rs", "fn fo<caret>o() {} fn main() { foo(); self::foo(); }")
        myFixture.renameElementAtCaret("bar")
        myFixture.checkResult("fn bar() {} fn main() { bar(); self::bar(); }")
    }

    fun `test rename local keeps other bindings`() {
        myFixture.configureByText("lib.rs", "fn f() { let x<caret> = 1; let y = x + 1; { let x = 2; x; } }")
        myFixture.renameElementAtCaret("z")
        myFixture.checkResult("fn f() { let z = 1; let y = z + 1; { let x = 2; x; } }")
    }

    fun `test rename field through method access`() {
        myFixture.configureByText("lib.rs", "struct S { va<caret>l: i32 } impl S { fn get(&self) -> i32 { self.val } } fn f(s: S) { S { val: 1 }; s.val; }")
        myFixture.renameElementAtCaret("value")
        myFixture.checkResult("struct S { value: i32 } impl S { fn get(&self) -> i32 { self.value } } fn f(s: S) { S { value: 1 }; s.value; }")
    }

    fun `test rename across files`() {
        myFixture.addFileToProject("src/util.rs", "pub fn helper() {}")
        myFixture.addFileToProject("src/lib.rs", "mod util; use util::helper; fn f() { helper(); }")
        myFixture.configureFromTempProjectFile("src/util.rs")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("helper") + 1)
        myFixture.renameElementAtCaret("assist")
        myFixture.checkResult("pub fn assist() {}")
        assertEquals("mod util; use util::assist; fn f() { assist(); }", myFixture.findFileInTempDir("src/lib.rs").let { psiManager.findFile(it)!!.text })
    }

    // ---------------------------------------------------------------- imports

    // ---------------------------------------------------------------- robustness

    /** Highlighting + every reference on 70 real-world files: must not throw, must stay fast. */
    fun `test highlighting and resolve on upstream fixtures`() {
        val files = File("src/test/testData/parser").listFiles { f -> f.extension == "rs" }!!
        val start = System.nanoTime()
        for (f in files) {
            myFixture.configureByText(f.name, f.readText())
            myFixture.doHighlighting()
            PsiTreeUtil.processElements(myFixture.file) { e -> e.references.forEach(PsiReference::resolve); true }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("too slow: $ms ms", ms < 60_000)
    }
}
