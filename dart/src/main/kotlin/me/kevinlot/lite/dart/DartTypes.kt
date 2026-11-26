package me.kevinlot.lite.dart

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

/**
 * A class, mixin, enum or extension type with its type arguments: `List<Shape>` is `List` with `[Shape]`.
 * A record is its type as written (`(A, {B b})`) or its literal, with the type parameters known there in [scope].
 */
data class DartType(val declaration: PsiElement, val arguments: List<DartType?> = emptyList(), val scope: Map<PsiElement, DartType?> = emptyMap()) {
    val isRecord: Boolean get() = declaration.isA(T.RECORD) || declaration.isA(T.RECORD_TYPE)
    val name: String? get() = declaration.child(T.COMPONENT_NAME)?.text
}

/** Types of expressions, as far as they are written: declared types, constructor calls, return types, initializers. */
object DartTypes {
    private val GUARD = RecursionGuard()
    private val BINARY = setOf(T.ADDITIVE_EXPRESSION, T.BITWISE_EXPRESSION, T.MULTIPLICATIVE_EXPRESSION, T.SHIFT_EXPRESSION)
    private val BOOLEAN = setOf(T.COMPARE_EXPRESSION, T.IS_EXPRESSION, T.LOGIC_AND_EXPRESSION, T.LOGIC_OR_EXPRESSION)
    private val FUTURES = setOf("Future", "FutureOr")
    private val OPERATORS = setOf(T.ADDITIVE_OPERATOR, T.BITWISE_OPERATOR, T.MULTIPLICATIVE_OPERATOR, T.SHIFT_OPERATOR)
    private val RETURNING = setOf(
        T.FUNCTION_DECLARATION_WITH_BODY, T.FUNCTION_DECLARATION_WITH_BODY_OR_NATIVE, T.GETTER_DECLARATION, T.METHOD_DECLARATION,
    )

    fun of(expression: PsiElement?): DartType? = GUARD.run(null) { expression?.let(::compute) }

    private fun compute(e: PsiElement): DartType? = when (e.elementType) {
        T.REFERENCE_EXPRESSION, T.CALL_EXPRESSION -> named(e)
        T.NEW_EXPRESSION, T.CONST_OBJECT_EXPRESSION -> created(e)
        T.THIS_EXPRESSION -> DartResolver.enclosingType(e)?.let { DartType(it.declaration, parameters(it.declaration)) }
        T.SUPER_EXPRESSION -> DartResolver.enclosingType(e)?.let(::supertypes)?.firstOrNull()
        T.AWAIT_EXPRESSION -> of(e.lastChild)?.let { future -> future.arguments.firstOrNull().takeIf { future.name in FUTURES } ?: future }
        T.AS_EXPRESSION -> resolve(e.child(T.TYPE), emptyMap())
        T.PARENTHESIZED_EXPRESSION, T.SUFFIX_EXPRESSION, T.VALUE_EXPRESSION, T.IF_NULL_EXPRESSION -> of(e.children.firstOrNull())
        T.TERNARY_EXPRESSION -> of(e.children.getOrNull(1))
        T.ARRAY_ACCESS_EXPRESSION -> of(indexed(e))?.let { operator(it, "[]", 1) ?: it.arguments.lastOrNull() }
        T.ASSIGN_EXPRESSION -> of(e.lastChild)
        in BINARY -> binary(e)
        in BOOLEAN -> core(e, "bool")
        T.PREFIX_EXPRESSION -> core(e, "bool").takeIf { e.firstChild?.text == "!" } ?: of(e.lastChild)
        T.SET_OR_MAP_LITERAL_EXPRESSION -> braces(e)
        T.SWITCH_EXPRESSION -> of(e.child(T.SWITCH_EXPRESSION_CASE)?.lastChild)
        T.STRING_LITERAL_EXPRESSION -> core(e, "String")
        T.LIST_LITERAL_EXPRESSION -> core(e, "List")?.copy(arguments = listOf(elementType(e)))
        T.LITERAL_EXPRESSION -> literal(e)
        else -> null
    }

