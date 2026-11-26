package me.kevinlot.lite.dart

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import me.kevinlot.lite.core.LiteBraceMatcher
import me.kevinlot.lite.core.LiteCStyleCommenter
import me.kevinlot.lite.core.LiteFindUsagesProvider
import me.kevinlot.lite.core.LiteNamesValidator
import me.kevinlot.lite.core.color
import me.kevinlot.lite.core.isA
import me.kevinlot.lite.dart.upstream.DartLexer
import me.kevinlot.lite.dart.upstream.DartTokenTypes as T
import me.kevinlot.lite.dart.upstream.DartTokenTypesSets as S

object DartColors {
    val ANNOTATION = key("DART_ANNOTATION", C.METADATA)
    val BLOCK_COMMENT = key("DART_BLOCK_COMMENT", C.BLOCK_COMMENT)
    val BRACES = key("DART_BRACES", C.BRACES)
    val BRACKETS = key("DART_BRACKETS", C.BRACKETS)
    val CLASS = key("DART_CLASS", C.CLASS_NAME)
    val CONSTANT = key("DART_CONSTANT", C.CONSTANT)
    val DOC_COMMENT = key("DART_DOC_COMMENT", C.DOC_COMMENT)
    val FIELD = key("DART_FIELD", C.INSTANCE_FIELD)
    val FUNCTION_CALL = key("DART_FUNCTION_CALL", C.FUNCTION_CALL)
    val FUNCTION_DECL = key("DART_FUNCTION_DECL", C.FUNCTION_DECLARATION)
    val KEYWORD = key("DART_KEYWORD", C.KEYWORD)
    val LINE_COMMENT = key("DART_LINE_COMMENT", C.LINE_COMMENT)
    val NUMBER = key("DART_NUMBER", C.NUMBER)
    val OPERATOR = key("DART_OPERATOR", C.OPERATION_SIGN)
    val PARAMETER = key("DART_PARAMETER", C.PARAMETER)
    val PARENS = key("DART_PARENS", C.PARENTHESES)
    val STRING = key("DART_STRING", C.STRING)
    val TEMPLATE = key("DART_TEMPLATE", C.VALID_STRING_ESCAPE)
    val TYPE = key("DART_TYPE", C.CLASS_REFERENCE)
    private fun key(name: String, fallback: TextAttributesKey) = createTextAttributesKey(name, fallback)
}

class DartSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = DartLexer()
    override fun getTokenHighlights(type: IElementType): Array<TextAttributesKey> = pack(
        when (type) {
            in S.RESERVED_WORDS -> DartColors.KEYWORD
            S.SINGLE_LINE_COMMENT -> DartColors.LINE_COMMENT
            S.MULTI_LINE_COMMENT -> DartColors.BLOCK_COMMENT
            S.SINGLE_LINE_DOC_COMMENT, S.MULTI_LINE_DOC_COMMENT -> DartColors.DOC_COMMENT
            in S.STRINGS -> DartColors.STRING
            T.SHORT_TEMPLATE_ENTRY_START, T.LONG_TEMPLATE_ENTRY_START, T.LONG_TEMPLATE_ENTRY_END -> DartColors.TEMPLATE
            T.NUMBER, T.HEX_NUMBER -> DartColors.NUMBER
            T.LBRACE, T.RBRACE -> DartColors.BRACES
            T.LPAREN, T.RPAREN -> DartColors.PARENS
            T.LBRACKET, T.RBRACKET -> DartColors.BRACKETS
            in S.OPERATORS -> DartColors.OPERATOR
            else -> null
        },
    )
}

class DartSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = DartSyntaxHighlighter()
}

