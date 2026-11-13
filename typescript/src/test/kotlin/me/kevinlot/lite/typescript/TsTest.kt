package me.kevinlot.lite.typescript

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.kevinlot.lite.typescript.psi.TsFunction
import me.kevinlot.lite.typescript.psi.TsReferenceExpr
import me.kevinlot.lite.typescript.resolve.TsEnvironments
import me.kevinlot.lite.typescript.resolve.TsProjectEnv
import java.io.File

class TsTest : BasePlatformTestCase() {

    /** A tiny standard library: a script, so its declarations are global (like lib.es5.d.ts). */
    private val lib = """
        interface Array<T> {
            length: number;
            map<U>(callbackfn: (value: T, index: number, array: T[]) => U): U[];
            find(predicate: (value: T) => boolean): T | undefined;
            forEach(callbackfn: (value: T) => void): void;
        }
        interface String { toUpperCase(): string; }
        interface Number { toFixed(digits?: number): string; }
        interface Boolean {}
        interface RegExp {}
        interface Object { toString(): string; }
        interface Function { call(thisArg: any): any; }
        interface Promise<T> { then<R>(onfulfilled: (value: T) => R): Promise<R>; }
        interface Map<K, V> { get(key: K): V | undefined; set(key: K, value: V): this; }
        interface MapConstructor { new <K, V>(): Map<K, V>; }
        declare var Map: MapConstructor;
        declare var console: { log(...data: any[]): void };
    """.trimIndent()

