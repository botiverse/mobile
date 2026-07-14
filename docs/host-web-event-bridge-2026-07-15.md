# Host↔Web Event Bridge — Draft Design

Date: 2026-07-15

Revision: 2 (addresses @MingQi CHANGES_REQUESTED review on r1).

Status: **Draft**. Design-only. Do not start implementation from this document until @artin and the platform owners sign off on this revision.

Ownership per @artin msg=0f7f0d59: @Bugen unified DRI.

## Motivation

The mobile app hosts selected web surfaces inside a native `SlockSandboxWebView`. Right now the WebView is one-way: `injectedJavaScript` lets the host push setup at load time, but there is no path for the web guest to signal state changes back to the host. That gap breaks the moment a web surface can mutate SESSION state that the host cares about — for example, if the user opens `/s/{slug}/settings/server` inside the WebView and taps **Delete server** or **Leave server**, the web app logs itself out and navigates to the login page, while the native shell still thinks it has a valid session. On the next cold start the user "mysteriously can't sign in." The failure is silent: no error at the moment it happens, only a bad state a while later.

This class of hazard is why @artin paused embedding the server-profile surface even though `SettingsPanel` is technically WebView-ready. Before we wire more session-mutating surfaces (server profile, account, logout, switch-server), we need the web guest to be able to *tell the host* what just happened.

## Scope

**In scope:**

- A minimal, additive extension to `SlockSandboxWebView` that lets a web page inside the WebView send named events with structured payloads to the KMP-shared layer.
- The vocabulary of session-related events that motivate this work.
- Per-platform mechanism sketches for Android WebView, iOS WKWebView, and OHOS Web.

**Explicitly out of scope (guardrails against scope creep):**

- No RPC / no return values / no ack. The bridge is fire-and-forget.
- No shared React store, no state mirror between web and native.
- No general host command vocabulary — that is what the existing `HRBridgeModule` / `KRBridgeModule` already do for Kuikly pages. This document is only about the WebView guest surface.
- No React Native JSI (already ruled out by `mobile-webview-server-contract-2026-07-02.md`).
- No new push, notification, or realtime infrastructure.
- No host→web synchronous state push. The existing `injectedJavaScript` covers the at-load-time direction; host reacts to session events by reconciling through canonical APIs (see §5), not by pushing state back into the WebView.

## Trust boundary

The bridge is a privileged capability. Its trust boundary MUST be pinned before implementation:

