package me.kevinlot.lite.rust.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.rust.RsFile
import me.kevinlot.lite.rust.RsLanguage
import me.kevinlot.lite.rust.lexer._RustLexer
import me.kevinlot.lite.rust.psi.RS_ALL_STRING_LITERALS
import me.kevinlot.lite.rust.psi.RS_COMMENTS
import me.kevinlot.lite.rust.psi.RsElementTypes
import me.kevinlot.lite.rust.psi.RsTokenType

class RsLexer : FlexAdapter(_RustLexer())

class RustParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = RsLexer()
    override fun createParser(project: Project?): PsiParser = RustParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = RS_COMMENTS
    override fun getStringLiteralElements(): TokenSet = RS_ALL_STRING_LITERALS
    override fun createElement(node: ASTNode): PsiElement = RsElementTypes.Factory.createElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = RsFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(RsLanguage)
        @JvmField val BLOCK_COMMENT = RsTokenType("<BLOCK_COMMENT>")
        @JvmField val EOL_COMMENT = RsTokenType("<EOL_COMMENT>")
        @JvmField val INNER_BLOCK_DOC_COMMENT = RsTokenType("<INNER_BLOCK_DOC_COMMENT>")
        @JvmField val OUTER_BLOCK_DOC_COMMENT = RsTokenType("<OUTER_BLOCK_DOC_COMMENT>")
        @JvmField val INNER_EOL_DOC_COMMENT = RsTokenType("<INNER_EOL_DOC_COMMENT>")
        @JvmField val OUTER_EOL_DOC_COMMENT = RsTokenType("<OUTER_EOL_DOC_COMMENT>")
    }
}
