package me.kevinlot.lite.kotlin

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import me.kevinlot.lite.kotlin.psi.KtClass
import me.kevinlot.lite.kotlin.psi.KtFunction
import me.kevinlot.lite.kotlin.psi.KtParameter
import me.kevinlot.lite.kotlin.psi.KtProperty

class KtInheritanceTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    private val api = """
        package api
        interface Repo {
            val size: Int
            fun find(id: String): String?
            fun find(id: String, strict: Boolean): String?
        }
    """.trimIndent()

    private val impl = """
        package impl
        import api.Repo
        abstract class BaseRepo : Repo { override fun find(id: String, strict: Boolean): String? = null }
        class DbRepo(override val size: Int) : BaseRepo() { override fun find(id: String): String? = id }
        class Holder(val repos: List<Repo>)
        val anonymous = object : Repo {
            override val size = 0
            override fun find(id: String): String? = null
            override fun find(id: String, strict: Boolean): String? = null
        }
    """.trimIndent()

    private fun setup(): PsiElement {
        myFixture.addFileToProject("src/impl/Impl.kt", impl)
        return myFixture.addFileToProject("src/api/Repo.kt", api)
    }

    private inline fun <reified T : PsiNamedElement> PsiElement.named(name: String, index: Int = 0): T =
        PsiTreeUtil.findChildrenOfType(this, T::class.java).filter { it.name == name }[index]

    private fun implementations(element: PsiElement): List<String> =
        DefinitionsScopedSearch.search(element).findAll().map { it.text.substringBefore('{').substringBefore('=').trim() }.sorted()

    fun `test implementations of an interface`() {
        val repo = setup().named<KtClass>("Repo")
        assertEquals(listOf("abstract class BaseRepo : Repo", "class DbRepo(override val size: Int) : BaseRepo()", "object : Repo"), implementations(repo))
    }

    fun `test implementations of an interface function keep the overload`() {
        val find = setup().named<KtFunction>("find")
        assertEquals(listOf("override fun find(id: String): String?", "override fun find(id: String): String?"), implementations(find))
    }

    fun `test implementations of an interface property`() {
        val size = setup().named<KtProperty>("size")
        assertEquals(listOf("override val size", "override val size: Int"), implementations(size))
    }

    fun `test override points to the interface member`() {
        setup()
        val file = myFixture.configureFromTempProjectFile("src/impl/Impl.kt")
        val override = file.named<KtFunction>("find", 1)
        val targets = (override.reference as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
        assertEquals(listOf("fun find(id: String): String?"), targets.map { it.text })
        assertEquals("val size: Int", file.named<KtParameter>("size").reference?.resolve()?.text)
        assertNull(file.named<KtClass>("DbRepo").reference)
    }

    fun `test usages of an interface member include its overrides`() {
        val find = setup().named<KtFunction>("find")
        assertEquals(2, ReferencesSearch.search(find).findAll().size)
    }

    fun `test kotlin class implementing a java interface`() {
        val java = myFixture.addFileToProject("src/j/Shape.java", "package j; public interface Shape { double area(); String getName(); }")
        myFixture.addFileToProject("src/app/Square.kt", "package app\nimport j.Shape\nclass Square : Shape {\n    override fun area() = 1.0\n    override val name = \"\"\n}")
        val shape = PsiTreeUtil.findChildOfType(java, PsiClass::class.java)!!
        assertEquals(listOf("class Square : Shape"), implementations(shape))
        assertEquals(listOf("override fun area()"), implementations(shape.findMethodsByName("area", false).single()))
        assertEquals(listOf("override val name"), implementations(shape.findMethodsByName("getName", false).single()))
        val square = psiManager.findFile(myFixture.findFileInTempDir("src/app/Square.kt"))!!
        assertEquals("area", (square.named<KtFunction>("area").reference?.resolve() as? PsiNamedElement)?.name)
        assertEquals("getName", (square.named<KtProperty>("name").reference?.resolve() as? PsiNamedElement)?.name)
    }

    fun `test click on an override goes to the interface member`() {
        myFixture.configureByText("a.kt", "interface Api { fun run() }\nclass Impl : Api { override fun ru<caret>n() {} }")
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        assertEquals(listOf("fun run()"), targets.map { it.text })
    }

    fun `test rename from the interface renames the overrides`() {
        myFixture.addFileToProject("src/impl/Impl.kt", "package impl\nimport api.Api\nclass Impl : Api { override fun run() {} }\nfun f(i: Impl) = i.run()")
        myFixture.configureByText("Api.kt", "package api\ninterface Api { fun ru<caret>n() }")
        myFixture.renameElementAtCaret("start")
        val impl = psiManager.findFile(myFixture.findFileInTempDir("src/impl/Impl.kt"))!!.text
        assertEquals("package impl\nimport api.Api\nclass Impl : Api { override fun start() {} }\nfun f(i: Impl) = i.start()", impl)
    }

    fun `test rename from an override renames the interface`() {
        myFixture.configureByText("a.kt", "interface Api { fun run() }\nclass Impl : Api { override fun ru<caret>n() {} }\nfun f(a: Api) = a.run()")
        myFixture.renameElementAtCaret("start")
        myFixture.checkResult("interface Api { fun start() }\nclass Impl : Api { override fun start() {} }\nfun f(a: Api) = a.start()")
    }
}
