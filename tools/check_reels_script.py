#!/usr/bin/env python3
"""Checks the JavaScript that ReelsActivity puts into Instagram's page: that it parses (with node, if installed) and
that every fmtv* function the Java side calls is defined in it. A slip in the escaped string, or a function cut out
along with its neighbour, otherwise fails silently on the TV."""
import re
import shutil
import subprocess
import sys
from pathlib import Path

src = (Path(__file__).resolve().parent.parent / "app/src/io/freemytv/ReelsActivity.java").read_text(encoding="utf-8")
start = src.index("private static final String SCRIPT = ")
end = src.index('+ "})();";', start) + len('+ "})();";')
body = "\n".join(line for line in src[start:end].split("\n") if not line.strip().startswith("//"))
parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
script = "".join(re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), p) for p in parts)

ok = True
defined = set(re.findall(r"window\.(fmtv\w+)\s*=", script))
called = set(re.findall(r'evaluateJavascript\("(?:window\.\w+&&)?(fmtv\w+)', src))
for name in sorted(called - defined):
    print(f"missing in the page script: {name}")
    ok = False

if shutil.which("node"):
    r = subprocess.run(["node", "-e", "new Function(require('fs').readFileSync(0,'utf8'))"], input=script,
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("syntax error in the page script:", (r.stderr.strip().splitlines() or ["?"])[-1])
        ok = False
else:
    print("node not found: syntax not checked")

print("page script OK" if ok else "page script BROKEN")
sys.exit(0 if ok else 1)
