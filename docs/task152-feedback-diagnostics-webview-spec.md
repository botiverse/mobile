# Task #152 — Feedback diagnostics WebView/bridge v3

## Scope

The standalone `feedback/` WebView remains the product feedback UI. On submit, the native host generates diagnostics and uploads them through the matching Hands app. The existing Web transport authenticates `/api/product-feedback/...` with the user session bearer held in `window.RaftFeedback.session.accessToken`; that bearer is kept in page memory only and is never written to a URL, Web Storage, log, or native diagnostics payload. The auth bootstrap and every runtime refresh are limited to the trusted feedback main document (exact origin/path/query); subframes and other navigations receive no session. The native host does not forward feedback-page console output to its logs. The WebView never receives a Hands client key, presign URL, local path, or diagnostics bytes. The diagnostics bridge receives only progress and a one-time controlled reference.

This applies to Android WebView, iOS WKWebView, and OHOS ArkWeb. A host without the optional capability keeps the existing feedback submission flow.

## Capability and bridge

Retain `raft-feedback-v1`. Add optional `diagnostics-upload-v1` capability. The page invokes:

```text
window.RaftFeedback.diagnostics.start({requestId}, binding)
window.RaftFeedback.diagnostics.cancel({requestId}, binding)
```

`binding` is the object `{accountId, sessionGeneration, webViewGeneration}` and is echoed by the host. A request is admitted only for the active account/session generation, WebView generation, and request ID.

The host dispatches a DOM `raft:feedback-diagnostics` event with:

```text
{ requestId, binding, type: "progress"|"done"|"failed", ... }
```

`progress` carries monotonic integer `sent` and positive integer `total` (at most 200 MiB); `done` carries `ref`; `failed` carries `generate_failed`, `too_large`, or `upload_failed`. Preparation has no byte progress event. Cancellation invalidates the request; it does not create a ticket.

The page ignores duplicate, stale, late, or mismatched events. Native origin/main-frame checks remain mandatory.

## Lifecycle and failure rules

1. User submits; page starts one diagnostics request.
2. Native host prepares the archive, then preuploads it with the Hands client key.
3. Native host emits generation/upload progress.
4. On `done(ref)`, the page creates the feedback ticket with the reference in the same submission.
5. Generation/upload failure, skip, close, account switch, logout, or 120 seconds without valid progress cancels/invalidate the request and permits ordinary feedback without diagnostics.
6. Network/5xx/timeout/unknown create result reuses the same submission ID/reference/content. Only explicit `feedback_diagnostics_invalid` or `feedback_diagnostics_rejected` before ticket creation permits a no-diagnostics retry with a new submission ID.

## Platform mapping

- **Android:** extend the existing AndroidX `WebMessageListener` and document-start host script. Upload/cancellation is owned by the Android activity/Hands client and fenced by request/session/WebView generation.
- **iOS:** add `WKScriptMessageHandler` plus a document-start `WKUserScript` to `RaftSandboxWebView`; upload/cancellation is owned by the existing feedback bridge module and fenced by WebView generation.
- **OHOS:** add ArkWeb JavaScript/native messaging to `RaftSandboxWebView.ets`; upload/cancellation is owned by `KRBridgeModule.ets` and fenced by render generation.

## Acceptance matrix

- Capability absent: legacy feedback works unchanged.
- Duplicate, stale, cancelled, and late events are ignored.
- No diagnostics bridge payload contains a Hands client key, presign URL, access token, local path, or diagnostics bytes. The user session bearer is limited to the in-memory `RaftFeedback.session` transport contract and must not appear in a URL, Web Storage, log, or native diagnostics payload.
- Auth bootstrap and token refresh run only in the trusted feedback main document; subframes, cross-origin pages, and path/query changes are fenced out. Feedback console output is not forwarded to native logs.
- Failure never blocks ordinary feedback.
- A `done` reference is one-time; late completion after cancel/close/account switch cannot create a second ticket.
- Shared contract plus Android, iOS, and OHOS compile checks pass. Runtime staging preupload → create-ticket evidence is required separately per platform.

## Staging server contract (f6d3e804, deploy 38157412327)

The staging endpoint accepts a `diagnostics` JSON field containing `r2_key`, `filename`, `content_type: application/zip`, and `size`, plus `X-Raft-Client-Kind: android|ios|ohos`. Local validation failure returns `400 feedback_diagnostics_invalid` without calling Hands; the WebView may remove diagnostics and retry with a new submission ID. Only diagnostic-bearing Hands 400 with exact `presigned_invalid`, `presigned_not_found`, or `presigned_size_mismatch` maps to `feedback_diagnostics_rejected`. The user may then remove diagnostics and use a new submission ID. Other failures retain the original ID/reference/content; 409 remains `feedback_submission_conflict`.

## Exact Web implementation and current native gap

Contract source: Web PR #9493, head `d51b62e451e1c627d448da27f1af23c3c7935198`,
`packages/web/src/feedback/standalone/{host,diagnostics}.ts`.
The optional capability and methods belong to `RaftFeedback`, not `RaftHost`.
The native branch does not yet have a diagnostics upload implementation. Do not
advertise `diagnostics-upload-v1` until start/cancel, lifecycle fencing, native
preupload and terminal events are implemented together. The legacy feedback
flow remains available without that capability.

Remaining work: shared request/binding validation and lifecycle state; native
archive/preupload/progress/cancel on all three hosts; OHOS host messaging;
platform-specific bootstrap session metadata; compile and real staging receipts.
Node tests execute the actual Android/iOS host scripts plus shared bootstrap to
check legacy event delivery and capability absence. They do not establish native
compile, upload support, or three-platform runtime acceptance.
