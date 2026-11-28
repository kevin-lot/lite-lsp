package me.kevinlot.lite.kotlin.resolve

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.kotlin.KtFile
import me.kevinlot.lite.kotlin.psi.KtClass
import me.kevinlot.lite.kotlin.psi.KtDestructuringEntry
import me.kevinlot.lite.kotlin.psi.KtElement
import me.kevinlot.lite.kotlin.psi.KtEnumEntry
import me.kevinlot.lite.kotlin.psi.KtFunction
import me.kevinlot.lite.kotlin.psi.KtImportDirective
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression
import me.kevinlot.lite.kotlin.psi.KtNamedDeclaration
import me.kevinlot.lite.kotlin.psi.KtObjectDeclaration
import me.kevinlot.lite.kotlin.psi.KtPackageDirective
import me.kevinlot.lite.kotlin.psi.KtParameter
import me.kevinlot.lite.kotlin.psi.KtProperty
import me.kevinlot.lite.kotlin.psi.KtTypeAlias
import me.kevinlot.lite.kotlin.psi.KtTypeParameter
import me.kevinlot.lite.kotlin.psi.compactPath
import me.kevinlot.lite.kotlin.types.KtTy
import me.kevinlot.lite.kotlin.types.KtTypes
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N

/** What a qualifier denotes: a package or a declaration whose nested/static members can be named. */
sealed class Namespace {
    data class Pkg(val fqName: String) : Namespace()
    data class Decl(val element: PsiElement) : Namespace()
}

/**
 * Kotlin name resolution: locals, lambdas (`it`, implicit receivers of scope functions), class
 * members and supertypes, companion objects, extension functions, imports (explicit, star, alias),
 * same-package and default imports, Java classes (JDK, project, library jars).
 *
 * Overloads are chosen by argument count, then by argument types ([KtOverloads]); `is` checks narrow a value ([KtSmartCasts]).
 * Not modelled (on purpose): generic inference beyond declared types and common collection/scope functions, implicit
 * conversions, lambda argument types in overload choice.
 */
object KtResolver {
    private val GUARD = RecursionGuard(48)
    private val TYPE_ARGUMENTS = Regex("<.*>")
    private val COMPONENT_N = Regex("component\\d+")
    private val RESOLVE_KEY = Key.create<CachedValue<List<PsiElement>>>("lite.kt.resolve")
    private val IMPORTS_KEY = Key.create<CachedValue<Imports>>("lite.kt.imports")

    /** Import table of a file: bound name -> imported paths, and the paths of the `.*` imports. */
    private class Imports(val named: Map<String, List<String>>, val star: List<String>)

    fun resolve(ref: KtNameReferenceExpression): List<PsiElement> =
        GUARD.run(emptyList()) { KtFiles.cachedNonRecursive(ref, RESOLVE_KEY, emptyList()) { doResolve(ref).distinct() } }

    private fun doResolve(ref: KtNameReferenceExpression): List<PsiElement> {
        val name = ref.referencedName ?: return emptyList()
        val parent = ref.parent ?: return emptyList()
        directiveSegment(ref)?.let { return it }
        KtCatalog.resolve(ref, name)?.let { return it }
        if (parent.node.elementType == N.VALUE_ARGUMENT_NAME) return namedArgument(ref, name)
        if (parent.node.elementType == N.USER_TYPE) {
            val qualifier = parent.children.firstOrNull { it.node.elementType == N.USER_TYPE }
            if (qualifier != null) return userTypeNamespaces(qualifier).flatMap { namespaceMember(it, name, ref, call = false) }
                .filter { isTypeLike(it) }
            return lexical(name, ref, call = false, typeOnly = true)
        }
        val call = isCall(ref)
        receiverOf(ref)?.let { receiver -> return filterOverloads(memberOf(receiver, name, call, ref), ref) }
        return filterOverloads(lexical(name, ref, call, typeOnly = false), ref)
    }

    // ---------------------------------------------------------------- shape helpers

    /** Call position, or `::name` (a callable reference names functions too). */
    fun isCall(ref: PsiElement): Boolean = (ref.parent?.node?.elementType == N.CALL_EXPRESSION && ref.parent.firstChild == ref) ||
        ref.parent?.node?.elementType == N.CALLABLE_REFERENCE_EXPRESSION

