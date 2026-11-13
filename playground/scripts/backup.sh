#!/usr/bin/env bash
# Backs up folders listed in a config file: ./backup.sh [-v] [-o OUTPUT_DIR] CONFIG
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/lib/files.sh"

OUTPUT_DIR="${TMPDIR:-/tmp}/backups"
VERBOSE=0

usage() {
  cat <<EOF
usage: $(basename "$0") [-v] [-o OUTPUT_DIR] CONFIG
  -v   verbose (LOG_LEVEL=debug)
  -o   where archives go (default: $OUTPUT_DIR)
EOF
  exit 2
}

parse_args() {
  while getopts "vo:h" opt; do
    case "$opt" in
      v) VERBOSE=1; LOG_LEVEL=debug ;;
      o) OUTPUT_DIR="$OPTARG" ;;
      h | *) usage ;;
    esac
  done
  shift $(( OPTIND - 1 ))
  CONFIG="${1:-}"
  [[ -n "$CONFIG" ]] || usage
}

backup_all() {
  local total=0 line name
  mkdir -p "$OUTPUT_DIR"
  while IFS= read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    name="$(basename "$line")"
    log_debug "$name: $(count_files "$line") files"
    archive_dir "$line" "$OUTPUT_DIR/$name-$(date +%F)"
    total=$(( total + 1 ))
  done < "$CONFIG"
  log_info "$total folder(s) backed up to $OUTPUT_DIR"
}

parse_args "$@"
(( VERBOSE )) && log_debug "config: $CONFIG"
backup_all
