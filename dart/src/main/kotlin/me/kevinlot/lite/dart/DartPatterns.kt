package me.kevinlot.lite.dart

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

/** Records and patterns: the fields of a record, and the type of the value a pattern is matched against. */
object DartPatterns {
    private val WRAPPING = setOf(T.LOGICAL_AND_PATTERN, T.LOGICAL_OR_PATTERN, T.PARENTHESIZED_PATTERN, T.UNARY_PATTERN)

    /** The field [name] of [record], `$1` being its first positional one: its name, else its type or its value. */
    fun recordField(record: DartType, name: String): PsiElement? {
        val node = record.declaration
        val index = name.removePrefix("$").toIntOrNull()?.takeIf { name.startsWith("$") }
        if (node.isA(T.RECORD_TYPE)) {
            if (index != null) return node.childrenOf(T.RECORD_TYPE_FIELD).getOrNull(index - 1)?.child(T.TYPE)
            return node.child(T.RECORD_TYPE_NAMED_FIELDS)?.childrenOf(T.RECORD_TYPE_NAMED_FIELD).orEmpty().map { it.lastChild }.firstOrNull { it?.text == name }
        }
        if (!node.isA(T.RECORD)) return null
        val (named, positional) = node.childrenOf(T.RECORD_FIELD).partition { it.child(T.COLON) != null }
        if (index != null) return positional.getOrNull(index - 1)?.lastChild
        return named.map { it.firstChild }.firstOrNull { it?.text == name }
    }

    /** The type of the value [pattern] is matched against: the value of the declaration, loop, `switch` or `case` it is in. */
    fun matched(pattern: PsiElement): DartType? {
        val holder = pattern.parent ?: return null
        return when (holder.elementType) {
            T.FOR_IN_PART -> DartTypes.of(holder.lastChild)?.arguments?.firstOrNull()
            T.IF_ELEMENT, T.IF_STATEMENT -> DartTypes.of(holder.children.firstOrNull())
            T.LIST_PATTERN_ELEMENT -> matched(holder.parent ?: return null)?.arguments?.firstOrNull()
            T.MAP_PATTERN_ENTRY -> matched(holder.parent ?: return null)?.arguments?.getOrNull(1)
            T.PATTERN_FIELD -> target(holder)?.let { (member, owner) -> DartTypes.declared(member, owner) }
            T.PATTERN_VARIABLE_DECLARATION -> DartTypes.of(holder.lastChild)
            T.SWITCH_CASE, T.SWITCH_EXPRESSION_CASE -> DartTypes.of(holder.parent?.children?.firstOrNull())
            in WRAPPING -> matched(holder)
            else -> null
        }
    }

    /** What [field] of a record or object pattern reads: `n: var x` and `:n` read `n`, a bare `var x` the next positional field. */
    fun target(field: PsiElement): Pair<PsiElement, DartType?>? {
        val container = field.parent ?: return null
        val named = field.child(T.COLON) != null
        val name = ((field.firstChild as? DartRef) ?: PsiTreeUtil.findChildOfType(field, DartName::class.java))?.text.takeIf { named }
        if (container.isA(T.OBJECT_PATTERN)) return objectType(container)?.let { type -> DartResolver.members(type, name ?: return null).firstOrNull() }
        val record = matched(container)?.takeIf { container.isA(T.RECORD_PATTERN) } ?: return null
        val key = name ?: "$${container.childrenOf(T.PATTERN_FIELD).filter { it.child(T.COLON) == null }.indexOf(field) + 1}"
        return recordField(record, key)?.let { it to record }
    }

    /** `Circle(:radius)` matched against a `Shape`: a `Circle`, with the type arguments the value gives when it is one already. */
    private fun objectType(pattern: PsiElement): DartType? {
        val named = DartResolver.lastName(pattern.firstChild)?.let(DartResolver::resolve)?.firstOrNull()?.parent
        val declaration = named?.takeIf { it.elementType in DartResolver.CLASSES } ?: return null
        return matched(pattern)?.let { DartTypes.asInstanceOf(it, declaration) }
            ?: DartType(declaration, DartTypes.arguments(pattern.child(T.TYPE_ARGUMENTS), emptyMap()))
    }
}