    /** `receiver.ref`, `receiver?.ref(...)`, `Receiver::ref` -> receiver expression. */
    fun receiverOf(e: PsiElement): PsiElement? {
        val selector = when {
            e is KtNameReferenceExpression && isCall(e) -> e.parent
            else -> e
        }
        val q = selector.parent ?: return null
        return when (q.node.elementType) {
            N.DOT_QUALIFIED_EXPRESSION, N.SAFE_ACCESS_EXPRESSION ->
                q.children.firstOrNull { it is KtElement }?.takeIf { it != selector }
            N.CALLABLE_REFERENCE_EXPRESSION ->
                q.children.firstOrNull { it is KtElement }?.takeIf { it != selector }
            else -> null
        }
    }

    private fun isTypeLike(e: PsiElement) =
        e is KtClass || e is KtObjectDeclaration || e is KtTypeAlias || e is KtTypeParameter || e is PsiClass

    // ---------------------------------------------------------------- import / package directives

    private fun directiveSegment(ref: KtNameReferenceExpression): List<PsiElement>? {
        val directive = PsiTreeUtil.getParentOfType(ref, KtImportDirective::class.java, KtPackageDirective::class.java) ?: return null
        if (ref.parent.node.elementType == N.IMPORT_ALIAS) return null
        val prefix = pathPrefix(directive, ref)
        if (directive is KtPackageDirective) return listOfNotNull(KtJava.findPackage(ref, prefix))
        val import = directive as KtImportDirective
        val isLast = prefix == import.importedPath && !import.isAllUnder
        if (isLast) return resolveImportPath(ref, prefix)
        return listOfNotNull(KtJava.findClass(ref, prefix) ?: KtJava.findPackage(ref, prefix))
    }

    /** `a.b.c` of an import/package path, up to and including [ref]. */
    private fun pathPrefix(directive: KtElement, ref: PsiElement): String {
        val path = directive.children.firstOrNull { it.node.elementType == N.DOT_QUALIFIED_EXPRESSION || it is KtNameReferenceExpression }
            ?: return ref.text
        val text = path.text.substring(0, ref.textRange.endOffset - path.textRange.startOffset)
        return compactPath(text)
    }

    /** Declarations an import path names: top-level Kotlin, Java class, nested/companion member, facade function. */
    fun resolveImportPath(place: PsiElement, fq: String): List<PsiElement> {
        val pkg = fq.substringBeforeLast('.', "")
        val last = fq.substringAfterLast('.')
        val out = mutableListOf<PsiElement>()
        out += KtFiles.topLevelInPackage(place, pkg, last)
        KtJava.findClass(place, fq)?.let { out += it }
        if (out.isEmpty()) {
            KtJava.findClass(place, pkg)?.let { out += namespaceMember(Namespace.Decl(it), last, place, call = false) }
            out += KtJava.facadeFunctions(place, listOf(pkg), last)
            out += kotlinClassNamespace(place, pkg).flatMap { namespaceMember(it, last, place, call = false) }
        }
        return out
    }

    /** `com.foo.Outer` written in Kotlin sources, for `import com.foo.Outer.Inner`. */
    private fun kotlinClassNamespace(place: PsiElement, fq: String): List<Namespace> {
        val pkg = fq.substringBeforeLast('.', "")
        return KtFiles.topLevelInPackage(place, pkg, fq.substringAfterLast('.')).filter { isTypeLike(it) }.map { Namespace.Decl(it) }
    }

    // ---------------------------------------------------------------- qualifiers

    private fun userTypeNamespaces(userType: PsiElement): List<Namespace> {
        val ref = userType.children.lastOrNull { it is KtNameReferenceExpression } as? KtNameReferenceExpression ?: return emptyList()
        val name = ref.referencedName ?: return emptyList()
        val qualifier = userType.children.firstOrNull { it.node.elementType == N.USER_TYPE }
        val targets = when {
            qualifier == null -> lexical(name, ref, call = false, typeOnly = true)
            else -> userTypeNamespaces(qualifier).flatMap { namespaceMember(it, name, ref, call = false) }
        }
        return targets.filter { isTypeLike(it) }.map { Namespace.Decl(it) }.ifEmpty {
            val fq = compactPath(userType.text.replace(TYPE_ARGUMENTS, ""))
            when {
                KtJava.findPackage(ref, fq) != null || KtFiles.filesInPackage(ref, fq).isNotEmpty() -> listOf(Namespace.Pkg(fq))
                else -> emptyList()
            }
        }
    }

