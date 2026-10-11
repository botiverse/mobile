# Task #152 — Feedback diagnostics WebView/bridge v3

## Scope

The standalone `feedback/` WebView remains the product feedback UI. On submit, the native host generates diagnostics and uploads them through the matching Hands app. The WebView receives only progress and a one-time controlled reference; it never receives a client key, access token, presign URL, local path, or diagnostics bytes.

This applies to Android WebView, iOS WKWebView, and OHOS ArkWeb. A host without the optional capability keeps the existing feedback submission flow.

## Capability and bridge

Retain `raft-feedback-v1`. Add optional `diagnostics-upload-v1` capability. The page invokes:

```text
diagnostics.start({requestId, binding})
diagnostics.cancel({requestId, binding})
```

`binding` is opaque to the page and echoed by the host. A request is admitted only for the active account/session generation, WebView generation, and request ID.

The host dispatches a DOM `raft:feedback-diagnostics` event with:

```text
{ requestId, binding, type: "progress"|"done"|"failed", ... }
```

`progress` carries `sent` and `total` (`total=0` means indeterminate); `done` carries one-time `ref`; `failed` carries a fixed reason: `cancelled`, `timeout`, `generation`, `network`, `invalid`, or `unknown`.

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
- **iOS:** add `WKScriptMessageHandler` plus a document-start `WKUserScript` to `SlockSandboxWebView`; upload/cancellation is owned by the existing feedback bridge module and fenced by WebView generation.
- **OHOS:** add ArkWeb JavaScript/native messaging to `SlockSandboxWebView.ets`; upload/cancellation is owned by `KRBridgeModule.ets` and fenced by render generation.

## Acceptance matrix

- Capability absent: legacy feedback works unchanged.
- Duplicate, stale, cancelled, and late events are ignored.
- No WebView payload contains key, URL, token, local path, or bytes.
- Failure never blocks ordinary feedback.
- A `done` reference is one-time; late completion after cancel/close/account switch cannot create a second ticket.
- Shared contract plus Android, iOS, and OHOS compile checks pass. Runtime staging preupload → create-ticket evidence is required separately per platform.

## Staging server contract (bdf237d1)

The staging endpoint accepts a `diagnostics` JSON field containing `r2_key`, `filename`, `content_type: application/zip`, and `size`, plus `X-Raft-Client-Kind: android|ios|ohos`. Local validation failure returns `400 feedback_diagnostics_invalid` without calling Hands; the WebView may remove diagnostics and retry with a new submission ID. A Hands reference rejection remains ordinary `400 feedback_invalid`; preserve the same submission ID, reference, and content.
