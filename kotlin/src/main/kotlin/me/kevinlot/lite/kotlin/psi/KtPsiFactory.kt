package me.kevinlot.lite.kotlin.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.kotlin.KtFile
import me.kevinlot.lite.kotlin.KtFileType

/** Builds PSI from source snippets (rename, imports). */
class KtPsiFactory(private val project: Project) {
    fun createFile(text: String): KtFile =
        PsiFileFactory.getInstance(project).createFileFromText("dummy.kt", KtFileType, text) as KtFile

    fun createIdentifier(name: String): PsiElement {
        val escaped = if (KtNames.isKeyword(name)) "`$name`" else name
        val fn = PsiTreeUtil.findChildOfType(createFile("fun $escaped() {}"), KtFunction::class.java)
        return fn?.nameIdentifier ?: throw IncorrectOperationException("Invalid identifier: $name")
    }

    fun createImport(path: String): KtImportDirective =
        PsiTreeUtil.findChildOfType(createFile("import $path\n"), KtImportDirective::class.java)
            ?: throw IncorrectOperationException("Invalid import: $path")

    fun createNewline(): PsiElement = createWhiteSpace("\n")

    fun createWhiteSpace(text: String): PsiElement = com.intellij.psi.PsiParserFacade.getInstance(project).createWhiteSpaceFromText(text)
}

object KtNames {
    /** Hard keywords: need backticks to be used as names. */
    val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    fun isKeyword(name: String) = name in KEYWORDS
}
