package me.kevinlot.lite.qml.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import me.kevinlot.lite.qml.QmlFile
import me.kevinlot.lite.qml.QmlLanguage
import me.kevinlot.lite.qml.psi.QmlPsiFactoryImpl
import me.kevinlot.lite.typescript.lexer.TsLexer
import me.kevinlot.lite.typescript.parser.TsParser
import me.kevinlot.lite.typescript.psi.TsPsiFactoryImpl
import me.kevinlot.lite.typescript.lexer.TsTokens as T
import me.kevinlot.lite.typescript.parser.TsElementTypes as JS

class QmlElementType(debugName: String) : IElementType(debugName, QmlLanguage)

object QmlElementTypes {
    private fun t(name: String) = QmlElementType(name)
    val PRAGMA = t("PRAGMA")
    val IMPORT = t("IMPORT")
    val MODULE_URI = t("MODULE_URI")
    val OBJECT = t("OBJECT")                 // Type { … }
    val OBJECT_BODY = t("OBJECT_BODY")
    val OBJECT_LIST = t("OBJECT_LIST")       // [ A {}, B {} ]
    val TYPE_REF = t("TYPE_REF")
    val ID = t("ID")                         // id: name
    val PROPERTY = t("PROPERTY")             // [default|required|readonly] property type name[: value]
    val SIGNAL = t("SIGNAL")
    val SIGNAL_PARAMETER = t("SIGNAL_PARAMETER")
    val ENUM = t("ENUM")
    val ENUM_MEMBER = t("ENUM_MEMBER")
    val INLINE_COMPONENT = t("INLINE_COMPONENT") // component Name: Base { … }
    val REQUIRED = t("REQUIRED")             // required name (makes an inherited property required)
    val BINDING = t("BINDING")               // a.b: value
    val GROUP = t("GROUP")                   // font { bold: true }
    val PROPERTY_REF = t("PROPERTY_REF")     // a.b on the left of a binding (also `on x`)
    val ANNOTATION = t("ANNOTATION")         // @Deprecated { … }

    val DECLARATIONS = TokenSet.create(ID, PROPERTY, SIGNAL, SIGNAL_PARAMETER, ENUM, ENUM_MEMBER, INLINE_COMPONENT)
}

private val Q = QmlElementTypes

/**
 * QML documents (and `.qmltypes`): imports, pragmas and object trees are parsed here; every JavaScript part
 * (binding values, handlers, functions) by the TypeScript/JavaScript parser this one extends.
 */
