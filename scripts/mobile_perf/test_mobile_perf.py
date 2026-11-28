"""Self-contained unit tests for scripts/mobile_perf."""

from __future__ import annotations

import copy
import hashlib
import io
import json
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
    ios_xctrace,
    make_diagnose_row,
    make_run_row,
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
    arm: str,
    iteration: int,
    duration_ms: float,
    device_id: str = "3f9a1c22",
    scenario: str = "thread_open",
    thermal_start: str = "none",
    thermal_end: str = "light",
    valid: bool = True,
    samples: int = 400,
    calibration_injected_ms: float | None = None,
    run_kind: str = "ab",
) -> dict:
    return make_run_row(
        project="raft-android",
        env="staging",
        run_id=f"{arm}-{iteration}",
        taken_at="2026-11-28T04:30:00Z",
        commit_sha="0123456789abcdef0123456789abcdef01234567",
        app_version="1.31.0",
        app_build="13100019",
        build_type="release-profileable",
        scenario=scenario,
        run_kind=run_kind,
        ab_session_id="ab-20261128-test" if run_kind == "ab" else None,
        ab_arm=arm if run_kind == "ab" else None,
        iteration=iteration,
        calibration_injected_ms=calibration_injected_ms,
        device_id=device_id,
        device_model="Redmi Note 12",
        os_version="android-14",
        layout_mode="phone",
        refresh_hz=90,
        warm_state="profile_warm",
        fixture_tag="mobile-bench-v1",
        thermal_start=thermal_start,
        thermal_end=thermal_end,
        samples=samples,
        valid=valid,
        duration_ms=duration_ms,
        frame_p50_ms=8.5 if samples > 0 else None,
        frame_p95_ms=16.0 if samples > 0 else None,
        frame_p99_ms=28.0 if samples > 0 else None,
        slow_frame_ratio=0.02 if samples > 0 else None,
        mem_peak_mb=300.0,
        segments={"cache_rows_shown_ms": 200, "network_applied_ms": 420},
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

        # Properly formed zero-sample row keeps the row and passes validation.
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

        # Zero samples with slow_frame_ratio=0.0 must be rejected.
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
            self.assertEqual(spec.required_segments, expected_segments[spec.name])
            for seg in spec.required_segments + spec.optional_segments:
                self.assertTrue(seg.endswith(SEGMENT_UNIT_SUFFIXES), f"{spec.name}: {seg}")


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

    def test_android_fallback_dumpsys_and_logcat_parse(self) -> None:
        # 10 synthetic framestats rows at 90Hz (budget = 11.111ms), 2 janky frames (18ms, 34ms).
        framestats_rows = [
            "Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted",
        ]
        durs_ms = [6.0, 7.2, 8.0, 8.5, 9.0, 9.2, 9.8, 10.5, 18.4, 34.2]
        base_ns = 1_000_000_000_000
        for idx, dur in enumerate(durs_ms):
            iv = base_ns + idx * 11_111_111
            fc = iv + int(dur * 1_000_000)
            framestats_rows.append(f"0,{iv},{iv},0,0,0,0,0,0,0,0,0,0,{fc}")
        # Add one flagged frame (Flags=1) that must be ignored.
        framestats_rows.append(f"1,{base_ns},{base_ns},0,0,0,0,0,0,0,0,0,0,{base_ns + 500_000_000}")
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
        self.assertEqual(run_row["segments"]["cache_rows_shown_ms"], 212)
        self.assertEqual(run_row["segments"]["network_applied_ms"], 455)
        self.assertEqual(run_row["segments"]["reading_anchor_attempt_count"], 3)
        self.assertEqual(run_row["segments"]["visible_shift_dp"], 36.0)
        self.assertEqual(run_row["segments"]["shift_count"], 2)
        self.assertEqual(diag_row["slow_frame_count"], 2)
        self.assertIsNone(diag_row["metal_ms"])
        self.assertEqual(diag_row["trace_sha256"], hashlib.sha256(raw_trace).hexdigest())

    def test_perfetto_sql_csv_and_json_parsing(self) -> None:
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

        run_row, diag_row = android_perfetto.parse_android_capture(
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
            window_ms=5000.0,
            duration_ms=610.0,
            perfetto_frame_timeline=frame_csv,
            perfetto_main_thread=main_json,
            perfetto_render_thread=render_csv,
            perfetto_compose_recomp=recomp_csv,
            perfetto_memory=mem_csv,
            taken_at="2026-11-28T04:21:00Z",
        )

        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["samples"], 4)
        self.assertEqual(run_row["mem_peak_mb"], 298.75)
        self.assertAlmostEqual(diag_row["ui_thread_ms"], 227.5, places=1)
        self.assertAlmostEqual(diag_row["render_thread_ms"], 95.2, places=1)
        self.assertEqual(diag_row["compose_recomp_count"], 14)
        self.assertEqual(diag_row["slow_frame_count"], 1)
        self.assertEqual(diag_row["top_hotspots"][0]["symbol"], "ThreadReadingAnchorKeeper.apply")

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
            taken_at="2026-11-28T04:22:00Z",
        )
        self.assertEqual(run_row["samples"], 0)
        self.assertFalse(run_row["valid"])
        self.assertIsNone(run_row["frame_p95_ms"])
        self.assertIsNone(run_row["slow_frame_ratio"])
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])

    def test_android_gfxinfo_summary_fallback_and_cold_start_displayed(self) -> None:
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
            warm_state="cold",
            gfxinfo_text=gfx_summary,
            logcat_text=logcat,
            taken_at="2026-11-28T04:23:00Z",
        )
        self.assertEqual(validate_run_row(run_row), [])
        self.assertEqual(validate_diagnose_row(diag_row), [])
        self.assertEqual(run_row["ttfd_ms"], 1245.0)
        self.assertEqual(run_row["segments"]["process_init_ms"], 412)
        self.assertEqual(run_row["segments"]["compose_first_frame_ms"], 1245.0)
        self.assertEqual(run_row["segments"]["auth_bootstrap_ms"], 180)
        self.assertEqual(run_row["segments"]["first_feed_render_ms"], 1120)
        self.assertEqual(run_row["samples"], 120)
        self.assertAlmostEqual(run_row["slow_frame_ratio"], 0.05, places=4)

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
        self.assertIn("thermal_invalid_reason", run_row["extra"])
        self.assertIn("thermal_invalid_reason", diag_row["extra"])
        self.assertEqual(run_row["extra"]["thermal_invalid_reason"], "thermal_end=severe")

        # Tier jump >= 2 (none -> moderate) also marks valid=False at capture time.
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
            gfxinfo_text=gfx_summary,
            thermal_start="none",
            thermal_end="moderate",
            taken_at="2026-11-28T04:24:10Z",
        )
        self.assertFalse(run_jump["valid"])
        self.assertFalse(diag_jump["valid"])
        self.assertIn("thermal_tier_jump=none->moderate", run_jump["extra"]["thermal_invalid_reason"])


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
        # Uses id/ref deduplication exactly as real xcrun xctrace export emits.
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
        self.assertLess(r1["median_delta"], -100.0)

    def test_improved_at_default_min_pairs_five(self) -> None:
        rows = self._build_pairs(
            [680, 685, 682, 678, 684],
            [520, 525, 522, 518, 524],
        )
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "improved")
        self.assertEqual(res["paired_count"], 5)
        self.assertLessEqual(res["p_value"], 0.05)

    def test_regressed(self) -> None:
        rows = self._build_pairs(
            [610, 615, 608, 612, 614, 611],
            [740, 745, 738, 742, 744, 741],
        )
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "regressed")
        self.assertGreater(res["median_delta"], 100.0)

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

    def test_cross_cohort_rejected(self) -> None:
        rows = [
            _make_ab_row(arm="base", iteration=1, duration_ms=680, device_id="dev-A"),
            _make_ab_row(arm="head", iteration=1, duration_ms=520, device_id="dev-B"),
        ]
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "invalid_cohort")

    def test_thermal_throttling_filtered_and_flagged(self) -> None:
        rows = self._build_pairs(
            [680, 684, 679, 682, 685, 681],
            [560, 564, 559, 562, 565, 561],
        )
        # Add thermally throttled iteration 7 (severe end) and iteration 8 (tier jump +2: none -> moderate).
        rows.append(_make_ab_row(arm="base", iteration=7, duration_ms=680, thermal_start="none", thermal_end="light"))
        rows.append(_make_ab_row(arm="head", iteration=7, duration_ms=1400, thermal_start="light", thermal_end="severe"))
        rows.append(_make_ab_row(arm="base", iteration=8, duration_ms=680, thermal_start="none", thermal_end="moderate"))
        rows.append(_make_ab_row(arm="head", iteration=8, duration_ms=560, thermal_start="none", thermal_end="light"))

        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms")
        self.assertEqual(res["verdict"], "improved")
        self.assertEqual(res["paired_count"], 6)
        self.assertEqual(res["thermal_excluded_count"], 2)
        self.assertEqual(len(res["thermal_flags"]), 2)
        self.assertTrue(any("thermally throttled" in r for r in res["reasons"]))

    def test_insufficient_samples_after_thermal_and_invalid_filter(self) -> None:
        rows = self._build_pairs([680, 684, 679], [560, 564, 559])
        rows.append(_make_ab_row(arm="base", iteration=4, duration_ms=680, samples=0, valid=False))
        rows.append(_make_ab_row(arm="head", iteration=4, duration_ms=560))
        res = ab_verdict.evaluate_ab(rows, primary_metric="duration_ms", min_pairs=5)
        self.assertEqual(res["verdict"], "insufficient_samples")
        self.assertEqual(res["paired_count"], 3)
        self.assertEqual(res["invalid_excluded_count"], 1)

    def test_200ms_calibration_pass_and_fail(self) -> None:
        base_rows = [
            _make_ab_row(arm="base", iteration=i, duration_ms=v, run_kind="calibration")
            for i, v in enumerate([680, 684, 679, 682, 685, 681], start=1)
        ]
        good_injected = [
            _make_ab_row(
                arm="head",
                iteration=i,
                duration_ms=v,
                run_kind="calibration",
                calibration_injected_ms=200.0,
            )
            for i, v in enumerate([878, 886, 881, 880, 884, 882], start=1)
        ]
        cal_ok = ab_verdict.evaluate_calibration(base_rows, good_injected, injected_ms=200.0)
        self.assertTrue(cal_ok["passed"])
        self.assertEqual(cal_ok["verdict"], "pass")
        self.assertGreaterEqual(cal_ok["observed_delta_ms"], 150.0)
        self.assertLessEqual(cal_ok["observed_delta_ms"], 250.0)

        # Under-detected stall (+95ms vs 200ms injected) must fail calibration.
        bad_injected = [
            _make_ab_row(
                arm="head",
                iteration=i,
                duration_ms=v,
                run_kind="calibration",
                calibration_injected_ms=200.0,
            )
            for i, v in enumerate([775, 779, 774, 777, 780, 776], start=1)
        ]
        cal_fail = ab_verdict.evaluate_calibration(base_rows, bad_injected, injected_ms=200.0)
        self.assertFalse(cal_fail["passed"])
        self.assertEqual(cal_fail["verdict"], "fail")


