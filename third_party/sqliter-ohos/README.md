# SQLiter OHOS Fork

This folder contains Slock's reproducible fork setup for the Touchlab SQLiter
driver required by the SQLDelight native driver on HarmonyOS/OHOS.

## Baseline

- Upstream repository: `https://github.com/touchlab/SQLiter.git`
- Upstream tag: `1.3.3`
- Slock fork branch: `slock-1.3.3-ohos`
- Default published version: `1.3.3-KBA-001`

## Notes

The SQLDelight OHOS fork expects `co.touchlab:sqliter-driver:1.3.3-KBA-001`.
That artifact is not available from Maven Central or the Tencent public Maven
repositories on this machine, so Slock publishes it locally from source.

The patch narrows this fork to the `ohosArm64()` native target, wires
`ohosArm64Main` to the existing Linux source set, and disables OHOS native test
link tasks. This is intentionally not a general-purpose SQLiter replacement; it
is the minimal artifact set required to unblock SQLDelight native-driver
compilation for OHOS.

## Build Locally

```bash
scripts/prepare_sqliter_ohos_fork.sh --publish
```

By default the script checks out the fork under:

```text
../Slock-third-party/sqliter-ohos
```

Run the Stately publish script first so SQLDelight can resolve both Touchlab
OHOS dependencies from `mavenLocal`.
