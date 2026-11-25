package me.kevinlot.lite.scala

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import me.kevinlot.lite.core.LiteKeysIndex
import me.kevinlot.lite.core.ProjectLayout
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.scala.ScalaElementTypes as E

/** Scala's project boundaries ([ProjectLayout]): an sbt or Gradle build (multi-module), else a Maven or Gradle module. */
object ScalaProject {
    val LAYOUT = ProjectLayout(
        rootManifests = listOf("build.sbt", "settings.gradle", "settings.gradle.kts"),
        moduleManifests = listOf("pom.xml", "build.gradle", "build.gradle.kts"),
        outputDirs = setOf(".bloop", ".metals", "build", "out", "target"),
    )
    private val SCOPE = Key.create<CachedValue<GlobalSearchScope>>("lite.scala.scope")

    fun scopeOf(e: PsiElement): GlobalSearchScope = LAYOUT.scopeOf(e, SCOPE)
}

/** name -> files declaring it at the top of a package or in a package object. */
class ScalaNameIndex : LiteKeysIndex<ScalaFile>(ScalaFileType, ScalaFile::class.java) {
    override fun getName(): ID<String, Void> = KEY
    override fun keys(file: ScalaFile): Collection<String> = ScalaFiles.collect(file, "").mapNotNull { it.declaration.name }.toSet()

    companion object {
        val KEY = ID.create<String, Void>("lite.scala.name")
    }
}

/** What statements declare in their scope. */
object ScalaScopes {
    private val DECLARATIONS = Key.create<CachedValue<List<ScalaNamed>>>("lite.scala.declarations")

    fun declarations(container: PsiElement): List<ScalaNamed> = CachedValuesManager.getCachedValue(container, DECLARATIONS) {
        CachedValueProvider.Result.create(container.children.flatMap(::declared), container.containingFile ?: container)
    }

    fun declared(statement: PsiElement): List<ScalaNamed> = when {
        statement.isA(E.VAL_DECL) -> variablesOf(statement)
        statement.isA(E.EXTENSION) -> extensionMethods(statement)
        statement is ScalaClass || statement is ScalaEnumCase || statement is ScalaFunction || statement is ScalaGiven || statement is ScalaTypeAlias ->
            listOf(statement as ScalaNamed)
        else -> emptyList()
    }

    private fun variablesOf(declaration: PsiElement): List<ScalaVariable> = declaration.children.flatMap { child ->
        when {
            child is ScalaVariable -> listOf(child)
            child.isA(E.PATTERN) -> patternVariables(child)
            else -> emptyList()
        }
    }

    fun patternVariables(pattern: PsiElement): List<ScalaVariable> = PsiTreeUtil.findChildrenOfType(pattern, ScalaVariable::class.java).toList()

    private fun extensionMethods(extension: PsiElement): List<ScalaFunction> =
        (extension.children.toList() + extension.children.filter { it.isA(E.BLOCK) }.flatMap { it.children.toList() }).filterIsInstance<ScalaFunction>()

    /** Parameters and type parameters of a function, class, lambda, given, extension, enum case or type alias. */
    fun parameters(owner: PsiElement): List<ScalaNamed> =
        owner.children.filter { it.isA(E.PARAMS) || it.isA(E.TYPE_PARAMS) }.flatMap { it.children.filterIsInstance<ScalaNamed>() }
}

/** A declaration at the top of [packageName], or in its package object. */
data class ScalaTopLevel(val packageName: String, val declaration: ScalaNamed)

object ScalaFiles {
    private val TOP_LEVEL = Key.create<CachedValue<List<ScalaTopLevel>>>("lite.scala.topLevel")

