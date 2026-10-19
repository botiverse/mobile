# Android Emulator Setup & Parallel Usage Guide (2026-06-21)

> Per @artin 2026-06-21 08:56 #product: "仓库里写个指引，怎么新建模拟器，安装apk，操作啥的，现在就两台模拟器，不够并行用的，看能不能标准化这块".

> Author: @Product-UX-Designer
> Scope: Slock Android emulator management for parallel dev / QA / visual capture workflows.
> Related: `AGENTS.md` "Working Rules", `scripts/slock-android-smoke.mjs` (Android smoke + screenshot).

---

## Why this guide

Currently we have 2 Android emulators running on this machine (Pixel_9a + Pixel_9a_2), with a 3rd AVD registered (Pixel_9_Pro_Fold) but not running. Multiple agents often need emulators at the same time (dev install + visual capture + QA smoke), and a single shared emulator causes serial blocking. This doc standardizes:

1. How to create new AVDs (with stable naming)
2. How to start / stop / assign AVDs
3. How to install APKs and run smoke
4. How to capture screenshots
5. Per-agent / per-task AVD allocation convention

---

## Current state (as of 2026-06-21)

```bash
$ emulator -list-avds
Pixel_9_Pro_Fold
Pixel_9a
Pixel_9a_2

$ adb devices
List of devices attached
emulator-5554   offline      ← stale, kill with `adb -s emulator-5554 emu kill`
emulator-5556   device       ← Pixel_9a
emulator-5558   device       ← Pixel_9a_2
```

emulator-NNNN port is auto-assigned by AVD: 5554/5556/5558 follow the standard port-2 convention (console = NNN4, adb = NNN6, NNN8 next).

**Available AVDs**:
| AVD name | System image | Form factor | Default port |
|----------|-------------|-------------|--------------|
| `Pixel_9a` | Pixel 9 | Phone, 6.3" | 5556 |
| `Pixel_9a_2` | Pixel 9 (clone) | Phone, 6.3" | 5558 |
| `Pixel_9_Pro_Fold` | Pixel 9 Pro Fold | Foldable, 7.6" inner | not yet running |

**Recommendation**: add 2-3 more `Pixel_9a_N` clones (different suffix) for true parallel isolation. Foldable `Pixel_9_Pro_Fold` is useful for big-screen / fold-state visual capture.

---

## Naming convention

`<family>_<index>_<purpose?>` (e.g., `Pixel_9a`, `Pixel_9a_2`, `Pixel_9a_visual`, `Pixel_9a_uat`).

- `family` = device family from `system-images` (Pixel_9a, Pixel_8, Pixel_Tablet, etc.)
- `index` = numeric suffix to disambiguate clones; `Pixel_9a_2` for the 2nd clone, `Pixel_9a_3` for 3rd, etc.
- `purpose?` = optional tag for dedicated use (e.g., `_visual` for visual capture only, `_uat` for UAT only)

Each AVD should have at most one purpose tag so the team knows which to grab for which work.

---

## Standard scripts (`scripts/`)

We're adding 5 new scripts (one per common operation) for consistency. Each script accepts a positional `<avd-name>` arg and prints structured output.

| Script | Purpose | Replaces ad-hoc commands |
|--------|---------|--------------------------|
| `scripts/avd_list.sh` | List AVDs + which are online | `emulator -list-avds` + `adb devices` |
| `scripts/avd_start.sh <avd>` | Cold-start a specific AVD (waits for `device` state) | `emulator @<avd> -no-snapshot ... &` |
| `scripts/avd_stop.sh <avd>` | Kill the running AVD cleanly | `adb -s <port> emu kill` |
| `scripts/avd_install.sh <avd> <apk>` | Install an APK on a specific AVD | `adb -s <port> install -r <apk>` |
| `scripts/avd_screenshot.sh <avd> <out.png>` | Capture full-screen screenshot to local file | `adb -s <port> exec-out screencap -p > <out.png>` |

`scripts/avd_list.sh` output (suggested format):
```
AVD                    State      Port      System image
Pixel_9a               online     5556      system-images;android-34;google_apis;arm64-v8a
Pixel_9a_2             online     5558      system-images;android-34;google_apis;arm64-v8a
Pixel_9a_3             offline    -         system-images;android-34;google_apis;arm64-v8a
Pixel_9_Pro_Fold       offline    -         system-images;android-34;google_apis_playstore;arm64-v8a
```

