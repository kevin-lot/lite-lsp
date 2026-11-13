package me.kevinlot.lite.python

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.python.parser.PyFile
import me.kevinlot.lite.python.psi.PyFunction
import me.kevinlot.lite.python.psi.PyReferenceExpr
import me.kevinlot.lite.python.resolve.PyEnvironments
import me.kevinlot.lite.python.resolve.PyProjectEnv
import java.io.File

class PyTest : BasePlatformTestCase() {

    private fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        val ref = file.findReferenceAt(refOffset) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        if (defFile == null) return assertEmpty(targets)
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }}", targets.any { t ->
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile) && (off == defOffset || defOffset == 0)
        })
    }

    private fun one(code: String) = check("main.py" to code)

    // ---------------------------------------------------------------- scopes
    fun `test local`() = one("def f():\n    <def>x = 1\n    return <ref>x\n")
    fun `test parameter`() = one("def f(<def>a):\n    return <ref>a\n")
    fun `test global from function`() = one("<def>LIMIT = 3\ndef f():\n    return <ref>LIMIT\n")
    fun `test local assigned later in the function`() = one("def f():\n    g = lambda: <ref>y\n    <def>y = 2\n")
    fun `test class body not visible in method`() = one("<def>v = 1\nclass A:\n    v = 2\n    def m(self):\n        return <ref>v\n")
    fun `test for target`() = one("for <def>i in range(3):\n    print(<ref>i)\n")
    fun `test lambda parameter`() = one("f = lambda <def>k: <ref>k\n")
    fun `test unresolved`() = one("print(<ref>nope)\n")

    // ---------------------------------------------------------------- classes and types
    fun `test method via self`() = one("class A:\n    def <def>m(self): pass\n    def n(self):\n        self.<ref>m()\n")
    fun `test instance attribute`() = one("class A:\n    def __init__(self):\n        self.<def>size = 1\n    def n(self):\n        return self.<ref>size\n")
    fun `test inherited method`() = one("class B:\n    def <def>m(self): pass\nclass A(B): pass\nA().<ref>m()\n")
    fun `test annotated parameter`() = one("class A:\n    def <def>m(self): pass\ndef f(a: A):\n    a.<ref>m()\n")
    fun `test optional annotation`() = one("from typing import Optional\nclass A:\n    <def>x = 1\ndef f(a: Optional[A]):\n    return a.<ref>x\n")
    fun `test pep604 union`() = one("class A:\n    <def>x = 1\ndef f(a: A | None):\n    return a.<ref>x\n")
    fun `test return annotation chain`() = one("class B:\n    def <def>done(self): pass\nclass A:\n    def next(self) -> 'B': return B()\nA().next().<ref>done()\n")
    fun `test list element in for`() = one("class A:\n    <def>x = 1\ndef f(xs: list[A]):\n    for a in xs:\n        a.<ref>x\n")
    fun `test assigned instance`() = one("class A:\n    def <def>m(self): pass\na = A()\na.<ref>m()\n")
    fun `test class attribute via class`() = one("class A:\n    <def>K = 1\nA.<ref>K\n")
    fun `test annotated self attribute`() = one("class A:\n    <def>x = 1\nclass R:\n    def __init__(self):\n        self.items: list[A] = []\n    def f(self):\n        for a in self.items:\n            a.<ref>x\n")
    fun `test dataclass field`() = one("from dataclasses import dataclass\n@dataclass\nclass P:\n    <def>x: float\ndef f(p: P):\n    return p.<ref>x\n")

    // ---------------------------------------------------------------- modules and imports
    fun `test import module`() = check("pkg/util.py" to "<def>def helper(): pass", "main.py" to "import pkg.util\npkg.util.<ref>helper()")
    fun `test from import`() = check("pkg/util.py" to "def <def>helper(): pass", "main.py" to "from pkg.util import helper\n<ref>helper()")
    fun `test import alias`() = check("pkg/util.py" to "def <def>helper(): pass", "main.py" to "from pkg.util import helper as h\n<ref>h()")
    fun `test import segment`() = check("pkg/util.py" to "<def>x = 1", "main.py" to "from pkg.<ref>util import x")
    fun `test relative import`() = check("pkg/a.py" to "class <def>A: pass", "pkg/b.py" to "from .a import A\n<ref>A()")
    fun `test package init reexport`() = check(
        "pkg/__init__.py" to "from .impl import Thing", "pkg/impl.py" to "class <def>Thing: pass", "main.py" to "from pkg import Thing\n<ref>Thing()",
    )
    fun `test star import`() = check("pkg/util.py" to "def <def>tool(): pass", "main.py" to "from pkg.util import *\n<ref>tool()")
    fun `test src layout`() = check("proj/pyproject.toml" to "", "proj/src/lib/core.py" to "def <def>run(): pass", "proj/app.py" to "from lib.core import run\n<ref>run()")

    // ---------------------------------------------------------------- editing
    fun `test rename method follows calls`() {
        myFixture.configureByText("a.py", "class A:\n    def ar<caret>ea(self): return 1\n\nA().area()\n")
        myFixture.renameElementAtCaret("size")
        myFixture.checkResult("class A:\n    def size(self): return 1\n\nA().size()\n")
    }

    fun `test rename local keeps other scopes`() {
        myFixture.configureByText("a.py", "def f():\n    x<caret> = 1\n    return x\n\ndef g():\n    x = 2\n    return x\n")
        myFixture.renameElementAtCaret("y")
        myFixture.checkResult("def f():\n    y = 1\n    return y\n\ndef g():\n    x = 2\n    return x\n")
    }

    fun `test find usages honours scope`() {
        val util = myFixture.addFileToProject("pkg/util.py", "def helper(): pass\n")
        myFixture.addFileToProject("a.py", "from pkg.util import helper\nhelper()\n")
        myFixture.addFileToProject("b.py", "from pkg.util import helper\nhelper()\nhelper()\n")
        val fn = PsiTreeUtil.findChildOfType(util, PyFunction::class.java)!!
        assertEquals(5, ReferencesSearch.search(fn, GlobalSearchScope.projectScope(project)).findAll().size)
        val onlyA = GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("a.py")))
        assertEquals(2, ReferencesSearch.search(fn, onlyA).findAll().size)
    }

    // ---------------------------------------------------------------- real code (read-only, optional)

    /** LITE_PYTHON_CORPUS=<dir>: every .py must parse. */
    fun `test corpus parses`() {
        val dir = System.getenv("LITE_PYTHON_CORPUS")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val files = dir.walkTopDown().onEnter { it.name !in setOf("test", "tests", "__pycache__", "site-packages", "lib2to3") }.filter { it.extension == "py" }.toList()
        val broken = files.mapNotNull { f ->
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: return@mapNotNull null
            PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.let { "${f.path.removePrefix(dir.path)}: ${it.errorDescription}" }
        }
        println("python corpus: ${files.size - broken.size}/${files.size} files without errors\n" + broken.take(15).joinToString("\n"))
        assertTrue(broken.size * 100 <= files.size)
    }

    /** LITE_PYTHON_PROJECT=<dir> (+ LITE_PYTHON_STDLIB): resolution rate of a whole project. */
    fun `test whole project`() {
        val dir = System.getenv("LITE_PYTHON_PROJECT")?.let(::File) ?: return
        val stdlib = System.getenv("LITE_PYTHON_STDLIB")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path, *listOfNotNull(stdlib).toTypedArray())
        val files = dir.walkTopDown().filter { it.extension == "py" || it.name == "pyproject.toml" }.toList()
        val added = files.map { myFixture.addFileToProject("proj/" + it.relativeTo(dir).path, it.readText()) }
        PyEnvironments.getInstance(project).setForTests(PyProjectEnv("/src/proj", listOf("/src/proj", "/src/proj/src"), listOfNotNull(stdlib)))
        var total = 0; val unresolved = mutableMapOf<String, Int>()
        added.filter { it is PyFile }.forEach { f ->
            PsiTreeUtil.findChildrenOfType(f, PyReferenceExpr::class.java).forEach { r ->
                total++
                if (r.reference?.resolve() == null) unresolved.merge(r.referencedName.orEmpty(), 1, Int::plus)
            }
        }
        println("PYPROJECT ${total - unresolved.values.sum()}/$total resolved; unresolved: " + unresolved.entries.sortedByDescending { it.value }.joinToString { "${it.key}×${it.value}" })
    }
}
