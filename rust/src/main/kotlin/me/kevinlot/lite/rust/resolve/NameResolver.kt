package me.kevinlot.lite.rust.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.rust.RsFile
import me.kevinlot.lite.rust.psi.*
import me.kevinlot.lite.rust.psi.ext.RsNamedElement
import me.kevinlot.lite.rust.psi.ext.RsPathMixin
import me.kevinlot.lite.rust.types.RsTypes

/** Rust namespaces: a name can denote a type/module, a value, or a macro. */
enum class Ns { TYPES, VALUES, MACROS }

private val ALL = setOf(Ns.TYPES, Ns.VALUES, Ns.MACROS)
private val TYPES = setOf(Ns.TYPES)
private val VALUES = setOf(Ns.VALUES)
private val MACROS = setOf(Ns.MACROS)

/**
 * Name resolution: lexical scopes, modules, `use` (groups, aliases, globs), enum variants,
 * associated items (through `impl` blocks), extern crates and the std prelude.
 *
 * Not modelled (on purpose, to stay light): macro expansion, `cfg` evaluation, visibility.
 * When several candidates remain (e.g. `#[cfg]` variants), all of them are returned.
 */
object NameResolver {

    // ---------------------------------------------------------------- entry points

    fun resolvePath(path: RsPath): List<PsiElement> = guarded {
        ModuleTree.cachedNonRecursive(path, PATH_KEY, emptyList()) { resolvePathIn(path, nsOf(path)) }
    }

    private val PATH_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<List<PsiElement>>>("lite.rs.path")
    private val SCOPE_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<java.util.concurrent.ConcurrentHashMap<String, List<PsiElement>>>>("lite.rs.scope")

    fun resolveMethod(call: RsMethodCall): List<PsiElement> = guarded { RsTypes.resolveMethod(call) }

    fun resolveField(lookup: RsFieldLookup): List<PsiElement> = guarded { RsTypes.resolveField(lookup) }

    fun resolveStructLiteralField(field: RsStructLiteralField): List<PsiElement> = guarded {
        val name = field.identifier?.text ?: field.integerLiteral?.text ?: return@guarded emptyList()
        val literal = PsiTreeUtil.getParentOfType(field, RsStructLiteral::class.java) ?: return@guarded emptyList()
        resolvePath(literal.path).flatMap { fieldsOf(it) }.filter { fieldName(it) == name }
    }

    /** `None`, `MAX`… in a pattern are references, not new bindings. */
    fun resolvePatBinding(binding: RsPatBinding): List<PsiElement> = guarded {
        val name = binding.name ?: return@guarded emptyList()
        if (!name.first().isUpperCase() || binding.bindingMode != null) return@guarded emptyList()
        lexical(name, VALUES, binding, itemsOnly = true).filter {
            it is RsConstant || (it is RsEnumVariant && it.blockFields == null && it.tupleFields == null) ||
                (it is RsStructItem && it.blockFields == null && it.tupleFields == null)
        }
    }

    // ---------------------------------------------------------------- paths

    fun nsOf(path: RsPath): Set<Ns> = when (val p = path.parent) {
        is RsMacroCall -> MACROS
        is RsPath, is RsPathType, is RsTraitRef, is RsTypeQual -> TYPES
        is RsPathExpr -> VALUES
        is RsPatTupleStruct -> VALUES
        is RsStructLiteral, is RsPatStruct -> TYPES
        is RsUseSpeck -> if (p.useGroup != null || p.mul != null) TYPES else ALL
        else -> ALL
    }

    /** Syntactic qualifier, or the enclosing `use a::{…}` prefix for the first segment inside a group. */
    fun qualifierOf(path: RsPath): RsPath? {
        path.path?.let { return it }
        if (path.coloncolon != null || path.typeQual != null) return null
        var top: PsiElement = path
        while (top.parent is RsPath) top = top.parent
        val speck = top.parent as? RsUseSpeck ?: return null
        val group = speck.parent as? RsUseGroup ?: return null
        return (group.parent as? RsUseSpeck)?.path
    }

