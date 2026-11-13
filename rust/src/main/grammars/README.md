# grammars/

Lexer (`RustLexer.flex`, JFlex) and grammar (`RustParser.bnf`, Grammar-Kit) imported from
[intellij-rust](https://github.com/intellij-rust/intellij-rust) (MIT, see `../../../THIRD_PARTY_LICENSE-intellij-rust.txt`).
The Java lexer and parser are generated from them at build time into `build/generated/`.

**Do not edit by hand.** They are produced by `tools/import_intellij_rust.py` (which also adds the Rust syntax
newer than upstream); `src/main/kotlin/.../parser/RustParserUtil.kt` comes from the same script.
