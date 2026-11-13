package me.kevinlot.lite.formats

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/* Formats whose names point to each other: Ctrl+Click, rename and Find Usages through their Linker. */

private fun ranges(regex: String, text: CharSequence, group: Int = 1) = Regex(regex).ranges(text, group)

/** HOCON (Typesafe/Lightbend config): `${a.b.c}` → the key `a.b.c` (nested blocks included), `include "x.conf"`. */
object Hocon : Format("Hocon", "HOCON", "conf") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule("""(?:#|//)[^\n]*""", Cat.COMMENT),
        Rule(""""{3}[\s\S]*?(?:"{3}|\z)""", Cat.STRING),
        Rule("""\$\{\??[^}\n]*}""", Cat.VARIABLE),
        Rule("""\binclude\b""", Cat.KEYWORD),
        Rule("""\b(?:file|url|classpath|required)(?=\()""", Cat.FUNCTION),
        Rule("""(?:[\w.-]+|"[^"\n]*")(?=\s*(?:=|:|\+=|\{))""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule("""\b(?:true|false|null|yes|no|on|off)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val stack = ArrayDeque<String>()
            var pendingBlock: String? = null
            val key = Regex("""(?:[\w.-]+|"[^"\n]*")(?=\s*(=|:|\+=|\{))""")
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '#' || (c == '/' && i + 1 < text.length && text[i + 1] == '/') -> { while (i < text.length && text[i] != '\n') i++; continue }
                    c == '"' && text.startsWith("\"\"\"", i) -> { i = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }; continue }
                    c == '$' && i + 1 < text.length && text[i + 1] == '{' -> { i = text.indexOf('}', i).let { if (it < 0) text.length else it + 1 }; continue }
                    c == '{' -> { stack.addLast(pendingBlock ?: stack.lastOrNull() ?: ""); pendingBlock = null }
                    c == '}' -> { stack.removeLastOrNull() }
                    c == '"' || c.isLetterOrDigit() || c == '_' -> {
                        val m = key.matchAt(text, i)
                        if (m != null) {
                            val raw = m.value.trim('"')
                            val full = listOfNotNull(stack.lastOrNull()?.takeIf { it.isNotEmpty() }, raw).joinToString(".")
                            val last = raw.substringAfterLast('.')
                            val start = m.range.last + 1 - (if (m.value.endsWith('"')) 1 else 0) - last.length
                            decls += Decl(TextRange(start, start + last.length), "key:$full")
                            if (m.groupValues[1] == "{" || text.substring(m.range.last + 1).trimStart().let { it.startsWith("{") || it.startsWith("={") || it.startsWith(":{") || it.startsWith("= {") || it.startsWith(": {") }) pendingBlock = full
                            i = m.range.last + 1
                            continue
                        }
                        if (c == '"') { i = text.indexOf('"', i + 1).let { if (it < 0) text.length else it + 1 }; continue }
                    }
                }
                i++
            }
            val refs = Regex("""\$\{\??\s*([\w.-]+)\s*}""").findAll(text).map { m ->
                val g = m.groups[1]!!
                val last = g.value.substringAfterLast('.')
                Ref(TextRange(g.range.first, g.range.last + 1), listOf("key:${g.value}"), TextRange(g.range.last + 1 - last.length, g.range.last + 1))
            }.toList()
            val paths = ranges("""\binclude\s+(?:required\(\s*)?(?:file\(\s*)?"([^"]+)"""", text).map { (r, p) -> PathLink(r, p) }.toList()
            return Links(decls, refs, paths)
        }
    }
}