    private fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrEmpty() }.joinToString(".")
    private fun nameOf(pkg: PsiElement): String = pkg.children.firstOrNull { it.isA(E.PACKAGE_NAME) }?.text.orEmpty().filterNot(Char::isWhitespace)

    /** `package a.b` without a body: it holds the rest of its container. */
    private fun isClause(e: PsiElement): Boolean = e.isA(E.PACKAGE) && e.lastChild.isA(E.PACKAGE_NAME)

    fun topLevel(file: PsiFile): List<ScalaTopLevel> = CachedValuesManager.getCachedValue(file, TOP_LEVEL) {
        CachedValueProvider.Result.create(collect(file, ""), file)
    }

    fun collect(container: PsiElement, packageName: String): List<ScalaTopLevel> =
        container.children.fold(packageName to emptyList<ScalaTopLevel>()) { (current, found), child ->
            when {
                isClause(child) -> join(current, nameOf(child)) to found
                child.isA(E.PACKAGE) -> current to found + collect(child, join(current, nameOf(child)))
                else -> current to found + ScalaScopes.declared(child).flatMap { withPackageObject(current, it) }
            }
        }.second

    private fun withPackageObject(packageName: String, declaration: ScalaNamed): List<ScalaTopLevel> {
        val own = listOf(ScalaTopLevel(packageName, declaration))
        if (declaration !is ScalaClass || !declaration.isPackageObject) return own
        return own + declaration.members.map { ScalaTopLevel(join(packageName, declaration.name), it) }
    }

    /** The package names that apply to [e] as written, outermost first: `package a.b` then `package c` gives [a.b, c]. */
    private fun packageChain(e: PsiElement): List<String> =
        generateSequence(e) { it.parent }.takeWhile { it !is PsiFile }.toList().asReversed().flatMap { level ->
            clausesBefore(level) + listOfNotNull(nameOf(level).takeIf { level.isA(E.PACKAGE) && level != e })
        }

    private fun clausesBefore(level: PsiElement): List<String> {
        val container = level.parent?.takeIf { it is PsiFile || it.isA(E.PACKAGE) } ?: return emptyList()
        return container.children.takeWhile { it != level }.filter(::isClause).map(::nameOf)
    }

    fun packageOf(e: PsiElement): String = join(*packageChain(e).toTypedArray())

    /** The packages whose members are visible at [e], innermost first, the root package last. */
    fun visiblePackages(e: PsiElement): List<String> = packageChain(e).runningFold("") { outer, name -> join(outer, name) }.asReversed()

    /** Qualified name of what holds [cls]: its outer class, else its package. */
    fun ownerName(cls: ScalaClass): String = cls.containingClass?.qualifiedName ?: packageOf(cls)
}

/*
 * Scala resolution, lexical:
 * - names: the nearest scope that declares the name (block, parameters, pattern and generator variables, members of
 *   the enclosing templates and of their parents, the file), each scope followed by its imports; then the packages of
 *   the file across the project;
 * - members `a.b`: only where the type of `a` is written: a declared type, `new C(…)`, `C(…)`, `this`, `super`, an
 *   object, the declared result type of a call. A path that names no value is read as a qualified name.
 */
object ScalaResolve {
    private const val COPY = "copy"
    private val GUARD = RecursionGuard()
    private val STATEMENT_LISTS = setOf(E.BLOCK, E.INJECTION, E.PACKAGE, E.TEMPLATE_BODY)
    private val PARAMETER_OWNERS = setOf(E.CLASS, E.ENUM_CASE, E.EXTENSION, E.FUNCTION, E.GIVEN, E.LAMBDA, E.TYPE_ALIAS)

    private fun named(declarations: List<ScalaNamed>, name: String): List<ScalaNamed> = declarations.filter { it.name == name }

    private fun targets(e: PsiElement): List<PsiElement> =
        (e.reference as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }.orEmpty()

    private fun enclosingClass(e: PsiElement): ScalaClass? = PsiTreeUtil.getParentOfType(e, ScalaClass::class.java)

    private fun isTerm(d: ScalaNamed) = d !is ScalaTypeAlias && d !is ScalaTypeParam && !(d is ScalaClass && d.isTrait)
    private fun isType(d: ScalaNamed) = d is ScalaTypeAlias || d is ScalaTypeParam || (d is ScalaClass && !d.isObject)

    // ------------------------------------------------------------ names

