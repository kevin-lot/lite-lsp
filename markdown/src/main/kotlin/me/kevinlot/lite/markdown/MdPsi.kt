package me.kevinlot.lite.markdown

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.Processor
import java.net.URLDecoder
import me.kevinlot.lite.markdown.MdElementTypes as E
import me.kevinlot.lite.markdown.MdTokens as T

open class MdElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** An element made of one token (a destination, a code span, an HTML tag), with [range] of its text replaced. */
private fun MdElement.rewritten(range: TextRange, replacement: String): PsiElement {
    (node.firstChildNode as? LeafElement)?.replaceWithText(range.replace(text, replacement))
    return this
}

/** `## Title`, or `Title` over a `===` or `---` line: a link target (`#title`); renaming it updates the anchors that point to it. */
class MdHeader(node: ASTNode) : MdElement(node), PsiNameIdentifierOwner {
    private val underline: ASTNode? get() = node.findChildByType(T.SETEXT_MARKER)
    val level: Int
        get() = node.findChildByType(T.HEADER_MARKER)?.textLength ?: when (underline?.chars?.firstOrNull()) {
            '-' -> 2
            else -> 1
        }
    override fun getName(): String = underline?.let { text.substring(0, it.startOffsetInParent).trim() }
        ?: text.substring(level).trim().trimEnd('#').trim()
    val anchor: String get() = Md.anchors(containingFile)[this] ?: Md.slug(name)
    override fun getNameIdentifier(): PsiElement? = underline?.let { firstChild } ?: node.findChildByType(T.HEADER_MARKER)?.treeNext?.let { n ->
        var e = n; while (e.elementType == T.WHITE_SPACE && e.treeNext != null) e = e.treeNext; e.psi
    }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement {
        val underlined = underline?.let { name + "\n" + it.text.take(1).repeat(maxOf(name.length, 3)) }
        return replace(MdFactory(project).header(underlined ?: ("#".repeat(level) + " " + name)))
    }
}

/** `[label]: destination`. */
class MdLinkDefinition(node: ASTNode) : MdElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.LINK_LABEL)?.psi
    override fun getName(): String? = nameIdentifier?.text
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(MdFactory(project).definitionLabel(name)) ?: throw IncorrectOperationException("No label")
        return this
    }
}

/** `[text][label]` / `[label]`: the definition with that label (case-insensitive). */
class MdLinkLabelRef(node: ASTNode) : MdElement(node) {
    override fun getReference(): PsiReference? = text.takeIf { it.isNotBlank() }?.let { MdLabelReference(this) }
}

/** `docs/setup.md#install`: the file (or folder), then the header of the anchor; `src/Foo.kt#L10`: the file, then its line. */
class MdLinkDestination(node: ASTNode) : MdElement(node) {
    val dest: String get() = text.removePrefix("<").removeSuffix(">")
    private val offset: Int get() = when { text.startsWith("<") -> 1; else -> 0 }

    override fun getReferences(): Array<PsiReference> = Md.references(this, offset, dest).toTypedArray()
    override fun getReference(): PsiReference? = references.firstOrNull()
}

/** `` `MdLexer.kt` ``, `` `docs/setup.md` ``: a code span naming a file links to it; any other code has no reference. */
class MdCodeSpan(node: ASTNode) : MdElement(node) {
    override fun getReferences(): Array<PsiReference> {
        val ticks = text.takeWhile { it == '`' }.length
        val path = text.substring(ticks, text.length - ticks).trim()
        if (!Md.looksLikePath(path)) return PsiReference.EMPTY_ARRAY
        return arrayOf(MdCodeSpanReference(this, TextRange.from(text.indexOf(path, ticks), path.length), path))
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
}

/** `<img src="docs/a.png">`, `<a href="docs/x.md#part">`: a local path is a link. */
class MdHtmlTag(node: ASTNode) : MdElement(node) {
    override fun getReferences(): Array<PsiReference> = TARGET.findAll(text).mapNotNull { it.groups[1] }
        .flatMap { Md.references(this, it.range.first, it.value) }.toList().toTypedArray()

    override fun getReference(): PsiReference? = references.firstOrNull()

    private companion object {
        val TARGET = Regex("(?<![\\w-])(?:src|href)\\s*=\\s*[\"']([^\"'<>]+)[\"']", RegexOption.IGNORE_CASE)
    }
}

/** A fenced block: its code is colored (and navigable) as the language of its info string. */
class MdCodeBlock(node: ASTNode) : MdElement(node), PsiLanguageInjectionHost {
    val info: String get() = node.findChildByType(T.FENCE_INFO)?.text?.trim()?.split(INFO_END)?.firstOrNull().orEmpty()
    val codeRange: TextRange? get() = codeRanges.takeIf { it.isNotEmpty() }?.let { TextRange(it.first().startOffset, it.last().endOffset) }

