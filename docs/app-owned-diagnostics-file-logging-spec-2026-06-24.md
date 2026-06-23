# App-owned Diagnostics File Logging Spec

Date: 2026-06-24
Owner lane: KMP/shared diagnostics + Android/OHOS host bridge
Source thread: #KMP:246bf225 / task #215

## Problem

Slock currently mixes three different logging purposes:

1. System console logs for external debugging (`logcat`, `hilog`).
2. App-owned diagnostics for Settings -> Diagnostics copy/share/feedback.
3. Short recent logs for on-screen inspection and crash breadcrumbs.

The current Android app-owned path is too memory-heavy:

- `SlockDiagnosticsLogger` writes to Android `Log.*`.
- `SanitizedDiagnosticsLogBuffer` stores up to `100_000` sanitized entries in memory.
- Feedback/export writes a temporary cache file only when the user taps an action.
- `SlockAndroidBuglyDiagnostics` is already integrated through `libs.bugly.pro`, initialized from `SLOCK_BUGLY_ANDROID_APP_ID` / `SLOCK_BUGLY_ANDROID_APP_KEY`, and reports launch, user context, caught exceptions, scenes, test crashes, and Kuikly performance snapshots to Bugly.
- Android Settings feedback already prepares a channel-ready text attachment (`bodyBase64`) from the memory export, but that file is not backed by a durable rolling log yet and is not paired with a Bugly upload action.

OHOS is closer to the desired long-term shape but still needs clearer boundaries:

- `SlockOhosDiagnostics` stores an in-memory recent buffer up to `5_000` entries.
- When Bugly is initialized, `setPersistentRecorder` forwards entries to `SlockOhosBuglyDiagnostics.record()`.
- Bugly `TDLog` writes bounded files under `bugly_tdlog` with `maxFileSize = 50MB` and `maxAliveTime = 7 days`.
- Settings copy/upload mostly uses the persistent Bugly/TDLog path, but Settings list still reads the memory recent buffer.
- Settings diagnostic feedback has two different product actions today:
  - Android prepares a channel-ready text attachment via `bodyBase64`.
  - OHOS `sendFeedback` currently requests a Bugly/TDDiag upload instead of returning an attachment draft.

The target direction is **not** reading back system `logcat` / `hilog`. Mobile apps should treat system logs as external debugging streams. The app must own its durable diagnostics files, because normal app permissions, privacy constraints, and platform sandboxing make system-log readback unreliable or inappropriate.

## Big-app Pattern

Large production apps generally split logging into sinks instead of relying on a single in-memory buffer:

- **Console sink**: human/debug visibility during development. Android `Log.*`, OHOS `hilog`, and Kuikly log adapters belong here. This sink is lossy and not a product data source.
- **Recent memory sink**: small ring for immediate UI display and crash breadcrumb summaries. It should hold hundreds of entries, not long history.
- **Rolling file sink**: app-owned sanitized text/JSONL files in the app sandbox. This is the canonical source for feedback bundles and support diagnostics.
- **Crash/observability sink**: Bugly/TDDiag or equivalent upload SDK. It may consume the same sanitized lines or attach rolling files.

The key rule: **write once to an app diagnostics pipeline, fan out to bounded sinks**. Do not make the UI scrape console output.

## Goals

- Make app-owned rolling files the canonical durable diagnostics source on Android and OHOS.
- Keep memory buffers small and explicitly "recent only".
- Keep system logs useful for external debugging, but never use `logcat` / `hilog` readback for Settings diagnostics.
- Preserve sanitization before data enters memory, file, Bugly, clipboard, share sheet, or feedback upload.
- Support copy/share/send-feedback from bounded files without building huge strings from memory.
- Support Settings -> Diagnostics feedback to a Raft channel by producing a local text attachment file/draft, even when Bugly is the durable OHOS sink.
- Keep high-frequency logs from hurting UI performance or storage.

## Non-goals

- Do not implement a full analytics pipeline.
- Do not capture raw request/response bodies.
- Do not read arbitrary system logs from the device.
- Do not persist access tokens, refresh tokens, auth headers, cookies, passwords, raw body, or unredacted email addresses.
- Do not mirror every Kuikly framework log into durable diagnostics by default.

## Proposed Architecture

```text
Shared/KMP diagnostic event
        |
        v
Host DiagnosticsRecorder
        |
        +--> ConsoleLogSink      logcat / hilog, debug-friendly, lossy
        +--> RecentMemorySink    last N sanitized entries for Settings preview
        +--> RollingFileSink     app-owned JSONL/text files, support bundle source
        +--> CrashSdkSink        Bugly/TDDiag breadcrumbs/upload, optional per platform
```

