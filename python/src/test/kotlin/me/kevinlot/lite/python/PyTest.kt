package me.kevinlot.lite.python

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import me.kevinlot.lite.python.ide.PyFindUsagesProvider
import me.kevinlot.lite.python.ide.PyNamesValidator
import me.kevinlot.lite.python.ide.PyRefactoringSupportProvider
import me.kevinlot.lite.python.ide.PySyntaxHighlighter
import me.kevinlot.lite.python.parser.PyFile
import me.kevinlot.lite.python.psi.PyFunction
import me.kevinlot.lite.python.psi.PyNamedDecl
import me.kevinlot.lite.python.psi.PyReferenceExpr
import me.kevinlot.lite.python.resolve.PyEnvironments
import me.kevinlot.lite.python.resolve.PyProjectEnv

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

    private fun annotated(code: String): String {
        myFixture.configureByText("a.py", code)
        return myFixture.doHighlighting().filter { it.forcedTextAttributesKey?.externalName?.startsWith("PY_") == true }.sortedBy { it.startOffset }
            .joinToString(" ") { "${it.text}:${it.forcedTextAttributesKey.externalName.removePrefix("PY_")}" }
    }

    private fun declarations(code: String): List<PyNamedDecl> =
        PsiTreeUtil.findChildrenOfType(myFixture.configureByText("a.py", code), PyNamedDecl::class.java).toList()

    private fun lexed(code: String): String {
        val highlighter = PySyntaxHighlighter()
        val lexer = highlighter.highlightingLexer.also { it.start(code) }
        val tokens = generateSequence { lexer.tokenType?.let { type -> (lexer.tokenText to highlighter.getTokenHighlights(type)).also { lexer.advance() } } }
        return tokens.mapNotNull { (text, keys) -> keys.firstOrNull()?.let { "$text:${it.externalName.removePrefix("PY_")}" } }.joinToString(" ")
    }

    private fun usages(declaration: PyNamedDecl): List<String> =
        ReferencesSearch.search(declaration).findAll().map { "${it.element.containingFile.name}:${it.element.textOffset}" }.sorted()

    // ---------------------------------------------------------------- scopes
    fun `test local`() = one("def f():\n    <def>x = 1\n    return <ref>x\n")
    fun `test parameter`() = one("def f(<def>a):\n    return <ref>a\n")
    fun `test global from function`() = one("<def>LIMIT = 3\ndef f():\n    return <ref>LIMIT\n")
    fun `test local assigned later in the function`() = one("def f():\n    g = lambda: <ref>y\n    <def>y = 2\n")
    fun `test name inside a stub file`() = check("lib.pyi" to "class <def>Shape: ...\ndef largest() -> <ref>Shape: ...\n")
    fun `test stub file is highlighted without error`() {
        myFixture.configureByText("lib.pyi", "class Shape:\n    area: float\n")
        myFixture.doHighlighting()
        assertEquals("LitePythonStub", myFixture.file.language.id)
    }
    fun `test an empty file has no element`() {
        listOf("empty.py", "empty.pyi", "pkg/__init__.py").forEach { name ->
            assertEmpty(name, myFixture.addFileToProject(name, "").children.toList())
        }
    }

    fun `test class attribute from the class body`() = one("class A:\n    <def>v = 1\n    w = <ref>v\n")
    fun `test closure variable`() = one("def outer():\n    <def>v = 1\n    def inner():\n        return <ref>v\n")
    fun `test comprehension variable`() = one("xs = [1]\nys = [<ref>x for <def>x in xs]\n")
    fun `test except target`() = one("try:\n    pass\nexcept ValueError as <def>e:\n    print(<ref>e)\n")
    fun `test nearest binding before the use`() = one("x = 1\n<def>x = 2\nprint(<ref>x)\nx = 3\n")
    fun `test nested function`() = one("def outer():\n    def <def>inner(): pass\n    <ref>inner()\n")
    fun `test tuple unpacking`() = one("<def>a, b = 1, 2\nprint(<ref>a)\n")
    fun `test walrus target`() = one("if (<def>n := 3) > 1:\n    print(<ref>n)\n")
    fun `test with target`() = one("with open('f') as <def>f:\n    <ref>f.read()\n")

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

    fun `test annotated class attribute`() = one("class B:\n    def <def>done(self): pass\nclass A:\n    b: B\n    def f(self):\n        self.b.<ref>done()\n")
    fun `test classmethod receiver`() = one("class A:\n    <def>K = 1\n    @classmethod\n    def make(cls):\n        return cls.<ref>K\n")
    fun `test cyclic inheritance ends`() = one("class A(B): pass\nclass B(A): pass\nA().<ref>nope\n")
    fun `test function return annotation`() = one("class A:\n    def <def>m(self): pass\ndef make() -> A: return A()\nmake().<ref>m()\n")
    fun `test generic base class`() = one("class Base:\n    def <def>m(self): pass\nclass Box(Base[int]): pass\nBox().<ref>m()\n")
    fun `test inherited instance attribute`() = one("class B:\n    def __init__(self):\n        self.<def>size = 1\nclass A(B): pass\nA().<ref>size\n")
    fun `test inherited through an import`() = check(
        "base.py" to "class Base:\n    def <def>m(self): pass", "main.py" to "from base import Base\nclass A(Base): pass\nA().<ref>m()",
    )
    fun `test list comprehension element`() = one("class A:\n    <def>x = 1\nys = [A() for _ in range(3)]\nys[0].<ref>x\n")
    fun `test list literal element`() = one("class A:\n    <def>x = 1\nxs = [A()]\nfor a in xs:\n    a.<ref>x\n")
    fun `test own member wins over the base`() = one("class B:\n    def m(self): pass\nclass A(B):\n    def <def>m(self): pass\nA().<ref>m()\n")
    fun `test parenthesized instance`() = one("class A:\n    def <def>m(self): pass\n(A()).<ref>m()\n")
    fun `test second base class`() = one("class B: pass\nclass C:\n    def <def>m(self): pass\nclass A(B, C): pass\nA().<ref>m()\n")
    fun `test self return type`() = one(
        "from typing import Self\nclass A:\n    def <def>m(self): pass\n    def clone(self) -> Self: return self\nA().clone().<ref>m()\n",
    )
    fun `test staticmethod has no receiver`() = one("class A:\n    def m(self): pass\n    @staticmethod\n    def f(x):\n        x.<ref>m()\n")
    fun `test subscript of an annotated list`() = one("class A:\n    <def>x = 1\ndef f(xs: list[A]):\n    return xs[0].<ref>x\n")
    fun `test typing union`() = one("from typing import Union\nclass A:\n    <def>x = 1\ndef f(a: Union[A, None]):\n    return a.<ref>x\n")
    fun `test untyped call is not inferred`() = one("class A:\n    def m(self): pass\ndef make(): return A()\nmake().<ref>m()\n")
    fun `test with item instance`() = one("class A:\n    def <def>m(self): pass\nwith A() as a:\n    a.<ref>m()\n")

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

    fun `test from package import module`() = check("pkg/util.py" to "def <def>helper(): pass", "main.py" to "from pkg import util\nutil.<ref>helper()")
    fun `test function level import`() = check(
        "pkg/util.py" to "def <def>helper(): pass", "main.py" to "def f():\n    from pkg.util import helper\n    <ref>helper()",
    )
    fun `test import binds the top package`() = check("pkg/__init__.py" to "<def>", "pkg/util.py" to "x = 1", "main.py" to "import pkg.util\n<ref>pkg.util.x")
    fun `test import roots are the project and its src folder`() {
        myFixture.addFileToProject("lib/setup.py", "")
        myFixture.addFileToProject("lib/src/shapes/__init__.py", "")
        val file = myFixture.addFileToProject("lib/tests/test_shapes.py", "").virtualFile
        val envs = PyEnvironments.getInstance(project)
        assertEquals("/src/lib", envs.projectRoot(file))
        assertEquals(listOf("/src/lib", "/src/lib/src"), envs.rootsFor(file))
        assertEmpty(envs.rootsFor(null))
    }
    fun `test module alias`() = check("pkg/util.py" to "def <def>helper(): pass", "main.py" to "import pkg.util as u\nu.<ref>helper()")
    fun `test parent relative import`() = check("pkg/a.py" to "class <def>A: pass", "pkg/sub/c.py" to "from ..a import A\n<ref>A()")
    fun `test project sources win over site packages`() {
        PyEnvironments.getInstance(project).setForTests(PyProjectEnv("/src/first", listOf("/src/first"), listOf("/src/first/.venv/site-packages")))
        check(
            "first/pyproject.toml" to "", "first/.venv/site-packages/shapes.py" to "def area(): pass",
            "first/shapes.py" to "def <def>area(): pass", "first/main.py" to "from shapes import area\n<ref>area()",
        )
    }
    fun `test relative import of a sibling module`() = check("pkg/a.py" to "<def>x = 1", "pkg/b.py" to "from . import a\na.<ref>x")
    fun `test site packages of the environment`() {
        PyEnvironments.getInstance(project).setForTests(PyProjectEnv("/src/app", listOf("/src/app"), listOf("/src/app/.venv/site-packages")))
        check(
            "app/pyproject.toml" to "", "app/.venv/site-packages/shapes/__init__.py" to "def <def>area(): pass",
            "app/main.py" to "from shapes import area\n<ref>area()",
        )
        assertTrue("/src/app/.venv/site-packages" in PyEnvironments.getInstance(project).libraryRoots())
    }
    fun `test star import through the package init`() = check(
        "pkg/__init__.py" to "from .impl import *", "pkg/impl.py" to "def <def>tool(): pass", "main.py" to "from pkg import tool\n<ref>tool()",
    )

    fun `test cyclic star imports end`() = check("a.py" to "from b import *", "b.py" to "from a import *", "main.py" to "import a\na.<ref>nope")
    fun `test cyclic from imports end`() = check("a.py" to "from b import x", "b.py" to "from a import <def>x", "main.py" to "import a\na.<ref>x")
    fun `test src layout created later`() {
        myFixture.addFileToProject("proj/pyproject.toml", "")
        val app = myFixture.addFileToProject("proj/app.py", "from lib.core import run\nrun()")
        fun target() = (app.findReferenceAt(app.text.lastIndexOf("run")) as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
        assertFalse(target().any { it is PyFunction })
        myFixture.addFileToProject("proj/src/lib/core.py", "def run(): pass")
        assertTrue(target().any { it is PyFunction })
    }

    // ---------------------------------------------------------------- highlighting and names

    fun `test comment line`() {
        myFixture.configureByText("a.py", "x<caret> = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("#x = 1\n")
    }

    fun `test declarations and calls are colored by kind`() = assertEquals(
        "cache:DECORATOR Shape:CLASS area:FUNCTION_DECL self:SELF scale:PARAMETER compute:FUNCTION_CALL self:SELF Point:CLASS",
        annotated("@cache\nclass Shape:\n    def area(self, scale):\n        return compute(self.side) + Point(scale)\n"),
    )

    fun `test keywords are not names`() {
        val validator = PyNamesValidator()
        listOf("shape", "_Shape2", "match").forEach { assertTrue(it, validator.isIdentifier(it, project)) }
        listOf("class", "None", "2d", "a.b", "_").forEach { assertFalse(it, validator.isIdentifier(it, project)) }
        assertTrue(validator.isKeyword("lambda", project))
    }

    fun `test tokens are colored`() = assertEquals(
        "def:KEYWORD (:PARENTHESES ):PARENTHESES \"\"\"Doc.\"\"\":DOCSTRING return:KEYWORD [:BRACKETS 1:NUMBER 'a':STRING {:BRACES }:BRACES None:KEYWORD " +
            "]:BRACKETS # note:COMMENT",
        lexed("def f():\n    \"\"\"Doc.\"\"\"\n    return [1, 'a', {}, None]  # note\n").replace(Regex(" \\S+:OPERATOR"), ""),
    )

    fun `test usage view names each kind`() {
        val provider = PyFindUsagesProvider()
        val all = declarations("import os\nclass A:\n    def m(self, p):\n        self.size = 1\ndef f(): pass\ncount = 0\n")
        val kinds = all.map { "${it.name}: ${provider.getType(it)}" }
        assertEquals(listOf("os: name", "A: class", "m: method", "self: parameter", "p: parameter", "size: attribute", "f: function", "count: variable"), kinds)
    }

    fun `test use scope of locals is their function`() {
        val byName = declarations("limit = 1\nclass A:\n    def m(self, p):\n        local = lambda k: k\n        self.size = p\n").associateBy { it.name }
        listOf("p", "local", "k").forEach { assertTrue(it, byName.getValue(it).useScope is LocalSearchScope) }
        listOf("limit", "A", "m", "size").forEach { assertFalse(it, byName.getValue(it).useScope is LocalSearchScope) }
        val provider = PyRefactoringSupportProvider()
        assertTrue(provider.isInplaceRenameAvailable(byName.getValue("p"), null))
        assertFalse(provider.isInplaceRenameAvailable(byName.getValue("A"), null))
        assertTrue(provider.isMemberInplaceRenameAvailable(byName.getValue("m"), null))
    }

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

    fun `test find usages of a local stay in its function`() {
        val locals = declarations("def f():\n    x = 1\n    return x\n\ndef g():\n    x = 2\n    return x + x\n").filter { it.name == "x" }
        assertEquals(listOf(1, 2), locals.map { usages(it).size })
    }

    fun `test find usages of a method include inherited calls`() {
        val all = declarations("class Shape:\n    def area(self): return 1\nclass Square(Shape): pass\nShape().area()\nSquare().area()\n")
        assertEquals(2, usages(all.single { it.name == "area" }).size)
    }

    fun `test rename class across files`() {
        val use = myFixture.addFileToProject("main.py", "from shapes import Shape\ns = Shape()\n")
        myFixture.configureByText("shapes.py", "class Sha<caret>pe: pass\n")
        myFixture.renameElementAtCaret("Figure")
        myFixture.checkResult("class Figure: pass\n")
        assertEquals("from shapes import Figure\ns = Figure()\n", use.text)
    }

    fun `test rename from a call`() {
        myFixture.configureByText("a.py", "def helper(): pass\n\nhel<caret>per()\n")
        myFixture.renameElementAtCaret("tool")
        myFixture.checkResult("def tool(): pass\n\ntool()\n")
    }

    fun `test rename instance attribute`() {
        myFixture.configureByText("a.py", "class A:\n    def __init__(self):\n        self.si<caret>ze = 1\n    def n(self):\n        return self.size\n")
        myFixture.renameElementAtCaret("width")
        myFixture.checkResult("class A:\n    def __init__(self):\n        self.width = 1\n    def n(self):\n        return self.width\n")
    }

    fun `test rename parameter stays in its function`() {
        myFixture.configureByText("a.py", "def f(a<caret>):\n    return a\n\ndef g(a):\n    return a\n")
        myFixture.renameElementAtCaret("b")
        myFixture.checkResult("def f(b):\n    return b\n\ndef g(a):\n    return a\n")
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
