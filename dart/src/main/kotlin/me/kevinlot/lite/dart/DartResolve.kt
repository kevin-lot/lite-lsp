package me.kevinlot.lite.dart

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T

/**
 * Dart name resolution: locals and patterns, parameters, members (inherited ones included), top-level names of the library,
 * of its imports and of what they export. Members of `a.b` come from the type of `a`: written, or read from its initializer.
 */
object DartResolver {
    private val GUARD = RecursionGuard()
    private val RESOLVED = Key.create<CachedValue<List<PsiElement>>>("lite.dart.resolved")
    private val RUNNING = ThreadLocal.withInitial { HashSet<PsiElement>() }

    val CLASSES = setOf(T.CLASS_DEFINITION, T.ENUM_DEFINITION, T.EXTENSION_TYPE_DECLARATION, T.MIXIN_DECLARATION)
    val CONSTRUCTORS = setOf(T.FACTORY_CONSTRUCTOR_DECLARATION, T.NAMED_CONSTRUCTOR_DECLARATION)
    val FUNCTIONS = setOf(
        T.FACTORY_CONSTRUCTOR_DECLARATION, T.FUNCTION_DECLARATION_WITH_BODY, T.FUNCTION_DECLARATION_WITH_BODY_OR_NATIVE, T.FUNCTION_EXPRESSION,
        T.GETTER_DECLARATION, T.METHOD_DECLARATION, T.NAMED_CONSTRUCTOR_DECLARATION, T.SETTER_DECLARATION,
    )
    private val PATTERN_OWNERS = setOf(T.IF_ELEMENT, T.IF_STATEMENT, T.SWITCH_CASE, T.SWITCH_EXPRESSION_CASE)
    private val TRANSPARENT = setOf(T.ARRAY_ACCESS_EXPRESSION, T.CALL_EXPRESSION, T.REFERENCE_EXPRESSION)

    /** A name whose type needs the name itself (`var a = b; var b = a;`) resolves to nothing instead of looping. */
    fun resolve(ref: DartRef): List<PsiElement> {
        val running = RUNNING.get()
        if (!running.add(ref)) return emptyList()
        try {
            return GUARD.run(emptyList()) {
                CachedValuesManager.getCachedValue(ref, RESOLVED) {
                    val found = DartLocalizations.entries(ref, targets(ref).map { it.first }.distinct())
                    CachedValueProvider.Result.create(found, PsiModificationTracker.MODIFICATION_COUNT)
                }
            }
        } finally {
            running.remove(ref)
        }
    }

    /** What [ref] names, each with the type it is a member of (whose type arguments give its own type). */
    fun targets(ref: DartRef): List<Pair<PsiElement, DartType?>> {
        val name = ref.referencedName
        val file = ref.containingFile as? DartFile ?: return emptyList()
        if (ref.isA(T.PARAMETER_NAME_REFERENCE_EXPRESSION)) return namedParameters(ref).filter { it.text == name }.map(::parameterTarget).map { it to null }
        if (ref.isA(T.LIBRARY_COMPONENT_REFERENCE_EXPRESSION)) return shown(ref, file, name).map { it to null }
        if (ref.parent.isA(T.FIELD_FORMAL_PARAMETER)) return formalField(ref, name)
        if (ref.parent.isA(T.PATTERN_FIELD)) return listOfNotNull(DartPatterns.target(ref.parent))
        constructorName(ref)?.let { type -> return members(type, name) }
        val receiver = receiverOf(ref) ?: return lexical(ref, name, file).map { it to null }
        return memberOf(receiver, ref, name, file)
    }

    // ---------------------------------------------------------------- qualified names

    /** The expression before the dot: `a` of `a.b`, the target of a cascade (`a..b()`). */
    private fun receiverOf(ref: DartRef): PsiElement? {
        val parent = ref.parent
        if (parent.isA(T.REFERENCE_EXPRESSION) && parent.firstChild != ref) return parent.firstChild
        val top = generateSequence<PsiElement>(ref) { e -> e.parent.takeIf { it.elementType in TRANSPARENT && it.firstChild == e } }.last()
        val cascaded = top.prevSibling?.let { it.isA(T.DOT_DOT) || it.isA(T.QUEST_DOT_DOT) } == true
        return top.parent.takeIf { it.isA(T.CASCADE_REFERENCE_EXPRESSION) && cascaded }?.parent?.firstChild
    }