    /** A receiver that names a package, a class or an object (`kotlin.math.max`, `Color.RED`, `Foo.create()`). */
    fun namespaceOf(receiver: PsiElement): List<Namespace> = when (receiver.node.elementType) {
        N.REFERENCE_EXPRESSION -> {
            val ref = receiver as KtNameReferenceExpression
            val name = ref.referencedName.orEmpty()
            val targets = resolve(ref)
            val decls = targets.filter { isTypeLike(it) || it is PsiPackage }.map { namespaceFor(it) }
            when {
                decls.isNotEmpty() -> decls
                targets.isNotEmpty() -> emptyList() // a value
                KtJava.findPackage(ref, name) != null || KtFiles.filesInPackage(ref, name).isNotEmpty() -> listOf(Namespace.Pkg(name))
                else -> emptyList()
            }
        }
        N.DOT_QUALIFIED_EXPRESSION -> qualifiedNamespace(receiver)
        else -> emptyList()
    }

    private fun namespaceFor(target: PsiElement): Namespace = when (target) {
        is PsiPackage -> Namespace.Pkg(target.qualifiedName)
        else -> Namespace.Decl(target)
    }

    private fun qualifiedNamespace(receiver: PsiElement): List<Namespace> {
        val inner = receiver.children.filter { it is KtElement }
        val head = inner.firstOrNull() ?: return emptyList()
        val tail = inner.getOrNull(1) as? KtNameReferenceExpression ?: return emptyList()
        return namespaceOf(head).flatMap { ns ->
            namespaceMember(ns, tail.referencedName.orEmpty(), tail, call = false).mapNotNull { t ->
                when {
                    t is PsiPackage -> Namespace.Pkg(t.qualifiedName)
                    isTypeLike(t) -> Namespace.Decl(t)
                    else -> null
                }
            }
        }
    }

    /** Members reachable through a namespace (no instance needed). */
    fun namespaceMember(ns: Namespace, name: String, place: PsiElement, call: Boolean): List<PsiElement> = when (ns) {
        is Namespace.Pkg -> {
            val fq = qualified(ns.fqName, name)
            KtFiles.topLevelInPackage(place, ns.fqName, name) +
                listOfNotNull(KtJava.findClass(place, fq)) +
                KtJava.facadeFunctions(place, listOf(ns.fqName), name) +
                listOfNotNull(KtJava.findPackage(place, fq))
        }
        is Namespace.Decl -> when (val d = ns.element) {
            is KtClass -> nested(d.body, name) + companionMembers(d, name, call)
            is KtObjectDeclaration -> declarationsIn(d.body, name, call)
            is KtTypeAlias -> KtTypes.typeOf(d.typeReference)
                .let { t -> (t as? KtTy.Cls)?.decl?.let { namespaceMember(Namespace.Decl(it), name, place, call) } }.orEmpty()
            is PsiClass -> KtJava.staticMembers(d, name)
            else -> emptyList()
        }
    }

    private fun qualified(pkg: String, name: String): String {
        if (pkg.isEmpty()) return name
        return "$pkg.$name"
    }

    private fun nested(body: KtElement?, name: String): List<PsiElement> =
        body?.children.orEmpty().filter { (it is KtClass || it is KtObjectDeclaration || it is KtEnumEntry) && (it as KtNamedDeclaration).name == name }

    private fun companionMembers(cls: KtClass, name: String, call: Boolean): List<PsiElement> =
        cls.body?.childrenOf<KtObjectDeclaration>()?.filter { it.isCompanion }?.flatMap { declarationsIn(it.body, name, call) }.orEmpty()

    private fun declarationsIn(body: KtElement?, name: String, call: Boolean): List<PsiElement> =
        body?.children.orEmpty().filter { it is KtNamedDeclaration && it.name == name && accepts(it, call) }

    /** For calls: functions, classes (constructors) and function-typed properties; otherwise anything but functions. */
    private fun accepts(e: PsiElement, call: Boolean): Boolean {
        if (call) return e !is KtEnumEntry
        return e !is KtFunction && !(e is PsiMethod && e.parameterList.parametersCount > 0)
    }

    // ---------------------------------------------------------------- members of values

