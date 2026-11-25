package me.kevinlot.lite.formats

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

/** LITE_FMT_PERF=<file:file>: for each file, the time to highlight it (lexing, injections, annotators) and to resolve its references. */
class PerfTest : FmtTestCase() {
    private fun millis(action: () -> Unit): Long = System.nanoTime().let { start -> action(); (System.nanoTime() - start) / 1_000_000 }

    fun `test timings`() {
        val files = System.getenv("LITE_FMT_PERF")?.split(':')?.map(::File) ?: return
        files.forEachIndexed { index, source ->
            val name = "Containerfile".takeIf { source.name.startsWith("Dockerfile") } ?: source.name
            val file = myFixture.addFileToProject("perf/$index/$name", source.readText())
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            val highlight = (1..3).map { millis { psiManager.dropPsiCaches(); myFixture.doHighlighting() } }
            val words = PsiTreeUtil.findChildrenOfType(file, FmtWord::class.java)
            val resolve = (1..3).map {
                millis {
                    psiManager.dropPsiCaches()
                    PsiTreeUtil.findChildrenOfType(myFixture.file, FmtWord::class.java).forEach { word -> word.references.forEach { (it as? PsiPolyVariantReference)?.multiResolve(false) } }
                }
            }
            val format = (file as FmtFile).format
            val text = file.text
            fun best(action: () -> Unit) = (1..5).minOf { millis(action) }
            val lex = best { val lexer = format.lexer(); lexer.start(text); while (lexer.tokenType != null) lexer.advance() }
            val link = best { format.linker?.links(text, file.name) }
            val keys = best { Pointers.yamlKeys(text) }
            val cron = best { words.forEach { CronPlaces.expression(it) } }
            val scripts = best { words.forEach { Scripts.pieces(it) } }
            println("perf   parts: lexer $lex ms, links $link ms (yaml keys $keys ms), cron places $cron ms, script pieces $scripts ms")
            println("perf ${source.name} (${source.length() / 1024} KB, ${words.size} words): highlight ${highlight.min()} ms (first ${highlight.first()}), resolve ${resolve.min()} ms (first ${resolve.first()})")
        }
    }
}
