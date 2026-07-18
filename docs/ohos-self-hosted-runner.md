# OHOS Bare-Metal Self-Hosted Runner

This runbook provisions one fail-closed GitHub Actions runner for the DevEco
HarmonyOS API 23 emulator. Start with one serial slot. Do not enable a second
runner or concurrent emulator until the first slot is stable and the dual-slot
negative/soak gates have been completed.

The emulator must run directly on a bare-metal Apple Silicon Mac. A macOS guest
created with Apple Virtualization.framework cannot host this emulator because
macOS guests do not expose nested virtualization. Hosted macOS VMs can build
with Hvigor, but they cannot provide the `OHOS-emulator-api23` execution gate.

## Isolation contract

GitHub labels select a runner process; they do not select or isolate a device.
The execution chain is:

```text
dedicated macOS user + host PF UID gate
        -> unique runner service + private runner .env
        -> fixed emulator instance and -hdcPort
        -> scripts/ci/ohos-slot-guard.sh
        -> hdc -p -t <fixed loopback endpoint>
        -> device marker + model/API/software gate
        -> GitHub concurrency group + runner-local atomic lock
```

Every install, launch, shell, file-transfer, input, log, screenshot, and cleanup
command in a CI job must go through `scripts/ci/ohos-slot-guard.sh hdc`. Do not
use `hdc list targets`, the first discovered target, `hdc -p` without `-t`, or
raw unguarded HDC in an emulator workflow.

Fixed ports, markers, and locks prevent accidental cross-slot use by compliant
jobs. They do not hide the emulator from another process running as the same
macOS user. On the shared Mac, ordinary `hdc list targets -v` discovers a live
fixed `127.0.0.1:16001` endpoint. A runner that shares the `artin` UID with
local agents therefore provides collision protection, not access isolation.

For agent isolation, run both the emulator and Actions runner as a dedicated
non-admin macOS user such as `raft-ohos-runner`, then use a host PF anchor to
block loopback TCP access to port 16001 for every other effective UID. PF can
match the effective user that opened a local TCP socket. Keep the guard,
marker, and lock even after the PF rule is installed; the layers solve
different failures.

An administrator must review and install the PF anchor. The essential filter
shape is:

```pf
block drop out quick on lo0 inet proto tcp \
  from any to 127.0.0.1 port 16001 user != raft-ohos-runner
```

Load it through a named anchor in the host's managed PF configuration; do not
replace or flush the system ruleset from a CI job. Runner jobs must never have
passwordless permission to edit PF rules. After enabling the anchor, terminate
old HDC connections so pre-existing state cannot bypass the new trial.

## Slot 1 machine contract

Use a dedicated DevEco emulator endpoint in the supported range 10000-16555:

```text
slot id:       ohos-emulator-api23-slot-1
runner label:  ohos-emulator-api23-slot-1
HDC endpoint:  127.0.0.1:16001
API:           23
model:         emulator
```

Stop an instance before changing its HDC port. On this shared Mac, do not stop,
migrate, delete, or recreate an emulator while another acceptance run owns it.

The existing `artin`-owned `Raft API23` instance may be used to prove the fixed
port and guard locally, but it is not the long-running isolated runner device.
Create the runner instance while logged in as `raft-ohos-runner` so its files,
process, and HDC host state belong to the dedicated UID:

```bash
DEVECO_HOME=/Applications/DevEco-Studio.app/Contents
EMULATOR="$DEVECO_HOME/tools/emulator/Emulator"

"$EMULATOR" \
  -create "Raft API23 Slot 1" \
  -deviceType Phone \
  -osVersion "HarmonyOS 6.0.31(23)" \
  -storage 6 \
  -memory 4

"$EMULATOR" -start "Raft API23 Slot 1" -hdcPort 16001
```

Confirm the endpoint directly; do not rely on the shared HDC registry:

```bash
HDC="$DEVECO_HOME/sdk/default/openharmony/toolchains/hdc"
"$HDC" -p -t 127.0.0.1:16001 shell param get const.ohos.apiversion
```

## Private runner environment

Put the binding in the GitHub runner installation's private `.env` file. This
file is machine state and must never be committed:

```dotenv
OHOS_SLOT_ID=ohos-emulator-api23-slot-1
OHOS_SERIAL=127.0.0.1:16001
OHOS_INSTANCE_UUID=<DevEco-instance-config-UUID>
OHOS_EXPECT_HOST_USER=raft-ohos-runner
OHOS_EXPECT_MODEL=emulator
OHOS_EXPECT_API=23
OHOS_EXPECT_SOFTWARE_CONTAINS=6.1.0.115
HDC_REAL=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
OHOS_SLOT_LOCK_ROOT=/Users/raft-ohos-runner/actions-runner-ohos-slot-1/_locks
GRADLE_USER_HOME=/Users/raft-ohos-runner/actions-runner-ohos-slot-1/_cache/gradle
KONAN_DATA_DIR=/Users/raft-ohos-runner/actions-runner-ohos-slot-1/_cache/konan
```

