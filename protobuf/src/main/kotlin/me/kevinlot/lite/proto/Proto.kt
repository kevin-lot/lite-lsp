package me.kevinlot.lite.proto

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.core.RecursionGuard
import me.kevinlot.lite.proto.lexer._ProtoLexer
import me.kevinlot.lite.proto.parser.PbParser
import me.kevinlot.lite.proto.parser.PbTypes as E
import me.kevinlot.lite.proto.upstream.ProtoTokenTypes as T
import javax.swing.Icon

object PbLanguage : Language("LiteProto") {
    private fun readResolve(): Any = PbLanguage
    override fun getDisplayName() = "Protocol Buffers"
}

object PbFileType : LanguageFileType(PbLanguage) {
    override fun getName() = "Protocol Buffers (Lite)"
    override fun getDisplayName() = "Protocol Buffers"
    override fun getDescription() = "Protocol Buffers file"
    override fun getDefaultExtension() = "proto"
    override fun getIcon(): Icon? = null
}

class PbFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, PbLanguage) {
    override fun getFileType() = PbFileType
    override fun toString() = "Protocol Buffers file"
}

class PbParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = FlexAdapter(_ProtoLexer(null))
    override fun createParser(project: Project?): PsiParser = PbParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.create(T.STRING_LITERAL)
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        in DEFINITIONS -> PbDefinition(node)
        E.SYMBOL_PATH -> PbSymbolPath(node)
        E.IMPORT_NAME -> PbImportName(node)
        else -> ASTWrapperPsiElement(node)
    }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = PbFile(viewProvider)

    companion object {
        val FILE = IFileElementType("LITE_PROTO_FILE", PbLanguage)
        val COMMENTS: TokenSet = TokenSet.create(T.LINE_COMMENT, T.BLOCK_COMMENT)
        val DEFINITIONS = TokenSet.create(
            E.MESSAGE_DEFINITION, E.ENUM_DEFINITION, E.SERVICE_DEFINITION, E.GROUP_DEFINITION, E.ONEOF_DEFINITION,
            E.SIMPLE_FIELD, E.MAP_FIELD, E.ENUM_VALUE, E.SERVICE_METHOD, E.SERVICE_STREAM,
        )
    }
}

// ---------------------------------------------------------------- PSI

/** message, enum, service, group, oneof, field, enum value, rpc: named by their identifier. */
class PbDefinition(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.IDENTIFIER_LITERAL)?.psi
    override fun getName(): String? = nameIdentifier?.text
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement {
        (nameIdentifier?.node as? LeafElement)?.replaceWithText(name) ?: throw IncorrectOperationException("No name")
        return this
    }
    /** Full name: `package.Outer.Inner` (enum values are siblings of their enum, as in protoc). */
    val fullName: String? get() = PbResolver.fullNameOf(this)
}

/**
 * `a.b.C`: SymbolPath(SymbolPath(SymbolPath(a).b).C); each level refers to the symbol its path names
 * (a package, a message…), the outermost one to what its context expects (a type, an extension field).
 */
class PbSymbolPath(node: ASTNode) : ASTWrapperPsiElement(node) {
    val nameElement: PsiElement? get() = node.getChildren(TokenSet.create(T.IDENTIFIER_LITERAL)).lastOrNull()?.psi
    val segments: List<String> get() = text.replace(Regex("\\s"), "").split('.').filter { it.isNotEmpty() }
    override fun getReference(): PsiReference? = nameElement?.let { PbSymbolReference(this) }
}

/** `import "a/b.proto"`: the file. */
class PbImportName(node: ASTNode) : ASTWrapperPsiElement(node) {
    val path: String get() = text.trim().trim('"', '\'')
    override fun getReference(): PsiReference = PbImportReference(this)
}

class PbSymbolReference(element: PbSymbolPath) :
    PsiPolyVariantReferenceBase<PbSymbolPath>(element, element.nameElement!!.let { TextRange.from(it.startOffsetInParent, it.textLength) }) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = PsiElementResolveResult.createResults(PbResolver.resolve(element))
    override fun handleElementRename(newElementName: String): PsiElement {
        (element.nameElement?.node as? LeafElement)?.replaceWithText(newElementName)
        return element
    }
}

