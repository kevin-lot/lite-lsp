package me.kevinlot.lite.typescript.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.core.localUseScope
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.TsFileType
import me.kevinlot.lite.typescript.resolve.TsMemberKeyReference
import me.kevinlot.lite.typescript.resolve.TsModuleReference
import me.kevinlot.lite.typescript.resolve.TsNameReference
import me.kevinlot.lite.typescript.resolve.TsShorthandReference
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as E

open class TsElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val type: IElementType get() = node.elementType
    fun child(t: IElementType): PsiElement? = node.findChildByType(t)?.psi
    fun hasToken(t: IElementType) = node.findChildByType(t) != null
    /** Contextual or reserved modifier among the direct tokens (`export`, `static`, `private`…). */
    fun hasModifier(text: String) = node.getChildren(MODIFIER_TOKENS).any { it.text == text }
    val typeNode: PsiElement? get() = child(E.TYPE_ANNOTATION)?.let { a -> a.children.firstOrNull { it.node.elementType in E.TYPES } }
    /** The expression after `=` (variables, parameters, properties, enum members). */
    val initializer: PsiElement?
        get() {
            var seenEq = false
            for (c in node.getChildren(null)) {
                if (c.elementType == T.EQ) seenEq = true else if (seenEq && c.psi is TsElement) return c.psi
            }
            return null
        }

    private companion object {
        val MODIFIER_TOKENS = TokenSet.orSet(TokenSet.create(T.SOFT_KEYWORD), T.KEYWORD_SET)
    }
}

/** Every declaration with a name: its name is its first identifier-like direct token. */
open class TsNamedDecl(node: ASTNode) : TsElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(NAME_TOKENS)?.psi
    override fun getName(): String? = nameIdentifier?.let { unquote(it.text) }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        val id = nameIdentifier ?: throw IncorrectOperationException("No name")
        if (id.node.elementType == T.IDENTIFIER) id.replace(TsPsiFactory(project).identifier(name))
        return this
    }

    val isExported: Boolean get() = hasModifier("export")
    val isDefault: Boolean get() = hasModifier("default")
    val isStatic: Boolean get() = hasModifier("static")

    /**
     * Locals are searched in their function/block, private members in their class, type parameters in their
     * owner, non-exported top-level declarations of a module in their file.
     */
    override fun getUseScope(): SearchScope {
        val local = when {
            type == E.TYPE_PARAMETER -> parent?.parent
            type == E.PARAMETER -> PsiTreeUtil.findFirstParent(this, true) { it.node?.elementType in E.FUNCTIONS || it.node?.elementType == E.CATCH_CLAUSE }
            type in MEMBERS && (hasModifier("private") || nameIdentifier?.node?.elementType == T.PRIVATE_NAME) ->
                PsiTreeUtil.findFirstParent(this, true) { it.node?.elementType in E.CLASSES }
            type in MEMBERS || type == E.ENUM_MEMBER -> null
            else -> PsiTreeUtil.findFirstParent(this, true) { it.node?.elementType in E.FUNCTIONS || it.node?.elementType == E.STATIC_BLOCK }
                ?: (containingFile as? TsFile)?.takeIf { isTopLevelPrivate(it) }
        }
        return localUseScope(local) ?: super.getUseScope()
    }

    private fun isTopLevelPrivate(file: TsFile): Boolean {
        if (isExported || file.isDeclarationFile) return false
        val statement = if (type == E.VARIABLE || type == E.BINDING) PsiTreeUtil.findFirstParent(this) { it.node?.elementType == E.VAR_STATEMENT } else this
        if (statement?.parent != file) return false
        if ((statement as? TsElement)?.hasModifier("export") == true) return false
        return me.kevinlot.lite.typescript.resolve.TsModules.isModule(file)
    }

    companion object {
        val NAME_TOKENS = TokenSet.create(T.IDENTIFIER, T.STRING, T.NUMBER, T.PRIVATE_NAME)
        val MEMBERS = TokenSet.create(E.PROPERTY, E.METHOD)
        fun unquote(s: String) = if (s.length >= 2 && (s[0] == '"' || s[0] == '\'')) s.substring(1, s.length - 1) else s
    }
}

