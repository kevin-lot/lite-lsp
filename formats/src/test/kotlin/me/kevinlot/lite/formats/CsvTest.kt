package me.kevinlot.lite.formats

import me.kevinlot.lite.formats.data.CsvPreviewEditor
import me.kevinlot.lite.formats.data.CsvSplitEditorProvider
import me.kevinlot.lite.formats.data.CsvTable

class CsvTest : FmtTestCase() {

    fun `test quote after leading spaces opens a quoted field`() = assertEquals(
        listOf(
            Cat.COL0 to "a", Cat.PUNCT to ",", Cat.COL1 to " \"b,c\" ", Cat.PUNCT to ",", Cat.COL2 to " d",
            Cat.COL0 to "e", Cat.PUNCT to ",", Cat.COL1 to "f \"g",
        ),
        tokens("a.csv", "a, \"b,c\" , d\ne,f \"g\n"),
    )

    fun `test rows of a tsv file`() = assertEquals(
        listOf(listOf("id", "name", "note"), listOf("1", "Ada", ""), listOf("2", "", "x y")),
        CsvTable.rows("id\tname\tnote\n1\tAda\t\n2\t\tx y\n"),
    )

    fun `test quoted cells lose their quotes`() = assertEquals(
        listOf(listOf("a", "b,c", "say \"hi\""), listOf("two\nlines", "d")),
        CsvTable.rows("a,\"b,c\",\"say \"\"hi\"\"\"\n\"two\nlines\",d"),
    )

    fun `test blank lines are skipped and the delimiter is guessed`() = assertEquals(
        listOf(listOf("a", "b"), listOf("1", "2")),
        CsvTable.rows("a;b\n\n1;2\n\n"),
    )

    fun `test a tsv file opens with a table beside the editor`() {
        val file = myFixture.addFileToProject("data/users.tsv", "id\tname\n1\tAda\n2\tLin\n").virtualFile
        val provider = CsvSplitEditorProvider()
        assertTrue(provider.accept(project, file))
        val editor = provider.createEditor(project, file)
        try {
            val preview = (editor as com.intellij.openapi.fileEditor.TextEditorWithPreview).previewEditor as CsvPreviewEditor
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("table not filled", { preview.shown.size == 3 }, 10)
            assertEquals(listOf(listOf("id", "name"), listOf("1", "Ada"), listOf("2", "Lin")), preview.shown)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    fun `test other formats keep the plain editor`() =
        assertFalse(CsvSplitEditorProvider().accept(project, myFixture.addFileToProject("a.toml", "a = 1\n").virtualFile))
}
