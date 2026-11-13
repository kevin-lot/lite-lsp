# Lite

Minimal native language support for IntelliJ IDEA 2026.3+ (build 262+). No LSP: everything runs inside
the IDE, with only `cargo metadata` / `rustc --print sysroot` (Rust) and `python3 -c sysconfig` (Python) run once
per project. Kotlin and TypeScript/JavaScript read their build files directly.

## Why

Code is written by an agent (Claude Code); the IDE is where it is **read and checked**. So the plugin only does what
reading needs: highlighting, Ctrl/Cmd+Click (with enough type inference to follow `a.b().c`), Find Usages and rename.
Everything that helps *typing* code (completion, auto-import, optimize imports, inspections, formatting) is left out on
purpose: an agent writes imports itself, and the compiler/linter reports the rest. Less code runs while the editor is
open, and there is less to maintain.

## Features

| Feature | Rust | Kotlin | Python | TypeScript / JavaScript | QML | Shell (sh, bash, zsh) | Markdown |
|---|---|---|---|---|---|---|---|
| Syntax highlighting (lexer + structural annotator) | yes | yes | yes | yes (JSX/TSX included) | yes (JavaScript parts too) | yes (heredocs, `$( )`, `${ }`, `$(( ))`) | yes (code blocks colored by the Lite language of their fence: ` ```rust `, ` ```bash `…) |
| Ctrl/Cmd+Click: locals, parameters, items, modules/packages, imports (groups, aliases, globs/star) | yes | yes | yes (LEGB, `import`/`from`/relative/`*`) | yes (ES imports/re-exports, `export *`, `require`, `module.exports`, module paths) | yes (`id`s, properties, signals, `function`s, enums, inline components, `Foo.qml` types, `import "dir"`, Qt modules, `qs.*` of Quickshell, `import "x.js" as X`) | yes (functions, `$VAR`/`${VAR}`/`$(( VAR ))` → assignment, `local`, `read`, `for`, later assignments → first; `source`/`.` files and what they define) | yes (links to files and folders, `#anchors` (same or other document, `-1` duplicates, encoded), `[ref]`/`[text][ref]` → `[ref]: …`, images, `~/` and `/` paths) |
| Ctrl/Cmd+Click on members with type inference (generics, supertypes, extensions/traits, `Deref`/scope functions) | yes | yes | yes (annotations, `self.x`, bases, `Optional`/`list[...]`) | yes (annotations, generics, inferred returns, callbacks `xs.map(x => x.id)`, `await`, unions, mapped types, `this.x =` in JS) | yes (`root.x`, `anchors.fill`, `Layout.fillWidth`, `onXChanged` → `x`, `Component.onCompleted`, `Math.max`, `Text.AlignLeft`) | n/a | n/a |
| Navigation into dependencies | std (rust-src) + Cargo crates (sources) | JDK + jars of the Gradle cache (decompiled) + Java sources | project (`.`/`src/`) + venv `site-packages` + stdlib (sources) | declared packages of `node_modules` (+ `@types`) + TypeScript `lib.*.d.ts` |Qt type descriptions (`.qmltypes`, `qmldir`) and QML files of the Qt import paths | sourced scripts (relative, `$(dirname "$0")/…`, `~/…`) | n/a |
| Rename (in place for locals) | yes | yes | yes | yes | yes (handlers follow: `onCountChanged` → `onTotalChanged`) | yes | yes (headers: their anchors follow; renamed files: links follow; link labels) |
| Find Usages, honouring the scope picked in the dialog (locals/private: searched in their container only) | yes | yes (+ Kotlin `p.name` usages of Java `getName()`) | yes | yes | yes (handlers included) | yes | yes (headers: by anchor) |
| Completion, import management, inspections, formatting, run/debug, build tool windows | no (by design) | no (by design) | no (by design) | no (by design) |no (by design) | no (by design) | no (by design) |

### Configuration and data formats (`formats` module)

Coloring for all of them; Ctrl/Cmd+Click, rename and Find Usages only where names point to each other.

