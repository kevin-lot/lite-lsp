package me.kevinlot.lite.kotlin.types

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N
import me.kevinlot.lite.kotlin.psi.*
import me.kevinlot.lite.kotlin.resolve.KtFiles
import me.kevinlot.lite.kotlin.resolve.KtJava
import me.kevinlot.lite.kotlin.resolve.KtResolver

/** Small type model: a class (Kotlin or Java) with arguments, a type parameter, or unknown. */
sealed class KtTy {
    data class Cls(val decl: PsiElement, val args: List<KtTy> = emptyList()) : KtTy()
    data class Param(val param: PsiElement) : KtTy()
    data object Unknown : KtTy()
}

/** Type parameter (Kotlin or Java) -> type. */
typealias Subst = Map<PsiElement, KtTy>

object KtTypes {
    private val GUARD = RecursionGuard(48)
    private val TYPE_KEY = Key.create<CachedValue<KtTy>>("lite.kt.exprType")

    /** Lambda calls whose `it` is the receiver itself / an element of the receiver. */
    private val IT_IS_RECEIVER = setOf("let", "also", "takeIf", "takeUnless", "use")
    private val THIS_IS_RECEIVER = setOf("apply", "run", "with")
    private val IT_IS_ELEMENT = setOf(
        "map", "mapNotNull", "flatMap", "filter", "filterNot", "forEach", "onEach", "first", "firstOrNull", "last",
        "lastOrNull", "find", "findLast", "any", "all", "none", "count", "sortedBy", "sortedByDescending", "groupBy",
        "associateBy", "associateWith", "associate", "partition", "sumOf", "maxByOrNull", "minByOrNull", "maxBy", "minBy",
        "distinctBy", "takeWhile", "dropWhile", "single", "singleOrNull", "indexOfFirst", "indexOfLast", "filterTo", "mapTo",
        "firstNotNullOf", "firstNotNullOfOrNull", "mapNotNullTo", "flatMapTo", "groupingBy", "sumBy", "minOf", "maxOf",
        "minOfOrNull", "maxOfOrNull", "sortedWith", "chunked", "windowed", "zip", "forEachIndexed", "fold", "reduce",
    )

    // ---------------------------------------------------------------- type references

    fun typeOf(typeRef: PsiElement?, subst: Subst = emptyMap()): KtTy {
        typeRef ?: return KtTy.Unknown
        val inner = (typeRef as? KtElement)?.children?.firstOrNull { it.node.elementType in TYPE_NODES } ?: return KtTy.Unknown
        val user = if (inner.node.elementType == N.NULLABLE_TYPE) inner.children.firstOrNull { it.node.elementType in TYPE_NODES } else inner
        if (user?.node?.elementType == N.NULLABLE_TYPE) return typeOf(user.parent, subst)
        if (user?.node?.elementType != N.USER_TYPE) return KtTy.Unknown
        val name = user.children.lastOrNull { it is KtNameReferenceExpression } as? KtNameReferenceExpression ?: return KtTy.Unknown
        val args = (user as KtElement).child(N.TYPE_ARGUMENT_LIST)?.children.orEmpty()
            .filter { it.node.elementType == N.TYPE_PROJECTION }
            .map { typeOf((it as KtElement).child(N.TYPE_REFERENCE), subst) }
        return when (val target = KtResolver.resolve(name).firstOrNull()) {
            is KtTypeParameter -> subst[target] ?: KtTy.Param(target)
            is KtTypeAlias -> typeOf(target.typeReference, subst + target.typeParameters.zip(args).toMap())
            is KtClass, is KtObjectDeclaration -> KtTy.Cls(target, args)
            is PsiTypeParameter -> subst[target] ?: KtTy.Param(target)
            is PsiClass -> KtTy.Cls(target, args)
            else -> KtTy.Unknown
        }
    }

