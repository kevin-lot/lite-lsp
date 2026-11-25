package me.kevinlot.lite.formats

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon
import me.kevinlot.lite.core.color

/*
 * Cron expressions, a language of their own: a lexer and a parser, so that a malformed expression is a syntax error
 * like in any other language. Two dialects: five fields (crontab, GitHub Actions, Kubernetes), and AWS `cron(…)` with
 * its six fields and `rate(…)` (EventBridge, Serverless). CronInjector.kt puts them inside YAML and HCL values.
 */

object CronLanguage : Language("LiteCron") {
    override fun getDisplayName() = "Cron"
}

object CronFileType : LanguageFileType(CronLanguage) {
    override fun getName() = "Cron (Lite)"
    override fun getDescription() = "Cron expression"
    override fun getDefaultExtension() = "cron"
    override fun getIcon(): Icon? = null
}

class CronFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, CronLanguage) {
    override fun getFileType() = CronFileType
}

object CronTokens {
    private fun token(name: String) = IElementType(name, CronLanguage)

    val COMMA = token("COMMA")
    val DASH = token("DASH")
    val HASH = token("HASH")
    val LPAREN = token("LPAREN")
    val NUMBER = token("NUMBER")
    val QUESTION = token("QUESTION")
    val RPAREN = token("RPAREN")
    val SLASH = token("SLASH")
    // blanks separate fields: the parser reads them, they are not skipped as white space
    val SPACE = token("SPACE")
    val STAR = token("STAR")
    val WORD = token("WORD")

    /** `cron` / `rate` and the unit of a rate: words the parser recognised. */
    val FUNCTION = token("FUNCTION")
    val UNIT = token("UNIT")

    val FIELDS = listOf("MINUTE", "HOUR", "DAY_OF_MONTH", "MONTH", "DAY_OF_WEEK", "YEAR").map(::token)
    val FILE = IFileElementType(CronLanguage)
    val SINGLE = mapOf('*' to STAR, '?' to QUESTION, '/' to SLASH, ',' to COMMA, '-' to DASH, '#' to HASH, '(' to LPAREN, ')' to RPAREN)
}

class CronLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        pos = startOffset
        advance()
    }

    override fun advance() {
        start = pos
        type = null
        if (pos < end) type = next()
    }

    private fun next(): IElementType {
        if (take(Char::isWhitespace)) return CronTokens.SPACE
        if (take(Char::isDigit)) return CronTokens.NUMBER
        if (take(Char::isLetter)) return CronTokens.WORD
        return CronTokens.SINGLE[buffer[pos++]] ?: TokenType.BAD_CHARACTER
    }

    private fun take(test: (Char) -> Boolean): Boolean {
        val from = pos
        while (pos < end && test(buffer[pos])) pos++
        return pos > from
    }

    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = pos
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end
}

/** What a field accepts: numbers of [range] and [names]; for the AWS day fields `?`, whole [words], a [suffix], `n#k`. */
private class CronSlot(
    val label: String, val range: IntRange, val names: List<String> = emptyList(),
    val words: Set<String> = emptySet(), val suffix: String? = null, val nth: Boolean = false,
) {
    val day: Boolean get() = suffix != null
}

private object CronSlots {
    private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val HOUR = CronSlot("hour", 0..23)
    private val MINUTE = CronSlot("minute", 0..59)
    private val MONTH = CronSlot("month", 1..12, MONTHS)

    val AWS = listOf(
        MINUTE, HOUR, CronSlot("day-of-month", 1..31, words = setOf("L", "LW"), suffix = "W"), MONTH,
        CronSlot("day-of-week", 1..7, DAYS, words = setOf("L") + DAYS.map { it + "L" }, suffix = "L", nth = true), CronSlot("year", 1970..2199),
    )
    val POSIX = listOf(MINUTE, HOUR, CronSlot("day-of-month", 1..31), MONTH, CronSlot("day-of-week", 0..7, DAYS))
    val UNITS = setOf("minute", "minutes", "hour", "hours", "day", "days")
}

class CronParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        CronParsing(builder).expression()
        file.done(root)
        return builder.treeBuilt
    }
}

private class CronParsing(private val b: PsiBuilder) {
    private val aws = b.originalText.trimStart().startsWith("cron(")

    fun expression() {
        skipSpaces()
        when {
            isCall("cron") -> call { fields(CronSlots.AWS) }
            isCall("rate") -> call(::rate)
            else -> fields(CronSlots.POSIX)
        }
        skipSpaces()
        if (!b.eof()) rest("Unexpected text after the expression")
    }

    private fun isCall(name: String) = b.tokenType == CronTokens.WORD && b.tokenText == name && b.lookAhead(1) == CronTokens.LPAREN

    private fun call(body: () -> Unit) {
        b.remapCurrentToken(CronTokens.FUNCTION)
        b.advanceLexer()
        b.advanceLexer()
        skipSpaces()
        body()
        skipSpaces()
        if (!eat(CronTokens.RPAREN)) b.error("Expected )")
    }

    private fun fields(slots: List<CronSlot>) {
        val dayRule = dayRuleProblem()
        val found = slots.indices.takeWhile { index ->
            atField().also { if (it) field(slots[index], CronTokens.FIELDS[index], dayRule.takeIf { slots[index].nth }) }
        }.size
        if (found < slots.size) b.error("Expected ${slots.size} fields (${slots.joinToString(" ") { it.label }}), found $found")
        while (atField()) bad("Unexpected field: ${slots.size} are expected")
    }

    /** AWS refuses a value in both day fields: one of them must be `?`, the other must not. */
    private fun dayRuleProblem(): String? {
        if (!aws) return null
        val raw = b.originalText.toString().substringAfter('(').substringBeforeLast(')').trim().split(BLANKS)
        if (raw.size < 5 || listOf(raw[2], raw[4]).count { it == "?" } == 1) return null
        return "AWS wants ? in exactly one of day-of-month and day-of-week"
    }

    private fun field(slot: CronSlot, type: IElementType, known: String?) {
        val marker = b.mark()
        val problem = known ?: fieldProblem(slot) ?: "Unexpected '${b.tokenText}' in ${slot.label}".takeIf { atField() }
        skipField()
        if (problem == null) marker.done(type)
        if (problem != null) marker.error(problem)
        skipSpaces()
    }

    /** Reads the field as far as it is valid: null when all of it is, else what is wrong where it stopped. */
    private fun fieldProblem(slot: CronSlot): String? {
        if (b.tokenType == CronTokens.QUESTION) {
            b.advanceLexer()
            if (!aws) return "? is only valid in AWS cron(…)"
            return "? is only for day-of-month and day-of-week".takeIf { !slot.day }
        }
        if (aws && b.tokenType == CronTokens.WORD && b.tokenText?.uppercase() in slot.words) {
            b.advanceLexer()
            return null
        }
        return items(slot)
    }

    private tailrec fun items(slot: CronSlot): String? {
        item(slot)?.let { return it }
        if (!eat(CronTokens.COMMA)) return null
        return items(slot)
    }

    /** `*`, `5`, `1-5`, `MON-FRI`, each with an optional `/step`; AWS also takes `15W`, `6L` and `6#3` in its day fields. */
    private fun item(slot: CronSlot): String? {
        if (eat(CronTokens.STAR)) return step()
        value(slot)?.let { return it }
        if (aws && b.tokenType == CronTokens.WORD && b.tokenText?.uppercase() == slot.suffix) {
            b.advanceLexer()
            return null
        }
        if (aws && slot.nth && eat(CronTokens.HASH)) return number(1..5, "Expected 1 to 5 after #")
        if (eat(CronTokens.DASH)) value(slot)?.let { return it }
        return step()
    }