These scripts wrap the actual `emulator` / `adb` calls so every agent runs the same commands. Errors get a single line of stderr with the failing command, easy to grep in CI.

---

## How to create a new AVD

```bash
# 1. List available system images (one-time discovery)
$ sdkmanager --list 2>/dev/null | grep "system-images;android-34" | head -10
system-images;android-34;google_apis;arm64-v8a
system-images;android-34;google_apis;x86_64
system-images;android-34;google_apis_playstore;arm64-v8a
system-images;android-34;default;arm64-v8a
...

# 2. Install system image (if not already)
$ sdkmanager "system-images;android-34;google_apis;arm64-v8a"

# 3. Create the AVD
$ avdmanager create avd \
    -n Pixel_9a_3 \
    -k "system-images;android-34;google_apis;arm64-v8a" \
    -d "pixel_9" \
    --force <<< "no"
# Note: <<< "no" answers the "custom hardware profile?" prompt with no.

# 4. Verify
$ avdmanager list avd | grep Pixel_9a_3
    Name: Pixel_9a_3
    Device: pixel_9 (Google)
    Path: /Users/artin/.android/avd/Pixel_9a_3.avd
    Target: Google APIs (Google Inc.)
    Based on: Android 14 (API level 34)
    Tag/ABI: google_apis/arm64-v8a

# 5. (Optional) Set boot animation off + speed up AVD config
$ sed -i '' 's/hw.gpu.enabled=yes/hw.gpu.enabled=yes/' \
    /Users/artin/.android/avd/Pixel_9a_3.avd/config.ini
# Add: hw.ramSize=4096, vm.heapSize=512, disk.dataPartition.size=4G for faster boot
```

### Cold-start an AVD

```bash
$ scripts/avd_start.sh Pixel_9a_3
# Equivalent raw command:
# $ANDROID_HOME/emulator/emulator @Pixel_9a_3 \
#     -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect \
#     -no-window -accel off &
```

Wait for the AVD to be `device` (not `offline`):
```bash
$ adb -s emulator-5560 wait-for-device
# ~30-90 seconds typical
$ adb -s emulator-5560 shell getprop sys.boot_completed
1
```

If boot takes >2 min, check `adb logcat -d -s SystemServer:E` for failures.

### Stop an AVD

```bash
$ scripts/avd_stop.sh Pixel_9a_3
# Equivalent: adb -s emulator-5560 emu kill
```

### Install APK + launch app

```bash
# Build APK
$ cd compose && ./gradlew :androidApp:assembleDebug -x lint
# → compose/androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Install
$ scripts/avd_install.sh Pixel_9a_3 \
    compose/androidApp/build/outputs/apk/debug/androidApp-debug.apk
# Equivalent: adb -s emulator-5560 install -r <apk>

# Launch main activity
$ adb -s emulator-5560 shell am start \
    -n build.raft.app/.MainActivity
# Or: adb -s emulator-5560 shell monkey -p build.raft.app -c android.intent.category.LAUNCHER 1
```

### Capture screenshot

```bash
$ scripts/avd_screenshot.sh Pixel_9a_3 /tmp/pixel_9a_3_home.png
# Equivalent: adb -s emulator-5560 exec-out screencap -p > /tmp/pixel_9a_3_home.png
# Verify: file /tmp/pixel_9a_3_home.png → image dimensions match AVD
```

For visual-testing suites, use `scripts/slock-visual-testing.mjs`; it forwards
to the canonical CLI in an external `<slock-source>/packages/visual-testing`
checkout selected by `SLOCK_SOURCE_REPO` (or the documented compatibility
alias). Record the external checkout SHA when attaching visual evidence.

---

## Parallel AVD allocation

Multiple agents (dev / QA / visual capture) need to share emulators. Conventions:

### Per-task lock (recommended)

When an agent starts a multi-step task that needs an AVD, it should **acquire** a specific AVD for the duration of the task, then **release** it.

