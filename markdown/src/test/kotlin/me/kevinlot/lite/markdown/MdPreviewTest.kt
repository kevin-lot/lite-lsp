package me.kevinlot.lite.markdown

import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.HTMLEditorKitBuilder
import javax.swing.JEditorPane

class MdPreviewTest : BasePlatformTestCase() {

    private fun html(markdown: String, path: String = "docs/readme.md"): String {
        val file = myFixture.addFileToProject(path, markdown)
        return MdHtml.render(markdown, file.virtualFile)
    }

    fun `test gfm table`() {
        val h = html("| a | b |\n|---|:-:|\n| 1 | 2 |\n")
        assertTrue(h, "<table cellspacing=\"0\" cellpadding=\"5\">" in h && "<td" in h && "<th" in h)
    }

    fun `test headers get github anchors`() {
        val h = html("# Getting Started\n## Getting Started\n## `code` & more\n")
        assertTrue(h, "<a name=\"getting-started\"></a>" in h)
        assertTrue(h, "<a name=\"getting-started-1\"></a>" in h)
        assertTrue(h, "<a name=\"code--more\"></a>" in h)
    }

    fun `test task list`() {
        val h = html("- [x] done\n- [ ] todo\n")
        assertTrue(h, "&#9745;" in h && "&#9744;" in h && "<input" !in h)
    }

    fun `test fenced code is colored by its language`() {
        val h = html("```markdown\n# Title\n[link](a.md)\n```\n")
        assertTrue(h, Regex("""<pre><code>.*<span style="[^"]*color: #[0-9a-fA-F]{6}""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(h))
        assertTrue(h, "# Title" in h.replace(Regex("<[^>]+>"), ""))
    }

    fun `test front matter is a block`() {
        val h = html("---\ntitle: Tour\n---\n# Tour\n")
        assertTrue(h, Regex("""<pre><code>.*title: Tour.*</code></pre>""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(h.replace(Regex("<span[^>]*>|</span>"), "")))
        assertTrue(h, "<hr" !in h)
    }

    fun `test strikethrough`() {
        val h = html("~~gone~~ *i* **b**")
        assertTrue(h, "<strike>gone</strike>" in h && "<em>i</em>" in h && "<strong>b</strong>" in h)
    }

    fun `test unknown fence is escaped text`() {
        val h = html("```nope\n<b>x</b> & y\n```\n")
        assertTrue(h, "&lt;b&gt;x&lt;/b&gt; &amp; y" in h)
    }

    fun `test relative image becomes a file url and remote image a link`() {
        val h = html("![logo](img/logo.png)\n![badge](https://img.shields.io/x.svg)\n![diagram](arch.svg)\n")
        assertTrue(h, Regex("""<img [^>]*src="file:[^"]*/docs/img/logo.png"""").containsMatchIn(h))
        assertTrue(h, "<a href=\"https://img.shields.io/x.svg\">[image: badge]</a>" in h)
        assertTrue(h, "[image: diagram]</a>" in h && "arch.svg\"" in h)
    }

    fun `test relative link becomes a file url`() {
        val h = html("[guide](guide.md#install)\n[web](https://example.org)\n[top](#title)\n")
        assertTrue(h, Regex("""href="file:[^"]*/docs/guide.md#install"""").containsMatchIn(h))
        assertTrue(h, "href=\"https://example.org\"" in h && "href=\"#title\"" in h)
    }

    fun `test swing displays the page`() {
        val pane = JEditorPane().apply { editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build() }
        pane.text = html("# T\n\n| a |\n|---|\n| 1 |\n\n```markdown\n# x\n```\n\n> quote\n\n- [x] ok\n")
        val text = pane.document.getText(0, pane.document.length)
        assertTrue(text, "T" in text && "quote" in text && "ok" in text)
    }

    fun `test markdown files open with a preview`() {
        val file = myFixture.addFileToProject("a.md", "# Hello\n").virtualFile
        val provider = MdSplitEditorProvider()
        assertTrue(provider.accept(project, file))
        val editor = provider.createEditor(project, file)
        try {
            assertTrue(editor is TextEditorWithPreview)
            val preview = (editor as TextEditorWithPreview).previewEditor as MdPreviewEditor
            PlatformTestUtil.waitWithEventsDispatching("preview not rendered", { "Hello" in preview.shownText }, 10)
        } finally {
            Disposer.dispose(editor)
        }
    }
}
