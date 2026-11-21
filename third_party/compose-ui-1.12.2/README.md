# compose-ui 1.12.2-raft.1 (patched Compose Multiplatform UI for iOS)

`third_party/` holds patched builds of third-party dependencies that Raft apps ship with while an
upstream fix is not released. Each entry is upstream source at an exact commit, plus the patches in
its directory, built and verified by a manual workflow and published under a `-raft.N` version to
our patched-dependency Maven repository. Nothing here is built by normal CI.

## This entry

| | |
|---|---|
| Upstream | [JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core) tag `v1.12.2` = `5f017375d926c73f1937408601b6cb28c12a8812` (the source of Compose Multiplatform 1.12.2) |
| Rebuilt artifacts | `org.jetbrains.compose.ui:ui-iosarm64` and `org.jetbrains.compose.ui:ui-iossimulatorarm64`, version `1.12.2-raft.1` |
| Not rebuilt | everything else, including the root `org.jetbrains.compose.ui:ui` module: the app keeps the official 1.12.2 for all other artifacts and pins only these two platform artifacts |
| Patches | `0001-cmp-10674-skip-reentrant-flush.patch` (upstream PR [#3580](https://github.com/JetBrains/compose-multiplatform-core/pull/3580), unmerged; applies cleanly to the tag) |
| Workflow | `.github/workflows/compose-ui-patch.yml` (`workflow_dispatch` only; `publish` input, default `false`) |
| Repository secrets | `COMPOSE_PATCH_MAVEN_URL`, `COMPOSE_PATCH_MAVEN_USER` (any non-empty name), `COMPOSE_PATCH_MAVEN_TOKEN` (the password); read only by the publish step |

### Why

iOS crashes while typing: [CMP-10674](https://youtrack.jetbrains.com/issue/CMP-10674).
`FlushCoroutineDispatcher.flush()` can be entered again from a task that an outer `flush()` is
running (for example an IME commit while touch input is handled). The nested flush runs the queue
inside the running task, and the dispatcher's state breaks. PR #3580 tracks the nesting depth of
`performRun` and makes a nested `flush()` a no-op: the outer flush runs the pending tasks. It also
adds two tests to `FlushCoroutineDispatcherTest` (`reentrant_flush_does_not_run_tasks_twice`,
`reentrant_flush_does_not_run_tasks_inside_running_task`).

### Upstream review risk: test this on a device before shipping

m-sasha noted on #3580 that the change makes **any** `flush()` called from inside a running task a
no-op, not just the crashing path. Work that an IME commit queues during touch handling now runs
later, in the outer flush, instead of right away. Before you ship a build with this pin, check on a
real iPhone with the system keyboard and a CJK/predictive IME:

- the typed text is complete and in order (fast typing, autocorrect, dictation);
- the cursor is in the right place after you commit a candidate (marked text, then commit);
- the sent message has exactly the text shown in the field (type, then tap Send at once).

### When to remove

Remove this when upstream releases a stable Compose Multiplatform version with the fix (PR
[#3622](https://github.com/JetBrains/compose-multiplatform-core/pull/3622) or a successor) and the
app upgrades to it. Then delete the app's pin of the two artifacts, delete this directory and the
workflow, and stop publishing `1.12.2-raft.N`. `upload_maven_dir.sh` is also used by
`.github/workflows/compose-foundation-patch.yml`: while `third_party/compose-foundation-1.12.1`
exists, move the script there instead of deleting it.

## How the build works

The workflow runs on a hosted macOS arm64 runner, `macos-26` by default (upstream CI uses
`macos-26-xlarge`). It uses JDK 21 (Corretto) and Xcode 26.5, the same as upstream's
`setup-prerequisites` and `setup-xcode` actions at the tag. Kotlin is 2.3.20, from upstream's
`gradle/libs.versions.toml`. That matches the `compiler_version=2.3.20` (`abi_version=2.3.0`,
`metadata_version=1.4.1`) in the official `ui-iosarm64-1.12.2.klib`. Gradle 9.5.0 comes from the
upstream wrapper.

1. The workflow fetches the tag with `--depth 1`, checks the commit SHA, checks that Kotlin is
   2.3.20, and runs `git apply` on every `*.patch` here, in name order.
2. It runs the test:
   `./gradlew :compose:ui:ui:iosSimulatorArm64Test --tests androidx.compose.ui.platform.FlushCoroutineDispatcherTest -Pjetbrains.publication.version.COMPOSE=1.12.2`.
   Then it checks the JUnit XML: no failures, and both new reentrant tests ran.
3. It builds and publishes to the local repository:
   `./gradlew :compose:ui:ui:publishIosArm64PublicationToMavenRepository :compose:ui:ui:publishIosSimulatorArm64PublicationToMavenRepository -Pjetbrains.publication.version.COMPOSE=1.12.2`.
   - `MavenRepository` is the repository that upstream's buildSrc adds (`MavenUploadHelper`). It
     writes to `upstream/out/repository`.
   - `jetbrains.publication.version.COMPOSE` is how upstream sets the version of every compose
     project. If it's not set, the version is `9999.0.0-SNAPSHOT`.
   - These are the same per-target publish tasks that `publishComposeJb -Pcompose.platforms=ios`
     runs, minus the root `KotlinMultiplatform` publication and the other modules.
4. `reversion_artifact.py` copies the two artifacts to the staging directory as `1.12.2-raft.1`.
   It changes only file names, the .pom's own `<version>`, and the file URLs in the .module, then
   regenerates the checksums.
   - Version 1.12.2 is built first because setting the COMPOSE version to `1.12.2-raft.1` would
     also change the versions that `ui` writes for its project dependencies (`runtime`,
     `ui-graphics`, `ui-text` and others) to `1.12.2-raft.1`. Those artifacts don't exist.
   - This needs no change to upstream build files and no second patch.
5. Verification, run before anything is uploaded:
   - `compare_with_official.py` downloads the official 1.12.2 `.module`, `.pom` and `.klib` from
     Maven Central and requires identical values for: variants, attributes, dependencies,
     constraints, POM dependencies and dependencyManagement, and klib manifest fields
     (`compiler_version`, `abi_version`, `metadata_version`, `unique_name`, targets, `depends`).
   - `klib dump-abi` from the Kotlin/Native 2.3.20 distribution must give the same public ABI as
     the official klib.
6. The staged Maven tree is uploaded as the workflow artifact `compose-ui-1.12.2-raft.1-maven`.
   This happens on every run.
7. The upload runs only if `publish` is `true`, and only from the default branch:
   `upload_maven_dir.sh` uploads the staged tree with HTTP PUT. It refuses to overwrite a version
   that already exists, so the next build must use `-raft.2`.
   - The credentials go to that one step's `env:`, then into a 0600 netrc file that is deleted
     when the step ends.
   - Credentials never go on a command line, into `$GITHUB_ENV` or into `$GITHUB_OUTPUT`.

To make a new revision, add or change patches here and bump `PATCHED_VERSION` in the workflow.
