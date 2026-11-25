package me.kevinlot.lite.formats

import com.intellij.psi.util.PsiTreeUtil

class GherkinTest : FmtTestCase() {

    private val commande = """
        # language: fr
        Fonctionnalité: Commandes
          Scénario: Panier
            Etant donnée une commande
            Et que le panier est vide
            Sachant qu'il pleut
            Lorsqu'elle arrive
            Donc le total est nul
    """.trimIndent() + "\n"

    private val pets = "Feature: Pets\n  Scenario: One\n    Given I have a dog\n    And I have a bird\n    Then the ratio is 1/2\n    And I visit home/page\n"

    private val outline = """
        Feature: Orders
          Scenario Outline: Create
            Given the payload
              ""${'"'}
              {"user": "<name>"}
              ""${'"'}
            And the lines
              | sku   | owner  |
              | <sku> | <name> |

            Examples:
              | name | sku | note \| more |
              | bob  | 12  | none         |
    """.trimIndent() + "\n"

    private fun resolved(file: String, text: String, word: String): List<Any> {
        val psi = myFixture.addFileToProject(file, text)
        return PsiTreeUtil.findChildrenOfType(psi, FmtWord::class.java).first { it.text == word }.references.mapNotNull { it.resolve() }
    }

    fun `test french given in the feminine`() = check(
        "features/steps.js" to "const { Given } = require('@cucumber/cucumber');\nGiven('<def>une commande', function () {});\n",
        "features/commande.feature" to commande.replaceFirst("une commande", "une <ref>commande"),
    )
    fun `test french and followed by que`() = check(
        "features/steps.js" to "const { Given } = require('@cucumber/cucumber');\nGiven('<def>le panier est vide', function () {});\n",
        "features/commande.feature" to commande.replaceFirst("panier est", "<ref>panier est"),
    )
    fun `test french keyword ending with an apostrophe`() = check(
        "features/steps.js" to "const { Given } = require('@cucumber/cucumber');\nGiven('<def>il pleut', function () {});\n",
        "features/commande.feature" to commande.replaceFirst("il pleut", "il <ref>pleut"),
    )
    fun `test french keywords are colored`() = tokens("commande.feature", commande).let {
        assertToken(it, Cat.KEYWORD, "Etant donnée"); assertToken(it, Cat.KEYWORD, "Et que"); assertToken(it, Cat.KEYWORD, "Sachant qu'")
        assertToken(it, Cat.KEYWORD, "Lorsqu'"); assertToken(it, Cat.KEYWORD, "Donc")
    }

    fun `test step to a french java annotation`() = check(
        "src/test/java/Steps.java" to "import io.cucumber.java.fr.Etantdonnée;\nclass Steps {\n  @Etantdonnée(\"<def>une commande\")\n  public void commande() {}\n}\n",
        "src/test/resources/commande.feature" to commande.replaceFirst("une commande", "une <ref>commande"),
    )
    fun `test step to a french java annotation without its apostrophe`() = check(
        "src/test/java/Steps.java" to "import io.cucumber.java.fr.Sachantqu;\nclass Steps {\n  @Sachantqu(\"<def>il pleut\")\n  public void pluie() {}\n}\n",
        "src/test/resources/commande.feature" to commande.replaceFirst("il pleut", "il <ref>pleut"),
    )
    fun `test step to a javascript defineStep`() = check(
        "features/steps.js" to "const { defineStep } = require('@cucumber/cucumber');\ndefineStep('<def>le total est nul', function () {});\n",
        "features/commande.feature" to commande.replaceFirst("total est", "<ref>total est"),
    )

    fun `test cucumber expression alternation`() = check(
        "features/steps.js" to "const { Given } = require('@cucumber/cucumber');\nGiven('<def>I have a cat/dog', function () {});\n",
        "features/pets.feature" to pets.replaceFirst("a dog", "a <ref>dog"),
    )
    fun `test alternation accepts only its alternatives`() {
        myFixture.addFileToProject("features/steps.js", "const { Given } = require('@cucumber/cucumber');\nGiven('I have a cat/dog', function () {});\n")
        assertEmpty(resolved("features/pets.feature", pets, "bird"))
    }
    fun `test escaped slash is not an alternation`() = check(
        "features/steps.js" to "const { Then } = require('@cucumber/cucumber');\nThen('<def>the ratio is 1\\\\/2', function () {});\n",
        "features/pets.feature" to pets.replaceFirst("ratio is", "<ref>ratio is"),
    )
    fun `test slash of a parse format stays literal`() = check(
        "features/steps/pets.py" to "from behave import given\n\n@given('<def>I visit home/page')\ndef step_impl(context):\n    pass\n",
        "features/pets.feature" to pets.replaceFirst("I visit", "I <ref>visit"),
    )

    fun `test placeholder in a doc string`() = check(
        "orders.feature" to outline.replaceFirst("\"<name>\"", "\"<<ref>name>\"").replaceFirst("| name | sku", "| <def>name | sku"),
    )
    fun `test placeholder in a step table`() = check(
        "orders.feature" to outline.replaceFirst("| <sku>", "| <<ref>sku>").replaceFirst("| name | sku", "| name | <def>sku"),
    )
    fun `test placeholder in a comment is not linked`() {
        val file = myFixture.configureByText("orders.feature", outline.replaceFirst("    Given the payload", "    # uses <name>\n    Given the payload"))
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).filter { it.text.startsWith("#") }.flatMap { it.references.toList() })
    }

    fun `test english feature synonyms are colored`() = tokens("need.feature", "Business Need: Orders\n  Scenario: One\n    Given a step\n").let {
        assertToken(it, Cat.KEYWORD, "Business Need")
        assertToken(tokens("ability.feature", "Ability: Orders\n"), Cat.KEYWORD, "Ability")
    }
    fun `test escaped pipe stays in its cell`() = tokens("orders.feature", outline).let {
        assertToken(it, Cat.KEY, "note \\| more"); assertToken(it, Cat.KEY, "sku"); assertToken(it, Cat.KEY, "bob")
    }
}