    internal fun resolvePathIn(path: RsPath, ns: Set<Ns>): List<PsiElement> {
        val name = (path as RsPathMixin).referenceName ?: return emptyList()
        val qualifier = qualifierOf(path)

        val found: List<PsiElement> = if (qualifier == null) {
            val typeQual = path.typeQual
            when {
                typeQual != null -> {
                    val owner = typeQual.traitRef?.path?.let { resolvePathIn(it, TYPES) }
                        ?: RsTypes.typeTargets(typeQual.typeReference)
                    owner.flatMap { member(it, name, ns) }
                }
                path.coloncolon != null -> listOfNotNull(crateOf(path)?.let { ModuleTree.externPrelude(it)[name] })
                name == "crate" -> listOfNotNull(crateOf(path))
                name == "super" -> listOfNotNull(ModuleTree.parentOfMod(ModuleTree.containingMod(path)))
                name == "self" -> if (isModulePosition(path)) listOf(ModuleTree.containingMod(path)) else selfValue(path)
                name == "Self" -> selfType(path)
                else -> lexical(name, ns, path)
            }
        } else {
            when (name) {
                "self" -> resolvePathIn(qualifier, TYPES)
                "super" -> resolvePathIn(qualifier, TYPES).mapNotNull { ModuleTree.parentOfMod(it) }
                else -> resolvePathIn(qualifier, TYPES).flatMap { member(it, name, ns) }
            }
        }
        return preferNs(found.distinct(), ns)
    }

    private fun isModulePosition(path: RsPath): Boolean =
        path.parent is RsPath || PsiTreeUtil.getParentOfType(path, RsUseItem::class.java) != null || path.parent is RsVisRestriction

    private fun crateOf(e: PsiElement): RsFile? = ModuleTree.crateRoot(e)

    private fun selfValue(e: PsiElement): List<PsiElement> {
        val fn = PsiTreeUtil.getParentOfType(e, RsFunction::class.java) ?: return emptyList()
        return listOfNotNull(fn.valueParameterList?.selfParameter)
    }

    /** `Self` inside an impl/trait/struct/enum. */
    fun selfType(e: PsiElement): List<PsiElement> {
        val owner = PsiTreeUtil.getParentOfType(e, RsImplItem::class.java, RsTraitItem::class.java, RsStructItem::class.java, RsEnumItem::class.java)
            ?: return emptyList()
        return if (owner is RsImplItem) RsTypes.typeTargets(owner.typeReference) else listOf(owner)
    }

    private fun preferNs(found: List<PsiElement>, ns: Set<Ns>): List<PsiElement> {
        if (ns == ALL || found.size <= 1) return found
        val matching = found.filter { nsOfElement(it).any { n -> n in ns } }
        return matching.ifEmpty { found }
    }

    fun nsOfElement(e: PsiElement): Set<Ns> = when (e) {
        is RsMacro, is RsMacro2 -> MACROS
        is RsStructItem -> if (e.blockFields == null) setOf(Ns.TYPES, Ns.VALUES) else TYPES
        is RsEnumVariant -> setOf(Ns.TYPES, Ns.VALUES)
        is RsEnumItem, is RsTraitItem, is RsTypeAlias, is RsModItem, is RsModDeclItem, is RsFile,
        is RsTypeParameter, is RsTraitAlias, is RsExternCrateItem -> TYPES
        else -> VALUES
    }

    // ---------------------------------------------------------------- members

    /** `target::name` */
    fun member(target: PsiElement, name: String, ns: Set<Ns>): List<PsiElement> = when (target) {
        is RsFile, is RsModItem -> moduleScope(target, name, ns)
        is RsEnumItem -> target.enumBody?.enumVariantList.orEmpty().filter { it.name == name } + associated(target, name)
        is RsTraitItem -> traitMembers(target, name)
        is RsStructItem -> associated(target, name)
        is RsTypeAlias -> RsTypes.typeTargets(target.typeReference).flatMap { member(it, name, ns) }.ifEmpty { associated(target, name) }
        is RsTypeParameter -> RsTypes.boundTraits(target).flatMap { traitMembers(it, name) }
        else -> emptyList()
    }

    fun traitMembers(trait: RsTraitItem, name: String): List<PsiElement> =
        trait.members?.let { membersNamed(it, name) }.orEmpty()

    /** Items of `impl Type` and `impl Trait for Type` blocks (plus trait defaults). */
    fun associated(type: PsiElement, name: String): List<PsiElement> {
        val typeName = (type as? RsNamedElement)?.name ?: return emptyList()
        val out = mutableListOf<PsiElement>()
        for (impl in implsOf(type, typeName)) {
            val own = impl.members?.let { membersNamed(it, name) }.orEmpty()
            out += own
            if (own.isEmpty()) impl.traitRef?.path?.let { resolvePathIn(it, TYPES) }?.filterIsInstance<RsTraitItem>()
                ?.forEach { out += traitMembers(it, name) }
        }
        return out
    }

    /**
     * All impl blocks whose self type resolves to [type]. The part found in libraries is memoized
     * for the session; impls written in the project are looked up each time (they change).
     */
    fun implsOf(type: PsiElement, typeName: String = (type as RsNamedElement).name.orEmpty()): List<RsImplItem> {
        fun matching(impls: List<RsImplItem>) = impls.filter { impl ->
            val targets = RsTypes.typeTargets(impl.typeReference)
            targets.isEmpty() || type in targets
        }
        if (!ModuleTree.isLibrary(type)) return matching(RsImplIndex.find(type.project, typeName))
        val lib = ModuleTree.cached(type, LIB_IMPLS_KEY) { matching(RsImplIndex.find(type.project, typeName, library = true)) }
        return lib + matching(RsImplIndex.find(type.project, typeName, library = false))
    }

