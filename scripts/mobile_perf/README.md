# `mobile_perf` — Closed-Loop Mobile Performance Harness

Pure Python 3.10+ standard-library package for Raft Android and iOS closed-loop
performance benchmarking, trace diagnostics, ScopeDB ingestion validation,
cohort-guarded paired A/B verdicts, and per-scenario sensitivity calibration.

Zero third-party Python dependencies. Existing `scripts/perf/` helpers remain
untouched.

## ScopeDB Schema Contract

Two authoritative tables are enforced by `scripts/mobile_perf/schema.py`:

| Table | Columns | Purpose |
| --- | --- | --- |
| `scopedb.perf.mobile_runs` | 38 | Longitudinal baseline, paired A/B, and calibration run metrics |
| `scopedb.perf.mobile_diagnose_runs` | 40 | Deep trace breakdown (`ui_thread_ms`, `render_thread_ms`, `metal_ms`, `top_hotspots`, `trace_sha256`, `trace_link`) |

### Cohort Key Invariant

Every row carries a deterministic `cohort_key`:

```text
dev:{device_id}|os:{os_version}|build:{build_type}|fx:{fixture_tag}|layout:{layout_mode}|hz:{refresh_hz}|warm:{warm_state}
```

- `refresh_hz` is mandatory (supplied explicitly via `--refresh-hz` or parsed from Android `dumpsys display`; no silent 60Hz fallback) and formatted as an integer when whole (`90`, `60`, `120`) or trimmed decimal (`59.94`).
- `cohort_key` **never** includes `app_version`, `app_build`, `commit_sha`, `thermal_start`, or `thermal_end`.

### Data Hygiene Rules

1. **Absent metrics are `null` (`None`), never `0`.** Positive timing/memory metrics reject `0`; Android keeps `metal_ms: null` and iOS keeps `render_thread_ms: null`.
2. **Release/profileable builds only.** Any `build_type` containing `debug` (case-insensitive) is rejected by schema validation.
3. **Cross-field `run_kind` and `calibration_injected_ms`.** Non-calibration rows (`baseline`, `ab`, `diagnose`) reject `calibration_injected_ms > 0`.
4. **Canonical per-project thermal enums.** Non-null `thermal_start`/`thermal_end` must match `raft-android` (`{none, light, moderate, severe, critical, emergency, shutdown}`) or `raft-ios` (`{nominal, fair, serious, critical}`).
5. **Zero-sample and thermally throttled rows stay in the table with `valid: false`.** `samples == 0` forces `valid = false` with `null` frame metrics; capture-time thermal throttling (either `thermal_start` or `thermal_end` in `{severe, critical, emergency, shutdown, serious}`, after stripping any optional `THERMAL_STATUS_` prefix, or thermal tier jump `>= 2`) marks `valid = false` and records `extra.thermal_invalid_reason`.
6. **Explicit units on `segments` & capture provenance.** Every numeric key in `segments` must end with `_ms`, `_mb`, `_count`, `_dp`, or `_ratio`. Parsers always populate `extra.capture_mode` (`perfetto`, `dumpsys_fallback`, `perfetto+dumpsys`, or `xctrace`).
7. **Canonical `top_hotspots` shape.** Each entry in `mobile_diagnose_runs.top_hotspots` has exact keys `{symbol, thread, self_ms, total_ms}` with `total_ms >= self_ms`.
8. **UTC timestamps.** `taken_at` must be ISO-8601 UTC ending in `Z`.

## First-Batch Scenarios (`mobile-bench-v1`)

All 7 canonical scenarios target the fixed read-only staging fixture `mobile-bench-v1`:

