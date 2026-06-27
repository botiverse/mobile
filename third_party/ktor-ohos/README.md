# Ktor OHOS Fork

This folder documents Slock's reproducible path for owning the Ktor OHOS fork
used by shared network code. The checkout itself lives under
`third_party/.workdirs/ktor-slock-ohos` and is prepared by script so the main
Slock tree does not vendor the full Ktor source.

## Baseline

- Reference fork: `https://gitcode.com/CPF-KMP-CMP/ktor.git`
- Reference branch: `main-3.3.3-OH`
- Reference tag checked during investigation: `v3.3.3-0.3.0`
- Reference commit checked during investigation:
  `a1863eb981cf353b2f048bc85e47f6f9e78fb72b`
- Default Slock local version: `3.3.3-slock-ohos-001`

The reference fork is based on Ktor `3.3.3` and publishes as
`3.3.3-0.3.0`. Slock should not depend on that external artifact directly in
app builds. Prepare and publish a Slock-owned version, then consume the Slock
coordinate from `mavenLocal` or an internal Maven repository.

## KBA Retarget Status

The `main-3.3.3-OH` reference fork cannot be retargeted to Slock's current
OHOS Kotlin family by only overriding Gradle versions.

Validated KBA publish attempt:

```bash
./gradlew --no-daemon --refresh-dependencies --no-configuration-cache \
  -PreleaseVersion=3.3.3-slock-ohos-001 \
  -Pkotlin_version=2.0.21-KBA-010 \
  -Pbuild_snapshot_train=true \
  -Patomicfu_version=0.23.2-KBA-001 \
  -Pcoroutines_version=1.8.0-KBA-002 \
  -Pserialization_version=1.7.1-KBA-003 \
  :ktor-io:publishKotlinMultiplatformPublicationToMavenLocal \
  :ktor-io:publishOhosArm64PublicationToMavenLocal
```

Findings:

- The fork's Gradle wrapper is `9.2.1`; KGP `2.0.21-KBA-010` needs the build
  to run on the Slock-compatible Gradle `8.13` line.
- The build must add Tencent Maven
  `https://mirrors.tencent.com/nexus/repository/maven-tencent/` before the
  eazytec repositories for KBA Kotlin compiler plugin and serialization
  artifacts. Without this, Gradle resolves some metadata from eazytec and then
  misses files that exist in Tencent Maven.
- After those repository/tooling fixes, `build-logic` still fails because Ktor
  `3.3.3` build code itself uses Kotlin/KGP `2.2` APIs:
  `ExperimentalAbiValidation` / `abiValidation`, Kotlin JS/Wasm env APIs,
  Kotlin hierarchy APIs such as `withOhosX64`, and Kotlin context parameters.
  This is a build-source compatibility issue before Ktor module compilation
  even starts.
- Ktor `3.0.3` is the upstream Ktor line that matches Kotlin `2.0.21`, but it
  does not include the OHOS fork deltas. Its dependency train also introduces
  a required missing artifact family for Slock: `kotlinx-io` with OHOS/KBA
  variants. Tencent/eazytec do not currently publish
  `org.jetbrains.kotlinx:kotlinx-io-core(-ohosarm64):0.5.4-KBA-001` or
  `0.9.0-KBA-001`.

Practical path forward:

1. Do not enable `SLOCK_ENABLE_KTOR_OHOS_FORK=1` for the current `3.3.3`
   reference artifacts in Slock's OHOS build; their KLIB ABI is produced by
   Kotlin `2.2.21-0.3.0`, while Slock consumes Kotlin `2.0.21-KBA-010`.
2. Rebase the OHOS deltas onto a Ktor `3.0.x` base, or backport the `3.3.3`
   build logic/source to KGP `2.0.21`; the former is the smaller build-system
   move.
3. Before either Ktor rebuild can succeed on OHOS, fork/publish
   `kotlinx-io` for the same KBA/OHOS target family, then pin Ktor's
   `kotlinx-io`, coroutines, atomicfu, and serialization versions as one
   train.

