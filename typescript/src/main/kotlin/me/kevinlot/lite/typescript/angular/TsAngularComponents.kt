package me.kevinlot.lite.typescript.angular

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.TsLanguage
import me.kevinlot.lite.typescript.psi.TsClass
import me.kevinlot.lite.typescript.psi.TsNamedDecl
import me.kevinlot.lite.typescript.psi.TsStringLiteral

/** One alternative of a `selector`: `button[app-btn]` is the element `button` with the attribute `app-btn`. */
class AngularSelector(val element: String?, val attributes: List<String>, val file: VirtualFile, val offset: Int)

/** The components and directives of the project by selector, and the inputs and outputs of their classes. */
object AngularComponents {
    private val ATTRIBUTE = Regex("""\[([\w-]+)[^\]]*]""")
    private val DECLARATION = Regex("""@(?:Component|Directive)\s*\(\s*\{[^@]*?\bselector\s*:\s*(['"`])([^'"`]*)\1""")
    private val ELEMENT = Regex("""^[\w-]+""")
    private val FILE_SELECTORS = Key.create<CachedValue<List<AngularSelector>>>("lite.angular.selectors.file")
    private val NOT = Regex(""":not\([^)]*\)""")
    private val PREFIXES = listOf("bindon-", "bind-", "on-")
    private val SELECTORS = Key.create<CachedValue<List<AngularSelector>>>("lite.angular.selectors")

    /** [element] is part of a template: an Angular file, or the HTML of an inline `template:`. */
    fun inTemplate(element: PsiElement): Boolean {
        if (element.containingFile?.originalFile?.viewProvider?.baseLanguage == AngularLanguage) return true
        return (InjectedLanguageManager.getInstance(element.project).getInjectionHost(element) as? TsStringLiteral)?.let(AngularTemplates::isInline) == true
    }

    /** The name a binding attribute gives: `[size]`, `(saved)`, `[(value)]`, `bind-size`, `*appIf` and a plain `size`. */
    fun bare(attribute: String): String? {
        val prefix = PREFIXES.firstOrNull(attribute::startsWith).orEmpty()
        val name = attribute.removePrefix(prefix).trim('[', ']', '(', ')', '*')
        return name.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } && !attribute.startsWith("let-") }
    }

    /** Every selector of the project. Read from the text of the files naming one; rebuilt when TypeScript changes, not on a template edit. */
    fun selectors(project: Project): List<AngularSelector> = CachedValuesManager.getManager(project).getCachedValue(project, SELECTORS, {
        val manager = PsiManager.getInstance(project)
        val found = ArrayList<AngularSelector>()
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(GlobalSearchScope.projectScope(project), UsageSearchContext.ANY, true, "selector") {
            (manager.findFile(it) as? TsFile)?.let { file -> found += selectors(file) }
            true
        }
        CachedValueProvider.Result.create(
            found, PsiModificationTracker.getInstance(project).forLanguage(TsLanguage), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    }, false)

    private fun selectors(file: TsFile): List<AngularSelector> = CachedValuesManager.getCachedValue(file, FILE_SELECTORS) {
        val found = DECLARATION.findAll(file.text).flatMap { declaration ->
            declaration.groupValues[2].split(',').map { NOT.replace(it.trim(), "") }.filter { it.isNotEmpty() }.map { alternative ->
                val attributes = ATTRIBUTE.findAll(alternative).map { it.groupValues[1] }.toList()
                AngularSelector(ELEMENT.find(alternative)?.value, attributes, file.virtualFile ?: file.originalFile.virtualFile, declaration.range.first)
            }
        }.toList()
        CachedValueProvider.Result.create(found, file)
    }

    fun classOf(selector: AngularSelector, project: Project): TsClass? {
        val file = PsiManager.getInstance(project).findFile(selector.file) as? TsFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, TsClass::class.java).firstOrNull { !it.isInterface && it.textRange.endOffset > selector.offset }
    }

    private fun names(tag: XmlTag): List<String> = tag.attributes.mapNotNull { bare(it.name) }

    /** The components [tag] is: its name is their element, and it carries the attributes their selector asks for. */
    fun components(tag: XmlTag): List<AngularSelector> =
        selectors(tag.project).filter { it.element == tag.name && names(tag).containsAll(it.attributes) }

    /** The directives the attribute [name] puts on [tag]: `[appTip]`, `button[appTip]`. */
    fun directives(tag: XmlTag, name: String): List<AngularSelector> =
        selectors(tag.project).filter { name in it.attributes && (it.element == null || it.element == tag.name) }

    /** The classes whose inputs and outputs [tag] can bind: its component and the directives its attributes name. */
    fun owners(tag: XmlTag): List<TsClass> =
        (components(tag) + names(tag).flatMap { directives(tag, it) }).distinct().mapNotNull { classOf(it, tag.project) }.distinct()

    /** The selectors declared by [cls], for the search of its tags and attributes. */
    fun selectorsOf(cls: TsClass): List<AngularSelector> =
        (cls.containingFile as? TsFile)?.let(::selectors).orEmpty().filter { classOf(it, cls.project) == cls }
}

