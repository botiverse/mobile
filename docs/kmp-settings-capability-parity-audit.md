# KMP Settings Capability Parity Audit

Date: 2026-05-23
Last refreshed: 2026-06-11

Status: historical snapshot. Do not use this file as the current Settings
parity source of truth without refreshing it against code, the task board, and
`docs/react-kmp-feature-map-2026-06-26.md`.

Scope: align the shared KMP Settings product route with the React Settings
source while keeping Settings state, business logic, actions, and effects in
`commonMain` MVI.

This is a capability audit, not just a visual audit. A Settings row rendering in
KMP is not enough; the route must load the same product data, preserve loading
state, expose the same supported actions, and route unsupported platform-only
items through explicit disabled/empty states.

## React Source Baseline

Reference source: `/Users/artin/Downloads/Data/Slock.html`, saved from
`https://app.slock.ai/s/slock-android/settings/server`.

React Settings sidebar:

| React item | React icon | Android/KMP decision |
| --- | --- | --- |
| Account | `User` | Keep as Settings route. Must be API/repository backed, not only auth-session backed. |
| Browser | `AppWindow` | Exclude from Android/Harmony Settings unless product asks for a platform browser route. |
| Server | `Building2` | Keep as Settings route. Must render Profile, Admins, Plan & Billing, Pending Invites, Join Links, Onboarding Agent, Danger Zone. |
| Release Notes | `FileText` | Missing from KMP Settings. Add as an explicit follow-up if Settings should match the React sidebar fully. |

React Server detail sections observed in the saved page:

- Profile
- Admins
- Plan & Billing
- Pending Invites
- Join Links
- Onboarding Agent
- Danger Zone

Android/KMP also has product-specific routes that are not in the React Settings
sidebar but are valid mobile/native Settings surfaces: Computers, Billing,
Mobile, Storage, About, and Diagnostics.

## Current KMP State

The shared Settings product path is `SettingsAppShellContent` in
`compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt`.

The current shared MVI boundary exists in
`compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedSettingsStore.kt`:

- `SharedSettingsState`
- `SharedSettingsIntent`
- `SharedSettingsEffect`
- `SharedSettingsStore`
- `SharedSettingsViewModel`
- `SharedSettingsDataSource`
- `SharedSettingsPlatformCapabilities`

This means the architecture baseline from task #34 is present. New Settings
work must extend this shared store/data-source/effect boundary instead of adding
route-local mutable business state in UI.

Current app-shell wiring constructs `SharedSettingsDataSource` with a real
`RealSettingsRepository`, notification/local preference repositories, and
platform capabilities. Repository-backed Account, Computers, Server, Billing,
Mobile, Storage, About, and Diagnostics slices have all moved into shared MVI
or are in review from #35-#38/#43/#44/#48/#49.

The remaining open Settings development slice is task #39: Server Settings
mutations with shared confirmations. Several typed mutation endpoints already
exist in `SettingsRepositoryDataSource`, so #39 should verify the UI
confirmation/in-progress/error flow and product coverage rather than starting
from "no repository".

About and Diagnostics were migrated after task #43:

- About loads app/session/device/runtime info through shared platform capability.
- About actions wire Diagnostics navigation, copy build/version, open Slock, and feedback.
- Diagnostics wires copy/share/clear/send feedback through shared effects and host capability.

## Product IA Decision

KMP Settings should use this visible product IA for now:

1. Account
2. Computers
3. Server
4. Billing
5. Mobile
6. Storage
7. About

Diagnostics stays as a detail route opened from About. Browser remains excluded.
Router/smoke previews must not be visible in the product Settings home; they can
remain available only through explicit test/harness routes. Release Notes is
still a React parity gap and should be added deliberately, not hidden inside
About or a smoke route.

## Capability Matrix

