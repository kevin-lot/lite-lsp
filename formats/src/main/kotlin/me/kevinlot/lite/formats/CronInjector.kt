package me.kevinlot.lite.formats

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/** Where YAML and HCL hold a cron expression: the value becomes [CronLanguage]. */
class CronInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(FmtWord::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val word = context as? FmtWord ?: return
        val range = CronPlaces.expression(word) ?: return
        registrar.startInjecting(CronLanguage).addPlace(null, null, word, range).doneInjecting()
    }
}

object CronPlaces {
    private val AWS_CALL = Regex("""(?:cron|rate)\(.*\)""")
    private val CRON_LIKE = Regex("""[\d*/,?#LW-]+|(?i:[a-z]{3})(?:[-,](?i:[a-z]{3}))*""")
    private val KEY = Regex("""(?:^|[\s{,-])["']?(cron|schedule\w*)["']?[ \t]*[:=][ \t]*$""")
    private val BLANKS = Regex("""\s+""")
    private val HOSTS = setOf(Cat.STRING, Cat.WORD)

    /**
     * The part of [word] (offsets inside it) that is a cron expression: a whole value written `cron(…)` / `rate(…)`,
     * the value of a `cron` key, or the value of a `schedule…` key that reads as five to seven cron fields.
     */
    fun expression(word: FmtWord): TextRange? {
        val format = (word.containingFile as? FmtFile)?.format ?: return null
        if (format != Yaml && format != Hcl) return null
        if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat !in HOSTS) return null
        val range = content(word.text) ?: return null
        val value = range.substring(word.text)
        // a template writes the expression at run time: its text is not one yet
        if ("\${" in value || "{{" in value) return null
        if (AWS_CALL.matches(value)) return range
        val key = keyBefore(word) ?: return null
        if (key == "cron") return range
        return range.takeIf { looksLikeFields(value) }
    }

    /** Without the quotes and the blanks around. */
    private fun content(text: String): TextRange? {
        val quoted = text.length >= 2 && text.first() in "\"'" && text.last() == text.first()
        val inner = when {
            quoted -> TextRange(1, text.length - 1)
            else -> TextRange(0, text.length)
        }
        val body = inner.substring(text)
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return TextRange.from(inner.startOffset + body.indexOf(trimmed), trimmed.length)
    }

    private fun keyBefore(word: FmtWord): String? {
        // the shared text of the file: `getText()` would copy it for every value
        val text = word.containingFile.viewProvider.contents
        val start = word.textRange.startOffset
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
        return KEY.find(text.subSequence(lineStart, start))?.groupValues?.get(1)
    }

    private fun looksLikeFields(value: String): Boolean {
        val fields = value.split(BLANKS)
        return fields.size in 4..7 && fields.all(CRON_LIKE::matches) && fields.any { field -> field.any { it.isDigit() || it == '*' } }
    }
}
