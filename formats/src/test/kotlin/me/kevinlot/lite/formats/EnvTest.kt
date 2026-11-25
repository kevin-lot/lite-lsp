package me.kevinlot.lite.formats

class EnvTest : FmtTestCase() {

    fun `test trailing comments`() = tokens(".env", "KEY=value # trailing\nQ=\"v\" # c\nS='v'#d\nH=a#b\n").let { found ->
        listOf("# trailing", "# c", "#d").forEach { assertToken(found, Cat.COMMENT, it) }
        assertEquals(found.toString(), 3, found.count { it.first == Cat.COMMENT })
    }
    fun `test a hash inside quotes is not a comment`() = tokens(".env", "A=\"x # y\"\nB='x # y'\n").let { found ->
        assertToken(found, Cat.STRING, "\"x # y\""); assertToken(found, Cat.STRING, "'x # y'")
        assertEquals(found.toString(), 0, found.count { it.first == Cat.COMMENT })
    }
    fun `test a name inside a value is not a declaration`() = tokens(".env", "DATABASE_URL=postgres://u@h/db?sslmode=require\n  export TOKEN=a=b\nSPACED = 1\n").let { found ->
        assertEquals(found.toString(), listOf("DATABASE_URL", "TOKEN", "SPACED"), found.filter { it.first == Cat.DECL }.map { it.second })
        assertToken(found, Cat.KEYWORD, "export")
    }
    fun `test key with a dash`() {
        assertToken(tokens(".env", "MY-KEY=1\n"), Cat.DECL, "MY-KEY")
        assertEquals(listOf("var:MY-KEY"), Fmt.links(myFixture.file).decls.map { it.key })
    }
}
