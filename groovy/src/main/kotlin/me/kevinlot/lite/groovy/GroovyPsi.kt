package me.kevinlot.lite.groovy

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.core.localUseScope
import me.kevinlot.lite.core.renameLeaf
import me.kevinlot.lite.groovy.GroovyElementTypes as E
import me.kevinlot.lite.groovy.GroovyTokens as T

open class GroovyElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val type: IElementType get() = node.elementType
}

private val NAME_TOKENS = TokenSet.create(T.IDENT, T.STRING)
private val QUOTES = listOf("'''", "\"\"\"", "$/", "'", "\"", "/")

/** Length of the delimiter that opens a quoted token. */
private fun quoteLength(text: String): Int = QUOTES.firstOrNull(text::startsWith)?.length ?: 0

private fun unquote(text: String): String {
    val quote = quoteLength(text)
    return text.substring(quote, maxOf(quote, text.length - quote))
}

internal fun identifier(project: Project, name: String): PsiElement? =
    PsiFileFactory.getInstance(project).createFileFromText("name.groovy", GroovyFileType, name)
        .let(PsiTreeUtil::getDeepestFirst).takeIf { it.node.elementType == T.IDENT && it.textLength == name.length }

/** A declaration: its name is the first name token directly under it. */
abstract class GroovyNamed(node: ASTNode) : GroovyElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(NAME_TOKENS)?.psi
    override fun getName(): String? = nameIdentifier?.text?.let(::unquote)
    override fun setName(name: String): PsiElement { renameLeaf(nameIdentifier, name); return this }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    protected open val modifierList: PsiElement? get() = children.firstOrNull { it.isA(E.MODIFIERS) }
    val isPrivate: Boolean get() = modifierList?.node?.findChildByType(T.kw("private")) != null
}

/** `class`, `interface`, `trait`, `enum`, `record` or `@interface`. */
class GroovyClass(node: ASTNode) : GroovyNamed(node) {
    private val keyword: ASTNode? get() = node.findChildByType(CLASS_KEYWORDS) ?: node.findChildByType(T.IDENT)
    override fun getNameIdentifier(): PsiElement? =
        generateSequence(keyword?.treeNext) { it.treeNext }.firstOrNull { it.elementType == T.IDENT }?.psi

    private val body: PsiElement? get() = children.firstOrNull { it.isA(E.CLASS_BODY) }
    val outerClass: GroovyClass? get() = PsiTreeUtil.getParentOfType(this, GroovyClass::class.java)
    val superTypes: List<GroovyType> get() = children.filterIsInstance<GroovyType>()
    val methods: List<GroovyMethod> get() = body?.children?.filterIsInstance<GroovyMethod>().orEmpty()
    val innerClasses: List<GroovyClass> get() = body?.children?.filterIsInstance<GroovyClass>().orEmpty()

    /** Fields, properties and enum constants. */
    val fields: List<GroovyNamed>
        get() = body?.children.orEmpty().flatMap { member ->
            when {
                member is GroovyEnumConstant -> listOf(member)
                member.isA(E.VAR_DECL) -> member.children.filterIsInstance<GroovyVariable>()
                else -> emptyList()
            }
        }

    val qualifiedName: String?
        get() {
            val name = name ?: return null
            val owner = outerClass?.qualifiedName ?: (containingFile as? GroovyFile)?.let(GroovyFiles::packageName)
            return listOfNotNull(owner?.takeIf { it.isNotEmpty() }, name).joinToString(".")
        }

    override fun getUseScope(): SearchScope = localUseScope(containingFile.takeIf { isPrivate }) ?: super.getUseScope()

    private companion object {
        val CLASS_KEYWORDS = TokenSet.create(T.kw("class"), T.kw("enum"), T.kw("interface"))
    }
}

class GroovyEnumConstant(node: ASTNode) : GroovyNamed(node)

/** A method or a constructor, of a class or of the script. */
class GroovyMethod(node: ASTNode) : GroovyNamed(node) {
    val params: List<GroovyVariable>
        get() = children.firstOrNull { it.isA(E.PARAMS) }?.children?.filterIsInstance<GroovyVariable>().orEmpty()
    val returnType: GroovyType? get() = children.firstNotNullOfOrNull { it as? GroovyType }
    override fun getUseScope(): SearchScope = localUseScope(containingFile.takeIf { isPrivate }) ?: super.getUseScope()
}

/** A local or script variable, a field, a parameter, a loop variable or a catch parameter. */
class GroovyVariable(node: ASTNode) : GroovyNamed(node) {
    private val declaration: PsiElement? get() = parent?.takeIf { it.isA(E.VAR_DECL) }
    override val modifierList: PsiElement? get() = super.modifierList ?: declaration?.children?.firstOrNull { it.isA(E.MODIFIERS) }

