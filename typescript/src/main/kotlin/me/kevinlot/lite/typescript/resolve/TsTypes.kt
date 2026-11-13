package me.kevinlot.lite.typescript.resolve

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.typescript.psi.*
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as E

/** Type parameter (or owner class, for `this`) → type. */
typealias Env = Map<PsiElement, TsTy>

/** Just enough types to find members: declared, inferred from initializers, calls, `new`, `await`, callbacks. */
sealed class TsTy {
    /** Instance of a class / interface / enum. */
    data class Inst(val decl: PsiElement, val args: List<TsTy> = emptyList()) : TsTy()
    /** A class itself, an enum object, a namespace, a module (`import * as m`). */
    data class Static(val decl: PsiElement) : TsTy()
    /** Object type literal or object literal. */
    data class Obj(val node: PsiElement, val env: Env) : TsTy()
    /** Something callable: a function-like declaration, a function type, a call signature. */
    data class Fn(val sig: PsiElement, val env: Env, val self: TsTy? = null) : TsTy()
    data class Union(val parts: List<TsTy>) : TsTy()
    /** A type of the language embedding JavaScript (see [TsHostType]). */
    data class Host(val type: TsHostType) : TsTy()
    data object Unknown : TsTy()
}

object TsTypes {
    private val GUARD = RecursionGuard(40)
    private val EXPR = Key.create<CachedValue<TsTy>>("lite.ts.exprTy")
    private val DECL = Key.create<CachedValue<TsTy>>("lite.ts.declTy")
    private val PRIMITIVES = mapOf("string" to "String", "number" to "Number", "boolean" to "Boolean", "bigint" to "BigInt", "symbol" to "Symbol", "object" to "Object")
    private val ITERABLES = setOf(
        "Array", "ReadonlyArray", "Set", "ReadonlySet", "Iterable", "IterableIterator", "Iterator", "AsyncIterable", "AsyncIterableIterator",
        "Generator", "AsyncGenerator", "ArrayLike", "NodeListOf", "HTMLCollectionOf", "ArrayIterator", "SetIterator",
    )
    private val PROMISES = setOf("Promise", "PromiseLike")

    fun union(parts: List<TsTy>): TsTy {
        val flat = parts.flatMap { if (it is TsTy.Union) it.parts else listOf(it) }.filter { it != TsTy.Unknown }.distinct()
        return when (flat.size) { 0 -> TsTy.Unknown; 1 -> flat[0]; else -> TsTy.Union(flat) }
    }

    private fun types(node: PsiElement) = node.children.filter { it.node.elementType in E.TYPES }
    private fun global(name: String, place: PsiElement, args: List<TsTy> = emptyList()): TsTy =
        TsGlobals.type(name, place)?.let { TsTy.Inst(it, args) } ?: TsTy.Unknown

    // ---------------------------------------------------------------- type nodes

    fun tyOf(node: PsiElement?, env: Env): TsTy = GUARD.run(TsTy.Unknown) {
        node ?: return@run TsTy.Unknown
        when (node.node.elementType) {
            E.TYPE_REFERENCE -> typeReference(node as TsElement, env)
            E.ARRAY_TYPE -> global("Array", node, listOf(tyOf(types(node).firstOrNull(), env)))
            E.TUPLE_TYPE -> global("Array", node, listOf(union(types(node).map { tyOf(it, env) })))
            E.UNION_TYPE, E.INTERSECTION_TYPE -> union(types(node).map { tyOf(it, env) })
            E.PAREN_TYPE -> tyOf(types(node).firstOrNull(), env)
            E.OBJECT_TYPE -> TsTy.Obj(node, env)
            E.MAPPED_TYPE -> mapped(node as TsElement, env)
            E.FUNCTION_TYPE, E.CONSTRUCTOR_TYPE -> TsTy.Fn(node, env)
            E.TYPEOF_TYPE -> (node as TsElement).child(E.REFERENCE)?.let { exprTy(it) } ?: TsTy.Unknown
            E.TYPE_OPERATOR -> if (node.firstChild?.text == "keyof") global("String", node) else tyOf(types(node).firstOrNull(), env)
            E.INDEXED_ACCESS_TYPE -> indexedAccess(node, env)
            E.CONDITIONAL_TYPE -> types(node).let { t -> union(listOfNotNull(t.getOrNull(2), t.getOrNull(3)).map { tyOf(it, env) }) }
            E.THIS_TYPE -> owner(node)?.let { env[it] ?: TsTy.Inst(it) } ?: TsTy.Unknown
            E.LITERAL_TYPE -> literal(node.firstChild, node)
            E.TEMPLATE_TYPE -> global("String", node)
            E.TYPE_PREDICATE -> global("Boolean", node)
            E.IMPORT_TYPE -> importType(node as TsElement, env)
            else -> TsTy.Unknown
        }
    }

