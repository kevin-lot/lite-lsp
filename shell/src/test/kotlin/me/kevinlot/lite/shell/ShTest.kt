package me.kevinlot.lite.shell

import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ShTest : BasePlatformTestCase() {

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
        val ref = file.findReferenceAt(refOffset) as? PsiPolyVariantReference ?: error("no reference at <ref>")
        val targets = ref.multiResolve(false).mapNotNull { it.element }
        if (defFile == null) return assertEmpty(targets)
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(40)}" }}", targets.any { t ->
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile!!) && (off == defOffset || defOffset == 0)
        })
    }

    private fun one(code: String) = check("main.sh" to code)

    private fun parses(code: String) {
        val file = myFixture.configureByText("a.sh", code.trimIndent())
        val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        assertNull(error?.let { "${it.errorDescription} at '${file.text.substring(it.textOffset).take(30)}'" }, error)
    }

    // ---------------------------------------------------------------- parser

    fun `test parses shell`() = parses("""
        #!/usr/bin/env bash
        set -euo pipefail
        readonly DIR="$(cd "$(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
        arr=(one "two three" ${'$'}'four\n')
        declare -A map=([a]=1 [b]=2)
        usage() { echo "usage: ${'$'}0 [-v]" >&2; exit 1; }
        function log { printf '%s\n' "$*"; }
        while getopts "vh" opt; do
          case "${'$'}opt" in
            v) VERBOSE=1 ;;
            h|*) usage ;;
          esac
        done
        if [[ -n "${'$'}{VERBOSE:-}" && ${'$'}# -gt 0 ]]; then log "verbose"; elif [ -f x ]; then :; else true; fi
        for f in *.txt; do wc -l < "${'$'}f" | tee -a out.log 2>&1; done
        for ((i = 0; i < 3; i++)); do (( n += i )); done
        x=${'$'}(( n * 2 + ${'$'}{#arr[@]} ))
        cat <<-EOF > "${'$'}DIR/conf"
        	name=${'$'}x
        	date=${'$'}(date)
        	EOF
        cat <<'RAW'
        ${'$'}not ${'$'}(expanded)
        RAW
        diff <(sort a) <(sort b) || echo `whoami`
        { echo a; echo b; } | sort
        lines=("${'$'}{(f)output}")
        echo done fi then
    """)

    // ---------------------------------------------------------------- functions

    fun `test function call`() = one("<def>greet() { echo hi; }\n<ref>greet")
    fun `test function keyword`() = one("function <def>build { make; }\nif true; then <ref>build; fi")
    fun `test function with dash`() = one("<def>my-tool() { :; }\nx=$(<ref>my-tool)")
    fun `test function defined later`() = one("main() { <ref>helper; }\n<def>helper() { :; }\nmain")
    fun `test external command`() = one("<ref>grep foo bar")

    // ---------------------------------------------------------------- variables

    fun `test variable`() = one("<def>NAME=world\necho \"hello \$<ref>NAME\"")
    fun `test braced variable`() = one("<def>DIR=/tmp\necho \"\${<ref>DIR:-/var}/x\"")
    fun `test arithmetic variable`() = one("<def>n=1\necho $(( <ref>n + 1 ))")
    fun `test local variable`() = one("x=global\nf() {\n  local <def>x=inner\n  echo \"\$<ref>x\"\n}")
    fun `test local is not global`() = one("<def>x=global\nf() { local x=inner; }\necho \$<ref>x")
    fun `test for variable`() = one("for <def>file in *; do echo \"\$<ref>file\"; done")
    fun `test read variable`() = one("while read -r <def>line; do echo \"\$<ref>line\"; done < f")
    fun `test export`() = one("export <def>PATH_EXTRA=/opt\necho \$<ref>PATH_EXTRA")
    fun `test heredoc variable`() = one("<def>who=me\ncat <<EOF\nhello \$<ref>who\nEOF")
    fun `test reassignment points to the first assignment`() = one("<def>COUNT=0\nfor f in *; do <ref>COUNT=1; done")
    fun `test environment variable is unresolved`() = one("echo \$<ref>HOME")

    // ---------------------------------------------------------------- source

    fun `test sourced function`() = check("lib.sh" to "<def>helper() { :; }", "main.sh" to "source ./lib.sh\n<ref>helper")
    fun `test dot source relative to script`() = check(
        "lib/common.sh" to "<def>LOG_LEVEL=info", "main.sh" to ". \"$(dirname \"$0\")/lib/common.sh\"\necho \$<ref>LOG_LEVEL",
    )
    fun `test source path navigates`() = check("lib.sh" to "<def>x=1", "main.sh" to "source <ref>lib.sh")

    // ---------------------------------------------------------------- editing

    fun `test rename function`() {
        myFixture.configureByText("a.sh", "gre<caret>et() { echo hi; }\ngreet\nx=$(greet)\n")
        myFixture.renameElementAtCaret("hello")
        myFixture.checkResult("hello() { echo hi; }\nhello\nx=$(hello)\n")
    }

    fun `test rename variable`() {
        myFixture.configureByText("a.sh", "CO<caret>UNT=1\necho \$COUNT \"\${COUNT}\" $(( COUNT + 1 ))\nCOUNT=2\n")
        myFixture.renameElementAtCaret("TOTAL")
        myFixture.checkResult("TOTAL=1\necho \$TOTAL \"\${TOTAL}\" $(( TOTAL + 1 ))\nTOTAL=2\n")
    }

    fun `test find usages honours scope`() {
        val lib = myFixture.addFileToProject("lib.sh", "helper() { :; }\n")
        myFixture.addFileToProject("a.sh", "source ./lib.sh\nhelper\n")
        myFixture.addFileToProject("b.sh", "source ./lib.sh\nhelper\nhelper\n")
        val fn = PsiTreeUtil.findChildOfType(lib, ShFunction::class.java)!!
        assertEquals(3, ReferencesSearch.search(fn, GlobalSearchScope.projectScope(project)).findAll().size)
        val onlyA = GlobalSearchScope.filesScope(project, listOf(myFixture.findFileInTempDir("a.sh")))
        assertEquals(1, ReferencesSearch.search(fn, onlyA).findAll().size)
    }

    // ---------------------------------------------------------------- real code (read-only, optional)

    /** LITE_SH_CORPUS=<dir:dir>: every shell script (by extension or `#!` line) must parse. */
    fun `test corpus parses`() {
        val dirs = System.getenv("LITE_SH_CORPUS")?.split(':')?.map(::File) ?: return
        dirs.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val shebang = Regex("^#!\\s*\\S*(/|env\\s+)(ba|z|da|k)?sh\\b")
        val files = dirs.flatMap { d ->
            d.walkTopDown().maxDepth(4).filter { f ->
                f.isFile && f.length() < 1_000_000 && (f.extension in setOf("sh", "bash", "zsh") || f.name in setOf(".zshrc", ".bashrc") ||
                    runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull()?.let { shebang.containsMatchIn(it) } == true)
            }.toList()
        }
        val broken = files.mapNotNull { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val psi = PsiFileFactory.getInstance(project).createFileFromText("x.sh", ShFileType, text)
            PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.let {
                "${f.path}:${text.substring(0, it.textOffset).count { c -> c == '\n' } + 1}: ${it.errorDescription} '${text.substring(it.textOffset).take(40).replace('\n', ' ')}'"
            }
        }
        println("shell corpus: ${files.size - broken.size}/${files.size} files without errors\n" + broken.take(30).joinToString("\n"))
        assertTrue(broken.size * 100 <= files.size * 2)
    }
}
