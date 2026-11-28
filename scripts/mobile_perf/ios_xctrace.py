"""iOS ``xcrun xctrace`` command builders and pure-stdlib XML export parsers.

Supports recording and exporting ``Animation Hitches``, ``Time Profiler``,
``Metal System Trace``, and ``App Launch`` traces, then parsing the exported
XML (including Apple's ``id``/``ref`` deduplication) plus signpost / app
diagnostics logs into validated ``scopedb.perf.mobile_runs`` and
``scopedb.perf.mobile_diagnose_runs`` rows.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import subprocess
import xml.etree.ElementTree as ET
from collections import defaultdict
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from .ab_verdict import is_thermal_throttled
from .scenarios import DEFAULT_FIXTURE_TAG
from .schema import (
    SEGMENT_UNIT_SUFFIXES,
    TOOL_VERSION,
    make_diagnose_row,
    make_run_row,
    utc_now_iso,
    validate_diagnose_row,
    validate_run_row,
)

XCTRACE_TEMPLATES: tuple[str, ...] = (
    "Animation Hitches",
    "Time Profiler",
    "Metal System Trace",
    "App Launch",
)

XPATH_HITCHES = '/trace-toc/run[@number="1"]/data/table[@schema="hitches"]'
XPATH_TIME_PROFILE = '/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]'
XPATH_THERMAL_STATE = '/trace-toc/run[@number="1"]/data/table[@schema="thermal-state"]'
XPATH_SIGNPOSTS = '/trace-toc/run[@number="1"]/data/table[@schema="os-signpost-interval"]'
XPATH_METAL_GPU = '/trace-toc/run[@number="1"]/data/table[@schema="metal-gpu-intervals"]'


# ---------------------------------------------------------------------------
# xcrun xctrace command builders & runners
# ---------------------------------------------------------------------------


def build_xctrace_record_command(
    template: str,
    output_trace: str | Path,
    *,
    device: str | None = None,
    launch_app: str | None = None,
    attach_target: str | None = None,
    time_limit_s: int | None = 10,
    launch_args: Sequence[str] = (),
) -> list[str]:
    """Build an ``xcrun xctrace record`` argv list."""
    if not template or not template.strip():
        raise ValueError("template must be a non-empty string")
    if launch_app and attach_target:
        raise ValueError("Pass either launch_app or attach_target, not both")

    cmd = ["xcrun", "xctrace", "record", "--template", template, "--output", str(output_trace)]
    if device:
        cmd.extend(["--device", device])
    if time_limit_s is not None:
        if time_limit_s <= 0:
            raise ValueError(f"time_limit_s must be > 0, got {time_limit_s!r}")
        cmd.extend(["--time-limit", f"{int(time_limit_s)}s"])
    if attach_target:
        cmd.extend(["--attach", attach_target])
    elif launch_app:
        cmd.extend(["--launch", "--", launch_app, *launch_args])
    return cmd


def build_xctrace_toc_command(trace_path: str | Path) -> list[str]:
    """Build an ``xcrun xctrace export --toc`` argv list."""
    return ["xcrun", "xctrace", "export", "--input", str(trace_path), "--toc"]


def build_xctrace_export_command(
    trace_path: str | Path,
    xpath: str,
    *,
    output_path: str | Path | None = None,
) -> list[str]:
    """Build an ``xcrun xctrace export --xpath`` argv list."""
    cmd = ["xcrun", "xctrace", "export", "--input", str(trace_path), "--xpath", xpath]
    if output_path is not None:
        cmd.extend(["--output", str(output_path)])
    return cmd


def run_xctrace_record(
    template: str,
    output_trace: str | Path,
    *,
    device: str | None = None,
    launch_app: str | None = None,
    attach_target: str | None = None,
    time_limit_s: int | None = 10,
    launch_args: Sequence[str] = (),
    timeout_s: int = 180,
) -> subprocess.CompletedProcess[str]:
    """Invoke ``xcrun xctrace record`` and raise on non-zero exit."""
    cmd = build_xctrace_record_command(
        template,
        output_trace,
        device=device,
        launch_app=launch_app,
        attach_target=attach_target,
        time_limit_s=time_limit_s,
        launch_args=launch_args,
    )
    return subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=timeout_s)


def run_xctrace_toc(trace_path: str | Path, *, timeout_s: int = 60) -> str:
    """Export the table-of-contents XML for an ``.trace`` bundle."""
    cmd = build_xctrace_toc_command(trace_path)
    proc = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=timeout_s)
    return proc.stdout


def run_xctrace_export(
    trace_path: str | Path,
    xpath: str,
    *,
    output_path: str | Path | None = None,
    timeout_s: int = 120,
) -> str:
    """Export an ``xctrace`` table as XML via XPath."""
    cmd = build_xctrace_export_command(trace_path, xpath, output_path=output_path)
    proc = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=timeout_s)
    if output_path is not None:
        return Path(output_path).read_text(encoding="utf-8")
    return proc.stdout


# ---------------------------------------------------------------------------
# Trace bundle deterministic SHA-256
# ---------------------------------------------------------------------------


def compute_trace_bundle_sha256(trace_path: str | Path) -> str:
    """Compute a deterministic SHA-256 digest over a trace file or ``.trace`` bundle directory."""
    path = Path(trace_path)
    if path.is_file():
        return hashlib.sha256(path.read_bytes()).hexdigest()
    if path.is_dir():
        digest = hashlib.sha256()
        files = sorted(p for p in path.rglob("*") if p.is_file())
        for file_path in files:
            rel = file_path.relative_to(path).as_posix().encode("utf-8")
            digest.update(rel)
            digest.update(b"\x00")
            digest.update(file_path.read_bytes())
            digest.update(b"\x00")
        return digest.hexdigest()
    raise FileNotFoundError(f"Trace path does not exist: {path}")


# ---------------------------------------------------------------------------
# XML ref-id resolution, process filtering & duration/timestamp helpers
# ---------------------------------------------------------------------------

_FMT_DURATION_RE = re.compile(r"([0-9]+(?:\.[0-9]+)?)\s*(ns|us|µs|ms|s)\b", re.IGNORECASE)
_KV_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)=([^\s,;]+)")
_TRAILING_PID_RE = re.compile(r"\s*\(\d+\)\s*$")
_TRAILING_PAREN_RE = re.compile(r"\(([^()]+)\)\s*$")

_IOS_THERMAL_INT_MAP: dict[int, str] = {
    0: "nominal",
    1: "fair",
    2: "serious",
    3: "critical",
}
_IOS_THERMAL_NAMES: frozenset[str] = frozenset(_IOS_THERMAL_INT_MAP.values())

_START_TIME_TAGS: frozenset[str] = frozenset(
    {"start-time", "start_time", "sample-time", "sample_time", "timestamp", "time", "start"}
)
_END_TIME_TAGS: frozenset[str] = frozenset({"end-time", "end_time", "end"})
_DURATION_TAGS: frozenset[str] = frozenset(
    {
        "duration",
        "frame-duration",
        "frame_duration",
        "actual-duration",
        "hitch-duration",
        "hitch_duration",
        "gpu-duration",
        "metal-duration",
        "weight",
        "sample-weight",
    }
)


def _require_refresh_hz(refresh_hz: float | None) -> float:
    if (
        refresh_hz is None
        or isinstance(refresh_hz, bool)
        or not isinstance(refresh_hz, (int, float))
        or not math.isfinite(float(refresh_hz))
        or float(refresh_hz) <= 0
    ):
        raise ValueError(
            "refresh_hz is required and must be a finite positive number (no silent 60Hz fallback)"
        )
    return float(refresh_hz)


def _build_id_map(root: ET.Element) -> dict[str, ET.Element]:
    id_map: dict[str, ET.Element] = {}
    for elem in root.iter():
        elem_id = elem.attrib.get("id")
        if elem_id is not None:
            id_map[elem_id] = elem
    return id_map


def _resolve(elem: ET.Element, id_map: Mapping[str, ET.Element]) -> ET.Element:
    ref = elem.attrib.get("ref")
    if ref is not None and ref in id_map:
        return id_map[ref]
    return elem


def _row_matches_process(
    row: ET.Element,
    id_map: Mapping[str, ET.Element],
    process_name: str | None,
) -> bool:
    """Return False when ``process_name`` is set and the row belongs to a different process."""
    if not process_name or not process_name.strip():
        return True
    target = process_name.strip().lower()

    thread_proc: str | None = None
    for child in row:
        tag = child.tag.lower()
        resolved = _resolve(child, id_map)
        if tag == "process":
            raw_proc = (resolved.attrib.get("fmt") or resolved.text or "").strip()
            if raw_proc:
                cleaned = _TRAILING_PID_RE.sub("", raw_proc).strip().lower()
                return cleaned == target or raw_proc.lower() == target
        elif tag == "thread":
            raw_thread = (resolved.attrib.get("fmt") or resolved.text or "").strip()
            match = _TRAILING_PAREN_RE.search(raw_thread)
            if match:
                thread_proc = match.group(1).strip().lower()

    if thread_proc is not None:
        return thread_proc == target
    return True


def _parse_duration_ms(elem: ET.Element, id_map: Mapping[str, ET.Element]) -> float | None:
    """Parse an xctrace duration/timestamp element (raw text in ns, or fmt string) to milliseconds."""
    resolved = _resolve(elem, id_map)
    text = (resolved.text or "").strip()
    if text:
        try:
            raw_val = float(text)
            if math.isfinite(raw_val) and raw_val >= 0:
                unit_attr = (resolved.attrib.get("unit") or "").strip().lower()
                if unit_attr == "ms":
                    return raw_val
                if unit_attr == "s":
                    return raw_val * 1000.0
                if unit_attr in ("us", "µs"):
                    return raw_val / 1000.0
                # Default xctrace raw integer text is nanoseconds.
                return raw_val / 1_000_000.0
        except ValueError:
            pass
    fmt = resolved.attrib.get("fmt") or ""
    match = _FMT_DURATION_RE.search(fmt)
    if match:
        val = float(match.group(1))
        unit = match.group(2).lower()
        if unit == "ns":
            return val / 1_000_000.0
        if unit in ("us", "µs"):
            return val / 1000.0
        if unit == "ms":
            return val
        if unit == "s":
            return val * 1000.0
    return None


def _infer_window_ms_from_xml(
    xml_entries: Sequence[tuple[str | None, str | None]],
) -> float | None:
    """Infer capture window duration (ms) from XML ``<start-time>`` + ``<duration>`` spans."""
    min_start: float | None = None
    max_end: float | None = None

    for xml_text, proc_filter in xml_entries:
        if not xml_text or not xml_text.strip():
            continue
        root = ET.fromstring(xml_text)
        id_map = _build_id_map(root)
        for row in root.iter("row"):
            if not _row_matches_process(row, id_map, proc_filter):
                continue
            start_ms: float | None = None
            end_ms: float | None = None
            dur_ms: float | None = None
            for child in row:
                tag = child.tag.lower()
                if tag in _START_TIME_TAGS:
                    val = _parse_duration_ms(child, id_map)
                    if val is not None:
                        start_ms = val
                elif tag in _END_TIME_TAGS:
                    val = _parse_duration_ms(child, id_map)
                    if val is not None:
                        end_ms = val
                elif tag in _DURATION_TAGS:
                    val = _parse_duration_ms(child, id_map)
                    if val is not None and val > 0:
                        dur_ms = max(dur_ms or 0.0, val)
            if start_ms is None:
                continue
            if min_start is None or start_ms < min_start:
                min_start = start_ms
            candidate_end = (
                end_ms
                if (end_ms is not None and end_ms >= start_ms)
                else (start_ms + dur_ms if dur_ms is not None else start_ms)
            )
            if max_end is None or candidate_end > max_end:
                max_end = candidate_end

    if min_start is not None and max_end is not None and max_end > min_start:
        return round(max_end - min_start, 3)
    return None


def _normalize_thread_name(raw: str) -> str:
    cleaned = raw.strip()
    lowered = cleaned.lower()
    if lowered.startswith("main thread") or lowered == "com.apple.main-thread" or lowered == "main":
        return "main"
    # Strip trailing hex tid like "0x1a2b3c" when present.
    cleaned = re.sub(r"\s+0x[0-9a-fA-F]+$", "", cleaned)
    # Strip trailing parenthetical process name like "(Raft)" if present.
    cleaned = re.sub(r"\s*\([^)]*\)$", "", cleaned).strip()
    return cleaned or "unknown"


def _percentile(sorted_values: Sequence[float], pct: float) -> float:
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    pos = (len(sorted_values) - 1) * (pct / 100.0)
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return float(sorted_values[lo])
    return float(sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo))


# ---------------------------------------------------------------------------
# Pure-stdlib ElementTree parsers for xctrace exports
# ---------------------------------------------------------------------------


def parse_xctrace_hitches_xml(
    xml_text: str,
    *,
    window_ms: float | None = None,
    refresh_hz: float | None = None,
    process_name: str | None = None,
) -> dict[str, Any]:
    """Parse exported ``Animation Hitches`` XML into hitch and frame metrics."""
    hz = _require_refresh_hz(refresh_hz)
    if not xml_text or not xml_text.strip():
        return {
            "hitch_count": 0,
            "total_hitch_ms": 0.0,
            "hitch_ms_per_s": None,
            "worst_frame_ms": None,
            "slow_frame_count": None,
            "slow_frame_ratio": None,
            "frame_p50_ms": None,
            "frame_p95_ms": None,
            "frame_p99_ms": None,
            "frame_samples": 0,
        }

    root = ET.fromstring(xml_text)
    id_map = _build_id_map(root)

    hitch_durations_ms: list[float] = []
    frame_durations_ms: list[float] = []
    budget_ms = 1000.0 / hz

    for row in root.iter("row"):
        if not _row_matches_process(row, id_map, process_name):
            continue
        hitch_ms: float | None = None
        frame_ms: float | None = None
        for child in row:
            tag = child.tag.lower()
            if tag in ("hitch-duration", "hitch_duration", "duration"):
                val = _parse_duration_ms(child, id_map)
                if val is not None and val > 0:
                    hitch_ms = val
            elif tag in ("frame-duration", "frame_duration", "actual-duration"):
                val = _parse_duration_ms(child, id_map)
                if val is not None and val > 0:
                    frame_ms = val

        # Distinguish hitch rows vs full frame rows.
        if frame_ms is not None:
            frame_durations_ms.append(frame_ms)
            if hitch_ms is not None and hitch_ms > 0:
                hitch_durations_ms.append(hitch_ms)
            elif frame_ms > budget_ms + 1e-6:
                hitch_durations_ms.append(frame_ms - budget_ms)
        elif hitch_ms is not None:
            hitch_durations_ms.append(hitch_ms)

    total_hitch_ms = sum(hitch_durations_ms)
    hitch_count = len(hitch_durations_ms)

    eff_window_ms = window_ms
    if eff_window_ms is None:
        eff_window_ms = _infer_window_ms_from_xml([(xml_text, process_name)])

    hitch_ms_per_s: float | None = None
    if eff_window_ms is not None and float(eff_window_ms) > 0 and (hitch_count > 0 or frame_durations_ms):
        hitch_ms_per_s = round(total_hitch_ms / (float(eff_window_ms) / 1000.0), 3)

    if frame_durations_ms:
        sorted_frames = sorted(frame_durations_ms)
        slow_count = sum(1 for d in sorted_frames if d > budget_ms + 1e-6)
        return {
            "hitch_count": hitch_count,
            "total_hitch_ms": round(total_hitch_ms, 3),
            "hitch_ms_per_s": hitch_ms_per_s,
            "worst_frame_ms": round(sorted_frames[-1], 3),
            "slow_frame_count": slow_count,
            "slow_frame_ratio": round(slow_count / len(sorted_frames), 6),
            "frame_p50_ms": round(_percentile(sorted_frames, 50.0), 3),
            "frame_p95_ms": round(_percentile(sorted_frames, 95.0), 3),
            "frame_p99_ms": round(_percentile(sorted_frames, 99.0), 3),
            "frame_samples": len(sorted_frames),
        }

    # Hitch-only table: worst_frame_ms is worst hitch + one frame budget when hitches occurred.
    worst_frame = round(max(hitch_durations_ms) + budget_ms, 3) if hitch_durations_ms else None
    return {
        "hitch_count": hitch_count,
        "total_hitch_ms": round(total_hitch_ms, 3),
        "hitch_ms_per_s": hitch_ms_per_s,
        "worst_frame_ms": worst_frame,
        "slow_frame_count": hitch_count if hitch_count > 0 else None,
        "slow_frame_ratio": None,
        "frame_p50_ms": None,
        "frame_p95_ms": None,
        "frame_p99_ms": None,
        "frame_samples": 0,
    }


def parse_xctrace_time_profile_xml(
    xml_text: str,
    *,
    top_n: int = 10,
    process_name: str | None = None,
) -> dict[str, Any]:
    """Parse exported ``Time Profiler`` XML into ``top_hotspots`` and ``ui_thread_ms``."""
    if not xml_text or not xml_text.strip():
        return {"top_hotspots": [], "ui_thread_ms": None, "profile_sample_count": 0}

    root = ET.fromstring(xml_text)
    id_map = _build_id_map(root)

    self_acc: dict[tuple[str, str], float] = defaultdict(float)
    total_acc: dict[tuple[str, str], float] = defaultdict(float)
    ui_thread_ms = 0.0
    sample_count = 0

    for row in root.iter("row"):
        if not _row_matches_process(row, id_map, process_name):
            continue
        weight_ms: float | None = None
        thread_name = "unknown"
        frames: list[str] = []
        direct_symbol: str | None = None
        direct_self_ms: float | None = None
        direct_total_ms: float | None = None

        for child in row:
            tag = child.tag.lower()
            resolved = _resolve(child, id_map)
            if tag in ("weight", "sample-weight", "duration"):
                weight_ms = _parse_duration_ms(child, id_map)
            elif tag == "thread":
                raw_thread = resolved.attrib.get("fmt") or resolved.text or "unknown"
                thread_name = _normalize_thread_name(raw_thread)
            elif tag == "backtrace":
                for frame_elem in resolved.findall("frame"):
                    rf = _resolve(frame_elem, id_map)
                    name = (rf.attrib.get("name") or rf.text or "").strip()
                    if name:
                        frames.append(name)
            elif tag == "symbol":
                direct_symbol = (resolved.attrib.get("name") or resolved.text or "").strip()
            elif tag in ("self-ms", "self_ms"):
                try:
                    direct_self_ms = float((resolved.text or "").strip())
                except ValueError:
                    pass
            elif tag in ("total-ms", "total_ms"):
                try:
                    direct_total_ms = float((resolved.text or "").strip())
                except ValueError:
                    pass

        if direct_symbol and direct_self_ms is not None:
            sample_count += 1
            total_v = direct_total_ms if direct_total_ms is not None else direct_self_ms
            if total_v < direct_self_ms:
                total_v = direct_self_ms
            self_acc[(direct_symbol, thread_name)] += direct_self_ms
            total_acc[(direct_symbol, thread_name)] += total_v
            if thread_name == "main":
                ui_thread_ms += direct_self_ms
            continue

        if not frames:
            continue
        sample_count += 1
        w = weight_ms if (weight_ms is not None and weight_ms > 0) else 1.0
        if thread_name == "main":
            ui_thread_ms += w
        # Frame 0 is the leaf (self time); every unique frame in the stack gets inclusive total time.
        leaf = frames[0]
        self_acc[(leaf, thread_name)] += w
        seen_in_stack: set[str] = set()
        for sym in frames:
            if sym not in seen_in_stack:
                seen_in_stack.add(sym)
                total_acc[(sym, thread_name)] += w

    hotspots: list[dict[str, Any]] = []
    for (symbol, thread), tot_ms in total_acc.items():
        s_ms = self_acc.get((symbol, thread), 0.0)
        t_ms = max(tot_ms, s_ms)
        if s_ms <= 0 and t_ms <= 0:
            continue
        hotspots.append(
            {
                "symbol": symbol,
                "thread": thread,
                "self_ms": round(s_ms, 3),
                "total_ms": round(t_ms, 3),
            }
        )
    hotspots.sort(key=lambda h: (-h["self_ms"], -h["total_ms"], h["symbol"]))

    return {
        "top_hotspots": hotspots[:top_n],
        "ui_thread_ms": round(ui_thread_ms, 3) if ui_thread_ms > 0 else None,
        "profile_sample_count": sample_count,
    }


def parse_xctrace_metal_xml(
    xml_text: str,
    *,
    process_name: str | None = None,
) -> dict[str, Any]:
    """Parse exported ``Metal System Trace`` GPU intervals XML into total ``metal_ms``."""
    if not xml_text or not xml_text.strip():
        return {"metal_ms": None, "gpu_interval_count": 0}

    root = ET.fromstring(xml_text)
    id_map = _build_id_map(root)
    total_ms = 0.0
    count = 0

    for row in root.iter("row"):
        if not _row_matches_process(row, id_map, process_name):
            continue
        for child in row:
            tag = child.tag.lower()
            if tag in ("duration", "gpu-duration", "metal-duration"):
                val = _parse_duration_ms(child, id_map)
                if val is not None and val > 0:
                    total_ms += val
                    count += 1
                    break

    return {
        "metal_ms": round(total_ms, 3) if total_ms > 0 else None,
        "gpu_interval_count": count,
    }


def parse_xctrace_thermal_xml(xml_text: str) -> tuple[str | None, str | None]:
    """Parse exported iOS ``thermal-state`` XML into ``(thermal_start, thermal_end)``."""
    if not xml_text or not xml_text.strip():
        return None, None

    root = ET.fromstring(xml_text)
    id_map = _build_id_map(root)
    states: list[str] = []

    for row in root.iter("row"):
        for child in row:
            tag = child.tag.lower()
            if tag in ("thermal-state", "thermal_state", "state", "narrative"):
                resolved = _resolve(child, id_map)
                candidates = [
                    (resolved.attrib.get("fmt") or "").strip().lower(),
                    (resolved.text or "").strip().lower(),
                ]
                matched: str | None = None
                for cand in candidates:
                    if not cand:
                        continue
                    for name in _IOS_THERMAL_NAMES:
                        if cand == name or name in cand.split():
                            matched = name
                            break
                    if matched:
                        break
                    try:
                        code = int(cand)
                        if code in _IOS_THERMAL_INT_MAP:
                            matched = _IOS_THERMAL_INT_MAP[code]
                            break
                    except ValueError:
                        continue
                if matched:
                    states.append(matched)
                    break

    if not states:
        return None, None
    return states[0], states[-1]


def _coerce_num(raw: str) -> Any:
    cleaned = raw.rstrip(".,;)")
    try:
        if "." not in cleaned and "e" not in cleaned.lower():
            return int(cleaned)
        val = float(cleaned)
        return val if math.isfinite(val) else cleaned
    except ValueError:
        return cleaned


def _extract_markers_from_text_lines(lines: Sequence[str]) -> dict[str, Any]:
    segments: dict[str, Any] = {}
    duration_ms: float | None = None
    ttfd_ms: float | None = None
    mem_peak_mb: float | None = None
    reading_anchor_attempts = 0
    has_summary_shift = False
    shift_count_fallback = 0
    shift_dp_fallback = 0.0

    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        # Support raft-diagnostics.jsonl lines (`{"tag":..., "message":...}`).
        if line.startswith("{") and line.endswith("}"):
            try:
                obj = json.loads(line)
                if isinstance(obj, Mapping) and isinstance(obj.get("message"), str):
                    line = f"{obj.get('tag', '')} {obj['message']}"
            except json.JSONDecodeError:
                pass

        kv = {k: _coerce_num(v) for k, v in _KV_RE.findall(line)}

        if "open_trace_summary" in line:
            dur = kv.get("duration_ms")
            if isinstance(dur, (int, float)) and not isinstance(dur, bool) and float(dur) > 0:
                duration_ms = float(dur)
            for seg_key in (
                "cache_rows_shown_ms",
                "network_applied_ms",
                "first_frame_ms",
                "target_positioned_ms",
                "stable_ms",
                "time_to_stable_ms",
                "shift_count",
                "frame_count",
            ):
                val = kv.get(seg_key)
                if isinstance(val, (int, float)) and not isinstance(val, bool) and float(val) >= 0:
                    segments[seg_key] = val
            if isinstance(kv.get("shift_sum_dp"), (int, float)) and not isinstance(kv.get("shift_sum_dp"), bool):
                segments["visible_shift_dp"] = round(float(kv["shift_sum_dp"]), 2)
                has_summary_shift = True
            if "shift_count" in segments:
                has_summary_shift = True

        elif "open_trace_shift" in line:
            shift_count_fallback += 1
            delta = kv.get("delta_dp")
            if isinstance(delta, (int, float)) and not isinstance(delta, bool):
                shift_dp_fallback += abs(float(delta))
            if kv.get("cause") == "reading_anchor":
                attempt = kv.get("owner_attempt")
                if isinstance(attempt, int) and not isinstance(attempt, bool) and attempt > 0:
                    reading_anchor_attempts = max(reading_anchor_attempts, attempt)
                else:
                    reading_anchor_attempts += 1

        else:
            if "reading_anchor" in line and "reading_anchor_attempt_count" not in kv:
                attempt = kv.get("attempt") or kv.get("owner_attempt")
                if isinstance(attempt, int) and not isinstance(attempt, bool) and attempt > 0:
                    reading_anchor_attempts = max(reading_anchor_attempts, attempt)
                elif kv.get("cause") == "reading_anchor" or "reading_anchor_attempt" in line:
                    reading_anchor_attempts += 1

            if isinstance(kv.get("duration_ms"), (int, float)) and not isinstance(kv.get("duration_ms"), bool) and float(kv["duration_ms"]) > 0 and duration_ms is None:
                duration_ms = float(kv["duration_ms"])
            if isinstance(kv.get("ttfd_ms"), (int, float)) and not isinstance(kv.get("ttfd_ms"), bool) and float(kv["ttfd_ms"]) > 0:
                ttfd_ms = float(kv["ttfd_ms"])
            if isinstance(kv.get("mem_peak_mb"), (int, float)) and not isinstance(kv.get("mem_peak_mb"), bool) and float(kv["mem_peak_mb"]) > 0:
                mem_peak_mb = max(mem_peak_mb or 0.0, float(kv["mem_peak_mb"]))

            for key, val in kv.items():
                if key in ("duration_ms", "ttfd_ms", "mem_peak_mb"):
                    continue
                if isinstance(val, (int, float)) and not isinstance(val, bool) and key.endswith(SEGMENT_UNIT_SUFFIXES):
                    segments[key] = val

    if reading_anchor_attempts > 0 and "reading_anchor_attempt_count" not in segments:
        segments["reading_anchor_attempt_count"] = reading_anchor_attempts
    if not has_summary_shift and shift_count_fallback > 0:
        segments.setdefault("shift_count", shift_count_fallback)
        segments.setdefault("visible_shift_dp", round(shift_dp_fallback, 2))

    return {
        "duration_ms": duration_ms,
        "ttfd_ms": ttfd_ms,
        "mem_peak_mb": mem_peak_mb,
        "segments": segments,
    }


def parse_ios_signpost_or_log_markers(
    *,
    signpost_xml: str | None = None,
    app_log_text: str | None = None,
    process_name: str | None = None,
) -> dict[str, Any]:
    """Extract iOS scenario latency and segment markers from signpost XML and/or app logs."""
    lines: list[str] = []
    segments: dict[str, Any] = {}
    duration_ms: float | None = None
    ttfd_ms: float | None = None

    if signpost_xml and signpost_xml.strip():
        root = ET.fromstring(signpost_xml)
        id_map = _build_id_map(root)
        for row in root.iter("row"):
            if not _row_matches_process(row, id_map, process_name):
                continue
            name: str | None = None
            dur_ms: float | None = None
            msg_parts: list[str] = []
            for child in row:
                tag = child.tag.lower()
                resolved = _resolve(child, id_map)
                if tag in ("signpost-name", "name"):
                    name = (resolved.attrib.get("fmt") or resolved.text or "").strip()
                elif tag == "duration":
                    dur_ms = _parse_duration_ms(child, id_map)
                elif tag in ("message", "start-message", "end-message", "narrative"):
                    txt = (resolved.attrib.get("fmt") or resolved.text or "").strip()
                    if txt:
                        msg_parts.append(txt)
            if name and dur_ms is not None and dur_ms > 0:
                if name in ("thread_open", "channel_switch", "composer_first_char", "webview_open", "foreground_resume"):
                    duration_ms = round(dur_ms, 3)
                elif name in ("cold_start", "ttfd"):
                    ttfd_ms = round(dur_ms, 3)
                elif name.endswith("_ms"):
                    segments[name] = round(dur_ms, 3)
                elif name in (
                    "process_init",
                    "compose_first_frame",
                    "auth_bootstrap",
                    "first_feed_render",
                    "input_delay",
                    "network",
                    "recompose_layout",
                    "render_gpu",
                    "ui_thread",
                    "recompose",
                    "render_thread_or_metal",
                    "ime_settle",
                    "first_char_p50",
                    "first_char_p95",
                    "key_to_state",
                    "text_layout",
                    "draw",
                    "container_init",
                    "first_paint",
                    "cache_rows_shown",
                    "network_applied",
                    "resume_first_frame",
                    "mutex_wait",
                    "jitter_wait",
                    "network_fetch",
                ):
                    segments[f"{name}_ms"] = round(dur_ms, 3)
            if msg_parts:
                lines.append(" ".join(msg_parts))

    if app_log_text:
        lines.extend(app_log_text.splitlines())

    log_parsed = _extract_markers_from_text_lines(lines)
    merged_segments = dict(segments)
    merged_segments.update(log_parsed["segments"])

    return {
        "duration_ms": duration_ms if duration_ms is not None else log_parsed["duration_ms"],
        "ttfd_ms": ttfd_ms if ttfd_ms is not None else log_parsed["ttfd_ms"],
        "mem_peak_mb": log_parsed["mem_peak_mb"],
        "segments": merged_segments,
    }


# ---------------------------------------------------------------------------
# Unified iOS emitter
# ---------------------------------------------------------------------------


def parse_xctrace_exports(
    *,
    run_id: str,
    commit_sha: str,
    app_version: str,
    app_build: str,
    build_type: str,
    scenario: str,
    device_id: str,
    device_model: str,
    os_version: str,
    refresh_hz: float | None = None,
    layout_mode: str = "phone",
    warm_state: str = "profile_warm",
    fixture_tag: str = DEFAULT_FIXTURE_TAG,
    env: str = "staging",
    project: str = "raft-ios",
    run_kind: str = "baseline",
    ab_session_id: str | None = None,
    ab_arm: str | None = None,
    iteration: int | None = None,
    calibration_injected_ms: float | None = None,
    window_ms: float | None = None,
    frame_count: int | None = None,
    process_name: str | None = None,
    thermal_start: str | None = None,
    thermal_end: str | None = None,
    duration_ms: float | None = None,
    ttfd_ms: float | None = None,
    mem_peak_mb: float | None = None,
    hitches_xml: str | None = None,
    time_profile_xml: str | None = None,
    thermal_xml: str | None = None,
    metal_xml: str | None = None,
    signpost_xml: str | None = None,
    app_log_text: str | None = None,
    trace_bytes: bytes | None = None,
    trace_path: str | Path | None = None,
    trace_link: str | None = None,
    diagnose_run_id: str | None = None,
    taken_at: str | None = None,
    extra_segments: Mapping[str, Any] | None = None,
    extra: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Parse iOS ``xctrace`` XML exports and logs into validated ScopeDB rows.

    Returns ``(run_row, diagnose_row)``. Requires explicit positive
    ``refresh_hz`` (no silent 60Hz fallback). Infers ``window_ms`` from trace
    XML timestamps when omitted; leaves both ``window_ms`` and
    ``hitch_ms_per_s`` as ``None`` if unavailable. Derives ``samples`` strictly
    from frame-duration rows, explicit ``frame_count``, or app-log
    ``frame_count`` (never mixing Time Profiler CPU samples or hitch counts).
    """
    resolved_hz = _require_refresh_hz(refresh_hz)
    if frame_count is not None and (
        not isinstance(frame_count, int) or isinstance(frame_count, bool) or frame_count < 0
    ):
        raise ValueError(f"frame_count must be a non-negative integer, got {frame_count!r}")
    if window_ms is not None and (
        isinstance(window_ms, bool)
        or not isinstance(window_ms, (int, float))
        or not math.isfinite(float(window_ms))
        or float(window_ms) <= 0
    ):
        raise ValueError(f"window_ms must be a finite positive number, got {window_ms!r}")

    resolved_window_ms: float | None = float(window_ms) if window_ms is not None else None
    if resolved_window_ms is None:
        resolved_window_ms = _infer_window_ms_from_xml(
            [
                (hitches_xml, process_name),
                (time_profile_xml, process_name),
                (metal_xml, process_name),
                (signpost_xml, process_name),
                (thermal_xml, None),
            ]
        )

    hitches = parse_xctrace_hitches_xml(
        hitches_xml or "",
        window_ms=resolved_window_ms,
        refresh_hz=resolved_hz,
        process_name=process_name,
    )
    time_prof = parse_xctrace_time_profile_xml(
        time_profile_xml or "", process_name=process_name
    )
    metal = parse_xctrace_metal_xml(metal_xml or "", process_name=process_name)
    xml_thermal_start, xml_thermal_end = parse_xctrace_thermal_xml(thermal_xml or "")
    markers = parse_ios_signpost_or_log_markers(
        signpost_xml=signpost_xml, app_log_text=app_log_text, process_name=process_name
    )

    resolved_thermal_start = thermal_start or xml_thermal_start
    resolved_thermal_end = thermal_end or xml_thermal_end
    resolved_duration_ms = duration_ms if duration_ms is not None else markers["duration_ms"]
    resolved_ttfd_ms = ttfd_ms if ttfd_ms is not None else markers["ttfd_ms"]
    resolved_mem_peak_mb = mem_peak_mb if mem_peak_mb is not None else markers["mem_peak_mb"]

    # Derive samples strictly from frame-duration rows, explicit frame_count, or app-log frame_count.
    # Never mix Time Profiler CPU samples or hitch counts into samples.
    log_frame_count = markers["segments"].get("frame_count")
    extra_frame_count = extra_segments.get("frame_count") if extra_segments else None
    if hitches["frame_samples"] > 0:
        samples = hitches["frame_samples"]
    elif frame_count is not None:
        samples = int(frame_count)
    elif isinstance(log_frame_count, int) and not isinstance(log_frame_count, bool) and log_frame_count >= 0:
        samples = int(log_frame_count)
    elif isinstance(extra_frame_count, int) and not isinstance(extra_frame_count, bool) and extra_frame_count >= 0:
        samples = int(extra_frame_count)
    else:
        samples = 0

    segments: dict[str, Any] = dict(markers["segments"])
    if samples > 0:
        segments.setdefault("frame_count", samples)
    if hitches["hitch_count"] > 0:
        segments.setdefault("hitch_count", hitches["hitch_count"])
    if extra_segments:
        segments.update(extra_segments)

    trace_sha256: str | None = None
    if trace_bytes is not None:
        trace_sha256 = hashlib.sha256(trace_bytes).hexdigest()
    elif trace_path is not None:
        trace_sha256 = compute_trace_bundle_sha256(trace_path)

    throttled, thermal_invalid_reason = is_thermal_throttled(
        resolved_thermal_start, resolved_thermal_end
    )
    valid = (samples > 0) and (not throttled)
    has_samples = samples > 0
    has_hitches_export = hitches_xml is not None
    timestamp = taken_at or utc_now_iso()

    extra_dict = dict(extra) if extra else {}
    extra_dict.setdefault("capture_mode", "xctrace")
    if time_profile_xml is not None or time_prof["profile_sample_count"] > 0:
        extra_dict["time_profile_sample_count"] = time_prof["profile_sample_count"]
    if throttled and thermal_invalid_reason:
        extra_dict["thermal_invalid_reason"] = thermal_invalid_reason

    if has_samples and has_hitches_export:
        if resolved_window_ms is not None and resolved_window_ms > 0:
            resolved_hitch_ms_per_s: float | None = (
                hitches["hitch_ms_per_s"]
                if hitches["hitch_ms_per_s"] is not None
                else round(hitches["total_hitch_ms"] / (resolved_window_ms / 1000.0), 3)
            )
        else:
            resolved_hitch_ms_per_s = None
        resolved_slow_frame_count: int | None = (
            hitches["slow_frame_count"]
            if hitches["slow_frame_count"] is not None
            else hitches["hitch_count"]
        )
    else:
        resolved_hitch_ms_per_s = None
        resolved_slow_frame_count = None

    common = {
        "project": project,
        "env": env,
        "tool_version": TOOL_VERSION,
        "taken_at": timestamp,
        "commit_sha": commit_sha,
        "app_version": app_version,
        "app_build": app_build,
        "build_type": build_type,
        "scenario": scenario,
        "run_kind": run_kind,
        "ab_session_id": ab_session_id,
        "ab_arm": ab_arm,
        "iteration": iteration,
        "calibration_injected_ms": calibration_injected_ms,
        "device_id": device_id,
        "device_model": device_model,
        "os_version": os_version,
        "layout_mode": layout_mode,
        "refresh_hz": resolved_hz,
        "warm_state": warm_state,
        "fixture_tag": fixture_tag,
        "thermal_start": resolved_thermal_start,
        "thermal_end": resolved_thermal_end,
        "samples": samples,
        "valid": valid,
        "segments": segments,
        "verdict": None,
        "extra": extra_dict,
    }

    run_row = make_run_row(
        run_id=run_id,
        duration_ms=resolved_duration_ms,
        ttfd_ms=resolved_ttfd_ms,
        frame_p50_ms=hitches["frame_p50_ms"] if has_samples else None,
        frame_p95_ms=hitches["frame_p95_ms"] if has_samples else None,
        frame_p99_ms=hitches["frame_p99_ms"] if has_samples else None,
        slow_frame_ratio=hitches["slow_frame_ratio"] if has_samples else None,
        hitch_ms_per_s=resolved_hitch_ms_per_s,
        mem_peak_mb=resolved_mem_peak_mb,
        **common,
    )

    diagnose_row = make_diagnose_row(
        run_id=diagnose_run_id or f"{run_id}-diag",
        window_ms=resolved_window_ms if (resolved_window_ms is not None and resolved_window_ms > 0) else None,
        ui_thread_ms=time_prof["ui_thread_ms"],
        render_thread_ms=None,
        metal_ms=metal["metal_ms"],
        worst_frame_ms=hitches["worst_frame_ms"] if has_samples else None,
        slow_frame_count=resolved_slow_frame_count,
        compose_recomp_count=None,
        top_hotspots=time_prof["top_hotspots"],
        trace_sha256=trace_sha256,
        trace_link=trace_link,
        **common,
    )

    run_errors = validate_run_row(run_row)
    if run_errors:
        raise ValueError("Generated invalid iOS mobile_runs row: " + "; ".join(run_errors))
    diag_errors = validate_diagnose_row(diagnose_row)
    if diag_errors:
        raise ValueError("Generated invalid iOS mobile_diagnose_runs row: " + "; ".join(diag_errors))

    return run_row, diagnose_row
