---
name: slock-android-adb-audit
description: Use when auditing Slock Android/KMP/Kuikly surfaces from adb, especially when an agent needs repeatable click, press-key, scroll, screenshot, UI XML, logcat, or Kuikly profiler artifact collection.
---

# Slock Android ADB Audit

Use `scripts/slock_android_adb.py` as the standard Android device driver for
manual-but-repeatable Kuikly profiler and surface audits. The CLI prints JSON for
every command, and `get-app-state` returns both a screenshot path and a UI XML
path so the captured state is reusable evidence.

## Setup

Run commands from the repository root.

```bash
python3 scripts/slock_android_adb.py devices
```

If more than one device is attached, pass `--device <serial>` to every command.
The default package is `ai.slock.android`, and the default launch component is
`ai.slock.android/.MainActivity`.

## Core Commands

Launch the app:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 start-app
```

Capture current app state:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 get-app-state \
  --out artifacts/android-adb/thread-001 \
  --prefix home
```

Underscore aliases also work for agent scripting, for example `get_app_state`,
`press_key`, `capture_logcat`, and `pull_profiler`.

The JSON result includes:

- `screenshot_path`
- `uixml_path`
- `uiautomator_log_path`

Drive the UI:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 click 540 2140
python3 scripts/slock_android_adb.py --device emulator-5560 press-key BACK
python3 scripts/slock_android_adb.py --device emulator-5560 scroll --direction up
python3 scripts/slock_android_adb.py --device emulator-5560 scroll \
  --x1 540 --y1 1750 --x2 540 --y2 650 --duration-ms 350
```

Capture logs:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 capture-logcat \
  --out artifacts/android-adb/thread-001 \
  --prefix thread \
  --filter-regex 'SlockPerf|SlockPerfScene|tab_switch_frame|Choreographer|Skipped|jank|Exception|FATAL'
```

Pull Kuikly profiler artifacts after exporting from Diagnostics:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 pull-profiler \
  --out artifacts/android-adb/thread-001 \
  --package ai.slock.android
```

## Audit Workflow

1. Start or install the candidate app, then launch it with `start-app`.
2. Open Settings -> About -> Diagnostics and enable the recomposition profiler.
3. Reset the profiler immediately before the focused scenario.
4. Use `get-app-state` before and after each scenario step that changes context.
5. Use `click`, `press-key`, and `scroll` for the actual scenario inputs.
6. Capture logcat for the same window.
7. Export the profiler report in Diagnostics, then run `pull-profiler`.
8. Analyze `profiler_report.json`, `profiler_frames.jsonl`, screenshots, UI XML,
   and logcat together. Do not attribute a jank frame to shared recomposition if
   profiler events are mostly `noScope` first composition or if host frame logs
   show the cost outside the shared recomposition window.

## Reporting

Every report should include:

- device serial and app build/git SHA;
- exact command sequence or a saved script;
- `get-app-state` JSON paths for before/after states;
- profiler artifact paths;
- filtered logcat path;
- whether evidence points to shared recomposition, route first mount, lazy item
  first composition, host/container render, or repository/realtime/cache work.