| Format | Files | Ctrl/Cmd+Click |
|---|---|---|
| TOML | `.toml`, `Cargo.lock`, `Pipfile` | — |
| YAML | `.yaml`, `.yml` | `*alias` → `&anchor` |
| KDL | `.kdl` | — |
| INI and relatives | `.ini`, `.cfg`, `.conf`, `.properties`, `.editorconfig`, `.gitconfig`, systemd units, `.desktop` | — |
| Dotenv | `.env`, `.env.*` | `${VAR}` / `$VAR` → `VAR=` |
| JSON5 | `.json5` | — |
| HOCON | `.hocon`, `application.conf`, `reference.conf` | `${a.b.c}` → the key (nested blocks), `include "x.conf"` → file |
| Dockerfile | `Dockerfile`, `Containerfile`, `*.dockerfile` | `$VAR` → `ARG`/`ENV`, `COPY --from=build` / `FROM build` → `FROM … AS build` |
| SQL | `.sql` | — (would need the database schema) |
| CSS, SCSS, Sass | `.css`, `.scss`, `.sass` (indented syntax) | `var(--x)` → `--x:` (any stylesheet of the project), `$x`, `@include`/`+mixin` → `@mixin`/`=mixin`, `@import`/`@use`/`url()` → file (partials `_x.scss`/`_x.sass`) |
| Makefile | `Makefile`, `GNUmakefile`, `.mk` | `$(VAR)` → assignment, prerequisites → their rule, `include` → file |
| HCL: Terraform, Terragrunt | `.tf`, `.tfvars`, `.hcl` | `var.`, `local.`, `module.`, `data.t.n`, `t.n` (resources), `dependency.` → their block, across the folder's `.tf` files; `.tfvars` keys → `variable`; local `source`/`config_path` → folder |
| CSV, TSV | `.csv`, `.tsv` | — (one color per column, delimiter guessed) |
| Ignore files | `.gitignore`, `.dockerignore`, `.npmignore`, `.helmignore`, `.prettierignore`, `.gitattributes`… | literal patterns (`/build/`, `config.local.json`) → the file or folder |

### Protocol Buffers (`protobuf` module)

`.proto` (proto2, proto3, editions), parsed with the grammar of JetBrains' protoeditor (structural highlighting:
messages, enums, fields, services, options). Ctrl/Cmd+Click, rename and Find Usages follow protoc's rules: names are
package-qualified (`acme.common.Id`, `common.Id` from a sibling package, absolute `.acme.common.Id`), searched from the
innermost scope outwards (nested `Outer.Inner`), across the files reached through `import` / `import public`. Covered:
field, `map<K, V>`, `rpc` request/response, `extend` and custom option `(acme.secret)` names, package segments;
`import "x.proto"` → file (folders up to the content root), Google's well-known types (`google/protobuf/*.proto`) bundled.

HTML stays with IntelliJ's own XML/HTML plugin; `<script>`, `<style>`, `style="…"` and `on…="…"` are colored and
navigable by Lite's JavaScript and CSS (when the official JavaScript/CSS plugins are disabled).

Plugin size: ~1.5 MB (official Rust plugin ~150 MB, official Kotlin plugin ~500 MB, official Python plugins ~325 MB,
official JavaScript/TypeScript plugin ~220 MB).

## Install

1. `./gradlew buildPlugin` → Settings → Plugins → ⚙ → *Install Plugin from Disk…* → `build/distributions/lite-0.12.0.zip`.
2. Disable the official Rust, Kotlin, Python, JavaScript, Shell Script and Markdown plugins, and the plugins of the
   formats above (TOML, YAML, INI, .env, HOCON, KDL, Dockerfile, Makefile, Terraform, Protocol Buffers, CSS, Sass, SQL, .ignore):
   they register the same file extensions.
   QML: JetBrains' own QML plugin (Marketplace, closed source, uses the qmlls language server) is not needed;
   if installed, disable it too.
   [`tools/ide_diet.py`](tools/ide_diet.py) does it, together with every other non-essential plugin.
3. Rust std navigation: `rustup component add rust-src`.

### Project boundaries (same rule for every language)

Name lookups never leave the file's project: nearest manifest (`Cargo.toml`; `settings.gradle(.kts)` then
`build.gradle(.kts)`; `pyproject.toml`/`setup.py`/`setup.cfg`/`requirements.txt`; a lock file, else `package.json`/
`tsconfig.json`), else the highest folder with `src/` or `.git` below the workspace root, else the workspace root.
Build outputs are ignored (`target/`; `bin/`, `build/`, `out/`; `build/`, `dist/`, `__pycache__/`…; `node_modules/`,
`dist/`, `.next/`…); libraries are always visible. Implemented once in `core`
(`ProjectLayout`, `LiteProjectScope`); each language only declares its manifests and output dirs.

