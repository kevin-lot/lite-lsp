package me.kevinlot.lite.markdown

/*
 * Mermaid, read for the preview only: flowcharts, sequence, state, class, entity-relationship diagrams and pies become
 * a small model that MdDiagram.kt lays out and paints. What is not understood is skipped; an unknown diagram type gives
 * null and the preview keeps the code block.
 */

internal enum class Shape { BOX, ROUND, CIRCLE, DIAMOND, POINT }

internal data class DNode(val id: String, val label: String, val shape: Shape = Shape.BOX)

internal data class DEdge(val from: String, val to: String, val label: String = "", val dashed: Boolean = false, val arrow: Boolean = true)

internal sealed class Step {
    data class Message(val from: String, val to: String, val text: String, val dashed: Boolean, val arrow: Boolean) : Step()
    data class Note(val over: List<String>, val text: String) : Step()
    data class Divider(val text: String) : Step()
}

internal sealed class Diagram {
    data class Graph(val nodes: List<DNode>, val edges: List<DEdge>, val horizontal: Boolean) : Diagram()
    data class Sequence(val actors: List<DNode>, val steps: List<Step>) : Diagram()
    data class Pie(val title: String, val slices: List<Pair<String, Double>>) : Diagram()
}

/** Nodes in the order they are first named; a later explicit label replaces the id shown until then. */
private class GraphBuilder {
    val nodes = LinkedHashMap<String, DNode>()
    val edges = ArrayList<DEdge>()

    fun node(id: String, label: String? = null, shape: Shape = Shape.BOX): String {
        val known = nodes[id]
        if (known == null || label != null) nodes[id] = DNode(id, label ?: id, shape.takeIf { label != null || known == null } ?: known?.shape ?: shape)
        return id
    }

    fun line(id: String, text: String) {
        val known = nodes[node(id)] ?: return
        nodes[id] = known.copy(label = "${known.label}\n$text")
    }

    fun build(horizontal: Boolean) = Diagram.Graph(nodes.values.toList(), edges.toList(), horizontal)
}

internal object MdMermaid {
    private val BREAK = Regex("""<br\s*/?>|\\n""")
    private val FRONT_MATTER = Regex("""\A\s*---\n[\s\S]*?\n---\n""")
    private val TAG = Regex("<[^>]+>")

    fun parse(code: String): Diagram? {
        val lines = code.replace(FRONT_MATTER, "").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%%") }
        val header = lines.firstOrNull() ?: return null
        val body = lines.drop(1)
        val type = header.substringBefore(' ').substringBefore('-')
        return when (type) {
            "classDiagram" -> blocks(body, ::classLine)
            "erDiagram" -> blocks(body, ::entityLine)
            "flowchart", "graph" -> flow(header, body)
            "pie" -> pie(header, body)
            "sequenceDiagram" -> sequence(body)
            "stateDiagram" -> state(body)
            else -> null
        }
    }

    private fun clean(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("`").replace(BREAK, "\n").replace(TAG, "").replace("#quot;", "\"").trim()

    // ---------------------------------------------------------------- flowchart

    private val FLOW_SKIPPED = Regex("""^(?:subgraph|end|direction|classDef|class|style|linkStyle|click)\b.*""")
    private val FLOW_NODE = Regex(
        """\s*([A-Za-z0-9_][\w.]*)(?::::[\w-]+)?\s*(\(\[.*?]\)|\[\[.*?]]|\[\(.*?\)]|\(\(.*?\)\)|\{\{.*?}}|\[.*?]|\(.*?\)|\{.*?}|>.*?])?(?::::[\w-]+)?""",
    )
    private val FLOW_EDGE = Regex(
        """\s*[<ox]?(?:--\s+([^-|>][^|>]*?)\s*--+|==\s+([^=|>][^|>]*?)\s*==+|-\.\s*([^.|>-][^|>]*?)\s*\.+-|--+|==+|-\.+-)([>ox])?\s*(?:\|([^|]*)\|)?\s*""",
    )

    private fun flow(header: String, body: List<String>): Diagram {
        val builder = GraphBuilder()
        body.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() && !FLOW_SKIPPED.matches(it) }.forEach { chain(it, builder) }
        val direction = header.substringAfter(' ', "").trim()
        return builder.build(horizontal = direction.startsWith("LR") || direction.startsWith("RL"))
    }

