package me.kevinlot.lite.formats

class JFlexTest : FmtTestCase() {

    private val quotes = "%%\n%state STRING\nQUOTE = \\\"\n%%\n" +
        "<YYINITIAL> . { char c = yycharat(0); if (c == '\"') yybegin(STRING); if (c == '\\'' || c == '\\\\') return T.BAD; }\n" +
        "<STRING> {QUOTE} { yybegin(YYINITIAL); }\n"

    fun `test state after a char literal holding a quote`() = check("L.flex" to mark(quotes, "%state |STRING", "yybegin(^STRING); if"))
    fun `test macro after char literals`() = check("L.flex" to mark(quotes, "|QUOTE =", "{^QUOTE}"))
    fun `test char literals are strings`() = tokens("L.flex", quotes).let {
        assertToken(it, Cat.STRING, "'\"'"); assertToken(it, Cat.STRING, "'\\''"); assertToken(it, Cat.STRING, "'\\\\'")
        assertToken(it, Cat.KEYWORD, "return"); assertToken(it, Cat.VARIABLE, "{QUOTE}")
    }
    fun `test name in a char literal is not a state`() {
        val text = "%%\n%state A\n%%\n. { if (c == 'A') yybegin(A); }\n"
        val file = myFixture.configureByText("L.flex", text)
        assertEquals(listOf(text.lastIndexOf("A")), Fmt.links(file).refs.map { it.range.startOffset })
    }

    private val classes = "%%\nLETTER = [a-z]\nID = [{LETTER}_] [{LETTER}\\{DIGIT}{OTHER}0-9]*\nSPACED = { LETTER }+\nJAVA = [[:jletter:]--[_$]] {LETTER}\n%%\n" +
        "{ ID } { return T.ID; }\n"

    fun `test macro inside a character class`() = check("L.flex" to mark(classes, "|LETTER =", "ID = [{^LETTER}_]"))
    fun `test second macro inside a character class`() = check("L.flex" to mark(classes, "|LETTER =", "[{^LETTER}\\{"))
    fun `test macro use with spaces`() = check("L.flex" to mark(classes, "|LETTER =", "SPACED = { ^LETTER }"))
    fun `test macro use with spaces in a rule`() = check("L.flex" to mark(classes, "|ID =", "{ ^ID } {"))
    fun `test only macros are names inside a character class`() {
        val file = myFixture.configureByText("L.flex", classes)
        val inClass = classes.indexOf("[{LETTER}\\{")..classes.indexOf("0-9]")
        assertEquals(1, Fmt.links(file).refs.count { it.range.startOffset in inClass })
    }
    fun `test macro use with spaces is colored`() = tokens("L.flex", classes).let {
        assertToken(it, Cat.VARIABLE, "{ LETTER }"); assertToken(it, Cat.VARIABLE, "{ ID }"); assertToken(it, Cat.KEYWORD, "return")
    }
    fun `test nested character class is one token`() = tokens("L.flex", classes).let {
        assertToken(it, Cat.STRING, "[[:jletter:]--[_$]]"); assertToken(it, Cat.VARIABLE, "{LETTER}")
    }
    fun `test rename macro used inside a character class`() {
        myFixture.configureByText("L.flex", "%%\nLET<caret>TER = [a-z]\nID = [{LETTER}_]+ { LETTER }\n%%\n")
        myFixture.renameElementAtCaret("ALPHA")
        myFixture.checkResult("%%\nALPHA = [a-z]\nID = [{ALPHA}_]+ { ALPHA }\n%%\n")
    }
}
