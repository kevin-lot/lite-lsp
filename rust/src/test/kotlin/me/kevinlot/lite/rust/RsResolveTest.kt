package me.kevinlot.lite.rust

import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `<ref>` marks the reference (just before its name), `<def>` the expected target name.
 * Multi-file cases: `//- path` headers, the first file is the one containing `<ref>` or not.
 */
class RsResolveTest : BasePlatformTestCase() {

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
        requireNotNull(refFile) { "no <ref>" }
        val file = myFixture.configureFromTempProjectFile(refFile)
        val ref = file.findReferenceAt(refOffset) ?: error("no reference at <ref>")
        val targets = (ref as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element } ?: listOfNotNull(ref.resolve())
        if (defFile == null) {
            assertEmpty("expected unresolved", targets)
            return
        }
        val hit = targets.any { t ->
            val path = t.containingFile.virtualFile.path
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            path.endsWith(defFile) && off == defOffset
        }
        assertTrue("resolved to ${targets.map { "${it.containingFile.name}:${it.textOffset} ${it.text.take(40)}" }}", hit)
    }

    private fun one(code: String) = check("src/lib.rs" to code)

    // ---------------------------------------------------------------- locals

    fun `test local binding`() = one("""fn f() { let <def>x = 1; let y = <ref>x; }""")
    fun `test shadowing picks nearest`() = one("""fn f() { let x = 1; let <def>x = 2; <ref>x; }""")
    fun `test binding not visible before let`() = one("""fn f() { <ref>x; let x = 1; }""")
    fun `test fn parameter`() = one("""fn f(<def>a: i32) { <ref>a; }""")
    fun `test closure parameter`() = one("""fn f() { let c = |<def>v| <ref>v; }""")
    fun `test match arm binding`() = one("""enum E { A(i32) } fn f(e: E) { match e { E::A(<def>n) => <ref>n } }""")
    fun `test if let binding`() = one("""enum O { S(i32), N } fn f(o: O) { if let O::S(<def>v) = o { <ref>v; } }""")
    fun `test for binding`() = one("""fn f() { for <def>i in 0..3 { <ref>i; } }""")
    fun `test pattern refers to unit variant`() = one("""enum E { <def>Empty, Full } use E::*; fn f(e: E) { match e { <ref>Empty => {} _ => {} } }""")

    // ---------------------------------------------------------------- items and paths

    fun `test function call`() = one("""fn <def>g() {} fn f() { <ref>g(); }""")
    fun `test struct type`() = one("""struct <def>S; fn f(_: <ref>S) {}""")
    fun `test generic param`() = one("""fn f<<def>T>(x: <ref>T) {}""")
    fun `test inline module path`() = one("""mod m { pub fn <def>g() {} } fn f() { m::<ref>g(); }""")
    fun `test crate and super`() = one("""fn <def>top() {} mod m { fn f() { super::<ref>top(); crate::top(); } }""")
    fun `test enum variant path`() = one("""enum E { <def>A, B } fn f() { E::<ref>A; }""")
    fun `test associated fn`() = one("""struct S; impl S { fn <def>new() -> S { S } } fn f() { S::<ref>new(); }""")
    fun `test Self in impl`() = one("""struct <def>S; impl S { fn new() -> <ref>Self { S } }""")
    fun `test local item in block`() = one("""fn f() { fn <def>inner() {} <ref>inner(); }""")
    fun `test macro rules`() = one("""macro_rules! <def>m { () => {} } fn f() { <ref>m!(); }""")
    fun `test unresolved`() = one("""fn f() { <ref>nope(); }""")

    // ---------------------------------------------------------------- use

    fun `test use alias`() = one("""mod m { pub struct <def>Thing; } use m::Thing as T; fn f(_: <ref>T) {}""")
    fun `test use group`() = one("""mod m { pub fn a() {} pub fn <def>b() {} } use m::{a, b}; fn f() { <ref>b(); }""")
    fun `test use glob`() = one("""mod m { pub fn <def>g() {} } use m::*; fn f() { <ref>g(); }""")
    fun `test use path segment`() = one("""mod <def>m { pub fn g() {} } use <ref>m::g;""")
    fun `test nested group self`() = one("""mod <def>m { pub fn g() {} } use m::{self as mm}; fn f() { <ref>mm::g(); }""")
    fun `test reexport chain`() = one("""mod a { pub struct <def>X; } mod b { pub use crate::a::X; } fn f(_: b::<ref>X) {}""")

    // ---------------------------------------------------------------- files and modules

    fun `test mod declaration to file`() = check(
        "src/lib.rs" to """mod <ref>util;""",
        "src/util.rs" to """<def>pub fn x() {}""",
    )

    fun `test path into other file`() = check(
        "src/lib.rs" to """mod util; fn f() { util::<ref>helper(); }""",
        "src/util.rs" to """pub fn <def>helper() {}""",
    )

    fun `test mod rs and super from nested file`() = check(
        "src/lib.rs" to """mod net; pub fn <def>root_fn() {}""",
        "src/net/mod.rs" to """mod tcp;""",
        "src/net/tcp.rs" to """fn f() { crate::<ref>root_fn(); }""",
    )

    // ---------------------------------------------------------------- types: fields and methods

    fun `test field of struct`() = one("""struct S { <def>a: i32 } fn f(s: S) { s.<ref>a; }""")
    fun `test method on struct`() = one("""struct S; impl S { fn <def>m(&self) {} } fn f(s: S) { s.<ref>m(); }""")
    fun `test method through reference`() = one("""struct S; impl S { fn <def>m(&self) {} } fn f(s: &S) { s.<ref>m(); }""")
    fun `test field on self`() = one("""struct S { <def>a: i32 } impl S { fn m(&self) -> i32 { self.<ref>a } }""")
    fun `test chained method returning Self`() = one(
        """struct B; impl B { fn new() -> Self { B } fn <def>with(self) -> Self { self } } fn f() { B::new().with().<ref>with(); }""",
    )
    fun `test generic field substitution`() = one(
        """struct In { <def>x: i32 } struct W<T> { inner: T } fn f(w: W<In>) { w.inner.<ref>x; }""",
    )
    /** The concrete implementation is the useful target, not the trait declaration. */
    fun `test method from trait impl`() = one(
        """trait T { fn hello(&self); } struct S; impl T for S { fn <def>hello(&self) {} } fn f(s: S) { s.<ref>hello(); }""",
    )

    fun `test trait default method`() = one(
        """trait T { fn <def>dflt(&self) {} } struct S; impl T for S {} fn f(s: S) { s.<ref>dflt(); }""",
    )
    fun `test method on type param bound`() = one(
        """trait Speak { fn <def>speak(&self); } fn f<T: Speak>(t: T) { t.<ref>speak(); }""",
    )
    fun `test where clause bound`() = one(
        """trait Speak { fn <def>speak(&self); } fn f<T>(t: &T) where T: Speak { t.<ref>speak(); }""",
    )
    fun `test deref to target`() = one(
        """trait Deref { type Target; } struct Inner; impl Inner { fn <def>go(&self) {} }
           struct Wrap; impl Deref for Wrap { type Target = Inner; }
           fn f(w: Wrap) { w.<ref>go(); }""",
    )
    fun `test let with inferred type`() = one(
        """struct S; impl S { fn new() -> S { S } fn <def>m(&self) {} } fn f() { let s = S::new(); s.<ref>m(); }""",
    )
    fun `test struct literal field`() = one("""struct S { <def>a: i32 } fn f() { S { <ref>a: 1 }; }""")
    fun `test tuple field`() = one("""struct P(i32, <def>i32); fn f(p: P) { p.<ref>1; }""")
    fun `test derive gives trait methods`() = one(
        """trait Clone { fn <def>clone(&self) -> Self; } #[derive(Clone)] struct S; fn f(s: S) { s.<ref>clone(); }""",
    )
    fun `test if let destructuring type`() = one(
        """struct In { <def>v: i32 } enum O { S(In), N } fn f(o: O) { if let O::S(x) = o { x.<ref>v; } }""",
    )
    fun `test array element type in for`() = one(
        """struct S { <def>a: i32 } fn f(xs: [S; 2]) { for s in xs { s.<ref>a; } }""",
    )
}
