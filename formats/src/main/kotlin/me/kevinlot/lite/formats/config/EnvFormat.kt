package me.kevinlot.lite.formats

import java.util.regex.Pattern

/** `.env`: `${VAR}` / `$VAR` → `VAR=` (in the same file). */
object Env : Format("Env", "Dotenv", "env") {
    override val lineComment = "#"
    private val ASSIGNMENT = Regex("""(?m)^\s*(?:export\s+)?([A-Za-z_][\w.-]*)\s*=""")
    private const val NAME = """[A-Za-z_][\w.-]*(?=[ \t]*=)"""
    private val SINGLE_QUOTED = Regex("""'[^']*'""")
    private val VARIABLE = Regex("""\$\{?([A-Za-z_]\w*)""")
    override val rules = listOf(
        Rule(R.HASH, Cat.COMMENT, lineStart = true),
        // after a space or a closing quote: `KEY=a#b` holds no comment
        Rule("""(?<=[ \t"'])#[^\n]*""", Cat.COMMENT),
        Rule("""export\b""", Cat.KEYWORD, lineStart = true),
        Rule(NAME, Cat.DECL, lineStart = true),
        Rule("""(?<=^[ \t]{0,40}export[ \t]{1,40})$NAME""", Cat.DECL, flags = java.util.regex.Pattern.MULTILINE),
        Rule(""""[^"\\]*+(?:\\.[^"\\]*+)*+"?""", Cat.STRING),
        Rule("""'[^']*'?""", Cat.STRING),
        Rule("""\$\{[^}\n]*}|\$[A-Za-z_]\w*""", Cat.VARIABLE),
    )
    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            // a name holds no quote: it is inside a single-quoted value when its first character is
            val single = Offsets(SINGLE_QUOTED.findAll(text).map { it.range })
            return Links(
                decls = ASSIGNMENT.ranges(text).map { (r, n) -> Decl(r, "var:$n") }.toList(),
                refs = VARIABLE.ranges(text).filter { (r, _) -> r.startOffset !in single }.map { (r, n) -> Ref(r, listOf("var:$n")) }.toList(),
            )
        }
    }
}
