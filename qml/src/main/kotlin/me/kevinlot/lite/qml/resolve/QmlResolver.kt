package me.kevinlot.lite.qml.resolve

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.qml.QmlFile
import me.kevinlot.lite.qml.psi.*
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.psi.TsFunction
import me.kevinlot.lite.typescript.psi.TsPsiFactory
import me.kevinlot.lite.qml.parser.QmlElementTypes as Q

// ---------------------------------------------------------------- references

/** A property in a binding: renaming `foo` turns `onFooChanged:` into `onBarChanged:`. */
class QmlPropertyReference(element: QmlPropertyRef) : LiteReference<QmlPropertyRef>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = QmlResolver.resolveProperty(element)

    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement {
        val old = nameToken.text
        val text = if (QmlResolver.isHandler(old)) {
            val suffix = if (old.endsWith("Changed") && resolveTargets().any { it.node.elementType == Q.PROPERTY || QmlTypes.isTypeInfo(it, "Property") }) "Changed" else ""
            "on" + newName.replaceFirstChar { it.uppercase() } + suffix
        } else newName
        return TsPsiFactory(element.project).identifier(text)
    }
}

/** A type name → its document (`Button.qml`), inline component or Qt type description. */
class QmlTypeReference(element: QmlTypeRef) :
    PsiPolyVariantReferenceBase<QmlTypeRef>(element, element.nameElement!!.textRangeInParent) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, true, incompleteCode)

    /** Renaming `Button.qml` renames its uses. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val name = element.nameElement ?: return element
        name.replace(TsPsiFactory(element.project).identifier(newElementName.removeSuffix(".qml")))
        return element
    }

    private companion object {
        val RESOLVER = ResolveCache.PolyVariantResolver<QmlTypeReference> { ref, _ ->
            QmlResolver.resolveType(ref.element).map { PsiElementResolveResult(it) }.toTypedArray()
        }
    }
}

private val PsiElement.textRangeInParent: TextRange get() = TextRange.from(startOffsetInParent, textLength)

// ---------------------------------------------------------------- resolution

/**
 * QML name lookup. Types: inline components, the document's folder (`Foo.qml`, its `qmldir`), then imports
 * (folders, modules of the import paths with their `qmldir` and `.qmltypes`, transitively). Members: the
 * object's own declarations, then its type's (QML documents, Qt prototypes). JavaScript names: ids, the
 * scope object, the component root, types, JavaScript imports, then JavaScript globals.
 */
object QmlResolver {
    private val GUARD = RecursionGuard(40)
    private val TYPE = Key.create<CachedValue<List<PsiElement>>>("lite.qml.type")
    private val PROPERTY = Key.create<CachedValue<List<PsiElement>>>("lite.qml.property")
    private val IDS = Key.create<CachedValue<Map<String, QmlNamed>>>("lite.qml.ids")
    private val HANDLER = Regex("^on[A-Z]")

    fun isHandler(name: String) = HANDLER.containsMatchIn(name)

    // ------------------------------------------------------------ types

    fun resolveType(ref: QmlTypeRef): List<PsiElement> = LiteCache.cachedNonRecursive(ref, TYPE, QmlLibraryOracle, emptyList()) {
        val file = ref.containingFile as? QmlFile ?: return@cachedNonRecursive emptyList()
        typeNamed(ref.referencedName ?: return@cachedNonRecursive emptyList(), ref.qualifier, file)
    }

    /** Targets: a [QmlFile] (a document), an inline component, or a Qt `Component` description. */
    fun typeNamed(name: String, qualifier: String?, file: QmlFile): List<PsiElement> = GUARD.run(emptyList()) {
        if (file.isTypeInfo) return@run emptyList()
        val imports = PsiTreeUtil.getChildrenOfTypeAsList(file, QmlImport::class.java)
        if (qualifier != null) {
            // `Controls.Button` (import alias), or `Document.Inline` (inline component of another document)
            val viaAlias = imports.filter { it.alias == qualifier }.flatMap { typesInImport(it, name, file) }
            if (viaAlias.isNotEmpty()) return@run viaAlias
            return@run typeNamed(qualifier, null, file).filterIsInstance<QmlFile>().flatMap { inlineComponents(it, name) }
        }
        inlineComponents(file, name).ifEmpty { null }?.let { return@run it }
        val dir = file.originalFile.virtualFile?.parent
        val manager = PsiManager.getInstance(file.project)
        dir?.let { d -> typesInDirectory(d, name, file) }?.ifEmpty { null }?.let { return@run it }
        imports.filter { it.alias == null }.flatMap { typesInImport(it, name, file) }.ifEmpty { null }?.let { return@run it }
        QmlEnvironment.builtins(file.project)?.let { b -> listOfNotNull(QmlTypes.exports(b)["QML/$name"]) }.orEmpty()
            .ifEmpty { listOfNotNull(dir?.findChild("$name.qml")?.let { manager.findFile(it) }) }
    }

    private fun inlineComponents(file: QmlFile, name: String): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(file, QmlNamed::class.java).filter { it.type == Q.INLINE_COMPONENT && it.name == name }

