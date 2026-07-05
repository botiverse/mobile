# harmony-use

`harmony-use` is a small, dependency-free Python library and CLI for repeatable
HarmonyOS/OHOS device automation from a host machine. It wraps the commands we
repeat during Raft OHOS work: DevEco environment discovery, HDC target control,
DevEco emulator start/stop, remote host/device connection, Hvigor
build/install/launch flows, screenshots, UI layout dumps, and HiLog
capture/analysis.

The package is intentionally not tied to a single checkout. Defaults match the
Raft OHOS app, but every path and identifier can be overridden by CLI flags or
environment variables.

## Install

From this repository:

```bash
python3 -m pip install ./tools/harmony-use
```

For one-off use without installing:

```bash
PYTHONPATH=tools/harmony-use/src python3 -m harmony_use --help
```

## Environment

By default the tool looks for DevEco Studio at:

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
harmony-use launch --bundle build.raft.harmony --pretty
harmony-use run-app --project compose/ohosApp --bundle build.raft.harmony --pretty
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
harmony-use hilog --bundle build.raft.harmony --duration 20 --out /tmp/harmony-use --pretty
harmony-use analyze-log "/path/to/HiLog.txt" --pretty
```

`analyze-log` groups the OHOS auth/network failures we repeatedly need during
Raft validation:

- old Ktor/CIO native TLS stub regressions (`TLS_sessions_are_not_supported_on_Native_platform`)
- NetworkKMM execution/init/internal errors (`slock_networkkmm*`)
- TLS certificate verification failures (`CURLE_SSL*`, peer/certificate verify text)

## Library Sketch

```python
from harmony_use import HarmonyConfig, HarmonyDevice
from harmony_use import HarmonyEmulator, RemoteHost

config = HarmonyConfig.from_defaults(project="compose/ohosApp")
device = HarmonyDevice(config)
print(device.devices())
device.launch(bundle="build.raft.harmony")
print(HarmonyEmulator(config).list().stdout)
print(RemoteHost("builder-mac.local", config).hdc_devices().stdout)
```

## Design Notes

- Commands return JSON so agents can paste concise evidence in Raft threads.
- HDC/Hvigor commands are built from structured APIs instead of ad hoc shell
  snippets.
- No credentials, signing material, or device addresses are stored in the
  package.
- The package prefers explicit failures: missing DevEco tools, no connected
  target, unsigned HAP, and unsupported `uitest` commands are reported as
  actionable errors.
