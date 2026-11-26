package me.kevinlot.lite.dart

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LazyParseablePsiElement
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

/** What a call runs: a function, method or constructor, and the type it is a member of. */
class DartCallee(val function: PsiElement, val owner: DartType?)

/** A function type: the types of its parameters and of its result, as written, with the type arguments known around them. */
class DartSignature(val parameters: List<PsiElement?>, val result: PsiElement?, val bindings: Map<PsiElement, DartType?>)

/**
 * Calls: which function an argument list is given to, which parameter an argument fills, and what that tells about types.
 * The parameters of a closure come from the function type its argument expects (`xs.map((e) => …)`), and the type parameters
 * of a generic function from its arguments (`T` of `map<T>` from what the closure returns).
 */
object DartCalls {
    private val FUNCTION_TYPES = setOf(T.TYPED_FUNCTION_TYPE, T.UNTYPED_FUNCTION_TYPE)
    private val FUTURES = setOf("Future", "FutureOr")

    // ---------------------------------------------------------------- callees and parameters

    /** The function the arguments of [call] are given to. */
    fun callee(call: PsiElement): DartCallee? = when (call.elementType) {
        T.CALL_EXPRESSION, T.METADATA -> DartResolver.lastName(call.firstChild?.let { first -> first.takeIf { it is DartRef } ?: call.children.firstOrNull() })
            ?.let(DartResolver::targets)?.firstNotNullOfOrNull { (target, owner) -> callable(target, owner) }
        T.CONST_OBJECT_EXPRESSION, T.NEW_EXPRESSION -> DartTypes.of(call)?.let { type ->
            val named = call.children.lastOrNull { it is DartRef && it.prevSibling.isA(T.DOT) }?.text
            DartResolver.constructor(type.declaration, named)?.let { DartCallee(it, type) }
        }
        T.REDIRECTION, T.SUPER_CALL_OR_FIELD_INITIALIZER -> {
            val owner = DartResolver.enclosingType(call)
            val target = owner?.takeIf { call.isA(T.REDIRECTION) || call.child(T.THIS_EXPRESSION) != null } ?: owner?.let(DartTypes::supertypes)?.firstOrNull()
            target?.let { type -> DartResolver.constructor(type.declaration, call.children.firstOrNull { it is DartRef }?.text)?.let { DartCallee(it, type) } }
        }
        else -> null
    }

    /** What a call of [target] runs: a function, or the unnamed constructor of a class. */
    private fun callable(target: PsiElement, owner: DartType?): DartCallee? {
        val declaration = target.parent ?: return null
        return when (declaration.elementType) {
            in DartResolver.CLASSES -> DartResolver.constructor(declaration, null)?.let { DartCallee(it, DartType(declaration)) }
            in DartResolver.CONSTRUCTORS -> DartCallee(declaration, DartResolver.enclosingType(declaration))
            in DartResolver.FUNCTIONS -> DartCallee(declaration, owner)
            else -> null
        }
    }

    /** The names of the parameters of [function]: a `componentName`, or the reference of `this.x` / `super.x`. */
    fun parameters(function: PsiElement): List<PsiElement> {
        val list = function.child(T.FORMAL_PARAMETER_LIST) ?: return emptyList()
        val optional = list.child(T.OPTIONAL_FORMAL_PARAMETERS)?.childrenOf(T.DEFAULT_FORMAL_NAMED_PARAMETER).orEmpty()
            .mapNotNull { it.child(T.NORMAL_FORMAL_PARAMETER) }
        return (list.childrenOf(T.NORMAL_FORMAL_PARAMETER) + optional).mapNotNull { parameter ->
            val form = parameter.firstChild ?: return@mapNotNull null
            form.child(T.COMPONENT_NAME) ?: form.takeIf { it.isA(T.FIELD_FORMAL_PARAMETER) }?.children?.lastOrNull { it is DartRef }
        }
    }