Restart the runner service after editing `.env`, then inspect the service log to
confirm the runner reconnected. Repository files, logs, artifacts, and Raft
messages must not contain the runner registration token or signing secrets.

Read the new UUID from `Emulator -list -details`; do not reuse the UUID of an
`artin`-owned preflight instance. The device marker binds the live emulator to
that dedicated user's DevEco instance UUID:

```text
/data/local/tmp/raft-runner-slot-id
ohos-emulator-api23-slot-1|<DevEco-instance-config-UUID>
```

Provision it only during setup, with the exact private environment loaded:

```bash
ALLOW_SLOT_PROVISION=1 scripts/ci/ohos-slot-guard.sh provision-marker
scripts/ci/ohos-slot-guard.sh verify
```

Normal CI never sets `ALLOW_SLOT_PROVISION` and therefore cannot rewrite device
identity.

## Register one GitHub runner

Use a fresh GitHub runner directory and a one-time registration token obtained
by an authorized repository administrator. Do not reuse another runner's
directory or `_work` tree.

```bash
mkdir -p /Users/raft-ohos-runner/actions-runner-ohos-slot-1
cd /Users/raft-ohos-runner/actions-runner-ohos-slot-1

# Download and verify the current GitHub Actions runner archive according to
# GitHub's repository Settings -> Actions -> Runners instructions.

./config.sh --unattended \
  --url https://github.com/botiverse/mobile \
  --token '<one-time-registration-token>' \
  --name 'jiacheng-ohos-api23-slot-1' \
  --labels 'ohos-emulator-api23,ohos-emulator-api23-slot-1' \
  --work '_work'

./svc.sh install
./svc.sh start
./svc.sh status
```

GitHub's default `self-hosted`, `macOS`, and `ARM64` labels plus the two custom
labels must all be present before dispatching the workflow.

Do not add the dedicated user to an admin group or grant local agents access to
its runner directory, credentials, emulator instance, or service definition.

## Cache and workspace boundaries

The runner may restore dependency downloads from GitHub Actions cache, but each
slot keeps independent writable state:

- independent runner directory and `_work`;
- independent `GRADLE_USER_HOME`, Gradle/Kotlin daemons, project `.gradle`, and
  build outputs;
- independent `KONAN_DATA_DIR`, Hvigor/ohpm temporary state, `TMPDIR`, reports,
  signing intermediates, and device state;
- shared read-only SDK/tool installations are allowed;
- do not let concurrent runners write the same complete `~/.gradle`, Konan, or
  build directory.

Cache keys must include OS/architecture and the relevant Gradle, Kotlin,
Kuikly, lockfile, and tool versions. Restore the same dependency content into
each runner's private cache rather than sharing live daemon/cache directories.

## First probe

Before dispatching, run the repository contract test locally:

```bash
bash -n scripts/ci/ohos-slot-guard.sh
bash -n scripts/ci/test_ohos_slot_guard.sh
scripts/ci/test_ohos_slot_guard.sh
```

Then manually dispatch `.github/workflows/ohos-emulator-slot-probe.yml`. The
workflow is intentionally limited to slot 1. It:

1. acquires the local slot lock;
2. verifies endpoint, marker, model, API, and software;
3. captures one screenshot through guarded direct HDC;
4. removes only the probe-owned device file;
5. releases the lock and uploads identity/screenshot evidence.

The first trial passes only when the job was scheduled to the expected runner,
the identity step passed, the screenshot was pulled, cleanup completed, and the
lock was released. A queued job means the label/service is unavailable; it is
not device evidence.

Before calling the runner agent-isolated, add two host-side negative probes:

1. As the ordinary `artin` user, both TCP connect and direct HDC to 16001 fail.
2. As `raft-ohos-runner`, direct HDC to 16001 passes and the repository guard
   reports `host_user=raft-ohos-runner` with the exact slot marker.

If the first probe succeeds, the PF boundary is not active; stop the rollout.

## Failures and recovery

- Marker/model/API/software mismatch: stop before device work and inspect the
  private `.env`, fixed port, and live instance. Never rewrite the marker from
  a normal job.
- Existing lock: inspect `<OHOS_SLOT_LOCK_ROOT>/<slot>.lock/owner`. Remove a
  stale lock only after proving no runner/manual process still owns the slot.
- Device not found: verify the named emulator process and fixed `-hdcPort`.
  Do not fall back to the first target.
- Runner offline: inspect the runner service and mount availability before
  changing labels or re-registering it.

After the single-slot probe is stable, add slot 2 only through a separate
review. That review must include wrong-endpoint marker failures in both
directions, double-acquire failure, distinct canaries, restart isolation, and a
30-60 minute simultaneous identity/device-command soak before enabling two-job
concurrency.

## References

- `docs/ohos-emulator-setup.md` for emulator lifecycle and evidence collection.
- `.github/workflows/ohos-macos-hvf-probe.yml` for the hosted-macOS nested-HVF
  boundary.
- Apple DTS: <https://developer.apple.com/forums/thread/827663>
