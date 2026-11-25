package me.kevinlot.lite.formats

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.util.IncorrectOperationException

/** A name-holding token: a declaration and/or references, as the format's [Linker] decides from offsets. */
class FmtWord(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner, PsiLanguageInjectionHost {
    private val format: Format? get() = (containingFile as? FmtFile)?.format
    val decl: Decl? get() = Fmt.links(containingFile).declsIn(textRange).firstOrNull()

    override fun getReferences(): Array<PsiReference> {
        val links = Fmt.links(containingFile)
        val r = textRange
        val refs = links.refsIn(r).map { FmtReference(this, it) } + links.pathsIn(r).map { FmtPathReference(this, it) }
        return refs.toTypedArray()
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
    override fun getNameIdentifier(): PsiElement? = takeIf { decl != null }
    override fun getName(): String? = decl?.range?.shiftLeft(textRange.startOffset)?.substring(text)

    override fun setName(name: String): PsiElement {
        val d = decl ?: throw IncorrectOperationException("Not a declaration")
        replaceIn(d.range, name)
        return this
    }

    /** Replaces [range] (file offsets, inside this token) by [text]. */
    fun replaceIn(range: TextRange, text: String) = replaceLocal(range.shiftLeft(textRange.startOffset), text)

    /** A value may hold another language (a cron expression): the text is taken as it is written. */
    override fun isValidHost(): Boolean = true
    override fun createLiteralTextEscaper(): LiteralTextEscaper<FmtWord> = LiteralTextEscaper.createSimple(this)
    override fun updateText(text: String): PsiLanguageInjectionHost = apply { replaceLocal(TextRange(0, textLength), text) }

    /** Replaces [local] (offsets in this token) by [text]. */
    fun replaceLocal(local: TextRange, text: String) {
        val leaf = node.firstChildNode as? LeafElement ?: return
        leaf.replaceWithText(local.replace(leaf.text, text))
    }
}

class FmtReference(element: FmtWord, private val ref: Ref) :
    PsiPolyVariantReferenceBase<FmtWord>(element, ref.range.shiftLeft(element.textRange.startOffset), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = PsiElementResolveResult.createResults(Fmt.resolve(element, ref))
    /** Offsets relative to the element: earlier renames in the same file may have moved it. */
    override fun handleElementRename(newElementName: String): PsiElement {
        element.replaceLocal(ref.nameRange.shiftLeft(ref.range.startOffset).shiftRight(rangeInElement.startOffset), newElementName)
        return element
    }
}

class FmtPathReference(element: FmtWord, private val link: PathLink) :
    PsiPolyVariantReferenceBase<FmtWord>(element, link.range.shiftLeft(element.textRange.startOffset), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile as? FmtFile ?: return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(listOfNotNull(file.format.linker?.resolvePath(file, link.path)))
    }
    override fun handleElementRename(newElementName: String): PsiElement {
        val linker = (element.containingFile as? FmtFile)?.format?.linker ?: return element
        element.replaceLocal(rangeInElement, linker.renamedPath(rangeInElement.substring(element.text), newElementName))
        return element
    }
}
