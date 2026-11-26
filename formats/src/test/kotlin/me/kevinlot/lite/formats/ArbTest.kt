package me.kevinlot.lite.formats

class ArbTest : FmtTestCase() {
    private val en = """
        {
          "@@locale": "en",
          "helloWorld": "Hello {name}",
          "@helloWorld": {
            "description": "Greeting",
            "placeholders": { "name": { "type": "String" }, "title": {} }
          },
          "title": "Home",
          "count": 2
        }
    """

    private val fr = """
        {
          "helloWorld": "Bonjour {name}",
          "title": "Accueil"
        }
    """

    fun `test tokens`() = tokens("app_en.arb", "{ \"a\": \"b\", \"@a\": { \"n\": 1, \"t\": true } }\n").let {
        assertEquals(listOf("\"a\"", "\"@a\"", "\"n\"", "\"t\""), it.filter { token -> token.first == Cat.KEY }.map { token -> token.second })
        assertToken(it, Cat.STRING, "\"b\"")
        assertToken(it, Cat.NUMBER, "1")
        assertToken(it, Cat.CONSTANT, "true")
    }

    fun `test description names its message`() = check("app_en.arb" to mark(en, "\"|helloWorld\": \"Hello", "\"@^helloWorld\""))

    fun `test only top level keys are messages`() {
        val links = Fmt.links(myFixture.configureByText("app_en.arb", en.trimIndent()))
        assertEquals(listOf("message:helloWorld", "message:title", "message:count"), links.decls.map { it.key })
        assertEquals(listOf("message:helloWorld"), links.refs.flatMap { it.keys })
    }

    fun `test description resolves in its own file`() {
        myFixture.addFileToProject("app_fr.arb", fr.trimIndent())
        val file = myFixture.configureByText("app_en.arb", en.trimIndent())
        val targets = (file.findElementAt(file.text.indexOf("@helloWorld") + 2)?.parent as FmtWord).references.flatMap { ref ->
            (ref as FmtReference).multiResolve(false).mapNotNull { it.element?.containingFile?.name }
        }
        assertEquals(listOf("app_en.arb"), targets)
    }

    fun `test rename reaches the description and the other locales`() {
        val other = myFixture.addFileToProject("app_fr.arb", fr.trimIndent())
        val file = myFixture.configureByText("app_en.arb", en.trimIndent())
        myFixture.renameElement(checkNotNull(file.findElementAt(file.text.indexOf("helloWorld"))?.parent), "greeting")
        assertTrue(file.text, "\"greeting\": \"Hello" in file.text && "\"@greeting\": {" in file.text)
        assertTrue(other.text, "\"greeting\": \"Bonjour" in other.text && "\"title\": \"Accueil\"" in other.text)
    }

    fun `test usages of a message`() {
        val file = myFixture.configureByText("app_en.arb", en.trimIndent())
        assertEquals(1, myFixture.findUsages(checkNotNull(file.findElementAt(file.text.indexOf("helloWorld"))?.parent)).size)
    }
}
