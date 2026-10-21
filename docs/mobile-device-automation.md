# Mobile Device Automation Runbook

This is the cross-platform command-line path for Slock mobile smoke work when an agent needs to launch the app, capture screenshots, tap coordinates, or scroll without using Android Studio or Xcode UI.

Related docs:

- `docs/android-emulator-setup.md` covers Android AVD lifecycle and raw `adb` equivalents.
- `scripts/slock_android_adb.py` is the Android JSON helper.
- `scripts/slock_ios_simctl.py` is the iOS simulator JSON helper.

## Shared Reporting Contract

When reporting a device automation run, include:

- platform and target id: Android adb serial or iOS simulator UDID/`booted`;
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

## iOS

iOS uses Simulator + `xcrun simctl`. `simctl` can boot, install, launch, open URLs, and capture screenshots, but it has no public input API. The helper therefore routes every tap and swipe through the checked-in `iosAppUITests` XCUITest target. It never sends macOS-global mouse/keyboard events or activates Simulator.app.

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

Window-relative coordinates are app points and run inside XCUITest:

```bash
python3 scripts/slock_ios_simctl.py --device booted tap 180 760 --coordinate-space window --pretty
```

Normalized coordinates (`0.0` to `1.0`) are also supported:

```bash
python3 scripts/slock_ios_simctl.py --device booted tap 0.5 0.8 --coordinate-space normalized --pretty
```

Arbitrary start/end swipes use the same coordinate-space contract:

```bash
python3 scripts/slock_ios_simctl.py --device booted swipe 200 760 200 320 --coordinate-space window --pretty
```

The first interaction builds the `iosAppUITests` scheme into `/tmp/ios-use-derived-data` by default, then starts persistent `QaAcceptanceLoopTests`. Later `ios-use` / `slock_ios_simctl.py` taps reuse that loop over the file protocol instead of launching a new one-shot XCTest process. A one-shot `IosUseInteractionTests` session can locate Kuikly Home rows and still fail to deliver the tap.

### iOS Caveats

- Coordinate taps are a smoke/debug path, not the final regression-test layer. Prefer accessibility labels and durable XCUITest cases for stable flows such as login, open Thread, send message, and attachment preview.
- Window-relative coordinates start at the app's top-left in XCTest points, not a semantic app node. Capture a screenshot immediately before tapping.
- `--coordinate-space global` is intentionally rejected: host-desktop coordinates cannot be mapped safely without host-global input.
- If multiple simulators are booted, avoid `--device booted`; pass a UDID from `devices`.

## Minimum Cross-Platform Smoke Shape

For a task that claims "mobile screen X works on all platforms", the minimum evidence should be:

- Android: `slock_android_adb.py get-app-state`, plus any tap/scroll commands.
- iOS: `slock_ios_simctl.py get-app-state`; if coordinate interaction was used, include the XCUITest backend, coordinate space, and exact coordinates.

Do not treat screenshot-only proof as enough for send, reply, mark-read, attachment download, or realtime recovery. Those paths need a reducer/store test plus at least one platform automation path that performs the user action.
