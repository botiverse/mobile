# Host↔Web Event Bridge — Draft Design

Date: 2026-07-15

Status: **Draft**. Design-only. Do not start implementation from this document until @artin and the platform owners (@MingQi et al.) sign off. Ownership per @artin msg=0f7f0d59: @Bugen unified DRI.

## Motivation

The mobile app hosts selected web surfaces inside a native `SlockSandboxWebView`.
Right now the WebView is a one-way viewer: `injectedJavaScript` lets the host push
setup at load time, but there is no path for the web guest to signal state changes
back to the host. That gap breaks the moment a web surface can mutate SESSION state
that the host cares about — for example, if the user opens `/s/{slug}/settings/server`
inside the WebView and taps **Delete server** or **Leave server**, the web app logs
itself out and navigates to the login page, while the native shell still thinks it
has a valid session. On the next cold start the user "mysteriously can't sign in."
The failure is silent: no error at the moment it happens, only a bad state a while
later.

This class of hazard is why @artin paused embedding the server-profile surface even
though `SettingsPanel` is technically WebView-ready. Before we wire more session-
mutating surfaces (server profile, account, logout, switch-server), we need the web
guest to be able to *tell the host* what just happened.

## Scope

**In scope:**

- A minimal, additive extension to `SlockSandboxWebView` that lets a web page inside
  the WebView send named events with structured payloads to the KMP-shared layer.
- The vocabulary of session-related events that motivate this work.
- Per-platform mechanism sketches for Android WebView, iOS WKWebView, and OHOS Web.

**Explicitly out of scope (guardrails against scope creep):**

- No RPC / no return values / no ack. The bridge is fire-and-forget.
- No shared React store, no state mirror between web and native.
- No general host command vocabulary — that is what the existing `HRBridgeModule` /
  `KRBridgeModule` already do for Kuikly pages. This document is only about the
  WebView guest surface.
- No React Native JSI (already ruled out by `mobile-webview-server-contract-2026-07-02.md`).
- No new push, notification, or realtime infrastructure.
- No host→web synchronous state push. The existing `injectedJavaScript` covers the
  at-load-time direction; host reacts to session events by tearing down or
  redirecting the WebView, not by pushing state back into it.

## Contract

### Web side

The web guest is a page in `github.com/botiverse/slock/packages/web`. The bridge is
exposed as a single global namespace:

```ts
// packages/web/src/embed/hostBridge.ts (new)
window.RaftHost = {
  version: "raft-host-v1",             // matches the existing `raft-settings-v1` embed contract
  emit(kind: string, payload?: object) { /* platform-provided implementation */ },
};
```

The `version` field lets the web app detect whether it is running inside a
compatible host. If `window.RaftHost` is absent (regular browser), `emit` is a
no-op — the same page still runs correctly.

Each platform's WebView installs its own `emit` implementation at page load,
BEFORE React mounts, via `injectedJavaScript`. The web app never provides the
`emit` implementation itself.

### Wire format

A single event is a JSON object:

```json
{
  "kind": "session:server-deleted",
  "payload": { "serverSlug": "acme" },
  "seq": 3,
  "ts": 1721000000000
}
```

Fields:

| Field | Type | Notes |
| --- | --- | --- |
| `kind` | string | Namespaced, colon-separated (`session:*`, future `nav:*`, `share:*`). Case-sensitive. |
| `payload` | object? | Optional. Host must tolerate missing / unknown fields. |
| `seq` | integer | Monotonic per-WebView-load; helps host detect drops if it wires per-platform delivery guarantees. |
| `ts` | integer | Unix milliseconds. Web-clock, informational — do not trust for auth decisions. |

Unknown `kind` values MUST be silently ignored by the host. This is the versioning
strategy: v1 hosts see only v1 kinds; a v2 web app running on a v1 host degrades
gracefully.

### KMP-shared additions

`compose/shared/src/commonMain/kotlin/build/raft/app/SlockSandboxWebView.kt`:

```kotlin
internal class SlockSandboxWebViewEvent : Event() {
    fun loadSuccess(handler: () -> Unit) { /* existing */ }
    fun loadFailure(handler: (String) -> Unit) { /* existing */ }
    fun navigationBlocked(handler: (String) -> Unit) { /* existing */ }
    fun urlChange(handler: (String) -> Unit) { /* existing */ }

    // New.
    fun hostMessage(handler: (kind: String, payload: JSONObject) -> Unit) {
        register(EVENT_HOST_MESSAGE) { rawJson ->
            val obj = JSONObject(rawJson as String)
            handler(obj.getString("kind"), obj.optJSONObject("payload") ?: JSONObject())
        }
    }

    companion object {
        // existing constants...
        const val EVENT_HOST_MESSAGE = "hostMessage"
    }
}
```

The event payload is delivered as a raw JSON string from the native layer; shared
code re-parses it. This keeps the KMP boundary narrow.

### Native platform seams

Each platform's WebView renderer implements the receive path:

- **Android** (`SlockSandboxWebViewRenderView.kt`): install a `WebMessagePort` pair on
  the WebView's main frame. Post the injectedJavaScript that binds `window.RaftHost.emit`
  to `port.postMessage(JSON.stringify({kind, payload, seq, ts}))`. On the native side,
  the port's `onMessage` handler forwards the string to the Kuikly event bus.
  `WebMessagePort` is preferred over `addJavascriptInterface` for the smaller attack
  surface: `addJavascriptInterface` exposes the entire Java object; `WebMessagePort`
  exposes only strings on a single named channel.
- **iOS** (`SlockSandboxWebView.m`): install a `WKScriptMessageHandler` on the
  `WKUserContentController` under the name `raftHost`. `injectedJavaScript` binds
  `window.RaftHost.emit` to `window.webkit.messageHandlers.raftHost.postMessage(JSON.stringify(...))`.
  The native handler dispatches through the Kuikly event bus.
