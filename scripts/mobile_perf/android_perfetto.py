"""Android Perfetto config/SQL toolkit and zero-dependency dumpsys/logcat parser.

Supports two complementary collection modes that emit the exact same validated
``scopedb.perf.mobile_runs`` and ``scopedb.perf.mobile_diagnose_runs`` schema:
1. Full Perfetto trace recording + ``trace_processor_shell`` SQL query parsing.
2. Zero-dependency ``dumpsys`` + ``logcat`` fallback that works immediately on
   any adb-connected device or TestBed cloud phone without ``trace_processor_shell``.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import re
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from .ab_verdict import is_thermal_throttled
from .scenarios import ANDROID_PACKAGE, DEFAULT_FIXTURE_TAG
from .schema import (
    SEGMENT_UNIT_SUFFIXES,
    TOOL_VERSION,
    make_diagnose_row,
    make_run_row,
    utc_now_iso,
    validate_diagnose_row,
    validate_run_row,
)

# ---------------------------------------------------------------------------
# Perfetto protobuf-text config generator
# ---------------------------------------------------------------------------


def build_perfetto_config(
    package: str = ANDROID_PACKAGE,
    duration_ms: int = 10000,
    *,
    buffer_kb: int = 65536,
    metadata_buffer_kb: int = 4096,
    proc_stats_poll_ms: int = 250,
) -> str:
    """Generate a valid Perfetto protobuf-text (.pbtx) trace configuration.

    Covers:
    - ``linux.ftrace`` (``sched/sched_switch``, ``gfx``, ``view``,
      ``binder_driver``, ``am``, ``wm``, and ``atrace_apps`` for ``package``)
    - ``android.surfaceflinger.frametimeline``
    - ``linux.process_stats``
    - ``android.log``
    """
    if not isinstance(package, str) or not package.strip():
        raise ValueError(f"package must be a non-empty string, got {package!r}")
    if any(ch in package for ch in ('"', "\n", "\r")):
        raise ValueError(f"package contains invalid characters: {package!r}")
    if not isinstance(duration_ms, int) or isinstance(duration_ms, bool) or duration_ms <= 0:
        raise ValueError(f"duration_ms must be a positive integer, got {duration_ms!r}")
    if buffer_kb <= 0 or metadata_buffer_kb <= 0:
        raise ValueError("buffer sizes must be positive integers")

    return f"""buffers: {{
  size_kb: {buffer_kb}
  fill_policy: RING_BUFFER
}}
buffers: {{
  size_kb: {metadata_buffer_kb}
  fill_policy: DISCARD
}}

duration_ms: {duration_ms}
flush_period_ms: 2500

data_sources: {{
  config {{
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {{
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "ftrace/print"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "binder_driver"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_apps: "{package}"
      buffer_size_kb: 4096
      drain_period_ms: 250
    }}
  }}
}}

data_sources: {{
  config {{
    name: "android.surfaceflinger.frametimeline"
    target_buffer: 0
  }}
}}

data_sources: {{
  config {{
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config {{
      scan_all_processes_on_start: true
      proc_stats_poll_ms: {proc_stats_poll_ms}
    }}
  }}
}}

data_sources: {{
  config {{
    name: "android.log"
    target_buffer: 1
    android_log_config {{
      log_ids: LID_DEFAULT
      log_ids: LID_SYSTEM
      log_ids: LID_EVENTS
    }}
  }}
}}
"""


# ---------------------------------------------------------------------------
# Perfetto SQL query templates (for trace_processor_shell)
# ---------------------------------------------------------------------------

PERFETTO_FRAME_TIMELINE_SQL = """\
SELECT
  a.surface_frame_token AS frame_token,
  a.dur / 1e6 AS dur_ms,
  e.dur / 1e6 AS expected_dur_ms,
  a.jank_type AS jank_type,
  CASE
    WHEN a.jank_type IS NOT NULL AND a.jank_type != 'None' AND a.jank_type != '' THEN 1
    WHEN e.dur IS NOT NULL AND a.dur > e.dur THEN 1
    ELSE 0
  END AS is_jank
FROM actual_frame_timeline_slice a
JOIN process p ON a.upid = p.upid
LEFT JOIN expected_frame_timeline_slice e
  ON a.upid = e.upid AND a.surface_frame_token = e.surface_frame_token
WHERE p.name = '{package}'
  AND a.dur > 0
ORDER BY a.ts ASC;
"""

PERFETTO_MAIN_THREAD_HOTSPOTS_SQL = """\
INCLUDE PERFETTO MODULE slices.self_dur;

SELECT
  s.name AS symbol,
  COALESCE(t.name, 'main') AS thread,
  SUM(sd.self_dur) / 1e6 AS self_ms,
  SUM(s.dur) / 1e6 AS total_ms,
  COUNT(*) AS call_count
FROM slice s
JOIN slice_self_dur sd ON s.id = sd.id
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name = '{package}'
  AND t.is_main_thread = 1
  AND s.dur > 0
GROUP BY s.name, thread
ORDER BY self_ms DESC
LIMIT 25;
"""

PERFETTO_RENDER_THREAD_HOTSPOTS_SQL = """\
INCLUDE PERFETTO MODULE slices.self_dur;

SELECT
  s.name AS symbol,
  t.name AS thread,
  SUM(sd.self_dur) / 1e6 AS self_ms,
  SUM(s.dur) / 1e6 AS total_ms,
  COUNT(*) AS call_count
FROM slice s
JOIN slice_self_dur sd ON s.id = sd.id
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name = '{package}'
  AND t.name = 'RenderThread'
  AND s.dur > 0
GROUP BY s.name, t.name
ORDER BY self_ms DESC
LIMIT 25;
"""

# Backwards-compatible aliases.
PERFETTO_MAIN_THREAD_SLICES_SQL = PERFETTO_MAIN_THREAD_HOTSPOTS_SQL
PERFETTO_RENDER_THREAD_SLICES_SQL = PERFETTO_RENDER_THREAD_HOTSPOTS_SQL

PERFETTO_THREAD_CPU_SQL = """\
SELECT
  CASE
    WHEN t.is_main_thread = 1 THEN 'main'
    ELSE t.name
  END AS thread_role,
  SUM(ss.dur) / 1e6 AS cpu_ms
