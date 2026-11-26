package me.kevinlot.lite.formats

import me.kevinlot.lite.formats.config.TomlReader
import me.kevinlot.lite.formats.config.TomlValue

class TomlTest : FmtTestCase() {

    private fun List<Pair<Cat?, String>>.of(cat: Cat) = filter { it.first == cat }.map { it.second }

    fun `test nested array at the start of a line is no table header`() =
        tokens("a.toml", "deps = [\n  [\"a\", \"b\"],\n  [1, 2],\n]\n[table]\n  [table.sub] # c\n[[bin]]\n").let {
            assertEquals(listOf("[table]", "[table.sub]", "[[bin]]"), it.of(Cat.SECTION))
            assertEquals(listOf("\"a\"", "\"b\""), it.of(Cat.STRING))
            assertEquals(listOf("1", "2"), it.of(Cat.NUMBER))
        }

    fun `test single quoted key`() = tokens("a.toml", "'quoted' = 1\n\"dq\".'sq'.bare = 'v'\n").let {
        assertEquals(listOf("'quoted'", "\"dq\".'sq'.bare"), it.of(Cat.KEY))
        assertEquals(listOf("'v'"), it.of(Cat.STRING))
    }

    private val root = """
        [workspace]
        members = ["crates/api", "crates/*"]

        [workspace.package]
        edition = "2021"
        version = "1.2.0"

        [workspace.dependencies]
        serde = { version = "1", features = ["derive"] }
        tokio = "1"
        [workspace.dependencies.tracing]
        version = "0.1"
    """

    private val member = """
        [package]
        name = "api"
        version.workspace = true
        edition = { workspace = true }
        build = "build.rs"

        [dependencies]
        serde = { workspace = true }
        tokio.workspace = true
        local = { path = "../local" }
        log = { version = "0.4", optional = true }

        [dev-dependencies.tracing]
        workspace = true

        [target.'cfg(unix)'.dependencies]
        libc = "0.2"

        [features]
        default = ["json", "dep:log"]
        json = ["serde/derive", "libc?/std"]
        full = ["json", "log"]
    """

    private fun cargo(def: String, ref: String, inRoot: Boolean = true) = when {
        inRoot -> check("Cargo.toml" to root.replaceFirst(def.replace("|", ""), def.replace("|", "<def>")),
            "crates/api/Cargo.toml" to member.replaceFirst(ref.replace("^", ""), ref.replace("^", "<ref>")))
        else -> check("crates/api/Cargo.toml" to mark(member, def, ref), "Cargo.toml" to root)
    }

    fun `test cargo workspace dependency in an inline table`() = cargo("|serde = { version", "^serde = { workspace")
    fun `test cargo workspace dependency with a dotted key`() = cargo("|tokio = \"1\"", "^tokio.workspace")
    fun `test cargo workspace dependency declared and used as tables`() = cargo("dependencies.|tracing]", "dev-dependencies.^tracing]")
    fun `test cargo workspace package key with a dotted key`() = cargo("|version = \"1.2.0\"", "^version.workspace")
    fun `test cargo workspace package key in an inline table`() = cargo("|edition = \"2021\"", "^edition = {")
    fun `test cargo feature of the same table`() = cargo("|json = [", "full = [\"^json\"", inRoot = false)
    fun `test cargo feature naming an optional dependency`() = cargo("|log = { version", "\"json\", \"^log\"]", inRoot = false)
    fun `test cargo dep prefix`() = cargo("|log = { version", "\"dep:^log\"", inRoot = false)
    fun `test cargo feature of a workspace dependency`() = cargo("|serde = { version", "\"^serde/derive\"")
    fun `test cargo weak feature of a target dependency`() = cargo("|libc = \"0.2\"", "\"^libc?/std\"", inRoot = false)

