# KMP Settings Action Wiring Audit

Date: 2026-05-23

Scope: task #44, created from `#设置项专修` feedback that shared Settings
routes can look present while rows/actions still do nothing. This audit checks
the product Settings IA from task #45 against current KMP wiring.

Rule: UI must not read raw responses, tokens, auth headers, or selected-server
headers. Actions must go through `SharedSettingsIntent`, `SharedSettingsEffect`,
`SharedSettingsDataSource`, `SettingsRepository`, or host capabilities.

## Summary

| Route | Overall state | Next task |
| --- | --- | --- |
| Settings Home | Wired | Continue using shared home nav. |
| Account | Partial | task #38 |
| Computers | Wired/disabled | task #35 complete; keep add-computer disabled until product route/capability exists. |
| Computer detail | Wired/read-only | task #35 complete; keep agent rows read-only until Members/Agent navigation is explicit. |
| Server | Partial | task #38 / task #39 |
| Billing | Partial | task #38 |
| Mobile | Partial | task #36 |
| Storage | Missing/partial | task #37 |
| About | Wired | task #43 complete; keep covered by route audit. |
| Diagnostics | Wired/partial | task #37 for richer diagnostics state, task #43 complete for core actions. |

This change also fixes one small MVI violation found during the audit: Account
Logout now dispatches `SharedSettingsIntent.Logout`, emits
`SharedSettingsEffect.Logout`, and is consumed by the host effect handler. It no
longer uses a route-local `pendingLogout` mutable state machine.

## Route Table