    private fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        assertNull("parse error: " + PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { "${it.errorDescription} at ${it.textOffset}" },
            PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
        val ref = file.findReferenceAt(refOffset) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        if (defFile == null) return assertEmpty(targets)
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(40)}" }}", targets.any { t ->
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile) && (off == defOffset || defOffset == 0)
        })
    }

    /** Resolution of <ref> never reaches a file under [forbidden]. */
    private fun checkNotIn(forbidden: String, vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5) }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        val ref = file.findReferenceAt(refOffset) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val paths = ref.multiResolve(false).mapNotNull { it.element?.containingFile?.virtualFile?.path }
        assertTrue(paths.toString(), paths.none { forbidden in it })
    }

    private fun one(code: String) = check("main.ts" to code)
    private fun withLib(code: String) = check("lib.d.ts" to lib, "main.ts" to code)

    private fun parses(name: String, code: String) {
        val file = myFixture.configureByText(name, code.trimIndent())
        val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        assertNull(error?.let { "${it.errorDescription} at '${file.text.substring(it.textOffset).take(30)}'" }, error)
    }

    // ---------------------------------------------------------------- parser

    fun `test parses typescript`() = parses("a.ts", """
        import type { A } from "./a";
        import def, * as ns from "x";
        export * from "./b";
        export { c as d, type E } from "./c";
        declare module "m" { export function f(): void; }
        declare global { interface Window { x: number } }
        namespace N.M { export const k = 1; }
        abstract class C<T extends object = {}> extends B<T> implements I, J<T> {
            private readonly x?: number = 1;
            static #count = 0;
            declare y: string;
            constructor(public a: string, protected b = 2) { super(); }
            get v(): T | undefined { return this.a as unknown as T; }
            abstract m<U>(u: U): asserts u is NonNullable<U>;
            [key: string]: unknown;
            static { C.#count++; }
        }
        type Fn = <T>(x: T, ...rest: T[]) => x is T;
        type M<T> = { readonly [K in keyof T]?: T[K] extends infer U ? U : never };
        type L = `a-${'$'}{string}` | 'b' | -1 | typeof import("./x").y;
        type Tup = [a: string, b?: number, ...c: boolean[]];
        enum E { A = 1, B, "c-d" }
        const enum F { X }
        function over(a: string): void;
        function over(a: any) { }
        let v = <T,>(x: T): T => x;
        const o = { a, b: 1, [k]: 2, m() {}, get g() { return 1 }, async *gen() {}, ...rest };
        const r = a?.b?.[c]?.(d)!.e satisfies X;
        for (const [k, v] of Object.entries(o)) { if (k in o) continue; }
        label: for (let i = 0; i < 10; i++) break label;
        const re = /[/]+/g.test(s) ? a / b / c : x >= y >> 2;
        async function* g() { yield* other(); await using res = x; }
        export default class {}
    """)

    fun `test parses jsx`() = parses("a.tsx", """
        import React from "react";
        export const App = ({ items }: Props) => (
            <Layout title="t" {...rest} data-x='1' on:click={() => go(<b>x</b>)}>
                {items.map(i => <Item key={i.id} {...i} />)}
                <>text {/* comment */} &amp; more</>
                <ui.Button disabled>ok</ui.Button>
            </Layout>
        );
        const cmp = a < b && c > d;
    """)

    fun `test parses javascript`() = parses("a.js", """
        #!/usr/bin/env node
        'use strict';
        const fs = require('fs');
        module.exports = { a: 1 };
        exports.b = function () { return this; };
        var self = this, x = a ? b : c;
        class K { #p = 1; static s() { return new.target; } }
        const t = tag`a${'$'}{b}c${'$'}{`nested ${'$'}{d}`}`;
        if (x) /re/.test(x);
    """)

    // ---------------------------------------------------------------- scopes

    fun `test local`() = one("function f() {\n  const <def>x = 1;\n  return <ref>x;\n}")
    fun `test parameter`() = one("function f(<def>a: number) { return <ref>a; }")
    fun `test closure`() = one("const <def>limit = 3;\nconst f = () => () => <ref>limit;")
    fun `test block scope`() = one("let <def>x = 1;\n{ let x = 2; }\nconsole.log(<ref>x);")
    fun `test hoisted var`() = one("function f() {\n  if (a) { var <def>v = 1; }\n  return <ref>v;\n}")
    fun `test function hoisting`() = one("<ref>g();\nfunction <def>g() {}")
    fun `test destructuring`() = one("const { a, b: <def>c } = obj;\n<ref>c;")
    fun `test catch parameter`() = one("try {} catch (<def>e) { <ref>e; }")
    fun `test arrow parameter`() = one("const f = <def>x => <ref>x;")
    fun `test type parameter`() = one("function f<<def>T>(x: <ref>T) {}")
    fun `test type vs value namespace`() = one("const Point = 1;\ninterface <def>Point { x: number }\nlet p: <ref>Point;")
    fun `test unresolved`() = one("<ref>nope;")

    // ---------------------------------------------------------------- members and types

    fun `test method via this`() = one("class A {\n  <def>m() {}\n  n() { this.<ref>m(); }\n}")
    fun `test annotated parameter`() = one("class A { <def>x = 1 }\nfunction f(a: A) { return a.<ref>x; }")
    fun `test interface member`() = one("interface P { <def>name: string }\nconst p: P = load();\np.<ref>name;")
    fun `test generic argument`() = one("class Item { <def>id = 1 }\ninterface Box<T> { value: T }\ndeclare const b: Box<Item>;\nb.value.<ref>id;")
    fun `test inherited method`() = one("class B { <def>m() {} }\nclass A extends B {}\nnew A().<ref>m();")
    fun `test generic base`() = one("class Item { <def>id = 1 }\nclass Box<T> { value!: T }\nclass Items extends Box<Item> {}\nnew Items().value.<ref>id;")
    fun `test interface extends`() = one("interface B { <def>b: number }\ninterface A extends B {}\ndeclare const a: A;\na.<ref>b;")
    fun `test return type chain`() = one("class B { <def>done() {} }\nclass A { next(): B { return new B(); } }\nnew A().next().<ref>done();")
    fun `test inferred return type`() = check("main.js" to "class B { <def>done() {} }\nfunction make() { return new B(); }\nmake().<ref>done();")
    fun `test object literal`() = one("const o = { <def>size: 1, grow() {} };\no.<ref>size;")
    fun `test parameter property`() = one("class A { constructor(private readonly <def>name: string) {} m() { return this.<ref>name; } }")
    fun `test javascript this assignment`() = check("main.js" to "class A {\n  constructor() { this.<def>count = 0; }\n  inc() { this.<ref>count++; }\n}")
    fun `test optional chaining`() = one("class A { <def>x = 1 }\ndeclare const a: A | undefined;\na?.<ref>x;")
    fun `test as expression`() = one("class A { <def>x = 1 }\n(data as A).<ref>x;")
    fun `test enum member`() = one("enum Color { Red, <def>Green }\nColor.<ref>Green;")
    fun `test namespace member`() = one("namespace Geo { export function <def>area() {} }\nGeo.<ref>area();")
    fun `test static member`() = one("class A { static <def>create() { return new A(); } }\nA.<ref>create();")
    fun `test type alias object`() = one("type P = { <def>x: number };\ndeclare const p: P;\np.<ref>x;")
    fun `test partial mapped type`() = withLib("type Partial<T> = { [K in keyof T]?: T[K] };\ninterface P { <def>x: number }\ndeclare const p: Partial<P>;\np.<ref>x;")
    fun `test destructured member key`() = one("interface P { <def>name: string }\ndeclare const p: P;\nconst { <ref>name: n } = p;")
    fun `test destructured parameter type`() = one("class A { <def>m() {} }\nfunction f({ a }: { a: A }) { a.<ref>m(); }")
    fun `test this return type`() = withLib("class Item { <def>id = 1 }\nconst m = new Map<string, Item>();\nm.set('a', new Item()).get('a')?.<ref>id;")

    // ---------------------------------------------------------------- standard library (mini lib)

    fun `test string method`() = check("lib.d.ts" to lib.replace("interface String { toUpperCase", "interface String { <def>toUpperCase"), "main.ts" to "'a'.<ref>toUpperCase();")
    fun `test callback parameter`() = withLib("class Item { <def>id = 1 }\nconst xs: Item[] = [];\nxs.map(x => x.<ref>id);")
    fun `test callback through generic return`() = withLib("class Item { <def>id = 1 }\nconst xs = [new Item()];\nxs.map(x => x).find(y => y.<ref>id === 1);")
    fun `test for of element`() = withLib("class Item { <def>id = 1 }\ndeclare const xs: Item[];\nfor (const x of xs) x.<ref>id;")
    fun `test await promise`() = withLib("class Item { <def>id = 1 }\ndeclare function load(): Promise<Item>;\nasync function f() { (await load()).<ref>id; }")
    fun `test then callback`() = withLib("class Item { <def>id = 1 }\ndeclare function load(): Promise<Item>;\nload().then(i => i.<ref>id);")
    fun `test new from constructor interface`() = withLib("class Item { <def>id = 1 }\nconst m = new Map<string, Item>();\nm.get('a')?.<ref>id;")
    fun `test global variable`() = check("lib.d.ts" to lib.replace("declare var console: { log(", "declare var console: { <def>log("), "main.ts" to "console.<ref>log(1);")

    // ---------------------------------------------------------------- modules

    fun `test named import`() = check("util.ts" to "export function <def>helper() {}", "main.ts" to "import { helper } from './util';\n<ref>helper();")
    fun `test import alias`() = check("util.ts" to "export const <def>x = 1;", "main.ts" to "import { x as y } from './util';\n<ref>y;")
    fun `test import specifier itself`() = check("util.ts" to "export const <def>x = 1;", "main.ts" to "import { <ref>x } from './util';")
    fun `test default import`() = check("util.ts" to "export default function <def>main() {}", "main.ts" to "import m from './util';\n<ref>m();")
    fun `test namespace import`() = check("util.ts" to "export class <def>K {}", "main.ts" to "import * as u from './util';\nnew u.<ref>K();")
    fun `test re-export`() = check("a.ts" to "export class <def>A {}", "index.ts" to "export { A } from './a';", "main.ts" to "import { A } from './index';\n<ref>A;")
    fun `test export star`() = check("a.ts" to "export class <def>A {}", "lib/index.ts" to "export * from '../a';", "main.ts" to "import { A } from './lib';\n<ref>A;")
    fun `test js extension maps to ts`() = check("a.ts" to "export class <def>A {}", "main.ts" to "import { A } from './a.js';\n<ref>A;")
    fun `test module specifier navigates to file`() = check("util.ts" to "<def>export const x = 1;", "main.ts" to "import { x } from '<ref>./util';")
    fun `test imported class member`() = check("a.ts" to "export class A { <def>m() {} }", "main.ts" to "import { A } from './a';\nnew A().<ref>m();")
    fun `test tsconfig paths`() = check(
        "tsconfig.json" to """{ "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["src/*"] } } }""",
        "src/models/user.ts" to "export class <def>User {}", "src/main.ts" to "import { User } from '@/models/user';\n<ref>User;",
    )
    fun `test package types entry`() = check(
        "package.json" to """{ "dependencies": { "lib": "1.0.0" } }""",
        "node_modules/lib/package.json" to """{ "name": "lib", "types": "./dist/index.d.ts" }""",
        "node_modules/lib/dist/index.d.ts" to "export declare function <def>run(): void;",
        "main.ts" to "import { run } from 'lib';\n<ref>run();",
    )
    fun `test package exports`() = check(
        "package.json" to "{}",
        "node_modules/@s/p/package.json" to """{ "exports": { ".": { "types": "./types/main.d.ts", "default": "./main.js" }, "./sub": "./sub.js" } }""",
        "node_modules/@s/p/types/main.d.ts" to "export interface <def>Opts {}",
        "main.ts" to "import type { Opts } from '@s/p';\nlet o: <ref>Opts;",
    )
    fun `test types package`() = check(
        "package.json" to "{}",
        "node_modules/@types/dep/index.d.ts" to "export function <def>go(): void;",
        "node_modules/dep/index.js" to "exports.go = () => {};",
        "main.ts" to "import { go } from 'dep';\n<ref>go();",
    )
    fun `test ambient module`() = check(
        "types.d.ts" to "declare module 'fsx' { export function <def>read(): string; }",
        "main.ts" to "import { read } from 'fsx';\n<ref>read();",
    )
    /** @types/node: `import assert from "node:assert/strict"`, `export =` a function merged with a namespace re-exporting aliases. */
    fun `test export assignment of merged function and namespace`() = check(
        "node.d.ts" to """
            declare module "assert" {
                function assert(value: unknown): void;
                namespace assert { function <def>strictEqual(a: unknown, b: unknown): void; }
                export = assert;
            }
            declare module "node:assert" { import assert = require("assert"); export = assert; }
            declare module "assert/strict" {
                import { strictEqual } from "node:assert";
                function strict(value: unknown): void;
                namespace strict { export { strictEqual, strictEqual as equal }; }
                export = strict;
            }
            declare module "node:assert/strict" { import strict = require("assert/strict"); export = strict; }
        """,
        "main.ts" to "import assert from 'node:assert/strict';\nassert.<ref>equal(1, 1);",
    )
    fun `test commonjs require`() = check("util.js" to "exports.<def>helper = function () {};", "main.js" to "const u = require('./util');\nu.<ref>helper();")
    fun `test commonjs module exports object`() = check("util.js" to "function <def>helper() {}\nmodule.exports = { helper };", "main.js" to "const { helper } = require('./util');\n<ref>helper();")
    fun `test module exports class`() = check("k.js" to "class <def>K {}\nmodule.exports = K;", "main.js" to "const K = require('./k');\nnew <ref>K();")
    fun `test bare specifier never above the project`() = checkNotIn("outer/node_modules",
        "outer/node_modules/lib/index.d.ts" to "export function run(): void;",
        "outer/app/package.json" to "{}",
        "outer/app/main.ts" to "import { run } from 'lib';\n<ref>run();",
    )

    // ---------------------------------------------------------------- JSX

    fun `test jsx component`() = check("Badge.tsx" to "export function <def>Badge() { return <span/>; }", "App.tsx" to "import { Badge } from './Badge';\nconst a = <<ref>Badge />;")
    fun `test jsx attribute expression`() = check("App.tsx" to "const <def>label = 'x';\nconst a = <div title={<ref>label}>{label}</div>;")
    fun `test jsx intrinsic has no reference`() {
        val file = myFixture.configureByText("A.tsx", "const a = <div/>;")
        val div = PsiTreeUtil.findChildrenOfType(file, TsReferenceExpr::class.java).first { it.referencedName == "div" }
        assertNull(div.reference)
    }

    // ---------------------------------------------------------------- editing

    fun `test rename method follows calls`() {
        myFixture.configureByText("a.ts", "class A {\n  ar<caret>ea() { return 1; }\n}\nnew A().area();\n")
        myFixture.renameElementAtCaret("size")
        myFixture.checkResult("class A {\n  size() { return 1; }\n}\nnew A().size();\n")
    }

    fun `test rename across import`() {
        myFixture.addFileToProject("util.ts", "export function helper() {}\n")
        val main = myFixture.addFileToProject("main.ts", "import { helper } from './util';\nhelper();\n")
        myFixture.configureFromTempProjectFile("util.ts")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("helper") + 1)
        myFixture.renameElementAtCaret("tool")
        assertEquals("import { tool } from './util';\ntool();\n", main.text)
    }

    fun `test rename local keeps other scopes`() {
        myFixture.configureByText("a.ts", "function f() {\n  const x<caret> = 1;\n  return x;\n}\nfunction g() {\n  const x = 2;\n  return x;\n}\n")
        myFixture.renameElementAtCaret("y")
        myFixture.checkResult("function f() {\n  const y = 1;\n  return y;\n}\nfunction g() {\n  const x = 2;\n  return x;\n}\n")
    }

    fun `test find usages honours scope`() {
        val util = myFixture.addFileToProject("util.ts", "export function helper() {}\n")
        myFixture.addFileToProject("a.ts", "import { helper } from './util';\nhelper();\n")
        myFixture.addFileToProject("b.ts", "import { helper } from './util';\nhelper();\nhelper();\n")
        val fn = PsiTreeUtil.findChildOfType(util, TsFunction::class.java)!!
        assertEquals(5, ReferencesSearch.search(fn, GlobalSearchScope.projectScope(project)).findAll().size)
        val onlyA = GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("a.ts")))
        assertEquals(2, ReferencesSearch.search(fn, onlyA).findAll().size)
    }

    // ---------------------------------------------------------------- real code (read-only, optional)

    /** LITE_TS_LIB=<typescript/lib>: navigation into the real standard library. */
    fun `test real standard library`() {
        val dir = System.getenv("LITE_TS_LIB") ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(dir)
        TsEnvironments.getInstance(project).setForTests(TsProjectEnv("/src", emptyList(), dir))
        WriteAction.runAndWait<RuntimeException> {
            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "test", emptyList(), listOf(LocalFileSystem.getInstance().findFileByPath(dir)!!), "test")
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val file = myFixture.addFileToProject("main.ts", """
            class Item { id = 1 }
            const xs = [new Item()];
            xs.map(x => x.id).forEach(n => n.toFixed());
            "a".toUpperCase().trim();
            new Map<string, Item>().get("k")?.id;
            Promise.resolve(new Item()).then(i => i.id);
            async function f() { const i = await Promise.resolve(new Item()); return i.id; }
            Object.entries({ a: 1 }).length;
            JSON.parse("{}");
            new Date().getTime();
        """.trimIndent())
        val refs = PsiTreeUtil.findChildrenOfType(file, TsReferenceExpr::class.java).filter { it.qualifier != null || it.referencedName in setOf("Promise", "Object", "JSON", "Map", "Date") }
        val unresolved = refs.filter { it.reference?.resolve() == null }.map { it.text }
        assertEmpty(unresolved)
    }

    /** LITE_TS_PROJECT=<dir> (+ LITE_TS_LIB): resolution rate of a whole project (sources only). */
    fun `test whole project`() {
        val dir = System.getenv("LITE_TS_PROJECT")?.let(::File) ?: return
        val lib = System.getenv("LITE_TS_LIB")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path, *listOfNotNull(lib).toTypedArray())
        val sources = setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "json")
        val files = dir.walkTopDown().onEnter { it.name !in setOf("node_modules", "dist", ".git") }.filter { it.isFile && it.extension in sources }.toList()
        val added = files.map { myFixture.addFileToProject("proj/" + it.relativeTo(dir).path, it.readText()) }
        val root = TsEnvironments.getInstance(project).projectRoot(added.first().virtualFile)!!
        val packages = TsEnvironments.packagesOf(dir)
        VfsRootAccess.allowRootAccess(testRootDisposable, *packages.toTypedArray())
        TsEnvironments.getInstance(project).setForTests(TsProjectEnv(root, packages, lib))
        val roots = (packages + listOfNotNull(lib)).mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
        if (roots.isNotEmpty()) {
            WriteAction.runAndWait<RuntimeException> { AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "test", emptyList(), roots, "test") }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        var total = 0; val unresolved = mutableMapOf<String, Int>(); val where = mutableListOf<String>()
        val started = System.nanoTime()
        added.filterIsInstance<TsFile>().forEach { f ->
            PsiTreeUtil.findChildrenOfType(f, TsReferenceExpr::class.java).forEach { r ->
                val ref = r.reference ?: return@forEach
                total++
                if ((ref as PsiPolyVariantReference).multiResolve(false).isEmpty()) {
                    unresolved.merge(r.referencedName.orEmpty(), 1, Int::plus)
                    where += "${f.name}:${f.text.substring(0, r.textOffset).count { it == '\n' } + 1} ${r.text.take(50)}"
                }
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("TSPROJECT ${total - unresolved.values.sum()}/$total resolved in $ms ms; unresolved: " + unresolved.entries.sortedByDescending { it.value }.joinToString { "${it.key}×${it.value}" })
        if (System.getenv("LITE_SHOW") != null) println(where.joinToString("\n"))
    }

    /** LITE_TS_CORPUS=<dir>: every .ts/.tsx/.js/.d.ts must parse. */
    fun `test corpus parses`() {
        val dir = System.getenv("LITE_TS_CORPUS")?.let(::File) ?: return
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val files = dir.walkTopDown().filter { it.extension in setOf("ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts") && it.length() < 3_000_000 }.toList()
        val started = System.nanoTime()
        val broken = files.mapNotNull { f ->
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: return@mapNotNull null
            PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.let {
                "${f.path.removePrefix(dir.path)}:${psi.text.substring(0, it.textOffset).count { c -> c == '\n' } + 1}: ${it.errorDescription} '${psi.text.substring(it.textOffset).take(40).replace('\n', ' ')}'"
            }
        }
        println("ts corpus: ${files.size - broken.size}/${files.size} files without errors in ${(System.nanoTime() - started) / 1_000_000} ms\n" + broken.take(25).joinToString("\n"))
        assertTrue(broken.size * 100 <= files.size)
    }
}
