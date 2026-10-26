#!/usr/bin/env python3
"""One-off migration: KuiklyBase kmmresource (`MR`) -> JetBrains Compose resources (`Res`).

Run from the repository root (idempotent: a second run finds nothing left to do):

    python3 scripts/migrate_mr_to_compose_resources.py

What it does
1. Strings: compose/shared/src/commonMain/resources/MR/{base,ja,zh-rCN}/strings.xml
   -> compose/shared/src/commonMain/composeResources/{values,values-ja,values-zh-rCN}/strings.xml.
   Keys and order are kept. Android string escapes that Compose resources does not
   decode (\\' \\" \\@ \\?) are unescaped so the visible text stays the same;
   \\n, \\t, \\\\ and \\uXXXX are kept (Compose resources decodes those itself).
2. Assets: MR/assets/<dir>/<file> -> composeResources/files/<dir>/<file> (git mv).
3. Kotlin call sites under compose/ (every *.kt outside build/):
   - `MR.strings.<key>` -> `Res.string.<key>`;
   - kmmresource imports -> the Raft string layer in `build.raft.app.resources`
     (StringDesc, desc, format, localized, stringResource, ...) or
     `org.jetbrains.compose.resources.StringResource`;
   - a file that uses `Res.string.*` gets `import build.raft.app.resources.*` (the generated
     `Res.string.<key>` accessors are top-level extension properties).
   `MR.assets` users and platform glue are migrated by hand (see the PR description).
"""

from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHARED_COMMON = ROOT / "compose/shared/src/commonMain"
MR_DIR = SHARED_COMMON / "resources/MR"
COMPOSE_RES_DIR = SHARED_COMMON / "composeResources"

LOCALE_DIRS = {"base": "values", "ja": "values-ja", "zh-rCN": "values-zh-rCN"}

RES_PACKAGE = "build.raft.app.resources"
STAR_IMPORT = f"import {RES_PACKAGE}.*"

# Old import -> new import (None: drop the import).
IMPORT_MAP = {
    "import com.tencent.tmm.kmmresource.compose.stringResource": f"import {RES_PACKAGE}.stringResource",
    "import com.tencent.tmm.kmmresource.compose.localized": f"import {RES_PACKAGE}.localized",
    "import com.tencent.tmm.kmmresource.resource.StringResource": "import org.jetbrains.compose.resources.StringResource",
    "import com.tencent.tmm.kmmresource.resource.format": f"import {RES_PACKAGE}.format",
    "import com.tencent.tmm.kmmresource.resource.desc.StringDesc": f"import {RES_PACKAGE}.StringDesc",
    "import com.tencent.tmm.kmmresource.resource.desc.desc": f"import {RES_PACKAGE}.desc",
    "import com.tencent.tmm.kmmresource.resource.desc.Resource": f"import {RES_PACKAGE}.Resource",
    "import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted": f"import {RES_PACKAGE}.ResourceFormatted",
    "import com.tencent.tmm.kmmresource.resource.desc.Raw": f"import {RES_PACKAGE}.Raw",
    "import com.tencent.tmm.kmmresource.resource.desc.ResourceStringDesc": f"import {RES_PACKAGE}.ResourceStringDesc",
    "import com.tencent.tmm.kmmresource.resource.desc.ResourceFormattedStringDesc":
        f"import {RES_PACKAGE}.ResourceFormattedStringDesc",
    "import com.tencent.tmm.kmmresource.resource.desc.RawStringDesc": f"import {RES_PACKAGE}.RawStringDesc",
    f"import {RES_PACKAGE}.MR": None,
}

# Fully qualified references in code.
FQ_MAP = [
    (f"{RES_PACKAGE}.MR.strings.", "Res.string."),
    ("com.tencent.tmm.kmmresource.compose.stringResource(", f"{RES_PACKAGE}.stringResource("),
    ("com.tencent.tmm.kmmresource.resource.StringResource", "org.jetbrains.compose.resources.StringResource"),
    ("com.tencent.tmm.kmmresource.resource.desc.StringDesc", f"{RES_PACKAGE}.StringDesc"),
]

ANDROID_ESCAPES = {"\\'": "'", '\\"': '"', "\\@": "@", "\\?": "?"}


def android_unescape(text: str) -> str:
    out = []
    i = 0
    while i < len(text):
        pair = text[i : i + 2]
        if pair == "\\\\":  # keep an escaped backslash for Compose resources to decode
            out.append(pair)
            i += 2
        elif pair in ANDROID_ESCAPES:
            out.append(ANDROID_ESCAPES[pair])
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(" ", "&#160;")
    )


