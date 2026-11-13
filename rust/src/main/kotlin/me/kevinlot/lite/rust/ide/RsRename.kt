package me.kevinlot.lite.rust.ide

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.rust.parser.RsLexer
import me.kevinlot.lite.rust.psi.*
import me.kevinlot.lite.rust.psi.RsElementTypes.IDENTIFIER
import me.kevinlot.lite.rust.psi.ext.RsNamedElement

/** Needed by rename: finds candidate files through the identifier word index. */
class RsFindUsagesProvider : me.kevinlot.lite.core.LiteFindUsagesProvider(
    ::RsLexer, TokenSet.create(IDENTIFIER), RS_COMMENTS, RS_ALL_STRING_LITERALS,
) {
    override fun isDeclaration(element: PsiElement): Boolean = element is RsNamedElement

    override fun kindOf(element: PsiElement): String = when (element) {
        is RsFunction -> "function"
        is RsStructItem -> "struct"
        is RsEnumItem -> "enum"
        is RsEnumVariant -> "variant"
        is RsTraitItem -> "trait"
        is RsTypeAlias -> "type alias"
        is RsConstant -> "constant"
        is RsNamedFieldDecl -> "field"
        is RsModItem, is RsModDeclItem -> "module"
        is RsPatBinding -> "binding"
        is RsTypeParameter -> "type parameter"
        is RsMacro -> "macro"
        else -> "item"
    }
}

/** In-place rename (inline in the editor) for locals; dialog for everything else. */
class RsRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is RsPatBinding || element is RsTypeParameter

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = element is RsNamedElement
}

class RsNamesValidator : me.kevinlot.lite.core.LiteNamesValidator(KEYWORDS) {
    override fun unescape(name: String): String = name.removePrefix("r#")
    override fun isEscaped(name: String): Boolean = name.startsWith("r#")

    private companion object {
        val KEYWORDS = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn",
            "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self",
            "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "yield",
        )
    }
}
