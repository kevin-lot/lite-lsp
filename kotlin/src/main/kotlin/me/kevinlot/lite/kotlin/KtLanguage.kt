package me.kevinlot.lite.kotlin

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import javax.swing.Icon

/** Distinct id from the official plugin ("kotlin") so both can coexist on disk. */
object KtLanguage : Language("LiteKotlin") {
    private fun readResolve(): Any = KtLanguage
    override fun getDisplayName(): String = "Kotlin"
}

object KtFileType : LanguageFileType(KtLanguage) {
    override fun getName(): String = "Kotlin (Lite)"
    override fun getDescription(): String = "Kotlin source file"
    override fun getDefaultExtension(): String = "kt"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/kotlin.svg", KtFileType::class.java) }
}

class KtFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, KtLanguage) {
    override fun getFileType() = KtFileType
    override fun toString(): String = "Kotlin file"
    val isScript: Boolean get() = name.endsWith(".kts")
}