| Page | Row / action | Current state | Required shared contract | Follow-up |
| --- | --- | --- | --- | --- |
| Home | Account, Computers, Server, Billing, Mobile, Storage, About rows | Wired. Row click changes shared Settings destination and dispatches `OpenDestination`. | Keep as shared nav state. | None. |
| Home | Browser | Intentionally absent from Android/KMP product IA after React audit. | No action unless product reintroduces Android Browser Settings. | None. |
| Home | Release Notes | Missing compared with React sidebar. | Add explicit shared destination/effect if product wants React sidebar parity. | New follow-up if required. |
| Home | Router / smoke previews | Removed from product Settings Home in task #45. | Harness-only route, not product row. | None. |
| Account | Logout | Wired by this task through `SharedSettingsIntent.Logout` and `SharedSettingsEffect.Logout`. | Host effect calls `SharedAuthDataSource.logout()` and exits to auth gate. | None. |
| Account | Save Profile | Inert today. `SettingsAccountContent` emits `saveProfile`, but app shell ignores it and product payload is session-derived. | Add `SaveAccountProfile` intent, data source call to `SettingsRepository.updateProfile`, action state, validation, success/error feedback. | task #38. |
| Account | Change Password | Inert today. Emits `changePassword`, app shell ignores it. | Either disabled with explicit unavailable copy or a shared effect/navigation to a real password flow. | task #38. |
| Account | Connected account rows | Display-only. No connect/disconnect action exists in shared shell. | Keep display-only unless API contract is confirmed; if clickable later, add shared intents/effects. | task #38. |
| Computers | Add computer | Disabled. Product payload keeps `canAddComputer=false` and shows a reason only when repository data provides one. | Keep disabled until add-computer route/capability and confirmation flow exists. | Future Computers mutation follow-up. |
| Computers | Computer row open | Wired. Product shell maps `SharedSettingsState.computers` into `SettingsComputersContent`; row click opens `SharedSettingsIntent.OpenComputer(machineId)`. | Keep route and repository load in commonMain store/data-source. | task #35 complete. |
| Computer detail | Retry workspaces / retry agents | Wired. Product shell dispatches `SharedSettingsIntent.OpenComputer(selectedComputerId)` so partial-section errors can retry without leaving detail. | Keep preserving base machine detail when workspace/agent sections fail. | task #35 complete. |
| Computer detail | Open agent | Intentionally read-only. `KuiklySettingsComputerDetailPayload.canOpenAgents` defaults false so agent rows do not emit inert `openAgent` events. | Add a shared navigation effect to Members/Agent detail before re-enabling clicks. | Members follow-up if needed. |
| Server | Members/admins counts | Partial. Current route-local state calls `BridgeMembersApi.serverMembers()` directly from UI composition. | Move to `SharedSettingsDataSource.loadServer()` / repository-backed state in commonMain. | task #38. |
| Server | Pending Invites | Disabled/pending. No action. | Add repository/capability for invite list/create if endpoint is confirmed. | task #39. |
| Server | Join Links | Disabled/pending. No action. | Add repository/capability for join-link list/create if endpoint is confirmed. | task #39. |
| Server | Onboarding Agent | Disabled/pending. No action. | Add shared state/action around onboarding settings and agent selection. | task #39. |
| Server | Delete workspace / Danger | Disabled. No shared confirmation/action state yet. | Add commonMain confirmation, typed delete request, in-progress/error/success effects. | task #39. |
| Billing | Current plan / usage | Partial. Product shell derives from auth session/static cards, not repository state. | Wire `SharedSettingsState.billing` from `SettingsRepository.getBilling()`. | task #38. |
| Billing | Choose Plan | Effectively disabled today because product payload only exposes current plan and coming-soon plans; standalone content can emit `choosePlan`, but product shell ignores it. | Keep disabled/no-permission until billing mutation contract exists. | task #38. |
| Mobile | Push permission | Wired to `SharedSettingsIntent.RequestNotificationPermission` and host `settingsCapability`. | Move permission snapshot/loading/error into `SharedSettingsState.mobile`, not static "Request". | task #36. |
| Mobile | Open system notification settings | Wired to `SharedSettingsIntent.OpenNotificationSettings` and host capability. | Keep. Also surface result/error state in shared state. | task #36. |
| Mobile | Send test notification | Wired to `SharedSettingsIntent.SendTestNotification` and host capability. | Keep. Add action feedback/error state. | task #36. |
| Mobile | Mute workspace | Disabled/pending. | Add shared preference/server mute state only after API/capability is confirmed. | task #36. |
| Mobile | Language | Disabled/pending. | Add `SetLocale` UI, shared preference snapshot, host persistence adapter. Store/effect already has `SetLocale`. | task #36. |
| Mobile | My message alignment | Disabled/pending. | Add `SetMessageAlignment` UI, shared preference snapshot, host persistence adapter. Store/effect already has `SetMessageAlignment`. | task #36. |
| Storage | Refresh | Inert in product shell. `SettingsStorageContent` emits `refreshStorage`, but app shell passes empty callback. | Wire to `SharedSettingsIntent.RefreshStorage` and real `loadStorage()` capability. | task #37. |
| Storage | Clear database / image cache | Inert in product shell. Content can emit `clearStorage`, store has `ClearStorage`, but host effect and capability are not connected. | Add `ClearStorage` effect consumption and host capability, with action state and feedback. | task #37. |
| About | Diagnostics row | Wired. Opens Diagnostics destination. | Keep. | task #43 complete. |
| About | Copy version/build | Wired. Uses host pasteboard. | Keep; consider moving through `SharedSettingsEffect` if more consistent later. | task #43 complete. |
| About | Open Slock | Wired. Uses host `openUrl`. | Keep. | task #43 complete. |
| About | Send feedback | Wired through shared `SendFeedback` effect and host capability. | Keep. | task #43 complete. |
| Diagnostics | Copy logs | Wired; disabled when no logs. | Keep. | task #43 complete. |
| Diagnostics | Share logs | Wired; disabled when no logs. | Keep. | task #43 complete. |
| Diagnostics | Clear logs | Wired; disabled when no logs. | Keep. | task #43 complete. |
| Diagnostics | Send feedback | Wired. | Keep. | task #43 complete. |

## Implementation Split

1. task #35 wired Computers list/detail through shared state and made Computer
   detail agent rows read-only until there is an explicit Members/Agent
   navigation effect.
2. task #36 should wire Mobile state/action feedback. Three notification
   actions already reach host capability; language/alignment and state snapshots
   still need implementation.
3. task #37 should wire Storage refresh/clear plus richer Diagnostics state.
4. task #38 should pass a real `SettingsRepository` or equivalent shared
   capability into app-shell `SharedSettingsDataSource` for Account, Billing,
   and Server data.
5. task #39 should add Server mutations and confirmations in commonMain.

## Validation Requirement

Future Settings PRs should include a row/action table for the touched route and
explicitly mark every visible control as one of:

- `wired`
- `disabled`
- `missing API`
- `intentionally read-only`

No new Settings row should be left as clickable but ignored by the app shell.
