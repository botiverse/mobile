# Raft mobile use tools

This package provides small, dependency-free Python libraries and CLIs for
repeatable Raft mobile device automation from a host machine.

The platform entrypoints are intentionally separate:

- `harmony-use` is HarmonyOS/OHOS only.
- `android-use` is Android/ADB only.
- `ios-use` is iOS/Xcode/simctl only.

Only neutral lower-level helpers are shared (`device_use` command runner, SSH
remote execution, shell quoting, and result models). Platform command semantics
stay in the platform package so Harmony-specific behavior does not grow Android
or iOS flags.

`harmony-use` wraps the commands we repeat during Raft OHOS work: DevEco
environment discovery, HDC target control, DevEco emulator start/stop, remote
host/device connection, Hvigor build/install/launch flows, screenshots, UI
layout dumps, and HiLog capture/analysis.

`android-use` packages the older `scripts/slock_android_adb.py` workflow into a
library/CLI: ADB device discovery, install/launch/intent helpers, Kuikly
page open, diagnostics deep links, screenshots, uiautomator XML, input, and
logcat. The old script remains as a compatibility wrapper.

`ios-use` covers the repeatable Xcode/simulator operations that do not require
extra dependencies: simctl device discovery, boot/shutdown, xcodebuild build,
install/launch/terminate, open URL, screenshot, log capture, log analysis, and
remote SSH wrappers.

The package is intentionally not tied to a single checkout. Defaults match the
Raft OHOS app, but every path and identifier can be overridden by CLI flags or
environment variables.

## Install

From this repository:

```bash
python3 -m pip install ./tools/mobile-use
```

For one-off use without installing:

```bash
PYTHONPATH=tools/mobile-use/src python3 -m harmony_use --help
```

## Environment

By default `harmony-use` looks for DevEco Studio at:

```text
/Applications/DevEco-Studio.app/Contents
```

Overrides:

- `DEVECO_HOME` or `SDK_HOME` for the DevEco app contents directory.
- `DEVECO_SDK_HOME` for the SDK directory.
- `HDC_BIN` for a specific `hdc` executable.
- `OHOS_SERIAL` for a default device target.
- `HARMONY_USE_PROJECT` for the OHOS app project directory.

## Common Commands

### Harmony/OHOS

Inspect host setup:

```bash
harmony-use doctor --pretty
harmony-use env
```

List and connect targets:

```bash
harmony-use devices --pretty
harmony-use connect 192.168.50.129:5555 --pretty
```

Start/stop a DevEco emulator:

```bash
harmony-use emulator-list --details --pretty
harmony-use emulator-start "Phone API 20" --bootmode coldboot --pretty
harmony-use emulator-stop "Phone API 20" --pretty
```

Run HDC commands through a remote host over SSH:

```bash
harmony-use remote-devices builder-mac.local --pretty
harmony-use remote-connect builder-mac.local 192.168.50.129:5555 --pretty
harmony-use remote-run builder-mac.local -- hdc list targets
```

Build, install, and launch the Raft OHOS app:

```bash
harmony-use build-hap --project compose/ohosApp --pretty
harmony-use install --project compose/ohosApp --pretty
harmony-use launch --bundle build.raft.mobile --pretty
harmony-use run-app --project compose/ohosApp --bundle build.raft.mobile --pretty
```

Capture device state:

```bash
harmony-use screenshot --out /tmp/harmony-use --pretty
harmony-use dump-layout --out /tmp/harmony-use --pretty
harmony-use state --out /tmp/harmony-use --pretty
```

Drive basic UI input:

```bash
harmony-use tap 540 1200
harmony-use swipe --direction up
harmony-use key Back
```

Capture or analyze HiLog:

```bash
harmony-use hilog --bundle build.raft.mobile --duration 20 --out /tmp/harmony-use --pretty
harmony-use analyze-log "/path/to/HiLog.txt" --pretty
```

`analyze-log` groups the OHOS auth/network failures we repeatedly need during
Raft validation:

- old Ktor/CIO native TLS stub regressions (`TLS_sessions_are_not_supported_on_Native_platform`)
- NetworkKMM execution/init/internal errors (`slock_networkkmm*`)
- TLS certificate verification failures (`CURLE_SSL*`, peer/certificate verify text)

### Android

Inspect ADB setup:

```bash
android-use doctor --pretty
android-use devices --pretty
```

Install, launch, and stop:

```bash
android-use install compose/androidApp/build/outputs/apk/debug/androidApp-debug.apk --pretty
android-use launch --pretty
android-use stop --pretty
android-use run-app compose/androidApp/build/outputs/apk/debug/androidApp-debug.apk --pretty
```