    private val LIB_IMPLS_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<List<RsImplItem>>>("lite.rs.libImpls")

    private fun membersNamed(members: RsMembers, name: String): List<PsiElement> =
        members.children.filter { (it as? RsNamedElement)?.name == name }

    fun fieldsOf(e: PsiElement): List<PsiElement> = when (e) {
        is RsStructItem -> e.blockFields?.namedFieldDeclList ?: e.tupleFields?.tupleFieldDeclList.orEmpty()
        is RsEnumVariant -> e.blockFields?.namedFieldDeclList ?: e.tupleFields?.tupleFieldDeclList.orEmpty()
        is RsTypeAlias -> RsTypes.typeTargets(e.typeReference).flatMap { fieldsOf(it) }
        else -> emptyList()
    }

    fun fieldName(field: PsiElement): String? = when (field) {
        is RsNamedFieldDecl -> field.name
        is RsTupleFieldDecl -> (field.parent as? RsTupleFields)?.tupleFieldDeclList?.indexOf(field)?.toString()
        else -> null
    }

    // ---------------------------------------------------------------- modules

    /** Everything [name] denotes inside module [mod] (items, imports, globs). Memoized per module. */
    fun moduleScope(mod: PsiElement, name: String, ns: Set<Ns>): List<PsiElement> {
        val memo = ModuleTree.cached(mod, SCOPE_KEY) { java.util.concurrent.ConcurrentHashMap() }
        memo[name]?.let { return it }
        val key = mod to name
        val visiting = VISITING.get()
        if (!visiting.add(key)) return emptyList()
        try {
            val out = mutableListOf<PsiElement>()
            for (child in mod.children) collectFromItem(child, name, ALL, out)
            val result = out.distinct()
            memo[name] = result
            return result
        } finally {
            visiting.remove(key)
        }
    }

    private fun collectFromItem(child: PsiElement, name: String, ns: Set<Ns>, out: MutableList<PsiElement>) {
        when (child) {
            is RsUseItem -> child.useSpeck?.let { useLookup(it, name, out) }
            is RsExternCrateItem -> {
                val bound = child.alias?.identifier?.text ?: child.identifier?.text ?: return
                if (bound != name) return
                val crate = child.identifier?.text
                val root = crateOf(child) ?: return
                out += if (child.self != null) listOf(root) else listOfNotNull(crate?.let { ModuleTree.externPrelude(root)[it] })
            }
            is RsModDeclItem -> if (child.name == name) out += (ModuleTree.resolveModDecl(child) ?: child)
            is RsForeignModItem -> child.children.forEach { collectFromItem(it, name, ns, out) }
            is RsNamedElement -> if (child.name == name && child !is RsPatBinding) out += child
        }
    }

    private fun useLookup(speck: RsUseSpeck, name: String, out: MutableList<PsiElement>) {
        val group = speck.useGroup
        when {
            group != null -> group.useSpeckList.forEach { useLookup(it, name, out) }
            speck.mul != null -> {
                val prefix = speck.path ?: return
                resolvePathIn(prefix, TYPES).forEach { out += member(it, name, ALL) }
            }
            else -> {
                val path = speck.path ?: return
                val alias = speck.alias
                if (alias?.underscore != null) return
                val ref = (path as RsPathMixin).referenceName
                val bound = alias?.identifier?.text ?: if (ref == "self") qualifierOf(path)?.let { (it as RsPathMixin).referenceName } else ref
                if (bound == name) out += resolvePathIn(path, ALL)
            }
        }
    }

    // ---------------------------------------------------------------- lexical scopes

