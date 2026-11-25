package me.kevinlot.lite.lua

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.TokenSet
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
 *   own body), else globals (`x = …`, `function x()`, `_G.x = …`: this file, then the files it requires, then the
 *   project);
 * - fields: tables are followed through locals, `require`, `return M`, `setmetatable(t, { __index = M })`, function
 *   returns and `self` in `function T:m()`; a table's fields are its constructor's and every `T.f = …` /
 *   `function T.f()` / `self.f = …` of its file. The first definition is the target. A name whose value is unknown
 *   owns the fields assigned through it;
 * - annotations: `---@type` / `---@param` / `---@return` give a `---@class`, which owns its `---@field` lines, the
 *   fields of the definition it documents and those of its parent classes.
 */
object LuaResolve {
    private val TYPING = ThreadLocal.withInitial { HashSet<PsiElement>() }
    private val CHOICE_OPERATORS = TokenSet.create(T.kw("or"), T.kw("and"))

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
        val function = functionOf(ref)
        return PsiTreeUtil.findChildrenOfType(function ?: ref.containingFile, LuaNameDecl::class.java)
            .firstOrNull { it.isLabel && it.name == ref.name && functionOf(it) == function }
    }

    fun functionOf(e: PsiElement): LuaFuncBody? = PsiTreeUtil.getParentOfType(e, LuaFuncBody::class.java)

    private val GLOBALS = Key.create<CachedValue<Map<String, List<PsiNameIdentifierOwner>>>>("lite.lua.globals")

    /** Global definitions of a file (`x = …`, `function x()`, `_G.x = …`), in source order. */
    fun globals(file: PsiFile): Map<String, List<PsiNameIdentifierOwner>> = CachedValuesManager.getCachedValue(file, GLOBALS) {
        val defs = PsiTreeUtil.findChildrenOfAnyType(file, LuaNameRef::class.java, LuaFieldAccess::class.java).filter(::isGlobalDefinition)
        CachedValueProvider.Result.create(defs.groupBy { it.name.orEmpty() }, file)
    }

    private fun isGlobalDefinition(e: PsiElement): Boolean = when (e) {
        is LuaNameRef -> e.isGlobalDefinition
        is LuaFieldAccess -> e.isGlobalDefinition
        else -> false
    }

    /** The Lua files of the project, in path order. */
    private fun projectFiles(project: Project): List<PsiFile> {
        val manager = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(LuaFileType, GlobalSearchScope.projectScope(project)).sortedBy { it.path }.mapNotNull { manager.findFile(it) }
    }

    private val PROJECT_GLOBALS = Key.create<CachedValue<Map<String, PsiNameIdentifierOwner>>>("lite.lua.projectGlobals")

    /** First definition of every global of the project, its files taken in path order. */
    private fun projectGlobals(project: Project): Map<String, PsiNameIdentifierOwner> = CachedValuesManager.getManager(project).getCachedValue(project, PROJECT_GLOBALS, {
        val first = projectFiles(project).flatMap { globals(it).entries }.distinctBy { it.key }.associate { it.key to it.value.first() }
        CachedValueProvider.Result.create(first, PsiModificationTracker.MODIFICATION_COUNT)
    }, false)

    /** First definition of global [name]: this file, the files it requires (transitively), then the project. */
    fun global(from: PsiFile, name: String): PsiNameIdentifierOwner? {
        val seen = LinkedHashSet<PsiFile>()
        val queue = ArrayDeque(listOf(from.originalFile))
        while (queue.isNotEmpty() && seen.size < 200) {
            val f = queue.removeFirst()
            if (!seen.add(f)) continue
            globals(f)[name]?.firstOrNull()?.let { return it }
            queue += LuaRequire.requiredFiles(f)
        }
        return projectGlobals(from.project)[name]
    }

    // ------------------------------------------------------------ fields

    fun resolveField(e: LuaFieldAccess): List<PsiElement> {
        val name = e.fieldName ?: return emptyList()
        if (e.isGlobalAccess) return listOfNotNull(global(e.containingFile, name))
        val tables = typeOf(e.base)
        for (t in tables) fieldDefs(t, name).firstOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    private val FIELD_SITES = Key.create<CachedValue<Map<String, List<LuaFieldAccess>>>>("lite.lua.fieldSites")

    /** `T.f = …` / `T["f"] = …` / `function T.f()` sites of a file by field name, in source order. */
    private fun fieldSites(file: PsiFile): Map<String, List<LuaFieldAccess>> = CachedValuesManager.getCachedValue(file, FIELD_SITES) {
        val sites = PsiTreeUtil.findChildrenOfType(file, LuaFieldAccess::class.java).filter { it.isDefinitionSite && it.fieldName != null }
        CachedValueProvider.Result.create(sites.groupBy { it.fieldName.orEmpty() }, file)
    }

    /** Definitions of field [name] of [t] (its constructor or `---@field` lines, then assignments in its file), in source order. */
    fun fieldDefs(t: PsiElement, name: String): List<PsiElement> {
        val own = when (t) {
            is LuaTable -> t.children.filterIsInstance<LuaTableField>()
            is LuaDocDeclaration -> docFields(t)
            else -> emptyList()
        }.filter { it.name == name }
        val file = t.containingFile ?: return own
        val assigned = fieldSites(file)[name].orEmpty().filter { t in typeOf(it.base) }
        return own + assigned
    }

    /** Tables an expression may be (constructors, classes, or definitions whose value is unknown but which own fields). */
    fun typeOf(e: PsiElement?): List<PsiElement> {
        e ?: return emptyList()
        val typing = TYPING.get()
        if (!typing.add(e)) return emptyList()
        try {
            return when (e) {
                is LuaTable -> listOf(e)
                is LuaNameRef -> nameType(e)
                is LuaFieldAccess -> {
                    val name = e.fieldName ?: return emptyList()
                    if (e.isGlobalAccess) return globalType(e.containingFile, name)
                    typeOf(e.base).flatMap { t -> fieldDefs(t, name).flatMap { valueTypes(it) } }.distinct()
                }
                is LuaCall -> callType(e)
                else -> when (e.node.elementType) {
                    E.PAREN -> typeOf(e.children.firstOrNull { it is LuaElement })
                    E.BINARY -> {
                        if (e.node.findChildByType(CHOICE_OPERATORS) == null) return emptyList()
                        e.children.filter { it is LuaElement }.flatMap { typeOf(it) }.distinct()
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
        if (local != null) return valueTypes(local).ifEmpty { listOf(local) }
        if (ref.name == "self") methodOwner(ref)?.let { return typeOf(it) }
        return globalType(ref.containingFile, ref.name)
    }

    /** A global table: its value, or the definition itself (`M = M or {}` resolves to the constructor). */
    private fun globalType(from: PsiFile, name: String): List<PsiElement> {
        val global = global(from, name) ?: return emptyList()
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

    /** Types of the value a definition holds: the annotated ones, then those of the expression assigned. */
    fun valueTypes(def: PsiElement): List<PsiElement> = (declaredTypes(def) + valueOf(def)?.let(::typeOf).orEmpty()).distinct()

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
        return (declaredReturn(function) + returnsOf(function).flatMap { typeOf(it) }).distinct()
    }

    /** The table a module file returns (`return M`). */
    fun moduleType(file: PsiFile): List<PsiElement> {
        val ret = (file as? LuaFile)?.block?.children?.lastOrNull { it.node.elementType == E.RETURN_STMT } ?: return emptyList()
        return typeOf(ret.children.firstOrNull { it is LuaElement })
    }

    /** The body of the function a callee designates, followed through aliases (`local new = M.new`). */
    private fun functionDefinition(callee: PsiElement?, aliases: Int = 0): LuaFuncBody? {
        val defs: List<PsiElement> = when (callee) {
            is LuaNameRef -> resolveName(callee)
            is LuaFieldAccess -> resolveField(callee)
            else -> emptyList()
        }
        return defs.firstNotNullOfOrNull { d -> bodyOf(d) ?: valueOf(d)?.takeIf { aliases < MAX_ALIASES }?.let { functionDefinition(it, aliases + 1) } }
    }

    private const val MAX_ALIASES = 8

    fun bodyOf(def: PsiElement): LuaFuncBody? {
        val parent = def.parent
        if (parent?.node?.elementType == E.LOCAL_FUNCTION) return PsiTreeUtil.getChildOfType(parent, LuaFuncBody::class.java)
        if (parent?.node?.elementType == E.FUNC_NAME) return PsiTreeUtil.getChildOfType(parent.parent, LuaFuncBody::class.java)
        val function = valueOf(def)?.takeIf { it.node.elementType == E.FUNCTION_EXPR } ?: return null
        return PsiTreeUtil.getChildOfType(function, LuaFuncBody::class.java)
    }

    private fun returnsOf(body: LuaFuncBody): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(body, LuaElement::class.java)
            .filter { it.node.elementType == E.RETURN_STMT && functionOf(it) == body }
            .mapNotNull { r -> r.children.firstOrNull { it is LuaElement } }

    // ------------------------------------------------------------ annotations

    private val CLASSES = Key.create<CachedValue<Map<String, LuaDocDeclaration>>>("lite.lua.classes")

    /** First `---@class` of every name in a file. */
    private fun classes(file: PsiFile): Map<String, LuaDocDeclaration> = CachedValuesManager.getCachedValue(file, CLASSES) {
        val declared = PsiTreeUtil.findChildrenOfType(file, LuaDocDeclaration::class.java).filter { it.tag == LuaTag.CLASS }
        CachedValueProvider.Result.create(declared.reversed().associateBy { it.name.orEmpty() }, file)
    }

    private val PROJECT_CLASSES = Key.create<CachedValue<Map<String, LuaDocDeclaration>>>("lite.lua.projectClasses")

    private fun projectClasses(project: Project): Map<String, LuaDocDeclaration> = CachedValuesManager.getManager(project).getCachedValue(project, PROJECT_CLASSES, {
        val first = projectFiles(project).flatMap { classes(it).entries }.distinctBy { it.key }.associate { it.key to it.value }
        CachedValueProvider.Result.create(first, PsiModificationTracker.MODIFICATION_COUNT)
    }, false)

    /** The `---@class` [name]: of this file, else the first of the project. */
    fun classOf(from: PsiFile, name: String): LuaDocDeclaration? = classes(from.originalFile)[name] ?: projectClasses(from.project)[name]

    /** Comments and line ends: what may separate the lines of one annotation block, and the block from its statement. */
    private fun inDocBlock(leaf: PsiElement): Boolean = leaf is PsiComment || (leaf is PsiWhiteSpace && leaf.text.count { it == '\n' } < 2)

    /** The annotation lines right above a statement, nearest first. */
    private fun docAbove(stmt: PsiElement?): Sequence<LuaDocComment> =
        generateSequence(stmt?.let { PsiTreeUtil.prevLeaf(it) }) { PsiTreeUtil.prevLeaf(it) }.takeWhile(::inDocBlock).filterIsInstance<LuaDocComment>()

    private fun leavesBelow(doc: LuaDocComment): Sequence<PsiElement> = generateSequence(PsiTreeUtil.nextLeaf(doc)) { PsiTreeUtil.nextLeaf(it) }

    /** The `---@field` lines under a `---@class` line. */
    private fun docFields(cls: LuaDocDeclaration): List<LuaDocDeclaration> =
        leavesBelow(cls).takeWhile(::inDocBlock).filterIsInstance<LuaDocDeclaration>().takeWhile { it.tag == LuaTag.FIELD }.toList()

    /** The definition a `---@class` line documents: the first name of the statement right below its block. */
    private fun documented(cls: LuaDocDeclaration): PsiElement? {
        val stmt = leavesBelow(cls).firstOrNull { !inDocBlock(it) }?.takeIf { it !is PsiWhiteSpace }?.let(::statementOf) ?: return null
        return when (stmt.node.elementType) {
            E.LOCAL_STMT -> stmt.children.firstOrNull { it is LuaNameDecl }
            E.ASSIGN_STMT -> stmt.firstChild?.firstChild
            else -> null
        }
    }

    private fun statementOf(e: PsiElement): PsiElement? = generateSequence(e) { it.parent }.firstOrNull { it.parent is LuaBlock }

    /** The class [name], the value of the definition it documents, then its parent classes likewise. */
    private fun classTypes(name: String?, from: PsiFile, seen: MutableSet<String> = HashSet()): List<PsiElement> {
        val cls = name?.takeIf(seen::add)?.let { classOf(from, it) } ?: return emptyList()
        val value = documented(cls)?.let(::valueOf)?.let(::typeOf).orEmpty()
        return (listOf(cls) + value + classTypes(cls.typeName, cls.containingFile, seen)).distinct()
    }

    /** Annotated types of a definition: `---@type` / `---@class` above its statement, `---@param` above its function, a `---@field`'s own. */
    private fun declaredTypes(def: PsiElement): List<PsiElement> {
        if (def is LuaDocComment) return classTypes(def.typeName.takeIf { def.tag == LuaTag.FIELD }, def.containingFile)
        val parent = def.parent ?: return emptyList()
        val doc = when (parent.node.elementType) {
            E.PARAMS -> docAbove(statementOf(parent)).firstOrNull { it.tag == LuaTag.PARAM && it.annotatedName == def.text }
            E.LOCAL_STMT -> docAbove(parent).firstOrNull { it.tag in VALUE_TAGS }
            E.TARGETS -> docAbove(parent.parent).firstOrNull { it.tag in VALUE_TAGS }
            else -> null
        } ?: return emptyList()
        val name = when (doc.tag) {
            LuaTag.CLASS -> doc.annotatedName
            else -> doc.typeName
        }
        return classTypes(name, def.containingFile)
    }

    private val VALUE_TAGS = setOf(LuaTag.TYPE, LuaTag.CLASS)

    private fun declaredReturn(body: LuaFuncBody): List<PsiElement> =
        classTypes(docAbove(statementOf(body)).lastOrNull { it.tag == LuaTag.RETURN }?.typeName, body.containingFile)
}

/** `require("a.b")` → `a/b.lua` or `a/b/init.lua` under the project's Lua roots; `dofile`/`loadfile` → a path. */
object LuaRequire {
    private val CALLS = mapOf("require" to true, "dofile" to false, "loadfile" to false)
    private val ROOT_FOLDERS = listOf("lua", "src", "lib")

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
        root?.let { r -> out += r; ROOT_FOLDERS.mapNotNullTo(out) { r.findChild(it) } }
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
        val candidates = candidates(module, dotted)
        for (r in roots(from)) for (c in candidates) r.findFileByRelativePath(c)?.takeIf { !it.isDirectory }?.let { f -> manager.findFile(f)?.let { return it } }
        return null
    }

    private fun candidates(module: String, dotted: Boolean): List<String> {
        if (!dotted) return listOf(module)
        val path = module.replace('.', '/')
        return listOf("$path.lua", "$path/init.lua")
    }

    private val REQUIRED = Key.create<CachedValue<List<PsiFile>>>("lite.lua.required")

    fun requiredFiles(file: PsiFile): List<PsiFile> = CachedValuesManager.getCachedValue(file, REQUIRED) {
        val files = PsiTreeUtil.findChildrenOfType(file, LuaLiteral::class.java).mapNotNull { referenceOf(it) }
            .mapNotNull { it.targets().firstOrNull() as? PsiFile }.distinct()
        CachedValueProvider.Result.create(files, PsiModificationTracker.MODIFICATION_COUNT)
    }
}