```bash
# Acquire
$ mkdir -p /tmp/avd_locks
$ while ! ln -sf "Pixel_9a_3" /tmp/avd_locks/$AGENT_$TASK 2>/dev/null; do sleep 5; done
# Release
$ rm /tmp/avd_locks/$AGENT_$TASK
```

`scripts/avd_acquire.sh <avd>` and `scripts/avd_release.sh <avd>` can wrap this. If no AVD is free, the script waits.

### Recommended AVD assignment by task type

| Task | AVD suggestion | Why |
|------|----------------|-----|
| Visual capture (e.g., `#222` baseline, `#203` specs) | `Pixel_9a` or `Pixel_9a_2` | Already running, fastest |
| Dev install (cherry-pick smoke) | `Pixel_9a_3` (new) | Isolated, won't conflict with visual capture |
| Foldable layout test | `Pixel_9_Pro_Fold` (foldable) | Tests fold state separately |
| UAT / manual smoke | `Pixel_9a` (with UAT flag) | Stable, well-known |
| Stress / jank repro | any AVD, but rerun 3x | Smoke protocol has high variance |

### Avoid

- Booting + killing the same AVD 5+ times in a session (slow; 30-90s per boot)
- Two agents installing to same AVD at the same time (race conditions on package install)
- Running visual capture while dev is installing (install restarts the app, capture is wrong)

---

## Cleanup & housekeeping

### Stale `emulator-NNNN` processes

```bash
# Find adb processes with no AVD
$ adb devices
List of devices attached
emulator-5554   offline      ← adb sees port 5554 but emulator is gone

# Kill the stale adb entry
$ adb -s emulator-5554 emu kill 2>/dev/null   # fails silently if no emulator
$ adb kill-server && adb start-server          # nukes adb cache
```

### Snapshot management

```bash
# Take a clean snapshot before destructive ops
$ adb -s emulator-5560 emu avd snapshot save "before-#{task-id}"

# Restore after smoke
$ adb -s emulator-5560 emu avd snapshot load "before-#{task-id}"
```

(Snapshots are 2-5s restore vs 60-90s fresh boot, valuable for visual baseline tests.)

### Disk cleanup

```bash
# AVD caches grow fast; check /Users/artin/.android/avd/ for >2GB AVDs
$ du -sh /Users/artin/.android/avd/*/snapshots/
# Delete old snapshots
$ rm -rf /Users/artin/.android/avd/Pixel_9a/snapshots/old-snapshot-*
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `adb devices` shows `offline` | Stale AVD process | `adb kill-server && adb start-server` |
| `emulator` boots but no adb | adb cache stale | `adb kill-server && adb -s emulator-NNNN reconnect` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Existing app signed with different key | `adb uninstall build.raft.app` then re-install |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | AVD disk full | Delete AVD snapshots; or recreate AVD with larger `disk.dataPartition.size` |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | New APK older than installed | `adb install -r -d <apk>` (allow downgrade) |
| Boot takes >3 min | Cold snapshot + first run | Set `-no-snapshot-load` on first boot, save snapshot after first init |
| Capture is all black | AVD just booted, screen not drawn yet | Wait for `sys.boot_completed=1`, sleep 5s, then capture |
| `adb install` hangs | AVD offline or auth required | Check `adb devices`; if `unauthorized`, tap "Allow USB debugging" on AVD |

---

## What to do for this task

For the current situation (only 2 emulators running, parallel work blocked):

1. **Create 2 more `Pixel_9a` clones** (`Pixel_9a_3`, `Pixel_9a_4`) for parallel dev/QA
2. **Add the 5 standard scripts** to `scripts/` (`avd_list.sh`, `avd_start.sh`, `avd_stop.sh`, `avd_install.sh`, `avd_screenshot.sh`)
3. **Add lock helper scripts** (`avd_acquire.sh`, `avd_release.sh`) for parallel allocation
4. **Update AGENTS.md** "Working Rules" with the AVD allocation convention + per-task AVD suggestions table

Owner 拍 scope（PUX scope is spec only; @Android-Developer-1 / @Cindy 拍是否 land scripts + AGENTS.md 改）。

---

## Out of scope

- iOS simulator (Slock Android only)
- Lightpanda browser testing (`.agents/skills/lightpanda/scripts/install.sh`)
- CI / cloud emulators (Farm, Bitrise, BrowserStack) — single-machine only