    /** `a`, `a.b`, `f()`, `a.f()`, `A()`, `A.named()`: the type the last name gives. */
    private fun named(e: PsiElement): DartType? {
        val call = e.isA(T.CALL_EXPRESSION)
        val ref = DartResolver.lastName(e.takeIf { !call } ?: e.firstChild) ?: return null
        // a setter and its getter share a name: the first target that has a type gives it
        return DartResolver.targets(ref).firstNotNullOfOrNull { (target, owner) -> typeOf(target, owner, ref, call) }
    }

    private fun typeOf(target: PsiElement, owner: DartType?, ref: DartRef, call: Boolean): DartType? {
        val declaration = target.parent ?: return null
        val expression = generateSequence<PsiElement>(ref) { it.parent.takeIf { p -> p.isA(T.REFERENCE_EXPRESSION) } }.last().parent.takeIf { call }
        val callee = expression?.let(DartCalls::callee)
        val given = callee?.let { DartCalls.inferred(expression, it) }.orEmpty()
        if (declaration.elementType in DartResolver.CLASSES || (call && isConstructor(declaration))) {
            val owning = PsiTreeUtil.findFirstParent(declaration, false) { it.elementType in DartResolver.CLASSES } ?: return null
            val read = DartResolver.typeParameters(owning).map(given::get).takeIf { arguments -> arguments.any { it != null } }.orEmpty()
            return DartType(owning, explicit(ref).ifEmpty { read })
        }
        if (call && declaration.elementType !in RETURNING) return DartCalls.called(target, owner)
        return declared(target, owner, given)
    }

    /** `a[i]`, or the target of the cascade for `a..[i]`. */
    private fun indexed(access: PsiElement): PsiElement? {
        val first = access.firstChild?.takeIf { it.firstChild != null }
        return first ?: PsiTreeUtil.findFirstParent(access) { it.isA(T.CASCADE_REFERENCE_EXPRESSION) }?.parent?.firstChild
    }

    /** `a + b` gives what `operator +` of the type of `a` returns; `int + int` is an `int`, not the `num` that `num` declares. */
    private fun binary(e: PsiElement): DartType? {
        val operands = e.children.filter { it.elementType !in OPERATORS }
        val left = of(operands.firstOrNull()) ?: return null
        val result = operator(left, e.children.firstOrNull { it.elementType in OPERATORS }?.text ?: return null, 1)
        if (result?.name != "num") return result
        val right = of(operands.getOrNull(1))
        return listOf(left, right).firstOrNull { it?.name == "double" } ?: left.takeIf { it.name == "int" && right?.name == "int" } ?: result
    }

    /** What the operator [sign] of [type] returns, the one taking [count] operands: `-a` and `a - b` share a name. */
    private fun operator(type: DartType, sign: String, count: Int): DartType? =
        DartResolver.members(type, sign).filter { DartCalls.parameters(it.first.parent).size == count }
            .firstNotNullOfOrNull { (member, owner) -> declared(member, owner) }

    /** `{a}` is a `Set`, `{k: v}` and `{}` a `Map`, unless the type arguments say otherwise. */
    private fun braces(e: PsiElement): DartType? {
        val written = arguments(e.child(T.TYPE_ARGUMENTS), emptyMap())
        val first = e.child(T.ELEMENT)?.firstChild
        val entry = first?.takeIf { it.isA(T.MAP_ENTRY) }
        val set = written.size == 1 || (written.isEmpty() && first != null && entry == null)
        if (set) return core(e, "Set")?.copy(arguments = written.ifEmpty { listOf(of(first)) })
        return core(e, "Map")?.copy(arguments = written.ifEmpty { listOfNotNull(entry).flatMap { listOf(of(it.firstChild), of(it.lastChild)) } })
    }

