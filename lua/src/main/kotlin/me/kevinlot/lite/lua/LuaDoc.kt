package me.kevinlot.lite.lua

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType

enum class LuaTag { CLASS, FIELD, PARAM, TYPE, RETURN }

/**
 * An EmmyLua / LuaLS annotation line: `---@class Name[: Parent]`, `---@field name Type`, `---@param name Type`,
 * `---@type Type`, `---@return Type`. Of a type, only the first class name is kept (`Foo|nil`, `Foo[]` → `Foo`).
 */
internal class LuaAnnotation(val tag: LuaTag, val name: TextRange?, val type: TextRange?) {
    companion object {
        private const val NAME = "[A-Za-z_][A-Za-z0-9_.]*"
        private val LINE = Regex(
            """^---+\s*@(class|field|param|type|return)\s+(?:(?:public|protected|private|package)\s+)?($NAME)\??(?:\s*:\s*($NAME)|\s+($NAME))?""",
        )

        fun parse(text: CharSequence): LuaAnnotation? {
            val groups = LINE.find(text)?.groups ?: return null
            val tag = LuaTag.valueOf(groups[1]?.value.orEmpty().uppercase())
            val ranges = groups.map { g -> g?.range?.let { TextRange(it.first, it.last + 1) } }
            return when (tag) {
                LuaTag.CLASS -> LuaAnnotation(tag, ranges[2], ranges[3])
                LuaTag.FIELD, LuaTag.PARAM -> LuaAnnotation(tag, ranges[2], ranges[4])
                LuaTag.TYPE, LuaTag.RETURN -> LuaAnnotation(tag, null, ranges[2])
            }
        }

        /** The leaf of a comment token: annotation lines get a PSI class of their own. */
        fun leaf(type: IElementType, text: CharSequence): LeafElement {
            val annotation = parse(text) ?: return PsiCommentImpl(type, text)
            return when (annotation.tag) {
                LuaTag.CLASS, LuaTag.FIELD -> LuaDocDeclaration(type, text, annotation)
                else -> LuaDocComment(type, text, annotation)
            }
        }
    }
}

/** An annotation line; its type is a reference to a `---@class`. */
open class LuaDocComment internal constructor(type: IElementType, text: CharSequence, private val annotation: LuaAnnotation) : PsiCommentImpl(type, text) {
    val tag: LuaTag get() = annotation.tag
    internal val nameRange: TextRange? get() = annotation.name
    /** The class or field declared, or the parameter annotated. */
    val annotatedName: String? get() = annotation.name?.substring(text)
    val typeName: String? get() = annotation.type?.substring(text)
    override fun getReferences(): Array<PsiReference> = annotation.type?.let { arrayOf(LuaClassReference(this, it)) } ?: PsiReference.EMPTY_ARRAY
    internal fun withText(range: TextRange, replacement: String): PsiElement = replaceWithText(range.replace(text, replacement)).psi
}

/** `---@class Name` or `---@field name Type`: a definition. */
class LuaDocDeclaration internal constructor(type: IElementType, text: CharSequence, annotation: LuaAnnotation) :
    LuaDocComment(type, text, annotation), PsiNamedElement {
    override fun getName(): String? = annotatedName
    override fun setName(name: String): PsiElement = nameRange?.let { withText(it, name) } ?: this
    override fun getTextOffset(): Int = startOffset + (nameRange?.startOffset ?: 0)
    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

class LuaClassReference(element: LuaDocComment, range: TextRange) : LuaReference<LuaDocComment>(element, range) {
    override fun targets(): List<PsiElement> = listOfNotNull(element.typeName?.let { LuaResolve.classOf(element.containingFile, it) })
    override fun handleElementRename(newElementName: String): PsiElement = element.withText(rangeInElement, newElementName)
}