### Where dependencies come from

- **Rust**: the opened file's own `Cargo.toml` (nearest one, never above the attached project) → `cargo metadata --locked`
  → only the crates that crate declares. Files outside the module tree borrow the crate of their directory.
- **Kotlin lookups** stay inside the file's own project: the folder of its `settings.gradle(.kts)`, else
  `build.gradle(.kts)`, else the highest folder with `src/` or `.git` below the workspace root. Build outputs
  holding copies of sources (`bin/`, `build/`, `out/`) are ignored; libraries and the JDK are always visible.
- **Kotlin**: the opened file's `build.gradle(.kts)` (+ `gradle/libs.versions.toml`) → declared coordinates,
  `kotlin("x")`, and their POM dependencies → jars **already in** `~/.gradle/caches` (nothing is downloaded).
  Without a build script (or a declared Kotlin version) the newest cached `kotlin-stdlib` is used.
  The Kotlin stdlib is read from its compiled facade classes (`CollectionsKt`, `StandardKt__StandardKt`…).
- **TypeScript/JavaScript**: the file's `package.json` (and its workspaces) → the declared packages found in **its own**
  `node_modules` (never a parent folder's), their dependencies, every `@types` package, and `typescript/lib` (else the
  one of the global Node installation). Nothing is installed: run `npm install` once. `node_modules` itself is excluded
  from indexing; only those packages are indexed. Imports follow Node/TypeScript resolution: `exports`/`types`/`main`,
  `@types/x`, `tsconfig.json` `paths`/`baseUrl`, `./a.js` → `a.ts`, `index.*`, `declare module "x"`.

## Known limits

- Rust: no macro expansion (`#[derive]` is handled through the trait), `#[cfg]` not evaluated (a chooser shows
  every candidate), closures have unknown types.
- TypeScript/JavaScript: no full type checker (no overload resolution beyond argument count, no narrowing, no
  conditional-type evaluation: both branches are tried); untyped JavaScript is typed from initializers, `new`, returns
  and `this.x = …` only. ES5 `X.prototype.m = function` members are not followed. `<script>` blocks in HTML are not
  highlighted (that came from the official JavaScript plugin).
- QML: types come from the documents, `qmldir` and Qt's `.qmltypes`; `var` properties and JavaScript values set
  from C++ are untyped. `qs.*` needs the Quickshell config (the folder of `shell.qml`) in the project.
- Shell: variables are resolved statically (first assignment of the script, `local` of the enclosing function, sourced
  scripts); environment variables and external commands are left unresolved. `((cmd)…)` (a subshell written without a
  space) is read as arithmetic, as bash itself first tries.
- Markdown: no rendered preview (by design: the source is what agents write); setext headers (`===` underlines)
  and indented code blocks are plain text.
- Python: built-ins written in C (`str`, `float`, `math`…) have no source to navigate to; types come from
  annotations, constructors and `self` only (no inference through untyped calls).
- Kotlin: no full overload resolution (filtered by argument count), no smart casts, generic inference only for
  declared types, compiled extensions (`toList`, `map`…) and common collection/scope functions (`it`, `this`).
  Libraries open as decompiled classes (no sources jars are downloaded). Kotlin built-ins (`Int`, `String`, `List`…)
  navigate to their Java counterparts.

## Code layout (modular)

