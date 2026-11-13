package me.kevinlot.lite.core

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet

/** Find usages / rename candidates come from the identifier word index built with the language lexer. */
abstract class LiteFindUsagesProvider(
    private val lexer: () -> Lexer,
    private val identifiers: TokenSet,
    private val comments: TokenSet,
    private val literals: TokenSet,
) : FindUsagesProvider {
    /** Kind shown in usage views ("function", "class"…). */
    abstract fun kindOf(element: PsiElement): String
    abstract fun isDeclaration(element: PsiElement): Boolean

    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(lexer(), identifiers, comments, literals)
    override fun canFindUsagesFor(element: PsiElement): Boolean = isDeclaration(element)
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement): String = kindOf(element)
    override fun getDescriptiveName(element: PsiElement): String = (element as? PsiNamedElement)?.name.orEmpty()
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

/** Identifier = letter or `_` then letters/digits/`_`, not a keyword (unless escaped by the language). */
open class LiteNamesValidator(private val keywords: Set<String>) : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean = name in keywords

    override fun isIdentifier(name: String, project: Project?): Boolean {
        val n = unescape(name)
        return n.isNotEmpty() && n != "_" && (n[0].isLetter() || n[0] == '_') &&
            n.all { it.isLetterOrDigit() || it == '_' } && (isEscaped(name) || n !in keywords)
    }

    protected open fun unescape(name: String): String = name
    protected open fun isEscaped(name: String): Boolean = false
}

/** `//` and `/* */` comments (shared by C-like languages). */
open class LiteCStyleCommenter : com.intellij.lang.Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Brace matching from (open, close, structural) triples. */
open class LiteBraceMatcher(vararg pairs: Triple<com.intellij.psi.tree.IElementType, com.intellij.psi.tree.IElementType, Boolean>) :
    com.intellij.lang.PairedBraceMatcher {
    private val pairs = pairs.map { (l, r, structural) -> com.intellij.lang.BracePair(l, r, structural) }.toTypedArray()
    override fun getPairs(): Array<com.intellij.lang.BracePair> = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: com.intellij.psi.tree.IElementType, contextType: com.intellij.psi.tree.IElementType?) = true
    override fun getCodeConstructStart(file: com.intellij.psi.PsiFile, openingBraceOffset: Int) = openingBraceOffset
}

/** Adds an information-level coloring annotation (no inspection, no message). */
fun com.intellij.lang.annotation.AnnotationHolder.color(element: PsiElement?, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
    element ?: return
    newSilentAnnotation(com.intellij.lang.annotation.HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
}

/**
 * Use scope of a declaration that is only visible inside [container] (locals, parameters, private
 * members): Find Usages and rename then search that element only, intersected with the scope the user picked.
 */
fun localUseScope(container: PsiElement?): com.intellij.psi.search.SearchScope? =
    container?.let { com.intellij.psi.search.LocalSearchScope(it) }
