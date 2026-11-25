package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType

/**
 * GNU Make: `$(VAR)`, `${VAR}`, `$(VAR:.c=.o)`, `$(call fn,…)`, `$(value VAR)`, `ifdef VAR`, `export A B` → assignment (also
 * target-specific: `test: DEBUG := 1`) or `define`; prerequisites (and `.PHONY` names) → their rule, or the source file; `include` → file.
 * Variables and targets are shared with the included makefiles and with the makefiles that include this one.
 */
object Make : Format("Make", "Makefile", "mk") {
    override val nameCategories = super.nameCategories + Cat.TEXT
    // a conditional may be indented with a tab where no rule is open: it is make's, not the shell's
    private val DIRECTIVE = Regex("""(?:ifeq|ifneq|ifdef|ifndef|else|endif)\b""")
    override val lineComment = "#"
    private const val FILL = '\u0000'
    private const val MODIFIERS = """(?:(?:override|export|private)\s+)*"""
    private const val NAME = """[A-Za-z_][\w.-]*"""
    private const val OPERATOR = """(?::::=|::=|:=|\?=|\+=|!=|=)"""
    private val FUNCTIONS = listOf(
        "call", "value", "origin", "flavor", "eval", "info", "warning", "error", "shell", "wildcard", "patsubst", "subst", "strip",
        "findstring", "filter", "filter-out", "sort", "word", "words", "wordlist", "firstword", "lastword", "dir", "notdir", "suffix",
        "basename", "addsuffix", "addprefix", "join", "realpath", "abspath", "if", "or", "and", "foreach", "file", "let", "intcmp", "guile",
    )
    /** Functions whose first argument is a variable name. */
    private val NAME_FUNCTIONS = setOf("call", "value", "origin", "flavor")
    private val ASSIGNMENT = Regex("""(?m)^ *$MODIFIERS($NAME)\s*$OPERATOR""")
    private val BUILD_PRODUCT = Regex("""\.(?:o|a|so|obj|lo|d)$""")
    private val CONTINUATION = Regex("""\\\r?\n""")
    private val DEFINE = Regex("""(?m)^ *define\s+($NAME)""")
    private val DEFINE_BLOCK = Regex("""(?m)^ *define\b[\s\S]*?^ *endef\b""")
    private val DIRECTIVE_NAME = Regex("""(?m)^\s*(?:ifdef|ifndef|undefine)\s+($NAME)\s*$""")
    private val EXPORT = Regex("""(?m)^ *(?:export|unexport)[ \t]+([^\n#=]++)(?!=)""")
    private val INCLUDE = Regex("""(?m)^ *-?s?include[ \t]+([^\n#]+)""")
    private val LITERAL = Regex("""(?<!\$)[^$FILL$]+""")
    private val NAME_CALL = Regex("""(?<!\$)\$[({](?:${NAME_FUNCTIONS.joinToString("|")})\s+($NAME)(?=[\s,)}])""")
    private val NAME_ONLY = Regex(NAME)
    private val NOT_A_NAME = setOf("&", ":")
    private val RULE = Regex("""(?m)^ *(?!(?:ifeq|ifneq|ifdef|ifndef|else)\b)([^\s:=#][^:=#\n]*?)::?+(?![:=])([^\n#;]*)""")
    private val RULE_WORD = Regex("""[^\s|]+""")
    private val TARGET_VARIABLE = Regex("""^\s*$MODIFIERS($NAME)\s*$OPERATOR""")
    private val VARIABLE = Regex("""(?<!\$)\$[({]($NAME)(?=[)}:])""")
    private val WORD = Regex("""\S+""")

    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT),
        Rule("""(?:-?include|sinclude|ifeq|ifneq|ifdef|ifndef|else|endif|define|endef|export|unexport|override|vpath|undefine|private)\b""", Cat.KEYWORD, lineStart = true),
        Rule("""(?<=include[ \t])[^\s#][^\n#]*""", Cat.STRING),
        Rule("""\.(?:PHONY|DEFAULT|SUFFIXES|PRECIOUS|INTERMEDIATE|SECONDARY|DELETE_ON_ERROR|SILENT|ONESHELL|NOTPARALLEL|EXPORT_ALL_VARIABLES|DEFAULT_GOAL|POSIX|IGNORE|LOW_RESOLUTION_TIME|SECONDEXPANSION)\b""", Cat.META),
        Rule("""(?<![^\n])$NAME(?=\s*(?:::=|:::=|:=|\?=|\+=|!=|=))""", Cat.DECL),
        Rule("""(?<![^\n])[^\s:=#.][^:=#\n]*?(?=:(?!=))""", Cat.DECL),
        Rule("""(?<=\b(?:define|ifdef|ifndef|undefine|export|unexport)[ \t])$NAME(?=\s*$)""", Cat.VARIABLE, flags = java.util.regex.Pattern.MULTILINE),
        Rule("""\$\$""", Cat.PUNCT),
        // paths in rules and recipes (`src/main.c`, `main.o`): one token, so that they can be followed
        Rule("""(?<![\w$(.-])(?:[\w-]+|\.{1,2})?(?:/[\w.-]+)+/?|(?<![\w$(.-])[\w-]+(?:\.[\w-]+)+""", Cat.WORD),
        Rule("""\$[({](?:${FUNCTIONS.joinToString("|")})(?=\s)""", Cat.FUNCTION),
        Rule("""\$[({][^)}\s:,]*[)}]?|\$[A-Za-z@<^?*%+|]""", Cat.VARIABLE),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
    )

    /** A recipe (the lines that start with a tab, and those they continue with `\`) is one token. */
    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        override fun next(): IElementType {
            pos = recipeEnd() ?: return super.next()
            return token(Cat.TEXT)
        }

        private fun recipeEnd(): Int? {
            val line = buf.lastIndexOf('\n', pos - 1) + 1
            if (buf[line] != '\t' || buf[pos].isWhitespace() || !onlyIndentBefore()) return null
            // a tab after a line ending with `\` continues a variable or a rule, it opens no recipe
            if (line >= 2 && buf[line - 2] == '\\') return null
            if (DIRECTIVE.matchAt(buf, pos) != null) return null
            return generateSequence(lineEnd(pos, buf)) { eol ->
                val next = (eol + 1).takeIf { it < end } ?: return@generateSequence null
                val continued = buf[eol - 1] == '\\'
                val command = buf[next] == '\t' && DIRECTIVE.matchAt(buf, next + 1) == null
                lineEnd(next, buf).takeIf { continued || command }
            }.last().coerceAtMost(end)
        }
    }

    override val scripts = object : ScriptRules() {
        private val prefix = Regex("""[ \t]*[@+-]*[ \t]*""")

        /** `$$` is how a recipe writes the `$` of the shell. */
        override val substitutions = listOf(Regex("""\$\$""") to "$")

        /**
         * Without the `@`, `-`, `+` in front of a command. A line continued from the one before loses its tab only,
         * as make does: the blanks after it still part `then\` from the word that follows.
         */
        override fun lines(word: FmtWord, file: FmtFile): List<TextRange> {
            if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat != Cat.TEXT) return emptyList()
            val body = word.text
            return Scripts.lines(body).map { line ->
                val continued = line.startOffset >= 2 && body[line.startOffset - 2] == '\\'
                val skipped = when {
                    continued -> 1.takeIf { body[line.startOffset] == '\t' } ?: 0
                    else -> prefix.matchAt(body, line.startOffset)?.value?.length ?: 0
                }
                TextRange(minOf(line.startOffset + skipped, line.endOffset), line.endOffset)
            }.filter { !it.isEmpty }
        }
    }

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            val filePrereqs = mutableListOf<PathLink>()
            val joined = CONTINUATION.replace(text) { " ".repeat(it.value.length) }
            fun ref(r: TextRange, n: String) { refs += Ref(r, listOf("var:$n")) }
            /** The names of [words] (targets or prerequisites), each on its last literal part: `/app` of `$(DIR)/app`. */
            fun names(words: MatchGroup) = RULE_WORD.findAll(words.value).mapNotNull { w ->
                val start = words.range.first + w.range.first
                val name = joined.substring(start, start + w.value.length)
                val literal = LITERAL.findAll(w.value).lastOrNull()?.range
                literal?.takeUnless { name in NOT_A_NAME || '%' in name || name.any(Char::isWhitespace) }?.let { range(start, it) to name }
            }
            ASSIGNMENT.ranges(joined).forEach { (r, n) -> decls += Decl(r, "var:$n") }
            DEFINE.ranges(joined).forEach { (r, n) -> decls += Decl(r, "var:$n") }
            // rules: `a b: c d | e` (special targets such as .PHONY declare nothing; their names are references)
            RULE.findAll(flatten(joined)).forEach { m ->
                val targets = m.group(1)
                val prereqs = m.group(2)
                TARGET_VARIABLE.find(prereqs.value)?.group(1)?.let { v ->
                    decls += Decl(range(prereqs.range.first, v.range), "var:${v.value}")
                    return@forEach
                }
                if (!targets.value.startsWith(".")) names(targets).forEach { (r, n) -> decls += Decl(r, "target:$n") }
                names(prereqs).forEach { (r, n) ->
                    refs += Ref(r, listOf("target:$n"))
                    // a source file (`main.o: main.c util.h`)
                    if (('.' in n || '/' in n) && '$' !in n) filePrereqs += PathLink(r, n)
                }
            }
            VARIABLE.ranges(text).forEach { (r, n) -> ref(r, n) }
            NAME_CALL.ranges(text).forEach { (r, n) -> ref(r, n) }
            DIRECTIVE_NAME.ranges(text).forEach { (r, n) -> ref(r, n) }
            EXPORT.findAll(joined).map { it.group(1) }.forEach { g ->
                WORD.findAll(g.value).filter { NAME_ONLY.matches(it.value) }.forEach { ref(range(g.range.first, it.range), it.value) }
            }
            val paths = INCLUDE.findAll(joined).mapNotNull { it.groups[1] }.flatMap { g ->
                WORD.findAll(g.value).filter { '$' !in it.value }.map { PathLink(range(g.range.first, it.range), it.value) }
            }.toList()
            // sources only: not another rule's target, nor a build product
            val targets = decls.mapTo(HashSet()) { it.key }
            val sources = filePrereqs.filter { "target:${it.path}" !in targets && !BUILD_PRODUCT.containsMatchIn(it.path) }
            return Links(decls, refs, paths + sources)
        }

        /** The included makefiles (transitively) and the project makefiles that include this one. */
        override fun scope(file: PsiFile): List<PsiFile> {
            val manager = PsiManager.getInstance(file.project)
            val all = FileTypeIndex.getFiles(MakeFileType, GlobalSearchScope.projectScope(file.project)).mapNotNull(manager::findFile)
            // asked for every makefile at each step of the walk: resolved once
            val included = HashMap<PsiFile, List<PsiFile>>()
            fun includes(f: PsiFile) = included.getOrPut(f) {
                Fmt.links(f).paths.mapNotNull { resolvePath(f, it.path) as? PsiFile }.filter { it.fileType == MakeFileType }
            }
            val seen = linkedSetOf(file.originalFile)
            val queue = ArrayDeque(seen)
            while (queue.isNotEmpty() && seen.size < 50) {
                val f = queue.removeFirst()
                (includes(f) + all.filter { o -> includes(o).any { it == f } }).forEach { if (seen.add(it)) queue += it }
            }
            return listOf(file) + seen.drop(1)
        }
    }

    /** [joined] at the same offsets, as rules read it: `define` blocks blanked, `$(…)` filled (its `:` and spaces are not the rule's). */
    private fun flatten(joined: String): String {
        val plain = DEFINE_BLOCK.replace(joined) { " ".repeat(it.value.length) }
        val depths = plain.indices.runningFold(0) { depth, i ->
            val c = plain[i]
            when {
                c == '\n' -> 0
                c in "({" && (depth > 0 || plain.getOrNull(i - 1) == '$') -> depth + 1
                c in ")}" && depth > 0 -> depth - 1
                else -> depth
            }
        }
        return String(CharArray(plain.length) { i -> plain[i].takeIf { it == '\n' || depths[i] + depths[i + 1] == 0 } ?: FILL })
    }
}
