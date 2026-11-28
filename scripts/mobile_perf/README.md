# `mobile_perf` — Closed-Loop Mobile Performance Harness

Pure Python 3.10+ standard-library package for Raft Android and iOS closed-loop
performance benchmarking, trace diagnostics, ScopeDB ingestion validation,
cohort-guarded paired A/B verdicts, and 200ms sensitivity calibration.

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

- `refresh_hz` is formatted as an integer when whole (`90`, `60`, `120`) or trimmed decimal (`59.94`).
- `cohort_key` **never** includes `app_version`, `app_build`, `commit_sha`, `thermal_start`, or `thermal_end`.

### Data Hygiene Rules

1. **Absent metrics are `null` (`None`), never `0`.** Positive timing/memory metrics reject `0`; Android keeps `metal_ms: null` and iOS keeps `render_thread_ms: null`.
2. **Zero-sample rows stay in the table with `valid: false`.** `samples == 0` forces `valid = false`, and frame-derived metrics must be `null`.
3. **Explicit units on `segments`.** Every numeric key in `segments` must end with `_ms`, `_mb`, `_count`, `_dp`, or `_ratio`.
4. **Canonical `top_hotspots` shape.** Each entry in `mobile_diagnose_runs.top_hotspots` has exact keys `{symbol, thread, self_ms, total_ms}` with `total_ms >= self_ms`.
5. **UTC timestamps.** `taken_at` must be ISO-8601 UTC ending in `Z`.

## First-Batch Scenarios (`mobile-bench-v1`)

All 7 scenarios target the fixed read-only staging fixture `mobile-bench-v1`:

| Scenario | Primary KPI (Android / iOS) | Required `segments` |
| --- | --- | --- |
| `cold_start_home` | `ttfd_ms` / `ttfd_ms` | `app_init_ms`, `first_frame_ms` |
| `thread_open` | `duration_ms` / `duration_ms` | `cache_rows_shown_ms`, `network_applied_ms`, `reading_anchor_attempt_count`, `visible_shift_dp`, `shift_count` |
| `thread_scroll_media` | `frame_p95_ms` / `hitch_ms_per_s` | `frame_count`, `image_decode_ms` |
| `activity_switch` | `duration_ms` / `duration_ms` | `tab_switch_ms`, `frame_count` |
| `composer_typing` | `frame_p95_ms` / `hitch_ms_per_s` | `keystroke_count`, `frame_count` |
| `composer_send` | `duration_ms` / `duration_ms` | `optimistic_render_ms`, `frame_count` |
| `webview_artifact_open` | `ttfd_ms` / `ttfd_ms` | `webview_ready_ms`, `artifact_dom_ready_ms` |

## CLI Usage

```bash
# List registered scenarios
python3 -m scripts.mobile_perf scenarios

# Validate ScopeDB JSONL files
python3 -m scripts.mobile_perf validate --kind run runs.jsonl
python3 -m scripts.mobile_perf validate --kind diagnose diagnose.jsonl

# Emit a Perfetto protobuf-text config for build.raft.app
python3 -m scripts.mobile_perf parse-android --emit-perfetto-config --config-duration-ms 10000 > mobile_perf.pbtx

# Parse Android dumpsys + logcat (or Perfetto SQL outputs) into validated JSONL
python3 -m scripts.mobile_perf parse-android \
  --run-id run-001 --commit-sha 0123456789abcdef0123456789abcdef01234567 \
  --app-version 1.31.0 --app-build 13100019 --scenario thread_open \
  --device-id 3f9a1c22 --device-model "Redmi Note 12" --os-version android-14 \
  --gfxinfo gfxinfo.txt --meminfo meminfo.txt --display display.txt \
  --thermal-start-file thermal_start.txt --thermal-end-file thermal_end.txt \
  --logcat logcat.txt --out-run runs.jsonl --out-diagnose diagnose.jsonl

# Parse iOS xctrace XML exports + app logs into validated JSONL
python3 -m scripts.mobile_perf parse-ios \
  --run-id ios-001 --commit-sha 0123456789abcdef0123456789abcdef01234567 \
  --app-version 1.31.0 --app-build 13100006 --scenario thread_open \
  --device-id 9b20e4d1 --device-model "iPhone 11" --os-version ios-17.6 \
  --hitches-xml hitches.xml --time-profile-xml time_profile.xml \
  --thermal-xml thermal.xml --metal-xml metal.xml --app-log diag.log \
  --out-run ios_runs.jsonl --out-diagnose ios_diagnose.jsonl

# Evaluate paired interleaved A/B runs
python3 -m scripts.mobile_perf ab-verdict ab_runs.jsonl --metric duration_ms

# Verify 200ms synthetic stall calibration
python3 -m scripts.mobile_perf calibrate --base cal_base.jsonl --injected cal_200ms.jsonl --injected-ms 200
```

## Paired A/B Verdict & Calibration Rules

- **Cohort guard:** Any mismatch in `cohort_key` or `scenario` immediately returns `verdict: "invalid_cohort"`.
- **Thermal guard:** Runs with `thermal_end` in `{severe, critical, emergency, shutdown, serious}` or a thermal tier jump `>= 2` (e.g. Android `none -> moderate`, iOS `nominal -> serious`) are flagged and excluded by default, with reasons recorded in the summary.
- **Noise-floor verdict rule:** Requires at least `min_pairs` (default `5`) valid paired iterations. A change is classified as `improved` or `regressed` only when statistically significant (`p <= 0.05`, deterministic exact paired sign-flip for `n >= 6` and exact two-sample permutation for `n = 5`) **and** `|median_delta| > max(rel_floor * base_median, noise_multiplier * pooled_mad, abs_floor_ms)`. Otherwise excessive variance or unverified large shifts classify as `noisy`, and tight low-variance differences classify as `neutral`.
- **200ms sensitivity calibration:** Verifies an injected 200ms stall is detected within `[150ms, 250ms]` (`tolerance_ratio = 0.25`) with `p <= 0.05` on identical cohorts.

## Running Tests

```bash
python3 -m unittest discover -s scripts/mobile_perf -v
```
