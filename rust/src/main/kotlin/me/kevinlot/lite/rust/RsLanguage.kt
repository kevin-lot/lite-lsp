package me.kevinlot.lite.rust

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import me.kevinlot.lite.rust.psi.RsItemsOwner
import javax.swing.Icon

/** Distinct id from the official plugin ("Rust") so both can coexist on disk. */
object RsLanguage : Language("LiteRust") {
    private fun readResolve(): Any = RsLanguage
    override fun getDisplayName(): String = "Rust"
    override fun isCaseSensitive(): Boolean = true
}

object RsFileType : LanguageFileType(RsLanguage) {
    override fun getName(): String = "Rust (Lite)"
    override fun getDescription(): String = "Rust source file"
    override fun getDefaultExtension(): String = "rs"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/rust.svg", RsFileType::class.java) }
}

class RsFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, RsLanguage), RsItemsOwner {
    override fun getFileType() = RsFileType
    override fun toString(): String = "Rust file"
}
