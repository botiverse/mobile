#!/usr/bin/env python3
"""Contract-first tests for collect_richtext_caps (task #441).

Run: python3 scripts/test_collect_richtext_caps.py
No third-party deps — plain asserts so it runs anywhere the report job runs.
"""

import json
import os
import tempfile

import collect_richtext_caps as c

V02 = (
    'SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true,"slockRefChrome":true,'
    '"reactionAtlas":false,"declaredDivergences":[]}'
)

failures = []


def check(name, cond):
    print(("ok   " if cond else "FAIL ") + name)
    if not cond:
        failures.append(name)


# --- parse_caps_line -------------------------------------------------------

def test_baseline():
    r = c.parse_caps_line(V02)
    check("baseline: caps parsed", r is not None)
    check("baseline: booleans verbatim", r["richTextCapabilities"] == {
        "inlineCodeChrome": True, "slockRefChrome": True, "reactionAtlas": False})
    check("baseline: divergences empty list", r["declaredDivergences"] == [])
    check("baseline: divergences not left in caps", "declaredDivergences" not in r["richTextCapabilities"])


def test_missing_marker_is_unknown():
    check("missing marker -> None (unknown, never default)", c.parse_caps_line("no marker here\nCASE_END") is None)
    check("empty text -> None", c.parse_caps_line("") is None)
    check("None text -> None", c.parse_caps_line(None) is None)


def test_missing_field_stays_absent():
    # reactionAtlas omitted entirely -> must NOT appear (unknown != false)
    line = 'SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true,"slockRefChrome":false}'
    r = c.parse_caps_line(line)
    check("omitted cap absent (not defaulted)", "reactionAtlas" not in r["richTextCapabilities"])
    check("omitted divergences -> [] on valid marker", r["declaredDivergences"] == [])


def test_divergences_payload():
    line = ('SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true,'
            '"declaredDivergences":[{"area":"inline-code","note":"ios raster"}]}')
    r = c.parse_caps_line(line)
    check("divergences array extracted", r["declaredDivergences"] == [{"area": "inline-code", "note": "ios raster"}])
    check("divergences pulled out of caps", "declaredDivergences" not in r["richTextCapabilities"])


def test_last_marker_wins():
    text = ('SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":false}\n'
            'some log\n'
            'SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true}\n')
    r = c.parse_caps_line(text)
    check("last marker wins (settled first-frame state)", r["richTextCapabilities"]["inlineCodeChrome"] is True)


def test_malformed_ignored():
    check("malformed JSON -> None", c.parse_caps_line('SLOCK_RICHTEXT_CAPS {not json}') is None)
    check("truncated object -> None", c.parse_caps_line('SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":tru') is None)
    # a good line after a malformed one is still collected
    text = 'SLOCK_RICHTEXT_CAPS {bad}\nSLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true}'
    r = c.parse_caps_line(text)
    check("valid marker after malformed still parsed", r is not None and r["richTextCapabilities"]["inlineCodeChrome"] is True)


def test_embedded_in_logcat_line():
    # android logcat prefixes each line with time/pid/tag
    line = '07-09 10:20:31.123  1234  1234 I SlockVisual: ' + V02 + '  '
    r = c.parse_caps_line(line)
    check("marker found inside prefixed logcat line", r is not None and r["richTextCapabilities"]["slockRefChrome"] is True)


def test_trailing_noise_after_object():
    line = 'SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true} rendered in 12ms'
    r = c.parse_caps_line(line)
    check("balanced-object parse survives trailing text", r is not None and r["richTextCapabilities"] == {"inlineCodeChrome": True})


# --- merge_into_metadata ---------------------------------------------------

def test_merge_writes_keys():
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "case.metadata.json")
        json.dump({"caseId": "x", "provider": "ios"}, open(p, "w"))
        changed = c.merge_into_metadata(p, c.parse_caps_line(V02))
        meta = json.load(open(p))
        check("merge returns True", changed is True)
        check("merge keeps existing keys", meta["caseId"] == "x")
        check("merge adds richTextCapabilities", meta["richTextCapabilities"]["inlineCodeChrome"] is True)
        check("merge adds declaredDivergences", meta["declaredDivergences"] == [])


def test_merge_none_writes_nothing():
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "case.metadata.json")
        json.dump({"caseId": "x"}, open(p, "w"))
        changed = c.merge_into_metadata(p, None)
        meta = json.load(open(p))
        check("no marker -> merge returns False", changed is False)
        check("no marker -> no caps key added (stays unknown)", "richTextCapabilities" not in meta)


def test_merge_missing_file():
    check("missing metadata file -> False", c.merge_into_metadata("/nonexistent/x.metadata.json", c.parse_caps_line(V02)) is False)


for fn in list(globals().values()):
    if callable(fn) and getattr(fn, "__name__", "").startswith("test_"):
        fn()

print()
if failures:
    print("%d FAILURE(S): %s" % (len(failures), ", ".join(failures)))
    raise SystemExit(1)
print("all richtext-caps collection tests passed")
