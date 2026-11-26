package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Fmt
import me.kevinlot.lite.formats.FmtFile
import me.kevinlot.lite.formats.FmtLexer
import me.kevinlot.lite.formats.FmtToken
import me.kevinlot.lite.formats.FmtWord
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.Pointers
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule
import me.kevinlot.lite.formats.ScriptRules
import me.kevinlot.lite.formats.Scripts
import me.kevinlot.lite.formats.findItem
import me.kevinlot.lite.formats.infra.GithubActions
import me.kevinlot.lite.formats.infra.Serverless
import me.kevinlot.lite.formats.infra.lineEnd

/** YAML: `*alias` → `&anchor` (in the same document). */
object Yaml : Format("Yaml", "YAML", "yaml") {
    override val lineComment = "#"
    /** A script (block scalar) holds `${{ … }}` names and the outputs of its step. */
    override val nameCategories = super.nameCategories + Cat.TEXT
    private const val BLOCK_INDICATOR = """[|>](?:[-+]\d?|\d[-+]?)?"""
    // a line that ends with the header of a block scalar: group 1 = its `- ` items, group 2 = its key
    private val BLOCK_HEADER: Pattern = Pattern.compile(
        """[ \t]*+((?:-[ \t]++)*+)((?:"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"|'[^'\n]*+(?:''[^'\n]*+)*+'|[^\s#'"{\[\]},&*!|>%@`-][^:#\n]*?)[ \t]*+:[ \t]++)?""" +
            """(?:[&!]\S*+[ \t]++)*+$BLOCK_INDICATOR[ \t]*+(?:#[^\n]*+)?""",
    )
    // ends before `: `, ` #` and the flow indicators
    private const val PLAIN = """(?:[^\s,\[\]{}#&*!|>'"%@`:?-]|[?-](?=[^\s,\[\]{}]))[^\s:#,\[\]{}]*+""" +
        """(?:(?:[ \t]++(?![\s#]|\z|:(?:\s|\z))|:(?=[^\s,\[\]{}])|#)[^\s:#,\[\]{}]*+)*+"""
    private const val SCALAR_END = """(?=[ \t]*+(?:[\n,\]}]|\z|(?<=[ \t])#))"""

    /** [first] where a node may start: on a new line, or after an indicator, a tag or an anchor. */
    private fun atNodeStart(first: String) = """$first(?<=(?:(?m:^)|[\[{,]|[:?-][ \t]|["']:|[!&][\w./:-]{0,40}[ \t])[ \t]{0,40}$first)"""

    override val rules = listOf(
        Rule("""(?<!\S)#[^\n]*""", Cat.COMMENT),
        Rule("""(?:---|\.\.\.)(?=\s|$)""", Cat.META, lineStart = true),
        Rule("""-(?=\s)""", Cat.OPERATOR),
        Rule(atNodeStart("&") + """[\w.-]+""", Cat.DECL),
        Rule(atNodeStart("""\*""") + """[\w.-]+""", Cat.VARIABLE),
        Rule("""!{1,2}[\w./:-]*""", Cat.META),
        Rule("""<<(?=\s*:)""", Cat.OPERATOR),
        Rule("""(?:"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"|'[^'\n]*+(?:''[^'\n]*+)*+')(?=\s*:(?:\s|$))""", Cat.KEY),
        Rule("""[^\s#:'"{}\[\],&*!|>%@`][^:#\n{}\[\],]*?(?=\s*:(?:\s|$))""", Cat.KEY),
        Rule("""(?<=\${'$'}ref['"]?:[ \t]{1,8})(?:[^\s#'"{\[]|\{[^}\s]*})[^\s,{}\]]*+(?:\{[^}\s]*}[^\s,{}\]]*+)*+""", Cat.STRING),
        Rule(atNodeStart("\"") + """[^"\\]*+(?:\\[\s\S][^"\\]*+)*+"?""", Cat.STRING),
        Rule(atNodeStart("'") + """[^']*+(?:''[^']*+)*+'?""", Cat.STRING),
        Rule("""$BLOCK_INDICATOR(?=[ \t]*(?:#[^\n]*)?$)""", Cat.OPERATOR, flags = Pattern.MULTILINE),
        Rule("""(?:true|false|True|False|TRUE|FALSE|null|Null|NULL|~)$SCALAR_END""", Cat.CONSTANT),
        Rule(R.NUMBER + SCALAR_END, Cat.NUMBER),
        Rule(PLAIN, Cat.WORD),
    )

