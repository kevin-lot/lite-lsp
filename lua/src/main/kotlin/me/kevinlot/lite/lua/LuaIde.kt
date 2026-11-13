package me.kevinlot.lite.lua

import com.intellij.lang.Commenter
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
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
import me.kevinlot.lite.core.LiteNamesValidator
import me.kevinlot.lite.core.color
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import me.kevinlot.lite.lua.LuaElementTypes as E
import me.kevinlot.lite.lua.LuaTokens as T

object LuaColors {
    val KEYWORD = key("LUA_KEYWORD", C.KEYWORD)
    val COMMENT = key("LUA_COMMENT", C.LINE_COMMENT)
    val STRING = key("LUA_STRING", C.STRING)
    val NUMBER = key("LUA_NUMBER", C.NUMBER)
    val OPERATOR = key("LUA_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("LUA_BRACES", C.BRACES)
    val PARENS = key("LUA_PARENS", C.PARENTHESES)
    val BRACKETS = key("LUA_BRACKETS", C.BRACKETS)
    val FUNCTION_DECL = key("LUA_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("LUA_FUNCTION_CALL", C.FUNCTION_CALL)
    val PARAMETER = key("LUA_PARAMETER", C.PARAMETER)
    val FIELD = key("LUA_FIELD", C.INSTANCE_FIELD)
    val GLOBAL = key("LUA_GLOBAL", C.GLOBAL_VARIABLE)
    val SELF = key("LUA_SELF", C.KEYWORD)
    val LABEL = key("LUA_LABEL", C.LABEL)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class LuaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = LuaLexer()
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when (t) {
            in T.KEYWORD_SET -> LuaColors.KEYWORD
            T.COMMENT, T.SHEBANG -> LuaColors.COMMENT
            T.STRING -> LuaColors.STRING
            T.NUMBER -> LuaColors.NUMBER
            in T.OPERATORS -> LuaColors.OPERATOR
            T.LBRACE, T.RBRACE -> LuaColors.BRACES
            T.LPAREN, T.RPAREN -> LuaColors.PARENS
            T.LBRACKET, T.RBRACKET -> LuaColors.BRACKETS
            T.DOUBLE_COLON -> LuaColors.LABEL
            else -> null
        },
    )
}

class LuaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = LuaSyntaxHighlighter()
}

/** Structure only (no resolve): declared functions, called names, parameters, fields, `self`, labels. */
class LuaHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is LuaNameDecl -> when {
                element.isLocalFunction -> holder.color(element, LuaColors.FUNCTION_DECL)
                element.isParameter -> holder.color(element, LuaColors.PARAMETER)
                element.isLabel -> holder.color(element, LuaColors.LABEL)
            }
            is LuaIndexExpr -> holder.color(element.field, when {
                element.parent?.node?.elementType == E.FUNC_NAME -> LuaColors.FUNCTION_DECL
                element.parent is LuaCall && element.parent.firstChild == element -> LuaColors.FUNCTION_CALL
                else -> LuaColors.FIELD
            })
            is LuaNameRef -> when {
                element.text == "self" -> holder.color(element, LuaColors.SELF)
                element.parent?.node?.elementType == E.FUNC_NAME -> holder.color(element, LuaColors.FUNCTION_DECL)
                element.parent is LuaCall && element.parent.firstChild == element -> holder.color(element, LuaColors.FUNCTION_CALL)
                element.parent?.node?.elementType == E.GOTO_STMT -> holder.color(element, LuaColors.LABEL)
            }
            is LuaTableField -> holder.color(element.nameIdentifier, LuaColors.FIELD)
        }
    }
}

class LuaBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, true), Triple(T.LPAREN, T.RPAREN, false), Triple(T.LBRACKET, T.RBRACKET, false))

class LuaCommenter : Commenter {
    override fun getLineCommentPrefix() = "--"
    override fun getBlockCommentPrefix() = "--[["
    override fun getBlockCommentSuffix() = "]]"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class LuaFindUsagesProvider : LiteFindUsagesProvider(::LuaLexer, TokenSet.create(T.IDENT), T.COMMENTS, T.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is LuaNameDecl || element is LuaTableField ||
        (element is LuaNameRef && element.isGlobalDefinition) || (element is LuaIndexExpr && element.isDefinitionSite)
    override fun kindOf(element: PsiElement) = when {
        element is LuaNameDecl && element.isParameter -> "parameter"
        element is LuaNameDecl && element.isLabel -> "label"
        element is LuaNameDecl && element.isLocalFunction -> "local function"
        element is LuaNameDecl -> "local"
        element is LuaNameRef -> "global"
        else -> "field"
    }
}

class LuaRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is LuaNameDecl && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is LuaNameDecl || element is LuaNameRef || element is LuaIndexExpr || element is LuaTableField
}

class LuaNamesValidator : LiteNamesValidator(T.KEYWORDS.keys)
