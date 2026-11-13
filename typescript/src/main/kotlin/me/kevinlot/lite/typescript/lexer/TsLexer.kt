package me.kevinlot.lite.typescript.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.typescript.lexer.TsTokens as T

/**
 * Hand-written TypeScript/JavaScript lexer (JSX optional).
 *
 * Context the grammar leaves to the lexer:
 * - `/` starts a regex only where an expression may start (after an operator, `(`, a keyword…);
 * - template literals and JSX nest through a frame stack (`${ … }` inside a template, `{ … }` inside JSX);
 * - with [jsx], `<` followed by a name or `>` where an expression may start opens an element.
 *
 * State: 0 = top level, regex allowed; 1 = top level, regex not allowed; 2 = inside a template or JSX
 * (not a restart point: the editor re-lexes from the previous top-level token).
 */
class TsLexer(private val jsx: Boolean = true) : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var pos = 0
    private var type: IElementType? = null
    private var state = 0
    private var regexOk = true
    /** Frames: [kind, brace depth or self-closing flag]. */
    private val stack = ArrayList<IntArray>()
    /** Per open `(`: does it follow `if`/`while`/`for`/`with` (then a regex may follow its `)`). */
    private val parens = ArrayList<Boolean>()
    private var lastSignificant: IElementType? = null
    private var closedStatementHead = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; pos = startOffset
        stack.clear(); parens.clear(); lastSignificant = null; regexOk = initialState != 1
        advance()
    }

    override fun getState() = state
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = pos
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        start = pos
        state = if (stack.isEmpty()) (if (regexOk) 0 else 1) else 2
        if (pos >= end) { type = null; return }
        val t = when (stack.lastOrNull()?.get(0)) {
            JSX_TAG, JSX_CLOSE -> jsxTag()
            JSX_CHILDREN -> jsxChildren()
            else -> js()
        }
        type = t
        if (t != T.WHITE_SPACE && t !in T.COMMENTS && t != T.JSX_TEXT) { regexOk = regexAfter(t); lastSignificant = t }
    }

    private fun regexAfter(t: IElementType): Boolean = when (t) {
        T.RPAR -> closedStatementHead
        T.IDENTIFIER, T.PRIVATE_NAME, T.NUMBER, T.STRING, T.REGEX, T.TEMPLATE_FULL, T.TEMPLATE_TAIL,
        T.RBRACKET, T.RBRACE, T.PLUSPLUS, T.MINUSMINUS, T.JSX_NAME -> false
        T.kw("this"), T.kw("super"), T.kw("true"), T.kw("false"), T.kw("null") -> false
        T.GT -> !jsxClosed
        else -> true
    }

    private fun ch(i: Int = 0): Char = if (pos + i < end) buf[pos + i] else '\u0000'

    // ---------------------------------------------------------------- JavaScript

    private fun js(): IElementType {
        jsxClosed = false
        val c = ch()
        if (c.isWhitespace()) { while (pos < end && ch().isWhitespace()) pos++; return T.WHITE_SPACE }
        if (Character.isJavaIdentifierStart(c) || c == '\\') return identifier()
        if (c.isDigit() || (c == '.' && ch(1).isDigit())) return number()
        when (c) {
            '"', '\'' -> return string(c)
            '`' -> { pos++; return template(head = true) }
            '/' -> return slash()
            '#' -> {
                if (pos == 0 && ch(1) == '!') return lineComment()
                pos++
                if (Character.isJavaIdentifierStart(ch())) { while (pos < end && Character.isJavaIdentifierPart(ch())) pos++; return T.PRIVATE_NAME }
                return T.BAD_CHARACTER
            }
            '{' -> { stack.lastOrNull()?.let { if (it[0] == TEMPLATE || it[0] == JSX_EXPR) it[1]++ }; pos++; return T.LBRACE }
            '}' -> {
                val top = stack.lastOrNull()
                if (top != null && (top[0] == TEMPLATE || top[0] == JSX_EXPR)) {
                    if (top[1] == 0) {
                        stack.removeAt(stack.size - 1); pos++
                        return if (top[0] == TEMPLATE) template(head = false) else T.RBRACE
                    }
                    top[1]--
                }
                pos++; return T.RBRACE
            }
            '<' -> {
                if (jsx && regexOk && (Character.isJavaIdentifierStart(ch(1)) || ch(1) == '>')) { stack += intArrayOf(JSX_TAG, 0); pos++; return T.LT }
                return op(if (ch(1) == '<') (if (ch(2) == '=') 3 else 2) else if (ch(1) == '=') 2 else 1, T.LT)
            }
            '>' -> { pos++; return T.GT }
            '=' -> return when {
                ch(1) == '>' -> { pos += 2; T.ARROW }
                ch(1) == '=' -> op(if (ch(2) == '=') 3 else 2)
                else -> { pos++; T.EQ }
            }
            '!' -> return if (ch(1) == '=') op(if (ch(2) == '=') 3 else 2) else { pos++; T.EXCL }
            '?' -> return when {
                ch(1) == '.' && !ch(2).isDigit() -> { pos += 2; T.QUEST_DOT }
                ch(1) == '?' -> op(if (ch(2) == '=') 3 else 2)
                else -> { pos++; T.QUEST }
            }
            '.' -> return if (ch(1) == '.' && ch(2) == '.') { pos += 3; T.ELLIPSIS } else { pos++; T.DOT }
            '*' -> return if (ch(1) == '*') op(if (ch(2) == '=') 3 else 2) else if (ch(1) == '=') op(2) else { pos++; T.MUL }
            '&' -> return doubled('&', T.AND)
            '|' -> return doubled('|', T.OR)
            '+' -> return if (ch(1) == '+') { pos += 2; T.PLUSPLUS } else if (ch(1) == '=') op(2) else { pos++; T.PLUS }
            '-' -> return if (ch(1) == '-') { pos += 2; T.MINUSMINUS } else if (ch(1) == '=') op(2) else { pos++; T.MINUS }
            '%', '^' -> return op(if (ch(1) == '=') 2 else 1)
        }
        pos++
        when (c) {
            '(' -> { parens += lastSignificant in STATEMENT_HEADS; return T.LPAR }
            ')' -> { closedStatementHead = parens.removeLastOrNull() ?: false; return T.RPAR }
        }
        return when (c) { '[' -> T.LBRACKET; ']' -> T.RBRACKET
            ';' -> T.SEMICOLON; ',' -> T.COMMA; ':' -> T.COLON; '~' -> T.TILDE; '@' -> T.AT
            else -> T.BAD_CHARACTER
        }
    }

    private fun op(length: Int, single: IElementType = T.OPERATOR): IElementType {
        pos += length
        return if (length == 1) single else T.OPERATOR
    }

    /** `&`, `&&`, `&=`, `&&=` (same for `|`). */
    private fun doubled(c: Char, single: IElementType): IElementType = when {
        ch(1) == c -> op(if (ch(2) == '=') 3 else 2)
        ch(1) == '=' -> op(2)
        else -> { pos++; single }
    }

    private fun identifier(): IElementType {
        val s = pos
        while (pos < end && (Character.isJavaIdentifierPart(ch()) || ch() == '\\')) {
            if (ch() == '\\') pos++ // \u escape
            pos++
        }
        if (pos == s) { pos++; return T.BAD_CHARACTER }
        return T.KEYWORDS[buf.subSequence(s, pos).toString()] ?: T.IDENTIFIER
    }

    private fun number(): IElementType {
        if (ch() == '0' && ch(1).lowercaseChar() in "xbo") {
            pos += 2
            while (pos < end && (ch().isLetterOrDigit() || ch() == '_')) pos++
            return T.NUMBER
        }
        while (pos < end && (ch().isDigit() || ch() == '_')) pos++
        if (ch() == '.') { pos++; while (pos < end && (ch().isDigit() || ch() == '_')) pos++ }
        if (ch().lowercaseChar() == 'e' && (ch(1).isDigit() || (ch(1) in "+-" && ch(2).isDigit()))) {
            pos += 2; while (pos < end && ch().isDigit()) pos++
        }
        if (ch() == 'n') pos++
        return T.NUMBER
    }

    private fun string(quote: Char): IElementType {
        pos++
        while (pos < end) {
            val c = ch()
            when {
                c == '\\' -> pos += 2
                c == quote -> { pos++; break }
                c == '\n' -> break
                else -> pos++
            }
        }
        if (pos > end) pos = end
        return T.STRING
    }

    /** After `` ` `` ([head]) or after the `}` closing a substitution. */
    private fun template(head: Boolean): IElementType {
        while (pos < end) {
            val c = ch()
            when {
                c == '\\' -> pos += 2
                c == '`' -> { pos++; return if (head) T.TEMPLATE_FULL else T.TEMPLATE_TAIL }
                c == '$' && ch(1) == '{' -> { pos += 2; stack += intArrayOf(TEMPLATE, 0); return if (head) T.TEMPLATE_HEAD else T.TEMPLATE_MIDDLE }
                else -> pos++
            }
        }
        pos = end
        return if (head) T.TEMPLATE_FULL else T.TEMPLATE_TAIL
    }

    private fun lineComment(): IElementType {
        while (pos < end && ch() != '\n') pos++
        return T.LINE_COMMENT
    }

    private fun slash(): IElementType {
        when (ch(1)) {
            '/' -> return lineComment()
            '*' -> {
                val doc = ch(2) == '*' && ch(3) != '/'
                pos += 2
                while (pos < end && !(ch() == '*' && ch(1) == '/')) pos++
                pos = minOf(pos + 2, end)
                return if (doc) T.DOC_COMMENT else T.BLOCK_COMMENT
            }
        }
        if (regexOk) regex()?.let { return it }
        return if (ch(1) == '=') op(2) else { pos++; T.DIV }
    }

    private fun regex(): IElementType? {
        var i = pos + 1
        var inClass = false
        while (i < end) {
            val c = buf[i]
            when {
                c == '\n' -> return null
                c == '\\' -> i++
                c == '[' -> inClass = true
                c == ']' -> inClass = false
                c == '/' && !inClass -> {
                    i++
                    while (i < end && Character.isJavaIdentifierPart(buf[i])) i++
                    pos = i
                    return T.REGEX
                }
            }
            i++
        }
        return null
    }

    // ---------------------------------------------------------------- JSX

    /** The last `>` closed an element (it is a value: a following `/` divides). */
    private var jsxClosed = false

    private fun jsxTag(): IElementType {
        jsxClosed = false
        val frame = stack.last()
        val c = ch()
        if (c.isWhitespace()) { while (pos < end && ch().isWhitespace()) pos++; return T.WHITE_SPACE }
        if (Character.isJavaIdentifierStart(c)) {
            var dashed = false
            while (pos < end && (Character.isJavaIdentifierPart(ch()) || ch() == '-')) { if (ch() == '-') dashed = true; pos++ }
            return if (dashed) T.JSX_NAME else T.IDENTIFIER
        }
        pos++
        return when (c) {
            '/' -> { if (frame[0] == JSX_TAG) frame[1] = 1; T.DIV }
            '>' -> {
                stack.removeAt(stack.size - 1)
                when {
                    frame[0] == JSX_CLOSE -> { if (stack.lastOrNull()?.get(0) == JSX_CHILDREN) stack.removeAt(stack.size - 1); jsxClosed = !inJsxMarkup() }
                    frame[1] == 1 -> jsxClosed = !inJsxMarkup()
                    else -> stack += intArrayOf(JSX_CHILDREN, 0)
                }
                T.GT
            }
            '{' -> { stack += intArrayOf(JSX_EXPR, 0); regexOk = true; T.LBRACE }
            '"', '\'' -> { while (pos < end && ch() != c) pos++; if (pos < end) pos++; T.STRING }
            '=' -> T.EQ
            '.' -> T.DOT
            ':' -> T.COLON
            '<' -> T.LT
            else -> T.BAD_CHARACTER
        }
    }

    private fun inJsxMarkup() = stack.lastOrNull()?.get(0).let { it == JSX_CHILDREN || it == JSX_TAG }

    private fun jsxChildren(): IElementType {
        when (ch()) {
            '{' -> { stack += intArrayOf(JSX_EXPR, 0); pos++; return T.LBRACE }
            '<' -> { stack += intArrayOf(if (ch(1) == '/') JSX_CLOSE else JSX_TAG, 0); pos++; return T.LT }
        }
        while (pos < end && ch() != '{' && ch() != '<') pos++
        return T.JSX_TEXT
    }

    private companion object {
        val STATEMENT_HEADS = setOf(T.kw("if"), T.kw("while"), T.kw("for"), T.kw("with"))
        const val TEMPLATE = 0
        const val JSX_TAG = 1
        const val JSX_CHILDREN = 2
        const val JSX_EXPR = 3
        const val JSX_CLOSE = 4
    }
}
