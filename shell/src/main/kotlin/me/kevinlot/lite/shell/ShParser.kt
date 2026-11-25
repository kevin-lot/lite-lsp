package me.kevinlot.lite.shell

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.shell.ShTokens as T

class ShElementType(debugName: String) : IElementType(debugName, ShLanguage)

object ShElementTypes {
    private fun t(name: String) = ShElementType(name)
    val FILE = IFileElementType("LITE_SHELL_FILE", ShLanguage)
    val FUNCTION_DEF = t("FUNCTION_DEF")
    val VAR_DECL = t("VAR_DECL")             // NAME=value, local NAME, read NAME, for NAME in, (( NAME = … )), ${NAME:=…}
    val VAR_REF = t("VAR_REF")               // $NAME, NAME in $(( ))
    val PARAM_EXPANSION = t("PARAM_EXPANSION") // ${NAME…}
    val COMMAND = t("COMMAND")
    val COMMAND_NAME = t("COMMAND_NAME")
    val SOURCE_PATH = t("SOURCE_PATH")       // the file of `source x` / `. x`
    val WORD = t("WORD")
    val STRING = t("STRING")                 // "…"
    val SUBSTITUTION = t("SUBSTITUTION")     // $( … ), ` … `, <( … )
    val ARITH = t("ARITH")
    val HEREDOC = t("HEREDOC")
    val IF = t("IF"); val LOOP = t("LOOP"); val CASE = t("CASE"); val CASE_ITEM = t("CASE_ITEM")
    val GROUP = t("GROUP"); val SUBSHELL = t("SUBSHELL"); val TEST = t("TEST")
}

private val E = ShElementTypes

