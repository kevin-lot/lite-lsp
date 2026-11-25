package me.kevinlot.lite.scala

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.core.LiteResolveCache
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.core.localUseScope
import me.kevinlot.lite.core.renameLeaf
import me.kevinlot.lite.scala.ScalaElementTypes as E
import me.kevinlot.lite.scala.ScalaTokens as T

open class ScalaElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val type: IElementType get() = node.elementType
}

private val MEMBER_NAMES = TokenSet.create(T.IDENT, T.OP, T.kw("super"), T.kw("this"), T.kw("type"))
private val PATTERN_PARTS = TokenSet.create(E.INFIX, E.PATTERN)
private val MEMBER_OWNERS = TokenSet.create(E.PACKAGE, E.TEMPLATE_BODY)
private val FUNCTION_OWNERS = TokenSet.create(E.ENUM_CASE, E.EXTENSION, E.FUNCTION, E.GIVEN)

/** A name as written in the language: `` `type` `` is the name `type`. */
internal fun unquoted(name: String): String = name.removeSurrounding("`")

internal fun identifier(project: Project, name: String): PsiElement? =
    PsiFileFactory.getInstance(project).createFileFromText("name.scala", ScalaFileType, name)
        .let(PsiTreeUtil::getDeepestFirst).takeIf { it.node.elementType in T.NAMES && it.textLength == name.length }

internal fun PsiElement.hasToken(type: IElementType): Boolean = node.findChildByType(type) != null

/** Whether the element sits where members are declared: a template body, a package or the file. */
internal fun PsiElement?.declaresMembers(): Boolean = this is PsiFile || (this != null && node.elementType in MEMBER_OWNERS)

/** A declaration: its name is the first name token directly under it. */
abstract class ScalaNamed(node: ASTNode) : ScalaElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.NAMES)?.psi
    override fun getName(): String? = nameIdentifier?.text?.let(::unquoted)
    override fun setName(name: String): PsiElement { renameLeaf(nameIdentifier, name); return this }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    protected open val modifierList: PsiElement? get() = children.firstOrNull { it.isA(E.MODIFIERS) }
    open val isPrivate: Boolean get() = modifierList?.hasToken(T.kw("private")) == true

    /** The class, trait or object whose body declares this. */
    val containingClass: ScalaClass? get() = PsiTreeUtil.getParentOfType(this, ScalaClass::class.java)

    /** The statement list this is declared in. */
    protected open val container: PsiElement? get() = parent

    /** Private members are searched in their file (a companion sees them), locals in their block. */
    override fun getUseScope(): SearchScope {
        if (!container.declaresMembers()) return localUseScope(container) ?: super.getUseScope()
        return localUseScope(containingFile.takeIf { isPrivate }) ?: super.getUseScope()
    }
}

/** `class`, `case class`, `trait`, `object`, `enum` or `package object`. */
class ScalaClass(node: ASTNode) : ScalaNamed(node) {
    val isObject: Boolean get() = hasToken(T.kw("object"))
    val isTrait: Boolean get() = hasToken(T.kw("trait"))
    val isEnum: Boolean get() = !isObject && !isTrait && !hasToken(T.kw("class"))
    val isPackageObject: Boolean get() = hasToken(T.kw("package"))
    val isCase: Boolean get() = modifierList?.hasToken(T.kw("case")) == true

    val body: PsiElement? get() = children.firstOrNull { it.isA(E.TEMPLATE_BODY) }
    val params: List<ScalaVariable> get() = paramsOf(this)
    val superTypes: List<ScalaType> get() = children.firstOrNull { it.isA(E.EXTENDS) }?.children?.filterIsInstance<ScalaType>().orEmpty()

    /** What the body declares, with the constructor parameters. */
    val members: List<ScalaNamed> get() = body?.let(ScalaScopes::declarations).orEmpty() + params

    /** The class and the object of the same name declared side by side. */
    val companion: ScalaClass?
        get() = parent?.children?.firstOrNull { it is ScalaClass && it != this && it.name == name && it.isObject != isObject } as? ScalaClass

    val qualifiedName: String? get() = name?.let { listOf(ScalaFiles.ownerName(this), it).filter(String::isNotEmpty).joinToString(".") }
}

class ScalaEnumCase(node: ASTNode) : ScalaNamed(node) {
    val params: List<ScalaVariable> get() = paramsOf(this)
}

