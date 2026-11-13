package me.kevinlot.lite.shell

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.shell.ShElementTypes as E
import me.kevinlot.lite.shell.ShTokens as T

open class ShElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** `name() { … }` / `function name { … }`. */
class ShFunction(node: ASTNode) : ShElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.WORD)?.psi
    override fun getName(): String? = nameIdentifier?.text
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(ShPsiFactory(project).word(name)) ?: throw IncorrectOperationException("No name")
        return this
    }
}

/** A variable set here: `NAME=…`, `local NAME`, `read NAME`, `for NAME in`. */
class ShVarDecl(node: ASTNode) : ShElement(node), PsiNameIdentifierOwner {
    private val token: PsiElement? get() = node.findChildByType(NAME_TOKENS)?.psi
    override fun getNameIdentifier(): PsiElement? = token
    override fun getName(): String? = token?.text?.let(::baseName)
    override fun getTextOffset(): Int = token?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        val t = token ?: throw IncorrectOperationException("No name")
        val old = t.text
        t.replace(if (t.node.elementType == T.ASSIGN_NAME) ShPsiFactory(project).assignName(name + old.substring(baseName(old).length)) else ShPsiFactory(project).word(name))
        return this
    }

    /** `local`/`declare`/`typeset` inside a function (without `-g`): only visible there. */
    val isLocal: Boolean
        get() {
            val command = parent as? ShElement ?: return false
            if (command.node.elementType != E.COMMAND) return false
            val name = command.node.findChildByType(E.COMMAND_NAME)?.text ?: return false
            if (name !in LOCAL_BUILTINS) return false
            if (command.node.getChildren(null).any { it.elementType == E.WORD && it.text == "-g" }) return false
            return PsiTreeUtil.getParentOfType(this, ShFunction::class.java) != null
        }

    override fun getUseScope(): SearchScope =
        if (isLocal) LocalSearchScope(PsiTreeUtil.getParentOfType(this, ShFunction::class.java)!!) else super.getUseScope()

    /** A later assignment (`COUNT=2`) refers to the declaring one (`COUNT=1`, `local COUNT`). */
    override fun getReference(): PsiReference? {
        val t = token ?: return null
        val n = name ?: return null
        if (ShResolver.variable(n, this).let { it == null || it == this }) return null
        return ShReassignReference(this, TextRange.from(t.startOffsetInParent, n.length))
    }

    companion object {
        val NAME_TOKENS = com.intellij.psi.tree.TokenSet.create(T.ASSIGN_NAME, T.WORD)
        val LOCAL_BUILTINS = setOf("local", "declare", "typeset", "integer", "float")
        fun baseName(text: String) = text.takeWhile { it.isLetterOrDigit() || it == '_' }
    }
}

/** `$NAME`, `${NAME…}`, `NAME` in `$(( … ))`. */
class ShVarRef(node: ASTNode) : ShElement(node) {
    val nameToken: PsiElement? get() = node.findChildByType(T.VAR_NAME)?.psi ?: node.findChildByType(T.VAR)?.psi
    val referencedName: String? get() = nameToken?.text?.removePrefix("$")
    override fun getReference(): PsiReference? {
        val n = referencedName ?: return null
        if (n.isEmpty() || !(n[0].isLetter() || n[0] == '_')) return null // $1, $@, $?
        return ShVarReference(this)
    }
}

/** First word of a command: a function of this script (or of a sourced one), else an external command. */
class ShCommandName(node: ASTNode) : ShElement(node) {
    val nameToken: PsiElement? get() = (firstChild as? ShElement)?.takeIf { it.node.getChildren(null).size == 1 }?.firstChild?.takeIf { it.node.elementType == T.WORD }
    override fun getReference(): PsiReference? = nameToken?.let { ShFunctionReference(this) }
}

/** `source lib.sh`, `. "$(dirname "$0")/lib.sh"`. */
class ShSourcePath(node: ASTNode) : ShElement(node) {
    override fun getReference(): PsiReference = ShSourceReference(this)

    /** The literal tail of the path: what follows the last expansion (`$DIR/lib.sh` → `lib.sh` next to the script). */
    val relativePath: String?
        get() {
            val word = firstChild ?: return null
            val sb = StringBuilder()
            var dynamic = false
            PsiTreeUtil.processElements(word) { e ->
                when (e.node.elementType) {
                    T.WORD, T.STRING_TEXT, T.NUMBER -> if (e.firstChild == null) sb.append(e.text)
                    T.STRING_SINGLE -> sb.append(e.text.trim('\''))
                    E.VAR_REF, E.PARAM_EXPANSION, E.SUBSTITUTION -> { dynamic = true; sb.clear() }
                }
                true
            }
            val path = sb.toString()
            return when {
                path.isEmpty() -> null
                dynamic -> path.removePrefix("/")
                else -> path
            }
        }
}

object ShPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        E.FUNCTION_DEF -> ShFunction(node)
        E.VAR_DECL -> ShVarDecl(node)
        E.VAR_REF, E.PARAM_EXPANSION -> ShVarRef(node)
        E.COMMAND_NAME -> ShCommandName(node)
        E.SOURCE_PATH -> ShSourcePath(node)
        else -> ShElement(node)
    }
}

class ShPsiFactory(private val project: Project) {
    private fun file(text: String): PsiFile = PsiFileFactory.getInstance(project).createFileFromText("dummy.sh", ShFileType, text)
    private fun token(text: String, type: com.intellij.psi.tree.IElementType): PsiElement =
        PsiTreeUtil.collectElements(file(text)) { it.node.elementType == type }.firstOrNull() ?: throw IncorrectOperationException(text)
    fun word(name: String) = token("$name x", T.WORD)
    fun assignName(text: String) = token("${text}x", T.ASSIGN_NAME)
    fun variable(name: String) = token("x \$$name", T.VAR)
    fun variableName(name: String) = token("x \${$name}", T.VAR_NAME)
}

