# Mobile Device Automation Runbook

This is the cross-platform command-line path for Slock mobile smoke work when an agent needs to launch the app, capture screenshots, tap coordinates, or scroll without using Android Studio, DevEco Studio, or Xcode UI.

Related docs:

- `docs/android-emulator-setup.md` covers Android AVD lifecycle and raw `adb` equivalents.
- `docs/ohos-emulator-setup.md` covers OHOS emulator image/instance lifecycle,
  HDC connection, install, layout inspection, and input smoke.
- `docs/ohos-screenshot-capture.md` covers the older OHOS screenshot-only script.
- `scripts/slock_android_adb.py` is the Android JSON helper.
- `scripts/slock_ohos_hdc.py` is the OHOS JSON helper.
- `scripts/slock_ios_simctl.py` is the iOS simulator JSON helper.

## Shared Reporting Contract

When reporting a device automation run, include:

- platform and target id: Android adb serial, OHOS hdc target, or iOS simulator UDID/`booted`;
- build/install/launch command, if the app was changed;
- screenshot path and any layout/state artifact path;
- tap/swipe coordinates and coordinate space;
- whether a failure happened in build, install, launch, input, screenshot, or app assertions.

Keep artifacts under `/tmp` or `artifacts/`; do not commit screenshots unless the task explicitly asks for checked-in fixtures.

## Android Baseline

Use the existing Android helper for active control:

```bash
python3 scripts/slock_android_adb.py devices --pretty
python3 scripts/slock_android_adb.py --device emulator-5560 start-app --pretty
python3 scripts/slock_android_adb.py --device emulator-5560 get-app-state --prefix home --pretty
python3 scripts/slock_android_adb.py --device emulator-5560 click 540 2100 --pretty
python3 scripts/slock_android_adb.py --device emulator-5560 scroll --direction up --pretty
```

`get-app-state` writes both a screenshot and `uiautomator` XML, so use it before coordinate taps when possible.

## OHOS

OHOS uses DevEco/HDC, not Android `adb`. The Slock app identifiers are:

- bundle: `build.raft.mobile`
- module: `entry`
- ability: `EntryAbility`
- project root: `compose/ohosApp`
- default hdc: `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc`

### Build, Install, Launch, Screenshot

For a full fresh build/install/capture:

```bash
scripts/capture_ohos_screenshot.sh --build --install --launch --case smoke
```

For active control, use the JSON helper:

```bash
python3 scripts/slock_ohos_hdc.py devices --pretty
python3 scripts/slock_ohos_hdc.py --target 127.0.0.1:5555 start-app --pretty
python3 scripts/slock_ohos_hdc.py --target 127.0.0.1:5555 get-app-state --prefix home --pretty
python3 scripts/slock_ohos_hdc.py --target 127.0.0.1:5555 click 540 2100 --pretty
python3 scripts/slock_ohos_hdc.py --target 127.0.0.1:5555 swipe --direction up --pretty
python3 scripts/slock_ohos_hdc.py --target 127.0.0.1:5555 press-key Back --pretty
```

The helper uses:

- screenshot: `snapshot_display -f /data/local/tmp/<file>.png`, then `snapshot_display -i 0 -f ...` as retry;
- input: `uitest uiInput click`, `uitest uiInput swipe`, `uitest uiInput keyEvent`;
- layout: `uitest dumpLayout -p /data/local/tmp/<file>.json`.

Run this when a target image behaves differently:

```bash
python3 scripts/slock_ohos_hdc.py --target 127.0.0.1:5555 input-help --pretty
```

### OHOS Caveats

- If `hdc list targets` prints `[Empty]`, start/connect an OHOS device or emulator before trying build/install/capture.
- For the exact DevEco emulator CLI used on the shared Mac, including small
  instance creation and disk-space/API compatibility failures, follow
  `docs/ohos-emulator-setup.md`.
- `uitest uiInput` support can vary by target image. If click/swipe fails, include `input-help` output in the task report.
- Direction swipes use conservative phone coordinates. For foldables/tablets, pass explicit `--x1 --y1 --x2 --y2`.
- Hvigor `onDeviceTest` has historically been blocked by the Slock OHOS test-target task name issue around `linktestSharedOhosArm64`; do not classify that as device input failure.

## iOS

iOS uses Simulator + `xcrun simctl`. `simctl` can boot, install, launch, open URLs, and capture screenshots. It cannot perform public coordinate taps by itself, so the helper uses macOS Accessibility for taps and `cliclick` for optional drag/swipe. For durable semantic flows, add an XCUITest target and drive UI by accessibility identifiers.

Current Slock iOS facts:

- workspace: `compose/iosApp/iosApp.xcworkspace`
- scheme: `iosApp`
- default bundle id used by helper: `build.raft.app`
- screenshot primitive: `xcrun simctl io booted screenshot <path>`

### Simulator Build and Launch

List simulators:

```bash
python3 scripts/slock_ios_simctl.py devices --pretty
```

Build for simulator:

```bash
python3 scripts/slock_ios_simctl.py build-app --pretty
```

The build command prints `.app` candidates under its derived data path. Install one on a booted simulator:

```bash
python3 scripts/slock_ios_simctl.py --device booted install-app /tmp/slock-ios-automation/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app --pretty
python3 scripts/slock_ios_simctl.py --device booted start-app --pretty
```

Capture state:

```bash
python3 scripts/slock_ios_simctl.py --device booted get-app-state --prefix home --pretty
```

### iOS Taps and Swipes

Bring Simulator to the foreground and grant Accessibility permission to the terminal app running the helper. Window-relative tap:

```bash
python3 scripts/slock_ios_simctl.py --device booted tap 180 760 --coordinate-space window --pretty
```

Global-coordinate tap:

```bash
python3 scripts/slock_ios_simctl.py --device booted tap 620 980 --coordinate-space global --pretty
```

Swipe requires `cliclick` because AppleScript's public click primitive does not provide a reliable drag:

```bash
brew install cliclick
python3 scripts/slock_ios_simctl.py --device booted swipe 200 760 200 320 --coordinate-space window --pretty
```

### iOS Caveats

- Coordinate taps are a smoke/debug path, not the final regression-test layer. Prefer XCUITest for stable flows such as login, open Thread, send message, and attachment preview.
- Window-relative coordinates start at the Simulator window top-left, not a semantic app node. Capture a screenshot immediately before tapping.
- If macOS denies Accessibility, the helper fails before clicking. Grant permission to Terminal/iTerm/Codex host and rerun.
- If multiple simulators are booted, avoid `--device booted`; pass a UDID from `devices`.

## Minimum Cross-Platform Smoke Shape

For a task that claims "mobile screen X works on all platforms", the minimum evidence should be:

- Android: `slock_android_adb.py get-app-state`, plus any tap/scroll commands.
- OHOS: `slock_ohos_hdc.py get-app-state`, plus explicit `click`/`swipe` if navigation is part of the claim.
- iOS: `slock_ios_simctl.py get-app-state`; if coordinate interaction was used, include the Accessibility/click backend and exact coordinates.

Do not treat screenshot-only proof as enough for send, reply, mark-read, attachment download, or realtime recovery. Those paths need a reducer/store test plus at least one platform automation path that performs the user action.
