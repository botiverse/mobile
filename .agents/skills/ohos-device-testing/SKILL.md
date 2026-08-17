---
name: ohos-device-testing
description: Use when investigating, configuring, or running Raft HarmonyOS/OpenHarmony OHOS device or emulator tests from the command line, including DevEco/Hvigor, HDC, Hypium/ArkXTest, single-target slot isolation, signed HAP carrier identity and build/install/runner/assertion failure triage.
---

# OHOS Device Testing

## Evidence model

An OHOS runtime receipt binds all of these:

- Mobile source SHA/tree;
- exact shared KLIB and renderer HAR/release-manifest identity;
- signed HAP/APP path, SHA-256, bundle/version and signer;
- one explicit HDC target serial plus device model, OHOS API and software version;
- exact test runner/filter and assertion result.

Gradle/HAR/Hvigor success alone is not device evidence. A product model name alone is not proof that the target runs OHOS.

## Discover current project facts

Do not copy historical bundle ids or Gradle task names. From the Mobile repo:

```bash
sed -n '1,120p' compose/ohosApp/AppScope/app.json5
sed -n '1,180p' compose/ohosApp/entry/src/ohosTest/module.json5
rg -n "OpenHarmonyTestRunner|TestAbility|@ohos/hypium" \
  compose/ohosApp/entry/src/ohosTest compose/ohosApp/entry/oh-package.json5 -S
```

Current main uses bundle `build.raft.mobile`, module `entry`, test sources under `entry/src/ohosTest`, and `OpenHarmonyTestRunner`; still read the files on the task exact.

## Tool environment

```bash
SDK_HOME=/Applications/DevEco-Studio.app/Contents
export DEVECO_SDK_HOME="$SDK_HOME/sdk"
export PATH="$DEVECO_SDK_HOME:$SDK_HOME/jbr/Contents/Home/bin:$SDK_HOME/tools/node/bin:$SDK_HOME/tools/ohpm/bin:$SDK_HOME/tools/hvigor/bin:$DEVECO_SDK_HOME/default/openharmony/toolchains:$PATH"
HDC_BIN="$DEVECO_SDK_HOME/default/openharmony/toolchains/hdc"
HVIGOR_JS="$SDK_HOME/tools/hvigor/bin/hvigorw.js"
NODE_BIN="$SDK_HOME/tools/node/bin/node"
```

List targets, then choose exactly one:

```bash
"$HDC_BIN" list targets
export OHOS_SERIAL=<exact-target-id>
"$HDC_BIN" -t "$OHOS_SERIAL" shell param get const.product.model
"$HDC_BIN" -t "$OHOS_SERIAL" shell param get const.ohos.apiversion
"$HDC_BIN" -t "$OHOS_SERIAL" shell param get const.product.software.version
```

`[Empty]` means no target. Multiple targets are not permission to loop over all of them.

## CI/emulator slot isolation

Managed concurrent emulator jobs must use `scripts/ci/ohos-slot-guard.sh`. It verifies host user, explicit loopback serial, device marker, instance UUID, model/API/software and a per-slot lock, then runs HDC as `hdc -p -t "$OHOS_SERIAL"`.

Before invoking the guard, the slot owner must provide the values assigned by the runner/device reservation:

```bash
export OHOS_EXPECT_HOST_USER=<reserved-host-user>
export OHOS_SLOT_ID=<reserved-slot-id>
export OHOS_SERIAL=<reserved-loopback-target>
export OHOS_INSTANCE_UUID=<reserved-emulator-instance>
export OHOS_EXPECT_MODEL=<reserved-model>
export OHOS_EXPECT_API=<reserved-api-level>
# Optional when the reservation also pins a software build:
export OHOS_EXPECT_SOFTWARE_CONTAINS=<expected-version-fragment>
```

Do not invent these values from an ambient target; obtain them from the authoritative slot reservation. `OHOS_SERIAL` must be the reserved `127.0.0.1:10000-16555` endpoint.