Open Slock/KMP surfaces:

```bash
android-use open-page compose_thread --page-data '{"channelId":"..."}' --pretty
android-use open-diagnostics --auto-close-ms 15000 --pretty
android-use start-intent -a build.raft.app.diagnostics.OPEN -n build.raft.app/.core.diagnostics.DiagnosticsDeepLinkActivity --pretty
```

Capture state and input:

```bash
android-use state --out /tmp/android-use --pretty
android-use tap 540 1200
android-use swipe --direction up
android-use key BACK
```

Capture/analyze logs:

```bash
android-use logcat --duration 10 --filter-regex 'Slock|Kuikly|AndroidRuntime' --out /tmp/android-use --pretty
android-use analyze-log /tmp/android-use/state-logcat.txt --pretty
android-use analyze-log /tmp/android-use/state-logcat.txt --trim-last-start --trim-out /tmp/android-use/last-start-logcat.txt --pretty
```

`analyze-log` counts app launches from ActivityTaskManager/ActivityManager
logcat markers for the configured Android package. Pass `--trim-last-start` to
analyze only the final app-start window; pass `--trim-out` to write that
last-start window to a separate file.

Remote host helpers:

```bash
android-use remote-devices builder-mac.local --pretty
android-use remote-run builder-mac.local -- adb devices
```

### iOS

Inspect Xcode/simctl setup:

```bash
ios-use doctor --pretty
ios-use devices --pretty
```

Build, install, launch, and stop:

```bash
ios-use build --workspace compose/iosApp/iosApp.xcworkspace --scheme iosApp --pretty
ios-use install --pretty
ios-use launch --pretty
ios-use terminate --pretty
ios-use run-app --workspace compose/iosApp/iosApp.xcworkspace --scheme iosApp --pretty
```

Simulator operations:

```bash
ios-use boot --device <simulator-udid> --pretty
ios-use shutdown --device <simulator-udid> --pretty
ios-use open-url buildraft://diagnostics --pretty
ios-use screenshot --out /tmp/ios-use --pretty
```

Drive UI interactions through `agent-device` by default (no XCTest loop, no
workspace required). Product-log verdicts stay in the caller:

```bash
ios-use doctor --pretty
ios-use tap-text Home --pretty
ios-use tap 0.5 0.9 --pretty
ios-use swipe --direction up --pretty
ios-use interact --actions '[{"type":"tapText","text":"Tasks"},{"type":"tapText","text":"Home","waitAfter":1}]' --pretty
```

`agent-device` must be on `PATH` (or set `AGENT_DEVICE_BIN`). The old
`QaAcceptanceLoopTests` file protocol remains as `--driver qa-loop` and still
needs `--workspace`. `tap` uses normalized coordinates (`0.0` to `1.0`).
`interact` accepts `tapText`, `tap`, `swipe`, `text`, `key`, and `wait`. A
swipe may use `direction`, or `x/y/x2/y2` with `coordinateSpace` `normalized`
or `points`. iOS interactive-pop is a coordinate swipe, not
`gesture swipe left-edge`.

Capture/analyze logs:

```bash
ios-use log --duration 10 --predicate 'process == "iosApp"' --out /tmp/ios-use --pretty
ios-use analyze-log /tmp/ios-use/state-ios-log.txt --pretty
```

Remote host helpers:

```bash
ios-use remote-devices builder-mac.local --pretty
ios-use remote-run builder-mac.local -- xcrun simctl list devices
```

## Library Sketch

```python
from android_use import AndroidConfig, AndroidDevice
from harmony_use import HarmonyConfig, HarmonyDevice, HarmonyEmulator
from ios_use import IosConfig, IosSimulator

config = HarmonyConfig.from_defaults(project="compose/ohosApp")
device = HarmonyDevice(config)
print(device.devices())
device.launch(bundle="build.raft.mobile")
print(HarmonyEmulator(config).list().stdout)

android = AndroidDevice(AndroidConfig.from_defaults())
print(android.devices())

ios = IosSimulator(IosConfig.from_defaults(workspace="compose/iosApp/iosApp.xcworkspace"))
print(ios.devices())
```

## Design Notes

- Commands return JSON so agents can paste concise evidence in Raft threads.
- Platform commands are built from structured APIs instead of ad hoc shell
  snippets.
- No credentials, signing material, or device addresses are stored in the
  package.
- The package prefers explicit failures: missing DevEco tools, no connected
  target, missing ADB/Xcode tools, unsigned HAP, missing APK/app bundles, and
  unsupported device commands are reported as actionable errors.