| Area | Current KMP status | Gap / risk | Canonical follow-up |
| --- | --- | --- | --- |
| Settings MVI boundary | Migrated | Extend it consistently; do not add UI-local state machines. | task #34 complete; enforce in all new Settings tasks. |
| Product IA | Partial | React Browser excluded correctly, but React Release Notes is missing. Router/smoke was product-visible before this audit. | Add Release Notes route if product wants React sidebar parity; keep Router harness-only. |
| Click/action wiring audit | In Review | task #44 audited visible Settings row/action wiring; remaining misses should be filed as concrete row/action bugs. | Keep #44 as validation, not a new broad migration. |
| Account | Mostly migrated | task #38 wired API-backed Account data; Account saveProfile typed boundary exists. Connected account edge cases should be verified by Product/QA. | Use focused follow-ups for any failed Account action, not a broad Account migration task. |
| Computers | In Review | task #35 restored Computers list/detail through shared store/repository wiring. | Validate real data, detail rows, retry/empty/error; add navigation follow-up only if Product wants agent-detail jumps. |
| Computer detail | In Review | Computer detail is shared/read-only by default; inert `openAgent` was removed from this slice. | If agent detail navigation is required, add a separate shared navigation effect. |
| Server page | Partial / focused follow-up | Server data is repository-backed from #38, and many typed mutation endpoints exist. Confirmation UX and complete mutation coverage remain task #39. | task #39 should implement/verify confirmations, pending/error state, and rollback/toast behavior. |
| Billing | Mostly migrated | Billing data is loaded through shared repository snapshots from #38. | Keep permission/empty/error validation in #38 review; add focused Billing bugs only if live data fails. |
| Mobile | Wired/partial | Notification preference, permission/settings/test notification feedback, locale, and message-alignment local preferences now flow through shared Settings MVI. Server mute remains hidden/disabled until a real API/capability is confirmed. | task #36 complete; future server mute follow-up if needed. |
| Storage | Wired/partial | Shared MVI now drives refresh/clear and Android returns live storage snapshots; OHOS exposes explicit unsupported/disabled rows until a native storage API is available. | task #37 complete. |
| About/Diagnostics | Migrated/wired | Core actions are wired; task #37 reverified the Diagnostics route while keeping copy/share/clear/send-feedback behind shared effects/host capabilities. | task #43 complete; task #44 audits all visible rows. |
| Loading/cache | Partial | Store preserves existing content on refresh failure, but most real repository/cache sources are not connected. | tasks #35-#39 by slice. |
| Realtime | Partial | Shared realtime now maps machine status/capability/update, server membership/deleted/plan, announcement, and reminder events into refresh intents, but Settings-specific fine-grained local mutation and OHOS token-refresh proof are still open. | Track remaining work as focused code/task-board follow-ups; add Settings realtime/cache hardening only for surfaces that still go stale. |

## Implementation Order

1. task #44: perform a route/action wiring audit against the product Settings
   IA above. Every visible row/action must be either wired to a shared
   intent/effect/repository capability or rendered disabled with an explicit
   state. The current audit output lives in
   `docs/kmp-settings-action-wiring-audit.md`.
2. task #35: restore Computers list/detail through shared MVI and repository or
   capability wiring.
3. task #36: restore Mobile notification and local preference controls through
   shared intents/state and host notification capabilities. Completed for
   notification preference, permission/settings/test feedback, language, and
   message alignment; server mute remains deferred.
4. task #37: Storage refresh/clear is live through shared MVI and host
   capabilities; Diagnostics copy/share/clear/send-feedback remains live from
   task #43 and was reverified.
5. task #38: Account, Billing, and Server API-backed data are wired into
   `SharedSettingsDataSource` for the app shell and are in review.
6. task #39: finish Server mutations with shared confirmation/in-progress/error
   state. Start from the existing typed endpoints in
   `SettingsRepositoryDataSource`; do not reopen the broader repository wiring.
7. Add a Release Notes parity task if React sidebar parity is required in
   Settings.

## Validation Gate

Every Settings KMP task must report:

- React source or native behavior it is matching.
- Shared state/intent/effect/data-source changes in `commonMain`.
- Host capability adapters used by Android/OHOS.
- Loading, refresh, empty, error, no-permission, and disabled behavior.
- Whether existing content is preserved during refresh failure.
- Focused store tests and platform compile gates.
- Real-device or connected validation when behavior depends on platform APIs.

## Bottom Line

Settings is no longer just a shell: the shared MVI boundary exists, real
repository wiring is present in the product shell, and #35-#38/#43/#44/#48/#49
have moved the major Settings slices into review. The remaining implementation
risk is not broad repository wiring; it is task #39 Server Settings mutations
with confirmations and any focused bugs found during review. Continue extending
the shared store/data-source/effect boundary rather than adding route-local
Settings logic.