    val isParameter: Boolean get() = type == E.PARAM
    val isField: Boolean get() = declaration?.parent.isA(E.CLASS_BODY)
    val declaredType: GroovyType?
        get() = children.firstNotNullOfOrNull { it as? GroovyType } ?: declaration?.children?.firstNotNullOfOrNull { it as? GroovyType }

    /** The value after `=`, of a variable declared alone. */
    val initializer: PsiElement? get() = node.findChildByType(T.ASSIGN)?.psi?.let { PsiTreeUtil.getNextSiblingOfType(it, GroovyElement::class.java) }

    /** Where the name is visible: the closure or method of a parameter, the loop, the block (or the file) of a local. */
    private val container: PsiElement?
        get() = when {
            isField -> containingFile.takeIf { isPrivate }
            isParameter -> parent?.parent
            declaration == null -> parent
            else -> generateSequence(declaration?.parent) { it.parent }.firstOrNull { !it.isA(E.LABELED) }
        }

    override fun getUseScope(): SearchScope = localUseScope(container) ?: super.getUseScope()
}

/** `{ a, b -> … }`, `{ … }` (implicit `it`) or a lambda. */
class GroovyClosure(node: ASTNode) : GroovyElement(node) {
    private val paramList: PsiElement? get() = children.firstOrNull { it.isA(E.PARAMS) }
    val params: List<GroovyVariable> get() = paramList?.children?.filterIsInstance<GroovyVariable>().orEmpty()
    val hasImplicitIt: Boolean get() = paramList == null
}

/** A call: `f(x)`, `f x, y`, `f { … }`, `a.b(x) { … }`. */
class GroovyCall(node: ASTNode) : GroovyElement(node) {
    val callee: PsiElement? get() = firstChild
    private val argList: List<PsiElement> get() = children.firstOrNull { it.isA(E.ARGS) }?.children.orEmpty().toList()
    val args: List<PsiElement> get() = argList.filter { !it.isA(E.ENTRY) }
    val namedArgs: List<PsiElement> get() = argList.filter { it.isA(E.ENTRY) }

    /** Named arguments travel as one map; closures after the parentheses are arguments too. */
    val argumentCount: Int get() = args.size + minOf(namedArgs.size, 1) + children.count { it is GroovyClosure && it != callee }
}

/** The label of `name: value`, when it is a plain name. */
internal fun entryLabel(entry: PsiElement): String? = entry.firstChild?.takeIf { it.node.elementType == T.IDENT }?.text

/** A string, a number, `true`, `false` or `null`; the path of `load` / `apply from:` refers to its file. */
class GroovyLiteral(node: ASTNode) : GroovyElement(node) {
    private val isString: Boolean get() = node.firstChildNode?.elementType == T.STRING
    private val quote: Int get() = quoteLength(text)
    val stringValue: String? get() = text.takeIf { isString }?.let(::unquote)
    val contentRange: TextRange get() = TextRange(quote, maxOf(quote, textLength - quote))

    fun setStringValue(value: String) {
        val open = text.take(quote)
        renameLeaf(firstChild, open + value + open)
    }

    override fun getReferences(): Array<PsiReference> = GroovyPaths.referenceOf(this)?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/**
 * A name used: a variable, a member of the enclosing class, a class, a step. The first `x = …` of a script that
 * declares no `x` defines a binding, and is a named element so that Find Usages and rename start from it.
 */
class GroovyNameRef(node: ASTNode) : GroovyElement(node), PsiNameIdentifierOwner {
    override fun getName(): String = text
    override fun getNameIdentifier(): PsiElement? = firstChild?.takeIf { isBindingDefinition }
    override fun setName(name: String): PsiElement { renameLeaf(firstChild, name); return this }
    override fun getReference(): PsiReference = GroovyNameReference(this)
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)
    override fun getUseScope(): SearchScope = localUseScope(containingFile) ?: super.getUseScope()

    val isThis: Boolean get() = node.firstChildNode?.elementType == T.kw("this")
    val isSuper: Boolean get() = node.firstChildNode?.elementType == T.kw("super")
    val isCallee: Boolean get() = (parent as? GroovyCall)?.callee == this
    val isAssigned: Boolean get() = parent.isA(E.ASSIGN) && parent.firstChild == this && parent.node.findChildByType(T.ASSIGN) != null
    val isBindingDefinition: Boolean get() = isAssigned && GroovyResolve.bindings(containingFile)[name] == this
}

