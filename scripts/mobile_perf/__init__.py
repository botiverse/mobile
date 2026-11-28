"""Closed-loop mobile performance harness for Raft Android and iOS.

Pure Python 3.10+ standard-library package producing ScopeDB-compatible
``mobile_runs`` (38 columns) and ``mobile_diagnose_runs`` (40 columns) JSONL
records, cohort-enforced paired A/B verdicts, and per-scenario sensitivity
calibration checks.
"""

from __future__ import annotations

from .ab_verdict import (
    classify_thermal_status,
    evaluate_ab,
    evaluate_calibration,
    is_thermal_throttled,
)
from .scenarios import (
    DEFAULT_FIXTURE_TAG,
    SCENARIOS,
    ScenarioSpec,
    get_calibration_config,
    get_primary_kpi,
    get_scenario,
    list_scenarios,
)
from .schema import (
    ANDROID_THERMAL_STATES,
    IOS_THERMAL_STATES,
    MOBILE_DIAGNOSE_RUNS_COLUMNS,
    MOBILE_DIAGNOSE_RUNS_TABLE,
    MOBILE_RUNS_COLUMNS,
    MOBILE_RUNS_TABLE,
    SEGMENT_UNIT_SUFFIXES,
    TOOL_VERSION,
    build_cohort_key,
    make_diagnose_row,
    make_run_row,
    read_jsonl,
    validate_diagnose_row,
    validate_jsonl_file,
    validate_run_row,
    write_jsonl,
)

__all__ = [
    "ANDROID_THERMAL_STATES",
    "DEFAULT_FIXTURE_TAG",
    "IOS_THERMAL_STATES",
    "MOBILE_DIAGNOSE_RUNS_COLUMNS",
    "MOBILE_DIAGNOSE_RUNS_TABLE",
    "MOBILE_RUNS_COLUMNS",
    "MOBILE_RUNS_TABLE",
    "SCENARIOS",
    "SEGMENT_UNIT_SUFFIXES",
    "ScenarioSpec",
    "TOOL_VERSION",
    "build_cohort_key",
    "classify_thermal_status",
    "evaluate_ab",
    "evaluate_calibration",
    "get_calibration_config",
    "get_primary_kpi",
    "get_scenario",
    "is_thermal_throttled",
    "list_scenarios",
    "make_diagnose_row",
    "make_run_row",
    "read_jsonl",
    "validate_diagnose_row",
    "validate_jsonl_file",
    "validate_run_row",
    "write_jsonl",
]
