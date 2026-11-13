package me.kevinlot.lite.kotlin.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilderFactory
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.kotlin.KtFile
import me.kevinlot.lite.kotlin.KtLanguage
import me.kevinlot.lite.kotlin.upstream.lexer.KotlinLexer
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.psi.KtPsiFactoryImpl
import me.kevinlot.lite.kotlin.upstream.parser.KtParser

/** File root: `.kts` files are parsed as scripts (top-level statements), `.kt` as regular files. */
object KtFileElementType : IFileElementType("LITE_KOTLIN_FILE", KtLanguage) {
    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode {
        val builder = PsiBuilderFactory.getInstance().createBuilder(psi.project, chameleon, KotlinLexer(), language, chameleon.chars)
        val script = psi.containingFile?.name?.endsWith(".kts") == true
        return KtParser(script).parse(this, builder).firstChildNode
    }
}

class KtParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = KotlinLexer()
    override fun createParser(project: Project?): PsiParser = KtParser(false)
    override fun getFileNodeType(): IFileElementType = KtFileElementType
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = KtTokens.STRINGS
    override fun getWhitespaceTokens(): TokenSet = KtTokens.WHITESPACES
    override fun createElement(node: ASTNode): PsiElement = KtPsiFactoryImpl.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = KtFile(viewProvider)

    companion object {
        /** KDoc included: it is a plain comment token here. */
        val COMMENTS: TokenSet = TokenSet.orSet(KtTokens.COMMENTS, TokenSet.create(KtTokens.DOC_COMMENT))
    }
}
