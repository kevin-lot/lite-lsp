package me.kevinlot.lite.kotlin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import me.kevinlot.lite.kotlin.psi.KtFunction
import me.kevinlot.lite.kotlin.psi.KtProperty

class KtFindUsagesTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    private fun usages(target: PsiElement, scope: SearchScope): List<String> =
        ReferencesSearch.search(target, scope).findAll().map { it.element.containingFile.name }.sorted()

    private fun setup(): KtFunction {
        val util = myFixture.addFileToProject("src/lib/Util.kt", "package lib\nfun helper() {}")
        myFixture.addFileToProject("src/app/A.kt", "package app\nimport lib.helper\nfun f() { helper(); helper() }")
        myFixture.addFileToProject("src/app/B.kt", "package app\nfun g() = lib.helper()")
        return PsiTreeUtil.findChildOfType(util, KtFunction::class.java)!!
    }

    fun `test project scope finds every usage`() {
        val fn = setup()
        assertEquals(listOf("A.kt", "A.kt", "A.kt", "B.kt"), usages(fn, GlobalSearchScope.projectScope(project)))
    }

    fun `test restricted scope keeps only its files`() {
        val fn = setup()
        val onlyB = GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("src/app/B.kt")))
        assertEquals(listOf("B.kt"), usages(fn, onlyB))
    }

    fun `test local property has a local use scope`() {
        myFixture.configureByText("a.kt", "fun f() { val x = 1; x + x }\nfun g() { val x = 2; x }")
        val x = PsiTreeUtil.findChildrenOfType(myFixture.file, KtProperty::class.java).first()
        assertTrue(x.useScope is LocalSearchScope)
        assertEquals(2, ReferencesSearch.search(x).findAll().size)
    }

    /** Kotlin reads Java getters as properties: `p.name` is a usage of `getName()`, within the chosen scope. */
    fun `test usages of a java getter from kotlin`() {
        val java = myFixture.addFileToProject("src/j/Person.java", "package j; public class Person { public String getName() { return \"\"; } }")
        myFixture.addFileToProject("src/app/A.kt", "package app\nimport j.Person\nfun f(p: Person) = p.name")
        myFixture.addFileToProject("src/app/B.kt", "package app\nimport j.Person\nfun g(p: Person) = p.name + p.getName()")
        val getter = PsiTreeUtil.findChildOfType(java, PsiClass::class.java)!!.findMethodsByName("getName", false).single()
        assertEquals(listOf("A.kt", "B.kt", "B.kt"), usages(getter, GlobalSearchScope.projectScope(project)))
        val onlyA = GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("src/app/A.kt")))
        assertEquals(listOf("A.kt"), usages(getter, onlyA))
    }
}