class QmlParser : TsParser(jsx = false) {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        b = builder
        val m = mark()
        while (!b.eof()) {
            val before = b.currentOffset
            when {
                atSoft("pragma") -> pragma()
                atKw("import") -> import()
                at(T.AT) -> annotation()
                identLike() -> objectDefinition(mark())
                else -> { b.error("object expected"); advance() }
            }
            eat(T.SEMICOLON)
            if (b.currentOffset == before) advance()
        }
        m.done(root)
        return b.treeBuilt
    }

    private fun pragma() {
        val m = mark()
        soft()
        expectIdent()
        if (eat(T.COLON)) do expectIdent() while (eat(T.COMMA))
        m.done(Q.PRAGMA)
    }

    /** `import QtQuick 2.15`, `import QtQuick.Layouts as L`, `import "dir"`, `import "util.js" as Util`. */
    private fun import() {
        val m = mark()
        advance()
        if (at(T.STRING)) advance() else {
            val uri = mark()
            expectIdent("module")
            while (at(T.DOT) && la(1) == T.IDENTIFIER) { advance(); advance() }
            uri.done(Q.MODULE_URI)
        }
        if (at(T.NUMBER)) advance()
        if (atSoft("as")) { soft(); expectIdent() }
        m.done(Q.IMPORT)
    }

    private fun objectDefinition(m: PsiBuilder.Marker) {
        typeRef()
        if (atSoft("on")) { soft(); propertyRef() }
        objectBody()
        m.done(Q.OBJECT)
    }

    private fun typeRef() {
        val r = mark()
        expectIdent("type")
        while (at(T.DOT) && la(1) == T.IDENTIFIER) { advance(); advance() }
        r.done(Q.TYPE_REF)
    }

    private fun objectBody() {
        val m = mark()
        expect(T.LBRACE, "'{'")
        while (!at(T.RBRACE) && !b.eof()) {
            val before = b.currentOffset
            member()
            while (eat(T.SEMICOLON)) { /* separators */ }
            if (b.currentOffset == before) { b.error("member expected"); advance() }
        }
        expect(T.RBRACE, "'}'")
        m.done(Q.OBJECT_BODY)
    }

    /** `@Deprecated { reason: "…" }` before an object or a member. */
    private fun annotation() {
        val m = mark()
        advance()
        typeRef()
        if (at(T.LBRACE)) objectBody()
        m.done(Q.ANNOTATION)
    }

    private fun member() {
        when {
            at(T.AT) -> annotation()
            atSoft("id") && la(1) == T.COLON -> { val m = mark(); soft(); advance(); expectIdent(); m.done(Q.ID) }
            isPropertyDeclaration() -> property()
            atSoft("signal") && la(1) == T.IDENTIFIER -> signal()
            atKw("function") || (atSoft("async") && la(1) == T.kw("function")) -> { val m = mark(); if (atSoft("async")) soft(); functionRest(m, JS.FUNCTION_DECLARATION) }
            atKw("enum") && la(1) == T.IDENTIFIER -> enum()
            atSoft("component") && la(1) == T.IDENTIFIER && la(2) == T.COLON -> {
                val m = mark(); soft(); advance(); advance()
                objectDefinition(mark())
                m.done(Q.INLINE_COMPONENT)
            }
            atSoft("required") && la(1) == T.IDENTIFIER && la(2) != T.IDENTIFIER && laText(1) != "property" -> {
                val m = mark(); soft(); propertyRef(); m.done(Q.REQUIRED)
            }
            identLike() || nameLike() -> bindingOrObject()
            else -> { b.error("member expected"); advance() }
        }
    }

    private fun isPropertyDeclaration(): Boolean {
        var i = 0
        while (true) {
            val t = la(i) ?: return false
            val text = laText(i)
            when {
                t == T.kw("default") || (t == T.IDENTIFIER && text in PROPERTY_MODIFIERS) -> i++
                t == T.IDENTIFIER && text == "property" -> return la(i + 1) == T.IDENTIFIER || la(i + 1) == T.kw("var")
                else -> return false
            }
        }
    }

    private fun property() {
        val m = mark()
        while (!atSoft("property")) if (atKw("default")) advance() else soft()
        soft()
        if (atSoft("alias")) soft() else {
            typeRef()
            if (eat(T.LT)) { typeRef(); expect(T.GT, "'>'") }
        }
        expectIdent()
        if (eat(T.COLON)) bindingValue()
        m.done(Q.PROPERTY)
    }

    /** `signal moved(int x, real y)` or `signal moved(x: int, y: real)`. */
    private fun signal() {
        val m = mark()
        soft()
        expectIdent()
        if (eat(T.LPAR)) {
            while (!at(T.RPAR) && !b.eof()) {
                val p = mark()
                if (identLike() && la(1) == T.COLON) { advance(); advance(); typeRef() } else { typeRef(); expectIdent() }
                p.done(Q.SIGNAL_PARAMETER)
                if (!eat(T.COMMA)) break
            }
            expect(T.RPAR, "')'")
        }
        m.done(Q.SIGNAL)
    }

    private fun enum() {
        val m = mark()
        advance()
        expectIdent()
        expect(T.LBRACE, "'{'")
        while (!at(T.RBRACE) && !b.eof()) {
            val e = mark()
            expectIdent()
            if (eat(T.EQ)) { eat(T.MINUS); expect(T.NUMBER, "number") }
            e.done(Q.ENUM_MEMBER)
            if (!eat(T.COMMA)) break
        }
        expect(T.RBRACE, "'}'")
        m.done(Q.ENUM)
    }

    /** `a.b: value`, `Type { }`, `Type on prop { }`, `group { }`. */
    private fun bindingOrObject() {
        val last = pathEnd(0)
        val next = la(last + 1)
        val upper = laText(last)?.firstOrNull()?.isUpperCase() == true
        val m = mark()
        when {
            upper && (next == T.LBRACE || (next == T.IDENTIFIER && laText(last + 1) == "on")) -> objectDefinition(m)
            next == T.LBRACE -> { propertyRef(); objectBody(); m.done(Q.GROUP) }
            else -> {
                propertyRef()
                if (expect(T.COLON, "':'")) bindingValue()
                m.done(Q.BINDING)
            }
        }
    }

    /** Lookahead index of the last name of a dotted path starting at [i]. */
    private fun pathEnd(i: Int): Int {
        var j = i
        while (la(j + 1) == T.DOT && la(j + 2).let { it == T.IDENTIFIER || it in T.NAME_LIKE }) j += 2
        return j
    }

    private fun propertyRef() {
        var r = mark()
        if (nameLike()) advanceAsIdent() else b.error("property expected")
        r.done(Q.PROPERTY_REF)
        while (at(T.DOT)) {
            r = r.precede()
            advance()
            if (nameLike()) advanceAsIdent() else b.error("property expected")
            r.done(Q.PROPERTY_REF)
        }
    }

    /** An object, a list of objects, a JavaScript block (handlers) or a JavaScript expression. */
    private fun bindingValue() {
        when {
            identLike() && startsObject(0) -> objectDefinition(mark())
            at(T.LBRACKET) && la(1) == T.IDENTIFIER && startsObject(1) -> {
                val m = mark()
                advance()
                while (!at(T.RBRACKET) && !b.eof()) {
                    objectDefinition(mark())
                    if (!eat(T.COMMA)) break
                }
                expect(T.RBRACKET, "']'")
                m.done(Q.OBJECT_LIST)
            }
            // `{ "key": value }` is an object; any other `{` a block (handlers)
            at(T.LBRACE) && la(2) == T.COLON && la(1).let { it == T.STRING || it == T.NUMBER } -> { expression(); semi() }
            at(T.LBRACE) -> block()
            // `x: if (c) a; else b`, `x: switch (v) { … }`: a statement
            b.tokenType in STATEMENT_KEYWORDS -> statement()
            else -> { expression(); semi() }
        }
    }

    private fun startsObject(i: Int): Boolean {
        val last = pathEnd(i)
        return laText(last)?.firstOrNull()?.isUpperCase() == true &&
            (la(last + 1) == T.LBRACE || (la(last + 1) == T.IDENTIFIER && laText(last + 1) == "on"))
    }

    private companion object {
        val STATEMENT_KEYWORDS = TokenSet.create(T.kw("if"), T.kw("switch"), T.kw("for"), T.kw("while"), T.kw("do"), T.kw("try"), T.kw("throw"), T.kw("return"))
        val PROPERTY_MODIFIERS = setOf("readonly", "required", "final", "virtual", "override")
    }
}

object QmlFileElementType : IFileElementType("LITE_QML_FILE", QmlLanguage) {
    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode {
        val builder = PsiBuilderFactory.getInstance().createBuilder(psi.project, chameleon, TsLexer(jsx = false), language, chameleon.chars)
        return QmlParser().parse(this, builder).firstChildNode
    }
}

class QmlParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = TsLexer(jsx = false)
    override fun createParser(project: Project?): PsiParser = QmlParser()
    override fun getFileNodeType(): IFileElementType = QmlFileElementType
    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = T.STRINGS
    override fun getWhitespaceTokens(): TokenSet = T.WHITESPACES
    /** QML nodes get QML PSI; the embedded JavaScript gets the TypeScript/JavaScript PSI. */
    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType is QmlElementType) QmlPsiFactoryImpl.create(node) else TsPsiFactoryImpl.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = QmlFile(viewProvider)
}
