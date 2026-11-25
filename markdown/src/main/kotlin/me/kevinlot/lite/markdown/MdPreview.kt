package me.kevinlot.lite.markdown

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.io.DigestUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import kotlin.math.pow
import kotlin.math.roundToInt
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/*
 * Markdown preview: GitHub-flavoured Markdown (JetBrains' intellij-markdown, bundled with the IDE) rendered to HTML
 * and shown by Swing (no embedded browser), beside the editor. Code blocks are colored by the Lite lexer of their
 * fence, with the editor's color scheme.
 */

object MdHtml {
    private val CHECKBOX = Regex("""<input[^>]*type="checkbox"[^>]*>""")
    private val FRONT_MATTER = Regex("""\A---[ \t]*\n([\s\S]*?)\n---[ \t]*(?:\n|\z)""")

    private val HEADING = Regex("""<h([1-6])>(.*?)</h\1>""", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("""<img\s+([^>]*)/?>""")
    private val IMAGE_ALT = Regex("""alt="([^"]*)"""")
    private val IMAGE_SRC = Regex("""src="([^"]*)"""")
    private val STRUCK = Regex("""<span class="user-del">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")
    /** Diagrams are shown larger than drawn: their image holds twice the pixels, and a wide one scrolls. */
    private const val DIAGRAM_ZOOM = 1.4
    private val DIAGRAM_SIZES = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** A full HTML page for [markdown]; relative links and images are resolved from [base]'s folder. [zoom] scales text and diagrams. */
    fun render(
        markdown: String, base: VirtualFile?, scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme, zoom: Double = 1.0,
    ): String {
        // YAML front matter (`---` … `---` on the first lines): shown as a YAML block, not as a rule and a paragraph
        val front = FRONT_MATTER.find(markdown)
        val text = front?.let { "```yaml\n${it.groupValues[1]}\n```\n" + markdown.substring(it.range.last + 1) } ?: markdown
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, text), folderUri(base)) +
            (MarkdownElementTypes.CODE_FENCE to FenceProvider(scheme, zoom))
        val body = HtmlGenerator(text, tree, providers, false).generateHtml()
        return "<html><head><style>${css(scheme, zoom)}</style></head>${polish(body)}</html>"
    }

    /** The document's own URI: relative references resolve against it, as a browser does. */
    private fun folderUri(file: VirtualFile?): URI? = file?.let { URI("file", null, it.path, null) }

    /** What Swing's HTML 3.2 cannot show as is: anchors on headers, task boxes, remote and SVG images, table borders. */
    private fun polish(html: String): String {
        val seen = HashMap<String, Int>()
        return html
            .replace(HEADING) { m ->
                val slug = Md.slug(StringUtil.unescapeXmlEntities(m.groupValues[2].replace(TAG, "")))
                val before = seen.getOrDefault(slug, 0)
                seen[slug] = before + 1
                val anchor = Md.numbered(slug, before)
                "<h${m.groupValues[1]}><a name=\"$anchor\"></a>${m.groupValues[2]}</h${m.groupValues[1]}>"
            }
            .replace(CHECKBOX) { when { "checked" in it.value -> "&#9745; "; else -> "&#9744; " } }
            .replace(IMAGE) { m ->
                val src = IMAGE_SRC.find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
                val alt = IMAGE_ALT.find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
                val remote = src.startsWith("http:") || src.startsWith("https:") || src.startsWith("//")
                val svg = src.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true)
                if (!remote && !svg) return@replace m.value
                val altSuffix = when { alt.isNotEmpty() -> ": $alt"; else -> "" }
                // not fetched (no network from the editor) nor drawable (SVG): a link to it instead
                "<a href=\"$src\">[image$altSuffix]</a>"
            }
            .replace("<table>", "<table cellspacing=\"0\" cellpadding=\"5\">")
            .replace(STRUCK) { "<strike>${it.groupValues[1]}</strike>" }
    }

    private fun hex(c: Color) = "#" + ColorUtil.toHex(c)
    private fun mix(a: Color, b: Color, t: Double) = Color(
        (a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(), (a.blue + (b.blue - a.blue) * t).toInt(),
    )

    private fun css(scheme: EditorColorsScheme, zoom: Double): String {
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val code = mix(bg, fg, 0.07)
        val border = mix(bg, fg, 0.25)
        val muted = mix(bg, fg, 0.6)
        val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val font = UIUtil.getLabelFont()
        val size = font.size + 1
        fun pt(points: Int) = (points * zoom).roundToInt()
        val mono = scheme.editorFontName
        return """
            body { font-family: '${font.family}'; font-size: ${pt(size)}pt; color: ${hex(fg)}; background-color: ${hex(bg)}; margin: 12px 24px; }
            p, ul, ol, table, pre, blockquote { margin-top: 6px; margin-bottom: 8px; }
            h1 { font-size: ${pt(size + 9)}pt; margin-top: 18px; margin-bottom: 8px; border-bottom: 1px solid ${hex(border)}; }
            h2 { font-size: ${pt(size + 6)}pt; margin-top: 16px; margin-bottom: 6px; border-bottom: 1px solid ${hex(border)}; }
            h3 { font-size: ${pt(size + 3)}pt; margin-top: 14px; margin-bottom: 4px; }
            h4, h5, h6 { font-size: ${pt(size + 1)}pt; margin-top: 12px; margin-bottom: 4px; }
            h1, h2, h3, h4, h5, h6, strong, b { font-weight: bold; }
            em, i { font-style: italic; }
            ul, ol { margin-left: 22px; }
            a { color: ${hex(link)}; }
            code { font-family: '$mono'; font-size: ${pt(size - 1)}pt; background-color: ${hex(code)}; }
            pre { font-family: '$mono'; font-size: ${pt(size - 1)}pt; background-color: ${hex(code)}; padding: 8px 10px; }
            blockquote { color: ${hex(muted)}; margin-left: 4px; padding-left: 12px; border-left: 3px solid ${hex(border)}; }
            th { background-color: ${hex(code)}; font-weight: bold; }
            th, td { border: 1px solid ${hex(border)}; }
            hr { color: ${hex(border)}; }
            li { margin-top: 2px; }
        """.trimIndent()
    }

    /** ```lang blocks: tokens of the Lite (or platform) lexer of that language, in the editor's colors. */
    private class FenceProvider(private val scheme: EditorColorsScheme, private val zoom: Double) : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            val info = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(text)?.toString().orEmpty()
            val content = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            val code = when {
                content.isEmpty() -> ""
                else -> text.substring(content.first().startOffset, content.last().endOffset)
            }
            val drawn = code.takeIf { info.trim().substringBefore(' ').equals("mermaid", ignoreCase = true) }?.let(::diagram)
            visitor.consumeHtml(drawn ?: "<pre><code>${highlight(code, info)}</code></pre>")
        }

        /** A Mermaid block as an image drawn by [MdDiagram]: Swing's HTML shows a picture, not a script's output. */
        private fun diagram(code: String): String? {
            val parsed = MdMermaid.parse(code) ?: return null
            val font = UIUtil.getLabelFont()
            val style = DiagramStyle(font, scheme.defaultForeground, scheme.defaultBackground)
            val key = DigestUtil.sha1Hex("$code|${font.family}|${font.size}|${style.foreground.rgb}|${style.background.rgb}")
            val file = File(File(FileUtil.getTempDirectory(), "lite-mermaid"), "$key.png")
            val size = DIAGRAM_SIZES[key]?.takeIf { file.exists() } ?: run {
                val drawn = MdDiagram.paint(parsed, style) ?: return null
                file.parentFile.mkdirs()
                ImageIO.write(drawn.image, "png", file)
                (drawn.width to drawn.height).also { DIAGRAM_SIZES[key] = it }
            }
            val shown = (size.first * DIAGRAM_ZOOM * zoom).roundToInt()
            return "<p><img src=\"${file.toURI()}\" width=\"$shown\" height=\"${size.second * shown / size.first}\"></p>"
        }

        private fun highlight(code: String, info: String): String {
            val language = MdCodeInjector.languageOf(info) ?: return StringUtil.escapeXmlEntities(code)
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, null, null) ?: return StringUtil.escapeXmlEntities(code)
            val lexer = highlighter.highlightingLexer
            lexer.start(code)
            val out = StringBuilder()
            while (true) {
                val type = lexer.tokenType ?: break
                val piece = StringUtil.escapeXmlEntities(code.substring(lexer.tokenStart, lexer.tokenEnd))
                val attributes = highlighter.getTokenHighlights(type).mapNotNull { scheme.getAttributes(it) }
                appendStyled(out, piece, attributes)
                lexer.advance()
            }
            return out.toString()
        }

        private fun appendStyled(out: StringBuilder, piece: String, attributes: List<TextAttributes>) {
            val color = attributes.lastOrNull { it.foregroundColor != null }?.foregroundColor
            val style = attributes.fold(0) { acc, a: TextAttributes -> acc or a.fontType }
            if (color == null && style == Font.PLAIN) { out.append(piece); return }
            out.append("<span style=\"")
            color?.let { out.append("color: ${hex(it)};") }
            if (style and Font.BOLD != 0) out.append(" font-weight: bold;")
            if (style and Font.ITALIC != 0) out.append(" font-style: italic;")
            out.append("\">").append(piece).append("</span>")
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
    private var zoom = PropertiesComponent.getInstance().getFloat(ZOOM_KEY, 1f).toDouble()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(300)
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(EditorColorsManager.TOPIC, EditorColorsListener { schedule(0) })
        // a wheel listener on the pane takes the events the scroll pane would get: hand the plain ones back
        pane.addMouseWheelListener { e ->
            if (!e.isControlDown && !e.isMetaDown) return@addMouseWheelListener scroll.dispatchEvent(SwingUtilities.convertMouseEvent(pane, e, scroll))
            zoomTo(zoom * ZOOM_STEP.pow(-e.preciseWheelRotation))
        }
        val menu = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        listOf(KeyEvent.VK_ADD, KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS).forEach { key(it, menu) { zoomTo(zoom * ZOOM_STEP) } }
        listOf(KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT).forEach { key(it, menu) { zoomTo(zoom / ZOOM_STEP) } }
        listOf(KeyEvent.VK_0, KeyEvent.VK_NUMPAD0).forEach { key(it, menu) { zoomTo(1.0) } }
        schedule(0)
    }

    private val panel = JPanel(BorderLayout()).apply {
        val actions = DefaultActionGroup(
            zoomAction("Zoom Out", AllIcons.Graph.ZoomOut) { zoomTo(zoom / ZOOM_STEP) },
            zoomAction("Reset Zoom", AllIcons.Graph.ActualZoom) { zoomTo(1.0) },
            zoomAction("Zoom In", AllIcons.Graph.ZoomIn) { zoomTo(zoom * ZOOM_STEP) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("LiteMarkdownPreview", actions, true)
        toolbar.targetComponent = pane
        add(JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.EAST) }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    private fun zoomAction(text: String, icon: Icon, action: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = action()
    }

    private fun key(code: Int, modifiers: Int, action: () -> Unit) =
        pane.registerKeyboardAction({ action() }, KeyStroke.getKeyStroke(code, modifiers), JComponent.WHEN_IN_FOCUSED_WINDOW)

    /** The zoom is kept for every preview and the next sessions. */
    internal fun zoomTo(value: Double) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        PropertiesComponent.getInstance().setValue(ZOOM_KEY, zoom.toFloat(), 1f)
        schedule(0)
    }

    private fun schedule(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            val html = ReadAction.compute<String, RuntimeException> { MdHtml.render(document.text, file, zoom = zoom) }
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

    override fun getComponent(): JComponent = panel
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

    private companion object {
        const val MAX_ZOOM = 4.0
        const val MIN_ZOOM = 0.5
        const val ZOOM_KEY = "lite.markdown.preview.zoom"
        const val ZOOM_STEP = 1.1
    }
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

