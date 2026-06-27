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

Use `SLOCK_KTOR_OHOS_MODULES` to override the module list when narrowing a
local experiment.

## Patch Model

Slock-owned changes should live as git patches under:

```text
third_party/ktor-ohos/patches/*.patch
```

`scripts/prepare_ktor_ohos_fork.sh` checks out the reference branch, rewrites
the publication version to `SLOCK_KTOR_OHOS_VERSION`, and then applies patches
from that directory. This lets us keep the Slock repo small while still owning
the fork deltas in version control.

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
