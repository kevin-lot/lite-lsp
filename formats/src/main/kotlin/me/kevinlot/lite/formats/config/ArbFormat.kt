package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Decl
import me.kevinlot.lite.formats.Fmt
import me.kevinlot.lite.formats.FmtFile
import me.kevinlot.lite.formats.FmtWord
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.R
import me.kevinlot.lite.formats.Ref
import me.kevinlot.lite.formats.Rule

/** Flutter's Application Resource Bundle: JSON whose top-level keys are the messages, `@key` holding the description of `key`. */
object Arb : Format("Arb", "ARB", "arb") {
    override val linker: Linker = ArbLinks
    override val rules = listOf(
        Rule(""""[^"\\\n]*+(?:\\.[^"\\\n]*+)*+"(?=\s*:)""", Cat.KEY),
        Rule(R.DQ, Cat.STRING),
        Rule("""\b(?:true|false|null)\b""", Cat.CONSTANT),
        Rule(R.NUMBER, Cat.NUMBER),
    )
}

/** A message is declared once per locale: the files beside this one hold the same keys. */
internal object ArbLinks : Linker() {
    private const val KIND = "message:"
    private val TOKEN = Regex(""""((?:[^"\\\n]|\\.)*)"(\s*:)?|[{}\[\]]""")

    override fun links(text: CharSequence, fileName: String): Links {
        val tokens = TOKEN.findAll(text).toList()
        val depths = tokens.runningFold(0) { depth, token ->
            when (token.value) {
                "{", "[" -> depth + 1
                "}", "]" -> depth - 1
                else -> depth
            }
        }
        val keys = tokens.zip(depths).filter { (token, depth) -> depth == 1 && token.groups[2] != null }.mapNotNull { it.first.groups[1] }
        val (described, messages) = keys.filter { !it.value.startsWith("@@") && it.value.isNotEmpty() }.partition { it.value.startsWith("@") }
        return Links(
            decls = messages.map { Decl(TextRange(it.range.first, it.range.last + 1), KIND + it.value) },
            refs = described.map { Ref(TextRange(it.range.first + 1, it.range.last + 1), listOf(KIND + it.value.drop(1))) },
        )
    }

    override fun scope(file: PsiFile): List<PsiFile> = listOf(file) + siblings(file)

    /** `@key` describes the `key` of its own file. */
    override fun resolve(file: PsiFile, ref: Ref): List<PsiElement>? = ref.keys.flatMap { Fmt.declarations(listOf(file), Arb, it) }.ifEmpty { null }

    fun siblings(file: PsiFile): List<PsiFile> =
        file.originalFile.containingDirectory?.files.orEmpty().filter { it != file.originalFile && (it as? FmtFile)?.format == Arb }
}

/** Renaming a message renames it in every locale. */
class ArbRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = (element.containingFile as? FmtFile)?.format == Arb && (element as? FmtWord)?.decl != null

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        val key = (element as? FmtWord)?.decl?.key ?: return
        Fmt.declarations(ArbLinks.siblings(element.containingFile), Arb, key).forEach { allRenames[it] = newName }
    }
}
