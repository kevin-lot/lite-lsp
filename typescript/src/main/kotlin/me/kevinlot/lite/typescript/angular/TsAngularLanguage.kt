package me.kevinlot.lite.typescript.angular

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.TemplateLanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateLanguage
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.OuterLanguageElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/*
 * The file of an Angular template: HTML with the template syntax laid over it (the platform's template languages).
 * This language holds `{{ … }}`, `@if (…)`, `@for (…)`, `@let …;`; the rest is the HTML plugin's own tree.
 */

object AngularLanguage : Language("LiteAngular"), TemplateLanguage {
    override fun getDisplayName() = "Angular template (Lite)"
}

object AngularFileType : LanguageFileType(AngularLanguage), TemplateLanguageFileType {
    override fun getName() = "Angular template (Lite)"
    override fun getDescription() = "Angular template (Lite)"
    override fun getDefaultExtension() = "component.html"
    override fun getIcon(): Icon = AllIcons.FileTypes.Html
}

object AngularTokens {
    val BRACES = IElementType("BRACES", AngularLanguage)
    val CONTENT = IElementType("CONTENT", AngularLanguage)
    val DECLARATION = IElementType("DECLARATION", AngularLanguage)
    val EXPRESSION = IElementType("EXPRESSION", AngularLanguage)
    val HEADER = IElementType("HEADER", AngularLanguage)
    val KEYWORD = IElementType("KEYWORD", AngularLanguage)
    val LOOP = IElementType("LOOP", AngularLanguage)
    val OUTER = OuterLanguageElementType("OUTER", AngularLanguage)
    val PUNCTUATION = IElementType("PUNCTUATION", AngularLanguage)
    val TRIGGERS = IElementType("TRIGGERS", AngularLanguage)

    val FILE = IFileElementType(AngularLanguage)
    val FRAGMENT = IElementType("FRAGMENT", AngularLanguage)
    val FRAGMENTS = TokenSet.create(DECLARATION, EXPRESSION, HEADER, LOOP, TRIGGERS)
    val TEMPLATE_DATA = TemplateDataElementType("LITE_ANGULAR_HTML", AngularLanguage, CONTENT, OUTER)
}

/** HTML until `{{` or a block keyword; the state says what the template syntax expects next. */
class AngularLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var text = ""
    private var end = 0
    private var start = 0
    private var stop = 0
    private var state = TEXT
    private var next = TEXT
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        text = buffer.toString()
        end = endOffset
        stop = startOffset
        next = initialState
        advance()
    }

    override fun advance() {
        start = stop
        state = next
        type = null
        if (start < end) lex()
    }

    private fun token(type: IElementType, stop: Int, next: Int) {
        this.type = type
        this.stop = stop.coerceIn(start + 1, end)
        this.next = next
    }

    private fun lex() {
        val c = text[start]
        when (state) {
            INTERPOLATION -> when {
                text.startsWith("}}", start) -> token(AngularTokens.BRACES, start + 2, TEXT)
                else -> token(AngularTokens.EXPRESSION, text.indexOf("}}", start).takeIf { it in start until end } ?: end, INTERPOLATION)
            }
            BLOCK, LOOP, DEFER -> when {
                c.isWhitespace() -> token(TokenType.WHITE_SPACE, blanks(), state)
                c == '(' -> token(AngularTokens.PUNCTUATION, start + 1, state + HEADER_OF)
                else -> content()
            }
            HEADER, LOOP_HEADER, DEFER_HEADER -> when {
                c == ')' -> token(AngularTokens.PUNCTUATION, start + 1, TEXT)
                else -> token(HEADERS.getValue(state), AngularSyntax.closing(text, start, ')') ?: end, state)
            }
            LET -> when {
                c.isWhitespace() -> token(TokenType.WHITE_SPACE, blanks(), LET)
                c == ';' -> token(AngularTokens.PUNCTUATION, start + 1, TEXT)
                else -> token(AngularTokens.DECLARATION, AngularSyntax.closing(text, start, ';') ?: end, LET)
            }
            else -> content()
        }
    }

    private fun content() {
        val keyword = keywordAt(start)
        when {
            text.startsWith("{{", start) -> token(AngularTokens.BRACES, start + 2, INTERPOLATION)
            keyword != null -> token(
                AngularTokens.KEYWORD, start + keyword.length,
                when (keyword) { "@let" -> LET; "@for" -> LOOP; in DEFERRED -> DEFER; else -> BLOCK },
            )
            else -> token(AngularTokens.CONTENT, (start + 1 until end).firstOrNull { text.startsWith("{{", it) || keywordAt(it) != null } ?: end, TEXT)
        }
    }

    private fun keywordAt(at: Int): String? {
        if (text[at] != '@' || text.getOrNull(at - 1)?.let { it.isLetterOrDigit() || it == '@' } == true) return null
        return AngularSyntax.KEYWORD.matchAt(text, at)?.value
    }

    private fun blanks(): Int = (start until end).firstOrNull { !text[it].isWhitespace() } ?: end

    override fun getState(): Int = state
    override fun getTokenType(): IElementType? = type
    override fun getTokenStart(): Int = start
    override fun getTokenEnd(): Int = stop
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    private companion object {
        const val TEXT = 0
        const val INTERPOLATION = 1
        const val BLOCK = 2
        const val LOOP = 3
        const val DEFER = 4
        const val HEADER = 5
        const val LOOP_HEADER = 6
        const val DEFER_HEADER = 7
        const val LET = 8
        const val HEADER_OF = HEADER - BLOCK
        val DEFERRED = setOf("@defer", "@error", "@loading", "@placeholder")
        val HEADERS = mapOf(DEFER_HEADER to AngularTokens.TRIGGERS, HEADER to AngularTokens.HEADER, LOOP_HEADER to AngularTokens.LOOP)
    }
}

class AngularParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            val fragment = builder.takeIf { it.tokenType in AngularTokens.FRAGMENTS }?.mark()
            builder.advanceLexer()
            fragment?.done(AngularTokens.FRAGMENT)
        }
        file.done(root)
        return builder.treeBuilt
    }
}

class AngularParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = AngularLexer()
    override fun createParser(project: Project?): PsiParser = AngularParser()
    override fun getFileNodeType(): IFileElementType = AngularTokens.FILE
    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = AngularFragment(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = AngularFile(viewProvider)
}

class AngularFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AngularLanguage) {
    override fun getFileType(): FileType = AngularFileType
    override fun toString(): String = "Angular template"
}

/** An expression of the template, read as TypeScript (see [AngularInjector]). */
class AngularFragment(node: ASTNode) : ASTWrapperPsiElement(node), PsiLanguageInjectionHost {
    val kind: IElementType get() = node.firstChildNode.elementType

    override fun isValidHost(): Boolean = true
    override fun createLiteralTextEscaper(): LiteralTextEscaper<AngularFragment> = LiteralTextEscaper.createSimple(this)
    override fun updateText(text: String): PsiLanguageInjectionHost {
        (node.firstChildNode as LeafElement).replaceWithText(text)
        return this
    }
}

class AngularViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(file: VirtualFile, language: Language?, manager: PsiManager, eventSystemEnabled: Boolean): FileViewProvider =
        AngularViewProvider(manager, file, eventSystemEnabled)
}

class AngularViewProvider(manager: PsiManager, file: VirtualFile, eventSystemEnabled: Boolean) :
    MultiplePsiFilesPerDocumentFileViewProvider(manager, file, eventSystemEnabled), TemplateLanguageFileViewProvider {
    override fun getBaseLanguage(): Language = AngularLanguage
    override fun getTemplateDataLanguage(): Language = HTMLLanguage.INSTANCE
    override fun getLanguages(): Set<Language> = setOf(AngularLanguage, HTMLLanguage.INSTANCE)
    override fun cloneInner(copy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider = AngularViewProvider(manager, copy, false)

    override fun createFile(language: Language): PsiFile? {
        val file = LanguageParserDefinitions.INSTANCE.forLanguage(language)?.createFile(this) ?: return null
        if (language == HTMLLanguage.INSTANCE) (file as PsiFileImpl).contentElementType = AngularTokens.TEMPLATE_DATA
        return file
    }
}

class AngularHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = AngularLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        AngularTokens.BRACES, AngularTokens.PUNCTUATION -> BRACES
        AngularTokens.KEYWORD -> KEYWORD
        else -> TextAttributesKey.EMPTY_ARRAY
    }

    private companion object {
        val BRACES = arrayOf(DefaultLanguageHighlighterColors.BRACES)
        val KEYWORD = arrayOf(DefaultLanguageHighlighterColors.KEYWORD)
    }
}

class AngularHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = AngularHighlighter()
}

/** The HTML between the template syntax keeps the colors of HTML. */
class AngularEditorHighlighterProvider : EditorHighlighterProvider {
    override fun getEditorHighlighter(project: Project?, fileType: FileType, virtualFile: VirtualFile?, colors: EditorColorsScheme): EditorHighlighter =
        LayeredLexerEditorHighlighter(AngularHighlighter(), colors).apply {
            SyntaxHighlighterFactory.getSyntaxHighlighter(HTMLLanguage.INSTANCE, project, virtualFile)
                ?.let { registerLayer(AngularTokens.CONTENT, LayerDescriptor(it, "")) }
        }
}
