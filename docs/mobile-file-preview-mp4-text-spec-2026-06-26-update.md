# Mobile File Preview: MP4 and Text Spec — Update (2026-06-26)

Update to `docs/mobile-file-preview-mp4-text-spec-2026-06-24.md` (written by @KMP-专家).
Triggered by @artin's `@Pi-Worker1 来开始做这件事吧` (msg `08880d58`) plus the follow-up
"你不熟悉的东西你就去查，可以吗，查完回来更新 spec，然后重新列计划" (msg `988a6fdf`).

This update reflects what's actually in `cd76a396` (local main) and the surrounding code
paths, **not** the original spec's assumed-from-research state. Findings are concrete
(file paths, class names, registration points) so the implementation plan is grounded.

## What changed since the original spec

1. **Android host video adapter is greenfield, not "needs registration".**
   The original spec assumed `SlockKuiklyAdapters.init()` and
   `SlockKuiklyActivity.registerExternalRenderView()` were the only wiring points. In
   reality, the spec author @KMP-专家 was looking at the wrong init site: the actual
   registration path is the `KuiklyRenderAdapterManager.krVideoViewAdapter = ...` setter
   inside `SlockKuiklyAdapters` (alongside `krImageAdapter` / `krLogAdapter` / etc.),
   AND the adapter **class itself does not exist in main** as of `cd76a396`. Confirmed:

   ```
   $ git log --oneline -- 'compose/androidApp/src/main/java/build/raft/app/shared/SlockKRVideoViewAdapter.kt'
   fea59f89 Revert "merge task424 android video view adapter"

   $ rg krVideoViewAdapter compose/androidApp/src/main
   (no matches)
   ```

   The earlier `a78bf775` commit (`fix(android): register kuikly video view adapter`)
   added the file and the setter line, then `fea59f89` reverted both (01:28 → 01:32,
   2026-06-26). The pattern is in `@Android-App-Integrator`'s reverted worktree
   `Slock-worktrees/cindy-native-task114-kuikly-publish/compose/androidApp/.../SlockKRVideoViewAdapter.kt`
   and is a usable reference but **must not be cherry-picked verbatim** without review:
   it's authored by a different agent lane, was reverted for owner-unknown reasons, and
   uses platform `VideoView` (Option A from spec — same call as the spec recommends, but
   still need owner sign-off).

2. **OHOS host video adapter is genuinely missing.**
   `compose/ohosApp/entry/src/main/ets/kuikly/KuiklyViewDelegate.ets` registers only
   `SlockOhosHdsTabBarView`, `SlockLucideIconView`, plus `KRBridgeModule` /
   `SlockNetworkModule` / `SlockMMKVModule` modules. No `krVideoViewAdapter` setter, no
   `SlockKRVideoAdapter.ets`. Spec's §"OHOS Host Plan" is therefore correct: needs new
   `compose/ohosApp/entry/src/main/ets/kuikly/adapter/SlockKRVideoAdapter.ets` modeled on
   the Kuikly fork prototype at
   `third_party/kuikly-ui/ohosApp/entry/src/main/ets/kuikly/adapters/AppKRVideoAdapter.ets`.

3. **Shared DTO exists in skeleton form, needs full typed payload.**
   `compose/shared/src/commonMain/kotlin/build/raft/app/data/FilesMessagesApi.kt`
   already has:

   ```kotlin
   internal interface SlockFilesMessagesApi {
       ...
       suspend fun attachmentPreview(attachmentId: AttachmentId): HomeNetworkResponse
   }

   @Serializable
   private data class AttachmentPreviewResponseDto(
       val status: String,
       val data: AttachmentPreviewDataDto? = null,
       val truncated: Boolean? = null,
   )

   @Serializable
   private data class AttachmentPreviewDataDto(val kind: String? = null)

   internal data class AttachmentPreviewSummary(val status: String, val kind: String?, val truncated: Boolean)
   ```

   The DTO is wired all the way through `SlockFilesMessagesApiClient.attachmentPreview()`
   and is consumed as `AttachmentPreviewSummary` (3 fields only). The shared package's
   canonical types are at
   `third_party/slock-source/packages/shared/src/attachmentPreview.ts`:

   ```ts
   export interface TextAttachmentPreviewData { kind: "text"; text: string; }
   export interface MarkdownAttachmentPreviewData { kind: "markdown"; markdown: string; }
   export interface DiffAttachmentPreviewData { kind: "diff"; stats: {...}; }
   export interface CsvAttachmentPreviewData { kind: "csv"; delimiter, headers, rows, rowCount, columnCount; }
   export interface PdfAttachmentPreviewData { kind: "pdf"; }
   export type AttachmentPreviewData = Diff | Csv | Markdown | Pdf | Text;
   export type AttachmentPreviewResponse = {status: "ok"; data; truncated?} | {status: "unsupported"};
   ```

   To consume the full payload, the KMP DTO has to grow a sealed `AttachmentPreviewData`
   type mirroring the TS discriminated union. Currently `AttachmentPreviewDataDto` only
   carries `kind`, so any kind-specific field (text body, markdown body, csv rows) is
   silently dropped on parse.

