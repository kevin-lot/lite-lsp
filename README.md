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

## Supported languages and formats

Every entry is highlighted. **Click** = Ctrl/Cmd+Click; **Rename / Usages** = rename and Find Usages (which honours the
scope picked in the dialog; locals are searched in their container only). Completion, import management, inspections,
formatting, run/debug and build tool windows: none, by design.

| | Language or format | Click | Rename / Usages | Replaces |
|---|---|---|---|---|
| **Programming** | [Rust](#rust) | with types | yes | Rust (RustRover plugin) |
| | [Kotlin](#kotlin) | with types | yes | Kotlin |
| | [Python](#python) | with types | yes | Python, Python Community |
| | [TypeScript, JavaScript, JSX/TSX](#typescript-and-javascript) | with types | yes | JavaScript and TypeScript |
| | [QML](#qml) | with types | yes | QML (Marketplace) |
| | [Lua](#lua) | with tables | yes | EmmyLua, Luanalysis (third-party) |
| | [Shell: sh, bash, zsh](#shell) | yes | yes | Shell Script |
| **Markup** | [Markdown](#markdown) | links, anchors | yes | Markdown |
| | [HTML `<script>` / `<style>`](#html) | as JavaScript / CSS | yes | JavaScript and CSS inside HTML |
| **Interfaces** | [Protocol Buffers](#protocol-buffers) | with packages | yes | Protocol Buffers |
| | [OpenAPI v1, v2, v3 (YAML, JSON)](#openapi-asyncapi-json-schema) | `$ref` | yes | OpenAPI Specifications |
| **Build and tasks** | [Makefile](#makefile) | yes | yes | Makefile Language |
| | [just](#just) | yes | yes | Just (third-party) |
| | [Dockerfile](#configuration-and-data-formats) | yes | yes | Docker (language part) |
| | [Terraform, Terragrunt (HCL)](#configuration-and-data-formats) | yes | yes | Terraform and HCL |
| **Configuration** | [YAML](#configuration-and-data-formats) | anchors, `$ref` | yes | YAML |
| | [HOCON](#configuration-and-data-formats) | yes | yes | HOCON |
| | [Dotenv](#configuration-and-data-formats) | yes | yes | .env files support |
| | [TOML, INI/.properties, KDL, JSON5](#configuration-and-data-formats) | — | — | Toml, Ini, Properties, KDL |
| | [Ignore files](#configuration-and-data-formats) | paths | — | .ignore |
| **Styles** | [CSS, SCSS, Sass](#configuration-and-data-formats) | yes | yes | CSS, Sass |
| **Data** | [SQL, CSV/TSV](#configuration-and-data-formats) | — | — | Database Tools and SQL (language part) |

Plugin size: ~2 MB (official Rust plugin ~150 MB, official Kotlin plugin ~500 MB, official Python plugins ~325 MB,
official JavaScript/TypeScript plugin ~220 MB).

Try every entry in [`playground/`](playground/README.md).

## Programming languages

### Rust

- **Click**: locals, parameters, items, modules, `use` (groups, aliases, globs); members with type inference
  (generics, traits, `Deref`, `#[derive]` through the trait).
- **Dependencies**: std (`rustup component add rust-src`) and Cargo crates, as sources. The opened file's own
  `Cargo.toml` (nearest one, never above the attached project) → `cargo metadata --locked` → only the crates that crate
  declares. Files outside the module tree borrow the crate of their directory.
- **Limits**: no macro expansion, `#[cfg]` not evaluated (a chooser shows every candidate), closures have unknown types.

### Kotlin

- **Click**: locals, parameters, declarations, packages, imports (aliases, star); members with type inference
  (generics, supertypes, extensions, scope functions `it`/`this`); Kotlin `p.name` finds Java `getName()`.
- **Dependencies**: the JDK, Java sources, and the jars **already in** `~/.gradle/caches` (nothing is downloaded): the
  file's `build.gradle(.kts)` (+ `gradle/libs.versions.toml`) → declared coordinates, `kotlin("x")` and their POM
  dependencies. Without a build script (or a declared Kotlin version) the newest cached `kotlin-stdlib` is used, read
  from its compiled facade classes (`CollectionsKt`, `StandardKt__StandardKt`…).
- **Limits**: no full overload resolution (filtered by argument count), no smart casts, generic inference only for
  declared types, compiled extensions (`toList`, `map`…) and common collection/scope functions. Libraries open as
  decompiled classes (no sources jars are downloaded). Kotlin built-ins (`Int`, `String`, `List`…) navigate to their
  Java counterparts.

### Python

- **Click**: LEGB scopes, `import` / `from` / relative / `*`; members from annotations, `self.x`, bases,
  `Optional` / `list[...]`.
- **Dependencies**: the project (`.`, `src/`), the venv's `site-packages` and the stdlib, as sources.
- **Limits**: built-ins written in C (`str`, `float`, `math`…) have no source to navigate to; types come from
  annotations, constructors and `self` only (no inference through untyped calls).

### TypeScript and JavaScript

One language, JSX/TSX included.

- **Click**: ES imports and re-exports, `export *`, `require`, `module.exports`; members with types (annotations,
  generics, inferred returns, callbacks `xs.map(x => x.id)`, `await`, unions, mapped types, `this.x =` in JavaScript).
- **Dependencies**: the file's `package.json` (and its workspaces) → the declared packages found in **its own**
  `node_modules` (never a parent folder's), their dependencies, every `@types` package, and `typescript/lib` (else the
  global Node installation's). Nothing is installed: run `npm install` once. `node_modules` itself is excluded from
  indexing; only those packages are indexed. Imports follow Node/TypeScript resolution: `exports`/`types`/`main`,
  `@types/x`, `tsconfig.json` `paths`/`baseUrl`, `./a.js` → `a.ts`, `index.*`, `declare module "x"`.
- **Limits**: no full type checker (no overload resolution beyond argument count, no narrowing, no conditional-type
  evaluation: both branches are tried); untyped JavaScript is typed from initializers, `new`, returns and `this.x = …`
  only. ES5 `X.prototype.m = function` members are not followed.

### QML

- **Click**: `id`s, properties, signals, `function`s, enums, inline components, `Foo.qml` types, `import "dir"`,
  Qt modules, `import "x.js" as X`, `qs.*` of Quickshell; with types: `root.x`, `anchors.fill`, `Layout.fillWidth`,
  `onXChanged` → `x`, `Component.onCompleted`, `Math.max`, `Text.AlignLeft`. JavaScript parts use the TypeScript module.
- **Rename**: handlers follow (`onCountChanged` → `onTotalChanged`); Find Usages includes them.
- **Dependencies**: Qt type descriptions (`.qmltypes`, `qmldir`) and QML files of the Qt import paths.
- **Limits**: `var` properties and JavaScript values set from C++ are untyped. `qs.*` needs the Quickshell config (the
  folder of `shell.qml`) in the project. JetBrains' own QML plugin (Marketplace, closed source, uses the qmlls language
  server) is not needed.

### Lua

Lua 5.1 to 5.4 and LuaJIT (`.lua`, `.rockspec`, `.luacheckrc`).

- **Click**: lexical scopes (locals declared before the use, `local x = x`, `repeat … until`), parameters, loop
  variables, `local function` in its own body, `goto` labels, globals across files; `require("a.b")` → `a/b.lua` or
  `a/b/init.lua` under the project root, `lua/`, `src/`; `dofile` / `loadfile` → the file.
- **Table fields**: followed through locals, `require` + `return M`, `M.f = …` / `function M.f()` /
  `function M:f()`, `self` in methods, `setmetatable(t, M)` with `__index`, and function returns (`Dog.new()` →
  `d:bark()`). The first definition is the target.
- **Rename**: renamed module files update their `require` strings.
- **Limits**: the runtime library (`string`, `table`, `vim.*`…) has no source in the project and is left unresolved;
  fields added by other files, dynamic `__index` functions and untyped parameters are not followed.

### Shell

sh, bash and zsh.

- **Click**: functions, `$VAR` / `${VAR}` / `$(( VAR ))` → assignment, `local`, `read`, `for`, later assignments →
  the first; `source` / `.` files and what they define (relative, `$(dirname "$0")/…`, `~/…`).
- **Highlighting**: heredocs, `$( )`, `${ }`, `$(( ))`.
- **Limits**: variables are resolved statically (first assignment of the script, `local` of the enclosing function,
  sourced scripts); environment variables and external commands are left unresolved. `((cmd)…)` (a subshell written
  without a space) is read as arithmetic, as bash itself first tries.

## Markup

### Markdown

- **Highlighting**: code blocks are colored by the Lite language of their fence (` ```rust `, ` ```bash `, ` ```lua `…).
- **Click**: links to files and folders (relative, `~/`, `/`), images, `#anchors` (same or other document, `-1`
  duplicates, URL-encoded), `[ref]` / `[text][ref]` → `[ref]: …`.
- **Rename**: headers (their anchors follow), linked files (links follow), link labels. Find Usages of a header finds the
  links to its anchor.
- **Limits**: no rendered preview (by design: the source is what agents write); setext headers (`===` underlines) and
  indented code blocks are plain text.

### HTML

HTML itself stays with IntelliJ's XML/HTML plugin. `<script>`, `<style>`, `style="…"` and `on…="…"` are colored and
navigable by Lite's JavaScript and CSS, when the official JavaScript/CSS plugins are disabled.

## Interfaces

### Protocol Buffers

`.proto` (proto2, proto3, editions), parsed with the grammar of JetBrains' protoeditor (structural highlighting:
messages, enums, fields, services, options).

- **Click**: protoc's rules: names are package-qualified (`acme.common.Id`, `common.Id` from a sibling package, absolute
  `.acme.common.Id`), searched from the innermost scope outwards (nested `Outer.Inner`), across the files reached through
  `import` / `import public`. Covers fields, `map<K, V>`, `rpc` request/response, `extend` and custom option
  `(acme.secret)` names, package segments; `import "x.proto"` → file (folders up to the content root).
- **Dependencies**: Google's well-known types (`google/protobuf/*.proto`) are bundled.

### OpenAPI, AsyncAPI, JSON Schema

OpenAPI v1 (Swagger 1.2), v2 (Swagger 2.0) and v3.x, in YAML and JSON files: everything that uses `$ref`.

- **Click**: `$ref: '#/components/schemas/Pet'` → the key (JSON pointers, `~1` escapes: `#/paths/~1pets/get`);
  `common.yaml#/Error`, `point.json#/Point` → the key in that file; `./pet.yaml` → the file; discriminator `mapping`
  values, `operationRef`; `$ref: Pet` (Swagger 2.0 shorthand); Swagger 1.2 `"$ref": "Pet"` / `"type": "Pet"` →
  `models.Pet`.
- **Rename / Usages**: renaming a key updates the `$ref`s of every file, YAML and JSON.
- JSON files stay with IntelliJ's JSON plugin; Lite adds the `$ref` navigation to them.

## Build and tasks

### Makefile

`Makefile`, `GNUmakefile`, `.mk`.

- **Click**: `$(VAR)`, `${VAR}`, `$(VAR:.c=.o)`, `$(call fn,…)`, `$(value VAR)`, `ifdef VAR`, `export VAR` →
  assignment or `define`; prerequisites and `.PHONY` names → their rule, else the source file (`main.o: src/main.c`);
  `include` → file.
- Names are shared with the included makefiles and with the makefiles that include this one. `$$VAR` (shell) is left
  alone.

### just

`justfile`, `.justfile`, `*.just`.

- **Click**: dependencies (`build`, `(build "x")`, after `&&`) and `alias` → recipe; `{{name}}`, `$name`, parameter
  defaults and dependency arguments → the recipe's parameter, else the variable; `import 'x.just'` → file (its names are
  shared); `mod name` → `name.just` / `name/mod.just`.

## Configuration and data formats

One short declaration each (`formats` module). Click, rename and Find Usages only where names point to each other.

| Format | Files | Click |
|---|---|---|
| YAML | `.yaml`, `.yml` | `*alias` → `&anchor`; [`$ref`](#openapi-asyncapi-json-schema) |
| HOCON | `.hocon`, `application.conf`, `reference.conf` | `${a.b.c}` → the key (nested blocks), `include "x.conf"` → file |
| Dotenv | `.env`, `.env.*` | `${VAR}` / `$VAR` → `VAR=` |
| Dockerfile | `Dockerfile`, `Containerfile`, `*.dockerfile` | `$VAR` → `ARG`/`ENV`, `COPY --from=build` / `FROM build` → `FROM … AS build` |
| HCL: Terraform, Terragrunt | `.tf`, `.tfvars`, `.hcl` | `var.`, `local.`, `module.`, `data.t.n`, `t.n` (resources), `dependency.` → their block, across the folder's `.tf` files; `.tfvars` keys → `variable`; local `source`/`config_path` → folder |
| CSS, SCSS, Sass | `.css`, `.scss`, `.sass` (indented syntax) | `var(--x)` → `--x:` (any stylesheet of the project), `$x`, `@include`/`+mixin` → `@mixin`/`=mixin`, `@import`/`@use`/`url()` → file (partials `_x.scss`/`_x.sass`) |
| Ignore files | `.gitignore`, `.dockerignore`, `.npmignore`, `.helmignore`, `.prettierignore`, `.gitattributes`… | literal patterns (`/build/`, `config.local.json`) → the file or folder |
| TOML | `.toml`, `Cargo.lock`, `Pipfile` | — |
| INI and relatives | `.ini`, `.cfg`, `.conf`, `.properties`, `.editorconfig`, `.gitconfig`, systemd units, `.desktop` | — |
| KDL | `.kdl` | — |
| JSON5 | `.json5` | — |
| SQL | `.sql` | — (would need the database schema) |
| CSV, TSV | `.csv`, `.tsv` | — (one color per column, delimiter guessed) |

## Install

1. `./gradlew buildPlugin` → Settings → Plugins → ⚙ → *Install Plugin from Disk…* → `build/distributions/lite-0.13.0.zip`.
2. Disable the plugins in the *Replaces* column of [the table above](#supported-languages-and-formats): they register
   the same file extensions. [`tools/ide_diet.py`](tools/ide_diet.py) does it, together with every other non-essential
   plugin.
3. Rust std navigation: `rustup component add rust-src`.

### Project boundaries (same rule for every language)

Name lookups never leave the file's project: nearest manifest (`Cargo.toml`; `settings.gradle(.kts)` then
`build.gradle(.kts)`; `pyproject.toml`/`setup.py`/`setup.cfg`/`requirements.txt`; a lock file, else `package.json`/
`tsconfig.json`), else the highest folder with `src/` or `.git` below the workspace root, else the workspace root.
Build outputs are ignored (`target/`; `bin/`, `build/`, `out/`; `build/`, `dist/`, `__pycache__/`…; `node_modules/`,
`dist/`, `.next/`…); libraries are always visible. Implemented once in `core`
(`ProjectLayout`, `LiteProjectScope`); each language only declares its manifests and output dirs.

## Code layout (modular)

```
core/       language-agnostic: library-aware cache, reference base, keys index, rename/find-usages bases, brace/commenter helpers
rust/       Rust: grammar (Grammar-Kit, from intellij-rust), PSI, Cargo, module tree, resolve, types
kotlin/     Kotlin: PSI, Gradle classpath, resolve (+ Java bridge), types
            kotlin/src/upstream/java: parser imported from the Kotlin compiler (generated, do not edit)
python/     Python: PSI, environment (project root, venv, stdlib), resolve + annotation types
            python/src/upstream/java: lexer/parser imported from intellij-community (generated, do not edit)
typescript/ TypeScript + JavaScript (one language): hand-written lexer/parser (JSX), PSI, node_modules/tsconfig
            environment, module resolution, resolve + types; hooks for languages embedding JavaScript
qml/        QML (+ `.qmltypes`): object/binding parser extending the JavaScript one, Qt import paths, qmldir/qmltypes,
            resolve (types, bindings, attached properties, JavaScript names); depends on core and typescript
lua/        Lua: lexer, recursive-descent parser, scopes, `require` and table-field resolution
shell/      Shell (sh, bash, zsh): context-tracking lexer (quotes, expansions, heredocs), command parser, resolve
markdown/   Markdown: line lexer, links/anchors/definitions, code blocks injected with the Lite language of their fence
protobuf/   Protocol Buffers: PSI over the imported grammar, protoc-style package/scope resolution, imports
            protobuf/src/upstream/java + src/main/grammars: lexer/grammar imported from protoeditor (do not edit)
formats/    configuration and data formats: a rule-based lexer and a generic declaration/reference linker, one short
            declaration per format (also Makefile, just); `$ref` JSON pointers (YAML here, JSON with the JSON plugin);
            HTML `<script>`/`<style>` injection (with the XML/HTML plugin)
src/        the plugin descriptor only: includes META-INF/lite-<module>.xml of each module
buildSrc/   `lite.module` convention (local IDE, JBR 25, platform test framework)
playground/ manual test project: one small project per language and format (see its README)
tools/      import scripts for the upstream grammars, plugin diet script
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
- Everything else (TypeScript/JavaScript, QML, Lua, Shell, Markdown, the formats) is written for this plugin.

## Develop

```
./gradlew test                       # core and every language
./gradlew buildPlugin                # build/distributions/lite-<version>.zip
./gradlew runIde                     # sandbox IDE
```

`gradle.properties` → `localIdePath` points at the local IDE (nothing downloaded); Gradle runs on its JBR 25.

Optional real-world inputs (read-only; run with `--rerun --no-configuration-cache`, `LITE_SHOW=1` lists the misses):

| Variable | Module | Measures |
|---|---|---|
| `RUST_SRC_LIBRARY=<sysroot>/lib/rustlib/src/rust/library` | rust | std resolution and parsing |
| `LITE_KOTLIN_CORPUS=<dir>`, `LITE_KOTLIN_PROJECT=<dir>` | kotlin | parse + highlight every `.kt`; resolution rate with the Gradle-cache jars |
| `LITE_PYTHON_CORPUS`, `LITE_PYTHON_PROJECT`, `LITE_PYTHON_STDLIB` | python | parsing; resolution rate |
| `LITE_TS_CORPUS=<dir>`, `LITE_TS_PROJECT=<dir>`, `LITE_TS_LIB=<typescript/lib>` | typescript | parse every `.ts`/`.js`; resolution rate |
| `LITE_QML_CORPUS=<dir:dir>`, `LITE_QML_PROJECT=<dir>` | qml | parsing; resolution rate |
| `LITE_LUA_CORPUS=<dir:dir>` | lua | parsing; names and fields resolved |
| `LITE_SH_CORPUS=<dir:dir>` | shell | parsing |
| `LITE_MD_CORPUS=<dir:dir>` | markdown | links resolved |
| `LITE_PROTO_CORPUS=<dir>` | protobuf | parsing; names resolved |
| `LITE_FMT_CORPUS=<dir:dir>` | formats | lexing; links resolved (YAML `$ref` included) |
