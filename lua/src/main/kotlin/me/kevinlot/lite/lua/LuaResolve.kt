package me.kevinlot.lite.lua

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.lua.LuaElementTypes as E
import me.kevinlot.lite.lua.LuaTokens as T

/*
 * Lua resolution:
 * - names: lexical scopes (locals declared before the use, parameters, loop variables, `local function` inside its
 *   own body), else globals (`x = …`, `function x()`: this file, then the files it requires, then the project);
 * - fields: tables are followed through locals, `require`, `return M`, `setmetatable(t, { __index = M })`, function
 *   returns and `self` in `function T:m()`; a table's fields are its constructor's and every `T.f = …` /
 *   `function T.f()` / `self.f = …` of its file. The first definition is the target.
 */
object LuaResolve {
    private val TYPING = ThreadLocal.withInitial { HashSet<PsiElement>() }

    // ------------------------------------------------------------ names

    fun resolveName(ref: LuaNameRef): List<PsiElement> {
        if (ref.parent?.node?.elementType == E.GOTO_STMT) return listOfNotNull(label(ref))
        local(ref, ref.name)?.let { return listOf(it) }
        return listOfNotNull(global(ref.containingFile, ref.name))
    }

    /** The local [name] visible at [place]. */
    fun local(place: PsiElement, name: String): LuaNameDecl? {
        var child: PsiElement = place
        var p = place.parent
        while (p != null && p !is PsiFile) {
            when (p.node.elementType) {
                E.BLOCK -> {
                    var s = child.prevSibling
                    while (s != null) { declsOf(s).lastOrNull { it.name == name }?.let { return it }; s = s.prevSibling }
                }
                E.FUNC_BODY -> (p as LuaFuncBody).params.lastOrNull { it.name == name }?.let { return it }
                E.FOR_NUM, E.FOR_IN -> if (child is LuaBlock) p.children.filterIsInstance<LuaNameDecl>().lastOrNull { it.name == name }?.let { return it }
                E.LOCAL_FUNCTION -> if (child is LuaFuncBody) p.children.filterIsInstance<LuaNameDecl>().firstOrNull { it.name == name }?.let { return it }
                E.REPEAT_STMT -> if (child !is LuaBlock) p.children.filterIsInstance<LuaBlock>().firstOrNull()?.let { b ->
                    b.children.reversed().forEach { s -> declsOf(s).lastOrNull { it.name == name }?.let { return it } }
                }
            }
            child = p
            p = p.parent
        }
        return null
    }

    private fun declsOf(stmt: PsiElement): List<LuaNameDecl> = when (stmt.node?.elementType) {
        E.LOCAL_STMT, E.LOCAL_FUNCTION -> stmt.children.filterIsInstance<LuaNameDecl>()
        else -> emptyList()
    }

    private fun label(ref: LuaNameRef): LuaNameDecl? {
        val scope = functionOf(ref) ?: ref.containingFile
        return PsiTreeUtil.findChildrenOfType(scope, LuaNameDecl::class.java).firstOrNull { it.isLabel && it.name == ref.name && functionOf(it) == functionOf(ref) }
    }

    fun functionOf(e: PsiElement): LuaFuncBody? = PsiTreeUtil.getParentOfType(e, LuaFuncBody::class.java)

    private val GLOBALS = Key.create<CachedValue<Map<String, List<LuaNameRef>>>>("lite.lua.globals")

