package me.kevinlot.lite.formats.data

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.FmtLexer
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule

/**
 * Log files: each line is one token, colored by the first level it names (`ERROR`, `level=warn`, `[info]`, `error:`).
 * The lines of a stack trace keep the level of the line they follow. Stack frames and `path:line` lead to the source.
 */
object Log : Format("Log", "Log", "log") {
    private val CONTINUATION = Regex("""^(?:at |Caused by|Suppressed:|\.\.\. \d+ (?:more|common frames))""")
    private val KEYED = """\b(?:level|levelname|loglevel|lvl|severity)"?\s*[=:]\s*"?"""
    private val STARTERS = """^(?:Exception in thread|Traceback \(most recent call last\)|thread '[^']*' panicked|panic:|[\w.$]*(?:Exception|Error):)"""
    private val WORDS = listOf(
        Cat.LOG_ERROR to "ERROR|FATAL|SEVERE|CRITICAL|PANIC|ERR|EMERG|ALERT", Cat.LOG_WARNING to "WARN|WARNING",
        Cat.LOG_INFO to "INFO|NOTICE", Cat.LOG_DEBUG to "DEBUG|TRACE|VERBOSE|FINE|FINER|FINEST",
    )
    private val LEVEL = WORDS.joinToString("|") { (cat, words) ->
        val starters = "|$STARTERS".takeIf { cat == Cat.LOG_ERROR }.orEmpty()
        "(?<${cat.name.replace("_", "")}>\\b(?:$words)\\b|(?i:$KEYED(?:$words)\\b)|\\[(?i:$words)]|^(?i:$words)(?:\\[\\w+])?:$starters)"
    }.toPattern()

    override val linker: Linker = LogLinks
    override val nameCategories = setOf(Cat.LOG_DEBUG, Cat.LOG_ERROR, Cat.LOG_INFO, Cat.LOG_LINE, Cat.LOG_WARNING)
    override val rules = emptyList<Rule>()

    /** The level the first level word of [line] names, the leftmost one: `INFO … ERROR code` is an `INFO` line. */
    fun level(line: CharSequence): Cat? {
        val matcher = LEVEL.matcher(line)
        if (!matcher.find()) return null
        return WORDS.map { it.first }.firstOrNull { matcher.group(it.name.replace("_", "")) != null }
    }

    /** [line] goes on with the event above it: it is indented, or a part of a stack trace. */
    fun continues(line: CharSequence, indented: Boolean): Boolean = indented || CONTINUATION.containsMatchIn(line)

    override fun lexer(): FmtLexer = object : FmtLexer(this) {
        private var current: Cat? = null

        /** The level of the event the first line belongs to is the one of a line above. */
        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
            current = lines(buffer, startOffset).firstNotNullOfOrNull { (line, indented) -> level(line) ?: Cat.LOG_LINE.takeIf { !continues(line, indented) } }
            super.start(buffer, startOffset, endOffset, initialState)
        }

        override fun next(): IElementType {
            if (buf[pos].isWhitespace()) {
                while (pos < end && buf[pos].isWhitespace()) pos++
                return TokenType.WHITE_SPACE
            }
            val indented = pos > 0 && buf[pos - 1] != '\n'
            val from = pos
            while (pos < end && buf[pos] != '\n') pos++
            val line = buf.subSequence(from, pos)
            val cat = level(line) ?: current.takeIf { continues(line, indented) } ?: Cat.LOG_LINE
            current = cat
            return token(cat)
        }
    }

    /** The lines above [offset] that are not blank, the nearest first, each with whether it is indented. */
    private fun lines(buffer: CharSequence, offset: Int): Sequence<Pair<CharSequence, Boolean>> =
        generateSequence(above(buffer, offset)) { above(buffer, it.startOffset) }.map { buffer.subSequence(it.startOffset, it.endOffset) }
            .filter { it.isNotBlank() }.map { it.trimStart() to it[0].isWhitespace() }

    /** The bounds of the line ending at the line break before [offset]; null at the top. */
    private fun above(buffer: CharSequence, offset: Int): TextRange? {
        val end = StringUtil.lastIndexOf(buffer, '\n', 0, offset).takeIf { it >= 0 } ?: return null
        return TextRange(StringUtil.lastIndexOf(buffer, '\n', 0, end) + 1, end)
    }
}