    private fun isConstructor(declaration: PsiElement): Boolean =
        declaration.isA(T.NAMED_CONSTRUCTOR_DECLARATION) || declaration.isA(T.FACTORY_CONSTRUCTOR_DECLARATION)

    /** `List<int>.filled(…)`, `Box<Shape>()`: the type arguments written after the name. */
    private fun explicit(ref: DartRef): List<DartType?> =
        generateSequence<PsiElement>(ref) { it.parent.takeIf { p -> p.isA(T.REFERENCE_EXPRESSION) } }.last().nextSibling
            ?.takeIf { it.isA(T.TYPE_ARGUMENTS) }.let { arguments(it, emptyMap()) }

    private fun created(e: PsiElement): DartType? {
        e.child(T.TYPE)?.let { return resolve(it, emptyMap()) }
        val name = e.children.firstOrNull { it is DartRef } ?: return null
        val named = DartResolver.lastName(name)?.let(DartResolver::resolve)?.firstOrNull()?.parent
        val target = named?.takeIf { it.elementType in DartResolver.CLASSES } ?: return null
        return DartType(target, arguments(e.child(T.TYPE_ARGUMENTS), emptyMap()))
    }

    /** The type of what [name] declares, [owner] being the type it is a member of. */
    fun declared(name: PsiElement, owner: DartType?, given: Map<PsiElement, DartType?> = emptyMap()): DartType? {
        val declaration = name.parent ?: return null
        val bindings = bindings(owner) + given
        return when (declaration.elementType) {
            T.VAR_ACCESS_DECLARATION -> resolve(declaration.child(T.TYPE), bindings) ?: initializer(declaration)
            T.VAR_DECLARATION_LIST_PART ->
                resolve(declaration.parent?.child(T.VAR_ACCESS_DECLARATION)?.child(T.TYPE), bindings) ?: of(declaration.child(T.VAR_INIT)?.lastChild)
            T.SIMPLE_FORMAL_PARAMETER -> resolve(declaration.child(T.TYPE), bindings) ?: DartCalls.closureParameter(declaration)
            T.VARIABLE_PATTERN -> resolve(declaration.child(T.TYPE), bindings) ?: DartPatterns.matched(declaration)
            T.CONSTANT_PATTERN -> DartPatterns.matched(declaration)
            T.RECORD_FIELD -> of(declaration.lastChild)
            T.RECORD_TYPE_FIELD, T.RECORD_TYPE_NAMED_FIELD -> resolve(declaration.child(T.TYPE), bindings)
            in RETURNING -> resolve(declaration.child(T.RETURN_TYPE)?.child(T.TYPE), bindings)
            T.ENUM_CONSTANT_DECLARATION -> declaration.parent?.let { DartType(it) }
            T.CATCH_PART -> resolve(declaration.parent?.child(T.TYPE), bindings).takeIf { declaration.child(T.COMPONENT_NAME) == name }
            T.FOR_IN_PART -> iterated(declaration)
            else -> null
        }
    }

    /** `var x = …;` has the type of its value; the variable of `for (final x in xs)` the element type of `xs`. */
    private fun initializer(variable: PsiElement): DartType? {
        val owner = variable.parent ?: return null
        if (owner.isA(T.FOR_IN_PART)) return iterated(owner)
        return of(owner.child(T.VAR_INIT)?.lastChild)
    }

    private fun iterated(loop: PsiElement): DartType? = of(loop.lastChild)?.arguments?.firstOrNull()

    private fun elementType(list: PsiElement): DartType? =
        arguments(list.child(T.TYPE_ARGUMENTS), emptyMap()).firstOrNull() ?: of(list.child(T.ELEMENT)?.firstChild)

    private fun literal(e: PsiElement): DartType? = when (e.firstChild?.elementType) {
        T.FALSE, T.TRUE -> core(e, "bool")
        T.HEX_NUMBER -> core(e, "int")
        T.NUMBER -> core(e, "double".takeIf { '.' in e.text } ?: "int")
        T.RECORD -> e.firstChild?.let { DartType(it) }
        else -> null
    }

