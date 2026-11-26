package me.kevinlot.lite.typescript.angular

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.typescript.psi.TsClass
import me.kevinlot.lite.typescript.psi.TsNamedDecl
import me.kevinlot.lite.typescript.psi.TsStringLiteral
import me.kevinlot.lite.typescript.resolve.TsTy
import me.kevinlot.lite.typescript.resolve.TsTypes

/** What a template can bind on a class: `name` as written in the template, which is the member's unless it has an alias. */
class AngularBinding(val member: TsNamedDecl, val name: String, val input: Boolean, val output: Boolean)

/** What the decorators of a class say: its inputs and outputs, those of its host directives, the name of a pipe. TypeScript only: no XML. */
object AngularMetadata {
    private val ALIAS = Regex("""(?:^\s*|\balias\s*:\s*)['"]([\w-]+)['"]""")
    private val BINDINGS = Key.create<CachedValue<List<AngularBinding>>>("lite.angular.bindings")
    private val DECORATED = Regex("""@(Input|Output)\s*\(([^)]*)\)""")
    private val ENTRY = Regex("""['"]\s*([\w$]+)\s*(?::\s*([\w$-]+)\s*)?['"]""")
    private val HOST = Regex("""\{\s*directive\s*:\s*([\w$]+)([^{}]*)}""")
    private val LISTED = Regex("""\b(inputs|outputs)\s*:\s*\[([^\]]*)]""")
    private val PIPE = Regex("""@Pipe\s*\(\s*\{[^}]*\bname\s*:\s*['"]([\w$-]+)['"]""")
    private val SIGNAL = Regex("""^\s*[?!]?\s*(?::[^=;]*)?=\s*(input|model|output|outputFromObservable)\b(?:\.required)?\s*(?:<[^;]*?>)?\s*\(""")

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
        val directive = directive(cls, host)
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

    /**
     * The member a string of `inputs: […]` / `outputs: […]` names, in the decorator of a class or in one of its `hostDirectives`,
     * with the range of the name in the string (`shade` of `'shade: tint'`).
     */
    fun listed(literal: TsStringLiteral): Pair<TsNamedDecl, TextRange>? {
        val cls = PsiTreeUtil.getParentOfType(literal, TsClass::class.java) ?: return null
        val at = literal.textRange.startOffset - cls.textRange.startOffset
        val metadata = metadata(cls).takeIf { at < it.length } ?: return null
        val list = LISTED.findAll(metadata).firstOrNull { it.groups[2]?.range?.contains(at) == true } ?: return null
        val name = ENTRY.find(literal.text)?.groups?.get(1) ?: return null
        val host = HOST.findAll(metadata).firstOrNull { at in it.range }
        val owner = host?.let { directive(cls, it) ?: return null } ?: cls
        val input = list.groupValues[1] == "inputs"
        val member = when (host) {
            null -> member(owner, name.value)
            else -> declared(owner).firstOrNull { it.name == name.value && it.input == input && it.member.name == name.value }?.member
        } ?: return null
        return member to TextRange(name.range.first, name.range.last + 1)
    }

    /** The name of the pipe [cls] is: `@Pipe({ name: 'money' })`. */
    fun pipeName(cls: TsClass): String? = PIPE.find(metadata(cls))?.groupValues?.get(1)

    private fun directive(cls: TsClass, host: MatchResult): TsClass? {
        val leaf = cls.findElementAt(host.groups[1]?.range?.first ?: return null)
        return generateSequence(leaf) { it.parent }.take(3).firstNotNullOfOrNull { it.reference?.resolve() as? TsClass }
    }

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

/** The name of a member in `inputs: ['size', 'shade: tint']`: it follows a rename of the member. */
class AngularListedReference(literal: TsStringLiteral, range: TextRange, private val member: TsNamedDecl) :
    PsiReferenceBase<TsStringLiteral>(literal, range, true) {
    override fun resolve(): PsiElement = member
    override fun handleElementRename(newElementName: String): PsiElement = element.updateText(rangeInElement.replace(element.text, newElementName))
}
