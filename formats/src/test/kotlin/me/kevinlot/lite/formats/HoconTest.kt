package me.kevinlot.lite.formats

class HoconTest : FmtTestCase() {

    fun `test classpath include`() = check("src/main/resources/defaults.conf" to "a = 1", "conf/application.conf" to "include classpath(\"<ref>defaults.conf\")\n")
    fun `test required classpath include without extension`() = check("lib/reference.conf" to "a = 1", "application.conf" to "include required(classpath(\"<ref>reference\"))\n")
    fun `test classpath include with folders`() = check(
        "src/main/resources/conf/db.conf" to "a = 1",
        "other/db.conf" to "a = 2",
        "app/application.conf" to "include classpath(\"<ref>conf/db.conf\")\n",
    )
    fun `test classpath include next to the file`() = check("app/db.conf" to "a = 1", "a/db.conf" to "a = 2", "app/application.conf" to "include classpath(\"<ref>db.conf\")\n")
    fun `test key of a classpath include wins over the project`() = check(
        "a/other.conf" to "port = 1",
        "src/main/resources/defaults.conf" to "<def>port = 2",
        "conf/application.conf" to "include classpath(\"defaults.conf\")\nurl = \${<ref>port}\n",
    )

    fun `test quoted segment after a plain one`() = check("application.conf" to "foo.\"<def>bar.baz\" = 1\nx = \${foo.\"<ref>bar.baz\"}\n")
    fun `test plain segment before a quoted one`() = check("application.conf" to "<def>foo.\"bar.baz\" = 1\nx = \${<ref>foo.\"bar.baz\"}\n")
    fun `test key of a block named by a mixed path`() = check("application.conf" to "foo.\"a.b\" {\n  <def>c = 1\n}\nx = \${foo.\"a.b\".<ref>c}\n")
    fun `test quoted segment is not a top-level key`() {
        myFixture.configureByText("application.conf", "foo.\"bar.baz\" = 1\n")
        assertEquals(listOf("key:foo", "key:foo.bar.baz"), Fmt.links(myFixture.file).decls.map { it.key })
    }
    fun `test each segment of a mixed path is a key`() = tokens("application.conf", "foo.\"bar.baz\".qux = 1\n").let {
        assertToken(it, Cat.KEY, "foo"); assertToken(it, Cat.KEY, "\"bar.baz\""); assertToken(it, Cat.KEY, "qux")
    }
}