```bash
scripts/ci/ohos-slot-guard.sh verify
scripts/ci/ohos-slot-guard.sh acquire
trap 'scripts/ci/ohos-slot-guard.sh release' EXIT
scripts/ci/ohos-slot-guard.sh hdc shell param get const.ohos.apiversion
```

Do not bypass the guard with ambient `hdc shell`, `install`, `file send` or cleanup. A shared macOS-user HDC server is not safe evidence for multiple concurrent slots.

For a manually attached physical device that is outside the loopback-slot contract, still use one explicit `-t "$OHOS_SERIAL"`, record the readback above, and do not claim CI slot isolation.

## Build layers

From `compose`/`compose/ohosApp`, use the repository's current scripts and pins:

```bash
./gradlew --no-daemon --console=plain -c settings.ohos.gradle.kts copyNetworkOhosRuntimeLibs

cd ohosApp
"$SDK_HOME/tools/ohpm/bin/ohpm" install --all
"$NODE_BIN" "$HVIGOR_JS" --sync -p product=default --analyze=normal --parallel
"$NODE_BIN" "$HVIGOR_JS" --mode module -p module=entry@default \
  -p product=default -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
```

Before install, record SHA-256 of the signed HAP and verify it consumed the intended shared KLIB/HAR. For immutable Kuikly releases, include the release-manifest digest and renderer HAR digest.

## Install and launch one target

```bash
HAP=entry/build/default/outputs/default/entry-default-signed.hap
BUNDLE=build.raft.mobile  # replace with the value read from AppScope/app.json5

"$HDC_BIN" -t "$OHOS_SERIAL" shell aa force-stop "$BUNDLE"
"$HDC_BIN" -t "$OHOS_SERIAL" install "$HAP"
"$HDC_BIN" -t "$OHOS_SERIAL" shell aa start -a EntryAbility -b "$BUNDLE"
```

In a managed slot, replace each direct HDC invocation with `ohos-slot-guard.sh hdc ...`.

## Test runner

Probe the target's runner syntax first:

```bash
"$HDC_BIN" -t "$OHOS_SERIAL" shell aa test -h
"$HDC_BIN" -t "$OHOS_SERIAL" shell \
  "aa test -b $BUNDLE -m entry -s unittest OpenHarmonyTestRunner"
```

Class/filter options vary by image and runner. Use only options shown by that target's `aa test -h` or the checked-in runner contract.

Hypium is appropriate for real UI actions, but it still requires the same exact carrier/target identity and test ability. Put focused tests under `entry/src/ohosTest/ets/test` and separate launch/runner failures from assertion failures.

## `onDeviceTest` and KMP test wiring

Never preserve an old missing-task caveat as a permanent fact. Probe the task inventory on the exact source:

```bash
./gradlew -c settings.ohos.gradle.kts :shared:tasks --all | rg -i "ohos.*test|link.*test.*ohos"
"$NODE_BIN" "$HVIGOR_JS" --mode module -p module=entry@default -p product=default --list
```

Current source may expose `linkDebugTestOhosArm64`; older reports mentioned `linktestSharedOhosArm64`. If `onDeviceTest` fails, classify the first failure as one of:

1. shared/KMP test link;
2. Hvigor test HAP build/sign;
3. install/target admission;
4. `aa test`/runner launch;
5. test assertion/runtime crash.

Use manual assemble/install/`aa test` only as a scoped diagnostic when the integrated task is broken; do not report it as equivalent without the same test carrier and runner.

## Reporting

Lead with PASS/RED/NOT RUN. Include source, dependency manifest/HAR, signed carrier and target identities; exact failing layer; runner/filter; logs or crash artifacts; and whether the target was Emulator or physical. Never relabel an Android device as OHOS without an HDC target plus `const.ohos.apiversion`/software readback.
