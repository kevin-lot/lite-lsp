package me.kevinlot.lite.rust.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import me.kevinlot.lite.rust.psi.RsElementTypes
import me.kevinlot.lite.rust.resolve.RsFieldReference
import me.kevinlot.lite.rust.resolve.RsMethodReference
import me.kevinlot.lite.rust.resolve.RsPatBindingReference
import me.kevinlot.lite.rust.resolve.RsPathReference
import me.kevinlot.lite.rust.resolve.RsStructLiteralFieldReference

/** `a::b::c` — the reference is on the last segment of each (nested) path node. */
abstract class RsPathMixin(node: ASTNode) : RsElementImpl(node) {
    /** The segment token: identifier, `self`, `super`, `crate` or `Self`. */
    val referenceNameElement: PsiElement?
        get() = node.findChildByType(PATH_IDENTS)?.psi

    val referenceName: String? get() = referenceNameElement?.text?.removePrefix("r#")

    override fun getReference(): PsiReference? = referenceNameElement?.let { RsPathReference(this as me.kevinlot.lite.rust.psi.RsPath) }

    private companion object {
        val PATH_IDENTS = com.intellij.psi.tree.TokenSet.create(
            RsElementTypes.IDENTIFIER, RsElementTypes.SELF, RsElementTypes.SUPER,
            RsElementTypes.CRATE, RsElementTypes.CSELF,
        )
    }
}

/** `.foo(...)` */
abstract class RsMethodCallMixin(node: ASTNode) : RsElementImpl(node) {
    val nameElement: PsiElement? get() = node.findChildByType(RsElementTypes.IDENTIFIER)?.psi
    override fun getReference(): PsiReference? = nameElement?.let { RsMethodReference(this as me.kevinlot.lite.rust.psi.RsMethodCall) }
}

/** `.foo` or `.0` */
abstract class RsFieldLookupMixin(node: ASTNode) : RsElementImpl(node) {
    val nameElement: PsiElement?
        get() = (node.findChildByType(RsElementTypes.IDENTIFIER) ?: node.findChildByType(RsElementTypes.INTEGER_LITERAL))?.psi
    override fun getReference(): PsiReference? = nameElement?.let { RsFieldReference(this as me.kevinlot.lite.rust.psi.RsFieldLookup) }
}

/** `Foo { bar: 1, baz }` */
abstract class RsStructLiteralFieldMixin(node: ASTNode) : RsElementImpl(node) {
    val nameElement: PsiElement?
        get() = (node.findChildByType(RsElementTypes.IDENTIFIER) ?: node.findChildByType(RsElementTypes.INTEGER_LITERAL))?.psi
    override fun getReference(): PsiReference? =
        nameElement?.let { RsStructLiteralFieldReference(this as me.kevinlot.lite.rust.psi.RsStructLiteralField) }
}

/**
 * `let x = …`, `|x| …`, `Some(x)` — a binding declaration, except when the identifier names an
 * existing unit variant / const (`None`, `MAX`): then it is a reference.
 */
abstract class RsPatBindingMixin(node: ASTNode) : RsNamedElementImpl(node) {
    override fun getReference(): PsiReference? = RsPatBindingReference(this as me.kevinlot.lite.rust.psi.RsPatBinding)
}

/** `mod foo;` — Ctrl+Click on the name opens `foo.rs` / `foo/mod.rs`. */
abstract class RsModDeclItemMixin(node: ASTNode) : RsNamedElementImpl(node) {
    override fun getReference(): PsiReference? = nameIdentifier?.let { me.kevinlot.lite.rust.resolve.RsModDeclReference(this as me.kevinlot.lite.rust.psi.RsModDeclItem) }
}