/** A custom element → its component class. Soft: an element no selector names is not an error. */
class AngularTagReference(tag: XmlTag, range: TextRange) : PsiPolyVariantReferenceBase<XmlTag>(tag, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(AngularComponents.components(element).mapNotNull { AngularComponents.classOf(it, element.project) }.distinct())

    /** The selector does not follow the name of the class. */
    override fun handleElementRename(newElementName: String): PsiElement = element
}

/** `[size]`, `(saved)`, `[(value)]`, `size` → the input or output of the tag's component or directives, else the directive the attribute is. */
class AngularAttributeReference(attribute: XmlAttribute, range: TextRange, private val name: String) :
    PsiPolyVariantReferenceBase<XmlAttribute>(attribute, range, true) {
    private val event: Boolean get() = element.name.startsWith("(") || element.name.startsWith("on-")

    private fun bindings(): List<AngularBinding> {
        val tag = element.parent ?: return emptyList()
        return AngularComponents.owners(tag).flatMap { owner ->
            AngularMetadata.bindings(owner, name).filter { (event && it.output) || (!event && it.input) } +
                AngularMetadata.bindings(owner, name.removeSuffix("Change")).filter { event && it.input && it.output && name.endsWith("Change") }
        }
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val tag = element.parent ?: return ResolveResult.EMPTY_ARRAY
        val members = bindings().map { it.member }.distinct()
        val classes = AngularComponents.directives(tag, name).mapNotNull { AngularComponents.classOf(it, element.project) }.distinct()
        return PsiElementResolveResult.createResults(members.ifEmpty { classes })
    }

    private fun isDirect(): Boolean = bindings().any { it.member.name == name }

    /** Read when the usage is found: a rename then moves the names (a string of `inputs: […]`) the binding is resolved by. */
    override fun isReferenceTo(element: PsiElement): Boolean {
        val found = super.isReferenceTo(element)
        if (found) this.element.putUserData(DIRECT, isDirect())
        return found
    }

    /** Only a binding written as its member is named follows the rename: an alias and a directive's selector stay. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val direct = element.getUserData(DIRECT) ?: isDirect()
        element.putUserData(DIRECT, null)
        if (!direct) return element
        return element.setName(rangeInElement.replace(element.name, newElementName))
    }

    private companion object {
        val DIRECT = Key.create<Boolean>("lite.angular.direct")
    }
}

class AngularReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlTag::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val tag = (element as? XmlTag)?.takeIf { '-' in it.name && AngularComponents.inTemplate(it) } ?: return PsiReference.EMPTY_ARRAY
                if (AngularComponents.components(tag).isEmpty()) return PsiReference.EMPTY_ARRAY
                val start = tag.text.indexOf(tag.name).takeIf { it >= 0 } ?: return PsiReference.EMPTY_ARRAY
                return arrayOf(AngularTagReference(tag, TextRange.from(start, tag.name.length)))
            }
        })
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttribute::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val attribute = (element as? XmlAttribute)?.takeIf(AngularComponents::inTemplate) ?: return PsiReference.EMPTY_ARRAY
                val tag = attribute.parent ?: return PsiReference.EMPTY_ARRAY
                val name = AngularComponents.bare(attribute.name) ?: return PsiReference.EMPTY_ARRAY
                if (AngularComponents.owners(tag).isEmpty()) return PsiReference.EMPTY_ARRAY
                return arrayOf(AngularAttributeReference(attribute, TextRange.from(attribute.name.indexOf(name), name.length), name))
            }
        })
    }
}
/**
 * A component is used under its selector (`app-shape-list`, `[appTip]`) and a pipe under its name, which a search for the
 * name of the class misses; an input or output may be named in a string of the decorator, which a search in code misses.
 */
class AngularSelectorSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = params.elementToSearch
        if (target is TsClass) {
            val selectors = AngularComponents.selectorsOf(target).flatMap { listOfNotNull(it.element) + it.attributes }
            val words = (selectors + listOfNotNull(AngularMetadata.pipeName(target))).distinct()
            words.forEach { params.optimizer.searchWord(it, params.effectiveSearchScope, UsageSearchContext.ANY, true, target) }
            return
        }
        val name = (target as? TsNamedDecl)?.takeIf { it.parent is TsClass || it.parent?.parent is TsClass }?.name ?: return
        params.optimizer.searchWord(name, params.effectiveSearchScope, UsageSearchContext.IN_STRINGS, true, target)
    }
}
