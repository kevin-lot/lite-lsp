package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern

/* Formats that only need coloring (and YAML/.env, whose names are file-local). */

internal object R {
    const val HASH = """#[^\n]*"""
    const val SLASH = """//[^\n]*"""
    const val BLOCK = """/\*[\s\S]*?(?:\*/|\z)"""
    const val DQ = """"(?:\\.|[^"\\\n])*"?"""
    const val SQ = """'(?:\\.|[^'\\\n])*'?"""
    const val NUMBER = """(?<![\w.])[-+]?(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][-+]?\d+)?)(?![\w])"""
}

object Toml : Format("Toml", "TOML", "toml") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT),
        Rule("""\[\[[^\]\n]*]]""", Cat.SECTION, lineStart = true),
        Rule("""\[[^\]\n]*]""", Cat.SECTION, lineStart = true),
        Rule(""""{3}[\s\S]*?(?:"{3}|\z)""", Cat.STRING),
        Rule("""'{3}[\s\S]*?(?:'{3}|\z)""", Cat.STRING),
        Rule("""(?:[\w-]+|"[^"\n]*")(?:\s*\.\s*(?:[\w-]+|"[^"\n]*"))*(?=\s*=)""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule("""'[^'\n]*'?""", Cat.STRING),
        Rule("""\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})?)?|\d{2}:\d{2}:\d{2}(?:\.\d+)?""", Cat.CONSTANT),
        Rule("""\b(?:true|false|inf|nan)\b""", Cat.KEYWORD),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}

/** YAML: `*alias` → `&anchor` (in the same document). */
object Yaml : Format("Yaml", "YAML", "yaml") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule("""(?<!\S)#[^\n]*""", Cat.COMMENT),
        Rule("""(?:---|\.\.\.)(?=\s|$)""", Cat.META, lineStart = true),
        Rule("""-(?=\s)""", Cat.OPERATOR),
        Rule("""&[\w.-]+""", Cat.DECL),
        Rule("""\*[\w.-]+""", Cat.VARIABLE),
        Rule("""!{1,2}[\w./:-]*""", Cat.META),
        Rule("""<<(?=\s*:)""", Cat.OPERATOR),
        Rule("""(?:"(?:\\.|[^"\\\n])*"|'(?:''|[^'\n])*')(?=\s*:(?:\s|$))""", Cat.KEY),
        Rule("""[^\s#:'"{}\[\],&*!|>%@`][^:#\n{}\[\],]*?(?=\s*:(?:\s|$))""", Cat.KEY),
        Rule("""(?<=\${'$'}ref['"]?:[ \t]{1,8})(?:[^\s#'"{\[]|\{[^}\s]*})(?:[^\s,{}\]]|\{[^}\s]*})*""", Cat.STRING),
        Rule(R.DQ, Cat.STRING),
        Rule("""'(?:''|[^'\n])*'?""", Cat.STRING),
        Rule("""[|>][-+]?\d*(?=[ \t]*(?:#[^\n]*)?$)""", Cat.OPERATOR, flags = Pattern.MULTILINE),
        Rule("""\b(?:true|false|True|False|TRUE|FALSE|null|Null|NULL)\b|~(?=\s|$)""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
    /** Anchors; and, for `$ref` (OpenAPI, AsyncAPI, JSON Schema), every key as a JSON pointer target. */
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String) = Links(
            decls = Regex("""&([\w.-]+)""").ranges(text).map { (r, n) -> Decl(r, "anchor:$n") }.toList() +
                Pointers.yamlKeys(text).map { (r, p) -> Decl(r, "key:$p") },
            refs = Regex("""(?<![\w*])\*([\w.-]+)""").ranges(text).map { (r, n) -> Ref(r, listOf("anchor:$n")) }.toList() +
                Pointers.yamlRefs(text),
        )
        override fun resolve(file: PsiFile, ref: Ref) = Pointers.resolveYaml(file, ref, this)
    }
}

object Kdl : Format("Kdl", "KDL", "kdl") {
    override val lineComment = "//"
    override val blockComment = "/*" to "*/"
    override val rules = listOf(
        Rule(R.SLASH, Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""/-""", Cat.COMMENT),
        Rule(""""{3}[\s\S]*?(?:"{3}|\z)""", Cat.STRING),
        Rule("""r?(#+)"[\s\S]*?"\1""", Cat.STRING),
        Rule(R.DQ, Cat.STRING),
        Rule("""#(?:true|false|null|inf|-inf|nan)\b|\b(?:true|false|null)\b""", Cat.CONSTANT),
        Rule("""\([^)\n]*\)""", Cat.TYPE),
        Rule("""[^\s{}()\[\]=;"#/\\]+(?==)""", Cat.ATTRIBUTE),
        Rule(R.NUMBER, Cat.NUMBER),
        Rule("""[^\s{}()\[\]=;"#/\\]+""", Cat.KEY, lineStart = true),
    )
}

