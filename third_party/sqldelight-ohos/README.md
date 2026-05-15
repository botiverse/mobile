# SQLDelight OHOS Fork

This folder keeps Slock's reproducible SQLDelight OHOS fork setup without
vendoring the full SQLDelight source tree into the app repository.

## Baseline

- Upstream repository: `https://github.com/sqldelight/sqldelight.git`
- Upstream tag: `2.1.0`
- Slock fork branch: `slock-2.1.0-ohos`
- Default published version: `2.1.0-slock-ohos-001`

The patch in `patches/0001-ohos-arm64-support.patch` is derived from
`blackbbc/sqldelight` branch `2.1.0-ohos`, but is applied on top of the
official SQLDelight `2.1.0` tag so Slock can keep the same SQLDelight API
baseline while adding OHOS artifacts.

## Current Direction

Slock should not make the SQLDelight native-driver fork the critical path for
HarmonyOS data work.

The practical migration path is to keep SQLDelight as the Android
implementation detail and move shared callers behind a small local-store
contract first:

- Android actual: continue using the current SQLDelight schema and
  `AndroidSqliteDriver`.
- OHOS actual: start with the simplest implementation that unblocks the shared
  UI/data flow, such as an in-memory store or a thin wrapper around Harmony
  local KV/RDB storage.
- Shared repositories: depend on the local-store contract, not on SQLDelight
  drivers directly.

This lets HarmonyOS validate login/settings/Home data flow without waiting on
the native SQLDelight dependency chain. A full SQLDelight native-driver fork can
remain available as a later persistence upgrade if we decide we need identical
SQLite-backed storage on OHOS.

After follow-up discussion, Slock still wants to keep the native SQLite path
moving in parallel because having a real SQLite-backed OHOS driver is useful.
The fork scripts therefore cover all three required layers:

1. `scripts/prepare_stately_ohos_fork.sh`
2. `scripts/prepare_sqliter_ohos_fork.sh`
3. `scripts/prepare_sqldelight_ohos_fork.sh`

Run them in that order when publishing the native-driver stack to `mavenLocal`.

Verified locally:

```bash
scripts/prepare_stately_ohos_fork.sh --publish
scripts/prepare_sqliter_ohos_fork.sh --publish
SLOCK_SQLDELIGHT_WORKDIR=/tmp/sqldelight-ohos-native-verify \
  scripts/prepare_sqldelight_ohos_fork.sh
cd /tmp/sqldelight-ohos-native-verify
./gradlew :drivers:native-driver:compileKotlinOhosArm64
```

The native-driver compile resolves the local OHOS artifacts:

- `co.touchlab:stately-concurrency-ohosarm64:2.1.0-KBA-001`
- `co.touchlab:stately-strict-ohosarm64:2.1.0-KBA-001`
- `co.touchlab:sqliter-driver-ohosarm64:1.3.3-KBA-001`

## Native-Driver Fork Status

`scripts/prepare_sqldelight_ohos_fork.sh` has been verified to apply this patch
cleanly to upstream `2.1.0`.

`drivers:native-driver:compileKotlinOhosArm64` is not fully green yet because
the SQLDelight OHOS patch depends on OHOS-enabled KBA builds of Touchlab
dependencies:

- `co.touchlab:stately-concurrency:2.1.0-KBA-001`
- `co.touchlab:sqliter-driver:1.3.3-KBA-001`

Those artifacts are not available in Maven Central, Tencent public Maven, or
the local Maven cache on this machine. Replacing them with the official
`2.1.0` / `1.3.3` artifacts still fails variant matching because those official
artifacts do not publish an `ohos_arm64` native variant.

The upstream comparison diff at
`/Users/artin/Downloads/Data/master...blackbbc_sqldelight_2.1.0-ohos.diff.txt`
does include the expected Tencent repositories in root `build.gradle`,
`settings.gradle`, `buildLogic/settings.gradle`, and `sample/settings.gradle`:

```groovy
maven { url "https://mirrors.tencent.com/nexus/repository/maven-tencent" }
maven { url "https://mirrors.tencent.com/nexus/repository/maven-public" }
mavenLocal()
```

The prepare script applies that full diff, so these repositories are present in
the generated fork. The local verification still returns 404 for the KBA
Touchlab artifacts from both Tencent repositories. That means blackbbc's branch
can be prepared from source, but a complete local publish also requires those
KBA dependency artifacts to exist in a private/local Maven source, or requires
Slock to fork/publish them as well.

So the full fork is a two-layer fork:

1. SQLDelight `2.1.0` with OHOS target/publication support.
2. Touchlab SQLiter/Stately OHOS variants published to the same internal Maven
   source before SQLDelight native-driver is compiled or published.

This repository includes Slock fork scaffolding for both dependencies:

- `third_party/stately-ohos`
- `third_party/sqliter-ohos`

## Build Locally

```bash
scripts/prepare_sqldelight_ohos_fork.sh --publish
```

By default the script checks out the fork under:

```text
../Slock-third-party/sqldelight-slock-ohos
```

Override locations or version when needed:

```bash
SLOCK_SQLDELIGHT_WORKDIR=/tmp/sqldelight-slock-ohos \
SLOCK_SQLDELIGHT_VERSION=2.1.0-slock-ohos-002 \
scripts/prepare_sqldelight_ohos_fork.sh --publish
```

## Artifacts Needed by Slock

The first smoke should consume only the minimal artifacts from Maven local:

- `app.cash.sqldelight:gradle-plugin:<version>`
- `app.cash.sqldelight:runtime:<version>`
- `app.cash.sqldelight:native-driver:<version>`
- `app.cash.sqldelight:android-driver:<version>`
- `app.cash.sqldelight:sqlite-driver:<version>`
- `app.cash.sqldelight:coroutines-extensions:<version>`

Keep the Android app on official `2.1.0` until a `compose/shared` smoke DB
compiles for both Android and OHOS. After that, migrate the real schema in a
separate task.
