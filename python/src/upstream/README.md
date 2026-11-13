# upstream/

Python lexer and parser imported from [intellij-community](https://github.com/JetBrains/intellij-community)
(`python/python-parser`, Apache 2.0, see `../../THIRD_PARTY_LICENSE-intellij-community.txt`), in the package
`me.kevinlot.lite.python.upstream` (our code never lives there). The lexer is the generated `_PythonLexer.java`
shipped upstream, so no JFlex step is needed.

**Do not edit by hand.** Regenerate with `tools/import_python_parser.py`, which renames packages and ids
(`LitePython`), the extension point and the message bundle, and disables incremental leaf reparse.
Element types are provided by `src/main/kotlin/.../parser/LitePyElementTypes.kt` (plain types, no stubs).
