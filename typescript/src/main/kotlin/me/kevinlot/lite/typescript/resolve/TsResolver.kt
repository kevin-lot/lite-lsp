package me.kevinlot.lite.typescript.resolve

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import me.kevinlot.lite.core.LibraryOracle
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.core.LiteKeysIndex
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.typescript.JsFileType
import me.kevinlot.lite.typescript.TsFile
import me.kevinlot.lite.typescript.TsFileType
import me.kevinlot.lite.typescript.psi.*
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as E

// ---------------------------------------------------------------- references

class TsNameReference(element: TsReferenceExpr) : LiteReference<TsReferenceExpr>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = TsResolver.resolve(element)
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement? =
        if (nameToken.node.elementType == T.IDENTIFIER) TsPsiFactory(element.project).identifier(newName) else null
}

/** `{ x }`: the variable `x`. */
class TsShorthandReference(element: TsShorthandProperty) : LiteReference<TsShorthandProperty>(element, { element.nameIdentifier }) {
    override fun resolveTargets(): List<PsiElement> = element.name?.let { TsResolver.resolveName(it, element.parent ?: element, Space.VALUE) }.orEmpty()
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement = TsPsiFactory(element.project).identifier(newName)
}

/** `const { key: local } = value`: the member `key` of `value`. */
class TsMemberKeyReference(element: TsPropertyKey) : LiteReference<TsPropertyKey>(element, { element.firstChild }) {
    override fun resolveTargets(): List<PsiElement> {
        val pattern = element.parent?.parent ?: return emptyList()
        return TsTypes.members(TsTypes.patternTy(pattern), element.text).map { it.first }
    }
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement = TsPsiFactory(element.project).identifier(newName)
}

/** Module specifier → file (or `declare module` blocks). Not renamed with files. */
class TsModuleReference(element: TsModuleSpecifier) :
    com.intellij.psi.PsiPolyVariantReferenceBase<TsModuleSpecifier>(element, TextRange(1, maxOf(1, element.textLength - 1)), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<com.intellij.psi.ResolveResult> =
        com.intellij.psi.PsiElementResolveResult.createResults(TsModules.targets(element))
    override fun handleElementRename(newElementName: String): PsiElement = element
}

// ---------------------------------------------------------------- scopes

enum class Space { VALUE, TYPE, ANY }

/** Names declared directly in a scope (block, function, class type parameters…), not in nested scopes. */
object TsScopes {
    private val KEY = Key.create<CachedValue<Map<String, List<PsiElement>>>>("lite.ts.scope.decls")
    private val BLOCKS = setOf(E.BLOCK, E.MODULE_BLOCK)
    private val TYPE_PARAM_OWNERS = setOf(
        E.CLASS_DECLARATION, E.CLASS_EXPRESSION, E.INTERFACE_DECLARATION, E.TYPE_ALIAS, E.FUNCTION_TYPE, E.CONSTRUCTOR_TYPE,
        E.CALL_SIGNATURE, E.CONSTRUCT_SIGNATURE, E.MAPPED_TYPE,
    )
    private val STOP = com.intellij.psi.tree.TokenSet.orSet(E.FUNCTIONS, E.CLASSES)
    private val TYPE_DECLS = setOf(E.CLASS_DECLARATION, E.CLASS_EXPRESSION, E.INTERFACE_DECLARATION, E.TYPE_ALIAS, E.ENUM_DECLARATION, E.TYPE_PARAMETER, E.NAMESPACE_DECLARATION)
    private val TYPE_ONLY = setOf(E.INTERFACE_DECLARATION, E.TYPE_ALIAS, E.TYPE_PARAMETER)

    fun isScope(e: PsiElement): Boolean {
        val t = e.node?.elementType ?: return false
        return e is PsiFile || t in BLOCKS || t in E.FUNCTIONS || t in TYPE_PARAM_OWNERS ||
            t == E.FOR_STATEMENT || t == E.CATCH_CLAUSE || t == E.SWITCH_STATEMENT || t == E.CONDITIONAL_TYPE
    }

