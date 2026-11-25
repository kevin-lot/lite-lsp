package me.kevinlot.lite.formats

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import java.util.TreeMap
import java.util.regex.Pattern

/**
 * Jinja2 templates: a name → its `set`, `with` or loop variable, macro or `call` parameter, or `macro`; `m.name` → the
 * name in the template imported as `m`; a block → the block of the extended template; `include`, `import`, `from` and
 * `extends` → the template file. Reads the default delimiters (`{% %}`, `{{ }}`, `{# #}`) or, when the file uses them,
 * the `<% %>`, `<< >>`, `<# #>` set chosen where `{{ }}` belongs to the generated text (GitHub Actions workflows).
 */
object Jinja : Format("Jinja", "Jinja2", "j2") {
    override val blockComment = "{#" to "#}"
    override val linker: Linker get() = JinjaLinker
    override val nameCategories = setOf(Cat.STRING, Cat.VARIABLE)
    override val rules = emptyList<Rule>()
    override fun lexer(): FmtLexer = JinjaLexer(this, host = null)

    val EXTENSIONS = listOf("j2", "jinja", "jinja2")
    val KEYWORDS = listOf(
        "and", "as", "autoescape", "block", "call", "context", "elif", "else", "endautoescape", "endblock", "endcall", "endfilter", "endfor", "endif",
        "endmacro", "endraw", "endset", "endwith", "extends", "filter", "for", "from", "if", "ignore", "import", "in", "include", "is", "macro",
        "missing", "not", "or", "raw", "recursive", "required", "scoped", "set", "with", "without",
    )

    /** The format of the generated text, from the name under the template extension (`release.yml.j2` is YAML). */
    fun hostOf(fileName: String?): Format? {
        val inner = fileName?.substringBeforeLast('.', "")?.takeIf { '.' in it } ?: return null
        return (FileTypeManager.getInstance().getFileTypeByFileName(inner) as? FmtFileType)?.format?.takeIf { it != Jinja }
    }
}

/** A tag with its delimiters: [kind] is `%` (statement), `{` (expression) or `#` (comment). */
class JinjaTag(val range: IntRange, val kind: Char, val body: IntRange)

object JinjaTags {
    /** A delimiter set: where a tag may open, the character its closing delimiters end with, the tag ending a raw block. */
    private class Delimiters(val closer: Char, val variableOnOneLine: Boolean, open: String, endRaw: String) {
        val endRaw = Regex(endRaw)
        val open = Regex(open)
    }

    private const val CLOSERS = ")]}"
    private val CUSTOM = Delimiters('>', variableOnOneLine = true, open = """<[#%<]""", endRaw = """<%[-+]?\s*endraw\s*[-+]?%>""")
    private val CUSTOM_STATEMENT = Regex("""<%[-+]?\s*(?:end\w+|block|call|extends|filter|for|from|if|import|include|macro|raw|set|with)\b""")
    private val CUSTOM_VARIABLE = Regex("""<<[ \t]*[\w('"][^\n]*?>>""")
    // `${{ … }}` is a GitHub Actions expression, never a Jinja one
    private val DEFAULT = Delimiters('}', variableOnOneLine = false, open = """\{[#%]|(?<!\$)\{\{""", endRaw = """\{%[-+]?\s*endraw\s*[-+]?%}""")
    private val DEFAULT_STATEMENT = Regex("""\{%[-+]?\s*\w""")
    private const val OPENERS = "([{"
    private val RAW = Regex("""\s*raw\s*""")
    private val STRING: Pattern = Pattern.compile(""""[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"|'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'""")
    private const val TRIM = "-+"

