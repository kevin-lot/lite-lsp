package me.kevinlot.lite.dart

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class DartTest : BasePlatformTestCase() {
    private val sdk = arrayOf(
        ".dart_tool/package_config.json" to """{"configVersion": 2, "flutterRoot": "file:///src/flutter", "packages": [
            {"name": "shapes", "rootUri": "../packages/shapes", "packageUri": "lib/"}]}""",
        "flutter/bin/cache/dart-sdk/lib/core/core.dart" to """
            library dart.core;
            part 'string.dart';
            class Object { String toString() => ''; int get hashCode => 0; }
            abstract class num { num operator +(num other); num operator -(num other); double operator /(num other); num operator -(); }
            class int extends num { int abs() => this; int operator <<(int count) => this; }
            class double extends num { double abs() => this; }
            class bool {}
            abstract class Enum { int get index; }
            abstract class Iterable<E> { E get first; int get length; Iterable<T> map<T>(T toElement(E e)); Iterable<E> where(bool test(E element)); }
            abstract class List<E> implements Iterable<E> { void add(E value); E operator [](int index); List<E> operator +(List<E> other); }
            abstract class Set<E> implements Iterable<E> {}
            abstract class Map<K, V> { V? operator [](Object? key); Iterable<MapEntry<K, V>> get entries; }
            class MapEntry<K, V> { final K key; final V value; }
            abstract class Future<T> { Future<R> then<R>(R onValue(T value)); }
            void print(Object? object) {}
        """,
        "flutter/bin/cache/dart-sdk/lib/core/string.dart" to "part of dart.core;\nclass String { int get length => 0; String trim() => this; String operator +(String other) => this; }\n",
        "flutter/bin/cache/dart-sdk/lib/async/async.dart" to "class Timer { void cancel() {} }\n",
    )

    /** `<ref>` resolves to `<def>`: the file holding `<ref>` is opened, the others are beside it. */
    private fun check(vararg files: Pair<String, String>) {
        val targets = resolve(*files)
        val (path, text) = files.first { "<def>" in it.second }
        val at = clean(text).let { it.replace("<ref>", "").indexOf("<def>") }
        assertTrue("resolved to ${targets.map { "${it.containingFile.name}:${it.textOffset} '${it.text.take(20)}'" }}",
            targets.any { it.containingFile.virtualFile.path.endsWith(path) && it.textOffset == at })
    }

    private fun one(text: String) = check("main.dart" to text)
    private fun none(text: String) = assertEmpty(resolve("main.dart" to text))
    private fun clean(text: String) = text.trimIndent()

    private fun resolve(vararg files: Pair<String, String>): List<com.intellij.psi.PsiElement> {
        val (path, text) = files.first { "<ref>" in it.second }
        files.filter { it.first != path }.forEach { myFixture.addFileToProject(it.first, clean(it.second).replace("<def>", "")) }
        val marked = clean(text).replace("<def>", "")
        val file = myFixture.addFileToProject(path, marked.replace("<ref>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val ref = myFixture.file.findReferenceAt(marked.indexOf("<ref>")) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        return ref.multiResolve(false).mapNotNull { it.element }
    }

    // ---------------------------------------------------------------- locals

    fun `test local variable`() = one("void f() { var <def>x = 1; print(<ref>x); }")
    fun `test typed local`() = one("void f() { final int <def>x = 1; <ref>x; }")
    fun `test second variable of a list`() = one("void f() { int a = 1, <def>b = 2; <ref>b; }")
    fun `test nearest declaration`() = one("void f() { var x = 1; { var <def>x = 2; <ref>x; } }")
    fun `test variable is not visible before its declaration`() = one("var <def>x = 0; void f() { <ref>x; var x = 1; }")
    fun `test variable is not visible in its own initializer`() = one("var <def>x = 0; void f() { var x = <ref>x; }")
    fun `test parameter`() = one("int f(int <def>a) => <ref>a;")
    fun `test named parameter`() = one("int f({required int <def>a}) => <ref>a;")
    fun `test optional positional parameter`() = one("int f([int <def>a = 1]) => <ref>a;")
    fun `test function typed parameter`() = one("int f(int <def>op(int x)) => <ref>op(1);")
    fun `test local function`() = one("void f() { <ref>g(); void <def>g() {} }")
    fun `test closure parameter`() = one("void f() { var g = (int <def>v) => <ref>v + 1; }")
    fun `test for in variable`() = one("void f(List<int> xs) { for (final <def>x in xs) { <ref>x; } }")
    fun `test for variable`() = one("void f() { for (var <def>i = 0; <ref>i < 3; i++) {} }")
    fun `test for variable in the body`() = one("void f() { for (var <def>i = 0; i < 3; i++) { <ref>i; } }")
    fun `test catch variables`() = one("void f() { try {} on Error catch (e, <def>st) { <ref>st; } }")
    fun `test record destructuring`() = one("void f() { final (a, <def>b) = (1, 2); <ref>b; }")
    fun `test switch case pattern`() = one("void f(Object o) { switch (o) { case int <def>n when <ref>n > 1: break; } }")
    fun `test switch case pattern in the body`() = one("void f(Object o) { switch (o) { case final int <def>n: print(<ref>n); } }")
    fun `test if case pattern`() = one("void f(Object o) { if (o case final String <def>s) { <ref>s; } }")
    fun `test type parameter of a function`() = one("T f<<def>T>(<ref>T x) => x;")
    fun `test short string template`() = one("void f() { var <def>x = 1; print('v \$<ref>x'); }")
    fun `test long string template`() = one("void f(String <def>s) { print(\"n \${<ref>s.length}\"); }")
    fun `test top level variable`() = one("int <def>total = 0; void f() { <ref>total++; }")
    fun `test top level function`() = one("void <def>g() {} void f() { <ref>g(); }")
    fun `test unknown name`() = none("void f() { <ref>missing; }")

    // ---------------------------------------------------------------- classes and members

    fun `test class in a type`() = one("class <def>A {} void f(<ref>A a) {}")
    fun `test constructor call names the class`() = one("class <def>A { A(); } void f() { <ref>A(); }")
    fun `test named constructor`() = one("class A { A.<def>empty(); } void f() { A.<ref>empty(); }")
    fun `test factory constructor`() = one("class A { A(); factory A.<def>make() => A(); } void f() { A.<ref>make(); }")
    fun `test named constructor after new`() = one("class A { A.<def>empty(); } void f() { new A.<ref>empty(); }")
    fun `test implicit this field`() = one("class A { int <def>n = 0; void m() { <ref>n++; } }")
    fun `test this field`() = one("class A { int <def>n = 0; void m() { this.<ref>n++; } }")
    fun `test method of the class`() = one("class A { void <def>m() {} void n() { <ref>m(); } }")
    fun `test getter`() = one("class A { int get <def>size => 1; } int f(A a) => a.<ref>size;")
    fun `test setter and getter share a name`() {
        assertEquals(2, resolve("main.dart" to "class A { int get v => 1; set v(int x) {} } void f(A a) { a.<ref>v = 2; }").size)
    }
    fun `test setter of the class and getter of its parent`() = one("class A { void <def>m() {} } abstract class B { A get v; } abstract class C extends B { set v(A x); } void f(C c) { c.v.<ref>m(); }")
    fun `test static member`() = one("class A { static int <def>count = 0; } void f() { A.<ref>count; }")
    fun `test enum value`() = one("enum Color { red, <def>green } void f() { Color.<ref>green; }")
    fun `test enum member`() = one("enum Color { red; int get <def>code => 1; } int f(Color c) => c.<ref>code;")
    fun `test field formal parameter`() = one("class A { final int <def>n; A(this.<ref>n); }")
    fun `test field formal parameter used in an initializer`() = one("class A { final int <def>n; final int m; A(this.n) : m = <ref>n; }")
    fun `test super parameter`() = one("class B { B({int <def>size = 0}); } class A extends B { A({super.<ref>size}); }")
    fun `test inherited member`() = one("class B { void <def>m() {} } class A extends B { void n() { <ref>m(); } }")
    fun `test member of a mixin`() = one("mixin M { void <def>m() {} } class A with M {} void f(A a) { a.<ref>m(); }")
    fun `test member of an interface`() = one("abstract class I { void <def>m(); } abstract class A implements I {} void f(A a) { a.<ref>m(); }")
    fun `test member of the on type of a mixin`() = one("class B { void <def>m() {} } mixin M on B { void n() { <ref>m(); } }")
    fun `test super member`() = one("class B { void <def>m() {} } class A extends B { void m() { super.<ref>m(); } }")
    fun `test own member hides the inherited one`() = one("class B { void m() {} } class A extends B { void <def>m() {} void n() { <ref>m(); } }")
    fun `test type parameter of a class`() = one("class Box<<def>T> { <ref>T? value; }")
    fun `test typedef`() = one("typedef <def>Op = int Function(int); int f(<ref>Op op) => op(1);")
    fun `test mixin application`() = one("mixin M { void <def>m() {} } class B {} class A = B with M; void f(A a) { a.<ref>m(); }")
    fun `test extension method`() = one("class A {} extension Ext on A { void <def>twice() {} } void f(A a) { a.<ref>twice(); }")
    fun `test member used in an extension`() = one("class A { int <def>n = 0; } extension Ext on A { int twice() => <ref>n * 2; }")
    fun `test extension type member`() = one("extension type Id(int value) { int get <def>next => 1; } int f(Id id) => id.<ref>next;")
    fun `test cyclic hierarchy ends`() = none("class A extends B {} class B extends A {} void f(A a) { a.<ref>m(); }")

    // ---------------------------------------------------------------- types of expressions

    fun `test member of a declared type`() = one("class A { void <def>m() {} } void f(A a) { a.<ref>m(); }")
    fun `test member of a nullable type`() = one("class A { void <def>m() {} } void f(A? a) { a?.<ref>m(); }")
    fun `test member of a constructed value`() = one("class A { void <def>m() {} } void f() { var a = A(); a.<ref>m(); }")
    fun `test member of a named constructor value`() = one("class A { A.empty(); void <def>m() {} } void f() { final a = A.empty(); a.<ref>m(); }")
    fun `test member of a const value`() = one("class A { const A(); void <def>m() {} } void f() { final a = const A(); a.<ref>m(); }")
    fun `test member of a call result`() = one("class A { void <def>m() {} } A make() => A(); void f() { make().<ref>m(); }")
    fun `test member of a method result`() = one("class A { void <def>m() {} } class B { A next() => A(); } void f(B b) { b.next().<ref>m(); }")
    fun `test member through a field`() = one("class A { void <def>m() {} } class B { final A a = A(); } void f(B b) { b.a.<ref>m(); }")
    fun `test member through a getter`() = one("class A { void <def>m() {} } class B { A get a => A(); } void f(B b) { b.a.<ref>m(); }")
    fun `test member of a variable typed by another`() = one("class A { void <def>m() {} } void f(A a) { var b = a; var c = b; c.<ref>m(); }")
    fun `test cascade`() = one("class A { void <def>m() {} } void f(A a) { a..<ref>m()..m(); }")
    fun `test second cascade section`() = one("class A { void m() {} void <def>n() {} } void f() { A()..m()..<ref>n(); }")
    fun `test cast`() = one("class A { void <def>m() {} } void f(Object o) { (o as A).<ref>m(); }")
    fun `test null assertion`() = one("class A { void <def>m() {} } void f(A? a) { a!.<ref>m(); }")
    fun `test generic field`() = one("class A { void <def>m() {} } class Box<T> { T value; Box(this.value); } void f(Box<A> b) { b.value.<ref>m(); }")
    fun `test generic method result`() = one("class A { void <def>m() {} } class Box<T> { T get() => throw 0; } void f(Box<A> b) { b.get().<ref>m(); }")
    fun `test generic supertype`() = one("class A { void <def>m() {} } class Box<T> { T? value; } class ABox extends Box<A> {} void f(ABox b) { b.value.<ref>m(); }")
    fun `test explicit type arguments of a constructor`() = one("class A { void <def>m() {} } class Box<T> { T? value; } void f() { Box<A>().value.<ref>m(); }")
    fun `test this in a method`() = one("class A { void <def>m() {} A self() => this; void n() { self().<ref>m(); } }")
    fun `test untyped closure parameter has no members`() = none("class A { void m() {} } void f(List<A> xs) { xs.map((e) => e.<ref>m()); }")

    fun `test member of a core type`() = check("main.dart" to "int f(String s) => s.<ref>length;", *sdk.map { it.first to it.second.replace("int get length => 0", "int get <def>length => 0") }.toTypedArray())
    fun `test member of a string literal`() = check("main.dart" to "void f() { 'a'.<ref>trim(); }", *sdk.map { it.first to it.second.replace("String trim()", "String <def>trim()") }.toTypedArray())
    fun `test element of a typed list literal`() = check("main.dart" to "class A { void <def>m() {} } void f() { var xs = <A>[]; xs.first.<ref>m(); }", *sdk)
    fun `test element of a list literal`() = check("main.dart" to "class A { void <def>m() {} } void f() { var xs = [A()]; for (final x in xs) { x.<ref>m(); } }", *sdk)
    fun `test for in over a list`() = check("main.dart" to "class A { void <def>m() {} } void f(List<A> xs) { for (var x in xs) { x.<ref>m(); } }", *sdk)
    fun `test inherited generic member`() = check("main.dart" to "class A { void <def>m() {} } void f(List<A> xs) { xs.first.<ref>m(); }", *sdk)
    fun `test await`() = check("main.dart" to "class A { void <def>m() {} } Future<A> load() => throw 0; void f() async { (await load()).<ref>m(); }", *sdk)
    fun `test awaited variable`() = check("main.dart" to "class A { void <def>m() {} } Future<A> load() => throw 0; void f() async { final a = await load(); a.<ref>m(); }", *sdk)
    fun `test object member`() = check("main.dart" to "class A {} void f(A a) { a.<ref>toString(); }", *sdk.map { it.first to it.second.replace("String toString()", "String <def>toString()") }.toTypedArray())
    fun `test enum index`() = check("main.dart" to "enum Color { red } int f(Color c) => c.<ref>index;", *sdk.map { it.first to it.second.replace("int get index;", "int get <def>index;") }.toTypedArray())
    fun `test core function`() = check("main.dart" to "void f() { <ref>print(1); }", *sdk.map { it.first to it.second.replace("void print(", "void <def>print(") }.toTypedArray())
    fun `test core class of a part`() = check("main.dart" to "<ref>String f() => '';", *sdk.map { it.first to it.second.replace("class String {", "class <def>String {") }.toTypedArray())
    fun `test dart import`() = check("main.dart" to "import 'dart:async';\nvoid f(<ref>Timer t) {}", *sdk.map { it.first to it.second.replace("class Timer", "class <def>Timer") }.toTypedArray())

    // ---------------------------------------------------------------- operators, literals, records and patterns

    private val v = "class V { V operator +(V o) => o; V operator -() => this; int operator -(V o) => 0; V operator [](int i) => this; void <def>m() {} }"

    private fun typed(text: String) = check("main.dart" to text, *sdk)

    /** What `<ref>` names, as the head of its declaration: `int abs`. */
    private fun found(text: String): List<String> = resolve("main.dart" to text, *sdk).map { it.parent.text.substringBefore('(').substringBefore(" =>").trim() }

    fun `test binary operator`() = one("$v void f(V a) { (a + a).<ref>m(); }")
    fun `test operator in an initializer`() = one("$v void f(V a) { final b = a + a; b.<ref>m(); }")
    fun `test unary operator`() = one("$v void f(V a) { (-a).<ref>m(); }")
    fun `test binary minus is not the unary one`() = assertEquals(listOf("int abs"), found("$v void f(V a) { (a - a).<ref>abs(); }".replace("<def>", "")))
    fun `test index operator`() = one("$v void f(V a) { a[0].<ref>m(); }")
    fun `test index in a cascade`() = one("$v void f(V a) { a..[0].<ref>m(); }")
    fun `test assignment has the type of its value`() = one("$v void f(V a, V? b) { (b = a).<ref>m(); }")
    fun `test comparison is no operand`() = none("$v void f(V a) { (a == a).<ref>m(); }")
    fun `test string concatenation`() = assertEquals(listOf("String trim"), found("void f(String s) { (s + 'x').<ref>trim(); }"))
    fun `test sum of ints is an int`() = assertEquals(listOf("int abs"), found("void f() { (1 + 2).<ref>abs(); }"))
    fun `test sum with a double is a double`() = assertEquals(listOf("double abs"), found("void f() { (1 + 2.0).<ref>abs(); }"))
    fun `test division is a double`() = assertEquals(listOf("double abs"), found("void f() { (1 / 2).<ref>abs(); }"))
    fun `test shift is declared by int`() = assertEquals(listOf("int abs"), found("void f() { (1 << 2).<ref>abs(); }"))
    fun `test list concatenation`() = typed("$v void f(List<V> xs) { (xs + xs).first.<ref>m(); }")
    fun `test element of a list by index`() = typed("$v void f(List<V> xs) { xs[0].<ref>m(); }")

    fun `test map literal`() = typed("$v void f(V a) { final m = {'k': a}; m['k'].<ref>m(); }")
    fun `test typed map literal`() = typed("$v void f() { final m = <String, V>{}; m['k'].<ref>m(); }")
    fun `test set literal`() = typed("$v void f(V a) { final s = {a}; s.first.<ref>m(); }")
    fun `test typed set literal`() = typed("$v void f() { final s = <V>{}; s.first.<ref>m(); }")
    fun `test entries of a map`() = typed("$v void f(Map<String, V> m) { for (final e in m.entries) { e.value.<ref>m(); } }")
    fun `test switch expression`() = one("$v void f(V a, int n) { final x = switch (n) { 1 => a, _ => a }; x.<ref>m(); }")

    fun `test positional record field`() = one("$v void f((V, {V n}) r) { r.${'$'}1.<ref>m(); }")
    fun `test named record field`() = one("$v void f((V, {V n}) r) { r.n.<ref>m(); }")
    fun `test record field name`() = one("class V {} void f((V, {V <def>n}) r) { r.<ref>n; }")
    fun `test record literal`() = one("$v void f(V a) { final r = (1, a, n: a); r.${'$'}2.<ref>m(); }")
    fun `test named field of a record literal`() = one("$v void f(V a) { final r = (1, n: a); r.n.<ref>m(); }")
    fun `test record literal field name`() = one("void f() { final r = (1, <def>n: 2); r.<ref>n; }")
    fun `test record result`() = one("$v (int, V) pair() => throw 0; void f() { pair().${'$'}2.<ref>m(); }")
    fun `test record behind a typedef`() = one("$v typedef P = (V, V); void f(P p) { p.${'$'}1.<ref>m(); }")
    fun `test record with a type parameter`() = one("$v class Box<T> { (T, int) get both => throw 0; } void f(Box<V> b) { b.both.${'$'}1.<ref>m(); }")
    fun `test nullable record`() = one("$v void f((V, V)? r) { r?.${'$'}1.<ref>m(); }")

    fun `test destructured positional field`() = one("$v void f((V, {V n}) r) { final (p, n: q) = r; p.<ref>m(); }")
    fun `test destructured named field`() = one("$v void f((V, {V n}) r) { final (p, n: q) = r; q.<ref>m(); }")
    fun `test destructured field by its own name`() = one("$v void f((V, {V n}) r) { var (u, :n) = r; n.<ref>m(); }")
    fun `test destructured literal`() = one("$v void f(V a) { final (p, q) = (1, a); q.<ref>m(); }")
    fun `test destructured loop variable`() = typed("$v void f(List<(int, V)> xs) { for (final (k, w) in xs) { w.<ref>m(); } }")
    fun `test record pattern of a case`() = one("$v void f((V, {V n}) r) { if (r case (_, n: final y)) { y.<ref>m(); } }")
    fun `test record pattern label`() = one("class V {} void f((V, {V <def>n}) r) { if (r case (_, <ref>n: final y)) {} }")
    fun `test untyped case variable`() = one("$v void f(V o) { if (o case final x) { x.<ref>m(); } }")
    fun `test nested patterns`() = one("$v void f(((int, V), int) r) { final ((a, b), c) = r; b.<ref>m(); }")

    private val c = "class C { final V v; C(this.v); }"

    fun `test object pattern field by its own name`() = one("$v $c void f(Object o) { switch (o) { case C(:final v): v.<ref>m(); } }")
    fun `test object pattern field`() = one("$v $c void f(Object o) { switch (o) { case C(v: var w): w.<ref>m(); } }")
    fun `test object pattern label`() = one("class V {} class C { final V <def>v; C(this.v); } void f(Object o) { switch (o) { case C(<ref>v: var w): break; } }")
    fun `test object pattern in a switch expression`() = one("$v $c int f(Object o) => switch (o) { C(:final v) => v.<ref>m(), _ => 0 };")
    fun `test object pattern takes the type arguments of the value`() =
        typed("$v void f(Map<String, V> m) { for (final MapEntry(:key, :value) in m.entries) { value.<ref>m(); } }")
    fun `test list pattern`() = typed("$v void f(List<V> xs) { if (xs case [var h, ...]) { h.<ref>m(); } }")
    fun `test map pattern`() = typed("$v void f(Map<String, V> m) { if (m case {'k': final w}) { w.<ref>m(); } }")
    fun `test pattern after a cast`() = one("$v $c void f(Object o) { if (o case (C(:final v) || C(:final v))) { v.<ref>m(); } }")

    fun `test async closure gives a future`() = typed("$v void f(V a) async { final g = () async => a; (await g()).<ref>m(); }")
    fun `test async closure given to a generic function`() =
        typed("$v Future<T> run<T>(Future<T> Function() body) => body(); void f(V a) async { (await run(() async => a)).<ref>m(); }")
    fun `test async block closure`() = typed("$v T run<T>(T Function() body) => body(); void f(V a) async { (await run(() async { return a; })).<ref>m(); }")
    fun `test async closure returning a future is not wrapped twice`() =
        typed("$v Future<V> load() => throw 0; void f() async { final g = () async => load(); (await g()).<ref>m(); }")

    // ---------------------------------------------------------------- localization

    private val generated = "abstract class AppLocalizations { static AppLocalizations of(Object context) => throw 0; " +
        "String get helloWorld; String get buttonCancel; String greeting(String name); String get locale; }"
    private val english = """{ "@@locale": "en", "helloWorld": "Hello", "@helloWorld": { "placeholders": { "greeting": {} } },
        "Button_Cancel": "Cancel", "greeting": "Hi {name}" }"""
    private val french = """{ "helloWorld": "Bonjour", "Button_Cancel": "Annuler" }"""
    private val bundles = arrayOf(
        "pubspec.yaml" to "name: app\n", "lib/l10n/app_localizations.dart" to generated, "lib/l10n/app_en.arb" to english, "lib/l10n/app_fr.arb" to french,
    )
    private val use = "import 'l10n/app_localizations.dart';\nvoid f(Object c) { AppLocalizations.of(c)."

    private fun messages(vararg files: Pair<String, String>): List<String> =
        resolve(*files).map { "${it.containingFile.virtualFile.path.removePrefix("/src/")}:${it.text.take(24)}" }.sorted()

    fun `test message of every locale`() = assertEquals(
        listOf("lib/l10n/app_en.arb:\"helloWorld\"", "lib/l10n/app_fr.arb:\"helloWorld\""), messages("lib/main.dart" to "$use<ref>helloWorld; }", *bundles),
    )
    fun `test message whose key is written another way`() = assertEquals(
        listOf("lib/l10n/app_en.arb:\"Button_Cancel\"", "lib/l10n/app_fr.arb:\"Button_Cancel\""), messages("lib/main.dart" to "$use<ref>buttonCancel; }", *bundles),
    )
    fun `test message with placeholders`() =
        assertEquals(listOf("lib/l10n/app_en.arb:\"greeting\""), messages("lib/main.dart" to "$use<ref>greeting('x'); }", *bundles))
    fun `test member that is no message`() =
        assertEquals(listOf("lib/l10n/app_localizations.dart:locale"), messages("lib/main.dart" to "$use<ref>locale; }", *bundles))
    fun `test message without the generated class`() = assertEquals(
        listOf("lib/l10n/app_en.arb:\"helloWorld\"", "lib/l10n/app_fr.arb:\"helloWorld\""),
        messages("lib/main.dart" to "void f(dynamic context) { context.l10n.<ref>helloWorld; }", *bundles),
    )
    fun `test unknown member of something else is no message`() =
        assertEmpty(messages("lib/main.dart" to "void f(dynamic context) { context.theme.<ref>helloWorld; }", *bundles))
    fun `test member of another class is no message`() = assertEquals(
        listOf("lib/main.dart:helloWorld"), messages("lib/main.dart" to "class Other { String get helloWorld => ''; } void f(Other o) { o.<ref>helloWorld; }", *bundles),
    )
    fun `test message keeps the type of its getter`() =
        assertEquals(listOf("String trim"), found("$generated void f(Object c) { AppLocalizations.of(c).helloWorld.<ref>trim(); }"))

    fun `test bundle named like the class`() = assertEquals(
        listOf("lib/l10n/arb/casino/casino_en.arb:\"title\""),
        messages(
            "lib/main.dart" to "import 'l10n/casino/casino_localizations.dart';\nvoid f(CasinoLocalizations l) { l.<ref>title; }",
            "pubspec.yaml" to "name: app\n", "lib/l10n/casino/casino_localizations.dart" to "abstract class CasinoLocalizations { String get title; }",
            "lib/l10n/arb/casino/casino_en.arb" to "{ \"title\": \"Casino\" }", "lib/l10n/arb/sport/sport_en.arb" to "{ \"title\": \"Sport\" }",
            "lib/l10n/arb/other/en.arb" to "{ \"title\": \"Other\" }",
        ),
    )

    fun `test bundle of another package than its class`() = assertEquals(
        listOf("app/lib/l10n/sport_en.arb:\"title\""),
        messages(
            "app/lib/main.dart" to "import '../../l10n/lib/sport_localizations.dart';\nvoid f(SportLocalizations l) { l.<ref>title; }",
            "app/pubspec.yaml" to "name: app\n", "l10n/pubspec.yaml" to "name: l10n\n",
            "l10n/lib/sport_localizations.dart" to "abstract class SportLocalizations { String get title; }\nclass SportLocalizationsEn extends SportLocalizations {}",
            "app/lib/l10n/sport_en.arb" to "{ \"title\": \"Sport\" }", "app/lib/l10n/casino_en.arb" to "{ \"title\": \"Casino\" }",
        ),
    )

    fun `test bundles of another package are left out`() = assertEquals(
        listOf("lib/l10n/app_en.arb:\"helloWorld\"", "lib/l10n/app_fr.arb:\"helloWorld\""),
        messages("lib/main.dart" to "$use<ref>helloWorld; }", *bundles, "other/pubspec.yaml" to "name: other\n", "other/lib/l10n/app_en.arb" to english),
    )

    fun `test usages of a message are its getter`() {
        resolve("lib/main.dart" to "$use<ref>buttonCancel; }", *bundles)
        val arb = myFixture.configureFromTempProjectFile("lib/l10n/app_en.arb")
        val usages = myFixture.findUsages(checkNotNull(arb.findElementAt(arb.text.indexOf("Button_Cancel"))?.parent))
        assertEquals(listOf("main.dart"), usages.mapNotNull { it.file?.name })
    }

    fun `test rename of a message renames every locale and its getter`() {
        resolve("lib/main.dart" to "$use<ref>buttonCancel; }", *bundles)
        val main = myFixture.file
        val arb = myFixture.configureFromTempProjectFile("lib/l10n/app_en.arb")
        myFixture.renameElement(checkNotNull(arb.findElementAt(arb.text.indexOf("Button_Cancel"))?.parent), "Button_Close")
        assertTrue(main.text, "AppLocalizations.of(c).buttonClose;" in main.text)
        assertTrue(arb.text, "\"Button_Close\": \"Cancel\"" in arb.text)
        val other = checkNotNull(psiManager.findFile(myFixture.findFileInTempDir("lib/l10n/app_fr.arb")))
        assertTrue(other.text, "\"Button_Close\": \"Annuler\"" in other.text)
    }

    // ---------------------------------------------------------------- closures and generic calls

    private val a = "class A { void <def>m() {} }"

    fun `test closure parameter from a function typed parameter`() = one("$a void each(void action(A a)) {} void f() { each((x) => x.<ref>m()); }")
    fun `test closure parameter from a function type`() = one("$a void each(void Function(A) action) {} void f() { each((x) => x.<ref>m()); }")
    fun `test closure parameter from a typedef`() = one("$a typedef Use = void Function(A a); void each(Use use) {} void f() { each((x) => x.<ref>m()); }")
    fun `test closure parameter from an old typedef`() = one("$a typedef void Use(A a); void each(Use use) {} void f() { each((x) => x.<ref>m()); }")
    fun `test closure parameter from a generic typedef`() = one("$a typedef Use<T> = void Function(T value); void each(Use<A> use) {} void f() { each((x) => x.<ref>m()); }")
    fun `test closure given by name`() = one("$a void each({required void Function(A) action}) {} void f() { each(action: (x) => x.<ref>m()); }")
    fun `test closure given to a field`() = one("$a class W { final void Function(A) cb; W({required this.cb}); } void f() { W(cb: (x) => x.<ref>m()); }")
    fun `test second closure parameter`() = one("$a void two(void Function(int, A) g) {} void f() { two((i, x) => x.<ref>m()); }")
    fun `test closure after another argument`() = one("$a void run(int n, void Function(A) g) {} void f() { run(1, (x) => x.<ref>m()); }")
    fun `test closure parameter from the type argument of the receiver`() = one("$a class Box<T> { void each(void action(T item)) {} } void f(Box<A> b) { b.each((x) => x.<ref>m()); }")
    fun `test closure with a block body`() = one("$a class Box<T> { void each(void action(T item)) {} } void f(Box<A> b) { b.each((x) { x.<ref>m(); }); }")
    fun `test closure held by a typed variable`() = one("$a void f() { final void Function(A) g = (x) => x.<ref>m(); }")
    fun `test closure of an unknown function has no members`() = none("$a void f() { missing((x) => x.<ref>m()); }")
    fun `test written closure parameter type wins`() = one("class B { void <def>m() {} } class A { void m() {} } void each(void action(A a)) {} void f() { each((B x) => x.<ref>m()); }")

    private val box = "$a class B { A get a => A(); } class Box<T> { Box<R> map<R>(R convert(T item)) => throw 0; T get value => throw 0; }"

    fun `test type parameter from what a closure returns`() = one("$box void f(Box<B> b) { b.map((x) => x.a).value.<ref>m(); }")
    fun `test type parameter from the return of a block`() = one("$box void f(Box<B> b) { b.map((x) { return x.a; }).value.<ref>m(); }")
    fun `test chained generic calls`() = one("$box void f(Box<B> b) { b.map((x) => x).map((y) => y.a).value.<ref>m(); }")
    fun `test written type arguments of a call`() = one("$a T make<T>() => throw 0; void f() { make<A>().<ref>m(); }")
    fun `test type parameter from an argument`() = one("$a T same<T>(T v) => v; void f() { same(A()).<ref>m(); }")
    fun `test type parameter from a named argument`() = one("$a T same<T>({required T v}) => v; void f() { same(v: A()).<ref>m(); }")
    fun `test type parameter inside a type`() = one("$a class Box<T> { T? value; } T open<T>(Box<T> b) => throw 0; void f(Box<A> b) { open(b).<ref>m(); }")
    fun `test type parameter of a constructor`() = one("$a class Box<T> { final T value; Box(this.value); } void f() { Box(A()).value.<ref>m(); }")
    fun `test type parameter of a named constructor`() = one("$a class Box<T> { T? value; Box.of(T v); } void f() { Box.of(A()).value.<ref>m(); }")
    fun `test type parameter of a constructed variable`() = one("$a class Box<T> { final T value; Box(this.value); } void f() { final b = Box(A()); b.value.<ref>m(); }")
    fun `test call of a variable holding a closure`() = one("$a void f() { final make = () => A(); make().<ref>m(); }")
    fun `test call of a function typed variable`() = one("$a void f(A Function() make) { make().<ref>m(); }")
    fun `test call of a function typed field`() = one("$a class W { final A Function(int) make; W(this.make); } void f(W w) { w.make(1).<ref>m(); }")

    fun `test map of a list`() = check("main.dart" to "$a void f(List<A> xs) { xs.map((e) => e.<ref>m()); }", *sdk)
    fun `test where then first`() = check("main.dart" to "$a void f(List<A> xs) { xs.where((e) => true).first.<ref>m(); }", *sdk)
    fun `test map then first`() = check("main.dart" to "$a class B { A get a => A(); } void f(List<B> xs) { xs.map((e) => e.a).first.<ref>m(); }", *sdk)
    fun `test for in over a mapped list`() = check("main.dart" to "$a class B { A get a => A(); } void f(List<B> xs) { for (final x in xs.map((e) => e.a)) { x.<ref>m(); } }", *sdk)
    fun `test then of a future`() = check("main.dart" to "$a Future<A> load() => throw 0; void f() { load().then((v) => v.<ref>m()); }", *sdk)
    fun `test type parameter through a supertype`() = check("main.dart" to "$a T head<T>(Iterable<T> xs) => throw 0; void f(List<A> xs) { head(xs).<ref>m(); }", *sdk)

    // ---------------------------------------------------------------- named arguments

    fun `test named argument of a function`() = one("void g({int <def>size = 0}) {} void f() { g(<ref>size: 1); }")
    fun `test named argument of a method`() = one("class A { void g({int <def>size = 0}) {} } void f(A a) { a.g(<ref>size: 1); }")
    fun `test named argument of a constructor`() = one("class A { A({int <def>size = 0}); } void f() { A(<ref>size: 1); }")
    fun `test named argument that is a field`() = one("class A { final int <def>size; A({required this.size}); } void f() { A(<ref>size: 1); }")
    fun `test named argument of a named constructor`() = one("class A { A.of({int <def>size = 0}); } void f() { A.of(<ref>size: 1); }")
    fun `test named argument after const`() = one("class A { const A({int <def>size = 0}); } void f() { const A(<ref>size: 1); }")
    fun `test named argument of a super call`() = one("class B { B({int <def>size = 0}); } class A extends B { A() : super(<ref>size: 1); }")
    fun `test named argument of a redirection`() = one("class A { A({int <def>size = 0}); A.one() : this(<ref>size: 1); }")
    fun `test named argument of an annotation`() = one("class Tag { const Tag({String <def>label = ''}); } @Tag(<ref>label: 'x') void f() {}")
    fun `test unknown named argument`() = none("void g({int size = 0}) {} void f() { g(<ref>other: 1); }")

    // ---------------------------------------------------------------- libraries

    fun `test import`() = check("lib/shape.dart" to "class <def>Shape {}", "lib/main.dart" to "import 'shape.dart';\nvoid f(<ref>Shape s) {}")
    fun `test import uri`() {
        val targets = resolve("lib/shape.dart" to "class Shape {}", "lib/main.dart" to "import '<ref>shape.dart';")
        assertEquals(listOf("shape.dart"), targets.map { (it as PsiFile).name })
    }
    fun `test import in another folder`() = check("lib/src/shape.dart" to "class <def>Shape {}", "lib/main.dart" to "import 'src/shape.dart';\nvoid f(<ref>Shape s) {}")
    fun `test show`() = check("shape.dart" to "class <def>Shape {} class Other {}", "main.dart" to "import 'shape.dart' show Shape;\nvoid f(<ref>Shape s) {}")
    fun `test name not shown`() = assertEmpty(resolve("shape.dart" to "class Shape {} class Other {}", "main.dart" to "import 'shape.dart' show Shape;\nvoid f(<ref>Other s) {}"))
    fun `test hidden name`() = assertEmpty(resolve("shape.dart" to "class Shape {}", "main.dart" to "import 'shape.dart' hide Shape;\nvoid f(<ref>Shape s) {}"))
    fun `test name of a show list`() = check("shape.dart" to "class <def>Shape {}", "main.dart" to "import 'shape.dart' show <ref>Shape;")
    fun `test private name is not imported`() = assertEmpty(resolve("shape.dart" to "class _Shape {}", "main.dart" to "import 'shape.dart';\nvoid f(<ref>_Shape s) {}"))
    fun `test import prefix`() = check("shape.dart" to "class <def>Shape {}", "main.dart" to "import 'shape.dart' as s;\nvoid f(s.<ref>Shape x) {}")
    fun `test import prefix itself`() = one("import 'shape.dart' as <def>s;\nvoid f(<ref>s.Shape x) {}")
    fun `test prefixed name needs its prefix`() = assertEmpty(resolve("shape.dart" to "class Shape {}", "main.dart" to "import 'shape.dart' as s;\nvoid f(<ref>Shape x) {}"))
    fun `test member through a prefix`() = check("shape.dart" to "class Shape { static int <def>count = 0; }", "main.dart" to "import 'shape.dart' as s;\nvoid f() { s.Shape.<ref>count; }")
    fun `test export`() = check("src/shape.dart" to "class <def>Shape {}", "shapes.dart" to "export 'src/shape.dart';", "main.dart" to "import 'shapes.dart';\nvoid f(<ref>Shape s) {}")
    fun `test export with show`() = assertEmpty(resolve("src/shape.dart" to "class Shape {} class Hidden {}", "shapes.dart" to "export 'src/shape.dart' show Shape;",
        "main.dart" to "import 'shapes.dart';\nvoid f(<ref>Hidden s) {}"))
    fun `test export chain`() = check("a.dart" to "class <def>Shape {}", "b.dart" to "export 'a.dart';", "c.dart" to "export 'b.dart';\nexport 'c.dart';",
        "main.dart" to "import 'c.dart';\nvoid f(<ref>Shape s) {}")
    fun `test part`() = check("src/part.dart" to "part of '../main.dart';\nclass <def>Shape {}", "main.dart" to "part 'src/part.dart';\nvoid f(<ref>Shape s) {}")
    fun `test name of the library from a part`() = check("main.dart" to "part 'part.dart';\nclass <def>Shape {}", "part.dart" to "part of 'main.dart';\nvoid f(<ref>Shape s) {}")
    fun `test part of a named library`() = check("main.dart" to "library app;\npart 'part.dart';\nclass <def>Shape {}", "part.dart" to "part of app;\nvoid f(<ref>Shape s) {}")
    fun `test import seen from a part`() = check("shape.dart" to "class <def>Shape {}", "main.dart" to "import 'shape.dart';\npart 'part.dart';",
        "part.dart" to "part of 'main.dart';\nvoid f(<ref>Shape s) {}")
    fun `test private name of the library in a part`() = check("main.dart" to "part 'part.dart';\nclass <def>_Shape {}", "part.dart" to "part of 'main.dart';\nvoid f(<ref>_Shape s) {}")
    fun `test package import of the project`() = check("pubspec.yaml" to "name: app\n", "lib/src/shape.dart" to "class <def>Shape {}",
        "test/shape_test.dart" to "import 'package:app/src/shape.dart';\nvoid f(<ref>Shape s) {}")
    fun `test package import of a dependency`() = check("packages/shapes/lib/shapes.dart" to "class <def>Shape {}",
        "lib/main.dart" to "import 'package:shapes/shapes.dart';\nvoid f(<ref>Shape s) {}", *sdk)
    fun `test package uri`() {
        val targets = resolve("packages/shapes/lib/shapes.dart" to "class Shape {}", "lib/main.dart" to "import '<ref>package:shapes/shapes.dart';", *sdk)
        assertEquals(listOf("shapes.dart"), targets.map { (it as PsiFile).name })
    }
    fun `test package without an sdk of its own keeps the one of the project`() = check(
        "packages/shapes/.dart_tool/package_config.json" to """{"configVersion": 2, "packages": []}""",
        "packages/shapes/lib/shapes.dart" to "String label() => '';",
        "lib/main.dart" to "import 'package:shapes/shapes.dart';\nint f() => label().<ref>length;",
        *sdk.map { it.first to it.second.replace("int get length => 0", "int get <def>length => 0") }.toTypedArray(),
    )
    fun `test unknown package`() = assertEmpty(resolve("main.dart" to "import '<ref>package:nothing/x.dart';"))
    fun `test extension of an import`() = check("ext.dart" to "extension Ext on String { void <def>shout() {} }", "main.dart" to "import 'ext.dart';\nvoid f(String s) { s.<ref>shout(); }")

    // ---------------------------------------------------------------- parsing, colors, usages, rename

    fun `test sample parses without error`() {
        val file = myFixture.configureByText("a.dart", """
            import 'package:shapes/shape.dart' as s show Shape;
            typedef Op = int Function(int a);
            enum Color { red, green; const Color(); }
            mixin Named on Object { String get label => 'x'; }
            sealed class Shape<T> extends Base with Named implements Comparable<T> {
              final double side;
              Shape(this.side, {required String name, int? size = 1}) : assert(side > 0);
              factory Shape.of(double v) => throw v;
              double scale<R>(R factor, [int n = 2]) { final x = side * n; return x; }
            }
            extension type Id(int value) implements int {}
            void main(List<String> args) async {
              var list = <Shape>[];
              final total = list.map((e) => e.side).first;
              final (a, b) = (1, 2);
              final label = switch (total) { > 1 => 'big', _ => 'small' };
              if (total case final double d when d > 0) {}
              final record = (x: 1, y: 2);
              list..add(a)..add(b);
            }
        """.trimIndent())
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription })
    }

    private fun errors(code: String): List<String> =
        PsiTreeUtil.findChildrenOfType(myFixture.configureByText("a.dart", code), PsiErrorElement::class.java).map { "${it.errorDescription} at ${it.textOffset}" }

    fun `test record type of a required parameter`() {
        assertEmpty(errors("void f({required (A, B) pair, required (A, void Function(B) on)? cb, required({int x}) named}) {}"))
        assertEmpty(errors("class X { X({required (A, B)? cb, required this.x}); void g(void required(int a), {required int Function() h}) {} }"))
    }

    fun `test function type as a record field`() {
        assertEmpty(errors("final cases = <({String given, (A, B) Function() build})>[]; (A, B) Function(int)? make; typedef P = (int, int)? Function();"))
        assertEmpty(errors("void f((A, B) Function() g, {required (A, B) Function()? h}) { final (a, b) = g(); (a, b) = h!(); }"))
        assertEmpty(errors("typedef R = (void Function() cb, int n);"))
        assertEmpty(errors("(int Function(int), String) pair() => throw 0;"))
        assertEmpty(errors("class A { final ({void Function()? onTap, String label}) x; final (void Function(),) y; A(this.x, this.y); }"))
        assertEmpty(errors("void f() { (void Function(int) a, int b) r = (g, 1); final (bool Function(), int)? q = null; }"))
        assertEmpty(errors("final List<({String title, Widget Function(BuildContext) builder})> items = [];"))
        assertEmpty(errors("Future<(A, B Function(C)?)> load((int, String Function()) r, {required (A, void Function(B) on)? cb}) async => throw 0;"))
        assertEmpty(errors("class A { (A Function<T>(T), int) get pair => throw 0; static const (int Function(), {String Function()? name}) z = (f, name: null); }"))
        assertEmpty(errors("typedef F = void Function((int, void Function()) r); void g() { for (final (a, b) in <(int, void Function())>[]) {} }"))
        assertEmpty(errors("void g() { final ({VoidCallback? onTap, T Function<T>() make}) r; final (Function, Function()) s; }"))
    }

    fun `test cascade from an index`() {
        assertEmpty(errors("void f(List<A> l) { l..[0].x = 1..[1].y()..[2]![3].z..[4] = 5; }"))
        assertEmpty(errors("void f(List<A> l) => l..[0].x.add(1)..length;"))
    }

    fun `test alternatives with a guard in a switch`() {
        assertEmpty(errors("int f(Object o, bool c) => switch (o) { A() || B() when c => 1, 1 || 2 when c => 3, C.a || C.b when c => 4, _ => 2 };"))
        assertEmpty(errors("void f(Object o, bool c) { switch (o) { case 1 || 2 when c: break; case C.a || C.b when c && !c: break; } }"))
        assertEmpty(errors("void f(Object o, bool c) { if (o case 1 || 2 when c) {} }"))
    }

    fun `test lexer colors`() {
        myFixture.configureByText("a.dart", "/// doc\nclass A { final x = 'a \$b'; } // end")
        val highlighter = DartSyntaxHighlighter()
        val lexer = highlighter.highlightingLexer.apply { start(myFixture.file.text) }
        val colors = generateSequence { lexer.tokenType?.also { lexer.advance() } }.flatMap { highlighter.getTokenHighlights(it).asSequence() }.toSet()
        assertTrue(colors.containsAll(listOf(DartColors.DOC_COMMENT, DartColors.KEYWORD, DartColors.STRING, DartColors.LINE_COMMENT, DartColors.BRACES)))
    }

    fun `test annotator colors`() {
        myFixture.configureByText("a.dart", "class A { int n = 0; void m(int p) { g(p); } }\nvoid g(int v) {}\n@override A make({A? other}) => A();")
        val keys = myFixture.doHighlighting().mapNotNull { it.forcedTextAttributesKey }.toSet()
        assertTrue(keys.toString(), keys.containsAll(listOf(DartColors.CLASS, DartColors.FIELD, DartColors.FUNCTION_DECL, DartColors.PARAMETER, DartColors.FUNCTION_CALL, DartColors.TYPE)))
    }

    private fun named(file: PsiFile, name: String): DartName = PsiTreeUtil.findChildrenOfType(file, DartName::class.java).first { it.name == name }

    fun `test usages and rename of a local`() {
        val file = myFixture.configureByText("a.dart", "void f() { var count = 1; count++; print(count); }\nvar count = 0;")
        val local = named(file, "count")
        assertEquals(2, ReferencesSearch.search(local).findAll().size)
        myFixture.renameElement(local, "total")
        assertEquals("void f() { var total = 1; total++; print(total); }\nvar count = 0;", file.text)
    }

    fun `test rename of a class reaches its constructors and the other files`() {
        val shape = myFixture.addFileToProject("shape.dart", "class Shape { Shape(); Shape.unit(); factory Shape.of() => Shape(); }")
        val main = myFixture.addFileToProject("main.dart", "import 'shape.dart';\nShape f() => Shape.unit();")
        myFixture.configureFromExistingVirtualFile(shape.virtualFile)
        myFixture.renameElement(named(shape, "Shape"), "Form")
        assertEquals("class Form { Form(); Form.unit(); factory Form.of() => Form(); }", shape.text)
        assertEquals("import 'shape.dart';\nForm f() => Form.unit();", main.text)
    }

    fun `test rename of a field reaches named arguments and field parameters`() {
        val file = myFixture.configureByText("a.dart", "class A { final int size; A({required this.size}); }\nint f() => A(size: 1).size;")
        myFixture.renameElement(named(file, "size"), "width")
        assertEquals("class A { final int width; A({required this.width}); }\nint f() => A(width: 1).width;", file.text)
    }

    fun `test usages of a method in another file`() {
        val shape = myFixture.addFileToProject("shape.dart", "class Shape { void draw() {} }")
        myFixture.addFileToProject("main.dart", "import 'shape.dart';\nvoid f(Shape s) { s.draw(); s..draw(); }")
        assertEquals(2, ReferencesSearch.search(named(shape, "draw")).findAll().size)
    }

    fun `test rename of a file updates its imports`() {
        val shape = myFixture.addFileToProject("src/shape.dart", "class Shape {}")
        val main = myFixture.addFileToProject("main.dart", "import 'src/shape.dart';\nShape? s;")
        myFixture.renameElement(shape, "form.dart")
        assertEquals("import 'src/form.dart';\nShape? s;", main.text)
    }

    fun `test kinds`() {
        val file = myFixture.configureByText("a.dart", "class A { int n = 0; void m(int p) {} A.make(); int get g => 1; }\nenum E { v }\ntypedef T = int;\nvoid f() { var l = 1; }")
        val provider = DartFindUsagesProvider()
        val kinds = listOf("A", "n", "m", "p", "make", "g", "E", "v", "T", "f", "l").associateWith { provider.getType(named(file, it)) }
        assertEquals(mapOf("A" to "class", "n" to "variable", "m" to "method", "p" to "parameter", "make" to "constructor", "g" to "getter", "E" to "enum",
            "v" to "enum value", "T" to "typedef", "f" to "function", "l" to "variable"), kinds)
    }

    /** LITE_DART_SDK=<flutter root>: names of the real SDK and of Flutter, through the exports of `material.dart`. */
    fun `test real sdk`() {
        val flutter = System.getenv("LITE_DART_SDK")?.let(::File)?.canonicalFile ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, flutter.path)
        myFixture.addFileToProject(".dart_tool/package_config.json", """{"configVersion": 2, "flutterRoot": "${flutter.toURI()}", "packages": [
            {"name": "flutter", "rootUri": "${File(flutter, "packages/flutter").toURI()}", "packageUri": "lib/"}]}""")
        val source = """
            import 'package:flutter/material.dart';
            class Badge extends StatelessWidget {
              final String label;
              const Badge({super.key, required this.label});
              Widget build(BuildContext context) => Padding(padding: EdgeInsets.all(8), child: Text(label.trim(), style: const TextStyle(fontSize: 12)));
            }
            void main() { final xs = <int>[1]; print(xs.first.abs()); runApp(const Badge(label: 'x')); }
            Widget list(List<String> names) => ListView.builder(itemCount: names.length, itemBuilder: (context, index) => Text(context.mounted ? names[index] : index.isOdd.toString()));
            void more(List<String> names) { names.map((s) => s.trimLeft()).toList(); Future<int>.value(1).then((v) => v.isEven); names.where((n) => n.isNotEmpty).forEach((w) => w.codeUnits); }
        """.trimIndent()
        val file = myFixture.addFileToProject("lib/main.dart", source)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val started = System.nanoTime()
        val names = listOf("StatelessWidget", "key", "Widget", "BuildContext", "Padding", "padding", "EdgeInsets", "all", "child", "Text", "trim", "style",
            "TextStyle", "fontSize", "first", "abs", "print", "runApp", "itemBuilder", "mounted", "isOdd", "trimLeft", "toList", "isEven", "isNotEmpty", "codeUnits")
        val found = names.associateWith { name ->
            val at = Regex("""\b$name\b""").find(source)!!.range.first
            (file.findReferenceAt(at) as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element?.containingFile?.name }.orEmpty()
        }
        println("DART SDK ${(System.nanoTime() - started) / 1_000_000} ms: $found")
        assertEmpty(found.filterValues { it.isEmpty() }.keys)
    }

    /** LITE_DART_CORPUS=<dir:dir>: every `.dart` file parses (at most 2% of the files with errors), and the share of names resolved. */
    fun `test corpus`() {
        val roots = System.getenv("LITE_DART_CORPUS")?.split(File.pathSeparator)?.map(::File) ?: return
        for (root in roots) {
            VfsRootAccess.allowRootAccess(testRootDisposable, root.canonicalPath)
            val files = root.walkTopDown().onEnter { it.name != ".dart_tool" && it.name != "build" && !it.name.startsWith(".") || it == root }
                .filter { it.isFile && it.name.endsWith(".dart") && !it.name.endsWith(".g.dart") && !it.name.endsWith(".freezed.dart") }.take(4000).toList()
            val started = System.nanoTime()
            var broken = 0; var names = 0; var resolved = 0
            val errors = ArrayList<String>(); val misses = HashMap<String, Int>()
            for ((index, f) in files.withIndex()) {
                val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: continue
                val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
                if (error != null) { broken++; errors += "${f.path.removePrefix(root.path)}:${psi.text.take(error.textOffset).count { it == '\n' } + 1} ${error.errorDescription.take(80)}" }
                if (index % 20 != 0) continue
                for (ref in PsiTreeUtil.findChildrenOfType(psi, DartRef::class.java).filter { it.isName }) {
                    names++
                    if (DartResolver.resolve(ref).isNotEmpty()) resolved++ else misses.merge(ref.text, 1, Int::plus)
                }
            }
            println("DART CORPUS ${root.name}: ${files.size} files, $broken with errors, names $resolved/$names (one file in 20), ${(System.nanoTime() - started) / 1_000_000} ms")
            if (System.getenv("LITE_SHOW") != null) {
                errors.take(25).forEach { println("  error $it") }
                println("  misses " + misses.entries.sortedByDescending { it.value }.take(40).joinToString { "${it.key}:${it.value}" })
            }
            assertTrue("$broken of ${files.size} files with errors", broken * 50 <= files.size)
        }
    }
}