    private fun memberOf(receiver: PsiElement, ref: DartRef, name: String, file: DartFile): List<Pair<PsiElement, DartType?>> {
        val prefix = (receiver as? DartRef)?.takeIf { it.isName }?.let(::resolve)?.firstOrNull { it.parent.isA(T.IMPORT_STATEMENT) }
        if (prefix != null) return DartLibraries.imported(file, name, prefix.text).map { it to null }
        val type = DartTypes.of(receiver)
        return type?.let { members(it, name) }.orEmpty().ifEmpty { extensions(file, name, type) }
    }

    /** `new A.named()`, `const A.named()`, `A<int>.named()`: the name after the type of an instance creation. */
    private fun constructorName(ref: DartRef): DartType? {
        val creation = ref.parent.takeIf { it.isA(T.NEW_EXPRESSION) || it.isA(T.CONST_OBJECT_EXPRESSION) } ?: return null
        if (!ref.prevSibling.isA(T.DOT)) return null
        return DartTypes.of(creation)
    }

    /** `this.x` of a constructor: the field; `super.x`: the parameter of the constructor of the superclass, else its field. */
    private fun formalField(ref: DartRef, name: String): List<Pair<PsiElement, DartType?>> {
        val owner = enclosingType(ref) ?: return emptyList()
        if (ref.parent.child(T.THIS) != null) return members(owner, name)
        val parents = DartTypes.supertypes(owner)
        val inherited = parents.mapNotNull { constructor(it.declaration, null) }.flatMap(DartCalls::parameters)
            .filter { it.text == name }.map(::parameterTarget)
        return inherited.map { it to null }.ifEmpty { parents.flatMap { members(it, name) } }
    }

    // ---------------------------------------------------------------- members

    /** The members of [type] named [name], its own first, then those of its supertypes; each with the type declaring it. */
    fun members(type: DartType, name: String, seen: MutableSet<PsiElement> = HashSet()): List<Pair<PsiElement, DartType?>> {
        if (!seen.add(type.declaration)) return emptyList()
        if (type.isRecord) DartPatterns.recordField(type, name)?.let { return listOf(it to type) }
        val own = declaredMembers(type.declaration).filter { it.text == name }
        // a class may declare the setter alone and inherit the getter (`List.first`)
        if (own.any { !it.declaration.isA(T.SETTER_DECLARATION) }) return own.map { it to type }
        return own.map { it to type } + DartTypes.supertypes(type).firstNotNullOfOrNull { parent -> members(parent, name, seen).ifEmpty { null } }.orEmpty()
    }

    /** Fields, methods, accessors, named constructors and enum values. The unnamed constructor bears the name of the class: it is no member. */
    fun declaredMembers(owner: PsiElement): List<DartName> {
        val body = owner.child(T.CLASS_BODY)?.child(T.CLASS_MEMBERS) ?: owner.child(T.CLASS_MEMBERS)
        val className = owner.child(T.COMPONENT_NAME)?.text
        val values = owner.childrenOf(T.ENUM_CONSTANT_DECLARATION).mapNotNull { it.child(T.COMPONENT_NAME) as? DartName }
        return values + body?.children.orEmpty().flatMap { member ->
            when (member.elementType) {
                T.VAR_DECLARATION_LIST -> variables(member)
                in CONSTRUCTORS -> member.childrenOf(T.COMPONENT_NAME).drop(1).filterIsInstance<DartName>()
                T.GETTER_DECLARATION, T.METHOD_DECLARATION, T.SETTER_DECLARATION ->
                    listOfNotNull((member.child(T.COMPONENT_NAME) as? DartName)?.takeIf { it.text != className || !member.isA(T.METHOD_DECLARATION) })
                else -> emptyList()
            }
        }
    }

