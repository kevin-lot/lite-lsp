package me.kevinlot.lite.formats

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import java.util.regex.Matcher
import javax.swing.Icon

open class FmtLanguage(id: String, private val display: String) : Language("Lite$id") {
    override fun getDisplayName() = display
}

class FmtToken(val cat: Cat, language: Language) : IElementType(cat.name, language)
class FmtElementType(name: String, language: Language) : IElementType(name, language)

open class FmtFileType(val format: Format, secondary: Boolean = false) : LanguageFileType(format.language, secondary) {
    override fun getName() = "${format.displayName} (Lite)"
    override fun getDisplayName() = format.displayName
    override fun getDescription() = "${format.displayName} file"
    override fun getDefaultExtension() = format.extension
    override fun getIcon(): Icon? = null
}

class FmtFile(viewProvider: FileViewProvider, val format: Format) : PsiFileBase(viewProvider, format.language) {
    override fun getFileType() = viewProvider.fileType
    override fun toString() = "${format.displayName} file"
}

/** Whitespace, then the first [Format.rules] that matches; else a word, else one character. */
open class FmtLexer(protected val format: Format) : LexerBase() {
    protected var buf: CharSequence = ""
    protected var end = 0
    protected var start = 0
    protected var pos = 0
    private var type: IElementType? = null
    private var rules: List<BoundRule> = emptyList()

    /** A rule and the matcher every token of a buffer reuses: one matcher per attempt is the lexer's main allocation. */
    protected class BoundRule(val rule: Rule, buffer: CharSequence) {
        val matcher: Matcher = rule.pattern.matcher(buffer).useTransparentBounds(true).useAnchoringBounds(false)
    }

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        rules = format.rules.map { BoundRule(it, buffer) }
        advance()
    }

    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = pos
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        start = pos
        type = null
        if (pos < end) type = next()
    }

    protected fun token(c: Cat) = format.token(c)

    protected fun onlyIndentBefore(): Boolean {
        var i = pos - 1
        while (i >= 0 && buf[i] != '\n') { if (buf[i] != ' ' && buf[i] != '\t') return false; i-- }
        return true
    }

    /** The category of the first of [rules] that matches at the current position, which then moves past the match. */
    protected fun match(rules: List<BoundRule>, lineStart: Boolean = true): Cat? {
        for (index in rules.indices) {
            val bound = rules[index]
            if (bound.rule.lineStart && !lineStart) continue
            val m = bound.matcher.region(pos, end)
            if (m.lookingAt() && m.end() > pos) { pos = m.end(); return bound.rule.cat }
        }
        return null
    }

    protected open fun next(): IElementType {
        val c = buf[pos]
        if (c.isWhitespace()) { while (pos < end && buf[pos].isWhitespace()) pos++; return TokenType.WHITE_SPACE }
        match(rules, onlyIndentBefore())?.let { return format.token(it) }
        if (c.isLetterOrDigit() || c == '_') {
            while (pos < end && (buf[pos].isLetterOrDigit() || buf[pos] == '_' || buf[pos] == '-')) pos++
            return format.token(Cat.WORD)
        }
        pos++
        return format.token(
            when (c) {
                '{' -> Cat.LBRACE; '}' -> Cat.RBRACE; '[' -> Cat.LBRACKET; ']' -> Cat.RBRACKET; '(' -> Cat.LPAREN; ')' -> Cat.RPAREN
                '=', ':', '+', '-', '*', '/', '<', '>', '!', '&', '|', '%', '?', '^', '~' -> Cat.OPERATOR
                else -> Cat.PUNCT
            },
        )
    }
}

/** Name-holding tokens get a node: it carries the references and declarations the [Linker] found there. */
class FmtParser(private val format: Format) : PsiParser {
    private val named = TokenSet.create(*format.nameCategories.map { format.token(it) }.toTypedArray())
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val m = builder.mark()
        val wrap = format.linker != null
        while (!builder.eof()) {
            val isName = wrap && builder.tokenType in named
            val word = builder.takeIf { isName }?.mark()
            builder.advanceLexer()
            word?.done(format.wordType)
        }
        m.done(root)
        return builder.treeBuilt
    }
}

open class FmtParserDefinition(private val format: Format) : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = format.lexer()
    override fun createParser(project: Project?): PsiParser = FmtParser(format)
    override fun getFileNodeType() = format.fileElementType
    override fun getCommentTokens(): TokenSet = TokenSet.create(format.token(Cat.COMMENT))
    /** None: names live in strings (`variable "region"`), and literal tokens are not offered for rename. */
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun createElement(node: ASTNode): PsiElement = FmtWord(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = FmtFile(viewProvider, format)
}
