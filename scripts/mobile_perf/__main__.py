"""CLI entrypoint for ``python3 -m scripts.mobile_perf <subcommand>``."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .ab_verdict import evaluate_ab, evaluate_calibration
from .android_perfetto import build_perfetto_config, parse_android_capture
from .ios_xctrace import parse_xctrace_exports
from .scenarios import ANDROID_PACKAGE, DEFAULT_FIXTURE_TAG, list_scenarios
from .schema import read_jsonl, validate_jsonl_file, write_jsonl


def _read_optional_text(path: str | None) -> str | None:
    if not path:
        return None
    return Path(path).read_text(encoding="utf-8")


def _add_common_capture_args(parser: argparse.ArgumentParser, *, default_project: str, default_build_type: str) -> None:
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--diagnose-run-id")
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--app-version", required=True)
    parser.add_argument("--app-build", required=True)
    parser.add_argument("--build-type", default=default_build_type)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--device-model", required=True)
    parser.add_argument("--os-version", required=True)
    parser.add_argument("--layout-mode", default="phone")
    parser.add_argument("--refresh-hz", type=float)
    parser.add_argument("--warm-state", default="profile_warm")
    parser.add_argument("--fixture-tag", default=DEFAULT_FIXTURE_TAG)
    parser.add_argument("--env", default="staging")
    parser.add_argument("--project", default=default_project)
    parser.add_argument("--run-kind", default="baseline")
    parser.add_argument("--ab-session-id")
    parser.add_argument("--ab-arm", choices=("base", "head"))
    parser.add_argument("--iteration", type=int)
    parser.add_argument("--calibration-injected-ms", type=float)
    parser.add_argument("--thermal-start")
    parser.add_argument("--thermal-end")
    parser.add_argument("--duration-ms", type=float)
    parser.add_argument("--ttfd-ms", type=float)
    parser.add_argument("--window-ms", type=float)
    parser.add_argument("--trace-path")
    parser.add_argument("--trace-link")
    parser.add_argument("--taken-at")
    parser.add_argument("--out-run", help="Write validated mobile_runs JSONL row to this path")
    parser.add_argument("--out-diagnose", help="Write validated mobile_diagnose_runs JSONL row to this path")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python3 -m scripts.mobile_perf",
        description="Closed-loop mobile performance harness (ScopeDB schema + Android/iOS parsers + A/B verdict).",
    )
    sub = parser.add_subparsers(dest="subcommand", required=True)

    # scenarios
    p_sc = sub.add_parser("scenarios", help="List registered first-batch perf scenarios")
    p_sc.add_argument("--json", action="store_true", dest="as_json", help="Emit JSON instead of table")

    # validate
    p_val = sub.add_parser("validate", help="Validate JSONL files against ScopeDB schema")
    p_val.add_argument("--kind", required=True, choices=("run", "diagnose"))
    p_val.add_argument("files", nargs="+", help="One or more JSONL files to validate")

    # parse-android
    p_and = sub.add_parser(
        "parse-android",
        help="Parse Android Perfetto SQL and/or dumpsys+logcat captures into ScopeDB JSONL",
    )
    p_and.add_argument(
        "--emit-perfetto-config",
        action="store_true",
        help="Print a Perfetto .pbtx config and exit",
    )
    p_and.add_argument("--package", default=ANDROID_PACKAGE)
    p_and.add_argument("--config-duration-ms", type=int, default=10000)
    # Metadata arguments (optional when --emit-perfetto-config is used; checked in handler).
    p_and.add_argument("--run-id")
    p_and.add_argument("--diagnose-run-id")
    p_and.add_argument("--commit-sha")
    p_and.add_argument("--app-version")
    p_and.add_argument("--app-build")
    p_and.add_argument("--build-type", default="release-profileable")
    p_and.add_argument("--scenario")
    p_and.add_argument("--device-id")
    p_and.add_argument("--device-model")
    p_and.add_argument("--os-version")
    p_and.add_argument("--layout-mode", default="phone")
    p_and.add_argument("--refresh-hz", type=float)
    p_and.add_argument("--warm-state", default="profile_warm")
    p_and.add_argument("--fixture-tag", default=DEFAULT_FIXTURE_TAG)
    p_and.add_argument("--env", default="staging")
    p_and.add_argument("--project", default="raft-android")
    p_and.add_argument("--run-kind", default="baseline")
    p_and.add_argument("--ab-session-id")
    p_and.add_argument("--ab-arm", choices=("base", "head"))
    p_and.add_argument("--iteration", type=int)
    p_and.add_argument("--calibration-injected-ms", type=float)
    p_and.add_argument("--thermal-start")
    p_and.add_argument("--thermal-end")
    p_and.add_argument("--duration-ms", type=float)
    p_and.add_argument("--ttfd-ms", type=float)
    p_and.add_argument("--window-ms", type=float)
    p_and.add_argument("--gfxinfo")
    p_and.add_argument("--meminfo")
    p_and.add_argument("--thermal-start-file")
    p_and.add_argument("--thermal-end-file")
    p_and.add_argument("--display")
    p_and.add_argument("--logcat")
    p_and.add_argument("--perfetto-frames")
    p_and.add_argument("--perfetto-main")
    p_and.add_argument("--perfetto-render")
    p_and.add_argument("--perfetto-compose")
    p_and.add_argument("--perfetto-memory")
    p_and.add_argument("--trace-path")
    p_and.add_argument("--trace-link")
    p_and.add_argument("--taken-at")
    p_and.add_argument("--out-run")
    p_and.add_argument("--out-diagnose")

    # parse-ios
    p_ios = sub.add_parser(
        "parse-ios",
        help="Parse iOS xctrace XML exports and signpost/app logs into ScopeDB JSONL",
    )
    _add_common_capture_args(p_ios, default_project="raft-ios", default_build_type="release-dsym")
    p_ios.add_argument("--hitches-xml")
    p_ios.add_argument("--time-profile-xml")
    p_ios.add_argument("--thermal-xml")
    p_ios.add_argument("--metal-xml")
    p_ios.add_argument("--signpost-xml")
    p_ios.add_argument("--app-log")
    p_ios.add_argument("--mem-peak-mb", type=float)

    # ab-verdict
    p_ab = sub.add_parser("ab-verdict", help="Evaluate paired interleaved A/B runs")
    p_ab.add_argument("input_jsonl", help="JSONL file containing paired base/head mobile_runs rows")
    p_ab.add_argument("--metric", help="Primary metric column (defaults to scenario primary KPI)")
    p_ab.add_argument("--min-pairs", type=int, default=5)
    p_ab.add_argument("--alpha", type=float, default=0.05)
    p_ab.add_argument("--rel-floor", type=float, default=0.05)
    p_ab.add_argument("--noise-multiplier", type=float, default=2.0)
    p_ab.add_argument("--abs-floor-ms", type=float)
    p_ab.add_argument("--higher-is-better", action="store_true")
    p_ab.add_argument("--no-filter-thermal", action="store_true")
    p_ab.add_argument("--out", help="Optional output JSON path")

    # calibrate
    p_cal = sub.add_parser("calibrate", help="Check 200ms synthetic stall detection sensitivity")
    p_cal.add_argument("--base", help="JSONL file with un-injected baseline rows")
    p_cal.add_argument("--injected", help="JSONL file with synthetic stall-injected rows")
    p_cal.add_argument(
        "--input",
        dest="combined_input",
        help="Single combined JSONL split by calibration_injected_ms (null/0 vs >0)",
    )
    p_cal.add_argument("--metric", help="Primary latency metric (defaults to scenario primary KPI)")
    p_cal.add_argument("--injected-ms", type=float, default=200.0)
    p_cal.add_argument("--tolerance-ratio", type=float, default=0.25)
    p_cal.add_argument("--min-pairs", type=int, default=5)
    p_cal.add_argument("--alpha", type=float, default=0.05)
    p_cal.add_argument("--no-filter-thermal", action="store_true")
    p_cal.add_argument("--out", help="Optional output JSON path")

    return parser


def _cmd_scenarios(args: argparse.Namespace) -> int:
    specs = list_scenarios()
    if args.as_json:
        print(json.dumps([s.to_dict() for s in specs], indent=2))
        return 0
    for spec in specs:
        segs = ", ".join(spec.required_segments)
        print(
            f"{spec.name:22s} kpi={spec.primary_kpi:14s} ios_kpi={spec.ios_primary_kpi:14s} "
            f"warm={spec.default_warm_state:12s} fixture={spec.fixture_tag} segments=[{segs}]"
        )
    return 0


def _cmd_validate(args: argparse.Namespace) -> int:
    had_errors = False
    for path in args.files:
        errors = validate_jsonl_file(path, args.kind)
        if errors:
            had_errors = True
            print(f"INVALID {path} ({len(errors)} error(s)):", file=sys.stderr)
            for err in errors:
                print(f"  - {err}", file=sys.stderr)
        else:
            print(f"OK {path} ({args.kind})")
    return 1 if had_errors else 0


def _emit_capture_rows(
    run_row: dict[str, Any],
    diag_row: dict[str, Any],
    out_run: str | None,
    out_diagnose: str | None,
) -> int:
    if out_run:
        write_jsonl(out_run, [run_row], kind="run")
    if out_diagnose:
        write_jsonl(out_diagnose, [diag_row], kind="diagnose")
    if not out_run and not out_diagnose:
        print(json.dumps({"run": run_row, "diagnose": diag_row}, indent=2))
    return 0


def _cmd_parse_android(args: argparse.Namespace) -> int:
    if args.emit_perfetto_config:
        print(build_perfetto_config(package=args.package, duration_ms=args.config_duration_ms), end="")
        return 0

    required = (
        "run_id",
        "commit_sha",
        "app_version",
        "app_build",
        "scenario",
        "device_id",
        "device_model",
        "os_version",
    )
    missing = [f"--{k.replace('_', '-')}" for k in required if not getattr(args, k)]
    if missing:
        print(f"Missing required arguments: {', '.join(missing)}", file=sys.stderr)
        return 2

    run_row, diag_row = parse_android_capture(
        run_id=args.run_id,
        diagnose_run_id=args.diagnose_run_id,
        commit_sha=args.commit_sha,
        app_version=args.app_version,
        app_build=args.app_build,
        build_type=args.build_type,
        scenario=args.scenario,
        device_id=args.device_id,
        device_model=args.device_model,
        os_version=args.os_version,
        layout_mode=args.layout_mode,
        refresh_hz=args.refresh_hz,
        warm_state=args.warm_state,
        fixture_tag=args.fixture_tag,
        env=args.env,
        project=args.project,
        run_kind=args.run_kind,
        ab_session_id=args.ab_session_id,
        ab_arm=args.ab_arm,
        iteration=args.iteration,
        calibration_injected_ms=args.calibration_injected_ms,
        thermal_start=args.thermal_start,
        thermal_end=args.thermal_end,
        duration_ms=args.duration_ms,
        ttfd_ms=args.ttfd_ms,
        window_ms=args.window_ms,
        gfxinfo_text=_read_optional_text(args.gfxinfo),
        meminfo_text=_read_optional_text(args.meminfo),
        thermal_start_text=_read_optional_text(args.thermal_start_file),
        thermal_end_text=_read_optional_text(args.thermal_end_file),
        display_text=_read_optional_text(args.display),
        logcat_text=_read_optional_text(args.logcat),
        perfetto_frame_timeline=_read_optional_text(args.perfetto_frames),
        perfetto_main_thread=_read_optional_text(args.perfetto_main),
        perfetto_render_thread=_read_optional_text(args.perfetto_render),
        perfetto_compose_recomp=_read_optional_text(args.perfetto_compose),
        perfetto_memory=_read_optional_text(args.perfetto_memory),
        trace_path=args.trace_path,
        trace_link=args.trace_link,
        taken_at=args.taken_at,
    )
    return _emit_capture_rows(run_row, diag_row, args.out_run, args.out_diagnose)


def _cmd_parse_ios(args: argparse.Namespace) -> int:
    run_row, diag_row = parse_xctrace_exports(
        run_id=args.run_id,
        diagnose_run_id=args.diagnose_run_id,
        commit_sha=args.commit_sha,
        app_version=args.app_version,
        app_build=args.app_build,
        build_type=args.build_type,
        scenario=args.scenario,
        device_id=args.device_id,
        device_model=args.device_model,
        os_version=args.os_version,
        layout_mode=args.layout_mode,
        refresh_hz=args.refresh_hz if args.refresh_hz is not None else 60.0,
        warm_state=args.warm_state,
        fixture_tag=args.fixture_tag,
        env=args.env,
        project=args.project,
        run_kind=args.run_kind,
        ab_session_id=args.ab_session_id,
        ab_arm=args.ab_arm,
        iteration=args.iteration,
        calibration_injected_ms=args.calibration_injected_ms,
        window_ms=args.window_ms if args.window_ms is not None else 5000.0,
        thermal_start=args.thermal_start,
        thermal_end=args.thermal_end,
        duration_ms=args.duration_ms,
        ttfd_ms=args.ttfd_ms,
        mem_peak_mb=args.mem_peak_mb,
        hitches_xml=_read_optional_text(args.hitches_xml),
        time_profile_xml=_read_optional_text(args.time_profile_xml),
        thermal_xml=_read_optional_text(args.thermal_xml),
        metal_xml=_read_optional_text(args.metal_xml),
        signpost_xml=_read_optional_text(args.signpost_xml),
        app_log_text=_read_optional_text(args.app_log),
        trace_path=args.trace_path,
        trace_link=args.trace_link,
        taken_at=args.taken_at,
    )
    return _emit_capture_rows(run_row, diag_row, args.out_run, args.out_diagnose)


def _cmd_ab_verdict(args: argparse.Namespace) -> int:
    rows = read_jsonl(args.input_jsonl)
    result = evaluate_ab(
        rows,
        primary_metric=args.metric,
        min_pairs=args.min_pairs,
        alpha=args.alpha,
        rel_floor=args.rel_floor,
        noise_multiplier=args.noise_multiplier,
        abs_floor_ms=args.abs_floor_ms,
        lower_is_better=not args.higher_is_better,
        filter_thermal=not args.no_filter_thermal,
    )
    rendered = json.dumps(result, indent=2)
    if args.out:
        Path(args.out).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0 if result["verdict"] not in ("invalid_cohort", "regressed") else 1


def _cmd_calibrate(args: argparse.Namespace) -> int:
    if args.combined_input:
        rows = read_jsonl(args.combined_input)
        base_rows = [r for r in rows if not r.get("calibration_injected_ms")]
        injected_rows = [r for r in rows if (r.get("calibration_injected_ms") or 0) > 0]
    elif args.base and args.injected:
        base_rows = read_jsonl(args.base)
        injected_rows = read_jsonl(args.injected)
    else:
        print("Provide either --input <combined.jsonl> or both --base and --injected", file=sys.stderr)
        return 2

    result = evaluate_calibration(
        base_rows,
        injected_rows,
        primary_metric=args.metric,
        injected_ms=args.injected_ms,
        tolerance_ratio=args.tolerance_ratio,
        min_pairs=args.min_pairs,
        alpha=args.alpha,
        filter_thermal=not args.no_filter_thermal,
    )
    rendered = json.dumps(result, indent=2)
    if args.out:
        Path(args.out).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0 if result.get("passed") else 1


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    dispatch = {
        "scenarios": _cmd_scenarios,
        "validate": _cmd_validate,
        "parse-android": _cmd_parse_android,
        "parse-ios": _cmd_parse_ios,
        "ab-verdict": _cmd_ab_verdict,
        "calibrate": _cmd_calibrate,
    }
    return dispatch[args.subcommand](args)


if __name__ == "__main__":
    sys.exit(main())