    private fun typesInDirectory(dir: com.intellij.openapi.vfs.VirtualFile, name: String, from: PsiFile): List<PsiElement> {
        val manager = PsiManager.getInstance(from.project)
        val declared = QmlDirs.read(dir)?.types?.get(name)?.let { dir.findFileByRelativePath(it) }
        return listOfNotNull((declared ?: dir.findChild("$name.qml"))?.let { manager.findFile(it) })
    }

    private fun typesInImport(import: QmlImport, name: String, file: QmlFile): List<PsiElement> {
        val from = file.originalFile.virtualFile ?: return emptyList()
        import.path?.let { path ->
            if (path.endsWith(".js") || path.endsWith(".mjs")) return emptyList()
            val dir = from.parent?.findFileByRelativePath(path)?.takeIf { it.isDirectory } ?: return emptyList()
            return typesInDirectory(dir, name, file)
        }
        return moduleTypes(import.uri ?: return emptyList(), name, file, HashSet())
    }

    /** Types of module [uri]: its `qmldir` documents, its `.qmltypes` exports, and the modules it imports. */
    private fun moduleTypes(uri: String, name: String, file: QmlFile, seen: MutableSet<String>): List<PsiElement> {
        if (!seen.add(uri)) return emptyList()
        val from = file.originalFile.virtualFile ?: return emptyList()
        val manager = PsiManager.getInstance(file.project)
        val out = mutableListOf<PsiElement>()
        for (dir in QmlEnvironment.moduleDirs(uri, from, file.project)) {
            val qmldir = QmlDirs.read(dir)
            if (qmldir == null) { dir.findChild("$name.qml")?.let { manager.findFile(it) }?.let { out += it }; continue }
            qmldir.types[name]?.let { dir.findFileByRelativePath(it) }?.let { manager.findFile(it) }?.let { out += it }
            for (info in qmldir.typeInfo) {
                val types = dir.findFileByRelativePath(info)?.let { manager.findFile(it) } as? QmlFile ?: continue
                QmlTypes.exports(types)["$uri/$name"]?.let { out += it }
            }
            if (out.isEmpty()) qmldir.imports.forEach { out += moduleTypes(it, name, file, seen) }
        }
        return out.distinct()
    }

    // ------------------------------------------------------------ members

    /** Members named [name] of a type target (document, inline component, Qt type) or of an object. */
    fun members(target: PsiElement, name: String): List<PsiElement> = GUARD.run(emptyList()) {
        val obj = QmlTypes.objectOf(target) ?: return@run emptyList()
        if (QmlTypes.isTypeInfo(obj, "Component")) return@run componentMembers(obj, name)
        val own = obj.members.filter { m ->
            when (m) {
                is QmlNamed -> m.type != Q.ID && m.name == name
                is TsFunction -> m.name == name
                else -> false
            }
        } + obj.members.filter { it.node.elementType == Q.ENUM }.flatMap { e -> e.children.filter { it is QmlNamed && it.name == name } }
        own.ifEmpty { obj.typeRef?.let { resolveType(it) }.orEmpty().flatMap { members(it, name) } }
    }

    /** A Qt type description: `Property`/`Signal`/`Method`/`Enum` children, enum values, then the prototype chain. */
    private fun componentMembers(component: QmlObject, name: String): List<PsiElement> {
        val own = component.members.filterIsInstance<QmlObject>().filter { m ->
            m.field("name") == name || (m.typeName == "Enum" && enumValues(m).contains(name))
        }
        if (own.isNotEmpty()) return own
        val project = component.project
        return listOfNotNull(component.field("prototype"), component.field("extension"))
            .mapNotNull { QmlTypeInfo.component(it, project) }.flatMap { members(it, name) }
    }

    private fun enumValues(enum: QmlObject): List<String> =
        enum.members.filterIsInstance<QmlBinding>().firstOrNull { it.path == "values" }?.value?.text
            ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()

    /** `Layout.fillWidth`, `Component.onCompleted`: members of the type's attached type. */
    private fun attachedMembers(types: List<PsiElement>, name: String): List<PsiElement> = types.flatMap { t ->
        val component = QmlTypes.objectOf(t)?.takeIf { QmlTypes.isTypeInfo(it, "Component") } ?: return@flatMap emptyList()
        val attached = component.field("attachedType")?.let { QmlTypeInfo.component(it, component.project) } ?: return@flatMap emptyList()
        memberOrHandler(attached, name)
    }

    private fun isTypeTarget(t: PsiElement) = t is QmlFile || t.node?.elementType == Q.INLINE_COMPONENT || QmlTypes.isTypeInfo(t, "Component")

    private fun aliasImport(file: QmlFile, name: String): QmlImport? =
        PsiTreeUtil.getChildrenOfTypeAsList(file, QmlImport::class.java).firstOrNull { it.alias == name && it.uri != null }

