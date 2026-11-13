#!/usr/bin/env python3
"""Import the Rust lexer/grammar from intellij-rust (MIT) and strip it down for Lite.

    git clone --depth 1 https://github.com/intellij-rust/intellij-rust /path/to/intellij-rust
    python3 tools/import_intellij_rust.py /path/to/intellij-rust

What changes vs upstream:
  * packages moved to me.kevinlot.lite.rust.*
  * no stubs, no upstream mixins/interfaces (we have our own, smaller ones, injected below)
"""
import re
import sys
from pathlib import Path

PKG = "me.kevinlot.lite.rust"
ROOT = Path(__file__).resolve().parent.parent
HEADER = "// Derived from intellij-rust (MIT, see THIRD_PARTY_LICENSE-intellij-rust.txt).\n"

# Rules whose PSI gets a name (Ctrl+Click target, rename).
NAMED = [
    "Function", "Constant", "StructItem", "EnumItem", "EnumVariant", "NamedFieldDecl",
    "TraitItem", "TypeAlias", "ModItem", "ModDeclItem", "TypeParameter", "ConstParameter",
    "PatBinding", "Alias", "ExternCrateItem", "TraitAlias", "Macro", "Macro2",
]
# Rules that carry a reference (Ctrl+Click source).
MIXINS = {
    "Path": "RsPathMixin",
    "MethodCall": "RsMethodCallMixin",
    "FieldLookup": "RsFieldLookupMixin",
    "StructLiteralField": "RsStructLiteralFieldMixin",
    "PatBinding": "RsPatBindingMixin",
    "ModDeclItem": "RsModDeclItemMixin",
}
STRIP = re.compile(r"^\s*(mixin|stubClass|elementTypeFactory)\s*=.*$|^\s*implements\s*=\s*\"[^\"]*\"|^\s*extends\s*=\s*\"org\.rust[^\"]*\"\s*$")


def strip_attrs(text: str) -> str:
    out, skipping = [], False
    for line in text.splitlines():
        if skipping:
            if "]" in line:
                skipping = False
            continue
        if re.match(r"^\s*implements\s*=\s*\[", line):
            skipping = "]" not in line
            continue
        if STRIP.match(line):
            continue
        out.append(line)
    return "\n".join(out) + "\n"


# Syntax added to Rust after intellij-rust was archived (Jan 2024), used by today's std.
SYNTAX_UPDATES = [
    # `const trait Foo`
    ("upper TraitItem ::= unsafe? auto? trait", "upper TraitItem ::= const? unsafe? const? auto? trait"),
    # `const impl<T> Foo<T>`
    ("upper ImplItem ::= unsafe? impl TypeParameterList? const?", "upper ImplItem ::= const? unsafe? const? impl TypeParameterList? const?"),
    # `T: [const] Trait` (replaces `~const`)
    ("TildeConst ::= '~' const {\n  pin = 1\n}", "TildeConst ::= '~' const | '[' const ']' {\n}"),
    # `safe fn` in `unsafe extern` blocks, `final fn` in traits
    ("upper Function ::= async? const? unsafe? ExternAbi?", "upper Function ::= \"final\"? async? const? unsafe? \"safe\"? ExternAbi?"),
    # `pub impl(crate) trait Foo` (impl restrictions); an `impl(` item is never an impl block
    ("upper TraitItem ::= const? unsafe? const? auto? trait",
     "upper TraitItem ::= TraitImplRestriction? const? unsafe? const? auto? trait"),
    ("upper ImplItem ::= const? unsafe? const? impl TypeParameterList? const?",
     "upper ImplItem ::= const? unsafe? const? !(impl '(' (crate | self | super | in)) impl TypeParameterList? const?"),
    ("Members ::= '{' InnerAttr* Items '}' {",
     "private TraitImplRestriction ::= impl VisRestriction\n\nMembers ::= '{' InnerAttr* Items '}' {"),
    # `F: const FnOnce`, `for<'a> [const] Fn(…)`
    ("TildeConst ::= '~' const | '[' const ']' {", "TildeConst ::= '~' const | '[' const ']' | const {"),
    ("private PolyboundInner ::= [ ForLifetimes | '?' | TildeConst ] Bound",
     "private PolyboundInner ::= ForLifetimes? [ '?' | TildeConst ] Bound"),
    # precise capturing: `impl Trait + use<'a, T>`
    ("Bound ::= Lifetime | TraitRef {", "Bound ::= Lifetime | TraitRef | UseBound {"),
]
EXTRA_RULES = """
// Lite: precise capturing bound `use<'a, T>`
private UseBound ::= use '<' [ <<comma_separated_list UseBoundArg>> ] '>' { pin = 2 }
private UseBoundArg ::= Lifetime | identifier | 'Self'
"""


