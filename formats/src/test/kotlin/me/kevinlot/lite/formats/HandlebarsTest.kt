package me.kevinlot.lite.formats

import me.kevinlot.lite.formats.template.Handlebars

class HandlebarsTest : FmtTestCase() {

    private fun referenceOffsets(name: String, text: String): List<Int> = Fmt.links(myFixture.configureByText(name, text)).refs.map { it.range.startOffset }

    fun `test escaped mustache is text`() = tokens("a.hbs", "<p>\\{{literal}} \\{{{html}}} {{name}}</p>").let {
        assertToken(it, Cat.VARIABLE, "name")
        assertFalse(it.toString(), it.any { (cat, text) -> cat == Cat.VARIABLE && text != "name" })
        assertEquals(1, it.count { (cat, _) -> cat == Cat.LBRACE })
    }
    fun `test mustache after an escaped backslash is a tag`() = tokens("a.hbs", "\\\\{{name}}").let {
        assertToken(it, Cat.LBRACE, "{{"); assertToken(it, Cat.VARIABLE, "name")
    }
    fun `test raw block is text`() = tokens("a.hbs", "{{{{raw}}}} {{inner}} {{{{/raw}}}}{{after}}").let {
        assertEquals(listOf("raw", "raw", "after"), it.filter { (cat, _) -> cat == Cat.VARIABLE }.map { (_, text) -> text })
        assertEquals(listOf("{{{{", "{{{{", "{{"), it.filter { (cat, _) -> cat == Cat.LBRACE }.map { (_, text) -> text })
    }
    fun `test escaped mustache and raw block hold no names`() {
        val text = "{{#each xs as |x|}}\\{{x}} {{{{raw}}}}{{x}}{{{{/raw}}}} {{x}}{{/each}}"
        assertEquals(listOf(text.lastIndexOf("{{x}}") + 2), referenceOffsets("a.hbs", text))
    }
    fun `test lexer restarts inside escaped and raw text`() {
        val text = "\\{{literal and more}} {{{{raw}}}} {{inner one}} {{{{/raw}}}}{{after}}"
        val lexer = Handlebars.lexer()
        listOf("and", "more", "inner", "one").forEach { word ->
            lexer.start(text, text.indexOf(word), text.length, 0)
            assertEquals(word, Cat.PUNCT, (lexer.tokenType as FmtToken).cat)
        }
        listOf("raw}}}} {{inner", "raw}}}}{{after", "after").forEach { word ->
            lexer.start(text, text.indexOf(word), text.length, 0)
            assertEquals(word, Cat.VARIABLE, (lexer.tokenType as FmtToken).cat)
        }
    }
    fun `test bracket segment is one name`() = tokens("a.hbs", "{{foo.[bar baz].qux}}").let {
        assertEquals(listOf("foo", "[bar baz]", "qux"), it.filter { (cat, _) -> cat == Cat.VARIABLE }.map { (_, text) -> text })
    }
    fun `test bracket segment holds no block parameter`() {
        val text = "{{#each xs as |baz|}}{{foo.[bar baz]}} {{baz}}{{/each}}"
        assertEquals(listOf(text.lastIndexOf("baz")), referenceOffsets("a.hbs", text))
    }
    fun `test mustache escaped tag is text`() = tokens("a.mustache", "\\{{literal}} {{name}}").let {
        assertEquals(listOf("name"), it.filter { (cat, _) -> cat == Cat.VARIABLE }.map { (_, text) -> text })
    }
}
