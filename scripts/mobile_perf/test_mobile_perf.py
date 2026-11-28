"""Self-contained unit tests for scripts/mobile_perf."""

from __future__ import annotations

import copy
import hashlib
import io
import json
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

# Ensure repo root is on sys.path under both `unittest discover -s scripts/mobile_perf`
# and direct script execution.
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from scripts.mobile_perf import (  # noqa: E402
    MOBILE_DIAGNOSE_RUNS_COLUMNS,
    MOBILE_RUNS_COLUMNS,
    SCENARIOS,
    SEGMENT_UNIT_SUFFIXES,
    __main__ as cli,
    ab_verdict,
    android_perfetto,
    build_cohort_key,
    classify_thermal_status,
    ios_xctrace,
    make_diagnose_row,
    make_run_row,
    read_jsonl,
    scenarios,
    schema,
    validate_diagnose_row,
    validate_jsonl_file,
    validate_run_row,
    write_jsonl,
)

# Inline verbatim copies of the authoritative ScopeDB sample rows.
SAMPLE_MOBILE_RUN_JSON = (
    '{"project":"raft-android","env":"staging","run_id":"selftest-0001",'
    '"tool_version":"mobile-perf/0.1.0","taken_at":"2026-11-28T04:20:00Z",'
    '"commit_sha":"0123456789abcdef0123456789abcdef01234567","app_version":"1.31.0",'
    '"app_build":"13100019","build_type":"release-profileable","scenario":"thread_open",'
    '"run_kind":"ab","ab_session_id":"ab-20261128-01","ab_arm":"base","iteration":1,'
    '"calibration_injected_ms":null,'
    '"cohort_key":"dev:3f9a1c22|os:android-14|build:release-profileable|fx:mobile-bench-v1|layout:phone|hz:90|warm:profile_warm",'
    '"device_id":"3f9a1c22","device_model":"Redmi Note 12","os_version":"android-14",'
    '"layout_mode":"phone","refresh_hz":90,"warm_state":"profile_warm",'
    '"fixture_tag":"mobile-bench-v1","thermal_start":"none","thermal_end":"light",'
    '"samples":412,"valid":true,"duration_ms":684,"ttfd_ms":null,'
    '"frame_p50_ms":8.9,"frame_p95_ms":18.2,"frame_p99_ms":34.5,'
    '"slow_frame_ratio":0.031,"hitch_ms_per_s":null,"mem_peak_mb":312.4,'
    '"segments":{"cache_rows_shown_ms":212,"network_applied_ms":455,'
    '"reading_anchor_attempt_count":3,"visible_shift_dp":36,"shift_count":1,"frame_count":412},'
    '"verdict":null,"extra":{}}'
)

SAMPLE_DIAGNOSE_RUN_JSON = (
    '{"project":"raft-ios","env":"staging","run_id":"selftest-d0001",'
    '"tool_version":"mobile-perf/0.1.0","taken_at":"2026-11-28T04:25:00Z",'
    '"commit_sha":"0123456789abcdef0123456789abcdef01234567","app_version":"1.31.0",'
    '"app_build":"13100006","build_type":"release-dsym","scenario":"thread_open",'
    '"run_kind":"baseline","ab_session_id":null,"ab_arm":null,"iteration":null,'
    '"calibration_injected_ms":null,'
    '"cohort_key":"dev:9b20e4d1|os:ios-17.6|build:release-dsym|fx:mobile-bench-v1|layout:phone|hz:60|warm:profile_warm",'
    '"device_id":"9b20e4d1","device_model":"iPhone 11","os_version":"ios-17.6",'
    '"layout_mode":"phone","refresh_hz":60,"warm_state":"profile_warm",'
    '"fixture_tag":"mobile-bench-v1","thermal_start":"nominal","thermal_end":"fair",'
    '"samples":388,"valid":true,"window_ms":5000,"ui_thread_ms":1420,'
    '"render_thread_ms":null,"metal_ms":610,"worst_frame_ms":96.4,'
    '"slow_frame_count":7,"compose_recomp_count":null,'
    '"top_hotspots":[{"symbol":"ThreadReadingAnchorKeeper.apply","thread":"main","self_ms":212.5,"total_ms":388.0}],'
    '"segments":{"cache_rows_shown_ms":198,"network_applied_ms":503},'
    '"trace_sha256":"<sha256 of the .trace bundle>","trace_link":"<where the raw trace is kept>",'
    '"verdict":null,"extra":{}}'
)


def _make_ab_row(
    *,
    arm: str | None = "base",
    iteration: int | None = 1,
    duration_ms: float | None = 680.0,
    ttfd_ms: float | None = None,
    slow_frame_ratio: float | None = None,
    hitch_ms_per_s: float | None = None,
    device_id: str = "3f9a1c22",
    scenario: str = "thread_open",
    project: str = "raft-android",
    os_version: str | None = None,
    build_type: str | None = None,
    refresh_hz: float | None = None,
    thermal_start: str | None = "none",
    thermal_end: str | None = "light",
    valid: bool = True,
    samples: int = 400,
    calibration_injected_ms: float | None = None,
    run_kind: str = "ab",
    ab_session_id: str | None = "ab-20261128-test",
    extra: dict | None = None,
) -> dict:
    is_ios = project == "raft-ios"
    eff_os = os_version or ("ios-17.6" if is_ios else "android-14")
    eff_build = build_type or ("release-dsym" if is_ios else "release-profileable")
    eff_hz = refresh_hz if refresh_hz is not None else (60 if is_ios else 90)
    eff_sfr = slow_frame_ratio if slow_frame_ratio is not None else (0.02 if (samples > 0 and not is_ios) else None)
    return make_run_row(
        project=project,
        env="staging",
        run_id=f"{arm}-{iteration}",
        taken_at="2026-11-28T04:30:00Z",
        commit_sha="0123456789abcdef0123456789abcdef01234567",
        app_version="1.31.0",
        app_build="13100019",
        build_type=eff_build,
        scenario=scenario,
        run_kind=run_kind,
        ab_session_id=ab_session_id if run_kind == "ab" else None,
        ab_arm=arm if run_kind == "ab" else None,
        iteration=iteration,
        calibration_injected_ms=calibration_injected_ms,
        device_id=device_id,
        device_model="iPhone 11" if is_ios else "Redmi Note 12",
        os_version=eff_os,
        layout_mode="phone",
        refresh_hz=eff_hz,
        warm_state="profile_warm",
        fixture_tag="mobile-bench-v1",
        thermal_start=thermal_start,
        thermal_end=thermal_end,
        samples=samples,
        valid=valid,
        duration_ms=duration_ms,
        ttfd_ms=ttfd_ms,
        frame_p50_ms=8.5 if samples > 0 else None,
        frame_p95_ms=16.0 if samples > 0 else None,
        frame_p99_ms=28.0 if samples > 0 else None,
        slow_frame_ratio=eff_sfr,
        hitch_ms_per_s=hitch_ms_per_s,
        mem_peak_mb=300.0,
        segments={"cache_rows_shown_ms": 200, "network_applied_ms": 420},
        extra=extra or {},
    )


class TestSchemaContract(unittest.TestCase):
    def test_authoritative_sample_rows_pass_verbatim(self) -> None:
        run_row = json.loads(SAMPLE_MOBILE_RUN_JSON)
        diag_row = json.loads(SAMPLE_DIAGNOSE_RUN_JSON)

        self.assertEqual(len(MOBILE_RUNS_COLUMNS), 38)
        self.assertEqual(len(MOBILE_DIAGNOSE_RUNS_COLUMNS), 40)
        self.assertEqual(tuple(run_row.keys()), MOBILE_RUNS_COLUMNS)
        self.assertEqual(tuple(diag_row.keys()), MOBILE_DIAGNOSE_RUNS_COLUMNS)

        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])

        with tempfile.TemporaryDirectory() as tmp:
            run_path = Path(tmp) / "sample.jsonl"
            diag_path = Path(tmp) / "sample_diag.jsonl"
            write_jsonl(run_path, [run_row], kind="run")
            write_jsonl(diag_path, [diag_row], kind="diagnose")
            self.assertEqual(validate_jsonl_file(run_path, "run"), [])
            self.assertEqual(validate_jsonl_file(diag_path, "diagnose"), [])

    def test_build_cohort_key_formatting(self) -> None:
        key_int = build_cohort_key(
            "3f9a1c22", "android-14", "release-profileable", "mobile-bench-v1", "phone", 90.0, "profile_warm"
        )
        self.assertEqual(
            key_int,
            "dev:3f9a1c22|os:android-14|build:release-profileable|fx:mobile-bench-v1|layout:phone|hz:90|warm:profile_warm",
        )
        key_frac = build_cohort_key(
            "abc", "ios-18.0", "release-dsym", "mobile-bench-v1", "tablet", 59.94, "cold"
        )
        self.assertIn("|hz:59.94|", key_frac)
        for forbidden in ("app_version", "app_build", "commit_sha", "thermal_start", "thermal_end"):
            self.assertNotIn(forbidden, key_int)

    def test_rejects_wrong_cohort_key_and_forbidden_tokens(self) -> None:
        row = json.loads(SAMPLE_MOBILE_RUN_JSON)
        row["cohort_key"] = row["cohort_key"] + "|commit_sha:0123"
        errors = validate_run_row(row)
        self.assertTrue(any("commit_sha" in e for e in errors))
        self.assertTrue(any("cohort_key mismatch" in e for e in errors))

    def test_rejects_zero_placeholders_for_absent_metrics(self) -> None:
        row = json.loads(SAMPLE_MOBILE_RUN_JSON)
        row["ttfd_ms"] = 0
        errors = validate_run_row(row)
        self.assertTrue(any("ttfd_ms" in e and "never 0" in e for e in errors))

        diag = json.loads(SAMPLE_DIAGNOSE_RUN_JSON)
        diag["render_thread_ms"] = 0
        diag_errors = validate_diagnose_row(diag)
        self.assertTrue(any("render_thread_ms" in e for e in diag_errors))

    def test_zero_samples_requires_valid_false_and_null_frame_metrics(self) -> None:
        row = json.loads(SAMPLE_MOBILE_RUN_JSON)
        row["samples"] = 0
        row["valid"] = True
        errors = validate_run_row(row)
        self.assertTrue(any("samples == 0 requires valid=False" in e for e in errors))

        zero_row = make_run_row(
            project="raft-android",
            env="staging",
            run_id="zero-001",
            taken_at="2026-11-28T04:20:00Z",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            run_kind="baseline",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            layout_mode="phone",
            refresh_hz=90,
            warm_state="profile_warm",
            fixture_tag="mobile-bench-v1",
            samples=0,
            valid=True,  # Forced to False by make_run_row!
        )
        self.assertFalse(zero_row["valid"])
        self.assertEqual(validate_run_row(zero_row), [])

        bad_zero = copy.deepcopy(zero_row)
        bad_zero["slow_frame_ratio"] = 0.0
        self.assertTrue(any("slow_frame_ratio" in e for e in validate_run_row(bad_zero)))

    def test_rejects_missing_unit_suffix_in_segments(self) -> None:
        row = json.loads(SAMPLE_MOBILE_RUN_JSON)
        row["segments"]["cache_rows_shown"] = 212
        errors = validate_run_row(row)
        self.assertTrue(any("segments.cache_rows_shown" in e for e in errors))

    def test_rejects_missing_columns_and_bad_hotspots(self) -> None:
        row = json.loads(SAMPLE_MOBILE_RUN_JSON)
        del row["mem_peak_mb"]
        errors = validate_run_row(row)
        self.assertTrue(any("missing columns" in e and "mem_peak_mb" in e for e in errors))

        diag = json.loads(SAMPLE_DIAGNOSE_RUN_JSON)
        diag["top_hotspots"] = [{"symbol": "foo", "thread": "main", "self_ms": 50.0, "total_ms": 10.0}]
        diag_errors = validate_diagnose_row(diag)
        self.assertTrue(any("total_ms" in e for e in diag_errors))

        diag2 = json.loads(SAMPLE_DIAGNOSE_RUN_JSON)
        diag2["taken_at"] = "2026-11-28 04:25:00"
        self.assertTrue(any("taken_at" in e for e in validate_diagnose_row(diag2)))

    def test_hard_rejects_debug_build_type(self) -> None:
        for bad_bt in ("debug", "Debug", "debug-profileable", "release-DEBUG"):
            row = _make_ab_row(build_type=bad_bt)
            errors = validate_run_row(row)
            self.assertTrue(any("debug" in e.lower() for e in errors), f"expected reject for {bad_bt}")

        diag = json.loads(SAMPLE_DIAGNOSE_RUN_JSON)
        diag["build_type"] = "Debug-dSYM"
        diag["cohort_key"] = build_cohort_key(
            diag["device_id"],
            diag["os_version"],
            diag["build_type"],
            diag["fixture_tag"],
            diag["layout_mode"],
            diag["refresh_hz"],
            diag["warm_state"],
        )
        self.assertTrue(any("debug" in e.lower() for e in validate_diagnose_row(diag)))

    def test_rejects_calibration_injected_ms_on_non_calibration_run_kinds(self) -> None:
        for rk in ("baseline", "ab", "diagnose"):
            row = _make_ab_row(run_kind=rk, calibration_injected_ms=200.0)
            errors = validate_run_row(row)
            self.assertTrue(
                any("calibration_injected_ms" in e for e in errors),
                f"expected error for run_kind={rk} with calibration_injected_ms=200",
            )
            row_zero = _make_ab_row(run_kind=rk, calibration_injected_ms=0.0)
            self.assertEqual(validate_run_row(row_zero), [])

        cal_row = _make_ab_row(run_kind="calibration", calibration_injected_ms=200.0)
        self.assertEqual(validate_run_row(cal_row), [])

    def test_enforces_per_project_canonical_thermal_enums(self) -> None:
        # Android rejects iOS thermal state and raw prefixed/uppercase forms.
        for bad_android in ("nominal", "fair", "serious", "THERMAL_STATUS_SEVERE", "SEVERE", "warm"):
            row = _make_ab_row(project="raft-android", thermal_start="none", thermal_end=bad_android)
            errors = validate_run_row(row)
            self.assertTrue(
                any("thermal_end for raft-android" in e for e in errors),
                f"Android should reject thermal_end={bad_android!r}",
            )

        # iOS rejects Android thermal states and non-lowercase forms.
        for bad_ios in ("none", "light", "moderate", "severe", "Nominal", "Serious"):
            row = _make_ab_row(project="raft-ios", thermal_start="nominal", thermal_end=bad_ios)
            errors = validate_run_row(row)
            self.assertTrue(
                any("thermal_end for raft-ios" in e for e in errors),
                f"iOS should reject thermal_end={bad_ios!r}",
            )

        # Canonical states pass on each platform.
        self.assertEqual(
            validate_run_row(_make_ab_row(project="raft-android", thermal_start="none", thermal_end="moderate")),
            [],
        )
        self.assertEqual(
            validate_run_row(_make_ab_row(project="raft-ios", thermal_start="nominal", thermal_end="critical")),
            [],
        )

    def test_write_jsonl_append_mode(self) -> None:
        row1 = _make_ab_row(arm="base", iteration=1, duration_ms=680.0)
        row2 = _make_ab_row(arm="head", iteration=1, duration_ms=610.0)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "runs.jsonl"
            self.assertEqual(write_jsonl(path, [row1], kind="run"), 1)
            self.assertEqual(write_jsonl(path, [row2], kind="run", append=True), 1)
            loaded = read_jsonl(path)
            self.assertEqual(len(loaded), 2)
            self.assertEqual([r["run_id"] for r in loaded], ["base-1", "head-1"])
            self.assertEqual(validate_jsonl_file(path, "run"), [])


