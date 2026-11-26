# Lite playground

One place to try every language and format of the Lite plugin. Each folder is a small project with its own
manifest (`Cargo.toml`, `build.gradle.kts`, `pyproject.toml`, `package.json`): Lite keeps lookups inside it, as in
real repositories. Everything below is a **Ctrl+Click** target.

## Programming languages

| Folder | Language | Try |
|---|---|---|
| [backend/rust](backend/rust/src/main.rs) | Rust | `largest(&shapes)` → lib.rs, `big.area()` through `dyn Shape`, `.scaled(2.0)`, `to_uppercase` (std, with `rust-src`) |
| [backend/kotlin](backend/kotlin/src/main/kotlin/playground/Main.kt) | Kotlin | `shapes.largest()` (extension), `it.area()`, `copy(…)` of a data class, `joinToString` (stdlib from the Gradle cache) |
| [backend/python](backend/python/README.md) | Python | see its README: `self.radius`, `from .geometry import Circle`, stdlib (with a venv) |
| [web](web/README.md) | TypeScript, JavaScript, JSX | see its README; `npm install` once for the standard library and `@types/node` |
| [web/src/app](web/src/app/shape-list.component.html) | Angular template | `title`, `remove(shape)` → the component; `shape.name` → `Shape`; `shape`, `i`, `count` → `@for` / `@let`; `area` → the pipe; `<app-shape-badge>` → its component, `[label]` and `(toggled)` → its input and output; [shape-badge](web/src/app/shape-badge.component.ts): the same in an inline `template:` |
| [web/index.html](web/index.html) | HTML `<script>` / `<style>` | `refresh(…)` and `var(--brand)` inside the HTML (official JavaScript/CSS plugins disabled) |
| [ui/qml](ui/qml/Main.qml) | QML | `Card { … }` → Card.qml, `onPicked` → `signal picked`, `first.count`, `Theme.spacing` (singleton, qmldir), `Layout.fillWidth` (Qt) |
| [scripts](scripts/README.md) | Shell | see its README: functions across `source`d files, `local`, `getopts` |
| [lua](lua/init.lua) | Lua | `require("app.util")` → lua/app/util.lua, `util.trim`, `rex:bark()` (setmetatable / `__index`), `self.name`, `VERSION` (global) |
| [pipeline](pipeline/Jenkinsfile) | Groovy (Jenkins, Gradle) | `deployApp` → `call` in vars/deployApp.groovy, `Release` → src/playground/ci/Release.groovy, `release.tag()`, `version` → its first assignment, `target`, `it`, `load 'ci/notify.groovy'`; [build.gradle](pipeline/build.gradle) `apply from:` → gradle/versions.gradle, `sharedLibrary` |
| [mobile](mobile/lib/main.dart) | Dart, pubspec | `ShapeCard(shape: …)` → the constructor parameter, `shape.area` → the getter in lib/src/shape.dart, `largest(shapes)` → the function, `'package:playground_mobile/…'` → the file, `l10n.shapeCount` → the message in [app_en.arb](mobile/lib/l10n/app_en.arb) and app_fr.arb, `pair.$1` and the variables of `final (first, count: n) = pair` → the record fields; [pubspec.yaml](mobile/pubspec.yaml): `assets/shapes.json` → the file |
| [loadtest](loadtest/src/test/scala/playground/ShapesSimulation.scala) | Scala (Gatling) | `ShapeScenarios.browse` → scenarios/ShapeScenarios.scala, `users`, `rampUp`, `baseUrl` → config/Settings.scala (`import Settings._`); [ShapeScenarios.scala](loadtest/src/test/scala/playground/scenarios/ShapeScenarios.scala) `Settings.url(…)`, `listShapes`, `shape("#{id}")`, `$id` in the string, `session` |
| [.github](.github/workflows/ci.yml) | GitHub Actions | `needs.build.outputs.version` → the job and its output, `steps.tag.outputs.value` → the `id:` and the `value=` of the script, `matrix.node`, `env.REGION`, `inputs.dry-run`, `needs: [build]`; `uses: ./.github/workflows/_deploy.yml` → the file, its `with: version` → the input; [_deploy.yml](.github/workflows/_deploy.yml) has a `cron:` |
| [service](service/serverless.yml) | Serverless, CloudFormation | `${self:custom.tables.orders}` → each key, `${self:custom.limits.max}` → config/shared.yml, `${param:domain}` → both stages, `!Ref OrdersQueue`, `!GetAtt OrdersTable.Arn` → resources/dynamodb.yml, `${OrdersQueue.QueueName}` in `!Sub`, `CreateDashorderLambdaFunction` → the function |
| [web/templates](web/templates/page.hbs) | Handlebars, Mustache | `{{#> layout}}`, `{{> header}}` → `partials/_header.hbs`, `{{> row}}` → the inline partial, `shape` → `as \|shape index\|`; [mail.mustache](web/templates/mail.mustache) `{{> signature}}` |
| [specs/shapes.feature](specs/shapes.feature) | Gherkin | a word of a step → its pattern in [steps/shapes.rs](specs/steps/shapes.rs), `<sides>` → its `Examples` column |
| [specs/Calc.flex](specs/Calc.flex) | JFlex | `{DIGIT}` → `DIGIT = …`, `<STRING>` and `yybegin(STRING)` → `%state STRING` |
| [templates](templates/release.yml.j2) | Jinja2 | `<< runner >>` → its `set`, `deploy_with('prod')` → the macro, `envname`, `line`, `include` → `parts/_notify.yml.j2`; [page.html.j2](templates/page.html.j2) default delimiters, `field` → macros.j2 |
| [policies](policies/shapes/access.rego) | Rego | `common.in_department` → common/helpers.rego, `data.common.has_role`, `is_editor` (two definitions), `level`, `limits.maximum` → limits.rego |
| [diagrams](diagrams/shapes.d2) | D2, Mermaid | D2: `...@common/c4` → the file, `class: person`, `[sync; inferred]`, `platform.api`, `${brand}`; Mermaid: [largest-shape.mermaid](diagrams/largest-shape.mermaid) `API`, `DB` → their participant, [shape-states.mmd](diagrams/shape-states.mmd) `if_archived` → its `state` line |
| [docs](docs/markdown-tour.md) | Markdown | the tour: links, anchors, references, code blocks in their language; the preview beside it |

