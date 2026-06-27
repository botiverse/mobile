# Ktor OHOS Fork

This folder documents Slock's reproducible path for owning the Ktor OHOS fork
used by shared network code. The checkout itself lives under
`third_party/.workdirs/ktor-slock-ohos` and is prepared by script so the main
Slock tree does not vendor the full Ktor source.

## Baseline

- Upstream base: `https://github.com/ktorio/ktor.git`
- Upstream tag: `2.3.7`
- Default Slock branch: `slock-2.3.7-ohos-kba`
- Default Slock local version: `2.3.7-slock-ohos-001`
- Patch artifact:
  `third_party/ktor-ohos/patches/0001-backport-ohos-to-ktor-2.3.7-kba.patch`

Ktor `2.3.7` is the practical Slock baseline because it matches Android's
current Ktor line and still uses Ktor's internal `ktor-io` modules. It does not
require the missing KBA/OHOS `org.jetbrains.kotlinx:kotlinx-io` artifact family
that blocks newer Ktor lines in Slock's current OHOS toolchain.

The patch retargets the Ktor build to Slock's current OHOS/KBA dependency train:

- Kotlin / KGP: `2.0.21-KBA-010`
- Coroutines: `1.8.0-KBA-002`
- Atomicfu: `0.23.2-KBA-001`
- Serialization: `1.7.1-KBA-003`

It adds an `ohosArm64` target, publishes `ohosArm64` variants for the Ktor
modules Slock needs, and keeps non-OHOS native targets disabled for this
minimal fork path.

## What The Patch Adds

The useful part is Ktor itself, not Ktorfit or generated API clients.

The Slock patch adds OHOS support across the Ktor `2.3.7` multiplatform stack:

- `ohosArm64` target registration in Ktor target utilities.
- OHOS KLIB publications such as `ktor-client-core-ohosarm64` and
  `ktor-client-cio-ohosarm64`.
- OHOS reuse of existing POSIX/Linux implementations where practical.
- KBA repository and plugin pins needed by Slock's OHOS compiler family.
- Source compatibility fixes needed for compiling Ktor `2.3.7` with K2/KBA.

This is a full Ktor native target fork. It is not a Kuikly host
`NetworkModule`; Slock's migration target is a shared KMP Ktor transport, with
host modules only keeping platform-owned concerns such as file refs,
environment URL rewrite, diagnostics, and lifecycle.

## Prepare Locally

Dry-run prepare:

```bash
scripts/prepare_ktor_ohos_fork.sh
```

By default this checks out upstream Ktor `2.3.7`, applies Slock-owned patches,
and rewrites local version metadata under:

```text
third_party/.workdirs/ktor-slock-ohos
```

Override the source repository after Slock creates its own remote fork:

```bash
SLOCK_KTOR_OHOS_SOURCE_URL=https://github.com/<slock-org>/ktor.git \
SLOCK_KTOR_OHOS_SOURCE_REF=slock-2.3.7-ohos-kba \
scripts/prepare_ktor_ohos_fork.sh
```

Publish the minimal artifacts needed by shared network code:

```bash
scripts/prepare_ktor_ohos_fork.sh --publish
```

Publish uses Slock's `compose/gradlew` by default so the fork build runs on
the same Gradle line as the OHOS app build. Use `SLOCK_KTOR_OHOS_GRADLE` only
when intentionally testing a different Gradle runner.

The script publishes Kotlin Multiplatform metadata and `ohosArm64`
publications for the Ktor modules used by Slock's first network target:

- `io.ktor:ktor-io`
- `io.ktor:ktor-utils`
- `io.ktor:ktor-http`
- `io.ktor:ktor-http-cio`
- `io.ktor:ktor-network`
- `io.ktor:ktor-network-tls`
- `io.ktor:ktor-events`
- `io.ktor:ktor-websockets`
- `io.ktor:ktor-websocket-serialization`
- `io.ktor:ktor-serialization`
- `io.ktor:ktor-serialization-kotlinx`
- `io.ktor:ktor-serialization-kotlinx-json`
- `io.ktor:ktor-client-core`
- `io.ktor:ktor-client-content-negotiation`
- `io.ktor:ktor-client-cio`

Use `SLOCK_KTOR_OHOS_MODULES` to override the module list when narrowing a
local experiment. The override expects Ktor Gradle project paths, for example
`:ktor-io :ktor-client:ktor-client-core`.

## Patch Model

Slock-owned changes should live as git patches under:

```text
third_party/ktor-ohos/patches/*.patch
```

Patch files are generated with zero context (`git diff -U0`) so the Slock repo
can keep `git diff --check` clean even when the patch contains blank source
context. The prepare script applies them with `git apply --unidiff-zero`.

`scripts/prepare_ktor_ohos_fork.sh` checks out the base ref, applies patches
from that directory, rewrites the local checkout version metadata to
`SLOCK_KTOR_OHOS_VERSION`, and passes the same value as Ktor's `releaseVersion`
Gradle property during publish. This lets us keep the Slock repo small while
still owning the fork deltas in version control.

When the fork stabilizes, mirror those patches into the real Slock remote fork
and point `SLOCK_KTOR_OHOS_SOURCE_URL` at that repo.

## Validation

Validated focused publish chain:

```bash
scripts/prepare_ktor_ohos_fork.sh --publish
```

The current Slock patch has successfully published the root KMP metadata and
`ohosArm64` publications for every module listed above using local version
`2.3.7-slock-ohos-001`.

After publishing, validate Slock's OHOS classpath with:

```bash
SLOCK_ENABLE_KTOR_OHOS_FORK=1 \
SLOCK_KTOR_OHOS_VERSION=2.3.7-slock-ohos-001 \
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64 \
  --no-daemon --console=plain
```

## Rejected 3.3 Reference Path

The `https://gitcode.com/CPF-KMP-CMP/ktor.git` reference fork on
`main-3.3.3-OH` / `v3.3.3-0.3.0` proved useful for identifying OHOS deltas,
but Slock cannot consume or trivially retarget those artifacts today.

Findings from the earlier investigation:

- The fork publishes Ktor `3.3.3-0.3.0` KLIBs produced by Kotlin
  `2.2.21-0.3.0`, while Slock consumes Kotlin `2.0.21-KBA-010`; enabling those
  artifacts directly fails with KLIB ABI mismatch.
- Its build logic uses Kotlin/KGP `2.2` APIs such as ABI validation DSL,
  JS/Wasm env APIs, hierarchy APIs, and context parameters, so overriding
  Gradle properties is not enough to rebuild it on Slock's KBA compiler family.
- Ktor `3.0.x` is closer to Kotlin `2.0.21`, but it already depends on
  `kotlinx-io`. Tencent/eazytec do not currently publish the required
  KBA/OHOS `kotlinx-io-core(-ohosarm64)` artifacts.

The 3.3 fork should stay as a reference source for OHOS implementation details,
not as Slock's current build input.

## Slock Integration Plan

Current state:

- Android main build uses Ktor `2.3.7`.
- OHOS build can optionally consume Slock's local Ktor `2.3.7` OHOS fork via
  `SLOCK_ENABLE_KTOR_OHOS_FORK=1`.
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

Do not switch Android release builds away from the existing host-network path
in the same commit as the OHOS smoke. Android should keep its current path
until the shared transport is validated.