    /** The unnamed constructor of [owner], or the one named [name]. */
    fun constructor(owner: PsiElement, name: String?): PsiElement? {
        val className = owner.child(T.COMPONENT_NAME)?.text
        val members = (owner.child(T.CLASS_BODY)?.child(T.CLASS_MEMBERS) ?: owner.child(T.CLASS_MEMBERS))?.children.orEmpty()
        return members.firstOrNull { member ->
            val names = member.childrenOf(T.COMPONENT_NAME).map { it.text }
            when {
                name != null -> member.elementType in CONSTRUCTORS && names.getOrNull(1) == name
                member.isA(T.METHOD_DECLARATION) -> names.firstOrNull() == className
                else -> member.isA(T.FACTORY_CONSTRUCTOR_DECLARATION) && names.size == 1
            }
        }
    }

    /**
     * `x.name` when the type of `x` has no such member: the extensions the file sees that declare it. Those whose `on` type
     * the receiver [type] is win, with the type arguments it gives them; when none does, or the type is unknown, all are kept.
     */
    private fun extensions(file: DartFile, name: String, type: DartType?): List<Pair<PsiElement, DartType?>> {
        val libraries = DartLibraries.library(file) + DartLibraries.directives(DartLibraries.library(file).first()).filter { it.kind == "import" }
            .mapNotNull { DartLibraries.resolveUri(file, it.uri) }
        val found = libraries.filter { name in it.text }.flatMap { it.children.filter { declaration -> declaration.isA(T.EXTENSION_DECLARATION) } }
            .flatMap { extension -> declaredMembers(extension).filter { it.text == name }.map { it to extension } }
        val applied = found.mapNotNull { (member, extension) -> type?.let { DartTypes.applied(extension, it) }?.let { member to it } }
        return applied.ifEmpty { found.map { it.first to null } }
    }

    fun variables(list: PsiElement): List<DartName> =
        list.children.filter { it.isA(T.VAR_ACCESS_DECLARATION) || it.isA(T.VAR_DECLARATION_LIST_PART) }.mapNotNull { it.child(T.COMPONENT_NAME) as? DartName }

    // ---------------------------------------------------------------- lexical scopes

    /** The class, mixin, enum or extension type around [place], as a type whose arguments are its own parameters. */
    fun enclosingType(place: PsiElement): DartType? =
        PsiTreeUtil.findFirstParent(place) { it.elementType in CLASSES }?.let { DartType(it) }

    private fun lexical(ref: DartRef, name: String, file: DartFile): List<PsiElement> {
        val at = ref.textRange.startOffset
        for (scope in generateSequence(ref.parent) { it.parent }.takeWhile { it !is DartFile }) {
            val found = when (scope.elementType) {
                T.STATEMENTS -> statements(scope, name, at)
                T.FOR_LOOP_PARTS -> loopVariables(scope, name)
                T.FOR_STATEMENT, T.FOR_ELEMENT -> loopVariables(parts(scope), name)
                T.ON_PART -> scope.child(T.CATCH_PART)?.childrenOf(T.COMPONENT_NAME).orEmpty().filter { it.text == name }
                in PATTERN_OWNERS -> patternVariables(scope, name)
                in FUNCTIONS -> DartCalls.parameters(scope).filter { it.text == name }.map(::parameterTarget) + typeParameters(scope, name)
                T.EXTENSION_DECLARATION -> declaredMembers(scope).filter { it.text == name }.ifEmpty { onType(scope, name) } + typeParameters(scope, name)
                in CLASSES -> typeParameters(scope, name).ifEmpty { members(DartType(scope), name).map { it.first } }
                T.FUNCTION_TYPE_ALIAS -> typeParameters(scope, name)
                else -> emptyList()
            }.filter { it != ref }
            if (found.isNotEmpty()) return found
        }
        return DartLibraries.declared(file, name).ifEmpty { DartLibraries.prefixes(file, name) }.ifEmpty { DartLibraries.imported(file, name) }
    }

    /** Variables are visible after their declaration, local functions everywhere in the block. */
    private fun statements(block: PsiElement, name: String, at: Int): List<PsiElement> = block.children.flatMap { statement ->
        when (statement.elementType) {
            T.FUNCTION_DECLARATION_WITH_BODY -> listOfNotNull(statement.child(T.COMPONENT_NAME))
            T.PATTERN_VARIABLE_DECLARATION -> bound(statement).filter { statement.textRange.endOffset <= at }
            T.VAR_DECLARATION_LIST -> variables(statement).filter { visibleFrom(it) <= at }
            else -> emptyList()
        }
    }.filter { it.text == name }.takeLast(1)

