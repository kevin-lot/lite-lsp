package me.kevinlot.lite.markdown

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class MdTest : BasePlatformTestCase() {

    /** <ref> resolves into [defFile] at <def> (offset 0 of a file without <def>: the file itself). */
    private fun check(vararg files: Pair<String, String>, expectNone: Boolean = false) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        val refs = file.findElementAt(refOffset)?.let { leaf -> generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }.flatMap { e ->
            e.references.filter { it.rangeInElement.shiftRight(e.textRange.startOffset).containsOffset(refOffset) }.asSequence() } }.orEmpty().toList()
        if (expectNone) return assertEmpty(refs)
        assertFalse("no reference at <ref>", refs.isEmpty())
        val targets = refs.mapNotNull { it.resolve() }
        assertTrue("resolved to ${targets.map { "${(it as? PsiFile)?.name ?: it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }}", targets.any { t ->
            if (t is PsiFile) return@any t.virtualFile.path.endsWith(defFile ?: files.first().first)
            val off = (t as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile!!) && (off == defOffset || defOffset == 0)
        })
    }

    fun `test structure`() {
        val file = myFixture.configureByText("a.md", """
            ---
            title: x
            ---
            # Title
            Some *emphasis*, **strong**, ~~gone~~, `code`, snake_case_name and 2*3*4.
            - [ ] task with [link](other.md#part) and ![img](logo.png)
            > quote with <https://example.com> and https://example.com/path.
            ```kotlin
            fun main() { println("# not a header") }
            ```
            | a | b |
            |---|---|
            [ref]: https://example.com "Title"
            ## Second
        """.trimIndent())
        assertEquals(listOf("Title", "Second"), PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java).map { it.name })
        assertEquals(1, PsiTreeUtil.findChildrenOfType(file, MdCodeBlock::class.java).size)
        assertEquals(listOf("other.md#part", "logo.png", "https://example.com"), PsiTreeUtil.findChildrenOfType(file, MdLinkDestination::class.java).map { it.dest })
        assertEquals(listOf("ref"), PsiTreeUtil.findChildrenOfType(file, MdLinkDefinition::class.java).map { it.name })
    }

    // ---------------------------------------------------------------- links

    fun `test link to file`() = check("docs/setup.md" to "# Setup", "README.md" to "See [setup](<ref>docs/setup.md).")
    fun `test link to header of other file`() = check("docs/setup.md" to "# Intro\n## <def>Getting Started", "README.md" to "[go](docs/setup.md#<ref>getting-started)")
    fun `test link to header of same file`() = check("README.md" to "# Intro\n## <def>Build & Run\nSee [build](#<ref>build--run).")
    fun `test link to parent folder file`() = check("src/main.rs" to "fn main() {}", "docs/guide.md" to "[main](../<ref>src/main.rs)")
    fun `test encoded path`() = check("docs/my notes.md" to "x", "README.md" to "[n](docs/<ref>my%20notes.md)")
    fun `test image`() = check("img/logo.svg" to "<svg/>", "README.md" to "![logo](<ref>img/logo.svg)")
    fun `test reference link`() = check("README.md" to "See [the docs][<ref>docs].\n\n[<def>docs]: docs/index.md")
    fun `test shortcut reference`() = check("README.md" to "See [<ref>Docs].\n\n[<def>docs]: docs/index.md")
    fun `test definition destination`() = check("docs/index.md" to "x", "README.md" to "[docs]: <ref>docs/index.md")
    fun `test external link has no reference`() = check("README.md" to "[x](<ref>https://example.com/a.md)", expectNone = true)

    // ---------------------------------------------------------------- code blocks

    fun `test code block is injected`() {
        val file = myFixture.configureByText("a.md", "Text\n```md\n# Inner\n```\n")
        val block = PsiTreeUtil.findChildOfType(file, MdCodeBlock::class.java)!!
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(block)
        assertEquals("LiteMarkdown", injected?.firstOrNull()?.first?.language?.id)
    }

    fun `test unknown code block language is plain`() {
        val file = myFixture.configureByText("a.md", "```nope\nx\n```\n")
        assertNull(InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(PsiTreeUtil.findChildOfType(file, MdCodeBlock::class.java)!!))
    }

    // ---------------------------------------------------------------- editing

    fun `test rename header updates anchors`() {
        val readme = myFixture.addFileToProject("README.md", "[go](docs/setup.md#getting-started)\n")
        myFixture.configureByText("setup.md", "")
        val setup = myFixture.addFileToProject("docs/setup.md", "# Intro\n## Getting Started\nSee [here](#getting-started).\n")
        myFixture.configureFromExistingVirtualFile(setup.virtualFile)
        myFixture.editor.caretModel.moveToOffset(setup.text.indexOf("Getting"))
        myFixture.renameElementAtCaret("First Steps")
        assertEquals("# Intro\n## First Steps\nSee [here](#first-steps).\n", setup.text)
        assertEquals("[go](docs/setup.md#first-steps)\n", readme.text)
    }

    fun `test rename file updates links`() {
        val readme = myFixture.addFileToProject("README.md", "[setup](docs/setup.md#intro) and ![x](docs/setup.md)\n")
        val setup = myFixture.addFileToProject("docs/setup.md", "# Intro\n")
        myFixture.renameElement(setup, "install.md")
        assertEquals("[setup](docs/install.md#intro) and ![x](docs/install.md)\n", readme.text)
    }

    fun `test find usages of a header`() {
        val setup = myFixture.addFileToProject("docs/setup.md", "## Getting Started\n[a](#getting-started)\n")
        myFixture.addFileToProject("README.md", "[b](docs/setup.md#getting-started) [c](docs/setup.md#other)\n")
        val header = PsiTreeUtil.findChildOfType(setup, MdHeader::class.java)!!
        assertEquals(2, ReferencesSearch.search(header, GlobalSearchScope.projectScope(project)).findAll().size)
    }

    // ---------------------------------------------------------------- real documents (read-only, optional)

    /** LITE_MD_CORPUS=<dir:dir>: every .md parses; local links are counted and resolved. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_MD_CORPUS")?.split(':')?.map(::File) ?: return
        dirs.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val files = dirs.flatMap { d -> d.walkTopDown().onEnter { it.name !in setOf("node_modules", ".git", "target", "build") }.filter { it.isFile && it.extension == "md" }.toList() }
        var local = 0; var resolved = 0; val misses = mutableListOf<String>()
        for (f in files) {
            val psi = psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!) ?: continue
            for (d in PsiTreeUtil.findChildrenOfType(psi, MdLinkDestination::class.java)) {
                val refs = d.references
                if (refs.isEmpty()) continue
                local++
                if (refs.all { it.resolve() != null }) resolved++ else misses += "${f.path}: ${d.dest}"
            }
        }
        println("md corpus: ${files.size} files, $resolved/$local local links resolved\n" + misses.take(20).joinToString("\n"))
    }
}