class TestScenariosRegistry(unittest.TestCase):
    def test_seven_registered_scenarios_on_mobile_bench_v1(self) -> None:
        expected_segments = {
            "cold_start": (
                "process_init_ms",
                "compose_first_frame_ms",
                "auth_bootstrap_ms",
                "first_feed_render_ms",
            ),
            "channel_switch": (
                "input_delay_ms",
                "network_ms",
                "recompose_layout_ms",
                "render_gpu_ms",
                "frame_count",
            ),
            "thread_scroll_ime": (
                "ui_thread_ms",
                "recompose_ms",
                "render_thread_or_metal_ms",
                "ime_settle_ms",
                "frame_count",
            ),
            "composer_first_char": (
                "first_char_p50_ms",
                "first_char_p95_ms",
                "key_to_state_ms",
                "text_layout_ms",
                "recompose_ms",
                "draw_ms",
                "frame_count",
            ),
            "webview_open": (
                "container_init_ms",
                "prewarm_hit_count",
                "network_ms",
                "first_paint_ms",
            ),
            "thread_open": (
                "cache_rows_shown_ms",
                "network_applied_ms",
                "reading_anchor_attempt_count",
                "visible_shift_dp",
                "shift_count",
                "frame_count",
            ),
            "foreground_resume": (
                "resume_first_frame_ms",
                "mutex_wait_ms",
                "jitter_wait_ms",
                "network_fetch_ms",
                "rss_mb",
                "pss_mb",
                "frame_count",
            ),
        }
        self.assertEqual(set(SCENARIOS.keys()), set(expected_segments.keys()))
        for spec in scenarios.list_scenarios():
            self.assertEqual(spec.fixture_tag, "mobile-bench-v1")
            self.assertTrue(spec.online_events)
            self.assertIn(spec.primary_kpi, MOBILE_RUNS_COLUMNS)
            self.assertIn(spec.ios_primary_kpi, MOBILE_RUNS_COLUMNS)
            self.assertIn(spec.calibration_metric, MOBILE_RUNS_COLUMNS)
            self.assertIn(spec.ios_calibration_metric, MOBILE_RUNS_COLUMNS)
            self.assertEqual(spec.required_segments, expected_segments[spec.name])
            for seg in spec.required_segments + spec.optional_segments:
                self.assertTrue(seg.endswith(SEGMENT_UNIT_SUFFIXES), f"{spec.name}: {seg}")

        # Per-scenario calibration configs: 6 latency_ms + thread_scroll_ime slow_frames.
        self.assertEqual(scenarios.get_calibration_config("cold_start"), ("latency_ms", "ttfd_ms"))
        self.assertEqual(scenarios.get_calibration_config("cold_start", project="raft-ios"), ("latency_ms", "ttfd_ms"))
        for sc_name in ("channel_switch", "composer_first_char", "webview_open", "thread_open", "foreground_resume"):
            self.assertEqual(scenarios.get_calibration_config(sc_name), ("latency_ms", "duration_ms"))
        self.assertEqual(
            scenarios.get_calibration_config("thread_scroll_ime", project="raft-android"),
            ("slow_frames", "slow_frame_ratio"),
        )
        self.assertEqual(
            scenarios.get_calibration_config("thread_scroll_ime", project="raft-ios"),
            ("slow_frames", "hitch_ms_per_s"),
        )