    /** A class of `dart:core`, when the SDK is known. */
    fun core(place: PsiElement, name: String): DartType? {
        val file = place.containingFile as? DartFile ?: return null
        return DartLibraries.resolveUri(file, "dart:core")?.let { DartLibraries.exported(it, name) }?.firstOrNull()?.parent?.let { DartType(it) }
    }

    // ---------------------------------------------------------------- written types

    /** The type a `type` node writes; [bindings] are the type parameters known from the receiver (`E` of a `List<Shape>`). */
    fun resolve(type: PsiElement?, bindings: Map<PsiElement, DartType?>): DartType? {
        type?.child(T.RECORD_TYPE)?.let { return DartType(it, scope = bindings) }
        val simple = type?.child(T.SIMPLE_TYPE) ?: type?.takeIf { it.isA(T.SIMPLE_TYPE) } ?: return null
        val target = DartResolver.lastName(simple.firstChild)?.let(DartResolver::resolve)?.firstOrNull() ?: return null
        val declaration = target.parent ?: return null
        return when (declaration.elementType) {
            T.TYPE_PARAMETER -> bindings[target]
            T.FUNCTION_TYPE_ALIAS -> GUARD.run(null) { resolve(declaration.child(T.TYPE), emptyMap()) }
            in DartResolver.CLASSES -> DartType(declaration, arguments(simple.child(T.TYPE_ARGUMENTS), bindings))
            else -> null
        }
    }

    /** The types a `<…>` list writes. */
    fun arguments(list: PsiElement?, bindings: Map<PsiElement, DartType?>): List<DartType?> =
        list?.child(T.TYPE_LIST)?.childrenOf(T.TYPE).orEmpty().map { resolve(it, bindings) }

    /** The type parameters of [type]'s declaration with the arguments [type] gives them. */
    fun bindings(type: DartType?): Map<PsiElement, DartType?> =
        type?.let { it.scope + DartResolver.typeParameters(it.declaration).mapIndexed { index, parameter -> parameter to it.arguments.getOrNull(index) } }
            .orEmpty()

    /** [type] seen as an instance of [declaration], one of its supertypes: a `List<Shape>` as an `Iterable` is `Iterable<Shape>`. */
    fun asInstanceOf(type: DartType, declaration: PsiElement?, seen: MutableSet<PsiElement> = HashSet()): DartType? {
        if (type.declaration == declaration) return type
        if (!seen.add(type.declaration)) return null
        return supertypes(type).firstNotNullOfOrNull { asInstanceOf(it, declaration, seen) }
    }

    /** Inside a class, its type parameters stand for themselves. */
    private fun parameters(declaration: PsiElement): List<DartType?> = DartResolver.typeParameters(declaration).map { null }

    /** The superclass, mixins and interfaces of [type] (`on` types of a mixin), else `Object`. */
    fun supertypes(type: DartType): List<DartType> {
        val bindings = bindings(type)
        val declaration = type.declaration
        val application = declaration.child(T.MIXIN_APPLICATION)
        val lists = listOf(T.MIXINS, T.INTERFACES, T.ON_MIXINS).flatMap { listOfNotNull(declaration.child(it), application?.child(it)) }
        val written = listOfNotNull(declaration.child(T.SUPERCLASS)?.child(T.TYPE)) + application?.childrenOf(T.TYPE).orEmpty() +
            lists.flatMap { it.child(T.TYPE_LIST)?.childrenOf(T.TYPE).orEmpty() }
        val found = written.mapNotNull { resolve(it, bindings) }
        val root = "Enum".takeIf { declaration.isA(T.ENUM_DEFINITION) } ?: "Object"
        return found.ifEmpty { listOfNotNull(core(declaration, root)?.takeIf { it.declaration != declaration }) }
    }
}