    /** A variable is not visible in its own initializer: `var x = x;` reads the outer `x`. */
    private fun visibleFrom(variable: DartName): Int {
        val declaration = variable.declaration
        val value = declaration.child(T.VAR_INIT) ?: declaration.parent?.child(T.VAR_INIT)?.takeIf { declaration.isA(T.VAR_ACCESS_DECLARATION) }
        return (value ?: variable).textRange.endOffset
    }

    private fun parts(loop: PsiElement): PsiElement? = loop.child(T.FOR_LOOP_PARTS_IN_BRACES)?.child(T.FOR_LOOP_PARTS) ?: loop.child(T.FOR_LOOP_PARTS)

    private fun loopVariables(parts: PsiElement?, name: String): List<PsiElement> {
        val loop = parts?.child(T.FOR_IN_PART)
        val declared = parts?.childrenOf(T.VAR_DECLARATION_LIST).orEmpty().flatMap(::variables) +
            listOfNotNull(loop?.child(T.VAR_ACCESS_DECLARATION)?.child(T.COMPONENT_NAME))
        return (declared + listOfNotNull(loop?.child(T.COMPONENT_NAME)) + loop?.let(::bound).orEmpty()).filter { it.text == name }
    }

    /** The variables the patterns of [owner] bind, the ones of the statements inside it left to their own scope. */
    private fun patternVariables(owner: PsiElement, name: String): List<PsiElement> =
        owner.children.filter { !it.isA(T.STATEMENTS) && !it.isA(T.BLOCK) }.flatMap(::bound).filter { it.text == name }

    private fun bound(pattern: PsiElement?): List<DartName> =
        PsiTreeUtil.collectElementsOfType(pattern, DartName::class.java).filter { !it.isA(T.COMPONENT_NAME) && !it.isLabel }

    private fun typeParameters(owner: PsiElement, name: String): List<PsiElement> = typeParameters(owner).filter { it.text == name }

    fun typeParameters(owner: PsiElement): List<PsiElement> =
        owner.child(T.TYPE_PARAMETERS)?.childrenOf(T.TYPE_PARAMETER).orEmpty().mapNotNull { it.child(T.COMPONENT_NAME) }

    private fun onType(extension: PsiElement, name: String): List<PsiElement> =
        DartTypes.resolve(extension.child(T.TYPE), emptyMap())?.let { members(it, name).map { member -> member.first } }.orEmpty()

    // ---------------------------------------------------------------- parameters and arguments

    /** `this.x` as a parameter is the field `x`. */
    private fun parameterTarget(parameter: PsiElement): PsiElement = (parameter as? DartRef)?.let(::resolve)?.firstOrNull() ?: parameter

    /** The parameters of what the argument list around [argument] calls. */
    private fun namedParameters(argument: DartRef): List<PsiElement> {
        val call = PsiTreeUtil.findFirstParent(argument) { it.isA(T.ARGUMENTS) }?.parent ?: return emptyList()
        return DartCalls.callee(call)?.let { DartCalls.parameters(it.function) }.orEmpty()
    }

    /** The name an expression ends with: `c` of `a.b.c`. */
    fun lastName(expression: PsiElement?): DartRef? =
        generateSequence(expression as? DartRef) { it.children.lastOrNull { child -> child is DartRef } as? DartRef }.lastOrNull()?.takeIf { it.isName }

    /** The names a `show` / `hide` lists are those of the library the directive names. */
    private fun shown(ref: DartRef, file: DartFile, name: String): List<PsiElement> {
        val directive = PsiTreeUtil.findFirstParent(ref) { it.isA(T.IMPORT_STATEMENT) || it.isA(T.EXPORT_STATEMENT) } ?: return emptyList()
        val uri = directive.child(T.URI_ELEMENT) as? DartUri ?: return emptyList()
        return DartLibraries.resolveUri(file, uri.uri)?.let { DartLibraries.exported(it, name) }.orEmpty()
    }
}