class TestAndroidPerfettoAndFallback(unittest.TestCase):
    def test_build_perfetto_config_contents(self) -> None:
        cfg = android_perfetto.build_perfetto_config("build.raft.app", 12000)
        for needle in (
            "duration_ms: 12000",
            'ftrace_events: "sched/sched_switch"',
            'atrace_categories: "gfx"',
            'atrace_categories: "view"',
            'atrace_categories: "binder_driver"',
            'atrace_categories: "am"',
            'atrace_categories: "wm"',
            'atrace_apps: "build.raft.app"',
            'name: "android.surfaceflinger.frametimeline"',
            'name: "linux.process_stats"',
            'name: "android.log"',
        ):
            self.assertIn(needle, cfg)

    def test_perfetto_sql_templates_use_slices_self_dur_and_sched_slice(self) -> None:
        for sql in (
            android_perfetto.PERFETTO_MAIN_THREAD_HOTSPOTS_SQL,
            android_perfetto.PERFETTO_RENDER_THREAD_HOTSPOTS_SQL,
        ):
            self.assertIn("INCLUDE PERFETTO MODULE slices.self_dur;", sql)
            self.assertIn("JOIN slice_self_dur sd ON s.id = sd.id", sql)
            self.assertIn("SUM(sd.self_dur) / 1e6 AS self_ms", sql)
            self.assertIn("SUM(s.dur) / 1e6 AS total_ms", sql)
            self.assertIsNone(re.search(r"\bs\.self_dur\b", sql))

        self.assertIn("FROM sched_slice ss", android_perfetto.PERFETTO_THREAD_CPU_SQL)
        self.assertIn("t.is_main_thread = 1", android_perfetto.PERFETTO_THREAD_CPU_SQL)
        self.assertIn("t.name = 'RenderThread'", android_perfetto.PERFETTO_THREAD_CPU_SQL)
        self.assertIn("SUM(ss.dur) / 1e6 AS cpu_ms", android_perfetto.PERFETTO_THREAD_CPU_SQL)

        self.assertIn("FROM trace_bounds", android_perfetto.PERFETTO_TRACE_BOUNDS_SQL)
        self.assertIn("(end_ts - start_ts) / 1e6 AS window_ms", android_perfetto.PERFETTO_TRACE_BOUNDS_SQL)

    def test_android_fallback_dumpsys_and_logcat_parse(self) -> None:
        # 10 framestats rows at 90Hz (budget = 11.111ms), including Flags=4 (kept) and one invalid timestamp row (dropped).
        framestats_rows = [
            "Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted",
        ]
        durs_ms = [6.0, 7.2, 8.0, 8.5, 9.0, 9.2, 9.8, 10.5, 18.4, 34.2]
        base_ns = 1_000_000_000_000
        for idx, dur in enumerate(durs_ms):
            iv = base_ns + idx * 11_111_111
            fc = iv + int(dur * 1_000_000)
            flag = 4 if idx == 8 else 0
            framestats_rows.append(f"{flag},{iv},{iv},0,0,0,0,0,0,0,0,0,0,{fc}")
        # Add invalid timestamp rows (IntendedVsync=0 and FrameCompleted <= IntendedVsync) that must be dropped.
        framestats_rows.append(f"0,0,0,0,0,0,0,0,0,0,0,0,0,{base_ns + 50_000_000}")
        framestats_rows.append(f"0,{base_ns},{base_ns},0,0,0,0,0,0,0,0,0,0,{base_ns}")
        gfxinfo_text = "Graphics info for build.raft.app\n---PROFILEDATA---\n" + "\n".join(framestats_rows) + "\n---PROFILEDATA---\n"

        meminfo_text = "App Summary\n  TOTAL PSS:   319897            TOTAL RSS:   420000\n"
        thermal_start_text = "Thermal Status:\n  Current status: 0\n"
        thermal_end_text = "Thermal Status:\n  Current status: 1\n"
        display_text = (
            "mActiveModeId=2\n"
            "DisplayModeRecord{mMode={id=1, width=1080, height=2400, fps=60.000004}}\n"
            "DisplayModeRecord{mMode={id=2, width=1080, height=2400, fps=90.00001}}\n"
        )
        logcat_text = "\n".join(
            [
                "11-28 04:20:01.100 D/SlockPerf: open_trace_summary trace_id=abc duration_ms=684 cache_rows_shown_ms=212 network_applied_ms=455 shift_count=2 shift_sum_dp=36.0 shift_max_dp=24.0 shifts_after_positioned=1 uploaded=false",
                "11-28 04:20:01.101 D/SlockPerf: open_trace_shift trace_id=abc seq=1 t_ms=240 delta_dp=12.0 phase=cache_rows_shown cause=reading_anchor owner_attempt=1",
                "11-28 04:20:01.102 D/SlockPerf: open_trace_shift trace_id=abc seq=2 t_ms=460 delta_dp=24.0 phase=network_applied cause=reading_anchor owner_attempt=3",
            ]
        )
        raw_trace = b"fake-perfetto-trace-payload"

        run_row, diag_row = android_perfetto.parse_android_capture(
            run_id="and-fallback-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            window_ms=6000.0,
            gfxinfo_text=gfxinfo_text,
            meminfo_text=meminfo_text,
            thermal_start_text=thermal_start_text,
            thermal_end_text=thermal_end_text,
            display_text=display_text,
            logcat_text=logcat_text,
            trace_bytes=raw_trace,
            trace_link="s3://perf-traces/and-fallback-01.perfetto-trace",
            taken_at="2026-11-28T04:20:00Z",
        )

        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["refresh_hz"], 90.0)
        self.assertEqual(run_row["thermal_start"], "none")
        self.assertEqual(run_row["thermal_end"], "light")
        self.assertEqual(run_row["samples"], 10)
        self.assertTrue(run_row["valid"])
        self.assertEqual(run_row["duration_ms"], 684.0)
        self.assertIsNone(run_row["ttfd_ms"])
        self.assertIsNone(run_row["hitch_ms_per_s"])
        self.assertAlmostEqual(run_row["slow_frame_ratio"], 0.2, places=3)
        self.assertAlmostEqual(run_row["mem_peak_mb"], 312.4, places=1)
        self.assertEqual(run_row["extra"]["capture_mode"], "dumpsys_fallback")
        self.assertEqual(diag_row["extra"]["capture_mode"], "dumpsys_fallback")
        self.assertEqual(run_row["segments"]["cache_rows_shown_ms"], 212)
        self.assertEqual(run_row["segments"]["network_applied_ms"], 455)
        self.assertEqual(run_row["segments"]["reading_anchor_attempt_count"], 3)
        self.assertEqual(run_row["segments"]["visible_shift_dp"], 36.0)
        self.assertEqual(run_row["segments"]["shift_count"], 2)
        self.assertEqual(diag_row["slow_frame_count"], 2)
        self.assertAlmostEqual(diag_row["worst_frame_ms"], 34.2, places=1)
        self.assertIsNone(diag_row["metal_ms"])
        self.assertEqual(diag_row["trace_sha256"], hashlib.sha256(raw_trace).hexdigest())

    def test_gfxinfo_profiledata_flags_opt_in_and_truncation_and_summary_worst_none(self) -> None:
        header = "Flags,IntendedVsync,Vsync,FrameCompleted"
        base_ns = 1_000_000_000_000
        # 3 frames: Flags=0 (8ms), Flags=1 (50ms window layout), Flags=4 (20ms surface view).
        rows = [
            f"0,{base_ns},{base_ns},{base_ns + 8_000_000}",
            f"1,{base_ns + 16_000_000},{base_ns + 16_000_000},{base_ns + 66_000_000}",
            f"4,{base_ns + 80_000_000},{base_ns + 80_000_000},{base_ns + 100_000_000}",
        ]
        gfx_only_pd = "---PROFILEDATA---\n" + header + "\n" + "\n".join(rows) + "\n---PROFILEDATA---\n"

        # Default keeps Flags=1 and Flags=4.
        parsed_default = android_perfetto.parse_dumpsys_gfxinfo_framestats(gfx_only_pd, refresh_hz=60.0)
        self.assertEqual(parsed_default["samples"], 3)
        self.assertAlmostEqual(parsed_default["worst_frame_ms"], 50.0, places=2)
        self.assertEqual(parsed_default["slow_frame_count"], 2)

        # Opt-in drops only (Flags & 1) != 0, keeping Flags=4.
        parsed_excl = android_perfetto.parse_dumpsys_gfxinfo_framestats(
            gfx_only_pd, refresh_hz=60.0, exclude_window_layout_frames=True
        )
        self.assertEqual(parsed_excl["samples"], 2)
        self.assertAlmostEqual(parsed_excl["worst_frame_ms"], 20.0, places=2)
        self.assertEqual(parsed_excl["slow_frame_count"], 1)

        # Truncated ring buffer: summary says 350 frames, PROFILEDATA only has 3 frames -> use summary + flag truncated.
        gfx_truncated = (
            "Total frames rendered: 350\n"
            "Janky frames: 28 (8.00%)\n"
            "50th percentile: 9ms\n"
            "95th percentile: 21ms\n"
            "99th percentile: 39ms\n"
            + gfx_only_pd
        )
        run_row, diag_row = android_perfetto.parse_android_capture(
            run_id="and-trunc-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_scroll_ime",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            gfxinfo_text=gfx_truncated,
            taken_at="2026-11-28T04:20:00Z",
        )
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["samples"], 350)
        self.assertEqual(diag_row["slow_frame_count"], 28)
        self.assertAlmostEqual(run_row["slow_frame_ratio"], 28 / 350, places=5)
        self.assertEqual(run_row["frame_p50_ms"], 9.0)
        self.assertEqual(run_row["frame_p95_ms"], 21.0)
        self.assertEqual(run_row["frame_p99_ms"], 39.0)
        self.assertIsNone(diag_row["worst_frame_ms"])
        self.assertTrue(run_row["extra"]["gfxinfo_profiledata_truncated"])
        self.assertEqual(run_row["extra"]["gfxinfo_profiledata_frame_count"], 3)
        self.assertTrue(diag_row["extra"]["gfxinfo_profiledata_truncated"])

        # Summary-only fallback NEVER substitutes p99 for worst_frame_ms.
        gfx_summary_only = (
            "Total frames rendered: 80\n"
            "Janky frames: 4 (5.00%)\n"
            "50th percentile: 7ms\n"
            "95th percentile: 15ms\n"
            "99th percentile: 29ms\n"
        )
        summary_parsed = android_perfetto.parse_dumpsys_gfxinfo_framestats(gfx_summary_only, refresh_hz=60.0)
        self.assertEqual(summary_parsed["samples"], 80)
        self.assertEqual(summary_parsed["frame_p99_ms"], 29.0)
        self.assertIsNone(summary_parsed["worst_frame_ms"])
        self.assertFalse(summary_parsed["gfxinfo_profiledata_truncated"])

    def test_gfxinfo_virtual_display_zero_jank_uses_histogram_and_legacy_fallbacks(self) -> None:
        # Real Android 12 redroid/Wuying channel_switch capture where GpuCompleted=-1 yields
        # `Janky frames: 0 (0.00%)` despite p95=129ms at 30Hz (budget 33.33ms) and legacy=105.
        redroid_gfx = (
            "Total frames rendered: 183\n"
            "Janky frames: 0 (0.00%)\n"
            "Janky frames (legacy): 105 (57.38%)\n"
            "50th percentile: 19ms\n"
            "90th percentile: 73ms\n"
            "95th percentile: 129ms\n"
            "99th percentile: 250ms\n"
            "HISTOGRAM: 5ms=10 6ms=23 7ms=15 8ms=8 10ms=4 11ms=4 12ms=2 13ms=5 14ms=3 15ms=4 16ms=2 "
            "17ms=1 18ms=6 19ms=17 20ms=5 21ms=4 22ms=2 23ms=4 24ms=3 25ms=1 26ms=3 27ms=1 30ms=4 32ms=1 "
            "34ms=7 38ms=1 40ms=1 44ms=2 46ms=1 48ms=5 53ms=1 57ms=3 61ms=4 65ms=2 69ms=5 73ms=1 "
            "85ms=1 89ms=1 93ms=1 101ms=2 109ms=1 121ms=1 129ms=2 150ms=6 250ms=2 300ms=1 4950ms=0\n"
            "50th gpu percentile: 4950ms\n"
            "GPU HISTOGRAM: 1ms=0 25ms=0 4950ms=0\n"
        )
        run_row, diag_row = android_perfetto.parse_android_capture(
            run_id="and-redroid-hist-01",
            commit_sha="cadafabab94a198a5b3a6bac5d2ff5365253accf",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release",
            scenario="channel_switch",
            device_id="04a70761",
            device_model="wuying android12",
            os_version="android-12",
            refresh_hz=30.0,
            gfxinfo_text=redroid_gfx,
            taken_at="2026-11-28T06:06:10Z",
        )
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        # At 30Hz (33.33ms budget), buckets >= 34ms sum to 51 of 183 frames (not 0 and not legacy 105).
        self.assertEqual(diag_row["slow_frame_count"], 51)
        self.assertAlmostEqual(run_row["slow_frame_ratio"], 51 / 183, places=6)
        self.assertEqual(run_row["extra"]["gfxinfo_janky_source"], "histogram_fallback")
        self.assertEqual(run_row["extra"]["gfxinfo_janky_legacy_count"], 105)
        self.assertEqual(diag_row["extra"]["gfxinfo_janky_source"], "histogram_fallback")

        # Smooth 30Hz run where every frame is 20ms (legacy counts >16.67ms, but HISTOGRAM proves 0 > 33.33ms).
        smooth_30hz_gfx = (
            "Total frames rendered: 50\n"
            "Janky frames: 0 (0.00%)\n"
            "Janky frames (legacy): 50 (100.00%)\n"
            "50th percentile: 20ms\n"
            "95th percentile: 22ms\n"
            "99th percentile: 24ms\n"
            "HISTOGRAM: 19ms=10 20ms=30 22ms=8 24ms=2\n"
        )
        smooth_parsed = android_perfetto.parse_dumpsys_gfxinfo_framestats(smooth_30hz_gfx, refresh_hz=30.0)
        self.assertEqual(smooth_parsed["slow_frame_count"], 0)
        self.assertEqual(smooth_parsed["slow_frame_ratio"], 0.0)
        self.assertIsNone(smooth_parsed.get("gfxinfo_janky_source"))

        # Without HISTOGRAM, falls back to legacy when percentiles exceed the frame budget.
        no_hist_gfx = (
            "Total frames rendered: 100\n"
            "Janky frames: 0 (0.00%)\n"
            "Janky frames (legacy): 25 (25.00%)\n"
            "50th percentile: 14ms\n"
            "95th percentile: 42ms\n"
            "99th percentile: 80ms\n"
        )
        legacy_parsed = android_perfetto.parse_dumpsys_gfxinfo_framestats(no_hist_gfx, refresh_hz=30.0)
        self.assertEqual(legacy_parsed["slow_frame_count"], 25)
        self.assertAlmostEqual(legacy_parsed["slow_frame_ratio"], 0.25, places=6)
        self.assertEqual(legacy_parsed["gfxinfo_janky_source"], "legacy_fallback")
        self.assertEqual(legacy_parsed["gfxinfo_janky_legacy_count"], 25)

    def test_perfetto_sql_csv_and_json_parsing_and_full_window_cpu(self) -> None:
        frame_csv = (
            "frame_token,dur_ms,expected_dur_ms,jank_type,is_jank\n"
            "1,7.5,11.11,None,0\n"
            "2,9.0,11.11,None,0\n"
            "3,10.2,11.11,None,0\n"
            "4,24.8,11.11,App Deadline Missed,1\n"
        )
        main_json = json.dumps(
            [
                {
                    "symbol": "ThreadReadingAnchorKeeper.apply",
                    "thread": "main",
                    "self_ms": 185.4,
                    "total_ms": 310.0,
                    "call_count": 3,
                },
                {
                    "symbol": "Choreographer#doFrame",
                    "thread": "main",
                    "self_ms": 42.1,
                    "total_ms": 420.5,
                    "call_count": 4,
                },
            ]
        )
        render_csv = (
            "symbol,thread,self_ms,total_ms,call_count\n"
            "DrawFrame,RenderThread,95.2,110.4,4\n"
        )
        recomp_csv = "recomp_count,total_ms\n14,68.2\n"
        mem_csv = "mem_peak_mb\n298.75\n"

        # 1. Without perfetto_thread_cpu: ui_thread_ms & render_thread_ms stay None (never summed from hotspots!).
        run_no_cpu, diag_no_cpu = android_perfetto.parse_android_capture(
            run_id="and-perfetto-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            duration_ms=610.0,
            perfetto_frame_timeline=frame_csv,
            perfetto_main_thread=main_json,
            perfetto_render_thread=render_csv,
            perfetto_compose_recomp=recomp_csv,
            perfetto_memory=mem_csv,
            taken_at="2026-11-28T04:21:00Z",
        )
        self.assertEqual(validate_run_row(run_no_cpu), [])
        self.assertEqual(validate_diagnose_row(diag_no_cpu), [])
        self.assertEqual(run_no_cpu["samples"], 4)
        self.assertEqual(run_no_cpu["mem_peak_mb"], 298.75)
        self.assertIsNone(diag_no_cpu["ui_thread_ms"])
        self.assertIsNone(diag_no_cpu["render_thread_ms"])
        self.assertIsNone(diag_no_cpu["window_ms"])
        self.assertEqual(diag_no_cpu["compose_recomp_count"], 14)
        self.assertEqual(diag_no_cpu["slow_frame_count"], 1)
        self.assertEqual(diag_no_cpu["top_hotspots"][0]["symbol"], "ThreadReadingAnchorKeeper.apply")
        self.assertEqual(run_no_cpu["extra"]["capture_mode"], "perfetto")

        # 2. With perfetto_thread_cpu + perfetto_trace_bounds (+ dumpsys meminfo -> capture_mode="perfetto+dumpsys").
        thread_cpu_csv = "thread_role,cpu_ms\nmain,812.45\nRenderThread,304.12\n"
        trace_bounds_csv = "window_ms\n6012.5\n"
        run_full, diag_full = android_perfetto.parse_android_capture(
            run_id="and-perfetto-02",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            duration_ms=610.0,
            perfetto_frame_timeline=frame_csv,
            perfetto_main_thread=main_json,
            perfetto_render_thread=render_csv,
            perfetto_thread_cpu=thread_cpu_csv,
            perfetto_trace_bounds=trace_bounds_csv,
            thermal_start_text="Current status: 0\n",
            taken_at="2026-11-28T04:21:00Z",
        )
        self.assertEqual(validate_run_row(run_full), [])
        self.assertEqual(validate_diagnose_row(diag_full), [])
        self.assertAlmostEqual(diag_full["ui_thread_ms"], 812.45, places=2)
        self.assertAlmostEqual(diag_full["render_thread_ms"], 304.12, places=2)
        self.assertAlmostEqual(diag_full["window_ms"], 6012.5, places=1)
        self.assertEqual(run_full["extra"]["capture_mode"], "perfetto+dumpsys")

    def test_android_requires_refresh_hz_without_silent_60hz_default(self) -> None:
        with self.assertRaises(ValueError):
            android_perfetto.parse_dumpsys_gfxinfo_framestats("Total frames rendered: 10\n")

        with self.assertRaises(ValueError):
            android_perfetto.parse_android_capture(
                run_id="and-no-hz",
                commit_sha="0123456789abcdef0123456789abcdef01234567",
                app_version="1.31.0",
                app_build="13100019",
                build_type="release-profileable",
                scenario="thread_open",
                device_id="3f9a1c22",
                device_model="Redmi Note 12",
                os_version="android-14",
                taken_at="2026-11-28T04:22:00Z",
            )

    def test_zero_sample_android_capture_is_valid_schema_with_valid_false(self) -> None:
        run_row, diag_row = android_perfetto.parse_android_capture(
            run_id="and-empty-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            taken_at="2026-11-28T04:22:00Z",
        )
        self.assertEqual(run_row["samples"], 0)
        self.assertFalse(run_row["valid"])
        self.assertIsNone(run_row["frame_p95_ms"])
        self.assertIsNone(run_row["slow_frame_ratio"])
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])

    def test_android_logcat_package_filter_and_ttid_vs_ttfd(self) -> None:
        # 1. Displayed alone populates segments.ttid_ms and compose_first_frame_ms, NEVER ttfd_ms;
        #    other packages are ignored.
        log_displayed_only = "\n".join(
            [
                "11-28 04:23:00.900 I/ActivityTaskManager: Displayed com.other.app/.Launcher: +400ms",
                "11-28 04:23:01.000 I/ActivityTaskManager: Fully drawn com.other.app/.Launcher: +900ms",
                "11-28 04:23:01.200 I/ActivityTaskManager: Displayed build.raft.app/.MainActivity for user 0: +1s245ms",
            ]
        )
        p1 = android_perfetto.parse_android_logcat(log_displayed_only)
        self.assertIsNone(p1["ttfd_ms"])
        self.assertEqual(p1["segments"]["ttid_ms"], 1245.0)
        self.assertEqual(p1["segments"]["compose_first_frame_ms"], 1245.0)

        # 2. Fully drawn for target package populates ttfd_ms.
        log_with_fd = log_displayed_only + "\n11-28 04:23:01.850 I/ActivityTaskManager: Fully drawn build.raft.app/.MainActivity: +1s850ms\n"
        p2 = android_perfetto.parse_android_logcat(log_with_fd)
        self.assertEqual(p2["ttfd_ms"], 1850.0)
        self.assertEqual(p2["segments"]["ttid_ms"], 1245.0)

        # 3. SlockPerf first_feed_render_ms populates ttfd_ms when Fully drawn is absent.
        gfx_summary = (
            "Total frames rendered: 120\n"
            "Janky frames: 6 (5.00%)\n"
            "50th percentile: 7ms\n"
            "95th percentile: 15ms\n"
            "99th percentile: 29ms\n"
        )
        logcat = (
            "11-28 04:23:01.200 I/ActivityTaskManager: Displayed build.raft.app/.MainActivity: +1s245ms\n"
            "11-28 04:23:01.210 D/SlockPerf: marker route=home phase=cold_start process_init_ms=412 auth_bootstrap_ms=180 first_feed_render_ms=1120\n"
        )
        run_row, diag_row = android_perfetto.parse_android_capture(
            run_id="and-cold-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="cold_start",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            warm_state="cold",
            gfxinfo_text=gfx_summary,
            logcat_text=logcat,
            taken_at="2026-11-28T04:23:00Z",
        )
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["ttfd_ms"], 1120.0)
        self.assertEqual(run_row["segments"]["ttid_ms"], 1245.0)
        self.assertEqual(run_row["segments"]["process_init_ms"], 412)
        self.assertEqual(run_row["segments"]["compose_first_frame_ms"], 1245.0)
        self.assertEqual(run_row["segments"]["auth_bootstrap_ms"], 180)
        self.assertEqual(run_row["segments"]["first_feed_render_ms"], 1120)
        self.assertEqual(run_row["samples"], 120)
        self.assertAlmostEqual(run_row["slow_frame_ratio"], 0.05, places=4)
        self.assertIsNone(diag_row["worst_frame_ms"])

    def test_parse_dumpsys_thermalservice_whole_device_and_max_sensor_tier(self) -> None:
        # Whole-device Current status must win over early cold sensor mStatus=0.
        hal_plus_current = (
            "Current temperatures from HAL:\n"
            "  Temperature{mValue=29.0, mType=0, mName=cpu0, mStatus=0}\n"
            "  Temperature{mValue=44.5, mType=3, mName=SKIN, mStatus=3}\n"
            "Thermal Status:\n"
            "  Current status: THERMAL_STATUS_SEVERE\n"
        )
        self.assertEqual(android_perfetto.parse_dumpsys_thermalservice(hal_plus_current), "severe")

        # When Current status / Thermal status is absent, take MAX tier across all mStatus lines.
        sensors_only = (
            "Temperature{mValue=29.0, mType=0, mName=cpu0, mStatus=0}\n"
            "Temperature{mValue=36.0, mType=1, mName=gpu, mStatus=1}\n"
            "Temperature{mValue=45.0, mType=3, mName=SKIN, mStatus=THERMAL_STATUS_CRITICAL}\n"
            "Temperature{mValue=30.0, mType=2, mName=battery, mStatus=0}\n"
        )
        self.assertEqual(android_perfetto.parse_dumpsys_thermalservice(sensors_only), "critical")

    def test_android_capture_thermal_throttling_sets_valid_false(self) -> None:
        gfx_summary = (
            "Total frames rendered: 100\n"
            "Janky frames: 12 (12.00%)\n"
            "50th percentile: 9ms\n"
            "95th percentile: 22ms\n"
            "99th percentile: 40ms\n"
        )
        run_row, diag_row = android_perfetto.parse_android_capture(
            run_id="and-hot-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            gfxinfo_text=gfx_summary,
            thermal_start_text="Thermal Status:\n  Current status: 1\n",
            thermal_end_text="Thermal Status:\n  Current status: 3\n",
            taken_at="2026-11-28T04:24:00Z",
        )
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["samples"], 100)
        self.assertFalse(run_row["valid"])
        self.assertFalse(diag_row["valid"])
        self.assertEqual(run_row["thermal_end"], "severe")
        self.assertEqual(run_row["extra"]["thermal_invalid_reason"], "thermal_end=severe")

        # Tier jump >= 2 (none -> moderate) and hot start (severe -> light) also mark valid=False.
        run_jump, diag_jump = android_perfetto.parse_android_capture(
            run_id="and-jump-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            gfxinfo_text=gfx_summary,
            thermal_start="none",
            thermal_end="moderate",
            taken_at="2026-11-28T04:24:10Z",
        )
        self.assertFalse(run_jump["valid"])
        self.assertFalse(diag_jump["valid"])
        self.assertIn("thermal_tier_jump=none->moderate", run_jump["extra"]["thermal_invalid_reason"])

        run_hot_start, _ = android_perfetto.parse_android_capture(
            run_id="and-hot-start-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100019",
            build_type="release-profileable",
            scenario="thread_open",
            device_id="3f9a1c22",
            device_model="Redmi Note 12",
            os_version="android-14",
            refresh_hz=90.0,
            gfxinfo_text=gfx_summary,
            thermal_start="severe",
            thermal_end="light",
            taken_at="2026-11-28T04:24:20Z",
        )
        self.assertFalse(run_hot_start["valid"])
        self.assertEqual(run_hot_start["extra"]["thermal_invalid_reason"], "thermal_start=severe")