    private fun memberOf(receiver: PsiElement, name: String, call: Boolean, place: PsiElement): List<PsiElement> {
        val ns = namespaceOf(receiver)
        if (ns.isNotEmpty()) { ns.flatMap { namespaceMember(it, name, place, call) }.takeIf { it.isNotEmpty() }?.let { return it } }
        return typeMembers(KtTypes.inferType(receiver), name, call, place)
    }

    /** Members of an instance of [ty]: own, inherited, then extension functions in scope. */
    fun typeMembers(ty: KtTy, name: String, call: Boolean, place: PsiElement): List<PsiElement> {
        val own = mutableListOf<PsiElement>()
        collectMembers(ty, name, call, place, HashSet(), own)
        if (own.isNotEmpty()) return own
        return extensions(ty, name, call, place)
    }

    private fun collectMembers(ty: KtTy, name: String, call: Boolean, place: PsiElement, seen: MutableSet<PsiElement>, out: MutableList<PsiElement>) {
        when (ty) {
            is KtTy.Cls -> {
                if (!seen.add(ty.decl)) return
                when (val d = ty.decl) {
                    is KtClass -> {
                        out += declarationsIn(d.body, name, call)
                        if (!call) out += d.primaryConstructorParameters.filter { it.isProperty && it.name == name }
                        // Generated members of data classes navigate to the class itself.
                        if (out.isEmpty() && call && d.hasModifier(KtTokens.DATA_KEYWORD) && (name == "copy" || name.matches(COMPONENT_N))) out += d
                        if (out.isEmpty()) {
                            val subst = KtTypes.substOf(ty)
                            d.superTypeReferences.forEach { collectMembers(KtTypes.typeOf(it, subst), name, call, place, seen, out) }
                        }
                        // Implicit supertypes: enums extend java.lang.Enum (`ordinal`, `name`), everything extends Any.
                        val implicit = when { d.isEnum -> "java.lang.Enum"; else -> "java.lang.Object" }
                        if (out.isEmpty()) KtJava.findClass(place, implicit)?.let { out += KtJava.members(it, name, call) }
                    }
                    is KtObjectDeclaration -> {
                        out += declarationsIn(d.body, name, call)
                        if (out.isEmpty()) d.superTypeReferences.forEach { collectMembers(KtTypes.typeOf(it), name, call, place, seen, out) }
                    }
                    is PsiClass -> out += KtJava.members(d, name, call)
                }
            }
            is KtTy.Param -> {
                val bound = (ty.param as? KtTypeParameter)?.boundTypeReference
                if (bound != null) collectMembers(KtTypes.typeOf(bound), name, call, place, seen, out)
            }
            KtTy.Unknown -> {}
        }
    }

    /** Extension functions/properties whose receiver is [ty] or one of its supertypes. */
    private fun extensions(ty: KtTy, name: String, call: Boolean, place: PsiElement): List<PsiElement> {
        val kotlinSupers = HashSet<PsiElement>()
        val javaSupers = HashSet<String>()
        supertypes(ty, kotlinSupers, javaSupers)
        if (kotlinSupers.isEmpty() && javaSupers.isEmpty()) return emptyList()
        val source = KtFiles.topLevelNamed(place, name).filter { decl ->
            val receiverRef = (decl as? KtFunction)?.receiverTypeReference ?: (decl as? KtProperty)?.receiverTypeReference ?: return@filter false
            if (!accepts(decl, call)) return@filter false
            when (val r = KtTypes.typeOf(receiverRef)) {
                is KtTy.Cls -> r.decl in kotlinSupers || (r.decl as? PsiClass)?.qualifiedName in javaSupers
                is KtTy.Param -> true // `fun <T> T.also()`
                KtTy.Unknown -> false
            }
        }
        if (source.isNotEmpty()) return source
        if (!call || javaSupers.isEmpty()) return emptyList()
        // Compiled stdlib/libraries: `CollectionsKt.map(Iterable, Function1)`.
        return KtJava.facadeFunctions(place, KtJava.DEFAULT_PACKAGES + starImports(place), name).filter { m ->
            when (val first = (m as? PsiMethod)?.parameterList?.parameters?.firstOrNull()?.type) {
                is com.intellij.psi.PsiClassType -> first.resolve().let { it is com.intellij.psi.PsiTypeParameter || it?.qualifiedName in javaSupers }
                is com.intellij.psi.PsiPrimitiveType -> first.boxedTypeName in javaSupers // `fun Int.coerceAtLeast()`
                else -> false
            }
        }
    }

