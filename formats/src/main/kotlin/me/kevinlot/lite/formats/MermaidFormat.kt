package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange

/**
 * Mermaid diagrams: in flowcharts, sequence, state, class and entity-relationship diagrams, an identifier → where it
 * first appears (its `participant`, `state`, `class` line or its first use); `:::name` and `class A,B name` → `classDef`.
 * The other diagram types are colored only: their lines are text, not names.
 */
object Mermaid : Format("Mermaid", "Mermaid", "mermaid") {
    override val lineComment = "%%"
    override val nameCategories = setOf(Cat.WORD)

    private val DIAGRAMS = listOf(
        "C4Component", "C4Container", "C4Context", "C4Deployment", "C4Dynamic", "architecture-beta", "block-beta", "classDiagram", "classDiagram-v2",
        "erDiagram", "flowchart", "gantt", "gitGraph", "graph", "journey", "kanban", "mindmap", "packet-beta", "pie", "quadrantChart",
        "requirementDiagram", "sankey-beta", "sequenceDiagram", "stateDiagram", "stateDiagram-v2", "timeline", "xychart-beta", "zenuml",
    )
    private val KEYWORDS = listOf(
        "Note", "accDescr", "accTitle", "activate", "actor", "alt", "and", "as", "autonumber", "box", "break", "class", "classDef", "click", "critical",
        "deactivate", "direction", "else", "end", "left", "linkStyle", "loop", "namespace", "note", "of", "opt", "option", "over", "par", "participant",
        "rect", "right", "section", "state", "style", "subgraph", "title",
    )
    private val DIRECTIONS = setOf("BT", "LR", "RL", "TB", "TD")
    /** The diagram types whose identifiers name things; a `{ … }` block holds other names in a state diagram only. */
    private val LINKED = setOf("classDiagram", "classDiagram-v2", "erDiagram", "flowchart", "graph", "sequenceDiagram", "stateDiagram", "stateDiagram-v2")
    private val COLON_STARTS_TEXT = LINKED - setOf("flowchart", "graph")
    private val BLOCK_HOLDS_TEXT = setOf("classDiagram", "classDiagram-v2", "erDiagram")
    private val STYLED = setOf("flowchart", "graph", "stateDiagram", "stateDiagram-v2")
    private const val ARROW = """(?:<\|?|[ox*](?=[-=.])|[|}][o|]?)?(?:[-=.~]{2,}|-(?=[>x)]))(?:\|?>{1,2}|[o|]?[|{]|[ox*](?!\w)|\))?"""
    private const val NAME = """[\p{L}_][\p{L}\p{N}_]*(?:-[\p{L}\p{N}_]+)*"""

    private val ARROWS = Regex(ARROW)
    private val BLOCK = Regex("""\{[^{}]*}""")
    private val CLASS_DEF = Regex("""(?m)^[ \t]*classDef[ \t]+($NAME(?:[ \t]*,[ \t]*$NAME)*)[^\n]*""")
    private val CLASS_LIST = Regex("""(?m)^[ \t]*class[ \t]+[^\n]*?[ \t]($NAME)[ \t]*;?[ \t]*$""")
    private val CLASS_SUFFIX = Regex(""":::($NAME)""")
    private val COLON_TEXT = Regex("""(?<!:):(?!::)[^\n]*""")
    private val COMMENT = Regex("""%%[^\n]*""")
    private val FIRST_WORD = Regex("""[A-Za-z][\w-]*""")
    private val FRONT_MATTER = Regex("""\A\s*---[ \t]*\n[\s\S]*?\n---[ \t]*(?:\n|\z)""")
    /** Lines that hold free text after their keyword, or a name followed by text. */
    private val FREE_TEXT = Regex(
        """(?m)^[ \t]*(?:(?:accDescr|accTitle|alt|and|autonumber|box|break|critical|direction|else|linkStyle|loop|opt|option|par|rect|section|title)\b[^\n]*""" +
            """|(?:actor|participant)[ \t]+\S+[ \t]+as\b([^\n]*)|(?:click|style|subgraph)[ \t]+\S+([^\n]*))""",
    )
    private val LABEL = Regex(""""[^"\n]*"|\|[^|\n]*\||\[[^\]\n]*]|\([^)\n]*\)|\{[^{}\n]*}|~[^~\n]*~|<<[^>\n]*>>""")
    private val NOTE_BLOCK = Regex("""(?m)^[ \t]*note\b[^:\n]*\n[\s\S]*?^[ \t]*end note\b""")
    private val WORD = Regex(NAME)

