package me.kevinlot.lite.formats

import com.intellij.psi.util.PsiTreeUtil

class D2Test : FmtTestCase() {
    private val library = "classes: {\n  person: {\n    shape: person\n    style: {fill: \"#E10014\"; bold: true}\n  }\n  system: {shape: rectangle}\n  sync: {style.stroke-dash: 0}\n  inferred: {style.opacity: 0.6}\n}\n"
    private val page = """
        ...@common/c4
        direction: down
        vars: {
          brand: "#8A0000"
          d2-config: {layout-engine: elk}
        }
        header: "" {
          near: top-center
          title: "Container view" {style: {font-color: ${'$'}{brand}; bold: true}}
        }
        player: "Player\n[Person]" {class: person}
        platform: "Offer platform" {
          api: "API\n[Container]" {class: system; width: 300}
          db: Database {shape: cylinder}
          api -> db: reads {class: [sync; inferred]}
          api -> _.player: notifies
        }
        player -> platform.api: "places a bet"
        platform.db.style.fill: ${'$'}{brand}
        (player -> platform.api)[0].style.stroke: red
        "Kong gateway" -> platform.api
    """.trimIndent() + "\n"

    private fun d2(def: String, ref: String) = arrayOf("diagrams/common/c4.d2" to library, "diagrams/page.d2" to mark(page, def, ref))

    fun `test shape`() = check(*d2("|player: \"Player", "^player -> platform.api: \"places"))
    fun `test nested shape`() = check(*d2("  |api: \"API", "player -> platform.^api: \"places"))
    fun `test container of a nested shape`() = check(*d2("|platform: \"Offer", "player -> ^platform.api: \"places"))
    fun `test shape inside its container`() = check(*d2("  |api: \"API", "  ^api -> db: reads"))
    fun `test shape of the parent container`() = check(*d2("|player: \"Player", "api -> _.^player: notifies"))
    fun `test shape under a reserved keyword`() = check(*d2("  |db: Database", "platform.^db.style.fill"))
    fun `test shape of an existing connection`() = check(*d2("|player: \"Player", "(^player -> platform.api)[0]"))
    fun `test class of an imported file`() = check("diagrams/common/c4.d2" to library.replaceFirst("  person: {", "  <def>person: {"), "diagrams/page.d2" to page.replaceFirst("{class: person}", "{class: <ref>person}"))
    fun `test second class of a list`() = check("diagrams/common/c4.d2" to library.replaceFirst("  inferred: {", "  <def>inferred: {"), "diagrams/page.d2" to page.replaceFirst("[sync; inferred]", "[sync; <ref>inferred]"))
    fun `test class of the file`() = check("a.d2" to "classes: {\n  <def>hot: {style.fill: red}\n}\nx: X {class: <ref>hot}\n")
    fun `test class written with dots`() = check("a.d2" to "classes.<def>hot.style.fill: red\nx.class: <ref>hot\n")
    fun `test variable`() = check(*d2("  |brand: \"#8A0000\"", "platform.db.style.fill: ${'$'}{^brand}"))
    fun `test variable inside a block`() = check(*d2("  |brand: \"#8A0000\"", "font-color: ${'$'}{^brand}; bold"))
    fun `test import`() = check("diagrams/common/c4.d2" to library, "diagrams/page.d2" to page.replaceFirst("...@common/c4", "...@<ref>common/c4"))
    fun `test import through another import`() = check(
        "common/base.d2" to "classes: {\n  <def>dim: {style.opacity: 0.4}\n}\n",
        "common/c4.d2" to "...@base\n",
        "page.d2" to "...@common/c4\nx: X {class: <ref>dim}\n",
    )

    fun `test keywords and labels are not shapes`() {
        val file = myFixture.addFileToProject("diagrams/page.d2", page)
        myFixture.addFileToProject("diagrams/common/c4.d2", library)
        val shapes = Fmt.links(file).decls.map { it.key }.filter { it.startsWith("shape:") }.map { it.removePrefix("shape:") }
        assertEquals(listOf("header", "header.title", "player", "platform", "platform.api", "platform.db", "Kong gateway"), shapes)
        assertEquals(listOf("var:brand"), Fmt.links(file).decls.map { it.key }.filter { it.startsWith("var:") })
        val unresolved = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java).flatMap { it.references.toList() }.filter { it.resolve() == null }
        assertEmpty(unresolved.map { it.canonicalText })
    }

    fun `test glob and comment declare nothing`() {
        val file = myFixture.configureByText("a.d2", "# note: a -> b\n*.style.fill: red\n\"\"\" block: c \"\"\"\nx -> y\n")
        assertEquals(listOf("shape:x", "shape:y"), Fmt.links(file).decls.map { it.key })
    }

    fun `test coloring`() = tokens("page.d2", page).let {
        assertToken(it, Cat.META, "...@common/c4"); assertToken(it, Cat.KEYWORD, "direction"); assertToken(it, Cat.KEYWORD, "vars"); assertToken(it, Cat.KEY, "player")
        assertToken(it, Cat.OPERATOR, "->"); assertToken(it, Cat.STRING, "\"places a bet\""); assertToken(it, Cat.STRING, "reads"); assertToken(it, Cat.KEYWORD, "class")
        assertToken(it, Cat.NUMBER, "300"); assertToken(it, Cat.CONSTANT, "true"); assertToken(it, Cat.KEY, "sync"); assertToken(it, Cat.STRING, "person")
    }

    fun `test rename shape`() {
        myFixture.configureByText("a.d2", "db: Database\napi: {\n  cache\n}\napi.cache -> d<caret>b\ndb.style.fill: red\n")
        myFixture.renameElementAtCaret("store")
        myFixture.checkResult("store: Database\napi: {\n  cache\n}\napi.cache -> store\nstore.style.fill: red\n")
    }

    fun `test rename class across the import`() {
        val user = myFixture.addFileToProject("page.d2", "...@lib\nx: X {class: hot}\ny: Y {class: [hot; cold]}\n")
        myFixture.configureByText("lib.d2", "classes: {\n  h<caret>ot: {style.fill: red}\n  cold: {style.fill: blue}\n}\n")
        myFixture.renameElementAtCaret("warm")
        myFixture.checkResult("classes: {\n  warm: {style.fill: red}\n  cold: {style.fill: blue}\n}\n")
        assertEquals("...@lib\nx: X {class: warm}\ny: Y {class: [warm; cold]}\n", user.text)
    }
}