    private fun value(slot: CronSlot): String? {
        if (b.tokenType == CronTokens.NUMBER) return number(slot.range, "${slot.label} must be between ${slot.range.first} and ${slot.range.last}")
        if (b.tokenType != CronTokens.WORD) return "Expected a value for ${slot.label}"
        val name = b.tokenText.orEmpty()
        b.advanceLexer()
        if (name.uppercase() in slot.names) return null
        return "'$name' is not a ${slot.label}"
    }

    private fun step(): String? {
        if (!eat(CronTokens.SLASH)) return null
        return number(1..Int.MAX_VALUE, "Expected a step after /")
    }

    private fun number(range: IntRange, problem: String): String? {
        if (b.tokenType != CronTokens.NUMBER) return problem
        val value = b.tokenText?.toIntOrNull()
        b.advanceLexer()
        return problem.takeIf { value == null || value !in range }
    }

    /** `rate(5 minutes)`: EventBridge rules want `1 minute`, EventBridge Scheduler `1 minutes`; both are taken. */
    private fun rate() {
        val value = b.tokenText?.toIntOrNull()?.takeIf { b.tokenType == CronTokens.NUMBER }
        if (value == null || value < 1) return bad("Expected a positive number").also { skipToClose() }
        b.advanceLexer()
        skipSpaces()
        if (b.tokenType != CronTokens.WORD || b.tokenText !in CronSlots.UNITS) return bad("Expected minute(s), hour(s) or day(s)").also { skipToClose() }
        b.remapCurrentToken(CronTokens.UNIT)
        b.advanceLexer()
    }

    /** One problem per rate: what follows it up to the `)` is not read again. */
    private fun skipToClose() { while (!b.eof() && b.tokenType != CronTokens.RPAREN) b.advanceLexer() }

    private fun atField() = !b.eof() && b.tokenType != CronTokens.SPACE && b.tokenType != CronTokens.RPAREN

    private fun bad(problem: String) {
        if (!atField()) return b.error(problem)
        val marker = b.mark()
        skipField()
        marker.error(problem)
        skipSpaces()
    }

    private fun eat(type: IElementType): Boolean {
        if (b.tokenType != type) return false
        b.advanceLexer()
        return true
    }

    private fun rest(problem: String) {
        val marker = b.mark()
        while (!b.eof()) b.advanceLexer()
        marker.error(problem)
    }

    private fun skipField() { while (atField()) b.advanceLexer() }

    private fun skipSpaces() { while (b.tokenType == CronTokens.SPACE) b.advanceLexer() }

    private companion object {
        val BLANKS = Regex("""\s+""")
    }
}

class CronParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = CronLexer()
    override fun createParser(project: Project?): PsiParser = CronParser()
    override fun getFileNodeType() = CronTokens.FILE
    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun getWhitespaceTokens(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = CronFile(viewProvider)
}

class CronHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer(): Lexer = CronLexer()
        override fun getTokenHighlights(type: IElementType): Array<TextAttributesKey> = when (type) {
            CronTokens.NUMBER -> pack(DefaultLanguageHighlighterColors.NUMBER)
            TokenType.BAD_CHARACTER -> pack(HighlighterColors.BAD_CHARACTER)
            else -> TextAttributesKey.EMPTY_ARRAY
        }
    }
}

/** One color per field, so that the position tells minute from hour; the lexer alone cannot know the position. */
class CronAnnotator : Annotator {
    private val colors = CronTokens.FIELDS.zip(listOf(Cat.COL0, Cat.COL1, Cat.COL2, Cat.COL3, Cat.COL4, Cat.COL5)).toMap() +
        mapOf(CronTokens.FUNCTION to Cat.FUNCTION, CronTokens.UNIT to Cat.KEYWORD)

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val key = colors[element.node.elementType]?.key ?: return
        holder.color(element, key)
    }
}