    /** The Kotlin declarations and the Java class names [ty] is a subtype of (itself included). */
    internal fun supertypes(ty: KtTy, kotlin: MutableSet<PsiElement>, java: MutableSet<String>) {
        val cls = ty as? KtTy.Cls ?: return
        when (val d = cls.decl) {
            is KtClass -> if (kotlin.add(d)) {
                d.superTypeReferences.forEach { supertypes(KtTypes.typeOf(it), kotlin, java) }
                java += "java.lang.Object"
            }
            is KtObjectDeclaration -> if (kotlin.add(d)) d.superTypeReferences.forEach { supertypes(KtTypes.typeOf(it), kotlin, java) }
            is PsiClass -> java += KtJava.supertypeNames(d)
        }
    }

    private fun starImports(place: PsiElement): List<String> = (place.containingFile as? KtFile)?.let { imports(it).star }.orEmpty()

    // ---------------------------------------------------------------- lexical scopes

    /**
     * Scopes from [place] outward: block-local declarations (before use), lambda parameters and `it`,
     * implicit receivers, `for`/`catch`/`when` variables, function parameters, class members (with
     * companion and supertypes), then the file: top-level, imports, same package, default imports.
     */
    fun lexical(name: String, place: PsiElement, call: Boolean, typeOnly: Boolean): List<PsiElement> {
        var prev: PsiElement = place
        var cur: PsiElement? = place.parent
        while (cur != null) {
            val found: List<PsiElement> = when {
                cur is KtFile -> return fileScope(cur, name, call, typeOnly, place)
                typeOnly -> typeScope(cur, prev, name)
                else -> valueScope(cur, prev, name, call, place)
            }
            if (found.isNotEmpty()) return found
            prev = cur
            cur = cur.parent
        }
        return emptyList()
    }

    private fun typeScope(cur: PsiElement, prev: PsiElement, name: String): List<PsiElement> = when {
        cur is KtNamedDeclaration -> cur.typeParameters.filter { it.name == name } + nestedTypes(cur, prev, name)
        cur.node.elementType == N.BLOCK -> cur.children.filter { (it is KtClass || it is KtTypeAlias) && (it as KtNamedDeclaration).name == name }
        else -> emptyList()
    }

    private fun nestedTypes(decl: KtNamedDeclaration, prev: PsiElement, name: String): List<PsiElement> = when {
        decl is KtObjectDeclaration -> nested(decl.body, name)
        // Nested classes are not visible from the class header (`class A : B()`): that would be circular.
        decl is KtClass && prev.node.elementType != N.SUPER_TYPE_LIST -> nested(decl.body, name) + supertypeNested(decl, name)
        else -> emptyList()
    }

    private fun supertypeNested(cls: KtClass, name: String): List<PsiElement> =
        cls.superTypeReferences.flatMap { ref ->
            when (val d = (KtTypes.typeOf(ref) as? KtTy.Cls)?.decl) {
                is KtClass -> nested(d.body, name)
                is PsiClass -> listOfNotNull(d.findInnerClassByName(name, true))
                else -> emptyList()
            }
        }

    private fun valueScope(cur: PsiElement, prev: PsiElement, name: String, call: Boolean, place: PsiElement): List<PsiElement> {
        val type = cur.node.elementType
        return when {
            type == N.BLOCK -> blockScope(cur, prev, name, call)
            type == N.FUNCTION_LITERAL -> lambdaScope(cur as KtElement, name, call, place)
            type == N.FOR && prev.node.elementType == N.BODY -> loopVariables(cur as KtElement, name)
            type == N.CATCH -> (cur as KtElement).child(N.VALUE_PARAMETER_LIST)?.children.orEmpty().filter { it is KtParameter && it.name == name }
            type == N.WHEN -> cur.children.filter { it is KtProperty && it.name == name }
            type == N.SECONDARY_CONSTRUCTOR ->
                (cur as KtElement).child(N.VALUE_PARAMETER_LIST)?.children.orEmpty().filter { it is KtParameter && it.name == name }
            cur is KtFunction -> cur.valueParameters.filter { it.name == name } +
                cur.typeParameters.filter { it.name == name } +
                (cur.receiverTypeReference?.let { typeMembers(KtTypes.typeOf(it), name, call, place) }.orEmpty())
            cur is KtProperty && cur.receiverTypeReference != null && prev != cur.receiverTypeReference ->
                typeMembers(KtTypes.typeOf(cur.receiverTypeReference), name, call, place)
            cur is KtClass -> classScope(cur, prev, name, call, place)
            cur is KtObjectDeclaration -> typeMembers(KtTy.Cls(cur), name, call, place)
            else -> emptyList()
        }
    }