    /** `A --> B & C -->|label| D`: each link joins every node of its left group to every node of its right group. */
    private fun chain(statement: String, builder: GraphBuilder) {
        val first = group(statement, 0, builder) ?: return
        generateSequence(first) { (from, at) ->
            val edge = FLOW_EDGE.matchAt(statement, at)?.takeIf { it.value.isNotBlank() } ?: return@generateSequence null
            val next = group(statement, edge.range.last + 1, builder) ?: return@generateSequence null
            val label = clean(edge.groupValues.slice(listOf(1, 2, 3, 5)).firstOrNull { it.isNotEmpty() }.orEmpty())
            val dashed = edge.value.trimStart().trimStart('<', 'o', 'x').startsWith("-.")
            from.forEach { a -> next.first.forEach { b -> builder.edges += DEdge(a, b, label, dashed, arrow = edge.groupValues[4] == ">") } }
            next
        }.count()
    }

    private fun group(statement: String, at: Int, builder: GraphBuilder): Pair<List<String>, Int>? {
        val first = flowNode(statement, at, builder) ?: return null
        val nodes = generateSequence(first) { (_, end) ->
            val amp = (end until statement.length).firstOrNull { !statement[it].isWhitespace() }?.takeIf { statement[it] == '&' } ?: return@generateSequence null
            flowNode(statement, amp + 1, builder)
        }.toList()
        return nodes.map { it.first } to nodes.last().second
    }

    private fun flowNode(statement: String, at: Int, builder: GraphBuilder): Pair<String, Int>? {
        val match = FLOW_NODE.matchAt(statement, at) ?: return null
        val raw = match.groupValues[2]
        val label = raw.takeIf { it.isNotEmpty() }?.let { clean(it.trimStart('(', '[', '{', '>').trimEnd(')', ']', '}')) }
        val shape = when {
            raw.startsWith("((") -> Shape.CIRCLE
            raw.startsWith("(") -> Shape.ROUND
            raw.startsWith("{") && !raw.startsWith("{{") -> Shape.DIAMOND
            else -> Shape.BOX
        }
        return builder.node(match.groupValues[1], label, shape) to match.range.last + 1
    }

    // ---------------------------------------------------------------- sequence

    private val SEQ_BLOCK = Regex("""^(loop|alt|else|opt|par|and|critical|option|break)\b\s*(.*)$""")
    private val SEQ_MESSAGE = Regex("""^(.+?)\s*(--?>>|--?>|--?x|--?\))\s*[+-]?\s*([^:]+?)\s*:\s*(.*)$""")
    private val SEQ_NOTE = Regex("""^[Nn]ote\s+(?:over|left of|right of)\s+([^:]+):\s*(.*)$""")
    private val SEQ_PARTICIPANT = Regex("""^(?:participant|actor)\s+(.+?)(?:\s+as\s+(.+))?$""")

    private fun sequence(body: List<String>): Diagram {
        val actors = LinkedHashMap<String, DNode>()
        fun actor(id: String, label: String? = null): String {
            if (label != null || id !in actors) actors[id] = DNode(id, clean(label ?: id))
            return id
        }
        val steps = body.mapNotNull { line ->
            SEQ_PARTICIPANT.find(line)?.let { actor(it.groupValues[1], it.groupValues[2].takeIf(String::isNotEmpty)); return@mapNotNull null }
            SEQ_NOTE.find(line)?.let { m -> return@mapNotNull Step.Note(m.groupValues[1].split(',').map { actor(it.trim()) }, clean(m.groupValues[2])) }
            SEQ_BLOCK.find(line)?.let { return@mapNotNull Step.Divider("${it.groupValues[1]} ${clean(it.groupValues[2])}".trim()) }
            val m = SEQ_MESSAGE.find(line) ?: return@mapNotNull null
            val arrow = m.groupValues[2]
            Step.Message(actor(m.groupValues[1].trim()), actor(m.groupValues[3].trim()), clean(m.groupValues[4]), arrow.startsWith("--"), !arrow.endsWith("x"))
        }
        return Diagram.Sequence(actors.values.toList(), steps)
    }

    // ---------------------------------------------------------------- state

    private val STATE_ALIAS = Regex("""^state\s+"([^"]*)"\s+as\s+([\w.]+).*$""")
    private val STATE_DESCRIPTION = Regex("""^([\w.]+)\s*:\s*(.+)$""")
    private val STATE_NAMED = Regex("""^state\s+([\w.]+).*$""")
    private val STATE_TRANSITION = Regex("""^(\[\*]|[\w.]+)\s*-->\s*(\[\*]|[\w.]+)\s*(?::\s*(.*))?$""")

