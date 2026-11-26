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
import me.kevinlot.lite.typescript.resolve.TsTy
import me.kevinlot.lite.typescript.resolve.TsTypes

/** One alternative of a `selector`: `button[app-btn]` is the element `button` with the attribute `app-btn`. */
class AngularSelector(val element: String?, val attributes: List<String>, val file: VirtualFile, val offset: Int)

/** What a template can bind on a class: `name` as written in the template, which is the member's unless it has an alias. */
class AngularBinding(val member: TsNamedDecl, val name: String, val input: Boolean, val output: Boolean)

/** The components and directives of the project by selector, and the inputs and outputs of their classes. */
object AngularComponents {
    private val ALIAS = Regex("""(?:^\s*|\balias\s*:\s*)['"]([\w-]+)['"]""")
    private val ATTRIBUTE = Regex("""\[([\w-]+)[^\]]*]""")
    private val BINDINGS = Key.create<CachedValue<List<AngularBinding>>>("lite.angular.bindings")
    private val DECLARATION = Regex("""@(?:Component|Directive)\s*\(\s*\{[^@]*?\bselector\s*:\s*(['"`])([^'"`]*)\1""")
    private val DECORATED = Regex("""@(Input|Output)\s*\(([^)]*)\)""")
    private val ELEMENT = Regex("""^[\w-]+""")
    private val ENTRY = Regex("""['"]\s*([\w$]+)\s*(?::\s*([\w$-]+)\s*)?['"]""")
    private val FILE_SELECTORS = Key.create<CachedValue<List<AngularSelector>>>("lite.angular.selectors.file")
    private val HOST = Regex("""\{\s*directive\s*:\s*([\w$]+)([^{}]*)}""")
    private val LISTED = Regex("""\b(inputs|outputs)\s*:\s*\[([^\]]*)]""")
    private val NOT = Regex(""":not\([^)]*\)""")
    private val PREFIXES = listOf("bindon-", "bind-", "on-")
    private val SELECTORS = Key.create<CachedValue<List<AngularSelector>>>("lite.angular.selectors")
    private val SIGNAL = Regex("""^\s*[?!]?\s*(?::[^=;]*)?=\s*(input|model|output|outputFromObservable)\b(?:\.required)?\s*(?:<[^;]*?>)?\s*\(""")

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

    /** The members of [cls] named [name] in a template, inherited ones included when they are not renamed by an alias. */
    fun bindings(cls: TsClass, name: String): List<AngularBinding> {
        val own = own(cls).filter { it.name == name }
        return own.ifEmpty { TsTypes.members(TsTy.Inst(cls), name).mapNotNull { (it.first as? TsNamedDecl)?.let(::binding) }.filter { it.name == name } }
    }

    private fun own(cls: TsClass): List<AngularBinding> = CachedValuesManager.getCachedValue(cls, BINDINGS) {
        CachedValueProvider.Result.create(declared(cls) + hosted(cls), PsiModificationTracker.MODIFICATION_COUNT)
    }

    private fun declared(cls: TsClass): List<AngularBinding> {
        val listed = listed(HOST.replace(metadata(cls), "")).mapNotNull { (member, name, input) ->
            member(cls, member)?.let { AngularBinding(it, name, input = input, output = !input) }
        }
        return cls.members.filterIsInstance<TsNamedDecl>().mapNotNull(::binding) + listed
    }

    /** `hostDirectives: [{ directive: Tip, inputs: ['tipDelay: delay'] }]`: the listed bindings of `Tip`, under the name given here. */
    private fun hosted(cls: TsClass): List<AngularBinding> = HOST.findAll(metadata(cls)).flatMap { host ->
        val leaf = cls.findElementAt(host.groups[1]?.range?.first ?: return@flatMap emptySequence())
        val directive = generateSequence(leaf) { it.parent }.take(3).firstNotNullOfOrNull { it.reference?.resolve() as? TsClass }
        listed(host.groupValues[2]).asSequence().flatMap { (member, name, input) ->
            directive?.let(::declared).orEmpty().filter { it.name == member && it.input == input }
                .map { AngularBinding(it.member, name, input = input, output = !input) }
        }
    }.toList()

    /** The entries of `inputs: ['size', 'label: caption']` and `outputs: […]`: member, name in a template, input or not. */
    private fun listed(text: String): List<Triple<String, String, Boolean>> = LISTED.findAll(text).flatMap { list ->
        ENTRY.findAll(list.groupValues[2]).map { Triple(it.groupValues[1], it.groupValues[2].ifEmpty { it.groupValues[1] }, list.groupValues[1] == "inputs") }
    }.toList()

    private fun member(cls: TsClass, name: String): TsNamedDecl? =
        TsTypes.members(TsTy.Inst(cls), name).firstNotNullOfOrNull { it.first as? TsNamedDecl }

    /** The decorators of [cls]: its text up to its name. */
    private fun metadata(cls: TsClass): String =
        cls.nameIdentifier?.let { cls.text.substring(0, it.textRange.startOffset - cls.textRange.startOffset) }.orEmpty()

    /** `@Input() x`, `@Output() x`, `x = input()`, `x = model()` (an input, and the output `xChange`), `x = output()`. */
    private fun binding(member: TsNamedDecl): AngularBinding? {
        val identifier = member.nameIdentifier ?: return null
        val name = member.name ?: return null
        val at = identifier.textRange.startOffset - member.textRange.startOffset
        val decorated = DECORATED.find(member.text.substring(0, at))
        val tail = member.text.substring(at + identifier.textLength).take(300)
        val signal = SIGNAL.find(tail)?.groupValues?.get(1)
        val kind = decorated?.groupValues?.get(1)?.lowercase() ?: signal ?: return null
        val alias = ALIAS.find(decorated?.groupValues?.get(2) ?: tail.substringBefore('\n'))?.groupValues?.get(1)
        return AngularBinding(member, alias ?: name, input = kind == "input" || kind == "model", output = kind.startsWith("output") || kind == "model")
    }
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
            AngularComponents.bindings(owner, name).filter { (event && it.output) || (!event && it.input) } +
                AngularComponents.bindings(owner, name.removeSuffix("Change")).filter { event && it.input && it.output && name.endsWith("Change") }
        }
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val tag = element.parent ?: return ResolveResult.EMPTY_ARRAY
        val members = bindings().map { it.member }.distinct()
        val classes = AngularComponents.directives(tag, name).mapNotNull { AngularComponents.classOf(it, element.project) }.distinct()
        return PsiElementResolveResult.createResults(members.ifEmpty { classes })
    }

    /** Only a binding written as its member is named follows the rename: an alias and a directive's selector stay. */
    override fun handleElementRename(newElementName: String): PsiElement {
        if (bindings().none { it.member.name == name }) return element
        return element.setName(rangeInElement.replace(element.name, newElementName))
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

/** A component is used under its selector (`app-shape-list`, `[appTip]`), which a search for the name of its class misses. */
class AngularSelectorSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = params.elementToSearch as? TsClass ?: return
        val words = AngularComponents.selectorsOf(target).flatMap { listOfNotNull(it.element) + it.attributes }.distinct()
        words.forEach { params.optimizer.searchWord(it, params.effectiveSearchScope, UsageSearchContext.ANY, true, target) }
    }
}