    fun resolveName(ref: ScalaRef): List<PsiElement> {
        if (ref.isThis) return listOfNotNull(enclosingClass(ref))
        if (ref.isSuper) return enclosingClass(ref)?.let(::parents).orEmpty().take(1)
        return choose(lookup(ref, ref.name, ::isTerm), callArity(ref), ref.isExtractor)
    }

    /** The declarations called [name] of the nearest scope that has some [accepted] ones. */
    private fun lookup(place: PsiElement, name: String, accepted: (ScalaNamed) -> Boolean): List<ScalaNamed> =
        lexical(place, name, accepted).ifEmpty {
            ScalaFiles.visiblePackages(place).firstNotNullOfOrNull { inPackage(place, it, name).filter(accepted).ifEmpty { null } }.orEmpty()
        }

    private fun lexical(place: PsiElement, name: String, accepted: (ScalaNamed) -> Boolean): List<ScalaNamed> =
        generateSequence(place) { it.parent }.takeWhile { it !is PsiFile }
            .firstNotNullOfOrNull { child -> visible(child.parent, child, name, accepted).ifEmpty { null } }.orEmpty()

    private fun visible(scope: PsiElement, child: PsiElement, name: String, accepted: (ScalaNamed) -> Boolean): List<ScalaNamed> =
        declared(scope, child, name).filter(accepted).ifEmpty { imported(scope, child, name).filter(accepted) }

    /** What [scope] declares under [name] for its part [child]. */
    private fun declared(scope: PsiElement, child: PsiElement, name: String): List<ScalaNamed> {
        val type = scope.node.elementType
        return when {
            type == E.TEMPLATE_BODY -> named(selfNames(scope), name).ifEmpty { templateMembers(scope, name) }
            scope is PsiFile || type == E.PACKAGE -> named(ScalaScopes.declarations(scope), name)
            type == E.BLOCK || type == E.INJECTION -> named(ScalaScopes.declarations(scope), name).filter { declaredBefore(it, child) }
            type == E.CASE_CLAUSE -> named(patternVariables(scope.children.filter { it != child }), name)
            type == E.FOR -> named(patternVariables(scope.children.takeWhile { it != child }.filter { it.isA(E.GENERATOR) }), name)
            type in PARAMETER_OWNERS -> named(ScalaScopes.parameters(scope), name)
            else -> emptyList()
        }
    }

    /** In a block a `val` is visible after its declaration; functions and classes are visible everywhere. */
    private fun declaredBefore(declaration: ScalaNamed, child: PsiElement): Boolean =
        declaration !is ScalaVariable || declaration.textOffset < child.textRange.startOffset

    private fun patternVariables(holders: List<PsiElement>): List<ScalaNamed> =
        holders.flatMap { holder -> (listOf(holder) + holder.children).filter { it.isA(E.PATTERN) } }.flatMap(ScalaScopes::patternVariables)

    private fun selfNames(body: PsiElement): List<ScalaNamed> =
        body.children.filter { it.isA(E.SELF_TYPE) }.flatMap { it.children.filterIsInstance<ScalaVariable>() }

    /** Members of the template that owns [body]: a class, an anonymous class or a given. */
    private fun templateMembers(body: PsiElement, name: String): List<ScalaNamed> {
        val owner = body.parent
        if (owner is ScalaClass) return members(owner, name)
        return named(ScalaScopes.declarations(body), name).ifEmpty {
            owner?.children.orEmpty().filterIsInstance<ScalaType>().mapNotNull(::classOfType)
                .firstNotNullOfOrNull { members(it, name).ifEmpty { null } }.orEmpty()
        }
    }

    // ------------------------------------------------------------ imports

    /** What the imports of [scope] written before [child] bring in under [name], the nearest import first. */
    private fun imported(scope: PsiElement, child: PsiElement, name: String): List<ScalaNamed> {
        if (scope !is PsiFile && scope.node.elementType !in STATEMENT_LISTS) return emptyList()
        return scope.children.takeWhile { it != child }.filter { it.isA(E.IMPORT) }.asReversed()
            .flatMap { it.children.filterIsInstance<ScalaImportExpr>().asReversed() }
            .firstNotNullOfOrNull { importedBy(it, name).ifEmpty { null } }.orEmpty()
    }

