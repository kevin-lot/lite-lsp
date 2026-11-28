package me.kevinlot.lite.kotlin.types

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import me.kevinlot.lite.kotlin.psi.KtClass
import me.kevinlot.lite.kotlin.psi.KtDestructuringEntry
import me.kevinlot.lite.kotlin.psi.KtElement
import me.kevinlot.lite.kotlin.psi.KtFunction
import me.kevinlot.lite.kotlin.psi.KtNameReferenceExpression
import me.kevinlot.lite.kotlin.psi.KtObjectDeclaration
import me.kevinlot.lite.kotlin.psi.KtParameter
import me.kevinlot.lite.kotlin.psi.KtProperty
import me.kevinlot.lite.kotlin.resolve.KtResolver
import me.kevinlot.lite.kotlin.upstream.lexer.KtTokens
import me.kevinlot.lite.kotlin.upstream.parser.KtNodeTypes as N

/**
 * Smart casts: the type an `is` check gives a stable value (parameter, `val`, local `var`, `it`, destructured entry) where the
 * check holds. `if (x is T) x.m()`, `if (x !is T) return` then `x.m()`, `x is T && x.m()`, `x !is T || x.m()`,
 * `when (x) { is T -> x.m() }`, `when { x is T -> … }`, `require(x is T)` / `check(x is T)`, and the `else` of a negated check.
 * Not modelled: assignments that invalidate a cast, several `is` conditions of one `when` entry, null checks (types here are
 * never nullable).
 */
object KtSmartCasts {
    private val CONTRACTS = setOf("require", "check", "assert")
    private val JUMPS = setOf(N.RETURN, N.THROW, N.BREAK, N.CONTINUE)

    /** The type [ref], which resolves to [target], has from the checks that hold where it is written; null without any. */
    fun castOf(ref: KtNameReferenceExpression, target: PsiElement?): KtTy? {
        if (target == null || !stable(target)) return null
        var prev: PsiElement = ref
        var cur: PsiElement? = ref.parent
        while (cur != null && cur !is PsiFile) {
            when (cur.node.elementType) {
                N.THEN -> ifOf(cur)?.let { positive(condition(it), target) }?.let { return it }
                N.ELSE -> ifOf(cur)?.let { negative(condition(it), target) }?.let { return it }
                N.BINARY_EXPRESSION -> binaryCast(cur, prev, target)?.let { return it }
                N.WHEN_ENTRY -> entryCast(cur, prev, target)?.let { return it }
                N.BLOCK -> earlierChecks(cur, prev, target)?.let { return it }
            }
            // a cast outlives neither the function nor the class the value is used in
            if (cur is KtFunction || cur is KtClass || cur is KtObjectDeclaration) return null
            prev = cur
            cur = cur.parent
        }
        return null
    }

    /** Values a cast sticks to: not a member `var`, which another thread may change between the check and the use. */
    private fun stable(target: PsiElement): Boolean = when (target) {
        is KtParameter -> target.node.findChildByType(KtTokens.VAR_KEYWORD) == null // `class H(var x: T)` is a member `var`
        is KtDestructuringEntry -> true
        is KtProperty -> target.isLocal || target.node.findChildByType(KtTokens.VAR_KEYWORD) == null
        is KtElement -> target.node.elementType == N.FUNCTION_LITERAL // `it`
        else -> false
    }

    private fun ifOf(branch: PsiElement): KtElement? = branch.parent?.takeIf { it.node.elementType == N.IF } as? KtElement
    private fun condition(ifExpr: KtElement): PsiElement? = (ifExpr.child(N.CONDITION) as? KtElement)?.children?.firstOrNull { it is KtElement }

    private fun operands(e: PsiElement): List<PsiElement> = e.children.filter { it is KtElement }
    private fun operator(e: PsiElement): String? = e.children.firstOrNull { it.node.elementType == N.OPERATION_REFERENCE }?.text

    /** `x is T && x.m()`, `x !is T || x.m()`: the right operand sees the left one hold (or fail). */
    private fun binaryCast(binary: PsiElement, prev: PsiElement, target: PsiElement): KtTy? {
        val parts = operands(binary)
        if (parts.size != 3 || parts[2] != prev) return null
        return when (operator(binary)) {
            "&&" -> positive(parts[0], target)
            "||" -> negative(parts[0], target)
            else -> null
        }
    }

