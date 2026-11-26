package me.kevinlot.lite.dart

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.core.localUseScope
import me.kevinlot.lite.core.renameLeaf
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

open class DartElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val type: IElementType get() = node.elementType
}

internal fun PsiElement.child(type: IElementType): PsiElement? = node?.findChildByType(type)?.psi
internal fun PsiElement.childrenOf(type: IElementType): List<PsiElement> = node?.getChildren(TokenSet.create(type)).orEmpty().map { it.psi }
internal val PsiElement.elementType: IElementType? get() = node?.elementType

/**
 * A declared name: a `componentName`, the variable a pattern binds (`final (a, b) = …`, `case int n`), the name of an extension,
 * or the label of a record field (`({int n})`, `(n: 1)`).
 */
class DartName(node: ASTNode) : DartElement(node), PsiNameIdentifierOwner {
    /** What the name is the name of: the declaration around a `componentName`, the pattern around a bound variable. */
    val declaration: PsiElement get() = parent

    /** The name of an extension or the label of a record field: no variable, whatever pattern or type it is written in. */
    val isLabel: Boolean get() = isA(T.ID)

    val isLocal: Boolean get() = declaration.elementType in LOCALS && PsiTreeUtil.findFirstParent(this) { it.isA(T.STATEMENTS) } != null

    override fun getNameIdentifier(): PsiElement = PsiTreeUtil.getDeepestFirst(this)
    override fun getName(): String = text
    override fun getTextOffset(): Int = textRange.startOffset
    override fun setName(name: String): PsiElement = apply { renameLeaf(nameIdentifier, name) }
    override fun getReference(): PsiReference? = takeIf { declaration.isA(T.RECORD_FIELD) }?.let(::DartFieldReference)

    /** A variable of a body is used in that body only; a parameter is named by its callers (`f(x: 1)`). */
    override fun getUseScope(): SearchScope =
        localUseScope(PsiTreeUtil.findFirstParent(this) { it.isA(T.FUNCTION_BODY) || it.isA(T.FUNCTION_EXPRESSION_BODY) }?.takeIf { isLocal })
            ?: super.getUseScope()

    private companion object {
        val LOCALS = setOf(T.VAR_ACCESS_DECLARATION, T.VAR_DECLARATION_LIST_PART, T.VARIABLE_PATTERN, T.CONSTANT_PATTERN, T.CATCH_PART, T.FOR_IN_PART)
    }
}

/** The label of a record literal is the field of the record type the literal is given to, when one is written. */
class DartFieldReference(element: DartName) : PsiReferenceBase<DartName>(element, TextRange(0, element.textLength), true) {
    override fun resolve(): PsiElement? =
        DartTypes.expected(element.declaration.parent?.parent)?.let { DartPatterns.recordField(it, element.text) }?.takeIf { it != element }

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}

/** A name that is used: `x`, the `b` of `a.b`, a named argument, a name of `show` / `hide`. */
class DartRef(node: ASTNode) : DartElement(node) {
    /** `a.b` is a reference around two: only the innermost ones carry a name. */
    val isName: Boolean get() = children.none { it is DartRef || it is DartName }

    val referencedName: String get() = text
    override fun getReference(): PsiReference? = takeIf { isName }?.let(::DartReference)
}

class DartReference(element: DartRef) : LiteReference<DartRef>(element, { PsiTreeUtil.getDeepestFirst(element) }) {
    override fun resolveTargets(): List<PsiElement> = DartResolver.resolve(element)
    /** A message is read through its getter, whose name may differ from the key. */
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? =
        DartPsi.identifier(element, newName.takeIf { resolveTargets().none(DartLocalizations::isEntry) } ?: DartLocalizations.getter(newName))
}

/** The string of `import`, `export`, `part` and `part of`: the file it names. */
class DartUri(node: ASTNode) : DartElement(node) {
    val uri: String get() = text.trim('\'', '"')
    override fun getReference(): PsiReference? = takeIf { textLength > 2 }?.let(::DartUriReference)
}

class DartUriReference(element: DartUri) : PsiPolyVariantReferenceBase<DartUri>(element, TextRange(1, element.textLength - 1), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(listOfNotNull(DartLibraries.resolveUri(element.containingFile, element.uri)))

    /** A renamed file keeps its folder: only the last segment of the URI changes. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val uri = element.uri.substringBeforeLast('/', "").let { folder -> newElementName.takeIf { folder.isEmpty() } ?: "$folder/$newElementName" }
        renameLeaf(PsiTreeUtil.collectElements(element) { it.isA(T.REGULAR_STRING_PART) }.singleOrNull() ?: return element, uri)
        return element
    }
}

object DartPsi {
    private val LABELLED = setOf(T.EXTENSION_DECLARATION, T.RECORD_FIELD, T.RECORD_TYPE_NAMED_FIELD)
    private val REFERENCES = setOf(T.REFERENCE_EXPRESSION, T.PARAMETER_NAME_REFERENCE_EXPRESSION, T.LIBRARY_COMPONENT_REFERENCE_EXPRESSION)

    fun create(node: ASTNode): PsiElement = when {
        node.elementType == T.COMPONENT_NAME || binds(node) || labels(node) -> DartName(node)
        node.elementType in REFERENCES -> DartRef(node)
        node.elementType == T.URI_ELEMENT -> DartUri(node)
        else -> DartElement(node)
    }

    private fun labels(node: ASTNode): Boolean = node.elementType == T.ID && node.treeParent?.elementType in LABELLED

    /** A reference a pattern declares: the variable of `var x` / `int x`, and the names of a destructuring declaration. */
    private fun binds(node: ASTNode): Boolean {
        if (node.elementType != T.REFERENCE_EXPRESSION) return false
        val parent = node.treeParent?.elementType
        if (parent == T.VARIABLE_PATTERN) return true
        if (parent != T.CONSTANT_PATTERN) return false
        return generateSequence(node.treeParent) { it.treeParent }.takeWhile { it.elementType != T.STATEMENTS }
            .any { it.elementType == T.PATTERN_VARIABLE_DECLARATION || it.elementType == T.FOR_IN_PART }
    }

    fun identifier(context: PsiElement, name: String): PsiElement? {
        val file = PsiFileFactory.getInstance(context.project).createFileFromText("a.dart", DartLanguage, "var $name;")
        return PsiTreeUtil.findChildOfType(file, DartName::class.java)?.nameIdentifier
    }
}
