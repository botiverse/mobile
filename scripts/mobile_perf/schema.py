"""ScopeDB schema contract, cohort-key builder, and JSONL validators.

Authoritative DDL targets:
- ``scopedb.perf.mobile_runs`` (38 columns)
- ``scopedb.perf.mobile_diagnose_runs`` (40 columns)
"""

from __future__ import annotations

import json
import math
import re
from collections.abc import Iterable, Mapping
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

TOOL_VERSION = "mobile-perf/0.1.0"

MOBILE_RUNS_TABLE = "scopedb.perf.mobile_runs"
MOBILE_DIAGNOSE_RUNS_TABLE = "scopedb.perf.mobile_diagnose_runs"

_COMMON_COLUMNS: tuple[str, ...] = (
    "project",
    "env",
    "run_id",
    "tool_version",
    "taken_at",
    "commit_sha",
    "app_version",
    "app_build",
    "build_type",
    "scenario",
    "run_kind",
    "ab_session_id",
    "ab_arm",
    "iteration",
    "calibration_injected_ms",
    "cohort_key",
    "device_id",
    "device_model",
    "os_version",
    "layout_mode",
    "refresh_hz",
    "warm_state",
    "fixture_tag",
    "thermal_start",
    "thermal_end",
    "samples",
    "valid",
)

MOBILE_RUNS_COLUMNS: tuple[str, ...] = _COMMON_COLUMNS + (
    "duration_ms",
    "ttfd_ms",
    "frame_p50_ms",
    "frame_p95_ms",
    "frame_p99_ms",
    "slow_frame_ratio",
    "hitch_ms_per_s",
    "mem_peak_mb",
    "segments",
    "verdict",
    "extra",
)

MOBILE_DIAGNOSE_RUNS_COLUMNS: tuple[str, ...] = _COMMON_COLUMNS + (
    "window_ms",
    "ui_thread_ms",
    "render_thread_ms",
    "metal_ms",
    "worst_frame_ms",
    "slow_frame_count",
    "compose_recomp_count",
    "top_hotspots",
    "segments",
    "trace_sha256",
    "trace_link",
    "verdict",
    "extra",
)

assert len(MOBILE_RUNS_COLUMNS) == 38
assert len(MOBILE_DIAGNOSE_RUNS_COLUMNS) == 40

SEGMENT_UNIT_SUFFIXES: tuple[str, ...] = ("_ms", "_mb", "_count", "_dp", "_ratio")

FORBIDDEN_COHORT_KEYS: tuple[str, ...] = (
    "app_version",
    "app_build",
    "commit_sha",
    "thermal_start",
    "thermal_end",
)

