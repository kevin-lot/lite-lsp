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
import com.intellij.psi.tree.IElementType
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

/** A stretch of the host read by TypeScript, with the text standing before and after it. */
class AngularPlace(val range: TextRange, val prefix: String, val suffix: String)

object AngularSyntax {
    private class Edit(val range: TextRange, val text: String)

    private val ALIAS = Regex("""^\s*as\s+([A-Za-z_$][\w$]*)\s*$""")
    private val BINDING = Regex("""([\[(][^\s=<>"'`]+[\])])\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val FOR_ITEM = Regex("""^(\s*)([A-Za-z_$][\w$]*)(\s+of\s+)(?=\S)""")
    // `(click)="delete.emit()"`, `{{ class }}`: a member may be named like a keyword, which TypeScript only reads after `this.`
    private val KEYWORD_MEMBER = Regex(
        """^\s*(?:break|case|catch|class|continue|default|delete|do|export|import|return|switch|throw|try|var|while|with)\s*(?:[.(]|$)""",
    )
    private val WHEN = Regex("""^\s*(?:(?:hydrate|prefetch)\s+)?when\s+(?=\S)""")
    private val TRACK = Regex("""^\s*track\s+(?=\S)""")
    private val VARIABLES = Regex("""^\s*let\s+\S""")
    val KEYWORD = Regex("""@(?:case|default|defer|else(?:\s+if)?|empty|error|for|if|let|loading|placeholder|switch)(?![\w-])""")
    private val WITH_HEADER = setOf(AngularTokens.HEADER, AngularTokens.LOOP)

    /** The fragments of a piece of template syntax of [kind]. */
    fun fragments(kind: IElementType, text: String): List<List<AngularPlace>> {
        val all = TextRange(0, text.length)
        return when (kind) {
            AngularTokens.DECLARATION -> listOfNotNull(declaration(text, all))
            AngularTokens.EXPRESSION -> listOfNotNull(expression(text, all))
            AngularTokens.TRIGGERS -> parts(text, all).mapNotNull { part ->
                WHEN.find(part.substring(text))?.let { expression(text, TextRange(part.startOffset + it.value.length, part.endOffset)) }
            }
            in WITH_HEADER -> header(text, all, kind == AngularTokens.LOOP)
            else -> emptyList()
        }
    }

    /** The fragments of a template written in [range] of [text] (a string of TypeScript): its template syntax and its bindings. */
    fun inline(text: String, range: TextRange): List<List<AngularPlace>> {
        val lexer = AngularLexer()
        lexer.start(text, range.startOffset, range.endOffset, 0)
        val found = ArrayList<List<AngularPlace>>()
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            val token = text.substring(start, lexer.tokenEnd)
            val fragments = when (val kind = lexer.tokenType) {
                AngularTokens.CONTENT -> BINDING.findAll(token).flatMap { attribute(it.groupValues[1], token, range(0, it.groups[2] ?: it.groups[3])) }.toList()
                else -> fragments(kind ?: AngularTokens.CONTENT, token)
            }
            found += fragments.map { places -> places.map { AngularPlace(it.range.shiftRight(start), it.prefix, it.suffix) } }
            lexer.advance()
        }
        return found
    }

    /** The fragment of an attribute value: all of it, for a binding. */
    fun attribute(name: String, text: String, value: TextRange): List<List<AngularPlace>> = when {
        value.isEmpty -> emptyList()
        name.startsWith("[") -> listOfNotNull(expression(text, value))
        name.startsWith("(") -> listOf(listOf(AngularPlace(value, "this.".takeIf { KEYWORD_MEMBER.containsMatchIn(value.substring(text)) }.orEmpty(), "\n")))
        else -> emptyList()
    }

    /** `item of items; track item.id; let i = $index`, `cond; as alias`, or one expression. */
    private fun header(text: String, range: TextRange, loop: Boolean): List<List<AngularPlace>> =
        parts(text, range).mapIndexedNotNull { index, part ->
            val source = part.substring(text)
            val item = FOR_ITEM.find(source)?.takeIf { loop && index == 0 }
            val alias = ALIAS.find(source)?.takeIf { !loop && index > 0 }
            val track = TRACK.find(source)?.takeIf { loop }
            when {
                item != null -> iteration(text, part, item)
                alias != null -> listOf(AngularPlace(range(part.startOffset, alias.groups[1]), "const ", " = 0;\n"))
                track != null -> expression(text, TextRange(part.startOffset + track.value.length, part.endOffset))
                loop && VARIABLES.containsMatchIn(source) -> listOf(AngularPlace(part, "", ";\n"))
                loop || index > 0 -> null
                else -> expression(text, part)
            }
        }

    private fun iteration(text: String, part: TextRange, item: MatchResult): List<AngularPlace>? {
        val name = range(part.startOffset, item.groups[2])
        val items = expression(text, TextRange(part.startOffset + item.value.length, part.endOffset)) ?: return null
        val last = items.last()
        return listOf(AngularPlace(name, "for (const ", " of ")) + items.dropLast(1) +
            AngularPlace(last.range, last.prefix, last.suffix.replace(");\n", ")) {}\n"))
    }

    /** `name = expression` of a `@let`. */
    private fun declaration(text: String, range: TextRange): List<AngularPlace>? {
        val equals = (range.startOffset until range.endOffset).firstOrNull { text[it] == '=' } ?: return null
        val value = expression(text, TextRange(equals + 1, range.endOffset)) ?: return null
        return listOf(AngularPlace(TextRange(range.startOffset, equals + 1), "const ", "")) + value
    }

    /** [range] as one expression in parentheses, its pipes written as calls; null when it is blank. */
    fun expression(text: String, range: TextRange): List<AngularPlace>? {
        val source = range.substring(text).ifBlank { return null }
        return places(range, pipes(text, range), "(" + "this.".takeIf { KEYWORD_MEMBER.containsMatchIn(source) }.orEmpty(), ");\n")
    }

    /** `| name: a : b` → `| name( a , b)`: the colons of a pipe become the punctuation of a call, in each pair of brackets. */
    private fun pipes(text: String, range: TextRange): List<Edit> {
        val edits = ArrayList<Edit>()
        val open = ArrayDeque(listOf(Group()))
        fun close(at: Int) {
            if (open.last().arguments) edits += Edit(TextRange(at, at), ")")
            open.last().arguments = false
            open.last().ternaries = 0
        }
        var quote: Char? = null
        var at = range.startOffset
        while (at < range.endOffset) {
            val c = text[at]
            val group = open.last()
            val single = c == '|' && text.getOrNull(at + 1) != '|' && text.getOrNull(at - 1) != '|'
            val question = c == '?' && text.getOrNull(at + 1) != '.' && text.getOrNull(at + 1) != '?' && text.getOrNull(at - 1) != '?'
            when {
                quote != null && c == '\\' -> at++
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' -> open += Group()
                c == ')' || c == ']' || c == '}' -> { close(at); if (open.size > 1) open.removeLast() }
                c == ',' -> { close(at); group.piped = false }
                single -> { close(at); group.piped = true }
                question && group.piped -> group.ternaries++
                c == ':' && group.piped && group.ternaries > 0 -> group.ternaries--
                c == ':' && group.piped -> { edits += Edit(TextRange(at, at + 1), when { group.arguments -> ","; else -> "(" }); group.arguments = true }
            }
            at++
        }
        close(range.endOffset)
        return edits
    }

    /** The brackets open at a point of an expression (or the expression itself): each has its own pipes. */
    private class Group {
        var arguments = false
        var piped = false
        var ternaries = 0
    }

    /** [range] cut at its top-level `;`. */
    private fun parts(text: String, range: TextRange): List<TextRange> {
        val cuts = ArrayList<Int>()
        scan(text, range) { at, c -> if (c == ';') cuts += at }
        return (listOf(range.startOffset) + cuts.map { it + 1 }).zip(cuts + range.endOffset).map { (from, to) -> TextRange(from, to) }
    }

    /** The offset of the [close] that ends what starts at [from], outside strings and brackets. */
    fun closing(text: String, from: Int, close: Char): Int? {
        var found: Int? = null
        scan(text, TextRange(from, text.length)) { at, c -> if (c == close && found == null) found = at }
        return found
    }

    /** Calls [visit] for each character of [range] outside strings and nested brackets; stops at a closing bracket of its own. */
    private fun scan(text: String, range: TextRange, visit: (Int, Char) -> Unit) {
        var depth = 0
        var quote: Char? = null
        var at = range.startOffset
        while (at < range.endOffset) {
            val c = text[at]
            when {
                quote != null && c == '\\' -> at++
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                (c == ')' || c == ']' || c == '}') && depth == 0 -> { visit(at, c); return }
                c == ')' || c == ']' || c == '}' -> depth--
                depth == 0 -> visit(at, c)
            }
            at++
        }
    }

    /** [range] without its [edits]: the text of an edit ends the place before it, or starts the first one. */
    private fun places(range: TextRange, edits: List<Edit>, prefix: String, suffix: String): List<AngularPlace>? {
        val starts = listOf(range.startOffset) + edits.map { it.range.endOffset }
        val ends = edits.map { it.range.startOffset } + range.endOffset
        val kept = starts.indices.filter { starts[it] < ends[it] }.ifEmpty { return null }
        return kept.mapIndexed { order, index ->
            val leading = prefix.takeIf { order == 0 }.orEmpty() + edits.take(index).joinToString("") { it.text }.takeIf { order == 0 }.orEmpty()
            val until = kept.getOrNull(order + 1) ?: edits.size
            val trailing = edits.subList(index, until).joinToString("") { it.text } + suffix.takeIf { order == kept.lastIndex }.orEmpty()
            AngularPlace(TextRange(starts[index], ends[index]), leading, trailing)
        }
    }

    private fun range(base: Int, group: MatchGroup?): TextRange = group?.range?.let { TextRange(base + it.first, base + it.last + 1) } ?: TextRange.EMPTY_RANGE
}

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
        // the character before a fragment is given as a suffix: a place ending where the fragment starts would be found there in its stead
        val places = markup.map { (from, to) ->
            when (to) { content.endOffset -> TextRange(from, to) to null; else -> TextRange(from, to - 1) to literal.text.substring(to - 1, to) }
        }
            .filter { (range, _) -> range.startOffset < range.endOffset }
        // HTML first: a smart pointer into a fragment is restored in the last injected file spanning it, and HTML spans the whole string
        Language.findLanguageByID("HTML")?.takeIf { places.isNotEmpty() }?.let { html ->
            places.fold(registrar.startInjecting(html)) { all, (range, suffix) -> all.addPlace(null, suffix, literal, range) }.doneInjecting()
        }
        fragments.forEach { fragment ->
            fragment.fold(registrar.startInjecting(TsLanguage)) { all, place -> all.addPlace(place.prefix, place.suffix, literal, place.range) }.doneInjecting()
        }
    }
}

class AngularScope : TsInjectionScope {
    override fun lookup(name: String, place: PsiElement, host: PsiElement): List<PsiElement>? = AngularTemplates.lookup(name, place, host)
}