    fun declarations(scope: PsiElement): Map<String, List<PsiElement>> = LiteCache.cached(scope, KEY, TsLibraryOracle) {
        val out = mutableListOf<PsiElement>()
        val t = scope.node.elementType
        when {
            scope is PsiFile || t in BLOCKS -> scope.children.forEach { c ->
                out += declaredBy(c)
                hoistedVars(c, out)
            }
            t == E.SWITCH_STATEMENT -> scope.children.filter { it.node.elementType == E.CASE_CLAUSE }.forEach { cc -> cc.children.forEach { out += declaredBy(it) } }
            t == E.FOR_STATEMENT -> scope.children.forEach { out += declaredBy(it) }
            t == E.CATCH_CLAUSE -> (scope as TsElement).child(E.PARAMETER)?.let { bindings(it, out) }
            t == E.CONDITIONAL_TYPE -> inferred(scope, out)
            else -> {
                (scope as? TsElement)?.child(E.TYPE_PARAMETER_LIST)?.children?.filterIsInstance<TsNamedDecl>()?.let { out += it }
                (scope as? TsElement)?.child(E.TYPE_PARAMETER)?.let { out += it } // mapped type
                (scope as? TsElement)?.child(E.PARAMETER_LIST)?.children?.forEach { p -> if (p.node.elementType == E.PARAMETER) bindings(p, out) }
                // `function f() {}` / `class C {}` expressions see their own name
                if ((t == E.FUNCTION_EXPRESSION || t == E.CLASS_EXPRESSION) && (scope as TsNamedDecl).name != null) out += scope
            }
        }
        out.groupBy { (it as? TsNamedDecl)?.name ?: "" }
    }

    /** What a statement declares in its scope. */
    fun declaredBy(statement: PsiElement): List<PsiElement> = when (statement.node?.elementType) {
        E.VAR_STATEMENT -> statement.children.filter { it.node.elementType == E.VARIABLE }.flatMap { v ->
            if ((v as TsNamedDecl).nameIdentifier != null) listOf(v) else mutableListOf<PsiElement>().also { bindings(v, it) }
        }
        E.FUNCTION_DECLARATION, E.CLASS_DECLARATION, E.INTERFACE_DECLARATION, E.TYPE_ALIAS, E.ENUM_DECLARATION, E.NAMESPACE_DECLARATION, E.IMPORT_EQUALS ->
            listOf(statement).filter { (it as TsNamedDecl).name != null }
        E.IMPORT_DECLARATION -> statement.children.filter { it is TsNamedDecl && it.name != null }
        else -> emptyList()
    }

    /** Names in a parameter or a destructuring pattern. */
    fun bindings(e: PsiElement, out: MutableList<PsiElement>) {
        if (e is TsNamedDecl && e.nameIdentifier != null && e.node.elementType != E.PROPERTY_ASSIGNMENT) { out += e; if (e.node.elementType == E.BINDING) return }
        for (c in e.children) when (c.node.elementType) {
            E.OBJECT_PATTERN, E.ARRAY_PATTERN, E.PATTERN_PROPERTY -> bindings(c, out)
            E.BINDING -> out += c
        }
    }

    /** `var` declared in nested statements belongs to the enclosing function/file. */
    private fun hoistedVars(e: PsiElement, out: MutableList<PsiElement>) {
        val t = e.node?.elementType ?: return
        if (t in STOP || t == E.VAR_STATEMENT) return
        for (c in e.children) {
            if (c.node.elementType == E.VAR_STATEMENT && c.firstChild?.node?.elementType == T.kw("var")) out += declaredBy(c)
            else if (c is TsElement && c.node.elementType !in STOP) hoistedVars(c, out)
        }
    }

    private fun inferred(e: PsiElement, out: MutableList<PsiElement>) {
        for (c in e.children) {
            if (c.node.elementType == E.INFER_TYPE) (c as TsElement).child(E.TYPE_PARAMETER)?.let { out += it }
            else if (c.node.elementType != E.CONDITIONAL_TYPE) inferred(c, out)
        }
    }

    fun matches(d: PsiElement, space: Space): Boolean = when (space) {
        Space.ANY -> true
        Space.TYPE -> d.node?.elementType in TYPE_DECLS || d is PsiFile || d.node?.elementType == E.AMBIENT_MODULE
        Space.VALUE -> d.node?.elementType !in TYPE_ONLY
    }
}

// ---------------------------------------------------------------- globals and indexes

/** Interpreter-like libraries never change during a session; the environment tracker invalidates them. */
object TsLibraryOracle : LibraryOracle {
    override fun isLibrary(e: PsiElement): Boolean {
        val vf = e.containingFile?.originalFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(e.project).isInLibrary(vf)
    }
    override fun tracker(e: PsiElement): ModificationTracker = TsEnvironments.getInstance(e.project).modificationTracker
}

/** Global names (script files, `declare global`, lib.d.ts) and ambient modules (`module:<name>`). */
class TsGlobalIndex : LiteKeysIndex<TsFile>(TsFileType, TsFile::class.java) {
    override fun getName(): ID<String, Void> = KEY
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(TsFileType, JsFileType)
    override fun keys(file: TsFile): Collection<String> = TsGlobals.indexKeys(file)
    override fun getVersion() = 2
    companion object { val KEY = ID.create<String, Void>("lite.ts.globals") }
}

object TsGlobals {
    private val GLOBALS = Key.create<CachedValue<Map<String, List<PsiElement>>>>("lite.ts.globals")

