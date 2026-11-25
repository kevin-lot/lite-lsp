package me.kevinlot.lite.formats

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

open class FmtHighlighterFactory(private val format: Format) : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer(): Lexer = format.lexer()
        override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack((t as? FmtToken)?.cat?.key)
    }
}

open class FmtCommenter(private val format: Format) : Commenter {
    override fun getLineCommentPrefix() = format.lineComment
    override fun getBlockCommentPrefix() = format.blockComment?.first
    override fun getBlockCommentSuffix() = format.blockComment?.second
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

open class FmtBraceMatcher(format: Format) : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(format.token(Cat.LBRACE), format.token(Cat.RBRACE), true),
        BracePair(format.token(Cat.LBRACKET), format.token(Cat.RBRACKET), false),
        BracePair(format.token(Cat.LPAREN), format.token(Cat.RPAREN), false),
    )
    override fun getPairs() = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}

open class FmtFindUsagesProvider(private val format: Format) : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        format.lexer(), TokenSet.create(*format.nameCategories.map { format.token(it) }.toTypedArray()),
        TokenSet.create(format.token(Cat.COMMENT)), TokenSet.EMPTY,
    )
    override fun canFindUsagesFor(element: PsiElement) = element is FmtWord && element.decl != null
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement) = (element as? FmtWord)?.decl?.key?.substringBefore(':') ?: "name"
    override fun getDescriptiveName(element: PsiElement) = (element as? FmtWord)?.name.orEmpty()
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}