/** Dockerfile: `$VAR` → `ARG`/`ENV`; `COPY --from=build` / `FROM build` → `FROM … AS build`. */
object Dockerfile : Format("Dockerfile", "Dockerfile", "dockerfile") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.WORD, Cat.VARIABLE, Cat.ATTRIBUTE, Cat.STRING)
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
            val decls = ranges("""(?mi)^\s*ARG\s+([A-Za-z_]\w*)""", text).map { (r, n) -> Decl(r, "var:$n") } +
                Regex("""(?mi)^\s*ENV\s+(.*)$""").findAll(text).flatMap { line ->
                    val base = line.groups[1]!!.range.first
                    val body = line.groupValues[1]
                    if (body.contains('=')) Regex("""(?:^|\s)([A-Za-z_]\w*)=""").ranges(body).map { (r, n) -> Decl(r.shiftRight(base), "var:$n") }
                    else Regex("""^([A-Za-z_]\w*)""").ranges(body).map { (r, n) -> Decl(r.shiftRight(base), "var:$n") }
                } +
                ranges("""(?mi)^\s*FROM\s+\S+\s+AS\s+([\w.-]+)""", text).map { (r, n) -> Decl(r, "stage:${n.lowercase()}") }
            val refs = ranges("""\$\{?([A-Za-z_]\w*)""", text).map { (r, n) -> Ref(r, listOf("var:$n")) } +
                ranges("""--from=([\w.-]+)""", text).map { (r, n) -> Ref(r, listOf("stage:${n.lowercase()}")) } +
                ranges("""(?mi)^\s*FROM\s+([\w.-]+)""", text).map { (r, n) -> Ref(r, listOf("stage:${n.lowercase()}")) }
            return Links(decls.toList(), refs.toList())
        }
    }
}

/**
 * CSS: `var(--x)` → `--x:`; `@import`/`url()` → the file. SCSS adds `$x`, `@mixin`/`@include`, `@function`, `@use`;
 * the indented Sass syntax ([indented]) also `=mixin` / `+mixin`, without braces and semicolons.
 */
open class StyleFormat(id: String, display: String, ext: String, private val scss: Boolean, private val indented: Boolean = false) : Format(id, display, ext) {
    override val blockComment = "/*" to "*/"
    override val lineComment: String? = if (scss) "//" else null
    override val nameCategories = setOf(Cat.VARIABLE, Cat.WORD, Cat.STRING, Cat.DECL, Cat.FUNCTION)
    override val rules = listOfNotNull(
        Rule(R.BLOCK, Cat.COMMENT),
        if (scss) Rule("""(?<!:)//[^\n]*""", Cat.COMMENT) else null,
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""(?<=url\()\s*[^"')\s][^)\s]*""", Cat.STRING),
        Rule("""@[\w-]+""", Cat.KEYWORD),
        if (indented) Rule("""=\s*[\w-]+""", Cat.DECL, lineStart = true) else null,
        if (indented) Rule("""\+\s*[\w-]+""", Cat.FUNCTION, lineStart = true) else null,
        Rule("""--[\w-]+""", Cat.VARIABLE),
        if (scss) Rule("""\$[\w-]+""", Cat.VARIABLE) else null,
        Rule("""#[0-9a-fA-F]{3,8}\b""", Cat.CONSTANT),
        Rule("""!important\b""", Cat.KEYWORD),
        Rule("""[a-zA-Z-]+(?=\s*:[^;{}\n]*(?:;|}|\n|$))""", Cat.KEY),
        Rule("""[\w-]+(?=\()""", Cat.FUNCTION),
        Rule("""[.#][\w-]+""", Cat.TYPE),
        Rule("""::?[\w-]+""", Cat.META),
        Rule("""-?(?:\d+\.?\d*|\.\d+)(?:%|[a-zA-Z]+)?""", Cat.NUMBER),
    )
    override val linker: Linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            ranges("""(--[\w-]+)\s*:""", text).forEach { (r, n) -> decls += Decl(r, "prop:$n") }
            ranges("""var\(\s*(--[\w-]+)""", text).forEach { (r, n) -> refs += Ref(r, listOf("prop:$n")) }
            if (scss) {
                val declared = ranges("""\$([\w-]+)\s*:""", text).toList()
                declared.forEach { (r, n) -> decls += Decl(r, "var:$n") }
                ranges("""\$([\w-]+)""", text).filter { (r, _) -> declared.none { it.first == r } }.forEach { (r, n) -> refs += Ref(r, listOf("var:$n")) }
                ranges("""@mixin\s+([\w-]+)""", text).forEach { (r, n) -> decls += Decl(r, "mixin:$n") }
                ranges("""@include\s+([\w-]+)""", text).forEach { (r, n) -> refs += Ref(r, listOf("mixin:$n")) }
                if (indented) {
                    ranges("""(?m)^\s*=\s*([\w-]+)""", text).forEach { (r, n) -> decls += Decl(r, "mixin:$n") }
                    ranges("""(?m)^\s*\+\s*([\w-]+)""", text).forEach { (r, n) -> refs += Ref(r, listOf("mixin:$n")) }
                }
                ranges("""@function\s+([\w-]+)""", text).forEach { (r, n) -> decls += Decl(r, "fn:$n") }
                // calls of @function: not mixin heads (`@mixin card(`, `=rounded(`, `+rounded(`) nor CSS functions
                ranges("""(?<![@\w$=+-])(?<!@mixin )(?<!@include )(?<!@function )([\w-]+)\(""", text)
                    .filter { (_, n) -> n !in CSS_FUNCTIONS }.forEach { (r, n) -> refs += Ref(r, listOf("fn:$n")) }
            }
            val paths = (ranges("""@(?:import|use|forward)\s+["']([^"']+)""", text) +
                ranges("""url\(\s*["']?([^"')\s]+)""", text)).filter { (_, p) -> !Regex("^(?:[a-z]+:|//|#)").containsMatchIn(p) }
                .map { (r, p) -> PathLink(r, p) }.toList()
            return Links(decls, refs, paths)
        }