    private fun blockScope(block: PsiElement, prev: PsiElement, name: String, call: Boolean): List<PsiElement> {
        val stmts = block.children
        val idx = stmts.indexOf(prev).takeIf { it >= 0 } ?: stmts.size
        for (i in idx - 1 downTo 0) {
            val s = stmts[i]
            if (s is KtNamedDeclaration && s.name == name && accepts(s, call)) return listOf(s)
            if (s.node.elementType == N.DESTRUCTURING_DECLARATION) {
                s.children.firstOrNull { it is KtDestructuringEntry && it.name == name }?.let { return listOf(it) }
            }
        }
        return emptyList()
    }

    private fun lambdaScope(literal: KtElement, name: String, call: Boolean, place: PsiElement): List<PsiElement> {
        val params = (literal.child(N.VALUE_PARAMETER_LIST) as? KtElement)?.children.orEmpty()
        params.firstOrNull { it is KtParameter && it.name == name }?.let { return listOf(it) }
        params.flatMap { p -> PsiTreeUtil.findChildrenOfType(p, KtDestructuringEntry::class.java) }.firstOrNull { it.name == name }?.let { return listOf(it) }
        if (name == "it" && params.isEmpty()) return listOf(literal)
        // Implicit receiver: `x.apply { name }`, `with(x) { name }`
        val receiver = KtTypes.lambdaThis(literal) ?: return emptyList()
        return typeMembers(receiver, name, call, place)
    }

    private fun loopVariables(loop: KtElement, name: String): List<PsiElement> {
        val param = loop.child(N.VALUE_PARAMETER) as? KtParameter
        if (param?.name == name) return listOf(param)
        return param?.let { PsiTreeUtil.findChildrenOfType(it, KtDestructuringEntry::class.java).filter { e -> e.name == name } }.orEmpty() +
            loop.children.filter { it.node.elementType == N.DESTRUCTURING_DECLARATION }
                .flatMap { d -> d.children.filter { it is KtDestructuringEntry && it.name == name } }
    }