class TestIosXctrace(unittest.TestCase):
    def test_command_builders(self) -> None:
        rec = ios_xctrace.build_xctrace_record_command(
            "Animation Hitches",
            "/tmp/out.trace",
            device="00008030-001122334455",
            launch_app="build.raft.app",
            time_limit_s=8,
        )
        self.assertEqual(
            rec,
            [
                "xcrun",
                "xctrace",
                "record",
                "--template",
                "Animation Hitches",
                "--output",
                "/tmp/out.trace",
                "--device",
                "00008030-001122334455",
                "--time-limit",
                "8s",
                "--launch",
                "--",
                "build.raft.app",
            ],
        )
        self.assertIn("--toc", ios_xctrace.build_xctrace_toc_command("/tmp/out.trace"))
        exp = ios_xctrace.build_xctrace_export_command("/tmp/out.trace", ios_xctrace.XPATH_HITCHES)
        self.assertIn(ios_xctrace.XPATH_HITCHES, exp)

    def test_xctrace_xml_exports_and_trace_bundle_sha256(self) -> None:
        hitches_xml = """\
<trace-query-result>
  <node xpath="hitches">
    <row><frame-duration fmt="12.0 ms">12000000</frame-duration></row>
    <row><frame-duration fmt="15.0 ms">15000000</frame-duration></row>
    <row><frame-duration fmt="16.0 ms">16000000</frame-duration></row>
    <row><frame-duration fmt="48.5 ms">48500000</frame-duration><hitch-duration fmt="31.8 ms">31800000</hitch-duration></row>
    <row><frame-duration fmt="96.4 ms">96400000</frame-duration><hitch-duration fmt="79.7 ms">79700000</hitch-duration></row>
  </node>
</trace-query-result>
"""
        time_profile_xml = """\
<trace-query-result>
  <node xpath="time-profile">
    <row>
      <weight fmt="120.5 ms">120500000</weight>
      <thread id="1" fmt="Main Thread 0x101 (Raft)"/>
      <backtrace id="10">
        <frame id="100" name="ThreadReadingAnchorKeeper.apply"/>
        <frame id="101" name="ThreadMessagesList.layout"/>
      </backtrace>
    </row>
    <row>
      <weight fmt="92.0 ms">92000000</weight>
      <thread ref="1"/>
      <backtrace ref="10"/>
    </row>
    <row>
      <weight fmt="175.5 ms">175500000</weight>
      <thread ref="1"/>
      <backtrace>
        <frame id="102" name="SkikoCanvas.draw"/>
        <frame ref="100"/>
      </backtrace>
    </row>
  </node>
</trace-query-result>
"""
        thermal_xml = """\
<trace-query-result>
  <node xpath="thermal-state">
    <row><thermal-state fmt="Nominal">0</thermal-state></row>
    <row><thermal-state fmt="Fair">1</thermal-state></row>
  </node>
</trace-query-result>
"""
        metal_xml = """\
<trace-query-result>
  <node xpath="metal">
    <row><duration fmt="250.0 ms">250000000</duration></row>
    <row><duration fmt="360.0 ms">360000000</duration></row>
  </node>
</trace-query-result>
"""
        signpost_xml = """\
<trace-query-result>
  <node xpath="signposts">
    <row>
      <signpost-name>cache_rows_shown</signpost-name>
      <duration fmt="198.0 ms">198000000</duration>
    </row>
    <row>
      <signpost-name>network_applied</signpost-name>
      <duration fmt="503.0 ms">503000000</duration>
    </row>
  </node>
</trace-query-result>
"""
        app_log = (
            '{"tag":"SlockPerf","message":"open_trace_summary duration_ms=640 shift_count=1 shift_sum_dp=18.5"}\n'
            '{"tag":"SlockPerf","message":"open_trace_shift seq=1 delta_dp=18.5 cause=reading_anchor owner_attempt=2"}\n'
        )

        with tempfile.TemporaryDirectory() as tmp:
            trace_bundle = Path(tmp) / "thread_open.trace"
            (trace_bundle / "corespace").mkdir(parents=True)
            (trace_bundle / "toc.xml").write_text("<toc/>", encoding="utf-8")
            (trace_bundle / "corespace" / "run1.bin").write_bytes(b"\x01\x02\x03\x04")

            run_row, diag_row = ios_xctrace.parse_xctrace_exports(
                run_id="ios-diag-01",
                commit_sha="0123456789abcdef0123456789abcdef01234567",
                app_version="1.31.0",
                app_build="13100006",
                build_type="release-dsym",
                scenario="thread_open",
                device_id="9b20e4d1",
                device_model="iPhone 11",
                os_version="ios-17.6",
                refresh_hz=60.0,
                window_ms=5000.0,
                hitches_xml=hitches_xml,
                time_profile_xml=time_profile_xml,
                thermal_xml=thermal_xml,
                metal_xml=metal_xml,
                signpost_xml=signpost_xml,
                app_log_text=app_log,
                trace_path=trace_bundle,
                trace_link="s3://perf-traces/ios-diag-01.trace.tgz",
                taken_at="2026-11-28T04:25:00Z",
            )

        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["thermal_start"], "nominal")
        self.assertEqual(run_row["thermal_end"], "fair")
        self.assertEqual(run_row["duration_ms"], 640.0)
        self.assertAlmostEqual(run_row["hitch_ms_per_s"], 22.3, places=1)
        self.assertEqual(run_row["extra"]["capture_mode"], "xctrace")
        self.assertEqual(run_row["extra"]["time_profile_sample_count"], 3)
        self.assertEqual(run_row["segments"]["cache_rows_shown_ms"], 198.0)
        self.assertEqual(run_row["segments"]["network_applied_ms"], 503.0)
        self.assertEqual(run_row["segments"]["reading_anchor_attempt_count"], 2)
        self.assertEqual(run_row["segments"]["visible_shift_dp"], 18.5)
        self.assertEqual(run_row["segments"]["shift_count"], 1)

        self.assertIsNone(diag_row["render_thread_ms"])
        self.assertAlmostEqual(diag_row["metal_ms"], 610.0, places=1)
        self.assertAlmostEqual(diag_row["ui_thread_ms"], 388.0, places=1)
        self.assertAlmostEqual(diag_row["worst_frame_ms"], 96.4, places=1)
        self.assertEqual(diag_row["slow_frame_count"], 2)
        self.assertEqual(len(diag_row["trace_sha256"]), 64)
        top = diag_row["top_hotspots"][0]
        self.assertEqual(top["symbol"], "ThreadReadingAnchorKeeper.apply")
        self.assertEqual(top["thread"], "main")
        self.assertAlmostEqual(top["self_ms"], 212.5, places=1)
        self.assertAlmostEqual(top["total_ms"], 388.0, places=1)

    def test_ios_zero_hitch_valid_run_and_strict_sample_separation(self) -> None:
        empty_hitches = "<trace-query-result><node xpath='hitches'></node></trace-query-result>"
        time_profile_xml = (
            "<trace-query-result><node>"
            '<row><start-time>1000000000</start-time><weight fmt="10.0 ms">10000000</weight>'
            '<thread fmt="Main Thread (Raft)"/><backtrace><frame name="Foo.bar"/></backtrace></row>'
            '<row><start-time>5990000000</start-time><weight fmt="10.0 ms">10000000</weight>'
            '<thread fmt="Main Thread (Raft)"/><backtrace><frame name="Foo.bar"/></backtrace></row>'
            "</node></trace-query-result>"
        )

        # 1. Zero hitches + explicit frame_count + inferred window_ms (5000ms from XML timestamps) -> valid=True, hitch_ms_per_s=0.0!
        run_ok, diag_ok = ios_xctrace.parse_xctrace_exports(
            run_id="ios-0hitch-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100006",
            build_type="release-dsym",
            scenario="thread_scroll_ime",
            device_id="9b20e4d1",
            device_model="iPhone 11",
            os_version="ios-17.6",
            refresh_hz=60.0,
            frame_count=300,
            thermal_start="nominal",
            thermal_end="nominal",
            hitches_xml=empty_hitches,
            time_profile_xml=time_profile_xml,
            taken_at="2026-11-28T04:26:00Z",
        )
        self.assertEqual(validate_run_row(run_ok), [])
        self.assertEqual(validate_diagnose_row(diag_ok), [])
        self.assertEqual(run_ok["samples"], 300)
        self.assertTrue(run_ok["valid"])
        self.assertEqual(run_ok["hitch_ms_per_s"], 0.0)
        self.assertEqual(diag_ok["slow_frame_count"], 0)
        self.assertAlmostEqual(diag_ok["window_ms"], 5000.0, places=1)
        self.assertEqual(run_ok["extra"]["time_profile_sample_count"], 2)

        # 2. Zero hitches + app_log frame_count + NO window_ms & NO timestamps -> window_ms=None, hitch_ms_per_s=None, valid=True.
        run_no_win, diag_no_win = ios_xctrace.parse_xctrace_exports(
            run_id="ios-0hitch-nowin",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100006",
            build_type="release-dsym",
            scenario="thread_scroll_ime",
            device_id="9b20e4d1",
            device_model="iPhone 11",
            os_version="ios-17.6",
            refresh_hz=60.0,
            thermal_start="nominal",
            thermal_end="nominal",
            hitches_xml=empty_hitches,
            app_log_text="SlockPerf frame_count=240\n",
            taken_at="2026-11-28T04:26:00Z",
        )
        self.assertEqual(validate_run_row(run_no_win), [])
        self.assertEqual(validate_diagnose_row(diag_no_win), [])
        self.assertEqual(run_no_win["samples"], 240)
        self.assertTrue(run_no_win["valid"])
        self.assertIsNone(diag_no_win["window_ms"])
        self.assertIsNone(run_no_win["hitch_ms_per_s"])
        self.assertEqual(diag_no_win["slow_frame_count"], 0)

        # 3. Time Profiler + hitch-only rows WITHOUT frame_count -> samples MUST be 0 (never CPU/hitch count!).
        hitch_only_xml = (
            "<trace-query-result><node>"
            '<row><hitch-duration fmt="30.0 ms">30000000</hitch-duration></row>'
            "</node></trace-query-result>"
        )
        run_no_frames, diag_no_frames = ios_xctrace.parse_xctrace_exports(
            run_id="ios-noframes",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100006",
            build_type="release-dsym",
            scenario="thread_scroll_ime",
            device_id="9b20e4d1",
            device_model="iPhone 11",
            os_version="ios-17.6",
            refresh_hz=60.0,
            window_ms=5000.0,
            hitches_xml=hitch_only_xml,
            time_profile_xml=time_profile_xml,
            taken_at="2026-11-28T04:26:00Z",
        )
        self.assertEqual(validate_run_row(run_no_frames), [])
        self.assertEqual(validate_diagnose_row(diag_no_frames), [])
        self.assertEqual(run_no_frames["samples"], 0)
        self.assertFalse(run_no_frames["valid"])
        self.assertEqual(run_no_frames["extra"]["time_profile_sample_count"], 2)
        self.assertIsNone(run_no_frames["hitch_ms_per_s"])
        self.assertIsNone(diag_no_frames["slow_frame_count"])

    def test_ios_requires_refresh_hz_and_process_name_filter(self) -> None:
        with self.assertRaises(ValueError):
            ios_xctrace.parse_xctrace_hitches_xml("<trace-query-result/>")
        with self.assertRaises(ValueError):
            ios_xctrace.parse_xctrace_exports(
                run_id="ios-nohz",
                commit_sha="0123456789abcdef0123456789abcdef01234567",
                app_version="1.31.0",
                app_build="13100006",
                build_type="release-dsym",
                scenario="thread_open",
                device_id="9b20e4d1",
                device_model="iPhone 11",
                os_version="ios-17.6",
            )

        # Cross-process XML filtering by <process> and <thread fmt="... (OtherApp)">.
        hitches_xml = """\
<trace-query-result><node>
  <row><process fmt="Raft (421)"/><frame-duration fmt="14.0 ms">14000000</frame-duration></row>
  <row><process fmt="SpringBoard (55)"/><frame-duration fmt="95.0 ms">95000000</frame-duration><hitch-duration fmt="78.0 ms">78000000</hitch-duration></row>
  <row><process fmt="Raft (421)"/><frame-duration fmt="32.0 ms">32000000</frame-duration><hitch-duration fmt="15.3 ms">15300000</hitch-duration></row>
</node></trace-query-result>
"""
        time_profile_xml = """\
<trace-query-result><node>
  <row><weight fmt="50.0 ms">50000000</weight><thread fmt="Main Thread 0x1 (Raft)"/><backtrace><frame name="RaftWork"/></backtrace></row>
  <row><weight fmt="400.0 ms">400000000</weight><thread fmt="Main Thread 0x2 (SpringBoard)"/><backtrace><frame name="SBWork"/></backtrace></row>
</node></trace-query-result>
"""
        metal_xml = """\
<trace-query-result><node>
  <row><process fmt="Raft (421)"/><duration fmt="120.0 ms">120000000</duration></row>
  <row><process fmt="backboardd (99)"/><duration fmt="900.0 ms">900000000</duration></row>
</node></trace-query-result>
"""
        signpost_xml = """\
<trace-query-result><node>
  <row><process fmt="Raft (421)"/><signpost-name>cache_rows_shown</signpost-name><duration fmt="150.0 ms">150000000</duration></row>
  <row><process fmt="OtherApp (77)"/><signpost-name>network_applied</signpost-name><duration fmt="999.0 ms">999000000</duration></row>
</node></trace-query-result>
"""
        run_row, diag_row = ios_xctrace.parse_xctrace_exports(
            run_id="ios-proc-filter",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100006",
            build_type="release-dsym",
            scenario="thread_open",
            device_id="9b20e4d1",
            device_model="iPhone 11",
            os_version="ios-17.6",
            refresh_hz=60.0,
            window_ms=5000.0,
            process_name="raft",
            hitches_xml=hitches_xml,
            time_profile_xml=time_profile_xml,
            metal_xml=metal_xml,
            signpost_xml=signpost_xml,
            taken_at="2026-11-28T04:26:00Z",
        )
        self.assertEqual(run_row["samples"], 2)
        self.assertAlmostEqual(diag_row["worst_frame_ms"], 32.0, places=1)
        self.assertEqual(diag_row["slow_frame_count"], 1)
        self.assertAlmostEqual(diag_row["ui_thread_ms"], 50.0, places=1)
        self.assertEqual([h["symbol"] for h in diag_row["top_hotspots"]], ["RaftWork"])
        self.assertAlmostEqual(diag_row["metal_ms"], 120.0, places=1)
        self.assertEqual(run_row["segments"]["cache_rows_shown_ms"], 150.0)
        self.assertNotIn("network_applied_ms", run_row["segments"])

    def test_ios_capture_thermal_throttling_sets_valid_false(self) -> None:
        hitches_xml = (
            "<trace-query-result><node>"
            '<row><frame-duration fmt="12.0 ms">12000000</frame-duration></row>'
            '<row><frame-duration fmt="45.0 ms">45000000</frame-duration>'
            '<hitch-duration fmt="28.3 ms">28300000</hitch-duration></row>'
            "</node></trace-query-result>"
        )
        thermal_xml = (
            "<trace-query-result><node>"
            '<row><thermal-state fmt="Nominal">0</thermal-state></row>'
            '<row><thermal-state fmt="Serious">2</thermal-state></row>'
            "</node></trace-query-result>"
        )
        run_row, diag_row = ios_xctrace.parse_xctrace_exports(
            run_id="ios-hot-01",
            commit_sha="0123456789abcdef0123456789abcdef01234567",
            app_version="1.31.0",
            app_build="13100006",
            build_type="release-dsym",
            scenario="thread_scroll_ime",
            device_id="9b20e4d1",
            device_model="iPhone 11",
            os_version="ios-17.6",
            refresh_hz=60.0,
            window_ms=5000.0,
            hitches_xml=hitches_xml,
            thermal_xml=thermal_xml,
            taken_at="2026-11-28T04:26:00Z",
        )
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["samples"], 2)
        self.assertFalse(run_row["valid"])
        self.assertFalse(diag_row["valid"])
        self.assertIsNotNone(run_row["hitch_ms_per_s"])
        self.assertEqual(run_row["extra"]["thermal_invalid_reason"], "thermal_end=serious")
        self.assertEqual(diag_row["extra"]["thermal_invalid_reason"], "thermal_end=serious")


