package me.kevinlot.lite.formats

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil

class BrunoTest : FmtTestCase() {

    private val request = """
        get {
          url: {{host}}/users?token={{token}}&key={{api.key}}&secret={{process.env.API_KEY}}
        }

        vars:pre-request {
          "api.key": 12
          cert: '''
            host: not-a-variable
          '''
          host: example.com
        }

        vars:post-response {
          @token: res.body.token
        }
    """.trimIndent() + "\n"

    private fun targets(text: String, word: String): List<PsiElement> {
        val file = myFixture.addFileToProject("api/users.bru", text)
        return PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).first { it.text == word }.references
            .flatMap { reference -> (reference as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element } }
    }

    private fun script(setter: String) = "get {\n  url: https://example.com/{{token}}\n}\n\nscript:post-response {\n  bru.$setter(\"token\", res.body.token);\n}\n"

    fun `test variable set for the collection by a script`() =
        check("api/users.bru" to mark(script("setCollectionVar"), "setCollectionVar(\"|token\"", "{{^token}}"))
    fun `test global environment variable set by a script`() =
        check("api/users.bru" to mark(script("setGlobalEnvVar"), "setGlobalEnvVar(\"|token\"", "{{^token}}"))
    fun `test a setter bruno does not have declares nothing`() = assertEmpty(targets(script("setGlobalVar"), "{{token}}"))

    fun `test local variable`() = check("api/users.bru" to mark(request, "@|token: res", "token={{^token}}"))
    fun `test quoted key`() = check("api/users.bru" to mark(request, "\"|api.key\": 12", "key={{^api.key}}"))
    fun `test quoted key is colored as a key`() = assertToken(tokens("users.bru", "headers {\n  \"my key\": 1\n}\n"), Cat.KEY, "\"my key\"")
    fun `test line of a multi-line value is not a variable`() {
        val found = targets(request, "{{host}}")
        assertEquals(listOf(request.indexOf("host: example.com")), found.map { it.textOffset })
    }

    fun `test process environment variable`() = check(
        "api/.env" to "OTHER=1\n<def>API_KEY=secret\n",
        "api/bruno.json" to "{}",
        "api/users/list.bru" to request.replaceFirst("process.env.API_KEY", "process.env.<ref>API_KEY"),
    )
    fun `test process environment variable without a dotenv file`() = assertEmpty(targets(request, "{{process.env.API_KEY}}"))
}
