# KMP Settings Capability Parity Audit

Date: 2026-05-23

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
`compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt`.

The current shared MVI boundary exists in
`compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedSettingsStore.kt`:

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

Current app-shell wiring still constructs `SharedSettingsDataSource` with
session store and platform capabilities only. It does not pass a real
`SettingsRepository`, so repository-backed Account, Computers, Server, Billing,
and Mobile data paths are still not live in the product shared shell.

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
| Click/action wiring audit | Missing | Some rows can still look active while doing nothing or only emitting generic events. | task #44. |
| Account | Partial | Product shell uses auth-session fallback, not live account repository/profile update/connected accounts. | task #38. |
| Computers | Missing/partial | Shared store supports repository route, but app shell does not pass a repository; product path can still show empty/static data. | task #35. |
| Computer detail | Missing/partial | Store has `ComputerDetail`, but product shell still needs real route/open-detail wiring and retry/inline errors. | task #35. |
| Server page | Partial | React sections are not fully repository-backed in shared shell; mutations/confirmations are missing. | task #38 and task #39. |
| Billing | Partial | Uses session/static plan data unless repository is wired; no permission/error parity. | task #38. |
| Mobile | Wired/partial | Notification preference, permission/settings/test notification feedback, locale, and message-alignment local preferences now flow through shared Settings MVI. Server mute remains hidden/disabled until a real API/capability is confirmed. | task #36 complete; future server mute follow-up if needed. |
| Storage | Partial | Platform capability boundary exists, but product path must prove real snapshot/refresh/clear feedback. | task #37. |
| About/Diagnostics | Migrated/partial | Core actions are wired; keep checking no inert rows remain and diagnostics state handles empty/error. | task #43 complete; task #44 audits all visible rows. |
| Loading/cache | Partial | Store preserves existing content on refresh failure, but most real repository/cache sources are not connected. | tasks #35-#39 by slice. |
| Realtime | Missing | Server/machine/billing updates can go stale unless invalidated/refreshed through store. | Later Settings realtime/cache hardening task. |

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
4. task #37: prove Storage and Diagnostics capabilities are live, including
   refresh, clear, copy/share, empty, and error states.
5. task #38: wire Account, Billing, and Server API-backed data into
   `SharedSettingsDataSource` for the app shell.
6. task #39: add Server mutations with shared confirmation/in-progress/error
   state.
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

Settings is no longer just a shell: the shared MVI boundary exists and
About/Diagnostics actions are wired. The remaining risk is capability parity:
large parts of Account, Computers, Server, Billing, Mobile, and Storage still
need real repository/capability wiring in the shared product path. Follow the
task #44 and task #35-#39 sequence rather than adding more route-local Settings
logic.