```
core/     language-agnostic: library-aware cache, reference base, keys index, rename/find-usages bases, brace/commenter helpers
rust/     Rust: grammar (Grammar-Kit, from intellij-rust), PSI, Cargo, module tree, resolve, types
python/   Python: PSI, environment (project root, venv, stdlib), resolve + annotation types
typescript/ TypeScript + JavaScript (one language): hand-written lexer/parser (JSX), PSI, node_modules/tsconfig
          environment, module resolution, resolve + types; hooks for languages embedding JavaScript
formats/  Configuration and data formats: a rule-based lexer and a generic declaration/reference linker, one short
          declaration per format; HTML `<script>`/`<style>` injection (optional, with the XML/HTML plugin)
markdown/ Markdown: line lexer, links/anchors/definitions, code blocks injected with the Lite language of their fence
protobuf/ Protocol Buffers: PSI over the imported grammar, protoc-style package/scope resolution, imports
          protobuf/src/upstream/java + src/main/grammars: lexer/grammar imported from protoeditor (do not edit)
shell/    Shell (sh, bash, zsh): context-tracking lexer (quotes, expansions, heredocs), command parser, resolve
qml/      QML (+ `.qmltypes`): object/binding parser extending the JavaScript one, Qt import paths, qmldir/qmltypes,
          resolve (types, bindings, attached properties, JavaScript names); depends on core and typescript
          python/src/upstream/java: lexer/parser imported from intellij-community (generated, do not edit)
kotlin/   Kotlin: PSI, Gradle classpath, resolve (+ Java bridge), types
          kotlin/src/upstream/java: parser imported from the Kotlin compiler (generated, do not edit)
src/      the plugin descriptor only: includes META-INF/lite-<language>.xml of each module
buildSrc/ `lite.module` convention (local IDE, JBR 25, platform test framework)
playground/ manual test project: one small project per language and format (see its README)
tools/    import scripts for the upstream grammars, plugin diet script
```

Each language module depends on `core` only (QML also on `typescript`, for the JavaScript it embeds); adding a
language = a new module + one `xi:include` line.

## Upstream code

- Rust lexer/grammar: [intellij-rust](https://github.com/intellij-rust/intellij-rust) (MIT, archived January 2024) via
  [`tools/import_intellij_rust.py`](tools/import_intellij_rust.py), which also adds the syntax used by today's std
  (`const trait`, `const impl`, `[const]` bounds, `impl(crate) trait`, `safe fn`, `final fn`, `use<..>`).
- Kotlin lexer/parser: the [Kotlin compiler](https://github.com/JetBrains/kotlin) (Apache 2.0) via
  [`tools/import_kotlin_parser.py`](tools/import_kotlin_parser.py): stubs and KDoc parsing removed, eager parsing.
- Python lexer/parser: [intellij-community](https://github.com/JetBrains/intellij-community) (Apache 2.0) via
  [`tools/import_python_parser.py`](tools/import_python_parser.py).
- Protocol Buffers lexer/grammar: [protoeditor](https://github.com/JetBrains/intellij-plugins/tree/master/protobuf)
  (Apache 2.0) via [`tools/import_protoeditor.py`](tools/import_protoeditor.py): their PSI dropped, aggregate option
  values `{ … }` skipped as a balanced block; Google's well-known types (BSD-3) copied as is.
- TypeScript/JavaScript: no upstream (JetBrains' JavaScript plugin is not open source); written for this plugin.

## Develop

```
./gradlew test                       # core and every language
./gradlew buildPlugin                # build/distributions/lite-<version>.zip
./gradlew runIde                     # sandbox IDE
```

Optional real-world inputs (read-only): `RUST_SRC_LIBRARY=<sysroot>/lib/rustlib/src/rust/library` (std resolution and
parsing), `LITE_KOTLIN_CORPUS=<dir>` (parse + highlight every `.kt`), `LITE_KOTLIN_PROJECT=<dir>` (resolution rate of a
whole project with its Gradle-cache jars; use `--no-configuration-cache --rerun`), `LITE_PYTHON_CORPUS`/`LITE_PYTHON_PROJECT`
(+ `LITE_PYTHON_STDLIB`), `LITE_TS_CORPUS=<dir>` (parse every `.ts`/`.js`), `LITE_TS_PROJECT=<dir>` (resolution rate,
`LITE_SHOW=1` lists the misses) with `LITE_TS_LIB=<typescript/lib>`, `LITE_QML_CORPUS=<dir:dir>`, `LITE_QML_PROJECT=<dir>` `LITE_SH_CORPUS=<dir:dir>`, `LITE_MD_CORPUS=<dir:dir>` , `LITE_FMT_CORPUS=<dir:dir>` and `LITE_PROTO_CORPUS=<dir>`.

`gradle.properties` → `localIdePath` points at the local IDE (nothing downloaded); Gradle runs on its JBR 25.
