package me.kevinlot.lite.python.parser

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.python.psi.PyPsiFactoryImpl
import me.kevinlot.lite.python.upstream.PyTokenTypes
import me.kevinlot.lite.python.upstream.PythonFileType
import me.kevinlot.lite.python.upstream.PythonLanguage
import me.kevinlot.lite.python.upstream.lexer.PythonIndentingLexer
import me.kevinlot.lite.python.upstream.parsing.PyParser

class PyFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, PythonLanguage.INSTANCE) {
    override fun getFileType() = PythonFileType.INSTANCE
    override fun toString() = "Python file"
}

class PyParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = PythonIndentingLexer()
    override fun createParser(project: Project?): PsiParser = PyParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = PyTokenTypes.STRING_NODES
    // Line breaks are whitespace for the builder: the indenting lexer emits STATEMENT_BREAK/INDENT/DEDENT itself.
    override fun getWhitespaceTokens(): TokenSet = PyTokenTypes.WHITESPACE_OR_LINEBREAK
    override fun createElement(node: ASTNode): PsiElement = PyPsiFactoryImpl.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = PyFile(viewProvider)

    companion object {
        val FILE = IFileElementType("LITE_PYTHON_FILE", PythonLanguage.INSTANCE)
        val COMMENTS: TokenSet = TokenSet.create(PyTokenTypes.END_OF_LINE_COMMENT)
    }
}
