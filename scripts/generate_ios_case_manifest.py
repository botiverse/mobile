#!/usr/bin/env python3
"""Generate the iOS visual-capture case manifest from the android harness.

The android instrumentation file (KmpVisualScreenshotCaptureTest.kt) is the
single source of truth for caseId -> (pageName, pageData). The iOS capture job
relaunches the app once per case with SLOCK_VISUAL_FIXTURE_PAGE(_DATA) env
(#359), so it can only run cases whose fixture setup is fully expressible as
launch data:

  included  - the method calls captureKmpComponentCase with a literal caseId,
              a literal pageName, and a pageData built exclusively from
              literal .put(...) values (or no pageData at all).
  pending   - anything else: interaction steps (UiAutomation etc.), pageData
              built from variables/helpers, or custom capture flows. These are
              listed explicitly so the gap is visible, never silent.

slockVisualZeroSafeArea:true is injected into every case's pageData to match
what captureKmpComponentCase does harness-side (line ~1422).

Usage:
  scripts/generate_ios_case_manifest.py [--filter id1,id2] [--out manifest.json]
Prints a summary to stderr; writes JSON {cases:[...], pending:[...]} to --out
(default stdout).
"""

import argparse
import json
import re
import sys
from pathlib import Path

HARNESS = Path(__file__).resolve().parent.parent / (
    "compose/androidApp/src/androidTest/java/build/raft/app/visual/"
    "KmpVisualScreenshotCaptureTest.kt"
)

INTERACTION_MARKERS = re.compile(
    r"UiAutomation|uiDevice|performClick|executeShellCommand|wm size|injectInputEvent"
)

PUT_LITERAL = re.compile(
    r"\.put\(\s*\"(?P<key>[^\"]+)\"\s*,\s*"
    r"(?P<val>\"(?:[^\"\\]|\\.)*\"|true|false|-?\d+(?:\.\d+)?)\s*\)"
)
PUT_ANY = re.compile(r"\.put\(")


def parse_literal(raw: str):
    if raw == "true":
        return True
    if raw == "false":
        return False
    if raw.startswith('"'):
        return json.loads(raw)  # kotlin string literals are json-compatible here
    return json.loads(raw)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--filter", default="", help="comma-separated caseIds")
    ap.add_argument("--out", default="-", help="output path or - for stdout")
    args = ap.parse_args()
    wanted = {c.strip() for c in args.filter.split(",") if c.strip()}

    src = HARNESS.read_text()
    # chunks: [prefix, name1, body1, name2, body2, ...] (body runs to next fun)
    chunks = re.split(r"\bfun\s+(\w+)\s*\(", src)

    cases, pending = [], []
    for i in range(1, len(chunks) - 1, 2):
        method, body = chunks[i], chunks[i + 1]
        if "captureKmpComponentCase(" not in body:
            continue
        case_m = re.search(r'caseId\s*=\s*"([^"]+)"', body)
        page_m = re.search(r'pageName\s*=\s*"([^"]+)"', body)
        if not case_m:
            continue
        case_id = case_m.group(1)
        if wanted and case_id not in wanted:
            continue

        def defer(reason: str) -> None:
            pending.append({"caseId": case_id, "method": method, "reason": reason})

        if not page_m:
            defer("pageName not a literal")
            continue
        if INTERACTION_MARKERS.search(body):
            defer("interaction steps in method body")
            continue

        page_data = {}
        if "pageData" in body:
            literal_puts = PUT_LITERAL.findall(body)
            if len(PUT_ANY.findall(body)) != len(literal_puts):
                defer("pageData has non-literal .put values")
                continue
            for key, raw in literal_puts:
                page_data[key] = parse_literal(raw)

        page_data["pageName"] = page_m.group(1)
        page_data["slockVisualZeroSafeArea"] = True
        cases.append(
            {"caseId": case_id, "pageName": page_m.group(1), "pageData": page_data}
        )

    manifest = {"cases": cases, "pending": pending}
    text = json.dumps(manifest, indent=2, ensure_ascii=False)
    if args.out == "-":
        print(text)
    else:
        Path(args.out).write_text(text)

    print(
        f"ios manifest: {len(cases)} capturable, {len(pending)} pending",
        file=sys.stderr,
    )
    for p in pending:
        print(f"  pending: {p['caseId']} ({p['reason']})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