FROM sched_slice ss
JOIN thread t ON ss.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name = '{package}'
  AND (t.is_main_thread = 1 OR t.name = 'RenderThread')
  AND ss.dur > 0
GROUP BY thread_role;
"""

PERFETTO_TRACE_BOUNDS_SQL = """\
SELECT
  (end_ts - start_ts) / 1e6 AS window_ms
FROM trace_bounds;
"""

PERFETTO_COMPOSE_RECOMP_SQL = """\
SELECT
  COUNT(*) AS recomp_count,
  SUM(s.dur) / 1e6 AS total_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name = '{package}'
  AND (
    s.name = 'Compose:recompose'
    OR s.name = 'Recomposer:recompose'
    OR s.name LIKE 'Compose:recompose%'
  );
"""

PERFETTO_MEMORY_SQL = """\
SELECT
  MAX(
    CASE
      WHEN pct.name = 'Heap size (KB)' THEN c.value / 1024.0
      ELSE c.value / 1048576.0
    END
  ) AS mem_peak_mb
FROM counter c
JOIN process_counter_track pct ON c.track_id = pct.id
JOIN process p ON pct.upid = p.upid
WHERE p.name = '{package}'
  AND pct.name IN ('mem.rss', 'mem.rss.anon', 'Heap size (KB)');
