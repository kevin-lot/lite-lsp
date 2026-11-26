package me.kevinlot.lite.formats

import com.intellij.json.psi.JsonProperty
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.formats.config.PackageScriptReference

class PackageScriptsTest : FmtTestCase() {
    private fun pkg(vararg scripts: Pair<String, String>) =
        "{\n  \"name\": \"svc\",\n  \"scripts\": {\n" + scripts.joinToString(",\n") { (name, line) -> "    $name: \"$line\"" } + "\n  }\n}\n"

    private fun injected(text: String, at: String, name: String = "package.json"): PsiFile? {
        val file = myFixture.configureByText(name, text)
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, text.indexOf(at))?.containingFile
    }

    fun `test a script is shell`() {
        val script = injected(pkg("\"build\"" to "tsc -p . && echo \\\"done\\\" > out.txt"), "tsc")!!
        assertEquals("LiteShell", script.language.id)
        assertEquals("tsc -p . && echo \"done\" > out.txt", script.text)
        assertNull(PsiTreeUtil.findChildOfType(script, PsiErrorElement::class.java))
    }

    fun `test the script run is a plain word for the shell`() =
        assertEquals("yarn ${Scripts.PLACEHOLDER} && jest", injected(pkg("\"lint\"" to "eslint .", "\"test\"" to "yarn lint && jest"), "yarn")!!.text)

    fun `test only scripts of a package json are shell`() {
        assertNull(injected("{\"scripts\": {\"build\": \"tsc\"}}", "tsc", "other.json"))
        assertNull(injected("{\"config\": {\"build\": \"tsc\"}, \"scripts\": {}}", "tsc"))
        assertNull(injected("{\"scripts\": {\"a\": {\"build\": \"tsc\"}}}", "tsc"))
    }

    fun `test yarn`() = check("package.json" to pkg("<def>\"build\"" to "tsc", "\"ci\"" to "yarn <ref>build"))
    fun `test yarn run with a flag`() = check("package.json" to pkg("<def>\"build\"" to "tsc", "\"ci\"" to "yarn run --silent <ref>build"))
    fun `test npm run`() = check("package.json" to pkg("<def>\"test:unit\"" to "jest", "\"test\"" to "npm run <ref>test:unit -- --ci"))
    fun `test npm test`() = check("package.json" to pkg("<def>\"test\"" to "jest", "\"ci\"" to "npm <ref>test"))
    fun `test pnpm after another command`() = check("package.json" to pkg("<def>\"lint\"" to "eslint .", "\"ci\"" to "tsc && pnpm <ref>lint"))
    fun `test run-s list`() = check("package.json" to pkg("\"a\"" to "x", "<def>\"b\"" to "y", "\"all\"" to "run-s a <ref>b"))
    fun `test quoted command of concurrently`() = check("package.json" to pkg("<def>\"api\"" to "node .", "\"dev\"" to "concurrently \\\"yarn <ref>api\\\""))

    fun `test a binary is no script`() {
        val file = myFixture.configureByText("package.json", pkg("\"build\"" to "tsc", "\"ci\"" to "yarn tsc && npm build && npm install"))
        val literal = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).first { it.name == "ci" }.value!!
        assertEmpty(literal.references.filterIsInstance<PackageScriptReference>())
    }

    fun `test usages and rename`() {
        val file = myFixture.configureByText("package.json", pkg("\"test:unit\"" to "jest", "\"test\"" to "yarn test:unit && npm run test:unit"))
        val script = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).first { it.name == "test:unit" }
        val ci = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).first { it.name == "test" }.value!!
        val own = ci.references.filterIsInstance<PackageScriptReference>().map { it.rangeInElement }
        assertEquals(2, own.size)
        // the JavaScript plugin of the test IDE links the same names: one reference per place is reported, its own first
        assertEquals(own, ReferencesSearch.search(script).findAll().map { it.rangeInElement }.sortedBy { it.startOffset })
        myFixture.renameElement(script, "unit")
        myFixture.checkResult(pkg("\"unit\"" to "jest", "\"test\"" to "yarn unit && npm run unit"))
    }
}
