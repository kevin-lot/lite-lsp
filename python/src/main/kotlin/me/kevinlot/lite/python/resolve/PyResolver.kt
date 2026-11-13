package me.kevinlot.lite.python.resolve

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import me.kevinlot.lite.core.LibraryOracle
import me.kevinlot.lite.core.LiteCache
import me.kevinlot.lite.core.LiteReference
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.python.parser.PyFile
import me.kevinlot.lite.python.psi.*
import me.kevinlot.lite.python.upstream.PyElementTypes as E
import me.kevinlot.lite.python.upstream.PyTokenTypes as T

class PyNameReference(element: PyReferenceExpr) : LiteReference<PyReferenceExpr>(element, { element.nameElement }) {
    override fun resolveTargets(): List<PsiElement> = PyResolver.resolve(element)
    override fun renameIdentifier(nameToken: PsiElement, newName: String): PsiElement = PyPsiFactory(element.project).identifier(newName)
}

/** Interpreter libraries are read-only for the session; the environment tracker invalidates them. */
object PyLibraryOracle : LibraryOracle {
    override fun isLibrary(e: PsiElement): Boolean {
        val vf = e.containingFile?.originalFile?.virtualFile ?: return false
        return com.intellij.openapi.roots.ProjectFileIndex.getInstance(e.project).isInLibrary(vf)
    }
    override fun tracker(e: PsiElement): ModificationTracker = PyEnvironments.getInstance(e.project).modificationTracker
}

/** Types: instances of classes and collections of them (from annotations, constructors, `self`). */
sealed class PyTy {
    data class Inst(val cls: PyClass) : PyTy()
    data class Coll(val elem: PyTy) : PyTy()
    data object Unknown : PyTy()
}

/** Names bound in a scope (module, class body, function), not descending into nested scopes. */
object PyScopes {
    private val NESTED = com.intellij.psi.tree.TokenSet.create(E.LAMBDA_EXPRESSION, E.LIST_COMP_EXPRESSION, E.SET_COMP_EXPRESSION, E.DICT_COMP_EXPRESSION, E.GENERATOR_EXPRESSION)
    private val KEY = Key.create<CachedValue<List<PsiElement>>>("lite.py.bindings")

    fun bindings(scope: PsiElement): List<PsiElement> = LiteCache.cached(scope, KEY, PyLibraryOracle) {
        val out = mutableListOf<PsiElement>()
        fun walk(e: PsiElement) {
            for (c in e.children) {
                when {
                    c is PyFunction || c is PyClass -> out += c // its own name; its body is another scope
                    c.node.elementType in NESTED -> {}
                    c is PyTarget -> if (c.qualifier == null) out += c
                    c is PyImportElement -> out += c
                    c.node.elementType == E.STAR_IMPORT_ELEMENT -> out += c
                    else -> walk(c)
                }
            }
        }
        val body = when (scope) {
            is PyFunction -> scope.body
            is PyClass -> scope.body
            else -> scope
        }
        if (scope is PyFunction) out += scope.parameters
        body?.let { walk(it) }
        out
    }
}

object PyResolver {
    private val GUARD = RecursionGuard(48)
    private val SPACES = Regex("\\s")
    private val RESOLVE = Key.create<CachedValue<List<PsiElement>>>("lite.py.resolve")
    private val TYPE = Key.create<CachedValue<PyTy>>("lite.py.type")
    private val TYPING_ONE = setOf("Optional", "Final", "ClassVar", "Annotated", "Type", "type")
    private val TYPING_COLL = setOf("list", "List", "set", "Set", "frozenset", "Sequence", "Iterable", "Iterator", "Collection", "tuple", "Tuple", "MutableSequence", "Generator", "deque")

    fun resolve(ref: PyReferenceExpr): List<PsiElement> =
        GUARD.run(emptyList()) { LiteCache.cachedNonRecursive(ref, RESOLVE, PyLibraryOracle, emptyList()) { doResolve(ref).distinct() } }

    private fun doResolve(ref: PyReferenceExpr): List<PsiElement> {
        val name = ref.referencedName ?: return emptyList()
        importPath(ref)?.let { return it }
        val q = ref.qualifier
        return if (q != null) members(q, name) else lexical(name, ref).flatMap { follow(it) }
    }

    // ---------------------------------------------------------------- imports