    /**
     * Walks scopes outward from [place]: blocks (bindings, local items), closures, match arms,
     * `for`/`if let`/`while let`, generic parameters, then the module, extern crates and prelude.
     */
    fun lexical(name: String, ns: Set<Ns>, place: PsiElement, itemsOnly: Boolean = false): List<PsiElement> {
        if (ns == MACROS) return macros(name, place)
        val values = Ns.VALUES in ns && !itemsOnly
        var prev: PsiElement = place
        var cur: PsiElement? = place.parent
        while (cur != null) {
            val local: List<PsiElement> = when (cur) {
                is RsBlock -> blockScope(cur, prev, name, ns, values)
                is RsFunction -> (if (values && prev is RsBlock) paramBindings(cur.valueParameterList, name) else emptyList()) +
                    typeParams(cur.typeParameterList, name)
                is RsLambdaExpr -> if (values && prev != cur.valueParameterList) paramBindings(cur.valueParameterList, name) else emptyList()
                is RsMatchArm -> if (values && prev != cur.pat) bindings(cur.pat, name) else emptyList()
                is RsForExpr -> if (values && prev == cur.block) bindings(cur.pat, name) else emptyList()
                is RsIfExpr -> if (values && prev == cur.block) condBindings(cur.condition, name) else emptyList()
                is RsWhileExpr -> if (values && prev == cur.block) condBindings(cur.condition, name) else emptyList()
                is RsImplItem -> typeParams(cur.typeParameterList, name)
                is RsTraitItem -> typeParams(cur.typeParameterList, name)
                is RsStructItem -> typeParams(cur.typeParameterList, name)
                is RsEnumItem -> typeParams(cur.typeParameterList, name)
                is RsTypeAlias -> typeParams(cur.typeParameterList, name)
                is RsFile, is RsModItem -> return moduleAndPrelude(cur, name, ns)
                else -> emptyList()
            }
            if (local.isNotEmpty()) return local
            prev = cur
            cur = cur.parent
        }
        return emptyList()
    }

    private fun moduleAndPrelude(mod: PsiElement, name: String, ns: Set<Ns>): List<PsiElement> {
        moduleScope(mod, name, ns).takeIf { it.isNotEmpty() }?.let { return it }
        val root = crateOf(mod) ?: return emptyList()
        ModuleTree.externPrelude(root)[name]?.let { return listOf(it) }
        return prelude(root, name, ns)
    }

    /** `std::prelude::rust_20xx::*` */
    private fun prelude(root: RsFile, name: String, ns: Set<Ns>): List<PsiElement> {
        val std = ModuleTree.externPrelude(root)["std"] ?: return emptyList()
        if (std == root) return emptyList()
        val edition = "rust_" + ModuleTree.edition(root)
        val module = moduleScope(std, "prelude", TYPES).flatMap { moduleScope(it, edition, TYPES) }
        return module.flatMap { moduleScope(it, name, ns) }
    }

    private fun blockScope(block: RsBlock, prev: PsiElement, name: String, ns: Set<Ns>, values: Boolean): List<PsiElement> {
        if (values) {
            // Bindings of `let` statements *before* the current one, nearest first (shadowing).
            val stmts = block.children.toList()
            val idx = stmts.indexOf(prev).let { if (it < 0) stmts.size else it }
            for (i in idx - 1 downTo 0) {
                val s = stmts[i] as? RsLetDecl ?: continue
                bindings(s.pat, name).takeIf { it.isNotEmpty() }?.let { return it.take(1) }
            }
        }
        val out = mutableListOf<PsiElement>()
        for (child in block.children) if (child !is RsLetDecl) collectFromItem(child, name, ns, out)
        return out
    }

    private fun paramBindings(params: RsValueParameterList?, name: String): List<PsiElement> =
        params?.valueParameterList.orEmpty().flatMap { bindings(it.pat, name) }

    private fun condBindings(cond: RsCondition?, name: String): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(cond, RsLetExpr::class.java).flatMap { bindings(it.pat, name) }

    fun bindings(pat: PsiElement?, name: String): List<PsiElement> {
        pat ?: return emptyList()
        val all = if (pat is RsPatBinding) listOf(pat) else PsiTreeUtil.findChildrenOfType(pat, RsPatBinding::class.java)
        return all.filter { it.name == name && resolvePatBinding(it).isEmpty() }
    }

    private fun typeParams(list: RsTypeParameterList?, name: String): List<PsiElement> =
        list?.let { it.typeParameterList.filter { p -> p.name == name } + it.constParameterList.filter { p -> p.name == name } }.orEmpty()

    /** Textual scope of `macro_rules!` first, then exported macros anywhere (dependencies, std). */
    private fun macros(name: String, place: PsiElement): List<PsiElement> {
        val file = place.containingFile
        PsiTreeUtil.findChildrenOfType(file, RsMacro::class.java).filter { it.name == name }.takeIf { it.isNotEmpty() }?.let { return it }
        val all = RsItemNameIndex.find(place, name).filter { it is RsMacro || it is RsMacro2 }
        val root = crateOf(place)?.originalFile?.virtualFile?.parent?.path
        val sameCrate = all.filter { root != null && it.containingFile.originalFile.virtualFile?.path?.startsWith(root) == true }
        return sameCrate.ifEmpty { all }
    }

    // ---------------------------------------------------------------- recursion guard

    private val VISITING = ThreadLocal.withInitial { HashSet<Pair<PsiElement, String>>() }
    private val GUARD = me.kevinlot.lite.core.RecursionGuard()

    private inline fun guarded(block: () -> List<PsiElement>): List<PsiElement> = GUARD.run(emptyList(), block)
}