        /** SCSS partials: `@use "base"` is `_base.scss`. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            super.resolvePath(file, path)?.let { return it }
            if (!scss) return null
            val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val name = path.substringAfterLast('/')
            return listOf("$dir$name.scss", "${dir}_$name.scss", "$dir$name.sass", "${dir}_$name.sass", "${dir}_$name", "$dir$name/_index.scss", "$dir$name/_index.sass", "$dir$name.css")
                .firstNotNullOfOrNull { super.resolvePath(file, it) }
        }

        /** Custom properties are global: every stylesheet of the project, after the file and its imports. */
        override fun scope(file: PsiFile): List<PsiFile> {
            val project = file.project
            val manager = PsiManager.getInstance(project)
            val own = super.scope(file)
            val all = listOf(Css, Scss, Sass).flatMap { f -> FileTypeIndex.getFiles(FMT_FILE_TYPES.getValue(f), GlobalSearchScope.projectScope(project)) }
                .take(500).mapNotNull { manager.findFile(it) }
            return own + all
        }
    }
}

private val CSS_FUNCTIONS = setOf(
    "var", "calc", "min", "max", "clamp", "url", "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix",
    "linear-gradient", "radial-gradient", "conic-gradient", "repeating-linear-gradient", "translate", "translateX", "translateY", "rotate",
    "scale", "skew", "matrix", "perspective", "cubic-bezier", "steps", "attr", "env", "format", "local", "image-set", "minmax", "repeat",
    "fit-content", "blur", "brightness", "contrast", "drop-shadow", "grayscale", "opacity", "saturate", "sepia", "not", "is", "where", "has",
    "nth-child", "nth-of-type", "lang", "dir", "if", "map-get", "darken", "lighten", "mix", "percentage", "round", "abs",
)

object Css : StyleFormat("Css", "CSS", "css", scss = false)
object Scss : StyleFormat("Scss", "SCSS", "scss", scss = true)
object Sass : StyleFormat("Sass", "Sass", "sass", scss = true, indented = true)

