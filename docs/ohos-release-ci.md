# OHOS release CI

The `OHOS Release` GitHub Actions workflow builds a signed HarmonyOS `.app`
for `build.raft.mobile`. It is manual-only and uploads the validated package,
`pack.info`, release metadata, and any generated symbols/source maps as a
private GitHub Actions artifact.

The release link runs in the repository's HarmonyOS toolchain image on
`blacksmith-8vcpu-ubuntu-2404`. The workflow gives the Gradle and
Kotlin/Native processes a 12 GB heap and uses one Gradle worker. Do not move
the release job to a runner with less than 16 GB of memory: the optimized
`linkReleaseSharedOhosArm64` task exceeds the project's normal 4 GB heap.

## Required repository secrets

Configure these secrets before dispatching the workflow:

| Secret | Content |
| --- | --- |
| `SLOCK_OHOS_SIGNING_CERT_BASE64` | Base64-encoded HarmonyOS release certificate (`.cer`) |
| `SLOCK_OHOS_SIGNING_PROFILE_BASE64` | Base64-encoded signed release provision profile (`.p7b`) |
| `SLOCK_OHOS_SIGNING_KEY_BASE64` | Base64-encoded release keystore (`.p12` or compatible keystore) |
| `SLOCK_OHOS_KEY_ALIAS` | Alias of the signing key in the keystore |
| `SLOCK_OHOS_KEY_PASSWORD` | Password for the signing key |
| `SLOCK_OHOS_KEYSTORE_PASSWORD` | Password for the keystore |

The workflow checks that the complete signing configuration exists before it
starts dependency prebuilds. Binary secrets are decoded only under
`$RUNNER_TEMP`, are permission-restricted, and are removed in an `always()`
cleanup step.

The existing GitHub Packages secrets are also used:

- `SLOCK_GITHUB_PACKAGES_USERNAME`, defaulting to `bytemain`
- `SLOCK_GITHUB_PACKAGES_TOKEN`, defaulting to `github.token`

## Dispatch and artifact

Open **Actions -> OHOS Release -> Run workflow**, choose the source ref, and
enter the semantic version components. `version_build` must be between 0 and
99. The workflow computes the integer version code as:

```text
major * 1,000,000 + minor * 10,000 + patch * 100 + build
```

Before upload, CI verifies all of the following:

- the `.app` ZIP structure is readable;
- `pack.info` reports the expected bundle, version name, and version code;
- at least one HAP is present;
- every contained HAP passes `hap-sign-tool.jar verify-app`;
- the uploaded `.app` has a recorded SHA-256 digest and byte size.

The artifact is named `raft-ohos-<version-name>-<version-code>` and is retained
for 30 days. Publishing to AppGallery Connect and publishing the package to
Hands are intentionally separate steps; the current Hands CLI Android publish
command must not be used for an OHOS `.app`.
