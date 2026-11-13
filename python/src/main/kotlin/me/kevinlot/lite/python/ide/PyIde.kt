package me.kevinlot.lite.python.ide

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
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
import me.kevinlot.lite.core.LiteNamesValidator
import me.kevinlot.lite.core.color
import me.kevinlot.lite.python.parser.PyParserDefinition
import me.kevinlot.lite.python.psi.*
import me.kevinlot.lite.python.upstream.PyElementTypes as E
import me.kevinlot.lite.python.upstream.PyTokenTypes as T
import me.kevinlot.lite.python.upstream.lexer.PythonHighlightingLexer
import me.kevinlot.lite.python.upstream.lexer.PythonLexer
import me.kevinlot.lite.python.upstream.psi.LanguageLevel

object PyColors {
    val KEYWORD = key("PY_KEYWORD", C.KEYWORD)
    val NUMBER = key("PY_NUMBER", C.NUMBER)
    val STRING = key("PY_STRING", C.STRING)
    val DOCSTRING = key("PY_DOCSTRING", C.DOC_COMMENT)
    val COMMENT = key("PY_COMMENT", C.LINE_COMMENT)
    val OPERATOR = key("PY_OPERATOR", C.OPERATION_SIGN)
    val BRACES = key("PY_BRACES", C.BRACES)
    val BRACKETS = key("PY_BRACKETS", C.BRACKETS)
    val PARENTHESES = key("PY_PARENTHESES", C.PARENTHESES)
    val CLASS = key("PY_CLASS", C.CLASS_NAME)
    val FUNCTION_DECL = key("PY_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("PY_FUNCTION_CALL", C.FUNCTION_CALL)
    val DECORATOR = key("PY_DECORATOR", C.METADATA)
    val SELF = key("PY_SELF", C.KEYWORD)
    val PARAMETER = key("PY_PARAMETER", C.PARAMETER)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class PySyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = PythonHighlightingLexer(LanguageLevel.getLatest())
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when {
            t == T.DOCSTRING -> PyColors.DOCSTRING
            t in T.STRING_NODES || t in T.FSTRING_TOKENS -> PyColors.STRING
            t in T.NUMERIC_LITERALS -> PyColors.NUMBER
            t == T.END_OF_LINE_COMMENT -> PyColors.COMMENT
            t == T.LBRACE || t == T.RBRACE -> PyColors.BRACES
            t == T.LBRACKET || t == T.RBRACKET -> PyColors.BRACKETS
            t == T.LPAR || t == T.RPAR -> PyColors.PARENTHESES
            t.toString().endsWith("_KEYWORD") || t in T.BOOL_LITERALS || t == T.NONE_KEYWORD -> PyColors.KEYWORD
            t in T.OPERATIONS -> PyColors.OPERATOR
            else -> null
        },
    )
}

class PySyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = PySyntaxHighlighter()
}

/** Structural coloring: no resolve, cheap. */
class PyHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when {
            element is PyClass -> holder.color(element.nameIdentifier, PyColors.CLASS)
            element is PyFunction -> holder.color(element.nameIdentifier, PyColors.FUNCTION_DECL)
            element.node.elementType == E.DECORATOR_CALL -> holder.color(element.children.firstOrNull { it is PyReferenceExpr } ?: element, PyColors.DECORATOR)
            element is PyReferenceExpr && element.qualifier == null && element.referencedName in SELF -> holder.color(element, PyColors.SELF)
            element is PyNamedDecl && element.node.elementType == E.NAMED_PARAMETER ->
                holder.color(element.nameIdentifier, if (element.name in SELF) PyColors.SELF else PyColors.PARAMETER)
            element is PyReferenceExpr && element.parent?.node?.elementType == E.CALL_EXPRESSION && element.parent.firstChild == element -> {
                val name = element.referencedName ?: return
                holder.color(element.nameElement, if (name.first().isUpperCase()) PyColors.CLASS else PyColors.FUNCTION_CALL)
            }
        }
    }
    private companion object { val SELF = setOf("self", "cls") }
}

class PyBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, false), Triple(T.LBRACKET, T.RBRACKET, false), Triple(T.LPAR, T.RPAR, false))

class PyCommenter : com.intellij.lang.Commenter {
    override fun getLineCommentPrefix() = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class PyFindUsagesProvider : LiteFindUsagesProvider(::PythonLexer, TokenSet.create(T.IDENTIFIER), PyParserDefinition.COMMENTS, T.STRING_NODES) {
    override fun isDeclaration(element: PsiElement) = element is PyNamedDecl
    override fun kindOf(element: PsiElement) = when (element) {
        is PyClass -> "class"
        is PyFunction -> if (element.containingClass != null) "method" else "function"
        is PyTarget -> if (element.qualifier != null) "attribute" else "variable"
        else -> if (element.node.elementType == E.NAMED_PARAMETER) "parameter" else "name"
    }
}

class PyRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) =
        element is PyNamedDecl && element.useScope is com.intellij.psi.search.LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is PyNamedDecl
}

class PyNamesValidator : LiteNamesValidator(
    setOf("False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or",
        "pass", "raise", "return", "try", "while", "with", "yield"),
)
