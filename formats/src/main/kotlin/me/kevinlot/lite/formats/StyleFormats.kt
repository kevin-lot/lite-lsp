package me.kevinlot.lite.formats

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * CSS: `var(--x)` → `--x:`; `@import`/`url()` → the file. SCSS adds `$x`, `@mixin`/`@include`, `@function`, `@use`;
 * the indented Sass syntax ([indented]) also `=mixin` / `+mixin`, without braces and semicolons.
 */
open class StyleFormat(id: String, display: String, ext: String, private val scss: Boolean, private val indented: Boolean = false) : Format(id, display, ext) {
    override val blockComment = "/*" to "*/"
    override val lineComment: String? = "//".takeIf { scss }
    override val nameCategories = setOf(Cat.VARIABLE, Cat.WORD, Cat.STRING, Cat.DECL, Cat.FUNCTION)
    override val rules = listOfNotNull(
        Rule(R.BLOCK, Cat.COMMENT),
        Rule("""(?<!:)//[^\n]*""", Cat.COMMENT).takeIf { scss },
        Rule(R.DQ, Cat.STRING),
        Rule(R.SQ, Cat.STRING),
        Rule("""(?<=url\()\s*[^"')\s][^)\s]*""", Cat.STRING),
        Rule("""@[\w-]+""", Cat.KEYWORD),
        Rule("""=\s*[\w-]+""", Cat.DECL, lineStart = true).takeIf { indented },
        Rule("""\+\s*[\w-]+""", Cat.FUNCTION, lineStart = true).takeIf { indented },
        Rule("""--[\w-]+""", Cat.VARIABLE),
        Rule("""\$[\w-]+""", Cat.VARIABLE).takeIf { scss },
        Rule("""#[0-9a-fA-F]{3,8}\b""", Cat.CONSTANT),
        Rule("""!important\b""", Cat.KEYWORD),
        Rule("""[a-zA-Z-]+(?=\s*:[^;{}\n]*(?:;|}|\n|$))""", Cat.KEY),
        Rule("""[\w-]+(?=\()""", Cat.FUNCTION),
        Rule("""[.#][\w-]+""", Cat.TYPE),
        Rule("""::?[\w-]+""", Cat.META),
        Rule("""-?(?:\d+\.?\d*|\.\d+)(?:%|[a-zA-Z]+)?""", Cat.NUMBER),
    )
    override val linker: Linker = object : Linker() {
        override fun links(text: CharSequence, fileName: String): Links {
            val decls = mutableListOf<Decl>()
            val refs = mutableListOf<Ref>()
            PROPERTY.ranges(text).forEach { (r, n) -> decls += Decl(r, "prop:$n") }
            PROPERTY_USE.ranges(text).forEach { (r, n) -> refs += Ref(r, listOf("prop:$n")) }
            if (scss) {
                val declared = VARIABLE.ranges(text).toList()
                val declaredAt = declared.mapTo(HashSet()) { it.first }
                declared.forEach { (r, n) -> decls += Decl(r, "var:$n") }
                VARIABLE_USE.ranges(text).filter { (r, _) -> r !in declaredAt }.forEach { (r, n) -> refs += Ref(r, listOf("var:$n")) }
                MIXIN.ranges(text).forEach { (r, n) -> decls += Decl(r, "mixin:$n") }
                INCLUDE.ranges(text).forEach { (r, n) -> refs += Ref(r, listOf("mixin:$n")) }
                if (indented) {
                    SASS_MIXIN.ranges(text).forEach { (r, n) -> decls += Decl(r, "mixin:$n") }
                    SASS_INCLUDE.ranges(text).forEach { (r, n) -> refs += Ref(r, listOf("mixin:$n")) }
                }
                FUNCTION.ranges(text).forEach { (r, n) -> decls += Decl(r, "fn:$n") }
                // calls of @function: not mixin heads (`@mixin card(`, `=rounded(`, `+rounded(`) nor CSS functions
                CALL.ranges(text).filter { (_, n) -> n !in CSS_FUNCTIONS }.forEach { (r, n) -> refs += Ref(r, listOf("fn:$n")) }
            }
            val paths = (IMPORT.ranges(text) + URL.ranges(text)).filter { (_, p) -> !NOT_A_FILE.containsMatchIn(p) }
                .map { (r, p) -> PathLink(r, p) }.toList()
            return Links(decls, refs, paths)
        }

        /** SCSS partials: `@use "base"` is `_base.scss`. */
        override fun resolvePath(file: PsiFile, path: String): PsiFileSystemItem? {
            super.resolvePath(file, path)?.let { return it }
            if (!scss) return null
            val name = path.substringAfterLast('/')
            val dir = path.removeSuffix(name)
            return listOf("$dir$name.scss", "${dir}_$name.scss", "$dir$name.sass", "${dir}_$name.sass", "${dir}_$name", "$dir$name/_index.scss", "$dir$name/_index.sass", "$dir$name.css")
                .firstNotNullOfOrNull { super.resolvePath(file, it) }
        }

        /** Custom properties are global: every stylesheet of the project, after the file and its imports. */
        override fun scope(file: PsiFile): List<PsiFile> {
            val project = file.project
            val manager = PsiManager.getInstance(project)
            val own = super.scope(file)
            val all = listOf(Css, Scss, Sass).flatMap { f -> FileTypeIndex.getFiles(FMT_FILE_TYPES.getValue(f), GlobalSearchScope.projectScope(project)) }
                .take(500).mapNotNull { manager.findFile(it) }
            return own + all
        }
    }

    private companion object {
        val CALL = Regex("""(?<![@\w$=+-])(?<!@mixin )(?<!@include )(?<!@function )([\w-]+)\(""")
        val FUNCTION = Regex("""@function\s+([\w-]+)""")
        val IMPORT = Regex("""@(?:import|use|forward)\s+["']([^"']+)""")
        val INCLUDE = Regex("""@include\s+([\w-]+)""")
        val MIXIN = Regex("""@mixin\s+([\w-]+)""")
        val NOT_A_FILE = Regex("^(?:[a-z]+:|//|#)")
        val PROPERTY = Regex("""(--[\w-]+)\s*:""")
        val PROPERTY_USE = Regex("""var\(\s*(--[\w-]+)""")
        val SASS_INCLUDE = Regex("""(?m)^\s*\+\s*([\w-]+)""")
        val SASS_MIXIN = Regex("""(?m)^\s*=\s*([\w-]+)""")
        val URL = Regex("""url\(\s*["']?([^"')\s]+)""")
        val VARIABLE = Regex("""\$([\w-]+)\s*:""")
        val VARIABLE_USE = Regex("""\$([\w-]+)""")
    }
}

private val CSS_FUNCTIONS = setOf(
    "var", "calc", "min", "max", "clamp", "url", "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix",
    "linear-gradient", "radial-gradient", "conic-gradient", "repeating-linear-gradient", "translate", "translateX", "translateY", "rotate",
    "scale", "skew", "matrix", "perspective", "cubic-bezier", "steps", "attr", "env", "format", "local", "image-set", "minmax", "repeat",
    "fit-content", "blur", "brightness", "contrast", "drop-shadow", "grayscale", "opacity", "saturate", "sepia", "not", "is", "where", "has",
    "nth-child", "nth-of-type", "lang", "dir", "if", "map-get", "darken", "lighten", "mix", "percentage", "round", "abs",
)

object Css : StyleFormat("Css", "CSS", "css", scss = false)
object Scss : StyleFormat("Scss", "SCSS", "scss", scss = true)
object Sass : StyleFormat("Sass", "Sass", "sass", scss = true, indented = true)
