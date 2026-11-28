package me.kevinlot.lite.formats

class BnfTest : FmtTestCase() {

    private val grammar = """
        {
          parserClass="a.b.Parser"
          extends(".*Expr")="Expr"
          tokens = [
            LBRACE = '{'
            RBRACE = '}'
            CLASS = "class"
            ID = "regexp:\w+"
            // NOTE = 'x'
          ]
        }
        File ::= Item*
        private Item ::= Class | <<list Expr>> { recoverWhile="Item_recover" }
        Class ::= 'class' ID '{' Expr? RBRACE { pin = 1 elementType = Class }
        private Item_recover ::= !('class' | '}')
        meta list ::= <<p>> (',' <<p>>)*
        Expr ::= ID
        // Expr ::= nothing
    """.trimIndent()

    fun `test rule use goes to its definition`() = check("G.bnf" to mark(grammar, "|Class ::=", "Item ::= ^Class |"))
    fun `test private rule`() = check("G.bnf" to mark(grammar, "private |Item ::=", "File ::= ^Item*"))
    fun `test meta rule call`() = check("G.bnf" to mark(grammar, "meta |list ::=", "<<^list Expr>>"))
    fun `test meta rule argument`() = check("G.bnf" to mark(grammar, "|Expr ::= ID", "<<list ^Expr>>"))
    fun `test token name goes to the tokens block`() = check("G.bnf" to mark(grammar, "|RBRACE = '}'", "Expr? ^RBRACE"))
    fun `test token text goes to the token that spells it`() = check("G.bnf" to mark(grammar, "LBRACE = '|{'", "ID '^{' Expr?"))
    fun `test keyword text in double quotes`() = check("G.bnf" to mark(grammar, "CLASS = \"|class\"", "Class ::= '^class'"))
    fun `test quoted rule in an attribute`() = check("G.bnf" to mark(grammar, "private |Item_recover ::=", "recoverWhile=\"^Item_recover\""))
    fun `test quoted rule in a pattern attribute`() = check("G.bnf" to mark(grammar, "|Expr ::= ID", "=\"^Expr\""))
    fun `test bare rule in an attribute value`() = check("G.bnf" to mark(grammar, "|Class ::=", "elementType = ^Class"))

    fun `test attribute keys, comments and regexps name nothing`() {
        val file = myFixture.configureByText("G.bnf", grammar)
        val links = Fmt.links(file)
        val refs = links.refs.map { grammar.substring(it.range.startOffset, it.range.endOffset) }
        assertFalse(refs.toString(), "pin" in refs || "nothing" in refs || "p" in refs)
        assertEquals(1, links.declared("rule:Expr").size)
        assertTrue(links.declared("token:NOTE").isEmpty())
        assertTrue(links.declared("value:regexp:\\w+").isEmpty())
    }

    fun `test colors`() = tokens("G.bnf", grammar).let {
        assertToken(it, Cat.KEYWORD, "private"); assertToken(it, Cat.KEYWORD, "meta")
        assertToken(it, Cat.DECL, "Item"); assertToken(it, Cat.OPERATOR, "::=")
        assertToken(it, Cat.KEY, "recoverWhile"); assertToken(it, Cat.KEY, "LBRACE"); assertToken(it, Cat.KEY, "extends")
        assertToken(it, Cat.STRING, "'{'"); assertToken(it, Cat.META, "<<"); assertToken(it, Cat.NUMBER, "1")
        assertToken(it, Cat.COMMENT, "// Expr ::= nothing")
    }

    fun `test rename a rule`() {
        myFixture.configureByText("G.bnf", "Fi<caret>le ::= Item\nItem ::= x { recoverWhile = \"File\" }\nOther ::= File\n")
        myFixture.renameElementAtCaret("Root")
        myFixture.checkResult("Root ::= Item\nItem ::= x { recoverWhile = \"Root\" }\nOther ::= Root\n")
    }

    fun `test rename a token`() {
        myFixture.configureByText("G.bnf", "{ tokens = [ SE<caret>MI = ';' ] }\nA ::= x SEMI ';'\n")
        myFixture.renameElementAtCaret("SEMICOLON")
        myFixture.checkResult("{ tokens = [ SEMICOLON = ';' ] }\nA ::= x SEMICOLON ';'\n")
    }

    fun `test a closing bracket inside a token value does not end the block`() {
        val file = myFixture.configureByText("G.bnf", "{ tokens = [ RBRACK = ']' COMMA = ',' ] }\nA ::= COMMA\n")
        assertEquals(1, Fmt.links(file).declared("token:COMMA").size)
    }

    fun `test the imported grammars of this repository are read`() {
        val text = java.io.File("../protobuf/src/main/grammars/Proto.bnf").readText()
        val file = myFixture.configureByText("Proto.bnf", text)
        val links = Fmt.links(file)
        assertTrue(links.decls.count { it.key.startsWith("rule:") } > 50)
        assertTrue(links.decls.count { it.key.startsWith("token:") } > 10)
        assertTrue(links.refs.size > 200)
    }
}
