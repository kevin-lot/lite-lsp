package me.kevinlot.lite.core

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException

fun PsiElement?.isA(type: IElementType): Boolean = this?.node?.elementType == type

/** Name tokens are renamed in place. */
fun renameLeaf(id: PsiElement?, name: String) { (id?.node as? LeafElement)?.replaceWithText(name) ?: throw IncorrectOperationException("No name") }

/** The result of the first lookup that finds something: lookups are ordered from the nearest scope to the farthest. */
fun firstFound(vararg lookups: () -> List<PsiElement>): List<PsiElement> =
    lookups.asSequence().map { it() }.firstOrNull { it.isNotEmpty() }.orEmpty()
