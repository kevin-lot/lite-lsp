package me.kevinlot.lite.shell

import com.intellij.lang.Commenter
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.color
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import me.kevinlot.lite.shell.ShElementTypes as E
import me.kevinlot.lite.shell.ShTokens as T

object ShColors {
    val KEYWORD = key("SH_KEYWORD", C.KEYWORD)
    val COMMENT = key("SH_COMMENT", C.LINE_COMMENT)
    val STRING = key("SH_STRING", C.STRING)
    val HEREDOC = key("SH_HEREDOC", C.STRING)
    val HEREDOC_MARKER = key("SH_HEREDOC_MARKER", C.LABEL)
    val NUMBER = key("SH_NUMBER", C.NUMBER)
    val VARIABLE = key("SH_VARIABLE", C.INSTANCE_FIELD)
    val EXPANSION = key("SH_EXPANSION", C.BRACES)
    val OPERATOR = key("SH_OPERATOR", C.OPERATION_SIGN)
    val REDIRECT = key("SH_REDIRECT", C.OPERATION_SIGN)
    val FUNCTION_DECL = key("SH_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val COMMAND = key("SH_COMMAND", C.FUNCTION_CALL)
    val ASSIGNED = key("SH_ASSIGNED", C.INSTANCE_FIELD)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class ShSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ShLexer()
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when (t) {
            in T.KEYWORD_SET -> ShColors.KEYWORD
            T.COMMENT -> ShColors.COMMENT
            T.STRING_SINGLE, T.STRING_TEXT, T.DQUOTE_START, T.DQUOTE_END -> ShColors.STRING
            T.HEREDOC_TEXT -> ShColors.HEREDOC
            T.HEREDOC_DELIM, T.HEREDOC_END, T.HEREDOC_OP -> ShColors.HEREDOC_MARKER
            T.NUMBER -> ShColors.NUMBER
            T.VAR, T.VAR_NAME -> ShColors.VARIABLE
            T.PARAM_START, T.PARAM_END, T.SUBST_START, T.SUBST_END, T.ARITH_START, T.ARITH_END, T.BACKTICK_START, T.BACKTICK_END -> ShColors.EXPANSION
            T.REDIRECT -> ShColors.REDIRECT
            T.PIPE, T.AND_AND, T.OR_OR, T.SEMI, T.CASE_END, T.AMP -> ShColors.OPERATOR
            else -> null
        },
    )
}

class ShSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ShSyntaxHighlighter()
}

/** Function definitions, command names and assigned variables (no resolve). */
class ShHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is ShFunction -> holder.color(element.nameIdentifier, ShColors.FUNCTION_DECL)
            is ShCommandName -> holder.color(element.nameToken, ShColors.COMMAND)
            is ShVarDecl -> holder.color(element.nameIdentifier, ShColors.ASSIGNED)
        }
    }
}

class ShBraceMatcher : LiteBraceMatcher(
    Triple(T.LBRACE, T.RBRACE, true), Triple(T.LPAR, T.RPAR, false), Triple(T.TEST_START, T.TEST_END, false),
    Triple(T.PARAM_START, T.PARAM_END, false), Triple(T.SUBST_START, T.SUBST_END, false), Triple(T.ARITH_START, T.ARITH_END, false),
)

class ShCommenter : Commenter {
    override fun getLineCommentPrefix() = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class ShFindUsagesProvider : LiteFindUsagesProvider(::ShLexer, TokenSet.create(T.WORD, T.VAR, T.VAR_NAME, T.ASSIGN_NAME), T.COMMENTS, T.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is ShFunction || element is ShVarDecl
    override fun kindOf(element: PsiElement) = if (element is ShFunction) "function" else "variable"
}

class ShRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is ShVarDecl && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is ShFunction || element is ShVarDecl
}

/** Variables: `[A-Za-z_][A-Za-z0-9_]*`; function names may also hold `-`, `.` and `:`. */
class ShNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?) = name in T.KEYWORDS
    override fun isIdentifier(name: String, project: Project?) = NAME.matches(name) && name !in T.KEYWORDS
    private companion object { val NAME = Regex("[A-Za-z_][A-Za-z0-9_:.-]*") }
}
