package me.kevinlot.lite.formats

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.TokenType
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
import java.util.regex.Pattern
import javax.swing.Icon
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C

/*
 * Configuration and data formats: each one is a [Format] — lexing rules (regular expressions tried in order)
 * and, when names point to each other (`*anchor`, `${VAR}`, `var.x`), a [Linker] that finds declarations,
 * references and file paths in the text. Coloring, Ctrl+Click, rename and Find Usages are generic.
 */

/** What a token is, for coloring (and whether it may hold a name). */
enum class Cat(val key: TextAttributesKey?) {
    COMMENT(C.LINE_COMMENT), STRING(C.STRING), NUMBER(C.NUMBER), KEYWORD(C.KEYWORD), CONSTANT(C.CONSTANT),
    KEY(C.INSTANCE_FIELD), SECTION(C.CLASS_NAME), VARIABLE(C.LOCAL_VARIABLE), TYPE(C.CLASS_REFERENCE), FUNCTION(C.FUNCTION_CALL),
    DECL(C.FUNCTION_DECLARATION), TAG(C.MARKUP_TAG), ATTRIBUTE(C.MARKUP_ATTRIBUTE), META(C.METADATA), OPERATOR(C.OPERATION_SIGN),
    WORD(null), PUNCT(null), LBRACE(C.BRACES), RBRACE(C.BRACES), LBRACKET(C.BRACKETS), RBRACKET(C.BRACKETS), LPAREN(C.PARENTHESES), RPAREN(C.PARENTHESES),
    COL0(C.KEYWORD), COL1(C.STRING), COL2(C.NUMBER), COL3(C.INSTANCE_FIELD), COL4(C.FUNCTION_CALL), COL5(C.METADATA),
}

/** A lexing rule: [regex] tried at the current position (only after indentation when [lineStart]). */
class Rule(regex: String, val cat: Cat, val lineStart: Boolean = false, flags: Int = 0) {
    val pattern: Pattern = Pattern.compile(regex, flags)
}

data class Decl(val range: TextRange, val key: String)
/** A use of a name: [keys] tried in order; [nameRange] is what a rename replaces (default: all of [range]). */
data class Ref(val range: TextRange, val keys: List<String>, val nameRange: TextRange = range)
data class PathLink(val range: TextRange, val path: String)
class Links(val decls: List<Decl> = emptyList(), val refs: List<Ref> = emptyList(), val paths: List<PathLink> = emptyList())

/** Finds names in a file's text; files sharing names (includes, a Terraform folder) are its [scope]. */
abstract class Linker {
    abstract fun links(text: CharSequence, fileName: String): Links
    open fun scope(file: PsiFile): List<PsiFile> = listOf(file) + Fmt.links(file).paths.mapNotNull { resolvePath(file, it.path) as? PsiFile }
    /** Relative to the file's folder by default. */
    open fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
        val dir = file.originalFile.virtualFile?.parent ?: return null
        val vf = (if (path.startsWith("/")) dir.fileSystem.findFileByPath(path) else dir.findFileByRelativePath(path)) ?: return null
        val manager = PsiManager.getInstance(file.project)
        return if (vf.isDirectory) manager.findDirectory(vf) else manager.findFile(vf)
    }
}

abstract class Format(val id: String, val displayName: String, val extension: String) {
    abstract val rules: List<Rule>
    open val lineComment: String? = null
    open val blockComment: Pair<String, String>? = null
    open val caseInsensitiveKeywords = false
    open val linker: Linker? = null
    /** Token kinds that may hold names (wrapped in PSI elements carrying references). */
    open val nameCategories: Set<Cat> = setOf(Cat.KEY, Cat.VARIABLE, Cat.TYPE, Cat.DECL, Cat.STRING, Cat.WORD, Cat.SECTION, Cat.FUNCTION, Cat.CONSTANT)
    /** A per-file lexer setup (e.g. the CSV delimiter guessed from the first line). */
    open fun lexer(): FmtLexer = FmtLexer(this)

    /** The platform keys languages by class: each format has its own (see Registrations.kt). */
    val language: Language by lazy { FMT_LANGUAGES.getValue(id) }
    val tokens: Map<Cat, IElementType> by lazy { Cat.entries.associateWith { FmtToken(it, language) } }
    val fileElementType: IFileElementType by lazy { IFileElementType("LITE_${id.uppercase()}_FILE", language) }
    val wordType: IElementType by lazy { FmtElementType("NAME", language) }
    fun token(c: Cat) = tokens.getValue(c)
}

open class FmtLanguage(id: String, private val display: String) : Language("Lite$id") {
    override fun getDisplayName() = display
}

