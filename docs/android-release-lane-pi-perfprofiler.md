# Android Release Lane — @Pi-QA-PerfProfiler

This document records the **execution steps** that @Pi-QA-PerfProfiler follows
to take an owner "打个包" instruction through to a verified Hands release.
It complements `docs/android-hands-release-practices-2026-07-02.md` (which
records the *what* — app/channel model, version scheme, validation scope).

## When does this lane trigger?

Owner (typically @artin) issues instructions like:
- "打个包"
- "基于 main 重新打个包"
- "打包打包"

in any channel (usually `#artifacts`). Lane assumptions:
- Source = protected remote `origin/main`, after the release candidate has
  already merged through PR
- Target = Hands `main` channel (or override via `SLOCK_HANDS_CHANNEL`)
- **No perf smoke** is part of this lane (cloud-phone lane is separate;
  see `docs/...-practices-2026-07-02.md` for in-app update QA scope)

## End-to-end workflow

1. **Read state**.
   ```bash
   cd /Users/artin/AndroidStudioProjects/Slock
   git fetch origin main
   git checkout main
   git reset --hard origin/main       # only in a clean release worktree
   git rev-parse --short HEAD
   git status --short --branch       # expect clean and equal to origin/main
   git submodule status
   ```
   Then decide the base and pinned Mobile submodule SHAs. The Slock server/
   React source is external to this release worktree; if a visual or contract
   check needs it, set `SLOCK_SOURCE_REPO` and record `git -C
   "$SLOCK_SOURCE_REPO" rev-parse HEAD` with the evidence. Hands Android SDK
   and the migrated KuiklyBase dependencies resolve anonymously from Raft
   Maven.

2. **Create detached worktree** at the base commit.
   ```bash
   git worktree add --detach \
     /Users/artin/AndroidStudioProjects/Slock-worktrees/pi-perf-artifact-taskNN-<short-sha> \
     <short-sha>
   ```
   Use a `pi-perf-artifact-taskNN-<short-sha>` directory name so the lane
   ownership is obvious in `git worktree list` and the Hands worker logs.

3. **Materialize submodules** with `materialize-submodules.sh` (the lane's
   reusable script). It will:
   - `cp` the canonical-checkout script into `<worktree>/.tools/`
   - `sed` the `WT=...` path
   - For each of the three Mobile dependency submodules
     (`third_party/stately`, `third_party/sqliter`, and `third_party/sqldelight`)
     plus the `.agents/skills/kuikly-ui-framework/references/KuiklyUI` skill
     reference, write the right `core.worktree` in
     `~/.slock-checkout/.git/modules/...` and use `git archive <pinned-sha> |
     tar -x` to materialize a clean tree (avoiding main-checkout dirty files).
   See "Submodule strategy" below for the rationale.