4. **Server preview provider whitelist is narrower than the spec assumed.**
   `third_party/slock-source/packages/server/src/services/attachmentPreviews/providers/text.ts`
   `TEXT_MIME_TYPES = {"text/plain"}` and only `.txt` / `.text` extensions. Even though
   spec §"Server text provider expansion" recommends expanding this, the server package
   lives in `third_party/slock-source/` — a separate source repo we don't control
   in-flow from the mobile repo. Any expansion to the server whitelist therefore needs
   a separate change against the server source (out of scope of this mobile task, but
   the mobile-side contract change must not depend on it being merged first).

5. **Shared text preview currently bypasses the server preview contract.**
   `compose/shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt`
   `loadThreadTextAttachmentPreview()` and `loadThreadMarkdownAttachmentPreview()`
   both:

   - call `threadApi.attachmentUrl(attachmentId, disposition = "inline")` to get a signed URL,
   - then `networkModule.request()` to read the body directly,
   - truncate locally with `SlockFilePreviewTextMaxChars` / `SlockFilePreviewMarkdownMaxChars`.

   This is the path the original spec §"Goals" #2 wants retired in favor of
   `/attachments/:id/preview`. The migration plan must keep both paths working until
   server expansion lands, otherwise some MIME types (e.g. `.json`, `.xml`) will lose
   preview until server catches up.

6. **Local main is dirty (not my lane).**
   `cd76a396` working tree has uncommitted changes to
   `SharedInboxPanel.kt` / `SharedHomeStore.kt` / `SlockKuiklyActivity.kt` /
   `SharedHomeStoreTest.kt` / `slock_android_adb.py`. Per server rule `0051b57a`,
   don't touch dirty main — implement in a worktree.

## Corrected architecture (concrete)

### Shared KMP (`compose/shared/src/commonMain`)

Files to read for context before editing:

- `data/FilesMessagesApi.kt` — extend `AttachmentPreviewDataDto` into a sealed
  discriminated union mirroring `packages/shared/src/attachmentPreview.ts`.
- `data/FilesMessagesApiClient.kt` — change the `attachmentPreview` return type from
  `AttachmentPreviewSummary` to a typed `AttachmentPreviewData` (with `status` +
  `truncated` wrapped). The `mapFilesBody` helper can stay; only the `transform`
  lambda needs to widen.
- `SlockFilePreview.kt` — add `isMp4VideoAttachment()`, `isMp4Attachment`,
  update `loadThreadAttachmentPreview()` to branch into a new `loadThreadVideoAttachmentPreview()`
  that fetches `attachmentUrl(..., disposition = "inline")` for MP4 and sets
  `kind = "video"`, `videoUrl = ...`. Add `SlockFilePreviewVideoPanel` composable.
- Add a `SlockFilePreviewVideoPayload` (or extend `SlockFilePreviewPayload` with
  `videoUrl: String? = null`) — **never in `pageData`**, only in page state, per
  route boundary.
- `commonTest/.../SlockFilePreviewDownloadStatusTest.kt` and a new test file for
  classifier behavior (`.mp4` → true; `.webm` → false; `video/mp4` → true;
  `video/webm` → false).

### Android host (`compose/androidApp/src/main`)

Files to read for context before editing:

- `java/build/raft/app/shared/SlockKuiklyAdapters.kt` (or wherever the
  `KuiklyRenderAdapterManager` block lives in main — search for `krImageAdapter =`
  to find the exact file; was `SlockKuiklyActivity.kt:965` in the reverted `a78bf775`
  but I need to confirm the post-revert location before adding `krVideoViewAdapter`).
- Reference only (NOT cherry-pick): the reverted worktree's
  `SlockKRVideoViewAdapter.kt` (~350 lines, platform `VideoView`, Option A — matches
  spec recommendation).

### OHOS host (`compose/ohosApp/entry/src/main/ets/kuikly`)

Files to read for context before editing:

