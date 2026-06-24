# Mobile File Preview: MP4 and Text Spec

## Context

Task #332 asks for a research/implementation spec for MP4 and common text previews, with HarmonyOS considered from the start.

Current state:

- React already treats supported video attachments as previews, not downloads. It classifies `.mp4` / `.webm` and `video/mp4` / `video/webm`, then requests `/attachments/:id/url?disposition=inline` and renders a `<video controls playsInline preload="metadata">`.
- The server already allows inline disposition for `application/pdf`, previewable images, and video MIME types. `isVideoPreviewAttachmentMimeType()` currently returns true for `video/mp4` and `video/webm`.
- The server `/attachments/:id/preview` path is data-only and currently supports diff, csv, markdown, pdf marker, and text. Existing provider caps are intentionally small: text 96 KiB stream / 128 KiB payload, markdown 128 KiB stream / 64 KiB payload, csv 128 KiB stream / 32 KiB payload, diff 256 KiB stream / 8 KiB payload.
- KMP `SlockFilePreview` already has a standalone lazy preview route. Thread attachments pass identity + file metadata, then the page fetches preview content. That pattern should be kept; do not pass MP4 URLs or text file bodies through the route payload.
- KMP currently fetches markdown/text attachment bytes directly from signed inline URLs for the standalone page. That works but bypasses the server preview provider contract and has a broader mobile-only text whitelist than React/server.
- Kuikly already provides `com.tencent.kuikly.core.views.Video` and platform render views named `KRVideoView`. Android and OHOS render implementations exist in the Kuikly fork, but Slock does not currently register a video adapter.
- Slock OHOS currently registers custom views/modules in `compose/ohosApp/entry/src/main/ets/kuikly/KuiklyViewDelegate.ets` and sets log/router adapters in `EntryAbility.ets`; it does not set `KuiklyRenderAdapterManager.krVideoViewAdapter`.
- Slock Android currently initializes image/log/font/router/thread adapters in `SlockKuiklyAdapters.init()` and custom render views in `SlockKuiklyActivity.registerExternalRenderView()`; it does not set `krVideoViewAdapter`.

External API notes:

- Huawei HarmonyOS documents an ArkUI `Video` component for video playback and control:
  <https://developer.huawei.com/consumer/en/doc/harmonyos-guides/arkts-common-components-video-player-V14>
- The OpenHarmony ArkUI Video reference says the `Video` component is used to play video and control playback and is supported since API version 7:
  <https://gitee.com/openharmony/docs/raw/591a2594e8fc35b600f326819744ee9d9be9f805/en/application-dev/reference/arkui-ts/ts-media-components-video.md>
- Huawei also exposes lower-level AVPlayer APIs, but Slock does not need AVPlayer for phase 1 because Kuikly's OHOS adapter already wraps ArkUI `Video`.

## Goals

1. MP4 attachments open in the standalone KMP file preview page on Android and OHOS.
2. Common text/source attachments open in the standalone KMP file preview page through the server preview contract, not by downloading unbounded file content in mobile.
3. Existing image, markdown, html-as-text, no-preview, download/open-with, and comment affordances keep working.
4. Route/pageData remains identity-first: attachment id, source, name, MIME, size. Fetched preview content stays in page state or repository response only.

## Non-Goals

- Do not implement WebM on OHOS in the first cross-platform slice. React supports WebM, but HarmonyOS codec/runtime coverage must be verified separately. Keep WebM as download/open-with on mobile unless both Android and OHOS validation passes.
- Do not render HTML as live HTML in Kuikly. HTML remains escaped text/source preview on mobile unless a sandboxed WebView preview is specified separately.
- Do not add upload-time video transcoding, thumbnails, or server-side video processing.
- Do not add arbitrary binary sniffing in mobile. Previewability is decided by filename/MIME plus server provider response.

## Proposed Contract

### Attachment classification

Shared KMP should add:

- `isMp4VideoAttachment()`: true for `.mp4` or normalized `video/mp4`.
- `isMobileDocumentPreviewAttachment()`: true for server-supported document/text preview families.

Do not use `normalizedMimeType().startsWith("video/")` for preview affordance on mobile. That makes unsupported formats look previewable. Use MP4 only for phase 1.

### Server text provider expansion

React/server text preview currently only accepts `.txt`, `.text`, and `text/plain`. KMP already has a broader source/text list. To make mobile and React converge, expand the server text provider instead of keeping a mobile-only direct-download path.