/** function, method, arrow, function expression. */
class TsFunction(node: ASTNode) : TsNamedDecl(node) {
    val parameters: List<TsNamedDecl> get() = child(E.PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>().orEmpty()
    val typeParameters: List<TsNamedDecl> get() = child(E.TYPE_PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>().orEmpty()
    val returnType: PsiElement? get() = typeNode
    /** Block body, or the expression of a concise arrow. */
    val body: PsiElement? get() = child(E.BLOCK) ?: if (type == E.ARROW_FUNCTION) node.getChildren(null).lastOrNull()?.psi?.takeIf { it is TsElement && it.node.elementType != E.TYPE_ANNOTATION && it.node.elementType != E.PARAMETER_LIST } else null
    val isGetter: Boolean get() = hasModifier("get")
    val isConstructor: Boolean get() = type == E.METHOD && name == "constructor"
}

/** class, interface (members in CLASS_BODY / OBJECT_TYPE). */
class TsClass(node: ASTNode) : TsNamedDecl(node) {
    val typeParameters: List<TsNamedDecl> get() = child(E.TYPE_PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>().orEmpty()
    val body: PsiElement? get() = child(E.CLASS_BODY) ?: child(E.OBJECT_TYPE)
    val members: List<PsiElement> get() = body?.children?.filter { it is TsElement }.orEmpty()
    val extendsClause: PsiElement? get() = child(E.EXTENDS_CLAUSE)
    val isInterface: Boolean get() = type == E.INTERFACE_DECLARATION
}

/** A name in an expression or a type, possibly qualified: `a.b.c` is REF(REF(REF(a).b).c). */
class TsReferenceExpr(node: ASTNode) : TsElement(node) {
    val nameElement: PsiElement? get() = node.getChildren(REF_NAMES).lastOrNull()?.psi
    val referencedName: String? get() = nameElement?.text
    val qualifier: PsiElement? get() = if (hasToken(T.DOT) || hasToken(T.QUEST_DOT)) firstChild?.takeIf { it is TsElement } else null
    /** Navigation lands on the name (`exports.helper = …`, `this.count = …` are declarations too). */
    override fun getTextOffset(): Int = nameElement?.textOffset ?: super.getTextOffset()

    override fun getReference(): PsiReference? {
        val name = referencedName ?: return null
        if (qualifier == null) {
            // `<div>`: an intrinsic element, not a name in scope
            if (parent?.node?.elementType == E.JSX_ELEMENT && name.first().isLowerCase()) return null
            // `string`, `undefined`…: keywords of the type language
            if (name in BUILTIN_TYPES && parent?.node?.elementType == E.TYPE_REFERENCE || name == "undefined") return null
        }
        return TsNameReference(this)
    }

    private companion object {
        val REF_NAMES = TokenSet.create(T.IDENTIFIER, T.PRIVATE_NAME)
        val BUILTIN_TYPES = setOf("string", "number", "boolean", "bigint", "symbol", "object", "any", "unknown", "never", "undefined", "void")
    }
}

/** `{ x }` in an object literal: declares property `x` and refers to the variable `x`. */
class TsShorthandProperty(node: ASTNode) : TsNamedDecl(node) {
    override fun getReference(): PsiReference? = nameIdentifier?.let { TsShorthandReference(this) }
}

/** `{ key: local }` in a destructuring pattern: `key` is a member of the destructured value. */
class TsPropertyKey(node: ASTNode) : TsElement(node) {
    override fun getReference(): PsiReference? = firstChild?.takeIf { it.node.elementType == T.IDENTIFIER }?.let { TsMemberKeyReference(this) }
}

/** `"./path"` after `from`, in `import("…")`, `require("…")`: navigates to the module file. */
class TsModuleSpecifier(node: ASTNode) : TsElement(node) {
    val path: String get() = TsNamedDecl.unquote(text)
    override fun getReference(): PsiReference = TsModuleReference(this)
}

/** `import { a as b }` / `export { a as b }`: the name bound (or exported) is the alias, else the name. */
class TsSpecifier(node: ASTNode) : TsNamedDecl(node) {
    /** The original name (in the other module for imports, in this one for local exports). */
    val ref: TsReferenceExpr? get() = children.firstOrNull { it is TsReferenceExpr } as? TsReferenceExpr
    val sourceName: String? get() = ref?.referencedName ?: node.findChildByType(T.STRING)?.text?.let(::unquote)
    private val alias: PsiElement?
        get() {
            val asToken = node.getChildren(TokenSet.create(T.SOFT_KEYWORD)).firstOrNull { it.text == "as" } ?: return null
            var n = asToken.treeNext
            while (n != null && (n.elementType == T.WHITE_SPACE || n.elementType in T.COMMENTS)) n = n.treeNext
            return n?.psi
        }
    override fun getNameIdentifier(): PsiElement? = alias ?: ref?.nameElement ?: node.findChildByType(T.STRING)?.psi
}

object TsPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        E.FUNCTION_DECLARATION, E.FUNCTION_EXPRESSION, E.ARROW_FUNCTION, E.METHOD -> TsFunction(node)
        E.CLASS_DECLARATION, E.CLASS_EXPRESSION, E.INTERFACE_DECLARATION -> TsClass(node)
        E.REFERENCE -> TsReferenceExpr(node)
        E.SHORTHAND_PROPERTY -> TsShorthandProperty(node)
        E.PROPERTY_KEY -> TsPropertyKey(node)
        E.MODULE_SPECIFIER -> TsModuleSpecifier(node)
        E.IMPORT_SPECIFIER, E.EXPORT_SPECIFIER -> TsSpecifier(node)
        E.VARIABLE, E.BINDING, E.PARAMETER, E.TYPE_PARAMETER, E.PROPERTY, E.PROPERTY_ASSIGNMENT, E.TYPE_ALIAS, E.ENUM_DECLARATION,
        E.ENUM_MEMBER, E.NAMESPACE_DECLARATION, E.AMBIENT_MODULE, E.IMPORT_DEFAULT, E.IMPORT_NAMESPACE, E.IMPORT_EQUALS, E.EXPORT_STAR,
        -> TsNamedDecl(node)
        else -> TsElement(node)
    }
}

class TsPsiFactory(private val project: Project) {
    fun file(text: String, name: String = "dummy.ts"): TsFile =
        PsiFileFactory.getInstance(project).createFileFromText(name, TsFileType, text) as TsFile

    fun identifier(name: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("let $name;"), TsNamedDecl::class.java)?.nameIdentifier ?: throw IncorrectOperationException(name)

    fun statement(text: String): PsiElement = file(text).firstChild
}
