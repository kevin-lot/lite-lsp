package me.kevinlot.lite.formats.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import me.kevinlot.lite.formats.Cat
import me.kevinlot.lite.formats.Format
import me.kevinlot.lite.formats.Linker
import me.kevinlot.lite.formats.Links
import me.kevinlot.lite.formats.PathLink
import me.kevinlot.lite.formats.Rule

/**
 * `CODEOWNERS`: a literal pattern (no `*`, `?`, `[`) leads to the file or folder it names, from the root of the
 * repository (the file sits there, or in `.github/`, `.gitlab/` or `docs/`).
 */
object Codeowners : Format("Codeowners", "CODEOWNERS", "CODEOWNERS") {
    override val lineComment = "#"
    override val nameCategories = setOf(Cat.KEY)

    private val HOLDERS = setOf(".github", ".gitlab", "docs")
    private val PATTERN = Regex("""(?m)^[ \t]*((?:\\.|[^\s\\#\[])(?:\\.|[^\s\\])*+)""")

    override val rules = listOf(
        Rule("""#[^\n]*""", Cat.COMMENT),
        Rule("""\[[^\]\n]*](?:\[\d+])?""", Cat.SECTION, lineStart = true),
        Rule("""(?:\\.|[^\s\\@#])(?:\\.|[^\s\\])*+""", Cat.KEY, lineStart = true),
        Rule("""@[\w.-]+(?:/[\w.-]+)?""", Cat.META),
        Rule("""[\w.+-]+@[\w.-]+""", Cat.STRING),
    )

    override val linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String) = Links(
            paths = PATTERN.findAll(text).mapNotNull { it.groups[1] }.mapNotNull { pattern ->
                if (pattern.value.any { it in "*?[" }) return@mapNotNull null
                val literal = pattern.value.trim('/')
                if (literal.isEmpty()) return@mapNotNull null
                val start = pattern.range.first + (pattern.value.length - pattern.value.trimStart('/').length)
                PathLink(TextRange(start, start + literal.length), literal.replace("\\", ""))
            }.toList(),
        )

        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            val folder = file.originalFile.virtualFile?.parent ?: return null
            val root = folder.parent?.takeIf { folder.name in HOLDERS } ?: folder
            val target = root.findFileByRelativePath(path) ?: return null
            val manager = PsiManager.getInstance(file.project)
            if (target.isDirectory) return manager.findDirectory(target)
            return manager.findFile(target)
        }
    }
}