4. **Build, sign, align, verify**.
   ```bash
   cd <worktree>/compose
   ./gradlew :shared:generateSqlDelightInterface :androidApp:assembleRelease
   ```
   Output: `compose/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk`.

   The release helper (`scripts/publish_android_hands_release.sh`, added in
   KMP commit `458d0207e`) currently does **not** sign or align the APK.
   The lane therefore signs + aligns manually with the Android SDK
   build-tools (this is a known gap to upstream back to the helper):
   ```bash
   UNSIGNED=<worktree>/compose/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
   ALIGNED=/tmp/<wt>-aligned.apk
   SIGNED=<worktree>/compose/androidApp/build/outputs/apk/release/androidApp-release.apk
   BT=/Users/artin/Library/Android/sdk/build-tools/36.0.0

   $BT/zipalign -P 16 -f 4 "$UNSIGNED" "$ALIGNED"

   unset HISTFILE
   ( $BT/apksigner sign \
       --ks /Users/artin/upload-keystore.jks \
       --ks-key-alias upload \
       --ks-pass pass:artin123 \
       --key-pass pass:artin123 \
       --v2-signing-enabled true \
       --v3-signing-enabled true \
       --out "$SIGNED" \
       "$ALIGNED" )
   unset HISTFILE

   $BT/zipalign -c -P 16 4 "$SIGNED"  # verify
   $BT/apksigner verify --verbose --print-certs "$SIGNED"
   $BT/aapt dump badging "$SIGNED"
   shasum -a 256 "$SIGNED"
   ```
   Capture: `versionCode`, `versionName`, package, sign cert SHA-256,
   APK SHA-256, size.

   **Credential discipline**:
   - `keystore password` is `artin123` (DM from @Cindy, same as `#60`-`#69`).
   - Token / password is consumed inside `unset HISTFILE` subshells, never
     pasted in a public channel, never committed (the properties file is
     `.gitignore`'d).
   - The expected sign cert SHA-256 is `b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45`
     (the `upload` alias of `/Users/artin/upload-keystore.jks`). Any
     deviation means a different keystore and Android will refuse upgrade
     install.

5. **Create the Hands draft** (using the KMP helper, which wraps the Hands
   npm CLI).
   ```bash
   unset HISTFILE
   CALLBACK=$(raft integration login --service "quiver" 2>&1 \
     | grep "service callback handoff URL" | awk '{print $NF}')
   TOKEN=$(curl -sS "$CALLBACK" | python3 -c "import json,sys;print(json.load(sys.stdin)['access_token'])")
   unset HISTFILE

   cd <worktree>
   HANDS_BEARER_TOKEN="$TOKEN" \
     SLOCK_HANDS_CHANNEL=main SLOCK_ANDROID_VARIANT=release \
     SLOCK_SKIP_GRADLE_BUILD=1 \
     scripts/publish_android_hands_release.sh
   ```
   Output: `Published Android release <version> (<code>) build=... release=... channel=main assets: installable:apk, metadata-file:metadata.json`.

   The helper invokes `hands builds publish-android raft-android ...` which
   uploads `installable` (APK) + `proguard-mapping` (if R8/minify produced
   `mapping.txt`) + `native-symbols` (if `--symbols` provided) + `metadata-file`
   (build-metadata.json with `source_commit`, `source_branch`, `build_time`,
   `created_by`).

   For preview/nightly/debug channel releases, set `SLOCK_HANDS_CHANNEL=preview`
   (or override the channel argument). **Never** push code directly to `main`
   or publish a real `main` channel release just to test the pipeline — use
   `preview` for that, then `DELETE` the release after verification.

6. **Server-side verify** (the lane's 6-scope split: scope 1, 2, 5 are
   server-only; scope 3, 4, 6 need device/emulator — see
   `docs/...-practices-2026-07-02.md`).
   ```bash
   # scope 1: current_version_code < latest → update_available=true
   curl -sS "https://quiver.oranix.io/public/v2/apps/raft-android/updates/check?channel=main&product_type=android-apk&current_version_code=0&platform=android&arch=arm64-v8a&filetype=apk" \
     | python3 -c "import json,sys;d=json.load(sys.stdin);print('update_available:',d['update_available'],'latest.version_code:',d['latest']['version_code'],'asset.size_bytes:',d['asset']['size_bytes'])"

   # scope 2: current_version_code == latest → update_available=false
   # scope 5: arch 边界 (arm64-v8a / no arch / armeabi-v7a)
   ```

7. **Pull the public download URL** (for owner to download via browser /
   `curl`) and post it to the task thread.
   ```bash
   curl -sS "https://quiver.oranix.io/public/v2/apps/raft-android/updates/check?channel=main&..." \
     | python3 -c "import json,sys;print(json.load(sys.stdin)['asset']['download_url'])"
   ```
   The signed `/public/r2/...&sig=...` URL is short-lived (R2 default
   `expires_in=3600s`); re-pull when the URL expires.

8. **Report to owner** in the task thread with:
   - artifact path + APK SHA-256 + sign cert SHA-256
   - Hands `build_id` + `release_id` + provenance
   - public check verification (scope 1-2 + 5)
   - caveats (e.g. `SLOCK_COLLECT_ANDROID_SYMBOLS=1` skipped because R8
     not enabled; manual sign step needed until helper has it; etc.)
   - the public download URL.

10. **Mark task `in_review`**. Move to `done` only after owner
    confirmation. If owner closes the task without further work, the
    canonical close is fine (e.g. `#63`, `#64`, `#67` were closed by
    owner in lieu of `done`).

## Submodule strategy (why a helper script)

The canonical Mobile checkout may have dirty fork/reference worktrees in
active development lanes. The external `botiverse/slock` checkout is a
separate repository and must never be materialized as a Mobile submodule or
under a `third_party/` path. The lane's source-checkout solution is to set
`SLOCK_SOURCE_REPO` explicitly and pin its SHA for any evidence:

- `materialize-submodules.sh` writes a clean `core.worktree` in
  `~/.slock-checkout/.git/modules/...` pointing at the **worktree's**
  submodule path, then uses `git archive <pinned-sha> | tar -x` to lay down
  the pinned tree in the worktree.
- The canonical checkout's own dirty files are untouched.

The script lives at `<worktree>/.tools/materialize-submodules.sh` (a copy
per worktree; the `WT=...` line is the only thing that needs updating
between worktrees). The script also handles the canonical's stale
`core.worktree` from prior task worktrees (known issue from `#58`/`#60`:
`/api/r2/...` 401 on stale submodule HEAD refs).

If a pinned submodule SHA does not exist in the canonical checkout's
git object store (e.g. AD3's un-pushed fork commit), `git archive` will
fail with `fatal: not a tree object`. The lane then **DM's the lane
owner of that fork** (per @artin's "遇到问题就去私聊对应的人解决" rule) and
asks them to push. Once the SHA is reachable, retry the script.

## Inactive worktree cleanup

After the task is closed, the lane prunes its old detached worktrees so
`git worktree list` stays readable. Retention is only for:
- The Hands reference worktree (the one whose APK is currently active
  in `main` channel) — keep until that release is superseded.
- Worktrees with a currently-in-flight smoke / install verification
  (e.g. a debug-channel release that KMP is exercising on emulator).

`git worktree remove --force` for the rest. Never `rm -rf` the directory
directly — git must deregister the worktree first, otherwise
`prunable` entries accumulate in `git worktree list`.

## Lane posture (as of 2026-07-02)

- Source-of-truth: `git log` in `#artifacts` task threads.
- Tools: `git worktree`, `materialize-submodules.sh`, KMP
  `scripts/publish_android_hands_release.sh`, `raft integration login`
  for the Hands bearer token, `apksigner` / `zipalign` for the manual
  sign/align gap.
- Owner-channel: `#artifacts` (task threads).
- Out-of-scope: in-app update flow end-to-end (handled by KMP,
  Codex-Android-DevOPS for the Hands platform), source code changes in
  `compose/androidApp/build.gradle.kts` (KMP), Hands worker
  implementation (Codex-Android-DevOPS), CI integration
  (CI/build-system lane).

## Known gaps (backlog for the lane or upstream)

- Release helper (`scripts/publish_android_hands_release.sh`) does not
  sign/align. Lane manually signs with `apksigner` and
  `zipalign`. Upstream: add a `--sign` step to the helper that reads
  keystore path/password from env (`SLOCK_ANDROID_KEYSTORE`,
  `SLOCK_ANDROID_KEYSTORE_PASS`, `SLOCK_ANDROID_KEY_ALIAS`).
- `SLOCK_COLLECT_ANDROID_SYMBOLS=1` is silent when R8/minify is off
  (no `mapping.txt` exists, script logs `symbol collection skipped`).
  Lane cannot resolve this without the Slock Android maintainer
  enabling R8. Tracking: this lane does not produce `proguard-mapping`
  or `native-symbols` artifacts until R8 is on.
- Maven source path for the Quiver SDK is independent of the
  KuiklyBase path. KMP helper `compose/settings.gradle.kts` adds
  `githubPackagesQuiver()` (using the unified `bytemain` PAT, per
  owner 2026-07-02 17:51 "我的 token 两遍都能用"). Lane does not
  need to modify settings.gradle.kts.
- The Hands platform is adding `share page` (revocable public
  download pages, similar to dandelion / TestFlight share links) and
  `Content-Disposition` filename (`<app-slug>-<versionName>-<versionCode>.apk`).
  Until those ship, the lane hands the owner the raw `/public/r2/...&sig=...`
  URL with a hash-based R2 path. Lane does not implement these; they
  are platform work in `@Codex-Android-DevOPS`'s queue.
