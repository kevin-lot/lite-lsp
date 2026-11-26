package me.kevinlot.lite.formats

import com.intellij.openapi.util.TextRange

/**
 * JFlex lexer specifications: `{MACRO}` (also inside a character class) → `MACRO = …`; a state in `<A, B>`, `yybegin(A)` or any Java code → its
 * `%state` / `%xstate` line. Names are local to the file.
 */
object JFlex : Format("JFlex", "JFlex", "flex") {
    override val blockComment = "/*" to "*/"
    override val lineComment = "//"
    override val nameCategories = setOf(Cat.DECL, Cat.STRING, Cat.TYPE, Cat.VARIABLE, Cat.WORD)

    private const val CHAR = """'(?:\\.|[^'\\\n])'"""
    private const val CLASS_TEXT = """[^\[\]\\\n]*+(?:\\.[^\[\]\\\n]*+)*+"""
    // one level of nested classes; a class with an unbalanced `[` ends at its first `]`
    private const val CLASS = """\[$CLASS_TEXT(?:\[$CLASS_TEXT]$CLASS_TEXT)*+]|\[[^\]\\\n]*+(?:\\.[^\]\\\n]*+)*+]"""
    private const val NAME = """[A-Za-z_]\w*"""
    private const val STATE_LIST = """<[ \t]*$NAME(?:[ \t]*,[ \t]*$NAME)*[ \t]*>"""
    private val JAVA_KEYWORDS = listOf(
        "boolean", "break", "case", "class", "default", "else", "false", "final", "if", "import", "int", "new", "null", "package", "private",
        "protected", "public", "return", "static", "switch", "this", "true", "void", "while",
    )

    private val CLASS_MACRO_USE = Regex("""(?<!\\)\{($NAME)}""")
    private val DEAD_TEXT = Regex("""\\.|$CLASS|${R.DQ}|$CHAR|${R.SLASH}|${R.BLOCK}""")
    private val MACRO = Regex("""(?m)^($NAME)[ \t]*=(?!=)""")
    private val MACRO_USE = Regex("""\{([ \t]*)($NAME)[ \t]*}""")
    private val SECTION = Regex("""(?m)^%%[ \t]*$""")
    private val STATE_LINE = Regex("""(?m)^%(?:state|xstate|s|x)\b([^\n]*)""")
    private val STATE_NAME = Regex(NAME)
    private val WORD = Regex("""(?<![\w.])$NAME""")

    override val rules = listOf(
        Rule(R.SLASH, Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""%%|%[{}]|%\w+""", Cat.META, lineStart = true),
        Rule("""$NAME(?=[ \t]*=(?!=))""", Cat.DECL, lineStart = true),
        Rule(STATE_LIST, Cat.TYPE),
        Rule("""\{[ \t]*$NAME[ \t]*}""", Cat.VARIABLE),
        Rule(R.DQ, Cat.STRING),
        Rule(CHAR, Cat.STRING),
        Rule(CLASS, Cat.STRING),
        Rule("""\\.""", Cat.CONSTANT),
        Rule("""(?:${JAVA_KEYWORDS.joinToString("|")})\b""", Cat.KEYWORD),
        Rule(R.NUMBER, Cat.NUMBER),
        Rule(NAME, Cat.WORD),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val deadText = DEAD_TEXT.findAll(text).toList()
            val dead = Offsets(deadText.asSequence().map { it.range })
            val live = { offset: Int -> offset !in dead }
            val declarations = SECTION.find(text)?.range?.last ?: return Links()
            val rules = SECTION.find(text, declarations)?.range?.first ?: text.length

            val macros = MACRO.ranges(text).filter { (range, _) -> range.startOffset in declarations..rules && live(range.startOffset) }
                .map { (range, name) -> Decl(range, "macro:$name") }.toList()
            val states = STATE_LINE.findAll(text).filter { it.range.first in declarations..rules }.flatMap { line ->
                val names = line.groups[1] ?: return@flatMap emptySequence()
                STATE_NAME.findAll(names.value).filter { live(names.range.first + it.range.first) }
                    .map { Decl(TextRange(names.range.first + it.range.first, names.range.first + it.range.last + 1), "state:${it.value}") }
            }.toList()
            val stateNames = states.map { it.key.removePrefix("state:") }.toSet()
            val declared = (macros + states).map { it.range.startOffset }.toSet()

            // `{ x }` is also Java code and `{x}` three characters of a class: only a declared macro makes them a use
            val macroNames = macros.map { it.key.removePrefix("macro:") }.toSet()
            val plainUses = MACRO_USE.findAll(text).filter { it.range.first > declarations && live(it.range.first) }
                .filter { it.groupValues[2] in macroNames || it.value.length == it.groupValues[2].length + 2 }
                .mapNotNull { it.groups[2]?.range }
            val classUses = deadText.asSequence().filter { it.value.startsWith('[') && it.range.first > declarations }.flatMap { inClass ->
                CLASS_MACRO_USE.findAll(inClass.value).mapNotNull { it.groups[1] }.filter { it.value in macroNames }
                    .map { inClass.range.first + it.range.first..inClass.range.first + it.range.last }
            }
            val macroUses = (plainUses + classUses).map { Ref(TextRange(it.first, it.last + 1), listOf("macro:${text.substring(it)}")) }.toList()
            val macroUseStarts = macroUses.map { it.range.startOffset }.toSet()
            val stateUses = WORD.findAll(text)
                .filter { it.value in stateNames && it.range.first !in declared && it.range.first !in macroUseStarts && live(it.range.first) }
                .filterNot { text.getOrNull(it.range.first - 1) == '{' && text.getOrNull(it.range.last + 1) == '}' }
                .map { Ref(TextRange(it.range.first, it.range.last + 1), listOf("state:${it.value}")) }
            return Links(decls = macros + states, refs = macroUses + stateUses)
        }
    }
}