    private fun importedBy(import: ScalaImportExpr, name: String): List<ScalaNamed> {
        val single = import.single
        if (single != null) return targets(single).filterIsInstance<ScalaNamed>().takeIf { (import.alias ?: single.name) == name }.orEmpty()
        val selectors = import.selectors
        val explicit = selectors.firstOrNull { it.importedName == name }
        if (explicit != null) return targets(explicit).filterIsInstance<ScalaNamed>()
        val wildcard = import.isWildcard || selectors.any { it.isWildcard }
        if (!wildcard || selectors.any { it.name == name }) return emptyList()
        return membersOfPath(import.prefix, name)
    }

    fun resolveSelector(selector: ScalaSelector): List<PsiElement> {
        val name = selector.name ?: return emptyList()
        return membersOfPath((selector.parent?.parent as? ScalaImportExpr)?.prefix, name)
    }

    /** [name] in what [path] denotes: a member of its object or value, else the qualified name `path.name`. */
    private fun membersOfPath(path: PsiElement?, name: String): List<ScalaNamed> {
        path ?: return emptyList()
        val ofValue = typeOf(path)?.let { members(it, name) }.orEmpty()
        return ofValue.ifEmpty { pathText(path)?.let { qualified(path, "$it.$name") }.orEmpty() }
    }

    private fun pathText(path: PsiElement): String? =
        path.text.filterNot(Char::isWhitespace).takeIf { text -> text.all { it.isLetterOrDigit() || it in "._$`" } }?.removePrefix("_root_.")

    /** Declarations of that qualified name; the first package may be relative to an enclosing one. */
    private fun qualified(place: PsiElement, qualifiedName: String): List<ScalaNamed> {
        val name = unquoted(qualifiedName.substringAfterLast('.'))
        val owner = qualifiedName.substringBeforeLast('.', "").replace("`", "")
        val owners = listOf(owner) + ScalaFiles.visiblePackages(place).filter { it.isNotEmpty() }.map { "$it.$owner" }
        return owners.firstNotNullOfOrNull { inPackage(place, it, name).ifEmpty { null } }.orEmpty()
    }

    /** Declarations called [name] at the top of [packageName] in the project of [place], in path order. */
    private fun inPackage(place: PsiElement, packageName: String, name: String): List<ScalaNamed> {
        val manager = PsiManager.getInstance(place.project)
        return FileBasedIndex.getInstance().getContainingFiles(ScalaNameIndex.KEY, name, ScalaProject.scopeOf(place)).sortedBy { it.path }
            .mapNotNull(manager::findFile).flatMap(ScalaFiles::topLevel)
            .filter { it.packageName == packageName && it.declaration.name == name }.map { it.declaration }
    }

    // ------------------------------------------------------------ choice

    /** Overloads are told apart by their number of arguments only, and never all dropped. */
    private fun bestArity(functions: List<ScalaFunction>, arity: Int?): List<ScalaFunction> =
        functions.filter { it.paramClauses.firstOrNull()?.children.orEmpty().size == arity }.ifEmpty { functions }

    private fun choose(found: List<ScalaNamed>, arity: Int?, extractor: Boolean = false): List<PsiElement> {
        val classes = found.filterIsInstance<ScalaClass>()
        if (classes.isNotEmpty()) return chooseClass(classes, arity, extractor)
        return bestArity(found.filterIsInstance<ScalaFunction>(), arity) + found.filter { it !is ScalaFunction }
    }

    /** A value names the object; `C(…)` its `apply`, `case C(…)` its `unapply`, and the class when the object writes none. */
    private fun chooseClass(classes: List<ScalaClass>, arity: Int?, extractor: Boolean): List<PsiElement> {
        val objects = classes.filter { it.isObject }
        val others = classes - objects.toSet()
        val method = when {
            arity != null -> "apply"
            extractor -> "unapply"
            else -> return objects.ifEmpty { others }
        }
        val written = objects.flatMap { members(it, method) }.filterIsInstance<ScalaFunction>()
        return bestArity(written, arity).ifEmpty { others }.ifEmpty { objects }
    }

