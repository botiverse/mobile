# Slock Android Performance Toolchain

Reusable scripts for Slock Android perf investigations. All tools are
**stdlib-only Python** (no pip dependencies) and assume you have:

- `adb` on PATH with at least one device/emulator online
- `python3 scripts/slock_android_adb.py` working (existing SKILL.md tool)
- the Slock Android app installed on the device (`build.raft.app`)

The tools chain together:

```
interaction_capture.py / cold_start_capture.py
    │  (runs UI scenario, captures full-text atrace + per-state UI XML/PNG)
    ▼
atrace.txt  +  ui_state_*.png / .xml
    │
    ▼
atrace_summary.py
    │  (parses atrace → counters + frame-pacing + monitor-contention)
    ▼
*.summary.json + *.summary.md
    │
    ▼
regression_compare.py
       (diff two summaries → delta report)
```

## Tools

### `cold_start_capture.py`
Force-stop → clear logcat → launch → per-checkpoint `/proc/<pid>/task/<tid>/stat`,
`top -H`, `dumpsys cpuinfo/gfxinfo/activity` snapshots. Optional `--atrace-window`
records a full-text atrace over the launch window.

```bash
python3 scripts/perf/cold_start_capture.py \
    --device emulator-5554 \
    --package build.raft.app \
    --component build.raft.app/.MainActivity \
    --checkpoints 0.5 1 2 3 5 8 12 \
    --atrace-window 12 \
    --out ./artifacts/coldstart-2026-06-26
```

Output:
- `00_am_start.txt`, `00_baseline_cpuinfo.txt`, `00_baseline_top.txt`
- `cp_NN_tNNNNms/{top_threads.txt, dumpsys_*.txt, proc_task_stat/<tid>_<name>.stat}`
- `atrace.txt` (when `--atrace-window` > 0)
- `logcat_full.txt`, `summary.json`

### `interaction_capture.py`
Stage-driven UI scenario with atrace. Stages are short strings:

```
tap:X:Y                    tap at screen coordinates
type:X:Y:TEXT              tap (X,Y) then `input text TEXT`
press:KEY                  `input keyevent KEY` (BACK, ENTER, …)
swipe:X1:Y1:X2:Y2:MS       `input swipe` over (MS) ms
open:COMPONENT             `am start -n COMPONENT`
home                       KEYCODE_HOME
```

```bash
python3 scripts/perf/interaction_capture.py \
    --device emulator-5554 \
    --package build.raft.app \
    --label send-message-slock-island \
    --out ./artifacts/perf-send-2026-06-26 \
    --stage open-channel:574:1621 \
    --stage type:530:2175:audit-test \
    --stage tap:993:2258 \
    --atrace-window 10 \
    --logcat-filter 'SLOCK_PERF_MARKER|SlockPerfScene|SlockKuiklyPerf|composer_send|thread_composer|thread_render|thread_messages|thread_row|thread_action_card|Choreographer|Skipped|jank|Exception|FATAL'
```

Output:
- `stage_NN_<kind>/<label>.{png,xml}` for each stage
- `atrace.txt`, `logcat_full.txt`, `logcat_filtered.txt`
- `summary.json`

### `atrace_summary.py`
Parse full-text atrace (Systrace text format) into a structured summary.

```bash
python3 scripts/perf/atrace_summary.py \
    --input ./artifacts/perf-send-2026-06-26/atrace.txt \
    --out-dir ./artifacts/perf-send-2026-06-26/
```

Output:
- `<input>.summary.json` — counters + frame pacing + monitor-contention owners/locks + VerifyClass by package
- `<input>.summary.md`   — human-readable report

**Known bug** (fixed in the latest version): the `table()` helper produces data
rows only, but the original code did `.splitlines()[2:]` thinking it was
skipping the manually-written header + separator rows. Since `table()` does
not produce those rows, `splitlines()[2:]` was skipping the first 2 data
rows. Make sure your version uses `.splitlines()` (no `[2:]`) for the four
Top-N tables; otherwise the rendered markdown will be missing rows.

### `regression_compare.py`
Diff two summaries and surface what changed.

```bash
python3 scripts/perf/regression_compare.py \
    --baseline artifacts/before/atrace.summary.json \
    --current  artifacts/after/atrace.summary.json \
    --out regression.md
```

Outputs a markdown delta report covering:
- counter deltas (Compose:recompose, Recomposer:animation, VerifyClass, etc.)
- frame-pacing deltas (jank frame count, p95 delta)
- heap deltas (min/max/delta KB)
- monitor-contention top owners

## End-to-end example

```bash
# 1. Capture send-path before the change.
python3 scripts/perf/interaction_capture.py \
    --device emulator-5554 --package build.raft.app \
    --label send-before --out artifacts/perf-send-before \
    --stage open-channel:574:1621 --stage type:530:2175:audit-test \
    --stage tap:993:2258 --atrace-window 10

# 2. Make your code change in a worktree, build, install.

# 3. Capture send-path after.
python3 scripts/perf/interaction_capture.py \
    --device emulator-5554 --package build.raft.app \
    --label send-after --out artifacts/perf-send-after \
    --stage open-channel:574:1621 --stage type:530:2175:audit-test \
    --stage tap:993:2258 --atrace-window 10

# 4. Summarize each.
python3 scripts/perf/atrace_summary.py \
    --input artifacts/perf-send-before/atrace.txt \
    --out-dir artifacts/perf-send-before/
python3 scripts/perf/atrace_summary.py \
    --input artifacts/perf-send-after/atrace.txt \
    --out-dir artifacts/perf-send-after/

# 5. Diff.
python3 scripts/perf/regression_compare.py \
    --baseline artifacts/perf-send-before/atrace.txt.summary.json \
    --current  artifacts/perf-send-after/atrace.txt.summary.json \
    --out regression-send-before-vs-after.md
```

## Captured-event reference (for `atrace_summary.py`)

| event class                              | signal |
| ---------------------------------------- | ------ |
| `Choreographer#doFrame`                  | frame delivery; delta between consecutive = jank |
| `monitor contention with owner <thread>` | main thread waiting for a lock held by `<thread>` |
| `VerifyClass <FQN>`                      | ART class verification on first touch (cold start) |
| `Compose:recompose`                      | Compose recomposition triggered |
| `Recomposer:animation`                   | Compose infinite animation tick |
| `Snapshot.*apply` / `SnapshotKt.advanceGlobalSnapshot` | Compose Snapshot global monitor contention |
| `Heap size (KB)\|<int>`                 | running heap footprint sample |

## Caveats / known limits

- `simpleperf` is not available on this emulator (no PMU events); use `atrace`
  for sampling. The legacy `atrace -o file` text format is what the parser
  consumes; the new Perfetto-based trace format is not yet supported.
- `atrace_summary.py` has a CLI-vs-module shadowing bug for the
  monitor-contention table rendering. Use `python3 -m perf.atrace_summary`
  to side-step until fixed.
- Compose `Recomposer:animation` event count doesn't differentiate
 60Hz-spinner-driven from lower-frequency animations — pair with explicit
 `Trace.beginSection` markers for component-level attribution.
- Per-thread jiffies capture (`proc_task_stat/<tid>_<name>.stat`) is more
  precise than `top -H` for CPU breakdown, but doesn't show **what** the
  thread was executing — use `atrace` for that.

## Author / maintainer

Pi-Worker1 (task #3, owner-mandated toolchain). Hand off or extend as needed.