## Configuration and data formats

| File | Format | Try |
|---|---|---|
| [Makefile](Makefile), [build/common.mk](build/common.mk) | Makefile | `$(IMAGE)` → common.mk, `check` prerequisites → their rules, `.PHONY` names, source files in prerequisites |
| [web/package.json](web/package.json) | npm scripts (JSON) | each script is shell; in `ci`, `yarn check` / `npm run build` / `npm test` → their script |
| [backend/rust/Cargo.toml](backend/rust/Cargo.toml) | Cargo.toml (TOML) | `"shapes"` in `[features]` → the feature, `path = "src/lib.rs"` → the file |
| [backend/kotlin/gradle/libs.versions.toml](backend/kotlin/gradle/libs.versions.toml) | version catalog (TOML) | `version.ref = "junit"` → `[versions]`, the items of `testing` → the libraries; `libs.junit.api` in [build.gradle.kts](backend/kotlin/build.gradle.kts) → the entry |
| [mise.toml](mise.toml) | mise tasks (TOML) | the `run` of each task is shell: `$script` → the `for` variable; `{{arg(…)}}` is mise's |
| [justfile](justfile), [build/common.just](build/common.just) | just | `check-web` dependencies → their recipes, `(check-rust "--quiet")`, `{{flags}}` → parameter, `{{tag}}` → variable, `alias c`, `import` |
| [api](api/openapi.yaml) | OpenAPI 3.1, Swagger 2.0 and 1.2 | `$ref: '#/components/schemas/Circle'`, `schemas/common.yaml#/Error`, `schemas/point.json#/Point`, `./paths/shape.yaml`, discriminator `mapping`; [swagger.json](api/legacy/swagger.json) `#/definitions/…`; [shapes-1.2.json](api/legacy/shapes-1.2.json) `"type": "Tag"` → its model |
| [api/requests](api/requests/get-shape.bru) | Bruno | `{{host}}`, `{{token}}` → environments/dev.bru, `{{page}}` → `vars:pre-request`, `{{requestId}}` → `bru.setVar` |
| [CODEOWNERS](CODEOWNERS) | CODEOWNERS | `/backend/rust/`, `/web/src/main.ts`, `docs/` → the folders and file |
| [docker/Dockerfile](docker/Dockerfile) | Dockerfile | `${RUST_VERSION}` → `ARG`, `--from=build` → `FROM … AS build`, `$APP_HOME` → `ENV` |
| [infra/terraform](infra/terraform/main.tf) | Terraform | `var.region` → variables.tf, `local.prefix`, `module.network` (outputs.tf), `aws_s3_bucket.archives`, `./modules/network`, prod.tfvars keys |
| [infra/terragrunt](infra/terragrunt/prod/app/terragrunt.hcl) | Terragrunt | `dependency.vpc` → its block, `config_path` / `source` → the folders |
| [proto](proto/api/shapes.proto) | Protocol Buffers | `import "common/types.proto"`, `playground.common.Id` → types.proto, `Point` |
| [config/application.conf](config/application.conf) | HOCON | `${app.http.host}` → defaults.conf (included), `${app.http.port}` → its key, `include "defaults.conf"` → the file |
| [config/app.yaml](config/app.yaml) | YAML | `*defaults` → `&defaults` |
| [.env](.env) | Dotenv | `${APP_PORT}` → `APP_PORT=` |
| [web/styles](web/styles/components.scss) | CSS, SCSS, Sass | `var(--brand)` → theme.css, `@include card` → `_mixins.scss`, `+rounded` → `=rounded` (legacy.sass) |
| [.gitignore](.gitignore), [.dockerignore](.dockerignore) | Ignore files | `/web/node_modules/`, `backend/rust/target` → the folders |
| [config](config/settings.toml) | TOML, INI/.properties, KDL, JSON5, CSV, SQL | coloring only: settings.toml, app.properties, layout.kdl, data.json5, users.csv (a color per column), schema.sql |

## Checks

```bash
make check        # bash -n on the scripts, tsc on web, cargo check on backend/rust (or: just check)
make test         # + the shell smoke test
```