| Scenario | Online Event(s) | Primary KPI (Android / iOS) | Calibration Mode (Android / iOS metric) | Required `segments` |
| --- | --- | --- | --- | --- |
| `cold_start` | `mobile.cold_start` / `startup` | `ttfd_ms` / `ttfd_ms` | `latency_ms` (`ttfd_ms` / `ttfd_ms`) | `process_init_ms`, `compose_first_frame_ms`, `auth_bootstrap_ms`, `first_feed_render_ms` |
| `channel_switch` | `mobile.frames.nav_transition` | `duration_ms` / `duration_ms` | `latency_ms` (`duration_ms` / `duration_ms`) | `input_delay_ms`, `network_ms`, `recompose_layout_ms`, `render_gpu_ms`, `frame_count` |
| `thread_scroll_ime` | `mobile.frames.scroll` | `frame_p95_ms` / `hitch_ms_per_s` | `slow_frames` (`slow_frame_ratio` / `hitch_ms_per_s`) | `ui_thread_ms`, `recompose_ms`, `render_thread_or_metal_ms`, `ime_settle_ms`, `frame_count` |
| `composer_first_char` | `first_char_latency` | `duration_ms` / `duration_ms` | `latency_ms` (`duration_ms` / `duration_ms`) | `first_char_p50_ms`, `first_char_p95_ms`, `key_to_state_ms`, `text_layout_ms`, `recompose_ms`, `draw_ms`, `frame_count` |
| `webview_open` | `mobile.webview.load` | `duration_ms` / `duration_ms` | `latency_ms` (`duration_ms` / `duration_ms`) | `container_init_ms`, `prewarm_hit_count`, `network_ms`, `first_paint_ms` |
| `thread_open` | `mobile.thread_open` | `duration_ms` / `duration_ms` | `latency_ms` (`duration_ms` / `duration_ms`) | `cache_rows_shown_ms`, `network_applied_ms`, `reading_anchor_attempt_count`, `visible_shift_dp`, `shift_count`, `frame_count` |
| `foreground_resume` | `mobile.frames.resume` + `activity:foreground_sync_updated` | `duration_ms` / `duration_ms` | `latency_ms` (`duration_ms` / `duration_ms`) | `resume_first_frame_ms`, `mutex_wait_ms`, `jitter_wait_ms`, `network_fetch_ms`, `rss_mb`, `pss_mb`, `frame_count` |

## Capture Parser Notes

- **Android Perfetto (`android_perfetto.py`):** Hotspot SQL uses `INCLUDE PERFETTO MODULE slices.self_dur;` joined against `slice_self_dur`. Full-window `ui_thread_ms` and `render_thread_ms` come exclusively from `sched_slice` (`--perfetto-thread-cpu`), and trace `window_ms` from `trace_bounds` (`--perfetto-trace-bounds`) — never summed from `LIMIT 25` hotspots.
- **Android `dumpsys gfxinfo` & logcat:** Both the summary block and `---PROFILEDATA---` are parsed; when the 120-frame ring buffer is truncated (`Total frames rendered > len(PROFILEDATA)`), full-window summary metrics are used with `worst_frame_ms = null` and `extra.gfxinfo_profiledata_truncated = true`. Summary-only fallback always leaves `worst_frame_ms = null`. Non-zero `Flags` are retained by default (filtered by timestamp validity); pass `--exclude-window-layout-frames` to opt into dropping `Flags & 1`. `Displayed <package>/...` populates `segments.ttid_ms` and `segments.compose_first_frame_ms` (never `ttfd_ms`); `Fully drawn <package>/...` (or explicit `--ttfd-ms` / `SlockPerf` `ttfd_ms=` / `first_feed_render_ms=`) populates `ttfd_ms`.
- **iOS `xctrace` (`ios_xctrace.py`):** `--refresh-hz` is mandatory and there is no silent `5000ms` window default (`window_ms` is inferred from XML timestamps when available or taken from `--window-ms`; if unavailable, `window_ms` and `hitch_ms_per_s` stay `null`). `samples` counts frame samples strictly (from `<frame-duration>`, `--frame-count`, or app-log `frame_count`), while Time Profiler sample counts go to `extra.time_profile_sample_count`. Smooth 0-hitch runs with `samples > 0` emit `slow_frame_count = 0`, `hitch_ms_per_s = 0.0` (when `window_ms` is known), and `valid = true`. Use `--process-name` to exclude rows from unrelated processes.

## CLI Usage

