# shell_template

Template to try the Lite plugin on shell scripts (bash): open it in the workspace, then

- Ctrl+Click: `archive_dir` / `count_files` (backup.sh) → lib/files.sh, `log_info` → lib/log.sh (through the
  `source` chain), `$OUTPUT_DIR` → its first assignment, `$line` → `local … line`, `$opt` → `getopts … opt`,
  `$(( count + 1 ))` → `local count`, `. "$SCRIPT_DIR/lib/files.sh"` → the file, `$LOG_PREFIX` → `readonly`.
- Rename `log` in lib/log.sh: its calls follow (and only those: `log_info` is another function).
- Rename `total` in `backup_all`: a `local`, renamed in place inside the function only.
- Find Usages on `die` with different scopes.

Run: `./tests/run.sh` (creates /tmp/backup-demo).