/** `def`: a method, a local function or a secondary constructor (which has no name). */
class ScalaFunction(node: ASTNode) : ScalaNamed(node) {
    val paramClauses: List<PsiElement> get() = children.filter { it.isA(E.PARAMS) }
    val params: List<ScalaVariable> get() = paramsOf(this)

    /** The written result type, whatever its shape. */
    val resultType: PsiElement? get() = children.firstOrNull { it is ScalaType || it.isA(E.COMPOUND_TYPE) }
    val body: PsiElement? get() = valueAfterAssign(this)

    /** A method of an `extension` belongs where the extension is declared. */
    override val container: PsiElement? get() = generateSequence(parent) { it.parent }.firstOrNull { !it.isA(E.EXTENSION) && !it.parent.isA(E.EXTENSION) }
}

class ScalaTypeAlias(node: ASTNode) : ScalaNamed(node) {
    val aliased: ScalaType? get() = children.firstNotNullOfOrNull { it as? ScalaType }
}

class ScalaTypeParam(node: ASTNode) : ScalaNamed(node) {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.IDENT)?.psi
    override fun getUseScope(): SearchScope = localUseScope(parent?.parent) ?: super.getUseScope()
}

/** `given name: Type …`; an anonymous given has no name. */
class ScalaGiven(node: ASTNode) : ScalaNamed(node) {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.IDENT)?.psi
    val givenType: ScalaType? get() = children.firstNotNullOfOrNull { it as? ScalaType }
}

/** A `val` / `var`, a parameter, or a name bound by a pattern, a generator or a self type. */
class ScalaVariable(node: ASTNode) : ScalaNamed(node) {
    val isParam: Boolean get() = type == E.PARAM

    /** What binds the name: a VAL_DECL, CASE_CLAUSE, GENERATOR or SELF_TYPE; for a parameter its function, class or lambda. */
    val binder: PsiElement?
        get() = when {
            isParam -> parent?.parent
            else -> generateSequence(parent) { it.parent }.firstOrNull { it.node.elementType !in PATTERN_PARTS }
        }

    private val declaration: PsiElement? get() = binder?.takeIf { it.isA(E.VAL_DECL) }
    val isSelf: Boolean get() = binder.isA(E.SELF_TYPE)
    val isClassParam: Boolean get() = isParam && binder is ScalaClass
    val isMember: Boolean get() = isClassParam || declaration?.parent.declaresMembers()

    /** A class parameter that is also a member seen from outside: `val` / `var`, or any parameter of a case class. */
    private val isPublicParam: Boolean
        get() = isClassParam && !isPrivate && (hasToken(T.kw("val")) || hasToken(T.kw("var")) || (binder as? ScalaClass)?.isCase == true)

    override val modifierList: PsiElement? get() = declaration?.children?.firstOrNull { it.isA(E.MODIFIERS) }
    override val isPrivate: Boolean get() = hasToken(T.kw("private")) || super.isPrivate

    /** The written type, whatever its shape: its own (`x: T`) or the one of its `val`. */
    val declaredType: PsiElement?
        get() = ownType(this) ?: declaration?.takeIf { parent == it }?.let(::ownType)

    private fun ownType(owner: PsiElement): PsiElement? = owner.children.firstOrNull { it is ScalaType || it.isA(E.COMPOUND_TYPE) }

    /** The value after `=`, of a variable declared alone. */
    val initializer: PsiElement?
        get() = declaration?.takeIf { parent == it && it.children.count { c -> c is ScalaVariable || c.isA(E.PATTERN) } == 1 }?.let(::valueAfterAssign)

    /** Named arguments reach the parameters of a public function from anywhere. */
    override fun getUseScope(): SearchScope {
        val owner = binder
        return when {
            isPublicParam -> super.getUseScope()
            isClassParam -> localUseScope(containingFile) ?: super.getUseScope()
            isParam && owner is ScalaNamed && owner.node.elementType in FUNCTION_OWNERS -> owner.useScope
            isParam -> localUseScope(owner) ?: super.getUseScope()
            declaration == null -> localUseScope(owner?.parent.takeIf { owner.isA(E.GENERATOR) || isSelf } ?: owner) ?: super.getUseScope()
            else -> super.getUseScope()
        }
    }

    override val container: PsiElement? get() = declaration?.parent
}

private fun paramsOf(owner: PsiElement): List<ScalaVariable> =
    owner.children.filter { it.isA(E.PARAMS) }.flatMap { it.children.filterIsInstance<ScalaVariable>() }