## What The Fork Adds

The useful part is Ktor itself, not Ktorfit or generated API clients.

The `main-3.3.3-OH` fork adds OHOS support across the Ktor multiplatform stack:

- `ohosArm64` and `ohosX64` targets in Ktor's target hierarchy.
- OHOS KLIB publications such as `ktor-client-core-ohosarm64` and
  `ktor-client-cio-ohosarm64`.
- iOS/Darwin variants in the same Ktor artifact family.
- OHOS cinterop/linker handling for native tests and curl/OpenSSL where needed.
- OHOS reuse of existing POSIX/Linux implementations where practical, such as
  `ktor-io` charset/encoding support.

This is a full Ktor native target fork. It is not a Kuikly host
`NetworkModule`; Slock's migration target is a shared KMP Ktor transport, with
host modules only keeping platform-owned concerns such as file refs,
environment URL rewrite, diagnostics, and lifecycle.

## Prepare Locally

Dry-run prepare:

```bash
scripts/prepare_ktor_ohos_fork.sh
```

By default this checks out the reference fork under:

```text
third_party/.workdirs/ktor-slock-ohos
```

Override the source repository after Slock creates its own remote fork:

```bash
SLOCK_KTOR_OHOS_SOURCE_URL=https://github.com/<slock-org>/ktor.git \
SLOCK_KTOR_OHOS_SOURCE_REF=slock-3.3.3-ohos \
scripts/prepare_ktor_ohos_fork.sh
```

Publish the minimal artifacts needed by shared network code:

```bash
scripts/prepare_ktor_ohos_fork.sh --publish
```

The script publishes Kotlin Multiplatform metadata and `ohosArm64`
publications for the Ktor modules used by Slock's first network target:

- `io.ktor:ktor-client-core`
- `io.ktor:ktor-client-cio`
- `io.ktor:ktor-client-content-negotiation`
- `io.ktor:ktor-serialization-kotlinx-json`
- `io.ktor:ktor-client-json`
- `io.ktor:ktor-client-logging`
- transitive Ktor modules used by those artifacts
  (`ktor-network`, `ktor-network-tls`, and related Ktor core modules)

Use `SLOCK_KTOR_OHOS_MODULES` to override the module list when narrowing a
local experiment.

## Patch Model

Slock-owned changes should live as git patches under:

```text
third_party/ktor-ohos/patches/*.patch
```

`scripts/prepare_ktor_ohos_fork.sh` checks out the reference branch, rewrites
the local checkout version metadata to `SLOCK_KTOR_OHOS_VERSION`, passes the
same value as Ktor's `releaseVersion` Gradle property during publish, and then
applies patches from that directory. This lets us keep the Slock repo small
while still owning the fork deltas in version control.

When the fork stabilizes, mirror those patches into the real Slock remote fork
and point `SLOCK_KTOR_OHOS_SOURCE_URL` at that repo.

## Slock Integration Plan

Current state:

- Android main build uses Ktor `2.3.7`.
- OHOS build currently does not depend on Ktor.
- Android Kuikly networking already calls Ktor internally from the host module.
- OHOS Kuikly networking currently uses ArkTS `@kit.NetworkKit`.
- iOS has no completed network host module yet.

Recommended sequence:

1. Prepare and publish the Slock Ktor OHOS fork to `mavenLocal` using the
   script above.
2. Enable the Slock OHOS Gradle hook with
   `SLOCK_ENABLE_KTOR_OHOS_FORK=1` only for Ktor spike builds.
3. Add a small shared KMP `KtorSlockNetworkTransport` and verify JSON
   GET/POST/PATCH/DELETE, timeout, cancellation, and auth refresh.
4. Add multipart/fileRef support after JSON traffic is stable.
5. Migrate Android and OHOS from per-host HTTP implementations to the shared
   Ktor transport only after focused smoke tests pass.

Do not switch Android release builds to the Ktor 3 fork in the same commit as
the OHOS smoke. Android should keep its current OkHttp path until the shared
transport is validated.
