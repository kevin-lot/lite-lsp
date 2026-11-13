package me.kevinlot.lite.lua

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
import me.kevinlot.lite.lua.LuaTokens as T

class LuaElementType(debugName: String) : IElementType(debugName, LuaLanguage)

object LuaElementTypes {
    val FILE = IFileElementType("LITE_LUA_FILE", LuaLanguage)
    private fun e(n: String) = LuaElementType(n)
    val BLOCK = e("BLOCK")
    val LOCAL_STMT = e("LOCAL_STMT")              // local a <const>, b = …
    val LOCAL_FUNCTION = e("LOCAL_FUNCTION")      // local function f() … end
    val FUNCTION_STMT = e("FUNCTION_STMT")        // function a.b:c() … end
    val FUNC_NAME = e("FUNC_NAME")
    val ASSIGN_STMT = e("ASSIGN_STMT")
    val TARGETS = e("TARGETS")                    // left-hand side of an assignment
    val CALL_STMT = e("CALL_STMT")
    val DO_STMT = e("DO_STMT"); val WHILE_STMT = e("WHILE_STMT"); val REPEAT_STMT = e("REPEAT_STMT"); val IF_STMT = e("IF_STMT")
    val FOR_NUM = e("FOR_NUM"); val FOR_IN = e("FOR_IN")
    val RETURN_STMT = e("RETURN_STMT"); val BREAK_STMT = e("BREAK_STMT"); val GOTO_STMT = e("GOTO_STMT"); val LABEL_STMT = e("LABEL_STMT")
    val NAME_DECL = e("NAME_DECL")                // local, parameter, loop variable, label
    val NAME_REF = e("NAME_REF")
    val INDEX_EXPR = e("INDEX_EXPR")              // a.b, a:b
    val BRACKET_INDEX = e("BRACKET_INDEX")        // a[b]
    val CALL_EXPR = e("CALL_EXPR")
    val ARGS = e("ARGS")
    val FUNCTION_EXPR = e("FUNCTION_EXPR")
    val FUNC_BODY = e("FUNC_BODY")
    val PARAMS = e("PARAMS")
    val TABLE = e("TABLE")
    val TABLE_FIELD = e("TABLE_FIELD")
    val BINARY = e("BINARY"); val UNARY = e("UNARY"); val PAREN = e("PAREN"); val LITERAL = e("LITERAL")
}

private typealias E = LuaElementTypes

