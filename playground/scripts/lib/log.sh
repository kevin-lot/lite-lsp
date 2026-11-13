#!/usr/bin/env bash
# Logging helpers, sourced by the other scripts.

LOG_LEVEL="${LOG_LEVEL:-info}"
readonly LOG_PREFIX="[backup]"

log() {
  local level="$1"; shift
  printf '%s %s: %s\n' "$LOG_PREFIX" "$level" "$*" >&2
}

log_info() { log info "$@"; }

log_debug() {
  [[ "$LOG_LEVEL" == debug ]] && log debug "$@"
  return 0
}

die() {
  log error "$@"
  exit 1
}
