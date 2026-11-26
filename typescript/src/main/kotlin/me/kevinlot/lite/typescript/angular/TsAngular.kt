package me.kevinlot.lite.typescript.angular

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.TsLanguage
import me.kevinlot.lite.typescript.parser.TsElementTypes as E
import me.kevinlot.lite.typescript.psi.TsClass
import me.kevinlot.lite.typescript.psi.TsNamedDecl
import me.kevinlot.lite.typescript.psi.TsStringLiteral
import me.kevinlot.lite.typescript.resolve.TsInjectionScope
import me.kevinlot.lite.typescript.resolve.TsTy
import me.kevinlot.lite.typescript.resolve.TsTypes

/*
 * Angular templates (`*.component.html`; the XML plugin's PSI, loaded with it, see lite-angular.xml). Their expressions
 * are read as Lite TypeScript, one fragment each: `{{ … }}`, `[input]="…"`, `(output)="…"`, the headers of `@if` /
 * `@for` / `@switch` / `@case` and `@let`. A pipe is written as a call there (`a | date: 'short'` reads
 * `a | date('short')`). Names no fragment declares are the variables of the template (`@for`, `@let`, `as`, `#ref`),
 * then the members of the component class whose `templateUrl` names the file, then the pipes of the project.
 * An inline `template: \`…\`` of a `@Component` gets the same fragments, and HTML for the rest of the string.
 */

object AngularTemplates {
    private val COMPONENT = Key.create<CachedValue<TsClass?>>("lite.angular.component")
    private val LET = Regex("""\blet\s+([A-Za-z_$][\w$]*)""")
    private val VARIABLES = Key.create<CachedValue<Map<String, List<PsiElement>>>>("lite.angular.variables")

    /** What holds the template [host] is part of: the Angular file, or the string of an inline `template:`. */
    private fun templateOf(host: PsiElement): PsiElement? =
        (host as? TsStringLiteral)?.takeIf(::isInline)
            ?: host.containingFile?.originalFile?.viewProvider?.takeIf { it.baseLanguage == AngularLanguage }?.getPsi(AngularLanguage)

    /** [literal] is the `template` of a `@Component({ … })`. */
    fun isInline(literal: TsStringLiteral): Boolean {
        if ((literal.parent as? TsNamedDecl)?.takeIf { it.type == E.PROPERTY_ASSIGNMENT }?.name != "template") return false
        return PsiTreeUtil.findFirstParent(literal) { it.node?.elementType == E.DECORATOR }?.text?.startsWith("@Component") == true
    }

    /**
     * The component class of [template]: the one its decorator is on, or the one whose `templateUrl` names the file,
     * in a TypeScript file of the same folder.
     */
    private fun component(template: PsiElement): TsClass? = CachedValuesManager.getCachedValue(template, COMPONENT) {
        val found = (template as? PsiFile)?.let(::findComponent) ?: PsiTreeUtil.getParentOfType(template, TsClass::class.java)
        CachedValueProvider.Result.create(found, PsiModificationTracker.MODIFICATION_COUNT)
    }

    private fun findComponent(file: PsiFile): TsClass? {
        val sources = file.containingDirectory?.files.orEmpty().filterIsInstance<TsFile>()
        val source = sources.firstOrNull { it.text.contains("templateUrl") && it.text.contains(file.name) } ?: return null
        val named = source.text.indexOf(file.name)
        val classes = PsiTreeUtil.findChildrenOfType(source, TsClass::class.java).filter { !it.isInterface }
        return classes.firstOrNull { it.textRange.endOffset > named } ?: classes.firstOrNull()
    }

    /** [name] at [place], in a fragment of [host]: the nearest template variable declared before it, else a member of the component, else a pipe. */
    fun lookup(name: String, place: PsiElement, host: PsiElement): List<PsiElement>? {
        val template = templateOf(host) ?: return null
        val at = offset(place)
        val declared = variables(template)[name].orEmpty()
        val variable = declared.lastOrNull { offset(it) <= at } ?: declared.firstOrNull()
        return variable?.let(::listOf)
            ?: component(template)?.let { c -> TsTypes.members(TsTy.Inst(c), name).map { it.first } }.orEmpty().ifEmpty { pipes(name, template) }
    }

