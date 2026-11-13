package me.kevinlot.lite.qml.ide

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteCStyleCommenter
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.color
import me.kevinlot.lite.qml.psi.*
import me.kevinlot.lite.qml.resolve.QmlResolver
import me.kevinlot.lite.typescript.ide.TsNamesValidator
import me.kevinlot.lite.typescript.ide.TsSyntaxHighlighter
import me.kevinlot.lite.typescript.lexer.TsLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import me.kevinlot.lite.qml.parser.QmlElementTypes as Q
import me.kevinlot.lite.typescript.lexer.TsTokens as T

object QmlColors {
    val KEYWORD = key("QML_KEYWORD", C.KEYWORD)
    val TYPE = key("QML_TYPE", C.CLASS_NAME)
    val ID = key("QML_ID", C.STATIC_FIELD)
    val PROPERTY = key("QML_PROPERTY", C.INSTANCE_FIELD)
    val HANDLER = key("QML_HANDLER", C.INSTANCE_METHOD)
    val SIGNAL = key("QML_SIGNAL", C.FUNCTION_DECLARATION)
    val MODULE = key("QML_MODULE", C.IDENTIFIER)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

/** The JavaScript lexer colors QML too (same tokens); the annotator adds the QML structure. */
class QmlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = TsSyntaxHighlighter(jsx = false)
}

/** Structural coloring, no resolve. */
class QmlHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when {
            element.node.elementType == T.SOFT_KEYWORD && element.parent is QmlElement -> holder.color(element, QmlColors.KEYWORD)
            element is QmlTypeRef -> holder.color(element, QmlColors.TYPE)
            element is QmlNamed -> holder.color(element.nameIdentifier, when (element.type) {
                Q.ID -> QmlColors.ID
                Q.SIGNAL -> QmlColors.SIGNAL
                Q.INLINE_COMPONENT, Q.ENUM -> QmlColors.TYPE
                else -> QmlColors.PROPERTY
            })
            element is QmlPropertyRef -> {
                val name = element.referencedName ?: return
                val attached = element.qualifier == null && name.first().isUpperCase() && element.parent is QmlPropertyRef
                holder.color(element.nameElement, if (QmlResolver.isHandler(name)) QmlColors.HANDLER else if (attached) QmlColors.TYPE else QmlColors.PROPERTY)
            }
            element.node.elementType == Q.MODULE_URI -> holder.color(element, QmlColors.MODULE)
        }
    }
}

class QmlBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, true), Triple(T.LBRACKET, T.RBRACKET, false), Triple(T.LPAR, T.RPAR, false))

class QmlCommenter : LiteCStyleCommenter()

class QmlFindUsagesProvider : LiteFindUsagesProvider({ TsLexer(jsx = false) }, TokenSet.create(T.IDENTIFIER), T.COMMENTS, T.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is QmlNamed
    override fun kindOf(element: PsiElement) = when ((element as? QmlNamed)?.type) {
        Q.ID -> "id"
        Q.SIGNAL -> "signal"
        Q.SIGNAL_PARAMETER -> "parameter"
        Q.ENUM -> "enum"
        Q.ENUM_MEMBER -> "enum value"
        Q.INLINE_COMPONENT -> "component"
        else -> "property"
    }
}

class QmlRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is QmlNamed && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is QmlNamed
}

class QmlNamesValidator : TsNamesValidator()
