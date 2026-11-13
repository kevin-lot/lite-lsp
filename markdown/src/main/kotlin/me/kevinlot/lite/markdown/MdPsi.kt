package me.kevinlot.lite.markdown

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
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
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.Processor
import java.net.URLDecoder
import me.kevinlot.lite.markdown.MdElementTypes as E
import me.kevinlot.lite.markdown.MdTokens as T

open class MdElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** `## Title`: a link target (`#title`); renaming it updates the anchors that point to it. */
class MdHeader(node: ASTNode) : MdElement(node), PsiNameIdentifierOwner {
    val level: Int get() = node.findChildByType(T.HEADER_MARKER)?.textLength ?: 1
    override fun getName(): String = text.substring(level).trim().trimEnd('#').trim()
    /** GitHub numbers repeated titles: `#macos`, then `#macos-1`. */
    val anchor: String
        get() {
            val slug = Md.slug(name)
            val before = Md.headers(containingFile).takeWhile { it != this }.count { Md.slug(it.name) == slug }
            return if (before == 0) slug else "$slug-$before"
        }
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(T.HEADER_MARKER)?.treeNext?.let { n ->
        var e = n; while (e.elementType == T.WHITE_SPACE && e.treeNext != null) e = e.treeNext; e.psi
    }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement = replace(MdFactory(project).header("#".repeat(level) + " " + name))
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

/** `docs/setup.md#install`: the file (or folder), then the header of the anchor. */
class MdLinkDestination(node: ASTNode) : MdElement(node) {
    val dest: String get() = text.removePrefix("<").removeSuffix(">")
    private val offset: Int get() = if (text.startsWith("<")) 1 else 0

    override fun getReferences(): Array<PsiReference> {
        val d = dest
        if (Md.EXTERNAL.containsMatchIn(d)) return PsiReference.EMPTY_ARRAY
        val hash = d.indexOf('#')
        val path = if (hash >= 0) d.substring(0, hash) else d.substringBefore('?')
        val out = mutableListOf<PsiReference>()
        if (path.isNotEmpty()) out += MdFileReference(this, TextRange.from(offset, path.length), path)
        if (hash >= 0 && hash + 1 < d.length) out += MdAnchorReference(this, TextRange(offset + hash + 1, offset + d.length), path, d.substring(hash + 1))
        return out.toTypedArray()
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
}

/** A fenced block: its code is colored (and navigable) as the language of its info string. */
class MdCodeBlock(node: ASTNode) : MdElement(node), PsiLanguageInjectionHost {
    val info: String get() = node.findChildByType(T.FENCE_INFO)?.text?.trim()?.split(Regex("[\\s{,]"))?.firstOrNull().orEmpty()
    val codeRange: TextRange?
        get() {
            val first = node.findChildByType(T.CODE_TEXT) ?: return null
            var last = first
            var n = first.treeNext
            while (n != null) { if (n.elementType == T.CODE_TEXT) last = n; n = n.treeNext }
            return TextRange(first.startOffsetInParent, last.startOffsetInParent + last.textLength)
        }
    override fun isValidHost() = true
    override fun updateText(text: String): PsiLanguageInjectionHost = replace(MdFactory(project).codeBlock(text)) as PsiLanguageInjectionHost
    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> = LiteralTextEscaper.createSimple(this)
}

object MdPsiFactoryImpl {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        E.HEADER -> MdHeader(node)
        E.LINK_DEFINITION -> MdLinkDefinition(node)
        E.LINK_LABEL_REF -> MdLinkLabelRef(node)
        E.LINK_DESTINATION -> MdLinkDestination(node)
        E.CODE_BLOCK -> MdCodeBlock(node)
        else -> MdElement(node)
    }
}

class MdFactory(private val project: Project) {
    private fun file(text: String): PsiFile = PsiFileFactory.getInstance(project).createFileFromText("dummy.md", MdFileType, text)
    fun header(text: String): PsiElement = PsiTreeUtil.findChildOfType(file(text), MdHeader::class.java) ?: throw IncorrectOperationException(text)
    fun codeBlock(text: String): PsiElement = PsiTreeUtil.findChildOfType(file(text), MdCodeBlock::class.java) ?: throw IncorrectOperationException(text)
    fun definitionLabel(label: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("[$label]: x"), MdLinkDefinition::class.java)?.nameIdentifier ?: throw IncorrectOperationException(label)
    fun destination(dest: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("[x]($dest)"), MdLinkDestination::class.java) ?: throw IncorrectOperationException(dest)
    fun labelRef(label: String): PsiElement =
        PsiTreeUtil.findChildOfType(file("[x][$label]"), MdLinkLabelRef::class.java) ?: throw IncorrectOperationException(label)
}

// ---------------------------------------------------------------- references

object Md {
    val EXTERNAL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*:|//)")

