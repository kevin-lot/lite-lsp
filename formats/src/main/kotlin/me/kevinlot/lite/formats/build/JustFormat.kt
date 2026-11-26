package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.tree.IElementType

/**
 * just (justfile): `{{name}}` and `$name` in recipe bodies, expressions of assignments, parameter defaults and
 * dependency arguments → the recipe's parameter, else the variable; dependencies and `alias` → recipes (`foo::recipe` → `mod foo`);
 * `import 'x.just'` and `mod name` → file.
 */
object Just : Format("Just", "Just", "just") {
    override val nameCategories = super.nameCategories + Cat.TEXT
    override val lineComment = "#"
    private const val ID = """[A-Za-z_][\w-]*"""
    private const val PARENS = """\([^()]*+(?:\([^()]*+\)[^()]*+)*+\)"""
    private const val PATH = """$ID(?:::$ID)*+"""
    private val KEYWORDS = setOf("if", "else", "true", "false", "alias", "export", "unexport", "import", "mod", "set")
    private val ALIAS = Regex("""^alias\s+($ID)\s*:=\s*($PATH)""")
    private val ASSIGNMENT = Regex("""^(?:export\s+)?($ID)\s*:=(.*)""")
    private val DEPENDENCY = Regex("""\(\s*($PATH)([^)]*)\)|(?<![\w:-])($PATH)(?![\w:-])""")
    private val EXPORTED = Regex("""(?<!\$)\$($ID)""")
    private val HEADER = Regex("""^@?($ID)([^\n]*)""")
    private val IDENTIFIER = Regex("""(?<![\w-])($ID)(?![\w-])(?!\s*\()""")
    private val IMPORT = Regex("""^import\??\s+(['"])([^'"\n]+)\1""")
    private val INTERPOLATION = Regex("""\{\{(?!\{)(.*?)}}""")
    private val MODULE = Regex("""^mod\??\s+($ID)(?:\s+(['"])([^'"\n]+)\2)?""")
    private val MODULE_NAME = Regex("""(?m)^mod\??\s+($ID)""")
    private val PARAMETER = Regex("""([+*]?\$?)($ID)(?:\s*=\s*([xf]?'[^']*'|[xf]?"[^"\\]*+(?:\\.[^"\\]*+)*+"|`[^`]*`|$PARENS|$ID(?:\s*$PARENS)?))?""")
    private val SETTING = Regex("""^set\s+[\w-]+\s*:=(.*)""")
    private val SETTING_LINE = Regex("""^set\b""")
    private val STRING = Regex("""(?:(?<![\w-])[xf])?(?:'[^']*'?|"[^"\\]*+(?:\\.[^"\\]*+)*+"?|`[^`]*`?)""")
    private val UNEXPORT = Regex("""^unexport\s+($ID)""")

    override val rules = listOf(
        Rule("""#![^\n]*""", Cat.META, lineStart = true),
        Rule(R.HASH, Cat.COMMENT),
        Rule("""\{\{\{\{""", Cat.PUNCT),
        Rule("""\{\{[\s\S]*?(?:}}|(?=\n))""", Cat.VARIABLE),
        Rule("""'{3}[\s\S]*?(?:'{3}|\z)|"{3}[\s\S]*?(?:"{3}|\z)|`{3}[\s\S]*?(?:`{3}|\z)""", Cat.STRING),
        Rule("""'[^'\n]*'?""", Cat.STRING),
        Rule(R.DQ, Cat.STRING),
        Rule("""`[^`\n]*`?""", Cat.STRING),
        Rule("""(?<![^\n])\[[^\]\n]*]""", Cat.META),
        Rule("""(?<![^\n])(?:alias|export|unexport|import\??|mod\??|set)\b""", Cat.KEYWORD),
        Rule("""(?<=\bset[ \t])[\w-]+""", Cat.KEY),
        Rule("""(?<![^\n])@?$ID(?=[^\n:]*:(?!=))""", Cat.DECL),
        Rule("""$ID(?=\s*:=)""", Cat.DECL),
        Rule("""\b(?:if|else|true|false)\b""", Cat.KEYWORD),
        Rule("""\$$ID""", Cat.VARIABLE),
        Rule(""":=|&&|==|!=|=~|\|\||\+|/""", Cat.OPERATOR),
    )

    /** The body of a recipe (the indented lines under its header) is one token. */
    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        override fun next(): IElementType {
            pos = bodyEnd() ?: return super.next()
            return token(Cat.TEXT)
        }

