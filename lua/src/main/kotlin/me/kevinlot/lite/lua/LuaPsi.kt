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
            t.startsWith("[") -> t.substringAfter('[').dropWhile { it == '=' }.removePrefix("[")
                .let { s -> s.substring(0, s.lastIndexOf(']').let { i -> s.lastIndexOf(']', i - 1) }.coerceAtLeast(0)) }.removePrefix("\n")
            t.length >= 2 && t.last() == t.first() -> t.substring(1, t.length - 1)
            else -> t.drop(1)
        }
    }
    /** Length of the opening delimiter: 1 for a quote, `level + 2` for `[==[`. */
    val quoteLength: Int
        get() {
            if (!text.startsWith("[")) return 1
            return text.indexOf('[', 1) + 1
        }
    /** What lies between the delimiters. */
    val contentRange: TextRange get() = TextRange(quoteLength, (textLength - quoteLength).coerceAtLeast(quoteLength))
    /** The value when it reads as a name: `t["name"]` is `t.name`. */
    val identifier: String? get() = stringValue?.takeIf(IDENTIFIER::matches)

    fun setStringValue(value: String) {
        val open = text.take(quoteLength)
        renameLeaf(firstChild, open + value + open.replace('[', ']'))
    }
    override fun getReferences(): Array<PsiReference> = LuaRequire.referenceOf(this)?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    private companion object { val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*") }
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
            parent?.node?.elementType == E.FOR_NUM || parent?.node?.elementType == E.FOR_IN -> parent
            else -> parent?.parent
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
    override fun getNameIdentifier(): PsiElement? = firstChild?.takeIf { isGlobalDefinition }
    override fun setName(name: String): PsiElement { renameLeaf(firstChild, name); return this }
    override fun getReference(): PsiReference = LuaNameReference(this)
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)
    val isDefinitionSite: Boolean get() = parent?.node?.elementType == E.TARGETS ||
        (parent?.node?.elementType == E.FUNC_NAME && parent.firstChild == this && parent.children.size == 1)
    val isGlobalDefinition: Boolean get() = isDefinitionSite && LuaResolve.local(this, name) == null
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

/** A field of a table by name, `a.b` or `a["b"]`: a read, or (assigned, `function a.b()`) a definition. */
sealed interface LuaFieldAccess : PsiNameIdentifierOwner {
    val base: PsiElement?
    val fieldName: String?
    val isDefinitionSite: Boolean
}

/** `_G.name` / `_ENV.name`: the global `name`. */
val LuaFieldAccess.isGlobalAccess: Boolean
    get() = (base as? LuaNameRef)?.let { it.name in GLOBAL_TABLES && LuaResolve.local(it, it.name) == null } == true

val LuaFieldAccess.isGlobalDefinition: Boolean get() = isDefinitionSite && fieldName != null && isGlobalAccess

private val GLOBAL_TABLES = setOf("_G", "_ENV")

/** `a.b` / `a:b`. */
class LuaIndexExpr(node: ASTNode) : LuaElement(node), LuaFieldAccess {
    override val base: PsiElement? get() = firstChild
    val field: PsiElement? get() = node.getChildren(null).lastOrNull { it.elementType == T.IDENT }?.psi?.takeIf { it != firstChild }
    val isMethod: Boolean get() = node.findChildByType(T.COLON) != null
    override val fieldName: String? get() = this.field?.text
    override val isDefinitionSite: Boolean get() = parent?.node?.elementType == E.TARGETS ||
        (parent?.node?.elementType == E.FUNC_NAME && parent.lastChild == this)
    override fun getName(): String? = fieldName
    override fun getNameIdentifier(): PsiElement? = field?.takeIf { isDefinitionSite }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement { renameLeaf(field, name); return this }
    override fun getReference(): PsiReference? = field?.let { LuaFieldReference(this, it.textRangeInParent) }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

/** `a[b]`; with a string key that reads as a name, `a["b"]`, it is the field `a.b`. */
class LuaBracketIndex(node: ASTNode) : LuaElement(node), LuaFieldAccess {
    override val base: PsiElement? get() = firstChild
    private val key: LuaLiteral? get() = (children.getOrNull(1) as? LuaLiteral)?.takeIf { it.identifier != null }
    override val fieldName: String? get() = key?.identifier
    override val isDefinitionSite: Boolean get() = parent?.node?.elementType == E.TARGETS
    override fun getName(): String? = fieldName
    override fun getNameIdentifier(): PsiElement? = key?.takeIf { isDefinitionSite }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement { key?.setStringValue(name) ?: throw IncorrectOperationException("No name"); return this }
    override fun getReference(): PsiReference? = key?.let { LuaFieldReference(this, it.contentRange.shiftRight(it.startOffsetInParent)) }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

/** `{ name = value }` / `{ ["name"] = value }`: a field definition. */
class LuaTableField(node: ASTNode) : LuaElement(node), PsiNameIdentifierOwner {
    private val id: PsiElement? get() = firstChild?.takeIf { it.node.elementType == T.IDENT && node.findChildByType(T.ASSIGN) != null }
    private val stringKey: LuaLiteral?
        get() = (children.firstOrNull() as? LuaLiteral)?.takeIf { node.firstChildNode?.elementType == T.LBRACKET && it.identifier != null }
    val value: PsiElement? get() {
        if (id != null || node.firstChildNode?.elementType == T.LBRACKET) return children.lastOrNull { it is LuaElement }
        return children.firstOrNull { it is LuaElement }
    }
    override fun getName(): String? = id?.text ?: stringKey?.identifier
    override fun getNameIdentifier(): PsiElement? = id ?: stringKey
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement {
        stringKey?.setStringValue(name) ?: renameLeaf(id, name)
        return this
    }
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

class LuaFieldReference(element: LuaFieldAccess, range: TextRange) : LuaReference<LuaFieldAccess>(element, range) {
    override fun targets() = LuaResolve.resolveField(element).filter { it != element }
    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}

/** `require("a.b")`, `dofile("x.lua")` → the file; a rename of the file changes the module name. */
class LuaRequireReference(element: LuaLiteral, private val module: String, private val dotted: Boolean) :
    LuaReference<LuaLiteral>(element, element.contentRange) {
    override fun targets(): List<PsiElement> = listOfNotNull(LuaRequire.target(element.containingFile, module, dotted))
    override fun handleElementRename(newElementName: String): PsiElement {
        val target = multiResolve(false).firstOrNull()?.element as? PsiFileSystemItem
        val base = newElementName.removeSuffix(".lua")
        val replaced = when {
            !dotted -> sibling('/', newElementName)
            target?.name == "init.lua" -> module   // the folder names the module
            else -> sibling('.', base)
        }
        element.setStringValue(replaced)
        return element
    }

    private fun sibling(separator: Char, name: String): String {
        val parent = module.substringBeforeLast(separator, "")
        if (parent.isEmpty()) return name
        return "$parent$separator$name"
    }
}
