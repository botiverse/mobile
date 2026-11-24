#!/usr/bin/env python3
"""Clickable UI in the app goes through the design system, so hover and press are on by default (#wg-mobile-sync:#268).

On the Web, raft-ui's buttons, rows and triggers carry `:hover` in their CSS: a page gets it without
writing anything. In Compose it is the other way round: a bare `Modifier.clickable(indication = null)`
or a `detectTapGestures` tap handler draws no hover and no press, and nothing in review or the
screenshot tests shows the gap. So, over compose/shared/src/commonMain:

- `clickable(` / `clickable {`, `combinedClickable(` and `detectTapGestures(` / `detectTapGestures {`
  are not used for clickable UI. Use `Modifier.raftClickable` (compose/designsystem
  .../components/RaftClickable.kt: hover, press and selected overlays on one interaction source,
  same role / label / long press) or a DS component (RaftButton, RaftIconButton, rows, menus).
- A gesture that is genuinely not a tap on a control (a scrim that only dismisses, a tap sink that
  keeps taps off what is under a popup, a drag or a seek, a message row's own tap / long press /
  selection) says so with an `// raw-click: <reason>` comment on the same line or in the comment
  block right above it.
- Sites that predate this check are listed per file, with their count, in
  scripts/ci/raw_clickables_baseline.txt. A file may not gain a site; when a migration removes one,
  the count is lowered in the same change (`--write-baseline`), so the list is always the exact
  remaining work.

Exit codes: 0 = clean; 1 = violations; 2 = usage error.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ROOT = "compose/shared/src/commonMain/kotlin"
BASELINE = "scripts/ci/raw_clickables_baseline.txt"
MARKER = re.compile(r"//\s*raw-click:\s*\S")
RAW = re.compile(r"(?<![\w$])(clickable|combinedClickable|detectTapGestures)\s*[({]")


def _strip(lines: list[str]) -> list[str]:
    """Each line's code with strings, `//` comments and block comments blanked out."""
    out: list[str] = []
    in_block = False
    for line in lines:
        code = ""
        i = 0
        while i < len(line):
            if in_block:
                end = line.find("*/", i)
                if end < 0:
                    i = len(line)
                else:
                    in_block = False
                    i = end + 2
                continue
            if line.startswith("/*", i):
                in_block = True
                i += 2
                continue
            if line.startswith("//", i):
                break
            if line[i] == '"':
                j = i + 1
                while j < len(line) and line[j] != '"':
                    j += 2 if line[j] == "\\" else 1
                code += '""'
                i = j + 1
                continue
            code += line[i]
            i += 1
        out.append(code)
    return out


def _marked(lines: list[str], i: int) -> bool:
    if MARKER.search(lines[i]):
        return True
    j = i - 1
    while j >= 0 and lines[j].strip().startswith("//"):
        if MARKER.search(lines[j]):
            return True
        j -= 1
    return False


def raw_sites(text: str) -> list[tuple[int, str]]:
    """(1-based line, call) for every unmarked raw click in a Kotlin file."""
    lines = text.split("\n")
    sites: list[tuple[int, str]] = []
    for i, code in enumerate(_strip(lines)):
        if code.lstrip().startswith("import "):
            continue
        for match in RAW.finditer(code):
            if not _marked(lines, i):
                sites.append((i + 1, match.group(1)))
    return sites


def scan(root: Path) -> dict[str, list[tuple[int, str]]]:
    found: dict[str, list[tuple[int, str]]] = {}
    directory = root / ROOT
    for path in sorted(directory.rglob("*.kt")):
        sites = raw_sites(path.read_text(encoding="utf-8"))
        if sites:
            found[str(path.relative_to(root))] = sites
    return found


def parse_baseline(text: str) -> dict[str, int]:
    baseline: dict[str, int] = {}
    for n, raw in enumerate(text.split("\n"), 1):
        line = raw.split("#")[0].strip()
        if not line:
            continue
        count, _, path = line.partition(" ")
        if not count.isdigit() or not path.strip():
            raise ValueError(f"{BASELINE}:{n}: expected `<count> <path>`, got {raw!r}")
        baseline[path.strip()] = int(count)
    return baseline


def compare(found: dict[str, list[tuple[int, str]]], baseline: dict[str, int]) -> list[str]:
    problems: list[str] = []
    for rel, sites in found.items():
        allowed = baseline.get(rel, 0)
        if len(sites) > allowed:
            where = ", ".join(f"{rel}:{line} {call}" for line, call in sites)
            problems.append(
                f"{rel}: {len(sites)} raw click(s), the baseline allows {allowed}. Use Modifier.raftClickable or a DS "
                f"component (hover + press), or mark a non-click gesture `// raw-click: <reason>`. Sites: {where}"
            )
        elif len(sites) < allowed:
            problems.append(f"{rel}: {len(sites)} raw click(s) left, the baseline still says {allowed}; lower it (--write-baseline)")
    for rel, allowed in baseline.items():
        if rel not in found and allowed:
            problems.append(f"{rel}: no raw clicks left, the baseline still says {allowed}; drop it (--write-baseline)")
    return problems


