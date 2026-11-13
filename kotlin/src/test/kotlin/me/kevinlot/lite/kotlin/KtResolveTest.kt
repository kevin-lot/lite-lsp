package me.kevinlot.lite.kotlin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** `<ref>` marks the reference, `<def>` the expected target name (Kotlin or Java file). */
class KtResolveTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    private fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null
        var refOffset = -1
        var defFile: String? = null
        var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>")
            if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>")
            if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        val ref = file.findReferenceAt(refOffset) ?: error("no reference at <ref>")
        val targets = (ref as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
        if (defFile == null) return assertEmpty(targets)
        val ok = targets.any { t ->
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            t.containingFile.virtualFile.path.endsWith(defFile) && off == defOffset
        }
        assertTrue("resolved to ${targets.map { "${it.containingFile.name}:${it.textOffset} ${it.text.take(40)}" }}", ok)
    }

    private fun one(code: String) = check("src/main.kt" to code)

    /** Java target identified by name (mock JDK has no meaningful offsets for us). */
    private fun java(code: String, expected: String) {
        val offset = code.indexOf("<ref>")
        myFixture.configureByText("main.kt", code.replace("<ref>", ""))
        val ref = myFixture.file.findReferenceAt(offset) as PsiPolyVariantReference
        val names = ref.multiResolve(false).mapNotNull { it.element }.map {
            when (it) { is PsiMember -> "${it.containingClass?.qualifiedName ?: (it as? PsiClass)?.qualifiedName}.${it.name}"; else -> it.toString() }
        }
        assertTrue("got $names", names.any { it.endsWith(expected) })
    }

    // ---------------------------------------------------------------- locals and lambdas
    fun `test local val`() = one("fun f() { val <def>x = 1; println(<ref>x) }")
    fun `test shadowing nearest`() = one("fun f() { val x = 1; val <def>x = 2; <ref>x }")
    fun `test parameter`() = one("fun f(<def>a: Int) = <ref>a")
    fun `test lambda parameter`() = one("fun f() { listOf(1).map { <def>v -> <ref>v } }")
    fun `test it`() = one("fun f() { listOf(1).forEach <def>{ println(<ref>it) } }")
    fun `test for variable`() = one("fun f() { for (<def>i in 0..3) <ref>i }")
    fun `test destructuring`() = one("fun f(p: Pair<Int, Int>) { val (<def>a, b) = p; <ref>a }")
    fun `test catch parameter`() = one("fun f() { try {} catch (<def>e: Exception) { <ref>e } }")

    // ---------------------------------------------------------------- classes and members
    fun `test top level function`() = one("fun <def>g() {} fun f() { <ref>g() }")
    fun `test class constructor`() = one("class <def>A; fun f() = <ref>A()")
    fun `test type reference`() = one("class <def>A; fun f(a: <ref>A) {}")
    fun `test generic type parameter`() = one("fun <<def>T> f(x: <ref>T) = x")
    fun `test member function`() = one("class A { fun <def>m() {} } fun f(a: A) { a.<ref>m() }")
    fun `test member property`() = one("class A { val <def>p = 1 } fun f(a: A) = a.<ref>p")
    fun `test constructor property`() = one("class A(val <def>p: Int) fun f(a: A) = a.<ref>p")
    fun `test implicit this member`() = one("class A { fun <def>m() {} fun n() { <ref>m() } }")
    fun `test constructor param in init`() = one("class A(<def>x: Int) { val y = <ref>x }")
    fun `test inherited member`() = one("open class B { fun <def>m() {} } class A : B() fun f(a: A) = a.<ref>m()")
    fun `test interface default member`() = one("interface I { fun <def>m() {} } class A : I fun f(a: A) = a.<ref>m()")
    fun `test companion member`() = one("class A { companion object { fun <def>create() = A() } } fun f() = A.<ref>create()")
    fun `test object member`() = one("object O { val <def>x = 1 } fun f() = O.<ref>x")
    fun `test enum entry`() = one("enum class C { <def>RED, GREEN } fun f() = C.<ref>RED")
    fun `test nested class`() = one("class A { class <def>B } fun f(b: A.<ref>B) {}")
    fun `test chained call with inferred type`() = one(
        "class B { fun <def>done() {} } class A { fun next() = B() } fun f() { A().next().<ref>done() }",
    )
    fun `test generic member substitution`() = one(
        "class Box<T>(val value: T) class In { val <def>z = 1 } fun f(b: Box<In>) = b.value.<ref>z",
    )
    fun `test extension function`() = one("class A fun A.<def>ext() {} fun f(a: A) = a.<ref>ext()")
    fun `test extension on supertype`() = one("open class B class A : B() fun B.<def>ext() {} fun f(a: A) = a.<ref>ext()")
    fun `test this in extension`() = one("class A { val <def>p = 1 } fun A.ext() = <ref>p")
    fun `test apply receiver`() = one("class A { var <def>p = 1 } fun f() = A().apply { <ref>p = 2 }")
    fun `test let it type`() = one("class A { val <def>p = 1 } fun f(a: A) = a.let { it.<ref>p }")
    fun `test collection element it`() = one("class A { val <def>p = 1 } fun f(xs: List<A>) = xs.map { it.<ref>p }")
    fun `test for over list element`() = one("class A { val <def>p = 1 } fun f(xs: List<A>) { for (x in xs) x.<ref>p }")
    fun `test named argument`() = one("fun g(<def>count: Int) {} fun f() = g(<ref>count = 1)")
    fun `test overload by arity`() = one("fun g(a: Int) {} fun <def>g(a: Int, b: Int) {} fun f() = <ref>g(1, 2)")
    fun `test lazy delegate type`() = one("class A { val <def>p = 1 } class U { val a by lazy { A() } fun f() = a.<ref>p }")
    fun `test destructured data class component type`() = one(
        "class R { val <def>p = 1 } data class Out(val r: R, val msg: String) fun g() = Out(R(), \"\") fun f() { val (r, m) = g(); r.<ref>p }",
    )
    fun `test data class copy`() = one("data class <def>P(val x: Int) fun f(p: P) = p.<ref>copy(x = 1)")
    /** `import kotlin.collections.List` names the type, not the `List(size) {}` factory function. */
    fun `test explicit import of builtin type keeps extension receiver`() = one(
        "import kotlin.collections.List\nclass C { val <def>s = 1 }\nfun List<C>.top() = this\nfun f(xs: List<C>) { xs.top()[0].<ref>s }",
    )
    /**
     * The plugin attaches Gradle jars as a *synthetic* library (not a module library): reproduce that and
     * check that compiled stdlib members and extensions are found (`Result.getOrNull`, `filter`, `maxByOrNull`).
     */
    fun `test stdlib through synthetic library like in the IDE`() {
        val jars = me.kevinlot.lite.kotlin.gradle.GradleProjects.jarsFor(null)
        if (jars.isEmpty()) return // no kotlin-stdlib in the Gradle cache of this machine
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, jars.first().parentFile.path)
        me.kevinlot.lite.kotlin.gradle.GradleProjects.getInstance(project).setJarsForTests(jars)
        val code = "class Card(val s: Int)\n" +
            "fun List<Card>.findHighCard(): Result<Card> { val c = this.maxByOrNull { it.s } ?: return Result.failure(Exception()); return Result.success(c) }\n" +
            "fun f(cards: List<Card>) = cards.filter { it.s > 1 }.findHighCard().getOrNull()\n"
        myFixture.configureByText("Solver.kt", code)
        fun targetOf(word: String, nth: Int = 0): String? {
            var idx = -1; repeat(nth + 1) { idx = code.indexOf(word, idx + 1) }
            val ref = myFixture.file.findReferenceAt(idx) as PsiPolyVariantReference
            return ref.multiResolve(false).firstOrNull()?.element?.let { (it as? com.intellij.psi.PsiNamedElement)?.name }
        }
        assertEquals("findHighCard", targetOf("findHighCard", 1))
        assertTrue(targetOf("getOrNull")!!.startsWith("getOrNull"))
        assertEquals("filter", targetOf("filter"))
        assertEquals("maxByOrNull", targetOf("maxByOrNull"))
    }

    fun `test unresolved`() = one("fun f() = <ref>nope()")

    // ---------------------------------------------------------------- files, packages, imports
    fun `test same package other file`() = check(
        "src/a/Util.kt" to "package a\nfun <def>helper() {}",
        "src/a/Main.kt" to "package a\nfun f() = <ref>helper()",
    )
    fun `test explicit import`() = check(
        "src/lib/Lib.kt" to "package lib\nclass <def>Thing",
        "src/app/Main.kt" to "package app\nimport lib.Thing\nfun f(t: <ref>Thing) {}",
    )
    fun `test import segment`() = check(
        "src/lib/Lib.kt" to "package lib\nclass <def>Thing",
        "src/app/Main.kt" to "package app\nimport lib.<ref>Thing",
    )
    fun `test star import`() = check(
        "src/lib/Lib.kt" to "package lib\nfun <def>tool() {}",
        "src/app/Main.kt" to "package app\nimport lib.*\nfun f() = <ref>tool()",
    )
    fun `test import alias`() = check(
        "src/lib/Lib.kt" to "package lib\nclass <def>Thing",
        "src/app/Main.kt" to "package app\nimport lib.Thing as T\nfun f(t: <ref>T) {}",
    )
    fun `test fully qualified call`() = check(
        "src/lib/Lib.kt" to "package lib\nobject Tools { fun <def>run() {} }",
        "src/app/Main.kt" to "package app\nfun f() = lib.Tools.<ref>run()",
    )

    // ---------------------------------------------------------------- project boundaries

    /** Targets of the reference at `<ref>` in [path] (files must already be added). */
    private fun targetsAt(path: String, offset: Int) =
        (myFixture.configureFromTempProjectFile(path).findReferenceAt(offset) as PsiPolyVariantReference)
            .multiResolve(false).mapNotNull { it.element }.map { it.containingFile.virtualFile.path.substringAfter("/src/") }

    /** Same default-package class in a sibling Gradle project and in a `bin/` copy: only our own counts. */
    fun `test lookups stay inside the gradle project`() {
        myFixture.addFileToProject("poker/settings.gradle.kts", "rootProject.name = \"poker\"")
        myFixture.addFileToProject("poker/src/main/kotlin/Cards.kt", "enum class CardStrength { ACE }")
        myFixture.addFileToProject("poker/bin/main/Cards.kt", "enum class CardStrength { ACE }")
        myFixture.addFileToProject("kata/poker/build.gradle.kts", "")
        myFixture.addFileToProject("kata/poker/src/main/kotlin/Cards.kt", "enum class CardStrength { ACE }")
        val code = "fun f(s: CardStrength) {}"
        myFixture.addFileToProject("poker/src/test/kotlin/Fixture.kt", code)
        assertEquals(listOf("poker/src/main/kotlin/Cards.kt"), targetsAt("poker/src/test/kotlin/Fixture.kt", code.indexOf("CardStrength")))
    }

    /** No build script at all: the project is the top-level folder of the workspace holding the file. */
    fun `test folder without build script is its own project`() {
        myFixture.addFileToProject("loose/src/Cards.kt", "enum class CardStrength { ACE }")
        myFixture.addFileToProject("other/src/Cards.kt", "enum class CardStrength { ACE }")
        val code = "fun f(s: CardStrength) {}"
        myFixture.addFileToProject("loose/test/Fixture.kt", code)
        assertEquals(listOf("loose/src/Cards.kt"), targetsAt("loose/test/Fixture.kt", code.indexOf("CardStrength")))
    }

    fun `test file in an output dir still sees its siblings`() {
        myFixture.addFileToProject("app/build.gradle.kts", "")
        myFixture.addFileToProject("app/bin/main/A.kt", "class Helper")
        val code = "fun f(h: Helper) {}"
        myFixture.addFileToProject("app/bin/main/B.kt", code)
        assertEquals(listOf("app/bin/main/A.kt"), targetsAt("app/bin/main/B.kt", code.indexOf("Helper")))
    }

    fun `test multi module build sees sibling modules`() {
        myFixture.addFileToProject("shop/settings.gradle.kts", "include(\"core\", \"web\")")
        myFixture.addFileToProject("shop/core/build.gradle.kts", "")
        myFixture.addFileToProject("shop/core/src/main/kotlin/shop/Order.kt", "package shop\nclass Order")
        myFixture.addFileToProject("shop/web/build.gradle.kts", "")
        val code = "package shop\nfun f(o: Order) {}"
        myFixture.addFileToProject("shop/web/src/main/kotlin/shop/Page.kt", code)
        assertEquals(listOf("shop/core/src/main/kotlin/shop/Order.kt"), targetsAt("shop/web/src/main/kotlin/shop/Page.kt", code.indexOf("Order")))
    }

    // ---------------------------------------------------------------- Java
    fun `test java class from kotlin`() = check(
        "src/j/Person.java" to "package j; public class <def>Person { public String getName() { return \"\"; } }",
        "src/app/Main.kt" to "package app\nimport j.Person\nfun f(p: <ref>Person) {}",
    )
    fun `test java getter as property`() = check(
        "src/j/Person.java" to "package j; public class Person { public String <def>getName() { return \"\"; } }",
        "src/app/Main.kt" to "package app\nimport j.Person\nfun f(p: Person) = p.<ref>name",
    )
    fun `test java static member`() = check(
        "src/j/Util.java" to "package j; public class Util { public static int <def>twice(int x) { return 2 * x; } }",
        "src/app/Main.kt" to "package app\nimport j.Util\nfun f() = Util.<ref>twice(2)",
    )
    fun `test java lang default import`() = java("fun f() = <ref>System.currentTimeMillis()", "java.lang.System.System")
    fun `test kotlin String maps to java`() = java("fun f(s: String) = s.<ref>length", "java.lang.String.length")
}
