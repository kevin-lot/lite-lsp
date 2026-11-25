package me.kevinlot.lite.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Font
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

class MdMermaidTest : BasePlatformTestCase() {
    private val style = DiagramStyle(Font(Font.SANS_SERIF, Font.PLAIN, 13), Color.BLACK, Color.WHITE)

    private fun graph(code: String) = MdMermaid.parse(code) as Diagram.Graph
    private fun Diagram.Graph.links() = edges.map { "${it.from}>${it.to}:${it.label}" }

    fun `test flowchart nodes, shapes and labelled links`() {
        val g = graph("flowchart LR\n  A[Start] --> B{Ok?}\n  B -->|yes| C(Done)\n  B -- no --> D((Stop)); D -.-> A\n")
        assertTrue(g.horizontal)
        assertEquals(listOf("A:Start:BOX", "B:Ok?:DIAMOND", "C:Done:ROUND", "D:Stop:CIRCLE"), g.nodes.map { "${it.id}:${it.label}:${it.shape}" })
        assertEquals(listOf("A>B:", "B>C:yes", "B>D:no", "D>A:"), g.links())
        assertEquals(listOf(false, false, false, true), g.edges.map { it.dashed })
    }

    fun `test flowchart chains, groups and skipped statements`() {
        val g = graph("graph TD\n  subgraph one\n  a --> b & c --> d\n  end\n  classDef x fill:#fff\n  a --- e\n  %% comment\n")
        assertFalse(g.horizontal)
        assertEquals(listOf("a>b:", "a>c:", "b>d:", "c>d:", "a>e:"), g.links())
        assertEquals(listOf(true, true, true, true, false), g.edges.map { it.arrow })
    }

    fun `test sequence diagram`() {
        val d = MdMermaid.parse("sequenceDiagram\n  participant A as Alice\n  A->>Bob: Hello\n  loop retry\n  Bob-->>A: Hi\n  end\n  Note over A,Bob: done\n  Bob->>Bob: think\n") as Diagram.Sequence
        assertEquals(listOf("A:Alice", "Bob:Bob"), d.actors.map { "${it.id}:${it.label}" })
        assertEquals(
            listOf(
                Step.Message("A", "Bob", "Hello", dashed = false, arrow = true), Step.Divider("loop retry"),
                Step.Message("Bob", "A", "Hi", dashed = true, arrow = true), Step.Note(listOf("A", "Bob"), "done"),
                Step.Message("Bob", "Bob", "think", dashed = false, arrow = true),
            ),
            d.steps,
        )
    }

    fun `test state diagram`() {
        val g = graph("stateDiagram-v2\n  [*] --> Idle\n  Idle --> Running : start\n  state \"Shut down\" as Off\n  Running --> Off\n  Off --> [*]\n")
        assertEquals(listOf("[start]>Idle:", "Idle>Running:start", "Running>Off:", "Off>[end]:"), g.links())
        assertEquals("Shut down", g.nodes.single { it.id == "Off" }.label)
        assertEquals(Shape.POINT, g.nodes.single { it.id == "[start]" }.shape)
    }

    fun `test class diagram`() {
        val g = graph("classDiagram\n  Animal <|-- Dog : is\n  class Animal {\n    +String name\n    +eat()\n  }\n  Dog : +bark()\n  Dog ..> Bone\n")
        assertEquals(listOf("Dog>Animal:is", "Dog>Bone:"), g.links())
        assertEquals("Animal\n+String name\n+eat()", g.nodes.single { it.id == "Animal" }.label)
        assertEquals("Dog\n+bark()", g.nodes.single { it.id == "Dog" }.label)
    }

    fun `test entity relationship diagram`() {
        val g = graph("erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  ORDER {\n    int id PK\n  }\n")
        assertEquals(listOf("CUSTOMER>ORDER:places"), g.links())
        assertEquals("ORDER\nint id PK", g.nodes.single { it.id == "ORDER" }.label)
    }

    fun `test pie`() {
        val p = MdMermaid.parse("pie title Pets\n  \"Dogs\" : 30\n  \"Cats\" : 10.5\n") as Diagram.Pie
        assertEquals("Pets", p.title)
        assertEquals(listOf("Dogs" to 30.0, "Cats" to 10.5), p.slices)
    }

    fun `test unknown diagram types are not drawn`() = assertNull(MdMermaid.parse("gantt\n  title Plan\n"))

    fun `test ranks follow the links and a cycle does not loop`() {
        val image = MdDiagram.paint(graph("graph TD\n A --> B --> C --> A\n B --> D\n D --> D\n"), style)!!
        assertTrue("${image.width}x${image.height}", image.height > image.width / 2 && image.width > 60)
        assertEquals(image.width * 2, image.image.width)
    }

    fun `test a horizontal graph is wider than tall`() {
        val image = MdDiagram.paint(graph("graph LR\n A --> B --> C --> D\n"), style)!!
        assertTrue("${image.width}x${image.height}", image.width > image.height * 2)
    }

    fun `test every kind paints`() = listOf(
        "sequenceDiagram\n A->>B: hi\n Note right of B: ok\n B-->>A: yo\n",
        "pie\n \"a\" : 1\n \"b\" : 3\n",
        "stateDiagram\n [*] --> S\n S --> [*]\n",
    ).forEach { assertNotNull(it, MdDiagram.paint(MdMermaid.parse(it)!!, style)) }

    fun `test the preview shows a mermaid block as an image`() {
        val html = MdHtml.render("# T\n\n```mermaid\ngraph TD\n  A --> B\n```\n", null)
        val src = Regex("""<img src="([^"]+)" width="(\d+)" height="(\d+)">""").find(html) ?: error(html)
        val png = ImageIO.read(File(URI(src.groupValues[1])))
        assertEquals(1.4, src.groupValues[2].toDouble() * 2 / png.width, 0.02)
        assertEquals(png.width.toDouble() / png.height, src.groupValues[2].toDouble() / src.groupValues[3].toDouble(), 0.05)
        assertFalse(html, "graph TD" in html)
    }

    fun `test the preview keeps an unknown diagram as code`() {
        val html = MdHtml.render("```mermaid\ngantt\n  title Plan\n```\n", null)
        assertTrue(html, "<pre><code>" in html && "<img" !in html)
    }

    /** LITE_MD_CORPUS=<dir:dir>: every ```mermaid block of the .md files is drawn into build/mermaid (LITE_SHOW=1 names the ones kept as code). */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_MD_CORPUS")?.split(':')?.map(::File) ?: return
        val out = File("build/mermaid").apply { deleteRecursively(); mkdirs() }
        val block = Regex("""```mermaid[^\n]*\n([\s\S]*?)\n\s*```""")
        val files = dirs.flatMap { d -> d.walkTopDown().onEnter { it.name !in setOf("node_modules", ".git", "target", "build") }.filter { it.isFile && it.extension == "md" }.toList() }
        val blocks = files.flatMap { f -> block.findAll(f.readText()).mapIndexed { i, m -> "${f.nameWithoutExtension}-$i" to m.groupValues[1] }.toList() }
        val kept = blocks.filter { (name, code) ->
            val image = MdMermaid.parse(code)?.let { MdDiagram.paint(it, style) } ?: return@filter true
            ImageIO.write(image.image, "png", File(out, "$name.png"))
            false
        }
        println("mermaid corpus: ${blocks.size} blocks, ${blocks.size - kept.size} drawn, kept as code: ${kept.groupingBy { it.second.trim().substringBefore('\n').substringBefore(' ') }.eachCount()}")
        if (System.getenv("LITE_SHOW") != null) kept.forEach { println(it.first) }
    }
}