    private fun classScope(cls: KtClass, prev: PsiElement, name: String, call: Boolean, place: PsiElement): List<PsiElement> {
        cls.typeParameters.filter { it.name == name }.takeIf { it.isNotEmpty() }?.let { return it }
        // Constructor parameters are visible in initializers and property initializers.
        if (prev.node.elementType == N.CLASS_BODY || prev.node.elementType == N.SUPER_TYPE_LIST) {
            val inInit = PsiTreeUtil.getParentOfType(place, KtFunction::class.java, true, KtClass::class.java) == null
            if (inInit) cls.primaryConstructorParameters.filter { it.name == name }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        typeMembers(KtTy.Cls(cls, cls.typeParameters.map { KtTy.Param(it) }), name, call, place).takeIf { it.isNotEmpty() }?.let { return it }
        return nested(cls.body, name) + companionMembers(cls, name, call)
    }

    private fun imports(file: KtFile): Imports = KtFiles.cached(file, IMPORTS_KEY) {
        val directives = file.children.firstOrNull { it.node.elementType == N.IMPORT_LIST }?.children.orEmpty().filterIsInstance<KtImportDirective>()
        val (star, named) = directives.partition { it.isAllUnder }
        val bound = named.mapNotNull { d -> d.importedPath?.let { path -> (d.aliasName ?: path.substringAfterLast('.')) to path } }
        Imports(bound.groupBy({ it.first }, { it.second }), star.mapNotNull { it.importedPath })
    }

    private fun fileScope(file: KtFile, name: String, call: Boolean, typeOnly: Boolean, place: PsiElement): List<PsiElement> {
        fun ok(e: PsiElement): Boolean {
            if (typeOnly) return isTypeLike(e)
            return accepts(e, call)
        }
        KtFiles.topLevel(file).filter { it.name == name && ok(it) }.takeIf { it.isNotEmpty() }?.let { return it }
        val imports = imports(file)
        // In a type position only classes count: `import kotlin.collections.List` must not pick the `List(size) {}` factory.
        imports.named[name].orEmpty().flatMap { resolveImportPath(place, it) }
            .filter { !typeOnly || isTypeLike(it) }.takeIf { it.isNotEmpty() }?.let { return it }
        val pkg = KtFiles.packageName(file)
        KtFiles.topLevelInPackage(file, pkg, name).filter { ok(it) }.takeIf { it.isNotEmpty() }?.let { return it }
        KtJava.findClass(place, qualified(pkg, name))?.let { return listOf(it) }
        imports.star.flatMap { fq ->
            KtJava.findClass(place, fq)?.let { namespaceMember(Namespace.Decl(it), name, place, call) }
                ?: namespaceMember(Namespace.Pkg(fq), name, place, call)
        }.filter { ok(it) || it is PsiMethod }.takeIf { it.isNotEmpty() }?.let { return it }
        return defaultImports(name, call, typeOnly, place)
    }

    private fun defaultImports(name: String, call: Boolean, typeOnly: Boolean, place: PsiElement): List<PsiElement> {
        KtFiles.defaultPackagesWithSources(place).flatMap { KtFiles.topLevelInPackage(place, it, name) }.takeIf { it.isNotEmpty() }?.let { return it }
        KtJava.builtin(place, name)?.let { return listOf(it) }
        KtJava.DEFAULT_PACKAGES.firstNotNullOfOrNull { KtJava.findClass(place, "$it.$name") }?.let { return listOf(it) }
        if (typeOnly) return emptyList()
        return KtJava.facadeFunctions(place, KtJava.DEFAULT_PACKAGES, name)
    }

    // ---------------------------------------------------------------- arguments and overloads

    /** `f(x = 1)`, `@Tag(x = 1)` and `: Base(x = 1)` all take named arguments. */
    private val ARGUMENT_OWNERS = setOf(N.ANNOTATION_ENTRY, N.CALL_EXPRESSION, N.SUPER_TYPE_CALL_ENTRY)

    private fun calleeOf(call: PsiElement): KtNameReferenceExpression? {
        if (call is PsiFile) return null
        if (call.node.elementType == N.CALL_EXPRESSION) return call.firstChild as? KtNameReferenceExpression
        val type = PsiTreeUtil.findChildrenOfAnyType(call, false, PsiElement::class.java).firstOrNull { it.node?.elementType == N.USER_TYPE }
        return type?.children?.lastOrNull { it is KtNameReferenceExpression } as? KtNameReferenceExpression
    }

    private fun namedArgument(ref: KtNameReferenceExpression, name: String): List<PsiElement> {
        val call = PsiTreeUtil.findFirstParent(ref) { it is PsiFile || it.node?.elementType in ARGUMENT_OWNERS } ?: return emptyList()
        val callee = calleeOf(call) ?: return emptyList()
        return resolve(callee).flatMap { t ->
            when (t) {
                is KtFunction -> t.valueParameters.filter { it.name == name }
                is KtClass -> t.primaryConstructorParameters.filter { it.name == name }
                is PsiMethod -> t.parameterList.parameters.filter { it.name == name }
                else -> emptyList()
            }
        }
    }

    /** Keeps the overloads whose parameter count accepts the call's argument count (all if none does), then the best by argument types. */
    private fun filterOverloads(targets: List<PsiElement>, ref: KtNameReferenceExpression): List<PsiElement> {
        if (targets.size < 2 || ref.parent?.node?.elementType != N.CALL_EXPRESSION) return targets
        val call = ref.parent as KtElement
        val args = (call.child(N.VALUE_ARGUMENT_LIST)?.children.orEmpty().count { it.node.elementType == N.VALUE_ARGUMENT }) +
            call.childrenOf(N.LAMBDA_ARGUMENT).size
        val fitting = targets.filter { t ->
            when (t) {
                is KtFunction -> {
                    val ps = t.valueParameters
                    val required = ps.count { p -> p.child(KtTokens.EQ) == null } // no default value
                    args in required..ps.size || ps.any { it.hasModifier(KtTokens.VARARG_KEYWORD) }
                }
                is PsiMethod -> t.parameterList.parametersCount == args || t.isVarArgs ||
                    // extension facade functions take the receiver as first parameter
                    t.parameterList.parametersCount == args + 1
                else -> true
            }
        }
        return KtOverloads.byArgumentTypes(fitting.ifEmpty { targets }, call)
    }
}