VALID_RUN_KINDS: frozenset[str] = frozenset({"baseline", "ab", "calibration", "diagnose"})
VALID_AB_ARMS: frozenset[str] = frozenset({"base", "head"})
ANDROID_THERMAL_STATES: frozenset[str] = frozenset(
    {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"}
)
IOS_THERMAL_STATES: frozenset[str] = frozenset({"nominal", "fair", "serious", "critical"})
PROJECT_THERMAL_STATES: dict[str, frozenset[str]] = {
    "raft-android": ANDROID_THERMAL_STATES,
    "raft-ios": IOS_THERMAL_STATES,
}
VALID_VERDICTS: frozenset[str] = frozenset(
    {
        "improved",
        "regressed",
        "neutral",
        "noisy",
        "insufficient_samples",
        "invalid_cohort",
        "pass",
        "fail",
    }
)

HOTSPOT_KEYS: frozenset[str] = frozenset({"symbol", "thread", "self_ms", "total_ms"})

_REQUIRED_NONEMPTY_STR_COMMON: tuple[str, ...] = (
    "project",
    "env",
    "run_id",
    "tool_version",
    "taken_at",
    "commit_sha",
    "app_version",
    "app_build",
    "build_type",
    "scenario",
    "run_kind",
    "cohort_key",
    "device_id",
    "device_model",
    "os_version",
    "layout_mode",
    "warm_state",
    "fixture_tag",
)

_RUN_STRICTLY_POSITIVE_METRICS: tuple[str, ...] = (
    "duration_ms",
    "ttfd_ms",
    "frame_p50_ms",
    "frame_p95_ms",
    "frame_p99_ms",
    "mem_peak_mb",
)

_RUN_FRAME_METRICS: tuple[str, ...] = (
    "frame_p50_ms",
    "frame_p95_ms",
    "frame_p99_ms",
    "slow_frame_ratio",
    "hitch_ms_per_s",
)

_DIAG_STRICTLY_POSITIVE_METRICS: tuple[str, ...] = (
    "window_ms",
    "ui_thread_ms",
    "render_thread_ms",
    "metal_ms",
    "worst_frame_ms",
)

_ISO_UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$")


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def utc_now_iso() -> str:
    """Return the current UTC timestamp formatted as ISO-8601 with trailing Z."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def is_valid_utc_iso(value: Any) -> bool:
    """Return True if value is an ISO-8601 UTC timestamp string ending in 'Z'."""
    if not isinstance(value, str) or not _ISO_UTC_RE.match(value):
        return False
    # Python datetime.fromisoformat supports up to 6 fractional digits on 3.10.
    base = value[:-1]
    if "." in base:
        head, frac = base.split(".", 1)
        base = f"{head}.{frac[:6]}"
    try:
        datetime.fromisoformat(base + "+00:00")
    except ValueError:
        return False
    return True


def format_refresh_hz_token(refresh_hz: int | float) -> str:
    """Format refresh rate cleanly for cohort_key (integer if whole, else trimmed decimal)."""
    if not _is_number(refresh_hz) or float(refresh_hz) <= 0:
        raise ValueError(f"refresh_hz must be a finite positive number, got {refresh_hz!r}")
    val = float(refresh_hz)
    if val.is_integer():
        return str(int(round(val)))
    return f"{val:.4f}".rstrip("0").rstrip(".")


def build_cohort_key(
    device_id: str,
    os_version: str,
    build_type: str,
    fixture_tag: str,
    layout_mode: str,
    refresh_hz: int | float,
    warm_state: str,
) -> str:
    """Build the canonical ScopeDB cohort_key string.

    Format:
        ``dev:{device_id}|os:{os_version}|build:{build_type}|fx:{fixture_tag}|layout:{layout_mode}|hz:{hz}|warm:{warm_state}``

    Intentionally excludes ``app_version``, ``app_build``, ``commit_sha``,
    ``thermal_start``, and ``thermal_end`` so longitudinal baselines and A/B
    arms remain comparable within the exact same hardware/software cohort.
    """
    parts = {
        "device_id": device_id,
        "os_version": os_version,
        "build_type": build_type,
        "fixture_tag": fixture_tag,
        "layout_mode": layout_mode,
        "warm_state": warm_state,
    }
    for name, raw in parts.items():
        if not isinstance(raw, str) or not raw.strip():
            raise ValueError(f"{name} must be a non-empty string, got {raw!r}")
        if "|" in raw:
            raise ValueError(f"{name} must not contain '|': {raw!r}")
    hz_token = format_refresh_hz_token(refresh_hz)
    return (
        f"dev:{device_id}|os:{os_version}|build:{build_type}|fx:{fixture_tag}"
        f"|layout:{layout_mode}|hz:{hz_token}|warm:{warm_state}"
    )


def parse_cohort_key(cohort_key: str) -> dict[str, str]:
    """Parse a pipe-delimited cohort_key into its ``{prefix: value}`` map."""
    if not isinstance(cohort_key, str) or not cohort_key:
        raise ValueError("cohort_key must be a non-empty string")
    parsed: dict[str, str] = {}
    for segment in cohort_key.split("|"):
        if ":" not in segment:
            raise ValueError(f"Malformed cohort_key segment (missing ':'): {segment!r}")
        key, value = segment.split(":", 1)
        if not key or not value or key in parsed:
            raise ValueError(f"Malformed or duplicate cohort_key segment: {segment!r}")
        parsed[key] = value
    return parsed


def _canonical_row(columns: tuple[str, ...], fields: Mapping[str, Any]) -> dict[str, Any]:
    return {col: fields.get(col) for col in columns}


def make_run_row(**fields: Any) -> dict[str, Any]:
    """Construct a canonical 38-column ``scopedb.perf.mobile_runs`` dict.

    Absent metrics default to ``None`` (never ``0``). If ``samples == 0``,
    ``valid`` is forced to ``False`` while keeping the row.
    """
    data = dict(fields)
    data.setdefault("tool_version", TOOL_VERSION)
    data.setdefault("taken_at", utc_now_iso())
    data.setdefault("segments", {})
    data.setdefault("extra", {})
    if "cohort_key" not in data or data["cohort_key"] is None:
        data["cohort_key"] = build_cohort_key(
            device_id=data["device_id"],
            os_version=data["os_version"],
            build_type=data["build_type"],
            fixture_tag=data["fixture_tag"],
            layout_mode=data["layout_mode"],
            refresh_hz=data["refresh_hz"],
            warm_state=data["warm_state"],
        )
    samples = data.get("samples", 0)
    data["samples"] = samples
    if samples == 0:
        data["valid"] = False
    else:
        data.setdefault("valid", True)
    return _canonical_row(MOBILE_RUNS_COLUMNS, data)


def make_diagnose_row(**fields: Any) -> dict[str, Any]:
    """Construct a canonical 40-column ``scopedb.perf.mobile_diagnose_runs`` dict.

    Absent metrics default to ``None`` (never ``0``). If ``samples == 0``,
    ``valid`` is forced to ``False`` while keeping the row.
    """
    data = dict(fields)
    data.setdefault("tool_version", TOOL_VERSION)
    data.setdefault("taken_at", utc_now_iso())
    data.setdefault("top_hotspots", [])
    data.setdefault("segments", {})
    data.setdefault("extra", {})
    if "cohort_key" not in data or data["cohort_key"] is None:
        data["cohort_key"] = build_cohort_key(
            device_id=data["device_id"],
            os_version=data["os_version"],
            build_type=data["build_type"],
            fixture_tag=data["fixture_tag"],
            layout_mode=data["layout_mode"],
            refresh_hz=data["refresh_hz"],
            warm_state=data["warm_state"],
        )
    samples = data.get("samples", 0)
    data["samples"] = samples
    if samples == 0:
        data["valid"] = False
    else:
        data.setdefault("valid", True)
    return _canonical_row(MOBILE_DIAGNOSE_RUNS_COLUMNS, data)


def _validate_columns(row: Mapping[str, Any], expected: tuple[str, ...]) -> list[str]:
    errors: list[str] = []
    expected_set = set(expected)
    actual_set = set(row.keys())
    missing = [col for col in expected if col not in actual_set]
    extra = sorted(actual_set - expected_set)
    if missing:
        errors.append(f"missing columns ({len(missing)}): {', '.join(missing)}")
    if extra:
        errors.append(f"unexpected columns ({len(extra)}): {', '.join(extra)}")
    return errors


def _validate_segments(segments: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(segments, Mapping):
        return [f"segments must be an object/dict, got {type(segments).__name__}"]
    for key, value in segments.items():
        if not isinstance(key, str) or not key.strip():
            errors.append(f"segments key must be a non-empty string, got {key!r}")
            continue
        if value is None:
            errors.append(f"segments.{key} must be omitted rather than null")
            continue
        if isinstance(value, bool):
            continue
        if isinstance(value, (int, float)):
            if not math.isfinite(float(value)):
                errors.append(f"segments.{key} must be finite, got {value!r}")
            if not key.endswith(SEGMENT_UNIT_SUFFIXES):
                errors.append(
                    f"segments.{key} is numeric and must end with one of {SEGMENT_UNIT_SUFFIXES}"
                )
        elif not isinstance(value, str):
            errors.append(
                f"segments.{key} has unsupported type {type(value).__name__} (expected number, string, or bool)"
            )
    return errors


def _validate_common(row: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []

    for col in _REQUIRED_NONEMPTY_STR_COMMON:
        val = row.get(col)
        if not isinstance(val, str) or not val.strip():
            errors.append(f"{col} must be a non-empty string, got {val!r}")

    taken_at = row.get("taken_at")
    if isinstance(taken_at, str) and taken_at.strip() and not is_valid_utc_iso(taken_at):
        errors.append(f"taken_at must be ISO-8601 UTC ending with 'Z', got {taken_at!r}")

    build_type = row.get("build_type")
    if isinstance(build_type, str) and "debug" in build_type.lower():
        errors.append(
            f"build_type must not be a debug build (got {build_type!r}); use a release/profileable build"
        )

    run_kind = row.get("run_kind")
    if isinstance(run_kind, str) and run_kind not in VALID_RUN_KINDS:
        errors.append(f"run_kind must be one of {sorted(VALID_RUN_KINDS)}, got {run_kind!r}")

    ab_session_id = row.get("ab_session_id")
    if ab_session_id is not None and (not isinstance(ab_session_id, str) or not ab_session_id.strip()):
        errors.append(f"ab_session_id must be null or a non-empty string, got {ab_session_id!r}")

    ab_arm = row.get("ab_arm")
    if ab_arm is not None and ab_arm not in VALID_AB_ARMS:
        errors.append(f"ab_arm must be null or one of {sorted(VALID_AB_ARMS)}, got {ab_arm!r}")

    iteration = row.get("iteration")
    if iteration is not None and (not _is_int(iteration) or iteration < 0):
        errors.append(f"iteration must be null or a non-negative integer, got {iteration!r}")

    if run_kind == "ab":
        if not ab_session_id:
            errors.append("run_kind='ab' requires ab_session_id")
        if ab_arm not in VALID_AB_ARMS:
            errors.append("run_kind='ab' requires ab_arm in {'base', 'head'}")
        if not _is_int(iteration) or iteration < 1:
            errors.append("run_kind='ab' requires iteration >= 1")

    cal_ms = row.get("calibration_injected_ms")
    if cal_ms is not None and (not _is_number(cal_ms) or float(cal_ms) < 0):
        errors.append(
            f"calibration_injected_ms must be null or a non-negative number, got {cal_ms!r}"
        )
    elif (
        run_kind in ("baseline", "ab", "diagnose")
        and cal_ms is not None
        and _is_number(cal_ms)
        and float(cal_ms) > 0
    ):
        errors.append(
            f"run_kind={run_kind!r} must not have calibration_injected_ms > 0 (got {cal_ms!r})"
        )

    refresh_hz = row.get("refresh_hz")
    if not _is_number(refresh_hz) or float(refresh_hz) <= 0:
        errors.append(f"refresh_hz must be a positive number, got {refresh_hz!r}")

    # Cohort key verification.
    cohort_key = row.get("cohort_key")
    if isinstance(cohort_key, str) and cohort_key.strip():
        for forbidden in FORBIDDEN_COHORT_KEYS:
            if forbidden in cohort_key:
                errors.append(f"cohort_key must never include {forbidden!r}: {cohort_key!r}")
        try:
            expected_cohort = build_cohort_key(
                device_id=row.get("device_id"),  # type: ignore[arg-type]
                os_version=row.get("os_version"),  # type: ignore[arg-type]
                build_type=row.get("build_type"),  # type: ignore[arg-type]
                fixture_tag=row.get("fixture_tag"),  # type: ignore[arg-type]
                layout_mode=row.get("layout_mode"),  # type: ignore[arg-type]
                refresh_hz=row.get("refresh_hz"),  # type: ignore[arg-type]
                warm_state=row.get("warm_state"),  # type: ignore[arg-type]
            )
        except ValueError as exc:
            errors.append(f"cannot build expected cohort_key from row fields: {exc}")
        else:
            if cohort_key != expected_cohort:
                errors.append(
                    f"cohort_key mismatch: got {cohort_key!r}, expected {expected_cohort!r}"
                )

    project = row.get("project")
    allowed_thermal = PROJECT_THERMAL_STATES.get(project) if isinstance(project, str) else None
    for thermal_col in ("thermal_start", "thermal_end"):
        tval = row.get(thermal_col)
        if tval is not None:
            if not isinstance(tval, str) or not tval.strip():
                errors.append(f"{thermal_col} must be null or a non-empty string, got {tval!r}")
            elif allowed_thermal is not None and tval not in allowed_thermal:
                errors.append(
                    f"{thermal_col} for {project} must be one of {sorted(allowed_thermal)}, got {tval!r}"
                )

    samples = row.get("samples")
    if not _is_int(samples) or samples < 0:
        errors.append(f"samples must be a non-negative integer, got {samples!r}")

    valid = row.get("valid")
    if not isinstance(valid, bool):
        errors.append(f"valid must be a boolean, got {valid!r}")
    elif _is_int(samples) and samples == 0 and valid is True:
        errors.append("samples == 0 requires valid=False (never mark zero-sample rows valid)")

    errors.extend(_validate_segments(row.get("segments")))

    verdict = row.get("verdict")
    if verdict is not None and (not isinstance(verdict, str) or verdict not in VALID_VERDICTS):
        errors.append(f"verdict must be null or one of {sorted(VALID_VERDICTS)}, got {verdict!r}")

    extra = row.get("extra")
    if not isinstance(extra, Mapping):
        errors.append(f"extra must be an object/dict, got {type(extra).__name__}")

    return errors


def validate_run_row(row: Mapping[str, Any]) -> list[str]:
    """Validate one ``scopedb.perf.mobile_runs`` row. Returns a list of errors (empty = valid)."""
    if not isinstance(row, Mapping):
        return [f"row must be a JSON object/dict, got {type(row).__name__}"]

    errors = _validate_columns(row, MOBILE_RUNS_COLUMNS)
    errors.extend(_validate_common(row))

    for col in _RUN_STRICTLY_POSITIVE_METRICS:
        val = row.get(col)
        if val is None:
            continue
        if not _is_number(val):
            errors.append(f"{col} must be null or a finite number, got {val!r}")
        elif float(val) <= 0:
            errors.append(f"{col} must be > 0 when present (use null, never 0, for absent metrics), got {val!r}")

    sfr = row.get("slow_frame_ratio")
    if sfr is not None:
        if not _is_number(sfr):
            errors.append(f"slow_frame_ratio must be null or a finite number, got {sfr!r}")
        elif not (0.0 <= float(sfr) <= 1.0):
            errors.append(f"slow_frame_ratio must be in [0.0, 1.0], got {sfr!r}")

    hitch = row.get("hitch_ms_per_s")
    if hitch is not None:
        if not _is_number(hitch):
            errors.append(f"hitch_ms_per_s must be null or a finite number, got {hitch!r}")
        elif float(hitch) < 0.0:
            errors.append(f"hitch_ms_per_s must be >= 0, got {hitch!r}")

    samples = row.get("samples")
    if _is_int(samples) and samples == 0:
        for col in _RUN_FRAME_METRICS:
            if row.get(col) is not None:
                errors.append(f"{col} must be null when samples == 0 (never 0), got {row.get(col)!r}")

    # Percentile ordering check when multiple percentiles are present.
    p50, p95, p99 = row.get("frame_p50_ms"), row.get("frame_p95_ms"), row.get("frame_p99_ms")
    if _is_number(p50) and _is_number(p95) and float(p50) > float(p95) + 1e-9:
        errors.append(f"frame_p50_ms ({p50}) must be <= frame_p95_ms ({p95})")
    if _is_number(p95) and _is_number(p99) and float(p95) > float(p99) + 1e-9:
        errors.append(f"frame_p95_ms ({p95}) must be <= frame_p99_ms ({p99})")
    if _is_number(p50) and _is_number(p99) and float(p50) > float(p99) + 1e-9:
        errors.append(f"frame_p50_ms ({p50}) must be <= frame_p99_ms ({p99})")

    return errors


def validate_diagnose_row(row: Mapping[str, Any]) -> list[str]:
    """Validate one ``scopedb.perf.mobile_diagnose_runs`` row. Returns errors (empty = valid)."""
    if not isinstance(row, Mapping):
        return [f"row must be a JSON object/dict, got {type(row).__name__}"]

    errors = _validate_columns(row, MOBILE_DIAGNOSE_RUNS_COLUMNS)
    errors.extend(_validate_common(row))

    for col in _DIAG_STRICTLY_POSITIVE_METRICS:
        val = row.get(col)
        if val is None:
            continue
        if not _is_number(val):
            errors.append(f"{col} must be null or a finite number, got {val!r}")
        elif float(val) <= 0:
            errors.append(f"{col} must be > 0 when present (use null, never 0, for absent metrics), got {val!r}")

    for col in ("slow_frame_count", "compose_recomp_count"):
        val = row.get(col)
        if val is not None and (not _is_int(val) or val < 0):
            errors.append(f"{col} must be null or a non-negative integer, got {val!r}")

    samples = row.get("samples")
    if _is_int(samples) and samples == 0:
        for col in ("worst_frame_ms", "slow_frame_count"):
            if row.get(col) is not None:
                errors.append(f"{col} must be null when samples == 0 (never 0), got {row.get(col)!r}")

    project = row.get("project")
    if project == "raft-android" and row.get("metal_ms") is not None:
        errors.append("metal_ms must be null for raft-android rows")
    if project == "raft-ios" and row.get("render_thread_ms") is not None:
        errors.append("render_thread_ms must be null for raft-ios rows")

    top_hotspots = row.get("top_hotspots")
    if not isinstance(top_hotspots, list):
        errors.append(f"top_hotspots must be a list/array, got {type(top_hotspots).__name__}")
    else:
        for idx, item in enumerate(top_hotspots):
            if not isinstance(item, Mapping):
                errors.append(f"top_hotspots[{idx}] must be an object, got {type(item).__name__}")
                continue
            item_keys = set(item.keys())
            if item_keys != HOTSPOT_KEYS:
                errors.append(
                    f"top_hotspots[{idx}] keys must be exactly {sorted(HOTSPOT_KEYS)}, got {sorted(item_keys)}"
                )
                continue
            if not isinstance(item["symbol"], str) or not item["symbol"].strip():
                errors.append(f"top_hotspots[{idx}].symbol must be a non-empty string")
            if not isinstance(item["thread"], str) or not item["thread"].strip():
                errors.append(f"top_hotspots[{idx}].thread must be a non-empty string")
            self_ms = item["self_ms"]
            total_ms = item["total_ms"]
            if not _is_number(self_ms) or float(self_ms) < 0:
                errors.append(f"top_hotspots[{idx}].self_ms must be a non-negative number")
            if not _is_number(total_ms) or float(total_ms) < 0:
                errors.append(f"top_hotspots[{idx}].total_ms must be a non-negative number")
            if _is_number(self_ms) and _is_number(total_ms) and float(total_ms) + 1e-6 < float(self_ms):
                errors.append(
                    f"top_hotspots[{idx}].total_ms ({total_ms}) must be >= self_ms ({self_ms})"
                )

    for col in ("trace_sha256", "trace_link"):
        val = row.get(col)
        if val is not None and (not isinstance(val, str) or not val.strip()):
            errors.append(f"{col} must be null or a non-empty string, got {val!r}")

    return errors


def _normalize_kind(kind: str) -> str:
    lowered = kind.strip().lower()
    if lowered in ("run", "runs", "mobile_runs", MOBILE_RUNS_TABLE):
        return "run"
    if lowered in ("diagnose", "diag", "diagnose_runs", "mobile_diagnose_runs", MOBILE_DIAGNOSE_RUNS_TABLE):
        return "diagnose"
    raise ValueError(f"Unknown JSONL kind {kind!r}; expected 'run' or 'diagnose'")


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    """Read a JSONL file into a list of dicts, skipping blank lines."""
    rows: list[dict[str, Any]] = []
    file_path = Path(path)
    with file_path.open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, start=1):
            stripped = raw.strip()
            if not stripped:
                continue
            try:
                obj = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{file_path}:{line_no}: invalid JSON: {exc}") from exc
            if not isinstance(obj, dict):
                raise ValueError(f"{file_path}:{line_no}: expected JSON object, got {type(obj).__name__}")
            rows.append(obj)
    return rows


def write_jsonl(
    path: str | Path,
    rows: Iterable[Mapping[str, Any]],
    *,
    kind: str | None = None,
    validate: bool = True,
    append: bool = False,
) -> int:
    """Write rows to a UTF-8 JSONL file in canonical DDL column order.

    When ``validate`` is True and ``kind`` is provided (or inferred from
    row column shape), all rows are validated before touching disk.
    When ``append`` is True, rows are appended to the existing file instead
    of truncating it. Returns the number of written rows.
    """
    materialized = [dict(r) for r in rows]
    resolved_kind: str | None = _normalize_kind(kind) if kind is not None else None
    if resolved_kind is None and materialized:
        first_keys = set(materialized[0].keys())
        if first_keys == set(MOBILE_RUNS_COLUMNS):
            resolved_kind = "run"
        elif first_keys == set(MOBILE_DIAGNOSE_RUNS_COLUMNS):
            resolved_kind = "diagnose"

    if validate and resolved_kind is not None:
        validator = validate_run_row if resolved_kind == "run" else validate_diagnose_row
        all_errors: list[str] = []
        for idx, row in enumerate(materialized, start=1):
            row_errors = validator(row)
            for err in row_errors:
                all_errors.append(f"row {idx}: {err}")
        if all_errors:
            raise ValueError("Refusing to write invalid JSONL:\n" + "\n".join(all_errors))

    columns = (
        MOBILE_RUNS_COLUMNS
        if resolved_kind == "run"
        else MOBILE_DIAGNOSE_RUNS_COLUMNS
        if resolved_kind == "diagnose"
        else None
    )

    out_path = Path(path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    needs_leading_nl = False
    if append and out_path.exists() and out_path.stat().st_size > 0:
        with out_path.open("rb") as chk:
            chk.seek(-1, 2)
            needs_leading_nl = chk.read(1) != b"\n"
    with out_path.open("a" if append else "w", encoding="utf-8") as handle:
        if needs_leading_nl:
            handle.write("\n")
        for row in materialized:
            ordered = _canonical_row(columns, row) if columns is not None else row
            handle.write(json.dumps(ordered, ensure_ascii=False, separators=(",", ":")) + "\n")
    return len(materialized)


def validate_jsonl_file(path: str | Path, kind: str) -> list[str]:
    """Validate every non-empty line in a JSONL file against the chosen ScopeDB table."""
    resolved = _normalize_kind(kind)
    validator = validate_run_row if resolved == "run" else validate_diagnose_row
    file_path = Path(path)
    errors: list[str] = []
    row_count = 0
    with file_path.open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, start=1):
            stripped = raw.strip()
            if not stripped:
                continue
            row_count += 1
            try:
                obj = json.loads(stripped)
            except json.JSONDecodeError as exc:
                errors.append(f"line {line_no}: invalid JSON: {exc}")
                continue
            for err in validator(obj):
                errors.append(f"line {line_no}: {err}")
    if row_count == 0:
        errors.append("file contains no JSONL rows")
    return errors
