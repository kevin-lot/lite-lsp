package me.kevinlot.lite.formats

class MermaidTest : FmtTestCase() {
    private val sequence = """
        sequenceDiagram
          participant WEB as Web portal
          participant MGC as campaign-config-service
          actor User
          Note right of WEB: Create campaign
          WEB ->> MGC: POST /campaigns
          MGC -->> WEB: 200 OK + created Campaign
          loop for each config step: audience, rules
            WEB ->> MGC: PUT /campaigns/<id>/missions
            MGC--)Bus: mission_updated
          end
          Bus--)User: notification
    """.trimIndent() + "\n"

    private fun names(name: String, text: String) = Fmt.links(myFixture.configureByText(name, text)).decls.map { it.key }

    fun `test sequence participant`() = check("a.mermaid" to mark(sequence, "participant |MGC as", "WEB ->> ^MGC: POST"))
    fun `test sequence note`() = check("a.mermaid" to mark(sequence, "participant |WEB as", "Note right of ^WEB"))
    fun `test sequence participant first used in a message`() = check("a.mermaid" to mark(sequence, "MGC--)|Bus", "^Bus--)User"))
    fun `test sequence text is not a name`() =
        assertEquals(listOf("node:WEB", "node:MGC", "node:User", "node:Bus"), names("a.mermaid", sequence))

    fun `test flowchart node`() = check("a.mmd" to "flowchart LR\n  A[Start] --> <def>B{Is it ok?}\n  <ref>B -->|yes| C(Done)\n  B -- no --> A\n")
    fun `test flowchart subgraph`() = check("a.mmd" to "graph TD\n  subgraph <def>one [First zone]\n    a --> b\n  end\n  c --> <ref>one\n")
    fun `test flowchart class`() = check("a.mmd" to "flowchart LR\n  A:::<ref>hot --> B\n  classDef <def>hot fill:#f96,stroke:#333\n")
    fun `test flowchart class list`() = check("a.mmd" to "flowchart LR\n  A --> B\n  classDef <def>hot fill:#f96\n  class A,B <ref>hot\n")
    fun `test flowchart node of a class list`() = check("a.mmd" to "flowchart LR\n  <def>A --> B\n  classDef hot fill:#f96\n  class <ref>A,B hot\n")
    fun `test flowchart labels and styles are not names`() = assertEquals(
        listOf("node:A", "node:B", "node:C", "class:hot"),
        names("a.mmd", "flowchart LR\n  A[Start here] -->|go on| B((Round one))\n  B --> C[\"Quoted text\"]\n  style A fill:#f9f,stroke:#333\n  classDef hot fill:#f96\n  linkStyle 0 stroke:red\n  click A callback \"Tooltip text\"\n"),
    )

    fun `test state`() = check("a.mmd" to "---\ntitle: Content page life cycle\n---\nstateDiagram-v2\n    IN_DESIGN --> [*] : DELETE /{id}\n    state <def>if_activation <<choice>>\n    [*] --> IN_DESIGN : POST /\n    IN_DESIGN --> <ref>if_activation : POST /{id}/publish\n")
    fun `test state front matter and labels are not names`() = assertEquals(
        listOf("node:IN_DESIGN", "node:ACTIVE", "node:Moving"),
        names("a.mmd", "---\ntitle: Life cycle\n---\nstateDiagram-v2\n    %% comment here\n    [*] --> IN_DESIGN : POST /pages\n    IN_DESIGN --> ACTIVE : publish page\n    state Moving {\n        ACTIVE --> IN_DESIGN\n    }\n    note right of ACTIVE\n        Visible to players\n    end note\n"),
    )

    fun `test entity relation`() = check("a.mermaid" to "erDiagram\n  <def>Campaign[\"Campaign (100)\"] {\n    identifier UUID\n    brand enum \"BETCLIC\"\n  }\n  Mission {\n    string name\n  }\n  <ref>Campaign ||--o{ Mission: \"has 0..n\"\n")
    fun `test entity attributes and arrows are not names`() =
        assertEquals(listOf("node:Campaign", "node:Mission"), names("a.mermaid", "erDiagram\n  Campaign {\n    identifier UUID\n  }\n  Campaign ||--o{ Mission: \"has 0..n\"\n  Mission }o--|| Campaign: belongs\n"))

    fun `test class diagram`() = check("a.mmd" to "classDiagram\n  class <def>Animal {\n    +int age\n    +isMammal() bool\n  }\n  <ref>Animal <|-- Duck\n  Duck : +swim()\n")
    fun `test class members are not names`() =
        assertEquals(listOf("node:Animal", "node:Duck"), names("a.mmd", "classDiagram\n  class Animal {\n    +int age\n    +List~String~ names\n  }\n  Animal <|-- Duck\n  Duck : +swim() void\n"))

    fun `test other diagrams are text`() {
        assertEmpty(names("a.mmd", "gantt\n  title A plan\n  section First\n  Task one :a1, 2026-01-01, 30d\n"))
        assertEmpty(names("b.mmd", "pie title Pets\n  \"Dogs\" : 386\n  \"Cats\" : 85\n"))
    }

    fun `test coloring`() = tokens("a.mermaid", sequence).let {
        assertToken(it, Cat.KEYWORD, "sequenceDiagram"); assertToken(it, Cat.KEYWORD, "participant"); assertToken(it, Cat.KEYWORD, "as"); assertToken(it, Cat.KEYWORD, "loop")
        assertToken(it, Cat.OPERATOR, "->>"); assertToken(it, Cat.OPERATOR, "-->>"); assertToken(it, Cat.OPERATOR, "--)"); assertToken(it, Cat.WORD, "MGC"); assertToken(it, Cat.KEYWORD, "end")
    }
    fun `test entity relation coloring`() = tokens("a.mermaid", "%%{init: {'theme': 'dark'}}%%\nerDiagram\n  %% note\n  A ||--o{ B : \"has\"\n").let {
        assertToken(it, Cat.META, "%%{init: {'theme': 'dark'}}%%"); assertToken(it, Cat.COMMENT, "%% note"); assertToken(it, Cat.OPERATOR, "||--o{"); assertToken(it, Cat.STRING, "\"has\"")
    }

    fun `test rename participant`() {
        myFixture.configureByText("a.mermaid", "sequenceDiagram\n  participant MGC as config service\n  WEB ->> M<caret>GC: POST MGC\n  MGC -->> WEB: ok\n")
        myFixture.renameElementAtCaret("Config")
        myFixture.checkResult("sequenceDiagram\n  participant Config as config service\n  WEB ->> Config: POST MGC\n  Config -->> WEB: ok\n")
    }
}
