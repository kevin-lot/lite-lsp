package me.kevinlot.lite.proto

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteCStyleCommenter
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.color
import me.kevinlot.lite.proto.lexer.ProtoKeywordTokenType
import me.kevinlot.lite.proto.lexer._ProtoLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import me.kevinlot.lite.proto.parser.PbTypes as E
import me.kevinlot.lite.proto.upstream.ProtoTokenTypes as T

object PbColors {
    val KEYWORD = key("PB_KEYWORD", C.KEYWORD)
    val STRING = key("PB_STRING", C.STRING)
    val NUMBER = key("PB_NUMBER", C.NUMBER)
    val LINE_COMMENT = key("PB_LINE_COMMENT", C.LINE_COMMENT)
    val BLOCK_COMMENT = key("PB_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val TYPE_DECL = key("PB_TYPE_DECL", C.CLASS_NAME)
    val TYPE_REF = key("PB_TYPE_REF", C.CLASS_REFERENCE)
    val FIELD = key("PB_FIELD", C.INSTANCE_FIELD)
    val ENUM_VALUE = key("PB_ENUM_VALUE", C.CONSTANT)
    val METHOD = key("PB_METHOD", C.FUNCTION_DECLARATION)
    val OPTION = key("PB_OPTION", C.METADATA)
    private fun key(n: String, f: TextAttributesKey) = createTextAttributesKey(n, f)
}

class PbSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = FlexAdapter(_ProtoLexer(null))
    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = pack(
        when {
            t is ProtoKeywordTokenType -> PbColors.KEYWORD
            t == T.STRING_LITERAL -> PbColors.STRING
            t == T.INTEGER_LITERAL || t == T.FLOAT_LITERAL -> PbColors.NUMBER
            t == T.LINE_COMMENT -> PbColors.LINE_COMMENT
            t == T.BLOCK_COMMENT -> PbColors.BLOCK_COMMENT
            else -> null
        },
    )
}

class PbSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = PbSyntaxHighlighter()
}

/** Declarations by kind, type names (scalars as keywords), option names. No resolve. */
class PbHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when {
            element is PbDefinition -> holder.color(element.nameIdentifier, when (element.node.elementType) {
                E.MESSAGE_DEFINITION, E.ENUM_DEFINITION, E.SERVICE_DEFINITION, E.GROUP_DEFINITION -> PbColors.TYPE_DECL
                E.ENUM_VALUE -> PbColors.ENUM_VALUE
                E.SERVICE_METHOD, E.SERVICE_STREAM -> PbColors.METHOD
                else -> PbColors.FIELD
            })
            element.node.elementType == E.TYPE_NAME || element.node.elementType == E.MESSAGE_TYPE_NAME ->
                holder.color(element, if (element.text in PbResolver.SCALARS) PbColors.KEYWORD else PbColors.TYPE_REF)
            element.node.elementType == E.OPTION_NAME && element.parent?.node?.elementType != E.OPTION_NAME -> holder.color(element, PbColors.OPTION)
        }
    }
}

class PbBraceMatcher : LiteBraceMatcher(
    Triple(T.LBRACE, T.RBRACE, true), Triple(T.LBRACK, T.RBRACK, false), Triple(T.LPAREN, T.RPAREN, false), Triple(T.LT, T.GT, false),
)

class PbCommenter : LiteCStyleCommenter()

class PbFindUsagesProvider : LiteFindUsagesProvider(
    { FlexAdapter(_ProtoLexer(null)) }, TokenSet.orSet(TokenSet.create(T.IDENTIFIER_LITERAL), KEYWORDS), PbParserDefinition.COMMENTS, TokenSet.create(T.STRING_LITERAL),
) {
    override fun isDeclaration(element: PsiElement) = element is PbDefinition
    override fun kindOf(element: PsiElement) = when (element.node.elementType) {
        E.MESSAGE_DEFINITION -> "message"; E.ENUM_DEFINITION -> "enum"; E.SERVICE_DEFINITION -> "service"; E.GROUP_DEFINITION -> "group"
        E.ONEOF_DEFINITION -> "oneof"; E.ENUM_VALUE -> "enum value"; E.SERVICE_METHOD, E.SERVICE_STREAM -> "rpc"
        else -> "field"
    }
    private companion object {
        val KEYWORDS = TokenSet.create(T.MESSAGE, T.ENUM, T.SERVICE, T.GROUP, T.ONEOF, T.MAP, T.PACKAGE, T.IMPORT, T.OPTION, T.STREAM, T.RPC, T.RETURNS)
    }
}

class PbRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is PbDefinition
}

class PbNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?) = false // keywords are valid protobuf names
    override fun isIdentifier(name: String, project: Project?) = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)
}