    /** The tags of [text]; what a `raw` block holds is text. */
    fun of(text: CharSequence): List<JinjaTag> {
        val delimiters = when {
            CUSTOM_STATEMENT.containsMatchIn(text) -> CUSTOM
            CUSTOM_VARIABLE.containsMatchIn(text) && !DEFAULT_STATEMENT.containsMatchIn(text) -> CUSTOM
            else -> DEFAULT
        }
        val tags = mutableListOf<JinjaTag>()
        var from = 0
        while (true) {
            val open = delimiters.open.find(text, from)?.range?.first ?: break
            val tag = tagAt(text, open, delimiters)
            from = when {
                tag == null -> open + 1
                opensRaw(tag, text) -> delimiters.endRaw.find(text, tag.range.last + 1)?.range?.first ?: text.length
                else -> tag.range.last + 1
            }
            tag?.let { tags += it }
        }
        return tags
    }

    private fun opensRaw(tag: JinjaTag, text: CharSequence): Boolean = tag.kind == '%' && RAW.matches(text.subSequence(tag.body.first, tag.body.last + 1))

    private fun tagAt(text: CharSequence, start: Int, delimiters: Delimiters): JinjaTag? {
        val kind = when (val c = text[start + 1]) {
            '#', '%' -> c
            else -> '{'
        }
        val close = when (kind) {
            '{' -> "${delimiters.closer}${delimiters.closer}"
            else -> "$kind${delimiters.closer}"
        }
        val limit = when {
            kind == '{' && delimiters.variableOnOneLine -> text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
            else -> text.length
        }
        val end = when (kind) {
            '#' -> text.indexOf(close, start + 2).takeIf { it >= 0 }
            else -> closeOf(text, start + 2, limit, close)
        } ?: return null
        return tag(text, start..end + 1, kind)
    }

    /** Where [close] ends a tag: outside its strings and brackets when they are balanced, else at its first occurrence. */
    private fun closeOf(text: CharSequence, from: Int, limit: Int, close: String): Int? {
        val string = STRING.matcher(text)
        val expected = ArrayDeque<Char>()
        var i = from
        while (i < limit) {
            val c = text[i]
            if (expected.isEmpty() && text.startsWith(close, i)) return i
            when {
                (c == '"' || c == '\'') && string.region(i, limit).lookingAt() -> i = string.end() - 1
                c in OPENERS -> expected.addLast(CLOSERS[OPENERS.indexOf(c)])
                c in CLOSERS && expected.removeLastOrNull() != c -> break
            }
            i++
        }
        return text.indexOf(close, from).takeIf { it in from..limit - close.length }
    }

    private fun tag(text: CharSequence, range: IntRange, kind: Char): JinjaTag {
        val open = 2 + trimLength(text[range.first + 2])
        val close = 2 + trimLength(text[range.last - 2])
        val bodyStart = minOf(range.first + open, range.last + 1)
        return JinjaTag(range, kind, bodyStart..maxOf(bodyStart - 1, range.last - close))
    }

    private fun trimLength(c: Char): Int {
        if (c in TRIM) return 1
        return 0
    }
}

