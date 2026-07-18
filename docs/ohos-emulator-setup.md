# OHOS Emulator Setup and Input Smoke Runbook

This is the repeatable command-line path for starting a HarmonyOS emulator on
the shared Mac, connecting it through HDC, installing Raft, capturing state,
and running focused input smoke checks. It is the OHOS companion to
`docs/android-emulator-setup.md` and the iOS section in
`docs/mobile-device-automation.md`.

Use emulator evidence for fast regression feedback. Do not substitute it for a
physical-device gate when the task is about real keyboard/touch timing,
device-specific rendering, radio/network behavior, or a named device such as
HOP-AL10.

## Tool and app identifiers

```bash
DEVECO_HOME=/Applications/DevEco-Studio.app/Contents
EMULATOR="$DEVECO_HOME/tools/emulator/Emulator"
HDC="$DEVECO_HOME/sdk/default/openharmony/toolchains/hdc"
```

Raft OHOS identifiers:

- bundle: `build.raft.mobile`
- module: `entry`
- ability: `EntryAbility`
- project: `compose/ohosApp`

The DevEco emulator CLI is a foreground process. Keep its terminal/session
alive while the emulator is running, or launch it from a process supervisor.

## Discover installed instances and images

List existing virtual devices with their API level, storage, and running state:

```bash
"$EMULATOR" -list -details
```

List downloaded images:

```bash
"$EMULATOR" -imageList -downloaded true
```

List all phone images visible to the installed DevEco account:

```bash
"$EMULATOR" -imageList -deviceType Phone
```

If the license has not been accepted:

```bash
"$EMULATOR" -license
"$EMULATOR" -license accept
```

## Create a small task emulator

Prefer a 6 GB data partition for task-scoped smoke work. Large pre-existing
instances may fail the startup disk-space gate even when macOS reports tens of
gigabytes free, because the emulator checks its configured virtual data
partition plus safety headroom.

Example using the currently downloaded API 23 phone image:

```bash
"$EMULATOR" \
  -create "Raft API23" \
  -deviceType Phone \
  -osVersion "HarmonyOS 6.0.31(23)" \
  -storage 6 \
  -memory 4
```

Verify the new instance:

```bash
"$EMULATOR" -list -details
```

Delete and recreate an expendable task instance when its disk/state is no
longer useful:

```bash
"$EMULATOR" -delete "Raft API23"
```

Do not delete named shared instances unless their owner has approved it.

## Start, connect, and stop

Start:

```bash
"$EMULATOR" -start "Raft API23"
```

The first boot normally takes about 20–60 seconds. In another terminal, wait
until HDC exposes a target:

```bash
"$HDC" list targets
```

The local emulator normally appears as:

```text
127.0.0.1:5555
```

Read the live identity instead of trusting the instance name:

```bash
TARGET=127.0.0.1:5555
"$HDC" -t "$TARGET" shell param get const.product.model
"$HDC" -t "$TARGET" shell param get const.ohos.apiversion
"$HDC" -t "$TARGET" shell param get const.product.software.version
```

Stop cleanly:

```bash
"$EMULATOR" -stop "Raft API23"
```

## Install and launch Raft

Use an already-built, signed HAP when the task does not require a source
change. This avoids a local Kotlin/Native + Hvigor build on the shared Mac.

Install:

```bash
"$HDC" -t "$TARGET" install /absolute/path/to/raft-ohos.signed.hap
```

Launch:

```bash
"$HDC" -t "$TARGET" shell aa start \
  -a EntryAbility \
  -b build.raft.mobile
```

Restart from a clean process while preserving app data:

```bash
"$HDC" -t "$TARGET" shell aa force-stop build.raft.mobile
"$HDC" -t "$TARGET" shell aa start \
  -a EntryAbility \
  -b build.raft.mobile
```

If install fails with `9568297 ... older sdk version in the device`, compare
the HAP's compatible SDK with the live emulator API. The current Raft OHOS
project declares HarmonyOS `6.1.0(23)`, so an API 22 image cannot install it;
use API 23 or newer.

## Capture screenshot and layout

Some current images reject `.png` output names. Use `.jpeg` for the remote
snapshot and pull it to `artifacts/`:

```bash
"$HDC" -t "$TARGET" shell snapshot_display \
  -f /data/local/tmp/raft-smoke.jpeg
"$HDC" -t "$TARGET" file recv \
  /data/local/tmp/raft-smoke.jpeg \
  artifacts/raft-smoke.jpeg
```

Dump the accessibility/UI tree:

```bash
"$HDC" -t "$TARGET" shell uitest dumpLayout \
  -p /data/local/tmp/raft-layout.json \
  -b build.raft.mobile \
  -a
"$HDC" -t "$TARGET" file recv \
  /data/local/tmp/raft-layout.json \
  artifacts/raft-layout.json
```

The repository helper wraps the same primitives:

