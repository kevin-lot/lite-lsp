package me.kevinlot.lite.formats

class JustTest : FmtTestCase() {
    private fun links(text: String) = Fmt.links(myFixture.configureByText("justfile", text))
    private fun declared(text: String) = links(text).decls.map { it.key }
    private fun referenced(text: String) = links(text).refs.flatMap { it.keys }

    fun `test default that is a function call`() = assertEquals(
        listOf("recipe:deploy", "param:deploy:target", "param:deploy:args"),
        declared("deploy target=env('TARGET', 'dev') *args:\n    echo\n"),
    )
    fun `test default that is a nested function call`() = assertEquals(
        listOf("recipe:deploy", "param:deploy:target", "param:deploy:args"),
        declared("deploy target=env('TARGET', env('FALLBACK', 'dev')) *args:\n    echo\n"),
    )
    fun `test default that is a prefixed string`() {
        val text = "open v=x'~/a':\n    echo\n"
        assertEquals(listOf("recipe:open", "param:open:v"), declared(text))
        assertEquals(emptyList<String>(), referenced(text))
    }
    fun `test variable in a function call default`() =
        check("justfile" to "<def>fallback := 'dev'\ndeploy target=env('TARGET', <ref>fallback):\n    echo\n")

    fun `test multi-line expression`() = check(
        "justfile" to "<def>release_flags := \"-O\"\nflags := if mode == \"debug\" {\n  \"-g\"\n} else {\n  <ref>release_flags\n}\n",
    )
    fun `test recipe after a multi-line expression`() = check(
        "justfile" to "flags := if mode == \"debug\" {\n  \"-g\" # {\n} else { \"-O\" }\n<def>build:\n    cc\ntest: <ref>build\n",
    )

    fun `test module path`() = check("justfile" to "mod <def>foo\ntest:\n    echo\nbuild: <ref>foo::test\n")
    fun `test module path is no local recipe`() =
        assertEquals(listOf("mod:foo", "mod:foo", "mod:foo"), referenced("mod foo\ntest:\n    echo\nbuild: foo::test (foo::bar::test 'x')\nalias t := foo::test\n"))
}
