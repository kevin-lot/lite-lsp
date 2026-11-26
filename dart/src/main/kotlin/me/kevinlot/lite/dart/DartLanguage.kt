package me.kevinlot.lite.dart

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon
import me.kevinlot.lite.dart.upstream.DartLexer
import me.kevinlot.lite.dart.upstream.DartParser
import me.kevinlot.lite.dart.upstream.DartTokenTypesSets

object DartLanguage : Language("LiteDart") {
    private fun readResolve(): Any = DartLanguage
    override fun getDisplayName() = "Dart"
}

object DartFileType : LanguageFileType(DartLanguage) {
    override fun getName() = "Dart (Lite)"
    override fun getDescription() = "Dart"
    override fun getDefaultExtension() = "dart"
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/dart.svg", DartFileType::class.java)
}

class DartFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DartLanguage) {
    override fun getFileType() = DartFileType
    override fun toString() = "Dart file"
}

class DartParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = DartLexer()
    override fun createParser(project: Project?): PsiParser = DartParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = DartTokenTypesSets.COMMENTS
    override fun getStringLiteralElements(): TokenSet = DartTokenTypesSets.STRINGS
    override fun createElement(node: ASTNode): PsiElement = DartPsi.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = DartFile(viewProvider)

    companion object {
        val FILE = IFileElementType("LITE_DART_FILE", DartLanguage)
    }
}