/** Structure only (no resolve): declarations, called names, types, annotations, and the keywords Dart also accepts as names. */
class DartHighlightingAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when {
            element is DartName -> declarationColor(element)?.let { holder.color(element, it) }
            element is DartRef && element.isName -> referenceColor(element)?.let { holder.color(element, it) }
            element.elementType in S.BUILT_IN_IDENTIFIERS && !element.parent.isA(T.ID) -> holder.color(element, DartColors.KEYWORD)
            element.isA(T.AT) && element.parent.isA(T.METADATA) -> holder.color(element, DartColors.ANNOTATION)
        }
    }

    private fun declarationColor(name: DartName): TextAttributesKey? = when (name.declaration.elementType) {
        in DartResolver.CLASSES, T.EXTENSION_DECLARATION, T.FUNCTION_TYPE_ALIAS, T.TYPE_PARAMETER -> DartColors.CLASS
        T.ENUM_CONSTANT_DECLARATION -> DartColors.CONSTANT
        in FUNCTIONS -> DartColors.FUNCTION_DECL
        T.SIMPLE_FORMAL_PARAMETER, T.FUNCTION_FORMAL_PARAMETER -> DartColors.PARAMETER
        T.VAR_ACCESS_DECLARATION, T.VAR_DECLARATION_LIST_PART -> DartColors.FIELD.takeIf { !name.isLocal }
        else -> null
    }

    private fun referenceColor(ref: DartRef): TextAttributesKey? {
        val whole = ref.parent.takeIf { it.isA(T.REFERENCE_EXPRESSION) && it.lastChild == ref } ?: ref
        val owner = whole.parent
        return when {
            ref.isA(T.PARAMETER_NAME_REFERENCE_EXPRESSION) -> DartColors.PARAMETER
            owner.isA(T.METADATA) -> DartColors.ANNOTATION
            owner.isA(T.SIMPLE_TYPE) -> DartColors.TYPE
            owner.isA(T.CALL_EXPRESSION) && owner.firstChild == whole -> DartColors.FUNCTION_CALL
            else -> null
        }
    }

    private companion object {
        val FUNCTIONS = setOf(
            T.FACTORY_CONSTRUCTOR_DECLARATION, T.FUNCTION_DECLARATION_WITH_BODY, T.FUNCTION_DECLARATION_WITH_BODY_OR_NATIVE, T.GETTER_DECLARATION,
            T.METHOD_DECLARATION, T.NAMED_CONSTRUCTOR_DECLARATION, T.SETTER_DECLARATION,
        )
    }
}

class DartBraceMatcher : LiteBraceMatcher(Triple(T.LBRACE, T.RBRACE, true), Triple(T.LPAREN, T.RPAREN, false), Triple(T.LBRACKET, T.RBRACKET, false))

class DartCommenter : LiteCStyleCommenter()

class DartFindUsagesProvider : LiteFindUsagesProvider(::DartLexer, TokenSet.create(T.IDENTIFIER), S.COMMENTS, S.STRINGS) {
    override fun isDeclaration(element: PsiElement) = element is DartName
    override fun kindOf(element: PsiElement) = when ((element as? DartName)?.declaration?.elementType) {
        T.CLASS_DEFINITION -> "class"
        T.ENUM_DEFINITION -> "enum"
        T.ENUM_CONSTANT_DECLARATION -> "enum value"
        T.EXTENSION_DECLARATION -> "extension"
        T.EXTENSION_TYPE_DECLARATION -> "extension type"
        T.MIXIN_DECLARATION -> "mixin"
        T.RECORD_FIELD, T.RECORD_TYPE_NAMED_FIELD -> "record field"
        T.FUNCTION_TYPE_ALIAS -> "typedef"
        T.TYPE_PARAMETER -> "type parameter"
        T.IMPORT_STATEMENT -> "import prefix"
        T.GETTER_DECLARATION -> "getter"
        T.SETTER_DECLARATION -> "setter"
        T.METHOD_DECLARATION -> "method"
        T.FACTORY_CONSTRUCTOR_DECLARATION, T.NAMED_CONSTRUCTOR_DECLARATION -> "constructor"
        T.FUNCTION_DECLARATION_WITH_BODY, T.FUNCTION_DECLARATION_WITH_BODY_OR_NATIVE -> "function"
        T.SIMPLE_FORMAL_PARAMETER, T.FUNCTION_FORMAL_PARAMETER -> "parameter"
        else -> "variable"
    }
}

class DartRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is DartName && element.useScope is LocalSearchScope
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is DartName
}

/** The constructors of a class bear its name: they are renamed with it. The label of a record literal renames the field of its type. */
class DartRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean =
        element is DartName && (element.declaration.elementType in DartResolver.CLASSES || element.declaration.isA(T.RECORD_FIELD))

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement = (element as? DartName)?.reference?.resolve() ?: element

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        val owner = (element as? DartName)?.declaration ?: return
        val members = (owner.child(T.CLASS_BODY)?.child(T.CLASS_MEMBERS) ?: owner.child(T.CLASS_MEMBERS))?.children.orEmpty()
        members.filter { it.isA(T.METHOD_DECLARATION) || it.isA(T.NAMED_CONSTRUCTOR_DECLARATION) || it.isA(T.FACTORY_CONSTRUCTOR_DECLARATION) }
            .mapNotNull { it.child(T.COMPONENT_NAME) }.filter { it.text == element.text }.forEach { allRenames[it] = newName }
    }
}

class DartNamesValidator : LiteNamesValidator(KEYWORDS) {
    private companion object {
        val KEYWORDS = setOf(
            "assert", "break", "case", "catch", "class", "const", "continue", "default", "do", "else", "enum", "extends", "false", "final", "finally", "for",
            "if", "in", "is", "new", "null", "rethrow", "return", "super", "switch", "this", "throw", "true", "try", "var", "void", "while", "with",
        )
    }
}
