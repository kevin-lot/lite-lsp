package me.kevinlot.lite.lua

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.lua.LuaElementTypes as E
import me.kevinlot.lite.lua.LuaTokens as T

open class LuaElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val type get() = node.elementType
}

class LuaBlock(node: ASTNode) : LuaElement(node)
class LuaTable(node: ASTNode) : LuaElement(node)
class LuaFuncBody(node: ASTNode) : LuaElement(node) {
    val params: List<LuaNameDecl> get() = node.findChildByType(E.PARAMS)?.getChildren(null)?.mapNotNull { it.psi as? LuaNameDecl }.orEmpty()
    val block: LuaBlock? get() = findChildByClass(LuaBlock::class.java)
}

/** A call: `f(x)`, `o:m{…}`, `require "a.b"`. */
class LuaCall(node: ASTNode) : LuaElement(node) {
    val callee: PsiElement? get() = firstChild
    val args: List<PsiElement> get() = node.findChildByType(E.ARGS)?.psi?.children?.filter { it is LuaElement }.orEmpty()
}

/** A string, number, `nil`, `true`, `false` or `...`; the argument of `require` refers to its module file. */
class LuaLiteral(node: ASTNode) : LuaElement(node) {
    val stringValue: String? get() {
        val t = text
        if (node.firstChildNode?.elementType != T.STRING) return null
        return when {
            t.startsWith("[") -> t.substringAfter('[').dropWhile { it == '=' }.removePrefix("[").let { s -> s.substring(0, s.lastIndexOf(']').let { i -> s.lastIndexOf(']', i - 1) }.coerceAtLeast(0)) }.removePrefix("\n")
            t.length >= 2 && t.last() == t.first() -> t.substring(1, t.length - 1)
            else -> t.drop(1)
        }
    }
    override fun getReferences(): Array<PsiReference> = LuaRequire.referenceOf(this)?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** Name tokens are renamed in place. */
internal fun renameLeaf(id: PsiElement?, name: String) {
    (id?.node as? LeafElement)?.replaceWithText(name) ?: throw IncorrectOperationException("No name")
}

/** A local, parameter, loop variable or label: visible in its scope only. */
class LuaNameDecl(node: ASTNode) : LuaElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = firstChild
    override fun getName(): String = text
    override fun setName(name: String): PsiElement { renameLeaf(firstChild, name); return this }
    val isParameter get() = parent?.node?.elementType == E.PARAMS
    val isLabel get() = parent?.node?.elementType == E.LABEL_STMT
    val isLocalFunction get() = parent?.node?.elementType == E.LOCAL_FUNCTION
    override fun getUseScope(): SearchScope {
        val container = when {
            isParameter -> parent?.parent
            isLabel -> LuaResolve.functionOf(this) ?: containingFile
            else -> parent?.let { if (it.node.elementType == E.FOR_NUM || it.node.elementType == E.FOR_IN) it else it.parent }
        }
        return container?.let { LocalSearchScope(it) } ?: super.getUseScope()
    }
}

/**
 * A name used: a local, else a global. Global definitions (`x = …`, `function x()`) are named elements, so that
 * Find Usages and rename start from them.
 */
class LuaNameRef(node: ASTNode) : LuaElement(node), PsiNameIdentifierOwner {
    override fun getName(): String = text
    override fun getNameIdentifier(): PsiElement? = if (isGlobalDefinition) firstChild else null
    override fun setName(name: String): PsiElement { renameLeaf(firstChild, name); return this }
    override fun getReference(): PsiReference = LuaNameReference(this)
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)
    val isDefinitionSite: Boolean get() = parent?.node?.elementType == E.TARGETS || (parent?.node?.elementType == E.FUNC_NAME && parent.firstChild == this && parent.children.size == 1)
    val isGlobalDefinition: Boolean get() = isDefinitionSite && LuaResolve.local(this, name) == null
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