    /** The arguments of [call]: each expression, with its name when it has one. */
    private fun arguments(call: PsiElement): List<Pair<String?, PsiElement>> =
        call.child(T.ARGUMENTS)?.child(T.ARGUMENT_LIST)?.children.orEmpty().mapNotNull { argument ->
            when {
                argument.isA(T.NAMED_ARGUMENT) -> argument.lastChild?.let { argument.firstChild?.text to it }
                else -> null to argument
            }
        }

    /** The parameter of [function] each argument of [call] fills: by name, else by position. */
    private fun filled(call: PsiElement, function: PsiElement): List<Pair<PsiElement, PsiElement>> {
        val parameters = parameters(function)
        val positional = arguments(call).filter { it.first == null }.map { it.second }
        return arguments(call).mapNotNull { (name, argument) ->
            val parameter = name?.let { parameters.firstOrNull { p -> p.text == it } }
                ?: parameters.getOrNull(positional.indexOf(argument)).takeIf { name == null }
            parameter?.let { it to argument }
        }
    }

    /** The type written on the parameter [argument] fills. */
    fun parameterType(argument: PsiElement): DartType? {
        val call = PsiTreeUtil.findFirstParent(argument) { it.isA(T.ARGUMENTS) }?.parent ?: return null
        val callee = callee(call) ?: return null
        val parameter = filled(call, callee.function).firstOrNull { it.second == argument }?.first ?: return null
        return DartTypes.resolve(typeOf(parameter), DartTypes.bindings(callee.owner))
    }

    // ---------------------------------------------------------------- function types

    /** The function type of [parameter] (the name of a parameter): written on it, behind a `typedef`, or that of the field of `this.x`. */
    private fun signature(parameter: PsiElement, bindings: Map<PsiElement, DartType?>): DartSignature? {
        val form = parameter.parent ?: return null
        return when (form.elementType) {
            T.FUNCTION_FORMAL_PARAMETER -> declared(form, bindings)
            T.SIMPLE_FORMAL_PARAMETER, T.VAR_ACCESS_DECLARATION -> signatureOf(form.child(T.TYPE), bindings)
            T.FIELD_FORMAL_PARAMETER -> (parameter as? DartRef)?.let(DartResolver::resolve)?.firstOrNull()?.let { signature(it, bindings) }
            else -> null
        }
    }

    /** `R name(A a)`: a function-typed parameter, or a `typedef` in its older form. */
    private fun declared(owner: PsiElement, bindings: Map<PsiElement, DartType?>): DartSignature =
        DartSignature(parameters(owner).map { it.parent?.child(T.TYPE) }, owner.child(T.RETURN_TYPE)?.child(T.TYPE), bindings)

    /** The function type a `type` node writes: `R Function(A)`, or a name a `typedef` gives one. */
    fun signatureOf(type: PsiElement?, bindings: Map<PsiElement, DartType?>): DartSignature? {
        val function = type?.children?.firstOrNull { it.elementType in FUNCTION_TYPES }
        if (function != null) {
            val parameters = function.child(T.PARAMETER_TYPE_LIST)?.childrenOf(T.NORMAL_PARAMETER_TYPE).orEmpty().map { it.child(T.TYPE) }
            return DartSignature(parameters, function.children.firstOrNull { it.isA(T.SIMPLE_TYPE) }, bindings)
        }
        val simple = type?.child(T.SIMPLE_TYPE) ?: return null
        val named = DartResolver.lastName(simple.firstChild)?.let(DartResolver::resolve)?.firstOrNull()?.parent
        val alias = named?.takeIf { it.isA(T.FUNCTION_TYPE_ALIAS) } ?: return null
        val given = DartTypes.arguments(simple.child(T.TYPE_ARGUMENTS), bindings)
        val own = DartResolver.typeParameters(alias).mapIndexed { index, parameter -> parameter to given.getOrNull(index) }.toMap()
        return alias.child(T.TYPE)?.let { signatureOf(it, own) } ?: declared(alias, own).takeIf { alias.child(T.FORMAL_PARAMETER_LIST) != null }
    }