    /** Global definitions of a file, in source order. */
    fun globals(file: PsiFile): Map<String, List<LuaNameRef>> = CachedValuesManager.getCachedValue(file, GLOBALS) {
        val defs = PsiTreeUtil.findChildrenOfType(file, LuaNameRef::class.java).filter { it.isGlobalDefinition }.groupBy { it.name }
        CachedValueProvider.Result.create(defs, PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** First definition of global [name]: this file, the files it requires (transitively), then the project. */
    fun global(from: PsiFile, name: String): LuaNameRef? {
        val seen = LinkedHashSet<PsiFile>()
        val queue = ArrayDeque(listOf(from.originalFile))
        while (queue.isNotEmpty() && seen.size < 200) {
            val f = queue.removeFirst()
            if (!seen.add(f)) continue
            globals(f)[name]?.firstOrNull()?.let { return it }
            queue += LuaRequire.requiredFiles(f)
        }
        val project = from.project
        return FileTypeIndex.getFiles(LuaFileType, GlobalSearchScope.projectScope(project)).sortedBy { it.path }.asSequence()
            .mapNotNull { PsiManager.getInstance(project).findFile(it) }.filter { it !in seen }
            .firstNotNullOfOrNull { globals(it)[name]?.firstOrNull() }
    }

    // ------------------------------------------------------------ fields

    fun resolveField(e: LuaIndexExpr): List<PsiElement> {
        val name = e.fieldName ?: return emptyList()
        val tables = typeOf(e.base)
        for (t in tables) fieldDefs(t, name).firstOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    /** Definitions of field [name] of table [t] (its constructor, then assignments in its file), in source order. */
    fun fieldDefs(t: PsiElement, name: String): List<PsiElement> {
        val own = (t as? LuaTable)?.children?.filterIsInstance<LuaTableField>()?.filter { it.name == name }.orEmpty()
        val file = t.containingFile ?: return own
        val assigned = PsiTreeUtil.findChildrenOfType(file, LuaIndexExpr::class.java)
            .filter { it.isDefinitionSite && it.fieldName == name && t in typeOf(it.base) }
        return own + assigned
    }

    /** Tables an expression may be (constructors, or definitions whose value is unknown but which own fields). */
    fun typeOf(e: PsiElement?): List<PsiElement> {
        e ?: return emptyList()
        val typing = TYPING.get()
        if (!typing.add(e)) return emptyList()
        try {
            return when (e) {
                is LuaTable -> listOf(e)
                is LuaNameRef -> nameType(e)
                is LuaIndexExpr -> {
                    val name = e.fieldName ?: return emptyList()
                    typeOf(e.base).flatMap { t -> fieldDefs(t, name).flatMap { valueTypes(it) } }.distinct()
                }
                is LuaCall -> callType(e)
                else -> when (e.node.elementType) {
                    E.PAREN -> typeOf(e.children.firstOrNull { it is LuaElement })
                    E.BINARY -> {
                        val op = e.node.getChildren(null).firstOrNull { it.elementType == T.kw("or") || it.elementType == T.kw("and") }
                        if (op == null) emptyList() else e.children.filter { it is LuaElement }.flatMap { typeOf(it) }.distinct()
                    }
                    else -> emptyList()
                }
            }
        } finally {
            typing.remove(e)
        }
    }

    private fun nameType(ref: LuaNameRef): List<PsiElement> {
        val local = local(ref, ref.name)
        if (local != null) return valueTypes(local)
        if (ref.name == "self") methodOwner(ref)?.let { return typeOf(it) }
        val global = global(ref.containingFile, ref.name) ?: return emptyList()
        // a global table: its value, or the definition itself (`M = M or {}` resolves to the constructor)
        return valueTypes(global).ifEmpty { listOf(global) }
    }

    /** `self` inside `function T:m()`: `T`. */
    private fun methodOwner(place: PsiElement): PsiElement? {
        var body = functionOf(place)
        while (body != null) {
            val stmt = body.parent
            if (stmt?.node?.elementType == E.FUNCTION_STMT) {
                val target = stmt.children.firstOrNull { it.node.elementType == E.FUNC_NAME }?.lastChild as? LuaIndexExpr
                if (target?.isMethod == true) return target.base
            }
            body = functionOf(body)
        }
        return null
    }

    /** Types of the value a definition holds. */
    fun valueTypes(def: PsiElement): List<PsiElement> = valueOf(def)?.let(::typeOf).orEmpty()

    /** The expression assigned by a definition (local, global, field), or null. */
    fun valueOf(def: PsiElement): PsiElement? {
        if (def is LuaTableField) return def.value
        val parent = def.parent ?: return null
        return when (parent.node.elementType) {
            E.LOCAL_STMT -> {
                val names = parent.children.filterIsInstance<LuaNameDecl>()
                val values = parent.children.filter { it is LuaElement && it !is LuaNameDecl }
                values.getOrNull(names.indexOf(def))
            }
            E.TARGETS -> {
                val stmt = parent.parent ?: return null
                val targets = parent.children.filter { it is LuaElement }
                stmt.children.filter { it is LuaElement && it != parent }.getOrNull(targets.indexOf(def))
            }
            else -> null
        }
    }

    private fun callType(call: LuaCall): List<PsiElement> {
        val callee = call.callee
        if (callee is LuaNameRef && local(callee, callee.name) == null) {
            when (callee.name) {
                "require" -> {
                    val module = (call.args.firstOrNull() as? LuaLiteral)?.stringValue ?: return emptyList()
                    return LuaRequire.target(call.containingFile, module, true)?.let(::moduleType).orEmpty()
                }
                "setmetatable" -> {
                    val args = call.args
                    val meta = typeOf(args.getOrNull(1)).flatMap { mt -> fieldDefs(mt, "__index").flatMap { valueTypes(it) } }
                    return (typeOf(args.firstOrNull()) + meta).distinct()
                }
            }
        }
        val function = functionDefinition(callee) ?: return emptyList()
        return returnsOf(function).flatMap { typeOf(it) }.distinct()
    }

    /** The table a module file returns (`return M`). */
    fun moduleType(file: PsiFile): List<PsiElement> {
        val ret = (file as? LuaFile)?.block?.children?.lastOrNull { it.node.elementType == E.RETURN_STMT } ?: return emptyList()
        return typeOf(ret.children.firstOrNull { it is LuaElement })
    }

    /** The body of the function a callee designates. */
    private fun functionDefinition(callee: PsiElement?): LuaFuncBody? {
        val defs: List<PsiElement> = when (callee) {
            is LuaNameRef -> resolveName(callee)
            is LuaIndexExpr -> resolveField(callee)
            else -> emptyList()
        }
        for (d in defs) bodyOf(d)?.let { return it }
        return null
    }

    fun bodyOf(def: PsiElement): LuaFuncBody? {
        val parent = def.parent
        if (parent?.node?.elementType == E.LOCAL_FUNCTION) return PsiTreeUtil.getChildOfType(parent, LuaFuncBody::class.java)
        if (parent?.node?.elementType == E.FUNC_NAME) return PsiTreeUtil.getChildOfType(parent.parent, LuaFuncBody::class.java)
        val value = valueOf(def) ?: return null
        return if (value.node.elementType == E.FUNCTION_EXPR) PsiTreeUtil.getChildOfType(value, LuaFuncBody::class.java) else null
    }

    private fun returnsOf(body: LuaFuncBody): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(body, LuaElement::class.java)
            .filter { it.node.elementType == E.RETURN_STMT && functionOf(it) == body }
            .mapNotNull { r -> r.children.firstOrNull { it is LuaElement } }
}

/** `require("a.b")` → `a/b.lua` or `a/b/init.lua` under the project's Lua roots; `dofile`/`loadfile` → a path. */
object LuaRequire {
    private val CALLS = mapOf("require" to true, "dofile" to false, "loadfile" to false)

    fun referenceOf(literal: LuaLiteral): LuaRequireReference? {
        val args = literal.parent?.takeIf { it.node.elementType == E.ARGS } ?: return null
        val call = args.parent as? LuaCall ?: return null
        val callee = call.callee as? LuaNameRef ?: return null
        val dotted = CALLS[callee.text] ?: return null
        if (args.children.firstOrNull { it is LuaElement } != literal) return null
        val value = literal.stringValue?.takeIf { it.isNotEmpty() } ?: return null
        return LuaRequireReference(literal, value, dotted)
    }

    /** Folders a module name is searched from: the content root (and its `lua/`, `src/`), then the file's folders. */
    private fun roots(from: PsiFile): List<VirtualFile> {
        val vf = from.originalFile.virtualFile ?: return emptyList()
        val root = ProjectFileIndex.getInstance(from.project).getContentRootForFile(vf)
        val out = LinkedHashSet<VirtualFile>()
        root?.let { r -> out += r; listOf("lua", "src", "lib").mapNotNullTo(out) { r.findChild(it) } }
        var dir = vf.parent
        while (dir != null) {
            out += dir
            dir.findChild("lua")?.let { out += it }
            if (dir == root) break
            dir = dir.parent
        }
        return out.toList()
    }

    fun target(from: PsiFile, module: String, dotted: Boolean): PsiFile? {
        val manager = PsiManager.getInstance(from.project)
        val candidates = if (dotted) module.replace('.', '/').let { listOf("$it.lua", "$it/init.lua") } else listOf(module)
        for (r in roots(from)) for (c in candidates) r.findFileByRelativePath(c)?.takeIf { !it.isDirectory }?.let { f -> manager.findFile(f)?.let { return it } }
        return null
    }

    private val REQUIRED = Key.create<CachedValue<List<PsiFile>>>("lite.lua.required")

    fun requiredFiles(file: PsiFile): List<PsiFile> = CachedValuesManager.getCachedValue(file, REQUIRED) {
        val files = PsiTreeUtil.findChildrenOfType(file, LuaLiteral::class.java).mapNotNull { referenceOf(it) }
            .mapNotNull { it.targets().firstOrNull() as? PsiFile }.distinct()
        CachedValueProvider.Result.create(files, PsiModificationTracker.MODIFICATION_COUNT)
    }
}
