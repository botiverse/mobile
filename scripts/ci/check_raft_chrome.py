#!/usr/bin/env python3
"""Fail when pages hand-draw brutal chrome instead of using the raft-ui primitives.

Origin: the raft-ui 0.6/0.7 alignment moved every primitive under
compose/shared/src/commonMain/kotlin/build/raft/app/components/ to the raft look, but
pages kept drawing their own chrome (square corners, a hard 1.5dp ink border, offset
shadow boxes), so they still rendered brutal next to raft primitives (artin's review of
the raft-ui pages branch). Pages must compose the primitives (SlockRaftCard,
SlockRaftInsetList / SlockRaftListRow, SlockRaftDescriptionList, SlockRaftTag,
SlockRaftValueBlock, SlockFormField, slockRaftCardSurface / slockRaftControlSurface ...).

Patterns rejected in commonMain outside components/:
  square-corners  `slockSquareCorners()`
  ink-border      `border(SlockStroke.Default, SlockColor.BorderBlack` (with or without a shape)
  brutal-shadow   `SlockShadow.Brutal*`
  offset-shadow   an `offset(x = .., y = ..)` box painted with the shadow ink
                  (`SlockColor.ShadowBlack` / `BorderBlack` / `theme.shadow.color`) - the brutal lift

Exceptions:
  ALLOWED_FILES   whole files that are out of scope (auth pages - login is a web
                  redirect now; visual / debug probe pages).
  BASELINE        scripts/ci/raft_chrome_baseline.json: per file, per pattern, the
                  count still allowed and why. The gate ratchets: a count above the
                  baseline fails, and so does a count BELOW it (a stale baseline would
                  let the debt grow back) - lower the number in the same change.

Usage: check_raft_chrome.py [--self-test] [--update-baseline] [--report]
  --update-baseline  rewrite the baseline to the current counts (keeps reasons; new
                     entries get a TODO reason that review must replace).
  --report           print the per-file counts and exit 0.

Exit codes: 0 = clean; 1 = violation or stale baseline; 2 = usage error.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCAN_ROOT = "compose/shared/src/commonMain/kotlin"
COMPONENTS_DIR = "compose/shared/src/commonMain/kotlin/build/raft/app/components/"
BASELINE_PATH = REPO_ROOT / "scripts/ci/raft_chrome_baseline.json"

APP = "compose/shared/src/commonMain/kotlin/build/raft/app/"
ALLOWED_FILES = {
    # Login / auth: a web redirect now, out of scope for the raft page migration.
    APP + "AuthPages.kt": "auth page (login is a web redirect)",
    APP + "AuthComponents.kt": "auth page (login is a web redirect)",
    APP + "AuthChooseServerPage.kt": "auth page (login is a web redirect)",
    APP + "WebLoginScreen.kt": "auth page (login is a web redirect)",
    APP + "WebLoginConsent.kt": "auth page (login is a web redirect)",
    APP + "PrivateDeploymentSheet.kt": "auth page (private-deployment sheet on the login screen)",
    # Visual / debug probe pages render the token and glyph sheets on purpose.
    APP + "TokenPaletteVisualPage.kt": "visual token palette fixture page",
    APP + "SlockTypographyProbePage.kt": "debug typography probe page",
    APP + "SlockLucideIconGalleryPage.kt": "debug icon gallery page",
    APP + "SlockPagerScrollProbePage.kt": "debug probe page",
    APP + "SlockTabPagerProbePage.kt": "debug probe page",
    APP + "SlockChildPageVisibilityProbePage.kt": "debug probe page",
    APP + "BottomTabBarSmokePage.kt": "debug smoke page",
    APP + "SettingsTabBarSmokePage.kt": "debug smoke page",
}

PATTERNS: dict[str, re.Pattern[str]] = {
    # The extension's own declaration is not a use.
    "square-corners": re.compile(r"(?<!fun Modifier\.)\bslockSquareCorners\(\)"),
    "ink-border": re.compile(r"\bborder\(\s*SlockStroke\.Default\s*,\s*SlockColor\.BorderBlack\b"),
    "brutal-shadow": re.compile(r"\bSlockShadow\.Brutal\w*"),
    # Bounded window: the offset box and its shadow fill are one modifier chain. A lift
    # only ever moves down-right, so an offset with a negative component (a badge nudged
    # onto a corner) is not a shadow.
    "offset-shadow": re.compile(
        r"\boffset\((?![^()]*-)[^()]*,[^()]*\)(?:(?!\bText\(|\bRow\(|\bColumn\(|\boffset\()[\s\S]){0,240}?"
        r"\bbackground\((?:[^()]|\([^()]*\))*?(?:SlockColor\.(?:ShadowBlack|BorderBlack)|\bshadow\.color)\b"
    ),
}

_LINE_COMMENT = re.compile(r"//[^\n]*")
_BLOCK_COMMENT = re.compile(r"/\*[\s\S]*?\*/")


def strip_comments(text: str) -> str:
    """Blank out comments (keeping newlines) so prose that names a pattern is not counted."""

    def blank(match: re.Match[str]) -> str:
        return re.sub(r"[^\n]", " ", match.group(0))

    text = _BLOCK_COMMENT.sub(blank, text)
    # Do not treat `//` inside a string literal ("https://...") as a comment opener.
    out_lines = []
    for line in text.split("\n"):
        in_string = False
        cut = None
        i = 0
        while i < len(line) - 1:
            ch = line[i]
            if ch == "\\" and in_string:
                i += 2
                continue
            if ch == '"':
                in_string = not in_string
            elif not in_string and line[i : i + 2] == "//":
                cut = i
                break
            i += 1
        out_lines.append(line if cut is None else line[:cut] + " " * (len(line) - cut))
    return "\n".join(out_lines)


def count_patterns(text: str) -> dict[str, list[int]]:
    """Pattern name -> 1-based line numbers of each occurrence."""
    code = strip_comments(text)
    hits: dict[str, list[int]] = {}
    for name, pattern in PATTERNS.items():
        lines = [code.count("\n", 0, m.start()) + 1 for m in pattern.finditer(code)]
        if lines:
            hits[name] = lines
    return hits


def in_scope(rel: str) -> bool:
    return rel.endswith(".kt") and not rel.startswith(COMPONENTS_DIR) and rel not in ALLOWED_FILES


def scan(root: Path) -> dict[str, dict[str, list[int]]]:
    result: dict[str, dict[str, list[int]]] = {}
    base = root / SCAN_ROOT
    for path in sorted(base.rglob("*.kt")):
        rel = path.relative_to(root).as_posix()
        if not in_scope(rel):
            continue
        hits = count_patterns(path.read_text(encoding="utf-8"))
        if hits:
            result[rel] = hits
    return result


def load_baseline(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return data.get("files", {})


def compare(found: dict[str, dict[str, list[int]]], baseline: dict[str, dict]) -> list[str]:
    problems: list[str] = []
    for rel in sorted(set(found) | set(baseline)):
        allowed = baseline.get(rel, {}).get("counts", {})
        hits = found.get(rel, {})
        for name in sorted(set(hits) | set(allowed)):
            have = len(hits.get(name, []))
            limit = int(allowed.get(name, 0))
            if have > limit:
                where = ", ".join(str(n) for n in hits[name])
                problems.append(
                    f"{rel}: {have} x {name} (baseline {limit}) at lines {where} - "
                    "use the raft primitives in components/ instead of hand-drawn chrome"
                )
            elif have < limit:
                problems.append(
                    f"{rel}: {name} dropped to {have} (baseline {limit}) - lower the baseline "
                    "(scripts/ci/check_raft_chrome.py --update-baseline) so the debt cannot grow back"
                )
    return problems


def write_baseline(path: Path, found: dict[str, dict[str, list[int]]], previous: dict[str, dict]) -> None:
    files = {}
    for rel in sorted(found):
        reason = previous.get(rel, {}).get("reason") or "TODO: why this file still hand-draws brutal chrome"
        files[rel] = {
            "reason": reason,
            "counts": {name: len(lines) for name, lines in sorted(found[rel].items())},
        }
    payload = {
        "comment": (
            "Ratchet baseline for scripts/ci/check_raft_chrome.py: brutal chrome still allowed per "
            "page file. Counts may only go down; remove a file once it reaches zero."
        ),
        "files": files,
    }
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def self_test() -> int:
    sample = "\n".join(
        [
            "fun Modifier.slockSquareCorners(): Modifier = this",
            "val a = Modifier.slockSquareCorners()",
            "// Modifier.border(SlockStroke.Default, SlockColor.BorderBlack) in a comment",
            "/* SlockShadow.Brutal in a block comment */",
            'val url = "https://example.com" // .slockSquareCorners()',
            "val b = Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)",
            "val c = Modifier.border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f), shape)",
            "val d = Modifier.border(SlockStroke.Thin, SlockColor.BorderHairline)",
            "val e = SlockShadow.BrutalSmall",
            "Box(",
            "    Modifier",
            "        .matchParentSize()",
            "        .offset(x = shadowOffset, y = shadowOffset)",
            "        .background(SlockColor.ShadowBlack)",
            ")",
            "Box(Modifier.offset(x = 2.dp, y = 0.dp)) { Text(\"x\") }",
            "Box(Modifier.offset(x = o, y = o).slockThemedCorners(r).background(theme.shadow.color))",
            "Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(if (on) SlockColor.ShadowBlack else Color.Transparent))",
            "Box(Modifier.size(s).offset(x = 5f, y = -5f).background(SlockColor.ShadowBlack, CircleShape))",
            "Box(Modifier.matchParentSize().offset(x = SlockStroke.Default, y = SlockStroke.Default).background(SlockColor.BorderBlack))",
        ]
    )
    hits = count_patterns(sample)
    expected = {
        "square-corners": [2],
        "ink-border": [6, 7],
        "brutal-shadow": [9],
        "offset-shadow": [13, 17, 18, 20],
    }
    if hits != expected:
        print(f"self-test: expected {expected}, got {hits}", file=sys.stderr)
        return 1
    found = {"a.kt": hits}
    exact = {"a.kt": {"counts": {k: len(v) for k, v in hits.items()}}}
    if compare(found, exact):
        print("self-test: exact baseline reported problems", file=sys.stderr)
        return 1
    tighter = {"a.kt": {"counts": {"square-corners": 1, "ink-border": 1, "brutal-shadow": 1, "offset-shadow": 4}}}
    over = compare(found, tighter)
    if len(over) != 1 or "ink-border" not in over[0]:
        print(f"self-test: growth over baseline not reported: {over}", file=sys.stderr)
        return 1
    looser = {"a.kt": {"counts": {"square-corners": 1, "ink-border": 3, "brutal-shadow": 1, "offset-shadow": 4}}}
    stale = compare(found, looser)
    if len(stale) != 1 or "dropped" not in stale[0]:
        print(f"self-test: stale baseline not reported: {stale}", file=sys.stderr)
        return 1
    if compare({}, {"gone.kt": {"counts": {"ink-border": 1}}}) == []:
        print("self-test: a file that reached zero must leave the baseline", file=sys.stderr)
        return 1
    if in_scope(COMPONENTS_DIR + "SlockRaftChrome.kt") or in_scope(APP + "AuthPages.kt"):
        print("self-test: components/ and allowlisted files must be out of scope", file=sys.stderr)
        return 1
    if not in_scope(APP + "MembersPage.kt"):
        print("self-test: pages must be in scope", file=sys.stderr)
        return 1
    print("check_raft_chrome self-test: ok")
    return 0


def main(argv: list[str]) -> int:
    known = {"--self-test", "--update-baseline", "--report"}
    unknown = [a for a in argv if a not in known]
    if unknown:
        print(f"usage: check_raft_chrome.py [{' | '.join(sorted(known))}] (unknown: {unknown})", file=sys.stderr)
        return 2
    if "--self-test" in argv:
        return self_test()
    found = scan(REPO_ROOT)
    baseline = load_baseline(BASELINE_PATH)
    if "--report" in argv:
        for rel, hits in sorted(found.items(), key=lambda kv: -sum(len(v) for v in kv[1].values())):
            print(f"{sum(len(v) for v in hits.values()):4d}  {rel}  " + ", ".join(f"{k}={len(v)}" for k, v in sorted(hits.items())))
        return 0
    if "--update-baseline" in argv:
        write_baseline(BASELINE_PATH, found, baseline)
        print(f"check_raft_chrome: baseline rewritten ({len(found)} files)")
        return 0
    problems = compare(found, baseline)
    if problems:
        print("check_raft_chrome: brutal page chrome outside components/:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    total = sum(sum(len(v) for v in hits.values()) for hits in found.values())
    print(f"check_raft_chrome: ok ({total} baselined occurrences in {len(found)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