    /** Declarations visible everywhere from [file]: all of a script, and `declare global` blocks of a module. */
    fun globalDecls(file: PsiFile): Map<String, List<PsiElement>> = LiteCache.cached(file, GLOBALS, TsLibraryOracle) {
        val out = mutableMapOf<String, MutableList<PsiElement>>()
        fun add(m: Map<String, List<PsiElement>>) = m.forEach { (k, v) -> out.getOrPut(k) { mutableListOf() } += v }
        if (!TsModules.isModule(file)) add(TsScopes.declarations(file))
        globalBlocks(file).forEach { add(TsScopes.declarations(it)) }
        out
    }

    private fun globalBlocks(file: PsiFile): List<PsiElement> = file.children.flatMap { s ->
        when (s.node.elementType) {
            E.GLOBAL_DECLARATION -> listOfNotNull((s as TsElement).child(E.MODULE_BLOCK))
            E.AMBIENT_MODULE -> (s as TsElement).child(E.MODULE_BLOCK)?.children?.filter { it.node.elementType == E.GLOBAL_DECLARATION }
                ?.mapNotNull { (it as TsElement).child(E.MODULE_BLOCK) }.orEmpty()
            else -> emptyList()
        }
    }

    fun lookup(name: String, place: PsiElement, space: Space): List<PsiElement> {
        val project = place.project
        val scope = TsEnvironments.scopeOf(place)
        val manager = com.intellij.psi.PsiManager.getInstance(project)
        return FileBasedIndex.getInstance().getContainingFiles(TsGlobalIndex.KEY, name, scope).mapNotNull { manager.findFile(it) }.flatMap { f -> globalDecls(f)[name].orEmpty() }
            .filter { TsScopes.matches(it, space) }
    }

    private val TYPES = Key.create<CachedValue<java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<PsiElement>>>>("lite.ts.globalTypes")

    /** First global class/interface named [name] (`String`, `Array`, `Promise`…), memoized per file. */
    fun type(name: String, place: PsiElement): PsiElement? {
        val file = place.containingFile?.originalFile ?: return null
        val cache = com.intellij.psi.util.CachedValuesManager.getCachedValue(file, TYPES) {
            com.intellij.psi.util.CachedValueProvider.Result.create(
                java.util.concurrent.ConcurrentHashMap(), com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT,
                TsEnvironments.getInstance(file.project).modificationTracker,
            )
        }
        cache[name]?.let { return it.orElse(null) }
        val found = lookup(name, place, Space.TYPE).firstOrNull { it is TsClass }
        cache[name] = java.util.Optional.ofNullable(found)
        return found
    }

    fun ambientModules(from: PsiFile, spec: String): List<PsiElement> {
        val manager = com.intellij.psi.PsiManager.getInstance(from.project)
        return FileBasedIndex.getInstance().getContainingFiles(TsGlobalIndex.KEY, "module:$spec", TsEnvironments.scopeOf(from)).mapNotNull { manager.findFile(it) }
            .flatMap { f -> f.children.filter { it.node.elementType == E.AMBIENT_MODULE && (it as TsNamedDecl).name == spec } }
    }

    // index keys: computed without caches (indexing PSI is a throwaway copy)

    fun indexKeys(file: TsFile): Collection<String> {
        val keys = HashSet<String>()
        val module = file.children.any { c ->
            val t = c.node.elementType
            t == E.IMPORT_DECLARATION || t == E.IMPORT_EQUALS || t == E.EXPORT_DECLARATION || t == E.EXPORT_STAR || t == E.EXPORT_ASSIGNMENT ||
                (c as? TsElement)?.hasModifier("export") == true
        }
        if (!module) file.children.forEach { s -> TsScopes.declaredBy(s).forEach { (it as? TsNamedDecl)?.name?.let(keys::add) } }
        file.children.filter { it.node.elementType == E.AMBIENT_MODULE }.forEach { s -> (s as TsNamedDecl).name?.let { keys += "module:$it" } }
        globalBlocks(file).forEach { b -> b.children.forEach { st -> TsScopes.declaredBy(st).forEach { (it as? TsNamedDecl)?.name?.let(keys::add) } } }
        return keys
    }

}

// ---------------------------------------------------------------- name resolution

object TsResolver {
    private val GUARD = RecursionGuard(48)
    private val RESOLVE = Key.create<CachedValue<List<PsiElement>>>("lite.ts.resolve")

    fun resolve(ref: TsReferenceExpr): List<PsiElement> =
        GUARD.run(emptyList()) { LiteCache.cachedNonRecursive(ref, RESOLVE, TsLibraryOracle, emptyList()) { doResolve(ref).distinct() } }