### Shared boundary

Shared code should continue to emit diagnostics through existing host capabilities:

- `BridgeModule.log(...)`
- typed diagnostics callbacks such as `diagnosticsLogger: (String) -> Unit`
- Settings diagnostics capability actions:
  - `loadDiagnostics`
  - `copyDiagnostics`
  - `shareDiagnostics`
  - `clearDiagnostics`
  - `prepareDiagnosticsFeedback`
  - `sendFeedback`

Shared/KMP should not know the host file path. It should only see a normalized `SharedSettingsDiagnosticsSnapshot` plus action feedback.

Feedback actions must be separated by product destination:

- **Channel feedback**: host returns a `SettingsDiagnosticsFeedbackDraft`-compatible payload:
  - `status=ok`
  - `message`
  - `fileName`
  - `mimeType=text/plain`
  - `sizeBytes`
  - `bodyBase64`
- **Crash/observability upload**: host requests Bugly/TDDiag upload and returns upload metadata such as `queryKey` or a platform-specific request id/status.

Do not overload a single `sendFeedback` action to mean both. If the UI entry point says "send diagnostics to channel", the host must return a local attachment draft. If the UI entry point says "upload to Bugly", the host may call TDDiag directly.

### Host boundary

Each host implements a single app-owned recorder:

```kotlin
interface AppDiagnosticsRecorder {
    fun record(level: DiagnosticsLevel, tag: String, message: String, timestampMillis: Long = now())
    fun recent(limit: Int = 300): List<DiagnosticEntry>
    fun exportText(maxBytes: Long = 2_000_000): String
    fun writeFeedbackBundle(): DiagnosticsBundle
    fun clear()
}
```

ArkTS equivalent:

```ts
interface AppDiagnosticsRecorder {
  record(level: DiagnosticsLevel, tag: string, message: string, timestampMillis?: number): void;
  recent(limit?: number): DiagnosticEntry[];
  exportText(maxBytes?: number): string;
  writeFeedbackBundle(): DiagnosticsBundle;
  clear(): void;
}
```

`record()` must sanitize before fan-out. A sink must never receive raw content and sanitize later.

## Android Plan

### Current state

- `SlockDiagnosticsLogger` gates verbose `d/i` by debug/profiler state.
- `SanitizedDiagnosticsLogBuffer` stores up to `100_000` entries in memory.
- Settings copy/share/feedback uses `SanitizedDiagnosticsLogBuffer.exportText()`.
- Feedback creates cache files under `cacheDir/slock-feedback`.
- `SlockAndroidBuglyDiagnostics` initializes Bugly Pro when app id/key are configured:
  - app version/build/channel/device/debug mode
  - Bugly Pro server host type
  - Java/native/ANR crash hooks through Bugly
  - looper metric and stack monitors
- Android currently forwards selected observability events to Bugly:
  - full launch via `AppLaunchProxy`
  - user/server context via `Bugly.updateUserId()` and `Bugly.putUserData()`
  - caught exceptions via `Bugly.postException(...)`
  - scene enter/exit + labels
  - Kuikly performance snapshots via `Bugly.monitorEvent(...)`
- This is a crash/observability sink. It is not a replacement for the user-facing Settings feedback file, because a Raft channel needs a local attachment draft.

### Target state

Add `AppDiagnosticsFileStore` under Android host code:

- Directory: `filesDir/slock-diagnostics/`
- Active file: `slock-diagnostics-current.jsonl`
- Rotated files: `slock-diagnostics-<epochMillis>.jsonl`
- Format: one sanitized JSON object per line.
- Rotation:
  - active file max: `1MB`
  - max rotated files: `8`
  - total directory budget: `10MB`
  - max age: `7 days`
- Write mode:
  - append-only
  - buffered async writer on a single background executor or coroutine dispatcher
  - flush on app background / feedback bundle / crash breadcrumb hook when possible

Suggested JSONL line:

```json
{"ts":1782264596123,"level":"warn","tag":"SlockNetworkProbe","message":"baseUrl=https://... code=200 durationMs=84","thread":"main","session":"optional-short-id"}
```

Do not include body text, bearer tokens, raw URLs with query secrets, request payloads, or full emails.

Keep `SlockAndroidBuglyDiagnostics` as the Android crash/observability upload sink:

