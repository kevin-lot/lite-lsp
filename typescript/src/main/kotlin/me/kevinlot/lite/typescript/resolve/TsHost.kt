package me.kevinlot.lite.typescript.resolve

import com.intellij.psi.PsiElement

/*
 * Hooks for a language that embeds JavaScript (QML): the JavaScript resolver and types stay unaware of it,
 * the host plugs in its scopes, the types of its declarations and their members.
 */

/** A host element enclosing JavaScript: unqualified names not found in JavaScript scopes are looked up here. */
interface TsHostScope {
    fun hostLookup(name: String, place: PsiElement): List<PsiElement>
}

/** A host declaration used as a JavaScript value (an `id`, a property…): its type. */
interface TsHostDecl {
    fun hostType(): TsTy
}

/** A host type (an object type, a component…): its members, for `a.b` in JavaScript. */
interface TsHostType {
    fun hostMembers(name: String): List<PsiElement>
}