    // ------------------------------------------------------------ types

    fun resolveType(type: ScalaType): List<PsiElement> {
        val path = type.path
        val name = path.lastOrNull() ?: return emptyList()
        if (type.isSingleton) return valuePath(type, path)
        if (path.size == 1) return lookup(type, name, ::isType).ifEmpty { lookup(type, name) { it is ScalaClass } }
        val nested = valuePath(type, path.dropLast(1)).mapNotNull(::typeOfTarget).flatMap { members(it, name) }
        return nested.filter(::isType).ifEmpty { nested }.ifEmpty { qualified(type, path.joinToString(".")) }
    }

    /** `a.b.c` read as a value: `a` by its name, then members. */
    private fun valuePath(place: PsiElement, path: List<String>): List<ScalaNamed> =
        path.drop(1).fold(lookup(place, path.first(), ::isTerm)) { owners, segment -> owners.mapNotNull(::typeOfTarget).flatMap { members(it, segment) } }

    private fun classOfType(type: PsiElement?): ScalaClass? = (type as? ScalaType)?.let(::targets)?.firstNotNullOfOrNull(::asClass)

    private fun asClass(target: PsiElement): ScalaClass? = when (target) {
        is ScalaClass -> target
        is ScalaTypeAlias -> GUARD.run<ScalaClass?>(null) { classOfType(target.aliased) }
        else -> null
    }

    /** The parents found in the project, the last one written first (it wins in the linearization). */
    private fun parents(cls: ScalaClass): List<ScalaClass> = cls.superTypes.mapNotNull(::classOfType).asReversed()

    // ------------------------------------------------------------ members

    fun resolveMember(member: ScalaMember): List<PsiElement> {
        val name = member.name ?: return emptyList()
        if (member.isThis) return listOfNotNull(typeOf(member))
        val found = membersOfPath(member.qualifier, name)
        if (member.parent is ScalaImportExpr) return found
        return choose(found, callArity(member))
    }

    /** Members [name] of [cls]: its own, else those of the first parent that has some. */
    fun members(cls: ScalaClass, name: String, seen: Set<ScalaClass> = emptySet()): List<ScalaNamed> {
        if (cls in seen) return emptyList()
        return named(cls.members, name).ifEmpty { parents(cls).firstNotNullOfOrNull { members(it, name, seen + cls).ifEmpty { null } }.orEmpty() }
    }

    fun resolveOperator(op: ScalaOpRef): List<PsiElement> {
        val operand = when {
            op.isRightAssociative -> op.right
            else -> op.left
        }
        val owner = typeOf(operand) ?: return emptyList()
        return choose(members(owner, op.name), when { op.parent.isA(E.INFIX) -> 1; else -> 0 })
    }

    fun resolveNamedArg(arg: ScalaNamedArg): List<PsiElement> {
        val name = arg.name ?: return emptyList()
        return named(calledBy(arg.parent).flatMap(::parametersOf), name)
    }

    /** What the argument list [args] is given to: the callee of a call, or the class of `new C(…)`, `extends C(…)`, `@C(…)`. */
    private fun calledBy(args: PsiElement?): List<PsiElement> {
        val call = args?.parent
        if (call is ScalaCall) return applied(call.callee)?.let { callee -> targets(callee).ifEmpty { listOfNotNull(copied(callee)) } }.orEmpty()
        return generateSequence(args?.prevSibling) { it.prevSibling }.firstNotNullOfOrNull { it as? ScalaType }?.let(::targets).orEmpty()
    }

    /** `x.copy(name = …)` and `copy(name = …)`: the case class whose parameters are copied. */
    private fun copied(callee: PsiElement): ScalaClass? = when {
        callee is ScalaRef && callee.name == COPY -> enclosingClass(callee)
        callee is ScalaMember && callee.name == COPY -> typeOf(callee.qualifier)
        else -> null
    }?.takeIf { it.isCase }