    /** `import a.b`, `from a.b import c`, `from . import x`: resolved as module paths, never lexically. */
    private fun importPath(ref: PyReferenceExpr): List<PsiElement>? {
        val stmt = PsiTreeUtil.getParentOfType(ref, PyElement::class.java)?.let {
            PsiTreeUtil.findFirstParent(ref) { e -> e.node?.elementType == E.IMPORT_STATEMENT || e.node?.elementType == E.FROM_IMPORT_STATEMENT }
        } ?: return null
        val path = ref.text.replace(SPACES, "")
        val element = PsiTreeUtil.getParentOfType(ref, PyImportElement::class.java)
        if (stmt.node.elementType == E.FROM_IMPORT_STATEMENT && element != null) {
            // `from m import name`: name is an attribute of m (or a submodule)
            val module = fromModule(stmt, ref) ?: return emptyList()
            return moduleMember(module, path, ref)
        }
        val base = if (stmt.node.elementType == E.FROM_IMPORT_STATEMENT) relativeBase(stmt, ref) else null
        return listOfNotNull(findModule(ref, path, base))
    }

    /** Module of a `from ... import` statement. */
    private fun fromModule(stmt: PsiElement, place: PsiElement): PsiElement? {
        val modRef = stmt.children.firstOrNull { it is PyReferenceExpr } as? PyReferenceExpr
        val base = relativeBase(stmt, place)
        return if (modRef == null) base?.let { PsiManager.getInstance(place.project).findDirectory(it) }
        else findModule(place, modRef.text.replace(SPACES, ""), base)
    }

    /** `from ..pkg import x`: the directory the dots point to (null for absolute imports). */
    private fun relativeBase(stmt: PsiElement, place: PsiElement): VirtualFile? {
        val modRef = stmt.children.firstOrNull { it is PyReferenceExpr }
        var dots = 0
        var n = stmt.node.firstChildNode
        while (n != null && n.elementType != T.IMPORT_KEYWORD && n.psi != modRef) {
            if (n.elementType == T.DOT) dots++
            n = n.treeNext
        }
        if (dots == 0) return null
        var dir = place.containingFile.originalFile.virtualFile?.parent
        repeat(dots - 1) { dir = dir?.parent }
        return dir
    }

    /** `a.b.c` -> `a/b/c.py`, `a/b/c/__init__.py` or the namespace package directory, in the import roots. */
    fun findModule(place: PsiElement, dotted: String, base: VirtualFile? = null): PsiElement? {
        val vf = place.containingFile.originalFile.virtualFile
        val rel = dotted.replace('.', '/')
        val candidates = if (base != null) listOf(base) else PyEnvironments.getInstance(place.project).rootsFor(vf).mapNotNull { dirOf(it, vf) }
        val pm = PsiManager.getInstance(place.project)
        for (root in candidates) {
            val file = root.findFileByRelativePath("$rel.py") ?: root.findFileByRelativePath("$rel/__init__.py")
            if (file != null) return pm.findFile(file)
            root.findFileByRelativePath(rel)?.takeIf { it.isDirectory }?.let { return pm.findDirectory(it) }
        }
        return null
    }

    private fun dirOf(path: String, hint: VirtualFile?): VirtualFile? =
        hint?.fileSystem?.findFileByPath(path) ?: LocalFileSystem.getInstance().findFileByPath(path)

    /** What a module (file or package directory) exposes as [name]: bindings, then submodules. */
    private fun moduleMember(module: PsiElement, name: String, place: PsiElement): List<PsiElement> {
        val file = module as? PyFile ?: (module as? PsiDirectory)?.findFile("__init__.py") as? PyFile
        val bound = file?.let { f -> PyScopes.bindings(f).filter { (it as? PyNamedDecl)?.name == name }.flatMap { follow(it) } }.orEmpty()
        if (bound.isNotEmpty()) return bound
        val dir = (module as? PsiDirectory) ?: module.takeIf { (it as? PyFile)?.name == "__init__.py" }?.let { (it as PyFile).containingDirectory }
        dir?.let { d ->
            d.findFile("$name.py")?.let { return listOf(it) }
            d.findSubdirectory(name)?.let { s -> return listOf(s.findFile("__init__.py") ?: s) }
        }
        // `from m import *` in the module itself
        return file?.let { f -> PyScopes.bindings(f).filter { it.node.elementType == E.STAR_IMPORT_ELEMENT }
            .flatMap { star -> star.parent?.let { fromModule(it, place) }?.let { moduleMember(it, name, place) }.orEmpty() } }.orEmpty()
    }

    /** An import binding navigates to what it imports. */
    private fun follow(e: PsiElement): List<PsiElement> {
        if (e !is PyImportElement) return listOf(e)
        val stmt = e.parent ?: return listOf(e)
        val path = e.importedPath?.text?.replace(SPACES, "") ?: return listOf(e)
        return if (stmt.node.elementType == E.FROM_IMPORT_STATEMENT) {
            fromModule(stmt, e)?.let { moduleMember(it, path, e) }.orEmpty().ifEmpty { listOf(e) }
        } else {
            // `import a.b` binds `a`; `import a.b as c` binds `a.b`
            val target = if (e.alias != null) path else path.substringBefore('.')
            listOfNotNull(findModule(e, target)).ifEmpty { listOf(e) }
        }
    }

