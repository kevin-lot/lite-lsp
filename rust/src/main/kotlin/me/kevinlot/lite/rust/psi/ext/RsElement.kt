package me.kevinlot.lite.rust.psi.ext

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.rust.psi.RsElementTypes
import me.kevinlot.lite.rust.psi.RsPsiFactory

interface RsElement : PsiElement

abstract class RsElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), RsElement

/** An item, binding, field… that can be the target of Ctrl+Click and can be renamed. */
interface RsNamedElement : RsElement, PsiNameIdentifierOwner

open class RsNamedElementImpl(node: ASTNode) : RsElementImpl(node), RsNamedElement {
    override fun getNameIdentifier(): PsiElement? =
        if (this is me.kevinlot.lite.rust.psi.RsMacro) {
            // `macro_rules! name`: "macro_rules" is itself lexed as an identifier.
            node.getChildren(com.intellij.psi.tree.TokenSet.create(RsElementTypes.IDENTIFIER)).lastOrNull()?.psi
        } else {
            node.findChildByType(RsElementTypes.IDENTIFIER)?.psi
        }

    override fun getName(): String? = nameIdentifier?.text?.removePrefix("r#")

    override fun setName(name: String): PsiElement {
        val id = nameIdentifier ?: throw IncorrectOperationException("No name identifier")
        id.replace(RsPsiFactory(project).createIdentifier(name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getReference(): PsiReference? = null

    /** Bindings and items declared inside a function body are only visible there. */
    override fun getUseScope(): com.intellij.psi.search.SearchScope {
        val local = when {
            this is me.kevinlot.lite.rust.psi.RsPatBinding || this is me.kevinlot.lite.rust.psi.RsTypeParameter ->
                com.intellij.psi.util.PsiTreeUtil.getParentOfType(this, me.kevinlot.lite.rust.psi.RsLambdaExpr::class.java,
                    me.kevinlot.lite.rust.psi.RsFunction::class.java, me.kevinlot.lite.rust.psi.RsImplItem::class.java,
                    me.kevinlot.lite.rust.psi.RsTraitItem::class.java, me.kevinlot.lite.rust.psi.RsStructItem::class.java,
                    me.kevinlot.lite.rust.psi.RsEnumItem::class.java)
            parent is me.kevinlot.lite.rust.psi.RsBlock -> parent
            else -> null
        }
        return me.kevinlot.lite.core.localUseScope(local) ?: super.getUseScope()
    }
}
