package me.kevinlot.lite.formats

class PropertiesTest : FmtTestCase() {
    private fun kinds(text: String) = tokens("a.properties", text).map { "${it.first}:${it.second}" }

    fun `test the three separators`() = assertEquals(
        listOf("KEY:a", "OPERATOR:=", "STRING:1", "KEY:b.c", "OPERATOR::", "STRING:2", "KEY:d", "STRING:3", "KEY:e", "OPERATOR:=", "STRING:x=y:z"),
        kinds("a=1\nb.c : 2\nd 3\n  e = x=y:z\n"),
    )

    fun `test both comment markers, only at the start of a line`() = assertEquals(
        listOf("COMMENT:# one", "COMMENT:! two", "KEY:k", "OPERATOR:=", "STRING:v", "STRING:#", "STRING:not", "STRING:!one"),
        kinds("# one\n  ! two\nk=v # not !one\n"),
    )

    fun `test a continued value is not a key nor a comment`() = assertEquals(
        listOf("KEY:list", "OPERATOR:=", "STRING:a,", "CONSTANT:\\", "STRING:b=c,", "CONSTANT:\\", "STRING:#d", "KEY:next", "OPERATOR:=", "STRING:1"),
        kinds("list = a,\\\n    b=c,\\\n    #d\nnext=1\n"),
    )

    fun `test an escaped backslash does not continue the line`() = assertEquals(
        listOf("KEY:path", "OPERATOR:=", "STRING:c:", "CONSTANT:\\\\", "KEY:k", "OPERATOR:=", "STRING:v"),
        kinds("path=c:\\\\\nk=v\n"),
    )

    fun `test escapes in keys and values`() = assertEquals(
        listOf("KEY:a\\ b\\=c", "OPERATOR:=", "CONSTANT:\\u00e9", "STRING:t", "CONSTANT:\\n"),
        kinds("a\\ b\\=c=\\u00e9t\\n\n"),
    )

    fun `test placeholders`() = assertEquals(
        listOf("KEY:url", "OPERATOR:=", "STRING:http://", "VARIABLE:\${host}", "STRING::", "VARIABLE:\${port:80}", "STRING:/\$x"),
        kinds("url=http://\${host}:\${port:80}/\$x\n"),
    )

    fun `test a key alone and an empty value`() = assertEquals(listOf("KEY:alone", "KEY:empty", "OPERATOR:="), kinds("alone\nempty=\n"))

    fun `test placeholder to its key`() = check("a.properties" to "<def>app.name=x\ngreeting=Hello \${<ref>app.name}\n")

    fun `test placeholder with a default`() = check("a.properties" to "<def>port=1\nurl=h:\${<ref>port:8080}\n")

    fun `test placeholder to a key of a file beside it`() = check(
        "cfg/application.properties" to "<def>db.host=localhost\n",
        "cfg/application-dev.properties" to "db.url=jdbc://\${<ref>db.host}/x\n",
    )

    fun `test a placeholder in a comment or a key is not a reference`() {
        myFixture.configureByText("a.properties", "x=1\n# \${x}\n\${x}=2\n")
        assertEmpty(Fmt.links(myFixture.file).refs)
    }

    fun `test rename a key renames its placeholders`() {
        myFixture.configureByText("a.properties", "app.na<caret>me=x\ngreeting=Hello \${app.name} \${app.name:none}\n")
        myFixture.renameElementAtCaret("app.title")
        myFixture.checkResult("app.title=x\ngreeting=Hello \${app.title} \${app.title:none}\n")
    }

    fun `test ini files keep their own format`() =
        assertEquals(Ini, (myFixture.configureByText("a.ini", "[s]\nk=v\n") as FmtFile).format)
}
