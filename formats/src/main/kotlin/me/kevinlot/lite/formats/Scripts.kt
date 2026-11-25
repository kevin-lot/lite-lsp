package me.kevinlot.lite.formats

import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import me.kevinlot.lite.core.color

/*
 * Shell scripts held by other formats: the `run: |` of a workflow, the `RUN` of a Dockerfile, the recipes of a
 * Makefile or a justfile, the `run` of a mise task. The lexer of the format makes one token of the script, and that
 * token is read as Lite Shell (found by id: nothing to compile against). What the format itself links or
 * interpolates there (`${{ … }}`, `$(VAR)`, `{{name}}`) stays its own: the shell sees a plain word instead.
 */

/** Where a format holds shell text, and what it writes into it before the shell reads it. */
abstract class ScriptRules {
    /** The lines of the script in [word] (offsets in its text), without what is not shell (indentation, `@` prefixes); none when it is no shell script. */
    abstract fun lines(word: FmtWord, file: FmtFile): List<TextRange>

    /** Texts the format replaces itself, with what the shell reads in their place. */
    open val substitutions: List<Pair<Regex, String>> = emptyList()
}

class ScriptInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(FmtWord::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val word = context as? FmtWord ?: return
        val pieces = Scripts.pieces(word).ifEmpty { return }
        val shell = Language.findLanguageByID("LiteShell") ?: return
        pieces.fold(registrar.startInjecting(shell)) { places, piece -> places.addPlace(piece.prefix, piece.suffix, word, piece.range) }.doneInjecting()
    }
}

object Scripts {
    /** A stretch of the script (offsets in its token), with what stands for the texts cut out around it. */
    class Piece(val range: TextRange, val prefix: String?, val suffix: String?)

    private class Cut(val range: TextRange, val replacement: String)

    /** A name the format resolves is given to the shell as a plain word. */
    const val PLACEHOLDER = "__name__"

    fun pieces(word: FmtWord): List<Piece> {
        val file = word.containingFile as? FmtFile ?: return emptyList()
        val rules = file.format.scripts ?: return emptyList()
        val lines = rules.lines(word, file).ifEmpty { return emptyList() }
        val cuts = cuts(word, file, rules)
        return lines.flatMap { line -> pieces(line, cuts.filter { line.contains(it.range) }) }
    }

    /** Each line of [body] from [from] on, with its line break, after at most [indent] blanks (not for the first line when [first] is false). */
    fun lines(body: String, from: Int = 0, indent: Int = 0, first: Boolean = false): List<TextRange> {
        val starts = listOf(from) + (from until body.length).filter { body[it] == '\n' }.map { it + 1 }.filter { it < body.length }
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: body.length
            val blanks = body.substring(start, end).takeWhile { it == ' ' || it == '\t' }.length.coerceAtMost(indent).takeIf { index > 0 || first } ?: 0
            TextRange(start + blanks, end)
        }.filter { !it.isEmpty }
    }

    /** What the format replaces or links in the script, outermost first when two overlap. */
    private fun cuts(word: FmtWord, file: FmtFile, rules: ScriptRules): List<Cut> {
        val text = word.text
        val offset = word.textRange.startOffset
        val links = Fmt.links(file)
        val substituted = rules.substitutions.flatMap { (regex, replacement) -> regex.findAll(text).map { Cut(TextRange(it.range.first, it.range.last + 1), replacement) } }
        val named = links.refsIn(word.textRange).filter { Fmt.resolve(word, it).isNotEmpty() }.map { it.range } +
            links.pathsIn(word.textRange).filter { file.format.linker?.resolvePath(file, it.path) != null }.map { it.range }
        val all = (substituted + named.map { Cut(it.shiftLeft(offset), PLACEHOLDER) }).sortedWith(compareBy({ it.range.startOffset }, { -it.range.length }))
        return all.fold(emptyList()) { kept, cut -> kept.takeIf { it.isNotEmpty() && cut.range.startOffset < it.last().range.endOffset } ?: (kept + cut) }
    }

    /** [line] where each of the [replaced] ranges reads as its text (they must not overlap). */
    fun without(line: TextRange, replaced: List<Pair<TextRange, String>>): List<Piece> =
        pieces(line, replaced.sortedBy { it.first.startOffset }.map { (range, text) -> Cut(range, text) })

    /** [line] without its [cuts]: what replaces a cut is the suffix of the text before it, or the prefix of the text after. */
    private fun pieces(line: TextRange, cuts: List<Cut>): List<Piece> {
        if (cuts.isEmpty()) return listOf(Piece(line, null, null))
        val starts = listOf(line.startOffset) + cuts.map { it.range.endOffset }
        val ends = cuts.map { it.range.startOffset } + line.endOffset
        val segments = starts.zip(ends).map { (from, to) -> TextRange(from, to) }
        val kept = segments.indices.filter { !segments[it].isEmpty }
        return kept.mapIndexed { order, index ->
            val leading = cuts.take(index).joinToString("") { it.replacement }.takeIf { order == 0 }
            val until = kept.getOrNull(order + 1) ?: cuts.size
            Piece(segments[index], leading?.ifEmpty { null }, cuts.subList(index, until).joinToString("") { it.replacement }.ifEmpty { null })
        }
    }
}

/** In a script, the names the format links keep a color of their own: the shell does not see them. */
class ScriptNamesAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val word = element as? FmtWord ?: return
        if ((word.node.firstChildNode?.elementType as? FmtToken)?.cat != Cat.TEXT) return
        val key = Cat.VARIABLE.key ?: return
        Fmt.links(word.containingFile).refsIn(word.textRange).forEach {
            holder.newSilentAnnotation(com.intellij.lang.annotation.HighlightSeverity.INFORMATION).range(it.range).textAttributes(key).create()
        }
    }
}
