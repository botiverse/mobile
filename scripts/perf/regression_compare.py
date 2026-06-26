"""Diff two `atrace_summary` outputs and surface what changed.

Owner pattern: "after a code change, did the perf regression go up or down?"
This compares two `*.summary.json` files (typically a `before` and an `after`
capture of the same scenario) and prints a delta report.

Usage:
  python3 scripts/perf/regression_compare.py \
      --baseline /path/to/before.summary.json \
      --current  /path/to/after.summary.json \
      --out /path/to/regression.md
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def diff_counters(before: dict, current: dict) -> list[tuple[str, int, int, int]]:
    """Return (key, before, current, delta) for every counter with non-zero delta."""
    out = []
    b = before.get("counters", {})
    c = current.get("counters", {})
    keys = set(b) | set(c)
    for k in sorted(keys):
        bv, cv = b.get(k, 0), c.get(k, 0)
        if cv - bv != 0:
            out.append((k, bv, cv, cv - bv))
    return out


def diff_topn(before: list, current: list, *, k: int = 5) -> list[tuple[str, int, int, int]]:
    """Compare top-N lists (entries = [(name, count)]).

    Output: rows where the same key changed, OR new entries appeared, OR
    old entries disappeared.
    """
    bmap = dict(before[:k])
    cmap = dict(current[:k])
    keys = set(bmap) | set(cmap)
    out = []
    for key in sorted(keys):
        bv, cv = bmap.get(key, 0), cmap.get(key, 0)
        if cv - bv != 0:
            out.append((key, bv, cv, cv - bv))
    return out


def diff_jank(before: dict, current: dict) -> dict[str, tuple[int, int, int]]:
    """Compare frame-pacing counters; returns dict key -> (before, current, delta)."""
    b, c = before.get("jank", {}), current.get("jank", {})
    out = {}
    for k in ("frame_count", "jank_count", "long_count", "very_long_count",
              "mean_delta_ms", "p50_delta_ms", "p95_delta_ms"):
        bv, cv = b.get(k, 0), c.get(k, 0)
        if cv != bv:
            out[k] = (bv, cv, cv - bv if isinstance(bv, (int, float)) else "n/a")
    return out


def diff_heap(before: dict, current: dict) -> dict[str, tuple]:
    b, c = before.get("heap", {}), current.get("heap", {})
    out = {}
    for k in ("min_kb", "max_kb", "delta_kb"):
        bv, cv = b.get(k, 0), c.get(k, 0)
        if cv != bv:
            out[k] = (bv, cv, cv - bv)
    return out


def render_markdown(
    baseline_path: Path,
    current_path: Path,
    deltas: dict,
) -> str:
    out = [
        "# perf regression report",
        "",
        f"- baseline: `{baseline_path}`",
        f"- current:  `{current_path}`",
        "",
        "## Counters (non-zero delta)",
        "",
        "| counter | baseline | current | delta |",
        "| --- | ---: | ---: | ---: |",
        *(
            f"| {k} | {bv} | {cv} | {dv:+d} |"
            for k, bv, cv, dv in deltas["counters"]
        ),
        "",
        "## Frame pacing",
        "",
        "| metric | baseline | current | delta |",
        "| --- | ---: | ---: | ---: |",
        *(
            f"| {k} | {bv} | {cv} | {dv} |"
            for k, (bv, cv, dv) in deltas["jank"].items()
        ),
        "",
        "## Heap (KB)",
        "",
        "| metric | baseline | current | delta |",
        "| --- | ---: | ---: | ---: |",
        *(
            f"| {k} | {bv} | {cv} | {dv:+d} |"
            for k, (bv, cv, dv) in deltas["heap"].items()
        ),
        "",
        "## Monitor contention top owners (top 5)",
        "",
        "| owner | baseline | current | delta |",
        "| --- | ---: | ---: | ---: |",
        *(
            f"| {k} | {bv} | {cv} | {dv:+d} |"
            for k, bv, cv, dv in deltas["monitor_owners"]
        ),
        "",
        "## Compose:recompose",
        "",
        f"baseline: **{deltas['compose_recompose'].get('baseline', 0)}**, current: **{deltas['compose_recompose'].get('current', 0)}**, "
        f"delta: **{deltas['compose_recompose'].get('delta', 0):+d}**",
        "",
        "## Recomposer:animation",
        "",
        f"baseline: **{deltas['recomposer_animation'].get('baseline', 0)}**, current: **{deltas['recomposer_animation'].get('current', 0)}**, "
        f"delta: **{deltas['recomposer_animation'].get('delta', 0):+d}**",
        "",
    ]
    return "\n".join(out)


def compute_deltas(before: dict, current: dict) -> dict:
    bc, cc = before.get("counters", {}), current.get("counters", {})
    return {
        "counters": diff_counters(before, current),
        "jank": diff_jank(before, current),
        "heap": diff_heap(before, current),
        "monitor_owners": diff_topn(
            before.get("monitor_contention", {}).get("top_owners", []),
            current.get("monitor_contention", {}).get("top_owners", []),
        ),
        "compose_recompose": {
            "baseline": bc.get("compose_recompose", 0),
            "current": cc.get("compose_recompose", 0),
            "delta": cc.get("compose_recompose", 0) - bc.get("compose_recompose", 0),
        },
        "recomposer_animation": {
            "baseline": bc.get("recomposer_animation", 0),
            "current": cc.get("recomposer_animation", 0),
            "delta": cc.get("recomposer_animation", 0) - bc.get("recomposer_animation", 0),
        },
    }


def main() -> int:
    p = argparse.ArgumentParser(description="Diff two atrace summaries.")
    p.add_argument("--baseline", required=True)
    p.add_argument("--current", required=True)
    p.add_argument("--out", default=None,
                   help="Optional output markdown path. Default: stdout.")
    args = p.parse_args()

    baseline = load(Path(args.baseline))
    current = load(Path(args.current))
    deltas = compute_deltas(baseline, current)
    md = render_markdown(Path(args.baseline), Path(args.current), deltas)
    if args.out:
        Path(args.out).write_text(md, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(md)
    return 0


if __name__ == "__main__":
    sys.exit(main())