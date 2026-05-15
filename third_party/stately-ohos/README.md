# Stately OHOS Fork

This folder contains Slock's reproducible fork setup for the Touchlab Stately
artifacts needed by the SQLDelight native driver on HarmonyOS/OHOS.

## Baseline

- Upstream repository: `https://github.com/touchlab/Stately.git`
- Upstream tag: `2.1.0`
- Slock fork branch: `slock-2.1.0-ohos`
- Default published version: `2.1.0-KBA-001`

## Notes

The SQLDelight OHOS fork expects `co.touchlab:stately-concurrency:2.1.0-KBA-001`.
That artifact is not available from Maven Central or the Tencent public Maven
repositories on this machine, so Slock publishes it locally from source.

The patch adds `ohosArm64()` and makes `ohosArm64Main` reuse the Linux lock
implementation. Kotlin/Native common metadata for Linux+OHOS does not resolve
`PTHREAD_MUTEX_RECURSIVE` as a common symbol, so the patch uses the POSIX
constant value `1` directly in the shared Linux/OHOS lock implementation.

## Build Locally

```bash
scripts/prepare_stately_ohos_fork.sh --publish
```

By default the script checks out the fork under:

```text
../Slock-third-party/stately-ohos
```

The publish command writes the required `stately-strict` and
`stately-concurrency` multiplatform/OHOS artifacts to `mavenLocal`.
