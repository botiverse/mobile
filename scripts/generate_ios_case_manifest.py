#!/usr/bin/env python3
"""Generate the iOS visual-capture case manifest from the android harness.

The android instrumentation file (KmpVisualScreenshotCaptureTest.kt) is the
single source of truth for caseId -> (pageName, pageData). The iOS capture job
relaunches the app once per case with SLOCK_VISUAL_FIXTURE_PAGE(_DATA) env
(#359), so it can only run cases whose fixture setup is fully expressible as
launch data.

v2 (task #418 follow-up): v1 only parsed @Test bodies that call
captureKmpComponentCase directly and SILENTLY SKIPPED tests going through
helper wrappers (captureKmpSettingsPageCase etc.) — 24 react-paired cases
never reached the manifest while the summary claimed "2 pending". v2 adds:

  * helper templates for wrappers whose pageData is trivially reconstructable
    (currently captureKmpSettingsPageCase);
  * a COMPLETENESS INVARIANT: every `caseId = "..."` / helper("caseId", ...)
    string literal found in the file MUST end up either in `cases` or in
    `pending` (with a reason). The script fails loudly if any id falls through,
    so a new helper pattern can never silently shrink coverage again.

Cases that stay pending, with reasons:
  * interaction steps in the method body (UiAutomation etc.);
  * pageData built from non-literal values with no known helper template
    (agent-detail / create-agent dialog build large nested payloads — the
    honest fix is a harness-emitted manifest, tracked separately);
  * full-height cases (display-grow via `wm size` has no iOS equivalent yet);
  * non-literal pageName.

slockVisualZeroSafeArea:true is injected into every case's pageData to match
what captureKmpComponentCase does harness-side.

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
CASE_ID_LITERAL = re.compile(r'"((?:components|screens|library|auth)\.[a-z0-9./_-]+)"')

# Helper templates: wrapper name -> how to rebuild (pageName, pageData) from
# its literal string arguments (after the caseId argument). Only add entries
# whose reconstruction is trivially faithful to the Kotlin helper body.
HELPER_TEMPLATES = {
    # captureKmpSettingsPageCase(caseId, variant) ->
    #   pageName compose_settings_page_visual, pageData {variant}
    "captureKmpSettingsPageCase": {
        "pageName": "compose_settings_page_visual",
        "arg_keys": ["variant"],
    },
}

# Helpers we recognise but deliberately leave pending, with the reason.
PENDING_HELPERS = {
    "captureKmpFullHeightSettingsCase": "full-height (display-grow) has no iOS equivalent yet",
    "captureMembersAgentDetailScreen": "complex helper pageData (memberDetailVisualPageData); needs harness-emitted manifest",
    "captureKmpHomeCase": "home-case helper with builder pageData; needs harness-emitted manifest",
}


def parse_literal(raw: str):
    if raw == "true":
        return True
    if raw == "false":
        return False
    return json.loads(raw)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--filter", default="", help="comma-separated caseIds")
    ap.add_argument("--out", default="-", help="output path or - for stdout")
    args = ap.parse_args()
    wanted = {c.strip() for c in args.filter.split(",") if c.strip()}

    src = HARNESS.read_text()
    chunks = re.split(r"\bfun\s+(\w+)\s*\(", src)

    # every caseId string literal in the file (fixture ids share this shape)
    all_ids = set(CASE_ID_LITERAL.findall(src))

    cases, pending, seen = [], [], set()

    def emit(case_id, page_name, page_data):
        page_data = dict(page_data)
        page_data["pageName"] = page_name
        page_data["slockVisualZeroSafeArea"] = True
        cases.append(
            {"caseId": case_id, "pageName": page_name, "pageData": page_data}
        )
        seen.add(case_id)

    def defer(case_id, method, reason):
        pending.append({"caseId": case_id, "method": method, "reason": reason})
        seen.add(case_id)

    for i in range(1, len(chunks) - 1, 2):
        method, body = chunks[i], chunks[i + 1]
        if method in HELPER_TEMPLATES or method in PENDING_HELPERS:
            continue  # helper definitions themselves are not cases

        # -- direct captureKmpComponentCase calls -------------------------
        if "captureKmpComponentCase(" in body:
            case_m = re.search(r'caseId\s*=\s*"([^"]+)"', body)
            page_m = re.search(r'pageName\s*=\s*"([^"]+)"', body)
            if not case_m:
                continue
            case_id = case_m.group(1)
            if not page_m:
                defer(case_id, method, "pageName not a literal")
                continue
            if INTERACTION_MARKERS.search(body):
                defer(case_id, method, "interaction steps in method body")
                continue
            page_data = {}
            if "pageData" in body:
                literal_puts = PUT_LITERAL.findall(body)
                if len(PUT_ANY.findall(body)) != len(literal_puts):
                    defer(case_id, method, "pageData has non-literal .put values")
                    continue
                for key, raw in literal_puts:
                    page_data[key] = parse_literal(raw)
            emit(case_id, page_m.group(1), page_data)
            continue

        # -- helper-wrapped calls ------------------------------------------
        for helper, template in HELPER_TEMPLATES.items():
            for call in re.finditer(
                helper + r"\(\s*\"([^\"]+)\"\s*(?:,\s*\"([^\"]+)\"\s*)*\)", body
            ):
                arg_strings = re.findall(r'"([^"]+)"', call.group(0))
                case_id, rest = arg_strings[0], arg_strings[1:]
                if len(rest) != len(template["arg_keys"]):
                    defer(case_id, method, f"{helper} arg count mismatch")
                    continue
                emit(case_id, template["pageName"], dict(zip(template["arg_keys"], rest)))
        for helper, reason in PENDING_HELPERS.items():
            if helper + "(" in body:
                for m in re.finditer(helper + r"\(\s*(?:caseId\s*=\s*)?\"([^\"]+)\"", body):
                    defer(m.group(1), method, reason)

    # ---- completeness invariant: no silent gaps ----------------------------
    unaccounted = sorted(all_ids - seen)
    for case_id in unaccounted:
        pending.append(
            {
                "caseId": case_id,
                "method": None,
                "reason": "UNRESOLVED — literal exists in harness but no parser/template matched (fix the generator!)",
            }
        )

    if wanted:
        cases = [c for c in cases if c["caseId"] in wanted]
        pending = [p for p in pending if p["caseId"] in wanted]

    manifest = {"cases": cases, "pending": pending}
    text = json.dumps(manifest, indent=2, ensure_ascii=False)
    if args.out == "-":
        print(text)
    else:
        Path(args.out).write_text(text)

    print(
        f"ios manifest: {len(cases)} capturable, {len(pending)} pending"
        + (f", {len(unaccounted)} UNRESOLVED (generator gap!)" if unaccounted else ""),
        file=sys.stderr,
    )
    for p in pending:
        print(f"  pending: {p['caseId']} ({p['reason']})", file=sys.stderr)
    # unresolved ids are a generator bug — fail loudly rather than under-report
    if unaccounted and not wanted:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