    /** The code alone: in a block quote, every line of the block starts with a `>` that is not code. */
    val codeRanges: List<TextRange>
        get() = node.getChildren(CODE).map { TextRange.from(it.startOffsetInParent, it.textLength) }.fold(mutableListOf()) { ranges, range ->
            val last = ranges.lastOrNull()
            when (last?.endOffset) {
                range.startOffset -> ranges[ranges.lastIndex] = last.union(range)
                else -> ranges += range
            }
            ranges
        }
    override fun isValidHost() = true
    override fun updateText(text: String): PsiLanguageInjectionHost = replace(MdFactory(project).codeBlock(text)) as PsiLanguageInjectionHost
    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> = LiteralTextEscaper.createSimple(this)

    private companion object {
        val CODE = TokenSet.create(T.CODE_TEXT)
        val INFO_END = Regex("[\\s{,]")
    }
}

object MdPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        E.HEADER -> MdHeader(node)
        E.LINK_DEFINITION -> MdLinkDefinition(node)
        E.LINK_LABEL_REF -> MdLinkLabelRef(node)
        E.LINK_DESTINATION -> MdLinkDestination(node)
        E.CODE_BLOCK -> MdCodeBlock(node)
        E.CODE_SPAN -> MdCodeSpan(node)
        E.HTML_TAG -> MdHtmlTag(node)
        else -> MdElement(node)
    }
}

class MdFactory(private val project: Project) {
    private fun file(text: String): PsiFile = PsiFileFactory.getInstance(project).createFileFromText("dummy.md", MdFileType, text)
    fun header(text: String): PsiElement = PsiTreeUtil.findChildOfType(file(text), MdHeader::class.java) ?: throw IncorrectOperationException(text)
    fun codeBlock(text: String): PsiElement = PsiTreeUtil.findChildOfType(file(text), MdCodeBlock::class.java) ?: throw IncorrectOperationException(text)
    fun definitionLabel(label: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("[$label]: x"), MdLinkDefinition::class.java)?.nameIdentifier ?: throw IncorrectOperationException(label)
    fun labelRef(label: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("[x][$label]"), MdLinkLabelRef::class.java) ?: throw IncorrectOperationException(label)
}

// ---------------------------------------------------------------- references

object Md {
    val EXTERNAL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*:|//)")
    private val FILE_NAME = Regex("\\.[a-zA-Z][a-zA-Z0-9]*$")
    private val LINE = Regex("^L(\\d{1,9})(C\\d+)?(-L?\\d+(C\\d+)?)?$")
    private const val MAX_PATH = 260
    private val PATH = Regex("^[\\w./@~+-]+$")
    private val SLUG_DROPPED = Regex("`|\\[|]\\([^)]*\\)|]")
    private val ANCHORS = Key.create<CachedValue<Map<MdHeader, String>>>("lite.md.anchors")

    /** GitHub anchors: lower case, punctuation dropped (but `-` and `_`), spaces → `-`. */
    fun slug(title: String): String = title.lowercase()
        .replace(SLUG_DROPPED, "")
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .replace(' ', '-')

    fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) } catch (_: IllegalArgumentException) { s }

    fun headers(file: PsiFile?): List<MdHeader> = PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java).toList()

    /** The anchor of every header of [file]; GitHub numbers repeated titles: `#macos`, then `#macos-1`. */
    fun anchors(file: PsiFile): Map<MdHeader, String> = CachedValuesManager.getCachedValue(file, ANCHORS) {
        val seen = HashMap<String, Int>()
        val anchors = headers(file).associateWith { header ->
            val slug = slug(header.name)
            val before = seen.getOrDefault(slug, 0)
            seen[slug] = before + 1
            numbered(slug, before)
        }
        CachedValueProvider.Result.create(anchors, file)
    }

    fun numbered(slug: String, before: Int): String {
        if (before == 0) return slug
        return "$slug-$before"
    }

    /** The references of the link target written at [start] in [element]: the file, then `#header` or `#L10`. */
    fun references(element: MdElement, start: Int, dest: String): List<PsiReference> {
        if (EXTERNAL.containsMatchIn(dest)) return emptyList()
        val hash = dest.indexOf('#')
        val path = when {
            hash >= 0 -> dest.substring(0, hash)
            else -> dest.substringBefore('?')
        }
        val file = path.takeIf { it.isNotEmpty() }?.let { MdFileReference(element, TextRange.from(start, it.length), it) }
        val fragment = dest.substring(hash + 1).takeIf { hash >= 0 && it.isNotEmpty() } ?: return listOfNotNull(file)
        val range = TextRange.from(start + hash + 1, fragment.length)
        val line = LINE.find(fragment)?.groupValues?.get(1)?.toInt()?.takeIf { path.isNotEmpty() }
        return listOfNotNull(file, line?.let { MdLineReference(element, range, path, it) } ?: MdAnchorReference(element, range, path, fragment))
    }

    /** A link path: relative to the document, `/…` from its content root, `~/…` from home; `%20` decoded, `.md` optional. */
    fun target(from: PsiElement, path: String): PsiFileSystemItem? {
        val vf = from.containingFile.originalFile.virtualFile ?: return null
        val decoded = decode(path)
        val target = find(from.project, vf, decoded) ?: find(from.project, vf, "$decoded.md") ?: return null
        val manager = PsiManager.getInstance(from.project)
        if (target.isDirectory) return manager.findDirectory(target)
        return manager.findFile(target)
    }

    private fun find(project: Project, from: VirtualFile, path: String): VirtualFile? {
        val relative = path.removePrefix("/").ifEmpty { "." }
        return when {
            // `~/bin/AGENTS.md`: the home folder (personal notes link across projects)
            path.startsWith("~/") -> from.fileSystem.findFileByPath(System.getProperty("user.home") + path.substring(1))
            path.startsWith("/") -> ProjectFileIndex.getInstance(project).getContentRootForFile(from)?.findFileByRelativePath(relative)
            else -> from.parent?.findFileByRelativePath(relative)
        }
    }

    /** Checked before any lookup: a code span is a path when it has a `/`, or is a file name with an extension. */
    fun looksLikePath(text: String): Boolean = text.length <= MAX_PATH && PATH.matches(text) && text.any { it.isLetterOrDigit() } &&
        ('/' in text || FILE_NAME.containsMatchIn(text))

    /** The file a code span names: as a link would, else from the content root, else the only project file of that name. */
    fun named(from: PsiElement, path: String): PsiFileSystemItem? = target(from, path)
        ?: path.takeIf { '/' in it && !it.startsWith("/") && !it.startsWith("~") }?.let { target(from, "/$it") }
        ?: path.takeIf { '/' !in it }?.let { unique(from.project, it) }

    private fun unique(project: Project, name: String): PsiFile? {
        if (DumbService.isDumb(project)) return null
        val file = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).singleOrNull() ?: return null
        return PsiManager.getInstance(project).findFile(file)
    }

    /** Where `#L10` lands in [file]; a line past the end is the last one. */
    fun line(file: PsiFile, number: Int): PsiElement? {
        val document = file.viewProvider.document?.takeIf { it.lineCount > 0 } ?: return null
        return MdLine(file, document.getLineStartOffset((number - 1).coerceIn(0, document.lineCount - 1)))
    }
}

open class MdFileReference(element: MdElement, range: TextRange, protected val path: String) :
    PsiPolyVariantReferenceBase<MdElement>(element, range, true) {
    protected open fun target(): PsiFileSystemItem? = Md.target(element, path)
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = PsiElementResolveResult.createResults(listOfNotNull(target()))

    /** A renamed file keeps its links: the last path segment changes, and stays without `.md` when it had no extension. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val old = rangeInElement.substring(element.text)
        val folder = old.substringBeforeLast('/', "")
        val name = when {
            '.' in old.substringAfterLast('/') -> newElementName
            else -> newElementName.removeSuffix(".md")
        }
        val renamed = when {
            folder.isEmpty() -> name
            else -> "$folder/$name"
        }
        return element.rewritten(rangeInElement, renamed)
    }
}

class MdCodeSpanReference(element: MdCodeSpan, range: TextRange, path: String) : MdFileReference(element, range, path) {
    override fun target(): PsiFileSystemItem? = Md.named(element, path)
}

/** `src/Foo.kt#L10-L20`: line 10 of the file. A place, not a usage: no rename reaches it. */
class MdLineReference(element: MdElement, range: TextRange, private val path: String, private val line: Int) :
    PsiReferenceBase<MdElement>(element, range, true) {
    override fun resolve(): PsiElement? = (Md.target(element, path) as? PsiFile)?.let { Md.line(it, line) }
    override fun isReferenceTo(element: PsiElement) = false
    override fun handleElementRename(newElementName: String): PsiElement = element
}

/** The start of a line, in a file of any language (a plain-text file has no element there). */
class MdLine(private val file: PsiFile, private val offset: Int) : FakePsiElement() {
    override fun equals(other: Any?) = other is MdLine && other.file == file && other.offset == offset
    override fun getName(): String = file.name
    override fun getParent(): PsiElement = file
    override fun getText(): String = ""
    override fun getTextOffset() = offset
    override fun getTextRange() = TextRange.from(offset, 0)
    override fun hashCode() = 31 * file.hashCode() + offset
    override fun isValid() = file.isValid
}

class MdAnchorReference(element: MdElement, range: TextRange, private val path: String, private val anchor: String) :
    PsiPolyVariantReferenceBase<MdElement>(element, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = when {
            path.isEmpty() -> element.containingFile
            else -> Md.target(element, path) as? MdFile
        }
        val wanted = Md.decode(anchor).lowercase()
        val anchors = file?.let { Md.anchors(it) }.orEmpty()
        return PsiElementResolveResult.createResults(anchors.filterValues { it == wanted }.keys)
    }

    /** Renaming the header `Getting started` → `Setup` turns `#getting-started` into `#setup`. */
    override fun handleElementRename(newElementName: String): PsiElement = element.rewritten(rangeInElement, Md.slug(newElementName))
}

