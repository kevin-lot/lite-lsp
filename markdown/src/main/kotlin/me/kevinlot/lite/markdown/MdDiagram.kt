package me.kevinlot.lite.markdown

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* Layout and painting of a [Diagram] with Java2D: a layered graph, a sequence chart or a pie. Plain, not pretty. */

internal class DiagramStyle(val font: Font, val foreground: Color, val background: Color) {
    val fill: Color = mix(background, foreground, 0.08)
    val line: Color = mix(background, foreground, 0.55)
    val note: Color = mix(background, Color(0xE6, 0xB4, 0x22), 0.35)

    private fun mix(a: Color, b: Color, t: Double) =
        Color((a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(), (a.blue + (b.blue - a.blue) * t).toInt())
}

/** [image] holds [SCALE] pixels per unit of [width] and [height], for sharp text on dense screens. */
internal class DiagramImage(val image: BufferedImage, val width: Int, val height: Int)

private const val SCALE = 2
private const val MARGIN = 16.0
private const val MAX_LABEL_GAP = 150.0
private const val MAX_NODES = 300
private const val NODE_GAP = 28.0
private val DASH = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f, 4f), 0f)
private val SOLID = BasicStroke(1.2f)

/** A drawing whose size is known before it is painted. */
private class Scene(val width: Double, val height: Double, val paint: (Graphics2D) -> Unit)

private class Label(text: String, metrics: FontMetrics) {
    val lines = text.split('\n')
    val lineHeight = metrics.height.toDouble()
    val ascent = metrics.ascent.toDouble()
    val widths = lines.map { metrics.stringWidth(it).toDouble() }
    val width = widths.maxOrNull() ?: 0.0
    val height = lineHeight * lines.size

    /** Each line centered on [cx], the block centered on [cy]. */
    fun draw(g: Graphics2D, cx: Double, cy: Double) = lines.forEachIndexed { i, line ->
        g.drawString(line, (cx - widths[i] / 2).toFloat(), (cy - height / 2 + i * lineHeight + ascent).toFloat())
    }
}

