package me.kevinlot.lite.groovy

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
import me.kevinlot.lite.groovy.GroovyTokens as T

class GroovyElementType(debugName: String) : IElementType(debugName, GroovyLanguage)

object GroovyElementTypes {
    val FILE = IFileElementType("LITE_GROOVY_FILE", GroovyLanguage)
    private fun e(n: String) = GroovyElementType(n)
    val PACKAGE = e("PACKAGE"); val IMPORT = e("IMPORT")
    val QUALIFIED_NAME = e("QUALIFIED_NAME")      // a.b.C of a package or an import
    val MODIFIERS = e("MODIFIERS"); val ANNOTATION = e("ANNOTATION")
    val CLASS = e("CLASS")                        // class, interface, trait, enum, record, @interface
    val CLASS_BODY = e("CLASS_BODY"); val ENUM_CONSTANT = e("ENUM_CONSTANT")
    val METHOD = e("METHOD")                      // method or constructor
    val PARAMS = e("PARAMS"); val PARAM = e("PARAM")
    val VAR_DECL = e("VAR_DECL")                  // def a = 1, b / String s / def (a, b) = …
    val VARIABLE = e("VARIABLE")                  // one name of a declaration, a loop variable, a catch parameter
    val TYPE = e("TYPE"); val TYPE_ARGS = e("TYPE_ARGS")
    val BLOCK = e("BLOCK"); val CLOSURE = e("CLOSURE")
    val IF = e("IF"); val WHILE = e("WHILE"); val DO_WHILE = e("DO_WHILE"); val FOR = e("FOR"); val SWITCH = e("SWITCH"); val CASE = e("CASE")
    val TRY = e("TRY"); val CATCH = e("CATCH"); val SYNCHRONIZED = e("SYNCHRONIZED"); val LABELED = e("LABELED")
    val RETURN = e("RETURN"); val THROW = e("THROW"); val BREAK = e("BREAK"); val CONTINUE = e("CONTINUE"); val ASSERT = e("ASSERT")
    val NAME_REF = e("NAME_REF")                  // a name, `this`, `super`
    val MEMBER = e("MEMBER")                      // a.b, a?.b, a*.b, a.&b, a::b
    val CALL = e("CALL"); val ARGS = e("ARGS")
    val ENTRY = e("ENTRY")                        // name: value (named argument, map entry)
    val INDEX = e("INDEX"); val NEW = e("NEW"); val LIST = e("LIST")
    val LITERAL = e("LITERAL"); val GSTRING = e("GSTRING"); val INJECTION = e("INJECTION")
    val ASSIGN = e("ASSIGN"); val TERNARY = e("TERNARY"); val BINARY = e("BINARY"); val UNARY = e("UNARY"); val CAST = e("CAST"); val PAREN = e("PAREN")
}

private typealias E = GroovyElementTypes

/** Whether a parsed type can only be a type (`int`, `List<String>`, `Foo`) or may as well be a value (`foo`, `a.b`). */
private enum class TypeKind { CERTAIN, MAYBE }

/**
 * Tolerant recursive descent. Statements are only as strict as scopes need: what a statement does not consume is
 * read as the next one, and an unknown token is reported and skipped alone.
 */
class GroovyParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val m = builder.mark()
        Impl(builder).statements { false }
        m.done(root)
        return builder.treeBuilt
    }

    private class Impl(val b: PsiBuilder) {
        private var nameEnd = -1    // offset after a postfix chain that ends on a name: `foo bar` may follow
        private var callEnd = -1    // offset after a postfix chain that ends on a call: `id('x') version '1'` may follow
        private var caseLabel = false
        private var groups = 0      // open `(` and `[` around the cursor: line breaks do not end an expression inside them

        private val at get() = b.tokenType
        private fun at(t: IElementType) = b.tokenType == t
        private fun atWord(text: String) = at(T.IDENT) && b.tokenText == text
        private fun eat(t: IElementType): Boolean { if (b.tokenType == t) { b.advanceLexer(); return true }; return false }
        private fun expect(t: IElementType, what: String) { if (!eat(t)) b.error("$what expected") }
        private fun kw(text: String) = T.kw(text)
        private fun atName() = at(T.IDENT) || at(T.STRING)

        // ---------------------------------------------------------------- layout

        private fun isTrivia(t: IElementType?) = t == T.WHITE_SPACE || (t != null && t in T.COMMENTS)

        /** Whether the raw token at [step] is white space holding a line break (a `\` continuation is none). */
        private fun breaksLine(step: Int): Boolean {
            if (b.rawLookup(step) != T.WHITE_SPACE) return false
            val text = b.originalText.subSequence(b.rawTokenTypeStart(step), b.rawTokenTypeStart(step + 1))
            return '\n' in text && !text.startsWith('\\')
        }

        private fun newlineBefore(): Boolean {
            if (groups > 0) return false
            b.tokenType
            return generateSequence(-1) { it - 1 }.takeWhile { isTrivia(b.rawLookup(it)) }.any(::breaksLine)
        }

        private fun newlineAfter(): Boolean {
            b.tokenType
            return generateSequence(1) { it + 1 }.takeWhile { isTrivia(b.rawLookup(it)) }.any(::breaksLine)
        }

        private fun spaceBefore(): Boolean { b.tokenType; return isTrivia(b.rawLookup(-1)) }
        private fun atStatementEnd() = b.eof() || at(T.SEMI) || at(T.RBRACE) || newlineBefore()

        /** The token after the bracket group opened at lookahead [from], or null when it does not close nearby. */
        private fun afterGroup(from: Int): IElementType? {
            val tokens = generateSequence(from) { it + 1 }.take(GROUP_LOOKAHEAD).map { b.lookAhead(it) }.takeWhile { it != null }
            val depths = tokens.runningFold(0) { depth, t ->
                when (t) {
                    T.LPAREN, T.LBRACKET, T.LBRACE -> depth + 1
                    T.RPAREN, T.RBRACKET, T.RBRACE -> depth - 1
                    else -> depth
                }
            }.drop(1)
            val length = depths.indexOfFirst { it == 0 }
            if (length < 0) return null
            return b.lookAhead(from + length + 1)
        }

        // ---------------------------------------------------------------- statements

        fun statements(stop: () -> Boolean) {
            val outer = groups
            groups = 0
            while (!b.eof() && !stop()) {
                val before = b.currentOffset
                statement()
                if (b.currentOffset == before) { b.error("Unexpected '${b.tokenText}'"); b.advanceLexer() }
            }
            groups = outer
        }

        private fun statement() {
            when (at) {
                T.SEMI -> b.advanceLexer()
                kw("package") -> { val m = b.mark(); b.advanceLexer(); qualifiedName(); m.done(E.PACKAGE) }
                kw("import") -> importStatement(b.mark())
                kw("if") -> ifStatement()
                kw("while") -> { val m = b.mark(); b.advanceLexer(); condition(); body(); m.done(E.WHILE) }
                kw("do") -> { val m = b.mark(); b.advanceLexer(); body(); if (eat(kw("while"))) condition(); m.done(E.DO_WHILE) }
                kw("for") -> forStatement()
                kw("switch") -> switchStatement()
                kw("try") -> tryStatement()
                kw("return") -> keywordStatement(E.RETURN)
                kw("throw") -> keywordStatement(E.THROW)
                kw("break") -> jump(E.BREAK)
                kw("continue") -> jump(E.CONTINUE)
                kw("assert") -> assertStatement()
                else -> otherStatement()
            }
        }

        private fun otherStatement() {
            if (at(kw("synchronized")) && b.lookAhead(1) == T.LPAREN) {
                val m = b.mark(); b.advanceLexer(); condition(); body(); m.done(E.SYNCHRONIZED)
                return
            }
            if (at(T.IDENT) && b.lookAhead(1) == T.COLON) {
                val m = b.mark(); b.advanceLexer(); b.advanceLexer()
                if (!atStatementEnd()) statement()
                m.done(E.LABELED)
                return
            }
            if (!declaration(inClass = false)) commandExpr()
        }

        private fun importStatement(m: PsiBuilder.Marker) {
            b.advanceLexer()
            eat(kw("static"))
            qualifiedName()
            if (at(T.DOT) && b.lookAhead(1) == T.MUL) { b.advanceLexer(); b.advanceLexer() }
            if (eat(kw("as"))) expect(T.IDENT, "Alias")
            m.done(E.IMPORT)
        }

        private fun qualifiedName() {
            val m = b.mark()
            expect(T.IDENT, "Name")
            while (at(T.DOT) && b.lookAhead(1) == T.IDENT) { b.advanceLexer(); b.advanceLexer() }
            m.done(E.QUALIFIED_NAME)
        }

        private fun condition() {
            expect(T.LPAREN, "'('")
            groups++
            if (!at(T.RPAREN)) expr()
            groups--
            expect(T.RPAREN, "')'")
        }

        private fun body() {
            if (at(T.LBRACE)) { block(); return }
            if (!b.eof() && !at(T.RBRACE)) statement()
        }

        private fun block() {
            val m = b.mark()
            b.advanceLexer()
            statements { at(T.RBRACE) }
            expect(T.RBRACE, "'}'")
            m.done(E.BLOCK)
        }

        private fun ifStatement() {
            val m = b.mark()
            b.advanceLexer(); condition(); body()
            if (at(T.SEMI) && b.lookAhead(1) == kw("else")) b.advanceLexer()
            if (eat(kw("else"))) body()
            m.done(E.IF)
        }

        private fun forStatement() {
            val m = b.mark()
            b.advanceLexer()
            expect(T.LPAREN, "'('")
            if (isForEach()) forEachHeader()
            if (!at(T.RPAREN)) classicForHeader()
            expect(T.RPAREN, "')'")
            body()
            m.done(E.FOR)
        }

        /** `for (x in xs)` / `for (String x : xs)`: an `in` or a `:` before any `;` at the top of the parentheses. */
        private fun isForEach(): Boolean {
            val first = generateSequence(0) { it + 1 }.take(GROUP_LOOKAHEAD).map { b.lookAhead(it) }.takeWhile { it != null && it != T.RPAREN && it != T.LPAREN }
                .firstOrNull { it == T.SEMI || it == T.COLON || it == kw("in") }
            return first != null && first != T.SEMI
        }

        private fun forEachHeader() {
            val v = b.mark()
            modifiers()
            if (!(at(T.IDENT) && isForEachSeparator(b.lookAhead(1)))) type()
            expect(T.IDENT, "Name")
            v.done(E.VARIABLE)
            if (isForEachSeparator(at)) b.advanceLexer()
            expr()
        }

        private fun isForEachSeparator(t: IElementType?) = t == kw("in") || t == T.COLON

        private fun classicForHeader() {
            if (!at(T.SEMI) && !declaration(inClass = false)) exprList()
            expect(T.SEMI, "';'")
            if (!at(T.SEMI)) expr()
            expect(T.SEMI, "';'")
            if (!at(T.RPAREN)) exprList()
        }

        private fun exprList() { expr(); while (eat(T.COMMA)) expr() }

        private fun switchStatement() {
            val m = b.mark()
            b.advanceLexer(); condition()
            expect(T.LBRACE, "'{'")
            statements { caseLabels(); at(T.RBRACE) || b.eof() }
            expect(T.RBRACE, "'}'")
            m.done(E.SWITCH)
        }

        private fun caseLabels() {
            while (at(kw("case")) || at(kw("default"))) {
                val m = b.mark()
                val labelled = eat(kw("case"))
                if (!labelled) b.advanceLexer()
                caseLabel = true
                if (labelled) exprList()
                caseLabel = false
                if (!eat(T.COLON)) eat(T.ARROW)
                m.done(E.CASE)
            }
        }

        private fun tryStatement() {
            val m = b.mark()
            b.advanceLexer()
            if (eat(T.LPAREN)) {
                statements { at(T.RPAREN) }
                expect(T.RPAREN, "')'")
            }
            body()
            while (at(kw("catch"))) catchClause()
            if (eat(kw("finally"))) body()
            m.done(E.TRY)
        }

        private fun catchClause() {
            val m = b.mark()
            b.advanceLexer()
            expect(T.LPAREN, "'('")
            val v = b.mark()
            modifiers()
            if (!(at(T.IDENT) && b.lookAhead(1) == T.RPAREN)) { type(); while (eat(T.BOR)) type() }
            expect(T.IDENT, "Name")
            v.done(E.VARIABLE)
            expect(T.RPAREN, "')'")
            body()
            m.done(E.CATCH)
        }

        private fun keywordStatement(type: IElementType) {
            val m = b.mark()
            b.advanceLexer()
            if (!atStatementEnd()) commandExpr()
            m.done(type)
        }

        private fun jump(type: IElementType) {
            val m = b.mark()
            b.advanceLexer()
            if (at(T.IDENT) && !newlineBefore()) b.advanceLexer()
            m.done(type)
        }

        private fun assertStatement() {
            val m = b.mark()
            b.advanceLexer()
            expr()
            if (eat(T.COLON) || eat(T.COMMA)) expr()
            m.done(E.ASSERT)
        }

        // ---------------------------------------------------------------- declarations

        /** A class, method or variable declaration at the cursor; false (nothing consumed) when it reads as an expression. */
        private fun declaration(inClass: Boolean): Boolean {
            val m = b.mark()
            val modified = modifiers()
            if (atClassKeyword()) { classDeclaration(m); return true }
            if (modified && at(kw("import"))) { importStatement(m); return true }
            if (modified && at(T.LPAREN)) { tupleDeclaration(m); return true }
            if (inClass && at(T.LBRACE)) { block(); m.drop(); return true }
            if ((modified || inClass) && at(T.LT)) typeArgs()
            if ((modified || inClass) && atName() && b.lookAhead(1) == T.LPAREN) { method(m); return true }
            if (modified && at(T.IDENT) && endsVariableName()) { variables(m); return true }
            val beforeType = b.mark()
            val kind = type()
            val typed = modified || inClass || kind == TypeKind.CERTAIN
            if (kind != null && typed && atName() && b.lookAhead(1) == T.LPAREN && (modified || inClass || afterGroup(1) == T.LBRACE)) {
                beforeType.drop(); method(m); return true
            }
            if (kind != null && typed && at(T.IDENT) && endsVariableName()) { beforeType.drop(); variables(m); return true }
            beforeType.rollbackTo()
            m.drop()
            return modified
        }

        private fun endsVariableName(): Boolean = when (b.lookAhead(1)) {
            T.ASSIGN, T.COMMA, T.SEMI, T.RBRACE, T.RPAREN, null -> true
            else -> newlineAfter()
        }

        private val modifierKeywords = TokenSet.create(
            *listOf("abstract", "def", "final", "native", "private", "protected", "public", "static", "strictfp", "transient", "volatile").map(::kw).toTypedArray(),
        )

        private fun atModifier(): Boolean = when {
            at(T.AT) -> b.lookAhead(1) == T.IDENT
            at(kw("synchronized")) -> b.lookAhead(1) != T.LPAREN
            at(kw("default")) -> b.lookAhead(1) != T.COLON && b.lookAhead(1) != T.ARROW
            atWord("var") -> b.lookAhead(1) == T.IDENT && b.lookAhead(2) == T.ASSIGN
            else -> at in modifierKeywords
        }

        private fun modifiers(): Boolean {
            if (!atModifier()) return false
            val m = b.mark()
            while (atModifier()) {
                if (at(T.AT)) annotation()
                if (!at(T.AT) && atModifier()) b.advanceLexer()
            }
            m.done(E.MODIFIERS)
            return true
        }

        private fun annotation() {
            val m = b.mark()
            b.advanceLexer()
            type()
            if (at(T.LPAREN) && !newlineBefore()) args()
            m.done(E.ANNOTATION)
        }

        private fun atClassKeyword(): Boolean = when {
            at(kw("class")) || at(kw("interface")) || at(kw("enum")) -> true
            at(T.AT) -> b.lookAhead(1) == kw("interface")
            atWord("trait") || atWord("record") -> b.lookAhead(1) == T.IDENT && b.lookAhead(2) in CLASS_HEADER_FOLLOWERS
            else -> false
        }

        private fun classDeclaration(m: PsiBuilder.Marker) {
            eat(T.AT)
            val enum = at(kw("enum"))
            b.advanceLexer()
            expect(T.IDENT, "Class name")
            if (at(T.LT)) typeArgs()
            if (at(T.LPAREN)) params()
            while (at(kw("extends")) || at(kw("implements")) || atWord("permits")) { b.advanceLexer(); typeList() }
            if (at(T.LBRACE)) classBody(enum)
            m.done(E.CLASS)
        }

        private fun typeList() { type(); while (eat(T.COMMA)) type() }

        private fun classBody(enum: Boolean) {
            val m = b.mark()
            b.advanceLexer()
            if (enum) enumConstants()
            statements { members(); at(T.RBRACE) || b.eof() }
            expect(T.RBRACE, "'}'")
            m.done(E.CLASS_BODY)
        }

        /** Reads the declarations at the cursor; the caller reads what is none as a statement. */
        private fun members() { while (!b.eof() && !at(T.RBRACE) && (eat(T.SEMI) || declaration(inClass = true))) Unit }

        private fun enumConstants() {
            while (at(T.AT)) annotation()
            while (at(T.IDENT) && (b.lookAhead(1) in ENUM_CONSTANT_FOLLOWERS || newlineAfter())) {
                val m = b.mark()
                b.advanceLexer()
                if (at(T.LPAREN)) args()
                if (at(T.LBRACE)) classBody(enum = false)
                m.done(E.ENUM_CONSTANT)
                eat(T.COMMA)
                while (at(T.AT)) annotation()
            }
        }

        private fun method(m: PsiBuilder.Marker) {
            b.advanceLexer()
            params()
            if (eat(kw("throws"))) typeList()
            if (eat(kw("default"))) expr()
            if (at(T.LBRACE)) block()
            m.done(E.METHOD)
        }

        private fun params() {
            val m = b.mark()
            b.advanceLexer()
            while (!b.eof() && !at(T.RPAREN)) {
                val before = b.currentOffset
                param()
                if (!eat(T.COMMA) && b.currentOffset == before) break
            }
            expect(T.RPAREN, "')'")
            m.done(E.PARAMS)
        }

        private fun param() {
            val m = b.mark()
            modifiers()
            if (!(at(T.IDENT) && b.lookAhead(1) in PARAM_NAME_FOLLOWERS)) { type(); eat(T.ELLIPSIS) }
            expect(T.IDENT, "Parameter name")
            if (eat(T.ASSIGN)) expr()
            m.done(E.PARAM)
        }

        private fun variables(m: PsiBuilder.Marker) {
            do {
                val v = b.mark()
                b.advanceLexer()
                if (eat(T.ASSIGN)) commandExpr()
                v.done(E.VARIABLE)
            } while (at(T.COMMA) && b.lookAhead(1) == T.IDENT && eat(T.COMMA))
            m.done(E.VAR_DECL)
        }

        /** `def (a, String b) = [1, 'x']`. */
        private fun tupleDeclaration(m: PsiBuilder.Marker) {
            b.advanceLexer()
            while (!b.eof() && !at(T.RPAREN)) {
                val before = b.currentOffset
                val v = b.mark()
                if (!(at(T.IDENT) && (b.lookAhead(1) == T.COMMA || b.lookAhead(1) == T.RPAREN))) type()
                expect(T.IDENT, "Name")
                v.done(E.VARIABLE)
                if (!eat(T.COMMA) && b.currentOffset == before) break
            }
            expect(T.RPAREN, "')'")
            if (eat(T.ASSIGN)) commandExpr()
            m.done(E.VAR_DECL)
        }

        // ---------------------------------------------------------------- types

        /** `int`, `def`, `a.b.C`, `Map<String, List<X>>`, `String[]`; null (nothing consumed) when no type is there. */
        private fun type(): TypeKind? {
            val m = b.mark()
            val named = when {
                at in T.PRIMITIVES || at(kw("def")) -> { b.advanceLexer(); TypeKind.CERTAIN }
                at(T.IDENT) -> qualifiedTypeName()
                else -> { m.drop(); return null }
            }
            val generic = at(T.LT)
            if (generic && !typeArgs()) { m.rollbackTo(); return null }
            val array = at(T.LBRACKET) && b.lookAhead(1) == T.RBRACKET
            while (at(T.LBRACKET) && b.lookAhead(1) == T.RBRACKET) { b.advanceLexer(); b.advanceLexer() }
            m.done(E.TYPE)
            if (generic || array) return TypeKind.CERTAIN
            return named
        }

        private fun qualifiedTypeName(): TypeKind {
            var last = b.tokenText.orEmpty()
            b.advanceLexer()
            while (at(T.DOT) && b.lookAhead(1) == T.IDENT) { b.advanceLexer(); last = b.tokenText.orEmpty(); b.advanceLexer() }
            if (last.firstOrNull()?.isUpperCase() == true) return TypeKind.CERTAIN
            return TypeKind.MAYBE
        }

        /** `<A, ? extends B, C<D>>`; false when the tokens are a comparison after all. */
        private fun typeArgs(): Boolean {
            val m = b.mark()
            b.advanceLexer()
            while (!at(T.GT)) {
                val argument = (eat(T.QUESTION) || type() != null) && typeBounds()
                if (!argument || !(at(T.GT) || eat(T.COMMA))) { m.drop(); return false }
            }
            b.advanceLexer()
            m.done(E.TYPE_ARGS)
            return true
        }

        private fun typeBounds(): Boolean {
            if (!eat(kw("extends")) && !eat(kw("super"))) return true
            if (type() == null) return false
            while (eat(T.BAND)) type()
            return true
        }

        // ---------------------------------------------------------------- expressions

        /** An expression, then the arguments of a call written without parentheses: `a b, c`, `a b c d`, `a(b) c d`. */
        private fun commandExpr() {
            val m = b.mark()
            expr()
            val callee = nameEnd == b.currentOffset && atCommandArg()
            val chained = callEnd == b.currentOffset && at(T.IDENT) && !newlineBefore()
            if (!callee && !chained) { m.drop(); return }
            var call = m
            if (callee) { commandArgs(); call.done(E.CALL); call = call.precede() }
            while (at(T.IDENT) && !newlineBefore()) {
                b.advanceLexer(); call.done(E.MEMBER); call = call.precede()
                val parenthesized = at(T.LPAREN) && !newlineBefore()
                if (!parenthesized && !atCommandArg()) break
                if (parenthesized) { args(); trailingClosures() }
                if (!parenthesized) commandArgs()
                call.done(E.CALL); call = call.precede()
            }
            call.drop()
        }

        private fun atCommandArg(): Boolean {
            if (b.eof() || newlineBefore()) return false
            if (at(T.LBRACKET)) return spaceBefore()
            return at in COMMAND_ARG_STARTS
        }

        private fun commandArgs() {
            val m = b.mark()
            argument()
            while (eat(T.COMMA)) argument()
            m.done(E.ARGS)
        }

        /** `value`, `name: value`, `(key): value`, `*: map`, `*list`. */
        private fun argument() {
            val m = b.mark()
            val label = b.lookAhead(1) == T.COLON && (at(T.IDENT) || at(T.STRING) || at(T.NUMBER) || at(T.MUL) || at in T.KEYWORD_SET)
            if (label) { b.advanceLexer(); b.advanceLexer(); expr(); m.done(E.ENTRY); return }
            if (at(T.MUL)) { b.advanceLexer(); expr(); m.done(E.UNARY); return }
            expr()
            if (!eat(T.COLON)) { m.drop(); return }
            expr()
            m.done(E.ENTRY)
        }

        private fun args() {
            val m = b.mark()
            b.advanceLexer()
            argumentsUntil(T.RPAREN)
            expect(T.RPAREN, "')'")
            m.done(E.ARGS)
        }

        private fun argumentsUntil(close: IElementType) {
            groups++
            while (!b.eof() && !at(close)) {
                val before = b.currentOffset
                argument()
                if (!eat(T.COMMA) && b.currentOffset == before) break
            }
            groups--
        }

        fun expr() {
            val m = b.mark()
            ternary()
            if (at !in T.ASSIGNMENTS && !atShiftAssign()) { m.drop(); return }
            while (at(T.GT)) b.advanceLexer()
            b.advanceLexer()
            commandExpr()
            m.done(E.ASSIGN)
        }

        /** `>>=` and `>>>=`, which the lexer leaves as `>` `>=`. */
        private fun atShiftAssign() = at(T.GT) && !spaceAfterCurrent() && (b.lookAhead(1) == T.GE || (b.lookAhead(1) == T.GT && b.lookAhead(2) == T.GE))

        private fun spaceAfterCurrent(): Boolean { b.tokenType; return isTrivia(b.rawLookup(1)) }

        private fun ternary() {
            val m = b.mark()
            binary(0)
            if (eat(T.ELVIS)) { ternary(); m.done(E.TERNARY); return }
            if (!eat(T.QUESTION)) { m.drop(); return }
            ternary()
            if (eat(T.COLON)) ternary()
            m.done(E.TERNARY)
        }

        private fun priority(t: IElementType?): Int? = when (t) {
            T.OR -> 1
            T.AND -> 2
            T.BOR -> 3
            T.XOR -> 4
            T.BAND -> 5
            T.EQ, T.NE, T.IDENTICAL, T.NOT_IDENTICAL, T.SPACESHIP, T.REGEX_FIND, T.REGEX_MATCH -> 6
            T.LT, T.GT, T.LE, T.GE, kw("in"), kw("instanceof"), kw("as"), T.NOT -> 7
            T.SHL, T.RANGE, T.RANGE_EXCLUSIVE -> 8
            T.PLUS, T.MINUS -> 9
            T.MUL, T.DIV, T.MOD -> 10
            T.POW -> 11
            else -> null
        }

        /** The priority of the binary operator at the cursor: `+` / `-` opening a line are signs, `!` counts in `!in` / `!instanceof` only. */
        private fun binaryPriority(): Int? {
            val t = at
            if ((t == T.PLUS || t == T.MINUS) && newlineBefore()) return null
            if (t == T.NOT && b.lookAhead(1) != kw("in") && b.lookAhead(1) != kw("instanceof")) return null
            if (t == T.GT && atShiftAssign()) return null
            return priority(t)
        }

        private fun binary(limit: Int) {
            var m = b.mark()
            unary()
            while (true) {
                val priority = binaryPriority() ?: break
                if (priority <= limit) break
                eat(T.NOT)
                val typed = at(kw("instanceof")) || at(kw("as"))
                val shift = at(T.GT)
                b.advanceLexer()
                while (shift && at(T.GT) && !spaceBefore()) b.advanceLexer()
                if (typed) type()
                if (!typed) binary(priority)
                m.done(E.BINARY)
                m = m.precede()
            }
            m.drop()
        }

        private fun unary() {
            if (at in PREFIX_OPERATORS) {
                val m = b.mark(); b.advanceLexer(); unary(); m.done(E.UNARY)
                return
            }
            if (at(T.LPAREN) && cast()) return
            postfix()
        }

        /** `(Type) value`: only a certain type, followed on its line by what starts a value. */
        private fun cast(): Boolean {
            val m = b.mark()
            b.advanceLexer()
            val isCast = type() == TypeKind.CERTAIN && eat(T.RPAREN) && at in CAST_OPERAND_STARTS && !newlineBefore()
            if (!isCast) { m.rollbackTo(); return false }
            unary()
            m.done(E.CAST)
            return true
        }

        private enum class Chain { NAME, CALL, VALUE }

        private fun postfix() {
            var m = b.mark()
            var chain = primary() ?: run { m.drop(); return }
            while (true) {
                val sameLine = !newlineBefore()
                chain = when {
                    at in T.MEMBER_SELECTORS -> { b.advanceLexer(); memberName(); m.done(E.MEMBER); Chain.NAME }
                    at(T.LPAREN) && sameLine -> { args(); trailingClosures(); m.done(E.CALL); Chain.CALL }
                    at(T.LBRACE) && sameLine && chain == Chain.NAME -> { trailingClosures(); m.done(E.CALL); Chain.CALL }
                    at(T.LBRACKET) && sameLine && !(chain == Chain.NAME && spaceBefore()) -> { index(); m.done(E.INDEX); Chain.VALUE }
                    (at(T.INC) || at(T.DEC)) && sameLine -> { b.advanceLexer(); m.done(E.UNARY); Chain.VALUE }
                    else -> break
                }
                m = m.precede()
            }
            m.drop()
            if (chain == Chain.NAME) nameEnd = b.currentOffset
            if (chain == Chain.CALL) callEnd = b.currentOffset
        }

        private fun trailingClosures() { while (at(T.LBRACE) && !newlineBefore()) closure() }

        private fun index() {
            b.advanceLexer()
            argumentsUntil(T.RBRACKET)
            expect(T.RBRACKET, "']'")
        }

        /** After a selector: a name, a quoted name, `"$dynamic"`, `(expr)` or `<T>name`. */
        private fun memberName() {
            if (at(T.LT)) typeArgs()
            when (at) {
                T.IDENT, T.STRING -> b.advanceLexer()
                T.GSTRING_BEGIN -> gstring()
                T.LPAREN -> paren()
                T.LBRACE -> closure()
                else -> if (!eat(T.MUL) && !eat(T.AT)) b.error("Name expected")
            }
        }

        private fun leaf(type: IElementType): Chain {
            val m = b.mark(); b.advanceLexer(); m.done(type)
            return Chain.VALUE
        }

        private fun primary(): Chain? = when (at) {
            T.IDENT -> when {
                b.lookAhead(1) == T.ARROW && !caseLabel -> lambda()
                b.lookAhead(1) == T.LT && genericType() -> Chain.VALUE
                else -> { leaf(E.NAME_REF); Chain.NAME }
            }
            T.AT -> { annotation(); Chain.VALUE }
            kw("this"), kw("super") -> leaf(E.NAME_REF)
            T.NUMBER, T.STRING, kw("true"), kw("false"), kw("null") -> leaf(E.LITERAL)
            T.GSTRING_BEGIN -> { gstring(); Chain.VALUE }
            T.LPAREN -> when {
                afterGroup(0) == T.ARROW && !caseLabel -> lambda()
                else -> { paren(); Chain.VALUE }
            }
            T.LBRACKET -> { val m = b.mark(); b.advanceLexer(); eat(T.COLON); argumentsUntil(T.RBRACKET); expect(T.RBRACKET, "']'"); m.done(E.LIST); Chain.VALUE }
            T.LBRACE -> { closure(); Chain.VALUE }
            kw("new") -> { newExpr(); Chain.VALUE }
            kw("switch") -> { switchStatement(); Chain.VALUE }
            else -> when {
                at in T.PRIMITIVES -> { type(); Chain.VALUE }
                else -> { b.error("Expression expected"); null }
            }
        }

        /** `List<String>` used as a value: `Mock(List<String>)`. */
        private fun genericType(): Boolean {
            val m = b.mark()
            val isType = type() == TypeKind.CERTAIN && at in GENERIC_TYPE_FOLLOWERS
            if (!isType) { m.rollbackTo(); return false }
            m.drop()
            return true
        }

        private fun paren() {
            val m = b.mark()
            b.advanceLexer()
            groups++
            if (!at(T.RPAREN)) exprList()
            groups--
            expect(T.RPAREN, "')'")
            m.done(E.PAREN)
        }

        private fun newExpr() {
            val m = b.mark()
            b.advanceLexer()
            newType()
            if (at(T.LPAREN)) { args(); if (at(T.LBRACE) && !newlineBefore()) classBody(enum = false) }
            val array = at(T.LBRACKET)
            while (at(T.LBRACKET)) index()
            if (array && at(T.LBRACE)) { b.advanceLexer(); argumentsUntil(T.RBRACE); expect(T.RBRACE, "'}'") }
            m.done(E.NEW)
        }

        /** The type after `new`, without the array brackets, which hold sizes there. */
        private fun newType() {
            val m = b.mark()
            if (at in T.PRIMITIVES) b.advanceLexer()
            if (at(T.IDENT)) qualifiedTypeName()
            if (at(T.LT)) typeArgs()
            m.done(E.TYPE)
        }

        private fun gstring() {
            val m = b.mark()
            b.advanceLexer()
            while (at(T.GSTRING_TEXT) || at(T.DOLLAR)) {
                if (eat(T.GSTRING_TEXT)) continue
                b.advanceLexer()
                if (at(T.LBRACE)) injection()
                if (at(T.IDENT) || at(kw("this"))) path()
            }
            if (!eat(T.GSTRING_END)) b.error("Unclosed string")
            m.done(E.GSTRING)
        }

        private fun injection() {
            val m = b.mark()
            b.advanceLexer()
            if (at(T.ARROW)) b.advanceLexer()
            statements { at(T.RBRACE) }
            expect(T.RBRACE, "'}'")
            m.done(E.INJECTION)
        }

        /** `$a.b.c` inside a string. */
        private fun path() {
            var m = b.mark()
            leaf(E.NAME_REF)
            while (at(T.DOT) && b.lookAhead(1) == T.IDENT) {
                b.advanceLexer(); b.advanceLexer()
                m.done(E.MEMBER)
                m = m.precede()
            }
            m.drop()
        }

        // ---------------------------------------------------------------- closures

        private fun closure() {
            val m = b.mark()
            b.advanceLexer()
            if (closureHasParams()) closureParams()
            statements { at(T.RBRACE) }
            expect(T.RBRACE, "'}'")
            m.done(E.CLOSURE)
        }

        /** `{ a, b -> … }`: an arrow before the first `;`, brace or line break (one after `{` or a comma excepted). */
        private fun closureHasParams(): Boolean {
            b.tokenType
            val steps = generateSequence(0) { it + 1 }.take(RAW_LOOKAHEAD).takeWhile { b.rawLookup(it) != null }
            val significant = steps.filter { !isTrivia(b.rawLookup(it)) || (breaksLine(it) && lastSignificantBefore(it) !in LINE_JOINERS) }
            val depths = significant.runningFold(0) { depth, i ->
                when (b.rawLookup(i)) {
                    T.LPAREN, T.LBRACKET -> depth + 1
                    T.RPAREN, T.RBRACKET -> depth - 1
                    else -> depth
                }
            }
            val stop = significant.zip(depths).firstOrNull { (i, depth) -> depth == 0 && b.rawLookup(i) in CLOSURE_PARAM_STOPS }?.first ?: return false
            return b.rawLookup(stop) == T.ARROW
        }

        private fun lastSignificantBefore(step: Int): IElementType? =
            generateSequence(step - 1) { it - 1 }.map { b.rawLookup(it) }.firstOrNull { !isTrivia(it) }

        private fun closureParams() {
            val m = b.mark()
            while (!b.eof() && !at(T.ARROW)) {
                val before = b.currentOffset
                param()
                if (!eat(T.COMMA) && b.currentOffset == before) break
            }
            expect(T.ARROW, "'->'")
            m.done(E.PARAMS)
        }

        /** `x -> …` and `(a, b) -> …`: a closure in Java's clothes. */
        private fun lambda(): Chain {
            val m = b.mark()
            if (at(T.LPAREN)) params()
            if (at(T.IDENT)) { val ps = b.mark(); val p = b.mark(); b.advanceLexer(); p.done(E.PARAM); ps.done(E.PARAMS) }
            expect(T.ARROW, "'->'")
            val braces = at(T.LBRACE)
            if (braces) block()
            if (!braces) expr()
            m.done(E.CLOSURE)
            return Chain.VALUE
        }

        private companion object {
            const val GROUP_LOOKAHEAD = 200
            const val RAW_LOOKAHEAD = 120
            val CAST_OPERAND_STARTS = TokenSet.create(
                T.BNOT, T.GSTRING_BEGIN, T.IDENT, T.LPAREN, T.NOT, T.NUMBER, T.STRING, T.kw("new"), T.kw("null"), T.kw("super"), T.kw("this"),
            )
            val CLASS_HEADER_FOLLOWERS = TokenSet.create(T.LBRACE, T.LPAREN, T.LT, T.kw("extends"), T.kw("implements"))
            val CLOSURE_PARAM_STOPS = TokenSet.create(T.ARROW, T.LBRACE, T.RBRACE, T.SEMI, T.WHITE_SPACE)
            val COMMAND_ARG_STARTS = TokenSet.create(
                T.BNOT, T.GSTRING_BEGIN, T.IDENT, T.NOT, T.NUMBER, T.STRING, T.kw("false"), T.kw("new"), T.kw("null"), T.kw("super"), T.kw("this"), T.kw("true"),
            )
            val ENUM_CONSTANT_FOLLOWERS = TokenSet.create(T.COMMA, T.LBRACE, T.LPAREN, T.RBRACE, T.SEMI)
            val GENERIC_TYPE_FOLLOWERS = TokenSet.create(T.COMMA, T.DOT, T.RBRACKET, T.RPAREN)
            val LINE_JOINERS = TokenSet.create(T.COMMA, T.LBRACE)
            val PARAM_NAME_FOLLOWERS = TokenSet.create(T.ARROW, T.ASSIGN, T.COMMA, T.RPAREN)
            val PREFIX_OPERATORS = TokenSet.create(T.BNOT, T.DEC, T.INC, T.MINUS, T.NOT, T.PLUS)
        }
    }
}

class GroovyParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GroovyLexer()
    override fun createParser(project: Project?): PsiParser = GroovyParser()
    override fun getFileNodeType(): IFileElementType = E.FILE
    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = T.STRINGS
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GroovyFile(viewProvider)
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        E.CALL -> GroovyCall(node)
        E.CLASS -> GroovyClass(node)
        E.CLOSURE -> GroovyClosure(node)
        E.ENUM_CONSTANT -> GroovyEnumConstant(node)
        E.IMPORT -> GroovyImport(node)
        E.LITERAL -> GroovyLiteral(node)
        E.MEMBER -> GroovyMember(node)
        E.METHOD -> GroovyMethod(node)
        E.NAME_REF -> GroovyNameRef(node)
        E.PARAM, E.VARIABLE -> GroovyVariable(node)
        E.QUALIFIED_NAME -> GroovyQualifiedName(node)
        E.TYPE -> GroovyType(node)
        else -> GroovyElement(node)
    }
}