/** Tags are found once per buffer, so a restart at any offset knows whether it is inside one. */
class JinjaLexer(format: Format, private val host: Format?) : FmtLexer(format) {
    private var hostRules: List<BoundRule> = emptyList()
    private var tagRules: List<BoundRule> = emptyList()
    private var tags = TreeMap<Int, JinjaTag>()

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        hostRules = host?.rules.orEmpty().map { BoundRule(it, buffer) }
        tagRules = TAG_RULES.map { BoundRule(it, buffer) }
        tags = JinjaTags.of(buffer).associateByTo(TreeMap()) { it.range.first }
        super.start(buffer, startOffset, endOffset, initialState)
    }

    override fun next(): IElementType {
        val c = buf[pos]
        if (c.isWhitespace()) {
            while (pos < end && buf[pos].isWhitespace()) pos++
            return TokenType.WHITE_SPACE
        }
        val tag = tags.floorEntry(pos)?.value?.takeIf { pos <= it.range.last } ?: return token(textToken(c))
        return token(tagToken(tag, c))
    }

    private fun tagToken(tag: JinjaTag, c: Char): Cat {
        val tagEnd = minOf(tag.range.last + 1, end)
        if (tag.kind == '#') { pos = tagEnd; return Cat.COMMENT }
        if (pos < tag.body.first) { pos = minOf(tag.body.first, end); return Cat.LBRACE }
        if (pos > tag.body.last) { pos = tagEnd; return Cat.RBRACE }
        matchUntil(tagRules, minOf(tag.body.last + 1, end), lineStart = false)?.let { return it }
        pos++
        return when (c) {
            '(' -> Cat.LPAREN
            ')' -> Cat.RPAREN
            '[' -> Cat.LBRACKET
            ']' -> Cat.RBRACKET
            '!', '%', '*', '+', '-', '/', '<', '=', '>', '|', '~' -> Cat.OPERATOR
            else -> Cat.PUNCT
        }
    }

    /** The generated text, colored with the rules of its own format up to the next tag. */
    private fun textToken(c: Char): Cat {
        val limit = minOf(tags.ceilingKey(pos) ?: end, end)
        matchUntil(hostRules, limit, onlyIndentBefore())?.let { return it }
        if (host != null && (c.isLetterOrDigit() || c == '_')) {
            while (pos < limit && (buf[pos].isLetterOrDigit() || buf[pos] == '_' || buf[pos] == '-')) pos++
            return Cat.WORD
        }
        pos++
        if (host != null) return Cat.PUNCT
        while (pos < limit && !buf[pos].isWhitespace()) pos++
        return Cat.PUNCT
    }

    private fun matchUntil(rules: List<BoundRule>, limit: Int, lineStart: Boolean): Cat? {
        for (bound in rules) {
            if (bound.rule.lineStart && !lineStart) continue
            val m = bound.matcher.region(pos, limit)
            if (m.lookingAt() && m.end() > pos) { pos = m.end(); return bound.rule.cat }
        }
        return null
    }

    private companion object {
        val TAG_RULES = listOf(
            Rule(""""[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"?|'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'?""", Cat.STRING),
            Rule("""(?:0[bB][01_]+|0[oO][0-7_]+|0[xX][\da-fA-F_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][-+]?\d[\d_]*)?)(?!\w)""", Cat.NUMBER),
            Rule("""(?<![\w.])(?:False|None|True|false|none|true)(?!\w)""", Cat.CONSTANT),
            Rule("""(?<![\w.])(?:${Jinja.KEYWORDS.joinToString("|")})(?!\w)""", Cat.KEYWORD),
            Rule("""(?<=\|[ \t]{0,3})[A-Za-z_]\w*""", Cat.FUNCTION),
            Rule("""[A-Za-z_]\w*""", Cat.VARIABLE),
        )
    }
}

/** Colors the text around the tags as the format the template generates. */
class JinjaHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer(): Lexer = JinjaLexer(Jinja, Jinja.hostOf(virtualFile?.name))
        override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack((t as? FmtToken)?.cat?.key)
    }
}

