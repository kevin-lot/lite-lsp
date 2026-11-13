package me.kevinlot.lite.typescript

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import javax.swing.Icon

/**
 * One language for TypeScript and JavaScript (TypeScript is a superset: one parser, one resolver).
 * Distinct id from the official plugin ("TypeScript", "JavaScript") so both can coexist on disk.
 */
object TsLanguage : Language("LiteTypeScript") {
    private fun readResolve(): Any = TsLanguage
    override fun getDisplayName(): String = "TypeScript/JavaScript"
}

object TsFileType : LanguageFileType(TsLanguage) {
    override fun getName(): String = "TypeScript (Lite)"
    override fun getDisplayName(): String = "TypeScript"
    override fun getDescription(): String = "TypeScript source file"
    override fun getDefaultExtension(): String = "ts"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/typescript.svg", TsFileType::class.java) }
}

object JsFileType : LanguageFileType(TsLanguage, true) {
    override fun getName(): String = "JavaScript (Lite)"
    override fun getDisplayName(): String = "JavaScript"
    override fun getDescription(): String = "JavaScript source file"
    override fun getDefaultExtension(): String = "js"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/javascript.svg", JsFileType::class.java) }
}

class TsFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TsLanguage) {
    override fun getFileType() = if (isJavaScript(name)) JsFileType else TsFileType
    override fun toString(): String = "TypeScript file"
    val isDeclarationFile: Boolean get() = isDeclaration(name)

    companion object {
        private val JS = setOf("js", "jsx", "mjs", "cjs")
        fun isJavaScript(name: String) = name.substringAfterLast('.') in JS
        fun isDeclaration(name: String) = name.endsWith(".d.ts") || name.endsWith(".d.mts") || name.endsWith(".d.cts")
        /** JSX everywhere but in `.ts` files (where `<T>x` is a type assertion). */
        fun hasJsx(name: String?) = name == null || name.substringAfterLast('.') !in setOf("ts", "mts", "cts")
    }
}