    override val rules = listOf(
        Rule("""%%\{[\s\S]*?(?:}%%|\z)""", Cat.META),
        Rule("""%%[^\n]*""", Cat.COMMENT),
        Rule(R.DQ, Cat.STRING),
        Rule("""<<[^>\n]*>>""", Cat.META),
        Rule("""(?:${DIAGRAMS.sortedByDescending { it.length }.joinToString("|")})(?![\w-])""", Cat.KEYWORD, lineStart = true),
        Rule("""(?<![\w-])(?:${KEYWORDS.sortedByDescending { it.length }.joinToString("|")})(?![\w-])""", Cat.KEYWORD),
        Rule(ARROW, Cat.OPERATOR),
        Rule("""\|[^|\n]+\|""", Cat.STRING),
        Rule("""\d+(?:\.\d+)?(?![\w-])""", Cat.NUMBER),
        Rule(NAME, Cat.WORD),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val start = FRONT_MATTER.find(text)?.range?.last?.plus(1) ?: 0
            val body = text.substring(start)
            val type = COMMENT.replace(body, "").lineSequence().firstNotNullOfOrNull { FIRST_WORD.find(it)?.value } ?: return Links()
            if (type !in LINKED) return Links()

            val classes = CLASS_DEF.findAll(body).mapNotNull { it.groups[1] }.flatMap { names -> WORD.findAll(names.value).map { range(start + names.range.first, it) to it.value } }
            val styled = type in STYLED
            val classUses = CLASS_SUFFIX.findAll(body).mapNotNull { it.groups[1] }.map { range(start, it) to it.value } +
                CLASS_LIST.findAll(body).filter { styled }.mapNotNull { it.groups[1] }.map { range(start, it) to it.value }
            val taken = (classes + classUses).map { it.first.startOffset }.toSet()

            val live = live(body, type)
            val header = live.indexOf(type).takeIf { it >= 0 }?.let { it + type.length } ?: 0
            val names = WORD.findAll(live, header)
                .filter { it.value !in KEYWORDS && it.value !in DIRECTIONS && start + it.range.first !in taken }
                .map { range(start, it.groups[0] ?: return@map null) to it.value }.filterNotNull().toList()
            val firsts = names.distinctBy { it.second }.toSet()
            return Links(
                decls = firsts.map { (range, name) -> Decl(range, "node:$name") } + classes.distinctBy { it.second }.map { (range, name) -> Decl(range, "class:$name") },
                refs = names.filterNot { it in firsts }.map { (range, name) -> Ref(range, listOf("node:$name")) } +
                    classUses.map { (range, name) -> Ref(range, listOf("class:$name")) },
            )
        }
    }

    private fun range(base: Int, group: MatchGroup) = TextRange(base + group.range.first, base + group.range.last + 1)
    private fun range(base: Int, match: MatchResult) = TextRange(base + match.range.first, base + match.range.last + 1)

    /** [body] at the same offsets, with everything that is text and not a name blanked. */
    private fun live(body: String, type: String): String {
        val blanked = StringBuilder(body)
        fun blank(range: IntRange) = range.forEach { if (blanked[it] != '\n') blanked[it] = ' ' }
        fun blankAll(pattern: Regex) = pattern.findAll(blanked).toList().forEach { blank(it.range) }
        fun blankGroups(pattern: Regex) = pattern.findAll(blanked).toList().forEach { match ->
            val groups = match.groups.drop(1).filterNotNull()
            if (groups.isEmpty()) blank(match.range)
            groups.forEach { blank(it.range) }
        }
        blankAll(COMMENT)
        blankAll(NOTE_BLOCK)
        // first: the `{` of an arrow such as `||--o{` opens no block
        blankAll(ARROWS)
        blankAll(LABEL)
        if (type in BLOCK_HOLDS_TEXT) blankAll(BLOCK)
        blankAll(CLASS_DEF)
        blankAll(CLASS_SUFFIX)
        blankGroups(FREE_TEXT)
        if (type in STYLED) blankGroups(CLASS_LIST)
        if (type in COLON_STARTS_TEXT) blankAll(COLON_TEXT)
        return blanked.toString()
    }
}
