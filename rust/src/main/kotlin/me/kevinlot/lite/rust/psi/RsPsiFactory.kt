package me.kevinlot.lite.rust.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import me.kevinlot.lite.rust.RsFile
import me.kevinlot.lite.rust.RsFileType

/** Builds PSI from source snippets (used by rename). */
class RsPsiFactory(private val project: Project) {

    fun createFile(text: String): RsFile =
        PsiFileFactory.getInstance(project).createFileFromText("dummy.rs", RsFileType, text) as RsFile

    fun createIdentifier(name: String): PsiElement {
        val fn = PsiTreeUtil.findChildOfType(createFile("fn $name() {}"), RsFunction::class.java)
            ?: throw IncorrectOperationException("Invalid identifier: $name")
        return fn.nameIdentifier ?: throw IncorrectOperationException("Invalid identifier: $name")
    }

    fun createUseItem(path: String): RsUseItem =
        PsiTreeUtil.findChildOfType(createFile("use $path;"), RsUseItem::class.java)
            ?: throw IncorrectOperationException("Invalid use path: $path")

    fun createNewline(): PsiElement = createFile("\n").firstChild
}
