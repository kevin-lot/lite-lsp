package me.kevinlot.lite.kotlin.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.kotlin.KtFile
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N
import me.kevinlot.lite.kotlin.resolve.KtSimpleNameReference

/**
 * Hand-written, minimal PSI over the compiler parser's tree: only what highlighting, navigation,
 * rename and imports need. Every other node is a plain [KtElement].
 */
open class KtElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    fun child(type: IElementType): PsiElement? = node.findChildByType(type)?.psi
    fun childrenOf(type: IElementType): List<PsiElement> = node.getChildren(TokenSet.create(type)).map { it.psi }
    inline fun <reified T : PsiElement> childOf(): T? = children.firstOrNull { it is T } as T?
    inline fun <reified T : PsiElement> childrenOf(): List<T> = children.filterIsInstance<T>()
}

/** Declaration with a name: Ctrl+Click target, rename subject. */
abstract class KtNamedDeclaration(node: ASTNode) : KtElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = child(KtTokens.IDENTIFIER)
    override fun getName(): String? = nameIdentifier?.text?.removeSurrounding("`")
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        val id = nameIdentifier ?: throw IncorrectOperationException("No name")
        id.replace(KtPsiFactory(project).createIdentifier(name))
        return this
    }

    val modifierList: PsiElement? get() = child(N.MODIFIER_LIST)
    fun hasModifier(token: IElementType): Boolean = modifierList?.node?.findChildByType(token) != null

    val typeParameters: List<KtTypeParameter>
        get() = (child(N.TYPE_PARAMETER_LIST) as? KtElement)?.childrenOf<KtTypeParameter>().orEmpty()

    /**
     * Locals, parameters, type parameters and `private` declarations are only visible in their
     * container: Find Usages / rename search there (intersected with the scope chosen by the user).
     */
    override fun getUseScope(): com.intellij.psi.search.SearchScope =
        me.kevinlot.lite.core.localUseScope(visibilityContainer()) ?: super.getUseScope()

    private fun visibilityContainer(): PsiElement? {
        val p = parent ?: return null
        return when {
            this is KtTypeParameter -> p.parent
            this is KtParameter && !(isProperty && p.parent?.node?.elementType == N.PRIMARY_CONSTRUCTOR) -> p.parent // fun, lambda, catch, for
            this is KtDestructuringEntry -> p.parent
            p.node.elementType == N.BLOCK -> p // local val/fun/class
            p.node.elementType == N.FOR -> p
            hasModifier(KtTokens.PRIVATE_KEYWORD) -> if (p is KtFile) p else p.parent // private top-level: file; member: class
            else -> null
        }
    }
}

/** Declarations that may sit in a class body or at top level. */
interface KtMemberLike : PsiElement

class KtClass(node: ASTNode) : KtNamedDeclaration(node), KtMemberLike {
    val isInterface: Boolean get() = node.findChildByType(KtTokens.INTERFACE_KEYWORD) != null
    val isEnum: Boolean get() = hasModifier(KtTokens.ENUM_KEYWORD)
    val body: KtElement? get() = child(N.CLASS_BODY) as? KtElement
    val primaryConstructorParameters: List<KtParameter>
        get() = ((child(N.PRIMARY_CONSTRUCTOR) as? KtElement)?.child(N.VALUE_PARAMETER_LIST) as? KtElement)?.childrenOf<KtParameter>().orEmpty()
    val superTypeReferences: List<KtElement> get() = superTypes(this)
}

class KtObjectDeclaration(node: ASTNode) : KtNamedDeclaration(node), KtMemberLike {
    val isCompanion: Boolean get() = hasModifier(KtTokens.COMPANION_KEYWORD)
    val body: KtElement? get() = child(N.CLASS_BODY) as? KtElement
    val superTypeReferences: List<KtElement> get() = superTypes(this)
    /** `companion object` without a name is called `Companion`. */
    override fun getName(): String? = super.getName() ?: if (isCompanion) "Companion" else null
}

/** `object : Runnable { … }` expression body. */
private fun superTypes(e: KtElement): List<KtElement> =
    (e.child(N.SUPER_TYPE_LIST) as? KtElement)?.children.orEmpty().mapNotNull { entry ->
        (entry as? KtElement)?.child(N.TYPE_REFERENCE) as? KtElement
            ?: ((entry as? KtElement)?.child(N.CONSTRUCTOR_CALLEE) as? KtElement)?.child(N.TYPE_REFERENCE) as? KtElement
    }

class KtFunction(node: ASTNode) : KtNamedDeclaration(node), KtMemberLike {
    val valueParameters: List<KtParameter>
        get() = (child(N.VALUE_PARAMETER_LIST) as? KtElement)?.childrenOf<KtParameter>().orEmpty()
    /** `fun Foo.bar()`: the type reference before the name. */
    val receiverTypeReference: KtElement? get() = typeRefBeforeName(this)
    /** `fun bar(): Foo`: the type reference after the parameter list. */
    val returnTypeReference: KtElement? get() = typeRefAfter(this, N.VALUE_PARAMETER_LIST)
    val bodyExpression: PsiElement?
        get() = child(N.BLOCK) ?: children.lastOrNull { it.node.elementType != N.TYPE_REFERENCE && it is KtElement && it.prevSiblingIs(KtTokens.EQ) }
    val isExtension: Boolean get() = receiverTypeReference != null
}