object JinjaLinker : Linker() {
    private val BLOCK = Regex("""^\s*(?:block|endblock)\s+([A-Za-z_]\w*)""")
    private const val BLOCK_KEY = "block:"
    private val CALL = Regex("""^\s*call\s*\(([^)]*)\)""")
    private const val CLOSERS = ")]}"
    private val CONSTANTS = setOf("False", "None", "True", "false", "none", "true")
    private val FOR = Regex("""^\s*for\s+([\w\s,()]+?)\s+in\b""")
    private val FROM = Regex("""^\s*from\s.+?\simport\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)
    private val IMPORT_AS = Regex("""^\s*import\s.+?\sas\s+([A-Za-z_]\w*)""", RegexOption.DOT_MATCHES_ALL)
    private val IMPORTED = Regex("""(?<![\w.])([A-Za-z_]\w*)(?:\s+as\s+([A-Za-z_]\w*))?""")
    private val MACRO = Regex("""^\s*macro\s+(\w+)\s*\(([\s\S]*)\)\s*$""")
    private const val MAX_LINEAGE = 16
    private val MEMBER = Regex("""(?<![\w.])([A-Za-z_]\w*)\s*\.\s*([A-Za-z_]\w*)""")
    private const val MEMBER_KEY = "member:"
    private val NAME = Regex("""(?<![\w.])(?<!\|[ \t]{0,3})[A-Za-z_]\w*+(?!\s*=(?!=))""")
    private const val OPENERS = "([{"
    private val PARAMETER = Regex("""(?:^|,)\s*([A-Za-z_]\w*)""")
    private val PATH = Regex("""^\s*(?:extends|from|import|include)\s+(?:"([^"\n]+)"|'([^'\n]+)')""")
    private val QUOTED = Regex(""""[^"\n]*"|'[^'\n]*'""")
    private val RESERVED = Jinja.KEYWORDS.toSet() + CONSTANTS
    private val SCOPES = setOf("call", "for", "macro", "with")
    private val SET = Regex("""^\s*set\s+([\w\s,]+?)\s*(?:=(?!=)|$)""")
    private val STATEMENT = Regex("""^\s*(\w+)""")
    private val WITH = Regex("""(?:^\s*with\s|,)\s*([A-Za-z_]\w*)\s*=(?!=)""")
    private val WORD = Regex("""[A-Za-z_]\w*""")

    /** A statement or expression tag, its text and the same text with the strings blanked. */
    private class Tag(val tag: JinjaTag, text: CharSequence) {
        val body: String = text.subSequence(tag.body.first, tag.body.last + 1).toString()
        val code: String = QUOTED.replace(body) { " ".repeat(it.value.length) }
        val path: MatchGroup? get() = PATH.find(body)?.groups?.drop(1)?.firstNotNullOfOrNull { it }
        val start: Int get() = tag.range.first
        val statement: String? = STATEMENT.find(code)?.groupValues?.get(1)?.takeIf { tag.kind == '%' }

        /** [code] with what lies deeper than [depth] brackets blanked: the commas left separate the tag's own items. */
        fun shallow(depth: Int): String {
            var level = 0
            val kept = CharArray(code.length) { i ->
                val c = code[i]
                if (c in CLOSERS) level--
                val visible = level <= depth
                if (c in OPENERS) level++
                c.takeIf { visible } ?: ' '
            }
            return String(kept)
        }

        fun range(group: MatchGroup): TextRange = TextRange(tag.body.first + group.range.first, tag.body.first + group.range.last + 1)
        fun words(group: MatchGroup): List<Pair<TextRange, String>> = WORD.findAll(group.value).map { word ->
            val start = tag.body.first + group.range.first + word.range.first
            TextRange(start, start + word.value.length) to word.value
        }.toList()
    }

    /** A `call`, `for`, `macro` or `with` block: its opening tag and where the names it declares stop being visible. */
    private class Block(val open: Tag, val end: Int) {
        val isMacro: Boolean get() = open.statement == "macro"
        operator fun contains(offset: Int): Boolean = offset > open.tag.range.last && offset < end
    }

    /** A place a name is written, and the key of the scope it belongs to. */
    private class Write(val range: TextRange, val key: String)

    override fun links(text: CharSequence, fileName: String): Links {
        val tags = statements(text)
        val blocks = blocks(tags, text.length)
        val macros = tags.filter { it.statement == "macro" }.mapNotNull { tag -> MACRO.find(tag.code)?.groups?.get(1)?.let { tag.range(it) to it.value } }
        val imports = tags.filter { it.statement == "from" }.flatMap { imported(it) }
        // a block of a template that extends another one belongs to that one
        val extends = tags.any { it.statement == "extends" }
        val (inherited, own) = tags.mapNotNull { templateBlock(it) }.partition { extends }

        val writes = tags.flatMap { writes(it, blocks) } + imports.mapNotNull { it.alias }.map { (range, name) -> Write(range, "var:$name") } + own
        val ordered = writes.sortedBy { it.range.startOffset }
        val firsts = ordered.distinctBy { it.key }.toSet()
        val declared = firsts.map { it.key }.toSet()
        val taken = ((ordered + inherited).map { it.range.startOffset } + macros.map { it.first.startOffset } + imports.map { it.name.first.startOffset }).toSet()

        val uses = tags.flatMap { tag ->
            NAME.findAll(tag.code).filter { it.value !in RESERVED && tag.tag.body.first + it.range.first !in taken }.map { name ->
                val offset = tag.tag.body.first + name.range.first
                Ref(TextRange(offset, offset + name.value.length), keys(name.value, offset, blocks, declared))
            }
        }
        return Links(
            decls = firsts.map { Decl(it.range, it.key) } + macros.map { (range, name) -> Decl(range, "macro:$name") },
            paths = tags.mapNotNull { tag -> tag.path?.let { PathLink(tag.range(it), it.value) } },
            refs = (ordered.filterNot { it in firsts } + inherited).map { Ref(it.range, listOf(it.key)) } +
                imports.map { Ref(it.name.first, listOf("macro:${it.name.second}", "var:${it.name.second}")) } + members(tags) + uses,
        )
    }

    override fun resolve(file: PsiFile, ref: Ref): List<PsiElement> = ref.keys.firstNotNullOfOrNull { declarations(file, it) }.orEmpty()

    /** A scoped key means nothing in another file; the other names are shared with the imported and included templates. */
    private fun declarations(file: PsiFile, key: String): List<PsiElement>? = when {
        key.startsWith(MEMBER_KEY) -> memberDeclarations(file, key.removePrefix(MEMBER_KEY))
        key.startsWith(BLOCK_KEY) -> lineage(file).firstNotNullOfOrNull { declarationsIn(listOf(it), key) }
        '@' in key -> declarationsIn(listOf(file), key)
        else -> declarationsIn(Fmt.scope(file), key)
    }

    private fun declarationsIn(files: List<PsiFile>, key: String): List<PsiElement>? = Fmt.declarations(files, Jinja, key).takeIf { it.isNotEmpty() }

    /** [member] is `name@path`: a macro or a variable of the template imported from that path. */
    private fun memberDeclarations(file: PsiFile, member: String): List<PsiElement>? {
        val template = resolvePath(file, member.substringAfter('@')) as? PsiFile ?: return null
        val name = member.substringBefore('@')
        return listOf("macro:$name", "var:$name").firstNotNullOfOrNull { declarationsIn(listOf(template), it) }
    }

    /** The template and the ones it extends, nearest first. */
    private fun lineage(file: PsiFile): List<PsiFile> = generateSequence(file) { extended(it) }.take(MAX_LINEAGE).distinct().toList()

    private fun extended(file: PsiFile): PsiFile? {
        val path = statements(file.text).firstOrNull { it.statement == "extends" }?.path ?: return null
        return resolvePath(file, path.value) as? PsiFile
    }

    private fun statements(text: CharSequence): List<Tag> = JinjaTags.of(text).filter { it.kind != '#' }.map { Tag(it, text) }

    private fun templateBlock(tag: Tag): Write? {
        if (tag.statement != "block" && tag.statement != "endblock") return null
        return BLOCK.find(tag.code)?.groups?.get(1)?.let { Write(tag.range(it), "$BLOCK_KEY${it.value}") }
    }

    /** `m.name` after `import "m.j2" as m`: a name of that template. */
    private fun members(tags: List<Tag>): List<Ref> {
        val templates = tags.filter { it.statement == "import" }.mapNotNull { tag ->
            val alias = IMPORT_AS.find(tag.code)?.groupValues?.get(1) ?: return@mapNotNull null
            tag.path?.let { alias to it.value }
        }.toMap()
        return tags.takeIf { templates.isNotEmpty() }.orEmpty().flatMap { tag ->
            MEMBER.findAll(tag.code).mapNotNull { use ->
                val path = templates[use.groupValues[1]] ?: return@mapNotNull null
                use.groups[2]?.let { Ref(tag.range(it), listOf("$MEMBER_KEY${it.value}@$path")) }
            }
        }
    }

    /** Beside the template, else the nearest project file whose path ends like the name (the loader's root is not known). */
    override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
        super.resolvePath(file, path)?.let { return it }
        val origin = file.originalFile.virtualFile?.path ?: return null
        val folder = path.substringBeforeLast('/', "")
        return FilenameIndex.getVirtualFilesByName(path.substringAfterLast('/'), GlobalSearchScope.projectScope(file.project))
            .filter { !it.isDirectory && (folder.isEmpty() || it.parent?.path.orEmpty().endsWith("/$folder")) }
            .sortedBy { it.path }
            .maxByOrNull { it.path.commonPrefixWith(origin).length }
            ?.let { PsiManager.getInstance(file.project).findFile(it) }
    }

    private class Imported(val name: Pair<TextRange, String>, val alias: Pair<TextRange, String>?)

    private fun imported(tag: Tag): List<Imported> {
        val list = FROM.find(tag.code)?.groups?.get(1) ?: return emptyList()
        return IMPORTED.findAll(list.value).filter { it.groupValues[1] !in RESERVED }.mapNotNull { item ->
            val name = item.groups[1] ?: return@mapNotNull null
            val base = tag.tag.body.first + list.range.first
            val alias = item.groups[2]?.let { TextRange(base + it.range.first, base + it.range.last + 1) to it.value }
            Imported(TextRange(base + name.range.first, base + name.range.last + 1) to name.value, alias)
        }.toList()
    }

    /** `set` writes into the enclosing macro (or the template); the other names belong to the block their tag opens. */
    private fun writes(tag: Tag, blocks: List<Block>): List<Write> {
        val macro = blocks.filter { it.isMacro && tag.start in it }.maxByOrNull { it.open.start }
        val outer = macro?.let { "@${it.open.start}" }.orEmpty()
        return when (tag.statement) {
            "call" -> parameters(tag, CALL.find(tag.shallow(1))?.groups?.get(1)).map { (range, name) -> Write(range, "var:$name@${tag.start}") }
            "for" -> FOR.find(tag.code)?.groups?.get(1)?.let { tag.words(it) }.orEmpty().map { (range, name) -> Write(range, "var:$name@${tag.start}") }
            "import" -> IMPORT_AS.find(tag.code)?.groups?.get(1)?.let { listOf(Write(tag.range(it), "var:${it.value}$outer")) }.orEmpty()
            "macro" -> parameters(tag, MACRO.find(tag.shallow(1))?.groups?.get(2)).map { (range, name) -> Write(range, "var:$name@${tag.start}") }
            "set" -> SET.find(tag.code)?.groups?.get(1)?.let { tag.words(it) }.orEmpty().map { (range, name) -> Write(range, "var:$name$outer") }
            "with" -> WITH.findAll(tag.shallow(0)).mapNotNull { it.groups[1] }.map { Write(tag.range(it), "var:${it.value}@${tag.start}") }.toList()
            else -> emptyList()
        }
    }

    private fun parameters(tag: Tag, list: MatchGroup?): List<Pair<TextRange, String>> {
        list ?: return emptyList()
        return PARAMETER.findAll(list.value).mapNotNull { it.groups[1] }.map { name ->
            val start = tag.tag.body.first + list.range.first + name.range.first
            TextRange(start, start + name.value.length) to name.value
        }.toList()
    }

    /** The innermost block declaring [name] first, then the template's own names and its macros. */
    private fun keys(name: String, offset: Int, blocks: List<Block>, declared: Set<String>): List<String> {
        val scoped = blocks.filter { offset in it }.sortedByDescending { it.open.start }.map { "var:$name@${it.open.start}" }.filter { it in declared }
        return scoped + listOf("var:$name", "macro:$name")
    }

    private fun blocks(tags: List<Tag>, textEnd: Int): List<Block> {
        val open = ArrayDeque<Tag>()
        val closed = mutableListOf<Block>()
        for (tag in tags) {
            val statement = tag.statement ?: continue
            if (statement in SCOPES) { open.addLast(tag); continue }
            if (!statement.startsWith("end")) continue
            val index = open.indexOfLast { it.statement == statement.removePrefix("end") }
            if (index < 0) continue
            while (open.size > index) closed += Block(open.removeLast(), tag.start)
        }
        return closed + open.map { Block(it, textEnd) }
    }
}