```bash
python3 scripts/slock_ohos_hdc.py devices --pretty
python3 scripts/slock_ohos_hdc.py \
  --target "$TARGET" \
  get-app-state \
  --prefix home \
  --pretty
```

## Coordinate input smoke

Inspect supported input commands on the live image:

```bash
"$HDC" -t "$TARGET" shell uitest uiInput help
```

Common operations:

```bash
"$HDC" -t "$TARGET" shell uitest uiInput click 660 970
"$HDC" -t "$TARGET" shell uitest uiInput inputText \
  660 970 \
  1111222233334444555566667777888899990000abcdefghijklmnopqrstuvwxyz1234567890
"$HDC" -t "$TARGET" shell uitest uiInput keyEvent 2055
```

`2055` is the delete/backspace key code on the tested HarmonyOS 6.1 emulator.
Confirm it on a new image by sending one key and checking the focused
`TextInput` value in a fresh layout dump before using it in a loop.

For the input regression gate:

1. Clear/restart the app so the field begins empty.
2. Inject 60–100 deterministic characters.
3. Dump layout and assert focused `TextInput.attributes.text` equals the exact
   input string.
4. Send delete until empty.
5. Dump layout and assert both `text` and `originalText` are empty.
6. Repeat three rounds.
7. Capture HILOG evidence for app/Kuikly and the system input subsystem.

On a fresh emulator, the first focus may open Celia Keyboard onboarding. Finish
the agreement/layout screens once, restart Raft, and then begin the measured
rounds. Do not count onboarding transitions as app input failures.

## HILOG

Clear the buffers before the measured run:

```bash
"$HDC" -t "$TARGET" shell hilog -r
```

Read buffered logs without leaving a blocking stream:

```bash
"$HDC" -t "$TARGET" shell hilog -x > artifacts/raft-ohos-hilog.txt
```

Review at least:

- Raft/Kuikly/input callback or exception records;
- `ProcessJank`/frame warnings;
- `InputMethod`, `KeyEvent`, and `HMKeyboard_HardwareInputManager` events;
- error/fatal records around the measured timestamps.

## Known shared-Mac findings

- Existing `Pura 90` is API 23 but has a 64 GB data partition. With about 64 GB
  host disk free, startup fails with `00801002 NO_ENOUGH_BASEDISK_SPACE_ERR`.
  A new 6 GB `Raft API23` instance starts successfully.
- The downloaded `nova 15 Pro` image is API 22. It boots, but current Raft HAPs
  requiring API 23 fail install with error `9568297`.
- CoreAudio warnings such as `Failed to create voice 'dac'` do not block boot,
  HDC, install, UI automation, or screenshots; record them but classify them as
  emulator audio limitations unless audio is the feature under test.
- Emulator input smoke is supplemental. Keep named physical-device gates open
  until the named hardware passes.

## CI virtualization boundary

The HarmonyOS macOS emulator uses `Hypervisor.framework`/HVF. Installing the
command-line SDK and emulator binaries is not sufficient when the macOS runner
is itself a VM and the provider does not expose nested virtualization.

Blacksmith run `29629122591` verified this boundary on
`blacksmith-6vcpu-macos-15`:

- the runner was `Apple Virtual Machine 1` / `VirtualMac2,1` on a virtual M4
  Pro;
- `kern.hv_vmm_present` was `1`, while `kern.hv_support` was unavailable;
- the pinned arm64 command-line tools installed and executed successfully;
- the workflow reported `hvf_unavailable` and skipped the API 23 image
  download because the nested emulator could not be started.

Use this runner for SDK/Hvigor/HDC build tooling, not as an
`OHOS-emulator-api23` execution source. Keep emulator UI execution on a
bare-metal Mac or an external executor that exposes HVF. The reproducible probe
is `.github/workflows/ohos-macos-hvf-probe.yml`.

## Reporting contract

Report:

- emulator instance name and HDC target;
- live OS/API/model values;
- installed HAP source/version/hash when available;
- exact input string, repeat count, and layout assertions;
- screenshot/layout/HILOG artifact paths;
- whether the result is emulator-only or satisfies a physical-device gate.

## Perf capture (external-executor, task #14)

After a measured run, assemble the self-describing perf.json from the HILOG
capture and the step log, then upload it to the Stamp run with `kind=perf`:

```bash
node scripts/qa/collect_perf_ohos.mjs \
  --hilog artifacts/raft-ohos-hilog.txt \
  --steps artifacts/steps.jsonl \
  --device-json '{"model":"<live model>","os":"<live os>","api":"<live api>","pid":<pid>,"hap_sha256":"<sha>","emulator_only":true}' \
  --environment OranixSlockTest --started <epoch_ms> --out artifacts/perf.json
```

Contract (locked with the OHOS lane): zero `ProcessJank`/frame hits is
`signal_absent/insufficient_evidence`, never a smoothness PASS — attach the
raw HILOG. A missing `--hilog` file is reported as `capture: "missing"`
(operator error), distinct from a genuinely quiet capture. Emulator jank is
not physical-device evidence.