/** Where a log points to: stack frames of the JVM and of Python, and any `path:line`, `path:line:col` or `path(line,col)`. */
internal object LogLinks : Linker() {
    private val JVM = Regex("""\bat (?:[\w$.@-]*/+)*([\w$.]+)\.[\w$<>]+\((\w[\w$.-]*\.\w+):(\d+)\)""")
    private val PATH = Regex("""(?<![\w/.\\-])((?:file://)?(?:[\w@.~+-]*/)*[\w@+-][\w@.+-]*\.[A-Za-z]\w{0,9})(?::(\d+)(?::(\d+))?|\((\d+),(\d+)\))""")
    private val PYTHON = Regex("""File "([^"\n]+)", line (\d+)""")
    private const val SEPARATOR = "\n"

    override fun links(text: CharSequence, fileName: String): Links {
        val frames = JVM.findAll(text).map { m ->
            val file = checkNotNull(m.groups[2])
            ref(file.range.first, checkNotNull(m.groups[3]).range.last + 1, file.value, m.groupValues[3], "", m.groupValues[1].substringBeforeLast('.', ""))
        }
        val python = PYTHON.findAll(text).map { m ->
            val file = checkNotNull(m.groups[1])
            ref(file.range.first, file.range.last + 1, file.value, m.groupValues[2], "", "")
        }
        val known = (frames + python).toList()
        val starts = known.map { it.range.startOffset }.toSet()
        val paths = PATH.findAll(text).filter { it.range.first !in starts }.map { m ->
            ref(m.range.first, m.range.last + 1, m.groupValues[1], m.groupValues[2] + m.groupValues[4], m.groupValues[3] + m.groupValues[5], "")
        }
        return Links(refs = (known + paths).sortedBy { it.range.startOffset })
    }

    private fun ref(from: Int, to: Int, path: String, line: String, column: String, pkg: String): Ref =
        Ref(TextRange(from, to), listOf(listOf(path.removePrefix("file://"), line, column, pkg).joinToString(SEPARATOR)))

    /** A log names no file of its own: nothing to list beside it. */
    override fun scope(file: PsiFile): List<PsiFile> = listOf(file)

    override fun resolve(file: PsiFile, ref: Ref): List<PsiElement> {
        val (path, line, column, pkg) = ref.keys.first().split(SEPARATOR).takeIf { it.size == 4 } ?: return emptyList()
        val manager = PsiManager.getInstance(file.project)
        return files(file, path, pkg).mapNotNull(manager::findFile).map { LogTarget(it, line.toIntOrNull() ?: 1, column.toIntOrNull() ?: 1) }
    }

    /**
     * The files [path] names: as written, from a folder above the log, else the files of the project of that name whose path
     * ends most like it (a log is often written on another machine). A stack frame of the JVM gives the package for a path.
     */
    private fun files(file: PsiFile, path: String, pkg: String): List<VirtualFile> {
        val relative = path.removePrefix("./")
        if (path.startsWith("/")) LocalFileSystem.getInstance().findFileByPath(path)?.let { return listOf(it) }
        val above = generateSequence(file.originalFile.virtualFile?.parent) { it.parent }
        if (pkg.isEmpty() && !path.startsWith("/")) above.firstNotNullOfOrNull { it.findFileByRelativePath(relative) }?.let { return listOf(it) }
        if (DumbService.isDumb(file.project)) return emptyList()
        val wanted = (pkg.split('.') + relative.split('/')).filter { it.isNotEmpty() }.reversed()
        val named = FilenameIndex.getVirtualFilesByName(wanted.first(), GlobalSearchScope.projectScope(file.project)).sortedBy { it.path }
        val scored = named.groupBy { candidate -> candidate.path.split('/').reversed().zip(wanted).takeWhile { it.first == it.second }.size }
        return scored.maxByOrNull { it.key }?.value.orEmpty()
    }
}

/** A line of a file, whatever its type: the file is not parsed to point at it. */
class LogTarget(private val file: PsiFile, val line: Int, val column: Int) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun isValid(): Boolean = file.isValid
    override fun getName(): String = "${file.name}:$line"
    override fun getPresentableText(): String = name
    override fun canNavigate(): Boolean = true
    override fun navigate(requestFocus: Boolean) {
        val target = file.virtualFile ?: return
        OpenFileDescriptor(file.project, target, line - 1, column - 1).navigate(requestFocus)
    }
    override fun equals(other: Any?): Boolean = other is LogTarget && other.file == file && other.line == line && other.column == column
    override fun hashCode(): Int = file.hashCode() * 31 + line
}
