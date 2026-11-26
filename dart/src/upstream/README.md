# Upstream code (generated, do not edit)

`java/` and `../main/grammars/Dart.{bnf,flex}` come from the official Dart plugin,
[flutter/dart-intellij-third-party](https://github.com/flutter/dart-intellij-third-party) (`third_party`, Apache License 2.0,
see `../../THIRD_PARTY_LICENSE-dart-intellij.txt`), imported by [`tools/import_dart.py`](../../../tools/import_dart.py):

- `Dart.bnf` (grammar rules) and `Dart.flex` (lexer), in Lite's packages;
- `DartLexer` (comments and string parts merged), `DartTokenTypesSets`, `DartGeneratedParserUtilBase`, `DartElementType`.

Patched by the script (`PATCHES`), for forms the grammar rejects: an index at the start of a cascade section
(`l..[0].x`), a record as the result of a function type (`(A, B) Function()`), a record type after `required`
(`{required (A, B) pair}`), and alternatives with a guard (`C.a || C.b when c`).

Not imported: their PSI (mixins, methods, interfaces), resolution, analysis server client and documentation-comment lexer.
Lite's PSI and resolution are in `src/main/kotlin`. Re-run the script to update; edit the script, not these files.
