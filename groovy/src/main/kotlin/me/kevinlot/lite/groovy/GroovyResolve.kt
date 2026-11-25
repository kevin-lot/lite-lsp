package me.kevinlot.lite.groovy

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import me.kevinlot.lite.core.LiteKeysIndex
import me.kevinlot.lite.core.ProjectLayout
import me.kevinlot.lite.core.firstFound
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.groovy.GroovyElementTypes as E

/** Groovy's project boundaries ([ProjectLayout]): Gradle settings (multi-module), else a build script or a Jenkinsfile. */
object GroovyProject {
    val LAYOUT = ProjectLayout(
        rootManifests = listOf("settings.gradle"),
        moduleManifests = listOf("build.gradle", "Jenkinsfile"),
        outputDirs = setOf(".gradle", "build", "out"),
    )
    private val SCOPE = Key.create<CachedValue<GlobalSearchScope>>("lite.groovy.scope")

    fun scopeOf(e: PsiElement): GlobalSearchScope = LAYOUT.scopeOf(e, SCOPE)

    fun rootOf(file: PsiFile): VirtualFile? {
        val vf = file.originalFile.virtualFile ?: return null
        return LAYOUT.rootOf(file.project, vf)?.let(vf.fileSystem::findFileByPath)
    }
}

/** class name -> files declaring a class of that name (nested ones included). */
class GroovyClassIndex : LiteKeysIndex<GroovyFile>(GroovyFileType, GroovyFile::class.java) {
    override fun getName(): ID<String, Void> = KEY
    override fun keys(file: GroovyFile): Collection<String> = PsiTreeUtil.findChildrenOfType(file, GroovyClass::class.java).mapNotNull { it.name }.toSet()

    companion object {
        val KEY = ID.create<String, Void>("lite.groovy.class")
    }
}

object GroovyFiles {
    private val CLASSES = Key.create<CachedValue<List<GroovyClass>>>("lite.groovy.classes")

    fun packageName(file: PsiFile): String =
        file.children.firstOrNull { it.isA(E.PACKAGE) }?.children?.firstNotNullOfOrNull { it as? GroovyQualifiedName }?.text.orEmpty()
            .filterNot(Char::isWhitespace)

    fun imports(file: PsiFile): List<GroovyImport> = file.children.filterIsInstance<GroovyImport>()

    /** Every class of the file, outer classes first. */
    fun classes(file: PsiFile): List<GroovyClass> = CachedValuesManager.getCachedValue(file, CLASSES) {
        CachedValueProvider.Result.create(PsiTreeUtil.findChildrenOfType(file, GroovyClass::class.java).toList(), file)
    }

    fun methods(file: PsiFile): List<GroovyMethod> = file.children.filterIsInstance<GroovyMethod>()

    /** Variables declared at the top of the script. */
    fun variables(file: PsiFile): List<GroovyVariable> = file.children.flatMap(GroovyResolve::variablesOf)
}

/*
 * Groovy resolution, lexical:
 * - names: locals declared before the use, parameters, closure parameters and `it`, loop and catch variables; then
 *   the members of the enclosing classes (inherited ones included), the methods and variables of the script, its
 *   bindings (`x = …` without a declaration), classes (same file, imports, same package, star imports), static
 *   imports, and for a call the Jenkins step `vars/<name>.groovy`;
 * - members `a.b`: only where the type of `a` is written: a declared type, `new C()`, `this`, `super`, a class
 *   (static access), the declared return type of a call.
 */
object GroovyResolve {
    private val BINDINGS = Key.create<CachedValue<Map<String, GroovyNameRef>>>("lite.groovy.bindings")
    private val SCOPES = setOf(E.BLOCK, E.INJECTION, E.SWITCH, E.VAR_DECL)
    private val HEADERS = setOf(E.CATCH, E.FOR, E.TRY)

    // ------------------------------------------------------------ names

    fun resolveName(ref: GroovyNameRef): List<PsiElement> {
        val name = ref.name
        val file = ref.containingFile ?: return emptyList()
        if (ref.isThis) return listOfNotNull(classOf(ref))
        if (ref.isSuper) return listOfNotNull(classOf(ref)?.let(::superClasses)?.firstOrNull())
        val arity = (ref.parent as? GroovyCall)?.takeIf { ref.isCallee }?.argumentCount
        return firstFound(
            { listOfNotNull(local(ref, name)) },
            { listOfNotNull(implicitIt(ref).takeIf { name == "it" }) },
            { enclosingClasses(ref).firstNotNullOfOrNull { members(it, name, arity).ifEmpty { null } }.orEmpty() },
            { scriptMembers(file, name, arity) },
            { resolveClass(ref, name) },
            { staticImports(ref, name, arity) },
            { arity?.let { GroovySteps.resolve(ref, name, it) }.orEmpty() },
        )
    }