    private fun typeReference(node: TsElement, env: Env): TsTy {
        val ref = node.child(E.REFERENCE) as? TsReferenceExpr ?: return TsTy.Unknown
        val args = node.child(E.TYPE_ARGUMENT_LIST)?.let { types(it) }.orEmpty().map { tyOf(it, env) }
        if (ref.qualifier == null) PRIMITIVES[ref.referencedName]?.let { return global(it, node) }
        val target = TsResolver.resolve(ref).firstOrNull() ?: return TsTy.Unknown
        return typeOfDecl(target, args, env)
    }

    private fun typeOfDecl(target: PsiElement, args: List<TsTy>, env: Env): TsTy = when (target.node.elementType) {
        E.TYPE_PARAMETER -> env[target] ?: (target as TsElement).let { tp -> types(tp).firstOrNull()?.takeIf { tp.hasToken(T.kw("extends")) } }?.let { tyOf(it, env) } ?: TsTy.Unknown
        E.CLASS_DECLARATION, E.CLASS_EXPRESSION, E.INTERFACE_DECLARATION, E.ENUM_DECLARATION -> TsTy.Inst(target, args)
        E.TYPE_ALIAS -> {
            val alias = target as TsElement
            val params = alias.child(E.TYPE_PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>().orEmpty()
            tyOf(types(alias).lastOrNull(), env + bind(params, args, env))
        }
        E.ENUM_MEMBER -> TsTy.Inst(target.parent)
        E.NAMESPACE_DECLARATION, E.AMBIENT_MODULE -> TsTy.Static(target)
        else -> if (target is PsiFile) TsTy.Static(target) else TsTy.Unknown
    }

    /** Type parameters bound to arguments, then to their defaults. */
    private fun bind(params: List<TsNamedDecl>, args: List<TsTy>, env: Env): Env = params.withIndex().associate { (i, p) ->
        p as PsiElement to (args.getOrNull(i) ?: p.takeIf { it.hasToken(T.EQ) }?.let { tyOf(types(it).lastOrNull(), env) } ?: TsTy.Unknown)
    }

    /** `{ [K in keyof T]: … }` (Partial, Readonly, Required…): members of `T`. */
    private fun mapped(node: TsElement, env: Env): TsTy {
        val constraint = node.child(E.TYPE_PARAMETER)?.let { types(it).firstOrNull() } ?: return TsTy.Unknown
        if (constraint.node.elementType != E.TYPE_OPERATOR || constraint.firstChild?.text != "keyof") return TsTy.Unknown
        return tyOf(types(constraint).firstOrNull(), env)
    }

    private fun indexedAccess(node: PsiElement, env: Env): TsTy {
        val (objNode, indexNode) = types(node).let { (it.getOrNull(0) ?: return TsTy.Unknown) to (it.getOrNull(1) ?: return TsTy.Unknown) }
        val obj = tyOf(objNode, env)
        val key = indexNode.takeIf { it.node.elementType == E.LITERAL_TYPE && it.firstChild?.node?.elementType == T.STRING }?.text?.let { TsNamedDecl.unquote(it) }
        return if (key != null) members(obj, key).firstOrNull()?.let { memberTy(it.first, it.second) } ?: TsTy.Unknown else elementOf(obj)
    }

    private fun importType(node: TsElement, env: Env): TsTy {
        val modules = (node.child(E.MODULE_SPECIFIER) as? TsModuleSpecifier)?.let { TsModules.targets(it) }.orEmpty()
        val names = node.node.getChildren(null).dropWhile { it.elementType != T.RPAR }.filter { it.elementType == T.IDENTIFIER }.map { it.text }
        var targets: List<PsiElement> = modules
        for (n in names) targets = targets.flatMap { TsModules.exports(it, n) }
        val args = node.child(E.TYPE_ARGUMENT_LIST)?.let { types(it) }.orEmpty().map { tyOf(it, env) }
        return targets.firstOrNull()?.let { typeOfDecl(it, args, env) } ?: TsTy.Unknown
    }

    private fun literal(token: PsiElement?, place: PsiElement): TsTy = when (token?.node?.elementType) {
        T.STRING, T.TEMPLATE_FULL -> global("String", place)
        T.NUMBER -> global("Number", place)
        T.REGEX -> global("RegExp", place)
        T.kw("true"), T.kw("false") -> global("Boolean", place)
        else -> TsTy.Unknown
    }

    private fun owner(e: PsiElement): PsiElement? = PsiTreeUtil.findFirstParent(e, true) { it is TsClass }

    // ---------------------------------------------------------------- expressions

    fun exprTy(e: PsiElement?): TsTy {
        e ?: return TsTy.Unknown
        return GUARD.run(TsTy.Unknown) { LiteCache.cachedNonRecursive(e, EXPR, TsLibraryOracle, TsTy.Unknown) { computeExpr(e) } }
    }

    /** Type of the object a qualified name is read from (`a` in `a.b`). */
    fun qualifierTy(q: PsiElement): TsTy = exprTy(q)

    private fun operands(e: PsiElement) = e.children.filter { it is TsElement }

    private fun computeExpr(e: PsiElement): TsTy = when (e.node.elementType) {
        E.REFERENCE -> {
            val r = e as TsReferenceExpr
            val q = r.qualifier
            if (q != null) members(exprTy(q), r.referencedName ?: "").firstOrNull()?.let { memberTy(it.first, it.second) } ?: TsTy.Unknown
            // merged declarations (`function f` + `namespace f`): the value has the members of all of them
            else union(TsResolver.resolve(r).map { declTy(it) })
        }
        E.THIS_EXPRESSION -> thisTy(e)
        E.SUPER_EXPRESSION -> (owner(e) as? TsClass)?.let { supertypes(TsTy.Inst(it)).firstOrNull() } ?: TsTy.Unknown
        E.CALL_EXPRESSION -> callTy(e as TsElement)
        E.NEW_EXPRESSION -> newTy(e as TsElement)
        E.PAREN_EXPRESSION, E.NON_NULL_EXPRESSION -> exprTy(operands(e).firstOrNull())
        E.AS_EXPRESSION, E.TYPE_ASSERTION -> types(e).firstOrNull()?.let { tyOf(it, emptyMap()) } ?: exprTy(operands(e).firstOrNull())
        E.AWAIT_EXPRESSION -> awaited(exprTy(operands(e).firstOrNull()))
        E.LITERAL -> literal(e.firstChild, e)
        E.TEMPLATE_EXPRESSION -> global("String", e)
        E.ARRAY_LITERAL -> global("Array", e, listOf(union(operands(e).take(8).map { if (it.node.elementType == E.SPREAD) elementOf(exprTy(operands(it).firstOrNull())) else exprTy(it) })))
        E.OBJECT_LITERAL -> TsTy.Obj(e, emptyMap())
        E.ARROW_FUNCTION, E.FUNCTION_EXPRESSION -> TsTy.Fn(e, emptyMap())
        E.CLASS_EXPRESSION -> TsTy.Static(e)
        E.CONDITIONAL_EXPRESSION -> union(operands(e).drop(1).map { exprTy(it) })
        E.BINARY_EXPRESSION -> {
            val op = e.node.getChildren(null).firstOrNull { it.elementType == T.OPERATOR }?.text
            when (op) {
                "??", "||" -> union(operands(e).map { exprTy(it) })
                "&&" -> exprTy(operands(e).lastOrNull())
                else -> TsTy.Unknown
            }
        }
        E.ASSIGNMENT_EXPRESSION -> exprTy(operands(e).lastOrNull())
        E.INDEX_EXPRESSION -> {
            val (obj, index) = operands(e).let { it.getOrNull(0) to it.getOrNull(1) }
            val key = index?.takeIf { it.node.elementType == E.LITERAL && it.firstChild.node.elementType == T.STRING }?.let { TsNamedDecl.unquote(it.text) }
            val objTy = exprTy(obj)
            if (key != null) members(objTy, key).firstOrNull()?.let { memberTy(it.first, it.second) } ?: TsTy.Unknown else elementOf(objTy)
        }
        E.IMPORT_CALL -> ((e as TsElement).child(E.MODULE_SPECIFIER) as? TsModuleSpecifier)?.let { TsModules.targets(it).firstOrNull() }
            ?.let { global("Promise", e, listOf(TsTy.Static(it))) } ?: TsTy.Unknown
        else -> TsTy.Unknown
    }

    private fun thisTy(e: PsiElement): TsTy {
        var p = e.parent
        while (p != null && p !is PsiFile) {
            val t = p.node.elementType
            if (t == E.METHOD || t == E.PROPERTY || t == E.STATIC_BLOCK) {
                val holder = p.parent
                if (holder?.node?.elementType == E.OBJECT_LITERAL) return TsTy.Obj(holder, emptyMap())
                val cls = holder?.parent as? TsClass ?: return TsTy.Unknown
                return if (t == E.STATIC_BLOCK || (p as? TsNamedDecl)?.isStatic == true) TsTy.Static(cls) else TsTy.Inst(cls)
            }
            if (t == E.FUNCTION_DECLARATION || t == E.FUNCTION_EXPRESSION) return TsTy.Unknown
            p = p.parent
        }
        return TsTy.Unknown
    }

    private fun callTy(call: TsElement): TsTy {
        val callee = call.firstChild ?: return TsTy.Unknown
        // require("x")
        call.child(E.ARGUMENT_LIST)?.let { (it as TsElement).child(E.MODULE_SPECIFIER) as? TsModuleSpecifier }?.let { spec ->
            return TsModules.targets(spec).firstOrNull()?.let { TsTy.Static(it) } ?: TsTy.Unknown
        }
        val fn = signature(exprTy(callee), call) ?: return TsTy.Unknown
        return returnTy(fn, call)
    }

    private fun newTy(n: TsElement): TsTy {
        val callee = operands(n).firstOrNull { it.node.elementType != E.TYPE_ARGUMENT_LIST && it.node.elementType != E.ARGUMENT_LIST } ?: return TsTy.Unknown
        val args = n.child(E.TYPE_ARGUMENT_LIST)?.let { types(it) }.orEmpty().map { tyOf(it, emptyMap()) }
        // `new Date()`: the lib declares `interface Date` next to `declare var Date: DateConstructor`
        if (callee is TsReferenceExpr && callee.qualifier == null) {
            TsResolver.resolveName(callee.referencedName ?: "", callee, Space.TYPE).firstOrNull { it is TsClass }?.let { return TsTy.Inst(it, args) }
        }
        return when (val ct = exprTy(callee)) {
            is TsTy.Static -> if (ct.decl is TsClass) TsTy.Inst(ct.decl, args) else TsTy.Unknown
            is TsTy.Inst, is TsTy.Obj -> signatureMember(ct, E.CONSTRUCT_SIGNATURE)?.let { returnTy(it, n) } ?: TsTy.Unknown
            else -> TsTy.Unknown
        }
    }

    /** What calling [ty] at [call] runs: overload picked by argument count, type arguments bound. */
    private fun signature(ty: TsTy, call: TsElement): TsTy.Fn? {
        val fn = callable(ty) ?: return null
        val sig = overload(fn.sig, call.child(E.ARGUMENT_LIST)?.let { operands(it).size } ?: 0)
        val env = fn.env + bindCall(sig, call, fn.env) + listOfNotNull(fn.self?.let { s -> owner(sig)?.let { it to s } })
        return TsTy.Fn(sig, env, fn.self)
    }

    private fun callable(ty: TsTy): TsTy.Fn? = when (ty) {
        is TsTy.Fn -> ty
        is TsTy.Union -> ty.parts.firstNotNullOfOrNull { callable(it) }
        is TsTy.Inst, is TsTy.Obj -> signatureMember(ty, E.CALL_SIGNATURE)
        else -> null
    }

    private fun signatureMember(ty: TsTy, kind: com.intellij.psi.tree.IElementType): TsTy.Fn? {
        val body = when (ty) {
            is TsTy.Inst -> (ty.decl as? TsClass)?.body
            is TsTy.Obj -> ty.node
            else -> null
        } ?: return null
        return body.children.firstOrNull { it.node.elementType == kind }?.let { TsTy.Fn(it, envOf(ty)) }
    }

    private fun overload(sig: PsiElement, argc: Int): PsiElement {
        val name = (sig as? TsFunction)?.name ?: return sig
        val siblings = sig.parent?.children?.filter { it.node.elementType == sig.node.elementType && (it as TsNamedDecl).name == name }.orEmpty()
        if (siblings.size < 2) return sig
        return siblings.firstOrNull { s ->
            val ps = (s as TsFunction).parameters
            val required = ps.count { p -> !p.hasToken(T.QUEST) && !p.hasToken(T.EQ) && !p.hasToken(T.ELLIPSIS) }
            argc >= required && (argc <= ps.size || ps.any { it.hasToken(T.ELLIPSIS) })
        } ?: sig
    }

    private fun parametersOf(sig: PsiElement): List<TsNamedDecl> =
        (sig as? TsElement)?.child(E.PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>().orEmpty()

    private fun typeParametersOf(sig: PsiElement): List<TsNamedDecl> =
        (sig as? TsElement)?.child(E.TYPE_PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>().orEmpty()

    /** Explicit `f<T>()`, else `T` bound from arguments whose parameter is typed `T` or `T[]` (callbacks excluded). */
    private fun bindCall(sig: PsiElement, call: TsElement, env: Env): Env {
        val tps = typeParametersOf(sig)
        if (tps.isEmpty()) return emptyMap()
        call.child(E.TYPE_ARGUMENT_LIST)?.let { list -> return bind(tps, types(list).map { tyOf(it, env) }, env) }
        val args = call.child(E.ARGUMENT_LIST)?.let { operands(it) }.orEmpty()
        val out = HashMap<PsiElement, TsTy>()
        parametersOf(sig).forEachIndexed { i, p ->
            val arg = args.getOrNull(i) ?: return@forEachIndexed
            val ann = p.typeNode ?: return@forEachIndexed
            if (arg.node.elementType in E.FUNCTIONS) {
                // `map<U>(cb: (v: T) => U)`: U is what the callback returns
                val fnType = functionTypeIn(ann) ?: return@forEachIndexed
                val ret = types(fnType).lastOrNull() ?: return@forEachIndexed
                val tp = typeParameterIn(ret, tps) ?: return@forEachIndexed
                if (tp !in out) returnTy(TsTy.Fn(arg, emptyMap()), null).takeIf { it != TsTy.Unknown }?.let { out[tp] = awaited(it) }
                return@forEachIndexed
            }
            val (refNode, element) = when (ann.node.elementType) {
                E.TYPE_REFERENCE -> ann to false
                E.ARRAY_TYPE -> (types(ann).firstOrNull() ?: return@forEachIndexed) to true
                else -> return@forEachIndexed
            }
            val tp = typeParameterIn(refNode, tps)?.takeIf { refNode.node.elementType == E.TYPE_REFERENCE } ?: return@forEachIndexed
            if (tp !in out) out[tp] = exprTy(arg).let { if (element) elementOf(it) else it }
        }
        return bind(tps, tps.map { out[it] ?: TsTy.Unknown }, env).filterValues { it != TsTy.Unknown } + out
    }

    /** The function type inside `((x: T) => U) | undefined`. */
    private fun functionTypeIn(t: PsiElement): PsiElement? = when (t.node.elementType) {
        E.FUNCTION_TYPE -> t
        E.PAREN_TYPE, E.UNION_TYPE -> types(t).firstNotNullOfOrNull { functionTypeIn(it) }
        else -> null
    }

    /** One of [tps] named directly by [t] (or by a member of the union [t]). */
    private fun typeParameterIn(t: PsiElement, tps: List<TsNamedDecl>): PsiElement? = when (t.node.elementType) {
        E.TYPE_REFERENCE -> ((t as TsElement).child(E.REFERENCE) as? TsReferenceExpr)?.takeIf { it.qualifier == null }
            ?.let { r -> tps.firstOrNull { it.name == r.referencedName } }
        E.UNION_TYPE, E.PAREN_TYPE -> types(t).firstNotNullOfOrNull { typeParameterIn(it, tps) }
        else -> null
    }

    private fun returnTy(fn: TsTy.Fn, call: PsiElement?): TsTy {
        val sig = fn.sig
        val annotation = if (sig.node.elementType == E.FUNCTION_TYPE || sig.node.elementType == E.CONSTRUCTOR_TYPE) types(sig).lastOrNull() else (sig as? TsElement)?.typeNode
        if (annotation != null) return tyOf(annotation, fn.env)
        val f = sig as? TsFunction ?: return TsTy.Unknown
        val body = f.body ?: return TsTy.Unknown
        val inferred = if (body.node.elementType == E.BLOCK) firstReturn(body) else exprTy(body)
        return if (f.hasModifier("async")) global("Promise", sig, listOf(inferred)) else inferred
    }

    private fun firstReturn(block: PsiElement): TsTy {
        val returns = PsiTreeUtil.findChildrenOfType(block, TsElement::class.java).asSequence()
            .filter { it.type == E.RETURN_STATEMENT && PsiTreeUtil.findFirstParent(it, true) { p -> p.node?.elementType in E.FUNCTIONS } == block.parent }
        return returns.mapNotNull { r -> r.children.firstOrNull { it is TsElement } }.map { exprTy(it) }.firstOrNull { it != TsTy.Unknown } ?: TsTy.Unknown
    }

    fun awaited(t: TsTy): TsTy = when (t) {
        is TsTy.Inst -> if ((t.decl as? TsNamedDecl)?.name in PROMISES) awaited(t.args.firstOrNull() ?: TsTy.Unknown) else t
        is TsTy.Union -> union(t.parts.map { awaited(it) })
        else -> t
    }

    fun elementOf(t: TsTy): TsTy = when (t) {
        is TsTy.Inst -> when ((t.decl as? TsNamedDecl)?.name) {
            in ITERABLES -> t.args.firstOrNull() ?: TsTy.Unknown
            "String" -> t
            "Map", "ReadonlyMap" -> TsTy.Unknown
            else -> supertypes(t).firstNotNullOfOrNull { s -> elementOf(s).takeIf { it != TsTy.Unknown } } ?: TsTy.Unknown
        }
        is TsTy.Union -> union(t.parts.map { elementOf(it) })
        else -> TsTy.Unknown
    }

    // ---------------------------------------------------------------- declarations

    fun declTy(d: PsiElement?): TsTy {
        d ?: return TsTy.Unknown
        return GUARD.run(TsTy.Unknown) { LiteCache.cachedNonRecursive(d, DECL, TsLibraryOracle, TsTy.Unknown) { computeDecl(d) } }
    }

    private fun computeDecl(d: PsiElement): TsTy = if (d is TsHostDecl) d.hostType() else when (d.node.elementType) {
        E.VARIABLE -> variableTy(d as TsElement)
        E.BINDING -> bindingTy(d)
        E.PARAMETER -> parameterTy(d as TsNamedDecl)
        E.FUNCTION_DECLARATION, E.METHOD, E.FUNCTION_EXPRESSION, E.ARROW_FUNCTION -> TsTy.Fn(d, emptyMap())
        E.CLASS_DECLARATION, E.CLASS_EXPRESSION, E.ENUM_DECLARATION, E.NAMESPACE_DECLARATION, E.AMBIENT_MODULE -> TsTy.Static(d)
        E.PROPERTY, E.PROPERTY_ASSIGNMENT, E.SHORTHAND_PROPERTY, E.ENUM_MEMBER -> memberTy(d, TsTy.Unknown)
        E.REFERENCE -> d.parent?.takeIf { it.node.elementType == E.ASSIGNMENT_EXPRESSION }?.let { exprTy(operands(it).lastOrNull()) } ?: TsTy.Unknown
        E.IMPORT_SPECIFIER, E.IMPORT_DEFAULT, E.IMPORT_NAMESPACE, E.IMPORT_EQUALS -> declTy(TsResolver.follow(d).firstOrNull())
        else -> if (d is PsiFile) TsTy.Static(d) else TsTy.Unknown
    }

    private fun variableTy(v: TsElement): TsTy =
        v.typeNode?.let { tyOf(it, emptyMap()) } ?: forOfElement(v) ?: v.initializer?.let { exprTy(it) } ?: TsTy.Unknown

    /** `for (const x of xs)`: element of `xs`; `for (const k in o)`: a string. */
    private fun forOfElement(v: PsiElement): TsTy? {
        val loop = v.parent?.parent?.takeIf { v.parent.node.elementType == E.VAR_STATEMENT && it.node.elementType == E.FOR_STATEMENT } ?: return null
        val nodes = loop.node.getChildren(null)
        val of = nodes.indexOfFirst { it.elementType == T.SOFT_KEYWORD && it.text == "of" }
        if (of >= 0) return elementOf(exprTy(nodes.drop(of + 1).firstOrNull { it.psi is TsElement }?.psi))
        if (nodes.any { it.elementType == T.kw("in") }) return global("String", v)
        return null
    }

    private fun bindingTy(b: PsiElement): TsTy {
        val parent = b.parent ?: return TsTy.Unknown
        return when (parent.node.elementType) {
            E.OBJECT_PATTERN -> member(patternTy(parent), (b as TsNamedDecl).name)
            E.PATTERN_PROPERTY -> member(patternTy(parent.parent), (parent as TsElement).child(E.PROPERTY_KEY)?.text)
            E.ARRAY_PATTERN -> elementOf(patternTy(parent))
            else -> TsTy.Unknown
        }
    }

    private fun member(t: TsTy, name: String?): TsTy =
        name?.let { members(t, it).firstOrNull() }?.let { memberTy(it.first, it.second) } ?: TsTy.Unknown

    /** Type of the value a destructuring pattern takes apart. */
    fun patternTy(pattern: PsiElement): TsTy {
        val parent = pattern.parent ?: return TsTy.Unknown
        return when (parent.node.elementType) {
            E.VARIABLE -> variableTy(parent as TsElement)
            E.PARAMETER -> parameterTy(parent as TsNamedDecl)
            E.PATTERN_PROPERTY -> member(patternTy(parent.parent), (parent as TsElement).child(E.PROPERTY_KEY)?.text)
            E.ARRAY_PATTERN -> elementOf(patternTy(parent))
            else -> TsTy.Unknown
        }
    }

    private fun parameterTy(p: TsNamedDecl): TsTy =
        p.typeNode?.let { tyOf(it, emptyMap()) } ?: p.initializer?.let { exprTy(it) } ?: contextualParameterTy(p)

    /** `xs.map(x => x.name)`: the parameter type the callee expects for this callback. */
    private fun contextualParameterTy(p: TsNamedDecl): TsTy {
        val fn = p.parent?.parent as? TsFunction ?: return TsTy.Unknown
        val index = fn.parameters.indexOf(p)
        val expected = expectedFn(fn) ?: return TsTy.Unknown
        val param = parametersOf(expected.sig).let { ps -> ps.getOrNull(index) ?: ps.lastOrNull()?.takeIf { it.hasToken(T.ELLIPSIS) } } ?: return TsTy.Unknown
        return param.typeNode?.let { tyOf(it, expected.env) } ?: TsTy.Unknown
    }

    private fun expectedFn(fn: PsiElement): TsTy.Fn? = callable(expectedTy(fn) ?: return null)

    /** The type a value is expected to have from where it is written (argument, annotated variable, typed object). */
    private fun expectedTy(value: PsiElement): TsTy? {
        val parent = value.parent ?: return null
        return when (parent.node.elementType) {
            E.ARGUMENT_LIST -> {
                val call = parent.parent as? TsElement ?: return null
                val index = operands(parent).indexOf(value)
                val callee = operands(call).firstOrNull() ?: return null
                val fn = if (call.type == E.NEW_EXPRESSION) constructorOf(exprTy(callee)) else callable(exprTy(callee))
                fn ?: return null
                val sig = overload(fn.sig, operands(parent).size)
                val env = fn.env + bindCall(sig, call, fn.env) + listOfNotNull(fn.self?.let { s -> owner(sig)?.let { it to s } })
                val param = parametersOf(sig).let { ps -> ps.getOrNull(index) ?: ps.lastOrNull()?.takeIf { it.hasToken(T.ELLIPSIS) } } ?: return null
                param.typeNode?.let { tyOf(it, env) }
            }
            E.VARIABLE -> (parent as TsElement).typeNode?.let { tyOf(it, emptyMap()) }
            E.PROPERTY_ASSIGNMENT -> member(expectedTy(parent.parent) ?: return null, (parent as TsNamedDecl).name)
            E.PAREN_EXPRESSION -> expectedTy(parent)
            else -> null
        }
    }

    private fun constructorOf(ty: TsTy): TsTy.Fn? = when (ty) {
        is TsTy.Static -> (ty.decl as? TsClass)?.members?.firstOrNull { (it as? TsFunction)?.isConstructor == true }?.let { TsTy.Fn(it, emptyMap()) }
        is TsTy.Inst, is TsTy.Obj -> signatureMember(ty, E.CONSTRUCT_SIGNATURE)
        else -> null
    }

    // ---------------------------------------------------------------- members

    /** Members named [name] of a value of type [ty], each with the receiver type to evaluate it in. */
    fun members(ty: TsTy, name: String): List<Pair<PsiElement, TsTy>> = GUARD.run(emptyList()) {
        when (ty) {
            is TsTy.Inst -> instanceMembers(ty, name).ifEmpty {
                if ((ty.decl as? TsNamedDecl)?.name == "Object" || ty.decl !is TsClass) emptyList()
                else TsGlobals.type("Object", ty.decl)?.let { instanceMembers(TsTy.Inst(it), name) }.orEmpty()
            }
            is TsTy.Static -> staticMembers(ty, name)
            is TsTy.Obj -> objectMembers(ty, name)
            is TsTy.Fn -> TsGlobals.type("Function", ty.sig)?.let { instanceMembers(TsTy.Inst(it), name) }.orEmpty()
            is TsTy.Union -> ty.parts.flatMap { members(it, name) }
            is TsTy.Host -> ty.type.hostMembers(name).map { it to ty }
            TsTy.Unknown -> emptyList()
        }
    }

    private fun instanceMembers(ty: TsTy.Inst, name: String): List<Pair<PsiElement, TsTy>> {
        val cls = ty.decl as? TsClass ?: return emptyList()
        val own = merged(cls).flatMap { d -> ownMembers(d, name, static = false).map { it to TsTy.Inst(d, ty.args) } }
        if (own.isNotEmpty()) return own
        return supertypes(ty).flatMap { members(it, name) }
    }

    private fun ownMembers(d: TsClass, name: String, static: Boolean): List<PsiElement> {
        val declared = d.members.filter { m ->
            (m.node.elementType == E.PROPERTY || m.node.elementType == E.METHOD) && (m as TsNamedDecl).name == name && m.isStatic == static
        }
        if (declared.isNotEmpty() || static || d.isInterface) return declared
        val ctor = d.members.firstOrNull { (it as? TsFunction)?.isConstructor == true } as? TsFunction ?: return emptyList()
        // constructor(private readonly x: T): parameter properties
        ctor.parameters.firstOrNull { p -> p.name == name && PARAMETER_PROPERTY.any { p.hasModifier(it) } }?.let { return listOf(it) }
        // JavaScript: `this.x = …` in the constructor
        return PsiTreeUtil.findChildrenOfType(ctor.body, TsReferenceExpr::class.java).filter { r ->
            r.referencedName == name && r.qualifier?.node?.elementType == E.THIS_EXPRESSION &&
                r.parent?.node?.elementType == E.ASSIGNMENT_EXPRESSION && r.parent.firstChild == r
        }.take(1)
    }

    private val PARAMETER_PROPERTY = listOf("public", "private", "protected", "readonly")

    /** Declaration merging: same-named interfaces/classes of the same scope (and of the global scope). */
    private fun merged(cls: TsClass): List<TsClass> {
        val name = cls.name ?: return listOf(cls)
        val container = cls.parent ?: return listOf(cls)
        val local = TsScopes.declarations(container)[name].orEmpty().filterIsInstance<TsClass>()
        val isGlobal = container is PsiFile && !TsModules.isModule(container) || container.parent?.node?.elementType == E.GLOBAL_DECLARATION
        val global = if (isGlobal) TsGlobals.lookup(name, cls, Space.TYPE).filterIsInstance<TsClass>() else emptyList()
        return (listOf(cls) + local + global).distinct()
    }

    fun supertypes(ty: TsTy.Inst): List<TsTy> {
        val cls = ty.decl as? TsClass ?: return emptyList()
        val clause = cls.extendsClause as? TsElement ?: return emptyList()
        val env = envOf(ty)
        if (cls.isInterface) return types(clause).map { tyOf(it, env) }
        val expr = operands(clause).firstOrNull { it is TsReferenceExpr } as? TsReferenceExpr ?: return emptyList()
        val args = clause.child(E.TYPE_ARGUMENT_LIST)?.let { types(it) }.orEmpty().map { tyOf(it, env) }
        val base = TsResolver.resolve(expr).firstOrNull { it is TsClass }
            ?: expr.referencedName?.let { n -> TsResolver.resolveName(n, expr, Space.TYPE).firstOrNull { it is TsClass } }
        return listOfNotNull(base?.let { TsTy.Inst(it, args) })
    }

    private fun staticMembers(ty: TsTy.Static, name: String): List<Pair<PsiElement, TsTy>> {
        val d = ty.decl
        return when {
            d is TsClass -> ownMembers(d, name, static = true).map { it to ty as TsTy } +
                supertypes(TsTy.Inst(d)).filterIsInstance<TsTy.Inst>().flatMap { staticMembers(TsTy.Static(it.decl), name) } +
                namespacesNamed(d).flatMap { ns -> TsModules.exports(ns, name).map { it to TsTy.Static(ns) } }
            d.node.elementType == E.ENUM_DECLARATION -> d.children.filter { it.node.elementType == E.ENUM_MEMBER && (it as TsNamedDecl).name == name }.map { it to ty }
            d.node.elementType == E.NAMESPACE_DECLARATION -> (listOf(d) + namespacesNamed(d)).distinct().flatMap { ns -> TsModules.exports(ns, name).map { it to TsTy.Static(ns) } }
            else -> TsModules.exports(d, name).map { it to ty }
        }
    }

    private fun namespacesNamed(d: PsiElement): List<PsiElement> {
        val name = (d as? TsNamedDecl)?.name ?: return emptyList()
        return d.parent?.let { TsScopes.declarations(it)[name] }.orEmpty().filter { it.node.elementType == E.NAMESPACE_DECLARATION }
    }

    private fun objectMembers(ty: TsTy.Obj, name: String): List<Pair<PsiElement, TsTy>> {
        val out = mutableListOf<Pair<PsiElement, TsTy>>()
        for (m in ty.node.children) {
            when (m.node.elementType) {
                E.PROPERTY, E.METHOD, E.PROPERTY_ASSIGNMENT, E.SHORTHAND_PROPERTY -> if ((m as TsNamedDecl).name == name) out += m to ty
                E.SPREAD -> out += members(exprTy(operands(m).firstOrNull()), name)
            }
        }
        return out
    }

    private fun envOf(t: TsTy): Env = when (t) {
        is TsTy.Inst -> ((t.decl as? TsClass)?.typeParameters.orEmpty().withIndex().associate { (i, p) -> p as PsiElement to (t.args.getOrNull(i) ?: TsTy.Unknown) }
            .filterValues { it != TsTy.Unknown }) + (t.decl to t)
        is TsTy.Obj -> t.env
        is TsTy.Fn -> t.env
        else -> emptyMap()
    }

    /** Where a member is really declared: `{ helper }` is the variable/function `helper`. */
    fun declarationOf(member: PsiElement): PsiElement =
        (member as? TsShorthandProperty)?.reference?.resolve() ?: member

    /** Type of member [m] read on [receiver] (generic parameters of the receiver substituted). */
    fun memberTy(m: PsiElement, receiver: TsTy): TsTy {
        val env = envOf(receiver)
        return when (m.node.elementType) {
            E.PROPERTY, E.PARAMETER -> (m as TsElement).typeNode?.let { tyOf(it, env) } ?: m.initializer?.let { exprTy(it) } ?: TsTy.Unknown
            E.METHOD -> if ((m as TsFunction).isGetter) m.returnType?.let { tyOf(it, env) } ?: TsTy.Unknown else TsTy.Fn(m, env, receiver)
            E.PROPERTY_ASSIGNMENT -> exprTy(operands(m).lastOrNull())
            E.SHORTHAND_PROPERTY -> (m as TsShorthandProperty).reference?.resolve()?.let { declTy(it) } ?: TsTy.Unknown
            E.ENUM_MEMBER -> TsTy.Inst(m.parent)
            else -> declTy(m)
        }
    }
}