```bash
# List registered scenarios
python3 -m scripts.mobile_perf scenarios

# Validate ScopeDB JSONL files
python3 -m scripts.mobile_perf validate --kind run runs.jsonl
python3 -m scripts.mobile_perf validate --kind diagnose diagnose.jsonl

# Emit a Perfetto protobuf-text config for build.raft.app
python3 -m scripts.mobile_perf parse-android --emit-perfetto-config --config-duration-ms 10000 > mobile_perf.pbtx

# Parse Android dumpsys + logcat (or Perfetto SQL outputs) into validated JSONL (--append for multi-run files)
python3 -m scripts.mobile_perf parse-android \
  --run-id run-001 --commit-sha 0123456789abcdef0123456789abcdef01234567 \
  --app-version 1.31.0 --app-build 13100019 --scenario thread_open \
  --device-id 3f9a1c22 --device-model "Redmi Note 12" --os-version android-14 \
  --gfxinfo gfxinfo.txt --meminfo meminfo.txt --display display.txt \
  --thermal-start-file thermal_start.txt --thermal-end-file thermal_end.txt \
  --logcat logcat.txt --out-run runs.jsonl --out-diagnose diagnose.jsonl --append

# Parse iOS xctrace XML exports + app logs into validated JSONL
python3 -m scripts.mobile_perf parse-ios \
  --run-id ios-001 --commit-sha 0123456789abcdef0123456789abcdef01234567 \
  --app-version 1.31.0 --app-build 13100006 --scenario thread_open \
  --device-id 9b20e4d1 --device-model "iPhone 11" --os-version ios-17.6 \
  --refresh-hz 60 --process-name Raft \
  --hitches-xml hitches.xml --time-profile-xml time_profile.xml \
  --thermal-xml thermal.xml --metal-xml metal.xml --app-log diag.log \
  --out-run ios_runs.jsonl --out-diagnose ios_diagnose.jsonl --append

# Evaluate paired interleaved A/B runs (exit 0=improved/neutral, 1=regressed, 2=insufficient/noisy/invalid_cohort)
python3 -m scripts.mobile_perf ab-verdict ab_runs.jsonl --metric duration_ms

# Verify latency calibration (default 200ms synthetic stall)
python3 -m scripts.mobile_perf calibrate --base cal_base.jsonl --injected cal_200ms.jsonl --injected-ms 200

# Verify slow-frame calibration for thread_scroll_ime
python3 -m scripts.mobile_perf calibrate --base scroll_base.jsonl --injected scroll_inj.jsonl \
  --mode slow_frames --injected-slow-frames 20
```

## Paired A/B Verdict & Calibration Rules

- **Strict A/B cohort & structural gate:** Any mismatch in `cohort_key`, `scenario`, or `ab_session_id` (missing or multiple sessions), any non-`ab` `run_kind`, any `calibration_injected_ms > 0`, any unrecognized `ab_arm` (reported in `unrecognized_arm_count`/`unrecognized_arms`), or any duplicate `(ab_arm, iteration)` returns `verdict: "invalid_cohort"`.
- **Thermal guard & unverified telemetry:** Runs throttled at start or end (`severe`, `critical`, `emergency`, `shutdown`, `serious`, with optional `THERMAL_STATUS_` prefix stripped) or with thermal tier jump `>= 2` are flagged and excluded by default. Runs with missing or unrecognized `thermal_start`/`thermal_end` are explicitly counted in `thermal_unverified_count`/`thermal_unverified_runs` with a warning in `reasons`.
- **Noise-floor verdict rule:** Requires at least `min_pairs` (default `6`) valid paired iterations. Always uses the deterministic exact paired sign-flip test (`_exact_paired_sign_flip_p_value`, never discarding pairing) and `paired_median_delta` as the decision effect size (`abs_delta`, `relative_delta_pct`, and `improved`/`regressed` direction), while also reporting `median_delta = head_median - base_median`.
- **CLI exit codes (`ab-verdict`):** `0` for `improved` or `neutral`; `1` for `regressed`; `2` for `insufficient_samples`, `noisy`, or `invalid_cohort`.
- **Per-scenario calibration:** Enforces a strict metadata gate (`run_kind == "calibration"`, `base_rows` with `calibration_injected_ms` in `{null, 0}`, `injected_rows` with `calibration_injected_ms > 0`, matching `injected_ms` in `latency_ms` mode, and unique `iteration` per group) before running statistics. Supports both `latency_ms` (6 latency scenarios) and `slow_frames` (`thread_scroll_ime` on `slow_frame_ratio` or `hitch_ms_per_s`), using paired median delta and exact paired sign-flip p-value when paired (`min_pairs = 6`).

## Running Tests

```bash
python3 -m unittest discover -s scripts/mobile_perf -v
```
