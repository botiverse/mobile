# Host↔Web Event Bridge — Implementation Status

Date: 2026-07-15

Companion to `docs/host-web-event-bridge-2026-07-15.md` (design, MingQi
DESIGN PASS `919c2cca…`). Captures the state of implementation across every
committed and pending piece so that work can resume against a written record
rather than message-thread archaeology.

Owner: @Bugen (unified DRI per @artin msg=0f7f0d59).
Reviewer: @MingQi (KMP + Android + iOS + OHOS lanes per @artin msg=48774a72).

## Committed and reviewed

### `botiverse/slock` PR #4849 — web side ✅ APPROVED

Exact head: `afda957b76d06d09934fc660d3d89c1a2fa5bb0a`. Approved by @MingQi
2026-07-15. Awaiting merge (`gh` is @stdrc; artin's implicit "MingQi merges
after CI" pattern for this class may apply, but no explicit authorization on
this exact yet; not pressing).

Contains:

- `packages/web/src/embed/hostBridge.ts` — `hasRaftHostEventBridge()`,
  `emitHostEvent(kind, payload?)`. Typed per-kind payload shapes. Whole
  detect + invoke inside try/catch (hostile getter / Proxy / throwing
  `.emit` cannot derail the caller).
- `packages/web/src/store/authStore.ts` — `session:logout` emit gated on
  `trigger === "explicit_user_logout"`, fired AFTER the local-clear
  sequence completes (reflects post-state, not intent).
- `scripts/check-host-bridge-callers.mjs` (lint) — layout-owner surfaces
  (`components/layout/**`, `components/ui/PanelHeader.tsx`, `embed.ts`)
  cannot import from `hostBridge`. Executable per-owner counterfactual test
  spawns the detector against each owner file with a corrupted copy.
- Tests: `hostBridge.test.ts`, `hostBridgeCallersDetector.test.ts`,
  `authStoreLogoutHostBridge.behavior.test.ts` (trigger matrix),
  `authStoreLogoutHostileBridge.behavior.test.ts` (hostile global × real
  `logout()` composition).

### `botiverse/mobile` PR #922 — design ✅ APPROVED

Exact head: `919c2cca…`. Approved by @MingQi. Design contract that PR #927
implements against.

## In progress — `botiverse/mobile` PR #927

Branch: `bugen/host-web-bridge-impl`. Current head: `ac9e71c`. Four commits
landed; all Android acceptance gates MUST ship in the same PR (MingQi
explicitly rejected "follow-up PR" for the tests + platforms).

Commits so far:

