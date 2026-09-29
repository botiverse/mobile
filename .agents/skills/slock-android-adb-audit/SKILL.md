---
name: slock-android-adb-audit
description: Use when auditing or debugging Slock Android/KMP/Kuikly surfaces from adb, especially when an agent needs repeatable internal intent navigation, Kuikly page launch, diagnostics deep links, click, press-key, scroll, screenshot, UI XML, or logcat collection.
---

# Slock Android ADB Audit

Use `scripts/slock_android_adb.py` as the standard Android device driver for
repeatable Kuikly surface audits. Prefer app-owned debug intents,
Kuikly page launches, and diagnostics hooks over fragile visual-only navigation.
The CLI prints JSON for every command, and `get-app-state` returns both a
screenshot path and a UI XML path so the captured state is reusable evidence.

## Setup

Run commands from the repository root.

```bash
python3 scripts/slock_android_adb.py devices
```

If more than one device is attached, pass `--device <serial>` to every command.
The default package is `ai.slock.android`, the default launcher component is
`ai.slock.android/.MainActivity`, and the Kuikly host component is
`ai.slock.android/.shared.SlockKuiklyActivity`.

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
`press_key`, `start_intent`, `open_page`, `open_diagnostics`,
and `capture_logcat`.

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

## Internal Navigation First

Before tapping through visible UI, search for app-owned entry points:

```bash
rg -n 'diagnostics\.OPEN|activity-alias|pageName|openPage|RouterModule|SlockKuiklyActivity|DeepLink' \
  compose/androidApp compose/shared
```

Use `start-intent` for any existing Android action/component. It supports
string, boolean, int, and long extras:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 start-intent \
  --action ai.slock.android.diagnostics.OPEN \
  --component ai.slock.android/.core.diagnostics.DiagnosticsDeepLinkActivity \
  --long-extra auto_close_ms=15000
```

Use `open-page` to start a Kuikly page with `pageName` and JSON `pageData`
when the target build allows shell/debug launches of the Kuikly activity:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 open-page router
python3 scripts/slock_android_adb.py --device emulator-5560 open-page compose_thread \
  --page-data '{"channelId":"<channel-id>","selectedTab":"chat"}'
```

If `open-page` fails with `Permission Denial` because the component is not
exported, do not return to a four-layer visual tap path as the default answer.
Add or request a debug-only exported intent/activity-alias for the target page,
then drive it with `start-intent`. Keep launch payloads identity-only: pass page
name, ids, selected tab, focus ids, and intent flags; do not pass cached rows,
signed URLs, or full route state.

### Diagnostics Deep Link

To open Diagnostics, use the dedicated helper once the debug-only intent exists:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 open-diagnostics \
  --auto-close-ms 15000
```

This helper emits:

```bash
adb shell am start -W \
  -a ai.slock.android.diagnostics.OPEN \
  -n ai.slock.android/.core.diagnostics.DiagnosticsDeepLinkActivity \
  --el auto_close_ms 15000
```

If the app reports that `DiagnosticsDeepLinkActivity` does not exist, the
implementation has not landed in that build yet. Use manual Diagnostics UI only
as a temporary fallback and record that the missing intent is the blocker.

Capture logs:

```bash
python3 scripts/slock_android_adb.py --device emulator-5560 capture-logcat \
  --out artifacts/android-adb/thread-001 \
  --prefix thread \
  --filter-regex 'SlockPerf|SlockPerfScene|tab_switch_frame|Choreographer|Skipped|jank|Exception|FATAL'
```

## Audit Workflow

1. Start or install the candidate app, then launch it with `start-app`.
2. Navigate with internal intents or `open-page` when available; use visual taps
   only for the specific interaction under audit or while a debug hook is missing.
3. Open Diagnostics with `open-diagnostics` when the scenario needs it;
   otherwise open Settings -> About -> Diagnostics manually.
4. Use `get-app-state` before and after each scenario step that changes context.
5. Use `click`, `press-key`, and `scroll` for the actual scenario inputs.
6. Capture logcat for the same window.
7. Analyze screenshots, UI XML, and logcat together. Do not attribute a jank
   frame to shared recomposition if host frame logs show the cost outside the
   shared recomposition window.

## Extending App Hooks

When a target route cannot be opened repeatably from adb:

- Prefer a debug-only exported `activity-alias` or action-specific deep link over
  UI text/bounds automation.
- Gate exported hooks out of release builds with build resources or `BuildConfig.DEBUG`.
- Route through the same production page/store code; do not create a separate
  diagnostics-only renderer that bypasses the bug.
- Keep route/pageData identity-only and let shared repositories/stores load data.
- Log app-owned diagnostics around the hook (`route`, `pageName`, ids, result,
  error) so a feedback file can prove which path ran.

## Reporting

Every report should include:

- device serial and app build/git SHA;
- exact command sequence or a saved script;
- `get-app-state` JSON paths for before/after states;
- filtered logcat path;
- internal intent or page launch used, or the exact missing-hook blocker;
- whether evidence points to shared recomposition, route first mount, lazy item
  first composition, host/container render, or repository/realtime/cache work.
