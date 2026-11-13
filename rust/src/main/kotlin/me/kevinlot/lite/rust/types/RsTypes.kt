package me.kevinlot.lite.rust.types

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.rust.psi.*
import me.kevinlot.lite.rust.psi.ext.RsNamedElement
import me.kevinlot.lite.rust.resolve.ModuleTree
import me.kevinlot.lite.rust.resolve.NameResolver
import me.kevinlot.lite.rust.resolve.Ns
import me.kevinlot.lite.rust.resolve.RsImplIndex
import me.kevinlot.lite.rust.resolve.RsItemNameIndex

/** A deliberately small type model: enough to find fields and methods of an expression. */
sealed class Ty {
    /** struct / enum / union with generic arguments */
    data class Adt(val item: RsNamedElement, val args: List<Ty>) : Ty()
    data class Ref(val inner: Ty) : Ty()
    data class Param(val param: RsTypeParameter) : Ty()
    data class Tuple(val items: List<Ty>) : Ty()
    data class Slice(val elem: Ty) : Ty()
    data class Prim(val name: String) : Ty()
    data class Dyn(val traits: List<RsTraitItem>) : Ty()
    data object Unknown : Ty()
}

/** Type parameter substitution plus the meaning of `Self`. */
data class Subst(val map: Map<RsTypeParameter, Ty> = emptyMap(), val self: Ty? = null) {
    operator fun get(p: RsTypeParameter): Ty? = map[p]
    operator fun plus(o: Subst) = Subst(map + o.map, o.self ?: self)
}

object RsTypes {
    private val PRIMITIVES = setOf(
        "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "bool", "char", "str",
    )
    private const val MAX_DEREF = 6

    // ---------------------------------------------------------------- field / method resolution

    fun resolveField(lookup: RsFieldLookup): List<PsiElement> {
        val dot = lookup.parent as? RsDotExpr ?: return emptyList()
        val name = lookup.identifier?.text ?: lookup.integerLiteral?.text ?: return emptyList()
        for (ty in derefChain(inferType(dot.expr))) {
            when (ty) {
                is Ty.Adt -> NameResolver.fieldsOf(ty.item).filter { NameResolver.fieldName(it) == name }.takeIf { it.isNotEmpty() }?.let { return it }
                else -> {}
            }
        }
        return emptyList()
    }

    fun resolveMethod(call: RsMethodCall): List<PsiElement> {
        val dot = call.parent as? RsDotExpr ?: return emptyList()
        val name = call.identifier.text
        val receiver = inferType(dot.expr)
        methodCandidates(receiver, name, call.project).takeIf { it.isNotEmpty() }?.let { return it.map { m -> m.fn } }
        // Unknown receiver: fall back on every method with that name (Ctrl+Click shows a chooser).
        if (receiver == Ty.Unknown || receiver is Ty.Param) {
            return RsItemNameIndex.find(call, name)
                .filterIsInstance<RsFunction>().filter { it.valueParameterList?.selfParameter != null }.take(20)
        }
        return emptyList()
    }

    data class MethodHit(val fn: RsFunction, val subst: Subst)

