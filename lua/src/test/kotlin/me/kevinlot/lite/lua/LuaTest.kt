package me.kevinlot.lite.lua

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
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

    // ---------------------------------------------------------------- editing

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
                if (LuaResolve.resolveField(it).isNotEmpty()) fieldsOk++
                else if (LuaResolve.typeOf(it.base).isNotEmpty()) { typedMisses++; if (System.getenv("LITE_SHOW") != null && misses.size < 40) misses += "${f.name}: ${it.text.take(50)}" }
            }
        }
        println("lua corpus in ${(System.nanoTime() - started) / 1_000_000} ms: ${files - broken}/$files files without errors, names $namesOk/$names, fields $fieldsOk/$fields ($typedMisses misses on a known table)\n" + errors.take(20).joinToString("\n") + misses.joinToString("\n"))
    }
}