    /**
     * `@for` items, `@let`, `as` aliases (declarations of the fragments) and the attributes naming one (`#ref`, `let-x`),
     * by name, in the order of the template.
     */
    private fun variables(template: PsiElement): Map<String, List<PsiElement>> = CachedValuesManager.getCachedValue(template, VARIABLES) {
        val manager = InjectedLanguageManager.getInstance(template.project)
        val found = ArrayList<Pair<String, PsiElement>>()
        fun collect(tree: PsiElement, injected: Boolean) {
            PsiTreeUtil.processElements(tree) { element ->
                if (element is XmlAttribute) found += declared(element).map { it to element }
                if (injected && element is TsNamedDecl && element.type == E.VARIABLE) element.name?.let { found += it to element }
                if (!injected && element is PsiLanguageInjectionHost) manager.enumerate(element) { fragment, _ -> collect(fragment, true) }
                true
            }
        }
        ((template as? PsiFile)?.viewProvider?.allFiles ?: listOf(template)).forEach { collect(it, false) }
        CachedValueProvider.Result.create(found.sortedBy { offset(it.second) }.groupBy({ it.first }, { it.second }), PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** The classes of the project declared as the pipe [name]: `@Pipe({ name: 'x' })`. */
    private fun pipes(name: String, template: PsiElement): List<PsiElement> {
        if (!name.first().isLetter()) return emptyList()
        val declaration = Regex("""@Pipe\s*\(\s*\{[^}]*\bname\s*:\s*['"]${Regex.escape(name)}['"]""")
        val found = ArrayList<PsiElement>()
        PsiSearchHelper.getInstance(template.project).processCandidateFilesForText(
            GlobalSearchScope.projectScope(template.project), UsageSearchContext.ANY, true, name,
        ) { file ->
            val source = template.manager.findFile(file) as? TsFile ?: return@processCandidateFilesForText true
            val at = declaration.find(source.text)?.range?.first ?: return@processCandidateFilesForText true
            PsiTreeUtil.findChildrenOfType(source, TsClass::class.java).firstOrNull { it.textRange.endOffset > at }?.let(found::add)
            true
        }
        return found
    }

    /** `#ref`, `let-item`, `*matCellDef="let row; let i = index"`. */
    private fun declared(attribute: XmlAttribute): List<String> = when {
        attribute.name.startsWith("#") -> listOf(attribute.name.drop(1))
        attribute.name.startsWith("let-") -> listOf(attribute.name.drop(4))
        attribute.name.startsWith("*") -> LET.findAll(attribute.value.orEmpty()).map { it.groupValues[1] }.toList()
        else -> emptyList()
    }

    private fun offset(element: PsiElement): Int = InjectedLanguageManager.getInstance(element.project).injectedToHost(element, element.textOffset)
}

class AngularInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(AngularFragment::class.java, XmlAttributeValue::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? PsiLanguageInjectionHost ?: return
        if (context.containingFile?.viewProvider?.baseLanguage != AngularLanguage) return
        val fragments = when (context) {
            is AngularFragment -> AngularSyntax.fragments(context.kind, context.text)
            is XmlAttributeValue ->
                AngularSyntax.attribute((context.parent as? XmlAttribute)?.name.orEmpty(), context.text, ElementManipulators.getValueTextRange(host))
            else -> emptyList()
        }
        fragments.forEach { places ->
            places.fold(registrar.startInjecting(TsLanguage)) { all, place -> all.addPlace(place.prefix, place.suffix, host, place.range) }.doneInjecting()
        }
    }
}

/** An inline template: its fragments as TypeScript, the rest of the string as HTML (found by id). */
class AngularInlineInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(TsStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val literal = (context as? TsStringLiteral)?.takeIf { it.isValidHost && AngularTemplates.isInline(it) } ?: return
        val content = TextRange(1, literal.textLength - 1).takeIf { !it.isEmpty } ?: return
        val fragments = AngularSyntax.inline(literal.text, content)
        val taken = fragments.map { TextRange(it.first().range.startOffset, it.last().range.endOffset) }.sortedBy { it.startOffset }
        val markup = (listOf(content.startOffset) + taken.map { it.endOffset }).zip(taken.map { it.startOffset } + content.endOffset)
        val text = markup.joinToString("") { (from, to) -> literal.text.substring(from, to) }
        val offsets = markup.runningFold(0) { length, (from, to) -> length + to - from }
        // one HTML file per piece, the other pieces as prefix and suffix: a smart pointer into a fragment is restored in any injected file spanning it
        Language.findLanguageByID("HTML")?.let { html ->
            markup.forEachIndexed { index, (from, to) ->
                val first = when (from) { content.startOffset -> from; else -> from + 1 }
                val last = when (to) { content.endOffset -> to; else -> to - 1 }
                val piece = TextRange(first, last).takeIf { first < last } ?: return@forEachIndexed
                val start = offsets[index] + first - from
                registrar.startInjecting(html).addPlace(text.substring(0, start), text.substring(start + piece.length), literal, piece).doneInjecting()
            }
        }
        fragments.forEach { fragment ->
            fragment.fold(registrar.startInjecting(TsLanguage)) { all, place -> all.addPlace(place.prefix, place.suffix, literal, place.range) }.doneInjecting()
        }
    }
}

class AngularScope : TsInjectionScope {
    override fun lookup(name: String, place: PsiElement, host: PsiElement): List<PsiElement>? = AngularTemplates.lookup(name, place, host)
}