    // ---------------------------------------------------------------- scopes (LEGB)

    fun lexical(name: String, place: PsiElement): List<PsiElement> {
        var cur: PsiElement? = place.parent
        var insideFunction = false // class bodies are not visible from their methods
        while (cur != null) {
            val scope: PsiElement? = when {
                cur is PyFunction -> cur
                cur is PyClass -> cur.takeIf { !insideFunction }
                cur is PsiFile -> cur
                cur.node?.elementType == E.LAMBDA_EXPRESSION -> cur
                else -> null
            }
            if (scope != null) {
                val found = bindingsNamed(scope, name, place)
                if (found.isNotEmpty()) return found
                if (scope is PsiFile) return starImports(scope, name, place)
            }
            if (cur is PyFunction) insideFunction = true
            cur = cur.parent
        }
        return emptyList()
    }

    private fun bindingsNamed(scope: PsiElement, name: String, place: PsiElement): List<PsiElement> {
        val all = if (scope.node?.elementType == E.LAMBDA_EXPRESSION) PsiTreeUtil.findChildrenOfType(scope, PyNamedDecl::class.java).filter { it.parent?.node?.elementType == E.PARAMETER_LIST }
        else PyScopes.bindings(scope)
        val named = all.filter { (it as? PyNamedDecl)?.name == name }
        if (named.isEmpty()) return emptyList()
        // nearest binding before the use, else the first one (assigned later in the same scope)
        val before = named.filter { it.textRange.startOffset <= place.textRange.startOffset }
        return listOf(before.lastOrNull() ?: named.first())
    }

    private fun starImports(file: PsiFile, name: String, place: PsiElement): List<PsiElement> =
        PyScopes.bindings(file).filter { it.node.elementType == E.STAR_IMPORT_ELEMENT }
            .flatMap { star -> star.parent?.let { fromModule(it, place) }?.let { moduleMember(it, name, place) }.orEmpty() }

    // ---------------------------------------------------------------- members

    /** `qualifier.name`: module attribute, class attribute, or instance attribute via the inferred type. */
    fun members(qualifier: PsiElement, name: String): List<PsiElement> {
        val targets = (qualifier as? PyReferenceExpr)?.let { resolve(it) }.orEmpty()
        targets.filter { it is PyFile || it is PsiDirectory }.flatMap { moduleMember(it, name, qualifier) }.takeIf { it.isNotEmpty() }?.let { return it }
        targets.filterIsInstance<PyClass>().flatMap { classMembers(it, name, HashSet()) }.takeIf { it.isNotEmpty() }?.let { return it }
        return when (val t = inferType(qualifier)) {
            is PyTy.Inst -> classMembers(t.cls, name, HashSet())
            else -> emptyList()
        }
    }

    /** Class body bindings, `self.name = …` in methods, then base classes (MRO approximated depth-first). */
    fun classMembers(cls: PyClass, name: String, seen: MutableSet<PyClass>): List<PsiElement> {
        if (!seen.add(cls)) return emptyList()
        val own = PyScopes.bindings(cls).filter { (it as? PyNamedDecl)?.name == name }.flatMap { follow(it) }
        if (own.isNotEmpty()) return own
        val instance = PsiTreeUtil.findChildrenOfType(cls.body, PyTarget::class.java)
            .filter { it.name == name && (it.qualifier as? PyReferenceExpr)?.referencedName == "self" }
        if (instance.isNotEmpty()) return listOf(instance.first())
        return cls.superClassExpressions.flatMap { base ->
            val ref = (base as? PyReferenceExpr) ?: base.children.firstOrNull { it is PyReferenceExpr } as? PyReferenceExpr
            ref?.let { resolve(it) }.orEmpty().filterIsInstance<PyClass>().flatMap { classMembers(it, name, seen) }
        }
    }

    // ---------------------------------------------------------------- types

    fun inferType(e: PsiElement?): PyTy {
        e ?: return PyTy.Unknown
        return GUARD.run(PyTy.Unknown) { LiteCache.cachedNonRecursive(e, TYPE, PyLibraryOracle, PyTy.Unknown) { infer(e) } }
    }

