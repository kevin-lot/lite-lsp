package me.kevinlot.lite.typescript.parser

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
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.TsLanguage
import me.kevinlot.lite.typescript.lexer.TsLexer
import me.kevinlot.lite.typescript.lexer.TsTokens
import me.kevinlot.lite.typescript.psi.TsPsiFactoryImpl

/** File root: JSX is enabled from the file name (everything but `.ts`/`.mts`/`.cts`). */
object TsFileElementType : IFileElementType("LITE_TS_FILE", TsLanguage) {
    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode {
        val jsx = TsFile.hasJsx(psi.containingFile?.name)
        val builder = PsiBuilderFactory.getInstance().createBuilder(psi.project, chameleon, TsLexer(jsx), language, chameleon.chars)
        return TsParser(jsx).parse(this, builder).firstChildNode
    }
}

class TsParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = TsLexer()
    override fun createParser(project: Project?): PsiParser = TsParser(jsx = true)
    override fun getFileNodeType(): IFileElementType = TsFileElementType
    override fun getCommentTokens(): TokenSet = TsTokens.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TsTokens.STRINGS
    override fun getWhitespaceTokens(): TokenSet = TsTokens.WHITESPACES
    override fun createElement(node: ASTNode): PsiElement = TsPsiFactoryImpl.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = TsFile(viewProvider)
}