- `KuiklyViewDelegate.ets` — add `getCustomRenderViewCreatorRegisterMap()` entry that
  registers the new `SlockKRVideoAdapter`, **or** add an explicit
  `KuiklyRenderAdapterManager.krVideoViewAdapter = ...` in startup. The fork's OHOS
  adapter protocol (`IKRVideoViewAdapter`) is a `createController()` +
  `createVideoView(ctx, view): ComponentContent` pair — different shape from
  Android's `createVideoView(context, src, listener)` — so the registration point
  on OHOS is also different. Need to verify in the fork's
  `core-render-ohos/.../components/KRVideoView.ets` where it consumes the
  `KuiklyRenderAdapterManager.krVideoViewAdapter` field. (Earlier `grep` showed
  `third_party/kuikly-ui/.../g/q.d.ets:24: static krVideoViewAdapter: IKRVideoViewAdapter | null;`
  — confirming the OHOS adapter manager exposes this field, but I haven't traced
  where `KRVideoView.ets` reads from it. That's a follow-up grep before coding.)
- Reference only (NOT cherry-pick): Kuikly fork prototype
  `third_party/kuikly-ui/ohosApp/entry/src/main/ets/kuikly/adapters/AppKRVideoAdapter.ets`
  — uses ArkUI `Video` + `VideoController`, wraps `ComponentContent`, emits
  `KRVideoPlayState` events.

### Server (`third_party/slock-source/packages/server`)

Out of direct mobile-repo scope. Mobile contract must be designed so the existing
narrow whitelist (`text/plain` + `.txt`/`.text` + `.md`/`.markdown`) keeps working.
For the broader whitelist (json / xml / yaml / toml / sql / py / go / rs / kt /
svg / etc.) the existing shared `slockFilePreviewIsTextLikeAttachment()` already
classifies those locally — the mobile side can keep the direct-fetch path for them
as a fallback until the server preview provider expands. Concretely:

- If `kind = "text"` / `"markdown"` / `"csv"` / `"diff"` from `/attachments/:id/preview`
  with `status = "ok"` → use that body (typed DTO).
- If server returns `status = "unsupported"` **and** the local
  `slockFilePreviewIsTextLikeAttachment(fileName, mimeType)` returns true → fall
  back to the existing direct-fetch path (don't regress).
- If neither matches → `kind = "unsupported"`, show "Download / Open with".

## Suggested order (worktree-per-task)

1. **Worktree A — spec clarification (this doc)**: `piw/task332-spec-update`. Update
   the original spec with these findings and the corrected plan. Owner review.
2. **Worktree B — shared DTO + classification (was #421)**: build on this spec, do
   the KMP DTO widening + `isMp4VideoAttachment()` + tests. Cherry-pick to local main
   only after `./gradlew :shared:compileKotlinMetadata` + commonTest passes.
3. **Worktree C — shared FilePreview video panel + markdown/text truncation (was #422)**:
   branch from B's cherry-pick, add `SlockFilePreviewVideoPanel` and tighten
   markdown/text truncation copy to use server `truncated` flag when available.
4. **Worktree D — Android host video adapter (was #424)**: branch from B (after
   shared has a `kind = "video"` path), write `SlockKRVideoViewAdapter.kt` +
   register `krVideoViewAdapter = ...` in the correct `KuiklyRenderAdapterManager`
   block, validate on device with a real MP4 attachment.
5. **Worktree E — OHOS host video adapter (was #425)**: branch from D's cherry-pick,
   add `SlockKRVideoAdapter.ets` modeled on the Kuikly fork prototype, register
   in `KuiklyViewDelegate.ets`. Validate on device with the same MP4 attachment.
   Pre-step: confirm `KRVideoView.ets` consumer of `krVideoViewAdapter` field.
6. **Worktree F — server text provider expansion (was #423)**: separate repo,
   separate change. Not blocking mobile lanes A–E.

## Open questions for owner before I start coding

1. **Is the reverted `SlockKRVideoViewAdapter.kt` from `a78bf775` acceptable as a
   starting point?** It implements Option A from spec exactly, but it was reverted —
   I don't know why. If you want a different adapter design (Media3 ExoPlayer per
   Option B, or a custom MediaPlayer wrapper), tell me before I write Worktree D.
2. **Is `kind = "video"` stored in `SlockFilePreviewPayload` (and therefore in
   `pageData` via JSON), or only in page state?** Spec §"Route and Cache Boundary"
   says pageData is identity-only; signed video URL must not go in pageData. The
   kind string itself is fine, but `videoUrl` must be page-state only. Confirm.
3. **For #423 server whitelist expansion — is that an in-scope-for-me change against
   `third_party/slock-source/`?** I assumed no (separate repo), but if owner wants
   it in this lane, say so and I'll branch that repo too.
4. **OHOS validation device**: do you have an OHOS device / emulator configured, or
   should #425 ship as code-only and the @QA-Tester lane do on-device verification?

## Validation

For each worktree, before cherry-pick to local main:

- `./gradlew :shared:compileKotlinMetadata :shared:compileDebugKotlinAndroid
   :shared:compileDebugUnitTestKotlinAndroid` passes (where applicable).
- commonTest passes for new classifiers and DTO parsing.
- For host adapters: device run with a real MP4 attachment in `#all` thread, play /
  pause / seek works, back-press releases player, no `Kuikly render error` /
  `videoPlayStateDidChanged` fatal in logcat / hilog.

Out of scope for this round (no change unless owner redirects):

- WebM on mobile.
- HTML-as-live-WebView preview.
- Upload-time video transcoding / thumbnails.
- CSV / diff full renderers in KMP (Phase 1: keep current thread chip summary).