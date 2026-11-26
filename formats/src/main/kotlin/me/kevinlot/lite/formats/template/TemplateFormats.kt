package me.kevinlot.lite.formats.template

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.FmtLexer
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.Offsets
import me.kevinlot.lite.formats.PathLink
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule

/**
 * Handlebars and Mustache: `{{> name}}` → the inline partial of the file, else the partial file of the project;
 * a name inside `{{#each xs as |x i|}} … {{/each}}` → its block parameter. The text around the tags is colored as HTML.
 */
abstract class TemplateFormat(id: String, displayName: String, extension: String) : Format(id, displayName, extension) {
    override val linker: Linker = TemplateLinker
    override val nameCategories = setOf(Cat.FUNCTION, Cat.STRING, Cat.VARIABLE)
    override val rules = emptyList<Rule>()
    override fun lexer(): FmtLexer = TemplateLexer(this)
}

object Handlebars : TemplateFormat("Handlebars", "Handlebars", "hbs") {
    override val blockComment = "{{!--" to "--}}"
}

object Mustache : TemplateFormat("Mustache", "Mustache", "mustache") {
    override val blockComment = "{{!" to "}}"
}

private const val TEMPLATE_COMMENT = """\{\{~?!--[\s\S]*?(?:--~?\}\}|\z)|\{\{~?![\s\S]*?(?:\}\}|\z)"""
private const val TEMPLATE_STRING = """"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"?|'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'?"""
private const val TEMPLATE_RAW_OPEN = "{{{{"
private const val TEMPLATE_SEGMENT = """\[[^\]\n]*]"""
val TEMPLATE_EXTENSIONS = listOf("handlebars", "hbs", "mustache")

/** Whether the mustache [brace] belongs to follows a lone backslash: it is then output as it is written. */
private fun escapedMustache(text: CharSequence, brace: Int): Boolean {
    val first = (brace downTo 1).firstOrNull { text[it - 1] != '{' } ?: return false
    return text[first - 1] == '\\' && text.getOrNull(first - 2) != '\\'
}

/**
 * Two modes, text and tag. The mode at a restart offset is read back from the last `{{` or `}}` before it;
 * an escaped `\{{` and what a `{{{{raw}}}}` block holds are text.
 */
