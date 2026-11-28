package me.kevinlot.lite.kotlin.resolve

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import me.kevinlot.lite.kotlin.psi.KtElement
import me.kevinlot.lite.kotlin.psi.KtFunction
import me.kevinlot.lite.kotlin.types.KtTy
import me.kevinlot.lite.kotlin.types.KtTypes
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N

/**
 * Overloads by argument types: among the candidates that take the call's argument count, keeps those whose parameters accept
 * every argument whose type is known, and of these the most specific (an exact class beats a supertype). Positional and named
 * arguments; `vararg`; Java primitives as their boxes; an integer literal also fits `Long`, `Short` and `Byte`, as in Kotlin.
 * An argument or a parameter of unknown or generic type fits anything.
 */
internal object KtOverloads {
    private const val EXACT = 2
    private const val SUBTYPE = 1
    private const val ANY = 0
    private val NARROWER_INTEGERS = setOf("java.lang.Long", "java.lang.Short", "java.lang.Byte")

    private class Argument(val name: String?, val expression: PsiElement?) {
        val type: KtTy by lazy { KtTypes.inferType(expression) }
        val isIntLiteral: Boolean get() = expression?.node?.elementType == N.INTEGER_CONSTANT && type != KtTy.Unknown
    }

    fun byArgumentTypes(candidates: List<PsiElement>, call: KtElement): List<PsiElement> {
        if (candidates.size < 2) return candidates
        val arguments = call.child(N.VALUE_ARGUMENT_LIST)?.children.orEmpty().filter { it.node.elementType == N.VALUE_ARGUMENT }.map { arg ->
            val name = (arg as KtElement).child(N.VALUE_ARGUMENT_NAME)?.text
            Argument(name, arg.children.lastOrNull { it is KtElement && it.node.elementType != N.VALUE_ARGUMENT_NAME })
        }
        if (arguments.isEmpty()) return candidates
        val scored = candidates.mapNotNull { c -> score(c, arguments, call)?.let { c to it } }
        if (scored.isEmpty()) return candidates
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.map { it.first }
    }

    /** The sum of how well each argument fits, or null when one does not fit. */
    private fun score(candidate: PsiElement, arguments: List<Argument>, call: KtElement): Int? = when (candidate) {
        is KtFunction -> {
            val params = candidate.valueParameters
            val vararg = params.indexOfFirst { it.hasModifier(KtTokens.VARARG_KEYWORD) }
            arguments.withIndex().sumOfOrNull { (i, arg) ->
                val param = when {
                    arg.name != null -> params.firstOrNull { it.name == arg.name } ?: return@sumOfOrNull null
                    vararg in 0..i -> params[vararg]
                    else -> params.getOrNull(i) ?: return@sumOfOrNull null
                }
                fits(arg, KtTypes.typeOf(param.typeReference), call)
            }
        }
        is PsiMethod -> {
            val params = candidate.parameterList.parameters
            // a compiled extension (`StringsKt.padStart(String, Int)`) takes its receiver first
            val offset = if (candidate.hasModifierProperty(PsiModifier.STATIC) && KtResolver.receiverOf(call) != null &&
                params.size == arguments.size + 1 && !candidate.isVarArgs) 1 else 0
            arguments.withIndex().sumOfOrNull { (i, arg) ->
                if (arg.name != null) return@sumOfOrNull ANY // Java parameter names are not part of the call
                val param = params.getOrNull(i + offset) ?: params.lastOrNull()?.takeIf { candidate.isVarArgs } ?: return@sumOfOrNull null
                val type = param.type.let { if (candidate.isVarArgs && param == params.last() && it is PsiArrayType) it.componentType else it }
                fits(arg, javaType(type, call), call)
            }
        }
        else -> ANY
    }

    private fun javaType(type: PsiType, place: PsiElement): KtTy = when (type) {
        is PsiPrimitiveType -> type.boxedTypeName?.let { KtJava.findClass(place, it) }?.let { KtTy.Cls(it) } ?: KtTy.Unknown
        else -> KtTypes.fromPsiType(type)
    }

    private fun fits(arg: Argument, param: KtTy, place: PsiElement): Int? {
        val expected = param as? KtTy.Cls ?: return ANY
        val actual = arg.type as? KtTy.Cls ?: return ANY
        if (actual.decl == expected.decl) return EXACT
        val kotlin = HashSet<PsiElement>()
        val java = HashSet<String>()
        KtResolver.supertypes(actual, kotlin, java)
        val expectedName = (expected.decl as? PsiClass)?.qualifiedName
        if (expected.decl in kotlin || expectedName in java) return SUBTYPE
        if (arg.isIntLiteral && expectedName in NARROWER_INTEGERS) return ANY
        // what the model cannot see (a Kotlin class extending a library one it cannot read) is not a mismatch
        if (kotlin.any { hasUnknownSupertype(it) }) return ANY
        return null
    }

    private fun hasUnknownSupertype(decl: PsiElement): Boolean {
        val refs = (decl as? me.kevinlot.lite.kotlin.psi.KtClass)?.superTypeReferences
            ?: (decl as? me.kevinlot.lite.kotlin.psi.KtObjectDeclaration)?.superTypeReferences ?: return false
        return refs.any { KtTypes.typeOf(it) == KtTy.Unknown }
    }

    private inline fun <T> Iterable<T>.sumOfOrNull(selector: (T) -> Int?): Int? {
        var sum = 0
        for (item in this) sum += selector(item) ?: return null
        return sum
    }
}
