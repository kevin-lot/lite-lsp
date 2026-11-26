package me.kevinlot.lite.formats.infra

private const val NAME = """[A-Za-z_][\w-]*"""

private val ATTRIBUTE = Regex("""^[ \t]*($NAME)[ \t]*=(?![=>])""", RegexOption.MULTILINE)
private val BLOCK_HEADER = Regex("""^[ \t]*($NAME)((?:[ \t]+(?:"[^"\n]*"|$NAME))*)[ \t]*\{""", RegexOption.MULTILINE)
private val DYNAMIC = Regex("""^[ \t]*dynamic[ \t]+"($NAME)"[ \t]*\{""", RegexOption.MULTILINE)
private val FOR = Regex("""[\[{]\s*for\s+($NAME)(?:\s*,\s*($NAME))?\s+in\b""")
// A name that is read: not after a `.`, and not the key of `name = …`.
private val IDENTIFIER = Regex("""(?<![\w.-])([A-Za-z_][\w-]*+)(?![ \t]*=(?![=>]))""")
private val ITERATOR = Regex("""iterator[ \t]*=[ \t]*($NAME)""")
private val KEY = Regex("""(?:^|[{,])[ \t]*($NAME)[ \t]*=(?![=>])""", RegexOption.MULTILINE)
private val LABEL = Regex(""""([^"\n]*)"|($NAME)""")
private val OBJECT_VALUE = Regex("""$NAME[ \t]*=[ \t]*(?:object\([ \t]*)?\{""")

// `labels` hold each label's text and where it starts; `body` runs from `{` to its `}`.
data class HclBlock(val body: IntRange, val labels: List<Pair<String, Int>>, val type: String, val typeOffset: Int)

data class HclAttribute(val name: String, val offset: Int)

// A name that exists in `scope` only: the variable of a `for`, the iterator of a `dynamic` block.
data class HclIterator(val name: String, val offset: Int, val scope: IntRange)

class HclFile(val text: CharSequence) {
    val mask: BooleanArray = hclCodeMask(text)
    private val depths: IntArray = IntArray(text.length + 1).also { depths ->
        text.indices.forEach { at -> depths[at + 1] = depths[at] + (delta(text[at]).takeIf { mask[at] } ?: 0) }
    }
    val blocks: List<HclBlock> = topLevelBlocks()

    fun attributes(range: IntRange): List<HclAttribute> {
        val base = depthAt(range.first)
        return ATTRIBUTE.findAll(text, range.first)
            .mapNotNull { it.groups[1] }
            .takeWhile { it.range.first <= range.last }
            .filter { mask[it.range.first] && depthAt(it.range.first) == base }
            .map { HclAttribute(name = it.value, offset = it.range.first) }
            .toList()
    }

    fun blockAt(offset: Int): HclBlock? = blocks.firstOrNull { offset in it.typeOffset..it.body.last }

    // From the attribute name to the end of its value: the closing bracket of what it opens, or the line.
    fun valueRange(attribute: HclAttribute): IntRange =
        attribute.offset..(opener(attribute)?.let(::closing) ?: (lineEnd(attribute.offset, text) - 1))

    // The keys of the object an attribute holds, an `object({ … })` type included: `inputs = { a = 1 }` gives `a`.
    fun entries(attribute: HclAttribute): List<HclAttribute> =
        OBJECT_VALUE.matchAt(text, attribute.offset)?.let { keys(it.range.last) }.orEmpty()

    // The keys between the brace at `open` and its closing one, also when they share a line: `{ a = 1, b = 2 }`.
    fun keys(open: Int): List<HclAttribute> {
        val close = closing(open)
        return KEY.findAll(text, open)
            .mapNotNull { it.groups[1] }
            .takeWhile { it.range.first < close }
            .filter { mask[it.range.first] && depthAt(it.range.first) == depthAt(open) + 1 }
            .map { HclAttribute(name = it.value, offset = it.range.first) }
            .toList()
    }

    fun iterators(): List<HclIterator> {
        val loops = FOR.findAll(text).filter { mask[it.range.first] }.flatMap { match ->
            val scope = match.range.first..closing(match.range.first)
            match.groups.drop(1).filterNotNull().map { HclIterator(name = it.value, offset = it.range.first, scope = scope) }
        }
        val dynamics = DYNAMIC.findAll(text).filter { mask[it.range.last] }.mapNotNull { match ->
            val body = match.range.last..closing(match.range.last)
            val renamed = attributes(body.first + 1..body.last).firstOrNull { it.name == "iterator" }?.let { ITERATOR.matchAt(text, it.offset) }
            (renamed ?: match).groups[1]?.let { HclIterator(name = it.value, offset = it.range.first, scope = body) }
        }
        return (loops + dynamics).toList()
    }

    // Where each iterator is read, with the innermost one of that name around the place.
    fun iteratorUses(): List<Pair<Int, HclIterator>> {
        val iterators = iterators()
        if (iterators.isEmpty()) return emptyList()
        val byName = iterators.groupBy { it.name }
        val declared = iterators.mapTo(HashSet()) { it.offset }
        return IDENTIFIER.findAll(text)
            .mapNotNull { it.groups[1] }
            .filter { mask[it.range.first] && it.range.first !in declared }
            .mapNotNull { use ->
                val around = byName[use.value].orEmpty().filter { use.range.first in it.scope }
                around.minByOrNull { it.scope.last - it.scope.first }?.let { use.range.first to it }
            }
            .toList()
    }

    private fun opener(attribute: HclAttribute): Int? =
        (attribute.offset until lineEnd(attribute.offset, text)).firstOrNull { mask[it] && text[it] in "{[(" }

    fun stringValue(block: HclBlock, name: String): String? {
        val attribute = attributes(block.body.first + 1..block.body.last).firstOrNull { it.name == name } ?: return null
        return text.subSequence(attribute.offset, lineEnd(attribute.offset, text)).toString().substringAfter('=').trim()
    }

    private fun closing(open: Int): Int =
        (open until text.length).asSequence()
            .filter { mask[it] && text[it] in "{}[]()" }
            .runningFold(-1 to 0) { (_, depth), at -> at to depth + delta(text[at]) }
            .drop(1)
            .firstOrNull { it.second == 0 }
            ?.first ?: (text.length - 1)

    private fun depthAt(offset: Int): Int = depths[offset]

    private fun topLevelBlocks(): List<HclBlock> = buildList {
        BLOCK_HEADER.findAll(text).forEach { match ->
            val type = match.groups[1]?.takeIf { mask[it.range.first] } ?: return@forEach
            if (lastOrNull()?.let { type.range.first <= it.body.last } == true) return@forEach
            val labels = match.groups[2]?.let { group ->
                LABEL.findAll(group.value)
                    .mapNotNull { label -> (label.groups[1] ?: label.groups[2])?.let { it.value to group.range.first + it.range.first } }
                    .toList()
            }.orEmpty()
            add(HclBlock(body = match.range.last..closing(match.range.last), labels = labels, type = type.value, typeOffset = type.range.first))
        }
    }

    private fun delta(char: Char): Int = when (char) {
        '{', '[', '(' -> 1
        '}', ']', ')' -> -1
        else -> 0
    }
}
