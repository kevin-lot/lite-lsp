#!/usr/bin/env python3
"""Import the Python lexer/parser module of intellij-community (Apache 2.0) into python/src/upstream.

    git clone --depth 1 --filter=blob:none --sparse https://github.com/JetBrains/intellij-community /tmp/ic
    git -C /tmp/ic sparse-checkout set --no-cone /python/python-parser/ /LICENSE.txt
    python3 tools/import_python_parser.py /tmp/ic

Changes vs upstream: package me.kevinlot.lite.python.upstream, language ids LitePython/LitePythonStub
(coexist with the official plugin), extension point/bundle renamed, incremental leaf reparse off.
Element types come from our PyElementTypesFacade implementation (plain types, no stubs).
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "python"
UP = "me.kevinlot.lite.python.upstream"
NOTICE = "// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).\n"

PATCHES = {
    "PythonLanguage.java": [('super("Python");', 'super("LitePython");')],
    "PythonFileType.java": [('String getName() {\n    return "Python";', 'String getName() {\n    return "Python (Lite)";')],
    "PyiLanguageDialect.java": [('"PythonStub"', '"LitePythonStub"')],
    "PythonDialectsTokenSetContributor.java": [('"Pythonid.dialectsTokenSetContributor"', '"me.kevinlot.lite.pythonDialectsTokenSetContributor"')],
    "PyParsingBundle.java": [('"messages.PyParserBundle"', '"messages.LitePyParserBundle"')],
}


def convert(text: str, name: str) -> str:
    t = text.replace("com.jetbrains.python", UP)
    t = t.replace('Registry.is("python.ast.leaves.incremental.reparse")', "false")
    for old, new in PATCHES.get(name, []):
        if old not in t:
            raise SystemExit(f"{name}: patch anchor not found: {old!r}")
        t = t.replace(old, new)
    return NOTICE + t


def main():
    src = Path(sys.argv[1]) / "python/python-parser"
    out = ROOT / "src/upstream/java" / UP.replace(".", "/")
    if out.exists():
        shutil.rmtree(out)
    for base in (src / "src/com/jetbrains/python", src / "gen/com/jetbrains/python"):
        for f in base.rglob("*"):
            if f.suffix in (".java", ".kt"):
                dst = out / f.relative_to(base)
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text(convert(f.read_text(), f.name))
    res = ROOT / "src/main/resources"
    (res / "messages").mkdir(parents=True, exist_ok=True)
    shutil.copy(src / "resources/messages/PyParserBundle.properties", res / "messages/LitePyParserBundle.properties")
    shutil.copytree(src / "resources/icons", res / "icons", dirs_exist_ok=True)
    shutil.copy(Path(sys.argv[1]) / "LICENSE.txt", ROOT / "THIRD_PARTY_LICENSE-intellij-community.txt")
    print("ok", sum(1 for _ in out.rglob("*.*")), "files")


if __name__ == "__main__":
    main()
