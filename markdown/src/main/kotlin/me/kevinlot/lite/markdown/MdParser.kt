package me.kevinlot.lite.markdown

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.markdown.MdTokens as T

class MdElementType(debugName: String) : IElementType(debugName, MdLanguage)

object MdElementTypes {
    private fun t(name: String) = MdElementType(name)
    val FILE = IFileElementType("LITE_MARKDOWN_FILE", MdLanguage)
    val HEADER = t("HEADER")
    val CODE_BLOCK = t("CODE_BLOCK")
    val CODE_SPAN = t("CODE_SPAN_ELEMENT")
    val HTML_TAG = t("HTML_TAG")
    val LINK = t("LINK")                     // [text](dest), ![alt](src), [text][label], [label]
    val LINK_DESTINATION = t("LINK_DESTINATION")
    val LINK_LABEL_REF = t("LINK_LABEL_REF")
    val LINK_DEFINITION = t("LINK_DEFINITION")
    val EMPHASIS = t("EMPHASIS"); val STRONG = t("STRONG"); val STRIKE = t("STRIKE")
}

private val E = MdElementTypes

/** Only what navigation and coloring need: headers, fenced code, links, link definitions, code spans, HTML tags, emphasis spans. */
class MdParser : PsiParser {
    private lateinit var b: PsiBuilder
    private var wrapping = true

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        b = builder
        val m = b.mark()
        while (!b.eof()) {
            while (at(T.QUOTE_MARKER)) b.advanceLexer()
            when {
                at(T.FENCE_START) -> codeBlock()
                at(T.HEADER_MARKER) -> header()
                at(T.LBRACKET) && b.lookAhead(1) == T.LINK_LABEL -> definition()
                else -> paragraphLine()
            }
            eat(T.NEWLINE)
        }
        m.done(root)
        return b.treeBuilt
    }

    private fun at(t: IElementType) = b.tokenType == t
    private fun eat(t: IElementType): Boolean { if (!at(t)) return false; b.advanceLexer(); return true }
    private fun lineEnded() = b.eof() || at(T.NEWLINE)

    private fun header() {
        val m = b.mark()
        b.advanceLexer()
        wrapping = false
        inline()
        wrapping = true
        m.done(E.HEADER)
    }

    /** A line of text, and the header it is when a `===` or `---` line underlines it. */
    private fun paragraphLine() {
        if (lineEnded()) return
        val m = b.mark()
        inline()
        if (!(at(T.NEWLINE) && b.lookAhead(1) == T.SETEXT_MARKER)) return m.drop()
        b.advanceLexer()
        b.advanceLexer()
        m.done(E.HEADER)
    }

    private fun codeBlock() {
        val m = b.mark()
        b.advanceLexer()
        eat(T.FENCE_INFO)
        eat(T.NEWLINE)
        while (at(T.CODE_TEXT) || at(T.QUOTE_MARKER)) b.advanceLexer()
        eat(T.FENCE_END)
        m.done(E.CODE_BLOCK)
    }

    private fun definition() {
        val m = b.mark()
        b.advanceLexer()
        b.advanceLexer() // label
        eat(T.RBRACKET)
        eat(T.DEF_COLON)
        if (at(T.LINK_DEST)) wrapped(E.LINK_DESTINATION)
        while (!lineEnded()) b.advanceLexer()
        m.done(E.LINK_DEFINITION)
    }

    private fun inline() {
        while (!lineEnded()) {
            when (b.tokenType) {
                T.LBRACKET, T.IMAGE_START -> link()
                T.STRONG -> span(T.STRONG, E.STRONG)
                T.EMPH -> span(T.EMPH, E.EMPHASIS)
                T.STRIKE -> span(T.STRIKE, E.STRIKE)
                else -> token()
            }
        }
    }

    /** One token, as an element of its own when it holds references. */
    private fun token() = when (b.tokenType) {
        T.CODE_SPAN -> wrapped(E.CODE_SPAN)
        T.HTML -> wrapped(E.HTML_TAG)
        else -> b.advanceLexer()
    }

    private fun wrapped(type: IElementType) {
        val m = b.mark()
        b.advanceLexer()
        m.done(type)
    }

    /** A link text may wrap inside its paragraph: not over a blank line, nor into another block. */
    private fun wraps(): Boolean {
        if (!wrapping || !at(T.NEWLINE)) return false
        val quoted = b.lookAhead(1) == T.QUOTE_MARKER
        val first = when { quoted -> 2; else -> 1 }
        val next = b.lookAhead(first) ?: return false
        return next !in BLOCK_STARTS && !(next == T.LBRACKET && b.lookAhead(first + 1) == T.LINK_LABEL)
    }

    /** `**…**`, `*…*`, `~~…~~` on one line; a lone marker stays a marker. */
    private fun span(marker: IElementType, type: IElementType) {
        val m = b.mark()
        b.advanceLexer()
        while (!lineEnded() && !at(marker)) {
            when (b.tokenType) {
                T.LBRACKET, T.IMAGE_START -> link()
                else -> token()
            }
        }
        if (!at(marker)) { m.rollbackTo(); b.advanceLexer(); return }
        b.advanceLexer()
        m.done(type)
    }

    private fun link() {
        val m = b.mark()
        val image = at(T.IMAGE_START)
        b.advanceLexer()
        val text = b.mark()
        var depth = 1
        while (!lineEnded() || wraps()) {
            if (at(T.IMAGE_START)) { link(); continue }
            if (at(T.LBRACKET)) depth++
            if (at(T.RBRACKET) && --depth == 0) break
            token()
        }
        if (!at(T.RBRACKET)) { text.drop(); m.rollbackTo(); b.advanceLexer(); return }
        val textEnd = b.mark()
        b.advanceLexer()
        when {
            at(T.LPAREN) -> {
                textEnd.drop(); text.drop()
                b.advanceLexer()
                if (at(T.LINK_DEST)) wrapped(E.LINK_DESTINATION)
                while (!lineEnded() && !at(T.RPAREN)) b.advanceLexer()
                eat(T.RPAREN)
                m.done(E.LINK)
            }
            at(T.LBRACKET) && b.rawLookup(-1) == T.RBRACKET -> {
                textEnd.drop(); text.drop()
                b.advanceLexer()
                val label = b.mark()
                while (!lineEnded() && !at(T.RBRACKET)) b.advanceLexer()
                label.done(E.LINK_LABEL_REF)
                eat(T.RBRACKET)
                m.done(E.LINK)
            }
            !image && depth == 0 -> {
                // shortcut reference `[label]`: the text is the label
                textEnd.rollbackTo()
                text.done(E.LINK_LABEL_REF)
                b.advanceLexer()
                m.done(E.LINK)
            }
            else -> { textEnd.drop(); text.drop(); m.done(E.LINK) }
        }
    }

    private companion object {
        val BLOCK_STARTS = TokenSet.create(T.NEWLINE, T.HEADER_MARKER, T.SETEXT_MARKER, T.FENCE_START, T.LIST_MARKER, T.HR)
    }
}

class MdParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = MdLexer()
    override fun createParser(project: Project?): PsiParser = MdParser()
    override fun getFileNodeType(): IFileElementType = E.FILE
    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun getWhitespaceTokens(): TokenSet = T.WHITESPACES
    override fun createElement(node: ASTNode): PsiElement = MdPsiFactoryImpl.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = MdFile(viewProvider)
}
