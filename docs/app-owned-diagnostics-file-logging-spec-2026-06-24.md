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

OHOS is closer to the desired long-term shape but still needs clearer boundaries:

- `SlockOhosDiagnostics` stores an in-memory recent buffer up to `5_000` entries.
- When Bugly is initialized, `setPersistentRecorder` forwards entries to `SlockOhosBuglyDiagnostics.record()`.
- Bugly `TDLog` writes bounded files under `bugly_tdlog` with `maxFileSize = 50MB` and `maxAliveTime = 7 days`.
- Settings copy/upload mostly uses the persistent Bugly/TDLog path, but Settings list still reads the memory recent buffer.

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

### Target state

Keep TDLog/Bugly as the OHOS durable file sink, but rename and document responsibilities:

- `SlockOhosDiagnostics`: recent memory + sanitization + fan-out coordinator.
- `SlockOhosBuglyDiagnostics`: durable file/crash upload sink.
- Add fallback app-owned file sink if Bugly init fails:
  - directory: `filesDir/slock-diagnostics/`
  - active/rotated JSONL files matching Android limits where practical.
  - record `durable_sink=fallback_file reason=<bugly init failure>` once.

Update `SlockOhosDiagnostics.MAX_ENTRIES` from `5_000` to recent-only (`500` or `1_000`) after file sink is verified.

Update Settings actions:

- `loadDiagnostics`: show recent memory entries plus durable sink metadata.
- `copyDiagnostics`: prefer durable TDLog/fallback file export; include recent memory tail only as a small prefix if needed.
- `clearDiagnostics`: clear recent memory and delete fallback app-owned files. For Bugly TDLog files, either:
  - delete files only if SDK/docs confirm this is safe, or
  - record a `clear_requested` marker and expose that persistent crash SDK logs are managed by retention.
- `uploadDiagnostics`: flush TDLog before upload; include generated diagnostic snapshot as currently done.

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
5. Add tests:
   - rotation by size
   - max file count
   - clear deletes files and memory
   - sanitization before file write
   - export truncates newest tail with truncation notice

### Phase 2: OHOS fallback + cleanup

1. Keep Bugly TDLog as primary durable sink.
2. Add fallback rolling file sink when Bugly is not initialized.
3. Lower `SlockOhosDiagnostics.MAX_ENTRIES`.
4. Update Settings load/copy/share/clear metadata.
5. Add ArkTS tests or manual HDC proof:
   - Bugly ready: durable sink `bugly_tdlog`
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
  - verify exported file contains recent lines after app restart
  - verify no token/email/raw body leakage
  - verify clear removes file-backed logs

OHOS:

- `./hvigorw --mode module -p module=entry assembleHap` if the host build is available.
- Manual HDC:
  - Bugly initialized: record log, copy diagnostics, upload diagnostics.
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
- OHOS has a documented durable primary sink and fallback path.
- Settings Diagnostics can show recent entries quickly but exports durable file history.
- No implementation reads from system `logcat` or `hilog`.
- Clear logs behavior is explicit and test-covered.
- Sensitive values are redacted before any sink receives them.

## Open Questions

- Should Android also use Bugly Android logs as the crash/upload sink, or is app-owned rolling file enough for phase 1?
- Should file format be JSONL only, or JSONL internal + text export for users?
- What exact retention should product want: 7 days / 10MB, or shorter for release builds?
- Should debug builds keep larger recent memory buffers, or should both debug/release use the same bounded design?
