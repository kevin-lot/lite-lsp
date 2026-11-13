package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/* Task runners: Makefile and justfile (names of variables and targets/recipes point to each other). */

private fun TextRange.shift(by: Int) = shiftRight(by)
private fun range(base: Int, r: IntRange) = TextRange(base + r.first, base + r.last + 1)

/**
 * GNU Make: `$(VAR)`, `${VAR}`, `$(VAR:.c=.o)`, `$(call fn,…)`, `$(value VAR)`, `ifdef VAR`, `export VAR` → assignment
 * or `define`; prerequisites (and `.PHONY` names) → their rule, or the source file; `include` → file. Variables and targets are shared with
 * the included makefiles and with the makefiles that include this one.
 */
object Make : Format("Make", "Makefile", "mk") {
    override val lineComment = "#"
    private const val NAME = """[A-Za-z_][\w.-]*"""
    private val FUNCTIONS = listOf(
        "call", "value", "origin", "flavor", "eval", "info", "warning", "error", "shell", "wildcard", "patsubst", "subst", "strip",
        "findstring", "filter", "filter-out", "sort", "word", "words", "wordlist", "firstword", "lastword", "dir", "notdir", "suffix",
        "basename", "addsuffix", "addprefix", "join", "realpath", "abspath", "if", "or", "and", "foreach", "file", "let", "intcmp", "guile",
    )
    /** Functions whose first argument is a variable name. */
    private val NAME_FUNCTIONS = setOf("call", "value", "origin", "flavor")

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

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            val filePrereqs = mutableListOf<PathLink>()
            fun ref(r: TextRange, n: String) { refs += Ref(r, listOf("var:$n")) }
            Regex("""(?m)^(?:(?:override|export|private)\s+)*($NAME)\s*(?::::=|::=|:=|\?=|\+=|!=|=)""").ranges(text).forEach { (r, n) -> decls += Decl(r, "var:$n") }
            Regex("""(?m)^define\s+($NAME)""").ranges(text).forEach { (r, n) -> decls += Decl(r, "var:$n") }
            // rules: `a b: c d | e` (special targets such as .PHONY declare nothing; their names are references)
            Regex("""(?m)^([^\s:=#][^:=#\n]*?)::?(?!=)([^\n#;]*)""").findAll(text).forEach { m ->
                val targets = m.groups[1]!!
                val prereqs = m.groups[2]!!
                if (Regex("""^\s*(?:(?:override|export|private)\s+)*$NAME\s*(?::=|\?=|\+=|!=|=)""").containsMatchIn(prereqs.value)) return@forEach // target-specific variable
                if (!targets.value.startsWith(".")) Regex("""\S+""").findAll(targets.value).filter { '%' !in it.value && '$' !in it.value }.forEach { t ->
                    decls += Decl(range(targets.range.first, t.range), "target:${t.value}")
                }
                Regex("""[^\s|]+""").findAll(prereqs.value).filter { '$' !in it.value && '%' !in it.value && it.value != ":" }.forEach { p ->
                    val r = range(prereqs.range.first, p.range)
                    refs += Ref(r, listOf("target:${p.value}"))
                    // a source file (`main.o: main.c util.h`)
                    if ('.' in p.value || '/' in p.value) filePrereqs += PathLink(r, p.value)
                }
            }
            Regex("""(?<!\$)\$[({]($NAME)(?=[)}:])""").ranges(text).forEach { (r, n) -> ref(r, n) }
            Regex("""(?<!\$)\$[({](?:${NAME_FUNCTIONS.joinToString("|")})\s+($NAME)(?=[\s,)}])""").ranges(text).forEach { (r, n) -> ref(r, n) }
            Regex("""(?m)^\s*(?:ifdef|ifndef|undefine|export|unexport)\s+($NAME)\s*$""").ranges(text).forEach { (r, n) -> ref(r, n) }
            val paths = Regex("""(?m)^-?s?include\s+([^\n#]+)""").findAll(text).flatMap { m ->
                val g = m.groups[1]!!
                Regex("""\S+""").findAll(g.value).filter { '$' !in it.value }.map { PathLink(range(g.range.first, it.range), it.value) }
            }.toList()
            // sources only: not another rule's target, nor a build product
            val targets = decls.mapTo(HashSet()) { it.key }
            val sources = filePrereqs.filter { "target:${it.path}" !in targets && !Regex("""\.(?:o|a|so|obj|lo|d)$""").containsMatchIn(it.path) }
            return Links(decls, refs, paths + sources)
        }

        /** The included makefiles (transitively) and the project makefiles that include this one. */
        override fun scope(file: PsiFile): List<PsiFile> {
            val all = FileTypeIndex.getFiles(MakeFileType, GlobalSearchScope.projectScope(file.project))
                .mapNotNull { PsiManager.getInstance(file.project).findFile(it) }
            fun includes(f: PsiFile) = Fmt.links(f).paths.mapNotNull { resolvePath(f, it.path) as? PsiFile }.filter { it.fileType == MakeFileType }
            val seen = linkedSetOf(file.originalFile)
            val queue = ArrayDeque(seen)
            while (queue.isNotEmpty() && seen.size < 50) {
                val f = queue.removeFirst()
                (includes(f) + all.filter { o -> includes(o).any { it == f } }).forEach { if (seen.add(it)) queue += it }
            }
            return listOf(file) + seen.drop(1)
        }
    }
}

