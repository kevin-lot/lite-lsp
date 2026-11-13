package me.kevinlot.lite.qml.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.qml.parser.QmlElementTypes as Q
import me.kevinlot.lite.qml.resolve.QmlPropertyReference
import me.kevinlot.lite.qml.resolve.QmlResolver
import me.kevinlot.lite.qml.resolve.QmlTypeReference
import me.kevinlot.lite.qml.resolve.QmlTypes
import me.kevinlot.lite.typescript.psi.TsNamedDecl
import me.kevinlot.lite.typescript.psi.TsPsiFactory
import me.kevinlot.lite.typescript.resolve.TsHostDecl
import me.kevinlot.lite.typescript.resolve.TsHostScope
import me.kevinlot.lite.typescript.resolve.TsTy
import me.kevinlot.lite.typescript.lexer.TsTokens as T

open class QmlElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val type: IElementType get() = node.elementType
    fun child(t: IElementType): PsiElement? = node.findChildByType(t)?.psi
    /** Text of the soft keywords and keywords among the direct tokens (`readonly`, `default`…). */
    fun hasModifier(text: String) = node.getChildren(MODIFIERS).any { it.text == text }

    private companion object { val MODIFIERS = TokenSet.orSet(TokenSet.create(T.SOFT_KEYWORD), T.KEYWORD_SET) }
}

/** `id:`, `property`, `signal`, `enum`, enum member, signal parameter, inline `component`: named by their first identifier. */
class QmlNamed(node: ASTNode) : QmlElement(node), PsiNameIdentifierOwner, TsHostDecl {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.IDENTIFIER)?.psi
    override fun getName(): String? = nameIdentifier?.text
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(TsPsiFactory(project).identifier(name)) ?: throw IncorrectOperationException("No name")
        return this
    }

    /** `id`s are only visible in their document. */
    override fun getUseScope(): SearchScope = if (type == Q.ID) LocalSearchScope(containingFile) else super.getUseScope()

    override fun hostType(): TsTy = QmlTypes.ofDeclaration(this)

    val typeRef: QmlTypeRef? get() = children.firstOrNull { it is QmlTypeRef } as? QmlTypeRef
    /** Binding value of a property (an object, or JavaScript). */
    val value: PsiElement?
        get() {
            var seenColon = false
            for (c in node.getChildren(null)) {
                if (c.elementType == T.COLON) seenColon = true else if (seenColon && c.psi !is com.intellij.psi.PsiWhiteSpace && c.psi.node.elementType !in T.COMMENTS) return c.psi
            }
            return null
        }
    val isAlias: Boolean get() = type == Q.PROPERTY && node.getChildren(TokenSet.create(T.SOFT_KEYWORD)).any { it.text == "alias" }
}

/** `Type { members }`: also a JavaScript scope (ids, its properties, the document's root properties). */
class QmlObject(node: ASTNode) : QmlElement(node), TsHostScope, TsHostDecl {
    val typeRef: QmlTypeRef? get() = child(Q.TYPE_REF) as? QmlTypeRef
    val typeName: String? get() = typeRef?.text
    val body: PsiElement? get() = child(Q.OBJECT_BODY)
    val members: List<PsiElement> get() = body?.children?.toList().orEmpty()
    val id: QmlNamed? get() = members.firstOrNull { it.node.elementType == Q.ID } as? QmlNamed

    override fun hostLookup(name: String, place: PsiElement): List<PsiElement> = QmlResolver.javaScriptName(name, this)
    override fun hostType(): TsTy = QmlTypes.ofObjectValue(this)

    /** In a `.qmltypes` file: the value of `name: "…"` (and other string fields). */
    fun field(key: String): String? = members.firstOrNull { it is QmlBinding && it.path == key }?.let { (it as QmlBinding).value }
        ?.takeIf { it.node.elementType == me.kevinlot.lite.typescript.parser.TsElementTypes.LITERAL }?.text?.let { TsNamedDecl.unquote(it) }
}

/** `a.b: value`. */
class QmlBinding(node: ASTNode) : QmlElement(node) {
    val target: QmlPropertyRef? get() = child(Q.PROPERTY_REF) as? QmlPropertyRef
    val path: String? get() = target?.text?.replace(" ", "")
    val value: PsiElement? get() = children.lastOrNull { it !is QmlPropertyRef }
}

/** A property name in a binding, a group or `on`: `anchors.fill`, `Layout.fillWidth`, `onClicked`. */
class QmlPropertyRef(node: ASTNode) : QmlElement(node) {
    val nameElement: PsiElement? get() = node.getChildren(TokenSet.create(T.IDENTIFIER)).lastOrNull()?.psi
    val referencedName: String? get() = nameElement?.text
    val qualifier: QmlPropertyRef? get() = firstChild as? QmlPropertyRef
    override fun getReference(): PsiReference? = nameElement?.let { QmlPropertyReference(this) }
}

/** A type name: `Rectangle`, `Controls.Button`, `list<Item>`'s `Item`. */
class QmlTypeRef(node: ASTNode) : QmlElement(node) {
    val nameElement: PsiElement? get() = node.getChildren(TokenSet.create(T.IDENTIFIER)).lastOrNull()?.psi
    val referencedName: String? get() = nameElement?.text
    /** `Controls` in `Controls.Button` (an import alias). */
    val qualifier: String? get() = text.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
    override fun getReference(): PsiReference? = nameElement?.let { QmlTypeReference(this) }
}

/** `import QtQuick.Layouts 1.15 as L` / `import "dir"`; with an alias, a JavaScript namespace (`L.Layout`). */
class QmlImport(node: ASTNode) : QmlElement(node), TsHostDecl {
    override fun hostType(): TsTy = QmlTypes.namespace(this)
    val uri: String? get() = child(Q.MODULE_URI)?.text?.replace(" ", "")
    val path: String? get() = node.findChildByType(T.STRING)?.text?.let { TsNamedDecl.unquote(it) }
    val alias: String?
        get() {
            val asToken = node.getChildren(TokenSet.create(T.SOFT_KEYWORD)).firstOrNull { it.text == "as" } ?: return null
            var n = asToken.treeNext
            while (n != null && n.elementType != T.IDENTIFIER) n = n.treeNext
            return n?.text
        }
}

object QmlPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        Q.OBJECT -> QmlObject(node)
        Q.BINDING -> QmlBinding(node)
        Q.PROPERTY_REF -> QmlPropertyRef(node)
        Q.TYPE_REF -> QmlTypeRef(node)
        Q.IMPORT -> QmlImport(node)
        in Q.DECLARATIONS -> QmlNamed(node)
        else -> QmlElement(node)
    }
}
