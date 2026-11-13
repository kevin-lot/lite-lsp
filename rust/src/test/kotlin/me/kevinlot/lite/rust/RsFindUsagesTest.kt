package me.kevinlot.lite.rust

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.rust.psi.RsFunction
import me.kevinlot.lite.rust.psi.RsPatBinding

/** Find Usages = ReferencesSearch with the scope picked in the dialog: it must be honoured. */
class RsFindUsagesTest : BasePlatformTestCase() {

    private fun usages(target: PsiNamedElement, scope: SearchScope): List<String> =
        ReferencesSearch.search(target, scope).findAll().map { it.element.containingFile.name }.sorted()

    private fun setup(): RsFunction {
        myFixture.addFileToProject("src/lib.rs", "mod util; mod a; mod b;")
        val util = myFixture.addFileToProject("src/util.rs", "pub fn helper() {}")
        myFixture.addFileToProject("src/a.rs", "fn f() { crate::util::helper(); crate::util::helper(); }")
        myFixture.addFileToProject("src/b.rs", "fn g() { crate::util::helper(); }")
        return PsiTreeUtil.findChildOfType(util, RsFunction::class.java)!!
    }

    fun `test project scope finds every usage`() {
        val fn = setup()
        assertEquals(listOf("a.rs", "a.rs", "b.rs"), usages(fn, GlobalSearchScope.projectScope(project)))
    }

    fun `test restricted scope keeps only its files`() {
        val fn = setup()
        val onlyB = GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("src/b.rs")))
        assertEquals(listOf("b.rs"), usages(fn, onlyB))
    }

    fun `test local binding has a local use scope`() {
        myFixture.configureByText("main.rs", "fn f() { let x = 1; x + x; } fn g() { let x = 2; x; }")
        val x = PsiTreeUtil.findChildrenOfType(myFixture.file, RsPatBinding::class.java).first()
        assertTrue(x.useScope is LocalSearchScope)
        assertEquals(2, ReferencesSearch.search(x).findAll().size)
    }

    /** Name-based fallbacks (macros, unknown receivers, imports) stay in the file's Cargo project. */
    fun `test macro lookup stays in its cargo project`() {
        myFixture.addFileToProject("app/Cargo.toml", "[package]\nname = \"app\"")
        myFixture.addFileToProject("app/src/lib.rs", "mod util;\nfn f() { shout!(); }")
        myFixture.addFileToProject("app/src/util.rs", "#[macro_export] macro_rules! shout { () => {} }")
        myFixture.addFileToProject("other/Cargo.toml", "[package]\nname = \"other\"")
        myFixture.addFileToProject("other/src/lib.rs", "#[macro_export] macro_rules! shout { () => {} }")
        val file = myFixture.configureFromTempProjectFile("app/src/lib.rs")
        val ref = file.findReferenceAt(file.text.indexOf("shout")) as com.intellij.psi.PsiPolyVariantReference
        assertEquals(listOf("util.rs"), ref.multiResolve(false).mapNotNull { it.element?.containingFile?.name })
    }
}
