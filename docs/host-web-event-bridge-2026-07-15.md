# Host↔Web Event Bridge — Draft Design

Date: 2026-07-15

Revision: 3 (addresses @MingQi CHANGES_REQUESTED review on r2).

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

**Trusted origin + main-frame-only (@MingQi r2 #1).** The trusted origin is derived from the WebView's own initial `src`, not from a document-level allowlist:

```
trustedOrigin = origin(initialSrc)     // scheme + host + effective port
```

That is the SINGLE authority. No static per-environment list. `initialSrc` for a production WebView on today's stack is `https://app.raft.build/...`; on staging it is `https://raft-app-staging.botiverse.dev/s/botiverse/...`; in Developer mode it is whatever `webUrl` was configured. The OTHER two hostnames (`app.slock.ai`, `slock-app-staging.botiverse.dev`) currently 301/302 to those origins and are therefore never the effective initial `src`. Any static allowlist is by definition a second authority that will drift.

**Pre-condition for capture.** `trustedOrigin` is captured ONLY after `initialSrc` passes the same URL/environment eligibility that the existing auth-bootstrap uses (HTTP(S) scheme, parses via the platform URL parser, environment matches the app's active env). If eligibility fails the bridge is not installed at all — `hostEventsEnabled=true` becomes a runtime no-op, not an implicit widening. If a legacy URL (e.g. an `app.slock.ai` `initialSrc` that would 301 to `app.raft.build`) is passed, `trustedOrigin` is captured as the LEGACY origin; the subsequent redirect is cross-origin from `trustedOrigin`'s perspective, and the bridge MUST remain detached across that navigation. Do NOT treat "we don't currently pass legacy URLs" as a safety invariant — treat that only as a scheduling fact.

Platform implementations MUST:

- **Android**: pin `trustedOrigin` at attach time from the WebView's initial URL. Bridge is installed via `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(trustedOrigin))` and messages arrive via `WebViewCompat.addWebMessageListener(webView, "raftHostPort", setOf(trustedOrigin), listener)` — both use the SAME exact-origin `allowedOriginRules` set. The listener's `onPostMessage` MUST additionally verify `sourceOrigin.toString() == trustedOrigin` AND `isMainFrame == true` before dispatching; the `allowedOriginRules` filter is a first line, the callback-side re-check is the second.
- **iOS**: pin `trustedOrigin` at the WebView owner. Register the `WKUserScript` with `injectionTime = .atDocumentStart` and `forMainFrameOnly = true`. In `WKScriptMessageHandler.userContentController(_:didReceive:)`, reject any message whose `message.frameInfo.request.URL.origin` is not equal to `trustedOrigin`.
- **OHOS**: pin `trustedOrigin` on the web component. Install the proxy for the main frame only; gate inbound on origin.

**Redirects and navigation.** If a navigation resolves to an origin != `trustedOrigin`, tear down the handler/port BEFORE the new document loads (Android `WebViewClient.shouldOverrideUrlLoading`; iOS `WKNavigationDelegate.decidePolicyFor navigationAction:`; OHOS equivalent). Re-attach only if a subsequent navigation returns exactly to `trustedOrigin`. Do NOT try to "widen" `trustedOrigin` after attach — a WebView whose initial `src` was `app.raft.build` never gains permission to send to a different origin.

Without this pin, any page reached from a session-enabled WebView (or any iframe) could emit `session:*` events — a same-origin escape via the bridge.

## Contract

### Ownership split (@MingQi r1 #8, tightened r2 #2)

Cleanly separated:

- **Platform** owns `window.RaftHost` (the injected global). Each platform injects a bridge script AT DOCUMENT-START, into the MAIN FRAME, only when `hostEventsEnabled = true` and the WebView's `trustedOrigin` is set.
- **KMP shared** owns the CONTRACT the bridge script implements — the `raft-host-v1` version string, the `window.RaftHost` object shape (`.version`, `.emit(kind, payload)`), and the wire schema. Shared publishes this as a template + constants, NOT as an executable JS blob. Reason: injection today is not a single shared code path — iOS/OHOS inject document-start caller JS through their WebView user-script APIs, while Android bootstraps via HTML plus `page-finished evaluate`. Pretending "one JS string, three platforms" would misdescribe production.
- **Web** (`packages/web/src/embed/hostBridge.ts` in `botiverse/slock`) owns a safe HELPER — `hasRaftHostEventBridge()` and `emitHostEvent(...)`. Web does NOT provide the `emit` implementation; web calls the injected `window.RaftHost.emit`.

**Composition with the existing auth `injectedJavaScript`.** The bridge script and the auth-seed script are TWO SEPARATE privileged scripts. Each platform composes them per its own script model:

- **iOS**: two `WKUserScript`s. Bridge script → `injectionTime = .atDocumentStart`, `forMainFrameOnly = true`. Auth-seed script → whatever the existing `mobile-webview-server-contract-2026-07-02.md` bootstrap requires (currently after document-start). The two are separate registrations; iOS makes their relative ordering explicit.
- **OHOS**: analogous — two independent document-start caller-JS registrations, bridge first.
- **Android**: bridge is injected via `WebViewCompat.addDocumentStartJavaScript` — the sole qualifying path. **Bootstrap HTML and `onPageFinished evaluate` do NOT qualify** and MUST NOT be used to install the bridge script: bootstrap HTML dies at `window.location.replace(targetUrl)`, and `onPageFinished` fires after the target document's React reads `window.RaftHost`. Auth seed continues to run via `onPageFinished evaluate`; it runs strictly after the bridge script (which is document-start on the target document).

**Non-clobber requirement.** The auth-seed script MUST NOT overwrite `window.RaftHost`, `window.RaftHost.version`, or `window.RaftHost.emit`. The bridge script defines `RaftHost` with `Object.defineProperty(..., { writable: false, configurable: false })` and freezes the object; a later script attempting to reassign gets a strict-mode throw or silent no-op in sloppy mode. Tests below verify this.

**Test requirements per platform (see also §Safety limits):**

- The web helper — before ANY React render or `emitHostEvent` call — observes `window.RaftHost.version === "raft-host-v1"` on the injected page. (Proves ordering: bridge before app JS.)
- Attempting to reassign `window.RaftHost = {}` from the page's own JS leaves the original object intact. (Proves non-clobber.)
- Removing the bridge script from the platform's registration (counterfactual) turns the helper's positive detection false. (Proves the gate is a real gate, not a tautology.)

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

KMP shared owns a TEMPLATE for the bridge script — the object shape (`RaftHost.version`, `RaftHost.emit`), the wire schema, the version string. Each platform assembles the actual JS from that template and injects it via its own document-start seam. There is NO shared executable JS blob.

Reference script shape (each platform inlines with its own `post` implementation and its own JS-source-manifest style):

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

- **Android** (`SlockSandboxWebViewRenderView.kt`): the specific document-start seam MUST be `androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(trustedOrigin))` (backed by `WebView` feature `DOCUMENT_START_SCRIPT` on WebView 83+). Inbound: `WebViewCompat.addWebMessageListener(webView, "raftHostPort", setOf(trustedOrigin), listener)`. The listener's `onPostMessage` receives strings, wraps as `mapOf("message" to raw)`, and forwards to the Kuikly event bus.

  This is the load-bearing choice @MingQi r3 flagged: the current renderer's *bootstrap HTML* is a transient document that dies at `window.location.replace(targetUrl)`, so `window.RaftHost` installed inside it does NOT survive into the target document. `onPageFinished evaluate` runs AFTER the target's React reads the global — too late. Only `addDocumentStartJavaScript` runs on the target document, before React, with an exact-origin rule. `addJavascriptInterface` is rejected on two counts: it exposes an entire Java object (wider attack surface) and it runs at the whole-webview level (no per-target-document isolation).

  **Support / fallback.** Both AndroidX WebKit features are required and MUST both be probed at attach time:
  - `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` — needed to inject the bridge script.
  - `WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)` — needed to receive messages back.

  If EITHER feature is unsupported, `hostEventsEnabled=true` MUST resolve to a no-op — the bridge is NOT installed, no listener is registered, and the WebView-owner surface MUST NOT allow session-mutating flows on that device. Probing only one leaves a "half-bridge" (script injected but no receive channel, or receive channel with no script — both silently broken). This is the fail-closed contract; the gate below exercises each of the three possible support states.

  **New dependency.** The mobile repo does not yet include AndroidX WebKit. Implementation MUST add a version-catalog entry — a pinned `androidx.webkit:webkit` — and route it through the existing `compose/androidApp/build.gradle.kts` KMP shared-target dependency block. Hosted Android compile and Android unit tests are the acceptance gates for that addition; no local install per @artin's workflow.

- **iOS** (`SlockSandboxWebView.m`): the seam is `WKUserContentController.addUserScript(WKUserScript(source: script, injectionTime: .atDocumentStart, forMainFrameOnly: true))` for the bridge script, and `addScriptMessageHandler(handler, name: "raftHost")` for the inbound channel. Inbound: reject any `WKScriptMessage` whose `message.frameInfo.request.URL.origin` is not equal to `trustedOrigin`. Dispatch through Kuikly with `@{@"message": raw}`.

- **OHOS** (`SlockSandboxWebView.ets`): the seam is the ArkTS Web component's document-start user-script API (equivalent to iOS `WKUserScript` semantics) plus the string-only proxy channel. Main-frame-only. Inbound: reject messages whose page origin != `trustedOrigin`. Same `{message: raw}` shape into Kuikly.

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

## Safety limits and lifecycle (@MingQi r1 #6, tightened r2 #3)

**Responsibility split.** Two layers of enforcement, deliberately separated:

- **Native / platform layer** — runs BEFORE Kuikly event-bus dispatch. Enforces the boundary controls that require platform APIs:
  - Origin gate (`message.origin === trustedOrigin`)
  - Main-frame-only gate
  - Lifecycle: detach on renderer destroy / on navigation off `trustedOrigin`; per-instance registration token to prevent ABA delivery
  - **UTF-8 byte size cap: `raw.utf8Bytes <= 4096`** — measured on the wire-encoded bytes, not on JS string `.length` (which counts UTF-16 code units and would mis-cap CJK / emoji payloads). Oversized messages are dropped whole, no partial parse, warn log.
- **Shared KMP layer** — runs INSIDE the Kuikly `hostMessage` handler on the params-map boundary. Enforces the semantic checks that are the same on all platforms:
  - JSON parse of the `message` string field
  - `kind` matches `^[a-z][a-z0-9]*(:[a-z][a-z0-9-]*)+$`, total length 4–64 chars
  - `kind` is in the current known vocabulary — unknown kinds are dropped with a warn log (soft versioning: consumer must not receive kinds it did not declare support for)
  - `payload` present and is a JSON object (`{}` fine; primitive/array/null dropped)

The split matters because r1 conflated them: it described "native parse boundary" while placing the parser in `SlockSandboxWebViewEvent`. The corrected shape: native gates the CHANNEL, shared parses the CONTENT. Each layer's failures land in the same warn-log family but at different call sites, and platform tests exercise the native layer while a shared unit test exercises the parser.

**Lifecycle / ABA safety.** Platform implementations MUST:

- Close the message handler / port pair when the renderer view is destroyed (Android `WebView.destroy`, iOS view controller `deinit`, OHOS equivalent).
- Detach on main-frame navigation to any URL whose origin is not equal to `trustedOrigin`; re-attach only if a subsequent navigation returns exactly to `trustedOrigin`.
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

- Unknown `kind` (not in the current known vocabulary) → dropped with a warn log at the shared KMP layer. Consumer never observes it. Soft versioning: a v2 web app running on a v1 host degrades to no delivery, not to silent acceptance of an unrecognised event.
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
- One end-to-end scenario, CI-driven: web calls `emitHostEvent("session:logout")` inside a WebView with `hostEventsEnabled=true` whose `initialSrc` origin was captured as `trustedOrigin` → the shared consumer receives `("session:logout", {})`.
- **Document-start ordering gate** (per platform): a page loaded into the WebView observes `window.RaftHost.version === "raft-host-v1"` from JS that runs BEFORE any React render / any user-code `emit` call. Verified by a probe script that reads the global at document-start and reports via the bridge itself; the absence of that report is the red signal.
- **Non-clobber gate** (per platform): a page attempting to reassign `window.RaftHost = {}` after document-start leaves the original bridge intact and continues to deliver events. Frozen `defineProperty` is the mechanism; the gate proves the mechanism.
- **Android WebView-feature fallback gates** (3 cases, covering the full support matrix):
  1. `DOCUMENT_START_SCRIPT=false, WEB_MESSAGE_LISTENER=true` → `hostEventsEnabled=true` is a no-op; target document does NOT see `window.RaftHost` and no listener is registered.
  2. `DOCUMENT_START_SCRIPT=true, WEB_MESSAGE_LISTENER=false` → same fail-closed no-op; no half-bridge in which the script exists but its channel is deaf.
  3. `DOCUMENT_START_SCRIPT=true, WEB_MESSAGE_LISTENER=true` (positive) → bridge installed, listener registered, end-to-end emit path works.

  Each case stubs the `WebViewFeature.isFeatureSupported` probe deterministically. The two negative cases guard against the "half-bridge" silent degradation that the r3-delta hardened for.
- **Android dependency gate**: the version catalog carries a pinned `androidx.webkit:webkit`; the shared-target dependency block wires it into the Android configuration. Hosted Android compile + unit tests pass. No new floating semver ranges.
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

- **r3 delta v2 (2026-07-15):** addresses @MingQi CHANGES_REQUESTED on `e968221…`. Four mechanical fidelity fixes:
  (1) **Trust-boundary Android bullet** rewritten to use `WebViewCompat.addDocumentStartJavaScript` + `WebViewCompat.addWebMessageListener` with SAME `allowedOriginRules`, plus callback-side `sourceOrigin == trustedOrigin` + `isMainFrame == true` re-check. Removed the earlier `WebMessagePort.postMessage(targetOrigin)` language that contradicted the Native seams section.
  (2) **Composition Android bullet** no longer says "bootstrap HTML AND/OR document-start capable path". Sole qualifying path is `WebViewCompat.addDocumentStartJavaScript`; bootstrap HTML and `onPageFinished evaluate` are explicitly disqualified with the reasons.
  (3) **Feature probe** now covers BOTH `DOCUMENT_START_SCRIPT` AND `WEB_MESSAGE_LISTENER`. Either false → no-op. Probing only one leaves a "half-bridge" silent-degradation path.
  (4) **New Android dependency** — `androidx.webkit:webkit` — called out in scope with hosted-CI acceptance. Fallback gates in acceptance section now cover the full 2×2 support matrix (both true positive, either false → fail-closed).
- **r3 (2026-07-15, superseded 2 minutes later by r3 delta below):** initial delta addressed @MingQi r2. Original three fixes:
  (1) `trustedOrigin = origin(initialSrc)` replaces the static allowlist.
  (2) KMP shared owns contract, not shared JS blob; bridge script + auth-seed script are independent privileged registrations per platform.
  (3) native/shared responsibility split; UTF-8 byte cap not JS `.length`.

  On re-review @MingQi (r3 CHANGES_REQUESTED) flagged three residual issues in the DOC's fidelity to those fixes — this file at r3+delta now carries the corrections:
  - **Android document-start seam pinned to `WebViewCompat.addDocumentStartJavaScript`** with an exact-origin rule set, plus a `WebViewFeature.DOCUMENT_START_SCRIPT` support probe + fail-closed fallback (no silent degradation to `page-finished evaluate`, which is too late for the target document's React).
  - **Origin pre-condition** made explicit: `trustedOrigin` is only captured after `initialSrc` passes existing auth-bootstrap eligibility. A legacy URL passed as `initialSrc` captures the legacy origin, then the redirect is cross-origin from `trustedOrigin`'s view and the bridge stays detached. Do not rely on "we don't currently pass legacy URLs" as an invariant.
  - **Residual r2 language purged** in three places: Native seams no longer says "shared JS generator"; iOS/lifecycle no longer says "allowlist"; failure modes no longer says "unknown kind silently ignored" (it's now warn+drop per the shared layer). Revision header updated to r3.
- **r2 (2026-07-15):** addresses @MingQi CHANGES_REQUESTED review on r1. Substantive additions:
  (1) `hostEventsEnabled` opt-in attr, (2) trusted-origin + main-frame-only requirement,
  (3) params-map boundary alignment (`{message: raw}`), (4) `seq`/`ts` removed from v1,
  (5) "wake/intent not canonical truth" semantics, (6) safety limits + lifecycle/ABA guards,
  (7) `hasRaftHostEventBridge()` name, orthogonal to layout embed, (8) ownership split
  clarified: platform owns the global, web owns the helper; injection order documented.
- **r1 (2026-07-15):** initial draft.