/** `a.b`, `a?.b`, `a*.b`, `a.&b`, `a::b`, `a.'b'`, and the `c` of the command chain `a b c`. */
class GroovyMember(node: ASTNode) : GroovyElement(node) {
    val qualifier: PsiElement? get() = firstChild
    val nameElement: PsiElement? get() = node.lastChildNode?.takeIf { it.elementType in NAME_TOKENS }?.psi
    override fun getName(): String? = nameElement?.text?.let(::unquote)
    val isCallee: Boolean get() = (parent as? GroovyCall)?.callee == this
    override fun getReference(): PsiReference? = nameElement?.let { GroovyMemberReference(this) }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** A type used: `Foo`, `a.b.Foo`, `List<Foo>`, `Foo[]`; primitive types and `def` refer to nothing. */
class GroovyType(node: ASTNode) : GroovyElement(node) {
    private val names: List<ASTNode> get() = node.getChildren(TokenSet.create(T.IDENT)).toList()
    val nameElement: PsiElement? get() = names.lastOrNull()?.psi
    val qualifiedName: String? get() = names.takeIf { it.isNotEmpty() }?.joinToString(".") { it.text }
    override fun getReference(): PsiReference? = nameElement?.let { GroovyTypeReference(this) }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** `a.b.C` of a `package` or an `import`: the last name of an import refers to what it imports. */
class GroovyQualifiedName(node: ASTNode) : GroovyElement(node) {
    val nameElement: PsiElement? get() = node.lastChildNode?.takeIf { it.elementType == T.IDENT }?.psi
    override fun getReference(): PsiReference? = nameElement?.takeIf { parent is GroovyImport }?.let { GroovyImportReference(this) }
    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

/** `import a.b.C`, `import a.b.*`, `import static a.b.C.m`, `import static a.b.C.*`, each with an optional `as Alias`. */
class GroovyImport(node: ASTNode) : GroovyElement(node) {
    val isStatic: Boolean get() = node.findChildByType(T.kw("static")) != null
    val isStar: Boolean get() = node.findChildByType(T.MUL) != null
    val qualifiedName: String get() = children.firstNotNullOfOrNull { it as? GroovyQualifiedName }?.text.orEmpty().filterNot(Char::isWhitespace)
    private val alias: String? get() = node.findChildByType(T.kw("as"))?.let { node.findChildByType(T.IDENT, it) }?.text

    /** The name the import brings into the file; none for a star import. */
    val importedName: String? get() = (alias ?: qualifiedName.substringAfterLast('.')).takeIf { !isStar }
}

// ---------------------------------------------------------------- references

abstract class GroovyReference<P : PsiElement>(element: P, nameElement: () -> PsiElement?) : LiteReference<P>(element, nameElement) {
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? = identifier(nameToken.project, newName)
}

class GroovyNameReference(element: GroovyNameRef) : GroovyReference<GroovyNameRef>(element, { element.firstChild }) {
    /** A definition is no use of itself (Ctrl+Click on it lists its usages). */
    override fun resolveTargets(): List<PsiElement> = GroovyResolve.resolveName(element).filter { it != element }

    private fun stepFiles(): List<PsiFile> = resolveTargets().filter(GroovySteps::isStep).mapNotNull { it.containingFile }

    override fun isReferenceTo(element: PsiElement): Boolean = super.isReferenceTo(element) || (element is PsiFile && element in stepFiles())

    /** A step is named after its file: it follows a rename of the file, not one of its `call` method. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val fileRenamed = newElementName.endsWith(GroovySteps.EXTENSION)
        if (stepFiles().isNotEmpty() && !fileRenamed) return element
        return super.handleElementRename(newElementName.removeSuffix(GroovySteps.EXTENSION))
    }
}

class GroovyMemberReference(element: GroovyMember) : GroovyReference<GroovyMember>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = GroovyResolve.resolveMember(element)
}

class GroovyTypeReference(element: GroovyType) : GroovyReference<GroovyType>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = GroovyResolve.resolveType(element)
}

class GroovyImportReference(element: GroovyQualifiedName) : GroovyReference<GroovyQualifiedName>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = (element.parent as? GroovyImport)?.let(GroovyResolve::resolveImport).orEmpty()
}

/** `load 'ci/deploy.groovy'`, `apply from: 'gradle/lint.gradle'` → the file; a rename of the file changes the path. */
class GroovyPathReference(element: GroovyLiteral) : GroovyReference<GroovyLiteral>(element, { element.firstChild }) {
    override fun getRangeInElement(): TextRange = element.contentRange
    override fun resolveTargets(): List<PsiElement> = listOfNotNull(GroovyPaths.target(element))

    override fun handleElementRename(newElementName: String): PsiElement {
        val folder = element.stringValue.orEmpty().substringBeforeLast('/', "")
        element.setStringValue(listOf(folder, newElementName).filter { it.isNotEmpty() }.joinToString("/"))
        return element
    }
}
