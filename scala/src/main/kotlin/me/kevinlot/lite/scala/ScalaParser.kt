package me.kevinlot.lite.scala

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
import me.kevinlot.lite.scala.ScalaTokens as T

class ScalaElementType(debugName: String) : IElementType(debugName, ScalaLanguage)

object ScalaElementTypes {
    val FILE = IFileElementType("LITE_SCALA_FILE", ScalaLanguage)
    private fun e(n: String) = ScalaElementType(n)
    val PACKAGE = e("PACKAGE"); val PACKAGE_NAME = e("PACKAGE_NAME")
    val IMPORT = e("IMPORT"); val IMPORT_EXPR = e("IMPORT_EXPR"); val SELECTORS = e("SELECTORS"); val SELECTOR = e("SELECTOR")
    val MODIFIERS = e("MODIFIERS"); val ANNOTATION = e("ANNOTATION")
    val CLASS = e("CLASS")                        // class, trait, object, enum, package object
    val EXTENDS = e("EXTENDS"); val TEMPLATE_BODY = e("TEMPLATE_BODY"); val SELF_TYPE = e("SELF_TYPE"); val ENUM_CASE = e("ENUM_CASE")
    val FUNCTION = e("FUNCTION"); val PARAMS = e("PARAMS"); val PARAM = e("PARAM")
    val TYPE_PARAMS = e("TYPE_PARAMS"); val TYPE_PARAM = e("TYPE_PARAM")
    val VAL_DECL = e("VAL_DECL")                  // val / var: names or patterns, a type, a value
    val VARIABLE = e("VARIABLE")                  // a name bound by a declaration, a pattern or a generator
    val TYPE_ALIAS = e("TYPE_ALIAS"); val GIVEN = e("GIVEN"); val EXTENSION = e("EXTENSION"); val END_MARKER = e("END_MARKER")
    val TYPE = e("TYPE")                          // a named type: a.b.C[X]
    val COMPOUND_TYPE = e("COMPOUND_TYPE")        // any other type: function, tuple, infix, wildcard
    val TYPE_ARGS = e("TYPE_ARGS")
    val BLOCK = e("BLOCK"); val LAMBDA = e("LAMBDA"); val CASE_CLAUSE = e("CASE_CLAUSE"); val PATTERN = e("PATTERN"); val GUARD = e("GUARD")
    val IF = e("IF"); val WHILE = e("WHILE"); val DO_WHILE = e("DO_WHILE"); val FOR = e("FOR"); val GENERATOR = e("GENERATOR")
    val TRY = e("TRY"); val CATCH = e("CATCH"); val FINALLY = e("FINALLY"); val MATCH = e("MATCH"); val RETURN = e("RETURN"); val THROW = e("THROW")
    val REF = e("REF")                            // a name, `this`, `super`
    val MEMBER = e("MEMBER")                      // a.b
    val OP_REF = e("OP_REF")                      // the operator of `a op b`
    val CALL = e("CALL"); val ARGS = e("ARGS"); val NAMED_ARG = e("NAMED_ARG"); val TYPE_APPLY = e("TYPE_APPLY"); val NEW = e("NEW")
    val LITERAL = e("LITERAL"); val ISTRING = e("ISTRING"); val INJECTION = e("INJECTION"); val PLACEHOLDER = e("PLACEHOLDER")
    val ASSIGN = e("ASSIGN"); val INFIX = e("INFIX"); val POSTFIX = e("POSTFIX"); val PREFIX = e("PREFIX"); val ASCRIPTION = e("ASCRIPTION")
    val PAREN = e("PAREN")
}

private typealias E = ScalaElementTypes

/**
 * Tolerant recursive descent over Scala 2 and the common Scala 3 syntax. Line breaks end a statement as in the
 * language (not inside parentheses, not before a token that cannot start one). A Scala 3 indentation region is read
 * as a block: it opens where a body is expected on a deeper-indented new line and closes at the first line that is
 * indented less. What a statement does not consume is read as the next one.
 */
class ScalaParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val m = builder.mark()
        Impl(builder).statements { false }
        m.done(root)
        return builder.treeBuilt
    }

    private class Impl(val b: PsiBuilder) {
        // What the enclosing constructs ask of the code at the cursor: saved and restored around each nested one.
        private var groups = 0              // open `(` and `[` around the cursor: line breaks do not end an expression inside them
        private var typeGroups = 0          // open `(` and `[` of a type: a `{` there is a refinement
        private var noLambda = false        // in a case guard: `=>` ends the guard
        private var noFunctionType = false  // in a typed pattern or a lambda parameter: `=>` ends the type
        private var noBarType = false       // in a typed pattern: `|` separates alternatives
        private var inCondition = false     // in `if … then`: `then` is no operator
        private var inEnum = false          // directly in an enum body: `case A, B` declares cases
        private var bracedStatement = -1    // offset of a statement that starts directly in `{ }`: a lambda there owns the rest of the block
        private var statementIndent = 0     // indentation of the line where the current statement starts
        private var regionColumn = -1       // column of the indentation region around the cursor; -1 inside braces

        private val text: CharSequence get() = b.originalText
        private val at get() = b.tokenType
        private fun at(t: IElementType) = b.tokenType == t
        private fun atWord(word: String) = at(T.IDENT) && b.tokenText == word
        private fun atOp(op: String) = at(T.OP) && b.tokenText == op
        private fun eat(t: IElementType): Boolean { if (b.tokenType == t) { b.advanceLexer(); return true }; return false }
        private fun expect(t: IElementType, what: String) { if (!eat(t)) b.error("$what expected") }
        private fun kw(word: String) = T.kw(word)
        private fun softKeyword() { b.remapCurrentToken(T.SOFT_KEYWORD); b.advanceLexer() }
        private fun eatWord(word: String): Boolean { if (!atWord(word)) return false; softKeyword(); return true }
        private fun eatOp(op: String): Boolean { if (!atOp(op)) return false; b.advanceLexer(); return true }

        // ---------------------------------------------------------------- layout

        private fun isTrivia(t: IElementType?) = t == T.WHITE_SPACE || (t != null && t in T.COMMENTS)

        /** Offset where the white space and comments before the current token start. */
        private fun triviaStart(): Int {
            b.tokenType
            val first = generateSequence(-1) { it - 1 }.takeWhile { isTrivia(b.rawLookup(it)) }.lastOrNull() ?: return b.currentOffset
            return b.rawTokenTypeStart(first)
        }

        private fun onNewLine(): Boolean = (triviaStart() until b.currentOffset).any { text[it] == '\n' }

        /** An empty line before the current token; comment lines are not empty. */
        private fun blankLineBefore(): Boolean {
            b.tokenType
            return generateSequence(-1) { it - 1 }.takeWhile { isTrivia(b.rawLookup(it)) }
                .any { b.rawLookup(it) == T.WHITE_SPACE && rawText(it).count { c -> c == '\n' } > 1 }
        }

        /** A line break that ends a statement: outside parentheses, before a token that can start a statement. */
        private fun newlineBefore(): Boolean = groups == 0 && at !in CONTINUATIONS && onNewLine()

        private fun lineStart(offset: Int): Int {
            if (offset <= 0) return 0
            return text.lastIndexOf('\n', offset - 1) + 1
        }

        private fun column(): Int { b.tokenType; return b.currentOffset - lineStart(b.currentOffset) }

        private fun indentOfLine(offset: Int): Int {
            val start = lineStart(offset)
            return (start until text.length).takeWhile { text[it] == ' ' || text[it] == '\t' }.count()
        }

        /** A body on a new line, indented deeper than the line where its statement starts, is an indentation region. */
        private fun startsIndentedBlock(): Boolean = !b.eof() && onNewLine() && column() > statementIndent

        /** Raw steps of the significant tokens after the current one. */
        private fun ahead(): Sequence<Int> {
            b.tokenType
            return generateSequence(1) { it + 1 }.takeWhile { b.rawLookup(it) != null }.filter { !isTrivia(b.rawLookup(it)) }
        }

        private fun rawText(step: Int): String = text.subSequence(b.rawTokenTypeStart(step), b.rawTokenTypeStart(step + 1)).toString()
        private fun breaksLine(step: Int): Boolean = isTrivia(b.rawLookup(step)) && '\n' in rawText(step)
        private fun wordAhead(n: Int): String? = ahead().elementAtOrNull(n - 1)?.takeIf { b.rawLookup(it) == T.IDENT }?.let(::rawText)
        private fun opAhead(n: Int): String? = ahead().elementAtOrNull(n - 1)?.takeIf { b.rawLookup(it) == T.OP }?.let(::rawText)

        /** Whether the [n]th token after the current one is the last of its line. */
        private fun lineEndsAfter(n: Int): Boolean {
            b.tokenType
            val step = when (n) {
                0 -> 0
                else -> ahead().elementAtOrNull(n - 1) ?: return true
            }
            val next = generateSequence(step + 1) { it + 1 }.first { !isTrivia(b.rawLookup(it)) || breaksLine(it) }
            return b.rawLookup(next) == null || breaksLine(next)
        }

        private fun newlineAfter(): Boolean = lineEndsAfter(0)

        /** The current token and the rest of its line at bracket depth 0, as raw steps. */
        private fun restOfLine(): Sequence<Int> {
            b.tokenType
            val steps = generateSequence(0) { it + 1 }.take(LINE_LOOKAHEAD).takeWhile { b.rawLookup(it) != null }
            val depths = steps.runningFold(0) { depth, step -> depth + depthChange(b.rawLookup(step)) }
            return steps.zip(depths).takeWhile { (step, depth) -> depth >= 0 && !(depth == 0 && breaksLine(step)) }
                .filter { (step, depth) -> depth == 0 && !isTrivia(b.rawLookup(step)) }.map { it.first }
        }

        private fun depthChange(t: IElementType?): Int = when (t) {
            T.LPAREN, T.LBRACKET, T.LBRACE -> 1
            T.RPAREN, T.RBRACKET, T.RBRACE -> -1
            else -> 0
        }

        /** The token after the bracket group opened at lookahead [from], or null when it does not close nearby. */
        private fun afterGroup(from: Int): IElementType? = groupEnd(from)?.let { b.lookAhead(it + 1) }

        private fun groupEnd(from: Int): Int? {
            val tokens = generateSequence(from) { it + 1 }.take(GROUP_LOOKAHEAD).map { b.lookAhead(it) }.takeWhile { it != null }
            val length = tokens.runningFold(0) { depth, t -> depth + depthChange(t) }.drop(1).indexOfFirst { it == 0 }
            return (from + length).takeIf { length >= 0 }
        }

        private fun skipGroup() {
            val close = groupEnd(0) ?: return b.advanceLexer()
            repeat(close + 1) { b.advanceLexer() }
        }

        /** The state above, as it is where a nested construct starts. */
        private inner class Saved {
            val groups = this@Impl.groups
            val typeGroups = this@Impl.typeGroups
            val noLambda = this@Impl.noLambda
            val noFunctionType = this@Impl.noFunctionType
            val noBarType = this@Impl.noBarType
            val inCondition = this@Impl.inCondition
            val inEnum = this@Impl.inEnum
            val bracedStatement = this@Impl.bracedStatement
            val statementIndent = this@Impl.statementIndent
            val regionColumn = this@Impl.regionColumn

            fun restore() {
                this@Impl.groups = groups
                this@Impl.typeGroups = typeGroups
                this@Impl.noLambda = noLambda
                this@Impl.noFunctionType = noFunctionType
                this@Impl.noBarType = noBarType
                this@Impl.inCondition = inCondition
                this@Impl.inEnum = inEnum
                this@Impl.bracedStatement = bracedStatement
                this@Impl.statementIndent = statementIndent
                this@Impl.regionColumn = regionColumn
            }
        }

        private fun clearExpressionFlags() {
            noLambda = false
            noFunctionType = false
            noBarType = false
            inCondition = false
        }

        /** Runs [parse] as a region of its own: what the enclosing expression set does not reach into it. */
        private fun <R> nested(enum: Boolean = false, parse: () -> R): R {
            val saved = Saved()
            clearExpressionFlags()
            groups = 0
            typeGroups = 0
            inEnum = enum
            bracedStatement = -1
            regionColumn = -1
            val result = parse()
            saved.restore()
            return result
        }

        /** Inside `( )` or `[ ]`: line breaks do not count and a guard's `=>` rule is off. */
        private fun <R> grouped(parse: () -> R): R {
            val saved = Saved()
            clearExpressionFlags()
            groups++
            val result = parse()
            saved.restore()
            return result
        }

        // ---------------------------------------------------------------- statements

        fun statements(braced: Boolean = false, enum: Boolean = false, stop: () -> Boolean) = nested(enum) {
            while (!b.eof() && !stop()) {
                val before = b.currentOffset
                if (braced) bracedStatement = before
                statement()
                if (b.currentOffset == before) { b.error("Unexpected '${b.tokenText}'"); b.advanceLexer() }
            }
        }

        /** The statements of an indentation region: every line that starts at the column of the first one, or deeper. */
        private fun indented(type: IElementType, enum: Boolean = false) {
            val m = b.mark()
            val column = column()
            nested(enum = enum) {
                regionColumn = column
                if (type == E.TEMPLATE_BODY) selfType()
                when {
                    type == E.BLOCK && atCaseClause() -> caseClauses()
                    else -> while (!b.eof() && at !in CONTINUATIONS && indentedStatement()) Unit
                }
            }
            m.done(type)
        }

        /** Reads one statement of the region; false when the region ends after it. */
        private fun indentedStatement(): Boolean {
            val before = b.currentOffset
            statement()
            if (b.currentOffset == before) return false
            val separated = at(T.SEMI)
            while (eat(T.SEMI)) Unit
            if (onNewLine()) return !leavesRegion()
            return separated
        }

        /** A line that starts left of the indentation region around the cursor closes it. */
        private fun leavesRegion(): Boolean = regionColumn >= 0 && onNewLine() && column() < regionColumn

        private fun statement() {
            statementIndent = indentOfLine(b.currentOffset)
            when {
                at(T.SEMI) -> b.advanceLexer()
                at(kw("package")) -> packageClause()
                at(kw("import")) -> importStatement()
                atWord("export") && b.lookAhead(1) == T.IDENT && !newlineAfter() -> importStatement()
                atEndMarker() -> endMarker()
                inEnum && at(kw("case")) && b.lookAhead(1) == T.IDENT -> enumCases()
                atDefinition() -> definition()
                else -> expr()
            }
        }

        private fun atEndMarker(): Boolean = atWord("end") && !newlineAfter() && b.lookAhead(1) in END_TARGETS && lineEndsAfter(1)

        private fun endMarker() {
            val m = b.mark()
            softKeyword()
            if (at(T.IDENT) && b.tokenText in T.SOFT_KEYWORDS) b.remapCurrentToken(T.SOFT_KEYWORD)
            b.advanceLexer()
            m.done(E.END_MARKER)
        }

        private fun packageClause() {
            val m = b.mark()
            if (b.lookAhead(1) == kw("object")) { b.advanceLexer(); classDeclaration(m); return }
            b.advanceLexer()
            val name = b.mark()
            expect(T.IDENT, "Package name")
            while (at(T.DOT) && b.lookAhead(1) == T.IDENT) { b.advanceLexer(); b.advanceLexer() }
            name.done(E.PACKAGE_NAME)
            if (at(T.LBRACE)) { b.advanceLexer(); statements { at(T.RBRACE) }; expect(T.RBRACE, "'}'") }
            if (at(T.COLON) && newlineAfter()) { b.advanceLexer(); if (startsIndentedBlock()) indented(E.BLOCK) }
            m.done(E.PACKAGE)
        }

        // ---------------------------------------------------------------- imports

        private fun importStatement() {
            val m = b.mark()
            if (!eat(kw("import"))) softKeyword()
            importExpr()
            while (eat(T.COMMA)) importExpr()
            m.done(E.IMPORT)
        }

        /** `a.b.C`, `a.b._`, `a.b.*`, `a.b.{C, D => E, _}`, `a.b.C as D`, `a.b.given`. */
        private fun importExpr() {
            val m = b.mark()
            var path = b.mark()
            if (at(T.IDENT) || at(kw("this")) || at(kw("super"))) leaf(E.REF)
            while (at(T.DOT) && isPathName(b.lookAhead(1)) && wordAhead(1) != "given") {
                b.advanceLexer(); b.advanceLexer()
                path.done(E.MEMBER)
                path = path.precede()
            }
            path.drop()
            if (eat(T.DOT)) importTail()
            if (eatWord("as") && !eat(T.IDENT)) eat(T.UNDERSCORE)
            m.done(E.IMPORT_EXPR)
        }

        private fun isPathName(t: IElementType?) = t == T.IDENT || t == kw("this") || t == kw("type")

        private fun importTail() {
            when {
                at(T.LBRACE) -> selectors()
                at(T.UNDERSCORE) || at(T.OP) -> b.advanceLexer()
                atWord("given") -> softKeyword()
                else -> b.error("Name expected")
            }
        }

        private fun selectors() {
            val m = b.mark()
            b.advanceLexer()
            grouped {
                while (!b.eof() && !at(T.RBRACE)) {
                    val before = b.currentOffset
                    selector()
                    if (!eat(T.COMMA) && b.currentOffset == before) break
                }
            }
            expect(T.RBRACE, "'}'")
            m.done(E.SELECTORS)
        }

        private fun selector() {
            val m = b.mark()
            when {
                atWord("given") -> { softKeyword(); type() }
                at(T.IDENT) || at(T.UNDERSCORE) || at(T.OP) -> b.advanceLexer()
                else -> { m.drop(); return }
            }
            if ((eat(T.ARROW) || eatWord("as")) && !eat(T.IDENT)) eat(T.UNDERSCORE)
            m.done(E.SELECTOR)
        }

        // ---------------------------------------------------------------- definitions

        private fun atDefinition(): Boolean = when {
            at(T.AT) -> true
            at(kw("implicit")) -> !startsImplicitLambda()
            at(kw("case")) -> b.lookAhead(1) == kw("class") || b.lookAhead(1) == kw("object")
            at in MODIFIERS || at in DEFINITION_KEYWORDS -> true
            else -> atSoftModifier() || atEnum() || atGiven() || atExtension()
        }

        private fun startsImplicitLambda(): Boolean {
            val name = b.lookAhead(1) == T.IDENT || b.lookAhead(1) == T.UNDERSCORE
            return name && (b.lookAhead(2) == T.ARROW || b.lookAhead(2) == T.COLON)
        }

        private fun atSoftModifier(): Boolean {
            if (!at(T.IDENT) || b.tokenText !in SOFT_MODIFIERS || newlineAfter()) return false
            val next = b.lookAhead(1)
            return next in MODIFIERS || next in DEFINITION_KEYWORDS || next == kw("case") || wordAhead(1)?.let { it in DEFINITION_WORDS } == true
        }

        private fun atEnum(): Boolean = atWord("enum") && b.lookAhead(1) == T.IDENT && !newlineAfter()

        private fun atGiven(): Boolean {
            if (!atWord("given") || newlineAfter()) return false
            val next = b.lookAhead(1)
            return next == T.IDENT || next == T.LBRACKET || (next == T.LPAREN && wordAhead(2) == "using" && afterGroup(1) == T.COLON)
        }

        private fun atExtension(): Boolean {
            if (!atWord("extension")) return false
            val next = b.lookAhead(1)
            return (next == T.LPAREN || next == T.LBRACKET) && afterGroup(1) in EXTENSION_FOLLOWERS
        }

        private fun definition() {
            val m = b.mark()
            modifiers()
            when {
                at(kw("class")) || at(kw("trait")) || at(kw("object")) || atEnum() -> classDeclaration(m)
                at(kw("val")) || at(kw("var")) -> valDeclaration(m)
                at(kw("def")) -> function(m)
                at(kw("type")) -> typeAlias(m)
                atGiven() -> given(m)
                atExtension() -> extension(m)
                inEnum && at(kw("case")) -> { enumCases(); m.drop() }
                else -> m.drop()
            }
        }

        private fun atModifier(): Boolean = when {
            at(kw("case")) -> b.lookAhead(1) == kw("class") || b.lookAhead(1) == kw("object")
            at(kw("implicit")) -> !startsImplicitLambda()
            else -> at(T.AT) || at in MODIFIERS || atSoftModifier()
        }

        private fun modifiers() {
            if (!atModifier()) return
            val m = b.mark()
            while (atModifier()) modifier()
            m.done(E.MODIFIERS)
        }

        private fun modifier() {
            if (at(T.AT)) return annotation()
            val access = at(kw("private")) || at(kw("protected"))
            if (at(T.IDENT)) b.remapCurrentToken(T.SOFT_KEYWORD)
            b.advanceLexer()
            if (access && at(T.LBRACKET)) skipGroup()
        }

        private fun annotation() {
            val m = b.mark()
            b.advanceLexer()
            simpleType()
            while (at(T.LPAREN) && !onNewLine()) args()
            m.done(E.ANNOTATION)
        }

        /** `class` / `trait` / `object` / `enum` / `package object`, with its parameters, parents and body. */
        private fun classDeclaration(m: PsiBuilder.Marker) {
            val enum = atEnum()
            if (enum) softKeyword()
            if (!enum) b.advanceLexer()
            name()
            if (at(T.LBRACKET)) typeParams()
            if (!onNewLine()) modifiers()
            while (atParamClause()) params()
            if (at(kw("extends"))) extendsClause()
            if (eatWord("derives")) { simpleType(); while (eat(T.COMMA)) simpleType() }
            templateBodyOpt(enum)
            m.done(E.CLASS)
        }

        private fun templateBodyOpt(enum: Boolean = false) {
            if (at(T.LBRACE) && !blankLineBefore()) return templateBody(enum)
            if (!at(T.COLON) || !newlineAfter()) return
            b.advanceLexer()
            if (startsIndentedBlock()) indented(E.TEMPLATE_BODY, enum)
        }

        /** `extends A(x) with B with C`, `extends A, B`, `extends { early } with A`. */
        private fun extendsClause() {
            val m = b.mark()
            b.advanceLexer()
            if (at(T.LBRACE)) templateBody()
            if (!at(kw("with"))) parent()
            while ((at(kw("with")) && b.lookAhead(1) != T.LBRACE && !lineEndsAfter(0)) || at(T.COMMA)) { b.advanceLexer(); parent() }
            m.done(E.EXTENDS)
        }

        private fun parent() {
            simpleType()
            while (at(T.AT)) annotation()
            while (at(T.LPAREN) && !newlineBefore()) args()
        }

        private fun templateBody(enum: Boolean = false) {
            val m = b.mark()
            b.advanceLexer()
            nested { selfType() }
            statements(enum = enum) { at(T.RBRACE) }
            expect(T.RBRACE, "'}'")
            m.done(E.TEMPLATE_BODY)
        }

        /** `self =>`, `self: T =>`, `this: T =>` at the top of a template body. */
        private fun selfType() {
            if (!at(T.IDENT) && !at(kw("this")) && !at(T.UNDERSCORE)) return
            val next = b.lookAhead(1)
            if (next != T.ARROW && next != T.COLON) return
            val m = b.mark()
            if (!at(T.IDENT)) b.advanceLexer()
            if (at(T.IDENT)) leaf(E.VARIABLE)
            noFunctionType = true
            if (eat(T.COLON)) type()
            noFunctionType = false
            if (!eat(T.ARROW)) { m.rollbackTo(); return }
            m.done(E.SELF_TYPE)
        }

        /** `case A, B`, `case C(x: Int) extends E(x)` in an enum. */
        private fun enumCases() {
            b.advanceLexer()
            do {
                val m = b.mark()
                name()
                if (at(T.LBRACKET)) typeParams()
                while (at(T.LPAREN) && !newlineBefore()) params()
                if (at(kw("extends"))) extendsClause()
                m.done(E.ENUM_CASE)
            } while (eat(T.COMMA))
        }

        private fun function(m: PsiBuilder.Marker) {
            b.advanceLexer()
            if (!eat(T.IDENT) && !eat(T.OP) && !eat(kw("this"))) b.error("Name expected")
            while (at(T.LBRACKET) || atParamClause()) {
                if (at(T.LBRACKET)) typeParams()
                if (at(T.LPAREN)) params()
            }
            if (eat(T.COLON)) type()
            if (eat(T.ASSIGN)) { eat(kw("macro")); body() }
            if (at(T.LBRACE) && !onNewLine()) block()
            m.done(E.FUNCTION)
        }

        /** A parameter clause may open the line after the previous one. */
        private fun atParamClause(): Boolean = at(T.LPAREN) && !blankLineBefore()

        private fun name() { if (!eat(T.IDENT) && !eat(T.OP)) b.error("Name expected") }

        private fun params() {
            val m = b.mark()
            b.advanceLexer()
            grouped {
                while (!b.eof() && !at(T.RPAREN)) {
                    val before = b.currentOffset
                    param()
                    if (!eat(T.COMMA) && b.currentOffset == before) break
                }
            }
            expect(T.RPAREN, "')'")
            m.done(E.PARAMS)
        }

        /** `[@a] [override] [val | var] [implicit | using] name: Type [= default]`; an anonymous `using Type` has no name. */
        private fun param() {
            val named = b.mark()
            while (at(T.AT) || at in MODIFIERS || at(kw("val")) || at(kw("var")) || atParamSoftModifier()) modifier()
            if (!(at(T.IDENT) && b.lookAhead(1) in PARAM_NAME_FOLLOWERS)) { named.drop(); type(); return }
            b.advanceLexer()
            if (eat(T.COLON)) paramType()
            if (eat(T.ASSIGN)) expr()
            named.done(E.PARAM)
        }

        private fun atParamSoftModifier(): Boolean =
            at(T.IDENT) && b.tokenText in PARAM_SOFT_MODIFIERS && b.lookAhead(1) != T.COLON && b.lookAhead(1) != T.COMMA && b.lookAhead(1) != T.RPAREN

        /** `=> T` (by name), `T*` (repeated). */
        private fun paramType() {
            type()
            if (atOp("*")) b.advanceLexer()
        }

        private fun typeParams() {
            val m = b.mark()
            b.advanceLexer()
            grouped {
                while (!b.eof() && !at(T.RBRACKET)) {
                    val before = b.currentOffset
                    typeParam()
                    if (!eat(T.COMMA) && b.currentOffset == before) break
                }
            }
            expect(T.RBRACKET, "']'")
            m.done(E.TYPE_PARAMS)
        }

        /** `[+A <: B : Ordering]`, `[F[_]]`, `[_]`. */
        private fun typeParam() {
            val m = b.mark()
            while (at(T.AT)) annotation()
            if (atOp("+") || atOp("-")) b.advanceLexer()
            if (!eat(T.IDENT) && !eat(T.UNDERSCORE)) { m.drop(); return }
            if (at(T.LBRACKET)) typeParams()
            bounds()
            m.done(E.TYPE_PARAM)
        }

        private fun bounds() {
            while (at in BOUNDS) {
                b.advanceLexer()
                if (at(T.LBRACE)) skipGroup()
                type()
            }
        }

        /** `val x = …`, `val x, y: T = …`, `val (a, b) = …`, `val Some(x) = …`. */
        private fun valDeclaration(m: PsiBuilder.Marker) {
            b.advanceLexer()
            do {
                when {
                    atSimpleVariable() -> leaf(E.VARIABLE)
                    else -> pattern()
                }
            } while (eat(T.COMMA))
            if (eat(T.COLON)) type()
            if (eat(T.ASSIGN)) body()
            m.done(E.VAL_DECL)
        }

        private fun atSimpleVariable(): Boolean = (at(T.IDENT) || at(T.OP)) && (b.lookAhead(1) in VARIABLE_FOLLOWERS || newlineAfter())

        private fun typeAlias(m: PsiBuilder.Marker) {
            b.advanceLexer()
            name()
            if (at(T.LBRACKET)) typeParams()
            bounds()
            typeGroups++
            if (eat(T.ASSIGN)) type()
            typeGroups--
            if (eat(kw("match"))) matchType()
            m.done(E.TYPE_ALIAS)
        }

        /** `X match { case A => B }`, with or without braces: only skipped over. */
        private fun matchType() {
            if (at(T.LBRACE)) return skipGroup()
            while (atCaseClause()) {
                b.advanceLexer()
                patternType()
                expect(T.ARROW, "'=>'")
                type()
            }
        }

        /** `given name: Type with …`, `given Type = …`, `given [T](using Ord[T]): Ord[List[T]] with …`. */
        private fun given(m: PsiBuilder.Marker) {
            softKeyword()
            val named = at(T.IDENT) && (b.lookAhead(1) == T.COLON || (b.lookAhead(1) in GROUP_OPENERS && afterGroups(1) == T.COLON))
            if (named) b.advanceLexer()
            while (at(T.LBRACKET) || at(T.LPAREN)) {
                if (at(T.LBRACKET)) typeParams()
                if (at(T.LPAREN)) params()
            }
            if (!eat(T.COLON)) eat(T.ARROW)
            if (at(T.LBRACKET)) { typeParams(); eat(T.ARROW) }
            parent()
            if (eat(kw("with"))) givenBody()
            if (eat(T.ASSIGN)) body()
            templateBodyOpt()
            m.done(E.GIVEN)
        }

        private fun givenBody() {
            if (at(T.LBRACE)) return templateBody()
            if (startsIndentedBlock()) indented(E.TEMPLATE_BODY)
        }

        /** The token after the consecutive bracket groups that start at lookahead [from]. */
        private fun afterGroups(from: Int): IElementType? {
            val end = generateSequence(groupEnd(from)) { last -> groupEnd(last + 1).takeIf { b.lookAhead(last + 1) in GROUP_OPENERS } }.lastOrNull()
            return end?.let { b.lookAhead(it + 1) }
        }

        /** `extension (x: T) def …`, with one definition, a block or an indentation region of them. */
        private fun extension(m: PsiBuilder.Marker) {
            softKeyword()
            while (at(T.LBRACKET) || at(T.LPAREN)) {
                if (at(T.LBRACKET)) typeParams()
                if (at(T.LPAREN)) params()
            }
            when {
                at(T.LBRACE) -> block()
                startsIndentedBlock() -> indented(E.BLOCK)
                else -> statement()
            }
            m.done(E.EXTENSION)
        }

        // ---------------------------------------------------------------- types

        /** Any type; false (nothing consumed) when no type is there. */
        private fun type(): Boolean {
            val m = b.mark()
            if (at(T.ARROW)) { b.advanceLexer(); type(); m.done(E.COMPOUND_TYPE); return true }
            if (at(T.LBRACKET)) { skipGroup(); eatFunctionArrow(); type(); m.done(E.COMPOUND_TYPE); return true }
            if (!infixType()) { m.drop(); return false }
            if (noFunctionType || !eatFunctionArrow()) { m.drop(); return true }
            type()
            m.done(E.COMPOUND_TYPE)
            return true
        }

        private fun eatFunctionArrow(): Boolean {
            if (eat(T.ARROW)) return true
            if (!atOp("?=>") && !atOp("=>>")) return false
            b.advanceLexer()
            return true
        }

        /** `A | B`, `A & B`, `A :: B`, `A with B`: only symbolic operators join types. */
        private fun infixType(): Boolean {
            val m = b.mark()
            if (!compoundType()) { m.drop(); return false }
            val start = b.currentOffset
            while (at(T.OP) && !newlineBefore() && !(noBarType && atOp("|")) && startsType(b.lookAhead(1))) { b.advanceLexer(); compoundType() }
            if (eat(kw("forSome")) && at(T.LBRACE)) skipGroup()
            if (b.currentOffset == start) { m.drop(); return true }
            m.done(E.COMPOUND_TYPE)
            return true
        }

        private fun startsType(t: IElementType?) = t == T.IDENT || t == T.LPAREN || t == T.UNDERSCORE || t == kw("this") || t in LITERALS

        private fun compoundType(): Boolean {
            val m = b.mark()
            if (!annotatedType()) { m.drop(); return false }
            val start = b.currentOffset
            while (at(kw("with")) && startsType(b.lookAhead(1))) { b.advanceLexer(); annotatedType() }
            if (atRefinement()) skipGroup()
            if (b.currentOffset == start) { m.drop(); return true }
            m.done(E.COMPOUND_TYPE)
            return true
        }

        /** `T { type X = … }`: braces inside a type's brackets, or followed by what cannot follow a block. */
        private fun atRefinement(): Boolean = at(T.LBRACE) && (typeGroups > 0 || (!onNewLine() && afterGroup(0) in REFINEMENT_FOLLOWERS))

        private fun annotatedType(): Boolean {
            if (!simpleType()) return false
            if (eatOp(CAPTURES) && at(T.LBRACE) && !onNewLine()) skipGroup()
            while (at(T.AT) && !newlineBefore()) annotation()
            return true
        }

        private fun simpleType(): Boolean {
            val m = b.mark()
            when {
                at(T.IDENT) || at(kw("this")) || at(kw("super")) -> { namedType(); m.done(E.TYPE) }
                at(T.LPAREN) -> { tupleType(); projections(); m.done(E.COMPOUND_TYPE) }
                at(T.UNDERSCORE) || atOp("?") -> { b.advanceLexer(); bounds(); m.done(E.COMPOUND_TYPE) }
                at(T.LBRACE) && typeGroups > 0 -> { skipGroup(); m.done(E.COMPOUND_TYPE) }
                at in LITERALS -> { b.advanceLexer(); m.done(E.COMPOUND_TYPE) }
                atOp("-") && b.lookAhead(1) == T.NUMBER -> { b.advanceLexer(); b.advanceLexer(); m.done(E.COMPOUND_TYPE) }
                at(T.OP) -> { namedType(); m.done(E.TYPE) }
                else -> { m.drop(); return false }
            }
            return true
        }

        /** `a.b.C[X]`, `x.type`, `A#B`. */
        private fun namedType() {
            b.advanceLexer()
            while (at(T.DOT) && (isPathName(b.lookAhead(1)) || b.lookAhead(1) == T.OP)) { b.advanceLexer(); b.advanceLexer() }
            if (at(T.LBRACKET) && !onNewLine()) typeArgs()
            projections()
        }

        /** `#Name[X]` after a type. */
        private fun projections() {
            while (at(T.HASH) && b.lookAhead(1) == T.IDENT) {
                b.advanceLexer(); b.advanceLexer()
                if (at(T.LBRACKET) && !onNewLine()) typeArgs()
            }
        }

        /** `(A, B)`, `(A, B) => C` (the arrow is read by the caller), `(x: A) => x.T`. */
        private fun tupleType() {
            b.advanceLexer()
            typeList(T.RPAREN)
            expect(T.RPAREN, "')'")
        }

        private fun typeList(close: IElementType) = grouped {
            typeGroups++
            while (!b.eof() && !at(close)) {
                val before = b.currentOffset
                if (at(T.IDENT) && b.lookAhead(1) == T.COLON) { b.advanceLexer(); b.advanceLexer() }
                type()
                if (atOp("*")) b.advanceLexer()
                if (!eat(T.COMMA) && b.currentOffset == before) break
            }
            typeGroups--
        }

        private fun typeArgs() {
            val m = b.mark()
            b.advanceLexer()
            typeList(T.RBRACKET)
            expect(T.RBRACKET, "']'")
            m.done(E.TYPE_ARGS)
        }

        // ---------------------------------------------------------------- patterns

        private fun pattern() {
            val m = b.mark()
            alternatives()
            m.done(E.PATTERN)
        }

        private fun alternatives() {
            typedPattern()
            while (atOp("|")) { b.advanceLexer(); typedPattern() }
        }

        /** `x: T`, `_: T`, `x @ pattern`, else an infix pattern. */
        private fun typedPattern() {
            val name = at(T.IDENT) || at(T.UNDERSCORE)
            if (name && b.lookAhead(1) == T.COLON) return typedVariable()
            if (name && b.lookAhead(1) == T.AT) {
                if (at(T.IDENT)) leaf(E.VARIABLE)
                eat(T.UNDERSCORE)
                b.advanceLexer()
            }
            if (atWord("given") && b.lookAhead(1) == T.IDENT) { softKeyword(); patternType(); return }
            infixPattern()
        }

        private fun typedVariable() {
            val m = b.mark()
            val named = at(T.IDENT)
            b.advanceLexer(); b.advanceLexer()
            patternType()
            if (!named) return m.drop()
            m.done(E.VARIABLE)
        }

        private fun patternType() {
            val flags = noFunctionType to noBarType
            noFunctionType = true; noBarType = true
            if (!type()) b.error("Type expected")
            noFunctionType = flags.first; noBarType = flags.second
        }

        private fun infixPattern() {
            var m = b.mark()
            simplePattern()
            while (at(T.OP) && !atOp("|") && !newlineBefore()) {
                leaf(E.OP_REF)
                simplePattern()
                m.done(E.INFIX)
                m = m.precede()
            }
            m.drop()
        }

        private fun simplePattern() {
            when {
                at(T.UNDERSCORE) -> { b.advanceLexer(); if (atOp("*")) b.advanceLexer() }
                at in LITERALS -> leaf(E.LITERAL)
                atOp("-") && b.lookAhead(1) == T.NUMBER -> { val m = b.mark(); b.advanceLexer(); b.advanceLexer(); m.done(E.LITERAL) }
                at(T.INTERPOLATOR) -> interpolatedString()
                at(T.QUOTE) -> { b.advanceLexer(); if (at(T.LBRACE) || at(T.LBRACKET)) skipGroup() }
                at(T.LPAREN) -> patternGroup()
                atBinding() -> { leaf(E.VARIABLE); if (atOp("*")) b.advanceLexer() }
                at(T.IDENT) || at(kw("this")) -> stablePattern()
                else -> b.error("Pattern expected")
            }
        }

        /** A plain name that starts in lower case binds a variable; `Name`, `` `name` `` and `a.b` refer to a value. */
        private fun atBinding(): Boolean {
            val first = b.tokenText?.firstOrNull() ?: return false
            return at(T.IDENT) && (first.isLowerCase() || first == '_') && b.lookAhead(1) !in STABLE_ID_FOLLOWERS
        }

        /** `Name`, `a.b.Name`, `Name(patterns)`, `Name[T](patterns)`. */
        private fun stablePattern() {
            var m = b.mark()
            leaf(E.REF)
            while (at(T.DOT) && (b.lookAhead(1) == T.IDENT || b.lookAhead(1) == T.OP)) {
                b.advanceLexer(); b.advanceLexer()
                m.done(E.MEMBER)
                m = m.precede()
            }
            m.drop()
            if (at(T.LBRACKET)) typeArgs()
            if (at(T.LPAREN) && !newlineBefore()) patternGroup()
        }

        private fun patternGroup() {
            b.advanceLexer()
            grouped {
                while (!b.eof() && !at(T.RPAREN)) {
                    val before = b.currentOffset
                    if (at(T.IDENT) && b.lookAhead(1) == T.ASSIGN) { b.advanceLexer(); b.advanceLexer() }
                    alternatives()
                    if (!eat(T.COMMA) && b.currentOffset == before) break
                }
            }
            expect(T.RPAREN, "')'")
        }

        // ---------------------------------------------------------------- expressions

        /** What follows `=`, `=>`, `then`, `else`, `do`, `yield`, `try`, `finally`: an indentation region or one expression. */
        private fun body() {
            if (startsIndentedBlock()) return indented(E.BLOCK)
            expr()
        }

        fun expr() {
            when (at) {
                kw("if") -> ifExpr()
                kw("while") -> whileExpr()
                kw("do") -> doWhile()
                kw("for") -> forExpr()
                kw("try") -> tryExpr()
                kw("throw") -> { val m = b.mark(); b.advanceLexer(); expr(); m.done(E.THROW) }
                kw("return") -> { val m = b.mark(); b.advanceLexer(); if (!atExprEnd()) expr(); m.done(E.RETURN) }
                else -> if (!lambda()) operatorExpr()
            }
        }

        private fun atExprEnd() = b.eof() || at !in EXPR_STARTS || newlineBefore()

        /** `x => …`, `_ => …`, `x: T => …`, `implicit x => …`, `(a, b) => …`, `(a: A, b: B) => …`. */
        private fun lambda(): Boolean {
            if (noLambda) return false
            val braced = b.currentOffset == bracedStatement
            val m = b.mark()
            eat(kw("implicit"))
            val header = when {
                at(T.LPAREN) -> afterGroup(0) == T.ARROW && lambdaParams()
                at(T.IDENT) || at(T.UNDERSCORE) -> b.lookAhead(1) == T.ARROW || b.lookAhead(1) == T.COLON || opAhead(1) == CONTEXT_ARROW
                else -> false
            }
            if (header && !at(T.ARROW)) lambdaParam()
            if (!header || !(eat(T.ARROW) || eatOp(CONTEXT_ARROW))) { m.rollbackTo(); return false }
            lambdaBody(braced)
            m.done(E.LAMBDA)
            return true
        }

        private fun lambdaParams(): Boolean { params(); return true }

        private fun lambdaParam() {
            val ps = b.mark()
            val p = b.mark()
            val named = at(T.IDENT)
            b.advanceLexer()
            noFunctionType = true
            if (eat(T.COLON)) type()
            noFunctionType = false
            if (named) p.done(E.PARAM)
            if (!named) p.drop()
            ps.done(E.PARAMS)
        }

        /** Directly in `{ }`, a lambda owns the statements up to the closing brace. */
        private fun lambdaBody(braced: Boolean) {
            if (!braced) return body()
            val m = b.mark()
            statements(braced = true) { at(T.RBRACE) || atCaseClause() }
            m.done(E.BLOCK)
        }

        /** An infix expression, then `= value`, `: Type` and `match { … }`. */
        private fun operatorExpr() {
            var m = b.mark()
            infix()
            while (true) {
                when {
                    at(T.ASSIGN) -> { b.advanceLexer(); body(); m.done(E.ASSIGN) }
                    at(T.COLON) -> { b.advanceLexer(); ascription(); m.done(E.ASCRIPTION) }
                    at(kw("match")) -> { b.advanceLexer(); matchBody(); m.done(E.MATCH) }
                    else -> break
                }
                m = m.precede()
            }
            m.drop()
        }

        /** `x: T`, `xs: _*`, `x: @unchecked`. */
        private fun ascription() {
            if (at(T.UNDERSCORE) && b.lookAhead(1) == T.OP) { b.advanceLexer(); b.advanceLexer(); return }
            if (at(T.AT)) { while (at(T.AT)) annotation(); return }
            if (!type()) b.error("Type expected")
        }

        private fun matchBody() {
            if (at(T.LBRACE)) return caseBlock()
            if (atCaseClause()) return caseClauses()
            b.error("'{' expected")
        }

        /** `a op b` and `a op`, left to right: precedence does not matter to scopes. */
        private fun infix() {
            var m = b.mark()
            prefix()
            while (atInfixOperator()) {
                val alphanumeric = at(T.IDENT)
                val line = indentOfLine(b.currentOffset)
                leaf(E.OP_REF)
                if (at(T.LBRACKET) && !onNewLine()) typeArgs()
                val operand = startsOperand(alphanumeric, line)
                if (operand) prefix()
                m.done(when { operand -> E.INFIX; else -> E.POSTFIX })
                m = m.precede()
            }
            m.drop()
        }

        /** A name after a value on its line; a symbolic operator may also open a line, when a space follows it (Scala 3). */
        private fun atInfixOperator(): Boolean {
            if (!at(T.IDENT) && !at(T.OP)) return false
            if (inCondition && atWord("then")) return false
            if (groups > 0 || !onNewLine()) return true
            return at(T.OP) && isTrivia(b.rawLookup(1)) && !newlineAfter()
        }

        /** Whether a right operand follows; after a line break, a name used as an operator needs a deeper-indented one. */
        private fun startsOperand(alphanumeric: Boolean, lineIndent: Int): Boolean {
            if (b.eof() || at !in EXPR_STARTS) return false
            if (at(T.OP) && b.tokenText !in PREFIX_OPERATORS && b.tokenText != UNIMPLEMENTED) return false
            if (groups > 0 || !onNewLine()) return true
            if (blankLineBefore()) return false
            return !alphanumeric || column() > lineIndent
        }

        private fun prefix() {
            if (!at(T.OP) || b.tokenText !in PREFIX_OPERATORS) return postfix()
            val m = b.mark()
            b.advanceLexer()
            prefix()
            m.done(E.PREFIX)
        }

        /** A value and what applies to it: `.name`, `[T]`, `(args)`, `{ block }`, `: indented block`, `_`. */
        private fun postfix() {
            var m = b.mark()
            if (!primary()) { m.drop(); return }
            while (true) {
                when {
                    at(T.DOT) && b.lookAhead(1) == kw("match") -> { b.advanceLexer(); b.advanceLexer(); matchBody(); m.done(E.MATCH) }
                    at(T.DOT) && leavesRegion() -> break
                    at(T.DOT) -> { b.advanceLexer(); memberName(); m.done(E.MEMBER) }
                    at(T.LBRACKET) && !onNewLine() -> { typeArgs(); m.done(E.TYPE_APPLY) }
                    at(T.LPAREN) && !newlineBefore() -> { args(); m.done(E.CALL) }
                    at(T.LBRACE) && !newlineBefore() -> { blockExpr(); m.done(E.CALL) }
                    at(T.COLON) && opensIndentedArgument() -> { indentedArgument(); m.done(E.CALL) }
                    at(T.UNDERSCORE) && !newlineBefore() && b.lookAhead(1) != T.ARROW -> { b.advanceLexer(); m.done(E.POSTFIX) }
                    else -> break
                }
                m = m.precede()
            }
            m.drop()
        }

        private fun memberName() {
            if (at(T.IDENT) || at(T.OP) || at in MEMBER_KEYWORDS) return b.advanceLexer()
            b.error("Name expected")
        }

        /** Scala 3: `xs.map: x =>` or `foo:` at the end of a line, then an indented argument. */
        private fun opensIndentedArgument(): Boolean {
            val rest = restOfLine().drop(1).toList()
            if (rest.isEmpty()) return true
            return b.rawLookup(rest.last()) == T.ARROW
        }

        private fun indentedArgument() {
            b.advanceLexer()
            if (lambda()) return
            if (startsIndentedBlock()) indented(E.BLOCK)
        }

        private fun leaf(type: IElementType) {
            val m = b.mark(); b.advanceLexer(); m.done(type)
        }

        private fun primary(): Boolean {
            when (at) {
                T.IDENT, T.OP, kw("this"), kw("super") -> leaf(E.REF)
                T.UNDERSCORE -> leaf(E.PLACEHOLDER)
                T.INTERPOLATOR -> interpolatedString()
                T.LPAREN -> paren()
                T.LBRACE -> blockExpr()
                T.QUOTE -> quoted()
                kw("new") -> newExpr()
                kw("if"), kw("while"), kw("do"), kw("for"), kw("try"), kw("throw"), kw("return") -> expr()
                else -> return literal()
            }
            return true
        }

        private fun literal(): Boolean {
            if (at in LITERALS) { leaf(E.LITERAL); return true }
            b.error("Expression expected")
            return false
        }

        /** Scala 3 quotes: `'{ … }`, `'[ T ]`. */
        private fun quoted() {
            b.advanceLexer()
            if (at(T.LBRACKET)) return skipGroup()
            if (at(T.LBRACE)) blockExpr()
        }

        private fun paren() {
            val m = b.mark()
            b.advanceLexer()
            grouped {
                while (!b.eof() && !at(T.RPAREN)) {
                    val before = b.currentOffset
                    expr()
                    if (!eat(T.COMMA) && b.currentOffset == before) break
                }
            }
            expect(T.RPAREN, "')'")
            m.done(E.PAREN)
        }

        private fun args() {
            val m = b.mark()
            b.advanceLexer()
            grouped {
                if (atWord("using") && b.lookAhead(1) in EXPR_STARTS) softKeyword()
                while (!b.eof() && !at(T.RPAREN)) {
                    val before = b.currentOffset
                    argument()
                    if (!eat(T.COMMA) && b.currentOffset == before) break
                }
            }
            expect(T.RPAREN, "')'")
            m.done(E.ARGS)
        }

        private fun argument() {
            if (!(at(T.IDENT) && b.lookAhead(1) == T.ASSIGN)) return expr()
            val m = b.mark()
            b.advanceLexer(); b.advanceLexer()
            expr()
            m.done(E.NAMED_ARG)
        }

        private fun block() {
            val m = b.mark()
            b.advanceLexer()
            statements(braced = true) { at(T.RBRACE) }
            expect(T.RBRACE, "'}'")
            m.done(E.BLOCK)
        }

        /** `{ statements }` or `{ case … => … }`. */
        private fun blockExpr() {
            if (b.lookAhead(1) == kw("case") && b.lookAhead(2) != kw("class") && b.lookAhead(2) != kw("object")) return caseBlock()
            block()
        }

        private fun atCaseClause() = at(kw("case")) && b.lookAhead(1) != kw("class") && b.lookAhead(1) != kw("object")

        private fun caseBlock() {
            val m = b.mark()
            b.advanceLexer()
            nested {
                while (!b.eof() && !at(T.RBRACE)) {
                    if (atCaseClause()) { caseClause(null); continue }
                    b.error("'case' expected")
                    b.advanceLexer()
                }
            }
            expect(T.RBRACE, "'}'")
            m.done(E.BLOCK)
        }

        /** Scala 3 clauses without braces: the one at the cursor and those that start lines at its column. */
        private fun caseClauses() = nested {
            val column = column()
            val indent = indentOfLine(b.currentOffset)
            caseClause(indent)
            while (atCaseClause() && onNewLine() && column() == column) caseClause(indent)
        }

        /** `case pattern [if guard] => statements`; without braces the statements end at a line indented like the one of the `case`, or less. */
        private fun caseClause(column: Int?) {
            val m = b.mark()
            b.advanceLexer()
            pattern()
            if (at(kw("if"))) guard()
            expect(T.ARROW, "'=>'")
            val body = b.mark()
            statements(braced = column == null) { atCaseClause() || at in CLOSERS || (column != null && onNewLine() && column() <= column) }
            body.done(E.BLOCK)
            m.done(E.CASE_CLAUSE)
        }

        private fun guard() {
            val m = b.mark()
            b.advanceLexer()
            val outer = noLambda
            noLambda = true
            operatorExpr()
            noLambda = outer
            m.done(E.GUARD)
        }

        private fun newExpr() {
            val m = b.mark()
            b.advanceLexer()
            when {
                at(T.LBRACE) -> templateBody()
                else -> parent()
            }
            while (at(kw("with")) && b.lookAhead(1) != T.LBRACE) { b.advanceLexer(); parent() }
            if (at(T.LBRACE) && !onNewLine()) templateBody()
            if (at(T.COLON) && newlineAfter() && groups == 0) templateBodyOpt()
            m.done(E.NEW)
        }

        private fun interpolatedString() {
            val m = b.mark()
            b.advanceLexer()
            expect(T.ISTRING_BEGIN, "String")
            while (at(T.ISTRING_TEXT) || at(T.DOLLAR)) {
                if (eat(T.ISTRING_TEXT)) continue
                b.advanceLexer()
                if (at(T.LBRACE)) injection()
                if (at(T.IDENT) || at(kw("this"))) leaf(E.REF)
            }
            if (!eat(T.ISTRING_END)) b.error("Unclosed string")
            m.done(E.ISTRING)
        }

        private fun injection() {
            val m = b.mark()
            b.advanceLexer()
            statements { at(T.RBRACE) }
            expect(T.RBRACE, "'}'")
            m.done(E.INJECTION)
        }

        // ---------------------------------------------------------------- control

        private fun condition() {
            val m = b.mark()
            b.advanceLexer()
            grouped { if (!at(T.RPAREN)) expr() }
            expect(T.RPAREN, "')'")
            m.done(E.PAREN)
        }

        private fun lineHas(word: (Int) -> Boolean): Boolean = restOfLine().any(word)

        /** `if (c) a else b`, `if c then a else b`; in an indentation region, an `else` left of the line of the `if` belongs to an outer one. */
        private fun ifExpr() {
            val m = b.mark()
            val indent = indentOfLine(b.currentOffset)
            b.advanceLexer()
            val scala3 = conditionContinues() || lineHas { b.rawLookup(it) == T.IDENT && rawText(it) == "then" }
            val parenthesized = !scala3 && at(T.LPAREN)
            if (parenthesized) condition()
            if (!parenthesized) conditionUntil { eatWord("then") }
            body()
            if (at(T.SEMI) && b.lookAhead(1) == kw("else")) b.advanceLexer()
            if (at(kw("else")) && !(regionColumn >= 0 && onNewLine() && column() < indent)) { b.advanceLexer(); body() }
            m.done(E.IF)
        }

        /** `(a) && b`, `(a).c`: the parentheses only open the condition, which a keyword will close. */
        private fun conditionContinues(): Boolean {
            val close = groupEnd(0)?.takeIf { at(T.LPAREN) } ?: return false
            if (b.lookAhead(close + 1) == T.DOT) return true
            return opAhead(close + 1)?.let { it !in PREFIX_OPERATORS && it != UNIMPLEMENTED } == true
        }

        /** A condition without parentheses, closed by a keyword. */
        private fun conditionUntil(close: () -> Boolean) {
            if (close()) return
            val outer = inCondition
            inCondition = true
            if (at in EXPR_STARTS) expr()
            inCondition = outer
            close()
        }

        /** `while (c) body`, `while c do body`. */
        private fun whileExpr() {
            val m = b.mark()
            b.advanceLexer()
            val scala3 = conditionContinues() || lineHas { b.rawLookup(it) == kw("do") }
            if (!scala3 && at(T.LPAREN)) condition()
            if (scala3) conditionUntil { eat(kw("do")) }
            if (!atExprEnd() || startsIndentedBlock()) body()
            m.done(E.WHILE)
        }

        private fun doWhile() {
            val m = b.mark()
            b.advanceLexer()
            body()
            while (eat(T.SEMI)) Unit
            if (eat(kw("while")) && at(T.LPAREN)) condition()
            m.done(E.DO_WHILE)
        }

        /** `for (enumerators) body`, `for { enumerators } yield body`, `for enumerators do body`. */
        private fun forExpr() {
            val m = b.mark()
            b.advanceLexer()
            when {
                at(T.LPAREN) && afterGroup(0) != T.LARROW -> { b.advanceLexer(); grouped { enumerators { at(T.RPAREN) } }; expect(T.RPAREN, "')'") }
                at(T.LBRACE) -> { b.advanceLexer(); nested { enumerators { at(T.RBRACE) } }; expect(T.RBRACE, "'}'") }
                else -> nested { enumerators { at(kw("yield")) || at(kw("do")) || at in CLOSERS } }
            }
            if (!eat(kw("yield"))) eat(kw("do"))
            body()
            m.done(E.FOR)
        }

        private fun enumerators(stop: () -> Boolean) {
            while (!b.eof() && !stop()) {
                val before = b.currentOffset
                enumerator()
                while (eat(T.SEMI)) Unit
                if (b.currentOffset == before) { b.error("Unexpected '${b.tokenText}'"); b.advanceLexer() }
            }
        }

        /** `pattern <- value`, `pattern = value`, `if guard`. */
        private fun enumerator() {
            if (at(kw("if"))) return guard()
            val m = b.mark()
            if (!eat(kw("val"))) eat(kw("case"))
            pattern()
            if (eat(T.LARROW) || eat(T.ASSIGN)) expr()
            m.done(E.GENERATOR)
        }

        private fun tryExpr() {
            val m = b.mark()
            b.advanceLexer()
            body()
            if (at(kw("catch"))) catchClause()
            if (at(kw("finally"))) { val f = b.mark(); b.advanceLexer(); body(); f.done(E.FINALLY) }
            m.done(E.TRY)
        }

        private fun catchClause() {
            val m = b.mark()
            b.advanceLexer()
            when {
                atCaseClause() -> caseClauses()
                else -> expr()
            }
            m.done(E.CATCH)
        }

        private companion object {
            const val GROUP_LOOKAHEAD = 400
            const val UNIMPLEMENTED = "???"
            const val CONTEXT_ARROW = "?=>"
            const val CAPTURES = "^"
            const val LINE_LOOKAHEAD = 400
            val BOUNDS = TokenSet.create(T.COLON, T.LOWER_BOUND, T.UPPER_BOUND, T.VIEW_BOUND)
            val CLOSERS = TokenSet.create(T.RBRACE, T.RBRACKET, T.RPAREN)
            val CONTINUATIONS = TokenSet.create(
                T.ARROW, T.ASSIGN, T.COLON, T.COMMA, T.DOT, T.HASH, T.LARROW, T.LBRACKET, T.LOWER_BOUND, T.RBRACE, T.RBRACKET, T.RPAREN, T.SEMI,
                T.UPPER_BOUND, T.VIEW_BOUND, T.kw("catch"), T.kw("else"), T.kw("extends"), T.kw("finally"), T.kw("forSome"), T.kw("match"),
                T.kw("with"), T.kw("yield"),
            )
            val DEFINITION_KEYWORDS = TokenSet.create(T.kw("class"), T.kw("def"), T.kw("object"), T.kw("trait"), T.kw("type"), T.kw("val"), T.kw("var"))
            val DEFINITION_WORDS = setOf("enum", "given", "infix", "inline", "opaque", "open", "transparent")
            val END_TARGETS = TokenSet.create(
                T.IDENT, T.kw("for"), T.kw("if"), T.kw("match"), T.kw("new"), T.kw("this"), T.kw("try"), T.kw("val"), T.kw("while"),
            )
            val LITERALS = TokenSet.create(T.CHAR, T.NUMBER, T.STRING, T.SYMBOL, T.kw("false"), T.kw("null"), T.kw("true"))
            val EXPR_STARTS = TokenSet.orSet(
                LITERALS,
                TokenSet.create(
                    T.IDENT, T.INTERPOLATOR, T.LBRACE, T.LPAREN, T.OP, T.QUOTE, T.UNDERSCORE, T.kw("do"), T.kw("for"), T.kw("if"), T.kw("implicit"),
                    T.kw("new"), T.kw("return"), T.kw("super"), T.kw("this"), T.kw("throw"), T.kw("try"), T.kw("while"),
                ),
            )
            val EXTENSION_FOLLOWERS = TokenSet.create(T.LBRACE, T.LBRACKET, T.LPAREN, T.kw("def"), T.kw("private"), T.kw("protected"))
            val GROUP_OPENERS = TokenSet.create(T.LBRACKET, T.LPAREN)
            val MEMBER_KEYWORDS = TokenSet.create(T.kw("super"), T.kw("this"), T.kw("type"))
            val MODIFIERS = TokenSet.create(
                T.kw("abstract"), T.kw("final"), T.kw("implicit"), T.kw("lazy"), T.kw("override"), T.kw("private"), T.kw("protected"), T.kw("sealed"),
            )
            val PARAM_NAME_FOLLOWERS = TokenSet.create(T.ASSIGN, T.COLON, T.COMMA, T.RPAREN)
            val PARAM_SOFT_MODIFIERS = setOf("erased", "inline", "using")
            val PREFIX_OPERATORS = setOf("!", "+", "-", "~")
            val REFINEMENT_FOLLOWERS = TokenSet.create(T.ASSIGN, T.COMMA, T.RBRACKET, T.RPAREN)
            val SOFT_MODIFIERS = setOf("infix", "inline", "opaque", "open", "transparent")
            val STABLE_ID_FOLLOWERS = TokenSet.create(T.DOT, T.LBRACKET, T.LPAREN)
            val VARIABLE_FOLLOWERS = TokenSet.create(T.ASSIGN, T.COLON, T.COMMA, T.RBRACE, T.SEMI)
        }
    }
}

class ScalaParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ScalaLexer()
    override fun createParser(project: Project?): PsiParser = ScalaParser()
    override fun getFileNodeType(): IFileElementType = E.FILE
    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = T.STRINGS
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ScalaFile(viewProvider)
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        E.CALL -> ScalaCall(node)
        E.CLASS -> ScalaClass(node)
        E.ENUM_CASE -> ScalaEnumCase(node)
        E.FUNCTION -> ScalaFunction(node)
        E.GIVEN -> ScalaGiven(node)
        E.IMPORT_EXPR -> ScalaImportExpr(node)
        E.MEMBER -> ScalaMember(node)
        E.NAMED_ARG -> ScalaNamedArg(node)
        E.OP_REF -> ScalaOpRef(node)
        E.PARAM, E.VARIABLE -> ScalaVariable(node)
        E.REF -> ScalaRef(node)
        E.SELECTOR -> ScalaSelector(node)
        E.TYPE -> ScalaType(node)
        E.TYPE_ALIAS -> ScalaTypeAlias(node)
        E.TYPE_PARAM -> ScalaTypeParam(node)
        else -> ScalaElement(node)
    }
}