/** Commands, compound commands, functions, assignments and the words they are made of (expansions included). */
class ShParser : PsiParser {
    private lateinit var b: PsiBuilder
    private var pendingHeredocs = 0

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        b = builder
        val m = b.mark()
        while (!b.eof()) {
            val before = b.currentOffset
            list(TokenSet.EMPTY)
            if (b.currentOffset == before) { b.error("command expected"); b.advanceLexer() }
        }
        m.done(root)
        return b.treeBuilt
    }

    private fun at(t: IElementType) = b.tokenType == t
    private fun eat(t: IElementType): Boolean { if (!at(t)) return false; b.advanceLexer(); return true }
    private fun expect(t: IElementType, what: String) { if (!eat(t)) b.error("$what expected") }
    private fun wordText(): String? = b.tokenText
    private fun isWordPart() = b.tokenType in T.WORD_PARTS
    /** No blank between the previous token and this one: still the same shell word. */
    private fun adjacent(): Boolean = b.rawLookup(-1).let { it != T.WHITE_SPACE && it != T.COMMENT }

    // ---------------------------------------------------------------- lists

    private fun list(stop: TokenSet) {
        while (!b.eof()) {
            separators()
            if (b.eof() || b.tokenType in stop || b.tokenType in CLOSERS) return
            val before = b.currentOffset
            andOr()
            if (b.currentOffset == before) {
                if (b.tokenType in stop || b.tokenType in CLOSERS) return
                b.error("command expected"); b.advanceLexer()
            }
        }
    }

    private fun separators() {
        while (true) {
            when {
                at(T.NEWLINE) -> { b.advanceLexer(); heredocBodies() }
                at(T.SEMI) || at(T.AMP) -> b.advanceLexer()
                else -> return
            }
        }
    }

    private fun heredocBodies() {
        while (pendingHeredocs > 0 && !b.eof()) {
            pendingHeredocs--
            val m = b.mark()
            while (!b.eof() && !at(T.HEREDOC_END)) expansionOrToken()
            eat(T.HEREDOC_END)
            m.done(E.HEREDOC)
        }
    }

    private fun andOr() {
        pipeline()
        while (at(T.AND_AND) || at(T.OR_OR) || at(T.PIPE)) {
            b.advanceLexer()
            while (at(T.NEWLINE)) { b.advanceLexer(); heredocBodies() }
            pipeline()
        }
    }

    private fun pipeline() {
        if (at(T.WORD) && wordText() == "!") b.advanceLexer()
        if (at(T.kw("time"))) b.advanceLexer()
        command()
    }

    // ---------------------------------------------------------------- commands

    private fun command() {
        val t = b.tokenType
        when {
            t == T.kw("if") -> ifCommand()
            t == T.kw("for") || t == T.kw("select") -> forCommand()
            t == T.kw("while") || t == T.kw("until") -> compound(E.LOOP) { list(STOP_DO); expectKw("do"); list(STOP_DONE); expectKw("done") }
            t == T.kw("case") -> caseCommand()
            t == T.kw("function") -> functionDef()
            t == T.kw("coproc") -> { b.advanceLexer(); command() }
            t == T.LBRACE -> compound(E.GROUP) { list(STOP_RBRACE); expect(T.RBRACE, "'}'") }
            t == T.LPAR -> compound(E.SUBSHELL) { list(STOP_RPAR); expect(T.RPAR, "')'") }
            t == T.TEST_START -> compound(E.TEST) {
                // conditions may continue on the next line after `&&` / `||`
                while (!b.eof() && !at(T.TEST_END)) testWordOrToken()
                expect(T.TEST_END, "']]'")
            }
            t == T.ARITH_START -> { val m = b.mark(); arithmetic(); redirections(); m.done(E.COMMAND) }
            (at(T.WORD) || t in T.KEYWORD_SET) && b.lookAhead(1) == T.LPAR && b.lookAhead(2) == T.RPAR -> functionDef()
            else -> simpleCommand()
        }
    }

    private inline fun compound(type: IElementType, body: () -> Unit) {
        val m = b.mark()
        b.advanceLexer()
        body()
        redirections()
        m.done(type)
    }

    private fun expectKw(kw: String) { if (!eat(T.kw(kw))) b.error("'$kw' expected") }

    private fun ifCommand() = compound(E.IF) {
        list(STOP_THEN); expectKw("then")
        list(STOP_IF_BODY)
        while (at(T.kw("elif"))) { b.advanceLexer(); list(STOP_THEN); expectKw("then"); list(STOP_IF_BODY) }
        if (eat(T.kw("else"))) list(STOP_FI)
        expectKw("fi")
    }

    /** `for NAME in words; do …; done`, `for ((…)); do …; done`, `select NAME in …`. */
    private fun forCommand() = compound(E.LOOP) {
        forHeader()
        separators()
        forBody()
    }

    private fun forHeader() {
        if (at(T.ARITH_START)) return arithmetic()
        val v = b.mark()
        name("name")
        v.done(E.VAR_DECL)
        while (at(T.NEWLINE)) b.advanceLexer()
        if (eat(T.kw("in"))) while (!b.eof() && !at(T.SEMI) && !at(T.NEWLINE) && !at(T.kw("do"))) wordOrToken()
    }

    private fun forBody() {
        if (at(T.LBRACE)) { b.advanceLexer(); list(STOP_RBRACE); expect(T.RBRACE, "'}'"); return } // zsh short form
        expectKw("do"); list(STOP_DONE); expectKw("done")
    }

    private fun caseCommand() = compound(E.CASE) {
        if (isWordPart()) word()
        while (at(T.NEWLINE)) b.advanceLexer()
        expectKw("in")
        while (!b.eof()) {
            separators()
            if (at(T.kw("esac")) || b.eof()) break
            val item = b.mark()
            eat(T.LPAR)
            while (!b.eof() && !at(T.RPAR) && !at(T.NEWLINE)) wordOrToken()
            expect(T.RPAR, "')'")
            list(STOP_CASE_ITEM)
            eat(T.CASE_END)
            item.done(E.CASE_ITEM)
        }
        expectKw("esac")
    }

    /** `function name { … }`, `function name() { … }`, `name() { … }`. */
    private fun functionDef() {
        val m = b.mark()
        eat(T.kw("function"))
        name("function name")
        if (eat(T.LPAR)) expect(T.RPAR, "')'")
        while (at(T.NEWLINE)) b.advanceLexer()
        command()
        m.done(E.FUNCTION_DEF)
    }

    /** A name, reserved words included: taken as a plain word. */
    private fun name(what: String) {
        if (!isWordPart()) { b.error("$what expected"); return }
        b.remapCurrentToken(T.WORD)
        b.advanceLexer()
    }

    private fun redirections() {
        while (true) {
            when {
                at(T.REDIRECT) -> { b.advanceLexer(); if (isWordPart()) word() }
                at(T.HEREDOC_OP) -> { b.advanceLexer(); if (eat(T.HEREDOC_DELIM)) pendingHeredocs++ }
                else -> return
            }
        }
    }

    private fun simpleCommand() {
        val m = b.mark()
        while (true) {
            when {
                at(T.ASSIGN_NAME) -> assignment()
                at(T.REDIRECT) || at(T.HEREDOC_OP) -> redirections()
                else -> break
            }
        }
        if (isWordPart()) {
            val name = when { at(T.WORD) -> wordText(); else -> null }
            val n = b.mark()
            word()
            n.done(E.COMMAND_NAME)
            arguments(name)
        }
        m.done(E.COMMAND)
    }

    private fun arguments(command: String?) {
        var first = true
        var option: String? = null
        while (!b.eof()) {
            when {
                at(T.REDIRECT) || at(T.HEREDOC_OP) -> redirections()
                !isWordPart() -> return
                command in ASSIGN_BUILTINS && at(T.ASSIGN_NAME) -> assignment()
                declaresName(command, first, option) && at(T.WORD) && NAME.matches(wordText() ?: "") && !nextIsAdjacentPart() -> declaration()
                (command == "source" || command == ".") && first -> { val s = b.mark(); word(); s.done(E.SOURCE_PATH) }
                else -> {
                    option = wordText()?.takeIf { at(T.WORD) && it.startsWith("-") }
                    word()
                    first = false
                    continue
                }
            }
            first = false
            option = null
        }
    }

    /** Whether a bare name here is a variable the command sets; [option] is the option word just before it. */
    private fun declaresName(command: String?, first: Boolean, option: String?): Boolean = when (command) {
        "getopts" -> !first
        "printf" -> option == "-v"
        in NAME_BUILTINS -> option == null || option.last() !in VALUE_OPTIONS[command].orEmpty()
        else -> false
    }

    private fun nextIsAdjacentPart(): Boolean = b.rawLookup(1) in T.WORD_PARTS

    private fun declaration() {
        val v = b.mark()
        b.advanceLexer()
        v.done(E.VAR_DECL)
    }

    private fun assignment() {
        val m = b.mark()
        b.advanceLexer()
        when {
            at(T.LPAR) && adjacent() -> {
                b.advanceLexer()
                while (!b.eof() && !at(T.RPAR)) wordOrToken()
                expect(T.RPAR, "')'")
            }
            isWordPart() && adjacent() -> word()
        }
        m.done(E.VAR_DECL)
    }

    // ---------------------------------------------------------------- words

    private fun word() {
        val m = b.mark()
        part()
        while (!b.eof() && isWordPart() && adjacent()) part()
        m.done(E.WORD)
    }

    private fun wordOrToken() {
        if (isWordPart()) return word()
        b.advanceLexer()
    }

    private fun testWordOrToken() {
        if (isWordPart() && !at(T.TEST_START)) return word()
        b.advanceLexer()
    }

    private fun expansionOrToken() {
        if (b.tokenType in EXPANSIONS) return part()
        b.advanceLexer()
    }

    private fun part() {
        when (b.tokenType) {
            T.VAR -> { val r = b.mark(); b.advanceLexer(); r.done(E.VAR_REF) }
            T.PARAM_START -> {
                val r = b.mark()
                b.advanceLexer()
                if (at(T.VAR_NAME) && assignsDefault()) declaration()
                while (!b.eof() && !at(T.PARAM_END)) expansionOrToken()
                expect(T.PARAM_END, "'}'")
                r.done(E.PARAM_EXPANSION)
            }
            T.SUBST_START -> { val m = b.mark(); b.advanceLexer(); list(STOP_SUBST); expect(T.SUBST_END, "')'"); m.done(E.SUBSTITUTION) }
            T.BACKTICK_START -> { val m = b.mark(); b.advanceLexer(); list(STOP_BACKTICK); expect(T.BACKTICK_END, "'`'"); m.done(E.SUBSTITUTION) }
            T.ARITH_START -> arithmetic()
            T.DQUOTE_START -> {
                val m = b.mark()
                b.advanceLexer()
                while (!b.eof() && !at(T.DQUOTE_END)) expansionOrToken()
                expect(T.DQUOTE_END, "'\"'")
                m.done(E.STRING)
            }
            in T.KEYWORD_SET -> { b.remapCurrentToken(T.WORD); b.advanceLexer() }
            else -> b.advanceLexer()
        }
    }

    private fun arithmetic() {
        val m = b.mark()
        b.advanceLexer()
        while (!b.eof() && !at(T.ARITH_END)) {
            when {
                at(T.VAR_NAME) && assignsInArithmetic() -> declaration()
                at(T.VAR_NAME) -> { val r = b.mark(); b.advanceLexer(); r.done(E.VAR_REF) }
                else -> expansionOrToken()
            }
        }
        expect(T.ARITH_END, "'))'")
        m.done(E.ARITH)
    }

    /** At a name of `(( … ))`: the operator after it is `=`, `+=`, `<<=`… and not a comparison. */
    private fun assignsInArithmetic(): Boolean {
        val m = b.mark()
        b.advanceLexer()
        val operator = generateSequence { b.tokenText?.takeIf { at(T.ARITH_OP) }?.also { b.advanceLexer() } }.take(4).joinToString("")
        m.rollbackTo()
        return ARITH_ASSIGNMENT.containsMatchIn(operator)
    }

    /** At the name of `${NAME:=default}` / `${NAME=default}`. */
    private fun assignsDefault(): Boolean {
        if (b.rawLookup(1) != T.PARAM_TEXT || !NAME.matches(wordText() ?: "")) return false
        val text = b.originalText.subSequence(b.rawTokenTypeStart(1), b.rawTokenTypeStart(2))
        return text.startsWith(":=") || text.startsWith("=")
    }

    private companion object {
        fun kws(vararg k: String): TokenSet = TokenSet.create(*k.map { T.kw(it) }.toTypedArray())
        val STOP_THEN = kws("then")
        val STOP_IF_BODY = kws("elif", "else", "fi")
        val STOP_FI = kws("fi")
        val STOP_DO = kws("do")
        val STOP_DONE = kws("done")
        val STOP_RBRACE = TokenSet.create(T.RBRACE)
        val STOP_RPAR = TokenSet.create(T.RPAR)
        val STOP_SUBST = TokenSet.create(T.SUBST_END)
        val STOP_BACKTICK = TokenSet.create(T.BACKTICK_END)
        val STOP_CASE_ITEM = TokenSet.orSet(TokenSet.create(T.CASE_END), kws("esac"))
        /** Never the start of a command: they close an enclosing construct. */
        val CLOSERS = TokenSet.orSet(
            TokenSet.create(T.RPAR, T.SUBST_END, T.BACKTICK_END, T.CASE_END, T.ARITH_END, T.TEST_END, T.PARAM_END, T.DQUOTE_END),
            kws("then", "elif", "else", "fi", "do", "done", "esac"),
        )
        val EXPANSIONS = TokenSet.create(T.VAR, T.PARAM_START, T.SUBST_START, T.BACKTICK_START, T.ARITH_START, T.DQUOTE_START)
        val DECLARE_BUILTINS = setOf("local", "declare", "typeset", "export", "readonly", "integer", "float")
        val ASSIGN_BUILTINS = DECLARE_BUILTINS + "let"
        /** Options taking a value, which is then not a variable name: `read -p prompt NAME`. */
        val VALUE_OPTIONS = mapOf("read" to "pdntu", "mapfile" to "dnOsuCc", "readarray" to "dnOsuCc")
        val NAME_BUILTINS = DECLARE_BUILTINS + VALUE_OPTIONS.keys
        val ARITH_ASSIGNMENT = Regex("^([-+*/%&|^]|<<|>>)?=(?!=)")
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

class ShParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ShLexer()
    override fun createParser(project: Project?): PsiParser = ShParser()
    override fun getFileNodeType(): IFileElementType = E.FILE
    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = T.STRINGS
    override fun getWhitespaceTokens(): TokenSet = T.WHITESPACES
    override fun createElement(node: ASTNode): PsiElement = ShPsiFactoryImpl.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ShFile(viewProvider)
}