private fun valueAfterAssign(owner: PsiElement): PsiElement? =
    owner.node.findChildByType(T.ASSIGN)?.psi?.let { PsiTreeUtil.getNextSiblingOfType(it, ScalaElement::class.java) }

/** The expression a call applies, under the type arguments: the `f` of `f[T](x)`. */
internal fun applied(e: PsiElement?): PsiElement? = generateSequence(e) { it.firstChild.takeIf { _ -> it.isA(E.TYPE_APPLY) } }.lastOrNull()

/** `f(x)`, `f { … }`, `f(x)(y)`: each argument list is a call of what precedes it. */
class ScalaCall(node: ASTNode) : ScalaElement(node) {
    val callee: PsiElement? get() = firstChild
    private val args: PsiElement? get() = children.firstOrNull { it.isA(E.ARGS) }
    val argumentCount: Int get() = args?.children?.size ?: 1
}

/** Number of arguments the name is called with, when it is called. */
internal fun callArity(name: PsiElement): Int? {
    val callee = generateSequence(name) { it.parent.takeIf { p -> p.isA(E.TYPE_APPLY) } }.last()
    return (callee.parent as? ScalaCall)?.takeIf { it.callee == callee }?.argumentCount
}

/** A name used: a variable, a member of an enclosing template, a class or an object; also `this` and `super`. */
class ScalaRef(node: ASTNode) : ScalaElement(node) {
    override fun getName(): String = unquoted(text)

    // kept by its element: the platform caches what a reference resolves to by reference instance
    private val cachedReference = ScalaNameReference(this)
    override fun getReference(): PsiReference = cachedReference
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)

    val isThis: Boolean get() = node.firstChildNode?.elementType == T.kw("this")
    val isSuper: Boolean get() = node.firstChildNode?.elementType == T.kw("super")
    val isCallee: Boolean get() = callArity(this) != null

    /** `case Name(…)`: the name of an extractor. */
    val isExtractor: Boolean get() = parent?.node?.elementType in PATTERN_PARTS && PsiTreeUtil.skipWhitespacesAndCommentsForward(this).isA(T.LPAREN)
}

/** `a.b`: also each step of an import path. */
class ScalaMember(node: ASTNode) : ScalaElement(node) {
    val qualifier: PsiElement? get() = firstChild
    val nameElement: PsiElement? get() = node.lastChildNode?.takeIf { it.elementType in MEMBER_NAMES }?.psi
    override fun getName(): String? = nameElement?.text?.let(::unquoted)
    val isThis: Boolean get() = nameElement.isA(T.kw("this"))
    val isCallee: Boolean get() = callArity(this) != null
    private val cachedReference = ScalaMemberReference(this)
    override fun getReference(): PsiReference? = cachedReference.takeIf { nameElement != null }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** The operator of `a op b` or `a op`: a method of `a`, or of `b` when its name ends with a colon. */
class ScalaOpRef(node: ASTNode) : ScalaElement(node) {
    override fun getName(): String = unquoted(text)
    val isSymbolic: Boolean get() = node.firstChildNode?.elementType == T.OP
    val isRightAssociative: Boolean get() = text.endsWith(':')
    val left: PsiElement? get() = parent?.firstChild?.takeIf { it != this }
    val right: PsiElement? get() = parent?.lastChild?.takeIf { it != this }
    private val cachedReference = ScalaOperatorReference(this)
    override fun getReference(): PsiReference = cachedReference
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)
}

/** A named type: `Foo`, `a.b.Foo`, `List[Foo]`, `x.type`; the last name refers to its class. */
class ScalaType(node: ASTNode) : ScalaElement(node) {
    val names: List<PsiElement> get() = node.getChildren(TokenSet.create(T.IDENT)).map { it.psi }
    val nameElement: PsiElement? get() = names.lastOrNull()
    val path: List<String> get() = names.map { unquoted(it.text) }
    val isSingleton: Boolean get() = hasToken(T.kw("type"))
    private val cachedReference = ScalaTypeReference(this)
    override fun getReference(): PsiReference? = cachedReference.takeIf { nameElement != null }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** `name = value` in an argument list: the name refers to a parameter of what is called. */
class ScalaNamedArg(node: ASTNode) : ScalaElement(node) {
    val nameElement: PsiElement? get() = firstChild?.takeIf { it.isA(T.IDENT) }
    override fun getName(): String? = nameElement?.text?.let(::unquoted)
    private val cachedReference = ScalaNamedArgReference(this)
    override fun getReference(): PsiReference? = cachedReference.takeIf { nameElement != null }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** One import: a path, then nothing (its last name is imported), a wildcard or selectors. */
class ScalaImportExpr(node: ASTNode) : ScalaElement(node) {
    private val path: PsiElement? get() = firstChild?.takeIf { it is ScalaRef || it is ScalaMember }
    val selectors: List<ScalaSelector> get() = children.firstOrNull { it.isA(E.SELECTORS) }?.children?.filterIsInstance<ScalaSelector>().orEmpty()
    private val hasSelectors: Boolean get() = children.any { it.isA(E.SELECTORS) }
    val isWildcard: Boolean get() = !hasSelectors && (hasToken(T.UNDERSCORE) || node.findChildByType(T.OP)?.text == "*") && !hasToken(T.SOFT_KEYWORD)
    private val isSingle: Boolean get() = !hasSelectors && !isWildcard && !hasToken(T.OP) && node.lastChildNode?.elementType != T.SOFT_KEYWORD

