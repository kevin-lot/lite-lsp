---
title: Markdown tour
tags: [lite, markdown]
---

![logo](../img/logo.svg)

# Markdown tour

Part of the [Lite playground](../README.md): Markdown features of the Lite plugin. Every link below is a **Ctrl+Click** target.

## Contents

- [Getting Started](getting-started.md) — a document in a sub-folder
- [Installation steps](getting-started.md#installation) — a header of another document
- [Configuration](guides/configuration.md#environment-variables) — two folders down
- [Second "Usage" section](#usage-1) — GitHub numbers repeated titles
- [Rust source](../backend/rust/src/main.rs) and [its folder](../backend/rust/src/) — code and folders
- [My notes](my%20notes.md) — an encoded path
- [Workspace index](~/Documents/dev/AGENTS.md) — a path from the home folder
- [Changelog][changelog] and [FAQ] — reference links, defined at the bottom

## Usage

Run `cargo run` in `backend/rust/` (see [Build & Run](#build--run)).

## Build & Run

```bash
greet() { echo "Hello, $1!"; }
NAME=world
greet "$NAME"
```

```rust
fn main() {
    let name = "world";
    println!("Hello, {name}!");
}
```

```python
def greet(name: str) -> str:
    return f"Hello, {name}!"

print(greet("world"))
```

```typescript
const greet = (name: string): string => `Hello, ${name}!`;
console.log(greet("world").toUpperCase());
```

## Usage

The same title twice: its anchor is `#usage-1`.

| Feature | Try |
|---|---|
| Rename a header | rename `Usage` above: `#usage` and `#usage-1` follow |
| Rename a file | rename `getting-started.md`: every link to it follows |
| Find Usages | on `## Installation` in getting-started.md |

- [x] *italic*, **bold**, ~~struck~~, `code`
- [ ] <https://example.com> and https://example.com stay external (no Ctrl+Click)

<!-- a comment: Ctrl+/ toggles it -->

[changelog]: CHANGELOG.md
[faq]: guides/faq.md "Frequently asked questions"