class PbImportReference(element: PbImportName) : PsiPolyVariantReferenceBase<PbImportName>(element, TextRange(1, maxOf(1, element.textLength - 1)), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(listOfNotNull(PbResolver.importedFile(element.containingFile, element.path)))
    override fun handleElementRename(newElementName: String): PsiElement = element
}

// ---------------------------------------------------------------- resolution

enum class Kind { PACKAGE, TYPE, SERVICE, FIELD, ENUM_VALUE, METHOD, ONEOF }

data class Sym(val fullName: String, val element: PsiElement, val kind: Kind)

/**
 * protoc's rules: names are package-qualified; a relative name is searched from the innermost scope
 * (`pkg.Outer.Inner`) outwards, its first component deciding the scope; `.a.b.C` is absolute. Visible
 * symbols are the file's and those of the files it imports (transitively).
 */
object PbResolver {
    private val GUARD = RecursionGuard(16)
    private val OWN = Key.create<CachedValue<List<Sym>>>("lite.proto.own")
    private val TABLE = Key.create<CachedValue<Map<String, List<Sym>>>>("lite.proto.table")
    private val TYPES = setOf(Kind.TYPE)
    private val AGGREGATES = setOf(Kind.PACKAGE, Kind.TYPE, Kind.SERVICE)
    val SCALARS = setOf("double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes")

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a.$b"

    private fun packageOf(file: PsiFile): String =
        file.node.findChildByType(E.PACKAGE_STATEMENT)?.findChildByType(E.PACKAGE_NAME)?.text?.replace(Regex("\\s"), "").orEmpty()

