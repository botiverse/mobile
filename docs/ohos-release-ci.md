# OHOS release CI

The `OHOS Release` GitHub Actions workflow builds a signed HarmonyOS `.app`
for `build.raft.mobile`. It is manual-only and uploads the validated package,
each independently verified signed `.hap`, `pack.info`, release metadata, and
any generated symbols/source maps as a private GitHub Actions artifact. It
also creates a draft Hands release containing the `.app` for AppGallery and
the standalone `.hap` for user sideloading.

The release link runs in the repository's HarmonyOS toolchain image on
`blacksmith-16vcpu-ubuntu-2404`. The workflow gives the Gradle and
Kotlin/Native processes a 24 GB heap and uses one Gradle worker. Do not move
the release job to a runner with less than 32 GB of memory: the optimized
`:ohosBridge:linkReleaseSharedOhosArm64` task exceeds the project's normal
4 GB heap.

`ohosBridge` is the native binary boundary. It depends on the full `shared`
KLIB internally but exports only the primitive `raftInitKuikly` C entry point;
realtime host calls use the existing KNOI bridge. Do not move the shared
library binary back to `:shared` or export shared UI/model/repository types from
`ohosBridge`: Kotlin/Native otherwise generates a multi-million-line C adapter
that makes the release link impractical. Release CI rejects a generated bridge
header larger than 500 lines as a guard against accidental export expansion.

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
| `HANDS_OHOS_BEARER_TOKEN` | App-scoped publisher deploy token for the Hands `raft-ohos` app |

The workflow checks that the complete signing configuration exists before it
starts dependency prebuilds. Binary secrets are decoded only under
`$RUNNER_TEMP`, are permission-restricted, and are removed in an `always()`
cleanup step.

Hvigor project signing expects DevEco-encrypted password fields tied to a
workstation-local `material/` directory, so CI does not inject raw repository
passwords into `signingConfig`. Hvigor assembles the unsigned `.app`, then the
workflow signs every contained HAP with the official `hap-sign-tool.jar` using
the raw passwords only as masked process arguments. The validation step then
independently verifies every signed HAP.

The existing GitHub Packages secrets are also used:

- `SLOCK_GITHUB_PACKAGES_USERNAME`, defaulting to `bytemain`
- `SLOCK_GITHUB_PACKAGES_TOKEN`, defaulting to `github.token`

## Dispatch and artifact

Open **Actions -> OHOS Release -> Run workflow**, choose the source ref, and
enter the semantic version components. `version_build` must be between 0 and
99. If this is the first platform draft of the train, schedule the Raft
10-minute interval reminder on the release thread before leaving the dispatch.
The workflow computes the integer version code as:

```text
major * 10,000,000 + minor * 100,000 + patch * 1,000 + build
```

`compose/ohosApp/AppScope/app.json5` may still contain placeholder
`versionName: "1.0.0"` / `versionCode: 1000000`. That is expected. CI rewrites
those fields with `scripts/set_ohos_app_version.mjs` at dispatch; do not treat
the source placeholders as a preflight blocker, and do not rewrite them into
the frozen tree.

Before upload, CI verifies all of the following:

- the `.app` ZIP structure is readable;
- `pack.info` reports the expected bundle, version name, and version code;
- at least one HAP is present;
- every contained HAP passes `hap-sign-tool.jar verify-app`;
- generated KmmResource strings are staged into the OHOS resource roots;
- the signed HAP has a nontrivial `resources.index` containing representative
  `slock_*` keys;
- the reaction atlas and manifest exist under both `rawfile/assets` and
  `resfile/assets`;
- every verified signed HAP is exported as a standalone artifact;
- the uploaded `.app` has a recorded SHA-256 digest and byte size.

The artifact is named `raft-ohos-<version-name>-<version-code>` and is retained
for 30 days. The workflow uses `hands builds publish-ohos --draft` so the
`.app`, `.hap`, metadata, and symbols share one Hands build while remaining
invisible to public update checks until the draft is reviewed and activated.
Signing material is never uploaded to Hands.

Reviewed bilingual release notes live at
`change-logs/<version>/ohos/{zh-CN,en}.md`. The workflow sends both files to
Hands when the pair exists, falls back to the historical root-level pair for
older versions, and uses its automated draft note only when neither reviewed
pair is available. The platform directory already supplies the context, so
individual bullets do not repeat "OHOS" or "HarmonyOS".