def render_baseline(found: dict[str, list[tuple[int, str]]]) -> str:
    total = sum(len(s) for s in found.values())
    head = [
        "# Raw clickable / combinedClickable / detectTapGestures sites in compose/shared/src/commonMain that",
        "# predate scripts/ci/check_raw_clickables.py (#wg-mobile-sync:#268). `<count> <path>` per file.",
        "# Migrate them to Modifier.raftClickable or a DS component, or mark a real non-click gesture",
        "# `// raw-click: <reason>`, then regenerate: python3 scripts/ci/check_raw_clickables.py --write-baseline",
        f"# Total: {total} site(s) in {len(found)} file(s).",
    ]
    body = [f"{len(sites)} {rel}" for rel, sites in sorted(found.items())]
    return "\n".join(head + body) + "\n"


def self_test() -> None:
    assert raw_sites("    .clickable(onClick = onOpen)") == [(1, "clickable")]
    assert raw_sites("    .clickable { onOpen() }") == [(1, "clickable")]
    assert raw_sites("    .combinedClickable(onClick = a, onLongClick = b)") == [(1, "combinedClickable")]
    assert raw_sites("    .pointerInput(Unit) { detectTapGestures { } }") == [(1, "detectTapGestures")]
    assert raw_sites("        detectTapGestures(\n            onTap = { open() },\n        )") == [(1, "detectTapGestures")]
    assert raw_sites("    .raftClickable(onClick = onOpen)") == [], "the DS entry point"
    assert raw_sites("import androidx.compose.foundation.clickable") == [], "an import"
    assert raw_sites("import androidx.compose.foundation.gestures.detectTapGestures") == [], "an import"
    assert raw_sites("    // a clickable() here would swallow the long press") == [], "a comment"
    assert raw_sites("    /**\n     * not `detectTapGestures(`\n     */") == [], "a doc comment"
    assert raw_sites('    val s = "clickable(x)"') == [], "a string"
    assert raw_sites("    return@detectTapGestures") == [], "a label"
    assert raw_sites("    .pointerInput(Unit) { detectTapGestures { } } // raw-click: tap sink under the popup") == [], "same-line marker"
    assert raw_sites("    // raw-click: the scrim only dismisses\n    .clickable(onClick = onDismiss)") == [], "marker above"
    assert raw_sites("    // raw-click: drag seek, not a control\n    // (a second line of reason)\n    detectTapGestures(") == [], "marker in the block above"
    assert raw_sites("    // raw-click:\n    .clickable(onClick = onDismiss)") == [(2, "clickable")], "a marker needs a reason"
    assert raw_sites("    // raw-click: x\n\n    .clickable(onClick = onDismiss)") == [(3, "clickable")], "a blank line ends the block"
    found = {"a.kt": [(3, "clickable"), (9, "clickable")]}
    assert compare(found, {"a.kt": 2}) == []
    assert compare(found, {"a.kt": 1}), "a new site"
    assert compare(found, {"a.kt": 3}), "a stale count"
    assert compare(found, {}), "a file not on the list"
    assert compare({}, {"a.kt": 1}), "a migrated file still listed"
    assert parse_baseline("# c\n2 a.kt\n") == {"a.kt": 2}
    assert parse_baseline(render_baseline(found)) == {"a.kt": 2}
    print("check_raw_clickables self-test: ok")


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        self_test()
        return 0
    found = scan(REPO_ROOT)
    if argv[1:] == ["--write-baseline"]:
        (REPO_ROOT / BASELINE).write_text(render_baseline(found))
        print(f"wrote {BASELINE}: {sum(len(s) for s in found.values())} site(s) in {len(found)} file(s)")
        return 0
    if argv[1:]:
        print("usage: check_raw_clickables.py [--self-test | --write-baseline]", file=sys.stderr)
        return 2
    baseline_path = REPO_ROOT / BASELINE
    baseline = parse_baseline(baseline_path.read_text()) if baseline_path.exists() else {}
    problems = compare(found, baseline)
    if problems:
        print("Raw clicks without hover / press (scripts/ci/check_raw_clickables.py):")
        for p in problems:
            print(f"  {p}")
        return 1
    total = sum(len(s) for s in found.values())
    print(f"raw clickables: no new sites; {total} listed in {BASELINE} across {len(found)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
