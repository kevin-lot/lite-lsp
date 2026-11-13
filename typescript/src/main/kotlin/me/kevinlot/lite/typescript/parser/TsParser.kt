package me.kevinlot.lite.typescript.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as E

/**
 * Hand-written recursive-descent parser for TypeScript, JavaScript and JSX, error tolerant.
 * TypeScript syntax is accepted in every file (it is a superset); [jsx] only changes what `<` means
 * at the start of an expression (an element, else a `<T>x` type assertion).
 *
 * Expressions keep only the structure resolution needs: member access, calls, `new`, arrows, literals,
 * `as`/`!`/`await`; binary operators are flat (precedence does not matter for navigation).
 *
 * Open for languages embedding JavaScript (QML): they parse their own structure and call the protected
 * expression/statement/function entry points.
 */
open class TsParser(private val jsx: Boolean) : PsiParser {
    protected lateinit var b: PsiBuilder
    /** Inside a `for (…` head: `in` is not a binary operator. */
    private var noIn = false
    /** Inside the `extends` operand of a conditional type. */
    private var noConditionalType = false

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        b = builder
        val m = b.mark()
        statements { b.eof() }
        m.done(root)
        return b.treeBuilt
    }

    // ---------------------------------------------------------------- helpers

    protected fun at(t: IElementType) = b.tokenType == t
    protected fun atKw(text: String) = b.tokenType == T.kw(text)
    protected fun atSoft(text: String) = b.tokenType == T.IDENTIFIER && b.tokenText == text
    protected fun atSoft(texts: Set<String>) = b.tokenType == T.IDENTIFIER && b.tokenText in texts
    protected fun la(n: Int): IElementType? = b.lookAhead(n)
    protected fun advance() = b.advanceLexer()
    protected fun mark() = b.mark()

    protected fun eat(t: IElementType): Boolean {
        if (b.tokenType != t) return false
        advance(); return true
    }

    protected fun eatKw(text: String) = eat(T.kw(text))

    protected fun expect(t: IElementType, what: String): Boolean {
        if (eat(t)) return true
        b.error("$what expected"); return false
    }

    /** Current identifier acts as a contextual keyword. */
    protected fun soft() {
        b.tokenType // skips whitespace: the remap must hit the token itself
        b.remapCurrentToken(T.SOFT_KEYWORD); advance()
    }

    protected fun laText(n: Int): String? {
        val m = mark()
        repeat(n) { advance() }
        val text = b.tokenText
        m.rollbackTo()
        return text
    }

    /** A line break between the previous significant token and the current one (ASI, `a\n(b)`…). */
    protected fun newlineBefore(): Boolean {
        var i = -1
        while (true) {
            val t = b.rawLookup(i) ?: return false
            if (t != T.WHITE_SPACE && t !in T.COMMENTS) return false
            val s = b.rawTokenTypeStart(i)
            val e = b.rawTokenTypeStart(i + 1)
            if (b.originalText.subSequence(s, e).contains('\n')) return true
            i--
        }
    }

    private fun newlineBeforeNext(): Boolean {
        val m = mark()
        advance()
        val nl = newlineBefore()
        m.rollbackTo()
        return nl
    }

    protected fun semi() {
        if (eat(T.SEMICOLON) || at(T.RBRACE) || b.eof() || newlineBefore()) return
        b.error("';' expected")
    }

    protected fun identLike() = at(T.IDENTIFIER)
    protected fun nameLike() = b.tokenType in T.NAME_LIKE

    /** Keywords used as names (`a.delete`, `{ default: 1 }`) become identifiers. */
    protected fun advanceAsIdent() {
        if (b.tokenType != T.IDENTIFIER && b.tokenType in T.NAME_LIKE) b.remapCurrentToken(T.IDENTIFIER)
        advance()
    }

    protected fun expectIdent(what: String = "name") {
        if (identLike() || nameLike()) advanceAsIdent() else b.error("$what expected")
    }

    /** Runs [item] until [stop], always making progress (unexpected tokens are skipped with an error). */
    private inline fun statements(stop: () -> Boolean) {
        while (!stop() && !b.eof()) {
            val before = b.currentOffset
            statement()
            if (b.currentOffset == before) { b.error("statement expected"); advance() }
        }
    }

    private inline fun <T> withNoIn(value: Boolean, f: () -> T): T {
        val saved = noIn
        noIn = value
        try { return f() } finally { noIn = saved }
    }

    // ---------------------------------------------------------------- statements

    protected fun statement() {
        val t = b.tokenType
        when {
            t == T.LBRACE -> block()
            t == T.SEMICOLON -> { val m = mark(); advance(); m.done(E.STATEMENT) }
            t == T.kw("var") || t == T.kw("let") -> { val m = mark(); varDeclList(); semi(); m.done(E.VAR_STATEMENT) }
            t == T.kw("const") -> if (la(1) == T.kw("enum")) declaration() else { val m = mark(); varDeclList(); semi(); m.done(E.VAR_STATEMENT) }
            // `using x = …`, `await using x = …`
            (atSoft("using") && la(1) == T.IDENTIFIER && !newlineBeforeNext()) || (t == T.kw("await") && laText(1) == "using" && la(2) == T.IDENTIFIER) -> {
                val m = mark(); eatKw("await"); varDeclList(); semi(); m.done(E.VAR_STATEMENT)
            }
            t == T.kw("if") -> { val m = mark(); advance(); parenExpression(); statement(); if (eatKw("else")) statement(); m.done(E.STATEMENT) }
            t == T.kw("for") -> forStatement()
            t == T.kw("while") || t == T.kw("with") -> { val m = mark(); advance(); parenExpression(); statement(); m.done(E.STATEMENT) }
            t == T.kw("do") -> { val m = mark(); advance(); statement(); if (eatKw("while")) parenExpression(); eat(T.SEMICOLON); m.done(E.STATEMENT) }
            t == T.kw("return") -> {
                val m = mark(); advance()
                if (!at(T.SEMICOLON) && !at(T.RBRACE) && !b.eof() && !newlineBefore()) expression()
                semi(); m.done(E.RETURN_STATEMENT)
            }
            t == T.kw("throw") -> { val m = mark(); advance(); expression(); semi(); m.done(E.STATEMENT) }
            t == T.kw("break") || t == T.kw("continue") -> {
                val m = mark(); advance()
                if (identLike() && !newlineBefore()) advance()
                semi(); m.done(E.STATEMENT)
            }
            t == T.kw("try") -> tryStatement()
            t == T.kw("switch") -> switchStatement()
            t == T.kw("debugger") -> { val m = mark(); advance(); semi(); m.done(E.STATEMENT) }
            t == T.kw("import") && la(1) != T.LPAR && la(1) != T.DOT -> importDeclaration(mark())
            t == T.kw("export") -> exportStatement()
            isDeclarationStart() -> declaration()
            t == T.IDENTIFIER && la(1) == T.COLON -> { val m = mark(); advance(); advance(); statement(); m.done(E.STATEMENT) }
            else -> { val m = mark(); expression(); semi(); m.done(E.EXPRESSION_STATEMENT) }
        }
    }

    private fun isDeclarationStart(): Boolean {
        val t = b.tokenType
        if (t == T.kw("function") || t == T.kw("class") || t == T.kw("enum") || t == T.AT) return true
        if (t != T.IDENTIFIER) return false
        val next = la(1)
        return when (b.tokenText) {
            "async" -> next == T.kw("function") && !newlineBeforeNext()
            "interface", "type" -> next == T.IDENTIFIER && !newlineBeforeNext()
            "namespace", "module" -> (next == T.IDENTIFIER || next == T.STRING) && !newlineBeforeNext()
            "abstract" -> next == T.kw("class")
            "global" -> next == T.LBRACE
            "declare" -> !newlineBeforeNext() && (next in DECLARE_KEYWORDS || (next == T.IDENTIFIER && laText(1) in DECLARE_SOFT))
            else -> false
        }
    }

    /** A declaration, possibly with `export`/`default`/`declare`/`abstract`/`async` modifiers (inside its node). */
    private fun declaration(m: PsiBuilder.Marker = mark()) {
        decorators()
        while (true) {
            when {
                atKw("export") || atKw("default") -> advance()
                atSoft("declare") && !newlineBeforeNext() -> soft()
                atSoft("abstract") && la(1) == T.kw("class") -> soft()
                atSoft("async") && la(1) == T.kw("function") -> soft()
                atKw("const") && la(1) == T.kw("enum") -> advance()
                else -> break
            }
        }
        when {
            atKw("function") -> functionRest(m, E.FUNCTION_DECLARATION)
            atKw("class") -> classRest(m, E.CLASS_DECLARATION)
            atKw("enum") -> enumRest(m)
            atSoft("interface") -> interfaceRest(m)
            atSoft("type") -> { soft(); expectIdent(); typeParameters(); expect(T.EQ, "'='"); type(); semi(); m.done(E.TYPE_ALIAS) }
            atSoft("namespace") || atSoft("module") -> namespaceRest(m)
            atSoft("global") -> { soft(); moduleBlock(); m.done(E.GLOBAL_DECLARATION) }
            atKw("var") || atKw("let") || atKw("const") -> { varDeclList(); semi(); m.done(E.VAR_STATEMENT) }
            atKw("import") -> importDeclaration(m)
            else -> { b.error("declaration expected"); m.done(E.STATEMENT) }
        }
    }

    protected fun block() {
        val m = mark()
        expect(T.LBRACE, "'{'")
        statements { at(T.RBRACE) }
        expect(T.RBRACE, "'}'")
        m.done(E.BLOCK)
    }

    private fun parenExpression() {
        expect(T.LPAR, "'('")
        withNoIn(false) { expression() }
        expect(T.RPAR, "')'")
    }

    private fun forStatement() {
        val m = mark()
        advance()
        eatKw("await")
        expect(T.LPAR, "'('")
        withNoIn(true) {
            when {
                at(T.SEMICOLON) -> {}
                atKw("var") || atKw("let") || atKw("const") || (atSoft("using") && la(1) == T.IDENTIFIER) -> {
                    val d = mark(); varDeclList(); d.done(E.VAR_STATEMENT)
                }
                else -> expression()
            }
        }
        when {
            atSoft("of") -> { soft(); assignment() }
            eatKw("in") -> expression()
            else -> {
                expect(T.SEMICOLON, "';'")
                if (!at(T.SEMICOLON)) expression()
                expect(T.SEMICOLON, "';'")
                if (!at(T.RPAR)) expression()
            }
        }
        expect(T.RPAR, "')'")
        statement()
        m.done(E.FOR_STATEMENT)
    }

    private fun tryStatement() {
        val m = mark()
        advance()
        block()
        if (atKw("catch")) {
            val c = mark()
            advance()
            if (eat(T.LPAR)) {
                val p = mark()
                if (identLike()) advance() else bindingTarget()
                typeAnnotation()
                p.done(E.PARAMETER)
                expect(T.RPAR, "')'")
            }
            block()
            c.done(E.CATCH_CLAUSE)
        }
        if (eatKw("finally")) block()
        m.done(E.STATEMENT)
    }

    private fun switchStatement() {
        val m = mark()
        advance()
        parenExpression()
        expect(T.LBRACE, "'{'")
        while (!at(T.RBRACE) && !b.eof()) {
            val c = mark()
            if (eatKw("case")) expression() else if (!eatKw("default")) { b.error("'case' expected"); advance() }
            expect(T.COLON, "':'")
            statements { atKw("case") || atKw("default") || at(T.RBRACE) }
            c.done(E.CASE_CLAUSE)
        }
        expect(T.RBRACE, "'}'")
        m.done(E.SWITCH_STATEMENT)
    }

    // ---------------------------------------------------------------- variables and patterns

    private fun varDeclList() {
        if (atSoft("using")) soft() else advance() // var / let / const
        do variable() while (eat(T.COMMA))
    }

    private fun variable() {
        val m = mark()
        if (identLike() || atKw("let") || atKw("yield") || atKw("await")) advanceAsIdent() else bindingTarget()
        eat(T.EXCL)
        typeAnnotation()
        if (eat(T.EQ)) assignment()
        m.done(E.VARIABLE)
    }

    private fun bindingTarget() {
        when {
            at(T.LBRACE) -> objectPattern()
            at(T.LBRACKET) -> arrayPattern()
            identLike() || nameLike() -> { val m = mark(); advanceAsIdent(); m.done(E.BINDING) }
            else -> b.error("name expected")
        }
    }

    private fun objectPattern() {
        val m = mark()
        advance()
        while (!at(T.RBRACE) && !b.eof()) {
            val p = mark()
            when {
                eat(T.ELLIPSIS) -> { bindingTarget(); p.drop() }
                (nameLike() || at(T.STRING) || at(T.NUMBER)) && la(1) == T.COLON -> {
                    val key = mark(); advanceAsIdent(); key.done(E.PROPERTY_KEY)
                    advance()
                    bindingTarget()
                    if (eat(T.EQ)) assignment()
                    p.done(E.PATTERN_PROPERTY)
                }
                at(T.LBRACKET) -> {
                    computedName(); expect(T.COLON, "':'"); bindingTarget()
                    if (eat(T.EQ)) assignment()
                    p.done(E.PATTERN_PROPERTY)
                }
                nameLike() -> { advanceAsIdent(); if (eat(T.EQ)) assignment(); p.done(E.BINDING) }
                else -> { b.error("name expected"); p.drop(); if (!at(T.COMMA)) advance() }
            }
            if (!eat(T.COMMA)) break
        }
        expect(T.RBRACE, "'}'")
        m.done(E.OBJECT_PATTERN)
    }

    private fun arrayPattern() {
        val m = mark()
        advance()
        while (!at(T.RBRACKET) && !b.eof()) {
            if (eat(T.COMMA)) continue
            eat(T.ELLIPSIS)
            bindingTarget()
            if (eat(T.EQ)) assignment()
            if (!eat(T.COMMA)) break
        }
        expect(T.RBRACKET, "']'")
        m.done(E.ARRAY_PATTERN)
    }

    // ---------------------------------------------------------------- functions and classes

    protected fun functionRest(m: PsiBuilder.Marker, type: IElementType) {
        expect(T.kw("function"), "'function'")
        eat(T.MUL)
        if (identLike() || atKw("yield") || atKw("await")) advanceAsIdent()
        typeParameters()
        parameterList()
        typeAnnotation()
        if (at(T.LBRACE)) block() else semi()
        m.done(type)
    }

    protected fun parameterList() {
        val m = mark()
        expect(T.LPAR, "'('")
        withNoIn(false) {
            while (!at(T.RPAR) && !b.eof()) {
                parameter()
                if (!eat(T.COMMA)) break
            }
        }
        expect(T.RPAR, "')'")
        m.done(E.PARAMETER_LIST)
    }

    private fun parameter() {
        val m = mark()
        decorators()
        while (atSoft(PARAMETER_MODIFIERS) && (la(1) == T.IDENTIFIER || la(1) == T.LBRACE || la(1) == T.LBRACKET || la(1) == T.kw("this"))) soft()
        eat(T.ELLIPSIS)
        when {
            atKw("this") -> advance()
            identLike() || nameLike() && !at(T.LBRACE) -> advanceAsIdent()
            else -> bindingTarget()
        }
        eat(T.QUEST)
        typeAnnotation()
        if (eat(T.EQ)) assignment()
        m.done(E.PARAMETER)
    }

    protected fun typeAnnotation() {
        if (!at(T.COLON)) return
        val m = mark()
        advance()
        type()
        m.done(E.TYPE_ANNOTATION)
    }

    private fun decorators() {
        while (at(T.AT)) {
            val d = mark()
            advance()
            leftHandSide()
            d.done(E.DECORATOR)
        }
    }

    private fun classRest(m: PsiBuilder.Marker, type: IElementType) {
        expect(T.kw("class"), "'class'")
        if (identLike() && !atSoft("implements")) advance()
        typeParameters()
        if (atKw("extends")) {
            val e = mark()
            advance()
            leftHandSide()
            if (at(T.LT)) typeArguments()
            e.done(E.EXTENDS_CLAUSE)
        }
        if (atSoft("implements")) {
            val i = mark()
            soft()
            do typeReference() while (eat(T.COMMA))
            i.done(E.IMPLEMENTS_CLAUSE)
        }
        classBody()
        m.done(type)
    }

    private fun classBody() {
        val m = mark()
        expect(T.LBRACE, "'{'")
        while (!at(T.RBRACE) && !b.eof()) {
            val before = b.currentOffset
            classMember()
            if (b.currentOffset == before) { b.error("member expected"); advance() }
        }
        expect(T.RBRACE, "'}'")
        m.done(E.CLASS_BODY)
    }

    private fun classMember() {
        if (eat(T.SEMICOLON)) return
        val m = mark()
        decorators()
        if (atSoft("static") && la(1) == T.LBRACE) { soft(); block(); m.done(E.STATIC_BLOCK); return }
        memberModifiers()
        if (at(T.LBRACKET) && isIndexSignature()) { indexSignatureRest(m); return }
        memberRest(m, inClass = true)
    }

    /** `public static async *`, `get`/`set`: only when a member name follows (else they are the name). */
    private fun memberModifiers() {
        while (atSoft(MEMBER_MODIFIERS) && la(1).let { it in T.NAME_LIKE || it == T.STRING || it == T.NUMBER || it == T.PRIVATE_NAME || it == T.LBRACKET || it == T.MUL }) {
            soft()
        }
        eat(T.MUL)
    }

    private fun isIndexSignature() = (la(1) == T.IDENTIFIER) && la(2) == T.COLON

    private fun indexSignatureRest(m: PsiBuilder.Marker) {
        advance()
        val p = mark(); advance(); typeAnnotation(); p.done(E.PARAMETER)
        expect(T.RBRACKET, "']'")
        typeAnnotation()
        memberEnd()
        m.done(E.INDEX_SIGNATURE)
    }

    private fun memberRest(m: PsiBuilder.Marker, inClass: Boolean) {
        propertyName()
        if (!eat(T.QUEST)) eat(T.EXCL)
        if (at(T.LPAR) || at(T.LT)) {
            typeParameters()
            parameterList()
            typeAnnotation()
            if (at(T.LBRACE)) block() else memberEnd()
            m.done(E.METHOD)
        } else {
            typeAnnotation()
            if (inClass && eat(T.EQ)) assignment()
            memberEnd()
            m.done(E.PROPERTY)
        }
    }

    private fun memberEnd() {
        if (eat(T.SEMICOLON) || eat(T.COMMA) || at(T.RBRACE) || b.eof() || newlineBefore()) return
        b.error("';' expected")
    }

    private fun propertyName() {
        when {
            nameLike() -> advanceAsIdent()
            at(T.STRING) || at(T.NUMBER) || at(T.PRIVATE_NAME) -> advance()
            at(T.LBRACKET) -> computedName()
            else -> b.error("property name expected")
        }
    }

    private fun computedName() {
        val m = mark()
        advance()
        withNoIn(false) { assignment() }
        expect(T.RBRACKET, "']'")
        m.done(E.COMPUTED_NAME)
    }

    private fun interfaceRest(m: PsiBuilder.Marker) {
        soft()
        expectIdent()
        typeParameters()
        if (atKw("extends")) {
            val e = mark()
            advance()
            do typeReference() while (eat(T.COMMA))
            e.done(E.EXTENDS_CLAUSE)
        }
        objectType()
        m.done(E.INTERFACE_DECLARATION)
    }

    private fun enumRest(m: PsiBuilder.Marker) {
        advance()
        expectIdent()
        expect(T.LBRACE, "'{'")
        while (!at(T.RBRACE) && !b.eof()) {
            val e = mark()
            if (nameLike()) advanceAsIdent() else if (at(T.STRING) || at(T.NUMBER)) advance() else { b.error("member expected"); e.drop(); advance(); continue }
            if (eat(T.EQ)) assignment()
            e.done(E.ENUM_MEMBER)
            if (!eat(T.COMMA)) break
        }
        expect(T.RBRACE, "'}'")
        m.done(E.ENUM_DECLARATION)
    }

    private fun namespaceRest(m: PsiBuilder.Marker) {
        soft()
        if (at(T.STRING)) {
            advance()
            if (at(T.LBRACE)) moduleBlock() else semi()
            m.done(E.AMBIENT_MODULE)
            return
        }
        expectIdent()
        while (eat(T.DOT)) expectIdent()
        moduleBlock()
        m.done(E.NAMESPACE_DECLARATION)
    }

    private fun moduleBlock() {
        val m = mark()
        expect(T.LBRACE, "'{'")
        statements { at(T.RBRACE) }
        expect(T.RBRACE, "'}'")
        m.done(E.MODULE_BLOCK)
    }

    // ---------------------------------------------------------------- imports / exports

    private fun importDeclaration(m: PsiBuilder.Marker) {
        advance() // import
        if (atSoft("type") && (la(1) == T.LBRACE || la(1) == T.MUL || (la(1) == T.IDENTIFIER && (laText(1) != "from" || la(2) != T.STRING)))) soft()
        if (at(T.STRING)) {
            moduleSpecifier(); importAttributes(); semi(); m.done(E.IMPORT_DECLARATION); return
        }
        if (identLike() && la(1) == T.EQ) {
            advance(); advance()
            if (atSoft("require") && la(1) == T.LPAR) { soft(); advance(); moduleSpecifier(); expect(T.RPAR, "')'") } else entityName()
            semi()
            m.done(E.IMPORT_EQUALS)
            return
        }
        if (identLike()) {
            val d = mark(); advance(); d.done(E.IMPORT_DEFAULT)
            eat(T.COMMA)
        }
        if (at(T.MUL)) {
            val n = mark()
            advance()
            if (atSoft("as")) soft() else b.error("'as' expected")
            expectIdent()
            n.done(E.IMPORT_NAMESPACE)
        } else if (at(T.LBRACE)) {
            specifiers(E.IMPORT_SPECIFIER)
        }
        fromClause()
        semi()
        m.done(E.IMPORT_DECLARATION)
    }

    /** `{ a, type b, c as d, "e-f" as g }` */
    private fun specifiers(type: IElementType) {
        advance()
        while (!at(T.RBRACE) && !b.eof()) {
            val s = mark()
            if (atSoft("type") && (la(1) in T.NAME_LIKE || la(1) == T.STRING) && laText(1) != "as") soft()
            when {
                nameLike() -> { val r = mark(); advanceAsIdent(); r.done(E.REFERENCE) }
                at(T.STRING) -> advance()
                else -> b.error("name expected")
            }
            if (atSoft("as")) { soft(); if (at(T.STRING)) advance() else expectIdent() }
            s.done(type)
            if (!eat(T.COMMA)) break
        }
        expect(T.RBRACE, "'}'")
    }

    private fun fromClause() {
        if (atSoft("from")) soft() else { b.error("'from' expected"); return }
        moduleSpecifier()
        importAttributes()
    }

    private fun importAttributes() {
        if ((atSoft("with") || atSoft("assert")) && la(1) == T.LBRACE) { soft(); objectLiteral() }
        else if (atKw("with") && la(1) == T.LBRACE) { advance(); objectLiteral() }
    }

    private fun moduleSpecifier() {
        if (!at(T.STRING)) { b.error("module name expected"); return }
        val m = mark(); advance(); m.done(E.MODULE_SPECIFIER)
    }

    private fun exportStatement() {
        val m = mark()
        when (la(1)) {
            T.EQ -> { advance(); advance(); expression(); semi(); m.done(E.EXPORT_ASSIGNMENT); return }
            T.kw("default") -> {
                val next = la(2)
                val text = laText(2)
                val decl = next == T.kw("function") || next == T.kw("class") || next == T.AT ||
                    (text == "abstract" && la(3) == T.kw("class")) || (text == "async" && la(3) == T.kw("function")) ||
                    (text == "interface" && la(3) == T.IDENTIFIER)
                if (decl) { advance(); declaration(m); return }
                advance(); advance()
                assignment(); semi()
                m.done(E.EXPORT_ASSIGNMENT)
                return
            }
        }
        advance() // export
        when {
            at(T.MUL) || (atSoft("type") && la(1) == T.MUL) -> {
                if (atSoft("type")) soft()
                advance()
                if (atSoft("as")) { soft(); if (at(T.STRING)) advance() else expectIdent() }
                fromClause(); semi()
                m.done(E.EXPORT_STAR)
            }
            at(T.LBRACE) || (atSoft("type") && la(1) == T.LBRACE) -> {
                if (atSoft("type")) soft()
                specifiers(E.EXPORT_SPECIFIER)
                if (atSoft("from")) fromClause()
                semi()
                m.done(E.EXPORT_DECLARATION)
            }
            atSoft("as") && laText(1) == "namespace" -> { soft(); soft(); expectIdent(); semi(); m.done(E.STATEMENT) }
            else -> declaration(m)
        }
    }

    private fun entityName() {
        var r = mark()
        expectIdent()
        r.done(E.REFERENCE)
        while (at(T.DOT)) {
            r = r.precede()
            advance()
            expectIdent()
            r.done(E.REFERENCE)
        }
    }

    // ---------------------------------------------------------------- types

    private fun typeParameters() {
        if (!at(T.LT)) return
        val m = mark()
        advance()
        while (!at(T.GT) && !b.eof()) {
            val p = mark()
            while ((atKw("in") || atSoft("out") || atKw("const")) && la(1) == T.IDENTIFIER) { if (at(T.IDENTIFIER)) soft() else advance() }
            expectIdent("type parameter")
            if (eatKw("extends")) type()
            if (eat(T.EQ)) type()
            p.done(E.TYPE_PARAMETER)
            if (!eat(T.COMMA)) break
        }
        expect(T.GT, "'>'")
        m.done(E.TYPE_PARAMETER_LIST)
    }

    private fun typeArguments() {
        val m = mark()
        advance()
        while (!at(T.GT) && !b.eof()) {
            type()
            if (!eat(T.COMMA)) break
        }
        expect(T.GT, "'>'")
        m.done(E.TYPE_ARGUMENT_LIST)
    }

    private fun type() {
        val m = mark()
        unionType()
        if (atKw("extends") && !noConditionalType && !newlineBefore()) {
            advance()
            val saved = noConditionalType
            noConditionalType = true
            unionType()
            noConditionalType = saved
            expect(T.QUEST, "'?'"); type(); expect(T.COLON, "':'"); type()
            m.done(E.CONDITIONAL_TYPE)
        } else m.drop()
    }

    private fun functionType(type: IElementType) {
        val m = mark()
        typeParameters()
        parameterList()
        expect(T.ARROW, "'=>'")
        type()
        m.done(type)
    }

    /** `(` … `)` `=>`, `()`, `(...`, `(a:` or `(a,`. */
    private fun functionTypeAhead(): Boolean {
        val n1 = la(1)
        if (n1 == T.RPAR || n1 == T.ELLIPSIS) return true
        if (n1 == T.IDENTIFIER || n1 == T.kw("this")) {
            val n2 = la(2)
            if (n2 == T.COLON || n2 == T.COMMA || n2 == T.QUEST || n2 == T.EQ) return true
            if (n2 == T.RPAR && la(3) == T.ARROW) return true
        }
        if (n1 == T.LBRACE || n1 == T.LBRACKET) {
            val close = matching(1) ?: return false
            return la(close + 1).let { it == T.COLON || it == T.COMMA || it == T.EQ || it == T.QUEST } ||
                (la(close + 1) == T.RPAR && la(close + 2) == T.ARROW)
        }
        return false
    }

    private fun unionType() {
        val m = mark()
        eat(T.OR)
        intersectionType()
        var n = 1
        while (at(T.OR)) { advance(); intersectionType(); n++ }
        if (n > 1) m.done(E.UNION_TYPE) else m.drop()
    }

    private fun intersectionType() {
        val m = mark()
        eat(T.AND)
        typeOperator()
        var n = 1
        while (at(T.AND)) { advance(); typeOperator(); n++ }
        if (n > 1) m.done(E.INTERSECTION_TYPE) else m.drop()
    }

    private fun typeOperator() {
        when {
            atSoft(TYPE_OPERATORS) && la(1).let { it != null && it !in TYPE_FOLLOW } -> {
                val m = mark(); soft(); typeOperator(); m.done(E.TYPE_OPERATOR)
            }
            atSoft("infer") && la(1) == T.IDENTIFIER -> {
                val m = mark()
                soft()
                val p = mark()
                advance()
                if (atKw("extends") && noConditionalType) { advance(); type() }
                p.done(E.TYPE_PARAMETER)
                m.done(E.INFER_TYPE)
            }
            else -> postfixType()
        }
    }

    private fun postfixType() {
        var m = mark()
        primaryType()
        while (at(T.LBRACKET) && !newlineBefore()) {
            if (la(1) == T.RBRACKET) { advance(); advance(); m.done(E.ARRAY_TYPE) }
            else { advance(); type(); expect(T.RBRACKET, "']'"); m.done(E.INDEXED_ACCESS_TYPE) }
            m = m.precede()
        }
        m.drop()
    }

    private fun primaryType() {
        val t = b.tokenType
        when {
            t == T.LT || (t == T.LPAR && functionTypeAhead()) -> functionType(E.FUNCTION_TYPE)
            t == T.kw("new") || (atSoft("abstract") && la(1) == T.kw("new")) -> {
                val m = mark()
                if (atSoft("abstract")) soft()
                advance()
                typeParameters(); parameterList(); expect(T.ARROW, "'=>'"); type()
                m.done(E.CONSTRUCTOR_TYPE)
            }
            t == T.LPAR -> { val m = mark(); advance(); type(); expect(T.RPAR, "')'"); m.done(E.PAREN_TYPE) }
            t == T.LBRACKET -> tupleType()
            t == T.LBRACE -> if (mappedTypeAhead()) mappedType() else objectType()
            t == T.kw("typeof") -> {
                val m = mark()
                advance()
                if (atKw("import")) importType() else entityName()
                if (at(T.LT) && !newlineBefore()) typeArguments()
                m.done(E.TYPEOF_TYPE)
            }
            t == T.kw("import") -> importType()
            t == T.kw("this") -> {
                val m = mark()
                advance()
                if (atSoft("is") && !newlineBefore()) { soft(); type(); m.done(E.TYPE_PREDICATE) } else m.done(E.THIS_TYPE)
            }
            atSoft("asserts") && (la(1) == T.IDENTIFIER || la(1) == T.kw("this")) && !newlineBeforeNext() -> {
                val m = mark()
                soft()
                if (atKw("this")) advance() else { val r = mark(); advance(); r.done(E.REFERENCE) }
                if (atSoft("is")) { soft(); type() }
                m.done(E.TYPE_PREDICATE)
            }
            t == T.IDENTIFIER -> {
                if (la(1) == T.IDENTIFIER && laText(1) == "is" && !newlineBeforeNext()) {
                    val m = mark()
                    val r = mark(); advance(); r.done(E.REFERENCE)
                    soft(); type()
                    m.done(E.TYPE_PREDICATE)
                } else typeReference()
            }
            // `var` as a type: QML function parameters (`function f(x: var)`)
            t == T.kw("var") -> { val m = mark(); val r = mark(); advanceAsIdent(); r.done(E.REFERENCE); m.done(E.TYPE_REFERENCE) }
            t == T.kw("void") || t == T.kw("null") || t == T.STRING || t == T.NUMBER || t == T.kw("true") || t == T.kw("false") ||
                t == T.TEMPLATE_FULL || t == T.MUL || t == T.QUEST -> { val m = mark(); advance(); m.done(E.LITERAL_TYPE) }
            t == T.MINUS && la(1) == T.NUMBER -> { val m = mark(); advance(); advance(); m.done(E.LITERAL_TYPE) }
            t == T.TEMPLATE_HEAD -> {
                val m = mark()
                advance()
                while (!b.eof()) {
                    type()
                    if (eat(T.TEMPLATE_MIDDLE)) continue
                    expect(T.TEMPLATE_TAIL, "'`'")
                    break
                }
                m.done(E.TEMPLATE_TYPE)
            }
            else -> {
                b.error("type expected")
                if (t != null && t !in TYPE_FOLLOW && t != T.RBRACE && t != T.LBRACE) advance()
            }
        }
    }

    private fun typeReference() {
        val m = mark()
        if (atKw("typeof") || atKw("import")) { primaryType(); m.drop(); return }
        entityName()
        if (at(T.LT) && !newlineBefore()) typeArguments()
        m.done(E.TYPE_REFERENCE)
    }

    private fun importType() {
        val m = mark()
        advance()
        expect(T.LPAR, "'('")
        moduleSpecifier()
        if (eat(T.COMMA)) { if (at(T.LBRACE)) objectLiteral() }
        expect(T.RPAR, "')'")
        while (eat(T.DOT)) expectIdent()
        if (at(T.LT) && !newlineBefore()) typeArguments()
        m.done(E.IMPORT_TYPE)
    }

    private fun tupleType() {
        val m = mark()
        advance()
        while (!at(T.RBRACKET) && !b.eof()) {
            eat(T.ELLIPSIS)
            if (nameLike() && (la(1) == T.COLON || (la(1) == T.QUEST && la(2) == T.COLON))) {
                advanceAsIdent(); eat(T.QUEST); advance(); type()
            } else {
                type(); eat(T.QUEST)
            }
            if (!eat(T.COMMA)) break
        }
        expect(T.RBRACKET, "']'")
        m.done(E.TUPLE_TYPE)
    }

    private fun mappedTypeAhead(): Boolean {
        var i = 1
        if (la(i) == T.PLUS || la(i) == T.MINUS) i++
        if (la(i) == T.IDENTIFIER && laText(i) == "readonly") i++
        return la(i) == T.LBRACKET && la(i + 1) == T.IDENTIFIER && la(i + 2) == T.kw("in")
    }

    private fun mappedType() {
        val m = mark()
        advance()
        if (at(T.PLUS) || at(T.MINUS)) advance()
        if (atSoft("readonly")) soft()
        advance() // [
        val p = mark()
        advance()
        advance() // in
        type()
        p.done(E.TYPE_PARAMETER)
        if (atSoft("as")) { soft(); type() }
        expect(T.RBRACKET, "']'")
        if (at(T.PLUS) || at(T.MINUS)) advance()
        eat(T.QUEST)
        typeAnnotation()
        if (!eat(T.SEMICOLON)) eat(T.COMMA)
        expect(T.RBRACE, "'}'")
        m.done(E.MAPPED_TYPE)
    }

    private fun objectType() {
        val m = mark()
        expect(T.LBRACE, "'{'")
        while (!at(T.RBRACE) && !b.eof()) {
            val before = b.currentOffset
            typeMember()
            if (b.currentOffset == before) { b.error("member expected"); advance() }
        }
        expect(T.RBRACE, "'}'")
        m.done(E.OBJECT_TYPE)
    }

    private fun typeMember() {
        if (eat(T.SEMICOLON) || eat(T.COMMA)) return
        val m = mark()
        when {
            at(T.LPAR) || at(T.LT) -> { typeParameters(); parameterList(); typeAnnotation(); memberEnd(); m.done(E.CALL_SIGNATURE) }
            atKw("new") && (la(1) == T.LPAR || la(1) == T.LT) -> {
                advance(); typeParameters(); parameterList(); typeAnnotation(); memberEnd(); m.done(E.CONSTRUCT_SIGNATURE)
            }
            else -> {
                memberModifiers()
                if (at(T.LBRACKET) && isIndexSignature()) indexSignatureRest(m) else memberRest(m, inClass = false)
            }
        }
    }

    /** Index of the token closing the bracket at lookahead [open] (`(`, `[`, `{`, `<`), or null. */
    private fun matching(open: Int): Int? {
        var depth = 0
        var i = open
        while (i < open + 400) {
            when (la(i) ?: return null) {
                T.LPAR, T.LBRACKET, T.LBRACE, T.TEMPLATE_HEAD -> depth++
                T.RPAR, T.RBRACKET, T.RBRACE, T.TEMPLATE_TAIL -> { depth--; if (depth == 0) return i }
                else -> {}
            }
            i++
        }
        return null
    }

    // ---------------------------------------------------------------- expressions

    protected fun expression() {
        val m = mark()
        assignment()
        if (at(T.COMMA)) {
            while (eat(T.COMMA)) assignment()
            m.done(E.BINARY_EXPRESSION)
        } else m.drop()
    }

    protected fun assignment() {
        if (arrowFunction()) return
        if (atKw("yield")) {
            val m = mark()
            advance()
            if (!newlineBefore() && startsExpression()) { eat(T.MUL); assignment() }
            m.done(E.UNARY_EXPRESSION)
            return
        }
        val m = mark()
        conditional()
        if (at(T.EQ) || (at(T.OPERATOR) && isAssignmentOperator(b.tokenText))) {
            advance()
            assignment()
            m.done(E.ASSIGNMENT_EXPRESSION)
        } else m.drop()
    }

    private fun startsExpression() = b.tokenType.let {
        it != null && it != T.RPAR && it != T.RBRACKET && it != T.RBRACE && it != T.SEMICOLON && it != T.COMMA && it != T.COLON
    }

    private fun conditional() {
        val m = mark()
        binary()
        if (at(T.QUEST)) {
            advance()
            withNoIn(false) { assignment() }
            expect(T.COLON, "':'")
            assignment()
            m.done(E.CONDITIONAL_EXPRESSION)
        } else m.drop()
    }

    private fun binary() {
        var m = mark()
        unary()
        while (true) {
            val t = b.tokenType
            when {
                t == T.GT -> {
                    // `>>`, `>=`, `>>>=`: glued back from single `>` tokens
                    var end = b.currentOffset + 1
                    advance()
                    while ((at(T.GT) || at(T.EQ) || at(T.OPERATOR) && b.tokenText == "==") && b.currentOffset == end) { end = b.currentOffset + b.tokenText!!.length; advance() }
                    unary()
                }
                t == T.OPERATOR && !isAssignmentOperator(b.tokenText) || t in BINARY || (t == T.kw("in") && !noIn) -> { advance(); unary() }
                (atSoft("as") || atSoft("satisfies")) && !newlineBefore() -> {
                    soft()
                    if (atKw("const")) advance() else type()
                    m.done(E.AS_EXPRESSION)
                    m = m.precede()
                    continue
                }
                else -> break
            }
            m.done(E.BINARY_EXPRESSION)
            m = m.precede()
        }
        m.drop()
    }

    private fun unary() {
        val t = b.tokenType
        when {
            t in PREFIX -> { val m = mark(); advance(); unary(); m.done(E.UNARY_EXPRESSION) }
            t == T.kw("await") && la(1).let { it != T.RPAR && it != T.SEMICOLON && it != T.COMMA && it != T.DOT } -> {
                val m = mark(); advance(); unary(); m.done(E.AWAIT_EXPRESSION)
            }
            t == T.LT && !jsx -> {
                val m = mark()
                advance(); type(); expect(T.GT, "'>'")
                unary()
                m.done(E.TYPE_ASSERTION)
            }
            else -> {
                val m = mark()
                leftHandSide()
                if ((at(T.PLUSPLUS) || at(T.MINUSMINUS)) && !newlineBefore()) { advance(); m.done(E.UNARY_EXPRESSION) } else m.drop()
            }
        }
    }

    private fun leftHandSide() {
        var m = mark()
        primary()
        while (true) {
            when {
                at(T.DOT) || at(T.QUEST_DOT) -> {
                    val optional = at(T.QUEST_DOT)
                    advance()
                    when {
                        optional && at(T.LPAR) -> { arguments(); m.done(E.CALL_EXPRESSION) }
                        optional && at(T.LBRACKET) -> { advance(); withNoIn(false) { expression() }; expect(T.RBRACKET, "']'"); m.done(E.INDEX_EXPRESSION) }
                        else -> {
                            if (at(T.PRIVATE_NAME)) advance() else if (nameLike()) advanceAsIdent() else b.error("name expected")
                            m.done(E.REFERENCE)
                        }
                    }
                }
                at(T.LBRACKET) -> { advance(); withNoIn(false) { expression() }; expect(T.RBRACKET, "']'"); m.done(E.INDEX_EXPRESSION) }
                at(T.LPAR) -> { arguments(); m.done(E.CALL_EXPRESSION) }
                at(T.LT) && typeArgumentsCallAhead() -> {
                    typeArguments()
                    if (at(T.LPAR)) arguments() else template()
                    m.done(E.CALL_EXPRESSION)
                }
                at(T.TEMPLATE_FULL) || at(T.TEMPLATE_HEAD) -> { template(); m.done(E.CALL_EXPRESSION) }
                at(T.EXCL) && !newlineBefore() -> { advance(); m.done(E.NON_NULL_EXPRESSION) }
                else -> break
            }
            m = m.precede()
        }
        m.drop()
    }

    /**
     * Index of the `>` closing type arguments that start at lookahead [o] (`<`), or null when the tokens
     * in between cannot be types (unbalanced brackets, operators, `;`): `a < b) { c > (d)` is not a call.
     */
    private fun typeArgumentsEnd(o: Int): Int? {
        var angles = 0
        var nest = 0
        var i = o
        while (i < o + 200) {
            val t = la(i) ?: return null
            when (t) {
                T.LT -> angles++
                T.GT -> { angles--; if (angles == 0 && nest == 0) return i }
                T.LPAR, T.LBRACKET, T.LBRACE -> nest++
                T.RPAR, T.RBRACKET, T.RBRACE -> { nest--; if (nest < 0) return null }
                else -> if (t !in TYPE_ARGUMENT_TOKENS) return null
            }
            i++
        }
        return null
    }

    /** `f<T>(` or ``f<T>`…` ``. */
    private fun typeArgumentsCallAhead(): Boolean =
        typeArgumentsEnd(0)?.let { la(it + 1) == T.LPAR || la(it + 1) == T.TEMPLATE_FULL || la(it + 1) == T.TEMPLATE_HEAD } == true

    private fun arguments() {
        val m = mark()
        advance()
        withNoIn(false) {
            while (!at(T.RPAR) && !b.eof()) {
                if (at(T.ELLIPSIS)) { val s = mark(); advance(); assignment(); s.done(E.SPREAD) } else assignment()
                if (!eat(T.COMMA)) break
            }
        }
        expect(T.RPAR, "')'")
        m.done(E.ARGUMENT_LIST)
    }

    private fun primary() {
        val t = b.tokenType
        when {
            t == T.IDENTIFIER -> when {
                atSoft("async") && la(1) == T.kw("function") && !newlineBeforeNext() -> functionExpression()
                atSoft("require") && la(1) == T.LPAR && la(2) == T.STRING && la(3) == T.RPAR -> {
                    // require("x"): the string is a module specifier (navigates to the file)
                    val call = mark()
                    val r = mark(); advance(); r.done(E.REFERENCE)
                    val args = mark(); advance(); moduleSpecifier(); advance(); args.done(E.ARGUMENT_LIST)
                    call.done(E.CALL_EXPRESSION)
                }
                else -> { val m = mark(); advance(); m.done(E.REFERENCE) }
            }
            t == T.kw("let") || t == T.kw("yield") || t == T.kw("await") -> { val m = mark(); advanceAsIdent(); m.done(E.REFERENCE) }
            t == T.PRIVATE_NAME -> { val m = mark(); advance(); m.done(E.REFERENCE) }
            t == T.kw("this") -> { val m = mark(); advance(); m.done(E.THIS_EXPRESSION) }
            t == T.kw("super") -> { val m = mark(); advance(); m.done(E.SUPER_EXPRESSION) }
            t == T.NUMBER || t == T.STRING || t == T.REGEX || t == T.kw("true") || t == T.kw("false") || t == T.kw("null") -> {
                val m = mark(); advance(); m.done(E.LITERAL)
            }
            t == T.TEMPLATE_FULL || t == T.TEMPLATE_HEAD -> template()
            t == T.LPAR -> { val m = mark(); parenExpression(); m.done(E.PAREN_EXPRESSION) }
            t == T.LBRACKET -> arrayLiteral()
            t == T.LBRACE -> objectLiteral()
            t == T.kw("function") -> functionExpression()
            t == T.kw("class") || t == T.AT -> { val m = mark(); decorators(); classRest(m, E.CLASS_EXPRESSION) }
            t == T.kw("new") -> newExpression()
            t == T.kw("import") -> {
                val m = mark()
                advance()
                if (eat(T.DOT)) { expectIdent(); m.done(E.LITERAL); return } // import.meta
                expect(T.LPAR, "'('")
                if (at(T.STRING) && (la(1) == T.RPAR || la(1) == T.COMMA)) moduleSpecifier() else assignment()
                if (eat(T.COMMA) && !at(T.RPAR)) assignment()
                expect(T.RPAR, "')'")
                m.done(E.IMPORT_CALL)
            }
            t == T.LT && jsx -> jsxElement()
            else -> {
                b.error("expression expected")
                if (t != null && t !in EXPRESSION_FOLLOW) advance()
            }
        }
    }

    private fun newExpression() {
        val m = mark()
        advance()
        if (eat(T.DOT)) { expectIdent(); m.done(E.LITERAL); return } // new.target
        var c = mark()
        if (atKw("new")) newExpression() else primary()
        while (at(T.DOT) || at(T.LBRACKET)) {
            if (eat(T.DOT)) {
                if (at(T.PRIVATE_NAME)) advance() else expectIdent()
                c.done(E.REFERENCE)
            } else {
                advance(); expression(); expect(T.RBRACKET, "']'")
                c.done(E.INDEX_EXPRESSION)
            }
            c = c.precede()
        }
        c.drop()
        if (at(T.LT) && typeArgumentsEnd(0) != null) typeArguments()
        if (at(T.LPAR)) arguments()
        m.done(E.NEW_EXPRESSION)
    }

    private fun functionExpression() {
        val m = mark()
        if (atSoft("async")) soft()
        functionRest(m, E.FUNCTION_EXPRESSION)
    }

    /** `x =>`, `async x =>`, `(…) =>`, `(…): T =>`, `<T>(…) =>` (not in JSX files). */
    private fun arrowFunction(): Boolean {
        val async = atSoft("async") && !newlineBeforeNext() && la(1).let { it == T.IDENTIFIER || it == T.LPAR || it == T.LT }
        val o = if (async) 1 else 0
        val first = la(o)
        val simple = (first == T.IDENTIFIER || first == T.kw("yield") || first == T.kw("await")) && la(o + 1) == T.ARROW
        val parens = first == T.LPAR && parenArrowAhead(o)
        val generic = first == T.LT && !jsx && la(o + 1) == T.IDENTIFIER && genericArrowAhead(o)
        if (!simple && !parens && !generic) return false
        val m = mark()
        if (async) soft()
        if (simple) {
            val list = mark(); val p = mark(); advanceAsIdent(); p.done(E.PARAMETER); list.done(E.PARAMETER_LIST)
        } else {
            typeParameters()
            parameterList()
            typeAnnotation()
        }
        expect(T.ARROW, "'=>'")
        if (at(T.LBRACE)) block() else assignment()
        m.done(E.ARROW_FUNCTION)
        return true
    }

    /** At lookahead [o] = `(`: the matching `)` is followed by `=>`, or by `:` and a type then `=>`. */
    private fun parenArrowAhead(o: Int): Boolean {
        val close = matching(o) ?: return false
        return when (la(close + 1)) {
            T.ARROW -> true
            T.COLON -> {
                var depth = 0
                var i = close + 2
                while (i < close + 200) {
                    val t = la(i) ?: return false
                    when {
                        t == T.ARROW && depth == 0 -> return true
                        t == T.LPAR || t == T.LBRACKET || t == T.LBRACE || t == T.LT -> depth++
                        t == T.RPAR || t == T.RBRACKET || t == T.RBRACE || t == T.GT -> { depth--; if (depth < 0) return false }
                        depth == 0 && t !in TYPE_ARGUMENT_TOKENS -> return false
                    }
                    i++
                }
                false
            }
            else -> false
        }
    }

    private fun genericArrowAhead(o: Int): Boolean =
        typeArgumentsEnd(o)?.let { la(it + 1) == T.LPAR && parenArrowAhead(it + 1) } == true

    private fun template() {
        val m = mark()
        if (eat(T.TEMPLATE_FULL)) { m.done(E.TEMPLATE_EXPRESSION); return }
        advance() // head
        withNoIn(false) {
            while (!b.eof()) {
                expression()
                if (eat(T.TEMPLATE_MIDDLE)) continue
                expect(T.TEMPLATE_TAIL, "'`'")
                break
            }
        }
        m.done(E.TEMPLATE_EXPRESSION)
    }

    private fun arrayLiteral() {
        val m = mark()
        advance()
        withNoIn(false) {
            while (!at(T.RBRACKET) && !b.eof()) {
                if (eat(T.COMMA)) continue
                if (at(T.ELLIPSIS)) { val s = mark(); advance(); assignment(); s.done(E.SPREAD) } else assignment()
                if (!eat(T.COMMA)) break
            }
        }
        expect(T.RBRACKET, "']'")
        m.done(E.ARRAY_LITERAL)
    }

    private fun objectLiteral() {
        val m = mark()
        advance()
        withNoIn(false) {
            while (!at(T.RBRACE) && !b.eof()) {
                val before = b.currentOffset
                objectMember()
                if (b.currentOffset == before) { b.error("property expected"); advance() }
                if (!eat(T.COMMA)) break
            }
        }
        expect(T.RBRACE, "'}'")
        m.done(E.OBJECT_LITERAL)
    }

    private fun objectMember() {
        val p = mark()
        if (at(T.ELLIPSIS)) { advance(); assignment(); p.done(E.SPREAD); return }
        val accessor = atSoft(OBJECT_MODIFIERS) && la(1).let { it in T.NAME_LIKE || it == T.STRING || it == T.NUMBER || it == T.LBRACKET || it == T.MUL }
        if (accessor) soft()
        if (eat(T.MUL) || accessor) { propertyName(); methodRest(p); return }
        val shorthandCandidate = at(T.IDENTIFIER)
        propertyName()
        when {
            at(T.LPAR) || at(T.LT) -> methodRest(p)
            eat(T.COLON) -> { assignment(); p.done(E.PROPERTY_ASSIGNMENT) }
            shorthandCandidate -> { if (eat(T.EQ)) assignment(); p.done(E.SHORTHAND_PROPERTY) }
            else -> { b.error("':' expected"); p.done(E.PROPERTY_ASSIGNMENT) }
        }
    }

    private fun methodRest(p: PsiBuilder.Marker) {
        eat(T.QUEST)
        typeParameters()
        parameterList()
        typeAnnotation()
        if (at(T.LBRACE)) block() else b.error("'{' expected")
        p.done(E.METHOD)
    }

    // ---------------------------------------------------------------- JSX

    private fun jsxElement() {
        val m = mark()
        advance() // <
        if (eat(T.GT)) {
            jsxChildren()
            closingTag()
            m.done(E.JSX_ELEMENT)
            return
        }
        jsxTagName()
        while (!b.eof() && !at(T.GT) && !at(T.DIV)) {
            when {
                at(T.LBRACE) -> jsxExpression()
                at(T.IDENTIFIER) || at(T.JSX_NAME) -> {
                    val a = mark()
                    advance()
                    if (eat(T.COLON)) advance()
                    if (eat(T.EQ)) {
                        when {
                            at(T.STRING) -> advance()
                            at(T.LBRACE) -> jsxExpression()
                            at(T.LT) -> jsxElement()
                            else -> b.error("attribute value expected")
                        }
                    }
                    a.done(E.JSX_ATTRIBUTE)
                }
                else -> { b.error("attribute expected"); advance() }
            }
        }
        if (eat(T.DIV)) { expect(T.GT, "'>'"); m.done(E.JSX_ELEMENT); return }
        expect(T.GT, "'>'")
        jsxChildren()
        closingTag()
        m.done(E.JSX_ELEMENT)
    }

    private fun closingTag() {
        if (!at(T.LT)) { b.error("closing tag expected"); return }
        advance()
        expect(T.DIV, "'/'")
        if (!at(T.GT)) jsxTagName()
        expect(T.GT, "'>'")
    }

    private fun jsxChildren() {
        while (!b.eof()) {
            when {
                at(T.JSX_TEXT) -> advance()
                at(T.LBRACE) -> jsxExpression()
                at(T.LT) -> if (la(1) == T.DIV) return else jsxElement()
                else -> return
            }
        }
    }

    private fun jsxExpression() {
        val m = mark()
        advance()
        eat(T.ELLIPSIS)
        if (!at(T.RBRACE)) withNoIn(false) { expression() }
        expect(T.RBRACE, "'}'")
        m.done(E.JSX_EXPRESSION)
    }

    private fun jsxTagName() {
        when {
            at(T.IDENTIFIER) -> {
                var r = mark()
                advance()
                r.done(E.REFERENCE)
                while (at(T.DOT)) {
                    r = r.precede()
                    advance()
                    if (at(T.IDENTIFIER)) advance() else b.error("name expected")
                    r.done(E.REFERENCE)
                }
                if (eat(T.COLON)) { if (at(T.IDENTIFIER) || at(T.JSX_NAME)) advance() }
            }
            at(T.JSX_NAME) -> advance()
            else -> b.error("tag name expected")
        }
    }

    private companion object {
        val DECLARE_KEYWORDS = setOf(T.kw("var"), T.kw("let"), T.kw("const"), T.kw("function"), T.kw("class"), T.kw("enum"))
        val DECLARE_SOFT = setOf("interface", "type", "namespace", "module", "global", "abstract", "async")
        val PARAMETER_MODIFIERS = setOf("public", "private", "protected", "readonly", "override")
        val MEMBER_MODIFIERS = setOf("public", "private", "protected", "static", "readonly", "abstract", "override", "declare", "accessor", "async", "get", "set")
        val OBJECT_MODIFIERS = setOf("get", "set", "async")
        val TYPE_OPERATORS = setOf("keyof", "unique", "readonly")
        val ASSIGNMENT_OPERATORS = setOf("+=", "-=", "*=", "/=", "%=", "**=", "<<=", "&=", "|=", "^=", "&&=", "||=", "??=")

        fun isAssignmentOperator(text: String?) = text in ASSIGNMENT_OPERATORS

        val BINARY = TokenSet.create(T.LT, T.OR, T.AND, T.MUL, T.DIV, T.PLUS, T.MINUS, T.kw("instanceof"))
        val PREFIX = TokenSet.create(T.EXCL, T.TILDE, T.PLUS, T.MINUS, T.PLUSPLUS, T.MINUSMINUS, T.kw("typeof"), T.kw("void"), T.kw("delete"))
        /** Tokens after which a type ends (an operator must not swallow them). */
        val TYPE_FOLLOW = TokenSet.create(T.RPAR, T.RBRACKET, T.GT, T.COMMA, T.SEMICOLON, T.EQ, T.OR, T.AND, T.ARROW, T.COLON, T.QUEST)
        val EXPRESSION_FOLLOW = TokenSet.create(T.RPAR, T.RBRACKET, T.RBRACE, T.SEMICOLON, T.COMMA, T.COLON)
        /** What may appear between `<` and `>` of type arguments (a cheap scan before committing). */
        val TYPE_ARGUMENT_TOKENS = TokenSet.orSet(
            TokenSet.create(
                T.IDENTIFIER, T.DOT, T.COMMA, T.LT, T.GT, T.LBRACKET, T.RBRACKET, T.LPAR, T.RPAR, T.LBRACE, T.RBRACE, T.OR, T.AND,
                T.ARROW, T.COLON, T.QUEST, T.STRING, T.NUMBER, T.ELLIPSIS, T.MINUS,
                T.kw("typeof"), T.kw("void"), T.kw("null"), T.kw("true"), T.kw("false"), T.kw("this"), T.kw("extends"), T.kw("new"),
                T.kw("import"), T.TEMPLATE_FULL,
            ),
        )
    }
}