class KtProperty(node: ASTNode) : KtNamedDeclaration(node), KtMemberLike {
    val receiverTypeReference: KtElement? get() = typeRefBeforeName(this)
    val typeReference: KtElement? get() = typeRefAfter(this, KtTokens.IDENTIFIER)
    val initializer: PsiElement? get() = children.firstOrNull { it is KtElement && it.prevSiblingIs(KtTokens.EQ) }
    val delegateExpression: PsiElement? get() = (child(N.PROPERTY_DELEGATE) as? KtElement)?.children?.lastOrNull()
    val isLocal: Boolean get() = parent !is KtFile && parent?.node?.elementType != N.CLASS_BODY
}

class KtParameter(node: ASTNode) : KtNamedDeclaration(node) {
    val typeReference: KtElement? get() = child(N.TYPE_REFERENCE) as? KtElement
    /** `class A(val x: Int)` */
    val isProperty: Boolean get() = node.findChildByType(KtTokens.VAL_VAR) != null
}

class KtTypeParameter(node: ASTNode) : KtNamedDeclaration(node) {
    val boundTypeReference: KtElement? get() = child(N.TYPE_REFERENCE) as? KtElement
}

class KtTypeAlias(node: ASTNode) : KtNamedDeclaration(node), KtMemberLike {
    val typeReference: KtElement? get() = child(N.TYPE_REFERENCE) as? KtElement
}

class KtEnumEntry(node: ASTNode) : KtNamedDeclaration(node), KtMemberLike

/** `val (a, b) = pair` */
class KtDestructuringEntry(node: ASTNode) : KtNamedDeclaration(node) {
    val typeReference: KtElement? get() = child(N.TYPE_REFERENCE) as? KtElement
}

/** A simple name in an expression, a type, an import or a package directive. */
class KtNameReferenceExpression(node: ASTNode) : KtElement(node) {
    val identifier: PsiElement?
        get() = node.findChildByType(NAME_TOKENS)?.psi
    val referencedName: String? get() = identifier?.text?.removeSurrounding("`")
    override fun getReference(): PsiReference? = identifier?.let { KtSimpleNameReference(this) }

    private companion object {
        val NAME_TOKENS = TokenSet.create(KtTokens.IDENTIFIER, KtTokens.FIELD_IDENTIFIER)
    }
}

class KtImportDirective(node: ASTNode) : KtElement(node) {
    /** `a.b.C` (without `.*` and alias). */
    val importedPath: String?
        get() = children.firstOrNull { it.node.elementType == N.DOT_QUALIFIED_EXPRESSION || it is KtNameReferenceExpression }
            ?.text?.let(::compactPath)
    val isAllUnder: Boolean get() = node.findChildByType(KtTokens.MUL) != null
    val aliasName: String? get() = (child(N.IMPORT_ALIAS) as? KtElement)?.child(KtTokens.IDENTIFIER)?.text
    /** Name the import binds in the file. */
    val boundName: String? get() = aliasName ?: importedPath?.substringAfterLast('.')
}

class KtPackageDirective(node: ASTNode) : KtElement(node) {
    val packageName: String
        get() = children.firstOrNull { it.node.elementType == N.DOT_QUALIFIED_EXPRESSION || it is KtNameReferenceExpression }
            ?.text?.let(::compactPath).orEmpty()
}

private val SPACES_AND_BACKTICKS = Regex("[\\s`]")

/** `a . b.`c`` -> `a.b.c` */
internal fun compactPath(text: String): String = text.replace(SPACES_AND_BACKTICKS, "")

private fun PsiElement.prevSiblingIs(token: IElementType): Boolean {
    var p = prevSibling
    while (p != null && (p.node.elementType in KtTokens.WHITESPACES || p.node.elementType in KtTokens.COMMENTS)) p = p.prevSibling
    return p?.node?.elementType == token
}

private fun typeRefBeforeName(decl: KtElement): KtElement? {
    var n = decl.node.firstChildNode
    while (n != null && n.elementType != KtTokens.IDENTIFIER) {
        if (n.elementType == N.TYPE_REFERENCE) return n.psi as? KtElement
        n = n.treeNext
    }
    return null
}

private fun typeRefAfter(decl: KtElement, anchor: IElementType): KtElement? {
    var n = decl.node.findChildByType(anchor)?.treeNext
    while (n != null) {
        when (n.elementType) {
            N.TYPE_REFERENCE -> return n.psi as? KtElement
            KtTokens.EQ, N.BLOCK, N.PROPERTY_ACCESSOR, KtTokens.BY_KEYWORD -> return null
        }
        n = n.treeNext
    }
    return null
}

/** Element type -> PSI class. */
object KtPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        N.CLASS -> KtClass(node)
        N.OBJECT_DECLARATION -> KtObjectDeclaration(node)
        N.FUN -> KtFunction(node)
        N.PROPERTY -> KtProperty(node)
        N.VALUE_PARAMETER -> KtParameter(node)
        N.TYPE_PARAMETER -> KtTypeParameter(node)
        N.TYPEALIAS -> KtTypeAlias(node)
        N.ENUM_ENTRY -> KtEnumEntry(node)
        N.DESTRUCTURING_DECLARATION_ENTRY -> KtDestructuringEntry(node)
        N.REFERENCE_EXPRESSION, N.ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION -> KtNameReferenceExpression(node)
        N.IMPORT_DIRECTIVE -> KtImportDirective(node)
        N.PACKAGE_DIRECTIVE -> KtPackageDirective(node)
        else -> KtElement(node)
    }
}