/** INI and its relatives: `.properties`, `.editorconfig`, `.gitconfig`, systemd units, `.desktop`. */
object Ini : Format("Ini", "INI", "ini") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule("""[;#!][^\n]*""", Cat.COMMENT, lineStart = true),
        Rule("""\[[^\]\n]*]""", Cat.SECTION, lineStart = true),
        Rule("""[^=:\s\[;#!][^=:\n]*?(?=\s*[=:])""", Cat.KEY, lineStart = true),
        Rule(R.DQ, Cat.STRING),
        Rule("""\$\{[^}\n]*}|%\([\w.-]+\)s""", Cat.VARIABLE),
        Rule("""(?i)\b(?:true|false|yes|no|on|off)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}

/** `.env`: `${VAR}` / `$VAR` → `VAR=` (in the same file). */
object Env : Format("Env", "Dotenv", "env") {
    override val lineComment = "#"
    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT, lineStart = true),
        Rule("""export\b""", Cat.KEYWORD, lineStart = true),
        Rule("""[A-Za-z_][\w.]*(?=\s*=)""", Cat.DECL),
        Rule(""""(?:\\.|[^"\\])*"?""", Cat.STRING),
        Rule("""'[^']*'?""", Cat.STRING),
        Rule("""\$\{[^}\n]*}|\$[A-Za-z_]\w*""", Cat.VARIABLE),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val single = Regex("""'[^']*'""").findAll(text).map { TextRange(it.range.first, it.range.last + 1) }.toList()
            return Links(
                decls = Regex("""(?m)^\s*(?:export\s+)?([A-Za-z_][\w.]*)\s*=""").ranges(text).map { (r, n) -> Decl(r, "var:$n") }.toList(),
                refs = Regex("""\$\{?([A-Za-z_]\w*)""").ranges(text).filter { (r, _) -> single.none { it.contains(r) } }
                    .map { (r, n) -> Ref(r, listOf("var:$n")) }.toList(),
            )
        }
    }
}

