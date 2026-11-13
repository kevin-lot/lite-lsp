#!/usr/bin/env bash
# File helpers: counting, archiving.

source "$(dirname "${BASH_SOURCE[0]}")/log.sh"

ARCHIVE_EXT="tar.gz"

count_files() {
  local dir="$1"
  local count=0
  for entry in "$dir"/*; do
    [[ -f "$entry" ]] && count=$(( count + 1 ))
  done
  echo "$count"
}

archive_dir() {
  local source_dir="$1" target="$2"
  log_debug "archiving $source_dir"
  tar -czf "$target.$ARCHIVE_EXT" -C "$(dirname "$source_dir")" "$(basename "$source_dir")" \
    || die "cannot archive $source_dir"
  log_info "created $target.$ARCHIVE_EXT"
}
