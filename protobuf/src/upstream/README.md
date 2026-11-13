# Upstream code (generated, do not edit)

`java/` and `../main/grammars/Proto.{bnf,flex}` come from [JetBrains/intellij-plugins](https://github.com/JetBrains/intellij-plugins/tree/master/protobuf)
(`protobuf/protoeditor-core`, Apache License 2.0, Copyright Google LLC), imported by
[`tools/import_protoeditor.py`](../../../tools/import_protoeditor.py):

- `proto.bnf` (grammar rules) and `proto.flex` (lexer), in Lite's packages;
- `ProtoTokenTypes`, `PbParserUtil` (keywords usable as identifiers, block-aware error recovery).

Not imported: their PSI (mixins, stubs, interfaces), their resolution and the text-format parser of aggregate option
values (`option (x) = { … }` is skipped as a balanced block). Lite's PSI and resolution are in `src/main/kotlin`.
Re-run the script to update; edit the script, not these files.
