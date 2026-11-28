package me.kevinlot.lite.formats.spec

import com.intellij.openapi.util.TextRange
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.Offsets
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule
import java.util.regex.Pattern

/**
 * Grammar-Kit grammars (`.bnf`): a rule name → its `Name ::=` line, also inside `<<meta Name>>` and in quoted attribute values
 * (`recoverWhile="Name_recover"`, `extends(".*Expr")="Expr"`); a token name → its line in `tokens=[…]`; a quoted token text
 * (`'{'`, `"class"`) → the token that declares it. Names are local to the file.
 */
object Bnf : Format("Bnf", "Grammar-Kit BNF", "bnf") {
    override val blockComment = "/*" to "*/"
    override val lineComment = "//"
    override val nameCategories = setOf(Cat.DECL, Cat.KEY, Cat.STRING, Cat.WORD)

    private const val NAME = """[A-Za-z_]\w*"""
    private const val MODIFIER = """(?:private|external|meta|left|inner|upper|fake|public)"""
    /** `pin = 1`, `extends(".*Expr") = Expr`, `LBRACE = '{'`: the name before `=`, not `==` or `::=`. */
    private const val KEY_AHEAD = """(?=[ \t]*(?:\([^)\n]*\))?[ \t]*=(?!=))"""

    private val DEAD_TEXT = Regex("""${R.SLASH}|${R.BLOCK}|${R.DQ}|${R.SQ}""")
    private val RULE = Regex("""(?m)^[ \t]*(?:$MODIFIER[ \t]+)*($NAME)[ \t]*::=""")
    private val TOKENS = Regex("""(?<![\w.])tokens[ \t]*=[ \t]*\[""")
    private val TOKEN = Regex("""(?<![\w.])($NAME)[ \t]*=[ \t]*(${R.DQ}|${R.SQ})""")
    private val WORD = Regex("""(?<![\w.])$NAME(?!\w)""")
    private val KEY: Pattern = Pattern.compile(KEY_AHEAD)

    override val rules = listOf(
        Rule(R.SLASH, Cat.COMMENT),
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""$MODIFIER(?=[ \t]+[\w \t]*::=)""", Cat.KEYWORD),
        Rule("""$NAME(?=[ \t]*::=)""", Cat.DECL),
        Rule("""::=""", Cat.OPERATOR),
        Rule("""<<|>>""", Cat.META),
        Rule("""$NAME$KEY_AHEAD""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""(?<==[ \t]{0,8})(?:true|false)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
        Rule(NAME, Cat.WORD),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val deadText = DEAD_TEXT.findAll(text).toList()
            val dead = Offsets(deadText.asSequence().map { it.range })
            val live = { offset: Int -> offset !in dead }

            val rules = RULE.findAll(text).mapNotNull { it.groups[1] }.filter { live(it.range.first) }
                .map { Decl(TextRange(it.range.first, it.range.last + 1), "rule:${it.value}") }.toList()
            val tokens = mutableListOf<Decl>()
            val values = mutableListOf<Decl>()
            tokensBlock(text, deadText, live)?.let { block ->
                for (m in TOKEN.findAll(text.subSequence(0, block.last), block.first)) {
                    val name = m.groups[1] ?: continue
                    if (!live(name.range.first)) continue
                    tokens += Decl(TextRange(name.range.first, name.range.last + 1), "token:${name.value}")
                    val value = m.groups[2] ?: continue
                    inner(value.value, value.range.first)?.takeUnless { (_, s) -> s.startsWith("regexp:") }
                        ?.let { (range, s) -> values += Decl(range, "value:$s") }
                }
            }
            val ruleNames = rules.map { it.key.removePrefix("rule:") }.toSet()
            val tokenNames = tokens.map { it.key.removePrefix("token:") }.toSet()
            val valueTexts = values.map { it.key.removePrefix("value:") }.toSet()
            val declared = (rules + tokens + values).map { it.range.startOffset }.toSet()

            // a bare name: a rule or a token, except attribute keys (`pin = 1`, `name = …`)
            val words = WORD.findAll(text)
                .filter { (it.value in ruleNames || it.value in tokenNames) && it.range.first !in declared && live(it.range.first) }
                .filterNot { KEY.matcher(text).region(it.range.last + 1, text.length).lookingAt() }
                .map { Ref(TextRange(it.range.first, it.range.last + 1), listOf("rule:${it.value}", "token:${it.value}")) }
            // a quoted text: the token it spells in an expression, the rule it names in an attribute value
            val strings = deadText.asSequence().filter { it.value[0] == '"' || it.value[0] == '\'' }.mapNotNull { inner(it.value, it.range.first) }
                .filter { (range, s) -> range.startOffset !in declared && (s in valueTexts || s in ruleNames) }
                .map { (range, s) ->
                    val keys = listOf("value:$s", "rule:$s")
                    Ref(range, if (afterEquals(text, range.startOffset - 1)) keys.reversed() else keys)
                }
            return Links(decls = rules + tokens + values, refs = (words + strings).toList())
        }
    }

    /** The offsets inside `tokens=[…]`: up to its first `]` outside strings and comments. */
    private fun tokensBlock(text: CharSequence, deadText: List<MatchResult>, live: (Int) -> Boolean): IntRange? {
        val open = TOKENS.findAll(text).firstOrNull { live(it.range.first) } ?: return null
        var i = open.range.last + 1
        var dead = deadText.indexOfFirst { it.range.last >= i }
        while (i < text.length) {
            if (dead >= 0 && dead < deadText.size && i >= deadText[dead].range.first) { i = deadText[dead].range.last + 1; dead++; continue }
            if (text[i] == ']') return open.range.last + 1..i
            i++
        }
        return open.range.last + 1..text.length
    }

    /** The text between the quotes of [quoted] (starting at [start]), when it is closed and not empty. */
    private fun inner(quoted: String, start: Int): Pair<TextRange, String>? {
        if (quoted.length < 3 || quoted.last() != quoted[0]) return null
        return TextRange(start + 1, start + quoted.length - 1) to quoted.substring(1, quoted.length - 1)
    }

    /** Whether the character before [offset] (spaces skipped) is the `=` of an attribute. */
    private fun afterEquals(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return i >= 0 && text[i] == '='
    }
}
