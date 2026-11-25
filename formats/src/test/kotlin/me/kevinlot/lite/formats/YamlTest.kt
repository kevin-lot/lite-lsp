package me.kevinlot.lite.formats

class YamlTest : FmtTestCase() {

    private fun yaml(text: String) = tokens("a.yml", text)
    private fun List<Pair<Cat?, String>>.of(cat: Cat) = filter { it.first == cat }.map { it.second }

    /** Offsets of the anchor declarations and of the alias references the linker finds in [text]. */
    private fun anchors(text: String): Pair<List<Int>, List<Int>> {
        val links = Fmt.links(myFixture.configureByText("a.yml", text))
        val aliases = links.refs.filter { ref -> ref.keys.any { it.startsWith("anchor:") } }
        return links.decls.filter { it.key.startsWith("anchor:") }.map { it.range.startOffset } to aliases.map { it.range.startOffset }
    }

    // ---------------------------------------------------------------- block scalars

    private val script = "steps:\n  - run: |\n      key: true # c\n      cmd 2>&1\n      rm *.log\n  - name: next\n"

    fun `test block scalar body is plain text`() = assertEquals(
        listOf(
            Cat.KEY to "steps", Cat.OPERATOR to ":", Cat.OPERATOR to "-", Cat.KEY to "run", Cat.OPERATOR to ":", Cat.OPERATOR to "|",
            Cat.TEXT to "key: true # c\n      cmd 2>&1\n      rm *.log",
            Cat.OPERATOR to "-", Cat.KEY to "name", Cat.OPERATOR to ":", Cat.WORD to "next",
        ),
        yaml(script),
    )

    fun `test block scalar indicators`() = yaml("a: |-\n  x: 1\nb: >+\n  y: 2\nc: |2\n    z: 3\nd: >2- # c\n  w: 4\ne: 1\n").let {
        assertEquals(listOf("a", "b", "c", "d", "e"), it.of(Cat.KEY))
        assertEquals(listOf("x: 1", "y: 2", "z: 3", "w: 4"), it.of(Cat.TEXT))
        assertEquals(listOf("|-", ">+", "|2", ">2-"), it.of(Cat.OPERATOR).filter { op -> op != ":" })
        assertToken(it, Cat.COMMENT, "# c")
    }

    fun `test block scalar ends at the first line that is not deeper than its key`() =
        yaml("a:\n  b: >\n    one\n\n      two: 2\n    # three\n  c: 3\n- |\n  item\n- last\n").let {
            assertEquals(listOf("one\n\n      two: 2\n    # three", "item"), it.of(Cat.TEXT))
            assertEquals(listOf("a", "b", "c"), it.of(Cat.KEY))
            assertToken(it, Cat.WORD, "last")
        }

    fun `test block scalar of a sequence item key starts deeper than the key`() =
        yaml("- run: |\n  name: x\n").let { assertEquals(listOf("run", "name"), it.of(Cat.KEY)) }

    fun `test lexer restarts at a block scalar body and after it`() {
        val lexer = Yaml.lexer()
        lexer.start(script, script.indexOf("key: true"), script.length, 0)
        assertEquals(Cat.TEXT, (lexer.tokenType as FmtToken).cat)
        assertEquals(script.indexOf("\n  - name"), lexer.tokenEnd)
        lexer.start(script, script.indexOf("name"), script.length, 0)
        assertEquals(Cat.KEY, (lexer.tokenType as FmtToken).cat)
    }

    fun `test no anchor nor alias in a block scalar, a comment or a string`() {
        val text = "base: &real 1\nrun: |\n  cmd 2>&1 &bg\n  rm *.log *real\n# &c *real\ns: \"&d *real\"\nuse: *real\n"
        assertEquals(listOf(text.indexOf("real")) to listOf(text.lastIndexOf("real")), anchors(text))
    }

    // ---------------------------------------------------------------- plain scalars

    fun `test apostrophe in a plain scalar opens no string`() = yaml("description: Don't do this\nother: 'yes'\nlist: ['a', \"b\"]\n- 'item'\n").let {
        assertToken(it, Cat.WORD, "Don't do this")
        assertEquals(listOf("'yes'", "'a'", "\"b\"", "'item'"), it.of(Cat.STRING))
    }

    fun `test quotes of a github expression open no string`() =
        assertEquals(emptyList<String>(), yaml("if: \${{ github.ref == 'refs/heads/main' }}\n").of(Cat.STRING))

    fun `test json style flow mapping`() = yaml("{\"a\":\"b\", \"c\": 'd'}\n").let {
        assertEquals(listOf("\"a\"", "\"b\"", "'d'"), it.of(Cat.STRING))
        assertEquals(listOf("\"c\""), it.of(Cat.KEY))
    }

    fun `test anchors and aliases only at the start of a node`() {
        val text = "url: http://x.io/p?a=1&b=2\nglob: src/*.ts\nrun: make && rm *.o\nbase: &b\n  k: 1\nuse: *b\nlist: [*b, &c x]\ntagged: !!str &d \"v\"\n"
        yaml(text).let {
            assertEquals(listOf("&b", "&c", "&d"), it.of(Cat.DECL))
            assertEquals(listOf("*b", "*b"), it.of(Cat.VARIABLE))
            assertToken(it, Cat.WORD, "http://x.io/p?a=1&b=2"); assertToken(it, Cat.WORD, "src/*.ts"); assertToken(it, Cat.WORD, "make && rm *.o")
            assertToken(it, Cat.STRING, "\"v\"")
        }
        assertEquals(listOf("&b\n", "&c", "&d").map { text.indexOf(it) + 1 } to listOf("*b\n", "*b,").map { text.indexOf(it) + 1 }, anchors(text))
    }

    fun `test alias after a tag and in a flow sequence`() {
        check("a.yml" to "base: !!map &<def>d\n  a: 1\nuse: *<ref>d\n")
        check("b.yml" to "a: &<def>x 1\nb: [1, *<ref>x]\n")
    }

    fun `test number and constant only as a whole scalar`() =
        yaml("version: 1.2.3\ndate: 2024-01-15\nmsg: it is true or null\nn: 42 # c\nf: -1.5e3\nb: true\nl: [1, false, 2x]\nm: {k: null}\nz: ~\nt: 12:30\n").let {
            assertEquals(listOf("42", "-1.5e3", "1"), it.of(Cat.NUMBER))
            assertEquals(listOf("true", "false", "null", "~"), it.of(Cat.CONSTANT))
            assertEquals(listOf("1.2.3", "2024-01-15", "it is true or null", "2x", "12:30"), it.of(Cat.WORD))
        }

    fun `test plain scalar stops before a comment and a mapping colon`() = yaml("a: b c # d\ne: f#g:h\n").let {
        assertEquals(listOf("b c", "f#g:h"), it.of(Cat.WORD)); assertEquals(listOf("a", "e"), it.of(Cat.KEY)); assertToken(it, Cat.COMMENT, "# d")
    }

    fun `test a plain scalar of very many words does not overflow the regex stack`() {
        val long = "word ".repeat(20_000) + "end"
        assertToken(yaml("k: $long\n"), Cat.WORD, long)
    }

    // ---------------------------------------------------------------- quoted scalars

    fun `test quoted scalar over several lines`() = yaml("a: \"first \\\" one\n  second: line\"\nb: 'one\n  two: ''x'' three'\nc: 1\n").let {
        assertEquals(listOf("\"first \\\" one\n  second: line\"", "'one\n  two: ''x'' three'"), it.of(Cat.STRING))
        assertEquals(listOf("a", "b", "c"), it.of(Cat.KEY))
    }
}
