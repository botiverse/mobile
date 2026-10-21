---
name: mobile-use
description: Use when controlling or auditing Raft mobile apps from command line across Android and iOS with the repo-local mobile-use package. Covers android-use, ios-use, screenshots/layout capture, log capture/analysis, launch trimming, and remote host wrappers.
---

# Mobile Use

Use `tools/mobile-use` as the standard repo-local package for repeatable mobile
device automation. It installs two platform-specific CLIs and keeps shared
bottom helpers in one package:

- `android-use` for ADB, Android app launch/control, logcat, screenshots, UI XML,
  and diagnostics intents.
- `ios-use` for Xcode/simctl build/install/launch, simulator control,
  screenshots, logs, and remote wrappers.

## Setup

From the repository root:

```bash
python3 -m pip install ./tools/mobile-use
```

For one-off use without installing:

```bash
PYTHONPATH=tools/mobile-use/src python3 -m android_use --help
PYTHONPATH=tools/mobile-use/src python3 -m ios_use --help
```

## Android Audit Flow

Prefer app-owned entry points over fragile visual navigation:

```bash
android-use devices --pretty
android-use launch --pretty
android-use open-diagnostics --pretty
android-use open-page compose_thread --page-data '{"channelId":"<id>"}' --pretty
android-use state --out artifacts/android --prefix thread --pretty
```

Capture and analyze logcat:

```bash
android-use logcat --duration 10 \
  --filter-regex 'Slock|Kuikly|AndroidRuntime|ActivityTaskManager|ActivityManager' \
  --out artifacts/android --prefix thread --pretty

android-use analyze-log artifacts/android/thread-logcat.txt \
  --trim-last-start \
  --trim-out artifacts/android/thread-last-start-logcat.txt \
  --pretty
```

`analyze-log` reports total lines, analyzed lines, launch count, last launch
marker, network failures, crashes, render signals, and optional trimmed output
path. It detects app starts from `ActivityTaskManager START`,
`ActivityManager Start proc`, `Displayed`, and `wm_on_resume_called` markers for
the configured Android package.

The legacy `scripts/slock_android_adb.py` remains a compatibility wrapper around
`android-use`; prefer `android-use` directly for new automation.

## iOS Flow

```bash
ios-use devices --pretty
ios-use build --workspace compose/iosApp/iosApp.xcworkspace --scheme iosApp --pretty
ios-use install --pretty
ios-use launch --pretty
ios-use screenshot --out artifacts/ios --pretty
ios-use tap-text Home --pretty
ios-use interact --actions '[{"type":"tapText","text":"all"}]' --pretty
ios-use log --duration 10 --predicate 'process == "iosApp"' --out artifacts/ios --pretty
ios-use analyze-log artifacts/ios/state-ios-log.txt --pretty
```

UI inspect/act defaults to `agent-device` (`AGENT_DEVICE_BIN` or PATH). Use
`--driver qa-loop --workspace compose/iosApp/iosApp.xcworkspace` only for the
legacy XCTest file protocol. Product-log verdicts stay outside `ios-use`.
