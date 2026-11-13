package me.kevinlot.lite.core

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache

/**
 * Reference on a name token of [element]: poly-variant (several targets give a chooser),
 * cached per PSI modification, renamed through [renameIdentifier].
 */
abstract class LiteReference<T : PsiElement>(element: T, private val nameElement: () -> PsiElement?) :
    PsiPolyVariantReferenceBase<T>(element) {

    abstract fun resolveTargets(): List<PsiElement>

    /** Builds the identifier token that replaces the old name, or null if the name token is not renamable. */
    protected abstract fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement?

    override fun getRangeInElement(): TextRange {
        val id = nameElement() ?: return TextRange.EMPTY_RANGE
        return TextRange.from(id.textRange.startOffset - element.textRange.startOffset, id.textLength)
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, true, incompleteCode)

    override fun resolve(): PsiElement? = multiResolve(false).firstOrNull()?.element

    override fun handleElementRename(newElementName: String): PsiElement {
        val id = nameElement() ?: return element
        renameIdentifier(id, newElementName)?.let { id.replace(it) }
        return element
    }

    override fun getVariants(): Array<Any> = emptyArray()

    private companion object {
        val RESOLVER = ResolveCache.PolyVariantResolver<LiteReference<*>> { ref, _ ->
            ref.resolveTargets().distinct().map { PsiElementResolveResult(it) }.toTypedArray()
        }
    }
}