| SHA | Contents |
| --- | --- |
| `45b718d` | KMP shared: `SlockSandboxWebViewEvent.hostMessage(handler)`, `SlockSandboxWebViewAttr.hostEventsEnabled`, `RAFT_HOST_VERSION`, `KNOWN_HOST_EVENT_KINDS`, initial parse-boundary tests. |
| `c00e619` | Android v1: `WebViewCompat.addDocumentStartJavaScript` + `WebViewCompat.addWebMessageListener` under `allowedOriginRules = setOf(trustedOrigin)`; dual feature probe; callback-side re-check; UTF-8 4KB cap; `androidx.webkit:webkit@1.14.0` new dep in catalog + `androidApp/build.gradle.kts`. |
| `f1909a2` | Android r6-fix: immutable `initialTrustedOrigin`, per-registration ABA epoch, off-origin nav detach, `notifyHostMessage` try/catch, shared-owned `RAFT_HOST_VERSION`, expanded KMP parse-boundary tests. |
| `ac9e71c` | KMP shared: `sharedSettingsWebOrigin(webUrl): String?` — single origin authority (r7 #4 groundwork). 14 unit tests. |

## Pending work — all lands on the same branch, same PR

The r7 review (MingQi msg=8c25d295, 2026-07-15) flagged 4 blockers that
require re-shaping the Android side plus KMP shared parser refactor plus
integration tests. The plan below sequences them.

### 1. Wire `expectedHostEventOrigin` through the Composable API

- Add `expectedHostEventOrigin: String? = null` to `SlockSandboxWebView` and
  `SlockWebView` Composable signatures.
- Add `SlockSandboxWebViewAttr.expectedHostEventOrigin(value: String?)`.
- Plumb through `SlockSandboxWebViewNode.applyWebViewAttrs`.
- `PROP_EXPECTED_HOST_EVENT_ORIGIN` in the Android renderer's `setProp` /
  `resetProp` switch; forward to the controller's
  `setExpectedHostEventOrigin(next)`.

Env authority stays with the caller (`SharedApiEnvironmentStore.current().webUrl`
→ `sharedSettingsWebOrigin(webUrl)`); the renderer only compares equality.

### 2. Integrate `HostBridgeController` into the Android renderer

Draft state machine lives at `notes/design-drafts/HostBridgeController.kt.draft`
(workspace). Ship it into `compose/androidApp/src/main/java/build/raft/app/shared/`
with the corrections below applied.

**Corrections vs. `f1909a2`**:

- **Never permanently seal.** Remove `bridgeSealedOff` field entirely. The
  invariants are: `initialTrustedOrigin` is captured ONCE (immutable), and
  `maybeAttach()` only succeeds when `currentEligibleOrigin ==
  initialTrustedOrigin`. Off-origin navigation and non-matching src
  changes DETACH, but a subsequent return to the trusted origin
  re-attaches (@MingQi r7 #1).
- **Pre-load nav seam.** Add `onMainFrameNavigationPreload(url)` called
  from `WebViewClient.shouldOverrideUrlLoading` /
  `shouldInterceptRequest` (main frame), which bumps epoch + detaches
  BEFORE the new document begins loading. `onPageStarted` retains a
  second-line call as a belt-and-braces defense (@MingQi r7 #2).
- **Capture requires matching `expectedHostEventOrigin`.** The controller
  refuses to capture unless `candidate == expectedHostEventOrigin`. No
  env authority is derived in the renderer.

### 3. Shared parser + typed drop reasons

@MingQi r7 #3: the KMP parse-boundary tests currently hand-copy the
handler's logic in `runHandler()`, so the two can drift. Fix:

- Extract to `SlockSandboxWebViewEvent.parseHostMessageParams(params: Any): HostMessageResult`
  where `HostMessageResult` is a sealed class:

  ```
  sealed class HostMessageResult {
      data class Ok(val kind: String, val payload: JSONObject) : HostMessageResult()
      data object EmptyMessage : HostMessageResult()
      data object MalformedJson : HostMessageResult()
      data class MalformedKind(val kind: String) : HostMessageResult()
      data class UnknownKind(val kind: String) : HostMessageResult()
      data object MissingPayload : HostMessageResult()
      data class InvalidPayloadType(val type: String) : HostMessageResult()
  }
  ```

- `hostMessage(handler)` becomes: parse, warn-log the drop reason with a
  named event family (`bridge_shared_parse_drop reason=…`), invoke handler
  only on `Ok`.
- Tests exercise `parseHostMessageParams` directly — production and test
  share the same parser; a change to the parser instantly propagates to
  every test call site.

### 4. Android JVM contract tests via fake `BridgeSeam`

With the controller extracted and injectable, all @MingQi-required v1
acceptance gates become plain JUnit tests:

| Gate | Fake seam scenario |
| --- | --- |
| 2×2 feature matrix — both true (positive) | `isFeatureSupported = true` → capture on eligible src → attach; message delivered. |
| 2×2 — DOCUMENT_START_SCRIPT false, WEB_MESSAGE_LISTENER true | `isFeatureSupported = false` → no attach, no listener, no `RaftHost` visible. |
| 2×2 — DOCUMENT_START_SCRIPT true, WEB_MESSAGE_LISTENER false | Same fail-closed no-op. |
| 2×2 — both false | Same. |
| Origin/main-frame — cross-origin frame | Fake seam invokes callback with `sourceOrigin != trustedOrigin` → drop. |
| Origin/main-frame — subframe | Callback with `isMainFrame = false` → drop. |
| Immutable origin + return | `updateSrc(trusted)` → attach; `updateSrc(other)` → detach + no re-attach; `updateSrc(trusted)` → re-attach. |
| ABA | Detach; fake replays a stale callback with old epoch → dropped. |
| UTF-8 byte cap | Callback with 5000-byte UTF-8 message → drop; 4096-byte → deliver. |
| Consumer throw | Handler throws → controller catches; state remains valid; next message still delivers. |
| Document-start ordering | Fake seam records call order; assert bridge-script install precedes any inbound message. |
| Non-clobber | Bridge script uses `Object.defineProperty(...writable=false, configurable=false)`; assert emitted script text pins those flags. |

### 5. iOS + OHOS

Once the Android shape passes review, mirror the pattern:

- **iOS** `compose/iosApp/iosApp/KuiklyExpand/Components/SlockSandboxWebView.m`:
  `WKUserScript(atDocumentStart, forMainFrameOnly=true)` + `WKScriptMessageHandler`
  on `WKUserContentController`. Inbound handler validates
  `message.frameInfo.request.URL.origin == trustedOrigin`. Bridge script
  uses `window.webkit.messageHandlers.raftHost.postMessage`.
- **OHOS** `compose/ohosApp/entry/src/main/ets/kuikly/components/SlockSandboxWebView.ets`:
  `registerJavaScriptProxy` equivalent. Same main-frame + origin gate.
- Both platforms consume the same `HostBridgeController` state-machine
  pattern (Swift/ArkTS ports) with their platform-specific `BridgeSeam`
  implementations. The pattern is portable; the classes are re-implemented.

## Estimated remaining work

Item | Estimate
--- | ---
1. `expectedHostEventOrigin` plumbing | 30 min
2. `HostBridgeController` integration + r7 fixes | 90 min
3. Shared parser + typed drop reasons | 60 min
4. Android JVM contract tests (12+ cases) | 90 min
5. iOS impl + tests | 3–4 hours
6. OHOS impl + tests | 3–4 hours

Total mobile-side remaining: roughly 1.5 focused sessions.

## Server Profile boundary reminder

Per MingQi's boundary note on the design PASS: **Server Profile stays
unwired** until bridge + canonical session reconciliation are verified end
to end. Do NOT enable `?embed=raft-settings-v1&shell=host` on
`/s/{slug}/settings/server` until that landing.
