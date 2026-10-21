# Fork Artifact Publishing to GitHub Packages

> **Status (2026-10-15): historical.** The forked stack now lives on Raft
> Artifacts rather than GitHub Packages: Stately `2.1.0-KBA-001`, SQLiter
> `1.3.3-KBA-001` and SQLDelight `2.1.0-KBA-003` are consumed remotely, and the
> `third_party/stately`, `third_party/sqliter` and `third_party/sqldelight`
> submodules were removed (#3029, #3031 and the follow-up). The text below is
> kept as the original design record. Its OHOS consumers were removed with the
> HarmonyOS app (#3181); this repo now builds Android and iOS only.

## Purpose

Move Slock's forked native dependency stack out of `mavenLocal()` and local HAR
files so CI can build Android and OHOS from reproducible, immutable artifacts.

The first publish line should be Kuikly `raft.2`, then OHOS render delivery, then
the forked SQLDelight stack. This order keeps the highest-risk UI/runtime layer
coherent before moving the database dependency chain.

This spec is intentionally a publishing/CI contract, not an implementation
patch. The repo currently has local publish helpers and local auto-publish
fallbacks; those should remain useful for development, but CI must consume
remote, suffixed artifacts.

## Current Local State

Kuikly is consumed as a release family:

- Android/KMP Kuikly artifacts use base `2.21.0` plus Kotlin `2.1.21`, produced
  today as `2.21.0-2.1.21` by `Version.getKuiklyVersion()`.
- OHOS Kuikly artifacts use base `2.21.0` plus Kotlin/OHOS `2.0.21-ohos`,
  produced today as `2.21.0-2.0.21-ohos` by
  `Version.getKuiklyOhosVersion()`.
- `scripts/build_kuikly_fork.sh` publishes the Slock fork to `mavenLocal()` for
  both lines. It builds Android with `KUIKLY_KOTLIN_VERSION=2.1.21` and OHOS
  with `KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010`.
- `compose/settings.gradle.kts`, `compose/build.gradle.kts`, and
  `compose/settings.ohos.gradle.kts` include `mavenLocal()`, so local artifacts
  can override public artifacts during development.
- `compose/ohosApp/entry/oh-package.json5` currently consumes the OHOS render
  package from a local HAR path:
  `file:./libs/kuikly-render-staging2-652ba003.har`.

The forked SQLDelight stack is also local-first today:

- `compose/settings.ohos.gradle.kts` checks `mavenLocal()` and auto-publishes
  missing OHOS artifacts from `third_party/stately`, `third_party/sqliter`, and
  `third_party/sqldelight`.
- The current versions are:
  - `co.touchlab:stately-*`: `2.1.0-KBA-001`
  - `co.touchlab:sqliter-driver*`: `1.3.3-KBA-001`
  - `app.cash.sqldelight:*`: `2.1.0-KBA-001`
- `docs/ohos-sqldelight-fork.md` documents the local verification path.

## Artifact Identity

Do not publish forked artifacts under the exact upstream/public versions. Same
coordinates with different bytecode are cache-hostile and impossible to reason
about in CI.

Use explicit Slock suffixes for every remote fork artifact. For the first Kuikly
remote publish, target:

- Android/KMP Kuikly: `2.21.0-2.1.21-raft.2`
- OHOS Kuikly KLIB/Maven artifacts: `2.21.0-2.0.21-ohos-raft.2`
- OHOS render HAR/package: include `2.21.0-2.0.21-ohos-raft.2` and the Kuikly
  fork commit in the package filename or package version metadata.

This means the implementation should add a publication-version override instead
of only setting `KUIKLY_VERSION=2.21.0-raft.2`. The fallback produces
`2.21.0-raft.2-2.1.21`, which is usable but less consistent with the existing
`<base>-<kotlin>` version shape.

For SQLDelight, preserve the existing KBA line and add a Slock publish suffix
only when the fork changes after the current local line:

- Stately: `2.1.0-KBA-001` for the current pinned fork; next remote-only Slock
  cut should be `2.1.0-KBA-001-raft.1`.
- SQLiter: `1.3.3-KBA-001`; next remote-only Slock cut should be
  `1.3.3-KBA-001-raft.1`.
- SQLDelight: `2.1.0-KBA-001`; next remote-only Slock cut should be
  `2.1.0-KBA-001-raft.1`.

If the team wants the first GitHub Packages upload to exactly mirror the current
local artifacts without a suffix, make it a one-time bootstrap only and never
reuse the same version for changed bytecode.

## Target Registries

GitHub Packages should host Maven/Gradle publications. Official GitHub docs use
this Maven registry URL shape:

```kotlin
maven {
    name = "GitHubPackages"
    url = uri("https://maven.pkg.github.com/OWNER/REPOSITORY")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull
            ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull
            ?: System.getenv("GITHUB_TOKEN")
    }
}
```

Notes from the GitHub Gradle/Actions docs:

- The owner in `maven.pkg.github.com/OWNER/REPOSITORY` must be lowercase.
- A workflow can use `GITHUB_TOKEN` to publish packages associated with the
  workflow repository.
- A classic PAT with `read:packages` is needed to install packages associated
  with other private repositories unless package access is explicitly granted
  to the consuming repository.
- Publish workflows should declare:

```yaml
permissions:
  contents: read
  packages: write
```

Primary references:

- <https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry>
- <https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle>
- <https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages>

## Repository Mapping

The final owner/repository names need owner confirmation, but the publishing
model should be:

1. Kuikly Maven artifacts
   - Source: `third_party/kuikly-ui` at the pinned fork commit.
   - Package host: the Slock repo if `GITHUB_TOKEN` should be enough, or the
     Kuikly fork repo if package provenance should follow the fork.
   - Recommended initial path: publish from the repo that owns the workflow and
     grant Slock CI read access. This avoids a PAT for same-repo publish.

2. OHOS render HAR/package
   - Source: same Kuikly fork commit and release family as the Maven artifacts.
   - Package host: the owner's HarmonyOS mirror repo or a dedicated OHOS
     artifact repo.
   - Delivery option A: GitHub Release asset or Actions artifact plus a checked
     lockfile update in `compose/ohosApp/entry/oh-package*.json5`.
   - Delivery option B: GitHub Packages npm registry if the HAR is wrapped as an
     OHPM-compatible package.
   - This needs a short proof-of-consumption before deciding. The current app
     uses `file:./libs/*.har`, so CI can be unblocked by downloading a release
     asset even before OHPM registry publishing is settled.

3. SQLDelight stack
   - Sources:
     - `third_party/stately`
     - `third_party/sqliter`
     - `third_party/sqldelight`
   - Package host: each fork repo or a shared Slock fork-artifacts repo.
   - Recommended initial path: publish all three to one GitHub Packages Maven
     registry that Slock CI can read. Splitting by fork repo improves
     provenance but requires more repository access configuration.

## CI Workflow Shape

Create one manual workflow first, then promote to tag/release triggers after the
first successful publish:

```yaml
name: Publish fork artifacts

on:
  workflow_dispatch:
    inputs:
      family:
        description: Artifact family to publish
        required: true
        type: choice
        options:
          - kuikly
          - kuikly-ohos-render
          - sqldelight-stack
      version_suffix:
        description: Slock suffix, for example raft.2
        required: true
      source_ref:
        description: Fork source ref or submodule commit
        required: true

permissions:
  contents: read
  packages: write
```

The workflow should fail before publish if:

- the requested source ref does not match the checked submodule commit;
- the final version does not include a Slock suffix;
- any Gradle publication still points only at `mavenLocal()`;
- the workflow is about to publish an already-existing version.

Use separate jobs because the toolchains differ:

1. `publish-kuikly-android`
   - Checks out the repo with submodules.
   - Builds from `third_party/kuikly-ui`.
   - Uses Kotlin `2.1.21`.
   - Publishes `core`, `core-annotations`, `core-ksp`, `core-render-android`,
     and `compose`.

2. `publish-kuikly-ohos-klib`
   - Checks out the same Kuikly fork commit.
   - Uses Kotlin/OHOS `2.0.21-KBA-010`.
   - Publishes KMP and `ohosArm64` publications for `core`,
     `core-annotations`, and `compose`, plus the KSP/render publications that
     the app resolves.

3. `publish-kuikly-ohos-render`
   - Runs only after the two Kuikly Maven jobs pass.
   - Builds the OHOS render HAR from the same source ref.
   - Publishes it to the selected HarmonyOS mirror path.
   - Emits the package URL, SHA-256, source ref, and version as workflow output.

4. `publish-sqldelight-stack`
   - Publishes Stately first, SQLiter second, SQLDelight third.
   - Runs SQLDelight native-driver compile against the just-published remote
     Stately/SQLiter artifacts before publishing SQLDelight runtime/driver.

## Build Script Changes Needed

Add remote publishing support without removing the local workflow:

1. Extend `scripts/build_kuikly_fork.sh` or add
   `scripts/publish_kuikly_fork.sh`.
   - Keep local `publishToMavenLocal` behavior as the default developer path.
   - Add `--repository github` or `PUBLISH_REPOSITORY=github` for CI.
   - Add `KUIKLY_PUBLICATION_VERSION_ANDROID` and
     `KUIKLY_PUBLICATION_VERSION_OHOS` so the final coordinates can be
     `2.21.0-2.1.21-raft.2` and `2.21.0-2.0.21-ohos-raft.2`.

2. Add a shared Gradle repository convention for GitHub Packages.
   - Read credentials from `gpr.user`/`gpr.key` locally and
     `GITHUB_ACTOR`/`GITHUB_TOKEN` in Actions.
   - Never print tokens.
   - Keep Tencent mirror and Maven Central for public dependencies.

3. Add a CI-only consumer repository block in Slock build settings.
   - CI should not rely on `mavenLocal()`.
   - For local development, keep `mavenLocal()` available but make remote
     suffixed artifacts the normal documented path.

4. Replace OHOS local auto-publish in CI.
   - `settings.ohos.gradle.kts` can keep local auto-publish for developer
     machines.
   - In CI, set a flag such as `SLOCK_DISABLE_OHOS_LOCAL_AUTOPUBLISH=true` and
     fail if the remote fork artifacts are missing.

## Consumer Changes Needed

Kuikly:

- Update `compose/buildSrc/src/main/java/KotlinBuildVar.kt` to consume
  `2.21.0-2.1.21-raft.2` and `2.21.0-2.0.21-ohos-raft.2` after publish.
- Add a `dependencyInsight` check to verify those exact versions are resolved.
- Keep `OpenKuiklyIOSRender ~> 2.21.0` unchanged until iOS fork publishing is
  explicitly included in the same release family.

OHOS render:

- Replace `file:./libs/kuikly-render-staging2-652ba003.har` with a CI-fetchable
  artifact path or an OHPM/GitHub Packages dependency.
- Commit the lockfile change only after a clean `ohpm install --all` from the
  published artifact source.

SQLDelight:

- Add the selected GitHub Packages Maven repo to
  `compose/settings.ohos.gradle.kts`.
- Change CI to resolve `co.touchlab:*` and `app.cash.sqldelight:*` from remote
  packages instead of auto-publishing submodules to `mavenLocal()`.
- Keep `docs/ohos-sqldelight-fork.md` as the local bootstrap guide and add a
  remote-consumption section when the first package set is published.

## Validation Gate

Before declaring a published family usable by Slock CI:

1. Dependency resolution
   - `./gradlew dependencyInsight` proves Android resolves Kuikly
     `2.21.0-2.1.21-raft.2`.
   - `./gradlew -c settings.ohos.gradle.kts dependencyInsight` or an equivalent
     OHOS compile log proves OHOS resolves
     `2.21.0-2.0.21-ohos-raft.2`.
   - CI logs must not contain `publishToMavenLocal` as a fallback path.

2. Android build
   - From `compose/`: run the normal shared/app compile used by current tasks.

3. OHOS shared build
   - From `compose/`: run
     `./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64`.

4. OHOS app build
   - From `compose/ohosApp`: run `ohpm install --all`.
   - Run the DevEco bundled `hvigorw.js --sync`.
   - Assemble the HAP from the published HAR/package.

5. SQLDelight stack
   - Compile `third_party/sqldelight :drivers:native-driver:compileKotlinOhosArm64`
     against remote Stately and SQLiter, not local Maven publications.
   - Compile Slock shared OHOS after switching the plugin/runtime to remote
     SQLDelight packages.

6. Provenance report
   - Record source repo, source commit, package URL, version, artifact SHA-256,
     workflow run URL, and consumer commit.

## Open Questions

1. Which GitHub owner/repository should host Kuikly Maven packages?
   - Same repo is easiest with `GITHUB_TOKEN`.
   - Fork repo is cleaner provenance but may need explicit package access or a
     PAT for Slock CI consumption.

2. Which HarmonyOS mirror should host the OHOS render HAR/package?
   - If the mirror is not a package registry, use GitHub Releases/assets first.
   - If it supports OHPM-compatible packages, prove `ohpm install --all` can
     resolve it before switching the lockfile.

3. Should forked third-party artifacts keep upstream group IDs?
   - Keeping `com.tencent.kuikly-open`, `app.cash.sqldelight`, and `co.touchlab`
     minimizes consumer changes.
   - Moving to an `ai.slock` or `com.bytemain` group makes provenance explicit
     but requires more build rewiring.

4. Should the first SQLDelight GitHub Packages publish use the current
   `*-KBA-001` versions or new `*-KBA-001-raft.1` versions?
   - Current versions match the local stack.
   - Suffixed versions are safer for remote cache hygiene.

## Recommended First PR

For the first implementation branch after this spec:

1. Add GitHub Packages Maven repository support and credential conventions.
2. Add a manual `publish-fork-artifacts.yml` workflow with only
   `family=kuikly`.
3. Add publication-version override support for Kuikly so CI can publish
   Android `2.21.0-2.1.21-raft.2` and OHOS
   `2.21.0-2.0.21-ohos-raft.2`.
4. Publish to a temporary package namespace and verify dependency resolution in
   a throwaway consumer branch before changing Slock's committed pins.
5. Only after that, change `KotlinBuildVar.kt` and the OHOS render dependency
   in a separate consumer branch.

## First Kuikly Publish Implementation

The runnable publish path belongs in the Kuikly fork repo, not in Slock. The
initial local KuiklyUI branch is:

- Repo: `bytemain/KuiklyUI`
- Local branch: `slock/publish-kuikly-raft2`
- Local commit: `19a65e8e ci: publish Slock Kuikly fork artifacts`

That KuiklyUI branch contains:

- `scripts/publish_slock_fork_github.sh`
- `.github/workflows/publish-slock-fork.yml`

The script avoids patching `third_party/kuikly-ui` for the first remote publish.
Instead it generates a temporary Gradle init script that:

1. Adds a `GitHubPackages` Maven repository to every Kuikly subproject with the
   `maven-publish` plugin.
2. Overrides every `MavenPublication.version` with
   `SLOCK_KUIKLY_PUBLICATION_VERSION`.
3. Leaves the fork's existing local `mavenLocal()` helper path untouched.

This is necessary because the current fork build files only publish to
`mavenLocal()` when no username/password is provided, but their built-in remote
repository URL is empty. Passing `username`/`password` directly to the fork would
select that empty URL path. The init script adds a usable GitHub Packages
repository without modifying the fork.

Manual local dry-run example, from the KuiklyUI repo:

```bash
GITHUB_PACKAGES_OWNER=bytemain \
GITHUB_PACKAGES_REPOSITORY=KuiklyUI \
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<token-with-packages-write> \
SLOCK_PUBLISH_DRY_RUN=1 \
SLOCK_PUBLISH_FAMILY=all \
scripts/publish_slock_fork_github.sh
```

Real publish example:

```bash
GITHUB_PACKAGES_OWNER=bytemain \
GITHUB_PACKAGES_REPOSITORY=KuiklyUI \
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<token-with-packages-write> \
KUIKLY_FORK_REF=<expected-kuikly-commit-or-ref> \
KUIKLY_VERSION=2.21.0 \
KUIKLY_VERSION_SUFFIX=raft.2 \
SLOCK_PUBLISH_FAMILY=all \
scripts/publish_slock_fork_github.sh
```

The default remote coordinates are:

- Android/KMP: `2.21.0-2.1.21-raft.2`
- OHOS: `2.21.0-2.0.21-ohos-raft.2`

CI usage:

1. Push the KuiklyUI branch/commit to `bytemain/KuiklyUI`.
2. Start `.github/workflows/publish-slock-fork.yml` manually in KuiklyUI.
3. Set `dry_run=true` first.
4. Confirm the workflow resolves the intended Kuikly source ref and lists
   `publish...ToGitHubPackagesRepository` tasks.
5. Re-run with `dry_run=false` only after the target package namespace is
   confirmed and package write access is available.

Important prerequisite: the Kuikly fork ref used in CI must be reachable from
`https://github.com/bytemain/KuiklyUI.git`. Local-only submodule commits cannot
be published by GitHub Actions until they are pushed to a branch or tag in that
fork.