    fun methodCandidates(receiver: Ty, name: String, project: com.intellij.openapi.project.Project): List<MethodHit> {
        for (ty in derefChain(receiver)) {
            val hits = methodsOn(ty, name, project)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun methodsOn(ty: Ty, name: String, project: com.intellij.openapi.project.Project): List<MethodHit> = when (ty) {
        is Ty.Adt -> implItemsFor(ty, name) + derivedTraitMethods(ty, name)
        is Ty.Param -> boundTraits(ty.param).flatMap { traitFns(it, name, Subst(self = ty)) }
        is Ty.Dyn -> ty.traits.flatMap { traitFns(it, name, Subst(self = ty)) }
        is Ty.Prim, is Ty.Slice -> primitiveImpls(ty, project).flatMap { impl -> implFns(impl, name, Subst(self = ty)) }
        else -> emptyList()
    }

    private fun implItemsFor(ty: Ty.Adt, name: String): List<MethodHit> =
        NameResolver.implsOf(ty.item).flatMap { impl ->
            val subst = unifyImpl(impl, ty)
            implFns(impl, name, subst).ifEmpty {
                traitOf(impl)?.let { traitFns(it, name, subst) }.orEmpty()
            }
        }

    /** `#[derive(Clone, Debug)]`: the impls are macro-generated, so look at the trait itself. */
    private fun derivedTraitMethods(ty: Ty.Adt, name: String): List<MethodHit> {
        val attrs = PsiTreeUtil.getChildrenOfTypeAsList(ty.item, RsOuterAttr::class.java)
        val derived = attrs.flatMap { attr ->
            val meta = PsiTreeUtil.findChildOfType(attr, RsMetaItem::class.java)
            if (meta?.firstChild?.text != "derive") emptyList() else PsiTreeUtil.findChildrenOfType(meta, RsPath::class.java)
        }
        return derived.flatMap { NameResolver.resolvePath(it) }.filterIsInstance<RsTraitItem>()
            .flatMap { traitFns(it, name, Subst(self = ty)) }
    }

    private fun primitiveImpls(ty: Ty, project: com.intellij.openapi.project.Project): List<RsImplItem> {
        val key = when (ty) { is Ty.Prim -> ty.name; is Ty.Slice -> "[]"; else -> return emptyList() }
        return RsImplIndex.find(project, key)
    }

    private fun implFns(impl: RsImplItem, name: String, subst: Subst): List<MethodHit> =
        impl.members?.children.orEmpty().filterIsInstance<RsFunction>().filter { it.name == name }.map { MethodHit(it, subst) }

    private fun traitFns(trait: RsTraitItem, name: String, subst: Subst): List<MethodHit> {
        val own = trait.members?.children.orEmpty().filterIsInstance<RsFunction>().filter { it.name == name }
        if (own.isNotEmpty()) return own.map { MethodHit(it, subst) }
        // Supertraits: `trait A: B`
        return trait.typeParamBounds?.polyboundList.orEmpty()
            .mapNotNull { it.bound.traitRef?.path }.flatMap { NameResolver.resolvePath(it) }
            .filterIsInstance<RsTraitItem>().filter { it != trait }.flatMap { traitFns(it, name, subst) }
    }

    private fun traitOf(impl: RsImplItem): RsTraitItem? =
        impl.traitRef?.path?.let { NameResolver.resolvePath(it) }?.filterIsInstance<RsTraitItem>()?.firstOrNull()

    fun boundTraits(param: RsTypeParameter): List<RsTraitItem> {
        val bounds = param.typeParamBounds?.polyboundList.orEmpty().toMutableList()
        // `where T: Trait`
        val owner = param.parent?.parent
        PsiTreeUtil.getChildrenOfTypeAsList(owner, RsWhereClause::class.java).flatMap { it.wherePredList }
            .filter { (it.typeReference as? RsPathType)?.path?.text == param.name }
            .forEach { bounds += it.typeParamBounds?.polyboundList.orEmpty() }
        return bounds.mapNotNull { it.bound.traitRef?.path }.flatMap { NameResolver.resolvePath(it) }.filterIsInstance<RsTraitItem>()
    }

    // ---------------------------------------------------------------- autoderef

    fun derefChain(ty: Ty): List<Ty> {
        val out = mutableListOf<Ty>()
        var cur: Ty? = ty
        while (cur != null && cur != Ty.Unknown && out.size < MAX_DEREF && cur !in out) {
            out += cur
            cur = deref(cur)
        }
        return out
    }

    private fun deref(ty: Ty): Ty? = when (ty) {
        is Ty.Ref -> ty.inner
        is Ty.Adt -> assocType(ty, "Deref", "Target")
        else -> null
    }

    /** `<ty as Trait>::Name` looked up in the matching impl block. */
    fun assocType(ty: Ty.Adt, traitName: String?, name: String): Ty? {
        for (impl in NameResolver.implsOf(ty.item)) {
            if (traitName != null && impl.traitRef?.path?.identifier?.text != traitName) continue
            val alias = impl.members?.children.orEmpty().filterIsInstance<RsTypeAlias>().firstOrNull { it.name == name } ?: continue
            val t = typeOf(alias.typeReference, unifyImpl(impl, ty))
            if (t != Ty.Unknown) return t
        }
        return null
    }

    // ---------------------------------------------------------------- type references

    /** Items a type reference names (struct, enum, trait, alias, type parameter). */
    fun typeTargets(ref: RsTypeReference?): List<PsiElement> = when (ref) {
        is RsPathType -> NameResolver.resolvePath(ref.path)
        is RsRefLikeType -> typeTargets(ref.typeReference)
        is RsParenType -> typeTargets(ref.typeReference)
        else -> emptyList()
    }

    fun typeOf(ref: RsTypeReference?, subst: Subst = Subst()): Ty = when (ref) {
        null -> Ty.Unknown
        is RsPathType -> pathType(ref.path, subst)
        is RsRefLikeType -> Ty.Ref(typeOf(ref.typeReference, subst))
        is RsParenType -> typeOf(ref.typeReference, subst)
        is RsTupleType -> Ty.Tuple(ref.typeReferenceList.map { typeOf(it, subst) })
        is RsUnitType -> Ty.Tuple(emptyList())
        is RsArrayType -> Ty.Slice(typeOf(ref.typeReference, subst))
        is RsTraitType -> Ty.Dyn(ref.polyboundList.mapNotNull { it.bound.traitRef?.path }.flatMap { NameResolver.resolvePath(it) }.filterIsInstance<RsTraitItem>())
        else -> Ty.Unknown
    }

    private fun pathType(path: RsPath, subst: Subst): Ty {
        val name = path.identifier?.text
        // `Self::Item`
        val q = path.path
        if (q != null && q.cself != null && name != null) {
            val self = subst.self
            if (self is Ty.Adt) assocType(self, null, name)?.let { return it }
            return Ty.Unknown
        }
        if (path.cself != null && q == null) return subst.self ?: NameResolver.selfType(path).firstOrNull()?.let { itemType(it, emptyList()) } ?: Ty.Unknown
        val args = path.typeArgumentList?.typeReferenceList.orEmpty().map { typeOf(it, subst) }
        val target = NameResolver.resolvePath(path).firstOrNull()
        // A single-segment primitive name wins over a module of the same name (`mod str` in alloc).
        if (q == null && name in PRIMITIVES && (target == null || ModuleTree.isModule(target) || target is RsModDeclItem)) return Ty.Prim(name!!)
        if (target == null) return Ty.Unknown
        if (target is RsTypeParameter) return subst[target] ?: Ty.Param(target)
        if (target is RsTypeAlias) {
            val params = target.typeParameterList?.typeParameterList.orEmpty()
            return typeOf(target.typeReference, subst + Subst(params.zip(args).toMap()))
        }
        return itemType(target, args)
    }

    private fun itemType(target: PsiElement, args: List<Ty>): Ty = when (target) {
        is RsStructItem, is RsEnumItem -> Ty.Adt(target as RsNamedElement, args)
        is RsTraitItem -> Ty.Dyn(listOf(target))
        else -> Ty.Unknown
    }

    /** Matches `impl<T> Foo<T>` against `Foo<i32>`: T -> i32, Self -> Foo<i32>. */
    fun unifyImpl(impl: RsImplItem, actual: Ty): Subst {
        val map = HashMap<RsTypeParameter, Ty>()
        val pattern = typeOf(impl.typeReference)
        unify(pattern, actual, map)
        return Subst(map, actual)
    }

    private fun unify(pattern: Ty, actual: Ty, map: MutableMap<RsTypeParameter, Ty>) {
        when {
            pattern is Ty.Param -> if (actual != Ty.Unknown) map.putIfAbsent(pattern.param, actual)
            pattern is Ty.Adt && actual is Ty.Adt -> pattern.args.zip(actual.args).forEach { (p, a) -> unify(p, a, map) }
            pattern is Ty.Ref && actual is Ty.Ref -> unify(pattern.inner, actual.inner, map)
            pattern is Ty.Slice && actual is Ty.Slice -> unify(pattern.elem, actual.elem, map)
            pattern is Ty.Tuple && actual is Ty.Tuple -> pattern.items.zip(actual.items).forEach { (p, a) -> unify(p, a, map) }
        }
    }

    private fun adtSubst(ty: Ty.Adt): Subst {
        val params = when (val i = ty.item) {
            is RsStructItem -> i.typeParameterList
            is RsEnumItem -> i.typeParameterList
            else -> null
        }?.typeParameterList.orEmpty()
        return Subst(params.zip(ty.args).toMap(), ty)
    }

    // ---------------------------------------------------------------- expressions

    private val TYPE_KEY = Key.create<CachedValue<Ty>>("lite.rs.exprType")

    fun inferType(expr: RsExpr?): Ty {
        expr ?: return Ty.Unknown
        return ModuleTree.cachedNonRecursive(expr, TYPE_KEY, Ty.Unknown) { infer(expr) }
    }

    private fun infer(expr: RsExpr): Ty = when (expr) {
        is RsLitExpr -> when {
            expr.stringLiteral != null || expr.rawStringLiteral != null -> Ty.Ref(Ty.Prim("str"))
            expr.integerLiteral != null -> Ty.Prim(PRIMITIVES.firstOrNull { expr.text.endsWith(it) && it != "str" } ?: "i32")
            expr.floatLiteral != null -> Ty.Prim(if (expr.text.endsWith("f32")) "f32" else "f64")
            expr.boolLiteral != null -> Ty.Prim("bool")
            expr.charLiteral != null -> Ty.Prim("char")
            else -> Ty.Unknown
        }
        is RsPathExpr -> valueType(NameResolver.resolvePath(expr.path).firstOrNull(), expr.path)
        is RsParenExpr -> inferType(expr.expr)
        is RsUnaryExpr -> when {
            expr.and != null -> Ty.Ref(inferType(expr.expr))
            expr.mul != null -> inferType(expr.expr).let { deref(it) ?: Ty.Unknown }
            else -> inferType(expr.expr)
        }
        is RsStructLiteral -> NameResolver.resolvePath(expr.path).firstOrNull()?.let { variantOrItemType(it, expr.path) } ?: Ty.Unknown
        is RsCallExpr -> callType(expr)
        is RsDotExpr -> dotType(expr)
        is RsTryExpr -> unwrapTry(inferType(expr.expr))
        is RsCastExpr -> typeOf(expr.typeReference)
        is RsTupleExpr -> Ty.Tuple(expr.exprList.map { inferType(it) })
        is RsUnitExpr -> Ty.Tuple(emptyList())
        is RsArrayExpr -> Ty.Slice(inferType(expr.exprList.firstOrNull()))
        is RsIndexExpr -> indexType(inferType(expr.exprList.firstOrNull()))
        is RsBlockExpr -> blockType(expr.block)
        is RsIfExpr -> blockType(expr.block)
        is RsMatchExpr -> inferType(expr.matchBody?.matchArmList?.firstOrNull()?.expr)
        is RsMacroExpr -> macroType(expr.macroCall)
        is RsRangeExpr -> Ty.Unknown
        else -> Ty.Unknown
    }

    private fun blockType(block: RsBlock?): Ty =
        (block?.children?.lastOrNull { it is RsStmt } as? RsExprStmt)?.takeIf { it.lastChild?.text != ";" }?.let { inferType(it.expr) } ?: Ty.Unknown

    private fun macroType(call: RsMacroCall): Ty = when (call.path.identifier?.text) {
        "vec" -> stdAdt(call, "Vec", listOf(inferType(call.vecMacroArgument?.exprList?.firstOrNull())))
        "format" -> stdAdt(call, "String", emptyList())
        else -> Ty.Unknown
    }

    private fun stdAdt(anchor: PsiElement, name: String, args: List<Ty>): Ty {
        val item = NameResolver.lexical(name, setOf(Ns.TYPES), anchor).firstOrNull() as? RsNamedElement ?: return Ty.Unknown
        return if (item is RsStructItem || item is RsEnumItem) Ty.Adt(item, args) else Ty.Unknown
    }

    /** Type of the value a path denotes (binding, fn item, const, unit struct/variant). */
    private fun valueType(target: PsiElement?, path: RsPath): Ty = when (target) {
        is RsPatBinding -> bindingType(target)
        is RsSelfParameter -> selfParamType(target)
        is RsConstant -> typeOf(target.typeReference)
        is RsStructItem, is RsEnumVariant -> variantOrItemType(target, path)
        else -> Ty.Unknown
    }

    private fun variantOrItemType(target: PsiElement, path: RsPath): Ty = when (target) {
        is RsEnumVariant -> PsiTreeUtil.getParentOfType(target, RsEnumItem::class.java)?.let { Ty.Adt(it, emptyList()) } ?: Ty.Unknown
        is RsStructItem -> Ty.Adt(target, path.typeArgumentList?.typeReferenceList.orEmpty().map { typeOf(it) })
        is RsTypeAlias -> typeOf(target.typeReference)
        else -> Ty.Unknown
    }

    private fun selfParamType(self: RsSelfParameter): Ty {
        val impl = PsiTreeUtil.getParentOfType(self, RsImplItem::class.java)
        val base: Ty = when {
            impl != null -> typeOf(impl.typeReference)
            else -> PsiTreeUtil.getParentOfType(self, RsTraitItem::class.java)?.let { Ty.Dyn(listOf(it)) } ?: Ty.Unknown
        }
        self.typeReference?.let { return typeOf(it, Subst(self = base)) }
        return if (self.and != null) Ty.Ref(base) else base
    }

    fun bindingType(binding: RsPatBinding): Ty {
        val pat = binding.parent as? RsPatIdent
        val decl = pat?.parent
        return when (decl) {
            is RsLetDecl -> decl.typeReference?.let { typeOf(it) } ?: inferType(decl.expr)
            is RsValueParameter -> typeOf(decl.typeReference)
            is RsForExpr -> iterItem(inferType(decl.expr))
            else -> destructured(binding)
        }
    }

    /** `let (a, b) = …`, `if let Some(x) = …` and `for x in …` with simple patterns. */
    private fun destructured(binding: RsPatBinding): Ty {
        val ident = binding.parent as? RsPatIdent ?: return Ty.Unknown
        val container = ident.parent
        if (container is RsPatTupleStruct) {
            val scrutinee = scrutineeType(container) ?: return Ty.Unknown
            val variant = container.path.identifier?.text
            val adt = derefChain(scrutinee).filterIsInstance<Ty.Adt>().firstOrNull() ?: return Ty.Unknown
            val idx = container.patList.indexOf(ident)
            val v = (adt.item as? RsEnumItem)?.enumBody?.enumVariantList?.firstOrNull { it.name == variant }
            val field = v?.tupleFields?.tupleFieldDeclList?.getOrNull(idx) ?: return Ty.Unknown
            return typeOf(field.typeReference, adtSubst(adt))
        }
        return Ty.Unknown
    }

    private fun scrutineeType(pat: PsiElement): Ty? {
        var p: PsiElement? = pat.parent
        while (p is RsPat) p = p.parent
        return when (p) {
            is RsLetExpr -> inferType(p.expr)
            is RsLetDecl -> inferType(p.expr)
            is RsMatchArm -> inferType((p.parent?.parent as? RsMatchExpr)?.expr)
            else -> null
        }
    }

    private fun iterItem(ty: Ty): Ty {
        for (t in derefChain(ty)) {
            when (t) {
                is Ty.Slice -> return if (ty is Ty.Ref) Ty.Ref(t.elem) else t.elem
                is Ty.Adt -> {
                    assocType(t, "Iterator", "Item")?.let { return it }
                    assocType(t, "IntoIterator", "Item")?.let { return it }
                    if (t.item.name == "Vec") return if (ty is Ty.Ref) Ty.Ref(t.args.firstOrNull() ?: Ty.Unknown) else t.args.firstOrNull() ?: Ty.Unknown
                }
                else -> {}
            }
        }
        return Ty.Unknown
    }

    private fun indexType(ty: Ty): Ty {
        for (t in derefChain(ty)) {
            when (t) {
                is Ty.Slice -> return t.elem
                is Ty.Adt -> when (t.item.name) {
                    "Vec", "VecDeque" -> return t.args.firstOrNull() ?: Ty.Unknown
                    "HashMap", "BTreeMap" -> return t.args.getOrNull(1) ?: Ty.Unknown
                }
                else -> {}
            }
        }
        return Ty.Unknown
    }

    private fun unwrapTry(ty: Ty): Ty {
        val adt = ty as? Ty.Adt ?: return Ty.Unknown
        return if (adt.item.name == "Option" || adt.item.name == "Result") adt.args.firstOrNull() ?: Ty.Unknown else Ty.Unknown
    }

    private fun callType(call: RsCallExpr): Ty {
        val callee = call.expr as? RsPathExpr ?: return Ty.Unknown
        val target = NameResolver.resolvePath(callee.path).firstOrNull() ?: return Ty.Unknown
        return when (target) {
            is RsFunction -> {
                val owner = callee.path.path?.let { q -> NameResolver.resolvePath(q).firstOrNull() }
                val self = when (owner) {
                    is RsStructItem, is RsEnumItem -> Ty.Adt(owner as RsNamedElement, callee.path.path?.typeArgumentList?.typeReferenceList.orEmpty().map { typeOf(it) })
                    else -> null
                }
                val implSubst = (target.parent?.parent as? RsImplItem)?.let { impl -> self?.let { unifyImpl(impl, it) } ?: Subst(self = typeOf(impl.typeReference)) }
                typeOf(target.retType?.typeReference, implSubst ?: Subst(self = self))
            }
            is RsStructItem -> Ty.Adt(target, emptyList())
            is RsEnumVariant -> variantOrItemType(target, callee.path)
            else -> Ty.Unknown
        }
    }

    private fun dotType(dot: RsDotExpr): Ty {
        val receiver = inferType(dot.expr)
        dot.methodCall?.let { call ->
            val hit = methodCandidates(receiver, call.identifier.text, dot.project).firstOrNull() ?: return Ty.Unknown
            return typeOf(hit.fn.retType?.typeReference, hit.subst)
        }
        dot.fieldLookup?.let { lookup ->
            val name = lookup.identifier?.text ?: lookup.integerLiteral?.text ?: return Ty.Unknown
            for (t in derefChain(receiver)) {
                when (t) {
                    is Ty.Adt -> NameResolver.fieldsOf(t.item).firstOrNull { NameResolver.fieldName(it) == name }?.let { f ->
                        val ref = (f as? RsNamedFieldDecl)?.typeReference ?: (f as? RsTupleFieldDecl)?.typeReference
                        return typeOf(ref, adtSubst(t))
                    }
                    is Ty.Tuple -> name.toIntOrNull()?.let { return t.items.getOrNull(it) ?: Ty.Unknown }
                    else -> {}
                }
            }
        }
        return Ty.Unknown
    }
}
