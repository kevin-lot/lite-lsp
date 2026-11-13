package me.kevinlot.lite.shell

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** POSIX sh, bash and zsh: one language (their common syntax; zsh/bash extensions are tolerated). */
object ShLanguage : Language("LiteShell") {
    private fun readResolve(): Any = ShLanguage
    override fun getDisplayName(): String = "Shell"
}

object ShFileType : LanguageFileType(ShLanguage) {
    override fun getName(): String = "Shell (Lite)"
    override fun getDisplayName(): String = "Shell"
    override fun getDescription(): String = "Shell script"
    override fun getDefaultExtension(): String = "sh"
    override fun getIcon(): Icon = ICON
    private val ICON: Icon by lazy { IconLoader.getIcon("/icons/shell.svg", ShFileType::class.java) }
}

class ShFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ShLanguage) {
    override fun getFileType() = ShFileType
    override fun toString(): String = "Shell file"
}

class ShTokenType(debugName: String) : IElementType(debugName, ShLanguage)

object ShTokens {
    private fun t(name: String) = ShTokenType(name)
    val WORD = t("WORD")
    val ASSIGN_NAME = t("ASSIGN_NAME")         // NAME= / NAME+= / NAME[i]= at the start of a word
    val VAR = t("VAR")                         // $name, $1, $@
    val VAR_NAME = t("VAR_NAME")               // name inside ${…} and $((…))
    val PARAM_START = t("\${"); val PARAM_END = t("PARAM_END"); val PARAM_TEXT = t("PARAM_TEXT"); val PARAM_OP = t("PARAM_OP")
    val SUBST_START = t("\$("); val SUBST_END = t("SUBST_END")   // $( … ) and <( … ) / >( … )
    val ARITH_START = t("\$(("); val ARITH_END = t("))"); val ARITH_OP = t("ARITH_OP")
    val BACKTICK_START = t("`"); val BACKTICK_END = t("BACKTICK_END")
    val STRING_SINGLE = t("STRING_SINGLE")     // '…' and $'…'
    val DQUOTE_START = t("\""); val DQUOTE_END = t("DQUOTE_END"); val STRING_TEXT = t("STRING_TEXT")
    val HEREDOC_OP = t("<<"); val HEREDOC_DELIM = t("HEREDOC_DELIM"); val HEREDOC_TEXT = t("HEREDOC_TEXT"); val HEREDOC_END = t("HEREDOC_END")
    val NUMBER = t("NUMBER")
    val COMMENT = t("COMMENT")
    val NEWLINE = t("NEWLINE")
    val SEMI = t(";"); val CASE_END = t(";;"); val AMP = t("&"); val AND_AND = t("&&"); val OR_OR = t("||"); val PIPE = t("|")
    val LPAR = t("("); val RPAR = t(")"); val LBRACE = t("{"); val RBRACE = t("}"); val TEST_START = t("[["); val TEST_END = t("]]")
    val REDIRECT = t("REDIRECT")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    val KEYWORDS: Map<String, ShTokenType> = listOf(
        "if", "then", "else", "elif", "fi", "for", "in", "while", "until", "do", "done", "case", "esac", "function", "select", "time", "coproc",
    ).associateWith { t(it.uppercase() + "_KEYWORD") }

    fun kw(text: String): ShTokenType = KEYWORDS.getValue(text)

    val KEYWORD_SET = TokenSet.create(*KEYWORDS.values.toTypedArray())
    val COMMENTS = TokenSet.create(COMMENT)
    val WHITESPACES = TokenSet.create(TokenType.WHITE_SPACE)
    val STRINGS = TokenSet.create(STRING_SINGLE, STRING_TEXT, HEREDOC_TEXT)
    /** Parts of a word: adjacent ones (no blank between) form one shell word. */
    val WORD_PARTS = TokenSet.orSet(
        TokenSet.create(WORD, ASSIGN_NAME, VAR, PARAM_START, SUBST_START, ARITH_START, BACKTICK_START, STRING_SINGLE, DQUOTE_START, NUMBER, LBRACE, RBRACE, TEST_START, TEST_END),
        KEYWORD_SET,
    )
}