- Continue to initialize Bugly Pro only when `BUGLY_ANDROID_APP_ID` and `BUGLY_ANDROID_APP_KEY` are configured.
- Continue to report caught exceptions, scenes, launch, user context, and Kuikly performance snapshots.
- Add an explicit Settings action for Bugly upload/reporting if the SDK supports uploading/attaching the generated diagnostics file. If the Android Bugly SDK does not expose a direct arbitrary-log upload API equivalent to OHOS `TDDiag.uploadLogFiles`, the action should still:
  - flush/record a Bugly breadcrumb that a diagnostics bundle was generated.
  - attach summary metadata through Bugly user data or monitor event where appropriate.
  - return a clear `status=unsupported` / `code=bugly_file_upload_unsupported` for file upload, while channel feedback still succeeds.
- Do not use Bugly console upload as the only feedback path. Settings channel feedback must always generate a local text attachment draft.

Update `SlockDiagnosticsLogger`:

- Keep writing to `Log.*` for console visibility.
- Change `SanitizedDiagnosticsLogBuffer` max from `100_000` to a recent-only bound (`500` or `1_000`).
- Add file sink write on every accepted entry.
- Keep verbose gating for `d/i`; `w/e` should always enter file sink.
- Add rate limiting for noisy tags:
  - per-tag cap for identical message signatures, e.g. 1 per 5 seconds plus dropped count summary.
  - explicit allowlist for high-volume profiler exports instead of general logs.

Update Settings actions:

- `loadDiagnostics`: read recent memory entries and include file metadata:
  - active file size
  - rotated file count
  - total bytes
  - oldest/newest timestamp if cheap
- `copyDiagnostics`: export bounded text from rolling files, newest first up to `2MB`.
- `shareDiagnostics`: attach a generated bundle file, not a huge clipboard string.
- `prepareDiagnosticsFeedback`: attach generated bundle file.
- `sendFeedback` / channel feedback:
  - write a local feedback text file from app-owned rolling logs.
  - return `fileName`, `mimeType`, `sizeBytes`, and `bodyBase64` to shared.
  - also trigger/report Bugly upload when available, but do not block the channel attachment on Bugly upload success.
- `uploadDiagnosticsToBugly`: explicit action for Bugly platform upload/reporting; return request id/status if the SDK exposes one, otherwise return unsupported with explanation.
- `clearDiagnostics`: clear recent memory and delete `filesDir/slock-diagnostics`.

## OHOS Plan

### Current state

- `SlockOhosDiagnostics` stores recent memory entries.
- `SlockOhosBuglyDiagnostics` initializes TDLog:
  - directory: `bugly_tdlog`
  - prefix: `slock`
  - async mode
  - max file size: `50MB`
  - max alive time: `7 days`
- `SlockOhosDiagnostics.setPersistentRecorder(...)` forwards to TDLog when Bugly is ready.
- Settings copy/upload already reads through `SlockOhosBuglyDiagnostics` for durable output.
- `SlockOhosBuglyDiagnostics.exportDiagnosticsText()` currently returns a text snapshot built from bundle/device metadata plus `SlockOhosDiagnostics.exportText(1000)`.
- `SlockOhosBuglyDiagnostics.writeDiagnosticSnapshot()` writes that snapshot to `filesDir/diagnostics_upload/diagnostics-<ts>.txt` for TDDiag upload.
- `KRBridgeModule.copyDiagnosticsCapability()` copies `exportDiagnosticsText()` to clipboard.
- `KRBridgeModule.sendFeedback` currently calls `uploadBuglyDiagnosticsCapability()`, which returns Bugly upload status/query key, not a `SettingsDiagnosticsFeedbackDraft` attachment.

### Target state

Keep TDLog/Bugly as the OHOS durable file sink, but rename and document responsibilities:

- `SlockOhosDiagnostics`: recent memory + sanitization + fan-out coordinator.
- `SlockOhosBuglyDiagnostics`: TDLog durable sink + crash/TDDiag upload adapter + Bugly-backed export writer.
- Add fallback app-owned file sink if Bugly init fails:
  - directory: `filesDir/slock-diagnostics/`
  - active/rotated JSONL files matching Android limits where practical.
  - record `durable_sink=fallback_file reason=<bugly init failure>` once.

Update `SlockOhosDiagnostics.MAX_ENTRIES` from `5_000` to recent-only (`500` or `1_000`) after file sink is verified.