/**
 * just (justfile): `{{name}}` and `$name` in recipe bodies, expressions of assignments, parameter defaults and
 * dependency arguments → the recipe's parameter, else the variable; dependencies and `alias` → recipes;
 * `import 'x.just'` and `mod name` → file.
 */
object Just : Format("Just", "Just", "just") {
    override val lineComment = "#"
    private const val ID = """[A-Za-z_][\w-]*"""
    private val KEYWORDS = setOf("if", "else", "true", "false", "alias", "export", "unexport", "import", "mod", "set")

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

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            val paths = mutableListOf<PathLink>()
            var recipe: String? = null
            /** Names of an expression (strings and function calls left out): parameter of [recipe] first, else variable. */
            fun expr(base: Int, s: String, inRecipe: String?) {
                val masked = Regex("""'[^']*'?|"(?:\\.|[^"\\])*"?|`[^`]*`?""").replace(s) { " ".repeat(it.value.length) }
                Regex("""(?<![\w-])($ID)(?![\w-])(?!\s*\()""").findAll(masked).filter { it.value !in KEYWORDS }.forEach { m ->
                    val keys = listOfNotNull(inRecipe?.let { "param:$it:${m.value}" }, "var:${m.value}")
                    refs += Ref(range(base, m.range), keys)
                }
            }
            var offset = 0
            for (line in text.split('\n')) {
                val start = offset
                offset += line.length + 1
                if (line.isBlank()) continue
                if (line[0] == ' ' || line[0] == '\t') {
                    if (recipe == null) continue
                    // recipe body: interpolations and exported names
                    Regex("""\{\{(?!\{)(.*?)}}""").findAll(line).forEach { m -> expr(start + m.groups[1]!!.range.first, m.groups[1]!!.value, recipe) }
                    Regex("""(?<!\$)\$($ID)""").findAll(line).forEach { m ->
                        val g = m.groups[1]!!
                        refs += Ref(range(start, g.range), listOf("param:$recipe:${g.value}", "var:${g.value}"))
                    }
                    continue
                }
                recipe = null
                if (line.startsWith("#") || line.startsWith("[")) continue
                Regex("""^alias\s+($ID)\s*:=\s*($ID)""").find(line)?.let { m ->
                    decls += Decl(range(start, m.groups[1]!!.range), "recipe:${m.groups[1]!!.value}")
                    refs += Ref(range(start, m.groups[2]!!.range), listOf("recipe:${m.groups[2]!!.value}"))
                    continue
                }
                Regex("""^(?:export\s+)?($ID)\s*:=(.*)""").find(line)?.let { m ->
                    decls += Decl(range(start, m.groups[1]!!.range), "var:${m.groups[1]!!.value}")
                    expr(start + m.groups[2]!!.range.first, m.groups[2]!!.value, null)
                    continue
                }
                Regex("""^set\s+[\w-]+\s*:=(.*)""").find(line)?.let { m -> expr(start + m.groups[1]!!.range.first, m.groups[1]!!.value, null); continue }
                if (Regex("""^set\b""").containsMatchIn(line)) continue
                Regex("""^unexport\s+($ID)""").find(line)?.let { m -> refs += Ref(range(start, m.groups[1]!!.range), listOf("var:${m.groups[1]!!.value}")); continue }
                Regex("""^import\??\s+(['"])([^'"\n]+)\1""").find(line)?.let { m -> paths += PathLink(range(start, m.groups[2]!!.range), m.groups[2]!!.value); continue }
                Regex("""^mod\??\s+($ID)(?:\s+(['"])([^'"\n]+)\2)?""").find(line)?.let { m ->
                    val explicit = m.groups[3]
                    paths += if (explicit != null) PathLink(range(start, explicit.range), "mod:" + explicit.value)
                    else PathLink(range(start, m.groups[1]!!.range), "mod:" + m.groups[1]!!.value)
                    continue
                }
                // recipe header: `@name a b='x' +rest $env: dep (dep2 a) && after`
                val header = Regex("""^@?($ID)([^\n]*)""").find(line) ?: continue
                val name = header.groups[1]!!
                val rest = header.groups[2]!!
                val colon = colonOf(rest.value) ?: continue
                recipe = name.value
                decls += Decl(range(start, name.range), "recipe:${name.value}")
                val base = start + rest.range.first
                // parameters and their defaults
                Regex("""([+*]?\$?)($ID)(?:\s*=\s*('[^']*'|"(?:\\.|[^"\\])*"|`[^`]*`|\([^)]*\)|$ID))?""").findAll(rest.value.substring(0, colon)).forEach { p ->
                    val n = p.groups[2]!!
                    decls += Decl(range(base, n.range), "param:${name.value}:${n.value}")
                    p.groups[3]?.let { d -> expr(base + d.range.first, d.value, name.value) }
                }
                // dependencies: names, `(name args…)`, after `&&`
                val deps = rest.value.substring(colon + 1).substringBefore('#')
                val depsBase = base + colon + 1
                Regex("""\(\s*($ID)([^)]*)\)|(?<![\w-])($ID)(?![\w-])""").findAll(deps).forEach { d ->
                    val dn = d.groups[1] ?: d.groups[3]!!
                    refs += Ref(range(depsBase, dn.range), listOf("recipe:${dn.value}"))
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
    }

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
                c == ':' && depth == 0 -> return if (s.getOrNull(i + 1) == '=') null else i
            }
        }
        return null
    }
}
