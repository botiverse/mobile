# OHOS Screenshot Capture

This is the minimal repeatable path for KMP/OHOS visual verification. It is meant to support checks such as Home bottom tabbar, logged-in top-right icon, long text, and input/composer pages without depending on DevEco Studio UI.

## Script

From the Android repo root:

```bash
scripts/capture_ohos_screenshot.sh --build --install --launch --case home-tabbar
```

If the HAP is already built and installed:

```bash
scripts/capture_ohos_screenshot.sh --launch --case home-tabbar
```

If you need a specific emulator/device:

```bash
scripts/capture_ohos_screenshot.sh --target 127.0.0.1:5555 --case home-tabbar
```

The script prints the required verification details:

- `hdc list targets`
- selected target id and best-effort device type/version fields
- bundle/module/ability: `build.raft.app / entry / EntryAbility`
- install and launch commands, when requested
- screenshot command
- pulled PNG path under `${TMPDIR:-/tmp}/slock-ohos-screenshots/` by default

## Default Toolchain

The script defaults to the DevEco installation used by this project:

```text
/Applications/DevEco-Studio.app/Contents
```

Override when needed:

```bash
DEVECO_STUDIO_HOME=/path/to/DevEco-Studio.app/Contents scripts/capture_ohos_screenshot.sh --case smoke
HDC_BIN=/path/to/hdc scripts/capture_ohos_screenshot.sh --case smoke
```

## First Visual Cases

Use one capture per case after navigating the app to the desired state:

```bash
scripts/capture_ohos_screenshot.sh --case home-tabbar
scripts/capture_ohos_screenshot.sh --case home-top-right-icon
scripts/capture_ohos_screenshot.sh --case thread-composer-input
scripts/capture_ohos_screenshot.sh --case settings-long-text
```

For task reports, include the printed target id, bundle/module/ability, exact screenshot command, and local PNG path.

## Known Limits

- If `hdc list targets` prints `[Empty]`, the script exits before install/capture.
- Navigation is manual for now; the script captures the current screen. This keeps the first path stable while OHOS UI automation is still being wired.
- The screenshot command uses `snapshot_display -f <remote.png>` and retries `snapshot_display -i 0 -f <remote.png>` for images that require a display id.