Update Settings actions:

- `loadDiagnostics`: show recent memory entries plus durable sink metadata.
- `copyDiagnostics`: prefer durable TDLog/fallback file export; include recent memory tail only as a small prefix if needed.
- `prepareDiagnosticsFeedback` / channel `sendFeedback`:
  - flush TDLog first when Bugly is initialized.
  - generate a user-readable text file under a cache/export directory, for example `cacheDir/slock-feedback/slock-feedback-<ts>.txt`.
  - the file content should use Bugly-backed export data:
    - header metadata from `SlockOhosBuglyDiagnostics.exportDiagnosticsText()`
    - recent memory tail
    - TDLog file metadata or clipped TDLog text if the SDK exposes safe file reads
    - explicit truncation notice when capped
  - return `fileName`, `mimeType`, `sizeBytes`, and `bodyBase64` so shared can attach it to the selected Raft channel.
  - prune old generated feedback files after creation.
- `uploadDiagnosticsToBugly` (or a clearly named equivalent): flush TDLog and request TDDiag upload; return `queryKey`.
- `clearDiagnostics`: clear recent memory and delete fallback app-owned files. For Bugly TDLog files, either:
  - delete files only if SDK/docs confirm this is safe, or
  - record a `clear_requested` marker and expose that persistent crash SDK logs are managed by retention.
- `uploadDiagnostics`: flush TDLog before upload; include generated diagnostic snapshot as currently done.

The channel feedback file must not require the user or support agent to open the Bugly console. Bugly can be the source of durable lines, but Settings feedback still needs a normal local file attachment.

## Event Model

Use structured fields where possible:

| Field | Required | Notes |
| --- | --- | --- |
| `ts` | yes | Unix millis. |
| `level` | yes | `debug/info/warn/error`. |
| `tag` | yes | Max 48 chars. |
| `message` | yes | Max 512 chars after sanitization; current 280 is acceptable for recent UI but file can allow 512. |
| `route` | no | `home`, `thread`, `settings`, etc. |
| `serverHash` | no | Short hash, never raw server id unless already non-sensitive by product decision. |
| `threadHash` | no | Short hash if needed. |
| `eventId` | no | Short trace/correlation id for realtime/network batches. |
| `dropped` | no | Count of suppressed duplicate lines. |

High-cardinality values must be normalized:

- URL path category instead of full URL with query.
- Message/thread/channel ids shortened or hashed unless already shown in UI.
- Error class and mapped code instead of raw exception message when the message may include a path/token.

## Sanitization

Use one shared pattern set per host and test it:

- `Authorization: Bearer ...`
- `bearer ...`
- `accessToken`, `refreshToken`
- password
- raw response/body lines
- JWTs
- email addresses
- filesystem paths that may include username when exporting externally

Sanitization must happen before:

- memory buffer insert
- file append
- Bugly/TDLog write
- clipboard copy
- share intent
- feedback bundle generation

## File Export Shape

Generated feedback bundle should be a plain text file for immediate usability:

```text
Slock diagnostics
Exported at: 2026-06-24T01:30:00+08:00
Platform: Android
App version: ...
Device: ...
Selected server present: true
Durable sink: rolling_file
Files included: active + 3 rotated

--- recent summary ---
...

--- logs ---
1782264596123 SlockNetworkProbe: baseUrl=https://... code=200 durationMs=84
...
```

Caps:

- Clipboard copy: max `200KB`, newest tail, with note if truncated.
- Share/feedback attachment: max `2MB` text by default.
- Upload SDK: can include native rolling files up to SDK/network limits.

For OHOS with Bugly enabled:

- `TDLog.flushLog()` must run before generating the channel feedback file.
- If direct TDLog file reads are supported and safe, include the newest TDLog tail in the exported text.
- If direct TDLog file reads are not available, generate the channel file from `exportDiagnosticsText()` and recent memory, and include:
  - `Durable sink: bugly_tdlog`
  - `TDLog dir: <filesDir>/bugly_tdlog`
  - `TDLog attachment: not embedded; uploaded through Bugly only`
- The Raft channel attachment path must remain local and deterministic from the host perspective. Do not make the channel feedback depend on the asynchronous Bugly upload succeeding.

## Performance Rules

- File writes must not run on the Kuikly render thread.
- Record path must be non-blocking from UI callers.
- If the file queue is full, drop low-priority debug/info entries first and emit one summary:
  - `diagnostics_dropped level=debug count=123 reason=queue_full`
