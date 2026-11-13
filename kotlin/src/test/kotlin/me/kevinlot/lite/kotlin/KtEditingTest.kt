package me.kevinlot.lite.kotlin

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

class KtEditingTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    fun `test rename function`() {
        myFixture.configureByText("a.kt", "fun gre<caret>et() {}\nfun f() { greet(); ::greet }")
        myFixture.renameElementAtCaret("hello")
        myFixture.checkResult("fun hello() {}\nfun f() { hello(); ::hello }")
    }

    fun `test rename local keeps shadowed`() {
        myFixture.configureByText("a.kt", "fun f() { val x<caret> = 1; println(x); run { val x = 2; x } }")
        myFixture.renameElementAtCaret("y")
        myFixture.checkResult("fun f() { val y = 1; println(y); run { val x = 2; x } }")
    }

    fun `test rename property used through receiver and named argument`() {
        myFixture.configureByText("a.kt", "class A(val coun<caret>t: Int) { fun twice() = count * 2 }\nfun f(a: A) = a.count + A(count = 1).count")
        myFixture.renameElementAtCaret("size")
        myFixture.checkResult("class A(val size: Int) { fun twice() = size * 2 }\nfun f(a: A) = a.size + A(size = 1).size")
    }

    fun `test rename class across files and imports`() {
        myFixture.addFileToProject("lib/Thing.kt", "package lib\nclass Thing")
        myFixture.addFileToProject("app/Main.kt", "package app\nimport lib.Thing\nfun f(t: Thing) = Thing()")
        myFixture.configureFromTempProjectFile("lib/Thing.kt")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("Thing") + 1)
        myFixture.renameElementAtCaret("Item")
        val main = psiManager.findFile(myFixture.findFileInTempDir("app/Main.kt"))!!.text
        assertEquals("package app\nimport lib.Item\nfun f(t: Item) = Item()", main)
    }

    /** Highlighting + every reference of real files (LITE_KOTLIN_CORPUS): no exception, bounded time. */
    fun `test corpus highlighting and resolve`() {
        val dir = System.getenv("LITE_KOTLIN_CORPUS")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val files = dir.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git") }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }.toList()
        var refs = 0
        var resolved = 0
        val start = System.nanoTime()
        for (f in files) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!
            myFixture.configureByText(f.name, String(vf.contentsToByteArray()))
            myFixture.doHighlighting()
            PsiTreeUtil.processElements(myFixture.file) { e ->
                e.references.forEach { r: PsiReference -> refs++; if (r.resolve() != null) resolved++ }
                true
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("kotlin corpus: ${files.size} files, $resolved/$refs references resolved (isolated files, no classpath), $ms ms")
        assertTrue(ms < 120_000)
    }
}
