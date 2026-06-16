#!/usr/bin/env python3
"""Generate a Kuikly/OHOS performance attribution summary.

The script intentionally has no third-party dependencies so it can run on a
fresh developer machine. It accepts a directory produced by
collect_kuikly_performance_attribution.sh, or any directory containing one or
more of these files:

- profiler_report.json
- profiler_frames.jsonl
- hilog.txt
- diagnostics.txt
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


SLOW_FRAME_MS = 16.0
DEFAULT_MARKER_WINDOW_MS = 2000.0

MARKER_PATTERNS = (
    "SLOCK_PERF_MARKER",
    "SlockPerfScene",
    "SlockKuiklyPerfSnapshot",
    "SlockKuiklyPerf",
    "kuikly_perf_snapshot",
    "home_open",
    "thread_open",
    "thread_payload",
    "thread_refresh",
    "thread_first",
    "modal_show",
    "composer_focus",
    "composer_send",
    "lucide_batch",
    "realtime_batch",
    "cache_read",
    "cache_write",
)

DURATION_RE = re.compile(
    r"(?:duration|durationMs|cost|costMs|elapsed|elapsedMs|firstFramePaintCost|firstPaint)[=:]\s*([0-9]+(?:\.[0-9]+)?)",
    re.IGNORECASE,
)
TS_RE = re.compile(r"(?:ts|tsMs|timestamp|timestampMs)[=:]\s*([0-9]+(?:\.[0-9]+)?)", re.IGNORECASE)
PHASE_RE = re.compile(r"(?:phase|scene|sceneName|route|reason)[=:]\s*([A-Za-z0-9_.:/-]+)", re.IGNORECASE)


@dataclass
class ComponentSummary:
    name: str
    recomposition_count: int = 0
    total_ms: float = 0.0
    avg_ms: float = 0.0
    max_ms: float = 0.0
    no_scope: bool = False


@dataclass
class FrameEvent:
    name: str
    duration_ms: float


@dataclass
class SlowFrame:
    index: int
    timestamp_ms: float | None
    duration_ms: float
    events: list[FrameEvent]


@dataclass
class Marker:
    source: str
    line_no: int
    label: str
    timestamp_ms: float | None
    duration_ms: float | None
    raw: str


def load_json(path: Path) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {path}: {exc}") from exc


def first_file(root: Path, names: Iterable[str]) -> Path | None:
    wanted = set(names)
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.name in wanted:
            return path
    return None


def all_text_files(root: Path) -> list[Path]:
    candidates: list[Path] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        name = path.name.lower()
        if name.endswith(".txt") or name.endswith(".log"):
            candidates.append(path)
    return candidates


def as_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        if isinstance(value, bool):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def as_int(value: Any, default: int = 0) -> int:
    try:
        if value is None:
            return default
        if isinstance(value, bool):
            return default
        return int(float(value))
    except (TypeError, ValueError):
        return default


def iter_dicts(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from iter_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_dicts(child)


def component_name(item: dict[str, Any]) -> str | None:
    for key in (
        "componentName",
        "scopeName",
        "composableName",
        "name",
        "key",
        "label",
        "functionName",
    ):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def extract_components(report: Any | None) -> list[ComponentSummary]:
    if report is None:
        return []

    components: dict[str, ComponentSummary] = {}
    for item in iter_dicts(report):
        count = as_int(
            item.get("recompositionCount")
            or item.get("count")
            or item.get("recompositions")
            or item.get("recomposeCount")
        )
        if count <= 0:
            continue
        name = component_name(item)
        if not name:
            continue
        summary = components.setdefault(name, ComponentSummary(name=name))
        summary.recomposition_count += count
        summary.total_ms += as_float(
            item.get("totalDurationMs")
            or item.get("totalMs")
            or item.get("totalTimeMs")
            or item.get("durationMs")
        )
        summary.max_ms = max(
            summary.max_ms,
            as_float(item.get("maxDurationMs") or item.get("maxMs") or item.get("maxTimeMs")),
        )
        summary.no_scope = summary.no_scope or bool(item.get("noScope") or item.get("isNoScope"))

    for summary in components.values():
        if summary.recomposition_count > 0 and summary.total_ms > 0:
            summary.avg_ms = summary.total_ms / summary.recomposition_count

    return sorted(
        components.values(),
        key=lambda c: (-c.recomposition_count, -c.total_ms, c.name),
    )


def parse_frame_events(obj: dict[str, Any]) -> list[FrameEvent]:
    events: list[FrameEvent] = []
    raw_events = obj.get("events") or obj.get("recompositions") or obj.get("items") or []
    if not isinstance(raw_events, list):
        return events
    for event in raw_events:
        if not isinstance(event, dict):
            continue
        name = component_name(event) or str(event.get("type") or "event")
        duration = as_float(
            event.get("durationMs")
            or event.get("costMs")
            or event.get("elapsedMs")
            or event.get("timeMs")
        )
        if duration > 0:
            events.append(FrameEvent(name=name, duration_ms=duration))
    return sorted(events, key=lambda e: -e.duration_ms)


def extract_frame_duration(obj: dict[str, Any], events: list[FrameEvent]) -> float:
    duration = as_float(
        obj.get("durationMs")
        or obj.get("frameDurationMs")
        or obj.get("totalDurationMs")
        or obj.get("costMs")
        or obj.get("elapsedMs")
    )
    if duration > 0:
        return duration
    if events:
        return sum(event.duration_ms for event in events)
    return 0.0


def extract_timestamp(obj: dict[str, Any]) -> float | None:
    for key in ("timestampMs", "tsMs", "timeMs", "timestamp", "ts"):
        if key in obj:
            value = as_float(obj.get(key), default=math.nan)
            if not math.isnan(value):
                return value
    return None


def extract_slow_frames(path: Path | None, threshold_ms: float) -> list[SlowFrame]:
    if path is None or not path.exists():
        return []
    slow_frames: list[SlowFrame] = []
    for index, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(obj, dict):
            continue
        events = parse_frame_events(obj)
        duration = extract_frame_duration(obj, events)
        if duration >= threshold_ms:
            slow_frames.append(
                SlowFrame(
                    index=index,
                    timestamp_ms=extract_timestamp(obj),
                    duration_ms=duration,
                    events=events[:5],
                )
            )
    return sorted(slow_frames, key=lambda f: -f.duration_ms)


def short_line(line: str, max_len: int = 180) -> str:
    text = " ".join(line.strip().split())
    if len(text) <= max_len:
        return text
    return text[: max_len - 3] + "..."


def extract_marker_label(line: str) -> str:
    phase_match = PHASE_RE.search(line)
    if phase_match:
        return phase_match.group(1)
    for pattern in MARKER_PATTERNS:
        if pattern in line:
            return pattern
    return "marker"


def parse_line_float(regex: re.Pattern[str], line: str) -> float | None:
    match = regex.search(line)
    if not match:
        return None
    return as_float(match.group(1), default=math.nan)


def extract_markers(text_paths: list[Path]) -> list[Marker]:
    markers: list[Marker] = []
    for path in text_paths:
        for line_no, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            if not any(pattern in line for pattern in MARKER_PATTERNS):
                continue
            duration = parse_line_float(DURATION_RE, line)
            if duration is not None and math.isnan(duration):
                duration = None
            timestamp = parse_line_float(TS_RE, line)
            if timestamp is not None and math.isnan(timestamp):
                timestamp = None
            markers.append(
                Marker(
                    source=str(path),
                    line_no=line_no,
                    label=extract_marker_label(line),
                    timestamp_ms=timestamp,
                    duration_ms=duration,
                    raw=short_line(line),
                )
            )
    return markers


def nearest_marker(frame: SlowFrame, markers: list[Marker], window_ms: float) -> Marker | None:
    if frame.timestamp_ms is None:
        return None
    with_ts = [m for m in markers if m.timestamp_ms is not None]
    if not with_ts:
        return None
    marker = min(with_ts, key=lambda m: abs((m.timestamp_ms or 0) - frame.timestamp_ms))
    if abs((marker.timestamp_ms or 0) - frame.timestamp_ms) <= window_ms:
        return marker
    return None


def marker_alerts(markers: list[Marker], args: argparse.Namespace) -> list[str]:
    alerts: list[str] = []
    for marker in markers:
        if marker.duration_ms is None:
            continue
        label = marker.label.lower()
        threshold: float | None = None
        if "lucide" in label or "icon" in label:
            threshold = args.icon_batch_ms
        elif "realtime" in label:
            threshold = args.realtime_batch_ms
        elif "cache" in label:
            threshold = args.cache_ms
        elif "first_frame" in label or "firstframe" in label:
            threshold = args.thread_first_frame_ms
        if threshold is not None and marker.duration_ms > threshold:
            alerts.append(f"{marker.label} took {marker.duration_ms:.1f} ms over {threshold:.1f} ms")
    return alerts


def write_table(lines: list[str], headers: list[str], rows: list[list[str]]) -> None:
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join("---" for _ in headers) + " |")
    for row in rows:
        lines.append("| " + " | ".join(row) + " |")


def bool_text(value: bool) -> str:
    return "yes" if value else "no"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Input directory containing profiler/log artifacts.")
    parser.add_argument("--output", type=Path, help="Markdown output path. Defaults to stdout.")
    parser.add_argument("--slow-frame-ms", type=float, default=SLOW_FRAME_MS)
    parser.add_argument("--marker-window-ms", type=float, default=DEFAULT_MARKER_WINDOW_MS)
    parser.add_argument("--thread-first-frame-ms", type=float, default=1000.0)
    parser.add_argument("--icon-batch-ms", type=float, default=16.0)
    parser.add_argument("--realtime-batch-ms", type=float, default=16.0)
    parser.add_argument("--cache-ms", type=float, default=30.0)
    args = parser.parse_args()

    root = args.input
    report_path = first_file(root, ("profiler_report.json", "cache_profiler_report.json", "files_profiler_report.json"))
    frames_path = first_file(root, ("profiler_frames.jsonl", "cache_profiler_frames.jsonl", "files_profiler_frames.jsonl"))
    text_paths = all_text_files(root)

    report = load_json(report_path) if report_path else None
    components = extract_components(report)
    slow_frames = extract_slow_frames(frames_path, args.slow_frame_ms)
    markers = extract_markers(text_paths)

    lines: list[str] = []
    lines.append("# Kuikly Performance Attribution Summary")
    lines.append("")
    lines.append("## Sources")
    write_table(
        lines,
        ["source", "path", "available"],
        [
            ["profiler_report.json", str(report_path or ""), bool_text(report_path is not None)],
            ["profiler_frames.jsonl", str(frames_path or ""), bool_text(frames_path is not None)],
            ["text logs", f"{len(text_paths)} file(s)", bool_text(bool(text_paths))],
        ],
    )

    lines.append("")
    lines.append("## Alerts")
    alerts: list[str] = []
    if not markers:
        alerts.append("no business/performance markers found; slow frames will be unattributed")
    high_recompose = [c for c in components if c.recomposition_count > 50]
    if high_recompose:
        alerts.append(f"{len(high_recompose)} component(s) have recompositionCount > 50")
    if slow_frames:
        alerts.append(f"{len(slow_frames)} frame(s) >= {args.slow_frame_ms:.1f} ms")
    alerts.extend(marker_alerts(markers, args))
    if not alerts:
        alerts.append("no threshold alerts")
    for alert in alerts:
        lines.append(f"- {alert}")

    lines.append("")
    lines.append("## Top Recomposition Components")
    top_components = components[:20]
    rows = [
        [
            c.name.replace("|", "\\|"),
            str(c.recomposition_count),
            f"{c.total_ms:.1f}",
            f"{c.avg_ms:.2f}",
            f"{c.max_ms:.1f}",
            bool_text(c.no_scope),
        ]
        for c in top_components
    ]
    if rows:
        write_table(lines, ["component", "count", "totalMs", "avgMs", "maxMs", "noScope"], rows)
    else:
        lines.append("No recomposition component data found.")

    lines.append("")
    lines.append("## Components Over 50 Recompositions")
    over_50 = [c for c in components if c.recomposition_count > 50]
    if over_50:
        write_table(
            lines,
            ["component", "count", "totalMs"],
            [[c.name.replace("|", "\\|"), str(c.recomposition_count), f"{c.total_ms:.1f}"] for c in over_50],
        )
    else:
        lines.append("None.")

    lines.append("")
    lines.append("## Slow Frames")
    if slow_frames:
        rows = []
        for frame in slow_frames[:20]:
            top_events = ", ".join(f"{e.name}:{e.duration_ms:.1f}ms" for e in frame.events[:3]) or "n/a"
            marker = nearest_marker(frame, markers, args.marker_window_ms)
            rows.append(
                [
                    str(frame.index),
                    "" if frame.timestamp_ms is None else f"{frame.timestamp_ms:.1f}",
                    f"{frame.duration_ms:.1f}",
                    (marker.label if marker else "unattributed").replace("|", "\\|"),
                    top_events.replace("|", "\\|"),
                ]
            )
        write_table(lines, ["line", "tsMs", "durationMs", "nearestMarker", "topEvents"], rows)
    else:
        lines.append("No slow frames found.")

    lines.append("")
    lines.append("## Markers And Snapshots")
    if markers:
        rows = [
            [
                marker.source.replace("|", "\\|"),
                str(marker.line_no),
                marker.label.replace("|", "\\|"),
                "" if marker.timestamp_ms is None else f"{marker.timestamp_ms:.1f}",
                "" if marker.duration_ms is None else f"{marker.duration_ms:.1f}",
                marker.raw.replace("|", "\\|"),
            ]
            for marker in markers[:80]
        ]
        write_table(lines, ["source", "line", "label", "tsMs", "durationMs", "raw"], rows)
    else:
        lines.append("No marker or snapshot lines found.")

    lines.append("")
    lines.append("## Attribution Notes")
    if not markers:
        lines.append("- Current capture lacks standardized business markers.")
        lines.append("- Add `SLOCK_PERF_MARKER` start/end lines for route, modal, cache, realtime, composer, and icon batches.")
    else:
        unattributed_count = sum(1 for frame in slow_frames if nearest_marker(frame, markers, args.marker_window_ms) is None)
        lines.append(f"- Unattributed slow frames: {unattributed_count}.")
        lines.append(f"- Marker matching window: +/- {args.marker_window_ms:.0f} ms.")

    output = "\n".join(lines) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