    private fun parametersOf(target: PsiElement): List<ScalaVariable> = when (target) {
        is ScalaClass -> target.params
        is ScalaEnumCase -> target.params
        is ScalaFunction -> target.params
        else -> emptyList()
    }

    // ------------------------------------------------------------ the type of an expression

    /** The class or object of an expression whose type is written down; null for anything that would need inference. */
    fun typeOf(e: PsiElement?): ScalaClass? = e?.let { GUARD.run<ScalaClass?>(null) { typeOfExpression(it) } }

    private fun typeOfExpression(e: PsiElement): ScalaClass? = when {
        e is ScalaRef && e.isThis -> enclosingClass(e)
        e is ScalaRef && e.isSuper -> enclosingClass(e)?.let(::parents)?.firstOrNull()
        e is ScalaMember && e.isThis -> generateSequence(enclosingClass(e), ::enclosingClass).firstOrNull { it.name == e.qualifier?.text }
        e is ScalaRef || e is ScalaMember -> targets(e).firstNotNullOfOrNull(::typeOfTarget)
        e is ScalaCall -> typeOfCall(e)
        e.isA(E.NEW) -> classOfType(e.children.firstOrNull { it is ScalaType })
        e.isA(E.ASCRIPTION) -> classOfType(e.lastChild)
        e.isA(E.TYPE_APPLY) -> typeOf(e.firstChild)
        e.isA(E.PAREN) -> typeOf(e.children.singleOrNull())
        e.isA(E.BLOCK) -> typeOf(e.children.lastOrNull())
        else -> null
    }

    fun typeOfTarget(target: PsiElement): ScalaClass? = when (target) {
        is ScalaClass -> target.takeIf { it.isObject || it.isEnum }
        is ScalaEnumCase -> target.containingClass
        is ScalaFunction -> resultClass(target)
        is ScalaGiven -> classOfType(target.givenType)
        is ScalaVariable -> valueClass(target)
        else -> null
    }

    /** The written result type; without one, the value when its own type is written (`= new C(…)`). */
    private fun resultClass(function: ScalaFunction): ScalaClass? = when (val written = function.resultType) {
        null -> typeOf(function.body)
        else -> classOfType(written)
    }

    private fun valueClass(variable: ScalaVariable): ScalaClass? {
        if (variable.isSelf) return variable.containingClass
        return when (val written = variable.declaredType) {
            null -> typeOf(variable.initializer)
            else -> classOfType(written)
        }
    }

    /** `f(a)`, `f(a)(b)`, `C(a)`, `obj(a)`: the calls beyond the parameter lists of what is called go to `apply`. */
    private fun typeOfCall(call: ScalaCall): ScalaClass? {
        val calls = generateSequence(call) { applied(it.callee) as? ScalaCall }.toList()
        val root = applied(calls.last().callee) ?: return null
        val isName = (root is ScalaRef && !root.isThis && !root.isSuper) || root is ScalaMember
        if (!isName) return afterApply(typeOf(root), calls.size)
        return targets(root).firstNotNullOfOrNull { applyTo(it, calls.size) }
    }

    private fun applyTo(target: PsiElement, calls: Int): ScalaClass? = when (target) {
        is ScalaFunction -> afterApply(resultClass(target), calls - maxOf(target.paramClauses.size, 1))
        is ScalaClass -> afterApply(target, when { target.isObject -> calls; else -> calls - 1 })
        else -> afterApply(typeOfTarget(target), calls)
    }

    private fun afterApply(cls: ScalaClass?, calls: Int): ScalaClass? {
        if (cls == null || calls <= 0) return cls
        val apply = members(cls, "apply").filterIsInstance<ScalaFunction>()
        return apply.firstNotNullOfOrNull { applyTo(it, calls) } ?: cls.companion?.takeIf { cls.isObject }
    }
}
