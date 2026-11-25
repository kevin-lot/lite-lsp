package me.kevinlot.lite.core

import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.TreeUtil

/**
 * Colors each bracket pair of a Lite language by nesting depth. Brackets are the tokens of the language's brace
 * matcher, so a bracket inside a string or a comment keeps its color. Colors: `colorSchemes/LiteRainbow*.xml`.
 */
class LiteRainbowAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile || element.textLength > MAX_LENGTH || !isLite(element)) return
        val pairs = LanguageBraceMatching.INSTANCE.forLanguage(element.language)?.pairs ?: return
        val opening = pairs.map { it.leftBraceType }.toSet()
        val closing = pairs.map { it.rightBraceType }.toSet()
        leaves(element.node).fold(0) { depth, leaf ->
            when (leaf.elementType) {
                in opening -> depth + 1
                in closing -> maxOf(depth - 1, 0)
                else -> return@fold depth
            }.also { next -> holder.rainbow(leaf, minOf(depth, next)) }
        }
    }

    private fun isLite(file: PsiFile): Boolean = file.language.javaClass.name.startsWith(LITE_PACKAGE)

    private fun leaves(root: ASTNode): Sequence<ASTNode> = generateSequence(TreeUtil.findFirstLeaf(root)) { TreeUtil.nextLeaf(it) }

    private fun AnnotationHolder.rainbow(leaf: ASTNode, depth: Int) =
        newSilentAnnotation(HighlightSeverity.INFORMATION).range(leaf.textRange).textAttributes(KEYS[depth % KEYS.size]).create()

    companion object {
        val KEYS: List<TextAttributesKey> = (1..5).map { TextAttributesKey.createTextAttributesKey("LITE_RAINBOW_$it") }
        private const val LITE_PACKAGE = "me.kevinlot.lite."
        private const val MAX_LENGTH = 500_000
    }
}
