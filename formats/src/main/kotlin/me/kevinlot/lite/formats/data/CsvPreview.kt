package me.kevinlot.lite.formats

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/* CSV / TSV preview: the file as a table beside the editor, read with the lexer that colors it (same delimiter, same quotes). */

object CsvTable {
    /** The cells of the first [limit] lines; a quoted cell loses its quotes, `""` in it is one quote. */
    fun rows(text: CharSequence, limit: Int = Int.MAX_VALUE): List<List<String>> {
        val lexer = Csv.lexer()
        lexer.start(text)
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        var cell: String? = null
        fun endRow() {
            if (cell != null || row.isNotEmpty()) rows += row + cell.orEmpty()
            row.clear()
            cell = null
        }
        while (lexer.tokenType != null && rows.size < limit) {
            val token = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            when ((lexer.tokenType as? FmtToken)?.cat) {
                Cat.PUNCT -> { row += cell.orEmpty(); cell = "" }
                null -> if (lexer.tokenType == TokenType.WHITE_SPACE) endRow()
                else -> cell = unquoted(token)
            }
            lexer.advance()
        }
        if (rows.size < limit) endRow()
        return rows
    }

    private fun unquoted(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return raw
        return trimmed.substring(1, trimmed.length - 1).replace("\"\"", "\"")
    }
}

/** The table side: read again (off the UI thread) shortly after each change; the first line names the columns. */
class CsvPreviewEditor(private val project: Project, private val file: VirtualFile, private val document: Document) :
    UserDataHolderBase(), FileEditor {

    private val table = JBTable().apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        autoCreateRowSorter = true
        setShowGrid(true)
    }
    private val scroll = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(300)
        }, this)
        schedule(0)
    }

    private fun schedule(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            val rows = ReadAction.compute<List<List<String>>, RuntimeException> { CsvTable.rows(document.immutableCharSequence, MAX_ROWS + 1) }
            ApplicationManager.getApplication().invokeLater({ show(rows) }, ModalityState.any()) { project.isDisposed }
        }, delay)
    }

    private fun show(rows: List<List<String>>) {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val header = rows.firstOrNull().orEmpty()
        val names = (0 until columns).map { header.getOrNull(it)?.takeIf(String::isNotBlank) ?: "${it + 1}" }
        val body = rows.drop(1).map { row -> Array<Any>(columns) { row.getOrNull(it).orEmpty() } }
        table.model = object : DefaultTableModel(body.toTypedArray(), names.toTypedArray()) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        fitColumns(names, body)
    }

    /** Each column as wide as its content, within limits: a long text does not push the others out of sight. */
    private fun fitColumns(names: List<String>, body: List<Array<Any>>) {
        val metrics = table.getFontMetrics(table.font)
        names.forEachIndexed { index, name ->
            val widest = (body.asSequence().take(MEASURED_ROWS).map { it[index].toString() } + name).maxOf(metrics::stringWidth)
            table.columnModel.getColumn(index).preferredWidth = (widest + JBUI.scale(18)).coerceIn(JBUI.scale(40), JBUI.scale(420))
        }
    }

    override fun getComponent(): JComponent = scroll
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName(): String = "Table"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}

    /** For tests: the column names, then the lines shown. */
    internal val shown: List<List<String>>
        get() = listOf((0 until table.columnCount).map(table::getColumnName)) +
            (0 until table.rowCount).map { row -> (0 until table.columnCount).map { table.getValueAt(row, it).toString() } }

    private companion object {
        const val MAX_ROWS = 20_000
        const val MEASURED_ROWS = 300
    }
}

/** CSV / TSV files open as editor + table (the toolbar switches to editor only / table only). */
class CsvSplitEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file.fileType == CsvFileType
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = CsvPreviewEditor(project, file, text.editor.document)
        return TextEditorWithPreview(text, preview, "CSV", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }
    override fun getEditorTypeId(): String = "lite-csv-preview"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
