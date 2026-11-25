package me.kevinlot.lite.lua

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class LuaTest : BasePlatformTestCase() {

    /** <ref> resolves to exactly <def> (or to the first file when it has no <def>). */
    private fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { fail("parse error: ${it.errorDescription} at '${file.text.substring(it.textOffset).take(30)}'") }
        val ref = generateSequence(file.findElementAt(refOffset)) { it.parent }.takeWhile { it !is PsiFile }
            .flatMap { it.references.asSequence() }.firstOrNull { it.rangeInElement.shiftRight(it.element.textRange.startOffset).containsOffset(refOffset) }
            as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        val describe = targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }
        if (defFile == null) return assertTrue("resolved to $describe", (targets.singleOrNull() as? PsiFile)?.virtualFile?.path?.endsWith(files.first().first) == true)
        assertEquals("resolved to $describe", 1, targets.size)
        val t = targets.single()
        val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
        assertTrue("resolved to $describe", t.containingFile.virtualFile.path.endsWith(defFile!!) && off == defOffset)
    }

    private fun one(code: String) = check("main.lua" to code)

    fun `test parses lua 5 4`() {
        val file = myFixture.configureByText("a.lua", """
            #!/usr/bin/env lua
            local a <const>, b <close> = 1, nil
            local t = { 1, 2; x = 3, ["y"] = 4, f = function(...) return ... end }
            local s = [==[ long ]] string ]==] .. 'q\'' .. "d\"" .. [[x]]
            --[[ block
            comment ]] -- line
            for i = 1, #t, 2 do if i > 1 then goto continue elseif i < 0 then break else end ::continue:: end
            for k, v in pairs(t) do print(k, v) end
            while true do break end
            repeat local z = 1 until z == 1
            local n = 0x1p4 + 3.5e-2 + 0xFF + 1 // 2 ~ 3 << 1 & 7 | 8
            local o = obj:method "str" { tbl = 1 }
            a.b.c = not -x ^ 2
            print(("%d"):format(1))
            return t
        """.trimIndent())
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let { "${it.errorDescription} at ${it.textOffset}: ${file.text.substring(it.textOffset).take(20)}" })
    }

    // ---------------------------------------------------------------- names

    fun `test local`() = one("local <def>x = 1\nprint(<ref>x)")
    fun `test shadowing in a nested block`() = one("local x = 1\ndo\n  local <def>x = 2\n  print(<ref>x)\nend")
    fun `test local is not visible in its own initializer`() = one("local <def>x = 1\nlocal x = <ref>x + 1")
    fun `test local declared after the use is not visible`() = one("<def>x = 1\nlocal function f() return <ref>x end\nlocal x = 2")
    fun `test parameter`() = one("local function f(<def>a, b)\n  return <ref>a + b\nend")
    fun `test local function is visible in its body`() = one("local function <def>fact(n)\n  return n <= 1 and 1 or n * <ref>fact(n - 1)\nend")
    fun `test numeric for`() = one("for <def>i = 1, 10 do print(<ref>i) end")
    fun `test generic for`() = one("for k, <def>v in pairs({}) do print(<ref>v) end")
    fun `test repeat until sees the block locals`() = one("repeat\n  local <def>done = true\nuntil <ref>done")
    fun `test goto label`() = one("for i = 1, 3 do\n  if i == 2 then goto <ref>continue end\n  ::<def>continue::\nend")
    fun `test global across files`() = check("util.lua" to "function <def>helper() end", "main.lua" to "<ref>helper()")
    fun `test global assignment`() = one("<def>DEBUG = false\nif <ref>DEBUG then end")

    // ---------------------------------------------------------------- modules and fields

    fun `test require`() = check("lua/app/util.lua" to "return {}", "init.lua" to "local u = require('<ref>app.util')")
    fun `test require init lua`() = check("app/init.lua" to "return {}", "main.lua" to "local app = require \"<ref>app\"")
    fun `test field of a required module`() = check(
        "lua/app/util.lua" to "local M = {}\nfunction M.<def>trim(s) return s end\nreturn M",
        "init.lua" to "local util = require('app.util')\nutil.<ref>trim(' x ')",
    )
    fun `test field of a module returning a table`() = check(
        "conf.lua" to "return {\n  <def>port = 8080,\n}",
        "main.lua" to "local conf = require('conf')\nprint(conf.<ref>port)",
    )
    fun `test field assigned later`() = one("local M = {}\nM.<def>version = '1'\nprint(M.<ref>version)")
    fun `test constructor field wins over a later assignment`() = one("local M = { <def>n = 1 }\nM.n = 2\nprint(M.<ref>n)")
    fun `test nested table`() = one("local M = { sub = { <def>x = 1 } }\nprint(M.sub.<ref>x)")
    fun `test method through self`() = one("local Dog = {}\nDog.__index = Dog\nfunction Dog:<def>bark() end\nfunction Dog:run()\n  self:<ref>bark()\nend")
    fun `test field set on self`() = one("local Dog = {}\nfunction Dog:init()\n  self.<def>name = 'rex'\nend\nfunction Dog:show()\n  print(self.<ref>name)\nend")
    fun `test object through a constructor`() = one(
        "local Dog = {}\nDog.__index = Dog\nfunction Dog.new()\n  return setmetatable({}, Dog)\nend\nfunction Dog:<def>bark() end\nlocal d = Dog.new()\nd:<ref>bark()",
    )
    fun `test global table`() = check("app.lua" to "App = {}\nfunction App.<def>start() end", "main.lua" to "App.<ref>start()")
    fun `test or default table`() = one("local M = M or {}\nM.<def>x = 1\nprint(M.<ref>x)")

    fun `test field of a local assigned later`() = one("local M\nM = {}\nfunction M.<def>new() end\nM.<ref>new()")
    fun `test field of a local of unknown value`() = one("local obj = unknown()\nobj.<def>x = 1\nprint(obj.<ref>x)")
    fun `test field of a parameter`() = one("local function f(opts)\n  opts.<def>x = 1\n  print(opts.<ref>x)\nend")
    fun `test fields of two parameters are apart`() = one("local function f(a, b)\n  a.x = 1\n  b.<def>x = 2\n  print(b.<ref>x)\nend")
    fun `test global defined through _G`() = check("util.lua" to "_G.<def>helper = function() end", "main.lua" to "<ref>helper()")
    fun `test global function defined through _ENV`() = one("function _ENV.<def>setup() end\n<ref>setup()")
    fun `test _G field is the global`() = one("<def>DEBUG = false\nprint(_G.<ref>DEBUG)")
    fun `test field of a global defined through _G`() = one("_G.App = {}\nfunction App.<def>start() end\n_G.App.<ref>start()")
    fun `test a local named _G is a plain table`() = one("local _G = {}\n_G.<def>x = 1\nx = 2\nprint(_G.<ref>x)")
    fun `test string key in a constructor`() = one("local t = { [<def>\"name\"] = 1 }\nprint(t.<ref>name)")
    fun `test string key assignment`() = one("local t = {}\nt[<def>'name'] = 1\nprint(t.<ref>name)")
    fun `test string key read`() = one("local t = { <def>name = 1 }\nprint(t[\"<ref>name\"])")
    fun `test string key chain`() = one("local t = { sub = { <def>x = 1 } }\nprint(t['sub'].<ref>x)")
    fun `test function alias keeps the return type`() = one(
        "local M = {}\nfunction M.new() return { <def>field = 1 } end\nlocal new = M.new\nlocal create = new\nprint(create().<ref>field)",
    )

    // ---------------------------------------------------------------- annotations

    private val foo = "---@class Foo\n---@field id number\n---@field <def>name string\nlocal Foo = {}\n"

    fun `test annotated type is a reference to its class`() = one("--- A thing.\n---@class <def>Foo\nlocal Foo = {}\n---@param p <ref>Foo\nlocal function f(p) end")
    fun `test class of another file`() = check("foo.lua" to "---@class <def>Foo\nlocal Foo = {}", "main.lua" to "---@type <ref>Foo|nil\nlocal x = make()")
    fun `test field of an annotated parameter`() = one(foo + "---@param n number\n---@param p Foo the thing\nlocal function f(n, p) return p.<ref>name end")
    fun `test field of an annotated method parameter`() = one(foo + "local M = {}\n---@param p? Foo[]\nfunction M.f(p) return p.<ref>name end")
    fun `test field of an annotated local`() = one(foo + "---@type Foo\nlocal x = make()\nprint(x.<ref>name)")
    fun `test field of an annotated global`() = one(foo + "---@type Foo\ncurrent = make()\nprint(current.<ref>name)")
    fun `test field of an annotated return`() = one(foo + "--- Builds one.\n---@return Foo\nlocal function make() return build() end\nprint(make().<ref>name)")
    fun `test annotated field through self`() = one(foo + "function Foo:show() print(self.<ref>name) end")
    fun `test annotation is not read across a blank line`() = one("---@class Foo\nlocal Foo = {}\n---@type Foo\n\nlocal x = {}\nx.<def>name = 1\nFoo.name = 2\nprint(x.<ref>name)")
    fun `test class owns the fields of its table`() = one("---@class Foo\nlocal Foo = { <def>size = 1 }\n---@param p Foo\nlocal function f(p) return p.<ref>size end")
    fun `test class owns the methods of its table`() = one("---@class Foo\nlocal Foo = {}\nfunction Foo:<def>show() end\n---@type Foo\nlocal x = make()\nx:<ref>show()")
    fun `test field of a parent class`() = one("---@class Base\n---@field <def>id number\n\n---@class Foo : Base\nlocal Foo = {}\n---@type Foo\nlocal x = make()\nprint(x.<ref>id)")
    fun `test parent class is a reference`() = one("---@class <def>Base\n\n---@class Foo : <ref>Base\nlocal Foo = {}")
    fun `test field typed by a class`() = one("---@class Bar\n---@field <def>n number\n\n---@class Foo\n---@field bar Bar\n\n---@param p Foo\nlocal function f(p) return p.bar.<ref>n end")

    // ---------------------------------------------------------------- editing

    fun `test rename string key`() {
        myFixture.configureByText("a.lua", "local t = { [\"name\"] = 1 }\nt['name'] = 2\nprint(t.na<caret>me, t[\"name\"])\n")
        myFixture.renameElementAtCaret("label")
        myFixture.checkResult("local t = { [\"label\"] = 1 }\nt['label'] = 2\nprint(t.label, t[\"label\"])\n")
    }

    fun `test rename annotated field and class`() {
        myFixture.configureByText("a.lua", "---@class Foo\n---@field name string\n\n---@param p Foo\nlocal function f(p) return p.na<caret>me end\n")
        myFixture.renameElementAtCaret("label")
        myFixture.checkResult("---@class Foo\n---@field label string\n\n---@param p Foo\nlocal function f(p) return p.label end\n")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("p Foo") + 3)
        myFixture.renameElementAtCaret("Thing")
        myFixture.checkResult("---@class Thing\n---@field label string\n\n---@param p Thing\nlocal function f(p) return p.label end\n")
    }

    fun `test rename local`() {
        myFixture.configureByText("a.lua", "local co<caret>unt = 0\ncount = count + 1\nprint(count)\n")
        myFixture.renameElementAtCaret("total")
        myFixture.checkResult("local total = 0\ntotal = total + 1\nprint(total)\n")
    }

    fun `test rename module function across files`() {
        val main = myFixture.addFileToProject("main.lua", "local u = require('util')\nu.trim('x')\n")
        myFixture.configureByText("util.lua", "local M = {}\nfunction M.tr<caret>im(s) return s end\nreturn M\n")
        myFixture.renameElementAtCaret("strip")
        myFixture.checkResult("local M = {}\nfunction M.strip(s) return s end\nreturn M\n")
        assertEquals("local u = require('util')\nu.strip('x')\n", main.text)
    }

    fun `test rename required file`() {
        val main = myFixture.addFileToProject("main.lua", "local u = require('lib.util')\n")
        val util = myFixture.addFileToProject("lib/util.lua", "return {}")
        myFixture.renameElement(util, "helpers.lua")
        assertEquals("local u = require('lib.helpers')\n", main.text)
    }

    fun `test rename file required with a long string`() {
        val main = myFixture.addFileToProject("main.lua", "local u = require [==[lib.util]==]\n")
        val util = myFixture.addFileToProject("lib/util.lua", "return {}")
        myFixture.renameElement(util, "helpers.lua")
        assertEquals("local u = require [==[lib.helpers]==]\n", main.text)
    }

    fun `test global of another file after that file changes`() {
        val util = myFixture.addFileToProject("util.lua", "function helper() end\n")
        val main = myFixture.configureByText("main.lua", "helper()\nrenamed()\n")
        val refs = PsiTreeUtil.findChildrenOfType(main, LuaNameRef::class.java).associateBy { it.name }
        assertEquals(util, LuaResolve.resolveName(refs.getValue("helper")).single().containingFile)
        assertEmpty(LuaResolve.resolveName(refs.getValue("renamed")))
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(util) ?: error("no document")
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("function renamed() end\n")
            documents.commitDocument(document)
        }
        assertEmpty(LuaResolve.resolveName(refs.getValue("helper")))
        assertEquals(util, LuaResolve.resolveName(refs.getValue("renamed")).single().containingFile)
    }

    fun `test find usages of a field`() {
        myFixture.addFileToProject("a.lua", "local u = require('util')\nu.trim('a')\nu.trim('b')\n")
        val util = myFixture.addFileToProject("util.lua", "local M = {}\nfunction M.trim(s) return s end\nlocal function other() return M.trim('c') end\nreturn M\n")
        val def = PsiTreeUtil.findChildrenOfType(util, LuaIndexExpr::class.java).first { it.isDefinitionSite }
        assertEquals(3, ReferencesSearch.search(def, GlobalSearchScope.projectScope(project)).findAll().size)
    }

    // ---------------------------------------------------------------- real files (read-only, optional)

    /** LITE_LUA_CORPUS=<dir:dir>: every .lua parses; names and fields resolved (globals of the runtime excepted). */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_LUA_CORPUS")?.split(':')?.map(::File) ?: return
        dirs.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        var files = 0; var broken = 0; var names = 0; var namesOk = 0; var fields = 0; var fieldsOk = 0; var typedMisses = 0
        val misses = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val started = System.nanoTime()
        for (d in dirs) for (f in d.walkTopDown().filter { it.isFile && it.extension == "lua" && it.length() < 1_000_000 }) {
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: continue
            files++
            PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.let { broken++; errors += "${f.path}:${it.textOffset}: ${it.errorDescription} '${psi.text.substring(it.textOffset).take(20)}'" }
            PsiTreeUtil.findChildrenOfType(psi, LuaNameRef::class.java).forEach { names++; if (LuaResolve.resolveName(it).isNotEmpty()) namesOk++ }
            PsiTreeUtil.findChildrenOfType(psi, LuaIndexExpr::class.java).forEach {
                fields++
                if (LuaResolve.resolveField(it).isNotEmpty()) { fieldsOk++; return@forEach }
                if (LuaResolve.typeOf(it.base).isEmpty()) return@forEach
                typedMisses++
                if (System.getenv("LITE_SHOW") != null && misses.size < 40) misses += "${f.name}: ${it.text.take(50)}"
            }
        }
        println("lua corpus in ${(System.nanoTime() - started) / 1_000_000} ms: ${files - broken}/$files files without errors, names $namesOk/$names, fields $fieldsOk/$fields ($typedMisses misses on a known table)\n" + errors.take(20).joinToString("\n") + misses.joinToString("\n"))
    }
}