internal object MdDiagram {
    fun paint(diagram: Diagram, style: DiagramStyle): DiagramImage? {
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        val metrics = scratch.getFontMetrics(style.font)
        scratch.dispose()
        val scene = when (diagram) {
            is Diagram.Graph -> graph(diagram, style, metrics)
            is Diagram.Pie -> pie(diagram, style, metrics)
            is Diagram.Sequence -> sequence(diagram, style, metrics)
        } ?: return null
        val width = scene.width.roundToInt().coerceAtLeast(1)
        val height = scene.height.roundToInt().coerceAtLeast(1)
        val image = BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.scale(SCALE.toDouble(), SCALE.toDouble())
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = style.background
        g.fill(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
        g.font = style.font
        g.stroke = SOLID
        scene.paint(g)
        g.dispose()
        return DiagramImage(image, width, height)
    }

    // ---------------------------------------------------------------- graph

    /** A node box: [main] runs along the ranks (down, or right when horizontal), [cross] across them. */
    private class Box(val node: DNode, val label: Label) {
        val width = when (node.shape) {
            Shape.DIAMOND -> label.width * 1.5 + 28
            Shape.POINT -> 16.0
            else -> label.width + 28
        }
        val height = when (node.shape) {
            Shape.DIAMOND -> label.height * 1.6 + 18
            Shape.POINT -> 16.0
            else -> label.height + 18
        }
        var x = 0.0
        var y = 0.0
    }

    private fun graph(diagram: Diagram.Graph, style: DiagramStyle, metrics: FontMetrics): Scene? {
        if (diagram.nodes.isEmpty() || diagram.nodes.size > MAX_NODES) return null
        val boxes = diagram.nodes.associate { it.id to Box(it, Label(it.label, metrics)) }
        val edges = diagram.edges.filter { it.from in boxes && it.to in boxes }
        val labels = edges.associateWith { Label(it.label, metrics) }
        val ranks = ranks(diagram.nodes.map { it.id }, edges)
        val layers = ordered(ranks, edges)
        val labelGap = labels.values.maxOfOrNull { along(diagram.horizontal, it.width, it.height) } ?: 0.0
        val incoming = edges.groupBy { it.to }
        // a node keeps room across the ranks for the labels of the links reaching it, drawn next to it
        val slots = boxes.values.associateWith { box ->
            val reaching = incoming[box.node.id].orEmpty().map(labels::getValue)
            val widest = along(diagram.horizontal, reaching.sumOf { it.height }, reaching.maxOfOrNull { it.width } ?: 0.0)
            max(along(diagram.horizontal, box.height, box.width), widest + 8)
        }
        // a link coming back to the first rank writes its label before that rank
        val lead = layers.first().flatMap { incoming[it].orEmpty() }.maxOfOrNull { labels.getValue(it).width + 8 }?.takeIf { diagram.horizontal } ?: 0.0
        place(layers.map { layer -> layer.mapNotNull(boxes::get) }, slots, diagram.horizontal, lead, rankGap = 44 + min(labelGap, along(diagram.horizontal, 2 * MAX_LABEL_GAP, MAX_LABEL_GAP)))
        val width = boxes.values.maxOf { it.x + along(diagram.horizontal, it.width, slots.getValue(it)) / 2 } + MARGIN
        val height = boxes.values.maxOf { it.y + along(diagram.horizontal, slots.getValue(it), it.height) / 2 } + MARGIN
        return Scene(width, height) { g ->
            edges.forEach { edge ->
                val same = incoming.getValue(edge.to)
                val to = boxes.getValue(edge.to)
                val label = labels.getValue(edge)
                val above = same.takeWhile { it !== edge }.sumOf { labels.getValue(it).height }
                val stacked = to.y - same.sumOf { labels.getValue(it).height } / 2 + above + label.height / 2
                val beside = (to.x - to.width / 2 - 8 - label.width / 2 to stacked).takeIf { diagram.horizontal }
                paintEdge(g, style, edge, boxes.getValue(edge.from), to, label, 0.75 - 0.3 * same.indexOf(edge) / same.size, beside)
            }
            boxes.values.forEach { paintBox(g, style, it) }
        }
    }

    /** The rank of a node is one more than its deepest predecessor; an edge closing a cycle does not count. */
    private fun ranks(ids: List<String>, edges: List<DEdge>): Map<String, Int> {
        val next = edges.filter { it.from != it.to }.groupBy({ it.from }, { it.to })
        val rank = LinkedHashMap<String, Int>()
        val open = HashSet<String>()
        val done = LinkedHashSet<String>()
        val forward = ArrayList<Pair<String, String>>()
        fun visit(id: String) {
            open += id
            next[id].orEmpty().filter { it !in open }.forEach { to ->
                forward += id to to
                if (to !in done) visit(to)
            }
            open -= id
            done += id
        }
        ids.filter { it !in done }.forEach { if (it !in done) visit(it) }
        val incoming = forward.groupBy({ it.second }, { it.first })
        done.reversed().forEach { id -> rank[id] = incoming[id].orEmpty().maxOfOrNull { (rank[it] ?: 0) + 1 } ?: 0 }
        return rank
    }

    /** Layers by rank; inside a layer, nodes follow the average position of their neighbours in the layer before. */
    private fun ordered(ranks: Map<String, Int>, edges: List<DEdge>): List<List<String>> {
        val neighbours = (edges.map { it.from to it.to } + edges.map { it.to to it.from }).groupBy({ it.first }, { it.second })
        val initial = ranks.entries.groupBy({ it.value }, { it.key }).toSortedMap().values.toList()
        return initial.drop(1).fold(listOf(initial.first())) { placed, layer ->
            val previous = placed.last().withIndex().associate { it.value to it.index }
            val sorted = layer.withIndex().sortedBy { (index, id) ->
                neighbours[id].orEmpty().mapNotNull(previous::get).takeIf { it.isNotEmpty() }?.average() ?: index.toDouble()
            }.map { it.value }
            placed.plusElement(sorted)
        }
    }

    /** Each layer is packed, then centered on the widest one. */
    private fun place(layers: List<List<Box>>, slots: Map<Box, Double>, horizontal: Boolean, lead: Double, rankGap: Double) {
        fun mainSize(b: Box) = along(horizontal, b.width, b.height)
        fun crossSize(b: Box) = slots.getValue(b)
        fun extent(layer: List<Box>) = layer.sumOf(::crossSize) + NODE_GAP * (layer.size - 1)
        val widest = layers.maxOf(::extent)
        layers.fold(MARGIN + lead) { offset, layer ->
            val thickness = layer.maxOf(::mainSize)
            layer.fold(MARGIN + (widest - extent(layer)) / 2) { free, box ->
                val cross = free + crossSize(box) / 2
                val main = offset + thickness / 2
                box.x = along(horizontal, main, cross)
                box.y = along(horizontal, cross, main)
                free + crossSize(box) + NODE_GAP
            }
            offset + thickness + rankGap
        }
    }

    /** [horizontally] when the ranks run left to right, [vertically] when they run down. */
    private fun along(horizontal: Boolean, horizontally: Double, vertically: Double): Double = when {
        horizontal -> horizontally
        else -> vertically
    }

    private fun stroke(dashed: Boolean): BasicStroke = when {
        dashed -> DASH
        else -> SOLID
    }

    private fun paintBox(g: Graphics2D, style: DiagramStyle, box: Box) {
        val left = box.x - box.width / 2
        val top = box.y - box.height / 2
        val outline = when (box.node.shape) {
            Shape.BOX -> Rectangle2D.Double(left, top, box.width, box.height)
            Shape.CIRCLE, Shape.POINT -> Ellipse2D.Double(left, top, box.width, box.height)
            Shape.DIAMOND -> Path2D.Double().apply {
                moveTo(box.x, top); lineTo(left + box.width, box.y); lineTo(box.x, top + box.height); lineTo(left, box.y); closePath()
            }
            Shape.ROUND -> RoundRectangle2D.Double(left, top, box.width, box.height, 16.0, 16.0)
        }
        g.color = style.line.takeIf { box.node.shape == Shape.POINT } ?: style.fill
        g.fill(outline)
        g.color = style.line
        g.draw(outline)
        g.color = style.foreground
        box.label.draw(g, box.x, box.y)
    }

    /** The label sits where its room was kept: [beside] the target, else above it at [along] (0 to 1) of the way. */
    private fun paintEdge(g: Graphics2D, style: DiagramStyle, edge: DEdge, from: Box, to: Box, label: Label, along: Double, beside: Pair<Double, Double>?) {
        g.color = style.line
        g.stroke = stroke(edge.dashed)
        if (from === to) {
            g.draw(Arc2D.Double(from.x + from.width / 2 - 12, from.y - 14, 28.0, 28.0, -150.0, 300.0, Arc2D.OPEN))
            g.stroke = SOLID
            return
        }
        val (x1, y1) = border(from, to.x, to.y)
        val (x2, y2) = border(to, from.x, from.y)
        g.draw(Line2D.Double(x1, y1, x2, y2))
        g.stroke = SOLID
        if (edge.arrow) arrowHead(g, x1, y1, x2, y2)
        if (edge.label.isEmpty()) return
        val (cx, cy) = beside ?: (to.x to y1 + (y2 - y1) * along)
        g.color = style.background
        g.fill(Rectangle2D.Double(cx - label.width / 2 - 3, cy - label.height / 2 - 1, label.width + 6, label.height + 2))
        g.color = style.foreground
        label.draw(g, cx, cy)
    }

    /** Where the segment from the center of [box] to ([tx], [ty]) leaves its bounding rectangle. */
    private fun border(box: Box, tx: Double, ty: Double): Pair<Double, Double> {
        val dx = tx - box.x
        val dy = ty - box.y
        if (dx == 0.0 && dy == 0.0) return box.x to box.y
        // a division by zero gives infinity, which min() then ignores
        val t = min(box.width / 2 / abs(dx), box.height / 2 / abs(dy))
        return box.x + dx * t to box.y + dy * t
    }

    private fun arrowHead(g: Graphics2D, x1: Double, y1: Double, x2: Double, y2: Double) {
        val angle = atan2(y2 - y1, x2 - x1)
        val head = Path2D.Double().apply {
            moveTo(x2, y2)
            lineTo(x2 - 9 * cos(angle - 0.4), y2 - 9 * sin(angle - 0.4))
            lineTo(x2 - 9 * cos(angle + 0.4), y2 - 9 * sin(angle + 0.4))
            closePath()
        }
        g.fill(head)
    }

    // ---------------------------------------------------------------- sequence

    private fun sequence(diagram: Diagram.Sequence, style: DiagramStyle, metrics: FontMetrics): Scene? {
        if (diagram.actors.isEmpty()) return null
        val index = diagram.actors.withIndex().associate { it.value.id to it.index }
        val heads = diagram.actors.map { Label(it.label, metrics) }
        val texts = diagram.steps.map { Label(text(it), metrics) }
        val messages = diagram.steps.zip(texts).mapNotNull { (step, label) -> (step as? Step.Message)?.let { it to label } }
        fun widestBetween(left: Int) = messages.filter { (m, _) -> setOf(index[m.from], index[m.to]) == setOf(left, left + 1) }.maxOfOrNull { it.second.width } ?: 0.0
        val centers = heads.indices.drop(1).runningFold(MARGIN + heads.first().width / 2 + 14) { x, i ->
            x + maxOf(130.0, (heads[i - 1].width + heads[i].width) / 2 + 48, widestBetween(i - 1) + 36)
        }
        val headHeight = heads.maxOf { it.height } + 16
        val tops = texts.runningFold(MARGIN + headHeight + 14) { y, label -> y + label.height + 26 }
        val selfWidth = messages.filter { (m, _) -> m.from == m.to }.maxOfOrNull { (m, label) -> centers[index.getValue(m.from)] + 40 + label.width } ?: 0.0
        val noteWidth = diagram.steps.zip(texts).filter { it.first is Step.Note }.maxOfOrNull { (step, label) -> noteCenter(step as Step.Note, index, centers) + label.width / 2 + 10 } ?: 0.0
        val width = maxOf(centers.last() + heads.last().width / 2 + 14, selfWidth, noteWidth) + MARGIN
        val bottom = tops.last() + 6
        return Scene(width, bottom + MARGIN) { g ->
            centers.forEachIndexed { i, cx ->
                g.color = style.line
                g.stroke = DASH
                g.draw(Line2D.Double(cx, MARGIN + headHeight, cx, bottom))
                g.stroke = SOLID
                val head = Rectangle2D.Double(cx - heads[i].width / 2 - 14, MARGIN, heads[i].width + 28, headHeight)
                g.color = style.fill
                g.fill(head)
                g.color = style.line
                g.draw(head)
                g.color = style.foreground
                heads[i].draw(g, cx, MARGIN + headHeight / 2)
            }
            diagram.steps.forEachIndexed { i, step -> paintStep(g, style, step, texts[i], tops[i], index, centers, width) }
        }
    }

    private fun text(step: Step): String = when (step) {
        is Step.Divider -> "[${step.text}]"
        is Step.Message -> step.text
        is Step.Note -> step.text
    }

    private fun noteCenter(note: Step.Note, index: Map<String, Int>, centers: List<Double>): Double =
        note.over.mapNotNull { index[it] }.map { centers[it] }.ifEmpty { listOf(centers.first()) }.average()

    private fun paintStep(g: Graphics2D, style: DiagramStyle, step: Step, label: Label, top: Double, index: Map<String, Int>, centers: List<Double>, width: Double) {
        val textY = top + label.height / 2
        val lineY = top + label.height + 6
        when (step) {
            is Step.Divider -> {
                g.color = style.line
                g.stroke = DASH
                g.draw(Line2D.Double(MARGIN / 2, lineY, width - MARGIN / 2, lineY))
                g.stroke = SOLID
                g.color = style.foreground
                label.draw(g, MARGIN + label.width / 2, textY)
            }
            is Step.Message -> paintMessage(g, style, step, label, textY, lineY, centers[index.getValue(step.from)], centers[index.getValue(step.to)])
            is Step.Note -> {
                val cx = noteCenter(step, index, centers)
                val box = Rectangle2D.Double(cx - label.width / 2 - 8, top - 3, label.width + 16, label.height + 8)
                g.color = style.note
                g.fill(box)
                g.color = style.line
                g.draw(box)
                g.color = style.foreground
                label.draw(g, cx, textY + 1)
            }
        }
    }

    private fun paintMessage(g: Graphics2D, style: DiagramStyle, message: Step.Message, label: Label, textY: Double, lineY: Double, from: Double, to: Double) {
        g.color = style.line
        g.stroke = stroke(message.dashed)
        if (from == to) {
            val loop = Path2D.Double().apply { moveTo(from, lineY - 12); lineTo(from + 30, lineY - 12); lineTo(from + 30, lineY); lineTo(from, lineY) }
            g.draw(loop)
            g.stroke = SOLID
            arrowHead(g, from + 30, lineY, from, lineY)
            g.color = style.foreground
            label.draw(g, from + 38 + label.width / 2, textY)
            return
        }
        g.draw(Line2D.Double(from, lineY, to, lineY))
        g.stroke = SOLID
        if (message.arrow) arrowHead(g, from, lineY, to, lineY)
        g.color = style.foreground
        label.draw(g, (from + to) / 2, textY)
    }

    // ---------------------------------------------------------------- pie

    private val PALETTE = listOf(0x4E79A7, 0xF28E2B, 0xE15759, 0x76B7B2, 0x59A14F, 0xEDC948, 0xB07AA1, 0xFF9DA7, 0x9C755F, 0xBAB0AC).map(::Color)

    private fun pie(diagram: Diagram.Pie, style: DiagramStyle, metrics: FontMetrics): Scene? {
        val total = diagram.slices.sumOf { it.second }
        if (total <= 0.0) return null
        val title = Label(diagram.title, metrics)
        val legend = diagram.slices.map { (name, value) -> Label("$name (${(value * 100 / total).roundToInt()}%)", metrics) }
        val top = MARGIN + (title.height + 10).takeIf { diagram.title.isNotEmpty() }.let { it ?: 0.0 }
        val diameter = max(160.0, legend.sumOf { it.height + 6 })
        val legendX = MARGIN + diameter + 24
        val width = max(legendX + 20 + (legend.maxOfOrNull { it.width } ?: 0.0), MARGIN + title.width) + MARGIN
        val starts = diagram.slices.runningFold(90.0) { angle, (_, value) -> angle - value * 360 / total }
        return Scene(width, top + diameter + MARGIN) { g ->
            g.color = style.foreground
            if (diagram.title.isNotEmpty()) title.draw(g, MARGIN + title.width / 2, MARGIN + title.height / 2)
            diagram.slices.forEachIndexed { i, (_, value) ->
                g.color = PALETTE[i % PALETTE.size]
                g.fill(Arc2D.Double(MARGIN, top, diameter, diameter, starts[i], -value * 360 / total, Arc2D.PIE))
                val rowY = top + i * (legend[i].height + 6)
                g.fill(Rectangle2D.Double(legendX, rowY + 2, 12.0, 12.0))
                g.color = style.foreground
                legend[i].draw(g, legendX + 20 + legend[i].width / 2, rowY + legend[i].height / 2)
            }
        }
    }
}