    /** A Qt type used as a JavaScript value: its enums and static members, else its attached object (`Theme.colorSet`). */
    fun typeValueMembers(component: QmlObject, name: String): List<PsiElement> =
        members(component, name).ifEmpty { attachedMembers(listOf(component), name) }

    /** `onClicked` → signal `clicked`; `onWidthChanged` → property `width` (or a `widthChanged` signal). */
    private fun memberOrHandler(target: PsiElement, name: String): List<PsiElement> {
        members(target, name).ifEmpty { null }?.let { return it }
        if (!isHandler(name)) return emptyList()
        val signal = name.substring(2).replaceFirstChar { it.lowercase() }
        members(target, signal).ifEmpty { null }?.let { return it }
        return if (signal.endsWith("Changed")) members(target, signal.removeSuffix("Changed")) else emptyList()
    }

    // ------------------------------------------------------------ bindings

    fun resolveProperty(ref: QmlPropertyRef): List<PsiElement> = LiteCache.cachedNonRecursive(ref, PROPERTY, QmlLibraryOracle, emptyList()) {
        val name = ref.referencedName ?: return@cachedNonRecursive emptyList()
        val q = ref.qualifier
        val file = ref.containingFile as? QmlFile
        when {
            // `Layout` in `Layout.fillWidth`, `QQC2` in `QQC2.ToolTip.text`: a type (or an import alias)
            q == null && ref.parent is QmlPropertyRef && name.first().isUpperCase() && file != null ->
                aliasImport(file, name)?.let { listOf(it) } ?: typeNamed(name, null, file)
            q != null -> resolveProperty(q).flatMap { t ->
                when {
                    t is QmlImport -> typesOf(t, name)
                    isTypeTarget(t) -> attachedMembers(listOf(t), name)
                    else -> QmlTypes.typeTargets(t).flatMap { memberOrHandler(it, name) }
                }
            }
            else -> contextTargets(ref).flatMap { memberOrHandler(it, name) }
        }
    }

    /** What a binding's names are members of: its object, or the type of its group (`font { bold: … }`). */
    private fun contextTargets(ref: QmlPropertyRef): List<PsiElement> {
        // `anchors` in `anchors.fill`: the whole path belongs to the binding
        var top: PsiElement = ref
        while (top.parent is QmlPropertyRef) top = top.parent
        // binding / group / `Behavior on x` / `required x` → object body → its object (or group)
        val owner = top.parent?.parent?.parent
        return when (owner) {
            is QmlObject -> listOf(owner)
            null -> emptyList()
            else -> if (owner.node.elementType == Q.GROUP) {
                (owner.firstChild as? QmlPropertyRef)?.let { resolveProperty(it) }.orEmpty().flatMap { QmlTypes.typeTargets(it) }
            } else emptyList()
        }
    }

    // ------------------------------------------------------------ JavaScript

    fun ids(file: PsiFile): Map<String, QmlNamed> = LiteCache.cached(file, IDS, QmlLibraryOracle) {
        PsiTreeUtil.findChildrenOfType(file, QmlNamed::class.java).filter { it.type == Q.ID && it.name != null }.associateBy { it.name!! }
    }

    /** An unqualified JavaScript name inside [scope] (the object holding the binding or function). */
    fun javaScriptName(name: String, scope: QmlObject): List<PsiElement> = GUARD.run(emptyList()) {
        val file = scope.containingFile as? QmlFile ?: return@run emptyList()
        ids(file)[name]?.let { return@run listOf(it) }
        members(scope, name).ifEmpty { null }?.let { return@run it }
        val root = PsiTreeUtil.findFirstParent(scope, true) { it.node?.elementType == Q.INLINE_COMPONENT }?.let { QmlTypes.objectOf(it) } ?: file.rootObject
        if (root != null && root != scope) members(root, name).ifEmpty { null }?.let { return@run it }
        // types: singletons, enums, attached objects (`Units.gridUnit`), and value types in annotations (`x: int`)
        typeNamed(name, null, file).ifEmpty { null }?.let { return@run it }
        javaScriptImport(file, name)?.let { return@run listOf(it) }
        QmlEnvironment.jsRoot(file.project)?.let { QmlTypes.components(it)["GlobalObject"] }?.let { members(it, name) }.orEmpty()
    }

    /** `import "util.js" as Util` (the script), `import QtQuick.Controls as QQC2` (the import: a namespace of types). */
    private fun javaScriptImport(file: QmlFile, name: String): PsiElement? {
        val import = PsiTreeUtil.getChildrenOfTypeAsList(file, QmlImport::class.java).firstOrNull { it.alias == name } ?: return null
        val path = import.path?.takeIf { it.endsWith(".js") || it.endsWith(".mjs") } ?: return import
        val vf = file.originalFile.virtualFile?.parent?.findFileByRelativePath(path) ?: return null
        return PsiManager.getInstance(file.project).findFile(vf) as? TsFile
    }

    /** Types an import brings, by name (for an aliased import used in JavaScript). */
    fun typesOf(import: QmlImport, name: String): List<PsiElement> =
        (import.containingFile as? QmlFile)?.let { typesInImport(import, name, it) }.orEmpty()
}