/** Makefile: `$(VAR)` → its assignment, prerequisites → their rule, `include` → the file. */
object Make : Format("Make", "Makefile", "mk") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT),
        Rule("""(?:-?include|sinclude|ifeq|ifneq|ifdef|ifndef|else|endif|define|endef|export|unexport|override|vpath|undefine)\b""", Cat.KEYWORD, lineStart = true),
        Rule("""(?<=include[ \t])[^\s#][^\n#]*""", Cat.STRING),
        Rule("""\.(?:PHONY|DEFAULT|SUFFIXES|PRECIOUS|INTERMEDIATE|SECONDARY|DELETE_ON_ERROR|SILENT|ONESHELL|NOTPARALLEL|EXPORT_ALL_VARIABLES|DEFAULT_GOAL)\b""", Cat.META),
        Rule("""(?<![^\n])[A-Za-z_][\w.-]*(?=\s*(?:::=|:=|\?=|\+=|!=|=))""", Cat.DECL),
        Rule("""(?<![^\n])[^\s:=#.][^:=#\n]*?(?=:(?!=))""", Cat.DECL),
        Rule("""\$[({][^)}\s]*[)}]?|\$[A-Za-z@<^?*%+|]""", Cat.VARIABLE),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            ranges("""(?m)^(?:override\s+|export\s+)?([A-Za-z_][\w.-]*)\s*(?:::=|:=|\?=|\+=|!=|=)""", text).forEach { (r, n) -> decls += Decl(r, "var:$n") }
            Regex("""(?m)^([^\s:=#.][^:=#\n]*?):(?!=)([^\n#;]*)""").findAll(text).forEach { m ->
                val targets = m.groups[1]!!
                Regex("""[^\s]+""").findAll(targets.value).filter { '%' !in it.value && '$' !in it.value }.forEach { t ->
                    decls += Decl(TextRange(targets.range.first + t.range.first, targets.range.first + t.range.last + 1), "target:${t.value}")
                }
                val prereqs = m.groups[2]!!
                Regex("""[^\s|]+""").findAll(prereqs.value).filter { '$' !in it.value && '%' !in it.value }.forEach { p ->
                    refs += Ref(TextRange(prereqs.range.first + p.range.first, prereqs.range.first + p.range.last + 1), listOf("target:${p.value}"))
                }
            }
            ranges("""\$[({]([A-Za-z_][\w.-]*)[)}]""", text).forEach { (r, n) -> refs += Ref(r, listOf("var:$n")) }
            val paths = Regex("""(?m)^-?s?include\s+([^\n#]+)""").findAll(text).flatMap { m ->
                val g = m.groups[1]!!
                Regex("""[^\s]+""").findAll(g.value).filter { '$' !in it.value }.map { PathLink(TextRange(g.range.first + it.range.first, g.range.first + it.range.last + 1), it.value) }
            }.toList()
            return Links(decls, refs, paths)
        }
    }
}

/**
 * HCL (Terraform, Terragrunt): `var.x`, `local.x`, `module.x`, `data.t.n`, `t.n` (resources), `dependency.x`
 * → their blocks, across the `.tf` files of the folder (a Terraform module); `.tfvars` keys → `variable`;
 * local `source`/`config_path`/`path` → the folder or file.
 */
