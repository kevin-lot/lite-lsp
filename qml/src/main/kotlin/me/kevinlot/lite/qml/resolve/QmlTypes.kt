package me.kevinlot.lite.qml.resolve

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.qml.QmlFile
import me.kevinlot.lite.qml.psi.QmlNamed
import me.kevinlot.lite.qml.psi.QmlObject
import me.kevinlot.lite.typescript.resolve.TsHostType
import me.kevinlot.lite.typescript.resolve.TsTy
import me.kevinlot.lite.typescript.resolve.TsTypes
import me.kevinlot.lite.qml.parser.QmlElementTypes as Q

/** QML values seen from JavaScript: objects (and Qt types) whose members are QML members. */
object QmlTypes {
    private val COMPONENTS = Key.create<CachedValue<Map<String, QmlObject>>>("lite.qml.components")
    private val EXPORTS = Key.create<CachedValue<Map<String, QmlObject>>>("lite.qml.exports")
    /** QML basic types and C++ value types → the JavaScript builtin describing their members (jsroot.qmltypes). */
    private val BASIC = mapOf(
        "string" to "String", "QString" to "String", "url" to "String", "QUrl" to "String",
        "int" to "Number", "real" to "Number", "double" to "Number", "number" to "Number", "float" to "Number", "qreal" to "Number",
        "bool" to "Boolean", "list" to "Array", "QVariantList" to "Array", "QStringList" to "Array", "date" to "Date", "QDateTime" to "Date",
    )

    /** A QML object (or Qt type description) as a JavaScript value. */
    data class Instance(val obj: QmlObject) : TsHostType {
        override fun hostMembers(name: String): List<PsiElement> = QmlResolver.members(obj, name)
    }

    fun instance(obj: QmlObject): TsTy = TsTy.Host(Instance(obj))

    /** A Qt type named in JavaScript (`Text.AlignLeft`, `Theme.colorSet`). */
    data class TypeValue(val component: QmlObject) : TsHostType {
        override fun hostMembers(name: String): List<PsiElement> = QmlResolver.typeValueMembers(component, name)
    }

    /** `import org.kde.kirigami as Kirigami`: `Kirigami.Units` is a type of that module. */
    data class Namespace(val import: me.kevinlot.lite.qml.psi.QmlImport) : TsHostType {
        override fun hostMembers(name: String): List<PsiElement> = QmlResolver.typesOf(import, name)
    }

    fun namespace(import: me.kevinlot.lite.qml.psi.QmlImport): TsTy = TsTy.Host(Namespace(import))

    fun isTypeInfo(e: PsiElement, kind: String) = e is QmlObject && e.typeName == kind && (e.containingFile as? QmlFile)?.isTypeInfo == true

    /** The object behind a type target: a document's root, an inline component's object, a Qt `Component`. */
    fun objectOf(target: PsiElement): QmlObject? = when {
        target is QmlFile -> target.rootObject
        target is QmlObject -> target
        target.node?.elementType == Q.INLINE_COMPONENT -> PsiTreeUtil.getChildOfType(target, QmlObject::class.java)
        else -> null
    }

    /** `Component`s of a `.qmltypes` file by C++ name. */
    fun components(file: QmlFile): Map<String, QmlObject> = LiteCache.cached(file, COMPONENTS, QmlLibraryOracle) {
        file.rootObject?.members.orEmpty().filterIsInstance<QmlObject>().filter { it.typeName == "Component" }
            .mapNotNull { c -> c.field("name")?.let { it to c } }.toMap()
    }

    /** `Component`s of a `.qmltypes` file by exported QML name: `QtQuick/Item`. */
    fun exports(file: QmlFile): Map<String, QmlObject> = LiteCache.cached(file, EXPORTS, QmlLibraryOracle) {
        val out = HashMap<String, QmlObject>()
        for (c in components(file).values) {
            val list = c.members.filterIsInstance<me.kevinlot.lite.qml.psi.QmlBinding>().firstOrNull { it.path == "exports" }?.value?.text ?: continue
            Regex("\"([^\" ]+)[^\"]*\"").findAll(list).forEach { out.putIfAbsent(it.groupValues[1], c) }
        }
        out
    }

    /** Type of a QML declaration used as a JavaScript value. */
    fun ofDeclaration(d: QmlNamed): TsTy = when (d.type) {
        Q.ID -> (d.parent?.parent as? QmlObject)?.let { instance(it) } ?: TsTy.Unknown
        Q.PROPERTY -> propertyType(d)
        Q.INLINE_COMPONENT -> objectOf(d)?.let { instance(it) } ?: TsTy.Unknown
        else -> TsTy.Unknown
    }

    private fun propertyType(p: QmlNamed): TsTy {
        val value = p.value
        if (p.isAlias) return TsTypes.exprTy(value)
        if (value is QmlObject) return instance(value)
        val typeName = p.typeRef?.referencedName ?: return TsTy.Unknown
        BASIC[typeName]?.let { return builtin(it, p) }
        if (typeName == "var" || typeName == "variant") return TsTypes.exprTy(value)
        return p.typeRef?.let { QmlResolver.resolveType(it) }.orEmpty().firstNotNullOfOrNull { objectOf(it) }?.let { instance(it) } ?: TsTy.Unknown
    }

    /** A Qt description used as a value: a `Property` → its type, a `Component` → itself; a QML object → itself. */
    fun ofObjectValue(obj: QmlObject): TsTy {
        if (isTypeInfo(obj, "Property")) {
            val type = obj.field("type") ?: return TsTy.Unknown
            BASIC[type]?.let { return builtin(it, obj) }
            return QmlTypeInfo.component(type.removeSuffix("*"), obj.project)?.let { instance(it) } ?: TsTy.Unknown
        }
        if (isTypeInfo(obj, "Method") || isTypeInfo(obj, "Signal") || isTypeInfo(obj, "Enum")) return TsTy.Unknown
        if (isTypeInfo(obj, "Component")) return TsTy.Host(TypeValue(obj))
        return instance(obj)
    }

    private fun builtin(name: String, place: PsiElement): TsTy =
        QmlEnvironment.jsRoot(place.project)?.let { components(it)[name] }?.let { instance(it) } ?: TsTy.Unknown

    /** Type targets of a member (for `anchors.fill`, `font { … }`): a property's type, a Qt property's C++ type. */
    fun typeTargets(member: PsiElement): List<PsiElement> = when {
        member is QmlNamed && member.type == Q.PROPERTY ->
            (member.value as? QmlObject)?.let { listOf(it) } ?: member.typeRef?.let { QmlResolver.resolveType(it) }.orEmpty()
        isTypeInfo(member, "Property") -> listOfNotNull((member as QmlObject).field("type")?.let { QmlTypeInfo.component(it.removeSuffix("*"), member.project) })
        else -> emptyList()
    }
}
