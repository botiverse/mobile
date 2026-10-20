#!/usr/bin/env python3
"""Legacy UI ratchet: the old brutal look may only shrink (local-only scan; not wired into CI per artin 2026-10-20).

Origin: #proj-mobile 2026-10-20 (xxchan "这 UI 有严重问题"). After the Compose
Multiplatform switch, pages kept calling the pre-raft-ui `RaftBrutal*`
components and hand-drawing thick ink borders, so fixing one page left the
others in the old style, and nothing stopped new code from adding more.

Two counts are tracked per file under compose/shared/src/commonMain:

- legacy component calls: `RaftBrutal*(` call sites (declarations excluded);
- thick ink borders: `.border(` with a stroke wider than 1dp in an ink colour
  (BorderBlack, ShadowBlack, TextBlack, lineStrong, ink, Color.Black).

A file may never exceed its baseline (new files start at 0). Lowering a count
always passes; run with --update-baseline in the same PR to lock the gain in.
Use compose/designsystem components instead (see AGENTS.md "Shared UI
Component Discipline").

Exit codes: 0 = ratchet holds; 1 = a count grew (or the baseline is missing);
2 = usage error.
"""

from __future__ import annotations

import json
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCAN_ROOT = "compose/shared/src/commonMain/kotlin"
BASELINE = "scripts/ci/legacy_ui_ratchet_baseline.json"

LEGACY_CALL = re.compile(r"(?<![\w.])RaftBrutal[A-Z]\w*\s*\(")
DECLARATION = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?RaftBrutal[A-Z]\w*\s*\(")
THICK_INK_BORDER = re.compile(
    r"\.border\(\s*(?:width\s*=\s*)?"
    r"(?:RaftAppStroke\.(?:Control|Default|Strong)|(?:1\.[1-9]\d*|[2-9](?:\.\d+)?)\.dp)"
    r"\s*,[^\n]*?(?:BorderBlack|ShadowBlack|TextBlack|lineStrong|\.ink\b|Color\.Black)"
)
METRICS = ("legacyComponentCalls", "thickInkBorders")


def count_source(text: str) -> dict[str, int]:
    calls = 0
    borders = 0
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        calls += len(LEGACY_CALL.findall(line)) - len(DECLARATION.findall(line))
        borders += len(THICK_INK_BORDER.findall(line))
    return {"legacyComponentCalls": calls, "thickInkBorders": borders}


def scan(root: Path) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    base = root / SCAN_ROOT
    for path in sorted(base.rglob("*.kt")):
        counts = count_source(path.read_text(encoding="utf-8"))
        if any(counts.values()):
            result[path.relative_to(root).as_posix()] = counts
    return result


def totals(counts: dict[str, dict[str, int]]) -> dict[str, int]:
    return {m: sum(c.get(m, 0) for c in counts.values()) for m in METRICS}


def compare(current: dict[str, dict[str, int]], baseline: dict[str, dict[str, int]]) -> list[str]:
    errors = []
    for file, counts in current.items():
        allowed = baseline.get(file, {})
        for metric in METRICS:
            now, was = counts.get(metric, 0), allowed.get(metric, 0)
            if now > was:
                errors.append(f"{file}: {metric} {was} -> {now}")
    return errors


def run(root: Path, update: bool) -> int:
    current = scan(root)
    baseline_path = root / BASELINE
    if update:
        payload = {"totals": totals(current), "files": current}
        baseline_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"baseline written: {totals(current)}")
        return 0
    if not baseline_path.is_file():
        print(f"error: missing {BASELINE}; run with --update-baseline", file=sys.stderr)
        return 1
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))["files"]
    errors = compare(current, baseline)
    if errors:
        print("Legacy UI grew. Use compose/designsystem components instead of RaftBrutal* or thick ink borders:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    now, was = totals(current), totals(baseline)
    print(f"legacy UI ratchet holds: {now} (baseline {was})")
    if now != was:
        print("counts went down; run with --update-baseline to lock the gain in")
    return 0


def self_test() -> int:
    sample = "\n".join(
        [
            "@Composable fun RaftBrutalButton(label: String) {}",
            "RaftBrutalButton(label = \"a\")",
            "    RaftBrutalIconButton(icon) ; foo.RaftBrutalTag(x)",
            "// RaftBrutalButton(label) in a comment",
            "Modifier.border(2.dp, RaftColor.BorderBlack, shape)",
            "Modifier.border(width = RaftAppStroke.Strong, color = colors.lineStrong)",
            "Modifier.border(1.dp, RaftColor.BorderBlack)",
            "Modifier.border(RaftAppStroke.Thin, RaftColor.BorderHairline, shape)",
            "Modifier.border(1.5.dp, RaftColor.BorderHairline)",
        ]
    )
    got = count_source(sample)
    expected = {"legacyComponentCalls": 2, "thickInkBorders": 2}
    if got != expected:
        print(f"self-test: count_source {got} != {expected}", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        page = root / SCAN_ROOT / "build/raft/app/Page.kt"
        page.parent.mkdir(parents=True)
        (root / "scripts/ci").mkdir(parents=True)
        page.write_text("RaftBrutalButton(a)\nRaftBrutalButton(b)\n", encoding="utf-8")
        if run(root, update=True) != 0:
            return 1
        page.write_text("RaftBrutalButton(a)\n", encoding="utf-8")
        if run(root, update=False) != 0:
            print("self-test: a decrease must pass", file=sys.stderr)
            return 1
        page.write_text("RaftBrutalButton(a)\nRaftBrutalButton(b)\nRaftBrutalButton(c)\n", encoding="utf-8")
        if run(root, update=False) != 1:
            print("self-test: an increase must fail", file=sys.stderr)
            return 1
        (page.parent / "New.kt").write_text("Modifier.border(2.dp, Color.Black)\n", encoding="utf-8")
        page.write_text("RaftBrutalButton(a)\n", encoding="utf-8")
        if run(root, update=False) != 1:
            print("self-test: a new file with a thick ink border must fail", file=sys.stderr)
            return 1
    print("self-test ok")
    return 0


def main(argv: list[str]) -> int:
    args = argv[1:]
    if args == ["--self-test"]:
        return self_test()
    if args == ["--update-baseline"]:
        return run(REPO_ROOT, update=True)
    if not args:
        return run(REPO_ROOT, update=False)
    print("usage: check_legacy_ui_ratchet.py [--self-test | --update-baseline]", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