Recommended server additions:

- MIME: `application/json`, `application/ld+json`, `application/xml`, `application/x-ndjson`, `application/yaml`, `application/x-yaml`, `application/toml`, `application/sql`, `application/javascript`, `application/typescript`, `application/x-sh`, `image/svg+xml`, and `text/*` except explicitly unsafe rendered types.
- Extensions: `json`, `jsonl`, `xml`, `yaml`, `yml`, `toml`, `ini`, `cfg`, `conf`, `env`, `log`, `sql`, `js`, `ts`, `jsx`, `tsx`, `css`, `scss`, `less`, `sh`, `bash`, `zsh`, `py`, `rb`, `go`, `rs`, `kt`, `kts`, `java`, `swift`, `c`, `cc`, `cpp`, `h`, `hpp`, `cs`, `php`, `svg`.
- UTF-8 strict decode for direct text provider, matching current `TextDecoder(... fatal: true)`. Unknown/binary data returns unsupported.
- Keep existing byte/payload caps. Large files should render a truncated preview only when the provider can safely build one; otherwise show no-preview with Download.

KMP then consumes `/attachments/:id/preview` for text-like/document previews. It should no longer fetch signed inline URLs and read full bodies itself for text/markdown.

### Preview DTO

KMP needs a real `AttachmentPreviewData` DTO, not only `AttachmentPreviewSummary`.

Add typed fields equivalent to shared web:

- `status: ok|unsupported`
- `data.kind: diff|csv|markdown|pdf|text`
- data payload fields by kind
- `truncated: Boolean`

Use the existing `SlockFilesMessagesApi.attachmentPreview()` path or expose the same typed method through the thread preview data source. The FilePreview page should only care about `attachmentId` and the returned preview DTO.

## KMP UI Plan

### Text / document preview

For `kind=text`:

- Render with `SlockFilePreviewTextPanel`.
- Preserve literal text; no markdown rendering.
- Show a small "Preview truncated" line when `truncated=true`.

For `kind=markdown`:

- Render with existing `SlockFilePreviewMarkdownPanel`.
- Show truncated copy when `truncated=true`.

For `kind=csv`:

- Phase 1 can render as a simple monospaced/table-like text panel with headers + rows. If this is too much UI work, mark CSV as "preview available soon" and keep Download until a focused CSV surface task.

For `kind=diff`:

- Phase 1 can show stats text, or keep Thread chip summary only and Download from standalone. Full diff rendering is separate.

For `kind=pdf`:

- Keep external/open-with or existing document preview path unless a PDF renderer task exists. MP4/text should not be blocked on PDF.

### MP4 preview

Add `kind=video`.

`loadThreadAttachmentPreview()` branch:

1. If MP4, request `threadApi.attachmentUrl(attachmentId, disposition = "inline")`.
2. Store returned signed URL in page state as `videoUrl`.
3. Render `SlockFilePreviewVideoPanel` using Kuikly `Video`.
4. Keep "Open with..." / "Download" as fallback.

`SlockFilePreviewVideoPanel`:

- Full available width, aspect-ratio 16:9 when unconstrained, max height bounded by viewport minus header/composer space.
- Black preview surface with a white/yellow brutal border consistent with image preview shell.
- Initial state: show play button overlay and filename until first frame.
- Controls: play/pause, progress time text from `playTimeDidChanged`, retry on error. Native controls are acceptable if platform adapter exposes them, but shared overlay keeps Android/OHOS consistent.
- `resizeModeToContain()` to avoid cropping.
- Pause/stop when leaving the page. Kuikly `KRVideoView.onDestroy()` already calls stop on Android; OHOS adapter should release in `aboutToDisappear()`.

## Android Host Plan

Option A, minimum dependency:

- Implement `SlockAndroidVideoViewAdapter : IKRVideoViewAdapter` using Android platform `android.widget.VideoView`.
- Register it in `SlockKuiklyAdapters.init()` as `KuiklyRenderAdapterManager.krVideoViewAdapter`.
- Use signed inline URL directly; no auth headers required because the token is in the URL.
- Implement play/pause/stop/seek/muted best-effort. Emit first-frame/state callbacks through `MediaPlayer` listeners.

Option B, stronger player:

- Add AndroidX Media3 `media3-exoplayer` + `media3-ui`, implement the same adapter around `PlayerView`.
- Prefer this if platform `VideoView` fails progressive MP4, seek, or buffering validation on the test device.