class FmtToken(val cat: Cat, language: Language) : IElementType(cat.name, language)
class FmtElementType(name: String, language: Language) : IElementType(name, language)

open class FmtFileType(val format: Format, secondary: Boolean = false) : LanguageFileType(format.language, secondary) {
    override fun getName() = "${format.displayName} (Lite)"
    override fun getDisplayName() = format.displayName
    override fun getDescription() = "${format.displayName} file"
    override fun getDefaultExtension() = format.extension
    override fun getIcon(): Icon? = null
}

class FmtFile(viewProvider: FileViewProvider, val format: Format) : PsiFileBase(viewProvider, format.language) {
    override fun getFileType() = viewProvider.fileType
    override fun toString() = "${format.displayName} file"
}

// ---------------------------------------------------------------- lexer

/** Whitespace, then the first [Format.rules] that matches; else a word, else one character. */
open class FmtLexer(protected val format: Format) : LexerBase() {
    protected var buf: CharSequence = ""
    protected var end = 0
    protected var start = 0
    protected var pos = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        advance()
    }

    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = pos
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        start = pos
        type = if (pos >= end) null else next()
    }

    protected fun token(c: Cat) = format.token(c)

    protected fun onlyIndentBefore(): Boolean {
        var i = pos - 1
        while (i >= 0 && buf[i] != '\n') { if (buf[i] != ' ' && buf[i] != '\t') return false; i-- }
        return true
    }

    protected open fun next(): IElementType {
        val c = buf[pos]
        if (c.isWhitespace()) { while (pos < end && buf[pos].isWhitespace()) pos++; return TokenType.WHITE_SPACE }
        val lineStart = onlyIndentBefore()
        for (rule in format.rules) {
            if (rule.lineStart && !lineStart) continue
            val m = rule.pattern.matcher(buf).region(pos, end)
            m.useTransparentBounds(true).useAnchoringBounds(false)
            if (m.lookingAt() && m.end() > pos) { pos = m.end(); return format.token(rule.cat) }
        }
        if (c.isLetterOrDigit() || c == '_') {
            while (pos < end && (buf[pos].isLetterOrDigit() || buf[pos] == '_' || buf[pos] == '-')) pos++
            return format.token(Cat.WORD)
        }
        pos++
        return format.token(
            when (c) {
                '{' -> Cat.LBRACE; '}' -> Cat.RBRACE; '[' -> Cat.LBRACKET; ']' -> Cat.RBRACKET; '(' -> Cat.LPAREN; ')' -> Cat.RPAREN
                '=', ':', '+', '-', '*', '/', '<', '>', '!', '&', '|', '%', '?', '^', '~' -> Cat.OPERATOR
                else -> Cat.PUNCT
            },
        )
    }
}

// ---------------------------------------------------------------- PSI

/** Name-holding tokens get a node: it carries the references and declarations the [Linker] found there. */
class FmtParser(private val format: Format) : PsiParser {
    private val named = TokenSet.create(*format.nameCategories.map { format.token(it) }.toTypedArray())
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val m = builder.mark()
        val wrap = format.linker != null
        while (!builder.eof()) {
            if (wrap && builder.tokenType in named) { val w = builder.mark(); builder.advanceLexer(); w.done(format.wordType) } else builder.advanceLexer()
        }
        m.done(root)
        return builder.treeBuilt
    }
}

open class FmtParserDefinition(private val format: Format) : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = format.lexer()
    override fun createParser(project: Project?): PsiParser = FmtParser(format)
    override fun getFileNodeType() = format.fileElementType
    override fun getCommentTokens(): TokenSet = TokenSet.create(format.token(Cat.COMMENT))
    /** None: names live in strings (`variable "region"`), and literal tokens are not offered for rename. */
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun createElement(node: ASTNode): PsiElement = FmtWord(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = FmtFile(viewProvider, format)
}