    private fun infer(e: PsiElement): PyTy = when (e.node.elementType) {
        E.REFERENCE_EXPRESSION -> valueType((e as PyReferenceExpr).let { resolve(it).firstOrNull() }, e)
        E.CALL_EXPRESSION -> when (val callee = (e.firstChild as? PyReferenceExpr)?.let { resolve(it).firstOrNull() }) {
            is PyClass -> PyTy.Inst(callee)
            is PyFunction -> annotationType(callee.returnAnnotation, callee)
            else -> PyTy.Unknown
        }
        E.PARENTHESIZED_EXPRESSION -> inferType(e.children.firstOrNull { it is PyElement })
        E.LIST_LITERAL_EXPRESSION, E.SET_LITERAL_EXPRESSION, E.TUPLE_EXPRESSION -> PyTy.Coll(inferType(e.children.firstOrNull { it is PyElement }))
        E.LIST_COMP_EXPRESSION -> PyTy.Coll(inferType(e.children.firstOrNull { it is PyElement }))
        E.SUBSCRIPTION_EXPRESSION -> (inferType(e.children.firstOrNull { it is PyElement }) as? PyTy.Coll)?.elem ?: PyTy.Unknown
        else -> PyTy.Unknown
    }

    private fun valueType(target: PsiElement?, place: PsiElement): PyTy = when (target) {
        is PyClass -> PyTy.Unknown // the class object itself: members are found through `classMembers`
        is PyTarget -> target.annotation?.let { annotationType(it, target) } ?: assignedType(target)
        is PyNamedDecl -> when {
            target.node.elementType != E.NAMED_PARAMETER -> PyTy.Unknown
            target.annotation != null -> annotationType(target.annotation, target)
            isSelf(target) -> PsiTreeUtil.getParentOfType(target, PyClass::class.java)?.let { PyTy.Inst(it) } ?: PyTy.Unknown
            else -> PyTy.Unknown
        }
        else -> PyTy.Unknown
    }

    /** First parameter of a method (not `@staticmethod`; `cls` of a `@classmethod` is treated alike). */
    private fun isSelf(param: PyNamedDecl): Boolean {
        val fn = PsiTreeUtil.getParentOfType(param, PyFunction::class.java) ?: return false
        return fn.containingClass != null && fn.parameters.firstOrNull() == param && "staticmethod" !in fn.decorators
    }

    /** `x = Foo()`, `for x in xs`, `with open() as f` (value side of the binding). */
    private fun assignedType(t: PyTarget): PyTy {
        val p = t.parent ?: return PyTy.Unknown
        return when (p.node.elementType) {
            E.ASSIGNMENT_STATEMENT -> inferType(p.children.lastOrNull { it is PyElement && it !is PyTarget })
            E.FOR_PART -> {
                // `for target in iterable:` — the expression right after `in`
                var n = p.node.findChildByType(T.IN_KEYWORD)?.treeNext
                while (n != null && n.psi !is PyElement) n = n.treeNext
                (inferType(n?.psi) as? PyTy.Coll)?.elem ?: PyTy.Unknown
            }
            E.WITH_ITEM -> inferType(p.children.firstOrNull { it is PyElement && it !is PyTarget })
            else -> PyTy.Unknown
        }
    }

    /** `Foo`, `"Foo"`, `Optional[Foo]`, `Foo | None`, `list[Foo]`, `Self`. */
    fun annotationType(a: PsiElement?, ctx: PsiElement): PyTy {
        a ?: return PyTy.Unknown
        return when (a.node.elementType) {
            E.REFERENCE_EXPRESSION -> when (val r = resolve(a as PyReferenceExpr).firstOrNull()) {
                is PyClass -> PyTy.Inst(r)
                else -> if (a.referencedName == "Self") PsiTreeUtil.getParentOfType(ctx, PyClass::class.java)?.let { PyTy.Inst(it) } ?: PyTy.Unknown else PyTy.Unknown
            }
            E.STRING_LITERAL_EXPRESSION -> {
                val name = a.text.trim('"', '\'')
                lexical(name, a).firstOrNull { it is PyClass }?.let { PyTy.Inst(it as PyClass) } ?: PyTy.Unknown
            }
            E.SUBSCRIPTION_EXPRESSION -> {
                val head = (a.firstChild as? PyReferenceExpr)?.referencedName
                val arg = a.children.drop(1).firstOrNull { it is PyElement }?.let { if (it.node.elementType == E.TUPLE_EXPRESSION) it.children.firstOrNull { c -> c is PyElement } else it }
                when (head) {
                    in TYPING_ONE -> annotationType(arg, ctx)
                    in TYPING_COLL -> PyTy.Coll(annotationType(arg, ctx))
                    "Union" -> annotationType(arg, ctx)
                    else -> annotationType(a.firstChild, ctx)
                }
            }
            E.BINARY_EXPRESSION -> a.children.filter { it is PyElement && it.text != "None" }.map { annotationType(it, ctx) }.firstOrNull { it != PyTy.Unknown } ?: PyTy.Unknown
            else -> PyTy.Unknown
        }
    }
}