    /** The variable [name] visible at [place]. */
    fun local(place: PsiElement, name: String): GroovyVariable? =
        generateSequence(place) { it.parent }.takeWhile { it !is PsiFile }
            .firstNotNullOfOrNull { child -> child.parent?.let { declared(it, child, name) } }

    private fun declared(scope: PsiElement, child: PsiElement, name: String): GroovyVariable? = when {
        scope is GroovyClosure -> scope.params.lastOrNull { it.name == name } ?: before(child, name)
        scope is GroovyMethod -> scope.params.lastOrNull { it.name == name }
        scope is PsiFile || scope.node.elementType in SCOPES -> before(child, name)
        scope.node.elementType in HEADERS -> scope.children.flatMap(::variablesOf).lastOrNull { it.name == name }
        else -> null
    }

    private fun before(statement: PsiElement, name: String): GroovyVariable? =
        generateSequence(statement.prevSibling) { it.prevSibling }.flatMap(::variablesOf).firstOrNull { it.name == name }

    /** The variables a statement declares in its scope. */
    fun variablesOf(statement: PsiElement): List<GroovyVariable> = when {
        statement is GroovyVariable -> listOf(statement)
        statement.isA(E.VAR_DECL) -> statement.children.filterIsInstance<GroovyVariable>()
        statement.isA(E.LABELED) -> statement.children.flatMap(::variablesOf)
        else -> emptyList()
    }

    private fun implicitIt(place: PsiElement): GroovyClosure? =
        generateSequence(closureOf(place), ::closureOf).firstOrNull { it.hasImplicitIt }

    private fun closureOf(e: PsiElement): GroovyClosure? = PsiTreeUtil.getParentOfType(e, GroovyClosure::class.java)
    private fun classOf(e: PsiElement): GroovyClass? = PsiTreeUtil.getParentOfType(e, GroovyClass::class.java)
    private fun enclosingClasses(e: PsiElement): Sequence<GroovyClass> = generateSequence(classOf(e), ::classOf)

    /** Methods of the script, its top-level variables wherever they are declared, then its bindings. */
    private fun scriptMembers(file: PsiFile, name: String, arity: Int?): List<PsiElement> {
        val methods = bestArity(GroovyFiles.methods(file).filter { it.name == name }, arity)
        val variables = GroovyFiles.variables(file).filter { it.name == name }.take(1)
        val found = when (arity) {
            null -> variables.ifEmpty { methods }
            else -> methods.ifEmpty { variables }
        }
        return found.ifEmpty { listOfNotNull(bindings(file)[name]) }
    }

    /** First `x = …` of every name the script assigns without declaring it, in source order. */
    fun bindings(file: PsiFile): Map<String, GroovyNameRef> = CachedValuesManager.getCachedValue(file, BINDINGS) {
        val declared = (GroovyFiles.variables(file).mapNotNull { it.name } + GroovyFiles.methods(file).mapNotNull { it.name }).toSet()
        val assigned = PsiTreeUtil.findChildrenOfType(file, GroovyNameRef::class.java)
            .filter { it.isAssigned && it.name !in declared && classOf(it) == null && local(it, it.name) == null }
        CachedValueProvider.Result.create(assigned.groupBy { it.name }.mapValues { it.value.first() }, file)
    }

    /** Overloads are told apart by their number of arguments only, and never all dropped. */
    private fun bestArity(methods: List<GroovyMethod>, arity: Int?): List<GroovyMethod> =
        methods.filter { it.params.size == arity }.ifEmpty { methods }

    // ------------------------------------------------------------ classes

    fun resolveType(type: GroovyType): List<PsiElement> {
        val name = type.qualifiedName ?: return emptyList()
        if ('.' in name) return qualified(type, name).ifEmpty { nested(type, name) }
        return resolveClass(type, name)
    }

    /** `Outer.Inner` where `Outer` is visible by its simple name. */
    private fun nested(place: PsiElement, name: String): List<PsiElement> {
        val segments = name.split('.')
        val outer = resolveClass(place, segments.first()).filterIsInstance<GroovyClass>()
        return segments.drop(1).fold(outer) { owners, segment -> owners.flatMap { it.innerClasses }.filter { it.name == segment } }
    }

