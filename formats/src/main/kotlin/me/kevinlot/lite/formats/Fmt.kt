package me.kevinlot.lite.formats

import com.intellij.lang.Language
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

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
    WORD(null), PUNCT(null), TEXT(null), LBRACE(C.BRACES), RBRACE(C.BRACES), LBRACKET(C.BRACKETS), RBRACKET(C.BRACKETS),
    LPAREN(C.PARENTHESES), RPAREN(C.PARENTHESES),
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
class Links(val decls: List<Decl> = emptyList(), val refs: List<Ref> = emptyList(), val paths: List<PathLink> = emptyList()) {
    private val declsByKey by lazy { decls.groupBy { it.key } }
    private val declsByOffset by lazy { ByOffset(decls) { it.range } }
    private val pathsByOffset by lazy { ByOffset(paths) { it.range } }
    private val refsByOffset by lazy { ByOffset(refs) { it.range } }

    fun declared(key: String): List<Decl> = declsByKey[key].orEmpty()
    fun declsIn(range: TextRange): List<Decl> = declsByOffset.within(range)
    fun pathsIn(range: TextRange): List<PathLink> = pathsByOffset.within(range)
    fun refsIn(range: TextRange): List<Ref> = refsByOffset.within(range)
}

/** Items by the start of their range: a token finds its own without scanning the whole file's. */
private class ByOffset<T>(items: List<T>, private val range: (T) -> TextRange) {
    private val byStart = items.withIndex().groupByTo(TreeMap()) { range(it.value).startOffset }

    /** The items inside [outer], in the order of the list they come from. */
    fun within(outer: TextRange): List<T> =
        byStart.subMap(outer.startOffset, true, outer.endOffset, true).values.flatten()
            .filter { outer.contains(range(it.value)) }.sortedBy { it.index }.map { it.value }
}

/** Offsets covered by [ranges], which must not overlap (the matches of one regex never do). */
internal class Offsets(ranges: Sequence<IntRange>) {
    private val ends = ranges.associateTo(TreeMap()) { it.first to it.last }
    operator fun contains(offset: Int): Boolean = ends.floorEntry(offset)?.let { offset <= it.value } == true
}

/** Finds names in a file's text; files sharing names (includes, a Terraform folder) are its [scope]. */
abstract class Linker {
    abstract fun links(text: CharSequence, fileName: String): Links
    open fun scope(file: PsiFile): List<PsiFile> = listOf(file) + Fmt.links(file).paths.mapNotNull { resolvePath(file, it.path) as? PsiFile }
    /** Relative to the file's folder by default. */
    open fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
        val dir = file.originalFile.virtualFile?.parent ?: return null
        val vf = when {
            path.startsWith("/") -> dir.fileSystem.findFileByPath(path)
            else -> dir.findFileByRelativePath(path)
        } ?: return null
        return PsiManager.getInstance(file.project).findItem(vf)
    }
    /** What a path becomes when the file it names is renamed to [newName]. */
    open fun renamedPath(old: String, newName: String): String {
        val folder = old.substringBeforeLast('/', "")
        return newName.takeIf { folder.isEmpty() } ?: "$folder/$newName"
    }
    /** Own resolution of [ref] (e.g. a JSON pointer), or null for the generic one: declarations by key in [scope]. */
    open fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? = null
}

abstract class Format(val id: String, val displayName: String, val extension: String) {
    abstract val rules: List<Rule>
    open val lineComment: String? = null
    open val blockComment: Pair<String, String>? = null
    open val caseInsensitiveKeywords = false
    open val linker: Linker? = null
    /** Token kinds that may hold names (wrapped in PSI elements carrying references). */
    open val nameCategories: Set<Cat> = setOf(Cat.KEY, Cat.VARIABLE, Cat.TYPE, Cat.DECL, Cat.STRING, Cat.WORD, Cat.SECTION, Cat.FUNCTION, Cat.CONSTANT)
    /** The shell scripts the format holds, as one [Cat.TEXT] token each (see Scripts.kt). */
    open val scripts: ScriptRules? = null
    /** A per-file lexer setup (e.g. the CSV delimiter guessed from the first line). */
    open fun lexer(): FmtLexer = FmtLexer(this)

    /** The platform keys languages by class: each format has its own (see Registrations.kt). */
    val language: Language by lazy { FMT_LANGUAGES.getValue(id) }
    val tokens: Map<Cat, IElementType> by lazy { Cat.entries.associateWith { FmtToken(it, language) } }
    val fileElementType: IFileElementType by lazy { IFileElementType("LITE_${id.uppercase()}_FILE", language) }
    val wordType: IElementType by lazy { FmtElementType("NAME", language) }
    fun token(c: Cat) = tokens.getValue(c)
}

