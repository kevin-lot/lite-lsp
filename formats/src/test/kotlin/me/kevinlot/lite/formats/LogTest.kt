package me.kevinlot.lite.formats

import com.intellij.psi.PsiFile
import me.kevinlot.lite.formats.data.Log
import me.kevinlot.lite.formats.data.LogTarget

class LogTest : FmtTestCase() {
    private fun levels(text: String): List<String> = tokens("app.log", text).map { it.first?.name?.removePrefix("LOG_").orEmpty() }

    fun `test level words`() = assertEquals(
        listOf("ERROR", "WARNING", "INFO", "DEBUG", "ERROR", "LINE"),
        levels("2024-01-01 10:00:00 ERROR boom\n10:00 WARN slow\n[main] INFO ok\nTRACE x\nnpm ERR! code 1\nplain text\n"),
    )

    fun `test the first level of a line wins`() = assertEquals(listOf("INFO", "LINE"), levels("INFO no ERROR here\nan error in lower case is no level\n"))

    fun `test keyed and bracketed levels`() = assertEquals(
        listOf("ERROR", "WARNING", "INFO", "DEBUG", "WARNING", "ERROR"),
        levels("{\"level\":\"error\",\"msg\":\"x\"}\nts=1 level=warn msg=y\n{\"severity\": \"INFO\"}\nlvl=debug\n[warning] z\nerror[E0308]: mismatched types\n"),
    )

    fun `test a stack trace keeps the level of its event`() = assertEquals(
        listOf("ERROR", "ERROR", "ERROR", "ERROR", "INFO", "LINE"),
        levels("ERROR failed\njava.lang.IllegalStateException: no\n\tat com.foo.Bar.run(Bar.kt:3)\nCaused by: java.io.IOException\nINFO next\nplain\n"),
    )

    fun `test an error without a level word`() = assertEquals(
        listOf("ERROR", "ERROR", "ERROR", "LINE", "ERROR", "ERROR"),
        levels("Traceback (most recent call last):\n  File \"app.py\", line 3, in <module>\nValueError: bad\nafter\nthread 'main' panicked at src/main.rs:2:5:\n  boom\n"),
    )

    fun `test lexing restarts inside a stack trace`() {
        val text = "WARN slow\n  detail one\n\n  detail two\nplain\n  under plain\n"
        val lexer = Log.lexer()
        fun at(marker: String): String? {
            lexer.start(text, text.indexOf(marker), text.length, 0)
            return (lexer.tokenType as? FmtToken)?.cat?.name
        }
        assertEquals(listOf("LOG_WARNING", "LOG_WARNING", "LOG_LINE", "LOG_LINE"), listOf("detail one", "detail two", "plain", "under plain").map(::at))
    }

    private val source = "package com.foo\n\nclass Bar {\n    fun run() {\n        error(\"x\")\n    }\n}\n"

    /** The files and lines the reference at `<ref>` of [log] leads to. */
    private fun targets(log: String, vararg files: Pair<String, String>): List<String> {
        files.forEach { myFixture.addFileToProject(it.first, it.second) }
        val file = myFixture.addFileToProject("logs/app.log", log.replace("<ref>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val at = log.indexOf("<ref>")
        val word = generateSequence(myFixture.file.findElementAt(at)) { it.parent }.takeWhile { it !is PsiFile }.filterIsInstance<FmtWord>().first()
        val refs = word.references.filter { it.rangeInElement.shiftRight(word.textRange.startOffset).containsOffset(at) }
        return refs.flatMap { (it as FmtReference).multiResolve(false).mapNotNull { r -> r.element as? LogTarget } }
            .map { "${it.containingFile.virtualFile.path.removePrefix("/src/")}:${it.line}:${it.column}" }
    }

    fun `test frame of the jvm`() = assertEquals(
        listOf("src/main/kotlin/com/foo/Bar.kt:5:1"),
        targets("ERROR x\n\tat com.foo.Bar.run(<ref>Bar.kt:5)\n", "src/main/kotlin/com/foo/Bar.kt" to source, "other/com/baz/Bar.kt" to source),
    )

    fun `test frame of an inner class and a module`() = assertEquals(
        listOf("src/main/kotlin/com/foo/Bar.kt:4:1"),
        targets("\tat app//com.foo.Bar\$run\$1.invoke(<ref>Bar.kt:4)\n", "src/main/kotlin/com/foo/Bar.kt" to source, "other/com/baz/Bar.kt" to source),
    )

    fun `test frame of an unknown package lists every file of that name`() =
        assertEquals(2, targets("\tat org.x.Bar.run(<ref>Bar.kt:3)\n", "a/Bar.kt" to source, "b/Bar.kt" to source).size)

    fun `test relative path with a line and a column`() =
        assertEquals(listOf("src/main.rs:2:12"), targets("thread 'main' panicked at <ref>src/main.rs:2:12:\n", "src/main.rs" to "fn main() {\n    panic!(\"boom\");\n}\n"))

    fun `test python frame`() = assertEquals(
        listOf("svc/app.py:2:1"), targets("  File \"<ref>svc/app.py\", line 2, in run\n", "svc/app.py" to "def run():\n    raise ValueError()\n"),
    )

    fun `test absolute path of another machine`() = assertEquals(
        listOf("web/src/app/list.ts:2:3"),
        targets("    at load (<ref>/app/web/src/app/list.ts:2:3)\n", "web/src/app/list.ts" to "function load() {\n  return 1\n}\n", "other/list.ts" to "x\n"),
    )

    fun `test compiler position in parentheses`() =
        assertEquals(listOf("src/a.ts:2:1"), targets("<ref>src/a.ts(2,1): error TS2322: no\n", "src/a.ts" to "let a\nconst b = 1\n"))

    fun `test a line past the end is kept as written`() = assertEquals(listOf("src/a.ts:99:1"), targets("<ref>src/a.ts:99\n", "src/a.ts" to "let a\n"))

    fun `test a host and a port name no file`() = assertEmpty(targets("INFO listening on <ref>example.com:8080\n"))

    fun `test a time is no path`() {
        val links = Fmt.links(myFixture.configureByText("app.log", "2024-01-01T10:00:00.123Z INFO v1.2.3 at 10.0.0.1:8080\n"))
        assertEmpty(links.refs)
    }
}