    fun fromPsiType(type: PsiType?, subst: Subst = emptyMap()): KtTy {
        val ct = type as? PsiClassType ?: return KtTy.Unknown
        return when (val cls = ct.resolve()) {
            is PsiTypeParameter -> subst[cls] ?: KtTy.Param(cls)
            null -> KtTy.Unknown
            else -> KtTy.Cls(cls, ct.parameters.map { fromPsiType(it, subst) })
        }
    }

    /** Type parameters of the class of [ty] -> its arguments (`List<String>`: E -> String). */
    fun substOf(ty: KtTy): Subst {
        val cls = ty as? KtTy.Cls ?: return emptyMap()
        val params: List<PsiElement> = when (val d = cls.decl) {
            is KtClass -> d.typeParameters
            is PsiClass -> d.typeParameters.toList()
            else -> emptyList()
        }
        return params.zip(cls.args).toMap()
    }

    /** Element type of collections, sequences, arrays and their Java counterparts. */
    fun elementType(ty: KtTy): KtTy {
        val cls = ty as? KtTy.Cls ?: return KtTy.Unknown
        if (cls.args.size == 1) return cls.args[0]
        // Map<K, V> iterates over Map.Entry<K, V>
        val entry = (cls.decl as? PsiClass)?.takeIf { it.qualifiedName?.endsWith("Map") == true }?.findInnerClassByName("Entry", true)
        return if (entry != null && cls.args.size == 2) KtTy.Cls(entry, cls.args) else KtTy.Unknown
    }

    // ---------------------------------------------------------------- expressions

    fun inferType(expr: PsiElement?): KtTy {
        expr ?: return KtTy.Unknown
        return KtFiles.cachedNonRecursive(expr, TYPE_KEY, KtTy.Unknown) { GUARD.run(KtTy.Unknown) { infer(expr) } }
    }

    private fun infer(e: PsiElement): KtTy = when (e.node.elementType) {
        N.REFERENCE_EXPRESSION -> valueType(KtResolver.resolve(e as KtNameReferenceExpression).firstOrNull(), receiverSubst(e))
        N.CALL_EXPRESSION -> callType(e as KtElement)
        N.DOT_QUALIFIED_EXPRESSION, N.SAFE_ACCESS_EXPRESSION -> inferType(e.children.lastOrNull())
        N.PARENTHESIZED, N.ANNOTATED_EXPRESSION, N.LABELED_EXPRESSION -> inferType(e.children.lastOrNull { it is KtElement })
        N.POSTFIX_EXPRESSION -> inferType(e.children.firstOrNull { it is KtElement })
        N.BINARY_WITH_TYPE -> typeOf((e as KtElement).child(N.TYPE_REFERENCE))
        N.THIS_EXPRESSION -> thisType(e)
        N.STRING_TEMPLATE -> KtJava.builtin(e, "String")?.let { KtTy.Cls(it) } ?: KtTy.Unknown
        N.IF -> inferType((e as KtElement).child(N.THEN)?.children?.firstOrNull())
        N.WHEN -> inferType((e as KtElement).child(N.WHEN_ENTRY)?.children?.lastOrNull { it is KtElement })
        N.TRY -> inferType((e as KtElement).child(N.BLOCK))
        N.BLOCK -> inferType(e.children.lastOrNull { it is KtElement && it !is KtNamedDeclaration })
        N.ARRAY_ACCESS_EXPRESSION -> inferType(e.children.firstOrNull { it is KtElement }).let { t ->
            val c = t as? KtTy.Cls
            if (c != null && c.args.size == 2) c.args[1] else elementType(t)
        }
        else -> KtTy.Unknown
    }

    /** Generic arguments of the receiver when [ref] is `receiver.ref`. */
    private fun receiverSubst(ref: PsiElement): Subst {
        val receiver = KtResolver.receiverOf(ref) ?: return emptyMap()
        return substOf(inferType(receiver))
    }