    /** The body of a block scalar (`|`, `>`) is one token of plain text, found from the line before it. */
    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        private val header = BLOCK_HEADER.matcher("")

        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
            header.reset(buffer)
            super.start(buffer, startOffset, endOffset, initialState)
        }

        override fun next(): IElementType {
            if (buf[pos].isWhitespace() || !onlyIndentBefore()) return super.next()
            pos = blockBodyEnd() ?: return super.next()
            return token(Cat.TEXT)
        }

        /** The end of the block scalar that starts at this line, when the line before has its header. */
        private fun blockBodyEnd(): Int? {
            val line = buf.lastIndexOf('\n', pos - 1) + 1
            val headerEnd = (line - 1 downTo 0).firstOrNull { !buf[it].isWhitespace() }?.plus(1) ?: return null
            val headerLine = buf.lastIndexOf('\n', headerEnd - 1) + 1
            if (!header.region(headerLine, headerEnd).matches()) return null
            val parent = (header.start(2).takeIf { it >= 0 } ?: (header.end(1) - 1)) - headerLine
            if (pos - line <= parent) return null
            return minOf(bodyEnd(line, parent), end)
        }

        /** The end of the last line, from [first] on, before one that is not indented deeper than [parent]. */
        private fun bodyEnd(first: Int, parent: Int): Int {
            var last = first
            var line = first
            while (line < end) {
                val eol = lineEnd(line, buf)
                val text = (line until eol).firstOrNull { buf[it] != ' ' && buf[it] != '\t' }
                if (text != null && text - line <= parent) return last
                if (text != null) last = eol
                line = eol + 1
            }
            return last
        }
    }

    /** The body of a `run: |` is a shell script, unless its step names another `shell:`. */
    override val scripts = object : ScriptRules() {
        private val runHeader = Regex("""(?:^|[\s-])(run)[ \t]*:[ \t]*[|>][-+0-9]*[ \t]*(?:#.*)?$""")
        private val shells = Regex("""["']?(?:bash|sh|zsh)\b.*""")
        private val shellValue = Regex("""["']?[ \t]*:[ \t]*([^\n#]*)""")

        /** GitHub writes the value of an expression into the script before the shell reads it. */
        override val substitutions = listOf(Regex("""\$\{\{.*?}}""") to Scripts.PLACEHOLDER)

        /** Without the indentation of the block: a here-document must find its end marker at the start of a line. */
        override fun lines(word: FmtWord, file: FmtFile): List<TextRange> {
            if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat != Cat.TEXT) return emptyList()
            val text = file.viewProvider.contents
            val start = word.textRange.startOffset
            val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
            val run = headerBefore(text, lineStart) ?: return emptyList()
            if (!isShell(file, text, run)) return emptyList()
            return Scripts.lines(word.text, indent = start - lineStart)
        }

        /** The offset of the `run` key that opens the block whose first line starts at [bodyLine]. */
        private fun headerBefore(text: CharSequence, bodyLine: Int): Int? {
            val end = (bodyLine - 1 downTo 0).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: return null
            val start = text.lastIndexOf('\n', end - 1) + 1
            return runHeader.find(text.subSequence(start, end))?.groups?.get(1)?.range?.first?.plus(start)
        }

        private fun isShell(file: FmtFile, text: CharSequence, run: Int): Boolean {
            val links = Fmt.links(file)
            val pointer = links.declsIn(TextRange.from(run, 3)).firstOrNull { it.key.startsWith("key:") }?.key ?: return true
            val shell = links.declared(pointer.removeSuffix("/run") + "/shell").firstOrNull() ?: return true
            val value = shellValue.matchAt(text, shell.range.endOffset)?.groupValues?.get(1)?.trim().orEmpty()
            return value.isEmpty() || shells.matches(value)
        }
    }

    /** Anchors and aliases as the lexer finds them (none in a comment, a string or a block scalar), without their sigil. */
    private fun names(text: CharSequence): List<Pair<Cat?, TextRange>> {
        if ('&' !in text) return emptyList()
        val lexer = lexer()
        lexer.start(text)
        return generateSequence {
            lexer.tokenType?.let { type -> ((type as? FmtToken)?.cat to TextRange(lexer.tokenStart + 1, lexer.tokenEnd)).also { lexer.advance() } }
        }
            .filter { it.first == Cat.DECL || it.first == Cat.VARIABLE }.toList()
    }

    /**
     * Anchors; for `$ref` (OpenAPI, AsyncAPI, JSON Schema), every key as a JSON pointer target; the names of GitHub
     * Actions workflows ([GithubActions]); Serverless variables and CloudFormation references ([Serverless]).
     */
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val (anchors, aliases) = names(text).partition { it.first == Cat.DECL }
            val keys = Pointers.yamlKeys(text)
            val actions = GithubActions.links(text, keys)
            // a workflow is no service: its scripts are not scanned for Serverless and CloudFormation forms
            val service = when {
                actions != null -> null
                else -> Serverless.links(text)
            }
            val pubspec = Pubspec.links(text, fileName)
            return Links(
                decls = anchors.map { (_, r) -> Decl(r, "anchor:${r.subSequence(text)}") } + keys.map { (r, p) -> Decl(r, "key:$p") } +
                    actions?.decls.orEmpty(),
                refs = aliases.map { (_, r) -> Ref(r, listOf("anchor:${r.subSequence(text)}")) } + Pointers.yamlRefs(text) +
                    actions?.refs.orEmpty() + service?.refs.orEmpty(),
                paths = actions?.paths.orEmpty() + service?.paths.orEmpty() + pubspec?.paths.orEmpty(),
            )
        }

        override fun resolve(file: PsiFile, ref: Ref) =
            Pointers.resolveYaml(file, ref, this) ?: GithubActions.resolve(file, ref, this) ?: Serverless.resolve(file, ref, this)

        /** Keys are looked up in the file itself: another file is reached by a `$ref` or a `uses:`, never by name. */
        override fun scope(file: PsiFile): List<PsiFile> = listOf(file)

        /** `./.github/…` (a local workflow or action) starts at the repository root; a folder is its `action.yml`. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            if (!path.startsWith("./.github/")) return super.resolvePath(file, path) ?: Serverless.resolvePath(file, path)
            val root = generateSequence(file.originalFile.virtualFile?.parent) { it.parent }.firstOrNull { it.name == ".github" }?.parent ?: return null
            val target = root.findFileByRelativePath(path.removePrefix("./")) ?: return null
            val item = target.takeUnless { it.isDirectory } ?: listOf("action.yml", "action.yaml").firstNotNullOfOrNull(target::findChild) ?: target
            return PsiManager.getInstance(file.project).findItem(item)
        }
    }
}