// ---------------------------------------------------------------- references

class ShVarReference(element: ShVarRef) :
    PsiPolyVariantReferenceBase<ShVarRef>(element, element.nameToken!!.let { t ->
        val dollar = if (t.text.startsWith("$")) 1 else 0
        TextRange.from(t.startOffsetInParent + dollar, t.textLength - dollar)
    }) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(listOfNotNull(ShResolver.variable(element.referencedName ?: "", element)))
    override fun handleElementRename(newElementName: String): PsiElement {
        val t = element.nameToken ?: return element
        t.replace(if (t.node.elementType == T.VAR) ShPsiFactory(element.project).variable(newElementName) else ShPsiFactory(element.project).variableName(newElementName))
        return element
    }
}

class ShFunctionReference(element: ShCommandName) :
    PsiPolyVariantReferenceBase<ShCommandName>(element, TextRange.from(element.nameToken!!.startOffsetInParent + element.firstChild.startOffsetInParent, element.nameToken!!.textLength)) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(listOfNotNull(element.nameToken?.text?.let { ShResolver.function(it, element.containingFile) }))
    override fun handleElementRename(newElementName: String): PsiElement {
        element.nameToken?.replace(ShPsiFactory(element.project).word(newElementName))
        return element
    }
}

class ShReassignReference(element: ShVarDecl, range: TextRange) : PsiPolyVariantReferenceBase<ShVarDecl>(element, range) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(listOfNotNull(element.name?.let { ShResolver.variable(it, element) }?.takeIf { it != element }))
    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}

class ShSourceReference(element: ShSourcePath) : PsiPolyVariantReferenceBase<ShSourcePath>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(listOfNotNull(ShResolver.sourced(element)))
    override fun handleElementRename(newElementName: String): PsiElement = element
}

// ---------------------------------------------------------------- resolution

/**
 * Functions: this script, then the scripts it sources (transitively). Variables: a `local` of the enclosing
 * function, else the first assignment of the script, else of a sourced script. Nothing else is searched:
 * environment variables and external commands stay unresolved.
 */
object ShResolver {
    private val GUARD = RecursionGuard(16)
    private val FUNCTIONS = Key.create<CachedValue<Map<String, ShFunction>>>("lite.sh.functions")
    private val GLOBALS = Key.create<CachedValue<Map<String, ShVarDecl>>>("lite.sh.globals")
    private val SOURCED = Key.create<CachedValue<List<PsiFile>>>("lite.sh.sourced")

    private fun <T> cached(file: PsiFile, key: Key<CachedValue<T>>, compute: () -> T): T =
        CachedValuesManager.getCachedValue(file, key) { CachedValueProvider.Result.create(compute(), PsiModificationTracker.MODIFICATION_COUNT) }

    fun function(name: String, file: PsiFile): ShFunction? = GUARD.run(null) {
        functions(file)[name] ?: sourcedFiles(file).firstNotNullOfOrNull { function(name, it) }
    }

    fun variable(name: String, place: PsiElement): ShVarDecl? = GUARD.run(null) {
        var fn = PsiTreeUtil.getParentOfType(place, ShFunction::class.java)
        while (fn != null) {
            PsiTreeUtil.findChildrenOfType(fn, ShVarDecl::class.java).firstOrNull { it.name == name && it.isLocal && PsiTreeUtil.getParentOfType(it, ShFunction::class.java) == fn }
                ?.let { return@run it }
            fn = PsiTreeUtil.getParentOfType(fn, ShFunction::class.java)
        }
        val file = place.containingFile ?: return@run null
        globals(file)[name] ?: sourcedFiles(file).firstNotNullOfOrNull { globals(it)[name] }
    }

    /** Functions of a script by name (the first definition wins). */
    private fun functions(file: PsiFile): Map<String, ShFunction> = cached(file, FUNCTIONS) {
        val out = LinkedHashMap<String, ShFunction>()
        PsiTreeUtil.findChildrenOfType(file, ShFunction::class.java).forEach { f -> f.name?.let { out.putIfAbsent(it, f) } }
        out
    }

    private fun globals(file: PsiFile): Map<String, ShVarDecl> = cached(file, GLOBALS) {
        val out = LinkedHashMap<String, ShVarDecl>()
        PsiTreeUtil.findChildrenOfType(file, ShVarDecl::class.java).forEach { d -> if (!d.isLocal) d.name?.let { out.putIfAbsent(it, d) } }
        out
    }

    private fun sourcedFiles(file: PsiFile): List<PsiFile> = cached(file, SOURCED) {
        PsiTreeUtil.findChildrenOfType(file, ShSourcePath::class.java).mapNotNull { sourced(it) }.distinct().filter { it != file }
    }

    /** The script a `source` designates: relative to the script's folder, or absolute, or under `~`. */
    fun sourced(path: ShSourcePath): PsiFile? {
        val rel = path.relativePath ?: return null
        val vf = path.containingFile.originalFile.virtualFile ?: return null
        val target = when {
            rel.startsWith("~/") -> vf.fileSystem.findFileByPath(System.getProperty("user.home") + rel.substring(1))
            rel.startsWith("/") -> vf.fileSystem.findFileByPath(rel)
            else -> vf.parent?.findFileByRelativePath(rel)
        } ?: return null
        return if (target.isDirectory) null else PsiManager.getInstance(path.project).findFile(target)
    }
}
