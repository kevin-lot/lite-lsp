package me.kevinlot.lite.markdown

import com.intellij.lang.Commenter
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.color
import java.awt.Font
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import me.kevinlot.lite.markdown.MdElementTypes as E
import me.kevinlot.lite.markdown.MdTokens as T

object MdColors {
    val HEADER = key("MD_HEADER", C.KEYWORD)
    val MARKER = key("MD_MARKER", C.OPERATION_SIGN)
    val CODE = key("MD_CODE", C.STRING)
    val FENCE = key("MD_FENCE", C.BLOCK_COMMENT)
    val LINK_TEXT = key("MD_LINK_TEXT", C.FUNCTION_CALL)
    val LINK_DEST = key("MD_LINK_DEST", C.HIGHLIGHTED_REFERENCE)
    val LABEL = key("MD_LABEL", C.LABEL)
    val HTML = key("MD_HTML", C.MARKUP_TAG)
    val COMMENT = key("MD_COMMENT", C.BLOCK_COMMENT)
    val FRONT_MATTER = key("MD_FRONT_MATTER", C.DOC_COMMENT)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class MdSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = MdLexer()
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when (t) {
            T.HEADER_MARKER -> MdColors.HEADER
            T.QUOTE_MARKER, T.LIST_MARKER, T.CHECKBOX, T.HR, T.EMPH, T.STRONG, T.STRIKE, T.PIPE -> MdColors.MARKER
            T.CODE_SPAN, T.CODE_TEXT -> MdColors.CODE
            T.FENCE_START, T.FENCE_END, T.FENCE_INFO -> MdColors.FENCE
            T.LINK_DEST, T.AUTOLINK, T.URL -> MdColors.LINK_DEST
            T.LINK_LABEL -> MdColors.LABEL
            T.HTML -> MdColors.HTML
            T.HTML_COMMENT -> MdColors.COMMENT
            T.FRONT_MATTER -> MdColors.FRONT_MATTER
            else -> null
        },
    )
}

class MdSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = MdSyntaxHighlighter()
}

/** Headers in bold header color, emphasis in italic/bold/struck, link texts. */
class MdHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element.node.elementType) {
            E.HEADER -> { holder.color(element, MdColors.HEADER); style(holder, element, TextAttributes(null, null, null, null, Font.BOLD)) }
            E.STRONG -> style(holder, element, TextAttributes(null, null, null, null, Font.BOLD))
            E.EMPHASIS -> style(holder, element, TextAttributes(null, null, null, null, Font.ITALIC))
            E.STRIKE -> style(holder, element, TextAttributes(null, null, java.awt.Color.GRAY, EffectType.STRIKEOUT, Font.PLAIN))
            E.LINK -> element.node.getChildren(TokenSet.create(T.TEXT)).forEach { holder.color(it.psi, MdColors.LINK_TEXT) }
        }
    }

    private fun style(holder: AnnotationHolder, element: PsiElement, attributes: TextAttributes) =
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).enforcedTextAttributes(attributes).create()
}

class MdBraceMatcher : LiteBraceMatcher(Triple(T.LBRACKET, T.RBRACKET, false), Triple(T.LPAREN, T.RPAREN, false))

class MdCommenter : Commenter {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix() = "<!--"
    override fun getBlockCommentSuffix() = "-->"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class MdFindUsagesProvider : LiteFindUsagesProvider(::MdLexer, TokenSet.create(T.TEXT, T.LINK_DEST, T.LINK_LABEL), T.COMMENTS, TokenSet.EMPTY) {
    override fun isDeclaration(element: PsiElement) = element is MdHeader || element is MdLinkDefinition
    override fun kindOf(element: PsiElement) = if (element is MdHeader) "header" else "link definition"
}

class MdRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is MdHeader || element is MdLinkDefinition
}

/** A header or a label: any one-line text. */
class MdNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?) = false
    override fun isIdentifier(name: String, project: Project?) = name.isNotBlank() && '\n' !in name
}