    /** The path whose members are imported. */
    val prefix: PsiElement? get() = when { isSingle -> (path as? ScalaMember)?.qualifier; else -> path }

    /** The single name the path ends with, when there is neither wildcard nor selectors. */
    val single: ScalaMember? get() = (path as? ScalaMember)?.takeIf { isSingle }
    val alias: String? get() = node.findChildByType(T.SOFT_KEYWORD)?.let { node.findChildByType(T.IDENT, it) }?.text?.let(::unquoted)
}

/** `C`, `C => D`, `C as D`, `C => _` (hidden), `_` / `*` (the rest) between the braces of an import. */
class ScalaSelector(node: ASTNode) : ScalaElement(node) {
    val nameElement: PsiElement? get() = firstChild?.takeIf { it.isA(T.IDENT) }
    override fun getName(): String? = nameElement?.text?.let(::unquoted)
    val isWildcard: Boolean get() = nameElement == null && firstChild?.node?.elementType != T.SOFT_KEYWORD
    private val renamed: PsiElement? get() = lastChild?.takeIf { it != firstChild }
    val isHidden: Boolean get() = renamed.isA(T.UNDERSCORE)

    /** The name the selector brings into scope. */
    val importedName: String? get() = when { isHidden -> null; else -> renamed?.takeIf { it.isA(T.IDENT) }?.text?.let(::unquoted) ?: name }
    private val cachedReference = ScalaSelectorReference(this)
    override fun getReference(): PsiReference? = cachedReference.takeIf { nameElement != null }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

// ---------------------------------------------------------------- references

abstract class ScalaReference<P : PsiElement>(element: P, private val nameElement: () -> PsiElement?) : LiteReference<P>(element, nameElement) {
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? = identifier(nameToken.project, newName)

    /** Scala names depend on each other in cycles (a parent found through an import found through that parent). */
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = LiteResolveCache.resolve(element.project, this) {
        resolveTargets().distinct().map<PsiElement, ResolveResult>(::PsiElementResolveResult).toTypedArray()
    }

    /** `Foo(x)` runs `Foo.apply`: the text names the object and its companion class, not the method. */
    override fun isReferenceTo(element: PsiElement): Boolean {
        val written = nameElement()?.text?.let(::unquoted)
        val implicit = multiResolve(false).mapNotNull { it.element as? ScalaFunction }.filter { it.name != written }
        if (implicit.isEmpty()) return super.isReferenceTo(element)
        return implicit.mapNotNull { it.containingClass }.any { it == element || it.companion == element }
    }
}

class ScalaNameReference(element: ScalaRef) : ScalaReference<ScalaRef>(element, { element.firstChild }) {
    override fun resolveTargets(): List<PsiElement> = ScalaResolve.resolveName(element)
}

class ScalaMemberReference(element: ScalaMember) : ScalaReference<ScalaMember>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = ScalaResolve.resolveMember(element)
}

class ScalaOperatorReference(element: ScalaOpRef) : ScalaReference<ScalaOpRef>(element, { element.firstChild }) {
    override fun resolveTargets(): List<PsiElement> = ScalaResolve.resolveOperator(element)
}

class ScalaTypeReference(element: ScalaType) : ScalaReference<ScalaType>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = ScalaResolve.resolveType(element)
}

class ScalaNamedArgReference(element: ScalaNamedArg) : ScalaReference<ScalaNamedArg>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = ScalaResolve.resolveNamedArg(element)
}

class ScalaSelectorReference(element: ScalaSelector) : ScalaReference<ScalaSelector>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = ScalaResolve.resolveSelector(element)
}
