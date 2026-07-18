"""Parse full-text atrace (Systrace text format) into a Markdown report.

The kind of atrace we get from `adb shell atrace ... -o /data/local/tmp/...` is
the legacy text format (TRACE:\\n# tracer: nop\\n#\\n... followed by per-event
lines). Each line has the form:

    <task-name>-<pid> (<tgid>) [cpu] flags ts: tracing_mark_write: <event>

We extract these signals (the ones we needed for the 2026-06-26 perf investigations):

  - Choreographer#doFrame                  frame delivery
  - doFrame                                (lower-level drawFrame wrapper)
  - monitor contention with owner <thread> lock-blocking sites
  - VerifyClass                            ART class verification
  - Compose:recompose / Recomposer:animation
                                          Compose runtime events
  - Choreographer!                         Skipped-frame markers (Android <14)
  - Heap size (KB)                         running heap footprint

Outputs:
  - <input>.summary.json    structured counters
  - <input>.summary.md      human-readable report

Usage:
  python3 scripts/perf/atrace_summary.py \
      --input artifacts/perf-send-2026-06-26/atrace.txt \
      --out-dir artifacts/perf-send-2026-06-26/ \
      --package build.raft.app

  --package restricts frame-pacing (Choreographer#doFrame) to the target app
  process; atrace also records systemui/launcher frames whose pacing is not
  the app's. Without --package all processes are counted (legacy behavior).
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


# Regex patterns anchored to the atrace text format. Tuned against traces
# from emulator-5554 (Pixel 9a Android 16) and emulator-5560.

# Single sample line: <task>-<pid> (<tgid>) [cpu] flags ts: tracing_mark_write: |TYPE|<tgid>|<event>
LINE_RE = re.compile(
    r"^\s+(?P<task>[^()\s]+)-(?P<tid>\d+)\s+\(\s*(?P<tgid>\d+)\)\s+\[(?P<cpu>\d+)\]\s+(?P<flags>[^:]+)\s+(?P<ts>\d+\.\d+):\s+tracing_mark_write:\s+(?P<event>.*)$"
)

EVENTS_OF_INTEREST = {
    "doframe": r"^Choreographer#doFrame ",
    "frame_pending": r"^notifyFramePending",
    "drawframe": r"^DrawFrames ",
    "heap_size": r"^Heap size \(KB\)\|\d+",
    "monitor_contention": r"^monitor contention with owner (\S+)",
    "verifyclass": r"^VerifyClass (\S+)",
    "compose_recompose": r"^Compose:recompose",
    "recomposer_animation": r"^Recomposer:animation",
    "snapshot_apply": r"^.*Snapshot.*apply",
    "sched_switch": r"^sched_switch:",
}


def parse(input_path: Path, package: str | None = None) -> dict[str, Any]:
    counters: dict[str, int] = {
        "total_lines": 0,
        "doframe": 0,
        "frame_pending": 0,
        "drawframe": 0,
        "heap_size": 0,
        "monitor_contention": 0,
        "verifyclass": 0,
        "compose_recompose": 0,
        "recomposer_animation": 0,
        "snapshot_apply": 0,
        "sched_switch": 0,
    }
    # Domain-specific counters
    heap_samples: list[int] = []
    contention_by_lock: Counter = Counter()
    contention_by_owner: Counter = Counter()
    contention_by_blocking_site: Counter = Counter()
    verifyclass_by_pkg: Counter = Counter()
    doframe_times: list[float] = []
    skipped_frames_marker = 0
    # Long-tail monitor-contention durations: we can't directly read durations
    # from atrace, but we *can* count how many times each owner appeared.
    # This is the proxy metric the perf investigations used.

    time_first: float | None = None
    time_last: float | None = None
    trace_seconds: float = 0.0

    for raw in input_path.read_text(errors="replace").splitlines():
        m = LINE_RE.match(raw)
        if not m:
            continue
        counters["total_lines"] += 1
        ts = float(m.group("ts"))
        if time_first is None:
            time_first = ts
        time_last = ts
        event = m.group("event")

        # Events in atrace have a leading "<TYPE>|<tgid>|" that LINE_RE captured
        # inside the event group. Strip it before applying EVENTS_OF_INTEREST so the
        # matchers (which assume a clean event payload) work.
        payload = event
        m_payload = re.match(r"^[A-Z]\|\d+\|(.*)$", event)
        if m_payload:
            payload = m_payload.group(1)

        for key, pat in EVENTS_OF_INTEREST.items():
            mm = re.match(pat, payload)
            if not mm:
                continue
            if key == "doframe" and package and m.group("task") != package:
                # atrace records Choreographer#doFrame for every process on the
                # device (systemui, nexuslauncher, ...). When --package is
                # given, only pace the app under test.
                break
            counters[key] += 1
            if key == "heap_size":
                # Heap events have format "Heap size (KB)|<int>"; capture the int.
                # Extract the KB value from "Heap size (KB)|<int>"
                kb_match = re.search(r"\|(\d+)\s*$", event)
                if kb_match:
                    heap_samples.append(int(kb_match.group(1)))
            elif key == "monitor_contention":
                owner = mm.group(1)
                contention_by_owner[owner] += 1
                # Lock site is the "at <stack>" line; block site is the next
                # "blocking from <stack>" line. Both contain Java stack frames.
                # Pull the second Java frame (first is "at <method>") for grouping.
                stack_match = re.search(
                    r"at\s+([\w.$<>]+)\s*\(",
                    payload,
                )
                if stack_match:
                    contention_by_blocking_site[stack_match.group(1)] += 1
                # First space-separated token after "owner" is the owner thread name.
                # (Already captured.)
                # Pull the lock name from "blocking from ...(<ClassName>)"
                lock_match = re.search(r"blocking from\s+[\w.]+\s+[\w.$]+\(([^)]+)\)", payload)
                if lock_match:
                    contention_by_lock[lock_match.group(1)] += 1
            elif key == "verifyclass":
                pkg_match = re.match(r"([\w.]+)", mm.group(1))
                if pkg_match:
                    verifyclass_by_pkg[pkg_match.group(1).split(".")[0]] += 1
            elif key == "doframe":
                doframe_times.append(ts)
            elif key == "recomposer_animation":
                # No payload.
                pass
            elif key == "compose_recompose":
                # No payload.
                pass
            break  # one event per line

    if time_first is not None and time_last is not None:
        trace_seconds = max(time_last - time_first, 0.0)

    # Skipped frames: doFrame interval >16.67ms (>60Hz budget) is "jank";
    # >33.33ms is "long frame"; >50ms is "very long".
    # atrace timestamps are SECONDS (e.g. 13322.897869); convert deltas to ms
    # before comparing against the millisecond thresholds below.
    doframe_deltas: list[float] = []
    if len(doframe_times) > 1:
        s = sorted(doframe_times)
        doframe_deltas = [(s[i + 1] - s[i]) * 1000.0 for i in range(len(s) - 1)]

    jank_threshold = 16.67
    long_threshold = 33.33
    very_long_threshold = 50.0

    jank_count = sum(1 for d in doframe_deltas if d > jank_threshold)
    long_count = sum(1 for d in doframe_deltas if d > long_threshold)
    very_long_count = sum(1 for d in doframe_deltas if d > very_long_threshold)
    mean_delta = statistics.fmean(doframe_deltas) if doframe_deltas else 0.0
    p50 = statistics.median(doframe_deltas) if doframe_deltas else 0.0
    p95 = (statistics.quantiles(doframe_deltas, n=20)[18]
           if len(doframe_deltas) >= 20 else (max(doframe_deltas) if doframe_deltas else 0.0))

    summary = {
        "input_path": str(input_path),
        "trace_seconds": trace_seconds,
        "counters": counters,
        "jank": {
            "threshold_ms": jank_threshold,
            "doframe_scope": package or "all-processes",
            "frame_count": len(doframe_times),
            "jank_count": jank_count,
            "long_count": long_count,
            "very_long_count": very_long_count,
            "mean_delta_ms": round(mean_delta, 3),
            "p50_delta_ms": round(p50, 3),
            "p95_delta_ms": round(p95, 3),
        },
        "heap": {
            "samples": len(heap_samples),
            "min_kb": min(heap_samples) if heap_samples else 0,
            "max_kb": max(heap_samples) if heap_samples else 0,
            "delta_kb": (max(heap_samples) - min(heap_samples)) if heap_samples else 0,
        },
        "monitor_contention": {
            "total": counters["monitor_contention"],
            "top_owners": contention_by_owner.most_common(10),
            "top_locks": contention_by_lock.most_common(10),
            "top_blocking_sites": contention_by_blocking_site.most_common(10),
        },
        "verifyclass": {
            "total": counters["verifyclass"],
            "top_packages": verifyclass_by_pkg.most_common(10),
        },
    }
    return summary


def render_markdown(summary: dict[str, Any]) -> str:
    c = summary["counters"]
    j = summary["jank"]
    h = summary["heap"]
    m = summary["monitor_contention"]
    v = summary["verifyclass"]

    def table(rows: list[tuple[str, Any]]) -> str:
        return "\n".join(f"| {k} | {v} |" for k, v in rows)

    out = [
        f"# atrace summary — `{Path(summary['input_path']).name}`",
        "",
        f"- trace window: **{summary['trace_seconds']:.2f}s**",
        f"- total matched lines: **{c['total_lines']:,}**",
        "",
        "## Frame pacing",
        "",
        f"- doFrame scope: **{j['doframe_scope']}**",
        "",
        table([
            ("frame count", f"{j['frame_count']:,}"),
            ("jank frames (>16.67ms)", j["jank_count"]),
            ("long frames (>33.33ms)", j["long_count"]),
            ("very long frames (>50ms)", j["very_long_count"]),
            ("mean delta (ms)", j["mean_delta_ms"]),
            ("p50 delta (ms)", j["p50_delta_ms"]),
            ("p95 delta (ms)", j["p95_delta_ms"]),
        ]),
        "",
        "## Heap",
        "",
        table([
            ("samples", h["samples"]),
            ("min (KB)", h["min_kb"]),
            ("max (KB)", h["max_kb"]),
            ("delta (KB)", h["delta_kb"]),
        ]),
        "",
        "## Compose / Kuikly events",
        "",
        table([
            ("Compose:recompose", c["compose_recompose"]),
            ("Recomposer:animation", c["recomposer_animation"]),
            ("Snapshot apply paths", c["snapshot_apply"]),
        ]),
        "",
        "## Monitor contention",
        "",
        f"total: **{m['total']}**",
        "",
        "### Top lock owners",
        "",
        "| owner | count |",
        "| --- | --- |",
        *table([(k, v) for k, v in m["top_owners"]]).splitlines(),
        "",
        "### Top lock targets (caller block sites)",
        "",
        "| caller | count |",
        "| --- | --- |",
        *table([(k, v) for k, v in m["top_blocking_sites"]]).splitlines(),
        "",
        "### Top lock objects",
        "",
        "| lock | count |",
        "| --- | --- |",
        *table([(k, v) for k, v in m["top_locks"]]).splitlines(),
        "",
        "## ART VerifyClass",
        "",
        f"total: **{v['total']}**",
        "",
        "| package | count |",
        "| --- | --- |",
        *table([(k, v) for k, v in v["top_packages"]]).splitlines(),
        "",
    ]
    return "\n".join(out)


def main() -> int:
    p = argparse.ArgumentParser(description="Parse atrace text into a Markdown report.")
    p.add_argument("--input", required=True, help="Path to full-text atrace dump.")
    p.add_argument("--out-dir", default=None,
                   help="Where to write summary.json + summary.md. Default: <input>.dir")
    p.add_argument("--package", default=None,
                   help="Restrict Choreographer#doFrame pacing to this app package "
                        "(e.g. build.raft.app). Default: count all processes.")
    args = p.parse_args()

    in_path = Path(args.input)
    if not in_path.exists():
        print(f"input not found: {in_path}", file=sys.stderr)
        return 2
    out_dir = Path(args.out_dir) if args.out_dir else in_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    summary = parse(in_path, package=args.package)
    (out_dir / f"{in_path.stem}.summary.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8",
    )
    (out_dir / f"{in_path.stem}.summary.md").write_text(
        render_markdown(summary), encoding="utf-8",
    )
    print(f"wrote {out_dir / (in_path.stem + '.summary.json')}")
    print(f"wrote {out_dir / (in_path.stem + '.summary.md')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())