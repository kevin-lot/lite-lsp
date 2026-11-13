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
| [web/index.html](web/index.html) | HTML `<script>` / `<style>` | `refresh(…)` and `var(--brand)` inside the HTML (official JavaScript/CSS plugins disabled) |
| [ui/qml](ui/qml/Main.qml) | QML | `Card { … }` → Card.qml, `onPicked` → `signal picked`, `first.count`, `Theme.spacing` (singleton, qmldir), `Layout.fillWidth` (Qt) |
| [scripts](scripts/README.md) | Shell | see its README: functions across `source`d files, `local`, `getopts` |
| [docs](docs/markdown-tour.md) | Markdown | the tour: links, anchors, references, code blocks in their language |

## Configuration and data formats

| File | Format | Try |
|---|---|---|
| [Makefile](Makefile), [build/common.mk](build/common.mk) | Makefile | `$(IMAGE)` → common.mk, `check` prerequisites → their rules |
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
make check        # bash -n on the scripts, tsc on web, cargo check on backend/rust
make test         # + the shell smoke test
```
