# KuiklyBase Platform Dependencies

This folder tracks Slock's plan for reusing Tencent's KuiklyBase-platform OHOS
dependency forks.

Reference repository:

```text
https://github.com/Tencent-TDS/KuiklyBase-platform
```

## Inventory

The repository is a patch catalog, not a single dependency artifact. Each
subdirectory contains a README plus a patch to apply on top of an upstream
project.

Useful dependencies for Slock:

| Component | KBA version | Upstream baseline | Why it matters |
| --- | --- | --- | --- |
| `kotlinx.serialization` | `1.7.1-KBA-003` | `Kotlin/kotlinx.serialization` commit `d2f7316e` | JSON serialization on OHOS. Current compose OHOS build already resolves this version. |
| `kotlinx.coroutines` | `1.8.0-KBA-001` | `Kotlin/kotlinx.coroutines` tag `1.8.0` | Ktor OHOS fork expects this version. |
| `kotlinx.atomicfu` | `0.23.2-KBA-001` | `Kotlin/kotlinx.atomicfu` tag `0.23.2` | Ktor OHOS fork expects this version. |
| `okio` | `3.9.10-KBA-001` | `square/okio` commit `c43c9a61` | Useful for future shared file/stream code on OHOS. |
| `kotlinx.datetime` | `0.6.0-RC.2-KBA-003` | `Kotlin/kotlinx-datetime` tag `v0.6.0-RC.2` | Date/time support if Settings or message metadata move deeper into shared. |

The repository also contains AndroidX/Compose/Skiko patches. Those are not part
of the current network milestone because Kuikly already owns the shared UI
runtime path.

## Maven Availability

Tencent public Maven contains the KuiklyBase versions currently used by our
compose OHOS build, for example:

- `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1-KBA-003`
- `org.jetbrains.kotlinx:kotlinx-serialization-core-ohosarm64:1.7.1-KBA-003`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-001`

The Ktor OHOS fork still has two unresolved dependency gaps on this machine:

- `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3-KBA-001`
- `org.jetbrains.kotlinx:kotlinx-io-core:0.5.4-KBA-001`

`KuiklyBase-platform` helps with the base stack, but it does not provide a
`kotlinx-io` patch and its serialization patch is `1.7.1-KBA-003`, not Ktor's
requested `1.7.3-KBA-001`.

## Prepare A Dependency Fork

Use the helper script:

```bash
scripts/prepare_kuiklybase_dependency.sh serialization
scripts/prepare_kuiklybase_dependency.sh coroutines
scripts/prepare_kuiklybase_dependency.sh atomicfu
scripts/prepare_kuiklybase_dependency.sh okio
scripts/prepare_kuiklybase_dependency.sh datetime
```

By default it clones `Tencent-TDS/KuiklyBase-platform` and the selected upstream
project under:

```text
../Slock-third-party/
```

It then checks out the documented baseline, applies the KuiklyBase patch, and
leaves the fork ready for inspection. Publish locally with:

```bash
scripts/prepare_kuiklybase_dependency.sh serialization --publish
```

Only publish from a reviewed Slock fork when we decide to own the artifact. For
now, this script is a reproducible preparation path and a way to inspect the
exact upstream diff.

## Network Integration Implication

For the OHOS network milestone, use this order:

1. Keep the Slock handwritten `SlockApiClient`; do not introduce Ktorfit yet.
2. Prepare or resolve Ktor's KBA transitive dependencies.
3. Either patch Ktor OHOS to use available `kotlinx.serialization`
   `1.7.1-KBA-003`, or port the KuiklyBase serialization patch forward to
   `1.7.3-KBA-001`.
4. Fork/publish `kotlinx-io:0.5.4-KBA-001` or adjust the Ktor fork to a version
   that resolves from public/internal Maven.
5. Only after `ktor-client-ohos` compiles, remove the current OHOS network
   source exclusions from `compose/shared/build.ohos.gradle.kts`.

This keeps Android's current Ktor/OkHttp runtime stable while we bring up the
OHOS engine in isolation.