/** `a.b` / `a:b`: a field read, or (assigned, `function a.b()`) a field definition. */
class LuaIndexExpr(node: ASTNode) : LuaElement(node), PsiNameIdentifierOwner {
    val base: PsiElement? get() = firstChild
    val field: PsiElement? get() = node.getChildren(null).lastOrNull { it.elementType == T.IDENT }?.psi?.takeIf { it != firstChild }
    val isMethod: Boolean get() = node.findChildByType(T.COLON) != null
    val fieldName: String? get() = this.field?.text
    val isDefinitionSite: Boolean get() = parent?.node?.elementType == E.TARGETS || (parent?.node?.elementType == E.FUNC_NAME && parent.lastChild == this)
    override fun getName(): String? = fieldName
    override fun getNameIdentifier(): PsiElement? = if (isDefinitionSite) field else null
    override fun getTextOffset(): Int = (if (isDefinitionSite) field?.textOffset else null) ?: super.getTextOffset()
    override fun setName(name: String): PsiElement { renameLeaf(field, name); return this }
    override fun getReference(): PsiReference? = if (field != null) LuaFieldReference(this) else null
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

/** `{ name = value }`: a field definition. */
class LuaTableField(node: ASTNode) : LuaElement(node), PsiNameIdentifierOwner {
    private val id: PsiElement? get() = firstChild?.takeIf { it.node.elementType == T.IDENT && node.findChildByType(T.ASSIGN) != null }
    val value: PsiElement? get() = if (id != null || node.firstChildNode?.elementType == T.LBRACKET) children.lastOrNull { it is LuaElement } else children.firstOrNull { it is LuaElement }
    override fun getName(): String? = id?.text
    override fun getNameIdentifier(): PsiElement? = id
    override fun setName(name: String): PsiElement { renameLeaf(id, name); return this }
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

// ---------------------------------------------------------------- references

abstract class LuaReference<T : PsiElement>(element: T, range: TextRange) : PsiPolyVariantReferenceBase<T>(element, range) {
    abstract fun targets(): List<PsiElement>
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, true, incompleteCode)
    override fun getVariants(): Array<Any> = emptyArray()
    private companion object {
        val RESOLVER = ResolveCache.PolyVariantResolver<LuaReference<*>> { ref, _ ->
            ref.targets().distinct().map { PsiElementResolveResult(it) }.toTypedArray()
        }
    }
}

class LuaNameReference(element: LuaNameRef) : LuaReference<LuaNameRef>(element, TextRange(0, element.textLength)) {
    /** A definition is no use of itself (Ctrl+Click on it lists its usages). */
    override fun targets() = LuaResolve.resolveName(element).filter { it != element }
    override fun handleElementRename(newElementName: String): PsiElement { renameLeaf(element.firstChild, newElementName); return element }
}

class LuaFieldReference(element: LuaIndexExpr) :
    LuaReference<LuaIndexExpr>(element, element.field!!.textRange.shiftLeft(element.textRange.startOffset)) {
    override fun targets() = LuaResolve.resolveField(element).filter { it != element }
    override fun handleElementRename(newElementName: String): PsiElement { renameLeaf(element.field, newElementName); return element }
}

/** `require("a.b")`, `dofile("x.lua")` → the file; a rename of the file changes the module name. */
class LuaRequireReference(element: LuaLiteral, private val module: String, private val dotted: Boolean) :
    LuaReference<LuaLiteral>(element, TextRange(1, (element.textLength - 1).coerceAtLeast(1))) {
    override fun targets(): List<PsiElement> = listOfNotNull(LuaRequire.target(element.containingFile, module, dotted))
    override fun handleElementRename(newElementName: String): PsiElement {
        val target = multiResolve(false).firstOrNull()?.element as? PsiFileSystemItem
        val base = newElementName.removeSuffix(".lua")
        val replaced = when {
            !dotted -> module.substringBeforeLast('/', "").let { if (it.isEmpty()) newElementName else "$it/$newElementName" }
            target?.name == "init.lua" -> module   // the folder names the module
            else -> module.substringBeforeLast('.', "").let { if (it.isEmpty()) base else "$it.$base" }
        }
        val quote = element.text.first()
        renameLeaf(element.firstChild, "$quote$replaced$quote")
        return element
    }
}