- No fsync per line. Flush on interval, app background, export, and crash hooks.
- High-frequency realtime/perf markers must aggregate before writing durable logs.

## Migration Steps

### Phase 1: Android file sink

1. Add `AppDiagnosticsFileStore`.
2. Wire `SlockDiagnosticsLogger` to recent memory + file sink + `Log.*`.
3. Lower `SanitizedDiagnosticsLogBuffer.MaxEntries` to `1_000`.
4. Update Settings copy/share/feedback/clear to use file export.
5. Integrate Android Bugly as a peer sink/action:
   - keep existing `SlockAndroidBuglyDiagnostics` crash/perf reporting.
   - add explicit Settings action for Bugly diagnostics upload/reporting.
   - channel feedback still returns a local file draft even if Bugly is disabled or upload is unsupported.
6. Add tests:
   - rotation by size
   - max file count
   - clear deletes files and memory
   - sanitization before file write
   - export truncates newest tail with truncation notice
   - channel feedback returns attachment draft when Bugly disabled
   - Bugly upload action reports configured / missing-config / unsupported-file-upload states correctly

### Phase 2: OHOS fallback + cleanup

1. Keep Bugly TDLog as primary durable sink.
2. Add a Bugly-backed channel feedback export action that returns `SettingsDiagnosticsFeedbackDraft` fields (`fileName`, `mimeType`, `sizeBytes`, `bodyBase64`).
3. Keep TDDiag upload as a separate explicitly named action; do not use it as the implementation of channel feedback.
4. Add fallback rolling file sink when Bugly is not initialized.
5. Lower `SlockOhosDiagnostics.MAX_ENTRIES`.
6. Update Settings load/copy/share/clear metadata.
7. Add ArkTS tests or manual HDC proof:
   - Bugly ready: durable sink `bugly_tdlog`
   - Bugly ready: channel feedback produces a local text attachment draft without waiting for Bugly upload success
   - Bugly ready: explicit Bugly upload returns upload request status/query key
   - Bugly failed: durable sink `fallback_file`
   - clear memory + fallback files

### Phase 3: Shared diagnostics contract polish

1. Document `settingsCapability` diagnostics response fields.
2. Normalize event names and high-volume throttling.
3. Add a small diagnostics health card in Settings:
   - durable sink
   - file bytes
   - last write time
   - dropped count

## Validation

Android:

- `./gradlew :androidApp:testDebugUnitTest --tests '*Diagnostics*'`
- `./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin`
- Manual:
  - trigger network probe, copy/share diagnostics, send feedback
  - trigger Bugly diagnostics action with config present and missing
  - verify exported file contains recent lines after app restart
  - verify no token/email/raw body leakage
  - verify clear removes file-backed logs

OHOS:

- `./hvigorw --mode module -p module=entry assembleHap` if the host build is available.
- Manual HDC:
  - Bugly initialized: record log, copy diagnostics, generate channel feedback attachment, upload diagnostics to Bugly.
  - Bugly disabled/failing: fallback file gets lines.
  - Settings clear behavior matches documented retention.

Cross-platform:

- App restart does not lose durable diagnostics.
- Memory usage does not grow with long sessions.
- High-volume logs do not flood console or durable files.
- Feedback bundle is usable without adb/hdc.

## Acceptance Criteria

- Android no longer depends on a `100_000` entry in-memory diagnostics history.
- Android feedback/copy/share reads from app-owned rolling files.
- Android keeps Bugly as a first-class crash/observability sink and exposes a separate diagnostics upload/report action when supported.
- Android channel feedback still produces a local attachment draft independent of Bugly availability.
- OHOS has a documented durable primary sink, channel attachment export path, and fallback path.
- OHOS channel feedback returns a local file draft; Bugly/TDDiag upload remains a separate action.
- Settings Diagnostics can show recent entries quickly but exports durable file history.
- No implementation reads from system `logcat` or `hilog`.
- Clear logs behavior is explicit and test-covered.
- Sensitive values are redacted before any sink receives them.

## Open Questions

- Does the Android Bugly Pro SDK expose an arbitrary file/log upload API comparable to OHOS `TDDiag.uploadLogFiles`, or should Android only report bundle metadata/events while channel feedback carries the actual text file?
- Should file format be JSONL only, or JSONL internal + text export for users?
- What exact retention should product want: 7 days / 10MB, or shorter for release builds?
- Should debug builds keep larger recent memory buffers, or should both debug/release use the same bounded design?