object Json5 : Format("Json5", "JSON5", "json5") {
    override val lineComment = "//"
    override val blockComment = "/*" to "*/"
    override val rules = listOf(
        Rule(R.SLASH, Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""(?:"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|[A-Za-z_$][\w$]*)(?=\s*:)""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""\b(?:true|false|null|Infinity|NaN)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}

object Sql : Format("Sql", "SQL", "sql") {
    override val lineComment = "--"
    override val blockComment = "/*" to "*/"
    override val rules = listOf(
        Rule("""--[^\n]*""", Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""'(?:''|[^'])*'?""", Cat.STRING),
        Rule("""\$(\w*)\$[\s\S]*?\$\1\$""", Cat.STRING), // PostgreSQL $$ … $$
        Rule(""""[^"\n]*"|`[^`\n]*`|\[[^\]\n]*]""", Cat.KEY),
        Rule("""(?i)\b(?:select|from|where|and|or|not|insert|into|values|update|set|delete|create|alter|drop|table|view|index|unique|primary|foreign|key|references|constraint|default|null|is|in|exists|between|like|ilike|as|on|join|inner|left|right|full|outer|cross|natural|using|group|by|order|having|limit|offset|union|all|distinct|case|when|then|else|end|with|recursive|returning|begin|commit|rollback|transaction|grant|revoke|if|database|schema|sequence|trigger|function|procedure|returns|return|declare|language|replace|cascade|restrict|check|asc|desc|nulls|first|last|over|partition|window|filter|lateral|true|false|current_date|current_timestamp|interval|cast|to|do|nothing|conflict|truncate|explain|analyze|vacuum|use|show|describe)\b""", Cat.KEYWORD),
        Rule("""(?i)\b(?:int|integer|smallint|bigint|serial|bigserial|decimal|numeric|real|float|double|precision|char|varchar|character|varying|text|uuid|bool|boolean|date|time|timestamp|timestamptz|json|jsonb|bytea|blob|clob|money|inet|array)\b""", Cat.TYPE),
        Rule("""[A-Za-z_]\w*(?=\s*\()""", Cat.FUNCTION),
        Rule(""":\w+|\$\d+|\?|@\w+""", Cat.VARIABLE),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}

/** CSV / TSV: each column in its own color; the delimiter is guessed from the first line. */
object Csv : Format("Csv", "CSV", "csv") {
    override val rules = emptyList<Rule>()
    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        private var delimiter = ','
        private var column = 0
        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
            val first = buffer.subSequence(0, buffer.indexOf('\n').let { if (it < 0) buffer.length else it })
            delimiter = listOf('\t', ';', ',', '|').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.contains(d) } ?: ','
            column = 0
            var i = startOffset - 1
            while (i >= 0 && buffer[i] != '\n') { if (buffer[i] == delimiter) column++; i-- }
            super.start(buffer, startOffset, endOffset, initialState)
        }
        override fun next(): IElementType {
            val c = buf[pos]
            if (c == '\n' || c == '\r') { while (pos < end && (buf[pos] == '\n' || buf[pos] == '\r')) pos++; column = 0; return TokenType.WHITE_SPACE }
            if (c == delimiter) { pos++; column++; return token(Cat.PUNCT) }
            if (c == '"') {
                pos++
                while (pos < end) { if (buf[pos] == '"') { if (pos + 1 < end && buf[pos + 1] == '"') pos += 2 else { pos++; break } } else pos++ }
            } else while (pos < end && buf[pos] != delimiter && buf[pos] != '\n' && buf[pos] != '\r') pos++
            return token(COLUMNS[column % COLUMNS.size])
        }
    }
    private val COLUMNS = listOf(Cat.COL0, Cat.COL1, Cat.COL2, Cat.COL3, Cat.COL4, Cat.COL5)
}

/**
 * `.gitignore` and relatives (`.dockerignore`, `.npmignore`, `.gitattributes`…): a literal pattern (no `*`, `?`, `[`)
 * leads to the file or folder it names, from the folder of the ignore file.
 */
object Ignore : Format("Ignore", "Ignore", "gitignore") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.KEY)
    override val rules = listOf(
        Rule("""#[^\n]*""", Cat.COMMENT, lineStart = true),
        Rule("""!""", Cat.OPERATOR, lineStart = true),
        Rule("""(?<![^\n!])(?:\\.|[^\s\\])+""", Cat.KEY),
        Rule("""-?[\w.-]+(?:=[^\s]*)?""", Cat.ATTRIBUTE),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String) = Links(
            paths = Regex("""(?m)^!?((?:\\.|[^\s\\#])(?:\\.|[^\s\\])*)""").findAll(text).mapNotNull { m ->
                val g = m.groups[1]!!
                val pattern = g.value
                if (pattern.any { it in "*?[" }) return@mapNotNull null
                val path = pattern.trim('/').replace("\\", "")
                if (path.isEmpty()) return@mapNotNull null
                val start = g.range.first + (pattern.length - pattern.trimStart('/').length)
                PathLink(TextRange(start, start + pattern.trim('/').length), path)
            }.toList(),
        )
    }
}
