package me.kevinlot.lite.python.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.python.parser.PyFile
import me.kevinlot.lite.python.resolve.PyNameReference
import me.kevinlot.lite.python.upstream.PyElementTypes as E
import me.kevinlot.lite.python.upstream.PyTokenTypes as T
import me.kevinlot.lite.python.upstream.PythonFileType

/** Minimal PSI over the upstream parser tree: declarations with names, references, imports. */
open class PyElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    fun child(type: IElementType): PsiElement? = node.findChildByType(type)?.psi
}

/** def, class, parameter, assignment target, import alias, type parameter. */
open class PyNamedDecl(node: ASTNode) : PyElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = child(T.IDENTIFIER)
    override fun getName(): String? = nameIdentifier?.text
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(PyPsiFactory(project).identifier(name)) ?: throw IncorrectOperationException("No name")
        return this
    }

    /** Parameters and locals are only visible in their function/lambda/comprehension. */
    override fun getUseScope(): com.intellij.psi.search.SearchScope {
        val container = when {
            this is PyClass -> null
            node.elementType == E.NAMED_PARAMETER -> PsiTreeUtil.getParentOfType(this, PyFunction::class.java, true)
                ?: PsiTreeUtil.findFirstParent(this) { it.node?.elementType == me.kevinlot.lite.python.upstream.PyElementTypes.LAMBDA_EXPRESSION }
            else -> PsiTreeUtil.getParentOfType(this, PyFunction::class.java, true)
                ?.takeIf { this !is PyFunction || it != parent?.parent }
        }
        return me.kevinlot.lite.core.localUseScope(container) ?: super.getUseScope()
    }

    /** `x: Foo`, `def f(x: Foo)` */
    val annotation: PsiElement?
        get() = (child(E.ANNOTATION) ?: (parent as? PyElement)?.takeIf {
            // `x: Foo` and `self.items: list[Foo] = []`: the annotation belongs to the statement
            parent.node.elementType == E.TYPE_DECLARATION_STATEMENT || parent.node.elementType == E.ASSIGNMENT_STATEMENT
        }?.child(E.ANNOTATION))
            ?.let { a -> a.children.lastOrNull { it is PyElement } }
}

class PyFunction(node: ASTNode) : PyNamedDecl(node) {
    val parameters: List<PyNamedDecl>
        get() = (child(E.PARAMETER_LIST) as? PyElement)?.children?.filterIsInstance<PyNamedDecl>().orEmpty()
    /** `-> Foo` */
    val returnAnnotation: PsiElement? get() = child(E.ANNOTATION)?.children?.lastOrNull { it is PyElement }
    val body: PsiElement? get() = child(E.STATEMENT_LIST)
    val containingClass: PyClass? get() = (parent?.parent as? PyClass)
    val decorators: List<String>
        get() = (child(E.DECORATOR_LIST) as? PyElement)?.children.orEmpty().mapNotNull { d -> d.children.firstOrNull { it is PyReferenceExpr }?.text }
}

class PyClass(node: ASTNode) : PyNamedDecl(node) {
    val body: PsiElement? get() = child(E.STATEMENT_LIST)
    /** `class A(B, C):` base expressions (keyword arguments like `metaclass=` excluded). */
    val superClassExpressions: List<PsiElement>
        get() = (child(E.ARGUMENT_LIST) as? PyElement)?.children.orEmpty().filter { it is PyReferenceExpr || it.node.elementType == E.SUBSCRIPTION_EXPRESSION }
}

/** A name in an expression, possibly qualified: `a.b.c` is REF(REF(REF(a).b).c). */
class PyReferenceExpr(node: ASTNode) : PyElement(node) {
    val nameElement: PsiElement? get() = node.getChildren(com.intellij.psi.tree.TokenSet.create(T.IDENTIFIER)).lastOrNull()?.psi
    val referencedName: String? get() = nameElement?.text
    val qualifier: PsiElement? get() = if (node.findChildByType(T.DOT) != null) children.firstOrNull { it is PyElement } else null
    override fun getReference(): PsiReference? = nameElement?.let { PyNameReference(this) }
}

/** A target (`x = 1`, `self.x = 1`, `for x in`): a declaration, also a reference when qualified. */
class PyTarget(node: ASTNode) : PyNamedDecl(node) {
    val qualifier: PsiElement? get() = if (node.findChildByType(T.DOT) != null) children.firstOrNull { it is PyElement } else null
    override fun getNameIdentifier(): PsiElement? = node.getChildren(com.intellij.psi.tree.TokenSet.create(T.IDENTIFIER)).lastOrNull()?.psi
}

/** `import a.b as c` / `from m import a as c` element. */
class PyImportElement(node: ASTNode) : PyNamedDecl(node) {
    val importedPath: PyReferenceExpr? get() = children.firstOrNull { it is PyReferenceExpr } as? PyReferenceExpr
    /** The alias target, if any. */
    val alias: PyTarget? get() = children.firstOrNull { it is PyTarget } as? PyTarget
    /** Name bound in the importing scope: alias, else last segment (`from`) or first segment (`import a.b`). */
    val boundName: String?
        get() = alias?.name ?: importedPath?.text?.let { if (parent?.node?.elementType == E.FROM_IMPORT_STATEMENT) it.substringAfterLast('.') else it.substringBefore('.') }
    override fun getNameIdentifier(): PsiElement? = alias?.nameIdentifier ?: importedPath?.nameElement
    override fun getName(): String? = boundName
}

object PyPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        E.FUNCTION_DECLARATION -> PyFunction(node)
        E.CLASS_DECLARATION -> PyClass(node)
        E.NAMED_PARAMETER, E.TYPE_PARAMETER -> PyNamedDecl(node)
        E.TARGET_EXPRESSION -> PyTarget(node)
        E.IMPORT_ELEMENT -> PyImportElement(node)
        E.REFERENCE_EXPRESSION -> PyReferenceExpr(node)
        else -> PyElement(node)
    }
}

class PyPsiFactory(private val project: Project) {
    fun file(text: String): PyFile = PsiFileFactory.getInstance(project).createFileFromText("dummy.py", PythonFileType.INSTANCE, text) as PyFile
    fun identifier(name: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("def $name(): pass"), PyFunction::class.java)?.nameIdentifier ?: throw IncorrectOperationException(name)
    fun statement(text: String): PsiElement = file(text).firstChild
}