class MdLabelReference(element: MdLinkLabelRef) : PsiPolyVariantReferenceBase<MdLinkLabelRef>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val label = normalize(element.text)
        return PsiElementResolveResult.createResults(
            PsiTreeUtil.findChildrenOfType(element.containingFile, MdLinkDefinition::class.java).filter { normalize(it.name.orEmpty()) == label },
        )
    }
    override fun handleElementRename(newElementName: String): PsiElement = element.replace(MdFactory(element.project).labelRef(newElementName))
    private fun normalize(s: String) = s.trim().lowercase().replace(BLANKS, " ")

    private companion object {
        val BLANKS = Regex("\\s+")
    }
}

// ---------------------------------------------------------------- code blocks

/** ```` ```rust ```` blocks are Lite Rust, ```` ```bash ```` Lite Shell…: languages found by id, no compile dependency. */
class MdCodeInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(MdCodeBlock::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val block = context as? MdCodeBlock ?: return
        val ranges = block.codeRanges.ifEmpty { return }
        val language = languageOf(block.info) ?: return
        ranges.fold(registrar.startInjecting(language)) { places, range -> places.addPlace(null, null, block, range) }.doneInjecting()
    }

    companion object {
        /** The language of a fence's info string: known names, else a file extension or name (`toml`, `Dockerfile`). */
        fun languageOf(info: String): Language? {
            val id = info.trim().substringBefore(' ').substringBefore('{').lowercase()
            if (id.isEmpty()) return null
            LANGUAGES[id]?.let { Language.findLanguageByID(it) }?.let { return it }
            val types = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            val type = types.getFileTypeByExtension(id).takeIf { it !is com.intellij.openapi.fileTypes.UnknownFileType }
                ?: types.getFileTypeByFileName(id).takeIf { it !is com.intellij.openapi.fileTypes.UnknownFileType }
                ?: types.getFileTypeByFileName(id.replaceFirstChar { it.uppercase() })
            return (type as? com.intellij.openapi.fileTypes.LanguageFileType)?.language
        }

        private val LANGUAGES = mapOf(
            "rust" to "LiteRust", "rs" to "LiteRust",
            "kotlin" to "LiteKotlin", "kt" to "LiteKotlin", "kts" to "LiteKotlin",
            "python" to "LitePython", "py" to "LitePython", "python3" to "LitePython",
            "typescript" to "LiteTypeScript", "ts" to "LiteTypeScript", "tsx" to "LiteTypeScript",
            "javascript" to "LiteTypeScript", "js" to "LiteTypeScript", "jsx" to "LiteTypeScript", "mjs" to "LiteTypeScript",
            "qml" to "LiteQml",
            "bash" to "LiteShell", "sh" to "LiteShell", "shell" to "LiteShell", "zsh" to "LiteShell",
            "json" to "JSON", "jsonc" to "JSON", "xml" to "XML", "html" to "HTML",
            "markdown" to "LiteMarkdown", "md" to "LiteMarkdown",
            "lua" to "LiteLua", "luajit" to "LiteLua", "proto" to "LiteProto", "protobuf" to "LiteProto",
        )
    }
}

// ---------------------------------------------------------------- find usages

/** Anchors are slugs (`#getting-started`), not the header text: searched as such in Markdown files. */
class MdAnchorSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val header = params.elementToSearch as? MdHeader ?: return
        val slug = ReadAction.compute<String, RuntimeException> { header.anchor }
        if (slug.isEmpty()) return
        val scope = when (val s = params.effectiveSearchScope) {
            is GlobalSearchScope -> GlobalSearchScope.getScopeRestrictedByFileTypes(s, MdFileType)
            else -> s
        }
        slug.split('-', '_').filter { it.isNotEmpty() }.maxByOrNull { it.length }?.let {
            params.optimizer.searchWord(it, scope, UsageSearchContext.ANY, false, header)
        }
    }
}
