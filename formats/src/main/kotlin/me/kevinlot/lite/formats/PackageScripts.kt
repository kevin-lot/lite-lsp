package me.kevinlot.lite.formats

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext

/*
 * The `scripts` of a `package.json` (the JSON plugin's PSI; loaded with it, see lite-json.xml): each one is read as
 * Lite Shell, and the script a package manager runs there (`yarn build`, `npm run lint`, `run-s a b`) points to its entry.
 */
object PackageScripts {
    private val NAME = Regex("""(?<![\w:.-])[\w:.-]+""")
    private val NPM_SHORT = Regex("""(?<![\w./-])npm\s+(restart|start|stop|test)(?![\w:.-])""")
    private val RUN = Regex("""(?<![\w./-])(?:(?:bun|pnpm|yarn)\s+(?:run\s+)?|npm\s+run(?:-script)?\s+)(?:-[\w-]+\s+)*([\w:.-]+)""")
    private val RUN_ALL = Regex("""(?<![\w./-])(?:npm-run-all|run-p|run-s)((?:\s+[\w:.-]+)+)""")

    /** The `scripts` object of [file], a `package.json`. */
    fun scripts(file: PsiFile?): JsonObject? =
        ((file as? JsonFile)?.takeIf { it.name == "package.json" }?.topLevelValue as? JsonObject)?.findProperty("scripts")?.value as? JsonObject

    /** [literal] is the command line of a script. */
    fun isScript(literal: JsonStringLiteral): Boolean {
        val property = literal.parent as? JsonProperty ?: return false
        return property.value === literal && literal.textLength > 2 && literal.text.endsWith('"') && property.parent == scripts(literal.containingFile)
    }

    /** The scripts of the file that [literal] runs (ranges in its text). */
    fun names(literal: JsonStringLiteral): List<TextRange> {
        val declared = scripts(literal.containingFile)?.propertyList.orEmpty().map { it.name }.toSet()
        val text = literal.text
        val listed = RUN_ALL.findAll(text).flatMap { all -> NAME.findAll(text.substring(all.group(1).range)).map { range(all.group(1).range.first, it.range) } }
        val single = (NPM_SHORT.findAll(text) + RUN.findAll(text)).map { range(0, it.group(1).range) }
        return (listed + single).filter { it.substring(text) in declared }.distinct().sortedBy { it.startOffset }.toList()
    }
}

class PackageScriptInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(JsonStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val literal = (context as? JsonStringLiteral)?.takeIf(PackageScripts::isScript) ?: return
        val host = literal as? PsiLanguageInjectionHost ?: return
        val shell = Language.findLanguageByID("LiteShell") ?: return
        val escapes = ESCAPE.findAll(literal.text).map { TextRange(it.range.first, it.range.last + 1) to it.value.drop(1) }
        Scripts.without(TextRange(1, literal.textLength - 1), PackageScripts.names(literal).map { it to Scripts.PLACEHOLDER } + escapes)
            .fold(registrar.startInjecting(shell)) { places, piece -> places.addPlace(piece.prefix, piece.suffix, host, piece.range) }.doneInjecting()
    }

    private companion object {
        val ESCAPE = Regex("""\\["\\/]""")
    }
}

class PackageScriptContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = (element as? JsonStringLiteral)?.takeIf(PackageScripts::isScript) ?: return PsiReference.EMPTY_ARRAY
                return PackageScripts.names(literal).map { PackageScriptReference(literal, it) }.toTypedArray()
            }
        })
    }
}

class PackageScriptReference(literal: JsonStringLiteral, range: TextRange) : PsiPolyVariantReferenceBase<JsonStringLiteral>(literal, range) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(
            listOfNotNull(PackageScripts.scripts(element.containingFile)?.findProperty(rangeInElement.substring(element.text))),
        )

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName) ?: element
}

/** The scripts a script runs keep a color of their own: the shell does not see them. */
class PackageScriptNamesAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val literal = (element as? JsonStringLiteral)?.takeIf(PackageScripts::isScript) ?: return
        val key = Cat.FUNCTION.key ?: return
        val start = literal.textRange.startOffset
        PackageScripts.names(literal).forEach {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(it.shiftRight(start)).textAttributes(key).create()
        }
    }
}
