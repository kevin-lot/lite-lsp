#!/usr/bin/env python3
"""Import the Protocol Buffers lexer and grammar of JetBrains' protoeditor (Apache 2.0) into the protobuf module.

    git clone --depth 1 --filter=blob:none --sparse https://github.com/JetBrains/intellij-plugins /tmp/ip
    git -C /tmp/ip sparse-checkout set --no-cone /protobuf/
    python3 tools/import_protoeditor.py /tmp/ip/protobuf

Kept: proto.bnf (rules), proto.flex (lexer), ProtoTokenTypes, PbParserUtil (keyword identifiers, block-aware
recovery), Google's well-known types (google/protobuf/*.proto). Dropped: their PSI (mixins, stubs, psi interfaces) and the text-format parser of aggregate option
values (`option (x) = { … }`), which is skipped as a balanced block.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE = os.path.join(ROOT, "protobuf")
UP_PKG = "me.kevinlot.lite.proto.upstream"
HEADER = "// Imported from JetBrains/intellij-plugins (protobuf/protoeditor-core) by tools/import_protoeditor.py. Do not edit.\n"


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    print("wrote", os.path.relpath(path, ROOT))


def bnf(src):
    s = open(src).read()
    head_end = s.index("tokens=[")
    head = s[:head_end]
    head = re.sub(r'generate=\[[^\]]*\]', 'generate=[names="long" psi="no" tokens="no"]', head)
    head = head.replace('parserClass="com.intellij.protobuf.lang.parser.PbParser"', 'parserClass="me.kevinlot.lite.proto.parser.PbParser"')
    head = head.replace('parserUtilClass="com.intellij.protobuf.lang.parser.PbParserUtil"', f'parserUtilClass="{UP_PKG}.PbParserUtil"')
    head = head.replace('"static com.intellij.protobuf.lang.psi.ProtoTokenTypes.*"', f'"static {UP_PKG}.ProtoTokenTypes.*"')
    head = re.sub(r'\n\s*(implements|extends|psiClassPrefix|psiImplClassSuffix|psiPackage|psiImplPackage|psiImplUtilClass)=[^\n]*', '', head)
    head = head.replace('elementTypeHolderClass="com.intellij.protobuf.lang.psi.PbTypes"', 'elementTypeHolderClass="me.kevinlot.lite.proto.parser.PbTypes"')
    head = head.replace('elementTypeClass="com.intellij.protobuf.lang.psi.PbElementType"', 'elementTypeClass="me.kevinlot.lite.proto.parser.PbElementType"')
    body = s[head_end:]
    # their PSI: interfaces, mixins, generated methods, stubs
    body = re.sub(r'\n\s*implements\s*=\s*\[[^\]]*\]', '', body)
    body = re.sub(r'\n\s*methods\s*=\s*\[[^\]]*\]', '', body)
    body = re.sub(r"\n\s*(implements|mixin|stubClass|elementTypeFactory)\s*=\s*(?:'[^']*'|\"[^\"]*\")", '', body)
    body = re.sub(r"\n\s*extends\s*=\s*'com\.[^']*'", '', body)
    # aggregate option values: a balanced block instead of the text-format grammar
    body = re.sub(r'AggregateValue ::= <<Block <<BlockBodyOptional AggregateValueEntry>>>> \{[^}]*\}',
                  "AggregateValue ::= '{' <<skipBalancedBlock>> '}' { pin = 1 }", body)
    body = re.sub(r'private AggregateValueEntry ::= <<parseTextField>> \{[^}]*\}\n', '', body)
    body = re.sub(r"private AggregateValueRecovery ::= !\('}' \| <<parseTextFieldName>>\)\n", '', body)
    assert "parseTextField" not in body, "text-format rules left"
    assert "com.intellij.protobuf" not in body, "upstream classes left"
    write(os.path.join(MODULE, "src/main/grammars/Proto.bnf"), "// " + HEADER + head + body)


def flex(src):
    s = open(src).read()
    s = s.replace("package com.intellij.protobuf.lang.lexer;", "package me.kevinlot.lite.proto.lexer;")
    s = s.replace("import com.intellij.protobuf.lang.lexer.ProtoLexer.CommentStyle;\n", "")
    s = s.replace("import com.intellij.protobuf.lang.psi.ProtoTokenTypes;", f"import {UP_PKG}.ProtoTokenTypes;")
    # Lite: protobuf files only (C-style comments, keywords returned, no `1f` float casts)
    s = re.sub(r'%\{.*?%\}', '%{\n  private IElementType keyword(IElementType type) { return type; }\n  private static final boolean allowFloatCast = false;\n%}', s, flags=re.S)
    s = s.replace("commentStyle == CommentStyle.C_STYLE", "true").replace("commentStyle == CommentStyle.SH_STYLE", "false")
    assert "com.intellij.protobuf" not in s and "CommentStyle" not in s
    write(os.path.join(MODULE, "src/main/grammars/Proto.flex"), "// " + HEADER + s)


def token_types(src):
    s = open(src).read()
    s = s.replace("package com.intellij.protobuf.lang.psi;", f"package {UP_PKG};\n\nimport me.kevinlot.lite.proto.lexer.ProtoTokenType;\nimport me.kevinlot.lite.proto.lexer.ProtoKeywordTokenType;\nimport me.kevinlot.lite.proto.lexer.ProtoCommentTokenType;")
    assert "com.intellij.protobuf" not in s
    write(os.path.join(MODULE, f"src/upstream/java/{UP_PKG.replace('.', '/')}/ProtoTokenTypes.java"), HEADER + s)


def parser_util(src):
    s = open(src).read()
    s = s.replace("package com.intellij.protobuf.lang.parser;", f"package {UP_PKG};")
    s = re.sub(r'import com\.intellij\.protobuf\.lang\.psi\.ProtoKeywordTokenType;', 'import me.kevinlot.lite.proto.lexer.ProtoKeywordTokenType;', s)
    s = re.sub(r'import com\.intellij\.protobuf\.lang\.psi\.ProtoTokenTypes;\n', '', s)
    s = re.sub(r'import com\.intellij\.protobuf\.[^\n]*\n', '', s)
    # text-format entry points are replaced by a balanced-block skip
    s = re.sub(r'\n  /\*\* Parses a prototext Field element[^\n]*\n  public static boolean parseTextField\(.*?\n  \}\n', '\n', s, flags=re.S)
    s = re.sub(r'\n  /\*\* Parses a prototext FieldName element[^\n]*\n  public static boolean parseTextFieldName\(.*?\n  \}\n', '\n', s, flags=re.S)
    s = s.replace("  private PbParserUtil() {}", '''  /** Added by the import: an aggregate option value `{ … }` is consumed up to its closing brace (not parsed). */
  public static boolean skipBalancedBlock(PsiBuilder builder, int level) {
    int depth = 0;
    while (!builder.eof()) {
      IElementType t = builder.getTokenType();
      if (t == ProtoTokenTypes.LBRACE) depth++;
      else if (t == ProtoTokenTypes.RBRACE) { if (depth == 0) return true; depth--; }
      builder.advanceLexer();
    }
    return true;
  }

  private PbParserUtil() {}''')
    assert "parseTextField" not in s and "com.intellij.protobuf" not in s, "parser util"
    write(os.path.join(MODULE, f"src/upstream/java/{UP_PKG.replace('.', '/')}/PbParserUtil.java"), HEADER + s)


def well_known(src_dir):
    """Google's well-known types (BSD-3, see their headers), served for `import "google/protobuf/…"`."""
    import shutil
    dst = os.path.join(MODULE, "src/main/resources/include/google/protobuf")
    os.makedirs(dst, exist_ok=True)
    for name in sorted(os.listdir(src_dir)):
        if name.endswith(".proto"):
            shutil.copyfile(os.path.join(src_dir, name), os.path.join(dst, name))
            print("wrote", os.path.relpath(os.path.join(dst, name), ROOT))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    core = os.path.join(sys.argv[1], "protoeditor-core/src/com/intellij/protobuf")
    bnf(os.path.join(core, "bnf/proto.bnf"))
    flex(os.path.join(core, "jflex/proto.flex"))
    token_types(os.path.join(core, "lang/psi/ProtoTokenTypes.java"))
    parser_util(os.path.join(core, "lang/parser/PbParserUtil.java"))
    well_known(os.path.join(sys.argv[1], "protoeditor-core/resources/include/google/protobuf"))


if __name__ == "__main__":
    main()