    // ---------------------------------------------------------------- closures

    /** The type of the parameter [form] of a closure, when it writes none: that of the function type the closure is given to. */
    fun closureParameter(form: PsiElement): DartType? {
        val closure = PsiTreeUtil.findFirstParent(form) { it.isA(T.FORMAL_PARAMETER_LIST) }?.parent?.takeIf { it.isA(T.FUNCTION_EXPRESSION) } ?: return null
        val index = parameters(closure).indexOfFirst { it.parent == form }
        val expected = expected(closure) ?: return null
        return DartTypes.resolve(expected.parameters.getOrNull(index), expected.bindings)
    }

    /**
     * The function type [closure] must have: that of the parameter it is an argument of, of the variable it initializes or is
     * assigned to, of the result of its function, or of the elements of the typed collection it is in.
     */
    private fun expected(closure: PsiElement): DartSignature? {
        val holder = closure.parent ?: return null
        return when (holder.elementType) {
            T.ARGUMENT_LIST, T.NAMED_ARGUMENT -> argument(closure)
            T.ASSIGN_EXPRESSION -> DartResolver.lastName(holder.firstChild.takeIf { it != closure })?.let(DartResolver::targets)?.firstOrNull()
                ?.let { (target, owner) -> signature(target, DartTypes.bindings(owner)) }
            T.ELEMENT, T.MAP_ENTRY -> element(holder)
            T.FUNCTION_BODY, T.RETURN_STATEMENT -> signatureOf(DartTypes.returnType(holder), emptyMap())
            T.IF_NULL_EXPRESSION, T.PARENTHESIZED_EXPRESSION, T.TERNARY_EXPRESSION -> expected(holder)
            T.VAR_INIT -> holder.parent?.child(T.VAR_ACCESS_DECLARATION)?.let { signatureOf(it.child(T.TYPE), emptyMap()) }
            else -> null
        }
    }

    private fun argument(closure: PsiElement): DartSignature? {
        val call = PsiTreeUtil.findFirstParent(closure) { it.isA(T.ARGUMENTS) }?.parent ?: return null
        val callee = callee(call) ?: return null
        val parameter = filled(call, callee.function).firstOrNull { it.second == closure }?.first ?: return null
        return signature(parameter, DartTypes.bindings(callee.owner) + inferred(call, callee, closures = false))
    }

    /** `<void Function(A)>[(a) => …]`, `<String, void Function(A)>{'k': (a) => …}`: the last type argument of the literal. */
    private fun element(holder: PsiElement): DartSignature? {
        val literal = PsiTreeUtil.findFirstParent(holder) { it.isA(T.LIST_LITERAL_EXPRESSION) || it.isA(T.SET_OR_MAP_LITERAL_EXPRESSION) }
        return signatureOf(literal?.child(T.TYPE_ARGUMENTS)?.child(T.TYPE_LIST)?.childrenOf(T.TYPE)?.lastOrNull(), emptyMap())
    }

    /** What a closure returns: the expression after `=>`, or the first `return` of its block; a `Future` of it when it is `async`. */
    private fun result(closure: PsiElement): DartType? {
        val body = closure.child(T.FUNCTION_EXPRESSION_BODY) ?: return null
        val returned = body.children.firstOrNull { !it.isA(T.BLOCK) && it !is LazyParseablePsiElement }
            ?: PsiTreeUtil.collectElements(body) { it.isA(T.RETURN_STATEMENT) }.firstOrNull()?.children?.lastOrNull()
        val type = DartTypes.of(returned)
        if (body.child(T.ASYNC) == null || type?.name in FUTURES) return type
        return DartTypes.core(closure, "Future")?.copy(arguments = listOf(type)) ?: type
    }

    // ---------------------------------------------------------------- type parameters