    fun `test cargo path dependency`() = check("local/Cargo.toml" to "[package]\nname = \"local\"\n",
        "api/Cargo.toml" to "[dependencies]\nlocal = { path = \"<ref>../local\" }\n")
    fun `test cargo build script path`() = check("build.rs" to "fn main() {}\n", "Cargo.toml" to "[package]\nbuild = \"<ref>build.rs\"\n")
    fun `test cargo workspace member path`() = check("crates/api/Cargo.toml" to "[package]\nname = \"api\"\n",
        "Cargo.toml" to "[workspace]\nmembers = [\"crates/*\", \"<ref>crates/api\"]\n")

    fun `test cargo glob member and plain toml have no link`() {
        val links = { name: String, text: String -> Fmt.links(myFixture.configureByText(name, text)) }
        assertEmpty(links("Cargo.toml", "[workspace]\nmembers = [\"crates/*\"]\n").paths)
        links("settings.toml", "[dependencies]\nserde = { workspace = true }\n[features]\na = [\"b\"]\n").let { assertEmpty(it.decls + it.refs + it.paths) }
    }

    fun `test cargo rename of a workspace dependency reaches the crates`() {
        val api = myFixture.addFileToProject("crates/api/Cargo.toml", member.trimIndent())
        val file = myFixture.addFileToProject("Cargo.toml", root.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.renameElement(checkNotNull(file.findElementAt(file.text.indexOf("serde = { version"))?.parent), "serde2")
        assertTrue(api.text, "serde2 = { workspace = true }" in api.text && "\"serde2/derive\"" in api.text)
        assertTrue(file.text, "serde2 = { version = \"1\"" in file.text)
    }

    private val catalog = """
        [versions]
        kotlin = "2.1.0"
        junit-jupiter = { strictly = "5.11.0" }

        [libraries]
        kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
        junit-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version = { ref = "junit-jupiter" } }
        junit-engine.module = "org.junit.jupiter:junit-jupiter-engine"

        [bundles]
        testing = ["junit-api", "junit-engine"]

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """

    private fun catalog(def: String, ref: String) = check("gradle/libs.versions.toml" to mark(catalog, def, ref))

    fun `test catalog version ref of a library`() = catalog("|kotlin = \"2.1.0\"", "kotlin-stdlib\", version.ref = \"^kotlin\"")
    fun `test catalog version ref in a nested table`() = catalog("|junit-jupiter = {", "version = { ref = \"^junit-jupiter\"")
    fun `test catalog version ref of a plugin`() = catalog("|kotlin = \"2.1.0\"", "kotlin.jvm\", version.ref = \"^kotlin\"")
    fun `test catalog bundle library`() = catalog("|junit-api = {", "testing = [\"^junit-api\"")
    fun `test catalog bundle library with a dotted key`() = catalog("|junit-engine.module", "\"junit-api\", \"^junit-engine\"")

    fun `test catalog rename of a version`() {
        val file = myFixture.configureByText("libs.versions.toml", catalog.trimIndent())
        myFixture.renameElement(checkNotNull(file.findElementAt(file.text.indexOf("kotlin = \"2.1.0\""))?.parent), "kotlin-lang")
        assertEquals(2, Regex("""version\.ref = "kotlin-lang"""").findAll(file.text).count())
        assertTrue(file.text, "kotlin-lang = \"2.1.0\"" in file.text)
    }

    fun `test toml reader`() {
        val entries = TomlReader("# c\n[a.\"b.c\"]\nx.y = { z = [1, \"two\", { w = 'three' }], q = true } # t\n[[bin]]\nname = \"\"\"\nmulti\"\"\"\nbroken line\nok = 1\n").entries()
        assertEquals(listOf("a/b.c/x/y/z", "a/b.c/x/y/q", "bin/name", "bin/ok"), entries.map { it.names.joinToString("/") })
        val items = (entries.first().value as TomlValue.Array).items
        assertEquals(listOf("two"), items.filterIsInstance<TomlValue.Text>().map { it.text })
        assertEquals("three", ((items.last() as TomlValue.Table).entries.single().second as TomlValue.Text).text)
        assertEquals("\nmulti", (entries[2].value as TomlValue.Text).text)
    }
}
