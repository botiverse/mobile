# Host↔Web Event Bridge — Remaining Closure Ledger

Date: 2026-07-15  
Canonical design: `mobile/docs/host-web-event-bridge-2026-07-15.md`  
Approved design exact: `919c2cca8cd964f322f90d830c178adfebceff5c`  
Implementation PRs: Web `botiverse/slock#4849`; Mobile `botiverse/mobile#927`

## Current status

- Web helper + explicit logout callsite is approved at `afda957b76d06d09934fc660d3d89c1a2fa5bb0a`.
- Mobile KMP/Android implementation remains changes-requested.
- Latest reviewed mobile groundwork exact: `ac9e71cd5b42edce631aeec41503346173b59b29`.
- iOS, OHOS, canonical host reconciliation, remaining Web callsites, and Server Profile wiring are not complete.
- Server Profile must remain unconnected until this ledger is closed and exact-head hosted gates pass.

## A. Shared contract

- [ ] Factor the real production `parseHostMessageParams(params)` parser.
  - Return typed success/drop results such as `Ok(kind, payload)`, `EmptyMessage`, `MalformedJson`, `MalformedKind`, `UnknownKind`, `MissingPayload`, and `InvalidPayloadType`.
  - `SlockSandboxWebViewEvent.hostMessage` and shared tests must execute the same production parser.
  - Delete the mirrored test-only `runHandler` implementation.
  - Unknown/malformed/wrong-shape drops must enter the shared warn-log family with a typed reason.
- [ ] Finish `sharedSettingsWebOrigin` canonicalization.
  - Accept only HTTP(S) absolute URLs with a valid host.
  - Normalize scheme and host case.
  - Normalize effective ports: implicit HTTPS equals `:443`; implicit HTTP equals `:80`.
  - Preserve non-default ports.
  - Support bracketed IPv6, including Developer URLs such as `http://[::1]:5173`.
  - Validate explicit ports in `1..65535`.
  - Reject userinfo, whitespace/control characters, missing hosts, malformed authorities, and non-HTTP(S) schemes.
- [ ] Add `expectedHostEventOrigin` to the shared WebView contract.
  - A session-mutating callsite derives both `src` and `expectedHostEventOrigin` from the same configured `webBaseUrl`.
  - The environment authority remains `SharedApiEnvironmentStore.current().webUrl` / `configuredWebBaseUrlOrNull()`.
  - Shared code passes the expected origin through Composable → attr → platform.
  - Default is null/off; ordinary WebViews expose no bridge.

## B. Android controller and seam

- [ ] Extract `HostBridgeController` behind an injectable `BridgeSeam`.
  - The renderer owns KRView/WebView plumbing only.
  - The controller owns capture, eligibility, install/detach, navigation decisions, lifecycle, ABA, size limits, and consumer exception handling.
- [ ] Implement immutable initial-origin semantics.
  - Capture once only when `origin(initialSrc) == expectedHostEventOrigin`.
  - Never repin to a later origin.
  - Off-origin or blank navigation detaches but does not permanently seal the renderer.
  - Returning to the original trusted origin can re-attach.
- [ ] Detach at the pre-load main-frame navigation seam.
  - Invalidate the epoch and detach before an off-origin target document starts.
  - `onPageStarted` remains a defensive second check, not the primary lifecycle gate.
  - `allowNavigation=true` may widen navigation behavior but never bridge authority.
- [ ] Enforce per-registration lifecycle/ABA safety.
  - Every listener registration captures a fresh epoch/token.
  - Detach, destroy, replacement, and attach rollback invalidate old tokens before cleanup.
  - A queued callback from an old listener must deliver zero events to a replacement consumer.
- [ ] Retain AndroidX WebKit invariants.
  - Require both `DOCUMENT_START_SCRIPT` and `WEB_MESSAGE_LISTENER`; either false is a complete no-op.
  - Listener and document-start script use the same exact-origin rule set.
  - Callback rechecks canonical `sourceOrigin == trustedOrigin` and `isMainFrame == true`.
  - The pinned dependency remains `androidx.webkit:webkit:1.14.0`.
- [ ] Retain safety and script invariants.
  - Enforce a 4096-byte UTF-8 raw-message cap.
  - Catch and log consumer exceptions without propagating into WebKit/Kuikly.
  - Install `window.RaftHost` at document start.
  - Freeze and make `RaftHost` non-writable/non-configurable so later auth seed JavaScript cannot clobber it.
  - Read the version from the shared `RAFT_HOST_VERSION` single source of truth.

