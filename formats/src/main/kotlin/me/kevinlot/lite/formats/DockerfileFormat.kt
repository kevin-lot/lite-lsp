package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType

/** Dockerfile: `$VAR` → `ARG`/`ENV`; `COPY --from=build` / `FROM build` → `FROM … AS build`. */
object Dockerfile : Format("Dockerfile", "Dockerfile", "dockerfile") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.WORD, Cat.VARIABLE, Cat.ATTRIBUTE, Cat.STRING, Cat.TEXT)
    private val ARG = Regex("""(?mi)^\s*ARG\s+([A-Za-z_]\w*)""")
    // the shell form of an instruction, after its flags (`RUN --mount=… make`)
    private val SHELL_FORM = Regex("""[ \t]*(?i:RUN|CMD|ENTRYPOINT)[ \t]+(?:--\S+[ \t]+)*""")
    private val HERE_DOCUMENT = Regex("""<<-?["']?(\w+)""")
    private val ENV = Regex("""(?mi)^\s*ENV\s+(.*)$""")
    private val ENV_NAME = Regex("""^([A-Za-z_]\w*)""")
    private val ENV_PAIR = Regex("""(?:^|\s)([A-Za-z_]\w*)=""")
    private val FROM = Regex("""(?mi)^\s*FROM\s+([\w.-]+)""")
    private val FROM_FLAG = Regex("""--from=([\w.-]+)""")
    private val STAGE = Regex("""(?mi)^\s*FROM\s+\S+\s+AS\s+([\w.-]+)""")
    private val VARIABLE = Regex("""\$\{?([A-Za-z_]\w*)""")
    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT, lineStart = true),
        Rule("""(?i)(?:FROM|RUN|CMD|LABEL|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL|MAINTAINER)\b""", Cat.KEYWORD, lineStart = true),
        Rule("""(?i)\bAS\b""", Cat.KEYWORD),
        Rule("""--[\w-]+(?:=[^\s\\]*)?""", Cat.ATTRIBUTE),
        Rule("""\$\{[^}\n]*}|\$[A-Za-z_]\w*""", Cat.VARIABLE),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule(R.NUMBER, Cat.NUMBER),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = ARG.ranges(text).map { (r, n) -> Decl(r, "var:$n") } +
                ENV.findAll(text).mapNotNull { it.groups[1] }.flatMap { body ->
                    val names = ENV_PAIR.takeIf { '=' in body.value } ?: ENV_NAME
                    names.ranges(body.value).map { (r, n) -> Decl(r.shiftRight(body.range.first), "var:$n") }
                } +
                STAGE.ranges(text).map { (r, n) -> Decl(r, "stage:${n.lowercase()}") }
            val refs = VARIABLE.ranges(text).map { (r, n) -> Ref(r, listOf("var:$n")) } +
                FROM_FLAG.ranges(text).map { (r, n) -> Ref(r, listOf("stage:${n.lowercase()}")) } +
                FROM.ranges(text).map { (r, n) -> Ref(r, listOf("stage:${n.lowercase()}")) }
            return Links(decls.toList(), refs.toList())
        }
    }

    /** The command of a shell-form `RUN` / `CMD` / `ENTRYPOINT` is one token, up to the end of its last continued line. */
    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        override fun next(): IElementType {
            pos = commandEnd() ?: return super.next()
            return token(Cat.TEXT)
        }

        private fun commandEnd(): Int? {
            val line = buf.lastIndexOf('\n', pos - 1) + 1
            val instruction = SHELL_FORM.matchAt(buf, line)?.takeIf { it.range.last + 1 == pos } ?: return null
            // `["a", "b"]` is the exec form: no shell reads it
            if (pos >= end || buf[pos] == '[' || buf[pos] == '\n') return null
            return minOf(hereDocumentEnd() ?: continuedEnd(instruction.range.first), end)
        }

        /** A line ending with a backslash goes on, and so does a comment line in the middle of such a command. */
        private tailrec fun continuedEnd(lineStart: Int): Int {
            val eol = lineEnd(lineStart, buf)
            val text = buf.subSequence(lineStart, eol).trim()
            if (eol >= end || !(text.endsWith("\\") || text.startsWith("#"))) return eol
            return continuedEnd(eol + 1)
        }

        private fun hereDocumentEnd(): Int? {
            val marker = HERE_DOCUMENT.matchAt(buf, pos)?.groupValues?.get(1) ?: return null
            val close = generateSequence(lineEnd(pos, buf) + 1) { lineEnd(it, buf) + 1 }.takeWhile { it < end }
                .firstOrNull { buf.subSequence(it, lineEnd(it, buf)).trim() == marker }
            return lineEnd(close ?: return end, buf)
        }
    }

    override val scripts = object : ScriptRules() {
        override fun lines(word: FmtWord, file: FmtFile): List<TextRange> {
            if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat != Cat.TEXT) return emptyList()
            return Scripts.lines(word.text)
        }
    }
}
