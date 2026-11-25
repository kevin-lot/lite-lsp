package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.Processor
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.kotlin.psi.KtClass
import me.kevinlot.lite.kotlin.psi.KtElement
import me.kevinlot.lite.kotlin.psi.KtFunction
import me.kevinlot.lite.kotlin.psi.KtNamedDeclaration
import me.kevinlot.lite.kotlin.psi.KtObjectDeclaration
import me.kevinlot.lite.kotlin.psi.KtParameter
import me.kevinlot.lite.kotlin.psi.KtProperty
import me.kevinlot.lite.kotlin.psi.KtPsiFactory
import me.kevinlot.lite.kotlin.types.KtTy
import me.kevinlot.lite.kotlin.types.KtTypes
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N

/** Supertypes and overrides, both ways: an `override` to what it overrides, a type or member to its implementations. */
object KtInheritance {
    /** Class or object whose body (or primary constructor) declares [member]. */
    fun ownerOf(member: PsiElement): PsiElement? {
        if (member is PsiMember) return member.containingClass
        val parent = member.parent ?: return null
        if (parent.node.elementType == N.CLASS_BODY) return parent.parent.takeIf { it is KtClass || it is KtObjectDeclaration }
        if (member is KtParameter && member.isProperty) return parent.parent?.parent as? KtClass
        return null
    }

    /** Nearest members of the supertypes that the `override` [member] overrides. */
    fun overridden(member: KtNamedDeclaration): List<PsiElement> {
        if (!member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return emptyList()
        return inherited(ownerOf(member), member, HashSet()).distinct()
    }

    private fun inherited(owner: PsiElement?, member: KtNamedDeclaration, seen: MutableSet<PsiElement>): List<PsiElement> =
        superTypeReferences(owner).mapNotNull { (KtTypes.typeOf(it) as? KtTy.Cls)?.decl }.filter(seen::add)
            .flatMap { type -> declared(type, member).ifEmpty { inherited(type, member, seen) } }

    private fun declared(type: PsiElement, member: KtNamedDeclaration): List<PsiElement> {
        val name = member.name ?: return emptyList()
        val candidates = when (type) {
            is KtNamedDeclaration -> members(type).filter { it.name == name }
            is PsiClass -> KtJava.members(type, name, member is KtFunction)
            else -> emptyList()
        }
        return candidates.filter { sameKind(member, it) && arity(it) == arity(member) }
    }

    /** The declaration at the top of the override chain of [member], or [member] itself. */
    fun root(member: KtNamedDeclaration): PsiElement {
        val seen = HashSet<PsiElement>()
        return generateSequence<PsiElement>(member) { current ->
            (current as? KtNamedDeclaration)?.let(::overridden)?.firstOrNull { it.isWritable && seen.add(it) }
        }.last()
    }

    /** Kotlin classes and objects that extend [type], directly or not. */
    fun inheritors(type: PsiElement, scope: SearchScope): List<KtNamedDeclaration> {
        val found = LinkedHashSet<KtNamedDeclaration>()
        val queue = ArrayDeque(listOf(type))
        while (queue.isNotEmpty()) {
            val direct = ReferencesSearch.search(queue.removeFirst(), scope).findAll().mapNotNull { inheritorAt(it.element) }
            queue += direct.filter(found::add)
        }
        return found.toList()
    }

    /** `override` members of the [inheritors] of the owner of [member]. */
    fun overriders(member: PsiElement, scope: SearchScope): List<KtNamedDeclaration> {
        val name = (member as? PsiNamedElement)?.name ?: return emptyList()
        val owner = ownerOf(member) ?: return emptyList()
        val names = listOf(name) + accessorProperty(member)
        return inheritors(owner, scope).flatMap(::members).filter {
            it.name in names && it.hasModifier(KtTokens.OVERRIDE_KEYWORD) && sameKind(it, member) && arity(it) == arity(member)
        }
    }

    private fun members(owner: KtNamedDeclaration): List<KtNamedDeclaration> = when (owner) {
        is KtClass -> owner.body?.childrenOf<KtNamedDeclaration>().orEmpty() + owner.primaryConstructorParameters.filter { it.isProperty }
        is KtObjectDeclaration -> owner.body?.childrenOf<KtNamedDeclaration>().orEmpty()
        else -> emptyList()
    }

    private fun superTypeReferences(owner: PsiElement?): List<KtElement> = when (owner) {
        is KtClass -> owner.superTypeReferences
        is KtObjectDeclaration -> owner.superTypeReferences
        else -> emptyList()
    }

    /** The class whose supertype list names [ref] as a supertype (not as a type argument of one). */
    private fun inheritorAt(ref: PsiElement): KtNamedDeclaration? {
        val list = PsiTreeUtil.findFirstParent(ref) { it.node?.elementType == N.SUPER_TYPE_LIST } ?: return null
        val owner = list.parent as? KtNamedDeclaration ?: return null
        return owner.takeIf { ref.parent?.parent in superTypeReferences(owner) }
    }

    /** A Kotlin property overrides the Java accessor `getName()` / `isOpen()`. */
    private fun accessorProperty(member: PsiElement): List<String> {
        val name = (member as? PsiMethod)?.name ?: return emptyList()
        return listOf("get", "is").filter { name.length > it.length && name.startsWith(it) }
            .map { name.removePrefix(it).replaceFirstChar(Char::lowercaseChar) }
    }

    private fun sameKind(member: PsiElement, other: PsiElement): Boolean {
        if (member is KtFunction) return other is KtFunction || other is PsiMethod
        return other is KtProperty || other is KtParameter || other is PsiField || (other is PsiMethod && arity(other) == 0)
    }

    private fun arity(member: PsiElement): Int = when (member) {
        is KtFunction -> member.valueParameters.size
        is PsiMethod -> member.parameterList.parametersCount
        else -> 0
    }
}

/** The name of an `override` member points to the member it overrides: usages and rename of the base reach it. */
class KtOverrideReference(element: KtNamedDeclaration) : LiteReference<KtNamedDeclaration>(element, { element.nameIdentifier }) {
    override fun resolveTargets(): List<PsiElement> = KtInheritance.overridden(element)

    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement =
        KtPsiFactory(element.project).createIdentifier(newName)
}

/** Go to Implementation: subclasses of a class or interface, overrides of a member. */
class KtImplementationSearcher : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {
    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        val element = parameters.element
        val found = when (element) {
            is KtClass, is PsiClass -> KtInheritance.inheritors(element, parameters.scope)
            is KtFunction, is KtProperty, is KtParameter, is PsiMethod -> KtInheritance.overriders(element, parameters.scope)
            else -> emptyList()
        }
        found.all(consumer::process)
    }
}

/** Renaming a member renames its whole override chain, from the declaration at its top. */
class KtOverrideRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean =
        element is KtNamedDeclaration && element !is KtClass && element !is KtObjectDeclaration && KtInheritance.ownerOf(element) != null

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        KtInheritance.overriders(element, element.useScope).forEach { allRenames[it] = newName }
    }

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement =
        KtInheritance.root(element as KtNamedDeclaration)
}