    /** The body of a `when` entry with one condition: `is T` on a subject that is [target], or a check of [target]. */
    private fun entryCast(entry: PsiElement, prev: PsiElement, target: PsiElement): KtTy? {
        val conditions = entry.children.filter { it.node.elementType == N.WHEN_CONDITION_IS_PATTERN || it.node.elementType == N.WHEN_CONDITION_EXPRESSION }
        if (prev in conditions || conditions.size != 1) return null
        val condition = conditions.single() as KtElement
        if (condition.node.elementType == N.WHEN_CONDITION_EXPRESSION) return positive(condition.children.firstOrNull { it is KtElement }, target)
        if (condition.node.findChildByType(KtTokens.NOT_IS) != null) return null
        val subject = entry.parent?.children?.firstOrNull { it is KtElement && it.node.elementType != N.WHEN_ENTRY } ?: return null
        if (subject != target && !refersTo(subject, target)) return null
        return KtTypes.typeOf(condition.child(N.TYPE_REFERENCE)).known()
    }

    /** Statements before [prev] in [block]: `if (x !is T) return`, `if (x is T) … else return`, `require(x is T)`. */
    private fun earlierChecks(block: PsiElement, prev: PsiElement, target: PsiElement): KtTy? {
        val statements = block.children.filter { it is KtElement }
        val index = statements.indexOf(prev).takeIf { it >= 0 } ?: return null
        for (i in index - 1 downTo 0) {
            val s = statements[i] as KtElement
            when (s.node.elementType) {
                N.IF -> {
                    val then = s.child(N.THEN)
                    val otherwise = s.child(N.ELSE)
                    if (jumps(then) && !jumps(otherwise)) negative(condition(s), target)?.let { return it }
                    if (otherwise != null && jumps(otherwise) && !jumps(then)) positive(condition(s), target)?.let { return it }
                }
                N.CALL_EXPRESSION -> {
                    val name = (s.firstChild as? KtNameReferenceExpression)?.referencedName
                    if (name in CONTRACTS) {
                        val argument = s.child(N.VALUE_ARGUMENT_LIST)?.children?.firstOrNull { it.node.elementType == N.VALUE_ARGUMENT }
                        positive(argument?.children?.lastOrNull { it is KtElement }, target)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** A branch that always leaves: `return`, `throw`, `break`, `continue`, or a block ending with one. */
    private fun jumps(branch: PsiElement?): Boolean {
        val e = when (branch?.node?.elementType) {
            N.THEN, N.ELSE -> branch.children.firstOrNull { it is KtElement }
            else -> branch
        } ?: return false
        return when (e.node.elementType) {
            in JUMPS -> true
            N.BLOCK -> jumps(e.children.lastOrNull { it is KtElement })
            else -> false
        }
    }

    /** The type [condition] gives [target] when it is true. */
    private fun positive(condition: PsiElement?, target: PsiElement): KtTy? {
        condition ?: return null
        return when (condition.node.elementType) {
            N.IS_EXPRESSION -> isCheck(condition, target, negated = false)
            N.BINARY_EXPRESSION -> operands(condition).takeIf { operator(condition) == "&&" && it.size == 3 }
                ?.let { positive(it[0], target) ?: positive(it[2], target) }
            N.PARENTHESIZED -> positive(operands(condition).firstOrNull(), target)
            N.PREFIX_EXPRESSION -> operands(condition).takeIf { operator(condition) == "!" }?.lastOrNull()?.let { negative(it, target) }
            else -> null
        }
    }

    /** The type [condition] gives [target] when it is false. */
    private fun negative(condition: PsiElement?, target: PsiElement): KtTy? {
        condition ?: return null
        return when (condition.node.elementType) {
            N.IS_EXPRESSION -> isCheck(condition, target, negated = true)
            N.BINARY_EXPRESSION -> operands(condition).takeIf { operator(condition) == "||" && it.size == 3 }
                ?.let { negative(it[0], target) ?: negative(it[2], target) }
            N.PARENTHESIZED -> negative(operands(condition).firstOrNull(), target)
            N.PREFIX_EXPRESSION -> operands(condition).takeIf { operator(condition) == "!" }?.lastOrNull()?.let { positive(it, target) }
            else -> null
        }
    }

    /** `x is T` (or `x !is T` when [negated]) on [target]. */
    private fun isCheck(check: PsiElement, target: PsiElement, negated: Boolean): KtTy? {
        val parts = operands(check)
        if (parts.size != 3 || (operator(check) == "!is") != negated || !refersTo(parts[0], target)) return null
        return KtTypes.typeOf(parts[2]).known()
    }

    private fun refersTo(e: PsiElement, target: PsiElement): Boolean {
        val ref = e as? KtNameReferenceExpression ?: return false
        if (ref.referencedName != (target as? KtElement)?.let(::nameOf)) return false
        return KtResolver.resolve(ref).firstOrNull() == target
    }

    private fun nameOf(target: KtElement): String? = when (target.node.elementType) {
        N.FUNCTION_LITERAL -> "it"
        else -> (target as? me.kevinlot.lite.kotlin.psi.KtNamedDeclaration)?.name
    }

    private fun KtTy.known(): KtTy? = takeUnless { it == KtTy.Unknown }
}