    /**
     * The type arguments of a call of [callee]: written (`f<int>()`), else read from the arguments. Those of a constructor are
     * the type parameters of its class. [closures] off leaves the closures out: their own parameters are typed from the rest.
     */
    fun inferred(call: PsiElement, callee: DartCallee, closures: Boolean = true): Map<PsiElement, DartType?> {
        val constructor = callee.function.elementType in DartResolver.CONSTRUCTORS || isUnnamedConstructor(callee.function)
        val open = DartResolver.typeParameters(callee.owner?.declaration?.takeIf { constructor } ?: callee.function)
        if (open.isEmpty()) return emptyMap()
        val written = DartTypes.arguments(call.child(T.TYPE_ARGUMENTS), emptyMap())
        if (written.isNotEmpty()) return open.mapIndexed { index, parameter -> parameter to written.getOrNull(index) }.toMap()
        val known = HashMap<PsiElement, DartType?>()
        val base = DartTypes.bindings(callee.owner).filterKeys { it !in open }
        val (functions, values) = filled(call, callee.function).partition { it.second.isA(T.FUNCTION_EXPRESSION) }
        values.forEach { (parameter, argument) -> unify(typeOf(parameter), DartTypes.of(argument), known, open) }
        if (closures) functions.forEach { (parameter, closure) -> unify(signature(parameter, base + known)?.result, result(closure), known, open) }
        return known
    }

    private fun isUnnamedConstructor(function: PsiElement): Boolean =
        function.isA(T.METHOD_DECLARATION) && function.child(T.COMPONENT_NAME)?.text == DartResolver.enclosingType(function)?.name

    /** The type written on a parameter; for `this.x`, on the field. */
    private fun typeOf(parameter: PsiElement): PsiElement? {
        val form = parameter.parent ?: return null
        if (!form.isA(T.FIELD_FORMAL_PARAMETER)) return form.child(T.TYPE)
        return (parameter as? DartRef)?.let(DartResolver::resolve)?.firstOrNull()?.parent?.child(T.TYPE)
    }

    /** Binds the [open] type parameters [written] names to what [actual] holds there: `List<T>` against a `List<Shape>` gives `T`. */
    fun unify(written: PsiElement?, actual: DartType?, known: MutableMap<PsiElement, DartType?>, open: List<PsiElement>) {
        actual ?: return
        val simple = written?.child(T.SIMPLE_TYPE) ?: written?.takeIf { it.isA(T.SIMPLE_TYPE) } ?: return
        val target = DartResolver.lastName(simple.firstChild)?.let(DartResolver::resolve)?.firstOrNull() ?: return
        if (target in open) { known.putIfAbsent(target, actual); return }
        val arguments = simple.child(T.TYPE_ARGUMENTS)?.child(T.TYPE_LIST)?.childrenOf(T.TYPE).orEmpty()
        if (arguments.isEmpty()) return
        // `FutureOr<R>` takes an `R` as well as a `Future<R>`
        if (target.text == "FutureOr") return unify(arguments.first(), actual.arguments.firstOrNull().takeIf { actual.name in FUTURES } ?: actual, known, open)
        val instance = DartTypes.asInstanceOf(actual, target.parent) ?: return
        arguments.forEachIndexed { index, argument -> unify(argument, instance.arguments.getOrNull(index), known, open) }
    }

    /** What calling [target] gives when it is a variable: the result of its function type, or of the closure it holds. */
    fun called(target: PsiElement, owner: DartType?): DartType? {
        val declaration = target.parent ?: return null
        val type = declaration.child(T.TYPE)
        val written = signatureOf(type, DartTypes.bindings(owner))
        if (written != null) return DartTypes.resolve(written.result, written.bindings)
        val value = declaration.parent?.child(T.VAR_INIT)?.lastChild?.takeIf { declaration.isA(T.VAR_ACCESS_DECLARATION) && it.isA(T.FUNCTION_EXPRESSION) }
        return value?.let(::result)
    }
}
