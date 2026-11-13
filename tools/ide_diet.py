#!/usr/bin/env python3
"""Disable every IntelliJ plugin except an explicit keep-list (plus their hard dependencies).

Dry run by default: prints what would change. `--apply` writes disabled_plugins.txt after
backing it up. Close the IDE first: it rewrites that file on exit.

    python3 tools/ide_diet.py                 # dry run
    python3 tools/ide_diet.py --apply
    python3 tools/ide_diet.py --keep org.intellij.plugins.markdown --apply
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

IDE_DIR = "/home/kevin/Softwares/idea/idea-IU-262.9437.185"
CONFIG_DIR = os.path.expanduser("~/.config/JetBrains/IntelliJIdea2026.3")
USER_PLUGINS_DIR = os.path.expanduser("~/.local/share/JetBrains/IntelliJIdea2026.3")

# Essential plugins of IntelliJ IDEA (cannot be disabled) and Lite. TextMate is not kept: Lite colors the formats in use
# (`--keep org.jetbrains.plugins.textmate` brings it back for the other ones).
REQUIRED = [
    "com.intellij.idea.customization", "com.intellij.java", "com.intellij.java.ide",
    "com.intellij.modules.json", "me.kevinlot.lite",
]
DEFAULT_KEEP = [
    "Git4Idea", "com.intellij.platform.vcs", "org.jetbrains.plugins.github",
    "org.jetbrains.plugins.terminal", "com.intellij.modules.pty4j",
    "com.anthropic.code.plugin", "com.intellij.mcpServer",
    "com.jetbrains.restClient",      # HTTP Client (.http files)
    "com.intellij.bookmarks",
    "com.intellij.problemsView",     # Problems tool window
    "org.jetbrains.plugins.workspace",  # Multi-Project Workspace
]


def read_plugins(*roots):
    plugins = {}
    for root in roots:
        for pdir in sorted(glob.glob(os.path.join(root, "*/"))):
            for jar in glob.glob(os.path.join(pdir, "lib", "*.jar")):
                try:
                    xml = zipfile.ZipFile(jar).read("META-INF/plugin.xml").decode("utf-8", "replace")
                except (KeyError, zipfile.BadZipFile):
                    continue
                xml = re.sub(r"<!--.*?-->", "", xml, flags=re.S)
                # Content modules are optional unless marked required; their deps are not hard deps.
                xml = re.sub(r"<content\b.*?</content>", "", xml, flags=re.S)
                m = re.search(r"<id>\s*([^<]+?)\s*</id>", xml) or re.search(r"<name>\s*([^<]+?)\s*</name>", xml)
                pid = m.group(1) if m else os.path.basename(pdir.rstrip("/"))
                deps = {d.group(2) for d in re.finditer(r"<depends([^>]*)>\s*([^<]+?)\s*</depends>", xml)
                        if 'optional="true"' not in d.group(1)}
                deps |= set(re.findall(r'<plugin\s+id="([^"]+)"', xml))
                name = re.search(r"<name>\s*([^<]+?)\s*</name>", xml)
                plugins[pid] = {
                    "name": name.group(1) if name else pid,
                    "deps": deps,
                    "provides": set(re.findall(r'<module\s+value="([^"]+)"', xml)),
                }
                break
    return plugins


def closure(plugins, seeds):
    providers = {m: pid for pid, p in plugins.items() for m in p["provides"]}
    keep, todo = set(), list(seeds)
    while todo:
        pid = todo.pop()
        pid = pid if pid in plugins else providers.get(pid)
        if pid and pid not in keep:
            keep.add(pid)
            todo.extend(plugins[pid]["deps"])
    return keep


def ide_running():
    return subprocess.run(["pgrep", "-f", IDE_DIR], capture_output=True).returncode == 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--keep", action="append", default=[], help="extra plugin id to keep (repeatable)")
    ap.add_argument("--no-defaults", action="store_true", help="do not keep Git/Terminal/GitHub/Claude Code")
    ap.add_argument("--apply", action="store_true", help="write disabled_plugins.txt (IDE must be closed)")
    args = ap.parse_args()

    plugins = read_plugins(os.path.join(IDE_DIR, "plugins"), USER_PLUGINS_DIR)
    seeds = REQUIRED + ([] if args.no_defaults else DEFAULT_KEEP) + args.keep
    keep = closure(plugins, seeds) | set(REQUIRED)

    path = os.path.join(CONFIG_DIR, "disabled_plugins.txt")
    old = set()
    if os.path.exists(path):
        with open(path) as f:
            old = {line.strip() for line in f if line.strip()}
    new = (old | set(plugins)) - keep

    enabled_now = sorted(keep & old)
    disabled_now = sorted(new - old)
    print(f"Keep ({len(keep)}): " + ", ".join(sorted(keep)))
    print(f"\nRe-enable ({len(enabled_now)}): " + (", ".join(enabled_now) or "-"))
    print(f"\nDisable ({len(disabled_now)}):")
    for pid in disabled_now:
        print(f"  {pid:55} {plugins.get(pid, {}).get('name', '')}")

    if not args.apply:
        print("\nDry run. Re-run with --apply (IDE closed) to write", path)
        return 0
    if ide_running():
        print("\nIntelliJ is running: close it first (it rewrites the file on exit).", file=sys.stderr)
        return 1
    if os.path.exists(path):
        backup = f"{path}.{time.strftime('%Y%m%d-%H%M%S')}.bak"
        shutil.copy2(path, backup)
        print(f"\nBackup: {backup}")
    with open(path, "w") as f:
        f.write("\n".join(sorted(new)) + "\n")
    print(f"Wrote {path} ({len(new)} disabled)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