    fun resolveClass(place: PsiElement, name: String): List<PsiElement> {
        val file = place.containingFile ?: return emptyList()
        val packageName = GroovyFiles.packageName(file)
        val imports = GroovyFiles.imports(file).filter { !it.isStatic }
        return firstFound(
            { enclosingClasses(place).flatMap { sequenceOf(it) + it.innerClasses }.filter { it.name == name }.take(1).toList() },
            { GroovyFiles.classes(file).filter { it.name == name }.take(1) },
            { imports.filter { it.importedName == name }.flatMap { qualified(place, it.qualifiedName) } },
            { named(place, name).filter { it.outerClass == null && GroovyFiles.packageName(it.containingFile) == packageName } },
            { imports.filter { it.isStar }.flatMap { qualified(place, "${it.qualifiedName}.$name") } },
        )
    }

    /** Classes called [name] in the project of [place], in path order. */
    private fun named(place: PsiElement, name: String): List<GroovyClass> {
        val manager = PsiManager.getInstance(place.project)
        return FileBasedIndex.getInstance().getContainingFiles(GroovyClassIndex.KEY, name, GroovyProject.scopeOf(place)).sortedBy { it.path }
            .mapNotNull(manager::findFile).flatMap(GroovyFiles::classes).filter { it.name == name }
    }

    private fun qualified(place: PsiElement, qualifiedName: String): List<GroovyClass> =
        named(place, qualifiedName.substringAfterLast('.')).filter { it.qualifiedName == qualifiedName }

    fun resolveImport(import: GroovyImport): List<PsiElement> {
        val name = import.qualifiedName
        if (!import.isStatic || import.isStar) return qualified(import, name)
        return qualified(import, name.substringBeforeLast('.')).flatMap { members(it, name.substringAfterLast('.'), null) }
    }

    private fun staticImports(place: PsiElement, name: String, arity: Int?): List<PsiElement> {
        val imports = GroovyFiles.imports(place.containingFile).filter { it.isStatic }
        val explicit = imports.filter { it.importedName == name }.flatMap(::resolveImport)
        return explicit.ifEmpty { imports.filter { it.isStar }.flatMap(::resolveImport).filterIsInstance<GroovyClass>().flatMap { members(it, name, arity) } }
    }

    // ------------------------------------------------------------ members

    fun resolveMember(member: GroovyMember): List<PsiElement> {
        val name = member.name ?: return emptyList()
        val qualifier = member.qualifier
        val arity = (member.parent as? GroovyCall)?.takeIf { member.isCallee }?.argumentCount
        if (qualifier is GroovyNameRef && qualifier.isThis && classOf(member) == null) return scriptMembers(member.containingFile, name, arity)
        typeOf(qualifier)?.let { return members(it, name, arity) }
        return qualifiedClass(member)
    }

    /** `a.b.C` written in full in an expression. */
    private fun qualifiedClass(member: GroovyMember): List<PsiElement> {
        val text = member.text.filterNot(Char::isWhitespace)
        if (!text.all { it.isLetterOrDigit() || it == '_' || it == '.' }) return emptyList()
        return qualified(member, text)
    }

    /** Members [name] of [cls]: its own, else those of the first super type that has some. A call prefers methods, a read prefers fields. */
    fun members(cls: GroovyClass, name: String, arity: Int?, seen: Set<GroovyClass> = emptySet()): List<PsiElement> {
        if (cls in seen) return emptyList()
        val methods = bestArity(cls.methods.filter { it.name == name }, arity)
        val fields = cls.fields.filter { it.name == name }
        val accessors = cls.methods.filter { it.name in accessorNames(name) }
        val inner = cls.innerClasses.filter { it.name == name }
        val own: List<PsiElement> = when (arity) {
            null -> fields.ifEmpty { accessors }.ifEmpty { methods }.ifEmpty { inner }
            else -> methods.ifEmpty { fields }
        }
        return own.ifEmpty { superClasses(cls).firstNotNullOfOrNull { members(it, name, arity, seen + cls).ifEmpty { null } }.orEmpty() }
    }

    /** `x.name` reads `getName()` / `isName()` and writes `setName()`. */
    private fun accessorNames(property: String): List<String> = listOf("get", "is", "set").map { it + property.replaceFirstChar(Char::uppercaseChar) }

    private fun superClasses(cls: GroovyClass): List<GroovyClass> = cls.superTypes.flatMap(::resolveType).filterIsInstance<GroovyClass>()

