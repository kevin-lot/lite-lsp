package me.kevinlot.lite.qml

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import me.kevinlot.lite.qml.psi.QmlObject
import me.kevinlot.lite.qml.resolve.QmlTypes
import me.kevinlot.lite.typescript.resolve.TsHostDecl
import me.kevinlot.lite.typescript.resolve.TsTy
import javax.swing.Icon

object QmlLanguage : Language("LiteQml") {
    private fun readResolve(): Any = QmlLanguage
    override fun getDisplayName(): String = "QML"
}

object QmlFileType : LanguageFileType(QmlLanguage) {
    override fun getName(): String = "QML (Lite)"
    override fun getDisplayName(): String = "QML"
    override fun getDescription(): String = "QML document"
    override fun getDefaultExtension(): String = "qml"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/qml.svg", QmlFileType::class.java) }
}

/** `.qmltypes`: Qt's descriptions of C++ types, written in QML syntax (`Component { Property { … } }`). */
object QmlTypesFileType : LanguageFileType(QmlLanguage, true) {
    override fun getName(): String = "QML types (Lite)"
    override fun getDisplayName(): String = "QML types"
    override fun getDescription(): String = "Qt type description"
    override fun getDefaultExtension(): String = "qmltypes"
    override fun getIcon(): Icon = QmlFileType.icon
}

/** A QML document: a component whose type name is the file name (`Button.qml` defines `Button`). */
class QmlFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, QmlLanguage), TsHostDecl {
    override fun getFileType() = if (name.endsWith(".qmltypes")) QmlTypesFileType else QmlFileType
    override fun toString(): String = "QML file"

    val rootObject: QmlObject? get() = children.firstOrNull { it is QmlObject } as? QmlObject
    val componentName: String get() = name.substringBefore('.')
    val isTypeInfo: Boolean get() = name.endsWith(".qmltypes")

    /** Used as a JavaScript value (`Style.margin` on a singleton, `Panel.Mode` on an enum): its root object. */
    override fun hostType(): TsTy = rootObject?.let { QmlTypes.instance(it) } ?: TsTy.Unknown
}