    private fun state(body: List<String>): Diagram {
        val builder = GraphBuilder()
        fun end(name: String, id: String): String {
            if (name != "[*]") return builder.node(name, shape = Shape.ROUND)
            return builder.node(id, "", Shape.POINT)
        }
        body.forEach { line ->
            STATE_ALIAS.find(line)?.let { builder.node(it.groupValues[2], clean(it.groupValues[1]), Shape.ROUND); return@forEach }
            STATE_NAMED.find(line)?.let { builder.node(it.groupValues[1], shape = Shape.ROUND); return@forEach }
            STATE_TRANSITION.find(line)?.let { m ->
                builder.edges += DEdge(end(m.groupValues[1], "[start]"), end(m.groupValues[2], "[end]"), clean(m.groupValues[3]))
                return@forEach
            }
            STATE_DESCRIPTION.find(line)?.let { builder.node(it.groupValues[1], clean(it.groupValues[2]), Shape.ROUND) }
        }
        return builder.build(horizontal = body.any { it.startsWith("direction LR") })
    }

    // ---------------------------------------------------------------- class and entity-relationship

    private val BLOCK_OPEN = Regex("""^(?:class\s+)?([\w-]+)(?:~[^~]*~)?(?:\s+\[?"[^"]*"]?)?\s*\{$""")
    private val CLASS_MEMBER = Regex("""^([\w-]+)\s*:\s*(.+)$""")
    private val CLASS_NAMED = Regex("""^class\s+([\w-]+).*$""")
    private val CLASS_RELATION = Regex(
        """^([\w-]+)\s*(?:"[^"]*")?\s*(<\|--|--\|>|<\|\.\.|\.\.\|>|\*--|--\*|o--|--o|<--|-->|<\.\.|\.\.>|--|\.\.)\s*(?:"[^"]*")?\s*([\w-]+)\s*(?::\s*(.*))?$""",
    )
    private val ENTITY_RELATION = Regex("""^([\w-]+)\s+[|o}{]{2}(--|\.\.)[|o}{]{2}\s+([\w-]+)\s*(?::\s*(.*))?$""")

    /** `Name {` … `}` lists the lines of a box; every other line goes to [single]. */
    private fun blocks(body: List<String>, single: (String, GraphBuilder) -> Unit): Diagram {
        val builder = GraphBuilder()
        body.fold(null as String?) { open, line ->
            if (line == "}") return@fold null
            if (open != null) return@fold open.also { builder.line(it, line) }
            val opened = BLOCK_OPEN.find(line)?.let { builder.node(it.groupValues[1]) }
            if (opened == null) single(line, builder)
            opened
        }
        return builder.build(horizontal = body.any { it.startsWith("direction LR") })
    }

    private fun classLine(line: String, builder: GraphBuilder) {
        val relation = CLASS_RELATION.find(line)
        if (relation != null) {
            val (left, op, right, label) = relation.destructured
            val reversed = op.startsWith("<") || op.startsWith("*") || op.startsWith("o")
            val (from, to) = (right to left).takeIf { reversed } ?: (left to right)
            builder.edges += DEdge(builder.node(from), builder.node(to), clean(label), dashed = ".." in op, arrow = op != "--" && op != "..")
            return
        }
        CLASS_MEMBER.find(line)?.let { builder.line(it.groupValues[1], it.groupValues[2]); return }
        CLASS_NAMED.find(line)?.let { builder.node(it.groupValues[1]) }
    }

    private fun entityLine(line: String, builder: GraphBuilder) {
        val relation = ENTITY_RELATION.find(line) ?: return
        val (left, op, right, label) = relation.destructured
        builder.edges += DEdge(builder.node(left), builder.node(right), clean(label), dashed = op == "..", arrow = false)
    }

    // ---------------------------------------------------------------- pie

    private val PIE_SLICE = Regex("""^"([^"]*)"\s*:\s*([\d.]+)$""")

    private fun pie(header: String, body: List<String>): Diagram {
        val titles = (listOf(header.substringAfter("pie").trim().removePrefix("showData").trim()) + body).filter { it.startsWith("title") }
        val slices = body.mapNotNull { PIE_SLICE.find(it) }.mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1] to it } }
        return Diagram.Pie(titles.firstOrNull()?.removePrefix("title")?.trim().orEmpty(), slices)
    }
}
