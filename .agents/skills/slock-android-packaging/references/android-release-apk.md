# Android Release APK Packaging

Run commands from a clean throwaway worktree unless the user explicitly requests
otherwise. Replace placeholders before running commands.

## Source Worktree

Package from current local `main` by default:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
git switch main
git status --short
git rev-parse --short HEAD
git status --short --branch
```

Create a detached build worktree:

```bash
SOURCE_SHA="$(git rev-parse --short HEAD)"
WORKTREE="/Users/artin/AndroidStudioProjects/Slock-worktrees/cindy-artifact-task<TASK>-${SOURCE_SHA}"
git worktree add --detach "$WORKTREE" HEAD
cd "$WORKTREE"
```

If local `main` advances before the final report, remove this worktree and start
again from the new local-main commit.

## Submodules

No build dependency is a submodule any more (the OHOS SQLDelight fork moved to
Raft Artifacts in #3031 and its gitlink was removed). The only remaining
submodule is the `.agents/skills/kuikly-ui-framework/references/KuiklyUI`
reference, which packaging does not need. Confirm nothing is unexpectedly
initialized:

```bash
git submodule status
```

If a future change makes a submodule build-required, initialize it explicitly
and note that in the report.

The Slock TypeScript server/React source is **not** a Mobile submodule. If a
packaging-side visual or contract check needs it, use an external checkout:

```bash
export SLOCK_SOURCE_REPO=/path/to/botiverse/slock
test -d "$SLOCK_SOURCE_REPO/packages/server"
test -d "$SLOCK_SOURCE_REPO/packages/shared"
git -C "$SLOCK_SOURCE_REPO" rev-parse HEAD  # record this SHA with evidence
```

## Build

Run preflight and build from `compose/`:

```bash
git diff --check HEAD
cd compose
./gradlew :shared:generateSqlDelightInterface
./gradlew :androidApp:assembleRelease
```

The unsigned release APK is normally under:

```text
compose/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

Confirm the exact path after each build:

```bash
find androidApp/build/outputs/apk/release -type f -name '*.apk' -maxdepth 1 -print
```

## Signing

The app module currently defines release packaging but no Gradle signing config,
so release artifacts must be signed externally with the authorized local upload
keystore setup on the builder machine.

Do not post or commit keystore aliases, passwords, secret Gradle properties,
environment values, or `.env` contents. If authorized signing material is not
available, stop and ask the owner in the relevant private/authorized surface.

Find Android build-tools:

```bash
ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$(find "$ANDROID_SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort | tail -1)"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT="$BUILD_TOOLS/aapt"
```

Align before signing:

```bash
UNSIGNED="androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
ALIGNED="../slock-release-aligned-${SOURCE_SHA}.apk"
SIGNED="../slock-release-signed-$(date +%Y%m%d)-${SOURCE_SHA}.apk"
"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"
```

Then sign with the authorized local signing command for this machine. Keep
passwords out of shell history and public logs. After signing, verify the signed
APK, not the unsigned or merely aligned APK.

## Verification

Run these checks on the final signed APK:

```bash
"$ZIPALIGN" -c -p 4 "$SIGNED"
"$APKSIGNER" verify --verbose --print-certs "$SIGNED"
"$AAPT" dump badging "$SIGNED" | sed -n '1,8p'
shasum -a 256 "$SIGNED"
ls -lh "$SIGNED"
```

Expected metadata should currently include:

- package: `ai.slock.android`
- versionCode: `1`
- versionName: `1.0`
- targetSdkVersion: `36`
- signer certificate SHA-256:
  `b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45`
- signature schemes: v2/v3 verified, v1 not used

If any value differs, explain why before uploading.

## Upload

Upload the signed APK:

```bash
raft attachment upload "$SIGNED"
```

Attachment ids are scoped to a message send. If the owner asks for the same APK
in another channel such as #raft-mobile, upload again and use the new attachment
id in that message.

## Artifact Thread Report Template

```text
已完成 Android release APK 打包并上传。

- 文件：<filename>
- 附件：<attachment-id>
- 来源：local main <short-sha> (<commit subject>)
- APK SHA-256：<sha256>
- 大小：<size>
- 包名/版本：ai.slock.android / versionCode 1 / versionName 1.0 / targetSdk 36
- 签名：cert SHA-256 b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45；v2/v3 verified；v1 not used
- 验证：git diff --check HEAD；:shared:generateSqlDelightInterface；:androidApp:assembleRelease；zipalign；apksigner verify；aapt badging；shasum
- git 状态：artifact-only，无代码提交；使用 clean detached worktree <path>；local main=<short-sha>；remote main 未推送/未变更

按 #49 规则，我会在本线程异步请 @Codex-Kuikly-Perf 做 login、对话流、基础导航和明显性能/功能回归 smoke，授权来源仍使用 #artifacts msg 851c65cf，不在公开线程复述凭据。
```

Then send the async smoke request in the same artifact thread:

```text
@Codex-Kuikly-Perf 请按 #49 对这个 APK 做异步 smoke：login、对话流、基础导航、明显性能/功能回归。测试账号授权来源使用 #artifacts msg 851c65cf；不要在公开线程复述凭据。也请把本次 smoke 的可复用检查点沉淀回知识库/清单。
```
