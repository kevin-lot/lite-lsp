package me.kevinlot.lite.formats.diagram

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Fmt
import me.kevinlot.lite.formats.FmtToken
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.PathLink
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule

/**
 * D2 diagrams: a shape → where it is first named, each level of `a.b.c` on its own; `class: x` → the class of that
 * name in a `classes` block; `${x}` → the variable of a `vars` block; `...@file` → the imported file, whose classes,
 * variables and shapes are shared.
 */
object D2 : Format("D2", "D2", "d2") {
    override val blockComment = "\"\"\"" to "\"\"\""
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.KEY, Cat.META, Cat.STRING, Cat.VARIABLE)

    /** The reserved keywords of d2 (`d2ast/keywords.go`): never the name of a shape. */
    private val RESERVED = setOf(
        "3d", "animated", "bold", "border-radius", "class", "classes", "constraint", "direction", "double-border", "fill", "fill-pattern", "filled",
        "font", "font-color", "font-size", "grid-columns", "grid-gap", "grid-rows", "height", "horizontal-gap", "icon", "italic", "label", "layers",
        "left", "link", "multiple", "near", "opacity", "scenarios", "shadow", "shape", "source-arrowhead", "steps", "stroke", "stroke-dash",
        "stroke-width", "style", "target-arrowhead", "text-transform", "tooltip", "top", "underline", "vars", "vertical-gap", "width",
    )
    private val BOARDS = setOf("layers", "scenarios", "steps")
    private const val MAX_IMPORTS = 30
    // `)` ends a key before the index of a connection: `(a -> b)[0]`
    private const val SEGMENT_CHAR = """(?:[^\s.:;{}\[\]|#"'<>)-]|-(?![->])|\)(?!\[))"""
    private const val VALUE_END = """(?=[ \t]*(?:[;}\n]|\z))"""
    private val SUBSTITUTION = Regex("""\$\{([^}\n]+)}""")

    override val rules = listOf(
        Rule("\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)", Cat.COMMENT),
        Rule(R.HASH, Cat.COMMENT),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""\|{3}[\s\S]*?(?:\|{3}|\z)|\|{2}[\s\S]*?(?:\|{2}|\z)|\|[\s\S]*?(?:\||\z)""", Cat.STRING),
        Rule("""(?:\.{3})?@[^\s;{}\[\],]+""", Cat.META),
        Rule("""\$\{[^}\n]*}""", Cat.VARIABLE),
        Rule("""<->|<-|->|--""", Cat.OPERATOR),
        Rule("""(?<=:[ \t]{0,8})(?:false|null|true)$VALUE_END""", Cat.CONSTANT),
        Rule("""(?<=:[ \t]{0,8})-?\d+(?:\.\d+)?$VALUE_END""", Cat.NUMBER),
        Rule("""(?<![\w-])(?:${RESERVED.sortedByDescending { it.length }.joinToString("|")})(?=[ \t]*[:.{])""", Cat.KEYWORD),
        Rule("""(?<=:[ \t]{0,8})[^\s;{}#|"'\[\]@$][^\n;{}#|\[\]$]*?(?=[ \t]*(?:[;{}\n]|\z|[ \t]#)|\$\{)""", Cat.STRING),
        Rule("""(?:[^\s.:;{}\[\]|#"'()@<>-]|-(?![->]))(?:[ \t]?$SEGMENT_CHAR)*+""", Cat.KEY),
    )

    private enum class Kind { CLASSES, IGNORED, SHAPES, VARIABLES }
    private class Frame(val kind: Kind, val path: List<String>)
    private class Token(val cat: Cat?, val range: TextRange, val text: String, val firstOfLine: Boolean) {
        val isName: Boolean get() = cat == Cat.KEY || cat == Cat.KEYWORD || cat == Cat.STRING
        val isQuoted: Boolean get() = text.length >= 2 && (text.first() == '"' || text.first() == '\'')
        val name: String get() = text.takeUnless { isQuoted } ?: text.substring(1, text.length - 1)
        val nameRange: TextRange get() = range.takeUnless { isQuoted } ?: TextRange(range.startOffset + 1, range.endOffset - 1)
        fun punct(c: String) = cat != Cat.STRING && cat != Cat.COMMENT && text == c
    }

    /** Names in the order they are written: the first one of a key declares it, the others refer to it. */
    private class Names {
        val writes = mutableListOf<Pair<TextRange, String>>()
        val refs = mutableListOf<Ref>()
        fun write(range: TextRange, key: String) { writes += range to key }
        fun links(paths: List<PathLink>): Links {
            val firsts = writes.distinctBy { it.second }.toSet()
            return Links(
                decls = firsts.map { (range, key) -> Decl(range, key) },
                paths = paths,
                refs = writes.filterNot { it in firsts }.map { (range, key) -> Ref(range, listOf(key)) } + refs,
            )
        }
    }

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val tokens = tokens(text)
            val names = Names()
            val frames = ArrayDeque(listOf(Frame(Kind.SHAPES, emptyList())))
            var at = 0
            while (at < tokens.size) {
                if (tokens[at].punct("}")) {
                    if (frames.size > 1) frames.removeLast()
                    at++
                    continue
                }
                val end = statementEnd(tokens, at)
                val terminator = tokens.getOrNull(end)
                val opens = terminator?.punct("{") == true
                val opened = statement(tokens.subList(at, end), frames.last(), names)
                if (opens) frames.addLast(opened)
                at = end.takeUnless { opens || terminator?.punct(";") == true } ?: (end + 1)
            }
            val imports = tokens.filter { it.cat == Cat.META }.map { token ->
                val path = token.text.substringAfter('@')
                PathLink(TextRange(token.range.endOffset - path.length, token.range.endOffset), path)
            }
            val substitutions = tokens.filter { it.cat != Cat.COMMENT }.flatMap { token ->
                SUBSTITUTION.findAll(token.text).mapNotNull { it.groups[1] }.map { name ->
                    Ref(TextRange(token.range.startOffset + name.range.first, token.range.startOffset + name.range.last + 1), listOf("var:${name.value}"))
                }
            }
            names.refs += substitutions
            return names.links(imports)
        }

        /** The file and what it imports, followed through the imports of the imports. */
        override fun scope(file: PsiFile): List<PsiFile> {
            val seen = linkedSetOf(file)
            val pending = ArrayDeque(listOf(file))
            while (pending.isNotEmpty() && seen.size < MAX_IMPORTS) {
                val current = pending.removeFirst()
                Fmt.links(current).paths.mapNotNull { resolvePath(current, it.path) as? PsiFile }.filter { seen.add(it) }.forEach { pending.addLast(it) }
            }
            return seen.toList()
        }

        /** An import names its file without the extension. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? =
            super.resolvePath(file, path)?.takeIf { it is PsiFile } ?: super.resolvePath(file, "$path.$extension")

        override fun renamedPath(old: String, newName: String): String =
            super.renamedPath(old, newName.takeIf { old.endsWith(".$extension") } ?: newName.removeSuffix(".$extension"))
    }

    private fun tokens(text: CharSequence): List<Token> {
        val lexer = lexer()
        lexer.start(text)
        val out = mutableListOf<Token>()
        var previousEnd = 0
        while (lexer.tokenType != null) {
            val cat = (lexer.tokenType as? FmtToken)?.cat
            if (cat != null && cat != Cat.COMMENT) {
                val newline = (previousEnd until lexer.tokenStart).any { text[it] == '\n' }
                out += Token(cat, TextRange(lexer.tokenStart, lexer.tokenEnd), text.substring(lexer.tokenStart, lexer.tokenEnd), newline || out.isEmpty())
                previousEnd = lexer.tokenEnd
            }
            lexer.advance()
        }
        return out
    }

    /** The index of the token after the statement starting at [from]: a `{`, a `}`, a `;`, or the first token of the next line. */
    private fun statementEnd(tokens: List<Token>, from: Int): Int {
        var depth = 0
        for (index in from until tokens.size) {
            val token = tokens[index]
            if (depth <= 0 && index > from && token.firstOfLine) return index
            if (token.punct("[")) depth++
            if (token.punct("]")) depth--
            if (depth <= 0 && (token.punct("{") || token.punct(";") || token.punct("}"))) return index
        }
        return tokens.size
    }

    /** Reads one statement in [frame] and returns the frame of the block it would open. */
    private fun statement(all: List<Token>, frame: Frame, names: Names): Frame {
        // `(a -> b)[0].style…` names an existing connection: only its two ends are shapes
        val edge = all.firstOrNull()?.punct("(") == true
        val tokens = all.takeUnless { edge } ?: all.drop(1).takeWhile { !it.punct(")") }
        val colon = tokens.indexOfFirst { it.punct(":") }
        val head = tokens.take(colon.takeIf { it >= 0 } ?: tokens.size)
        val value = tokens.drop(head.size + 1)
        val paths = head.fold(mutableListOf(mutableListOf<Token>())) { acc, token ->
            if (token.cat == Cat.OPERATOR) acc += mutableListOf<Token>()
            if (token.isName) acc.last() += token
            acc
        }.filter { it.isNotEmpty() }
        val first = paths.firstOrNull().orEmpty()
        // a class may be given anywhere, also in the block of a connection
        if (first.singleOrNull()?.name == "class") value.filter { it.isName }.forEach { names.refs += Ref(it.nameRange, listOf("class:${it.name.trim()}")) }
        return when (frame.kind) {
            Kind.IGNORED -> Frame(Kind.IGNORED, frame.path)
            Kind.CLASSES -> {
                first.firstOrNull()?.let { names.write(it.nameRange, "class:${it.name}") }
                Frame(Kind.IGNORED, frame.path)
            }
            Kind.VARIABLES -> {
                val path = frame.path + first.map { it.name }
                first.lastOrNull()?.takeIf { "d2-config" !in path }?.let { names.write(it.nameRange, "var:${path.joinToString(".")}") }
                Frame(Kind.VARIABLES.takeUnless { "d2-config" in path } ?: Kind.IGNORED, path)
            }
            Kind.SHAPES -> {
                val opened = paths.map { shapes(it, value, frame, names) }
                opened.singleOrNull()?.takeUnless { edge } ?: Frame(Kind.IGNORED, frame.path)
            }
        }
    }

    /** Declares or uses each level of a shape path; returns the frame a block opened on that path would have. */
    private fun shapes(path: List<Token>, value: List<Token>, frame: Frame, names: Names): Frame {
        val reserved = path.indexOfFirst { !it.isQuoted && it.name in RESERVED && it.name !in BOARDS }.takeIf { it >= 0 } ?: path.size
        val shape = path.take(reserved)
        val keyword = path.getOrNull(reserved)?.name
        val nested = path.drop(reserved + 1)
        if (keyword == "class" && path.size > 1) value.filter { it.isName }.forEach { names.refs += Ref(it.nameRange, listOf("class:${it.name.trim()}")) }
        if (keyword == "classes") nested.firstOrNull()?.let { names.write(it.nameRange, "class:${it.name}") }
        if (keyword == "vars" && nested.isNotEmpty()) names.write(nested.last().nameRange, "var:${nested.joinToString(".") { it.name }}")

        val absolute = shape.fold(frame.path) { parent, token -> when (token.name) { "_" -> parent.dropLast(1); else -> parent + token.name } }
        if (shape.none { it.name.contains('*') || it.name.startsWith("&") || it.name.startsWith("!") }) {
            val base = absolute.size - shape.count { it.name != "_" }
            shape.filter { it.name != "_" }.forEachIndexed { index, token ->
                names.write(token.nameRange, "shape:${absolute.take(base + index + 1).joinToString(".")}")
            }
        }
        return when {
            keyword == "classes" && nested.isEmpty() -> Frame(Kind.CLASSES, absolute)
            keyword == "vars" -> Frame(Kind.VARIABLES, nested.map { it.name })
            keyword != null -> Frame(Kind.IGNORED, absolute)
            else -> Frame(Kind.SHAPES, absolute)
        }
    }
}