/** A name-holding token: a declaration and/or references, as the format's [Linker] decides from offsets. */
class FmtWord(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    private val format: Format? get() = (containingFile as? FmtFile)?.format
    val decl: Decl? get() = Fmt.links(containingFile).decls.firstOrNull { textRange.contains(it.range) }

    override fun getReferences(): Array<PsiReference> {
        val links = Fmt.links(containingFile)
        val r = textRange
        val refs = links.refs.filter { r.contains(it.range) }.map { FmtReference(this, it) } +
            links.paths.filter { r.contains(it.range) }.map { FmtPathReference(this, it) }
        return refs.toTypedArray()
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
    override fun getNameIdentifier(): PsiElement? = if (decl != null) this else null
    override fun getName(): String? = decl?.range?.substring(containingFile.text)

    override fun setName(name: String): PsiElement {
        val d = decl ?: throw IncorrectOperationException("Not a declaration")
        replaceIn(d.range, name)
        return this
    }

    /** Replaces [range] (file offsets, inside this token) by [text]. */
    fun replaceIn(range: TextRange, text: String) {
        val leaf = node.firstChildNode as? LeafElement ?: return
        val local = range.shiftLeft(textRange.startOffset)
        leaf.replaceWithText(local.replace(leaf.text, text))
    }
}

class FmtReference(element: FmtWord, private val ref: Ref) :
    PsiPolyVariantReferenceBase<FmtWord>(element, ref.range.shiftLeft(element.textRange.startOffset), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = PsiElementResolveResult.createResults(Fmt.resolve(element, ref))
    override fun handleElementRename(newElementName: String): PsiElement { element.replaceIn(ref.nameRange, newElementName); return element }
}

class FmtPathReference(element: FmtWord, private val link: PathLink) :
    PsiPolyVariantReferenceBase<FmtWord>(element, link.range.shiftLeft(element.textRange.startOffset), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile as? FmtFile ?: return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(listOfNotNull(file.format.linker?.resolvePath(file, link.path)))
    }
    override fun handleElementRename(newElementName: String): PsiElement {
        val old = link.range.substring(element.containingFile.text)
        val renamed = old.substringBeforeLast('/', "").let { if (it.isEmpty()) newElementName else "$it/$newElementName" }
        element.replaceIn(link.range, renamed)
        return element
    }
}

object Fmt {
    private val LINKS = Key.create<CachedValue<Links>>("lite.fmt.links")

    fun links(file: PsiFile?): Links {
        val f = file as? FmtFile ?: return Links()
        val linker = f.format.linker ?: return Links()
        return CachedValuesManager.getCachedValue(f, LINKS) { CachedValueProvider.Result.create(linker.links(f.text, f.name), PsiModificationTracker.MODIFICATION_COUNT) }
    }

    /** A file of the scope read with [format]'s linker (an included `defaults.conf` is HOCON, whatever its type). */
    private fun linksAs(f: PsiFile, format: Format): Links =
        if ((f as? FmtFile)?.format == format) links(f) else format.linker?.links(f.text, f.name) ?: Links()

    /** Declarations with the first key that has any, in the reference's file and its scope. */
    fun resolve(place: FmtWord, ref: Ref): List<PsiElement> {
        val file = place.containingFile as? FmtFile ?: return emptyList()
        val scope = file.format.linker?.scope(file).orEmpty().distinct()
        for (key in ref.keys) {
            val found = scope.flatMap { f ->
                linksAs(f, file.format).decls.filter { it.key == key }.mapNotNull { d ->
                    val leaf = f.findElementAt(d.range.startOffset)
                    PsiTreeUtil.getParentOfType(leaf, FmtWord::class.java, false) ?: leaf
                }
            }
            if (found.isNotEmpty()) return found.distinct()
        }
        return emptyList()
    }
}

// ---------------------------------------------------------------- IDE

open class FmtHighlighterFactory(private val format: Format) : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer(): Lexer = format.lexer()
        override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack((t as? FmtToken)?.cat?.key)
    }
}

open class FmtCommenter(private val format: Format) : Commenter {
    override fun getLineCommentPrefix() = format.lineComment
    override fun getBlockCommentPrefix() = format.blockComment?.first
    override fun getBlockCommentSuffix() = format.blockComment?.second
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

open class FmtBraceMatcher(format: Format) : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(format.token(Cat.LBRACE), format.token(Cat.RBRACE), true),
        BracePair(format.token(Cat.LBRACKET), format.token(Cat.RBRACKET), false),
        BracePair(format.token(Cat.LPAREN), format.token(Cat.RPAREN), false),
    )
    override fun getPairs() = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}

open class FmtFindUsagesProvider(private val format: Format) : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        format.lexer(), TokenSet.create(*format.nameCategories.map { format.token(it) }.toTypedArray()),
        TokenSet.create(format.token(Cat.COMMENT)), TokenSet.EMPTY,
    )
    override fun canFindUsagesFor(element: PsiElement) = element is FmtWord && element.decl != null
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement) = (element as? FmtWord)?.decl?.key?.substringBefore(':') ?: "name"
    override fun getDescriptiveName(element: PsiElement) = (element as? FmtWord)?.name.orEmpty()
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}

/** Helpers for linkers: matches of a regex group as ranges. */
fun Regex.ranges(text: CharSequence, group: Int = 1): Sequence<Pair<TextRange, String>> =
    findAll(text).mapNotNull { m -> m.groups[group]?.let { g -> TextRange(g.range.first, g.range.last + 1) to g.value } }