        private fun bodyEnd(): Int? {
            val line = buf.lastIndexOf('\n', pos - 1) + 1
            if (pos == line || buf[pos].isWhitespace() || !onlyIndentBefore() || !underHeader(line)) return null
            val last = generateSequence(line) { start -> (lineEnd(start, buf) + 1).takeIf { it < end && inBody(it) } }
                .last { buf.subSequence(it, lineEnd(it, buf)).isNotBlank() }
            return lineEnd(last, buf).coerceAtMost(end)
        }

        private fun inBody(lineStart: Int): Boolean =
            buf[lineStart] == ' ' || buf[lineStart] == '\t' || buf.subSequence(lineStart, lineEnd(lineStart, buf)).isBlank()

        /** The line above (blank ones aside) is a recipe header: an indented line elsewhere continues an expression. */
        private fun underHeader(lineStart: Int): Boolean {
            val end = (lineStart - 1 downTo 0).firstOrNull { !buf[it].isWhitespace() }?.plus(1) ?: return false
            val header = buf.subSequence(buf.lastIndexOf('\n', end - 1) + 1, end).toString()
            return !header.first().isWhitespace() && colonOf(header) != null
        }
    }

    override val scripts = object : ScriptRules() {
        private val shebang = Regex("""#!.*""")
        private val shells = Regex("""#!.*\b(?:bash|sh|zsh)\b.*""")
        private val prefix = Regex("""[@-]*""")

        /** just writes the value of an interpolation into the line before the shell reads it. */
        override val substitutions = listOf(Regex("""\{\{\{\{""") to "{{", Regex("""\{\{.*?}}""") to Scripts.PLACEHOLDER)

        /** Without the indentation, the `@` / `-` in front of a line, and the `#!` line; none when that line names another interpreter. */
        override fun lines(word: FmtWord, file: FmtFile): List<TextRange> {
            if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat != Cat.TEXT) return emptyList()
            val body = word.text
            val start = word.textRange.startOffset
            val indent = start - (file.viewProvider.contents.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1)
            val lines = Scripts.lines(body, indent = indent)
            val interpreter = shebang.matchAt(body, 0)?.value
                ?: return lines.map { TextRange(it.startOffset + (prefix.matchAt(body, it.startOffset)?.value?.length ?: 0), it.endOffset) }
                    .filter { !it.isEmpty }
            if (!shells.matches(interpreter)) return emptyList()
            return lines.drop(1)
        }
    }

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            val paths = mutableListOf<PathLink>()
            var recipe: String? = null
            /** Names of an expression (strings and function calls left out): parameter of [recipe] first, else variable. */
            fun expr(base: Int, s: String, inRecipe: String?) {
                IDENTIFIER.findAll(code(s)).filter { it.value !in KEYWORDS }.forEach { m ->
                    val keys = listOfNotNull(inRecipe?.let { "param:$it:${m.value}" }, "var:${m.value}")
                    refs += Ref(range(base, m.range), keys)
                }
            }
            /** A recipe, or the module of `module::recipe` (whose recipes are not this file's). */
            fun dependency(base: Int, path: MatchGroup): Ref {
                val module = path.value.substringBefore("::")
                if (module == path.value) return Ref(range(base, path.range), listOf("recipe:$module"))
                return Ref(TextRange.from(base + path.range.first, module.length), listOf("mod:$module"))
            }
            var offset = 0
            var open = 0
            for (line in text.split('\n')) {
                val start = offset
                offset += line.length + 1
                if (line.isBlank()) continue
                if (open > 0) {
                    expr(start, line, null)
                    open += unclosed(line)
                    continue
                }
                if (line[0] == ' ' || line[0] == '\t') {
                    if (recipe == null) continue
                    // recipe body: interpolations and exported names
                    INTERPOLATION.findAll(line).map { it.group(1) }.forEach { g -> expr(start + g.range.first, g.value, recipe) }
                    EXPORTED.findAll(line).map { it.group(1) }.forEach { g ->
                        refs += Ref(range(start, g.range), listOf("param:$recipe:${g.value}", "var:${g.value}"))
                    }
                    continue
                }
                recipe = null
                if (line.startsWith("#") || line.startsWith("[")) continue
                ALIAS.find(line)?.let { m ->
                    decls += Decl(range(start, m.group(1).range), "recipe:${m.group(1).value}")
                    refs += dependency(start, m.group(2))
                    continue
                }
                ASSIGNMENT.find(line)?.let { m ->
                    decls += Decl(range(start, m.group(1).range), "var:${m.group(1).value}")
                    expr(start + m.group(2).range.first, m.group(2).value, null)
                    open = unclosed(m.group(2).value)
                    continue
                }
                SETTING.find(line)?.group(1)?.let { g -> expr(start + g.range.first, g.value, null); continue }
                if (SETTING_LINE.containsMatchIn(line)) continue
                UNEXPORT.find(line)?.group(1)?.let { g -> refs += Ref(range(start, g.range), listOf("var:${g.value}")); continue }
                IMPORT.find(line)?.group(2)?.let { g -> paths += PathLink(range(start, g.range), g.value); continue }
                MODULE.find(line)?.let { m ->
                    val target = m.groups[3] ?: m.group(1)
                    paths += PathLink(range(start, target.range), "mod:" + target.value)
                    continue
                }
                // recipe header: `@name a b='x' +rest $env: dep (dep2 a) && after`
                val header = HEADER.find(line) ?: continue
                val name = header.group(1)
                val rest = header.group(2)
                val colon = colonOf(rest.value) ?: continue
                recipe = name.value
                decls += Decl(range(start, name.range), "recipe:${name.value}")
                val base = start + rest.range.first
                // parameters and their defaults
                PARAMETER.findAll(rest.value.substring(0, colon)).forEach { p ->
                    val n = p.group(2)
                    decls += Decl(range(base, n.range), "param:${name.value}:${n.value}")
                    p.groups[3]?.let { d -> expr(base + d.range.first, d.value, name.value) }
                }
                // dependencies: names, `(name args…)`, after `&&`
                val deps = rest.value.substring(colon + 1).substringBefore('#')
                val depsBase = base + colon + 1
                DEPENDENCY.findAll(deps).forEach { d ->
                    refs += dependency(depsBase, d.groups[1] ?: d.group(3))
                    d.groups[2]?.let { a -> expr(depsBase + a.range.first, a.value, name.value) }
                }
            }
            return Links(decls, refs, paths)
        }

        /** `mod name` → `name.just`, `name/mod.just`, `name/justfile`… (an explicit path may be a folder too). */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            if (!path.startsWith("mod:")) return super.resolvePath(file, path)
            val p = path.removePrefix("mod:")
            val found = super.resolvePath(file, p) ?: super.resolvePath(file, "$p.just")
            val dir = (found as? com.intellij.psi.PsiDirectory) ?: return found ?: super.resolvePath(file, p)
            return listOf("mod.just", "justfile", "Justfile", ".justfile").firstNotNullOfOrNull { dir.findFile(it) } ?: dir
        }

        /** Imported files share names; modules have their own. */
        override fun scope(file: PsiFile): List<PsiFile> =
            listOf(file) + Fmt.links(file).paths.filter { !it.path.startsWith("mod:") }.mapNotNull { resolvePath(file, it.path) as? PsiFile }

        /** `foo` of `foo::recipe` → the name in `mod foo`, which is no declaration: Ctrl+Click on it opens the module. */
        override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? {
            val module = ref.keys.firstOrNull()?.takeIf { it.startsWith("mod:") }?.removePrefix("mod:") ?: return null
            return Fmt.scope(file).flatMap { f ->
                MODULE_NAME.findAll(f.text).map { it.group(1) }.filter { it.value == module }.mapNotNull { f.findElementAt(it.range.first)?.parent }
            }
        }
    }

    /** [s] at the same offsets, without its strings (blanked) nor its comment. */
    private fun code(s: String) = STRING.replace(s) { " ".repeat(it.value.length) }.substringBefore('#')

    /** How many brackets [s] opens without closing them: the expression goes on over the next lines. */
    private fun unclosed(s: String) = code(s).let { c -> c.count { it in "({[" } - c.count { it in ")}]" } }

    /** The header's `:` (not `:=`, not inside quotes or parentheses), or null when the line is no recipe. */
    private fun colonOf(s: String): Int? {
        var quote: Char? = null
        var depth = 0
        for ((i, c) in s.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' -> depth++
                c == ')' -> depth--
                c == '#' -> return null
                c == ':' && depth == 0 -> return i.takeIf { s.getOrNull(i + 1) != '=' }
            }
        }
        return null
    }
}
