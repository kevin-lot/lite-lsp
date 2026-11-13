#!/usr/bin/env bash
# Smoke test: backs up two demo folders into a temporary directory.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/lib/files.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p /tmp/backup-demo/{notes,photos}
touch /tmp/backup-demo/notes/{a,b}.txt

[[ "$(count_files /tmp/backup-demo/notes)" -eq 2 ]] || die "count_files: expected 2"
"$ROOT/backup.sh" -o "$WORK" "$ROOT/backup.conf"
[[ "$(ls "$WORK" | wc -l)" -eq 2 ]] || die "expected 2 archives in $WORK"
log_info "ok"