    /** The class of an expression whose type is written down; null for anything that would need inference. */
    fun typeOf(e: PsiElement?): GroovyClass? = when {
        e is GroovyNameRef && e.isThis -> classOf(e)
        e is GroovyNameRef -> resolveName(e).firstNotNullOfOrNull(::typeOfTarget)
        e is GroovyMember -> resolveMember(e).firstNotNullOfOrNull(::typeOfTarget)
        e is GroovyCall -> calleeTargets(e.callee).firstNotNullOfOrNull { method -> (method as? GroovyMethod)?.returnType?.let(::classOfType) }
        e.isA(E.NEW) -> e?.children?.firstNotNullOfOrNull { it as? GroovyType }?.let(::classOfType)
        e.isA(E.PAREN) -> typeOf(e?.children?.firstOrNull())
        else -> null
    }

    private fun calleeTargets(callee: PsiElement?): List<PsiElement> = when (callee) {
        is GroovyNameRef -> resolveName(callee)
        is GroovyMember -> resolveMember(callee)
        else -> emptyList()
    }

    private fun classOfType(type: GroovyType): GroovyClass? = resolveType(type).firstNotNullOfOrNull { it as? GroovyClass }

    private fun typeOfTarget(target: PsiElement): GroovyClass? = when (target) {
        is GroovyClass -> target
        is GroovyEnumConstant -> classOf(target)
        is GroovyMethod -> target.returnType?.let(::classOfType)
        is GroovyVariable -> target.declaredType?.let(::classOfType) ?: target.initializer?.takeIf { it.isA(E.NEW) }?.let(::typeOf)
        else -> null
    }
}

/** Jenkins shared libraries: the call `deployApp(…)` runs the `call` method of `vars/deployApp.groovy`. */
object GroovySteps {
    const val EXTENSION = ".groovy"
    private const val FOLDER = "vars"
    private const val ENTRY_POINT = "call"

    fun resolve(place: PsiElement, name: String, arity: Int): List<PsiElement> {
        val manager = PsiManager.getInstance(place.project)
        return FilenameIndex.getVirtualFilesByName(name + EXTENSION, GroovyProject.scopeOf(place)).filter { it.parent?.name == FOLDER }
            .sortedBy { it.path }.mapNotNull(manager::findFile).flatMap { entryPoints(it, arity).ifEmpty { listOf(it) } }
    }

    private fun entryPoints(file: PsiFile, arity: Int): List<PsiElement> {
        val calls = GroovyFiles.methods(file).filter { it.name == ENTRY_POINT }
        return calls.filter { it.params.size == arity }.ifEmpty { calls.take(1) }
    }

    /** Whether [target] is what a step name refers to: a file of `vars/` or its `call` method. */
    fun isStep(target: PsiElement): Boolean {
        val inFolder = target.containingFile?.originalFile?.virtualFile?.parent?.name == FOLDER
        return inFolder && (target is PsiFile || (target is GroovyMethod && target.name == ENTRY_POINT && target.parent is PsiFile))
    }
}

/** Usages of a step's `call` method are the calls of the step, which are spelled like its file. */
class GroovyStepUsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val method = parameters.elementToSearch as? GroovyMethod ?: return
        if (!GroovySteps.isStep(method)) return
        val step = method.containingFile.virtualFile?.nameWithoutExtension ?: return
        parameters.optimizer.searchWord(step, parameters.effectiveSearchScope, UsageSearchContext.IN_CODE, true, method)
    }
}

/** `load 'x.groovy'` and `apply from: 'x.gradle'`: a path from the file's folder, else from the project root. */
object GroovyPaths {
    fun referenceOf(literal: GroovyLiteral): GroovyPathReference? {
        if (literal.stringValue.isNullOrBlank()) return null
        if (!isArgumentOf(literal, "load") && !isApplyFrom(literal)) return null
        return GroovyPathReference(literal)
    }

    private fun isArgumentOf(arg: PsiElement, function: String): Boolean {
        val call = arg.parent?.takeIf { it.isA(E.ARGS) }?.parent as? GroovyCall ?: return false
        return (call.callee as? GroovyNameRef)?.name == function
    }

    private fun isApplyFrom(literal: GroovyLiteral): Boolean {
        val entry = literal.parent?.takeIf { it.isA(E.ENTRY) } ?: return false
        return entryLabel(entry) == "from" && isArgumentOf(entry, "apply")
    }

    fun target(literal: GroovyLiteral): PsiFile? {
        val path = literal.stringValue ?: return null
        val file = literal.containingFile ?: return null
        val folders = listOfNotNull(file.originalFile.virtualFile?.parent, GroovyProject.rootOf(file))
        val found = folders.firstNotNullOfOrNull { folder -> folder.findFileByRelativePath(path)?.takeIf { !it.isDirectory } } ?: return null
        return PsiManager.getInstance(literal.project).findFile(found)
    }
}