class TestAbVerdictAndCalibration(unittest.TestCase):
    def _build_pairs(
        self,
        base_vals: list[float],
        head_vals: list[float],
        **kwargs,
    ) -> list[dict]:
        rows = []
        for idx, (b, h) in enumerate(zip(base_vals, head_vals), start=1):
            rows.append(_make_ab_row(arm="base", iteration=idx, duration_ms=b, **kwargs))
            rows.append(_make_ab_row(arm="head", iteration=idx, duration_ms=h, **kwargs))
        return rows

    def test_improved_and_deterministic(self) -> None:
        rows = self._build_pairs(
            [682, 686, 680, 684, 679, 685, 681, 683],
            [560, 564, 558, 562, 557, 563, 559, 561],
        )
        r1 = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        r2 = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(r1, r2)
        self.assertEqual(r1["verdict"], "improved")
        self.assertEqual(r1["p_value_method"], "exact_paired_sign_flip")
        self.assertLessEqual(r1["p_value"], 0.05)
        self.assertLess(r1["paired_median_delta"], -100.0)
        self.assertEqual(r1["thermal_unverified_count"], 0)
        self.assertEqual(r1["thermal_unverified_runs"], [])

    def test_default_min_pairs_six_and_no_unpaired_fallback(self) -> None:
        five_pairs = self._build_pairs(
            [680, 685, 682, 678, 684],
            [520, 525, 522, 518, 524],
        )
        # Default min_pairs=6 rejects 5 pairs as insufficient_samples.
        res_default = ab_verdict.evaluate_ab(five_pairs, primary_metric="duration_ms")
        self.assertEqual(res_default["verdict"], "insufficient_samples")
        self.assertEqual(res_default["min_pairs"], 6)
        self.assertEqual(res_default["paired_count"], 5)

        # Even with explicit min_pairs=5, NEVER falls back to unpaired two-sample permutation:
        # exact_paired_sign_flip yields p = 2 / 32 = 0.0625 > 0.05 -> noisy, not improved!
        res_five = ab_verdict.evaluate_ab(five_pairs, primary_metric="duration_ms", min_pairs=5)
        self.assertEqual(res_five["p_value_method"], "exact_paired_sign_flip")
        self.assertAlmostEqual(res_five["p_value"], 0.0625, places=6)
        self.assertEqual(res_five["verdict"], "noisy")

        # 6 pairs reaches exact paired sign-flip p = 2 / 64 = 0.03125 <= 0.05 -> improved!
        six_pairs = self._build_pairs(
            [680, 685, 682, 678, 684, 681],
            [520, 525, 522, 518, 524, 521],
        )
        res_six = ab_verdict.evaluate_ab(six_pairs, primary_metric="duration_ms")
        self.assertEqual(res_six["verdict"], "improved")
        self.assertEqual(res_six["p_value_method"], "exact_paired_sign_flip")
        self.assertAlmostEqual(res_six["p_value"], 0.03125, places=6)

    def test_paired_median_delta_drives_decision_effect_size(self) -> None:
        # Construct pairs where head_med - base_med has opposite sign to paired_median_delta:
        # base = [100, 100, 100, 1000, 1000, 1000] (med=550)
        # head = [120, 120, 120, 1020, 1020, 1020] -> both +20; instead make step shift:
        # base = [200, 200, 200, 1000, 1000, 1000] -> med = 600
        # head = [1050, 100, 100, 900, 900, 900] -> sorted: [100, 100, 900, 900, 900, 1050], med = 900
        # median_delta = +300, whereas deltas = [+850, -100, -100, -100, -100, -100] -> paired_median_delta = -100!
        # Even cleaner: all 6 deltas negative while median_delta != paired_median_delta:
        # base = [600, 600, 600, 700, 700, 700] (med=650, MAD=50)
        # head = [520, 520, 520, 480, 480, 480] (med=500, MAD=20)
        # median_delta = 500 - 650 = -150; deltas = [-80, -80, -80, -220, -220, -220] -> paired_median_delta = -150.
        # Now asymmetric:
        # base = [600, 601, 602, 650, 651, 652] (med=626)
        # head = [400, 401, 402, 620, 621, 622] (med=511 -> median_delta=-115)
        # deltas = [-200, -200, -200, -30, -30, -30] -> paired_median_delta = -115.
        # Make 4 small negative deltas (-30) and 2 huge negative deltas (-300):
        # base = [600, 600, 600, 600, 600, 600] (med=600) -> wait, base=[500, 500, 600, 600, 600, 600] (med=600)
        # head = [200, 200, 570, 570, 570, 570] (med=570 -> median_delta=-30, paired_median_delta=-30).
        # To make median_delta != paired_median_delta:
        # base = [500, 500, 500, 620, 620, 620] (med = 560, MAD = 60)
        # head = [460, 460, 460, 470, 470, 470] (med = 465, MAD = 5)
        # median_delta = 465 - 560 = -95.0
        # deltas = [-40, -40, -40, -150, -150, -150] -> paired_median_delta = -95.0.
        # Make 4 pairs with delta=-140 and 2 pairs with delta=-10:
        # base = [500, 500, 550, 550, 550, 550] -> med = 550
        # head = [490, 490, 410, 410, 410, 410] -> sorted: [410, 410, 410, 410, 490, 490], med = 410 -> median_delta = -140, deltas = [-10, -10, -140, -140, -140, -140] -> paired_med = -140.
        # Make base = [500, 510, 520, 600, 610, 620] (med=560)
        # head = [400, 410, 420, 590, 500, 510] -> deltas = [-100, -100, -100, -10, -110, -110]
        # sorted head = [400, 410, 420, 500, 510, 590] -> head_med = 460 -> median_delta = -100.
        # Shift head[3] to 595, head[0..2] to [490, 500, 510], head[4..5] to [500, 510]:
        # Let's compute directly:
        base = [500.0, 500.0, 600.0, 600.0, 700.0, 700.0]  # med = 600.0
        head = [490.0, 490.0, 490.0, 490.0, 690.0, 690.0]  # sorted med = 490.0 -> median_delta = -110.0
        # deltas = [-10, -10, -110, -110, -10, -10] -> sorted: [-110, -110, -10, -10, -10, -10] -> paired_median_delta = -10.0!
        rows = self._build_pairs(base, head)
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms", max_noise_ratio=0.5)
        self.assertEqual(res["median_delta"], -110.0)
        self.assertEqual(res["paired_median_delta"], -10.0)
        self.assertAlmostEqual(res["relative_delta_pct"], -10.0 / 600.0 * 100.0, places=3)
        # Since |paired_median_delta| = 10.0 <= practical_floor (30.0), verdict is neutral (NOT improved as -110 would claim!).
        self.assertEqual(res["verdict"], "neutral")

    def test_regressed(self) -> None:
        rows = self._build_pairs(
            [610, 615, 608, 612, 614, 611],
            [740, 745, 738, 742, 744, 741],
        )
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "regressed")
        self.assertGreater(res["paired_median_delta"], 100.0)

    def test_neutral(self) -> None:
        rows = self._build_pairs(
            [680, 684, 678, 682, 685, 681],
            [683, 681, 682, 679, 686, 684],
        )
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "neutral")

    def test_noisy_high_variance(self) -> None:
        rows = self._build_pairs(
            [420, 910, 510, 960, 480, 880],
            [450, 940, 490, 990, 460, 920],
        )
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "noisy")

    def test_cross_cohort_and_session_and_duplicate_and_run_kind_and_arm_gates(self) -> None:
        # 1. Cross-cohort rejected.
        cross = [
            _make_ab_row(arm="base", iteration=1, duration_ms=680, device_id="dev-A"),
            _make_ab_row(arm="head", iteration=1, duration_ms=520, device_id="dev-B"),
        ]
        self.assertEqual(ab_verdict.evaluate_ab(cross)["verdict"], "invalid_cohort")

        # 2. Missing ab_session_id rejected.
        missing_sess = self._build_pairs([680] * 6, [560] * 6)
        missing_sess[0]["ab_session_id"] = None
        res_ms = ab_verdict.evaluate_ab(missing_sess)
        self.assertEqual(res_ms["verdict"], "invalid_cohort")
        self.assertTrue(any("ab_session_id" in r for r in res_ms["reasons"]))

        # 3. Multiple ab_session_id values rejected.
        multi_sess = self._build_pairs([680] * 6, [560] * 6)
        multi_sess[-1]["ab_session_id"] = "ab-other-session"
        res_multi = ab_verdict.evaluate_ab(multi_sess)
        self.assertEqual(res_multi["verdict"], "invalid_cohort")
        self.assertTrue(any("ab_session_id mismatch" in r for r in res_multi["reasons"]))

        # 4. Duplicate (ab_arm, iteration) across valid rows AND across valid+invalid rows rejected.
        dup_valid = self._build_pairs([680] * 6, [560] * 6)
        dup_valid.append(_make_ab_row(arm="base", iteration=2, duration_ms=690))
        res_dup = ab_verdict.evaluate_ab(dup_valid)
        self.assertEqual(res_dup["verdict"], "invalid_cohort")
        self.assertTrue(any("base#2" in r for r in res_dup["reasons"]))

        dup_mixed = self._build_pairs([680] * 6, [560] * 6)
        dup_mixed.append(_make_ab_row(arm="head", iteration=3, duration_ms=560, valid=False, samples=0))
        res_dup_mixed = ab_verdict.evaluate_ab(dup_mixed)
        self.assertEqual(res_dup_mixed["verdict"], "invalid_cohort")
        self.assertTrue(any("head#3" in r for r in res_dup_mixed["reasons"]))

        # 5. Non-'ab' run_kind rejected.
        bad_rk = self._build_pairs([680] * 6, [560] * 6)
        bad_rk[0]["run_kind"] = "baseline"
        res_rk = ab_verdict.evaluate_ab(bad_rk)
        self.assertEqual(res_rk["verdict"], "invalid_cohort")
        self.assertTrue(any("run_kind" in r for r in res_rk["reasons"]))

        # 6. calibration_injected_ms > 0 rejected in evaluate_ab.
        bad_cal = self._build_pairs([680] * 6, [560] * 6)
        bad_cal[1]["calibration_injected_ms"] = 200.0
        res_cal = ab_verdict.evaluate_ab(bad_cal)
        self.assertEqual(res_cal["verdict"], "invalid_cohort")
        self.assertTrue(any("calibration_injected_ms" in r for r in res_cal["reasons"]))

        # 7. Unrecognized ab_arm (e.g. "pr") never silently skipped -> invalid_cohort with count & values.
        bad_arm = self._build_pairs([680] * 6, [560] * 6)
        extra_pr = _make_ab_row(arm="head", iteration=7, duration_ms=550)
        extra_pr["ab_arm"] = "pr"
        bad_arm.append(extra_pr)
        res_arm = ab_verdict.evaluate_ab(bad_arm)
        self.assertEqual(res_arm["verdict"], "invalid_cohort")
        self.assertEqual(res_arm["unrecognized_arm_count"], 1)
        self.assertEqual(res_arm["unrecognized_arms"], ["pr"])
        self.assertTrue(any("unrecognized ab_arm" in r and "pr" in r for r in res_arm["reasons"]))

    def test_thermal_classification_prefix_start_end_and_unverified_reporting(self) -> None:
        # Prefix stripping & both start and end checks.
        self.assertEqual(
            ab_verdict.is_thermal_throttled("THERMAL_STATUS_NONE", "THERMAL_STATUS_SEVERE"),
            (True, "thermal_end=severe"),
        )
        self.assertEqual(
            ab_verdict.is_thermal_throttled("severe", "light"),
            (True, "thermal_start=severe"),
        )
        self.assertEqual(
            ab_verdict.is_thermal_throttled("THERMAL_STATUS_CRITICAL", "light"),
            (True, "thermal_start=critical"),
        )
        throttled_jump, jump_reason = ab_verdict.is_thermal_throttled(
            "THERMAL_STATUS_NONE", "THERMAL_STATUS_MODERATE"
        )
        self.assertTrue(throttled_jump)
        self.assertIn("none->moderate", jump_reason or "")

        # classify_thermal_status tri-state checks.
        self.assertEqual(classify_thermal_status("none", "light"), ("ok", None))
        self.assertEqual(classify_thermal_status("nominal", "fair"), ("ok", None))
        self.assertEqual(classify_thermal_status(None, "THERMAL_STATUS_SEVERE"), ("throttled", "thermal_end=severe"))
        self.assertEqual(classify_thermal_status("severe", None), ("throttled", "thermal_start=severe"))
        st_missing, r_missing = classify_thermal_status(None, "light")
        self.assertEqual(st_missing, "unverified")
        self.assertIn("thermal_start=missing", r_missing or "")
        st_unknown, r_unknown = classify_thermal_status("none", "toasty")
        self.assertEqual(st_unknown, "unverified")
        self.assertIn("thermal_end=unknown", r_unknown or "")

        # evaluate_ab exposes thermal_unverified_count + warning in reasons.
        rows = self._build_pairs(
            [680, 684, 679, 682, 685, 681],
            [560, 564, 559, 562, 565, 561],
            thermal_start=None,
            thermal_end=None,
        )
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "improved")
        self.assertEqual(res["thermal_unverified_count"], 12)
        self.assertEqual(len(res["thermal_unverified_runs"]), 12)
        self.assertTrue(any("unverified thermal" in r.lower() for r in res["reasons"]))

    def test_thermal_throttling_filtered_and_flagged(self) -> None:
        rows = self._build_pairs(
            [680, 684, 679, 682, 685, 681],
            [560, 564, 559, 562, 565, 561],
        )
        rows.append(_make_ab_row(arm="base", iteration=7, duration_ms=680, thermal_start="none", thermal_end="light"))
        rows.append(_make_ab_row(arm="head", iteration=7, duration_ms=1400, thermal_start="light", thermal_end="severe"))
        rows.append(_make_ab_row(arm="base", iteration=8, duration_ms=680, thermal_start="none", thermal_end="moderate"))
        rows.append(_make_ab_row(arm="head", iteration=8, duration_ms=560, thermal_start="none", thermal_end="light"))
        rows.append(_make_ab_row(arm="base", iteration=9, duration_ms=680, thermal_start="severe", thermal_end="light"))
        rows.append(_make_ab_row(arm="head", iteration=9, duration_ms=560, thermal_start="none", thermal_end="light"))

        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "improved")
        self.assertEqual(res["paired_count"], 6)
        self.assertEqual(res["thermal_excluded_count"], 3)
        self.assertEqual(len(res["thermal_flags"]), 3)
        self.assertTrue(any("thermally throttled" in r for r in res["reasons"]))

    def test_insufficient_samples_after_thermal_and_invalid_filter(self) -> None:
        rows = self._build_pairs([680, 684, 679], [560, 564, 559])
        rows.append(_make_ab_row(arm="base", iteration=4, duration_ms=680, samples=0, valid=False))
        rows.append(_make_ab_row(arm="head", iteration=4, duration_ms=560))
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "insufficient_samples")
        self.assertEqual(res["paired_count"], 3)
        self.assertEqual(res["invalid_excluded_count"], 1)

    def test_calibration_metadata_gate_duplicates_and_modes(self) -> None:
        # 1. Passing ordinary baseline/ab datasets MUST fail with invalid_cohort.
        ordinary_base = [_make_ab_row(arm="base", iteration=i, duration_ms=680.0, run_kind="baseline") for i in range(1, 7)]
        ordinary_ab = [_make_ab_row(arm="head", iteration=i, duration_ms=880.0, run_kind="ab") for i in range(1, 7)]
        res_bad_kind = ab_verdict.evaluate_calibration(ordinary_base, ordinary_ab, injected_ms=200.0)
        self.assertFalse(res_bad_kind["passed"])
        self.assertEqual(res_bad_kind["verdict"], "invalid_cohort")

        # 2. Valid latency_ms calibration (thread_open -> duration_ms) pass & fail.
        base_rows = [
            _make_ab_row(arm="base", iteration=i, duration_ms=v, run_kind="calibration")
            for i, v in enumerate([680, 684, 679, 682, 685, 681], start=1)
        ]
        good_injected = [
            _make_ab_row(arm="head", iteration=i, duration_ms=v, run_kind="calibration", calibration_injected_ms=200.0)
            for i, v in enumerate([878, 886, 881, 880, 884, 882], start=1)
        ]
        cal_ok = ab_verdict.evaluate_calibration(base_rows, good_injected, injected_ms=200.0)
        self.assertTrue(cal_ok["passed"])
        self.assertEqual(cal_ok["verdict"], "pass")
        self.assertEqual(cal_ok["calibration_mode"], "latency_ms")
        self.assertEqual(cal_ok["primary_metric"], "duration_ms")
        self.assertEqual(cal_ok["p_value_method"], "exact_paired_sign_flip")
        self.assertGreaterEqual(cal_ok["observed_delta_ms"], 150.0)
        self.assertLessEqual(cal_ok["observed_delta_ms"], 250.0)

        # Mismatched calibration_injected_ms in latency_ms mode -> invalid_cohort.
        wrong_inj_meta = copy.deepcopy(good_injected)
        wrong_inj_meta[0]["calibration_injected_ms"] = 100.0
        res_mismatch = ab_verdict.evaluate_calibration(base_rows, wrong_inj_meta, injected_ms=200.0)
        self.assertFalse(res_mismatch["passed"])
        self.assertEqual(res_mismatch["verdict"], "invalid_cohort")

        # Non-zero calibration_injected_ms in base_rows -> invalid_cohort.
        bad_base_meta = copy.deepcopy(base_rows)
        bad_base_meta[0]["calibration_injected_ms"] = 50.0
        res_bad_base = ab_verdict.evaluate_calibration(bad_base_meta, good_injected, injected_ms=200.0)
        self.assertFalse(res_bad_base["passed"])
        self.assertEqual(res_bad_base["verdict"], "invalid_cohort")

        # Duplicate iteration inside base_rows or injected_rows -> invalid_cohort.
        dup_base = base_rows + [_make_ab_row(arm="base", iteration=1, duration_ms=680, run_kind="calibration")]
        res_dup_cal = ab_verdict.evaluate_calibration(dup_base, good_injected, injected_ms=200.0)
        self.assertFalse(res_dup_cal["passed"])
        self.assertEqual(res_dup_cal["verdict"], "invalid_cohort")
        self.assertTrue(any("duplicate iteration in base_rows" in r for r in res_dup_cal["reasons"]))

        dup_inj = good_injected + [
            _make_ab_row(arm="head", iteration=2, duration_ms=880, run_kind="calibration", calibration_injected_ms=200.0)
        ]
        res_dup_inj = ab_verdict.evaluate_calibration(base_rows, dup_inj, injected_ms=200.0)
        self.assertFalse(res_dup_inj["passed"])
        self.assertEqual(res_dup_inj["verdict"], "invalid_cohort")
        self.assertTrue(any("duplicate iteration in injected_rows" in r for r in res_dup_inj["reasons"]))

        # cold_start auto-selects latency_ms mode and ttfd_ms metric when primary_metric is omitted.
        cs_base = [
            _make_ab_row(scenario="cold_start", iteration=i, duration_ms=None, ttfd_ms=1100.0 + i, run_kind="calibration")
            for i in range(1, 7)
        ]
        cs_inj = [
            _make_ab_row(
                scenario="cold_start",
                iteration=i,
                duration_ms=None,
                ttfd_ms=1300.0 + i,
                run_kind="calibration",
                calibration_injected_ms=200.0,
            )
            for i in range(1, 7)
        ]
        cal_cs = ab_verdict.evaluate_calibration(cs_base, cs_inj, injected_ms=200.0)
        self.assertTrue(cal_cs["passed"])
        self.assertEqual(cal_cs["calibration_mode"], "latency_ms")
        self.assertEqual(cal_cs["primary_metric"], "ttfd_ms")

        # Under-detected stall (+95ms vs 200ms) -> fail.
        bad_injected = [
            _make_ab_row(arm="head", iteration=i, duration_ms=v, run_kind="calibration", calibration_injected_ms=200.0)
            for i, v in enumerate([775, 779, 774, 777, 780, 776], start=1)
        ]
        cal_fail = ab_verdict.evaluate_calibration(base_rows, bad_injected, injected_ms=200.0)
        self.assertFalse(cal_fail["passed"])
        self.assertEqual(cal_fail["verdict"], "fail")

        # Unverified thermal telemetry in calibration is explicitly reported.
        unver_base = [
            _make_ab_row(arm="base", iteration=i, duration_ms=680 + i, run_kind="calibration", thermal_start=None, thermal_end=None)
            for i in range(1, 7)
        ]
        unver_inj = [
            _make_ab_row(arm="head", iteration=i, duration_ms=880 + i, run_kind="calibration", calibration_injected_ms=200.0, thermal_start=None, thermal_end=None)
            for i in range(1, 7)
        ]
        cal_unver = ab_verdict.evaluate_calibration(unver_base, unver_inj, injected_ms=200.0)
        self.assertTrue(cal_unver["passed"])
        self.assertEqual(cal_unver["thermal_unverified_count"], 12)
        self.assertTrue(any("unverified thermal" in r.lower() for r in cal_unver["reasons"]))

        # 3. slow_frames mode on thread_scroll_ime Android (slow_frame_ratio).
        and_scroll_base = [
            _make_ab_row(
                scenario="thread_scroll_ime",
                iteration=i,
                samples=400,
                slow_frame_ratio=0.02 + i * 0.0005,
                run_kind="calibration",
            )
            for i in range(1, 7)
        ]
        and_scroll_inj = [
            _make_ab_row(
                scenario="thread_scroll_ime",
                iteration=i,
                samples=400,
                slow_frame_ratio=0.07 + i * 0.0005,
                run_kind="calibration",
                calibration_injected_ms=40.0,
            )
            for i in range(1, 7)
        ]
        cal_and_sf = ab_verdict.evaluate_calibration(
            and_scroll_base, and_scroll_inj, injected_slow_frames=20
        )
        self.assertTrue(cal_and_sf["passed"])
        self.assertEqual(cal_and_sf["calibration_mode"], "slow_frames")
        self.assertEqual(cal_and_sf["primary_metric"], "slow_frame_ratio")
        self.assertAlmostEqual(cal_and_sf["expected_delta"], 0.05, places=4)
        self.assertAlmostEqual(cal_and_sf["observed_delta"], 0.05, places=4)

        # 4. slow_frames mode on thread_scroll_ime iOS (hitch_ms_per_s via injected_slow_frames & via expected_delta).
        ios_scroll_base = [
            _make_ab_row(
                project="raft-ios",
                scenario="thread_scroll_ime",
                iteration=i,
                samples=300,
                thermal_start="nominal",
                thermal_end="fair",
                hitch_ms_per_s=2.0 + i * 0.1,
                run_kind="calibration",
            )
            for i in range(1, 7)
        ]
        ios_scroll_inj = [
            _make_ab_row(
                project="raft-ios",
                scenario="thread_scroll_ime",
                iteration=i,
                samples=300,
                thermal_start="nominal",
                thermal_end="fair",
                hitch_ms_per_s=42.0 + i * 0.1,
                run_kind="calibration",
                calibration_injected_ms=40.0,
            )
            for i in range(1, 7)
        ]
        # 5 frames * 40ms / 5.0s = 40.0 ms/s expected delta.
        cal_ios_sf = ab_verdict.evaluate_calibration(
            ios_scroll_base,
            ios_scroll_inj,
            injected_ms=40.0,
            injected_slow_frames=5,
            window_ms=5000.0,
        )
        self.assertTrue(cal_ios_sf["passed"])
        self.assertEqual(cal_ios_sf["calibration_mode"], "slow_frames")
        self.assertEqual(cal_ios_sf["primary_metric"], "hitch_ms_per_s")
        self.assertAlmostEqual(cal_ios_sf["expected_delta"], 40.0, places=3)
        self.assertAlmostEqual(cal_ios_sf["observed_delta"], 40.0, places=3)

        # Explicit expected_delta override works too.
        cal_ios_exp = ab_verdict.evaluate_calibration(
            ios_scroll_base, ios_scroll_inj, expected_delta=40.0
        )
        self.assertTrue(cal_ios_exp["passed"])

        # 5. Paired median delta is used in evaluate_calibration when paired.
        cal_base_asym = [
            _make_ab_row(iteration=i, duration_ms=v, run_kind="calibration")
            for i, v in enumerate([500.0, 500.0, 600.0, 600.0, 700.0, 700.0], start=1)
        ]
        # deltas = [200, 200, 200, 200, 200, 200] -> paired_median_delta = 200.0; make 2 outliers in head:
        # Actually make base=[500, 500, 600, 600, 700, 700] (med=600)
        # head=[700, 700, 800, 800, 720, 720] -> sorted head=[700,700,720,720,800,800] (med=720 -> median_delta=120)
        # deltas = [200, 200, 200, 200, 20, 20] -> paired_median_delta = 200.0 -> PASSES because paired median is 200ms!
        cal_inj_asym = [
            _make_ab_row(iteration=i, duration_ms=v, run_kind="calibration", calibration_injected_ms=200.0)
            for i, v in enumerate([700.0, 700.0, 800.0, 800.0, 720.0, 720.0], start=1)
        ]
        cal_asym = ab_verdict.evaluate_calibration(cal_base_asym, cal_inj_asym, injected_ms=200.0)
        self.assertEqual(cal_asym["median_delta"], 120.0)
        self.assertEqual(cal_asym["paired_median_delta"], 200.0)
        self.assertEqual(cal_asym["observed_delta_ms"], 200.0)
        self.assertTrue(cal_asym["passed"])


