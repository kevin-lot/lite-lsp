package me.kevinlot.lite.markdown

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** CommonMark + the GitHub extensions agents write (tables, task lists, strikethrough, front matter). */
object MdLanguage : Language("LiteMarkdown") {
    private fun readResolve(): Any = MdLanguage
    override fun getDisplayName(): String = "Markdown"
}

object MdFileType : LanguageFileType(MdLanguage) {
    override fun getName(): String = "Markdown (Lite)"
    override fun getDisplayName(): String = "Markdown"
    override fun getDescription(): String = "Markdown document"
    override fun getDefaultExtension(): String = "md"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/markdown.svg", MdFileType::class.java) }
}

class MdFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MdLanguage) {
    override fun getFileType() = MdFileType
    override fun toString(): String = "Markdown file"
}

class MdTokenType(debugName: String) : IElementType(debugName, MdLanguage)

object MdTokens {
    private fun t(name: String) = MdTokenType(name)
    val TEXT = t("TEXT")
    val NEWLINE = t("NEWLINE")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val HEADER_MARKER = t("HEADER_MARKER")
    val QUOTE_MARKER = t("QUOTE_MARKER")
    val LIST_MARKER = t("LIST_MARKER")
    val CHECKBOX = t("CHECKBOX")
    val HR = t("HR")
    val FENCE_START = t("FENCE_START")
    val FENCE_INFO = t("FENCE_INFO")
    val FENCE_END = t("FENCE_END")
    val CODE_TEXT = t("CODE_TEXT")
    val CODE_SPAN = t("CODE_SPAN")
    val FRONT_MATTER = t("FRONT_MATTER")
    val LBRACKET = t("["); val RBRACKET = t("]"); val IMAGE_START = t("![")
    val LPAREN = t("("); val RPAREN = t(")")
    val LINK_DEST = t("LINK_DEST")
    val LINK_LABEL = t("LINK_LABEL")          // the label of a definition: [label]: url
    val DEF_COLON = t("DEF_COLON")
    val AUTOLINK = t("AUTOLINK")              // <https://…>
    val URL = t("URL")                        // bare https://…
    val HTML = t("HTML")
    val HTML_COMMENT = t("HTML_COMMENT")
    val EMPH = t("EMPH")                      // * or _
    val STRONG = t("STRONG")                  // ** or __
    val STRIKE = t("STRIKE")                  // ~~
    val PIPE = t("|")

    val COMMENTS = TokenSet.create(HTML_COMMENT)
    val WHITESPACES = TokenSet.create(TokenType.WHITE_SPACE)
    val TEXTS = TokenSet.create(TEXT, CODE_TEXT, CODE_SPAN, LINK_DEST)
}