    /** GitHub anchors: lower case, punctuation dropped (but `-` and `_`), spaces → `-`. */
    fun slug(title: String): String = title.lowercase()
        .replace(Regex("`|\\[|]\\([^)]*\\)|]"), "")
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .replace(' ', '-')

    fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) } catch (_: IllegalArgumentException) { s }

    fun headers(file: PsiFile?): List<MdHeader> = PsiTreeUtil.findChildrenOfType(file, MdHeader::class.java).toList()

    /** A link path: relative to the document, `/…` from its content root, `~/…` from home; `%20` decoded. */
    fun target(from: PsiElement, path: String): PsiFileSystemItem? {
        val vf = from.containingFile.originalFile.virtualFile ?: return null
        val decoded = decode(path)
        val target = if (decoded.startsWith("~/")) {
            // `~/bin/AGENTS.md`: the home folder (personal notes link across projects)
            vf.fileSystem.findFileByPath(System.getProperty("user.home") + decoded.substring(1))
        } else {
            val base = if (decoded.startsWith("/")) ProjectFileIndex.getInstance(from.project).getContentRootForFile(vf) else vf.parent
            base?.findFileByRelativePath(decoded.removePrefix("/").ifEmpty { "." })
        } ?: return null
        val manager = PsiManager.getInstance(from.project)
        return if (target.isDirectory) manager.findDirectory(target) else manager.findFile(target)
    }
}

class MdFileReference(element: MdLinkDestination, range: TextRange, private val path: String) :
    PsiPolyVariantReferenceBase<MdLinkDestination>(element, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = PsiElementResolveResult.createResults(listOfNotNull(Md.target(element, path)))

    /** A renamed file keeps its links: the last path segment changes. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val text = element.text
        val r = rangeInElement
        val old = r.substring(text)
        val renamed = old.substringBeforeLast('/', "").let { if (it.isEmpty()) newElementName else "$it/$newElementName" }
        return element.replace(MdFactory(element.project).destination(text.substring(0, r.startOffset) + renamed + text.substring(r.endOffset)))
    }
}

class MdAnchorReference(element: MdLinkDestination, range: TextRange, private val path: String, private val anchor: String) :
    PsiPolyVariantReferenceBase<MdLinkDestination>(element, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = if (path.isEmpty()) element.containingFile else Md.target(element, path) as? MdFile
        val wanted = Md.decode(anchor).lowercase()
        return PsiElementResolveResult.createResults(Md.headers(file).filter { it.anchor == wanted })
    }

    /** Renaming the header `Getting started` → `Setup` turns `#getting-started` into `#setup`. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val text = element.text
        val r = rangeInElement
        return element.replace(MdFactory(element.project).destination(text.substring(0, r.startOffset) + Md.slug(newElementName) + text.substring(r.endOffset)))
    }
}

class MdLabelReference(element: MdLinkLabelRef) : PsiPolyVariantReferenceBase<MdLinkLabelRef>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val label = normalize(element.text)
        return PsiElementResolveResult.createResults(
            PsiTreeUtil.findChildrenOfType(element.containingFile, MdLinkDefinition::class.java).filter { normalize(it.name.orEmpty()) == label },
        )
    }
    override fun handleElementRename(newElementName: String): PsiElement = element.replace(MdFactory(element.project).labelRef(newElementName))
    private fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
}

// ---------------------------------------------------------------- code blocks

/** ```` ```rust ```` blocks are Lite Rust, ```` ```bash ```` Lite Shell…: languages found by id, no compile dependency. */
class MdCodeInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(MdCodeBlock::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val block = context as? MdCodeBlock ?: return
        val range = block.codeRange ?: return
        val language = languageOf(block.info) ?: return
        registrar.startInjecting(language).addPlace(null, null, block, range).doneInjecting()
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
