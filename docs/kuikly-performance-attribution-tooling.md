# Kuikly Performance Attribution Tooling

> **Update (2026-09-29):** the in-app recomposition profiler
> (`SharedSettingsRecompositionProfiler`, Diagnostics profiler controls) was
> removed per artin. The collector no longer pulls `KuiklyProfiler/` files; the
> analyzer still accepts `profiler_report.json` / `profiler_frames.jsonl` from
> older captures, but new captures rely on the PerformanceModule and OHOS host
> planes only.

This document defines the first practical path for finding where OHOS Kuikly
jank comes from. The goal is not to rely on one profiler. We need three aligned
planes:

1. Kuikly/Compose recomposition profiler:
   `profiler_report.json` and `profiler_frames.jsonl`.
2. Kuikly `PerformanceModule` and existing performance snapshots:
   launch, frame, memory, first frame, FPS, and route snapshots.
3. OHOS host diagnostics:
   `SlockOhosDiagnostics`, `hilog`, `hitrace`, HiDumper, and DevEco Profiler.

The key output is an attribution table:

```text
jank window -> nearest business marker -> Kuikly CPU/recompose evidence -> native/system evidence -> owner
```

When a window cannot be mapped to a marker, the tooling must say
`unattributed`. That is preferable to pretending a frame has a known owner.

## Why PerformanceModule Is Not Enough

Kuikly `PerformanceModule` is the page-level base signal. It can tell us launch
cost, frame/FPS, and memory values reported by the renderer. It cannot identify
which shared composable, icon batch, cache write, realtime payload, or modal
transition caused a specific jank window.

The recomposition profiler has the opposite shape: it identifies recomposition
scope and frame cost in shared code, but it does not know whether native render,
OHOS scheduling, resource decode, or ArkTS bridge work was also involved.

The attribution tool therefore joins both with business markers.

## Existing Signals

Current code already has the following useful hooks:

- OHOS `KuiklyViewDelegate.ets` enables `KRMonitorType.LAUNCH`,
  `KRMonitorType.FRAME`, and `KRMonitorType.MEMORY`, then records
  `SlockKuiklyPerf`.
- `BridgeModule.reportKuiklyPerformanceSnapshot(...)` records summarized Kuikly
  metrics through the native `kuiklyPerformanceSnapshot` bridge.
- `PerformanceSceneEffect` marks current route scenes such as `kuikly_home` and
  `kuikly_thread`.

These are enough for a first report. They are not enough for full attribution
until we add explicit business markers.

## Marker Contract

Use `BridgeModule.performanceMarker(...)` for shared business markers. On OHOS
the bridge emits each marker to `SlockOhosDiagnostics` and to an easy-to-grep
console/hilog line. Where possible, later platform work can also wrap major
windows with `hitrace` tags so DevEco trace timelines can show the same names.

Minimum line shape:

```text
SLOCK_PERF_MARKER sceneId=<uuid> route=<route> phase=<name> event=<start|end|instant> tsMs=<monotonic_ms> durationMs=<n> threadHash=<hash> meta=<small-json-or-key-values>
```

Marker rules:

- `sceneId` is created at route entry and propagated through child work.
- `threadId` should never be logged raw; use a stable hash when needed.
- Every `start` must have a matching `end` with the same `sceneId` and `phase`.
- Long-running batch work must be logged as batch start/end totals, not per-item
  console spam.
- Missing markers are reported as `unattributed`.

Minimum marker set:

- `home_open`
- `thread_open_start`
- `thread_payload_ready`
- `thread_first_render`
- `thread_first_frame`
> **Retired (2026-07-07, task #229/#207):** the previously planned
> `modal_show_*` / `cache_read_*` / icon-batch markers are deliberately NOT
> being added — per artin, the log surface is already too wide, and standing
> scatter markers have weak yield. Future performance attribution should open
> a per-scenario task and add markers with sampling/toggle/aggregation
> boundaries only.

- `modal_show_start`
- `modal_show_end`
- `composer_focus`
- `composer_send`
- `lucide_batch_start`
- `lucide_batch_end`
- `realtime_batch_apply_start`
- `realtime_batch_apply_end`
- `cache_read_start`
- `cache_read_end`
- `cache_write_start`
- `cache_write_end`

## Collection Flow

1. Run one focused scenario, for example Home -> Thread -> open members modal.
2. Collect OHOS files and logs:

```bash
HDC_BIN=/path/to/hdc ./scripts/collect_kuikly_performance_attribution.sh \
  --out artifacts/kuikly-performance/thread-modal-001
```

3. Generate the markdown attribution report:

```bash
python3 scripts/analyze_kuikly_performance_attribution.py \
  --input artifacts/kuikly-performance/thread-modal-001 \
  --output artifacts/kuikly-performance/thread-modal-001/summary.md
```

## Analyzer Output

The analyzer must always include:

- source availability table;
- Top 20 components sorted by `recompositionCount`;
- every component with `recompositionCount > 50`;
- slow frames over 16 ms from `profiler_frames.jsonl`;
- marker table from `hilog` / diagnostics lines;
- Kuikly snapshot lines from `SlockKuiklyPerfSnapshot` and `SlockKuiklyPerf`;
- attribution table with `unattributed` when no nearby marker exists;
- threshold alerts.

Default alert thresholds:

| Signal | Threshold |
| --- | ---: |
| Slow frame | 16 ms |
| Thread first frame | 1000 ms |
| Kuikly-heavy one-second window | 70% |
| Icon batch | 16 ms |
| Realtime apply batch | 16 ms |
| Cache read/write | 30 ms |

Thresholds are intentionally conservative. They should be tuned after we have a
few real nova9a and simulator captures.

## Official OHOS Tooling References

Use Huawei official docs as the source of truth for the platform tooling:

- HDC: <https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/hdc>
- HiLog: <https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/hilog>
- HiDumper: <https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/hidumper>
- DevEco Profiler: <https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/ui-inspector-profiler>
- HiTrace FAQ: <https://developer.huawei.com/consumer/cn/doc/harmonyos-faqs/faqs-performance-analysis-kit-31>

## Implementation Phases

Phase 1, tooling:

- add collector and analyzer scripts;
- analyze existing profiler artifacts and hilog diagnostics;
- report missing markers as `unattributed`.

Phase 2, marker API:

- add common `BridgeModule.performanceMarker(...)`;
- write markers to `SlockOhosDiagnostics` and `hilog`;
- optionally add `hitrace` wrappers on OHOS native side.

Phase 3, instrumentation:

- instrument Home, Thread, modal, composer, cache, realtime, and icon batch
  phases with the minimum marker set above. Current shared code already emits
  route open, Thread payload/render/first-frame, composer send, and realtime
  batch markers; modal, cache, and icon-batch markers should be added at their
  specific callsites.

Phase 4, validation:

- run focused scenarios on nova9a;
- attach generated `summary.md` to the task thread;
- only move visual/perf regressions to Done after the report contains no large
  `unattributed` jank windows for the scenario being accepted.