**Opt-in per WebView (@MingQi #1).** The channel is NOT installed in every `SlockSandboxWebView`. `SlockSandboxWebViewAttr` gains:

```kotlin
fun hostEventsEnabled(value: Boolean): SlockSandboxWebViewAttr { /* default false */ }
```

Only surfaces that can mutate session state set `hostEventsEnabled = true` (initially: server-settings embed, server-delete confirmation flow). Sandbox previews, marketplace, billing, etc. stay off. If `hostEventsEnabled` is false, no bridge script is injected, no message handler is registered, no channel exists — a WebView without a consumer never exposes the surface.

**Trusted origin + main-frame-only (@MingQi #2).** The bridge is installed only when the initial navigation targets an approved HTTPS origin from an allowlist:

```
prod:    https://app.slock.ai
staging: https://slock-app-staging.botiverse.dev
dev:     http://localhost:5173  (Vite)
```

Platform implementations MUST:

- **Android**: `postMessage` uses the exact target origin string; never `Uri.EMPTY` or `"*"`. Attach the `WebMessagePort` only to the main frame. Detach the ports before allowing navigation to any URL whose origin is not in the allowlist.
- **iOS**: register the `WKUserScript` with `injectionTime = .atDocumentStart` and `forMainFrameOnly = true`. In `WKScriptMessageHandler.userContentController(_:didReceive:)`, reject any message whose `frameInfo.request.URL.origin` is not in the allowlist. Remove the script handler before navigating away from an approved origin.
- **OHOS**: equivalent — install the proxy only for the main frame, gate the message handler on origin, tear down before untrusted navigation.

Without this pin, any page reached from an allowed WebView (including any iframe) could emit `session:*` events. That's a same-origin escape via the bridge.

## Contract

### Ownership split (@MingQi #8)

Cleanly separated:

- **Platform** owns `window.RaftHost` (the injected global). Each platform's WebView renderer injects the SAME bridge script at document-start into the main frame, only when `hostEventsEnabled = true` and the origin is trusted. The script sets `window.RaftHost = { version, emit }` where `emit` calls the platform-specific message-port / script-message-handler.
- **Web** (`packages/web/src/embed/hostBridge.ts` in `botiverse/slock`) owns a safe HELPER that DETECTS and CALLS the injected global. Web does NOT provide `emit`; web calls it.

Composition order with the existing auth `injectedJavaScript`: bridge script runs first (document-start), auth token seed runs later (per current `mobile-webview-server-contract-2026-07-02.md` bootstrap). Bridge script must be self-contained — no dependency on the auth seed running before it. Auth seed must not overwrite `window.RaftHost` or `.version`. Document this in the mobile-webview-server-contract update; the two `injectedJavaScript` snippets are concatenated in that order, in the KMP shared renderer, not composed independently by each platform.

### Web side

```ts
// packages/web/src/embed/hostBridge.ts  (new)

/** True only when a compatible host installed the bridge for THIS WebView. */
export function hasRaftHostEventBridge(): boolean {
  if (typeof window === "undefined") return false;
  const rh = (window as unknown as { RaftHost?: { version?: string; emit?: unknown } }).RaftHost;
  return typeof rh === "object" && rh !== null
    && rh.version === "raft-host-v1"
    && typeof rh.emit === "function";
}

/**
 * Emit an event to the host. No-op when the bridge is absent (regular browser, or
 * a WebView surface that did not enable hostEvents). Fire-and-forget by design.
 *
 * NOTE: this helper is bridge-availability-only. Layout embedding is governed by
 * `embed=raft-settings-v1&shell=host` and remains the authority for what web
 * renders. Do not use `hasRaftHostEventBridge()` to gate layout.
 */
export function emitHostEvent(kind: HostEventKind, payload?: HostEventPayload): void {
  if (!hasRaftHostEventBridge()) return;
  (window as unknown as { RaftHost: { emit: (k: string, p?: object) => void } })
    .RaftHost.emit(kind, payload ?? {});
}
```

### Wire format

A single event flowing web → host, as JSON:

```json
{
  "kind": "session:server-deleted",
  "payload": { "serverSlug": "acme" }
}
```

Fields:

| Field | Type | Notes |
| --- | --- | --- |
| `kind` | string | Namespaced, colon-separated. See §Safety limits for allowed pattern. Case-sensitive. |
| `payload` | object | REQUIRED — an object, possibly empty `{}`. Not a primitive, not an array. |

**No `seq` / `ts` in v1 (@MingQi #4).** The r1 draft included these, but the shared handler only exposed `(kind, payload)`, so a consumer could never observe them — an unobservable field is not a contract. If a per-load monotonic counter becomes useful later, promote it to a typed shared event `HostWebEvent(kind, payload, seq, timestampMillis)` with an explicit reset-on-document-load semantic, in a follow-up revision of this document.

### KMP-shared additions

`compose/shared/src/commonMain/kotlin/build/raft/app/SlockSandboxWebView.kt`:

```kotlin
internal class SlockSandboxWebViewEvent : Event() {
    // Existing events unchanged...
    fun loadSuccess(handler: () -> Unit) { /* existing */ }
    fun loadFailure(handler: (String) -> Unit) { /* existing */ }
    fun navigationBlocked(handler: (String) -> Unit) { /* existing */ }
    fun urlChange(handler: (String) -> Unit) { /* existing */ }

    // New. Aligned with the existing Kuikly callback boundary (@MingQi #3):
    // native renderer invokes with a params MAP, e.g. mapOf("message" to rawJson).
    fun hostMessage(handler: (kind: String, payload: JSONObject) -> Unit) {
        register(EVENT_HOST_MESSAGE) { params ->
            val raw = (params as? JSONObject)?.optString("message", "") ?: ""
            if (raw.isEmpty()) return@register       // malformed — parse-boundary drop with warn log
            val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@register
            val kind = parsed.optString("kind", "")
            if (!isValidKind(kind)) return@register  // §Safety limits
            val payload = parsed.optJSONObject("payload") ?: return@register  // payload required, object
            handler(kind, payload)
        }
    }

    companion object {
        // existing constants...
        const val EVENT_HOST_MESSAGE = "hostMessage"
    }
}
```

The Kuikly boundary contract is `params: JSONObject` with a single string key `message`, not a raw string. This matches the existing `urlChange`/`loadFailure` shape.

### Native platform seams

Each platform's WebView renderer implements the receive path AND the injected script generator. The script generator is a shared function in KMP that emits the same JS on every platform (only the `postMessage` target differs). Concretely, each platform's `injectedJavaScript` gets prefixed with:

```js
(function () {
  'use strict';
  var post = /* platform-specific */;
  Object.defineProperty(window, 'RaftHost', {
    value: Object.freeze({
      version: 'raft-host-v1',
      emit: function (kind, payload) {
        if (typeof kind !== 'string') return;
        try { post(JSON.stringify({ kind: kind, payload: payload || {} })); } catch (_) {}
      },
    }),
    writable: false, configurable: false,
  });
})();
```

Per platform:

- **Android** (`SlockSandboxWebViewRenderView.kt`): create a `WebMessagePort` pair on the main frame, bind to `window.RaftBridge` (internal name) via `WebViewCompat.postWebMessage`. The injected `post` is `window.__raftBridgePort.postMessage(m)`. Preferred over `addJavascriptInterface` because that exposes an entire Java object; `WebMessagePort` exposes only strings on one named channel. The Kotlin side receives via `WebMessagePort.WebMessageCallback.onMessage`, extracts the string, wraps as `mapOf("message" to raw)`, forwards to the Kuikly event bus.
- **iOS** (`SlockSandboxWebView.m`): register `WKScriptMessageHandler` on `WKUserContentController` under name `raftHost` (`.atDocumentStart`, `forMainFrameOnly: true`). The injected `post` is `window.webkit.messageHandlers.raftHost.postMessage(m)`. Inbound handler validates `message.frameInfo.request.URL.origin` against the allowlist, then dispatches through Kuikly with `@{@"message": raw}`.
- **OHOS** (`SlockSandboxWebView.ets`): use `webController.registerJavaScriptProxy` (or the current equivalent) for a string-only channel; same origin/main-frame gate; same `{message: raw}` shape into the Kuikly event bus.

## Event vocabulary v1

```
session:logout                    (payload: {})
session:server-deleted            (payload: { serverSlug: string })
session:server-left               (payload: { serverSlug: string })
session:server-switched           (payload: { serverSlug: string })
session:token-refresh-failed      (payload: {})
```

### Semantics — wake/intent, not canonical truth (@MingQi #5)

**Events are notifications of user intent, not authoritative statements about auth state.** Except for `session:logout`, host reactions MUST reconcile through the canonical host session/membership API before acting. Do not blindly mutate host cache from a JS payload.

- `session:logout` — the user completed logout on the web page (`useAuthStore.logout()`). Host clears its own `StoredAuthSession` and returns the user to the login screen. Unilateral — this is the only event whose payload is trusted, because logout is by definition destroying local state.
- `session:server-deleted` — the user attempted to delete `serverSlug`. Host must call its own `serverService.listServers()` (canonical) and route from the confirmed result. If the server IS still in the returned list, treat as spurious (log-and-drop). If it's gone, refresh memberships and route to the next server or picker.
- `session:server-left` — same reconciliation shape as `server-deleted`. The distinction is product-level (destructive vs. reversible from the owner's side), not host-reaction level.
- `session:server-switched` — the user selected `serverSlug` inside the WebView. Host must validate that `serverSlug` is present in its own session's authorized-servers before storing it as "last active". If validation fails, log and ignore.
- `session:token-refresh-failed` — the web app's refresh cycle failed. Host must run its OWN auth bootstrap (its own `refresh` call, its own `me` call). Do not assume "web says refresh failed" ⇒ "user is unauthenticated"; the WebView may simply have a stale token cached separately from the host.

The web app emits these as the visible user action succeeds (inside `authStore.logout()`, inside `serverService.leaveServer()` success handler, etc.). It does NOT emit them speculatively.

## Safety limits and lifecycle (@MingQi #6)

**Input limits.** Enforced at the native parse boundary; violations are dropped with a `BridgeLogLevel.warn` log:

- Raw message size ≤ 4 KB (`raw.length <= 4096`). Larger messages are dropped whole — no partial parse.
- `kind` matches `^[a-z][a-z0-9]*(:[a-z][a-z0-9-]*)+$`, total length 4–64 chars. Prevents `kind` injection into log strings and pins the namespaced shape.
- `payload` must be a JSON object (`{}` is fine, `null` / arrays / primitives are dropped).

**Lifecycle / ABA safety.** Platform implementations MUST:

- Close the message handler / port pair when the renderer view is destroyed (Android `WebView.destroy`, iOS view controller `deinit`, OHOS equivalent).
- Detach on main-frame navigation to any URL whose origin is not in the allowlist; re-attach only if a subsequent navigation returns to an approved origin.
- A callback delivered from an OLD renderer's registration MUST NOT reach a REPLACEMENT WebView instance. This is the ABA case — a pending message queued by a torn-down WebView must be dropped, not dispatched through the shared event bus that a new WebView is subscribed to. Concretely: each registration carries a per-instance token; the shared consumer checks the token and drops stale messages.

**Tests required for v1** (per platform):

1. Positive: web emits `session:logout` → shared consumer receives `("session:logout", {})`.
2. Malformed message string (not JSON) → dropped, no crash, warn log emitted.
3. Missing `message` field on params → dropped, no crash.
4. Kind too long / wrong pattern → dropped.
5. Payload not an object → dropped.
6. Message from an iframe with a different origin (where possible to construct) → dropped.
7. Detach on renderer destroy: message queued mid-teardown does not reach the shared event bus.
8. Old registration replacement: a message from an old WebView instance does not reach the new instance's consumer.

## Handshake / detection

Web calls `hasRaftHostEventBridge()` before calling `emitHostEvent(...)`. The helper checks `window.RaftHost.version === "raft-host-v1"`. If the version is different or the global is absent, `emitHostEvent` is a no-op and the page runs identically outside a compatible host.

`hasRaftHostEventBridge()` is orthogonal to the layout embed contract (`embed=raft-settings-v1&shell=host`). A page can be layout-embedded and NOT bridge-enabled (marketplace / billing surfaces will be exactly that). Do not use `hasRaftHostEventBridge()` to decide what to render; that authority lives with `isHostShell()` from `packages/web/src/embed.ts`. This split was a specific comment (@MingQi #7): bridge availability is a capability, not a layout property.

## Failure modes, restated

- Unknown `kind` → silently ignored by the host after parse validation. Soft versioning.
- Non-embedded browser → `window.RaftHost` undefined → `emitHostEvent` short-circuits.
- Malformed / oversized / wrong-shape message → dropped at parse boundary, warn log.
- Cross-origin frame emits → rejected at the native origin gate.
- WebView tear-down during in-flight emit → the ABA guard drops it before reaching the replacement.
- Host handler throws → wrap platform-side, log with `BridgeLogLevel.error`, do NOT let it propagate into the Kuikly render pipeline.

## Non-goals, restated for review

If any of these change scope, do NOT extend this document — write a new one:

- **RPC / return values / ack.** Adds ack, timeout, request-id, error propagation. That is what `HRBridgeModule` already provides for Kuikly pages, and it is a full IPC.
- **State mirror.** Adds a schema for "auth state," "server list," "user identity." Once we have that, we have a duplicated auth store in two runtimes, and the two will drift.
- **Runtime host → web push.** The existing `injectedJavaScript` covers page-load initialisation. If runtime push becomes necessary, design it explicitly, not by silently repurposing `injectedJavaScript`.
- **Custom binary protocols.** JSON strings only.

## Acceptance for v1

- KMP shared adds `hostMessage(...)` event on the params-map boundary. Unit tests: positive, malformed message, missing message field, kind pattern, payload shape.
- Each of Android / iOS / OHOS implements the platform seam (opt-in attr, trusted-origin + main-frame gate, string-only channel, ABA guard). Integration tests per §Safety limits.
- `packages/web/src/embed/hostBridge.ts` in `botiverse/slock` implements `hasRaftHostEventBridge()` and `emitHostEvent(...)`. Behaviour tests:
  - Outside a host (`RaftHost` undefined), `emitHostEvent` is a no-op — no throw, no console error.
  - Inside a host, `emitHostEvent` invokes the injected `emit` exactly once per call with a `{}` payload when omitted.
  - Layout embed detection (`isHostShell()`) is NOT influenced by bridge presence — bridge on / off does not change what web renders.
- One end-to-end scenario, CI-driven: web calls `emitHostEvent("session:logout")` inside a WebView with `hostEventsEnabled=true` on the approved origin → the shared consumer receives `("session:logout", {})`.
- Non-goals for cross-origin frames, oversized payloads, and torn-down renderers each have a red gate as described in §Safety limits.

## Owners and review

- **@Bugen** — unified DRI (design, web side, coordinating across platforms).
- **KMP shared + Android review** — @MingQi.
- **iOS review** — [TBD by artin].
- **OHOS review** — [TBD by artin].
- **Product boundary** — @artin final sign-off; event vocabulary lives with him.

## Related documents

- `docs/mobile-webview-server-contract-2026-07-02.md` — session bootstrap direction (host → web). This document is the return path.
- `docs/kuikly-bridge-module-contract.md` — the shared Kuikly bridge (native → shared). Explicitly OUT OF SCOPE here — Kuikly bridge is not the WebView guest surface.
- `docs/ios-kmp-p0-host-bridge-spec-2026-06-27.md` — iOS-side Kuikly bridge, same boundary as above.

## Changelog

- **r2 (2026-07-15):** addresses @MingQi CHANGES_REQUESTED review on r1. Substantive additions:
  (1) `hostEventsEnabled` opt-in attr, (2) trusted-origin + main-frame-only requirement,
  (3) params-map boundary alignment (`{message: raw}`), (4) `seq`/`ts` removed from v1,
  (5) "wake/intent not canonical truth" semantics, (6) safety limits + lifecycle/ABA guards,
  (7) `hasRaftHostEventBridge()` name, orthogonal to layout embed, (8) ownership split
  clarified: platform owns the global, web owns the helper; injection order documented.
- **r1 (2026-07-15):** initial draft.
