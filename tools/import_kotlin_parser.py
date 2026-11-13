#!/usr/bin/env python3
"""Import the Kotlin lexer and parser from the Kotlin compiler (Apache 2.0) into kotlin/.

    git clone --depth 1 --filter=blob:none --sparse https://github.com/JetBrains/kotlin /tmp/kotlin
    git -C /tmp/kotlin sparse-checkout set compiler/psi/parser compiler/psi/psi-api license
    python3 tools/import_kotlin_parser.py /tmp/kotlin

What changes vs upstream:
  * packages moved to me.kevinlot.lite.kotlin.{lexer,parser}
  * node types are plain element types (no stubs, no compiler PSI classes)
  * KDoc is a single comment token (no KDoc parser)
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "kotlin"
JAVA = ROOT / "src/upstream/java/me/kevinlot/lite/kotlin/upstream"
PKG = "me.kevinlot.lite.kotlin"
# Imported code lives in its own package: nothing in it is ours to edit.
UP = f"{PKG}.upstream"

LEXER_FILES = ["KtTokens.java", "KtToken.java", "KtSingleValueToken.java", "KtKeywordToken.java", "KtModifierKeywordToken.java"]
LEXER_IMPL = ["KotlinLexer.java", "KotlinLexerException.java"]
PARSER_FILES = [
    "AbstractKotlinParsing.java", "KotlinParsing.java", "KotlinExpressionParsing.java",
    "SemanticWhitespaceAwarePsiBuilder.java", "SemanticWhitespaceAwarePsiBuilderImpl.java",
    "SemanticWhitespaceAwarePsiBuilderAdapter.java", "SemanticWhitespaceAwarePsiBuilderForByClause.java",
    "TruncatedSemanticWhitespaceAwarePsiBuilder.java", "TokenStreamPattern.java", "TokenStreamPredicate.java",
    "AbstractTokenStreamPattern.java", "AbstractTokenStreamPredicate.java", "FirstBefore.java", "LastBefore.java",
    "Consumer.java", "KotlinWhitespaceAndCommentsBinders.kt",
]
NOTICE = "// Derived from the Kotlin compiler (Apache 2.0, see kotlin/THIRD_PARTY_LICENSE-kotlin.txt).\n"


def rename(text: str) -> str:
    t = text
    t = t.replace("org.jetbrains.kotlin.lexer", f"{UP}.lexer")
    t = t.replace("org.jetbrains.kotlin.parsing", f"{UP}.parser")
    t = t.replace("org.jetbrains.kotlin.lang.BinaryOperationPrecedence", f"{UP}.parser.BinaryOperationPrecedence")
    t = t.replace("org.jetbrains.kotlin.psi.stubs.elements.KtTokenSets", f"{UP}.parser.KtTokenSets")
    t = t.replace("org.jetbrains.kotlin.KtNodeTypes", f"{UP}.parser.KtNodeTypes")
    t = t.replace("org.jetbrains.kotlin.idea.KotlinLanguage", f"{PKG}.KtLanguage")
    t = t.replace("KotlinLanguage.INSTANCE", "KtLanguage.INSTANCE")
    t = t.replace("import org.jetbrains.kotlin.utils.strings.StringsKt;\n", "")
    t = t.replace("StringsKt.substringWithContext(", "ParserUtil.substringWithContext(")
    return t


def node_types(src: str) -> str:
    """KtNodeTypes with plain element types: `NAME = KtStubBasedElementTypes.X` -> `new KtNodeType("NAME")`."""
    out = []
    for line in src.splitlines():
        m = re.match(r'(\s*)(@Deprecated\s+)?@NotNull\s+(I\w*ElementType)\s+(\w+)\s*=\s*(.+);\s*$', line)
        if not m:
            if line.startswith("import org.jetbrains.kotlin.psi") or "KtStubBasedElementTypes" in line:
                continue
            out.append(line)
            continue
        indent, dep, kind, name, value = m.groups()
        dep = dep or ""
        if re.fullmatch(r"[A-Z_]+", value):  # alias of another constant
            out.append(f"{indent}{dep}@NotNull {kind} {name} = {value};")
        elif kind == "IFileElementType":
            # .kt and .kts share one file type; it picks parseFile/parseScript from the file name.
            out.append(f"{indent}{dep}@NotNull IFileElementType {name} = KtFileElementType.INSTANCE;")
        elif "KtLeftBoundNodeType" in value:
            out.append(f'{indent}{dep}@NotNull IElementType {name} = new KtNodeType.KtLeftBoundNodeType("{name}");')
        else:
            out.append(f'{indent}{dep}@NotNull IElementType {name} = new KtNodeType("{name}");')
    t = "\n".join(out) + "\n"
    t = t.replace("package org.jetbrains.kotlin;", f"package {UP}.parser;\n\nimport {PKG}.KtLanguage;\nimport {PKG}.parser.KtFileElementType;")
    return t


NODE_TYPE = f"""{NOTICE}package {UP}.parser;

import com.intellij.psi.tree.IElementType;
import {PKG}.KtLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/** Composite element type of the Kotlin tree (PSI is created by KtPsiFactoryImpl from the type). */
public class KtNodeType extends IElementType {{
    public KtNodeType(@NotNull @NonNls String debugName) {{
        super(debugName, KtLanguage.INSTANCE);
    }}

