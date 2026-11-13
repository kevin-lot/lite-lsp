package me.kevinlot.lite.kotlin.resolve

import com.intellij.psi.PsiElement
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression
import me.kevinlot.lite.kotlin.psi.KtPsiFactory

/** Reference of any simple name: local, member, top-level, import segment, type, Java class. */
class KtSimpleNameReference(element: KtNameReferenceExpression) : LiteReference<KtNameReferenceExpression>(element, { element.identifier }) {
    override fun resolveTargets(): List<PsiElement> = KtResolver.resolve(element)

    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? =
        if (nameToken.node.elementType == KtTokens.IDENTIFIER) KtPsiFactory(element.project).createIdentifier(newName) else null
}