Recommendation: start with Option A for the first MP4-only slice because it avoids new dependencies. Switch to Media3 only if validation proves platform `VideoView` is insufficient.

## OHOS Host Plan

Use Kuikly's existing OHOS adapter shape from the fork demo, adapted into Slock:

- Add `compose/ohosApp/entry/src/main/ets/kuikly/adapters/SlockKRVideoAdapter.ets`.
- Implement `IKRVideoViewAdapter` using ArkUI `Video`, `VideoController`, and `ImageFit`.
- Support `setSrc`, `play`, `pause`, `stop`, `setVideoContentMode`, `setMuted`, `setRate`, and `seekToTime`.
- Emit `onPlayStateDidChanged`, `onFirstFrameDidDisplay`, and `onPlayTimeDidChanged`.
- Register `KuiklyRenderAdapterManager.krVideoViewAdapter = new SlockKRVideoViewAdapter()` from OHOS startup, alongside log/router adapter setup.
- Use remote signed HTTPS inline URL in phase 1. If ArkUI `Video` cannot play the signed URL reliably, add a bridge method that downloads to app cache and returns a `file://`/sandbox URI, reusing the current `openAttachment` cache code path but without launching an external ability.

Why this is viable:

- Kuikly OHOS `KRVideoView` already delegates to `krVideoViewAdapter` and creates an ArkUI `ComponentContent`.
- Huawei/OpenHarmony expose an ArkUI `Video` component suitable for playback/control, so Slock can use the platform component rather than a custom renderer.

## Route and Cache Boundary

Allowed in route payload:

- `source=thread_attachment`
- `attachmentId`
- `name`
- `externalFileName`
- `externalMimeType`
- `meta`

Not allowed in route payload:

- signed preview URL
- text file contents
- CSV rows
- video duration/progress

The FilePreview page owns fetched preview state. Re-opening can refetch because signed URLs expire and text previews are cacheable server-side.

## Suggested Implementation Tasks

1. Shared classification + preview DTO:
   - MP4-only mobile video classifier.
   - Full attachment preview DTO parser.
   - FilePreview page consumes `/attachments/:id/preview` for markdown/text and any newly supported text/source types.

2. Shared FilePreview UI:
   - Add `kind=video`, `videoUrl`, `SlockFilePreviewVideoPanel`.
   - Add truncated copy for text/markdown server previews.
   - Keep unsupported/download fallback.

3. Server preview provider expansion:
   - Expand text MIME/extension whitelist with strict UTF-8 and caps.
   - Add tests mirroring KMP classifier expectations.

4. Android host video adapter:
   - Register `krVideoViewAdapter`.
   - Validate MP4 signed URL playback, pause/resume, back close.

5. OHOS host video adapter:
   - Add/register ArkUI `Video` adapter.
   - Validate remote MP4 URL. If remote fails, add cached local playback bridge.

## Validation

Static/unit:

- KMP tests:
  - `.mp4` and `video/mp4` route to `kind=video`.
  - `.webm` does not route to mobile video in phase 1.
  - text/source types route to server preview request, not signed body fetch.
  - lazy Thread preview payload does not include URL/content.
- Server tests:
  - JSON/log/source/SVG text files produce `kind=text`.
  - binary invalid UTF-8 returns unsupported.
  - caps/truncation hold.
- Web contract tests:
  - Existing video preview tests remain unchanged.

Android device:

- Upload/seed a small MP4 attachment in `#all`.
- Open Thread -> tap MP4 attachment -> standalone preview shows video shell -> play/pause works.
- Force back, reopen, URL refetch works.
- Log filters: no `Kuikly render error`, no player fatal, no route payload parse error.

OHOS device:

- Install HAP, open same MP4 attachment.
- Confirm first frame or playable state through UI and app-owned `SlockOhosDiagnostics`.
- Validate back/close releases player; re-open does not leave audio/video running.
- If ArkUI remote URL fails, repeat after implementing cache-to-local playback bridge.

## Open Questions

- Should mobile match React and eventually support WebM on Android only, or require identical Android/OHOS support before exposing WebM?
- Do we want CSV/diff full standalone renderers in KMP now, or is "text-like source + MP4" the intended first slice?
- Should HTML remain text/source on mobile, or should a separate sandboxed WebView preview be planned?
