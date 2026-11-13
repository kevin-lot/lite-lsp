package me.kevinlot.lite.formats

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * HTML: `<script>` is Lite JavaScript, `<style>` Lite CSS, and so are `on…="…"` and `style="…"` attributes
 * (the official JavaScript and CSS plugins being disabled). Languages are found by id: nothing to compile against.
 */
class HtmlInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java, XmlAttributeValue::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? PsiLanguageInjectionHost ?: return
        val id = languageFor(context) ?: return
        val language = Language.findLanguageByID(id) ?: return
        val range = ElementManipulators.getValueTextRange(host)
        if (range.isEmpty) return
        // a `style` attribute holds declarations: wrapped in a rule so they read as one
        val (prefix, suffix) = if (context is XmlAttributeValue && id == "LiteCss") "x{" to "}" else null to null
        registrar.startInjecting(language).addPlace(prefix, suffix, host, range).doneInjecting()
    }

    /** The Lite language of an HTML host: `<script>`/`<style>` text, `style=`/`on…=` values (null: none). */
    fun languageFor(context: PsiElement): String? {
        if (context.containingFile?.language?.id?.let { it == "HTML" || it == "XHTML" || it.endsWith("HTML") } != true) return null
        return when (context) {
            is XmlText -> when (context.parentTag?.name?.lowercase()) {
                "script" -> scriptLanguage(context.parentTag!!)
                "style" -> "LiteCss"
                else -> null
            }
            is XmlAttributeValue -> (context.parent as? XmlAttribute)?.name?.lowercase()?.let { name ->
                when {
                    name == "style" -> "LiteCss"
                    name.startsWith("on") && name.length > 2 -> "LiteTypeScript"
                    else -> null
                }
            }
            else -> null
        }
    }

    /** No `type`, a JavaScript type or `module`; not JSON, templates or other languages. */
    private fun scriptLanguage(tag: XmlTag): String? {
        val type = tag.getAttributeValue("type")?.lowercase() ?: return "LiteTypeScript"
        return if (type == "module" || type.contains("javascript") || type.contains("ecmascript")) "LiteTypeScript" else null
    }
}