class TemplateLexer(format: Format) : FmtLexer(format) {
    private var inside = false
    private var opensRaw = false
    private var raw = false
    private var tagRules: List<BoundRule> = emptyList()
    private var textRules: List<BoundRule> = emptyList()

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        val before = buffer.subSequence(0, startOffset)
        val open = before.lastIndexOf("{{")
        val rawOpen = before.lastIndexOf(TEMPLATE_RAW_OPEN)
        val rawOpened = rawOpen >= 0 && !closesRaw(buffer, rawOpen)
        inside = open > before.lastIndexOf("}}") && !escapedMustache(before, open)
        raw = rawOpened && before.indexOf("}}", rawOpen) >= 0
        opensRaw = rawOpened && !raw
        tagRules = TAG_RULES.map { BoundRule(it, buffer) }
        textRules = TEXT_RULES.map { BoundRule(it, buffer) }
        super.start(buffer, startOffset, endOffset, initialState)
    }

    override fun next(): IElementType {
        val c = buf[pos]
        if (c.isWhitespace()) {
            while (pos < end && buf[pos].isWhitespace()) pos++
            return TokenType.WHITE_SPACE
        }
        if (raw && !(buf.startsWith(TEMPLATE_RAW_OPEN, pos) && closesRaw(buf, pos))) return token(textToken())
        if (c == '{' && pos + 1 < end && buf[pos + 1] == '{') return token(open())
        if (inside) return token(tagToken(c))
        if (c == '\\' && buf.startsWith("{{", pos + 1) && escapedMustache(buf, pos + 1)) {
            pos += 1 + run('{', pos + 1)
            return token(Cat.PUNCT)
        }
        return token(textToken())
    }

    private fun closesRaw(text: CharSequence, rawOpen: Int): Boolean = text.getOrNull(rawOpen + TEMPLATE_RAW_OPEN.length) == '/'

    private fun open(): Cat {
        val comment = COMMENT.matcher(buf).region(pos, end)
        if (comment.lookingAt()) {
            pos = comment.end()
            inside = false
            return Cat.COMMENT
        }
        opensRaw = buf.startsWith(TEMPLATE_RAW_OPEN, pos) && !closesRaw(buf, pos)
        raw = false
        pos += run('{')
        inside = true
        return Cat.LBRACE
    }

    private fun tagToken(c: Char): Cat {
        if (c == '}' && run('}') >= 2) {
            pos += run('}')
            inside = false
            raw = opensRaw
            opensRaw = false
            return Cat.RBRACE
        }
        match(tagRules)?.let { return it }
        pos++
        return when (c) {
            '(' -> Cat.LPAREN
            ')' -> Cat.RPAREN
            '[' -> Cat.LBRACKET
            ']' -> Cat.RBRACKET
            '#', '&', '*', '/', '=', '>', '^', '|', '~' -> Cat.OPERATOR
            else -> Cat.PUNCT
        }
    }

    private fun textToken(): Cat {
        match(textRules)?.let { return it }
        pos++
        while (pos < end && !buf[pos].isWhitespace() && buf[pos] !in TEXT_STOPS) pos++
        return Cat.PUNCT
    }

    private fun run(c: Char, from: Int = pos): Int = (from until minOf(end, from + 4)).takeWhile { buf[it] == c }.size

    private companion object {
        val COMMENT: Pattern = Pattern.compile(TEMPLATE_COMMENT)
        const val TEXT_STOPS = "\"'/<=>\\{"
        val TAG_RULES = listOf(
            Rule(TEMPLATE_STRING, Cat.STRING),
            Rule(TEMPLATE_SEGMENT, Cat.VARIABLE),
            Rule("""@[\w-]+""", Cat.META),
            Rule("""-?\d+(?:\.\d+)?(?![\w-])""", Cat.NUMBER),
            Rule("""(?<=>[ \t]{0,8})[\w$-]+(?:[./][\w$-]+)*""", Cat.FUNCTION),
            Rule("""(?<![\w$./-])(?:false|null|true|undefined)(?![\w$.-])""", Cat.CONSTANT),
            Rule("""(?<![\w$./-])(?:as|each|else|if|inline|this|unless|with)(?![\w$-]|=)""", Cat.KEYWORD),
            Rule("""[A-Za-z_][\w-]*(?==)""", Cat.ATTRIBUTE),
            Rule("""[A-Za-z_$][\w$-]*""", Cat.VARIABLE),
        )
        val TEXT_RULES = listOf(
            Rule("""<!--[\s\S]*?(?:-->|\z)""", Cat.COMMENT),
            Rule("""</?[A-Za-z][\w:-]*|/?>""", Cat.TAG),
            Rule("""[A-Za-z_:@][\w:.-]*(?==["'{])""", Cat.ATTRIBUTE),
            Rule("""(?<==)(?:"[^"{\n]*"|'[^'{\n]*')""", Cat.STRING),
        )
    }
}

object TemplateLinker : Linker() {
    private val BLOCK_NAME = Regex("""[^\s~]+""")
    private val COMMENT = Regex(TEMPLATE_COMMENT)
    private val HEAD = Regex("""(?<![\w$.@/-])[A-Za-z_$][\w$-]*(?![\w$-]|\s*=)""")
    private val INLINE = Regex("""^\s*inline\s+(?:"([^"\n]+)"|'([^'\n]+)')""")
    private val OPENERS = setOf("#", "#*", "#>", "^")
    private val PARAMS = Regex("""\bas\s*\|([^|]*)\|""")
    private val PARTIAL = Regex("""^\s*(?:"([^"\n]+)"|'([^'\n]+)'|([\w$-]+(?:[./][\w$-]+)*))""")
    private val QUOTED = Regex(""""[^"\n]*"|'[^'\n]*'|$TEMPLATE_SEGMENT""")
    private val RAW = Regex("""\{{4}(?!/)[^{}]*\}{4}([\s\S]*?)(?=\{{4}/|\z)""")
    private val TAG =Regex("""\{{2,4}~?[ \t]*(#>|#\*|[#/^>&])?([^{}]*)\}{2,4}""")
    private val WORD = Regex("""[A-Za-z_$][\w$-]*""")

    /** The tags a block's parameters are visible in, and the declaration key of each parameter. */
    private class Scope(val range: IntRange, val start: Int, val parameters: List<Pair<TextRange, String>>) {
        val keys = parameters.associate { (_, name) -> name to "param:$name@$start" }
    }