/** Recursive descent over Lua 5.4's grammar; errors are reported and skipped one token at a time. */
class LuaParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val m = builder.mark()
        Impl(builder).run {
            block()
            while (!builder.eof()) { builder.error("Unexpected '${builder.tokenText}'"); builder.advanceLexer(); block() }
        }
        m.done(root)
        return builder.treeBuilt
    }

    private class Impl(val b: PsiBuilder) {
        private val at get() = b.tokenType
        private fun at(t: IElementType) = b.tokenType == t
        private fun eat(t: IElementType): Boolean { if (b.tokenType == t) { b.advanceLexer(); return true }; return false }
        private fun expect(t: IElementType, what: String) { if (!eat(t)) b.error("$what expected") }
        private val blockEnd = TokenSet.create(T.kw("end"), T.kw("else"), T.kw("elseif"), T.kw("until"))

        fun block() {
            val m = b.mark()
            while (!b.eof() && at !in blockEnd) {
                if (at(T.kw("return"))) { returnStmt(); continue }
                val before = b.currentOffset
                statement()
                if (b.currentOffset == before) { b.error("Statement expected"); b.advanceLexer() }
            }
            m.done(E.BLOCK)
        }

        private fun returnStmt() {
            val m = b.mark()
            b.advanceLexer()
            if (!b.eof() && at !in blockEnd && !at(T.SEMI)) exprList()
            eat(T.SEMI)
            m.done(E.RETURN_STMT)
        }

        private fun statement() {
            when (at) {
                T.SEMI -> b.advanceLexer()
                T.DOUBLE_COLON -> { val m = b.mark(); b.advanceLexer(); nameDecl(); expect(T.DOUBLE_COLON, "'::'"); m.done(E.LABEL_STMT) }
                T.kw("break") -> { val m = b.mark(); b.advanceLexer(); m.done(E.BREAK_STMT) }
                T.kw("goto") -> { val m = b.mark(); b.advanceLexer(); nameRef(); m.done(E.GOTO_STMT) }
                T.kw("do") -> { val m = b.mark(); b.advanceLexer(); block(); expect(T.kw("end"), "'end'"); m.done(E.DO_STMT) }
                T.kw("while") -> { val m = b.mark(); b.advanceLexer(); expr(); expect(T.kw("do"), "'do'"); block(); expect(T.kw("end"), "'end'"); m.done(E.WHILE_STMT) }
                T.kw("repeat") -> { val m = b.mark(); b.advanceLexer(); block(); expect(T.kw("until"), "'until'"); expr(); m.done(E.REPEAT_STMT) }
                T.kw("if") -> ifStmt()
                T.kw("for") -> forStmt()
                T.kw("function") -> {
                    val m = b.mark()
                    b.advanceLexer()
                    funcName()
                    funcBody()
                    m.done(E.FUNCTION_STMT)
                }
                T.kw("local") -> localStmt()
                else -> exprStmt()
            }
        }

        private fun ifStmt() {
            val m = b.mark()
            b.advanceLexer()
            expr(); expect(T.kw("then"), "'then'"); block()
            while (at(T.kw("elseif"))) { b.advanceLexer(); expr(); expect(T.kw("then"), "'then'"); block() }
            if (eat(T.kw("else"))) block()
            expect(T.kw("end"), "'end'")
            m.done(E.IF_STMT)
        }

        private fun forStmt() {
            val m = b.mark()
            b.advanceLexer()
            nameDecl()
            if (eat(T.ASSIGN)) {
                expr(); expect(T.COMMA, "','"); expr(); if (eat(T.COMMA)) expr()
                expect(T.kw("do"), "'do'"); block(); expect(T.kw("end"), "'end'")
                m.done(E.FOR_NUM)
            } else {
                while (eat(T.COMMA)) nameDecl()
                expect(T.kw("in"), "'in'"); exprList()
                expect(T.kw("do"), "'do'"); block(); expect(T.kw("end"), "'end'")
                m.done(E.FOR_IN)
            }
        }

        private fun localStmt() {
            val m = b.mark()
            b.advanceLexer()
            if (eat(T.kw("function"))) { nameDecl(); funcBody(); m.done(E.LOCAL_FUNCTION); return }
            do {
                nameDecl()
                if (at(T.LT)) { b.advanceLexer(); eat(T.IDENT); expect(T.GT, "'>'") }   // <const> / <close>
            } while (eat(T.COMMA))
            if (eat(T.ASSIGN)) exprList()
            m.done(E.LOCAL_STMT)
        }

        private fun exprStmt() {
            val m = b.mark()
            val targets = b.mark()
            if (!suffixedExpr()) { targets.drop(); m.drop(); return }
            if (at(T.ASSIGN) || at(T.COMMA)) {
                while (eat(T.COMMA)) suffixedExpr()
                targets.done(E.TARGETS)
                expect(T.ASSIGN, "'='")
                exprList()
                m.done(E.ASSIGN_STMT)
            } else {
                targets.drop()
                m.done(E.CALL_STMT)
            }
        }

        private fun funcName() {
            val m = b.mark()
            var x = b.mark()
            if (!at(T.IDENT)) { b.error("Name expected"); x.drop(); m.done(E.FUNC_NAME); return }
            nameRef()
            while (at(T.DOT) || at(T.COLON)) {
                val colon = at(T.COLON)
                b.advanceLexer()
                expect(T.IDENT, "Name")
                x.done(E.INDEX_EXPR)
                x = x.precede()
                if (colon) break
            }
            x.drop()
            m.done(E.FUNC_NAME)
        }

        private fun funcBody() {
            val m = b.mark()
            val p = b.mark()
            expect(T.LPAREN, "'('")
            if (!at(T.RPAREN)) do {
                if (eat(T.ELLIPSIS)) break
                if (at(T.IDENT)) nameDecl() else { b.error("Parameter expected"); break }
            } while (eat(T.COMMA))
            expect(T.RPAREN, "')'")
            p.done(E.PARAMS)
            block()
            expect(T.kw("end"), "'end'")
            m.done(E.FUNC_BODY)
        }

        private fun nameDecl() {
            if (!at(T.IDENT)) { b.error("Name expected"); return }
            val m = b.mark(); b.advanceLexer(); m.done(E.NAME_DECL)
        }

        private fun nameRef() {
            if (!at(T.IDENT)) { b.error("Name expected"); return }
            val m = b.mark(); b.advanceLexer(); m.done(E.NAME_REF)
        }

        fun exprList() { expr(); while (eat(T.COMMA)) expr() }

        // ------------------------------------------------------------ expressions

        private fun binaryPriority(t: IElementType?): Pair<Int, Int>? = when (t) {
            T.kw("or") -> 1 to 1
            T.kw("and") -> 2 to 2
            T.LT, T.GT, T.LE, T.GE, T.NE, T.EQ -> 3 to 3
            T.BOR -> 4 to 4
            T.BXOR -> 5 to 5
            T.BAND -> 6 to 6
            T.SHL, T.SHR -> 7 to 7
            T.CONCAT -> 9 to 8
            T.PLUS, T.MINUS -> 10 to 10
            T.MUL, T.DIV, T.IDIV, T.MOD -> 11 to 11
            T.POW -> 14 to 13
            else -> null
        }

        fun expr(limit: Int = 0) {
            var m = b.mark()
            if (at(T.kw("not")) || at(T.MINUS) || at(T.LEN) || at(T.BXOR)) {
                b.advanceLexer(); expr(12); m.done(E.UNARY); m = m.precede()
            } else if (!simpleExpr()) {
                b.error("Expression expected"); m.drop(); return
            }
            while (true) {
                val (left, right) = binaryPriority(at) ?: break
                if (left <= limit) break
                b.advanceLexer()
                expr(right)
                m.done(E.BINARY)
                m = m.precede()
            }
            m.drop()
        }

        private fun simpleExpr(): Boolean = when (at) {
            T.NUMBER, T.STRING, T.kw("nil"), T.kw("true"), T.kw("false"), T.ELLIPSIS -> { val m = b.mark(); b.advanceLexer(); m.done(E.LITERAL); true }
            T.LBRACE -> { table(); true }
            T.kw("function") -> { val m = b.mark(); b.advanceLexer(); funcBody(); m.done(E.FUNCTION_EXPR); true }
            else -> suffixedExpr()
        }

        /** name or (expr), then .name, [expr], :name(args), (args), {table}, "string". */
        fun suffixedExpr(): Boolean {
            var m = b.mark()
            when (at) {
                T.IDENT -> nameRef()
                T.LPAREN -> { val p = b.mark(); b.advanceLexer(); expr(); expect(T.RPAREN, "')'"); p.done(E.PAREN) }
                else -> { m.drop(); return false }
            }
            while (true) {
                when (at) {
                    T.DOT -> { b.advanceLexer(); expect(T.IDENT, "Name"); m.done(E.INDEX_EXPR) }
                    T.LBRACKET -> { b.advanceLexer(); expr(); expect(T.RBRACKET, "']'"); m.done(E.BRACKET_INDEX) }
                    T.COLON -> {
                        b.advanceLexer(); expect(T.IDENT, "Name"); m.done(E.INDEX_EXPR)
                        m = m.precede(); args(); m.done(E.CALL_EXPR)
                    }
                    T.LPAREN, T.LBRACE, T.STRING -> { args(); m.done(E.CALL_EXPR) }
                    else -> { m.drop(); return true }
                }
                m = m.precede()
            }
        }

        private fun args() {
            val m = b.mark()
            when (at) {
                T.LPAREN -> { b.advanceLexer(); if (!at(T.RPAREN)) exprList(); expect(T.RPAREN, "')'") }
                T.LBRACE -> table()
                T.STRING -> { val s = b.mark(); b.advanceLexer(); s.done(E.LITERAL) }
                else -> b.error("Arguments expected")
            }
            m.done(E.ARGS)
        }

        private fun table() {
            val m = b.mark()
            b.advanceLexer()
            while (!b.eof() && !at(T.RBRACE)) {
                val f = b.mark()
                when {
                    at(T.LBRACKET) -> { b.advanceLexer(); expr(); expect(T.RBRACKET, "']'"); expect(T.ASSIGN, "'='"); expr() }
                    at(T.IDENT) && b.lookAhead(1) == T.ASSIGN -> { b.advanceLexer(); b.advanceLexer(); expr() }
                    else -> expr()
                }
                f.done(E.TABLE_FIELD)
                if (!eat(T.COMMA) && !eat(T.SEMI)) break
            }
            expect(T.RBRACE, "'}'")
            m.done(E.TABLE)
        }
    }
}

class LuaParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = LuaLexer()
    override fun createParser(project: Project?): PsiParser = LuaParser()
    override fun getFileNodeType(): IFileElementType = E.FILE
    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = T.STRINGS
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun createFile(viewProvider: FileViewProvider): PsiFile = LuaFile(viewProvider)
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        E.BLOCK -> LuaBlock(node)
        E.NAME_DECL -> LuaNameDecl(node)
        E.NAME_REF -> LuaNameRef(node)
        E.INDEX_EXPR -> LuaIndexExpr(node)
        E.TABLE_FIELD -> LuaTableField(node)
        E.TABLE -> LuaTable(node)
        E.CALL_EXPR -> LuaCall(node)
        E.FUNC_BODY -> LuaFuncBody(node)
        E.LITERAL -> LuaLiteral(node)
        else -> LuaElement(node)
    }
}
