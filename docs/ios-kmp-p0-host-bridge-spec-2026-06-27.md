# iOS KMP P0 Host Bridge Spec

> **Obsolete (2026-10-20):** Kuikly-era iOS host spec (OpenKuiklyIOSRender). iOS ships on Compose Multiplatform; kept for history only.

Date: 2026-06-27

## Background

The Slock iOS KMP path is intentionally not enabled today. `compose/shared/build.gradle.kts` still has the iOS targets, CocoaPods plugin, generated framework wiring, iOS source set wiring, and `kspIos*` dependencies commented out.

`compose/iosApp` already exists as an iOS starter host with `OpenKuiklyIOSRender`, `SDWebImage`, a Podfile, and a minimal `HRBridgeModule`, so the missing work is not "create an app from nothing". The current gap is to make the host bridge and shared framework generation real enough for a first smoke path.

Per product direction, this first slice does not implement network. It should focus on the simple host capabilities that let shared/Kuikly code boot, log, route, and inspect basic platform behavior on iOS.

## P0 Goal

Deliver a first iOS host bridge version that can support a minimal Kuikly/KMP page smoke without adding network, realtime, push, auth, or database scope.

The P0 owner should prefer small, verifiable host capability methods over a broad iOS app integration. If enabling the iOS KMP target itself expands beyond the host-bridge task, document the blocker and coordinate a separate KMP build task instead of hiding that work inside native bridge changes.

## P0 Bridge Methods

Implement or normalize these simple iOS bridge capabilities first:

1. `log`
   - Map level/content/tag to `os_log` or `NSLog`.
   - Normalize missing or unknown levels to info.
   - Do not log secrets, auth tokens, cookies, full credentials, signed URLs, or raw `.env` content.

2. `copyToPasteboard`
   - Keep the existing pasteboard implementation.
   - Normalize parameter names and missing text behavior to match Android/OHOS where possible.

3. `openPage`
   - Push or present a `KuiklyRenderViewController` for the requested page.
   - Use identity-only pageData. Do not pass message arrays, task arrays, member snapshots, attachment payloads, or file bytes through route payloads.
   - Prefer existing shared launch helper shapes when available instead of constructing rich route state in Swift/Objective-C.

4. `closePage`
   - Pop the current navigation controller when possible.
   - Fall back to dismissing the current controller.
   - Treat a missing navigation controller as a no-op plus diagnostic log.

5. `openUrl`
   - Use `UIApplication.openURL` / modern equivalent.
   - Validate non-empty URL input.
   - This is allowed in P0 because it is a platform capability, not a shared network client.

6. `toast`
   - Provide a minimal best-effort user-visible toast/alert/overlay.
   - Avoid adding a large UI dependency for the first slice.

7. Time helpers
   - Implement `currentTimestamp`, `localServeTime`, and `dateFormatter` only if the current shared bridge contract requires them.
   - Keep the wire shape aligned with Android/OHOS.
   - Prefer moving date formatting into shared code later so hosts supply only clock primitives.

8. Performance marker helpers
   - Implement `performanceMarker` / `performanceScene` as local diagnostic logs for P0.
   - Do not wire a full profiler in this task.

9. `appBadge`
   - If the bridge contract calls it, update the iOS badge best-effort or log an explicit unsupported result when permission/runtime state prevents it.

10. Settings capability
   - If shared code asks for settings capability discovery, return a small explicit supported/unsupported capability response.
   - Do not invent broad settings flows in this first task.

## Explicitly Out Of Scope

- Network module / Ktor Darwin actual / `SlockApiClient` iOS networking.
- Realtime WebSocket, background reconnect, or APNs.
- Social auth.
- Attachment upload, file picker, and preview providers.
- SQLDelight / relational store, unless it is strictly needed to compile a minimal shared iOS framework.
- Full performance profiler integration.
- Full app-shell navigation parity.
- Any route payload that carries content snapshots instead of identity.

## Shared Extraction Candidates

While implementing iOS P0, watch for logic that should move to `shared` instead of being copied across Android/OHOS/iOS:

1. Bridge method names and parameter/result schemas.
   - Android/OHOS/iOS should not drift through stringly-typed method names.
   - A shared contract object or DTO helper can define stable bridge action names and field names.

2. Log level normalization.
   - Shared already has `BridgeLogLevel.fromWire`.
   - Host implementations should either reuse the same contract shape or keep only platform-specific sink mapping locally.

3. Route launch payload construction.
   - Route/pageData must remain identity-only.
   - Shared helpers such as Thread launch payload builders should own route payload shape; hosts should only pass the page name and identity data.

4. Date/time formatting.
   - Hosts should ideally expose clock primitives.
   - Human-readable formatting rules belong in shared unless platform APIs are explicitly required.

5. Performance marker parsing.
   - Hosts can own sinks, but event names and metadata keys should be shared.

6. Settings capability actions.
   - Supported action names and result enums should be shared so Android/OHOS/iOS report the same contract.

## Validation

Minimum validation for the first implementation:

1. Compile the iOS app / Pods path if the local environment supports Xcode/CocoaPods.
2. If full iOS KMP target generation is still blocked, document the exact Gradle/CocoaPods blocker.
3. Smoke the following host bridge calls:
   - page boot
   - `log`
   - `copyToPasteboard`
   - `openPage`
   - `closePage`
   - `openUrl`
   - time helper if enabled
4. Report which bridge methods are implemented, which are explicit unsupported stubs, and which still require a separate shared/KMP build task.

## Expected Deliverable

Create an implementation task for iOS P0 host bridge. The implementation should produce one small commit that either:

- implements the simple bridge methods and proves a minimal iOS smoke path, or
- documents the exact compile/build blocker before the host bridge can be exercised.

Network remains deferred by design.