def convert_bnf(src: str) -> str:
    t = strip_attrs(src)
    for old, new in SYNTAX_UPDATES:
        if old not in t:
            raise SystemExit(f"grammar update anchor not found: {old!r}")
        t = t.replace(old, new)
    t = t.replace("org.rust.lang.core.parser", f"{PKG}.parser")
    t = t.replace("org.rust.lang.core.psi.ext.RsElementImpl", f"{PKG}.psi.ext.RsElementImpl")
    t = t.replace("org.rust.lang.core.psi.ext.RsElement'", f"{PKG}.psi.ext.RsElement'")
    t = t.replace("org.rust.lang.core.psi", f"{PKG}.psi")
    t += EXTRA_RULES
    rules = set(re.findall(r"^(?:\w+ )*(\w+)\s*::=", t, re.M))
    named = [r for r in NAMED if r in rules]
    missing = sorted(set(NAMED) - rules)
    if missing:
        print("warning: unknown NAMED rules", missing, file=sys.stderr)
    extra = [
        f'    implements("{"|".join(named)}")=["{PKG}.psi.ext.RsElement" "{PKG}.psi.ext.RsNamedElement"]',
        f'    mixin("{"|".join(n for n in named if n not in MIXINS)}")="{PKG}.psi.ext.RsNamedElementImpl"',
    ]
    for rule, mixin in MIXINS.items():
        extra.append(f'    mixin("{rule}")="{PKG}.psi.ext.{mixin}"')
    t = t.replace("    generateTokenAccessors=true", "    generateTokenAccessors=true\n" + "\n".join(extra), 1)
    return HEADER + t


def convert_flex(src: str) -> str:
    t = src.replace("org.rust.lang.core", PKG)
    return HEADER + t


def replace_fun(text: str, name: str, body: str) -> str:
    """Replace `fun name(...): Boolean = ...` or `fun name(...): Boolean { ... }` by `body`."""
    m = re.search(r"\n(\s*)@JvmStatic\n\s*fun " + name + r"\(", text)
    if not m:
        raise SystemExit(f"function {name} not found")
    start = m.start()
    # Expression body ends at the next blank line; block body at the matching brace.
    rest = text[m.end():]
    sig_end = rest.index(")") + 1
    after = rest[sig_end:]
    if after.lstrip().startswith(": Boolean {") or after.lstrip().startswith("{"):
        depth, i = 0, after.index("{")
        while True:
            if after[i] == "{": depth += 1
            elif after[i] == "}":
                depth -= 1
                if depth == 0: break
            i += 1
        end = m.end() + sig_end + i + 1
    else:
        end = m.end() + sig_end + after.index("\n\n")
    return text[:start] + "\n" + body + text[end:]


def convert_parser_util(src: str) -> str:
    t = src.replace("package org.rust.lang.core.parser", f"package {PKG}.parser")
    t = t.replace("import org.rust.RsBundle\n", "")
    t = t.replace("import org.rust.stdext.makeBitMask\n", "")
    t = t.replace("import org.rust.lang.core.parser.RustParserDefinition.Companion.",
                  f"import {PKG}.parser.RustParserDefinition.Companion.")
    t = t.replace("import org.rust.lang.core.psi.", f"import {PKG}.psi.")
    t = re.sub(r'RsBundle\.message\("([^"]+)"(?:,\s*([^)]+))?\)',
               lambda m: f'parseError("{m.group(1)}"' + (f", {m.group(2)})" if m.group(2) else ")"), t)
    # No lazy (chameleon) parsing: blocks and token trees are parsed eagerly.
    t = replace_fun(t, "parseMacroArgumentLazy",
                    "    @JvmStatic\n    fun parseMacroArgumentLazy(builder: PsiBuilder, level: Int): Boolean =\n"
                    "        RustParser.MacroArgument(builder, level)\n")
    t = replace_fun(t, "parseMacroBodyLazy",
                    "    @JvmStatic\n    fun parseMacroBodyLazy(builder: PsiBuilder, level: Int): Boolean =\n"
                    "        RustParser.MacroBody(builder, level)\n")
    t = replace_fun(t, "parseCodeBlockLazy",
                    "    @JvmStatic\n    fun parseCodeBlockLazy(builder: PsiBuilder, level: Int): Boolean =\n"
                    "        RustParser.InnerAttrsAndBlock(builder, level)\n")
    t += '''
private fun makeBitMask(bitToSet: Int): Int = 1 shl bitToSet

private fun PsiBuilder.Marker.close(result: Boolean): Boolean {
    if (result) drop() else rollbackTo()
    return result
}

private fun PsiBuilder.rawLookupText(steps: Int): CharSequence {
    val start = rawTokenTypeStart(steps)
    val end = rawTokenTypeStart(steps + 1)
    return if (start == -1 || end == -1) "" else originalText.subSequence(start, end)
}

private fun parseError(key: String, vararg args: String): String = when (key) {
    "parsing.error.expected" -> "'${args.getOrElse(0) { "" }}' expected"
    "parsing.error.expected2" -> "'${args.getOrElse(0) { "" }}' expected"
    else -> key.removePrefix("parsing.error.").replace('.', ' ') + args.joinToString(" ", prefix = " ")
}
'''
    return "// Derived from intellij-rust (MIT, see THIRD_PARTY_LICENSE-intellij-rust.txt).\n" + t


def main():
    upstream = Path(sys.argv[1])
    g = upstream / "src/main/grammars"
    (ROOT / "rust/src/main/grammars").mkdir(parents=True, exist_ok=True)
    (ROOT / "rust/src/main/grammars/RustParser.bnf").write_text(convert_bnf((g / "RustParser.bnf").read_text()))
    (ROOT / "rust/src/main/grammars/RustLexer.flex").write_text(convert_flex((g / "RustLexer.flex").read_text()))
    util = upstream / "src/main/kotlin/org/rust/lang/core/parser/RustParserUtil.kt"
    out = ROOT / f"rust/src/main/kotlin/{PKG.replace('.', '/')}/parser/RustParserUtil.kt"
    out.write_text(convert_parser_util(util.read_text()))
    print("ok")


if __name__ == "__main__":
    main()
