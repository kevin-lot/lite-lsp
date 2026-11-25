package me.kevinlot.lite.formats

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Helpers shared by the tests of the formats: `<ref>` / `<def>` markers and token lists. */
abstract class FmtTestCase : BasePlatformTestCase() {

    /** <ref> resolves to <def> (or to the first file when it has no <def>: a path). */
    protected fun check(vararg files: Pair<String, String>) {
        var refFile: String? = null; var refOffset = -1; var defFile: String? = null; var defOffset = -1
        for ((path, raw) in files) {
            var text = raw.trimIndent()
            val d = text.indexOf("<def>"); if (d >= 0) { defFile = path; defOffset = d; text = text.removeRange(d, d + 5) }
            val r = text.indexOf("<ref>"); if (r >= 0) { refFile = path; refOffset = r; text = text.removeRange(r, r + 5); if (defFile == path && defOffset > r) defOffset -= 5 }
            myFixture.addFileToProject(path, text)
        }
        val file = myFixture.configureFromTempProjectFile(refFile!!)
        val refs = generateSequence(file.findElementAt(refOffset)) { it.parent }.takeWhile { it !is PsiFile }
            .flatMap { e -> e.references.filter { it.rangeInElement.shiftRight(e.textRange.startOffset).containsOffset(refOffset) }.asSequence() }.toList()
        assertFalse("no reference at <ref>", refs.isEmpty())
        val targets = refs.mapNotNull { it.resolve() }
        assertTrue("resolved to ${targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }}", targets.any { t ->
            if (t is com.intellij.psi.PsiFileSystemItem) {
                val expected = defFile ?: files.first().first
                // a folder: the one holding the expected file
                return@any when (t) {
                    is com.intellij.psi.PsiDirectory -> "/src/$expected".startsWith(t.virtualFile.path + "/")
                    else -> t.virtualFile.path.endsWith(expected)
                }
            }
            val off = (t as? FmtWord)?.decl?.range?.startOffset ?: t.textOffset
            (t.containingFile?.virtualFile?.path ?: "").endsWith(defFile!!) && off == defOffset
        })
    }

    protected fun tokens(name: String, text: String): List<Pair<Cat?, String>> {
        val format = (myFixture.configureByText(name, text) as FmtFile).format
        val lexer = format.lexer()
        lexer.start(text)
        val out = mutableListOf<Pair<Cat?, String>>()
        var last = 0
        while (lexer.tokenType != null) {
            assertEquals("gap before ${lexer.tokenStart}", last, lexer.tokenStart)
            out += (lexer.tokenType as? FmtToken)?.cat to text.substring(lexer.tokenStart, lexer.tokenEnd)
            last = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, last)
        return out.filter { it.second.isNotBlank() }
    }

    protected fun assertToken(tokens: List<Pair<Cat?, String>>, cat: Cat, text: String) =
        assertTrue("no $cat '$text' in $tokens", tokens.any { it.first == cat && it.second == text })


    /** [text] with `<def>` at the `|` of [def] and `<ref>` at the `^` of [ref]. */
    protected fun mark(text: String, def: String, ref: String) = text
        .replaceFirst(ref.replace("^", ""), ref.replace("^", "<ref>"))
        .replaceFirst(def.replace("|", ""), def.replace("|", "<def>"))
}