## C. Android executable gates — must land in mobile PR #927

- [ ] Preserve test-first commit history for the controller oracle.
- [ ] Dual-feature support matrix:
  - `DOCUMENT_START_SCRIPT=false`, listener=true → no listener and no script.
  - document-start=true, `WEB_MESSAGE_LISTENER=false` → no listener and no script.
  - both=true → complete bridge install and delivery.
- [ ] Origin and main-frame gates.
- [ ] Immutable origin, off-origin detach, and return-to-trusted-origin re-attach.
- [ ] Pre-load detach occurs before the target document starts.
- [ ] ABA stale-listener callback delivers zero events.
- [ ] UTF-8 cap boundaries, including CJK and emoji payloads.
- [ ] Throwing consumer is contained and logged.
- [ ] Partial-install rollback leaves no half-bridge.
- [ ] Document-start ordering probe sees `RaftHost.version` before page/user code.
- [ ] Non-clobber probe proves reassignment cannot replace the bridge.
- [ ] Real AndroidX seam uses the expected listener/script origin rules.
- [ ] End-to-end Android path: Web emits logout → native `{message: raw}` → shared receives `("session:logout", {})`.
- [ ] Hosted sign-off, Android compile/unit, connected-test APK, and emulator gates pass at one immutable exact.

## D. iOS — only after Android controller shape passes

- [ ] Use `WKUserScript` at `.atDocumentStart` with main-frame-only injection.
- [ ] Use the same expected-origin/immutable-origin controller semantics.
- [ ] Reject non-main-frame or wrong-origin `WKScriptMessage` input.
- [ ] Implement pre-navigation detach, same-origin re-attach, per-registration ABA token, UTF-8 cap, and exception containment.
- [ ] Keep bridge and auth-seed scripts separate; bridge installs first and cannot be clobbered.
- [ ] Add the equivalent positive, negative, lifecycle, ordering, and non-clobber gates.
- [ ] Hosted iOS compile/test gates pass at the final exact.

## E. OHOS — only after Android controller shape passes

- [ ] Use the OHOS document-start caller-JS API plus a string-only proxy channel.
- [ ] Use the same expected-origin/immutable-origin controller semantics.
- [ ] Enforce main-frame, exact origin, pre-navigation detach, same-origin re-attach, ABA, UTF-8 cap, and exception containment.
- [ ] Keep bridge and auth seed separate and non-clobbering.
- [ ] Add equivalent controller and real-seam gates.
- [ ] Hosted shared/Hvigor and required HDC/device gates pass at the final exact.

## F. Reconciliation and product wiring

- [ ] Add the KMP host-event consumer and canonical reconciliation flow.
  - `session:logout` is the only unilateral local-session destruction event.
  - `server-deleted`, `server-left`, `server-switched`, and `token-refresh-failed` are wake/intents only.
  - The host must reconcile through its own canonical session/server/membership APIs before mutating state.
  - No RPC, state mirror, runtime host push, or JSI is introduced.
- [ ] Add and review the remaining Web callsites one by one.
  - Server delete success.
  - Server leave success.
  - Server switch success.
  - Token-refresh-failed timing.
  - Each callsite must emit only after the relevant Web action reaches its approved completion point.
- [ ] Wire Server Profile last.
  - Enable host events only after the shared/platform bridge and reconciliation are complete.
  - Unsupported Android feature combinations must prevent session-mutating Web flows rather than silently opening a deaf/half bridge.
- [ ] Run one exact-head cross-repository closure.
  - Web helper/callsites.
  - KMP shared parser/consumer.
  - Android, iOS, and OHOS seams.
  - Canonical reconciliation.
  - Server Profile integration.
  - Hosted platform gates and runtime package validation.

## Review order

1. Production shared parser + typed result tests.
2. `BridgeSeam` and controller test-first oracle commit.
3. Controller implementation until the oracle is green.
4. Renderer/expected-origin wiring and real AndroidX seam gates.
5. Immutable all-green Android/KMP exact review.
6. Copy the approved controller semantics to iOS and OHOS with platform-specific gates.
7. Add reconciliation and remaining Web callsites.
8. Wire Server Profile and run end-to-end validation.