    /** Left-bound: whitespace before it is not included (used for delegation calls). */
    public static class KtLeftBoundNodeType extends KtNodeType {{
        public KtLeftBoundNodeType(@NotNull @NonNls String debugName) {{
            super(debugName);
        }}

        @Override
        public boolean isLeftBound() {{
            return true;
        }}
    }}
}}
"""

TOKEN_SETS = f"""{NOTICE}package {UP}.parser;

import com.intellij.psi.tree.TokenSet;
import {UP}.lexer.KtTokens;

/** The two token sets of the compiler's KtTokenSets that the parser needs. */
public interface KtTokenSets {{
    TokenSet POSTFIX_OPERATIONS = TokenSet.create(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS, KtTokens.EXCLEXCL, KtTokens.DOT,
        KtTokens.SAFE_ACCESS, KtTokens.ERROR_SAFE_ACCESS);
    TokenSet PREFIX_OPERATIONS = TokenSet.create(KtTokens.MINUS, KtTokens.PLUS, KtTokens.MINUSMINUS, KtTokens.PLUSPLUS, KtTokens.EXCL);
}}
"""

PARSER_UTIL = f"""{NOTICE}package {UP}.parser;

final class ParserUtil {{
    private ParserUtil() {{}}

    /** Text around [start, end) for error messages (from the compiler's StringsKt). */
    static String substringWithContext(CharSequence text, int start, int end, int context) {{
        int from = Math.max(0, start - context);
        int to = Math.min(text.length(), end + context);
        return text.subSequence(from, to).toString();
    }}
}}
"""

ENTRY = f"""{NOTICE}package {UP}.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/** Parses a whole `.kt` / `.kts` file eagerly (no lazy blocks or lambdas). */
public class KtParser implements PsiParser {{
    private final boolean script;

    public KtParser(boolean script) {{
        this.script = script;
    }}

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {{
        KotlinParsing parsing = KotlinParsing.createForTopLevelNonLazy(new SemanticWhitespaceAwarePsiBuilderImpl(builder));
        if (script) parsing.parseScript(); else parsing.parseFile();
        return builder.getTreeBuilt();
    }}
}}
"""


def main():
    up = Path(sys.argv[1])
    psi = up / "compiler/psi"
    (JAVA / "lexer").mkdir(parents=True, exist_ok=True)
    (JAVA / "parser").mkdir(parents=True, exist_ok=True)
    (ROOT / "src/main/grammars").mkdir(parents=True, exist_ok=True)

    for f in LEXER_FILES:
        t = rename((psi / "psi-api/src/org/jetbrains/kotlin/lexer" / f).read_text())
        if f == "KtTokens.java":
            t = t.replace(f"import org.jetbrains.kotlin.kdoc.lexer.KDocTokens;\n", "")
            t = t.replace("import org.jetbrains.kotlin.psi.KtPsiUtil;\n", "")
            t = t.replace("int EOF_Id = KDocTokens.CODE_SPAN_TEXT_Id + 1;", "int EOF_Id = 1;")
            t = t.replace("@NotNull IElementType DOC_COMMENT   = KDocTokens.KDOC;", '@NotNull IElementType DOC_COMMENT   = new KtToken("KDoc");')
            t = t.replace(f"import static org.jetbrains.kotlin.KtNodeTypes.*;", f"import static {UP}.parser.KtNodeTypes.*;")
        (JAVA / "lexer" / f).write_text(t)
    for f in LEXER_IMPL:
        (JAVA / "lexer" / f).write_text(rename((psi / "parser/src/org/jetbrains/kotlin/lexer" / f).read_text()))
    flex = rename((psi / "parser/src/org/jetbrains/kotlin/lexer/Kotlin.flex").read_text())
    (ROOT / "src/main/grammars/Kotlin.flex").write_text(NOTICE + flex)

    for f in PARSER_FILES:
        (JAVA / "parser" / f).write_text(rename((psi / "parser/src/org/jetbrains/kotlin/parsing" / f).read_text()))
    prec = rename((psi / "psi-api/src/org/jetbrains/kotlin/lang/BinaryOperationPrecedence.kt").read_text())
    prec = prec.replace("package org.jetbrains.kotlin.lang", f"package {UP}.parser")
    (JAVA / "parser/BinaryOperationPrecedence.kt").write_text(prec)
    (JAVA / "parser/KtNodeTypes.java").write_text(NOTICE + node_types((psi / "psi-api/src/org/jetbrains/kotlin/KtNodeTypes.java").read_text()))
    (JAVA / "parser/KtNodeType.java").write_text(NODE_TYPE)
    (JAVA / "parser/KtTokenSets.java").write_text(TOKEN_SETS)
    (JAVA / "parser/ParserUtil.java").write_text(PARSER_UTIL)
    (JAVA / "parser/KtParser.java").write_text(ENTRY)
    shutil.copy(up / "license/LICENSE.txt", ROOT / "THIRD_PARTY_LICENSE-kotlin.txt")
    print("ok")


if __name__ == "__main__":
    main()