object Fmt {
    private val LINKS = Key.create<CachedValue<Links>>("lite.fmt.links")

    private val LINKS_AS = ConcurrentHashMap<Format, Key<CachedValue<Links>>>()
    private const val MAX_PROJECT_FILES = 500
    private val NO_LINKS = Links()
    private val PROJECT_FILES = ConcurrentHashMap<Format, Key<CachedValue<List<VirtualFile>>>>()
    private val SCOPE = Key.create<CachedValue<List<PsiFile>>>("lite.fmt.scope")

    fun links(file: PsiFile?): Links {
        val f = file as? FmtFile ?: return NO_LINKS
        val linker = f.format.linker ?: return NO_LINKS
        return CachedValuesManager.getCachedValue(f, LINKS) {
            CachedValueProvider.Result.create(linker.links(f.text, f.name), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    /** A file of the scope read with [format]'s linker (an included `defaults.conf` is HOCON, whatever its type). */
    private fun linksAs(f: PsiFile, format: Format): Links {
        if ((f as? FmtFile)?.format == format) return links(f)
        val linker = format.linker ?: return NO_LINKS
        val key = LINKS_AS.computeIfAbsent(format) { Key.create("lite.fmt.links.${it.id}") }
        return CachedValuesManager.getCachedValue(f, key) {
            CachedValueProvider.Result.create(linker.links(f.text, f.name), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    /** The [Linker.scope] of [file], without duplicates. Cached: every resolve asks for it, and it may list the project. */
    fun scope(file: PsiFile): List<PsiFile> {
        val f = file as? FmtFile ?: return emptyList()
        val linker = f.format.linker ?: return emptyList()
        return CachedValuesManager.getCachedValue(f, SCOPE) {
            CachedValueProvider.Result.create(
                linker.scope(f).distinct(), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }
    }

    /** The project's files of [format], sorted by path and capped. Cached: a resolve must not list the project again. */
    fun projectFiles(project: Project, format: Format): List<VirtualFile> {
        val key = PROJECT_FILES.computeIfAbsent(format) { Key.create("lite.fmt.files.${it.id}") }
        return CachedValuesManager.getManager(project).getCachedValue(project, key, {
            val files = FileTypeIndex.getFiles(FMT_FILE_TYPES.getValue(format), GlobalSearchScope.projectScope(project))
            CachedValueProvider.Result.create(
                files.sortedBy { it.path }.take(MAX_PROJECT_FILES), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }, false)
    }

    /** Declarations with the first key that has any, in the reference's file and its scope. */
    fun resolve(place: FmtWord, ref: Ref): List<PsiElement> {
        val file = place.containingFile as? FmtFile ?: return emptyList()
        file.format.linker?.resolve(file, ref)?.let { return it }
        val scope = scope(file)
        for (key in ref.keys) {
            val found = declarations(scope, file.format, key)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** The declarations of [key] in [files], each read with [format]'s linker. */
    fun declarations(files: List<PsiFile>, format: Format, key: String): List<PsiElement> =
        files.flatMap { f ->
            linksAs(f, format).declared(key).mapNotNull { d ->
                val leaf = f.findElementAt(d.range.startOffset)
                PsiTreeUtil.getParentOfType(leaf, FmtWord::class.java, false) ?: leaf
            }
        }.distinct()
}

/** The folder or the file [vf] is. */
internal fun PsiManager.findItem(vf: VirtualFile): PsiFileSystemItem? = when {
    vf.isDirectory -> findDirectory(vf)
    else -> findFile(vf)
}

/** Helpers for linkers: matches of a regex group as ranges. */
fun Regex.ranges(text: CharSequence, group: Int = 1): Sequence<Pair<TextRange, String>> =
    findAll(text).mapNotNull { m -> m.groups[group]?.let { g -> TextRange(g.range.first, g.range.last + 1) to g.value } }

/** A group its pattern always fills when it matches. */
internal fun MatchResult.group(index: Int): MatchGroup = checkNotNull(groups[index])

internal fun range(base: Int, r: IntRange) = TextRange(base + r.first, base + r.last + 1)

// Strings are matched run by run (`[^"\\]*+`), never character by character inside a repeated alternation: java.util.regex
// recurses once per iteration of `(?:a|b)*`, and a 6000-character string then overflows the stack.
internal object R {
    const val HASH = """#[^\n]*"""
    const val SLASH = """//[^\n]*"""
    const val BLOCK = """/\*[\s\S]*?(?:\*/|\z)"""
    const val DQ = """"[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"?"""
    const val SQ = """'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'?"""
    const val NUMBER = """(?<![\w.])[-+]?(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][-+]?\d+)?)(?![\w])"""
}