class TestCliEndToEnd(unittest.TestCase):
    def _run_cli(self, argv: list[str]) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cli.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_cli_scenarios_validate_and_ab_and_calibrate(self) -> None:
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

            # A/B verdict CLI
            ab_rows = []
            for idx, (b, h) in enumerate(
                zip([680, 684, 678, 682, 685, 681], [570, 574, 568, 572, 575, 571]), start=1
            ):
                ab_rows.append(_make_ab_row(arm="base", iteration=idx, duration_ms=b))
                ab_rows.append(_make_ab_row(arm="head", iteration=idx, duration_ms=h))
            ab_file = tmp_dir / "ab.jsonl"
            write_jsonl(ab_file, ab_rows, kind="run")

            c3, ab_out, _ = self._run_cli(["ab-verdict", str(ab_file)])
            self.assertEqual(c3, 0)
            self.assertEqual(json.loads(ab_out)["verdict"], "improved")

            # Calibrate CLI with combined input
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

            # parse-android and parse-ios CLI writing validated JSONL outputs
            gfx_file = tmp_dir / "gfx.txt"
            gfx_file.write_text(
                "Total frames rendered: 50\nJanky frames: 2 (4.00%)\n"
                "50th percentile: 8ms\n95th percentile: 16ms\n99th percentile: 24ms\n",
                encoding="utf-8",
            )
            and_run_out = tmp_dir / "and_run.jsonl"
            and_diag_out = tmp_dir / "and_diag.jsonl"
            c5, _, _ = self._run_cli(
                [
                    "parse-android",
                    "--run-id", "cli-and-01",
                    "--commit-sha", "0123456789abcdef0123456789abcdef01234567",
                    "--app-version", "1.31.0",
                    "--app-build", "13100019",
                    "--scenario", "thread_open",
                    "--device-id", "3f9a1c22",
                    "--device-model", "Redmi Note 12",
                    "--os-version", "android-14",
                    "--duration-ms", "650",
                    "--gfxinfo", str(gfx_file),
                    "--out-run", str(and_run_out),
                    "--out-diagnose", str(and_diag_out),
                ]
            )
            self.assertEqual(c5, 0)
            self.assertEqual(validate_jsonl_file(and_run_out, "run"), [])
            self.assertEqual(validate_jsonl_file(and_diag_out, "diagnose"), [])

            hitch_xml_file = tmp_dir / "hitches.xml"
            hitch_xml_file.write_text(
                '<trace-query-result><node>'
                '<row><frame-duration fmt="12.0 ms">12000000</frame-duration></row>'
                '<row><frame-duration fmt="42.0 ms">42000000</frame-duration>'
                '<hitch-duration fmt="25.3 ms">25300000</hitch-duration></row>'
                '</node></trace-query-result>',
                encoding="utf-8",
            )
            ios_run_out = tmp_dir / "ios_run.jsonl"
            ios_diag_out = tmp_dir / "ios_diag.jsonl"
            c6, _, _ = self._run_cli(
                [
                    "parse-ios",
                    "--run-id", "cli-ios-01",
                    "--commit-sha", "0123456789abcdef0123456789abcdef01234567",
                    "--app-version", "1.31.0",
                    "--app-build", "13100006",
                    "--scenario", "thread_open",
                    "--device-id", "9b20e4d1",
                    "--device-model", "iPhone 11",
                    "--os-version", "ios-17.6",
                    "--duration-ms", "620",
                    "--hitches-xml", str(hitch_xml_file),
                    "--out-run", str(ios_run_out),
                    "--out-diagnose", str(ios_diag_out),
                ]
            )
            self.assertEqual(c6, 0)
            self.assertEqual(validate_jsonl_file(ios_run_out, "run"), [])
            self.assertEqual(validate_jsonl_file(ios_diag_out, "diagnose"), [])


if __name__ == "__main__":
    unittest.main()
