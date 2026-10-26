#!/usr/bin/env python3
"""Fail on ASCII edge spaces in the shared string resources (task #536, S8).

Android's resource compiler trims leading/trailing whitespace of an unquoted
<string> value, so `"I agree to the "` reaches the app as `"I agree to the"` and
`prefix + bold(name) + suffix` renders as `deleteVisual Serverand`. iOS keeps
the space, so the same fixture renders differently per platform. The repo's
precedent (auth_legal_notice_*) is the `&#160;` entity, which survives on every
target. This check has no allow-list on purpose: an edge space in a
resource is never what the author meant.

Usage: check_string_edge_spaces.py [--fix] [paths...]
  default paths: compose/shared/src/commonMain/composeResources/values*/strings.xml
  --fix rewrites ASCII edge spaces to &#160; in place (idempotent).
"""
from __future__ import annotations

import glob
import re
import sys
from pathlib import Path

DEFAULT_GLOB = "compose/shared/src/commonMain/composeResources/values*/strings.xml"
# Only <string> entries: as of 2026-09-03 the three MR files carry no <plurals> /
# <string-array> <item>s at all (LiBai counted 0), so this is a description of the
# current resource shape, not a judgement that <item> is exempt. aapt trims those
# the same way; extend ENTRY if such entries ever appear.
ENTRY = re.compile(r'(<string name="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)
NBSP = "&#160;"


def offenders(text: str):
    for m in ENTRY.finditer(text):
        value = m.group(3)
        if value.startswith(" ") or value.endswith(" "):
            yield m.group(2), value


def fix(text: str) -> str:
    def repl(m: re.Match) -> str:
        value = m.group(3)
        lead = len(value) - len(value.lstrip(" "))
        trail = len(value) - len(value.rstrip(" ")) if value.strip(" ") else 0
        core = value[lead : len(value) - trail] if trail else value[lead:]
        return f"{m.group(1)}{NBSP * lead}{core}{NBSP * trail}{m.group(4)}"

    return ENTRY.sub(repl, text)


def self_test() -> int:
    """The checker must turn red on a known offender and green after --fix logic."""
    sample = (
        '<resources>\n'
        '    <string name="ok">no edge</string>\n'
        '    <string name="nbsp_ok">already&#160;</string>\n'
        '    <string name="lead"> and</string>\n'
        '    <string name="trail">delete </string>\n'
        '    <string name="both">  x  </string>\n'
        '    <string name="blank">   </string>\n'
        '</resources>\n'
    )
    names = [n for n, _ in offenders(sample)]
    if names != ["lead", "trail", "both", "blank"]:
        print(f"self-test: expected 4 offenders, got {names}", file=sys.stderr)
        return 1
    fixed = fix(sample)
    if list(offenders(fixed)):
        print("self-test: --fix left offenders behind", file=sys.stderr)
        return 1
    for expect in ('>&#160;and<', '>delete&#160;<', '>&#160;&#160;x&#160;&#160;<', '>&#160;&#160;&#160;<', '>no edge<', '>already&#160;<'):
        if expect not in fixed:
            print(f"self-test: fixed text lacks {expect!r}", file=sys.stderr)
            return 1
    if fix(fixed) != fixed:
        print("self-test: --fix is not idempotent", file=sys.stderr)
        return 1
    # Mutation guard: a value with only interior spaces is NOT an offender.
    if list(offenders('<string name="i">a b</string>')):
        print("self-test: interior space misreported", file=sys.stderr)
        return 1
    print("check_string_edge_spaces self-test: ok")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    do_fix = "--fix" in argv
    paths = [a for a in argv if not a.startswith("--")] or sorted(glob.glob(DEFAULT_GLOB))
    if not paths:
        print(f"check_string_edge_spaces: no strings.xml matched {DEFAULT_GLOB}", file=sys.stderr)
        return 2  # a check that scans nothing must not pass
    missing = [p for p in paths if not Path(p).is_file()]
    if missing:
        print(f"check_string_edge_spaces: missing file(s): {missing}", file=sys.stderr)
        return 2  # a check that cannot read its input must not pass
    total = 0
    for p in paths:
        path = Path(p)
        text = path.read_text(encoding="utf-8")
        found = list(offenders(text))
        if found and do_fix:
            path.write_text(fix(text), encoding="utf-8")
            found_after = list(offenders(path.read_text(encoding="utf-8")))
            print(f"fixed {p}: {len(found)} -> {len(found_after)}")
            found = found_after
        for name, value in found:
            print(f"{p}: {name}: {value!r} has an ASCII edge space (use {NBSP})")
        total += len(found)
    print(f"check_string_edge_spaces: {total} offender(s) across {len(paths)} file(s)")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