    override fun links(text: CharSequence, fileName: String): Links {
        val comments = Offsets(COMMENT.findAll(text).map { it.range })
        val raw = Offsets(RAW.findAll(text).mapNotNull { it.groups[1]?.range })
        val tags = TAG.findAll(text).filter { it.range.first !in comments && it.range.first !in raw && !escapedMustache(text, it.range.first) }.toList()

        val inline = tags.filter { it.groupValues[1] == "#*" }.mapNotNull { named(INLINE, it) }
            .map { (range, name) -> Decl(range, "partial:$name") }
        val inlineKeys = inline.map { it.key }.toSet()
        val partials = tags.filter { it.groupValues[1] == ">" || it.groupValues[1] == "#>" }.mapNotNull { named(PARTIAL, it) }
        val (local, files) = partials.partition { (_, name) -> "partial:$name" in inlineKeys }

        val scopes = scopes(tags, text.length).map { (tag, end) -> Scope(tag.range.last + 1..end, tag.range.first, parametersOf(tag)) }
            .filter { it.parameters.isNotEmpty() }
        val params = scopes.flatMap { scope -> scope.parameters.map { (range, name) -> Decl(range, scope.keys.getValue(name)) } }
        val uses = tags.filter { it.groupValues[1] != "/" }.flatMap { tag ->
            val body = tag.groups[2] ?: return@flatMap emptyList()
            HEAD.findAll(QUOTED.replace(body.value) { " ".repeat(it.value.length) }).mapNotNull { head ->
                val offset = body.range.first + head.range.first
                val key = scopes.filter { offset in it.range && head.value in it.keys }.maxByOrNull { it.start }?.keys?.get(head.value)
                key?.let { Ref(TextRange(offset, offset + head.value.length), listOf(it)) }
            }.toList()
        }
        return Links(
            decls = inline + params,
            paths = files.map { (range, name) -> PathLink(range, name) },
            refs = local.map { (range, name) -> Ref(range, listOf("partial:$name")) } + uses,
        )
    }

    /** Block parameters are keyed by offset: they mean nothing in another file. */
    override fun scope(file: PsiFile): List<PsiFile> = listOf(file)

    /** A partial is registered by name, whatever its folder: the nearest file named like it, `_name` included. */
    override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
        val origin = file.originalFile.virtualFile?.path ?: return null
        val folder = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val names = listOf(name) + TEMPLATE_EXTENSIONS.flatMap { listOf("$name.$it", "_$name.$it") }
        val scope = GlobalSearchScope.projectScope(file.project)
        return names.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
            .filter { !it.isDirectory && (folder.isEmpty() || it.parent?.path.orEmpty().endsWith("/$folder")) }
            .sortedBy { it.path }
            .maxByOrNull { it.path.commonPrefixWith(origin).length }
            ?.let { PsiManager.getInstance(file.project).findFile(it) }
    }

    /** `{{> footer}}` names `footer.hbs` or `_footer.hbs`: the extension and the underscore stay out of the tag. */
    override fun renamedPath(old: String, newName: String): String {
        val last = old.substringAfterLast('/')
        val bare = newName.takeIf { '.' in last } ?: newName.substringBeforeLast('.')
        return super.renamedPath(old, bare.takeIf { last.startsWith("_") } ?: bare.removePrefix("_"))
    }

    private fun named(pattern: Regex, tag: MatchResult): Pair<TextRange, String>? {
        val body = tag.groups[2] ?: return null
        val name = pattern.find(body.value)?.groups?.drop(1)?.firstNotNullOfOrNull { it } ?: return null
        return TextRange(body.range.first + name.range.first, body.range.first + name.range.last + 1) to name.value
    }

    private fun parametersOf(tag: MatchResult): List<Pair<TextRange, String>> {
        val body = tag.groups[2] ?: return emptyList()
        val list = PARAMS.find(body.value)?.groups?.get(1) ?: return emptyList()
        return WORD.findAll(list.value).map { word ->
            val start = body.range.first + list.range.first + word.range.first
            TextRange(start, start + word.value.length) to word.value
        }.toList()
    }

    /** Each block-opening tag with the offset its block ends at (the end of the text when it is never closed). */
    private fun scopes(tags: List<MatchResult>, textEnd: Int): List<Pair<MatchResult, Int>> {
        val open = ArrayDeque<MatchResult>()
        val closed = mutableListOf<Pair<MatchResult, Int>>()
        for (tag in tags) {
            val sigil = tag.groupValues[1]
            if (sigil == "/") {
                val index = open.indexOfLast { blockName(it) == blockName(tag) }
                if (index >= 0) while (open.size > index) closed += open.removeLast() to tag.range.first
                continue
            }
            if (sigil in OPENERS && tag.groupValues[2].isNotBlank()) open.addLast(tag)
        }
        return closed + open.map { it to textEnd }
    }

    private fun blockName(tag: MatchResult): String? {
        if (tag.groupValues[1] == "#*") return "inline"
        return BLOCK_NAME.find(tag.groupValues[2])?.value
    }
}
