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
| | [Angular templates](#angular-templates) (`*.component.html`, inline `template:`) | component members, with types | yes | Angular and AngularJS |
| | [QML](#qml) | with types | yes | QML (Marketplace) |
| | [Groovy](#groovy) (Gradle scripts, Jenkinsfile) | lexical, declared types | yes | Groovy |
| | [Scala](#scala) (Gatling simulations, sbt) | lexical, written types | yes | Scala |
| | [Dart](#dart) (Flutter), `pubspec.yaml` | with types | yes | Dart |
| | [Lua](#lua) | with tables | yes | EmmyLua, Luanalysis (third-party) |
| | [Shell: sh, bash, zsh](#shell) | yes | yes | Shell Script |
| | [fish](#fish) | yes | yes | — |
| | [Rego (Open Policy Agent)](#rego) | rules, variables, packages | yes | Open Policy Agent |
| **Markup** | [Markdown](#markdown), with a preview | links, anchors | yes | Markdown |
| | [HTML `<script>` / `<style>`](#html) | as JavaScript / CSS | yes | JavaScript and CSS inside HTML |
| | [Handlebars](#handlebars-and-mustache) | partials, block parameters | yes | Handlebars/Mustache |
| | [Jinja2](#jinja2) | variables, macros, includes | yes | — |
| | [Mustache](#handlebars-and-mustache) | partials | yes | Handlebars/Mustache |
| **Diagrams** | [D2](#d2) | shapes, classes, variables, imports | yes | — |
| | [Mermaid](#mermaid) | participants, states, nodes, classes | yes | Mermaid |
| **Interfaces** | [Protocol Buffers](#protocol-buffers) | with packages | yes | Protocol Buffers |
| | [Bruno](#bruno) | `{{variables}}` | yes | — |
| | [OpenAPI v1, v2, v3 (YAML, JSON)](#openapi-asyncapi-json-schema) | `$ref` | yes | OpenAPI Specifications |
| **Specifications** | [Gherkin](#gherkin) | steps → definitions, `<placeholders>` | columns | Gherkin, Cucumber plugins |
| | [JFlex](#jflex) | macros, states | yes | Grammar-Kit (its JFlex part) |
| **Build and tasks** | [Makefile](#makefile) | yes | yes | Makefile Language |
| | [just](#just) | yes | yes | Just (third-party) |
| | [Dockerfile](#configuration-and-data-formats) | yes | yes | Docker (language part) |
| | [Terraform, Terragrunt (HCL)](#configuration-and-data-formats) | yes | yes | Terraform and HCL |
| **Configuration** | [YAML](#configuration-and-data-formats) | anchors, `$ref`, [GitHub Actions](#github-actions), [Serverless and CloudFormation](#serverless-and-cloudformation) | yes | YAML, GitHub Actions, Serverless Framework, AWS CloudFormation |
| | [HOCON](#configuration-and-data-formats) | yes | yes | HOCON |
| | [Dotenv](#configuration-and-data-formats) | yes | yes | .env files support |
| | [INI](#configuration-and-data-formats) | — | — | Ini |
| | [JSON5](#configuration-and-data-formats) | — | — | — |
| | [ARB](#dart) (Flutter messages) | `@key`, Dart getters | yes | Flutter Intl (third-party) |
| | [KDL](#configuration-and-data-formats) | — | — | KDL |
| | [Properties](#configuration-and-data-formats) | yes | yes | Properties |
| | [TOML](#configuration-and-data-formats) | `Cargo.toml`, Gradle version catalogs | yes | Toml |
| | [CODEOWNERS](#configuration-and-data-formats) | paths | — | — |
| | [Ignore files](#configuration-and-data-formats) | paths | — | .ignore |
| **Styles** | [CSS](#configuration-and-data-formats) | yes | yes | CSS |
| | [Sass (indented syntax)](#configuration-and-data-formats) | yes | yes | Sass |
| | [SCSS](#configuration-and-data-formats) | yes | yes | Sass |
| **Data** | [CSV, TSV](#configuration-and-data-formats), with a table preview | — | — | — |
| | [SQL](#configuration-and-data-formats) | — | — | Database Tools and SQL (language part) |

Plugin size: ~2 MB (official Rust plugin ~150 MB, official Kotlin plugin ~500 MB, official Python plugins ~325 MB,
official JavaScript/TypeScript plugin ~220 MB).

Try every entry in [`playground/`](playground/README.md).

## Rainbow brackets

`()`, `[]` and `{}` are colored by nesting depth (five colors, cycling) in every language and format of the plugin.
Brackets inside strings and comments keep their color. Replaces the Rainbow Brackets plugin for these files; the
colors are in `core/src/main/resources/colorSchemes/` (no settings page).

## Terminal keys (macOS)

In the IDE's terminal, Cmd+Left / Cmd+Right move by word: they send `ESC b` / `ESC f`, as Option+Left / Right do,
instead of the hard-coded Ctrl+A / Ctrl+E. The shell and full-screen programs (Claude Code, vim, less) then agree, and
Ctrl+A / Ctrl+E keep their own meaning (start / end of line). Both terminal engines, only with the Terminal plugin
enabled: the classic one (JediTerm) through the IDE's event queue, the reworked one through its experimental key
listener API of 2026.3.

## Cron expressions

A language of its own (lexer and parser), injected into YAML and HCL values. Each field has its own color (minute, hour,
day of month, month, day of week, year), and a malformed expression is a syntax error, underlined like in any other
parsed language. Nothing to click, no description of the schedule.

- **Five fields** in the value of a `cron` key, or of a `schedule…` key when it reads as cron fields (crontab, GitHub
  Actions, Kubernetes): `*`, lists, ranges, `/step`, month and day names.
- **AWS** `cron(…)` as a whole value (EventBridge, Serverless, `schedule_expression`): six fields with the year, `?` in
  exactly one of the two day fields, `L`, `LW`, `15W`, `6#3`, `6L`. `rate(5 minutes)` too, with either spelling of the
  unit for 1.
- **Syntax errors**: the number of fields, a value out of range, an unknown name, `?` outside AWS, the AWS day rule.
- **Left alone**: a value holding a template (`${…}`, `{{…}}`), `cron()` inside a sentence, JSON files.

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
- **Inheritance**: Go to Implementation (Cmd/Ctrl+Alt+B, Cmd/Ctrl+Alt+Click) lists the Kotlin subclasses of a class or
  interface (Kotlin or Java) and the overrides of a member; Cmd/Ctrl+Click on an `override` name goes to the member it
  overrides. Usages of a member include its overrides, and renaming a member renames its whole override chain.
- **Version catalog**: in a `*.gradle.kts` script, `libs.foo.bar` → the entry `foo-bar` (or `foo_bar`, `foo.bar`) of
  `gradle/libs.versions.toml`, beside the script or above it; `libs.versions.x`, `libs.plugins.x`, `libs.bundles.x` the
  same. Find Usages of an entry lists the scripts; renaming it updates an accessor only when its last segment alone changes.
- **Dependencies**: the JDK, Java sources, and the jars **already in** `~/.gradle/caches` (nothing is downloaded): the
  file's `build.gradle(.kts)` (+ `gradle/libs.versions.toml`) → declared coordinates, `kotlin("x")` and their POM
  dependencies. Without a build script (or a declared Kotlin version) the newest cached `kotlin-stdlib` is used, read
  from its compiled facade classes (`CollectionsKt`, `StandardKt__StandardKt`…).
- **Limits**: no full overload resolution (filtered by argument count, overrides included), no smart casts, generic inference only for
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
  only. ES5 constructor functions are followed: `X.prototype.m = …`, `this.m = …` inside `X` and static `X.m = …`.

### QML

- **Click**: `id`s, properties, signals, `function`s, enums, inline components, `Foo.qml` types, `import "dir"`,
  Qt modules, `import "x.js" as X`, `qs.*` of Quickshell; with types: `root.x`, `anchors.fill`, `Layout.fillWidth`,
  `onXChanged` → `x`, `Component.onCompleted`, `Math.max`, `Text.AlignLeft`. JavaScript parts use the TypeScript module.
- **Rename**: handlers follow (`onCountChanged` → `onTotalChanged`); Find Usages includes them.
- **Dependencies**: Qt type descriptions (`.qmltypes`, `qmldir`) and QML files of the Qt import paths.
- **Limits**: `var` properties and JavaScript values set from C++ are untyped. `qs.*` needs the Quickshell config (the
  folder of `shell.qml`) in the project. JetBrains' own QML plugin (Marketplace, closed source, uses the qmlls language
  server) is not needed.

### Groovy

Groovy 2 to 4: `.groovy`, `.gvy`, `.gradle` (not `.gradle.kts`), `Jenkinsfile`, scripts with a `groovy` shebang.

- **Click**: locals declared before the use (`def`, typed, `def (a, b) = …`), parameters, closure parameters and the
  implicit `it`, `for` / `catch` / try-with-resources variables; names inside strings (`"$name"`, `"${expr}"`, slashy
  strings); script methods, top-level variables and bindings (`x = …` without a declaration → its first assignment).
- **Classes**: fields and methods through implicit `this`, `this.x`, `super.m()`, outer classes and inherited members
  (`extends`, `implements`, traits); `x.name` → `getName()` / `isName()` / `setName()` when there is no field. Class
  names in types, `new`, casts, annotations: same file, nested, `import a.b.C` (also `as Alias`, `.*`, `import static`),
  same package, fully qualified.
- **Members** `a.b` only where the type is written: a declared type, `new C()`, `this`, `super`, static `C.m()`, enum
  constants, the declared return type of a call.
- **Jenkins and Gradle**: a call to an otherwise unknown name (`deployApp(…)`) → `call` in `vars/deployApp.groovy` of
  the project; `load 'x.groovy'` and `apply from: 'x.gradle'` → the file (the file's folder, then the project root).
- **Rename**: a renamed class updates imports and types; a renamed `vars/` step file updates its calls; a renamed
  `load` / `apply from:` target updates the path.
- **Limits**: no JDK/GDK, Gradle DSL or Jenkins DSL model (`println`, `sh`, `stage`, `dependencies { … }` stay
  unresolved); no type inference (`def x = make(); x.m()` needs a declared return type; closure delegates are not
  followed); classes of another package need an import; paths in interpolated strings are not followed;
  `Jenkinsfile.<suffix>` names are not taken (`Jenkinsfile.yaml` is YAML); `<% %>` templates are not Groovy.

### Scala

Scala 2 and the common Scala 3 syntax: `.scala`, `.sc`, `.sbt`.

- **Click**: locals declared before the use, local `def`s and classes anywhere in their block, parameters of every
  clause, type parameters, lambda parameters (`x => …`, `(a, b) => …`), pattern variables (`case`, `val (a, b) = …`,
  `x @ …`, `catch` cases), `for` generators and definitions, names inside interpolated strings (`s"$name ${expr}"`).
- **Members**: of the enclosing class / trait / object / enum and of outer ones, constructor parameters, inherited
  members through `extends` / `with` (project sources), `this.x`, `super.x`. `a.b` only where the type of `a` is
  written: a declared type, `new C(…)`, a case-class or `apply` call `C(…)`, an object, the declared result type of a
  call, or a `val` / `def` whose value is one of these. `a op b` → the method of `a`. Named arguments → the parameter.
  Overloads by name, then by argument count.
- **Classes and objects**: same file, same package across files, chained `package` clauses, package objects; imports
  `a.b.C`, `a.b._` / `a.b.*`, `a.b.{C, D => E, _}`, `C as D`, relative and local imports, `import obj._`; fully
  qualified names. A type is the class, a value is the object; `C(…)` → the `apply` of the companion, else the class.
- **Rename**: a class and its companion are renamed together; imports, types, `new`, calls and named arguments follow.
- **Scala 3**: `enum`, named `given`s, `extension`, `using`, `if … then`, braceless templates and bodies with `end`
  markers; its keywords are contextual, so Scala 2 code using `end` or `given` as names is unaffected.
- **Limits**: no jars, so the JDK, the Scala library (`String`, `List`, `Option`…) and the Gatling DSL (`exec`, `http`,
  `scenario`, `check`…) stay unresolved; no type inference (untyped lambda parameters, generics, `xs.map(_.x)`), no
  implicits or givens; a class of another package needs an import; in a Maven multi-module build lookups stop at the
  nearest `pom.xml`; symbolic names (`+`, `::`) are not renamed or listed; XML literals and a few rare Scala 3
  layouts are reported as errors.

### Dart

`.dart`, parsed with the grammar of the official Dart plugin (Dart 3: records, patterns, class modifiers, extension types).

- **Click**: locals declared before the use, local functions, parameters, closure parameters, `for` / `for in` and
  `catch` variables, the variables a pattern binds (`final (a, b) = …`, `case int n when …`, `if (x case final T v)`),
  type parameters, names inside strings (`'$name ${expr}'`); `this.x` and `super.x` parameters → the field or the
  parameter of the superclass.
- **Members**: fields, methods, getters and setters, named and factory constructors, enum values, static members,
  inherited ones through `extends` / `with` / `implements` / `on`, extension methods. `a.b` follows the type of `a`:
  declared, or read from its value (`var a = A();`, `A.named()`, `const A()`, a call and its return type, a field or a
  getter, `await`, `as`, `!`, a cascade `a..b()`), with type arguments (`Box<A>().value`, the element of a `List<A>`,
  the variable of a `for in`).
- **Operators and literals**: `a + b`, `-a`, `a[i]` give what the operator of the type of `a` returns (`int + int` is
  an `int`, with a `double` a `double`); comparisons, `is`, `!`, `&&` and `||` a `bool`; `{k: v}` is a `Map`, `{a}` a
  `Set`; an assignment and a `switch` expression have the type of their value. The elements of a collection are read
  through `if`, `for` and spreads (`[if (c) a]`, `[...xs]`, `{for (final x in xs) k: x}`).
- **Records and patterns**: `r.$1` and `r.name` of a record → the field of its type or of its literal, with its type;
  the variables of a pattern take the type of what they match: `final (a, n: b) = r`, `for (final (k, v) in pairs)`,
  `case Circle(:final radius)`, `case [var first, ...]`, `case {'k': final v}`, `if (x case final y)`. The label of a
  pattern field (`Circle(radius: var r)`) → the member or the record field. The label of a record literal (`(n: a)`)
  → the field of the record type the literal is given to: a typed variable, the result of its function, a parameter.
- **Extensions**: a member no class declares comes from the extensions the file sees, the ones whose `on` type the
  receiver is (a supertype, `on List<T>` with `T` read from the receiver, `on T`), the most specific first (`on Sub`
  before `on Base`, a class before `on T`); all of them when the type is unknown or none fits. `Ext(x).m()` and `Ext.staticMember` name the extension.
- **Closures and generic calls**: the parameters of a closure take the types of the function type it is given to
  (`xs.map((e) => e.name)`, `then((value) => …)`, `itemBuilder: (context, index) => …`), written on the parameter,
  behind a `typedef` or on the field of `this.x`. The type parameters of a method or a constructor are read from its
  arguments and from what its closures return (`xs.map((e) => e.shape).first`, `Box(a).value`), or written (`f<A>()`).
  An `async` closure returns a `Future` of its value. A closure is also typed by the variable or field it is assigned
  to, the result of the function returning it, and the type arguments of the collection it is in.
- **Constructors**: `A()`, `new A()`, `const A()` → the unnamed constructor when the class writes one, else the class;
  Find Usages and Rename of the class still reach those calls.
- **Named arguments**: `Text(style: …)`, `f(size: 1)`, `super(x: 1)`, `@Tag(label: 'x')` → the parameter, or the field
  of a `this.x` parameter.
- **Libraries**: `import` (relative, `package:`, `dart:`), `show` / `hide`, `as prefix`, `export` chains, `part` and
  `part of`; the string of a directive → the file. Names starting with `_` stay inside their library.
- **Dependencies**: read from `.dart_tool/package_config.json`, which `dart pub get` / `flutter pub get` writes (the
  nearest one above the file): the pub cache, path and workspace packages, and the SDK (`flutterRoot` there, else
  `DART_SDK`, else the `dart` of the `PATH`). Nothing is indexed: a library is opened when a name leads to it, and a
  file whose text lacks the name is not parsed. Without that file, the packages of the project are still found by the
  `name:` of their `pubspec.yaml`.
- **Rename / Usages**: a renamed class renames its constructors; a renamed field renames its `this.x` parameters and
  named arguments; a renamed file updates the directives naming it; a named record field is renamed in its type, its
  accesses (`r.n`), the literals given to that type and the labels of patterns; an extension by its name.
- **Localization**: a member of a localizations class (`AppLocalizations.of(context)!.helloWorld`, a class named
  `…Localizations` or `S`) → the message in the `.arb` files, one target per locale, instead of the generated getter.
  The key matches whatever its case and underscores (`Button_Cancel` for `buttonCancel`). The bundles are those of
  the package of the class, else of the project, narrowed to the ones named like the class (`app_en.arb` or a folder
  `app` for `AppLocalizations`). Without the generated code, a receiver named `l10n`, `…Localizations` or `S.of(…)` is
  enough. In an `.arb` file, `@key` → its message; renaming a message renames it in every locale, its `@key` and its
  getter in Dart (the generated code is to be generated again); Find Usages of a message lists the Dart code reading
  it.
- **`pubspec.yaml`** (and `pubspec_overrides.yaml`): `path:` of a dependency, the members of a `workspace:`, and under
  `flutter:` the `assets:` and the `asset:` of a font → the folder or file.
- **Limits**: no types for the parameters of a closure nothing gives a function type to (`final f = (x) => …`, a
  closure returned by a closure); record types are structural, so a field is followed from one written type (a
  `typedef` reaches every use), not across two types written alike, and `:n` in a pattern does not follow a rename;
  the placeholders of a message are not linked; conditional imports take the default URI; the first resolve through a large library (Flutter's `material.dart`)
  takes a few seconds.

### Lua

Lua 5.1 to 5.4 and LuaJIT (`.lua`, `.rockspec`, `.luacheckrc`).

- **Click**: lexical scopes (locals declared before the use, `local x = x`, `repeat … until`), parameters, loop
  variables, `local function` in its own body, `goto` labels, globals across files; `require("a.b")` → `a/b.lua` or
  `a/b/init.lua` under the project root, `lua/`, `src/`; `dofile` / `loadfile` → the file.
- **Table fields**: followed through locals, `require` + `return M`, `M.f = …` / `function M.f()` /
  `function M:f()`, `self` in methods, `setmetatable(t, M)` with `__index`, and function returns (`Dog.new()` →
  `d:bark()`). The first definition is the target. A local or a parameter of unknown value owns the fields assigned
  through it; `t["name"]` and `{ ["name"] = … }` are the field `name`; `_G.x` / `_ENV.x` are the global `x`;
  `local new = M.new` keeps the return type of `M.new`.
- **Annotations** (EmmyLua / LuaLS): `---@class Name[: Parent]`, `---@field`, `---@param`, `---@type`, `---@return`
  give types; a type name in an annotation leads to its class. Only the first class name of a union, array or generic.
- **Rename**: renamed module files update their `require` strings.
- **Limits**: the runtime library (`string`, `table`, `vim.*`…) has no source in the project and is left unresolved;
  fields added by other files and dynamic `__index` functions are not followed; rename and Find Usages start from a
  use, not from a `---@class` / `---@field` line.

### Shell

**Scripts inside other files** are read as Shell too (colored, variables and functions navigable, syntax errors
reported): the `run: |` of a workflow, the shell form of `RUN` / `CMD` / `ENTRYPOINT` in a Dockerfile, the recipes of
a Makefile and of a justfile, the `run` of a mise task (`[tasks.x]` in `mise.toml`, any table of a `tasks.toml`),
the `scripts` of a `package.json` (needs the JSON plugin). There, the script a package manager runs points to its
entry, with rename and Find Usages: `yarn build`, `yarn run build`, `npm run build`, `npm test`, `pnpm build`,
`bun run build`, the names of `run-s` / `run-p` / `npm-run-all`. A name that is no script of the file is a binary.
What the host file writes into the script stays its own and the shell sees a plain word there: `${{ … }}` of GitHub
Actions, the `$(VAR)` a Makefile defines, a Dockerfile `ARG` / `ENV`, `{{name}}` of just and of mise. Each line is read
without what is not shell: the indentation of a YAML block, the tab and the `@` / `-` of a recipe. Left as plain
text: a step with another `shell:`, a just recipe with another `#!` interpreter, the exec form `["a", "b"]`, a
one-line `run:` of YAML.

sh, bash and zsh. A script without extension is recognised by its `#!` line (interpreter matched as a whole word).

- **Click**: functions, `$VAR` / `${VAR}` / `$(( VAR ))` / `$[ VAR ]` → assignment, `local`, `read`, `for`,
  `(( x = … ))`, `for ((i=0; …))`, `let x=…`, `mapfile` / `readarray NAME`, `printf -v NAME`, `${NAME:=default}`,
  later assignments → the first; `source` / `.` files and what they define (relative, `$(dirname "$0")/…`, `~/…`).
- **Highlighting**: heredocs, `$( )`, `${ }`, `$(( ))`.
- **Limits**: variables are resolved statically (first assignment of the script, `local` of the enclosing function,
  sourced scripts); environment variables and external commands are left unresolved. `((cmd)…)` (a subshell written
  without a space) is read as arithmetic, as bash itself first tries.

### fish

`.fish`, and scripts whose `#!` line names `fish`.

- **Click**: a command word → `function name`; `$x` → its first `set x` / `for x in` / `read x` / `function -a x`
  (later writes refer to the first); `source file` → the file (`~/…` included).
- **Scopes**: a variable written with `set -l`, a function argument or a `for` variable inside a function belongs to that
  function. Functions and global variables are looked up in the file, the files it sources, `functions/name.fish`
  anywhere in the project (autoloading), then every fish file of the project.
- **Limits**: no command substitution or block structure beyond `function … end`; `source` paths built from variables
  are not followed; universal variables and the functions of `~/.config/fish` outside the project are unresolved.

### Rego

`.rego`, the policy language of Open Policy Agent.

- **Colors**: keywords, rule and function definitions, calls, `data` / `input`, strings, numbers, comments.
- **Click**: a name → the variable of its rule (`x := …`, `some x`, `every x`, arguments, `contains x`), else the
  import of that name, else the rule or function of that name in the package, in any file of it; `data.a.b.rule` and
  `alias.rule` → the rule in package `a.b`; a package segment → the `package` lines of that package.
- **Rename**: variables inside their rule; rules with their uses across the project (`alias.rule`, `data.a.b.rule`).
- **Limits**: statements are told apart by their first column, as `opa fmt` writes them; built-in functions, `input`
  and the JSON data documents have no target; fields of a rule's value (`rule.field`) and package prefixes without a
  `package` line of their own are not followed; no type checking.

## Markup

### Markdown

- **Highlighting**: code blocks are colored by the Lite language of their fence (` ```rust `, ` ```bash `, ` ```lua `…;
  any file extension or name works too: ` ```toml `, ` ```Dockerfile `).
- **Click**: links to files and folders (relative, `~/`, `/`, `.md` optional), images, `#anchors` (same or other
  document, `-1` duplicates, URL-encoded), `file#L10` / `#L10-L20` → that line, `[ref]` / `[text][ref]` → `[ref]: …`;
  a code span naming a file (`` `docs/setup.md` ``, `` `MdLexer.kt` `` when only one project file bears that name);
  `<img src>` and `<a href>` of inline HTML. A link text may wrap over the lines of a paragraph. Headers are `#` ones
  and setext ones (`Title` over `===` or `---`); a fence inside a block quote is a code block.
- **Rename**: headers (their anchors follow), linked files (links follow), link labels. Find Usages of a header finds the
  links to its anchor.
- **Preview**: Markdown files open as editor + preview (the editor toolbar switches to editor only or preview only),
  updated as you type. GitHub-flavoured Markdown (tables, task lists, strikethrough, autolinks) through JetBrains'
  intellij-markdown, which ships with the IDE; YAML front matter shown as a block; code blocks colored like the editor;
  light and dark themes follow the color scheme. Links: `#anchors` scroll the preview, project files open in the
  editor, web links in the browser. Shown by Swing, without an embedded browser (JCEF can stay disabled).
- **Zoom**: the buttons above the preview (zoom out, reset to 100%, zoom in), Ctrl/Cmd + mouse wheel over it, or
  Ctrl/Cmd `+` / `-` / `0` (reset); from 50% to 400%, text and diagrams together, kept for every preview and the next
  sessions.
- **Mermaid in the preview**: a ` ```mermaid ` block is drawn as a picture by the plugin itself (Java2D, no JavaScript
  engine): flowcharts (`graph` / `flowchart`), sequence, state, class and entity-relationship diagrams, pies. The
  layout is plain: nodes in ranks, straight links, labels next to the node they reach; subgraphs, styles and
  activations are ignored; diagrams are shown at 140% of the text scale, and a wide one scrolls sideways. Any other type (gantt, C4, mindmap…) stays a
  code block.
- **Limits**: in the editor, indented code blocks are plain text, and a `---` line right under a paragraph line is a
  level-2 header, as on GitHub. In the
  preview: remote images are not downloaded and SVG images are not drawn (both become links), CSS is basic (Swing's
  HTML 3.2), no math, no scroll synchronization with the editor.

### HTML

HTML itself stays with IntelliJ's XML/HTML plugin. `<script>`, `<style>`, `style="…"` and `on…="…"` are colored and
navigable by Lite's JavaScript and CSS, when the official JavaScript/CSS plugins are disabled.

### Angular templates

`*.component.html` is HTML with the template syntax read as TypeScript (needs the XML/HTML plugin; the official
Angular plugin disabled): `{{ … }}`, `[input]="…"`, `(output)="…"`, `[(model)]="…"`, the headers of `@if` / `@else if` /
`@for` / `@switch` / `@case`, `@let`, the `when` of `@defer`. Pipes are understood, with their arguments.
An inline `template: \`…\`` of a `@Component` is read the same way, the rest of the string as HTML.

- Names → the members of the component class (the class of the same folder whose `templateUrl` names the file), with
  types: `item.name` → the property of the item's type. Rename and Find Usages of a member reach its templates.
- Template variables → where they are declared: the item and the `let` of a `@for`, `@let`, `; as alias`, `#ref`,
  `let-row`, `*matCellDef="let row"`.
- A pipe → its class (`@Pipe({ name: 'money' })`), for the pipes of the project; Find Usages of the class lists the
  templates using the pipe, and renaming the class leaves its name alone.
- A custom element (`<app-card>`) → the component whose `selector` names it, for the components of the project;
  `section[appCard]` asks for the attribute too. An attribute a directive's selector names (`appTip`, `*appIf`) → the
  directive.
- `[size]`, `size="…"`, `(closed)`, `[(value)]`, `bind-` / `on-` / `bindon-` → the input or output of the element's
  component or of its directives: `@Input()`, `@Output()`, `input()`, `input.required()`, `model()` (also the event
  `valueChange`), `output()`, an alias (`@Input('kind')`, `{ alias: 'kind' }`), a setter, an inherited member; the
  `inputs: ['size', 'shade: tint']` / `outputs: […]` of the decorator, and those a `hostDirectives` entry exposes
  (`{ directive: Tip, inputs: ['tipDelay: delay'] }`). Those strings follow a rename of the member.
- Rename / Usages: a renamed input or output renames its attributes (an alias stays as written); Find Usages of a
  component or a directive lists its elements and attributes. Renaming the class leaves the selector alone.
- Not covered: templates not named `*.component.html`, an inline template holding `${…}`, the microsyntax of
  `*directive` values, pipes, components and directives of libraries, selectors by class (`.x`); renaming the name
  of a pipe; an element name without a dash is only matched through its attributes.

### Handlebars and Mustache

`.hbs`, `.handlebars` (Handlebars); `.mustache` (Mustache).

- **Colors**: tags, comments (`{{! }}`, `{{!-- --}}`), block helpers, strings, numbers, `key=` arguments, `@index`; the
  text around the tags is colored as light HTML (tags, attributes, attribute values, comments).
- **Click**: `{{> name}}` and `{{#> name}}` → the `{{#*inline "name"}}` of the file, else the nearest project file named
  `name` or `_name` with a template extension (`shared/card` → `…/shared/card.hbs`); a name inside
  `{{#each xs as |x i|}} … {{/each}}` → its block parameter, the innermost block first.
- **Rename**: block parameters, inline partials, and a partial file with the tags naming it.
- `\{{x}}` and the content of `{{{{raw}}}} … {{{{/raw}}}}` are text; `[bar baz]` is one path segment.
- **Limits**: partials registered under another name than their file, dynamic partials (`{{> (lookup …)}}`), helpers
  (they are JavaScript) and data paths (`user.name`) are not followed; Mustache's `{{=<% %>=}}` delimiter change is not
  read; no HTML navigation or embedded CSS/JavaScript.

### Jinja2

`.j2`, `.jinja`, `.jinja2`.

- **Delimiters**: the default ones (`{% %}`, `{{ }}`, `{# #}`), or `<% %>`, `<< >>`, `<# #>` when the file uses them
  (the set chosen where `{{ }}` belongs to the generated text, as in GitHub Actions workflows). `${{ … }}` is never read
  as Jinja.
- **Colors**: tags, statements, strings, numbers, filters; the text around the tags is colored as the format the
  template generates, from the name under the template extension (`release.yml.j2` is YAML).
- **Click**: a block name (`block x`, `endblock x`) → the same block of the nearest template it `extends`;
  `m.name` after `import "m.j2" as m` → the macro or `set` of that file; `with a = 1` variables and `call(item)`
  parameters, up to `endwith` / `endcall`. The content of `raw … endraw` is text. A tag ends outside its strings and
  balanced brackets.
- **Click**: a name → its first `set`, its loop variable (`for x in`), its macro parameter or its `macro`; a name
  brought by `from "x" import name` → the macro in that file; `include`, `import`, `from`, `extends` → the template,
  beside the file or the nearest one whose path ends like the name. Loop variables and parameters belong to their block,
  a `set` inside a macro to that macro.
- **Rename**: variables, parameters, macros (with their imports).
- **Limits**: names given by the rendering code (the context, globals, custom filters) have no target; attributes
  (`img.input`), `block` names and `namespace` fields are not followed; a string holding a tag delimiter ends the tag
  early.

## Diagrams

Colors, click and rename. `.d2`, `.mermaid` and `.mmd` files have no rendered preview of their own; Mermaid blocks of
a Markdown file are drawn in the [Markdown preview](#markdown).

### D2

`.d2`. C4 models written in D2 are plain D2 files.

- **Colors**: reserved keywords (the list of `d2ast/keywords.go`), shapes, connections, strings and block strings,
  imports, `${substitutions}`, comments.
- **Click**: a shape → where it is first named, each level of `a.b.c` on its own, `_` going up a container;
  `class: x` and `class: [x; y]` → the class of that name in a `classes` block; `${x}` → the variable of a `vars`
  block; `...@common/c4` and `@file` → the file (`.d2` optional). Classes, variables and shapes are shared with the
  imported files, followed through their own imports.
- **Rename**: shapes, classes and variables, with their uses in the importing files.
- **Limits**: globs (`*`, `**`) and filters (`&`) name nothing; a shape first named in two files has two targets;
  `layers`, `scenarios` and `steps` are read as containers.

### Mermaid

`.mermaid`, `.mmd`, and the ` ```mermaid ` blocks of Markdown files.

- **Colors**: diagram types, keywords, arrows, strings and edge labels, directives, comments, for every diagram type.
- **Click**: in flowcharts (`flowchart`, `graph`), sequence, state, class and entity-relationship diagrams, an
  identifier → where it first appears (its `participant`, `state` or `class` line when there is one, else its first
  use); `:::name` and `class A,B name` → the `classDef`.
- **Rename**: identifiers and `classDef` names, inside the file.
- **Limits**: the other diagram types (gantt, pie, journey, mindmap, git graph, C4…) are colored only, since their
  lines are text; the text of a flowchart link written `A -- text --> B` is read as names.

## Interfaces

### Protocol Buffers

`.proto` (proto2, proto3, editions), parsed with the grammar of JetBrains' protoeditor (structural highlighting:
messages, enums, fields, services, options).

- **Click**: protoc's rules: names are package-qualified (`acme.common.Id`, `common.Id` from a sibling package, absolute
  `.acme.common.Id`), searched from the innermost scope outwards (nested `Outer.Inner`), across the files reached through
  `import` / `import public`. Covers fields, `map<K, V>`, `rpc` request/response, `extend` and custom option
  `(acme.secret)` names, package segments; `import "x.proto"` → file (folders up to the content root).
- **Dependencies**: Google's well-known types (`google/protobuf/*.proto`) are bundled.

### Bruno

`.bru`.

- **Colors**: blocks (`meta`, `get`, `headers`, `vars:pre-request`…), keys, `{{variables}}`, strings, numbers.
- **Click**: `{{name}}` → the variable of that name in a `vars` block, a `vars:secret` list or a
  `bru.setVar("name", …)`: the request itself, the `folder.bru` and `collection.bru` above it, then every file of the
  collection's `environments` folder (several environments: several targets).
- **Rename**: a variable with its uses across the collection; the same variable declared in another environment is not
  renamed with it.
- Also: `bru.setVar`, `bru.setEnvVar`, `bru.setGlobalEnvVar` and `bru.setCollectionVar` declare; `@name` in a `vars`
  block is the local variable `name`; quoted keys (`"api.key": v`); `{{process.env.NAME}}` → `NAME=` in the `.env` of
  the collection.
- **Limits**: variables kept by the application (global, secrets typed in the UI) have no target;
  the scripts and the JSON bodies are colored lightly, not resolved.

### GitHub Actions

Workflows and composite actions: any YAML file with a top-level `jobs:` or `runs:`.

- **Click**, inside `${{ … }}` and in `if:` conditions (with or without the braces):
  `needs.job` and `jobs.job` → the job, `….outputs.name` → the output of that job; `steps.id` → the step with that
  `id:`, `….outputs.name` → the `name=… >> $GITHUB_OUTPUT` of its script; `inputs.name` and
  `github.event.inputs.name` → the `workflow_call` / `workflow_dispatch` input (or the input of an action);
  `env.NAME` → the `env:` of the step, then of the job, then of the workflow; `matrix.key` → the matrix of the job;
  `secrets.name` → the `workflow_call` secret.
- `needs: a`, `needs: [a, b]` and its list form → the jobs.
- **Local workflows and actions**: `uses: ./.github/workflows/_x.yml` → the file, `uses: ./.github/actions/x` → its
  `action.yml`. The keys under `with:` and `secrets:` of such a job or step lead to the inputs and secrets that file
  declares, and `needs.job.outputs.name` to the outputs of the workflow the job calls.
- **Scripts**: the body of a `run: |` is [Shell](#shell); a `${{ … }}` in it counts as a plain word, as GitHub
  replaces it before the shell runs.
- **Rename / Usages**: a renamed job, step id, input, output or `env` key renames its uses; a renamed workflow file
  renames its `uses:`.
- **Limits**: what the repository does not hold stays unresolved: repository secrets and variables (`secrets.X`,
  `vars.X`), outputs and inputs of remote actions and workflows (`owner/repo/…@v1`), `github.*` and `runner.*`.
  `needs['job']` and `fromJSON(…)` paths are not read. Expressions are not colored.

### Serverless and CloudFormation

In any YAML file that uses their forms.

- **Serverless variables**: each segment of `${self:a.b.c}` → the key of that level in the service file. The path is
  followed through a value written `${file(…)}`, through a list of included files (`functions.0.hello`), and through a
  path holding a variable (`env.${self:custom.stage}.yml` stands for every `env.*.yml`, one target per file).
  `${file(./x.yml)}` → the file, `${file(./x.yml):a.b}` → its keys; `${param:x}` → the parameter of each stage.
- **The service file** of an included file is the `serverless.yml` above it, or in a folder beside one of its parents
  (`middleware/serverless.yml` including `deploy/serverless/…`): `self:` is read there.
- **CloudFormation**: `!Ref X` and `Ref: X` → the resource or the parameter; `!GetAtt X.Arn`, `Fn::GetAtt`,
  `DependsOn` → the resource; `${X}` and `${X.Attr}` in a `!Sub` → the resource or the parameter; `Condition: X` and
  `!If [X, …]` → the condition; `!FindInMap [X, …]` → the mapping. Looked up in the file, in the service and in the
  files the service includes, under `Resources:` at the top or under `resources:`.
- **Resources Serverless makes itself**: `HelloLambdaFunction` and `HelloLogGroup` → the function `hello`,
  `XLambdaLayer` → the layer `x`.
- **Rename / Usages**: a renamed key, resource or condition renames its references.
- **Limits**: what the files do not hold stays unresolved: `${opt:…}`, `${env:…}`, `${ssm:…}`, `${cf:…}`, a
  `${param:…}` given on the command line, resources made by plugins, `AWS::` pseudo parameters. JSON templates are not read.

### OpenAPI, AsyncAPI, JSON Schema

OpenAPI v1 (Swagger 1.2), v2 (Swagger 2.0) and v3.x, in YAML and JSON files: everything that uses `$ref`.

- **Click**: `$ref: '#/components/schemas/Pet'` → the key (JSON pointers, `~1` escapes: `#/paths/~1pets/get`);
  `common.yaml#/Error`, `point.json#/Point` → the key in that file; `./pet.yaml` → the file; discriminator `mapping`
  values, `operationRef`; `$ref: Pet` (Swagger 2.0 shorthand); Swagger 1.2 `"$ref": "Pet"` / `"type": "Pet"` →
  `models.Pet`.
- **Rename / Usages**: renaming a key updates the `$ref`s of every file, YAML and JSON.
- JSON files stay with IntelliJ's JSON plugin; Lite adds the `$ref` navigation to them.

## Specifications

### Gherkin

`.feature`, with English and French keywords.

- **Colors**: keywords, tags, comments, strings, doc strings, `<placeholders>`, table cells.
- **Click**: any word of a step → the pattern of its definition in the code, read as a Cucumber expression (`{int}`,
  `{string}`, `{word}`, `cucumber(s)`), a `parse` format (`{count:d}`) or a regular expression. Definitions are looked
  for in the project files naming their library: Cucumber for Rust (`#[given(expr = "…")]`, `regex = r"…"`), JVM
  (`@Given("…")`, `Given("…") { }`), JavaScript and TypeScript (`Given('…', fn)`, `Given(/…/, fn)`), Go (godog),
  .NET (Reqnroll, SpecFlow), and Python (behave, pytest-bdd); also the French annotations of Cucumber-JVM (`@Soit`,
  `@Alors`, `@Etantdonnéque`) and `defineStep(…)`. Alternatives (`cat/dog`, `\/` for a slash) are read. `<name>` in a
  scenario outline (step, doc string or step table) → its `Examples` column; a step with placeholders is matched with
  the values of the first example row. Keywords are the complete `en` and `fr` lists of Cucumber's
  `gherkin-languages.json`.
- **Rename**: an `Examples` column with its `<placeholders>`, inside its scenario.
- **Limits**: nothing from the code back to the steps (no Find Usages of a definition); custom parameter types of
  Cucumber expressions are not read; other languages than English and French are not colored.

### JFlex

`.flex`, `.jflex`: the lexer specifications of Grammar-Kit and JFlex (the `*.flex` files of this repository).

- **Colors**: directives (`%state`, `%%`), macro definitions and uses, state lists, strings, character classes, the
  Java keywords of the actions, comments.
- **Click**: `{MACRO}` → `MACRO = …`; a state in `<A, B>`, in `yybegin(A)` or anywhere in the Java code → its `%state`
  or `%xstate` line. A macro and a state may share a name.
- **Rename**: macros and states, inside the file.
- Java char literals (`'"'`) are strings; `{MACRO}` inside a character class and `{ MACRO }` with spaces are macro
  uses; a character class may nest one level.
- **Limits**: the Java code is colored, not resolved (token types, methods); `YYINITIAL` is built in, so it has no
  target.

## Build and tasks

### Makefile

`Makefile`, `GNUmakefile`, `.mk`.

- **Click**: `$(VAR)`, `${VAR}`, `$(VAR:.c=.o)`, `$(call fn,…)`, `$(value VAR)`, `ifdef VAR`, `export A B` →
  assignment (also target-specific: `test: DEBUG := 1`) or `define`; prerequisites and `.PHONY` names (continued
  with `\` too) → their rule, else the source file (`main.o: src/main.c`); `include` → file. Space-indented
  assignments, includes and rules (inside `ifeq`) count; tab-indented lines are recipes. A target holding a variable
  (`$(BUILD_DIR)/app`) is linked by its full text.
- **Limits**: a target that is only a variable (`$(BIN):`) is not linked as a target.
- Names are shared with the included makefiles and with the makefiles that include this one. `$$VAR` (shell) is left
  alone.

### just

`justfile`, `.justfile`, `*.just`.

- **Click**: dependencies (`build`, `(build "x")`, after `&&`) and `alias` → recipe; `{{name}}`, `$name`, parameter
  defaults and dependency arguments → the recipe's parameter, else the variable; `import 'x.just'` → file (its names are
  shared); `mod name` → `name.just` / `name/mod.just`; `foo` of `foo::recipe` → the `mod foo` line (the recipe part is
  not followed into the module). Expressions may run over several lines while a bracket is open.

## Configuration and data formats

One short declaration each (`formats` module). Click, rename and Find Usages only where names point to each other.

| Format | Files | Click |
|---|---|---|
| YAML | `.yaml`, `.yml` | `*alias` → `&anchor`; `pubspec.yaml` paths (see [Dart](#dart)); [`$ref`](#openapi-asyncapi-json-schema); [GitHub Actions](#github-actions) names; [Serverless and CloudFormation](#serverless-and-cloudformation) references. The body of a block scalar (`run: \|`) is plain text; anchors, aliases, quotes, numbers and constants only count at the start of a value |
| HOCON | `.hocon`, `.conf` | each segment of `${a.b.c}` → the key of that level (nested blocks, dotted keys): the file, its includes, then every HOCON file of the project; `include "x"`, `include classpath("x")` → file (`.conf` optional); `a."b.c"` is a two-level path |
| Dotenv | `.env`, `.env.*` | `${VAR}` / `$VAR` → `VAR=` (keys may hold `-`; ` # comment` after a value) |
| Properties | `.properties` | `${key}`, `${key:default}` → `key=` in the file or a `.properties` file beside it; `=`, `:` or blank separators, `#`/`!` comments, values continued by a trailing `\` |
| Dockerfile | `Dockerfile`, `Containerfile`, `*.dockerfile` | `$VAR` → `ARG`/`ENV`, `COPY --from=build` / `FROM build` → `FROM … AS build` |
| HCL: Terraform, Terragrunt | `.tf`, `.tfvars`, `.hcl` | see [Terraform and Terragrunt](#terraform-and-terragrunt) |
| CSS, SCSS, Sass | `.css`, `.scss`, `.sass` (indented syntax) | `var(--x)` → `--x:` (any stylesheet of the project), `$x`, `@include`/`+mixin` → `@mixin`/`=mixin`, `@import`/`@use`/`url()` → file (partials `_x.scss`/`_x.sass`) |
| CODEOWNERS | `CODEOWNERS` | literal patterns (`/deploy/`, `src/main.rs`) → the file or folder, from the repository root (the file may sit in `.github/`, `.gitlab/` or `docs/`) |
| Ignore files | `.gitignore`, `.dockerignore`, `.npmignore`, `.helmignore`, `.prettierignore`, `.gitattributes`… | literal patterns (`/build/`, `config.local.json`) → the file or folder |
| TOML | `.toml`, `Cargo.lock`, `Pipfile` | see [Cargo.toml and version catalogs](#cargotoml-and-version-catalogs); the `run` of a mise task is [Shell](#shell); any other TOML file: — |
| INI and relatives | `.ini`, `.cfg`, `.editorconfig`, `.gitconfig`, systemd units, `.desktop` | — |
| KDL | `.kdl` | — |
| JSON5 | `.json5` | — |
| ARB (Flutter messages) | `.arb` | `@key` → its message; rename in every locale; see [Dart](#dart) |
| SQL | `.sql` | — (would need the database schema) |
| CSV, TSV | `.csv`, `.tsv` | — (one color per column, delimiter guessed). Opens as editor + table: the first line names the columns, a click on a header sorts, quoted cells are unquoted, updated as you type; read-only, the first 20 000 lines |

### Cargo.toml and version catalogs

- **`Cargo.toml`**: a dependency written `x = { workspace = true }`, `x.workspace = true` or `[dependencies.x]` with
  `workspace = true` → `x` under `[workspace.dependencies]` of the workspace root (the nearest `Cargo.toml` above with a
  `[workspace]` table); `version.workspace = true` under `[package]` → the key of `[workspace.package]`. In `[features]`,
  `"a"` → the feature, else the optional dependency; `"dep:a"`, `"a/f"` and `"a?/f"` → the dependency `a`.
  `path = "…"` (dependencies, `[lib]`, `[[bin]]`), `build`, `readme`, `license-file` and the literal `members` of a
  workspace → the file or folder.
- **Version catalog** (`*.versions.toml`): `version.ref = "x"` and `version = { ref = "x" }` → `x` under `[versions]`; the
  items of a `[bundles]` list → the library. From a build script, see [Kotlin](#kotlin).
- **Rename / Usages**: a renamed workspace dependency, feature or version renames its uses, in the crates of the workspace too.
- **Limits**: features of another crate (`features = ["derive"]`, the `f` of `"a/f"`), glob members (`crates/*`),
  `[patch]` and `[lints]` tables, a catalog declared in `settings.gradle(.kts)` under another name or path.

### Terraform and Terragrunt

A Terraform module is a folder: its `.tf`/`.tfvars` files share their names. A Terragrunt `.hcl` file stands alone.

- **Click, inside a module**: `var.`, `local.`, `module.`, `data.t.n`, `t.n` (resources), `dependency.`, `include.` → their
  block or `locals` key; `.tfvars` keys → `variable`; `local.x.a.b` → the key of the object the local holds,
  `var.x.a` → the key in the variable's `type = object({…})`, else in its `default`; the variables of
  `[for s in …]` / `{for k, v in …}` and the iterator of a `dynamic` block; `provider = aws.west` → `alias = "west"`
  of `provider "aws"`; `ephemeral.t.n`.
- **Click, across modules**: `module.x.out` → the `output` of the module's folder; the arguments of a `module` block →
  the `variable` they set; the keys of Terragrunt `inputs = { … }` → the variables of the stack named by
  `terraform { source }` (of the file, or of a file it includes); `dependency.x.outputs.out` → the output of the stack
  that unit deploys; `include.x.locals.y` → the local of the included file.
- **Paths**: `source`, `config_path`, `path`, `file("…")` → the folder or file. `${get_terragrunt_dir()}`,
  `${get_parent_terragrunt_dir()}`, `${get_repo_root()}`, `${path_relative_from_include()}` and `${path.module}` are
  replaced by what they return, the `//` of a source is ignored, `find_in_parent_folders("x")` walks up from the parent.
- **Dependencies**: a `module` with a git or registry `source` is found through `.terraform/modules/modules.json`, which
  `terraform init` writes (in the module folder or a parent). Not initialised means not reachable.
- **Rename / Usages**: a variable or an output is renamed with what names it from outside (`module` blocks, `inputs`,
  `.tfvars`, `module.x.out`, `dependency.x.outputs.out`). The Terraform state is not: a renamed resource still needs a
  `moved` block.
- **Limits**: providers ship no source, so a resource type and its attributes lead nowhere; attributes of a computed
  value (`yamldecode(…)`, `merge(…)`), index steps (`var.list[0].name`) and `%{ for }` directives are not followed;
  `inputs` built with
  `merge(…)`, `.terragrunt-cache` and `read_terragrunt_config` results are not followed.

## Install

1. `./gradlew buildPlugin` → Settings → Plugins → ⚙ → *Install Plugin from Disk…* → `build/distributions/lite-0.43.0.zip`.
2. Disable the plugins in the *Replaces* column of [the table above](#supported-languages-and-formats): they register
   the same file extensions. [`tools/ide_diet.py`](tools/ide_diet.py) does it, together with every other non-essential
   plugin.
3. Rust std navigation: `rustup component add rust-src`.

### Project boundaries (same rule for every language)

Name lookups never leave the file's project: nearest manifest (`Cargo.toml`; `settings.gradle(.kts)` then
`build.gradle(.kts)`; `pyproject.toml`/`setup.py`/`setup.cfg`/`requirements.txt`; a lock file, else `package.json`/
`tsconfig.json`; for Scala `build.sbt` or `settings.gradle(.kts)`, else the nearest `pom.xml` / `build.gradle(.kts)`), else the highest folder with `src/` or `.git` (a folder, or the file of a worktree or submodule) below the workspace root, else the workspace root.
Build outputs are ignored (`target/`; `bin/`, `build/`, `out/`; `build/`, `dist/`, `__pycache__/`…; `node_modules/`,
`dist/`, `.next/`…); libraries are always visible. Implemented once in `core`
(`ProjectLayout`, `LiteProjectScope`); each language only declares its manifests and output dirs.

## Code layout (modular)

```
core/       language-agnostic: library-aware cache, reference base, keys index, rename/find-usages bases, brace/commenter helpers, cycle-safe resolve cache, rainbow brackets
rust/       Rust: grammar (Grammar-Kit, from intellij-rust), PSI, Cargo, module tree, resolve, types
kotlin/     Kotlin: PSI, Gradle classpath, resolve (+ Java bridge), types
            kotlin/src/upstream/java: parser imported from the Kotlin compiler (generated, do not edit)
python/     Python: PSI, environment (project root, venv, stdlib), resolve + annotation types
            python/src/upstream/java: lexer/parser imported from intellij-community (generated, do not edit)
typescript/ TypeScript + JavaScript (one language): hand-written lexer/parser (JSX), PSI, node_modules/tsconfig
            environment, module resolution, resolve + types; hooks for languages embedding JavaScript;
            Angular templates: a template language over HTML (with the XML/HTML plugin), expressions injected as TypeScript
qml/        QML (+ `.qmltypes`): object/binding parser extending the JavaScript one, Qt import paths, qmldir/qmltypes,
            resolve (types, bindings, attached properties, JavaScript names); depends on core and typescript
dart/       Dart: PSI over the imported grammar, lexical and member resolve, types (written, read from values, inferred
            for closures and generic calls), libraries (imports, exports, parts), packages and SDK from
            `.dart_tool/package_config.json`
            dart/src/upstream/java + src/main/grammars: lexer/grammar imported from the official Dart plugin (do not edit)
scala/      Scala 2 and 3 (sources, scripts, sbt files): string-aware lexer (interpolation, nested comments), tolerant
            recursive-descent parser (line breaks, Scala 3 indentation regions), lexical resolve, name index
groovy/     Groovy (scripts, classes, Gradle build scripts, Jenkinsfiles): string-aware lexer (GString interpolation,
            slashy strings), tolerant recursive-descent parser, lexical resolve, class index, Jenkins steps
lua/        Lua: lexer, recursive-descent parser, scopes, `require` and table-field resolution
shell/      Shell (sh, bash, zsh): context-tracking lexer (quotes, expansions, heredocs), command parser, resolve
markdown/   Markdown: line lexer, links/anchors/definitions, code blocks injected with the Lite language of their fence,
            preview (intellij-markdown → HTML → Swing, beside the editor), Mermaid blocks drawn with Java2D
protobuf/   Protocol Buffers: PSI over the imported grammar, protoc-style package/scope resolution, imports
            protobuf/src/upstream/java + src/main/grammars: lexer/grammar imported from protoeditor (do not edit)
terminal/   Cmd+arrows by word in the IDE's terminal (macOS): a key listener on the Terminal plugin's views
formats/    configuration and data formats: a rule-based lexer and a generic declaration/reference linker (the engine,
            at the root of the package), one short declaration per format in a sub-package per family: `config`,
            `infra`, `build`, `template`, `diagram`, `spec`, `data`; `$ref` JSON pointers (YAML here, JSON with the JSON plugin);
            HTML `<script>`/`<style>` injection (with the XML/HTML plugin);
            CSV / TSV table preview (Swing, beside the editor);
            cron expressions: a small language (lexer, parser) injected into YAML and HCL values;
            scripts held by YAML, Dockerfile, Makefile, justfile, mise tasks and `package.json` read as Lite Shell
            (found by language id; a test-only dependency on shell/)
src/        the plugin descriptor only: includes META-INF/lite-<module>.xml of each module
buildSrc/   `lite.module` convention (local IDE, JBR 25, platform test framework)
playground/ manual test project: one small project per language and format (see its README)
tools/      import scripts for the upstream grammars, plugin diet script
```

Each language module depends on `core` only (QML also on `typescript`, for the JavaScript it embeds); adding a
language = a new module + one `xi:include` line. The larger modules (`kotlin`, `protobuf`, `python`, `qml`, `rust`,
`typescript`) split their package by role (`ide`, `lexer`, `parser`, `psi`, `resolve`, `types`…), `formats` by family
of formats; the others are one flat package (`me.kevinlot.lite.<module>`).

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
  values `{ … }` skipped as a balanced block; Google's well-known types (BSD-3) copied as is. Licenses:
  `protobuf/THIRD_PARTY_LICENSE-protoeditor.txt`, `protobuf/THIRD_PARTY_LICENSE-protobuf.txt`.
- Dart lexer/grammar: the [official Dart plugin](https://github.com/flutter/dart-intellij-third-party) (`third_party`,
  Apache 2.0) via [`tools/import_dart.py`](tools/import_dart.py): their PSI dropped, a documentation comment kept as one
  token, four rules patched for forms the grammar rejects (see `dart/src/upstream/README.md`). License: `dart/THIRD_PARTY_LICENSE-dart-intellij.txt`.
- Checked against, without copied code: the grammars of the official or reference projects, each read under its
  license — Rego ([opa-idea-plugin](https://github.com/open-policy-agent/opa-idea-plugin), MIT), Shell, YAML, TOML and
  Markdown (intellij-community, Apache 2.0), Makefile (MIT), Handlebars, Gherkin and Terraform (Apache 2.0) in
  [intellij-plugins](https://github.com/JetBrains/intellij-plugins), Scala ([scala/scala](https://github.com/scala/scala) language specification and [intellij-scala](https://github.com/JetBrains/intellij-scala) `_ScalaCoreLexer.flex`, both Apache 2.0), Groovy ([apache/groovy](https://github.com/apache/groovy) `GroovyLexer.g4` and intellij-community `groovy.flex`, Apache 2.0), Lua (EmmyLua, Apache 2.0), HOCON (intellij-hocon,
  Apache 2.0), JFlex (Grammar-Kit, Apache 2.0), D2 (keyword list of d2, MPL-2.0), Mermaid (MIT), Bruno (MIT), Jinja (BSD-3), just (CC0), Dotenv (MIT), Properties (intellij-community, Apache 2.0), ignore files
  (Apache 2.0), KDL (intellij-kdl, MIT), CSV (intellij-csv-validator, Apache 2.0), QML (intellij-qml, MIT). The French
  and English Gherkin keywords come from Cucumber's `gherkin-languages.json` (MIT).
- Everything else (TypeScript/JavaScript, QML, Scala, Groovy, Lua, Shell, fish, Markdown, Rego, Handlebars/Mustache, Jinja2, D2, Mermaid, Bruno, Gherkin, JFlex, the formats) is written for this plugin.

## Develop

```
./gradlew test                       # core and every language
./gradlew buildPlugin                # build/distributions/lite-<version>.zip
./gradlew runIde                     # sandbox IDE
```

The plugin compiles against a local IDE (nothing downloaded) and Gradle runs on its JBR 25. Two environment variables
say where it is, per machine; a git-ignored `mise.local.toml` holds them (then `mise exec -- ./gradlew …`):

```toml
[env]
JAVA_HOME = "<IDE>/jbr"                       # macOS: "<IDE>.app/Contents/jbr/Contents/Home"
ORG_GRADLE_PROJECT_localIdePath = "<IDE>"     # macOS: "<IDE>.app"
```

Offline (`--offline`), when the Gradle cache lacks what the IDE build asks for:
`ORG_GRADLE_PROJECT_testFrameworkVersion = "<cached version>"` pins the platform test framework, and
`ORG_GRADLE_PROJECT_instrumentCode = "false"` skips the bytecode instrumentation (not-null assertions of the imported
Java code).

Optional real-world inputs (read-only; run with `--rerun --no-configuration-cache`, `LITE_SHOW=1` lists the misses):

| Variable | Module | Measures |
|---|---|---|
| `RUST_SRC_LIBRARY=<sysroot>/lib/rustlib/src/rust/library` | rust | std resolution and parsing |
| `LITE_KOTLIN_CORPUS=<dir>`, `LITE_KOTLIN_PROJECT=<dir>` | kotlin | parse + highlight every `.kt`; resolution rate with the Gradle-cache jars |
| `LITE_PYTHON_CORPUS`, `LITE_PYTHON_PROJECT`, `LITE_PYTHON_STDLIB` | python | parsing; resolution rate |
| `LITE_TS_CORPUS=<dir>`, `LITE_TS_PROJECT=<dir>`, `LITE_TS_LIB=<typescript/lib>` | typescript | parse every `.ts`/`.js`; resolution rate |
| `LITE_NG_CORPUS=<dir>` | typescript | expressions of every `*.component.html` and inline template without syntax error; resolution rate |
| `LITE_QML_CORPUS=<dir:dir>`, `LITE_QML_PROJECT=<dir>` | qml | parsing; resolution rate |
| `LITE_SCALA_CORPUS=<dir:dir>` | scala | lexing and parsing (≤ 2% of files with errors, per directory); share of names resolved; the most missed names |
| `LITE_DART_CORPUS=<dir:dir>`, `LITE_DART_SDK=<flutter root>` | dart | parsing (≤ 2% of files with errors), share of names resolved; names of a Flutter widget through the real SDK |
| `LITE_GROOVY_CORPUS=<dir:dir>` | groovy | lexing and parsing (≤ 2% of files with errors); share of references resolved |
| `LITE_LUA_CORPUS=<dir:dir>` | lua | parsing; names and fields resolved |
| `LITE_SH_CORPUS=<dir:dir>` | shell | parsing |
| `LITE_MD_CORPUS=<dir:dir>` | markdown | links resolved; every ` ```mermaid ` block drawn into `markdown/build/mermaid/` |
| `LITE_PROTO_CORPUS=<dir>` | protobuf | parsing; names resolved |
| `LITE_FMT_CORPUS=<dir:dir>` | formats | lexing; links resolved (YAML `$ref` included); cron expressions, scripts and Serverless references found |
| `LITE_FMT_PERF=<file:file>` | formats | per file, the time to highlight it and to resolve its references, with the share of the lexer, the linker and the injections |

## License

[Apache License 2.0](LICENSE). Imported code keeps its own license: see [NOTICE](NOTICE) and "Upstream code".
