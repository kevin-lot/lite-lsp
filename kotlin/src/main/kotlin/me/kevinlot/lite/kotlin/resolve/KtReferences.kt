package me.kevinlot.lite.kotlin.resolve

import com.intellij.psi.PsiElement
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression
import me.kevinlot.lite.kotlin.psi.KtPsiFactory
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens

/** Reference of any simple name: local, member, top-level, import segment, type, Java class. */
class KtSimpleNameReference(element: KtNameReferenceExpression) : LiteReference<KtNameReferenceExpression>(element, { element.identifier }) {
    override fun resolveTargets(): List<PsiElement> = KtResolver.resolve(element)

    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? {
        if (nameToken.node.elementType != KtTokens.IDENTIFIER) return null
        val name = when {
            KtCatalog.isEntry(resolve()) -> KtCatalog.renamed(element, newName) ?: return null
            else -> newName
        }
        return KtPsiFactory(element.project).createIdentifier(name)
    }
}