    private fun doResolve(ref: TsReferenceExpr): List<PsiElement> {
        val name = ref.referencedName ?: return emptyList()
        val parent = ref.parent
        if (parent is TsSpecifier) {
            val decl = parent.parent as? TsElement ?: return emptyList()
            val from = decl.child(E.MODULE_SPECIFIER) as? TsModuleSpecifier
            return when {
                from != null -> TsModules.targets(from).flatMap { TsModules.exports(it, name) }
                parent.type == E.EXPORT_SPECIFIER -> resolveName(name, decl, Space.ANY)
                else -> emptyList()
            }
        }
        val q = ref.qualifier
        if (q != null) {
            val space = if (parent?.node?.elementType == E.TYPE_REFERENCE) Space.TYPE else Space.ANY
            return TsTypes.members(TsTypes.qualifierTy(q), name).map { it.first }.filter { TsScopes.matches(it, space) }
        }
        return resolveName(name, ref, spaceOf(ref))
    }

    /** Lexical scopes outwards, then globals; imports are followed to the declaration they bring in. */
    fun resolveName(name: String, place: PsiElement, space: Space): List<PsiElement> {
        var e: PsiElement? = place
        while (e != null) {
            // JavaScript embedded in another language (QML): the host knows the rest of the scope chain
            if (e is TsHostScope) return e.hostLookup(name, place)
            if (TsScopes.isScope(e)) {
                val found = TsScopes.declarations(e)[name]
                if (!found.isNullOrEmpty()) {
                    val followed = found.flatMap { d -> follow(d).ifEmpty { listOf(d) } }
                    val matching = followed.filter { TsScopes.matches(it, space) }
                    if (matching.isNotEmpty()) return matching
                    if (found.any { it is TsSpecifier || it.node.elementType == E.IMPORT_DEFAULT }) return followed
                }
            }
            if (e is PsiFile) break
            e = e.parent
        }
        return TsGlobals.lookup(name, place, space)
    }

    /** `T` in a type is a type; `A` in `A.B` is a namespace inside a type, a value inside an expression. */
    private fun spaceOf(ref: TsReferenceExpr): Space {
        var top: PsiElement = ref
        while (top.parent is TsReferenceExpr) top = top.parent
        return when (top.parent?.node?.elementType) {
            E.TYPE_REFERENCE -> if (top == ref) Space.TYPE else Space.ANY
            E.IMPORT_EQUALS -> Space.ANY
            else -> Space.VALUE
        }
    }

    /** Import bindings → what they import (declarations, or module files for `* as ns`). */
    fun follow(d: PsiElement): List<PsiElement> = GUARD.run(emptyList()) {
        when (d.node?.elementType) {
            E.IMPORT_SPECIFIER -> {
                val s = d as TsSpecifier
                s.ref?.let { resolve(it) } ?: s.sourceName?.let { n -> moduleOf(d.parent)?.flatMap { TsModules.exports(it, n) } }.orEmpty()
            }
            E.IMPORT_DEFAULT -> moduleOf(d.parent).orEmpty().flatMap { m -> TsModules.exports(m, "default").ifEmpty { listOf(m) } }
            E.IMPORT_NAMESPACE -> moduleOf(d.parent).orEmpty()
            E.IMPORT_EQUALS -> moduleOf(d) ?: (d as TsElement).child(E.REFERENCE)?.let { resolve(it as TsReferenceExpr) }.orEmpty()
            // CommonJS imports: `const m = require("x")`, `const { a } = require("x")`
            E.VARIABLE -> required(d as TsElement)?.let { m -> TsModules.exports(m, "default").ifEmpty { listOf(m) } }.orEmpty()
            E.BINDING -> {
                val pattern = d.parent?.takeIf { it.node.elementType == E.OBJECT_PATTERN }
                val variable = pattern?.parent as? TsElement
                if (variable?.type == E.VARIABLE && required(variable) != null) TsTypes.members(TsTypes.patternTy(pattern), (d as TsNamedDecl).name ?: "").map { TsTypes.declarationOf(it.first) }
                else emptyList()
            }
            else -> emptyList()
        }
    }

    /** The module of `require("x")` initializing [variable]. */
    private fun required(variable: TsElement): PsiElement? {
        val call = variable.initializer?.takeIf { it.node.elementType == E.CALL_EXPRESSION } as? TsElement ?: return null
        val spec = (call.child(E.ARGUMENT_LIST) as? TsElement)?.child(E.MODULE_SPECIFIER) as? TsModuleSpecifier ?: return null
        return TsModules.targets(spec).firstOrNull()
    }

    private fun moduleOf(decl: PsiElement?): List<PsiElement>? =
        ((decl as? TsElement)?.child(E.MODULE_SPECIFIER) as? TsModuleSpecifier)?.let { TsModules.targets(it) }
}
