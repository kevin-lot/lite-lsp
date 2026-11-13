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
    val LINK = t("LINK")                     // [text](dest), ![alt](src), [text][label], [label]
    val LINK_DESTINATION = t("LINK_DESTINATION")
    val LINK_LABEL_REF = t("LINK_LABEL_REF")
    val LINK_DEFINITION = t("LINK_DEFINITION")
    val EMPHASIS = t("EMPHASIS"); val STRONG = t("STRONG"); val STRIKE = t("STRIKE")
}

private val E = MdElementTypes

/** Only what navigation and coloring need: headers, fenced code, links, link definitions, emphasis spans. */
class MdParser : PsiParser {
    private lateinit var b: PsiBuilder

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        b = builder
        val m = b.mark()
        while (!b.eof()) {
            when {
                at(T.FENCE_START) -> codeBlock()
                at(T.HEADER_MARKER) -> { val h = b.mark(); b.advanceLexer(); inline(); h.done(E.HEADER) }
                at(T.LBRACKET) && b.lookAhead(1) == T.LINK_LABEL -> definition()
                else -> inline()
            }
            eat(T.NEWLINE)
        }
        m.done(root)
        return b.treeBuilt
    }

    private fun at(t: IElementType) = b.tokenType == t
    private fun eat(t: IElementType): Boolean { if (!at(t)) return false; b.advanceLexer(); return true }
    private fun lineEnded() = b.eof() || at(T.NEWLINE)

    private fun codeBlock() {
        val m = b.mark()
        b.advanceLexer()
        eat(T.FENCE_INFO)
        eat(T.NEWLINE)
        while (at(T.CODE_TEXT)) b.advanceLexer()
        eat(T.FENCE_END)
        m.done(E.CODE_BLOCK)
    }

    private fun definition() {
        val m = b.mark()
        b.advanceLexer()
        b.advanceLexer() // label
        eat(T.RBRACKET)
        eat(T.DEF_COLON)
        if (at(T.LINK_DEST)) { val d = b.mark(); b.advanceLexer(); d.done(E.LINK_DESTINATION) }
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
                else -> b.advanceLexer()
            }
        }
    }

    /** `**…**`, `*…*`, `~~…~~` on one line; a lone marker stays a marker. */
    private fun span(marker: IElementType, type: IElementType) {
        val m = b.mark()
        b.advanceLexer()
        while (!lineEnded() && !at(marker)) if (at(T.LBRACKET) || at(T.IMAGE_START)) link() else b.advanceLexer()
        if (at(marker)) { b.advanceLexer(); m.done(type) } else m.rollbackTo().also { b.advanceLexer() }
    }

    private fun link() {
        val m = b.mark()
        val image = at(T.IMAGE_START)
        b.advanceLexer()
        val text = b.mark()
        var depth = 1
        while (!lineEnded()) {
            if (at(T.LBRACKET) || at(T.IMAGE_START)) depth++
            if (at(T.RBRACKET) && --depth == 0) break
            b.advanceLexer()
        }
        if (!at(T.RBRACKET)) { text.drop(); m.rollbackTo(); b.advanceLexer(); return }
        val textEnd = b.mark()
        b.advanceLexer()
        when {
            at(T.LPAREN) -> {
                textEnd.drop(); text.drop()
                b.advanceLexer()
                if (at(T.LINK_DEST)) { val d = b.mark(); b.advanceLexer(); d.done(E.LINK_DESTINATION) }
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
