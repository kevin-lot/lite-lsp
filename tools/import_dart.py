#!/usr/bin/env python3
"""Import the Dart lexer and grammar of the official Dart plugin (Apache 2.0) into the dart module.

    git clone --depth 1 --filter=blob:none --sparse https://github.com/flutter/dart-intellij-third-party /tmp/dart
    git -C /tmp/dart sparse-checkout set --no-cone /third_party/LICENSE.TXT /third_party/src/main/java/com/jetbrains/lang/dart/
    python3 tools/import_dart.py /tmp/dart/third_party

Kept: Dart.bnf (rules), Dart.flex (lexer), DartLexer (merged comments and string parts), DartTokenTypesSets,
DartGeneratedParserUtilBase (operators made of `>` tokens, lazy blocks), DartElementType. Dropped: their PSI (mixins,
methods, interfaces: Lite's is in src/main/kotlin), the documentation-comment lexer (a doc comment is one token).
"""
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE = os.path.join(ROOT, "dart")
PKG = "me.kevinlot.lite.dart"
UP = PKG + ".upstream"
THEIRS = "com.jetbrains.lang.dart"
HEADER = "// Imported from flutter/dart-intellij-third-party (third_party) by tools/import_dart.py. Do not edit.\n"


# Forms the upstream grammar rejects: `l..[0].x`, `(A, B) Function()`, `required (A, B) r` read as a function named
# `required`, and `C.a || C.b when c` read as a variable named `when`.
PATCHES = [
    (
        "(voidTypeFunctionType | untypedFunctionType | simpleType) typedFunctionType+",
        "(voidTypeFunctionType | untypedFunctionType | simpleType | recordType) typedFunctionType+",
    ),
    ("functionFormalParameter ::= !functionTypeWrapper (", "functionFormalParameter ::= !functionTypeWrapper !('required' recordType <<nonStrictID>>) ("),
    (
        "(arrayAccess | refOrThisOrSuperOrParenExpression callOrArrayAccessOrQualifiedRefExpression) << varInitWrapper >>\n",
        "(cascadeArrayAccess | refOrThisOrSuperOrParenExpression) callOrArrayAccessOrQualifiedRefExpression << varInitWrapper >>\n"
        'cascadeArrayAccess ::= arrayAccess {elementType="arrayAccessExpression"}\n',
    ),
    ("| type !'as' referenceExpression\n", "| type !('as' | 'when' expression) referenceExpression\n"),
]


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    print("wrote", os.path.relpath(path, ROOT))


def java(name, text):
    assert THEIRS not in text, name
    write(os.path.join(MODULE, f"src/upstream/java/{UP.replace('.', '/')}/{name}"), HEADER + text)


def bnf(src):
    s = open(src).read()
    at = s.index("tokens = [")
    head, body = s[:at], s[at:]
    head = re.sub(r'\n\s*(implements|extends|psiClassPrefix|psiImplClassSuffix|psiPackage|psiImplPackage|psiImplUtilClass)="[^\n]*', "", head)
    head = head.replace(f'parserClass="{THEIRS}.DartParser"', f'generate=[psi="no"]\n\n  parserClass="{UP}.DartParser"')
    head = head.replace(f"{THEIRS}.", f"{UP}.")
    # their PSI: mixins, interfaces, generated methods; `extends=<rule>` stays, it shapes the tree
    body = re.sub(r'\s*\b(mixin|implements)\s*=\s*"[^"]*"', "", body)
    body = re.sub(r'\s*\bimplements\s*=\s*\[[^\]]*\]', "", body)
    body = re.sub(r'\s*\bmethods\s*=\s*\[[^\]]*\]', "", body)
    body = re.sub(r'\{\s*\}', "", body)
    assert THEIRS not in head + body and 'psi="no"' in head, "upstream classes left in the grammar"
    for theirs, ours in PATCHES:
        assert body.count(theirs) == 1, theirs
        body = body.replace(theirs, ours)
    write(os.path.join(MODULE, "src/main/grammars/Dart.bnf"), "// " + HEADER + head + body)


def flex(src):
    s = open(src).read().replace(f"package {THEIRS}.lexer;", f"package {UP};")
    s = s.replace(f"import static {THEIRS}.lexer.DartLexer.*;\n", "").replace(f"{THEIRS}.", f"{UP}.")
    assert THEIRS not in s
    write(os.path.join(MODULE, "src/main/grammars/Dart.flex"), "// " + HEADER + s)


def lexer(src):
    java("DartLexer.java", open(src).read().replace(f"package {THEIRS}.lexer;", f"package {UP};").replace(f"{THEIRS}.", f"{UP}."))


def element_type(src):
    s = open(src).read().replace(f"package {THEIRS};", f"package {UP};\n\nimport {PKG}.DartLanguage;")
    java("DartElementType.java", s)


def parser_util(src):
    java("DartGeneratedParserUtilBase.java", open(src).read().replace(f"package {THEIRS};", f"package {UP};").replace(f"{THEIRS}.", f"{UP}."))


def token_sets(src):
    s = open(src).read().replace(f"package {THEIRS};", f"package {UP};\n\nimport {PKG}.DartLanguage;")
    s = s.replace(f"import {THEIRS}.lexer.DartDocLexer;\n", "")
    s = s.replace(f"import {THEIRS}.psi.impl.DartLazyParseableBlockImpl;", "import com.intellij.psi.impl.source.tree.LazyParseablePsiElement;")
    s = s.replace(f"{THEIRS}.", f"{UP}.")
    s = s.replace("new DartDocCommentElementType()", 'new DartElementType("MULTI_LINE_DOC_COMMENT")')
    s = re.sub(r"\n  class DartDocCommentElementType extends ILazyParseableElementType \{.*?\n  \}\n(?=\n  class DartLazyParseableBlockElementType)", "\n", s, flags=re.S)
    s = s.replace("new DartLazyParseableBlockImpl(this, text)", "new LazyParseablePsiElement(this, text)")
    s = re.sub(r",\s*DartParserDefinition\.DART_FILE", "", s)
    s = s.replace("  // DartDocLexer splits MULTI_LINE_DOC_COMMENT in tokens\n", "")
    assert "DartDocLexer" not in s and "DartParserDefinition" not in s and "DartLazyParseableBlockImpl" not in s
    java("DartTokenTypesSets.java", s)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = os.path.join(sys.argv[1], "src/main/java", THEIRS.replace(".", "/"))
    bnf(os.path.join(src, "Dart.bnf"))
    flex(os.path.join(src, "lexer/Dart.flex"))
    lexer(os.path.join(src, "lexer/DartLexer.java"))
    element_type(os.path.join(src, "DartElementType.java"))
    parser_util(os.path.join(src, "DartGeneratedParserUtilBase.java"))
    token_sets(os.path.join(src, "DartTokenTypesSets.java"))
    shutil.copyfile(os.path.join(sys.argv[1], "LICENSE.TXT"), os.path.join(MODULE, "THIRD_PARTY_LICENSE-dart-intellij.txt"))
    print("wrote dart/THIRD_PARTY_LICENSE-dart-intellij.txt")


if __name__ == "__main__":
    main()