"""


def render_perfetto_sql(template: str, package: str = ANDROID_PACKAGE) -> str:
    """Substitute the target Android package into a Perfetto SQL template."""
    safe_pkg = package.replace("'", "''")
    return template.format(package=safe_pkg)


def build_adb_perfetto_command(
    serial: str,
    remote_config_path: str = "/data/misc/perfetto-configs/mobile_perf.pbtx",
    remote_trace_path: str = "/data/misc/perfetto-traces/mobile_perf.perfetto-trace",
) -> list[str]:
    """Return an ``adb`` command invoking on-device Perfetto recording."""
    return [
        "adb",
        "-s",
        serial,
        "shell",
        "perfetto",
        "--txt",
        "-c",
        remote_config_path,
        "-o",
        remote_trace_path,
    ]


def build_adb_dumpsys_commands(serial: str, package: str = ANDROID_PACKAGE) -> dict[str, list[str]]:
    """Return standard zero-dependency adb dumpsys/logcat commands for one capture."""
    return {
        "gfxinfo_reset": ["adb", "-s", serial, "shell", "dumpsys", "gfxinfo", package, "reset"],
        "gfxinfo_framestats": ["adb", "-s", serial, "shell", "dumpsys", "gfxinfo", package, "framestats"],
        "meminfo": ["adb", "-s", serial, "shell", "dumpsys", "meminfo", package],
        "thermalservice": ["adb", "-s", serial, "shell", "dumpsys", "thermalservice"],
        "display": ["adb", "-s", serial, "shell", "dumpsys", "display"],
        "logcat_dump": ["adb", "-s", serial, "logcat", "-d", "-v", "time"],
    }


# ---------------------------------------------------------------------------
# Numeric & percentile helpers
# ---------------------------------------------------------------------------


def _require_refresh_hz(refresh_hz: float | None) -> float:
    if (
        refresh_hz is None
        or isinstance(refresh_hz, bool)
        or not isinstance(refresh_hz, (int, float))
        or not math.isfinite(float(refresh_hz))
        or float(refresh_hz) <= 0
    ):
        raise ValueError(
            "refresh_hz is required and must be a finite positive number "
            "(provide refresh_hz explicitly or via dumpsys display; no silent 60Hz fallback)"
        )
    return float(refresh_hz)


def _percentile(sorted_values: Sequence[float], pct: float) -> float:
    """Linearly interpolated percentile (matching numpy/R type 7)."""
    if not sorted_values:
        raise ValueError("Cannot compute percentile of empty sequence")
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    pos = (len(sorted_values) - 1) * (pct / 100.0)
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return float(sorted_values[lo])
    frac = pos - lo
    return float(sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * frac)


def _summarize_frame_durations(
    durations_ms: Sequence[float],
    refresh_hz: float,
    jank_flags: Sequence[bool] | None = None,
) -> dict[str, Any]:
    hz = _require_refresh_hz(refresh_hz)
    valid_durs = sorted(
        float(d) for d in durations_ms if d is not None and math.isfinite(float(d)) and float(d) > 0
    )
    if not valid_durs:
        return {
            "samples": 0,
            "frame_p50_ms": None,
            "frame_p95_ms": None,
            "frame_p99_ms": None,
            "worst_frame_ms": None,
            "slow_frame_count": None,
            "slow_frame_ratio": None,
        }
    budget_ms = 1000.0 / hz
    if jank_flags is not None and len(jank_flags) == len(durations_ms):
        slow_count = sum(1 for flag in jank_flags if flag)
    else:
        slow_count = sum(1 for d in valid_durs if d > budget_ms + 1e-6)
    samples = len(valid_durs)
    return {
        "samples": samples,
        "frame_p50_ms": round(_percentile(valid_durs, 50.0), 3),
        "frame_p95_ms": round(_percentile(valid_durs, 95.0), 3),
        "frame_p99_ms": round(_percentile(valid_durs, 99.0), 3),
        "worst_frame_ms": round(valid_durs[-1], 3),
        "slow_frame_count": slow_count,
        "slow_frame_ratio": round(slow_count / samples, 6),
    }


def _coerce_scalar(raw: Any) -> Any:
    if raw is None:
        return None
    if isinstance(raw, (int, float, bool)):
        return raw
    text = str(raw).strip()
    if text == "" or text.upper() in ("NULL", "NONE", "[NULL]"):
        return None
    try:
        if "." not in text and "e" not in text.lower():
            return int(text)
        val = float(text)
        return val if math.isfinite(val) else None
    except ValueError:
        return text


# ---------------------------------------------------------------------------
# Perfetto trace_processor_shell output parsers
# ---------------------------------------------------------------------------


def parse_trace_processor_output(text: str) -> list[dict[str, Any]]:
    """Parse ``trace_processor_shell`` query output in CSV or JSON format."""
    stripped = text.strip()
    if not stripped:
        return []
    if stripped.startswith("[") or stripped.startswith("{"):
        payload = json.loads(stripped)
        if isinstance(payload, list):
            return [{k: _coerce_scalar(v) for k, v in item.items()} for item in payload if isinstance(item, Mapping)]
        if isinstance(payload, Mapping):
            rows_raw = payload.get("rows", [])
            columns = payload.get("columns")
            if columns and rows_raw and isinstance(rows_raw[0], list):
                return [
                    {col: _coerce_scalar(val) for col, val in zip(columns, row)}
                    for row in rows_raw
                ]
            if isinstance(rows_raw, list):
                return [{k: _coerce_scalar(v) for k, v in item.items()} for item in rows_raw if isinstance(item, Mapping)]
        raise ValueError("Unsupported trace_processor JSON structure")

    reader = csv.DictReader(io.StringIO(stripped))
    out: list[dict[str, Any]] = []
    for row in reader:
        out.append({k.strip(): _coerce_scalar(v) for k, v in row.items() if k is not None})
    return out


def _extract_hotspots(
    rows: Sequence[Mapping[str, Any]],
    *,
    default_thread: str,
) -> list[dict[str, Any]]:
    hotspots: list[dict[str, Any]] = []
    for row in rows:
        symbol = row.get("symbol") or row.get("name")
        if not isinstance(symbol, str) or not symbol.strip():
            continue
        thread = row.get("thread") or default_thread
        if not isinstance(thread, str) or not thread.strip():
            thread = default_thread
        self_raw = row.get("self_ms")
        total_raw = row.get("total_ms")
        if self_raw is None and total_raw is None:
            continue
        self_ms = float(self_raw if self_raw is not None else total_raw)
        total_ms = float(total_raw if total_raw is not None else self_ms)
        if self_ms < 0 or total_ms < 0:
            continue
        if total_ms < self_ms:
            total_ms = self_ms
        hotspots.append(
            {
                "symbol": symbol.strip(),
                "thread": thread.strip(),
                "self_ms": round(self_ms, 3),
                "total_ms": round(total_ms, 3),
            }
        )
    return hotspots


def _parse_thread_cpu_rows(rows: Sequence[Mapping[str, Any]]) -> tuple[float | None, float | None]:
    ui_ms = 0.0
    rt_ms = 0.0
    for row in rows:
        cpu_raw = row.get("cpu_ms")
        if cpu_raw is None:
            cpu_raw = row.get("dur_ms")
        if not isinstance(cpu_raw, (int, float)) or isinstance(cpu_raw, bool) or not math.isfinite(float(cpu_raw)):
            continue
        cpu_val = float(cpu_raw)
        if cpu_val <= 0:
            continue

        is_main = row.get("is_main_thread")
        role_raw = row.get("thread_role") or row.get("thread") or row.get("name") or ""
        role = str(role_raw).strip().lower()
        if is_main == 1 or is_main is True or role in ("main", "ui", "main_thread"):
            ui_ms += cpu_val
        elif role == "renderthread":
            rt_ms += cpu_val
    return (round(ui_ms, 3) if ui_ms > 0 else None), (round(rt_ms, 3) if rt_ms > 0 else None)


def parse_perfetto_query_results(
    *,
    frame_timeline_raw: str | None = None,
    main_thread_raw: str | None = None,
    render_thread_raw: str | None = None,
    thread_cpu_raw: str | None = None,
    trace_bounds_raw: str | None = None,
    compose_recomp_raw: str | None = None,
    memory_raw: str | None = None,
    refresh_hz: float | None = None,
    top_n: int = 10,
) -> dict[str, Any]:
    """Combine parsed ``trace_processor_shell`` query outputs into normalized metrics.

    ``ui_thread_ms`` and ``render_thread_ms`` are populated exclusively from
    full-window ``sched_slice`` CPU accounting (``thread_cpu_raw``) and are
    never fabricated by summing truncated ``LIMIT 25`` hotspot rows.
    """
    result: dict[str, Any] = {
        "samples": 0,
        "frame_p50_ms": None,
        "frame_p95_ms": None,
        "frame_p99_ms": None,
        "worst_frame_ms": None,
        "slow_frame_count": None,
        "slow_frame_ratio": None,
        "window_ms": None,
        "ui_thread_ms": None,
        "render_thread_ms": None,
        "compose_recomp_count": None,
        "mem_peak_mb": None,
        "top_hotspots": [],
    }

    if frame_timeline_raw:
        hz = _require_refresh_hz(refresh_hz)
        frame_rows = parse_trace_processor_output(frame_timeline_raw)
        durations: list[float] = []
        jank_flags: list[bool] = []
        has_jank_col = False
        for row in frame_rows:
            dur = row.get("dur_ms")
            if isinstance(dur, (int, float)) and not isinstance(dur, bool) and float(dur) > 0:
                durations.append(float(dur))
                if "is_jank" in row and row["is_jank"] is not None:
                    has_jank_col = True
                    jank_flags.append(bool(int(row["is_jank"])))
                elif "jank_type" in row and row["jank_type"] is not None:
                    has_jank_col = True
                    jt = str(row["jank_type"]).strip()
                    jank_flags.append(jt not in ("", "None", "0"))
                else:
                    jank_flags.append(float(dur) > (1000.0 / hz) + 1e-6)
        frame_summary = _summarize_frame_durations(
            durations,
            refresh_hz=hz,
            jank_flags=jank_flags if has_jank_col else None,
        )
        result.update(frame_summary)

    hotspots: list[dict[str, Any]] = []
    if main_thread_raw:
        main_rows = parse_trace_processor_output(main_thread_raw)
        hotspots.extend(_extract_hotspots(main_rows, default_thread="main"))

    if render_thread_raw:
        rt_rows = parse_trace_processor_output(render_thread_raw)
        hotspots.extend(_extract_hotspots(rt_rows, default_thread="RenderThread"))

    hotspots.sort(key=lambda h: (-h["self_ms"], -h["total_ms"], h["symbol"]))
    result["top_hotspots"] = hotspots[:top_n]

    if thread_cpu_raw:
        cpu_rows = parse_trace_processor_output(thread_cpu_raw)
        ui_ms, rt_ms = _parse_thread_cpu_rows(cpu_rows)
        result["ui_thread_ms"] = ui_ms
        result["render_thread_ms"] = rt_ms

    if trace_bounds_raw:
        bound_rows = parse_trace_processor_output(trace_bounds_raw)
        for row in bound_rows:
            w_raw = row.get("window_ms")
            if isinstance(w_raw, (int, float)) and not isinstance(w_raw, bool) and math.isfinite(float(w_raw)) and float(w_raw) > 0:
                result["window_ms"] = round(float(w_raw), 3)
                break

    if compose_recomp_raw:
        recomp_rows = parse_trace_processor_output(compose_recomp_raw)
        if recomp_rows:
            cnt = recomp_rows[0].get("recomp_count")
            if isinstance(cnt, int) and not isinstance(cnt, bool) and cnt >= 0:
                result["compose_recomp_count"] = cnt

    if memory_raw:
        mem_rows = parse_trace_processor_output(memory_raw)
        peaks = [
            float(r["mem_peak_mb"])
            for r in mem_rows
            if isinstance(r.get("mem_peak_mb"), (int, float))
            and not isinstance(r.get("mem_peak_mb"), bool)
            and float(r["mem_peak_mb"]) > 0
        ]
        if peaks:
            result["mem_peak_mb"] = round(max(peaks), 2)

    return result


# ---------------------------------------------------------------------------
# Zero-dependency dumpsys / logcat fallback parsers
# ---------------------------------------------------------------------------

_GFX_TOTAL_RE = re.compile(r"Total frames rendered:[ \t]*(\d+)")
_GFX_JANKY_RE = re.compile(r"Janky frames:[ \t]*(\d+)")
_GFX_JANKY_LEGACY_RE = re.compile(r"Janky frames \(legacy\):[ \t]*(\d+)")
_GFX_HISTOGRAM_LINE_RE = re.compile(r"^HISTOGRAM:[ \t]*(.+)$", re.MULTILINE)
_GFX_HISTOGRAM_BUCKET_RE = re.compile(r"(\d+(?:\.\d+)?)ms=(\d+)")
_GFX_PCT_RE = re.compile(r"(\d+)th percentile:[ \t]*(\d+(?:\.\d+)?)ms")

_MEMINFO_TOTAL_PSS_RE = re.compile(r"TOTAL PSS:[ \t]*(\d+)")
_MEMINFO_TOTAL_ROW_RE = re.compile(r"^[ \t]*TOTAL[ \t]+(\d+)\b", re.MULTILINE)

_THERMAL_STATUS_INT_MAP: dict[int, str] = {
    0: "none",
    1: "light",
    2: "moderate",
    3: "severe",
    4: "critical",
    5: "emergency",
    6: "shutdown",
}
_THERMAL_NAME_TO_INT: dict[str, int] = {v: k for k, v in _THERMAL_STATUS_INT_MAP.items()}
_THERMAL_STATUS_NAMES: frozenset[str] = frozenset(_THERMAL_STATUS_INT_MAP.values())
_THERMAL_WHOLE_DEVICE_RE = re.compile(
    r"(?:Current status|Thermal status)[ \t]*[:=][ \t]*([A-Za-z0-9_]+)", re.IGNORECASE
)
_THERMAL_SENSOR_MSTATUS_RE = re.compile(
    r"\bmStatus[ \t]*[:=][ \t]*([A-Za-z0-9_]+)", re.IGNORECASE
)

_ACTIVE_MODE_ID_RE = re.compile(r"\b(?:mActiveModeId|activeModeId)[ \t]*[:=][ \t]*(\d+)")
_DISPLAY_MODE_RE = re.compile(
    r"DisplayModeRecord\{[^}]*id=(\d+)[^}]*fps=(\d+(?:\.\d+)?)"
)
_REFRESH_FALLBACK_RE = re.compile(
    r"(?:renderFrameRate|refreshRate|vsyncRate)[ \t]*[:= \t]+(\d+(?:\.\d+)?)"
)

_KV_TOKEN_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)=([^\s,;]+)")


def parse_dumpsys_gfxinfo_framestats(
    text: str,
    refresh_hz: float | None = None,
    *,
    exclude_window_layout_frames: bool = False,
) -> dict[str, Any]:
    """Parse ``dumpsys gfxinfo <pkg> framestats`` output.

    Parses both the full-window summary block and the ``---PROFILEDATA---``
    120-frame ring buffer when present:
    - If ``summary_total_frames > len(profiledata_frames)``, uses full-window
      metrics from the summary section, sets ``worst_frame_ms = None``, and
      flags ``gfxinfo_profiledata_truncated = True``.
    - In summary-only fallback, keeps ``worst_frame_ms = None`` (never
      substitutes p99).
    - Does not blanket-drop ``Flags != 0`` frames; filters by timestamp
      validity and only skips ``(flags & 1) != 0`` (``FLAG_WINDOW_LAYOUT_CHANGED``)
      when ``exclude_window_layout_frames=True``.
    """
    hz = _require_refresh_hz(refresh_hz)
    empty = _summarize_frame_durations([], refresh_hz=hz)
    empty["gfxinfo_profiledata_truncated"] = False
    empty["gfxinfo_profiledata_frame_count"] = 0
    if not text or not text.strip():
        return empty

    # 1. Parse PROFILEDATA block if present.
    profiledata_durations: list[float] = []
    marker = "---PROFILEDATA---"
    summary_text = text
    if marker in text:
        parts = text.split(marker)
        summary_text = parts[0] + ("\n" + "\n".join(parts[2:]) if len(parts) > 2 else "")
        if len(parts) >= 2:
            block = parts[1].strip()
            lines = [ln.strip() for ln in block.splitlines() if ln.strip()]
            if len(lines) >= 2:
                header = [h.strip() for h in lines[0].rstrip(",").split(",")]
                col_idx = {name: i for i, name in enumerate(header)}
                flags_i = col_idx.get("Flags")
                intended_i = col_idx.get("IntendedVsync")
                completed_i = col_idx.get("FrameCompleted")
                total_dur_i = col_idx.get("TotalDuration")

                for line in lines[1:]:
                    fields = [f.strip() for f in line.rstrip(",").split(",")]
                    if len(fields) < 4:
                        continue
                    try:
                        flags = (
                            int(fields[flags_i])
                            if flags_i is not None and flags_i < len(fields)
                            else 0
                        )
                        if exclude_window_layout_frames and (flags & 1) != 0:
                            continue
                        if (
                            intended_i is not None
                            and completed_i is not None
                            and intended_i < len(fields)
                            and completed_i < len(fields)
                        ):
                            intended = int(fields[intended_i])
                            completed = int(fields[completed_i])
                            if intended <= 0 or completed <= intended:
                                continue
                            dur_ms = (completed - intended) / 1_000_000.0
                        elif total_dur_i is not None and total_dur_i < len(fields):
                            total_ns = int(fields[total_dur_i])
                            if total_ns <= 0:
                                continue
                            dur_ms = total_ns / 1_000_000.0
                        else:
                            continue
                    except ValueError:
                        continue
                    if 0.0 < dur_ms < 60_000.0:
                        profiledata_durations.append(dur_ms)

    # 2. Always parse summary lines when present.
    total_match = _GFX_TOTAL_RE.search(summary_text)
    summary_total_frames = int(total_match.group(1)) if total_match else 0
    summary_metrics: dict[str, Any] | None = None
    if summary_total_frames > 0:
        budget_ms = 1000.0 / hz
        janky_match = _GFX_JANKY_RE.search(summary_text)
        legacy_match = _GFX_JANKY_LEGACY_RE.search(summary_text)
        slow_count = int(janky_match.group(1)) if janky_match else None
        legacy_count = int(legacy_match.group(1)) if legacy_match else None
        janky_source: str | None = None

        pcts: dict[int, float] = {}
        for match in _GFX_PCT_RE.finditer(summary_text):
            pct = int(match.group(1))
            val = float(match.group(2))
            if val > 0:
                pcts[pct] = val

        # Android 12+ virtual displays (e.g. redroid/Wuying where GpuCompleted=-1)
        # report `Janky frames: 0 (0.00%)` while recording real CPU frame times in
        # `HISTOGRAM:` and `Janky frames (legacy): N`. Recover refresh-rate-aware
        # slow frames from `HISTOGRAM:` (or `legacy` when `HISTOGRAM:` is absent).
        if slow_count is None or slow_count == 0:
            hist_line_match = _GFX_HISTOGRAM_LINE_RE.search(summary_text)
            hist_buckets = (
                [
                    (float(m.group(1)), int(m.group(2)))
                    for m in _GFX_HISTOGRAM_BUCKET_RE.finditer(hist_line_match.group(1))
                ]
                if hist_line_match
                else []
            )
            hist_total = sum(cnt for _, cnt in hist_buckets)
            if hist_total > 0:
                hist_slow = sum(cnt for b_ms, cnt in hist_buckets if b_ms > budget_ms + 1e-6)
                if slow_count is None or hist_slow > 0:
                    slow_count = min(hist_slow, summary_total_frames)
                    janky_source = "histogram_fallback"
            elif legacy_count is not None and legacy_count > 0:
                p95 = pcts.get(95)
                p99 = pcts.get(99)
                pct_exceeds_budget = (
                    (p95 is not None and p95 > budget_ms + 1e-6)
                    or (p99 is not None and p99 > budget_ms + 1e-6)
                )
                if pct_exceeds_budget or hz >= 59.0:
                    slow_count = min(legacy_count, summary_total_frames)
                    janky_source = "legacy_fallback"

        slow_ratio = round(slow_count / summary_total_frames, 6) if slow_count is not None else None

        summary_metrics = {
            "samples": summary_total_frames,
            "frame_p50_ms": pcts.get(50),
            "frame_p95_ms": pcts.get(95),
            "frame_p99_ms": pcts.get(99),
            "worst_frame_ms": None,
            "slow_frame_count": slow_count,
            "slow_frame_ratio": slow_ratio,
            "gfxinfo_janky_source": janky_source,
            "gfxinfo_janky_legacy_count": legacy_count if janky_source is not None else None,
        }

    # 3. Reconcile PROFILEDATA vs full-window summary.
    profiledata_count = len(profiledata_durations)
    if profiledata_count > 0 and summary_metrics is not None and summary_total_frames > profiledata_count:
        out = dict(summary_metrics)
        out["gfxinfo_profiledata_truncated"] = True
        out["gfxinfo_profiledata_frame_count"] = profiledata_count
        return out

    if profiledata_count > 0:
        out = _summarize_frame_durations(profiledata_durations, refresh_hz=hz)
        out["gfxinfo_profiledata_truncated"] = False
        out["gfxinfo_profiledata_frame_count"] = profiledata_count
        return out

    if summary_metrics is not None:
        out = dict(summary_metrics)
        out["gfxinfo_profiledata_truncated"] = False
        out["gfxinfo_profiledata_frame_count"] = 0
        return out

    return empty


def parse_dumpsys_meminfo(text: str) -> float | None:
    """Parse ``dumpsys meminfo <pkg>`` output and return peak/total PSS in MB."""
    if not text:
        return None
    match = _MEMINFO_TOTAL_PSS_RE.search(text)
    if not match:
        match = _MEMINFO_TOTAL_ROW_RE.search(text)
    if not match:
        return None
    kb = int(match.group(1))
    if kb <= 0:
        return None
    return round(kb / 1024.0, 2)


def _parse_android_thermal_token(raw_token: str) -> str | None:
    token = raw_token.strip().lower()
    if token.startswith("thermal_status_"):
        token = token[len("thermal_status_") :]
    if token in _THERMAL_STATUS_NAMES:
        return token
    try:
        code = int(token)
    except ValueError:
        return None
    return _THERMAL_STATUS_INT_MAP.get(code)


def parse_dumpsys_thermalservice(text: str) -> str | None:
    """Parse ``dumpsys thermalservice`` output into a canonical Android thermal level.

    Matches whole-device ``Current status:`` / ``Thermal status:`` first
    (stripping optional ``THERMAL_STATUS_`` prefix). Only when absent, takes
    the maximum severity tier across all ``mStatus`` sensor occurrences so an
    early cold sensor never masks a hot device.
    """
    if not text:
        return None

    for match in _THERMAL_WHOLE_DEVICE_RE.finditer(text):
        parsed = _parse_android_thermal_token(match.group(1))
        if parsed is not None:
            return parsed

    sensor_tiers: list[int] = []
    for match in _THERMAL_SENSOR_MSTATUS_RE.finditer(text):
        parsed = _parse_android_thermal_token(match.group(1))
        if parsed is not None:
            sensor_tiers.append(_THERMAL_NAME_TO_INT[parsed])
    if sensor_tiers:
        return _THERMAL_STATUS_INT_MAP[max(sensor_tiers)]
    return None


def _clean_refresh_hz(raw_hz: float) -> float:
    nearest = round(raw_hz)
    if abs(raw_hz - nearest) <= 0.05 and nearest > 0:
        return float(nearest)
    return round(raw_hz, 2)


def parse_dumpsys_display_refresh_hz(text: str) -> float | None:
    """Extract the active display refresh rate (Hz) from ``dumpsys display``."""
    if not text:
        return None
    modes: dict[int, float] = {}
    for match in _DISPLAY_MODE_RE.finditer(text):
        mode_id = int(match.group(1))
        fps = float(match.group(2))
        if fps > 0:
            modes[mode_id] = fps
    active_match = _ACTIVE_MODE_ID_RE.search(text)
    if active_match and int(active_match.group(1)) in modes:
        return _clean_refresh_hz(modes[int(active_match.group(1))])
    fallback = _REFRESH_FALLBACK_RE.search(text)
    if fallback:
        val = float(fallback.group(1))
        if val > 0:
            return _clean_refresh_hz(val)
    if modes:
        return _clean_refresh_hz(next(iter(modes.values())))
    return None


def _parse_kv_pairs(line: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, raw_val in _KV_TOKEN_RE.findall(line):
        cleaned = raw_val.rstrip(".,;)")
        out[key] = _coerce_scalar(cleaned)
    return out


def parse_android_logcat(text: str, *, package: str = ANDROID_PACKAGE) -> dict[str, Any]:
    """Parse Raft Android perf logcat markers into top-level metrics and unit-suffixed segments.

    Recognizes:
    - ``open_trace_summary`` and ``open_trace_shift`` lines from ``ThreadOpenTrace.kt``
    - ``SlockPerf`` markers and ``operation_finish`` / ``operation_slow`` / ``jank_frame``
      lines from ``RaftPerformanceDiagnostics.kt``
    - ``ActivityTaskManager`` ``Displayed <package>/...: +NNNms`` -> ``segments["ttid_ms"]``
      and ``segments["compose_first_frame_ms"]`` (never ``ttfd_ms``)
    - ``ActivityTaskManager`` ``Fully drawn <package>/...: +NNNms`` -> ``ttfd_ms``
    - Generic ``SLOCK_PERF_MARKER`` / ``mobile_perf`` key=value marker lines
    """
    segments: dict[str, Any] = {}
    duration_ms: float | None = None
    fully_drawn_ms: float | None = None
    explicit_ttfd_ms: float | None = None
    first_feed_render_ttfd_ms: float | None = None
    reading_anchor_attempts = 0
    shift_dp_fallback = 0.0
    shift_count_fallback = 0
    has_summary_shift = False
    jank_log_count = 0

    if not text:
        return {"duration_ms": None, "ttfd_ms": None, "segments": {}}

    pkg_escaped = re.escape(package)
    displayed_re = re.compile(
        rf"\bDisplayed\s+{pkg_escaped}/\S+(?:\s+for\s+user\s+\d+)?:\s*\+(?:(\d+)s)?(\d+)ms"
    )
    fully_drawn_re = re.compile(
        rf"\bFully\s+drawn\s+{pkg_escaped}/\S+(?:\s+for\s+user\s+\d+)?:\s*\+(?:(\d+)s)?(\d+)ms"
    )

    for line in text.splitlines():
        disp = displayed_re.search(line)
        if disp:
            secs = int(disp.group(1)) if disp.group(1) else 0
            ms = int(disp.group(2))
            total_ms = float(secs * 1000 + ms)
            if total_ms > 0:
                segments["ttid_ms"] = total_ms
                segments["compose_first_frame_ms"] = total_ms

        fd = fully_drawn_re.search(line)
        if fd:
            secs = int(fd.group(1)) if fd.group(1) else 0
            ms = int(fd.group(2))
            total_ms = float(secs * 1000 + ms)
            if total_ms > 0:
                fully_drawn_ms = total_ms

        kv = _parse_kv_pairs(line)

        if "open_trace_summary" in line:
            dur = kv.get("duration_ms")
            if isinstance(dur, (int, float)) and not isinstance(dur, bool) and float(dur) > 0:
                duration_ms = float(dur)
            for seg_key in (
                "cache_rows_shown_ms",
                "network_applied_ms",
                "first_frame_ms",
                "transition_end_ms",
                "target_positioned_ms",
                "stable_ms",
                "time_to_stable_ms",
                "shift_count",
            ):
                val = kv.get(seg_key)
                if isinstance(val, (int, float)) and not isinstance(val, bool) and float(val) >= 0:
                    segments[seg_key] = val
            if isinstance(kv.get("shift_sum_dp"), (int, float)) and not isinstance(kv.get("shift_sum_dp"), bool):
                segments["visible_shift_dp"] = round(float(kv["shift_sum_dp"]), 2)
                has_summary_shift = True
            if isinstance(kv.get("shift_max_dp"), (int, float)) and not isinstance(kv.get("shift_max_dp"), bool):
                segments["shift_max_dp"] = round(float(kv["shift_max_dp"]), 2)
            if isinstance(kv.get("shifts_after_positioned"), int) and not isinstance(
                kv.get("shifts_after_positioned"), bool
            ):
                segments["shifts_after_positioned_count"] = kv["shifts_after_positioned"]
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

        elif any(tag in line for tag in ("SlockPerf", "SLOCK_PERF_MARKER", "mobile_perf", "reading_anchor")):
            if "jank_frame" in line:
                jank_log_count += 1
            if "reading_anchor" in line and "reading_anchor_attempt_count" not in kv:
                attempt = kv.get("attempt") or kv.get("owner_attempt")
                if isinstance(attempt, int) and not isinstance(attempt, bool) and attempt > 0:
                    reading_anchor_attempts = max(reading_anchor_attempts, attempt)
                elif kv.get("cause") == "reading_anchor" or "reading_anchor_attempt" in line:
                    reading_anchor_attempts += 1

            # Extract top-level latency hints and valid unit-suffixed segments.
            if "duration_ms" in kv and isinstance(kv["duration_ms"], (int, float)) and not isinstance(kv["duration_ms"], bool) and float(kv["duration_ms"]) > 0:
                if duration_ms is None:
                    duration_ms = float(kv["duration_ms"])
            elif "durationMs" in kv and isinstance(kv["durationMs"], (int, float)) and not isinstance(kv["durationMs"], bool) and float(kv["durationMs"]) > 0:
                phase = str(kv.get("phase") or kv.get("operation") or "")
                if (
                    phase in ("thread_open", "channel_switch", "composer_first_char", "webview_open", "foreground_resume")
                    or "operation_finish" in line
                    or "operation_slow" in line
                ):
                    if duration_ms is None:
                        duration_ms = float(kv["durationMs"])
                if phase.startswith("bottom_tab_"):
                    segments["recompose_layout_ms"] = float(kv["durationMs"])

            if "ttfd_ms" in kv and isinstance(kv["ttfd_ms"], (int, float)) and not isinstance(kv["ttfd_ms"], bool) and float(kv["ttfd_ms"]) > 0:
                explicit_ttfd_ms = float(kv["ttfd_ms"])

            if (
                "first_feed_render_ms" in kv
                and isinstance(kv["first_feed_render_ms"], (int, float))
                and not isinstance(kv["first_feed_render_ms"], bool)
                and float(kv["first_feed_render_ms"]) > 0
            ):
                first_feed_render_ttfd_ms = float(kv["first_feed_render_ms"])

            for key, val in kv.items():
                if key in ("duration_ms", "ttfd_ms"):
                    continue
                if isinstance(val, (int, float)) and not isinstance(val, bool) and key.endswith(SEGMENT_UNIT_SUFFIXES):
                    segments[key] = val

    if reading_anchor_attempts > 0 and "reading_anchor_attempt_count" not in segments:
        segments["reading_anchor_attempt_count"] = reading_anchor_attempts
    if not has_summary_shift and shift_count_fallback > 0:
        segments.setdefault("shift_count", shift_count_fallback)
        segments.setdefault("visible_shift_dp", round(shift_dp_fallback, 2))
    if jank_log_count > 0:
        segments.setdefault("jank_log_count", jank_log_count)

    ttfd_ms = (
        fully_drawn_ms
        if fully_drawn_ms is not None
        else explicit_ttfd_ms
        if explicit_ttfd_ms is not None
        else first_feed_render_ttfd_ms
    )

    return {
        "duration_ms": duration_ms,
        "ttfd_ms": ttfd_ms,
        "segments": segments,
    }


def compute_sha256_bytes(data: bytes) -> str:
    """Return lowercase 64-char SHA-256 hex digest over raw bytes."""
    return hashlib.sha256(data).hexdigest()


def parse_android_capture(
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
    package: str = ANDROID_PACKAGE,
    layout_mode: str = "phone",
    warm_state: str = "profile_warm",
    fixture_tag: str = DEFAULT_FIXTURE_TAG,
    env: str = "staging",
    project: str = "raft-android",
    run_kind: str = "baseline",
    ab_session_id: str | None = None,
    ab_arm: str | None = None,
    iteration: int | None = None,
    calibration_injected_ms: float | None = None,
    refresh_hz: float | None = None,
    thermal_start: str | None = None,
    thermal_end: str | None = None,
    duration_ms: float | None = None,
    ttfd_ms: float | None = None,
    window_ms: float | None = None,
    gfxinfo_text: str | None = None,
    meminfo_text: str | None = None,
    thermal_start_text: str | None = None,
    thermal_end_text: str | None = None,
    display_text: str | None = None,
    logcat_text: str | None = None,
    exclude_window_layout_frames: bool = False,
    perfetto_frame_timeline: str | None = None,
    perfetto_main_thread: str | None = None,
    perfetto_render_thread: str | None = None,
    perfetto_thread_cpu: str | None = None,
    perfetto_trace_bounds: str | None = None,
    perfetto_compose_recomp: str | None = None,
    perfetto_memory: str | None = None,
    trace_bytes: bytes | None = None,
    trace_path: str | Path | None = None,
    trace_link: str | None = None,
    diagnose_run_id: str | None = None,
    taken_at: str | None = None,
    extra_segments: Mapping[str, Any] | None = None,
    extra: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Parse Android Perfetto and/or dumpsys+logcat captures into validated ScopeDB rows.

    Returns ``(run_row, diagnose_row)``. Absent metrics are strictly ``None``
    (never ``0``), and zero-sample captures emit ``valid=False`` while retaining
    both rows. ``refresh_hz`` must be supplied explicitly or parsed from
    ``display_text`` (no silent 60Hz fallback).
    """
    resolved_hz_raw = refresh_hz
    if resolved_hz_raw is None and display_text:
        resolved_hz_raw = parse_dumpsys_display_refresh_hz(display_text)
    resolved_hz = _require_refresh_hz(resolved_hz_raw)

    resolved_thermal_start = thermal_start or (
        parse_dumpsys_thermalservice(thermal_start_text) if thermal_start_text else None
    )
    resolved_thermal_end = thermal_end or (
        parse_dumpsys_thermalservice(thermal_end_text) if thermal_end_text else None
    )

    perfetto_metrics = parse_perfetto_query_results(
        frame_timeline_raw=perfetto_frame_timeline,
        main_thread_raw=perfetto_main_thread,
        render_thread_raw=perfetto_render_thread,
        thread_cpu_raw=perfetto_thread_cpu,
        trace_bounds_raw=perfetto_trace_bounds,
        compose_recomp_raw=perfetto_compose_recomp,
        memory_raw=perfetto_memory,
        refresh_hz=resolved_hz,
    )
    gfx_metrics = (
        parse_dumpsys_gfxinfo_framestats(
            gfxinfo_text,
            refresh_hz=resolved_hz,
            exclude_window_layout_frames=exclude_window_layout_frames,
        )
        if gfxinfo_text
        else {
            **_summarize_frame_durations([], refresh_hz=resolved_hz),
            "gfxinfo_profiledata_truncated": False,
            "gfxinfo_profiledata_frame_count": 0,
        }
    )
    # Prefer Perfetto frame timeline when present; fall back seamlessly to dumpsys gfxinfo.
    used_perfetto_frames = perfetto_metrics["samples"] > 0
    frame_metrics = perfetto_metrics if used_perfetto_frames else gfx_metrics

    mem_peak_mb = perfetto_metrics["mem_peak_mb"]
    if mem_peak_mb is None and meminfo_text:
        mem_peak_mb = parse_dumpsys_meminfo(meminfo_text)

    logcat_parsed = parse_android_logcat(logcat_text or "", package=package)
    resolved_duration_ms = duration_ms if duration_ms is not None else logcat_parsed["duration_ms"]
    resolved_ttfd_ms = ttfd_ms if ttfd_ms is not None else logcat_parsed["ttfd_ms"]
    resolved_window_ms = window_ms if window_ms is not None else perfetto_metrics["window_ms"]

    segments: dict[str, Any] = dict(logcat_parsed["segments"])
    if frame_metrics["samples"] > 0:
        segments["frame_count"] = frame_metrics["samples"]
    if extra_segments:
        segments.update(extra_segments)

    trace_sha256: str | None = None
    if trace_bytes is not None:
        trace_sha256 = compute_sha256_bytes(trace_bytes)
    elif trace_path is not None:
        trace_sha256 = compute_sha256_bytes(Path(trace_path).read_bytes())

    samples = int(frame_metrics["samples"])
    throttled, thermal_invalid_reason = is_thermal_throttled(
        resolved_thermal_start, resolved_thermal_end
    )
    valid = (samples > 0) and (not throttled)
    timestamp = taken_at or utc_now_iso()

    has_perfetto = any(
        bool(x and str(x).strip())
        for x in (
            perfetto_frame_timeline,
            perfetto_main_thread,
            perfetto_render_thread,
            perfetto_thread_cpu,
            perfetto_trace_bounds,
            perfetto_compose_recomp,
            perfetto_memory,
        )
    )
    has_dumpsys = any(
        bool(x and str(x).strip())
        for x in (gfxinfo_text, meminfo_text, thermal_start_text, thermal_end_text, display_text)
    )
    if has_perfetto and has_dumpsys:
        capture_mode = "perfetto+dumpsys"
    elif has_perfetto:
        capture_mode = "perfetto"
    else:
        capture_mode = "dumpsys_fallback"

    extra_dict = dict(extra) if extra else {}
    extra_dict.setdefault("capture_mode", capture_mode)
    if throttled and thermal_invalid_reason:
        extra_dict["thermal_invalid_reason"] = thermal_invalid_reason
    if not used_perfetto_frames and gfx_metrics.get("gfxinfo_profiledata_truncated"):
        extra_dict["gfxinfo_profiledata_truncated"] = True
        extra_dict["gfxinfo_profiledata_frame_count"] = gfx_metrics["gfxinfo_profiledata_frame_count"]
    if not used_perfetto_frames and gfx_metrics.get("gfxinfo_janky_source"):
        extra_dict["gfxinfo_janky_source"] = gfx_metrics["gfxinfo_janky_source"]
        if gfx_metrics.get("gfxinfo_janky_legacy_count") is not None:
            extra_dict["gfxinfo_janky_legacy_count"] = gfx_metrics["gfxinfo_janky_legacy_count"]

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
        frame_p50_ms=frame_metrics["frame_p50_ms"],
        frame_p95_ms=frame_metrics["frame_p95_ms"],
        frame_p99_ms=frame_metrics["frame_p99_ms"],
        slow_frame_ratio=frame_metrics["slow_frame_ratio"],
        hitch_ms_per_s=None,
        mem_peak_mb=mem_peak_mb,
        **common,
    )

    diagnose_row = make_diagnose_row(
        run_id=diagnose_run_id or f"{run_id}-diag",
        window_ms=resolved_window_ms if (resolved_window_ms is not None and resolved_window_ms > 0) else None,
        ui_thread_ms=perfetto_metrics["ui_thread_ms"],
        render_thread_ms=perfetto_metrics["render_thread_ms"],
        metal_ms=None,
        worst_frame_ms=frame_metrics["worst_frame_ms"],
        slow_frame_count=frame_metrics["slow_frame_count"],
        compose_recomp_count=perfetto_metrics["compose_recomp_count"],
        top_hotspots=perfetto_metrics["top_hotspots"],
        trace_sha256=trace_sha256,
        trace_link=trace_link,
        **common,
    )

    run_errors = validate_run_row(run_row)
    if run_errors:
        raise ValueError("Generated invalid Android mobile_runs row: " + "; ".join(run_errors))
    diag_errors = validate_diagnose_row(diagnose_row)
    if diag_errors:
        raise ValueError("Generated invalid Android mobile_diagnose_runs row: " + "; ".join(diag_errors))

    return run_row, diagnose_row