class TestCliEndToEnd(unittest.TestCase):
    def _run_cli(self, argv: list[str]) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cli.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_cli_ab_verdict_exit_codes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)

            def _eval_cli(rows: list[dict], name: str) -> tuple[int, dict]:
                p = tmp_dir / f"{name}.jsonl"
                write_jsonl(p, rows, kind="run", validate=False)
                code, stdout, _ = self._run_cli(["ab-verdict", str(p)])
                return code, json.loads(stdout)

            def _pairs(b_vals: list[float], h_vals: list[float]) -> list[dict]:
                out = []
                for idx, (b, h) in enumerate(zip(b_vals, h_vals), start=1):
                    out.append(_make_ab_row(arm="base", iteration=idx, duration_ms=b))
                    out.append(_make_ab_row(arm="head", iteration=idx, duration_ms=h))
                return out

            # improved -> 0
            c_imp, r_imp = _eval_cli(_pairs([680, 684, 678, 682, 685, 681], [570, 574, 568, 572, 575, 571]), "imp")
            self.assertEqual(c_imp, 0)
            self.assertEqual(r_imp["verdict"], "improved")

            # neutral -> 0
            c_neu, r_neu = _eval_cli(_pairs([680, 684, 678, 682, 685, 681], [683, 681, 682, 679, 686, 684]), "neu")
            self.assertEqual(c_neu, 0)
            self.assertEqual(r_neu["verdict"], "neutral")

            # regressed -> 1
            c_reg, r_reg = _eval_cli(_pairs([610, 615, 608, 612, 614, 611], [740, 745, 738, 742, 744, 741]), "reg")
            self.assertEqual(c_reg, 1)
            self.assertEqual(r_reg["verdict"], "regressed")

            # insufficient_samples -> 2
            c_ins, r_ins = _eval_cli(_pairs([680, 684, 679], [560, 564, 559]), "ins")
            self.assertEqual(c_ins, 2)
            self.assertEqual(r_ins["verdict"], "insufficient_samples")

            # noisy -> 2
            c_noi, r_noi = _eval_cli(_pairs([420, 910, 510, 960, 480, 880], [450, 940, 490, 990, 460, 920]), "noi")
            self.assertEqual(c_noi, 2)
            self.assertEqual(r_noi["verdict"], "noisy")

            # invalid_cohort -> 2
            bad_rows = [
                _make_ab_row(arm="base", iteration=1, duration_ms=680, device_id="dev-A"),
                _make_ab_row(arm="head", iteration=1, duration_ms=520, device_id="dev-B"),
            ]
            c_inv, r_inv = _eval_cli(bad_rows, "inv")
            self.assertEqual(c_inv, 2)
            self.assertEqual(r_inv["verdict"], "invalid_cohort")

    def test_cli_scenarios_validate_parse_append_and_calibrate(self) -> None:
        code, stdout, _ = self._run_cli(["scenarios", "--json"])
        self.assertEqual(code, 0)
        parsed = json.loads(stdout)
        self.assertEqual(len(parsed), 7)

        code, cfg_out, _ = self._run_cli(["parse-android", "--emit-perfetto-config"])
        self.assertEqual(code, 0)
        self.assertIn("android.surfaceflinger.frametimeline", cfg_out)

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            run_file = tmp_dir / "sample.jsonl"
            diag_file = tmp_dir / "sample_diag.jsonl"
            run_file.write_text(SAMPLE_MOBILE_RUN_JSON + "\n", encoding="utf-8")
            diag_file.write_text(SAMPLE_DIAGNOSE_RUN_JSON + "\n", encoding="utf-8")

            c1, _, _ = self._run_cli(["validate", "--kind", "run", str(run_file)])
            c2, _, _ = self._run_cli(["validate", "--kind", "diagnose", str(diag_file)])
            self.assertEqual(c1, 0)
            self.assertEqual(c2, 0)

            # Calibrate CLI with combined input (latency_ms) and slow_frames mode.
            cal_rows = [
                _make_ab_row(arm="base", iteration=i, duration_ms=680 + i, run_kind="calibration")
                for i in range(1, 7)
            ] + [
                _make_ab_row(
                    arm="head",
                    iteration=i,
                    duration_ms=880 + i,
                    run_kind="calibration",
                    calibration_injected_ms=200.0,
                )
                for i in range(1, 7)
            ]
            cal_file = tmp_dir / "cal.jsonl"
            write_jsonl(cal_file, cal_rows, kind="run")
            c4, cal_out, _ = self._run_cli(["calibrate", "--input", str(cal_file)])
            self.assertEqual(c4, 0)
            self.assertTrue(json.loads(cal_out)["passed"])

            sf_rows = [
                _make_ab_row(
                    scenario="thread_scroll_ime",
                    iteration=i,
                    samples=400,
                    slow_frame_ratio=0.02 + i * 0.0002,
                    run_kind="calibration",
                )
                for i in range(1, 7)
            ] + [
                _make_ab_row(
                    scenario="thread_scroll_ime",
                    iteration=i,
                    samples=400,
                    slow_frame_ratio=0.07 + i * 0.0002,
                    run_kind="calibration",
                    calibration_injected_ms=40.0,
                )
                for i in range(1, 7)
            ]
            sf_file = tmp_dir / "cal_sf.jsonl"
            write_jsonl(sf_file, sf_rows, kind="run")
            c_sf, sf_out, _ = self._run_cli(
                ["calibrate", "--input", str(sf_file), "--mode", "slow_frames", "--injected-slow-frames", "20"]
            )
            self.assertEqual(c_sf, 0)
            sf_res = json.loads(sf_out)
            self.assertTrue(sf_res["passed"])
            self.assertEqual(sf_res["primary_metric"], "slow_frame_ratio")

            # parse-android missing --refresh-hz and --display -> exit 2 with stderr message.
            gfx_file = tmp_dir / "gfx.txt"
            gfx_file.write_text(
                "Total frames rendered: 50\nJanky frames: 2 (4.00%)\n"
                "50th percentile: 8ms\n95th percentile: 16ms\n99th percentile: 24ms\n",
                encoding="utf-8",
            )
            c_nohz_and, _, err_and = self._run_cli(
                [
                    "parse-android",
                    "--run-id", "cli-and-nohz",
                    "--commit-sha", "0123456789abcdef0123456789abcdef01234567",
                    "--app-version", "1.31.0",
                    "--app-build", "13100019",
                    "--scenario", "thread_open",
                    "--device-id", "3f9a1c22",
                    "--device-model", "Redmi Note 12",
                    "--os-version", "android-14",
                    "--gfxinfo", str(gfx_file),
                ]
            )
            self.assertEqual(c_nohz_and, 2)
            self.assertIn("refresh_hz", err_and)

            # parse-android with --refresh-hz, --perfetto-thread-cpu, --perfetto-trace-bounds, and --append.
            cpu_file = tmp_dir / "cpu.csv"
            cpu_file.write_text("thread_role,cpu_ms\nmain,510.5\nRenderThread,180.2\n", encoding="utf-8")
            bounds_file = tmp_dir / "bounds.csv"
            bounds_file.write_text("window_ms\n5000.0\n", encoding="utf-8")
            and_run_out = tmp_dir / "and_run.jsonl"
            and_diag_out = tmp_dir / "and_diag.jsonl"
            for rid in ("cli-and-01", "cli-and-02"):
                c5, _, _ = self._run_cli(
                    [
                        "parse-android",
                        "--run-id", rid,
                        "--commit-sha", "0123456789abcdef0123456789abcdef01234567",
                        "--app-version", "1.31.0",
                        "--app-build", "13100019",
                        "--scenario", "thread_open",
                        "--device-id", "3f9a1c22",
                        "--device-model", "Redmi Note 12",
                        "--os-version", "android-14",
                        "--refresh-hz", "90",
                        "--duration-ms", "650",
                        "--gfxinfo", str(gfx_file),
                        "--perfetto-thread-cpu", str(cpu_file),
                        "--perfetto-trace-bounds", str(bounds_file),
                        "--out-run", str(and_run_out),
                        "--out-diagnose", str(and_diag_out),
                        "--append",
                    ]
                )
                self.assertEqual(c5, 0)
            self.assertEqual(validate_jsonl_file(and_run_out, "run"), [])
            self.assertEqual(validate_jsonl_file(and_diag_out, "diagnose"), [])
            self.assertEqual(len(read_jsonl(and_run_out)), 2)
            diag_loaded = read_jsonl(and_diag_out)[0]
            self.assertAlmostEqual(diag_loaded["ui_thread_ms"], 510.5, places=1)
            self.assertAlmostEqual(diag_loaded["render_thread_ms"], 180.2, places=1)
            self.assertAlmostEqual(diag_loaded["window_ms"], 5000.0, places=1)

            # parse-ios missing --refresh-hz -> exit 2 with stderr message.
            hitch_xml_file = tmp_dir / "hitches.xml"
            hitch_xml_file.write_text(
                "<trace-query-result><node/></trace-query-result>",
                encoding="utf-8",
            )
            c_nohz_ios, _, err_ios = self._run_cli(
                [
                    "parse-ios",
                    "--run-id", "cli-ios-nohz",
                    "--commit-sha", "0123456789abcdef0123456789abcdef01234567",
                    "--app-version", "1.31.0",
                    "--app-build", "13100006",
                    "--scenario", "thread_open",
                    "--device-id", "9b20e4d1",
                    "--device-model", "iPhone 11",
                    "--os-version", "ios-17.6",
                    "--hitches-xml", str(hitch_xml_file),
                ]
            )
            self.assertEqual(c_nohz_ios, 2)
            self.assertIn("refresh-hz", err_ios)

            # parse-ios with --refresh-hz, --frame-count, --process-name, --window-ms, --append.
            ios_run_out = tmp_dir / "ios_run.jsonl"
            ios_diag_out = tmp_dir / "ios_diag.jsonl"
            for rid in ("cli-ios-01", "cli-ios-02"):
                c6, _, _ = self._run_cli(
                    [
                        "parse-ios",
                        "--run-id", rid,
                        "--commit-sha", "0123456789abcdef0123456789abcdef01234567",
                        "--app-version", "1.31.0",
                        "--app-build", "13100006",
                        "--scenario", "thread_scroll_ime",
                        "--device-id", "9b20e4d1",
                        "--device-model", "iPhone 11",
                        "--os-version", "ios-17.6",
                        "--refresh-hz", "60",
                        "--window-ms", "5000",
                        "--frame-count", "300",
                        "--process-name", "Raft",
                        "--hitches-xml", str(hitch_xml_file),
                        "--out-run", str(ios_run_out),
                        "--out-diagnose", str(ios_diag_out),
                        "--append",
                    ]
                )
                self.assertEqual(c6, 0)
            self.assertEqual(validate_jsonl_file(ios_run_out, "run"), [])
            self.assertEqual(validate_jsonl_file(ios_diag_out, "diagnose"), [])
            ios_runs = read_jsonl(ios_run_out)
            self.assertEqual(len(ios_runs), 2)
            self.assertEqual(ios_runs[0]["hitch_ms_per_s"], 0.0)
            self.assertTrue(ios_runs[0]["valid"])


if __name__ == "__main__":
    unittest.main()
