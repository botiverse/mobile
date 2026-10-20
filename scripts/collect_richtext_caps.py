#!/usr/bin/env python3
"""Collect the SLOCK_RICHTEXT_CAPS marker into per-case visual metadata.

task #441 (unified renderer Phase 3c, collection side). The shared render
contract exposes `raftRichTextCapsLine()`; each native render host prints ONE
line, after the first frame, of the form:

    SLOCK_RICHTEXT_CAPS {"inlineCodeChrome":true,"slockRefChrome":true,"reactionAtlas":false,"declaredDivergences":[...]}

This module is the *collection* half: it greps that line out of a native log
(iOS per-case launch.log, android shard logcat segmented by case) and merges it
into the case's `<caseId>.metadata.json` under two keys the site reads:

    "richTextCapabilities": { "inlineCodeChrome": true, ... }   # factual booleans only
    "declaredDivergences":  [ ... ]                             # array, pulled out of the marker

Contract rules (locked v0.2, mirror the emit side — see #429/#430):
  * The capability fields are FACTUAL booleans. A missing marker means the caps
    are UNKNOWN: we write NOTHING (no key at all). We never synthesise a default
    (never `true`, never `false`) — absence on the site renders as unknown.
  * If more than one marker line is present (e.g. re-render), the LAST one wins:
    it reflects the settled first-frame-complete state.
  * `declaredDivergences` is optional in the payload; absent -> [] is written
    only when a valid marker exists (so it stays distinct from "unknown").
  * A malformed / non-JSON payload is ignored (treated as no marker) rather than
    crashing the report pipeline.

The field family is intentionally open (svgDecode etc. may join later): whatever
boolean/JSON fields the marker carries besides `declaredDivergences` flow through
verbatim into `richTextCapabilities`, so new capabilities need no change here.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys

MARKER_PREFIX = "SLOCK_RICHTEXT_CAPS "
DIVERGENCES_KEY = "declaredDivergences"


def parse_caps_line(text):
    """Extract (richTextCapabilities, declaredDivergences) from log ``text``.

    Returns a dict ``{"richTextCapabilities": {...}, "declaredDivergences": [...]}``
    for the LAST well-formed marker line, or ``None`` if no valid marker is found
    (missing marker == unknown, never a default).
    """
    if not text:
        return None
    found = None
    for line in text.splitlines():
        idx = line.find(MARKER_PREFIX)
        if idx == -1:
            continue
        payload = line[idx + len(MARKER_PREFIX):].strip()
        obj = _loads_leading_object(payload)
        if not isinstance(obj, dict):
            # malformed / truncated marker -> ignore, keep any earlier valid one
            continue
        found = obj  # last valid marker wins
    if found is None:
        return None
    divergences = found.get(DIVERGENCES_KEY, [])
    if not isinstance(divergences, list):
        divergences = []
    caps = {k: v for k, v in found.items() if k != DIVERGENCES_KEY}
    return {"richTextCapabilities": caps, DIVERGENCES_KEY: divergences}


def _loads_leading_object(payload):
    """json.loads the leading JSON object in ``payload``.

    The marker is emitted as a single line, but a log writer may append trailing
    text after the object. Try a plain parse first, then fall back to matching the
    balanced leading ``{...}`` so trailing noise doesn't defeat collection.
    """
    try:
        return json.loads(payload)
    except (ValueError, TypeError):
        pass
    if not payload.startswith("{"):
        return None
    depth = 0
    in_str = False
    esc = False
    for i, ch in enumerate(payload):
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(payload[: i + 1])
                except (ValueError, TypeError):
                    return None
    return None


def merge_into_metadata(metadata_path, parsed):
    """Merge ``parsed`` (from :func:`parse_caps_line`) into a metadata JSON file.

    Returns True if the file was updated, False otherwise (no marker, or file
    missing). A ``None`` ``parsed`` writes nothing — unknown stays unknown.
    """
    if parsed is None:
        return False
    if not os.path.isfile(metadata_path):
        return False
    with open(metadata_path) as f:
        meta = json.load(f)
    meta["richTextCapabilities"] = parsed["richTextCapabilities"]
    meta[DIVERGENCES_KEY] = parsed[DIVERGENCES_KEY]
    with open(metadata_path, "w") as f:
        json.dump(meta, f, indent=2)
    return True


def collect_from_log(metadata_path, log_path):
    """Read ``log_path``, parse the marker, merge into ``metadata_path``."""
    if not os.path.isfile(log_path):
        return False
    with open(log_path, errors="replace") as f:
        text = f.read()
    return merge_into_metadata(metadata_path, parse_caps_line(text))


def _main(argv=None):
    ap = argparse.ArgumentParser(description="Merge SLOCK_RICHTEXT_CAPS marker into per-case metadata")
    ap.add_argument("--metadata", help="single <caseId>.metadata.json to update")
    ap.add_argument("--log", help="native log file to grep the marker from (pairs with --metadata)")
    ap.add_argument("--metadata-dir", help="dir of *.metadata.json (batch mode)")
    ap.add_argument("--log-for", help="printf-style path with %%s for caseId, e.g. logs/%%s.launch.log")
    args = ap.parse_args(argv)

    updated = 0
    if args.metadata and args.log:
        if collect_from_log(args.metadata, args.log):
            updated += 1
    elif args.metadata_dir and args.log_for:
        for meta in sorted(glob.glob(os.path.join(args.metadata_dir, "*.metadata.json"))):
            case_id = os.path.basename(meta)[: -len(".metadata.json")]
            log_path = args.log_for % case_id
            if collect_from_log(meta, log_path):
                updated += 1
    else:
        ap.error("provide either --metadata + --log, or --metadata-dir + --log-for")
    print("richtext caps merged into %d case(s)" % updated)
    return 0


if __name__ == "__main__":
    sys.exit(_main())