def convert_strings() -> None:
    if not MR_DIR.exists():
        print("strings: MR directory already gone, skipping")
        return
    for src_name, dst_name in LOCALE_DIRS.items():
        src = MR_DIR / src_name / "strings.xml"
        if not src.exists():
            print(f"strings: {src_name} already converted, skipping")
            continue
        root = ET.parse(src).getroot()
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for element in root:
            if element.tag != "string" or len(element):
                sys.exit(f"{src}: unsupported element <{element.tag}> ({element.get('name')})")
            name = element.get("name")
            text = android_unescape(element.text or "")
            lines.append(f'    <string name="{name}">{xml_escape(text)}</string>')
        lines.append("</resources>")
        dst = COMPOSE_RES_DIR / dst_name / "strings.xml"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text("\n".join(lines) + "\n", encoding="utf-8")
        subprocess.run(["git", "rm", "-q", str(src)], cwd=ROOT, check=True)
        print(f"strings: {src.relative_to(ROOT)} -> {dst.relative_to(ROOT)} ({len(root)} keys)")


def move_assets() -> None:
    assets = MR_DIR / "assets"
    if not assets.exists():
        print("assets: already moved, skipping")
        return
    for sub in sorted(p for p in assets.iterdir() if p.is_dir()):
        dst = COMPOSE_RES_DIR / "files" / sub.name
        dst.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "mv", str(sub), str(dst)], cwd=ROOT, check=True)
        print(f"assets: {sub.relative_to(ROOT)} -> {dst.relative_to(ROOT)}")


IMPORT_RE = re.compile(r"^import [\w.]+(\.\*)?$")


OLD_PATTERN = re.compile(r"^import com\.tencent\.tmm\.kmmresource\.|^import build\.raft\.app\.resources\.MR$|com\.tencent\.tmm\.kmmresource\.[\w.]+[(\s]|\bMR\.strings\.", re.M)


def rewrite_kotlin(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    # Only files that still use kmmresource: a re-run (e.g. after a rebase brings in new
    # MR.strings call sites) must not touch already migrated files or `Res.string` in comments.
    if not OLD_PATTERN.search(original):
        return False
    text = original
    for old, new in FQ_MAP:
        text = text.replace(old, new)
    text = re.sub(r"\bMR\.strings\.", "Res.string.", text)

    lines = text.split("\n")
    package = next((l.split()[1] for l in lines if l.startswith("package ")), "")
    out: list[str] = []
    for line in lines:
        stripped = line.rstrip()
        if stripped in IMPORT_MAP:
            new = IMPORT_MAP[stripped]
            if new is not None:
                out.append(new)
            continue
        out.append(line)

    uses_res = re.search(r"\bRes\.string\.", "\n".join(out)) is not None
    if uses_res and package != RES_PACKAGE:
        # The star import covers Res, its accessors and the Raft string layer.
        out = [l for l in out if not (l.startswith(f"import {RES_PACKAGE}.") and IMPORT_RE.match(l.rstrip()))]
        insert_at = next((i for i, l in enumerate(out) if l.startswith("import ")), None)
        if insert_at is None:
            pkg_index = next(i for i, l in enumerate(out) if l.startswith("package "))
            out[pkg_index + 1 : pkg_index + 1] = ["", STAR_IMPORT]
        else:
            out.insert(insert_at, STAR_IMPORT)

    # Drop exact duplicate imports the mapping may have produced.
    seen: set[str] = set()
    deduped: list[str] = []
    for line in out:
        if line.startswith("import "):
            if line in seen:
                continue
            seen.add(line)
        deduped.append(line)

    text = "\n".join(deduped)
    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def rewrite_call_sites() -> None:
    changed = 0
    for path in sorted((ROOT / "compose").rglob("*.kt")):
        relative = path.relative_to(ROOT).as_posix()
        # Skip Gradle output (compose/<module>/build/...); source packages are also named `build`.
        if re.match(r"compose/[^/]+/build/", relative) or "/.gradle/" in relative:
            continue
        if rewrite_kotlin(path):
            changed += 1
    print(f"kotlin: rewrote {changed} files")


def main() -> None:
    convert_strings()
    move_assets()
    rewrite_call_sites()


if __name__ == "__main__":
    main()