    /** Symbols declared by [file] itself. */
    fun ownSymbols(file: PsiFile): List<Sym> = CachedValuesManager.getCachedValue(file, OWN) {
        val out = mutableListOf<Sym>()
        val pkg = packageOf(file)
        file.node.findChildByType(E.PACKAGE_STATEMENT)?.findChildByType(E.PACKAGE_NAME)?.let { name ->
            // a.b.c: packages `a`, `a.b`, `a.b.c`, each at its own PackageName level
            var n: ASTNode? = name
            while (n != null && n.elementType == E.PACKAGE_NAME) {
                out += Sym(n.text.replace(Regex("\\s"), ""), com.intellij.psi.util.PsiTreeUtil.getDeepestLast(n.psi), Kind.PACKAGE) // its last segment
                n = n.firstChildNode
            }
        }
        fun walk(node: ASTNode, scope: String) {
            for (c in node.getChildren(null)) {
                val def = c.psi as? PbDefinition
                val name = def?.name
                when (c.elementType) {
                    E.MESSAGE_DEFINITION, E.GROUP_DEFINITION -> if (name != null) {
                        val full = join(scope, name)
                        out += Sym(full, def, Kind.TYPE)
                        if (c.elementType == E.GROUP_DEFINITION) out += Sym(join(scope, name.lowercase()), def, Kind.FIELD)
                        c.findChildByType(E.MESSAGE_BODY)?.let { walk(it, full) }
                    }
                    E.ENUM_DEFINITION -> if (name != null) {
                        out += Sym(join(scope, name), def, Kind.TYPE)
                        c.findChildByType(E.ENUM_BODY)?.getChildren(TokenSet.create(E.ENUM_VALUE))?.forEach { v ->
                            (v.psi as PbDefinition).name?.let { out += Sym(join(scope, it), v.psi, Kind.ENUM_VALUE) }
                        }
                    }
                    E.SERVICE_DEFINITION -> if (name != null) {
                        val full = join(scope, name)
                        out += Sym(full, def, Kind.SERVICE)
                        c.findChildByType(E.SERVICE_BODY)?.getChildren(TokenSet.create(E.SERVICE_METHOD, E.SERVICE_STREAM))?.forEach { m ->
                            (m.psi as PbDefinition).name?.let { out += Sym(join(full, it), m.psi, Kind.METHOD) }
                        }
                    }
                    E.SIMPLE_FIELD, E.MAP_FIELD -> if (name != null) out += Sym(join(scope, name), def, Kind.FIELD)
                    E.ONEOF_DEFINITION -> {
                        if (name != null) out += Sym(join(scope, name), def, Kind.ONEOF)
                        c.findChildByType(E.ONEOF_BODY)?.let { walk(it, scope) }
                    }
                    // fields of `extend Foo { … }` are declared in the enclosing scope
                    E.EXTEND_DEFINITION -> c.findChildByType(E.EXTEND_BODY)?.let { walk(it, scope) }
                    E.MESSAGE_BODY, E.ONEOF_BODY -> walk(c, scope)
                }
            }
        }
        walk(file.node, pkg)
        CachedValueProvider.Result.create(out.toList(), PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** The file and every file it imports, transitively. */
    fun visibleFiles(file: PsiFile): List<PsiFile> {
        val seen = LinkedHashSet<PsiFile>()
        fun visit(f: PsiFile, depth: Int) {
            if (!seen.add(f) || depth > 12) return
            PsiTreeUtil.findChildrenOfType(f, PbImportName::class.java).forEach { i -> importedFile(f, i.path)?.let { visit(it, depth + 1) } }
        }
        visit(file, 0)
        return seen.toList()
    }

    fun table(file: PsiFile): Map<String, List<Sym>> = CachedValuesManager.getCachedValue(file, TABLE) {
        CachedValueProvider.Result.create(visibleFiles(file).flatMap { ownSymbols(it) }.groupBy { it.fullName }, PsiModificationTracker.MODIFICATION_COUNT)
    }

    fun fullNameOf(def: PbDefinition): String? = ownSymbols(def.containingFile).firstOrNull { it.element == def }?.fullName

    /** `pkg.Outer.Inner` for an element inside `message Inner` inside `message Outer`. */
    private fun scopeOf(e: PsiElement): String {
        val file = e.containingFile
        val enclosing = generateSequence(e.parent) { it.parent }.takeWhile { it !is PsiFile }
            .firstOrNull { it.node.elementType == E.MESSAGE_DEFINITION || it.node.elementType == E.GROUP_DEFINITION } as? PbDefinition
        return enclosing?.fullName ?: packageOf(file)
    }

    fun resolve(path: PbSymbolPath): List<PsiElement> = GUARD.run(emptyList()) {
        val segments = path.segments
        if (segments.isEmpty()) return@run emptyList()
        val file = path.containingFile
        val table = table(file)
        // what the outermost path of this name must be
        var top: PsiElement = path
        while (top.parent is PbSymbolPath) top = top.parent
        val holder = top.parent?.node?.elementType
        val kinds = when {
            top != path -> AGGREGATES
            holder == E.TYPE_NAME || holder == E.MESSAGE_TYPE_NAME -> TYPES
            holder == E.EXTENSION_NAME -> setOf(Kind.FIELD)
            else -> Kind.entries.toSet()
        }
        val absolute = top.prevSibling?.let { generateSequence(it) { s -> s.prevSibling }.firstOrNull { s -> s.text.isNotBlank() } }?.text == "."
        val target = segments.joinToString(".")
        val candidates = if (absolute) listOf(target) else {
            // innermost scope first; the first scope knowing the first component decides
            val scope = scopeOf(path)
            val scopes = generateSequence(scope) { s -> if (s.isEmpty()) null else s.substringBeforeLast('.', "") }.toList()
            scopes.firstOrNull { s -> table.containsKey(join(s, segments.first())) }?.let { listOf(join(it, target)) }.orEmpty()
        }
        candidates.flatMap { table[it].orEmpty() }.filter { it.kind in kinds }.map { it.element }.distinct()
    }

    /** Imports are relative to a proto root: the file's folder or one of its parents, up to the content root. */
    fun importedFile(from: PsiFile, path: String): PsiFile? {
        val vf = from.originalFile.virtualFile ?: return null
        val root = ProjectFileIndex.getInstance(from.project).getContentRootForFile(vf)
        var dir = vf.parent
        while (dir != null) {
            dir.findFileByRelativePath(path)?.let { return PsiManager.getInstance(from.project).findFile(it) }
            if (dir == root) break
            dir = dir.parent
        }
        // well-known types bundled with the plugin (google/protobuf/timestamp.proto…)
        val bundled = PbResolver::class.java.getResource("/include/$path")?.let { com.intellij.openapi.vfs.VfsUtil.findFileByURL(it) }
        return bundled?.let { PsiManager.getInstance(from.project).findFile(it) }
    }
}