- **OHOS** (`SlockSandboxWebView.ets`): use `webController.registerJavaScriptProxy`
  or the equivalent OHOS API for the string-only channel. Same shape.

Each platform's injectedJavaScript is a small, deterministic snippet emitted by the
KMP renderer, so the web guest sees the same `window.RaftHost` API regardless of
which host it is running under.

### Event vocabulary v1

```
session:logout                    (no payload)
session:server-deleted            (payload: { serverSlug: string })
session:server-left               (payload: { serverSlug: string })
session:server-switched           (payload: { serverSlug: string })
session:token-refresh-failed      (no payload)
```

Semantics:

- `session:logout` — the user completed logout on the web page (`useAuthStore.logout()`).
  Host must clear its own `StoredAuthSession` and return the user to the login screen.
- `session:server-deleted` — the user deleted `serverSlug`. Host must forget any
  cached identity for that server and route the user to a different server or to
  server-picker if none remain.
- `session:server-left` — the user left `serverSlug`. Host reaction is identical to
  `server-deleted` from the mobile shell's perspective (this server is no longer
  reachable), but the event is distinct because it is not a destructive action —
  server data still exists for other members.
- `session:server-switched` — the user picked a different server inside the WebView.
  Host updates its "last active server" preference.
- `session:token-refresh-failed` — the web app's refresh cycle failed. Host must
  re-run its own auth bootstrap; do not assume the token in `StoredAuthSession` is
  usable.

The web app emits these events as it currently would perform the visible action:
inside `authStore.logout()`, inside `serverService.leaveServer()` success, etc.

### Handshake and detection

Web:

```ts
export function isEmbedded(): boolean {
  return typeof window !== "undefined"
    && typeof (window as any).RaftHost === "object"
    && (window as any).RaftHost.version === "raft-host-v1";
}
```

Only web code that has an event to emit checks `isEmbedded()`. All other code —
including the layout code — continues to run identically inside and outside the
WebView. There is no branch on "am I embedded" for anything other than emitting
these signals.

Host:

- If `SlockSandboxWebViewEvent.hostMessage(...)` is never called by the shared
  page code, the platform WebView still installs the channel but no consumer
  processes the events. This is a debug-log condition, not an error.

### Failure modes

- Web emits an unknown kind → host silently ignores. This is a soft versioning path.
- Web is not embedded (regular browser) → `window.RaftHost` is undefined; `emit`
  short-circuits to a no-op.
- Native channel drops a message due to a lifecycle race (e.g., WebView is being
  torn down as the emit fires) → seq gaps are visible to the host and can be logged;
  we do NOT introduce ack/redelivery in v1 because the events that motivate this
  design are all "the WebView is about to close anyway" cases where re-delivery is
  meaningless.
- Malformed JSON reaches the host → catch at the parse boundary in KMP, log with
  `BridgeLogLevel.warn`, drop.

## Non-goals, restated for review

If any of these change scope, do NOT extend this document — write a new one:

- **RPC / return values.** Adds ack, timeout, request-id, error propagation. This
  is a full IPC and is what `HRBridgeModule` already provides for Kuikly pages.
- **State mirror.** Adds a schema for "auth state," "server list," "user identity."
  Once we have that, we have a duplicated auth store in two runtimes, and the two
  will drift. The current design has ONE piece of state — the event kind — and no
  shared state model.
- **Push events from host to web at runtime.** The existing `injectedJavaScript`
  covers page-load initialisation, and this document's use cases don't need runtime
  push. If we add it later, add it here explicitly, not by silently repurposing
  `injectedJavaScript`.
- **Custom binary protocols.** JSON strings only. If we ever ship high-frequency
  events (media, telemetry), we design that separately with dedicated channels.

## Acceptance for v1

- KMP shared adds `hostMessage(...)` event, one unit test that verifies the JSON
  parse boundary.
- Each of Android / iOS / OHOS implements the platform seam, plus one integration
  test that a page inside `SlockSandboxWebView` emitting `window.RaftHost.emit("session:logout")`
  results in the shared consumer receiving `("session:logout", {})`.
- `packages/web/src/embed/hostBridge.ts` in `botiverse/slock` implements `window.RaftHost`
  with the wire format above, plus behaviour tests that:
  - Outside a host (`RaftHost` undefined), `emit` is a no-op — no throw, no console
    error.
  - Inside a host, `emit` invokes the injected `postMessage` exactly once per call
    with the wire shape.
- One end-to-end scenario, driven from CI: web calls `RaftHost.emit("session:logout")`,
  host receives the pair `("session:logout", {})`.
- No changes to `injectedJavaScript` shape used by callers other than what the
  bridge installs itself.

## Owners and review

- **@Bugen** — unified DRI (design, web side, coordinating across platforms).
- **KMP shared** — @MingQi to review shared additions.
- **Android** — [TBD by artin/MingQi] to review `SlockSandboxWebViewRenderView.kt`.
- **iOS** — [TBD] to review `SlockSandboxWebView.m`.
- **OHOS** — [TBD] to review the ets renderer.
- **Product boundary** — @artin final sign-off; event vocabulary lives with him.

## Related documents

- `docs/mobile-webview-server-contract-2026-07-02.md` — session bootstrap direction; this
  document is the return path for the same session lifecycle.
- `docs/kuikly-bridge-module-contract.md` — the shared Kuikly bridge; explicitly OUT OF
  SCOPE here (native → shared, not WebView guest → native).
- `docs/ios-kmp-p0-host-bridge-spec-2026-06-27.md` — the iOS-side Kuikly bridge; same
  boundary as above.
