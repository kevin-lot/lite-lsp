package me.kevinlot.lite.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Color
import java.awt.Font
import java.beans.PropertyChangeListener
import java.net.URI
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/*
 * Markdown preview: GitHub-flavoured Markdown (JetBrains' intellij-markdown, bundled with the IDE) rendered to HTML
 * and shown by Swing (no embedded browser), beside the editor. Code blocks are colored by the Lite lexer of their
 * fence, with the editor's color scheme.
 */

object MdHtml {
    private val FRONT_MATTER = Regex("""\A---[ \t]*\n([\s\S]*?)\n---[ \t]*(?:\n|\z)""")

    /** A full HTML page for [markdown]; relative links and images are resolved from [base]'s folder. */
    fun render(markdown: String, base: VirtualFile?, scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme): String {
        // YAML front matter (`---` … `---` on the first lines): shown as a YAML block, not as a rule and a paragraph
        val front = FRONT_MATTER.find(markdown)
        val text = if (front != null) "```yaml\n${front.groupValues[1]}\n```\n" + markdown.substring(front.range.last + 1) else markdown
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, text), folderUri(base)) +
            (MarkdownElementTypes.CODE_FENCE to FenceProvider(scheme))
        val body = HtmlGenerator(text, tree, providers, false).generateHtml()
        return "<html><head><style>${css(scheme)}</style></head>${polish(body)}</html>"
    }

    /** The document's own URI: relative references resolve against it, as a browser does. */
    private fun folderUri(file: VirtualFile?): URI? = file?.let { URI("file", null, it.path, null) }

    /** What Swing's HTML 3.2 cannot show as is: anchors on headers, task boxes, remote and SVG images, table borders. */
    private fun polish(html: String): String {
        val seen = HashMap<String, Int>()
        return html
            .replace(Regex("""<h([1-6])>(.*?)</h\1>""", RegexOption.DOT_MATCHES_ALL)) { m ->
                val title = StringUtil.unescapeXmlEntities(m.groupValues[2].replace(Regex("<[^>]+>"), ""))
                val slug = Md.slug(title).let { s -> seen.merge(s, 1, Int::plus)!!.let { n -> if (n == 1) s else "$s-${n - 1}" } }
                "<h${m.groupValues[1]}><a name=\"$slug\"></a>${m.groupValues[2]}</h${m.groupValues[1]}>"
            }
            .replace(Regex("""<input[^>]*type="checkbox"[^>]*>""")) { if ("checked" in it.value) "&#9745; " else "&#9744; " }
            .replace(Regex("""<img\s+([^>]*)/?>""")) { m ->
                val src = Regex("""src="([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
                val alt = Regex("""alt="([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
                val remote = src.startsWith("http:") || src.startsWith("https:") || src.startsWith("//")
                // not fetched (no network from the editor) nor drawable (SVG): a link to it instead
                if (remote || src.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true)) {
                    "<a href=\"$src\">[image${if (alt.isNotEmpty()) ": $alt" else ""}]</a>"
                } else m.value
            }
            .replace("<table>", "<table cellspacing=\"0\" cellpadding=\"5\">")
            .replace(Regex("""<span class="user-del">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)) { "<strike>${it.groupValues[1]}</strike>" }
    }

    private fun hex(c: Color) = "#" + ColorUtil.toHex(c)
    private fun mix(a: Color, b: Color, t: Double) = Color(
        (a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(), (a.blue + (b.blue - a.blue) * t).toInt(),
    )

    private fun css(scheme: EditorColorsScheme): String {
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val code = mix(bg, fg, 0.07)
        val border = mix(bg, fg, 0.25)
        val muted = mix(bg, fg, 0.6)
        val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val font = UIUtil.getLabelFont()
        val size = font.size + 1
        val mono = scheme.editorFontName
        return """
            body { font-family: '${font.family}'; font-size: ${size}pt; color: ${hex(fg)}; background-color: ${hex(bg)}; margin: 12px 24px; }
            p, ul, ol, table, pre, blockquote { margin-top: 6px; margin-bottom: 8px; }
            h1 { font-size: ${size + 9}pt; margin-top: 18px; margin-bottom: 8px; border-bottom: 1px solid ${hex(border)}; }
            h2 { font-size: ${size + 6}pt; margin-top: 16px; margin-bottom: 6px; border-bottom: 1px solid ${hex(border)}; }
            h3 { font-size: ${size + 3}pt; margin-top: 14px; margin-bottom: 4px; }
            h4, h5, h6 { font-size: ${size + 1}pt; margin-top: 12px; margin-bottom: 4px; }
            h1, h2, h3, h4, h5, h6, strong, b { font-weight: bold; }
            em, i { font-style: italic; }
            ul, ol { margin-left: 22px; }
            a { color: ${hex(link)}; }
            code { font-family: '$mono'; font-size: ${size - 1}pt; background-color: ${hex(code)}; }
            pre { font-family: '$mono'; font-size: ${size - 1}pt; background-color: ${hex(code)}; padding: 8px 10px; }
            blockquote { color: ${hex(muted)}; margin-left: 4px; padding-left: 12px; border-left: 3px solid ${hex(border)}; }
            th { background-color: ${hex(code)}; font-weight: bold; }
            th, td { border: 1px solid ${hex(border)}; }
            hr { color: ${hex(border)}; }
            li { margin-top: 2px; }
        """.trimIndent()
    }

    /** ```lang blocks: tokens of the Lite (or platform) lexer of that language, in the editor's colors. */
    private class FenceProvider(private val scheme: EditorColorsScheme) : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            val info = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(text)?.toString().orEmpty()
            val content = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            val code = if (content.isEmpty()) "" else text.substring(content.first().startOffset, content.last().endOffset)
            visitor.consumeHtml("<pre><code>${highlight(code, info)}</code></pre>")
        }

        private fun highlight(code: String, info: String): String {
            val language = MdCodeInjector.languageOf(info) ?: return StringUtil.escapeXmlEntities(code)
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, null, null) ?: return StringUtil.escapeXmlEntities(code)
            val lexer = highlighter.highlightingLexer
            lexer.start(code)
            val out = StringBuilder()
            while (lexer.tokenType != null) {
                val piece = StringUtil.escapeXmlEntities(code.substring(lexer.tokenStart, lexer.tokenEnd))
                val attributes = highlighter.getTokenHighlights(lexer.tokenType!!).mapNotNull { scheme.getAttributes(it) }
                val color = attributes.lastOrNull { it.foregroundColor != null }?.foregroundColor
                val style = attributes.fold(0) { acc, a: TextAttributes -> acc or a.fontType }
                if (color == null && style == Font.PLAIN) out.append(piece) else {
                    out.append("<span style=\"")
                    color?.let { out.append("color: ${hex(it)};") }
                    if (style and Font.BOLD != 0) out.append(" font-weight: bold;")
                    if (style and Font.ITALIC != 0) out.append(" font-style: italic;")
                    out.append("\">").append(piece).append("</span>")
                }
                lexer.advance()
            }
            return out.toString()
        }
    }
}

/** The rendered side: re-rendered (off the UI thread) shortly after each change, keeping the scroll position. */
class MdPreviewEditor(private val project: Project, private val file: VirtualFile, private val document: Document) :
    UserDataHolderBase(), FileEditor {

    private val pane = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) open(e.description ?: return@addHyperlinkListener) }
    }
    private val scroll = JBScrollPane(pane).apply { border = JBUI.Borders.empty() }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(300)
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(EditorColorsManager.TOPIC, EditorColorsListener { schedule(0) })
        schedule(0)
    }

    private fun schedule(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            val html = ReadAction.compute<String, RuntimeException> { MdHtml.render(document.text, file) }
            ApplicationManager.getApplication().invokeLater({ show(html) }, ModalityState.any()) { project.isDisposed }
        }, delay)
    }

    private fun show(html: String) {
        val position = scroll.viewport.viewPosition
        pane.text = html
        SwingUtilities.invokeLater { scroll.viewport.viewPosition = position }
    }

    /** `#anchor` in place, web links in the browser, project files in the editor. */
    private fun open(href: String) {
        if (href.startsWith("#")) { pane.scrollToReference(Md.decode(href.substring(1))); return }
        val uri = runCatching { URI(href) }.getOrNull()
        if (uri == null || uri.scheme != "file") { BrowserUtil.browse(href); return }
        val target = LocalFileSystem.getInstance().findFileByPath(uri.path) ?: file.fileSystem.findFileByPath(uri.path) ?: return
        if (target == file && uri.fragment != null) { pane.scrollToReference(uri.fragment); return }
        OpenFileDescriptor(project, target).navigate(true)
    }

    override fun getComponent(): JComponent = scroll
    override fun getPreferredFocusedComponent(): JComponent = pane
    override fun getName(): String = "Preview"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}

    /** For tests: the HTML currently shown. */
    internal val shownText: String get() = pane.text
}

/** Markdown files open as editor + preview (the toolbar switches to editor only / preview only). */
class MdSplitEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file.fileType == MdFileType
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = MdPreviewEditor(project, file, text.editor.document)
        return TextEditorWithPreview(text, preview, "Markdown", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }
    override fun getEditorTypeId(): String = "lite-markdown-preview"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

