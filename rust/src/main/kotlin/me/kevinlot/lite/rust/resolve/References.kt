package me.kevinlot.lite.rust.resolve

import com.intellij.psi.PsiElement
import me.kevinlot.lite.rust.psi.RsFieldLookup
import me.kevinlot.lite.rust.psi.RsMethodCall
import me.kevinlot.lite.rust.psi.RsPatBinding
import me.kevinlot.lite.rust.psi.RsPath
import me.kevinlot.lite.rust.psi.RsStructLiteralField
import me.kevinlot.lite.rust.psi.ext.RsElement
import me.kevinlot.lite.rust.psi.ext.RsFieldLookupMixin
import me.kevinlot.lite.rust.psi.ext.RsMethodCallMixin
import me.kevinlot.lite.rust.psi.ext.RsPathMixin
import me.kevinlot.lite.rust.psi.ext.RsStructLiteralFieldMixin

/** Rust flavour of the shared reference: renames through a Rust identifier token. */
abstract class RsReferenceBase<T : RsElement>(element: T, nameElement: () -> PsiElement?) :
    me.kevinlot.lite.core.LiteReference<T>(element, nameElement) {

    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? =
        if (nameToken.node.elementType == me.kevinlot.lite.rust.psi.RsElementTypes.IDENTIFIER)
            me.kevinlot.lite.rust.psi.RsPsiFactory(element.project).createIdentifier(newName)
        else null
}

class RsPathReference(element: RsPath) :
    RsReferenceBase<RsPath>(element, { (element as RsPathMixin).referenceNameElement }) {
    override fun resolveTargets(): List<PsiElement> = NameResolver.resolvePath(element)
}

class RsMethodReference(element: RsMethodCall) :
    RsReferenceBase<RsMethodCall>(element, { (element as RsMethodCallMixin).nameElement }) {
    override fun resolveTargets(): List<PsiElement> = NameResolver.resolveMethod(element)
}

class RsFieldReference(element: RsFieldLookup) :
    RsReferenceBase<RsFieldLookup>(element, { (element as RsFieldLookupMixin).nameElement }) {
    override fun resolveTargets(): List<PsiElement> = NameResolver.resolveField(element)
}

class RsStructLiteralFieldReference(element: RsStructLiteralField) :
    RsReferenceBase<RsStructLiteralField>(element, { (element as RsStructLiteralFieldMixin).nameElement }) {
    override fun resolveTargets(): List<PsiElement> = NameResolver.resolveStructLiteralField(element)
}

/** Only resolves when the binding is really a path to a unit struct/variant or a const. */
class RsPatBindingReference(element: RsPatBinding) : RsReferenceBase<RsPatBinding>(element, { element.nameIdentifier }) {
    override fun resolveTargets(): List<PsiElement> = NameResolver.resolvePatBinding(element)
}

class RsModDeclReference(element: me.kevinlot.lite.rust.psi.RsModDeclItem) :
    RsReferenceBase<me.kevinlot.lite.rust.psi.RsModDeclItem>(element, { element.nameIdentifier }) {
    override fun resolveTargets(): List<PsiElement> = listOfNotNull(ModuleTree.resolveModDecl(element))
}