    private fun callType(call: KtElement): KtTy {
        val callee = call.children.firstOrNull() as? KtNameReferenceExpression ?: return KtTy.Unknown
        val subst = receiverSubst(call)
        return when (val target = KtResolver.resolve(callee).firstOrNull()) {
            is KtFunction -> returnType(target, subst)
            is KtClass -> KtTy.Cls(target, call.child(N.TYPE_ARGUMENT_LIST)?.children.orEmpty()
                .filter { it.node.elementType == N.TYPE_PROJECTION }.map { typeOf((it as KtElement).child(N.TYPE_REFERENCE)) })
            is PsiMethod -> if (target.isConstructor) target.containingClass?.let { KtTy.Cls(it) } ?: KtTy.Unknown
                else fromPsiType(target.returnType, subst + argumentSubst(target, call) + extensionSubst(target, call))
            is PsiClass -> KtTy.Cls(target)
            is KtParameter -> KtTy.Unknown // invoking a function-typed parameter
            else -> scopeFunctionResult(callee.referencedName, call)
        }
    }

    /**
     * `cards.toList()` compiles to `CollectionsKt.toList(Iterable<T>)`: binds `T` from the receiver type
     * (positional arguments), so the result is `List<Card>` rather than `List<T>`.
     */
    private fun extensionSubst(method: PsiMethod, call: KtElement): Subst {
        if (!method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) || method.typeParameters.isEmpty()) return emptyMap()
        val receiver = KtResolver.receiverOf(call) ?: return emptyMap()
        val receiverTy = inferType(receiver) as? KtTy.Cls ?: return emptyMap()
        val first = method.parameterList.parameters.firstOrNull()?.type as? PsiClassType ?: return emptyMap()
        val out = HashMap<PsiElement, KtTy>()
        val firstCls = first.resolve()
        if (firstCls is PsiTypeParameter) out[firstCls] = receiverTy
        first.parameters.zip(receiverTy.args).forEach { (p, a) ->
            ((p as? PsiClassType)?.resolve() as? PsiTypeParameter)?.let { out[it] = a }
            ((p as? com.intellij.psi.PsiWildcardType)?.bound as? PsiClassType)?.resolve()?.let { if (it is PsiTypeParameter) out[it] = a }
        }
        return out
    }

    /** `listOf(a, b)`, `setOf(x)`, `Pair(a, b)`: binds `T` from the arguments passed for `T` / `vararg T`. */
    private fun argumentSubst(method: PsiMethod, call: KtElement): Subst {
        if (method.typeParameters.isEmpty()) return emptyMap()
        val args = call.child(N.VALUE_ARGUMENT_LIST)?.children.orEmpty().filter { it.node.elementType == N.VALUE_ARGUMENT }
        val params = method.parameterList.parameters
        val offset = if (KtResolver.receiverOf(call) != null && method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) 1 else 0
        val out = HashMap<PsiElement, KtTy>()
        args.forEachIndexed { i, arg ->
            val p = params.getOrNull(i + offset) ?: params.lastOrNull()?.takeIf { it.isVarArgs } ?: return@forEachIndexed
            val type = (p.type as? com.intellij.psi.PsiArrayType)?.componentType ?: p.type
            val tp = (type as? PsiClassType)?.resolve() as? PsiTypeParameter ?: return@forEachIndexed
            if (tp !in out) inferType(arg.children.lastOrNull { it is KtElement }).takeIf { it != KtTy.Unknown }?.let { out[tp] = it }
        }
        return out
    }

    /** `x.let { … }` returns the lambda result, `x.apply { … }` / `also` return `x`. */
    private fun scopeFunctionResult(name: String?, call: KtElement): KtTy {
        val receiver = KtResolver.receiverOf(call) ?: return KtTy.Unknown
        return when (name) {
            "apply", "also" -> inferType(receiver)
            else -> KtTy.Unknown
        }
    }

    fun returnType(fn: KtFunction, subst: Subst = emptyMap()): KtTy {
        fn.returnTypeReference?.let { return typeOf(it, subst) }
        val body = fn.bodyExpression ?: return KtTy.Unknown
        return if (body.node.elementType == N.BLOCK) KtTy.Unknown else inferType(body)
    }

    /** Type of the value [target] denotes. */
    fun valueType(target: PsiElement?, subst: Subst = emptyMap()): KtTy = when (target) {
        is KtProperty -> target.typeReference?.let { typeOf(it, subst) }
            ?: target.initializer?.let { inferType(it) }
            ?: target.delegateExpression?.let { lazyValue(it) }
            ?: KtTy.Unknown
        is KtParameter -> target.typeReference?.let { typeOf(it, subst) } ?: lambdaParameterType(target)
        is KtDestructuringEntry -> target.typeReference?.let { typeOf(it, subst) } ?: destructuredType(target)
        is KtObjectDeclaration -> KtTy.Cls(target)
        is KtClass -> target.body?.childrenOf<KtObjectDeclaration>()?.firstOrNull { it.isCompanion }?.let { KtTy.Cls(it) } ?: KtTy.Cls(target)
        is KtEnumEntry -> PsiTreeUtil.getParentOfType(target, KtClass::class.java)?.let { KtTy.Cls(it) } ?: KtTy.Unknown
        is KtElement -> if (target.node.elementType == N.FUNCTION_LITERAL) itType(target) else KtTy.Unknown
        is PsiField -> fromPsiType(target.type, subst)
        is PsiMethod -> fromPsiType(target.returnType, subst)
        is PsiClass -> KtTy.Cls(target)
        else -> KtTy.Unknown
    }

    /**
     * `val (a, b) = expr`, `for ((k, v) in map)`, `{ (a, b) -> }`: the n-th component of the value —
     * a data class constructor property, `Pair`/`Triple` arguments, or a map entry key/value.
     */
    private fun destructuredType(entry: KtDestructuringEntry): KtTy {
        val decl = entry.parent as? KtElement ?: return KtTy.Unknown
        val index = decl.childrenOf<KtDestructuringEntry>().indexOf(entry)
        val holder = decl.parent
        val whole: KtTy = when {
            holder?.node?.elementType == N.FOR -> elementType(inferType((holder as KtElement).child(N.LOOP_RANGE)?.children?.firstOrNull()))

            holder is KtParameter -> lambdaParameterType(holder)
            else -> inferType(decl.children.lastOrNull { it is KtElement && it !is KtDestructuringEntry })
        }
        val cls = whole as? KtTy.Cls ?: return KtTy.Unknown
        return when (val d = cls.decl) {
            is KtClass -> d.primaryConstructorParameters.getOrNull(index)?.typeReference?.let { typeOf(it, substOf(cls)) } ?: KtTy.Unknown
            is PsiClass -> when (d.qualifiedName) {
                "kotlin.Pair", "kotlin.Triple", "java.util.Map.Entry" -> cls.args.getOrNull(index) ?: KtTy.Unknown
                else -> KtTy.Unknown
            }
            else -> KtTy.Unknown
        }
    }

    /** `val x by lazy { Foo() }` */
    private fun lazyValue(delegate: PsiElement): KtTy {
        val lambda = PsiTreeUtil.collectElements(delegate) { it.node?.elementType == N.FUNCTION_LITERAL }.firstOrNull()
        val block = (lambda as? KtElement)?.child(N.BLOCK) ?: return KtTy.Unknown
        return inferType(block)
    }

    // ---------------------------------------------------------------- lambdas, `it` and `this`

    /** Parameter of a lambda without explicit type: `list.map { x -> … }`. */
    private fun lambdaParameterType(param: KtParameter): KtTy {
        val literal = param.parent?.parent ?: return KtTy.Unknown
        if (literal.node.elementType != N.FUNCTION_LITERAL) {
            // `for (x in xs)`
            if (literal.node.elementType == N.FOR || param.parent?.node?.elementType == N.FOR) {
                val loop = if (param.parent?.node?.elementType == N.FOR) param.parent!! else literal
                return elementType(inferType((loop as KtElement).child(N.LOOP_RANGE)?.children?.firstOrNull()))
            }
            return KtTy.Unknown
        }
        val index = (param.parent as? KtElement)?.childrenOf<KtParameter>()?.indexOf(param) ?: 0
        return lambdaArgumentType(literal as KtElement, index)
    }

    /** `it` of a lambda without parameters. */
    fun itType(literal: KtElement): KtTy = lambdaArgumentType(literal, 0)

    private fun lambdaArgumentType(literal: KtElement, index: Int): KtTy {
        val call = callOfLambda(literal) ?: return KtTy.Unknown
        val callee = (call.children.firstOrNull() as? KtNameReferenceExpression) ?: return KtTy.Unknown
        val name = callee.referencedName
        val receiver = KtResolver.receiverOf(call)
        val receiverTy = receiver?.let { inferType(it) } ?: KtTy.Unknown
        if (index == 0 && name in IT_IS_RECEIVER) return receiverTy
        if (index == 0 && name in IT_IS_ELEMENT) return elementType(receiverTy)
        // Declared function: take the function-type parameter the lambda is passed to.
        val fn = KtResolver.resolve(callee).firstOrNull() as? KtFunction ?: return KtTy.Unknown
        val fnType = fn.valueParameters.lastOrNull()?.typeReference?.children?.firstOrNull { it.node.elementType == N.FUNCTION_TYPE }
            ?: return KtTy.Unknown
        val params = ((fnType as KtElement).child(N.VALUE_PARAMETER_LIST) as? KtElement)?.childrenOf<KtParameter>().orEmpty()
        return params.getOrNull(index)?.typeReference?.let { typeOf(it, substOf(receiverTy)) } ?: KtTy.Unknown
    }

    /** Implicit receiver inside `x.apply { }`, `with(x) { }`, `fun T.ext()` or a class body. */
    fun thisType(place: PsiElement): KtTy {
        var e: PsiElement? = place.parent
        while (e != null) {
            when {
                e.node.elementType == N.FUNCTION_LITERAL -> lambdaThis(e as KtElement)?.let { return it }
                e is KtFunction && e.receiverTypeReference != null -> return typeOf(e.receiverTypeReference)
                e is KtClass -> return KtTy.Cls(e, e.typeParameters.map { KtTy.Param(it) })
                e is KtObjectDeclaration -> return KtTy.Cls(e)
            }
            e = e.parent
        }
        return KtTy.Unknown
    }

    fun lambdaThis(literal: KtElement): KtTy? {
        val call = callOfLambda(literal) ?: return null
        val name = (call.children.firstOrNull() as? KtNameReferenceExpression)?.referencedName
        if (name == "with") {
            val firstArg = call.child(N.VALUE_ARGUMENT_LIST)?.children?.firstOrNull { it.node.elementType == N.VALUE_ARGUMENT }
            return inferType(firstArg?.children?.lastOrNull())
        }
        if (name in THIS_IS_RECEIVER) return KtResolver.receiverOf(call)?.let { inferType(it) }
        return null
    }

    /** CALL_EXPRESSION a lambda (FUNCTION_LITERAL) is an argument of. */
    fun callOfLambda(literal: PsiElement): KtElement? {
        var e: PsiElement? = literal.parent // LAMBDA_EXPRESSION
        while (e != null && e.node.elementType in LAMBDA_WRAPPERS) e = e.parent
        return e?.takeIf { it.node.elementType == N.CALL_EXPRESSION } as? KtElement
    }

    private val LAMBDA_WRAPPERS = com.intellij.psi.tree.TokenSet.create(
        N.LAMBDA_EXPRESSION, N.LAMBDA_ARGUMENT, N.VALUE_ARGUMENT, N.VALUE_ARGUMENT_LIST, N.LABELED_EXPRESSION, N.ANNOTATED_EXPRESSION,
    )
    private val TYPE_NODES = com.intellij.psi.tree.TokenSet.create(N.USER_TYPE, N.NULLABLE_TYPE, N.FUNCTION_TYPE, N.DYNAMIC_TYPE)
}