object Hcl : Format("Hcl", "HCL", "tf") {
    override val lineComment = "#"
    override val blockComment = "/*" to "*/"
    override val nameCategories = setOf(Cat.VARIABLE, Cat.STRING, Cat.KEY, Cat.WORD)
    private val RESERVED = setOf("var", "local", "module", "data", "dependency", "each", "count", "path", "terraform", "self", "include")
    override val rules = listOf(
        Rule("""(?:#|//)[^\n]*""", Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""<<-?(\w+)[\s\S]*?\n\s*\1(?=\s*$)""", Cat.STRING, flags = java.util.regex.Pattern.MULTILINE),
        Rule(""""(?:\\.|\$\{[^}]*}|[^"\\\n])*"?""", Cat.STRING),
        Rule("""(?:resource|data|variable|output|module|provider|locals|terraform|backend|required_providers|dynamic|moved|import|check|removed|include|dependency|dependencies|generate|remote_state|inputs)\b""", Cat.KEYWORD, lineStart = true),
        Rule("""\b(?:true|false|null)\b""", Cat.CONSTANT),
        Rule("""\b(?:for|in|if|endfor|endif)\b""", Cat.KEYWORD),
        Rule("""[A-Za-z_][\w-]*(?=\s*=(?!=))""", Cat.KEY),
        Rule("""[a-z_][\w]*(?=\()""", Cat.FUNCTION),
        Rule("""[A-Za-z_][\w-]*(?:\.(?:[A-Za-z_*][\w-]*|\d+)|\[[^\]\n]*])+""", Cat.VARIABLE),
        Rule(R.NUMBER, Cat.NUMBER),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            for ((kind, block) in listOf("var" to "variable", "module" to "module", "output" to "output", "dep" to "dependency")) {
                ranges("""(?m)^\s*$block\s+"([^"]+)"""", text).forEach { (r, n) -> decls += Decl(r, "$kind:$n") }
            }
            for ((kind, block) in listOf("res" to "resource", "data" to "data")) {
                Regex("""(?m)^\s*$block\s+"([^"]+)"\s+"([^"]+)"""").findAll(text).forEach { m ->
                    val g = m.groups[2]!!
                    decls += Decl(TextRange(g.range.first, g.range.last + 1), "$kind:${m.groupValues[1]}.${g.value}")
                }
            }
            // keys of `locals { … }` blocks (depth 1 only)
            Regex("""(?m)^\s*locals\s*\{""").findAll(text).forEach { m ->
                var depth = 1
                var i = m.range.last + 1
                val start = i
                while (i < text.length && depth > 0) { if (text[i] == '{') depth++ else if (text[i] == '}') depth--; i++ }
                val body = text.subSequence(start, i)
                var d = 0
                var lineStart = 0
                for (j in body.indices) {
                    if (j == lineStart && d == 0) Regex("""^\s*([A-Za-z_][\w-]*)\s*=(?!=)""").find(body.subSequence(j, body.length))?.let { k ->
                        val g = k.groups[1]!!
                        decls += Decl(TextRange(start + j + g.range.first, start + j + g.range.last + 1), "local:${g.value}")
                    }
                    when (body[j]) { '{', '[', '(' -> d++; '}', ']', ')' -> d--; '\n' -> lineStart = j + 1 }
                }
            }
            Regex("""\b(var|local|module|dependency)\.([A-Za-z_][\w-]*)""").findAll(text).forEach { m ->
                val g = m.groups[2]!!
                val kind = when (m.groupValues[1]) { "var" -> "var"; "local" -> "local"; "module" -> "module"; else -> "dep" }
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("$kind:${g.value}"))
            }
            Regex("""\bdata\.([A-Za-z_][\w-]*)\.([A-Za-z_][\w-]*)""").findAll(text).forEach { m ->
                val g = m.groups[2]!!
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("data:${m.groupValues[1]}.${g.value}"))
            }
            Regex("""(?<![\w.])([a-z][a-z0-9]*_[\w-]+)\.([A-Za-z_][\w-]*)""").findAll(text).forEach { m ->
                if (m.groupValues[1] in RESERVED) return@forEach
                val g = m.groups[2]!!
                refs += Ref(TextRange(g.range.first, g.range.last + 1), listOf("res:${m.groupValues[1]}.${g.value}"))
            }
            if (fileName.endsWith(".tfvars")) ranges("""(?m)^\s*([A-Za-z_][\w-]*)\s*=""", text).forEach { (r, n) -> refs += Ref(r, listOf("var:$n")) }
            val paths = ranges("""\b(?:source|config_path|path)\s*=\s*"(\.\.?/[^"]*)"""", text).map { (r, p) -> PathLink(r, p.replace("//", "/")) } +
                ranges("""\b(?:file|templatefile|fileexists)\(\s*"(?:\$\{path\.module}/)?([^"$]+)"""", text).map { (r, p) -> PathLink(r, p) }
            return Links(decls, refs, paths.toList())
        }

        /** A Terraform module is a folder: its `.tf`/`.tfvars` files share their names. Terragrunt files stand alone. */
        override fun scope(file: PsiFile): List<PsiFile> {
            if (file.name.endsWith(".hcl")) return listOf(file)
            val dir = file.originalFile.containingDirectory ?: return listOf(file)
            return listOf(file) + dir.files.filter { it != file && (it.name.endsWith(".tf") || it.name.endsWith(".tfvars")) }
        }
    }
}

/** The file type of each format (by format), for lookups by type. */
val FMT_FILE_TYPES: Map<Format, FmtFileType> by lazy { ALL_FILE_TYPES.associateBy { it.format } }
