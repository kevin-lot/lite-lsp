package me.kevinlot.lite.markdown

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileEditor.FileEditorManager
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

    private fun tokens(text: String): List<Pair<String, String>> {
        val lexer = MdLexer()
        lexer.start(text)
        return generateSequence { lexer.tokenType?.let { type -> (type.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd)).also { lexer.advance() } } }.toList()
    }

    fun `test a backslash ending a destination stays inside the text`() {
        assertEquals("LINK_DEST" to "b\\", tokens("[a](b\\").last())
        assertEquals("LINK_DEST" to "b\\", tokens("[a]: b\\").last())
    }

    fun `test a very long rule`() {
        val rule = "-".repeat(50_000)
        assertEquals(listOf("HR" to rule), tokens(rule))
        assertEquals(listOf("HR" to "* * *"), tokens("* * *"))
        assertEquals("LIST_MARKER", tokens("- - x").first().first)
    }

    fun `test a longer fence holds a shorter one`() {
        val file = myFixture.configureByText("a.md", "````md\n```sh\nls\n```\n````\n# After\n")
        val block = PsiTreeUtil.findChildrenOfType(file, MdCodeBlock::class.java).single()
        assertEquals("md", block.info)
        assertEquals("```sh\nls\n```\n", block.codeRange?.substring(block.text))
        assertEquals(listOf("After"), PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java).map { it.name })
    }

    // ---------------------------------------------------------------- links

    fun `test link to file`() = check("docs/setup.md" to "# Setup", "README.md" to "See [setup](<ref>docs/setup.md).")
    fun `test link to header of other file`() = check("docs/setup.md" to "# Intro\n## <def>Getting Started", "README.md" to "[go](docs/setup.md#<ref>getting-started)")
    fun `test link to header of same file`() = check("README.md" to "# Intro\n## <def>Build & Run\nSee [build](#<ref>build--run).")
    fun `test link to a repeated header`() = check("README.md" to "# Setup\ntext\n# <def>Setup\nSee [again](#<ref>setup-1).")
    fun `test link to parent folder file`() = check("src/main.rs" to "fn main() {}", "docs/guide.md" to "[main](../<ref>src/main.rs)")
    fun `test encoded path`() = check("docs/my notes.md" to "x", "README.md" to "[n](docs/<ref>my%20notes.md)")
    fun `test image`() = check("img/logo.svg" to "<svg/>", "README.md" to "![logo](<ref>img/logo.svg)")
    fun `test reference link`() = check("README.md" to "See [the docs][<ref>docs].\n\n[<def>docs]: docs/index.md")
    fun `test shortcut reference`() = check("README.md" to "See [<ref>Docs].\n\n[<def>docs]: docs/index.md")
    fun `test definition destination`() = check("docs/index.md" to "x", "README.md" to "[docs]: <ref>docs/index.md")
    fun `test external link has no reference`() = check("README.md" to "[x](<ref>https://example.com/a.md)", expectNone = true)

    // ---------------------------------------------------------------- wrapped link text

    fun `test link text wrapped over two lines`() = check("docs/setup.md" to "# Setup", "README.md" to "see [the setup\nguide](<ref>docs/setup.md)")
    fun `test wrapped link text in a quote`() = check("docs/setup.md" to "# Setup", "README.md" to "> see [the setup\n> guide](<ref>docs/setup.md)")

    fun `test link text does not wrap over a blank line or into a block`() {
        fun destinations(text: String) = PsiTreeUtil.findChildrenOfType(myFixture.configureByText("a.md", text), MdLinkDestination::class.java).map { it.dest }
        assertEmpty(destinations("see [the setup\n\nguide](docs/setup.md)"))
        assertEmpty(destinations("see [the setup\n- guide](docs/setup.md)"))
        assertEmpty(destinations("# see [the setup\nguide](docs/setup.md)"))
        assertEquals(listOf("b.md"), destinations("see [a\nvery\nlong text](b.md)"))
    }

    // ---------------------------------------------------------------- setext headers

    fun `test link to a setext header`() = check("README.md" to "<def>Getting Started\n-----\ntext\n\nSee [x](#<ref>getting-started).")
    fun `test link to a setext header of other file`() = check("docs/setup.md" to "Intro\n=====\n\n<def>Build & Run\n---\n", "README.md" to "[go](docs/setup.md#<ref>build--run)")

    fun `test setext headers`() {
        val file = myFixture.configureByText("a.md", """
            Title
            =====
            text

            ---
            Sub title
            ---
            - item
            ---
            # Atx
            ---
            > quote
            ---
            ```
            code
            ```
            ---
            | a |
            ===
        """.trimIndent())
        val headers = PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java)
        assertEquals(listOf("Title" to 1, "Sub title" to 2, "Atx" to 1), headers.map { it.name to it.level })
        assertEquals(listOf("---", "---", "---", "---", "---"), tokens(file.text).filter { it.first == "HR" }.map { it.second })
    }

    fun `test rename setext header updates anchors and underline`() {
        val readme = myFixture.addFileToProject("README.md", "Getting Started\n===\n\nSee [here](#getting-started).\n")
        myFixture.configureFromExistingVirtualFile(readme.virtualFile)
        myFixture.editor.caretModel.moveToOffset(readme.text.indexOf("Getting"))
        myFixture.renameElementAtCaret("First Steps")
        assertEquals("First Steps\n===========\n\nSee [here](#first-steps).\n", readme.text)
    }

    /** The editor re-lexes from the nearest token whose state is 0: the tokens must come out the same. */
    fun `test lexing restarts at any state 0 token`() {
        val text = "Title\n===\ntext [a\nb](c.md) `x.kt` <img src=\"a.png\">\n---\n\n---\n> ```kotlin\n> val a = 1\n>\n> ```\n> # Quoted\n```\ncode\n```\n---\n[r]: x\n"
        fun tokens(from: Int): List<Triple<String, Int, Int>> {
            val lexer = MdLexer()
            lexer.start(text, from, text.length, 0)
            return generateSequence { lexer.tokenType?.let { type -> Triple(type.toString(), lexer.tokenStart, lexer.state).also { lexer.advance() } } }.toList()
        }
        val all = tokens(0)
        assertEquals(2, all.count { it.first == "SETEXT_MARKER" })
        assertEquals(2, all.count { it.first == "HR" })
        assertEquals(2, all.count { it.first == "FENCE_END" })
        all.forEachIndexed { i, (_, start, state) -> if (state == 0) assertEquals("restart at $start", all.drop(i), tokens(start)) }
    }

    // ---------------------------------------------------------------- link target forms

    fun `test link to a line`() = check("src/Foo.txt" to "a\nb\n<def>c\nd\n", "README.md" to "[x](src/Foo.txt#<ref>L3)")
    fun `test link to a line range`() = check("src/Foo.txt" to "a\nb\n<def>c\nd\n", "README.md" to "[x](src/Foo.txt#<ref>L3-L4)")
    fun `test link to a line keeps its file reference`() = check("src/Foo.txt" to "a\nb\n", "README.md" to "[x](<ref>src/Foo.txt#L2)")
    fun `test link without the markdown extension`() = check("docs/setup.md" to "# Setup", "README.md" to "[x](<ref>docs/setup)")
    fun `test link without the markdown extension to a header`() = check("docs/setup.md" to "# <def>Setup", "README.md" to "[x](docs/setup#<ref>setup)")
    fun `test a file named like the link wins over its markdown twin`() = check("docs/setup" to "x", "docs/setup.md" to "# Setup", "README.md" to "[x](<ref>docs/setup)")

    fun `test click on a line anchor opens the file at that line`() {
        myFixture.addFileToProject("src/Foo.txt", "a\nb\nc\nd\n")
        myFixture.configureByText("a.md", "[x](src/Foo.txt#L<caret>3-L4)")
        myFixture.performEditorAction("GotoDeclaration")
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertEquals("Foo.txt" to 4, editor?.virtualFile?.name to editor?.caretModel?.offset)
    }

    fun `test rename file keeps line anchors and missing extensions`() {
        val readme = myFixture.addFileToProject("README.md", "[a](docs/setup.md#L1) [b](docs/setup)\n")
        val setup = myFixture.addFileToProject("docs/setup.md", "# Intro\n")
        myFixture.renameElement(setup, "install.md")
        assertEquals("[a](docs/install.md#L1) [b](docs/install)\n", readme.text)
    }

    // ---------------------------------------------------------------- code spans

    fun `test code span naming a unique project file`() = check("src/deep/MdLexer.kt" to "x", "docs/a.md" to "See `<ref>MdLexer.kt`.")
    fun `test code span with a path relative to the document`() = check("docs/setup.md" to "# Setup", "README.md" to "See `<ref>docs/setup.md`.")
    fun `test code span with a path from the project root`() = check("tools/ide_diet.py" to "x", "docs/deep/a.md" to "Run ``<ref>tools/ide_diet.py``.")
    fun `test code span naming a symbol has no reference`() = check("src/MdLexer.kt" to "x", "README.md" to "See `<ref>MdLexer` and `a.b()`.", expectNone = true)
    fun `test code span with code has no reference`() = check("README.md" to "See `<ref>val a = b / c`.", expectNone = true)

    fun `test code span naming an ambiguous or missing file resolves to nothing`() {
        myFixture.addFileToProject("a/Foo.kt", "x")
        myFixture.addFileToProject("b/Foo.kt", "x")
        val file = myFixture.configureByText("a.md", "`Foo.kt` and `Missing.kt` and `no/such.md`")
        val spans = PsiTreeUtil.findChildrenOfType(file, MdCodeSpan::class.java)
        assertEquals(3, spans.size)
        spans.forEach { assertNull(it.text, it.reference?.resolve()) }
    }

    fun `test rename file updates code spans`() {
        val readme = myFixture.addFileToProject("README.md", "See `docs/setup.md` and `setup.md`.\n")
        val setup = myFixture.addFileToProject("docs/setup.md", "# Intro\n")
        myFixture.renameElement(setup, "install.md")
        assertEquals("See `docs/install.md` and `install.md`.\n", readme.text)
    }

    // ---------------------------------------------------------------- smaller forms

    fun `test image inside a link`() = check("docs/logo.png" to "x", "README.md" to "[![logo](<ref>docs/logo.png)](https://example.com) text")

    fun `test image inside a link keeps the outer destination`() {
        val file = myFixture.configureByText("a.md", "[![logo](docs/logo.png) text](other.md)")
        assertEquals(listOf("docs/logo.png", "other.md"), PsiTreeUtil.findChildrenOfType(file, MdLinkDestination::class.java).map { it.dest })
    }

    fun `test fenced code block inside a block quote`() {
        val file = myFixture.configureByText("a.md", "> Note\n> ```md\n> # Inner\n>\n> [x](y.md)\n> ```\n> # After\n\n# Last\n")
        val block = PsiTreeUtil.findChildrenOfType(file, MdCodeBlock::class.java).single()
        assertEquals("md", block.info)
        assertEquals("# Inner\n\n[x](y.md)\n", block.codeRanges.joinToString("") { it.substring(block.text) })
        assertEquals("# Inner\n\n[x](y.md)\n", InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(block)?.firstOrNull()?.first?.text)
        assertEquals(listOf("After", "Last"), PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java).map { it.name })
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, MdLinkDestination::class.java))
    }

    fun `test quoted fence ends with its quote`() {
        val file = myFixture.configureByText("a.md", "> ```sh\n> ls\n\n# After\n")
        assertEquals(listOf("After"), PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java).map { it.name })
    }

    fun `test html image source`() = check("docs/a.png" to "x", "README.md" to "<img src=\"<ref>docs/a.png\" width=\"10\">")
    fun `test html link`() = check("docs/x.md" to "# X", "README.md" to "See <a href='<ref>docs/x.md'>x</a>.")
    fun `test html link to a header`() = check("docs/x.md" to "# <def>Part", "README.md" to "See <a href=\"docs/x.md#<ref>part\">x</a>.")
    fun `test html image inside a link`() = check("docs/a.png" to "x", "README.md" to "[<img src=\"<ref>docs/a.png\">](https://example.com)")
    fun `test external html link has no reference`() = check("README.md" to "<a href=\"<ref>https://example.com/a.md\">x</a>", expectNone = true)

    fun `test rename file updates html links`() {
        val readme = myFixture.addFileToProject("README.md", "<a href=\"docs/setup.md#intro\"><img src=\"docs/setup.md\"></a>\n")
        val setup = myFixture.addFileToProject("docs/setup.md", "# Intro\n")
        myFixture.renameElement(setup, "install.md")
        assertEquals("<a href=\"docs/install.md#intro\"><img src=\"docs/install.md\"></a>\n", readme.text)
    }

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
                when { refs.all { it.resolve() != null } -> resolved++; else -> misses += "${f.path}: ${d.dest}" }
            }
        }
        println("md corpus: ${files.size} files, $resolved/$local local links resolved\n" + misses.take(20).joinToString("\n"))
    }
}
