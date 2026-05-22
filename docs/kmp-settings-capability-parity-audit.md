# KMP Settings Capability Parity Audit

Date: 2026-05-22

Scope: Settings migration from legacy Android native Settings screens/repository
to the shared KMP/Kuikly app shell. This audit covers Account, Computers,
Server, Billing, Mobile, Storage, About/Diagnostics, Settings navigation, safe
area, cache/loading, actions, and API state ownership.

This is a capability audit, not a visual audit. A shared Settings page opening
or rendering cards does not prove that the previously implemented Android
Settings behavior has been migrated.

## Decision

Shared Settings is not yet at native capability parity. The shared shell has a
clickable Settings IA and several KMP-rendered pages, but most product data and
actions are still session-derived placeholders or route-local effects.

The target architecture is:

1. `commonMain` owns `SharedSettingsStore` / reducers / `StateFlow` / effects.
2. UI only collects `SharedSettingsState` and emits `SharedSettingsIntent`.
3. Android/OHOS host only provide capabilities: auth/session store, network,
   notification permission, locale/preference persistence, storage management,
   diagnostics buffer, feedback routing, local database/cache, and system safe
   area.
4. Loading/refresh must preserve existing content where possible and must not
   erase valid Settings data on transient network failure.
5. Settings UI must never read raw API response, bearer token, auth header, or
   selected-server header directly.

## Current Product Paths

| Path | Current implementation | Notes |
| --- | --- | --- |
| Shared app shell Settings tab | `SettingsAppShellContent` in `SettingsHomePage.kt` | Current main product path after Android launch moved into `compose_slock_app_shell`. |
| Standalone shared pages | `compose_settings_home`, `compose_settings_account`, `compose_settings_billing`, `compose_settings_storage`, `compose_settings_computers` | Useful as smoke/legacy wrappers, but not the full product Settings tab. |
| Legacy Android native Settings | `MainActivity.kt` + `RealSettingsRepository` + `feature/settings/*` | Still contains more complete Account/Computers/Server/Mobile/Storage/About behavior, but is no longer the primary logged-in product path. |

## Capability Matrix

| Area | Native Android capability before migration | Shared Settings current state | Status | Risk | Required follow-up |
| --- | --- | --- | --- | --- | --- |
| Navigation / IA | Settings Home routes to Account, Computers, Server, Billing, Mobile, Storage, About, Diagnostics, with compact/wide behavior and back rules. | Shared shell has Settings home rows and detail shell back behavior. Diagnostics is folded into About rows, not a real detail route. Router/smoke entry is still visible in shared Settings home. | Partial | Product Settings can show test-only entry and loses Diagnostics detail parity. | Add `SharedSettingsStore` route state and remove product-visible smoke/router row from real shell; keep test route only in explicit preview/harness. Add Diagnostics route. |
| Safe area / header | Android native Settings detail headers had host/status handling. | Shared Settings detail shell now consumes `slockTopSafeAreaPadding()` after task #31. | Migrated | Low. | Keep safe-area at shell/header level, not per detail page. |
| Account profile | `RealSettingsRepository.getAccount()` calls Settings account endpoint and maps profile/connected accounts; `updateProfile()` exists. Native UI had logout and connected accounts. | Shared Account builds profile from auth session only; connected accounts are always empty; profile update action is not surfaced. Logout calls shared auth data source. | Partial | Account data can regress from API-backed to session-only; connected accounts disappear. | Create shared Account data source/store using Settings repository/capability; add refresh/error/no-permission state and update profile intent. |
| Sessions | Older native Settings had sessions/revoke contract, later removed from IA after Web parity review. | Shared Settings has no Sessions route. | Intentionally unsupported for current IA | Low if product decision remains. | Keep excluded unless Web/product reintroduces Sessions. |
| Computers list | Native `getComputers()` calls `GET /servers/{serverId}/machines`, parses machine variants, logs sanitized outcomes, and displays empty/error/no-permission. | Shared Computers payload is hardcoded empty: "Computers are moving to shared Settings". No API, no cache, no machine list. | Missing | Users lose connected machine visibility. | Add shared Computers store/data source backed by host Settings capability or common repository; load cached/current machine list and expose open-detail intent. |
| Computer detail | Native `getComputerDetail(machineId)` combines machines, workspaces, agents, inline section errors, member enrichment, and retry. | Shared app shell has no computer detail route. Standalone `SettingsComputerDetailContent` exists, but product shell never reaches real detail data. | Missing | Machine/workspace/agent diagnostics disappear. | Add `SharedSettingsRoute.ComputerDetail(machineId)` and store intents for load/retry workspaces/agents. |
| Server profile/admins | Native `getServerSettings()` uses selected server + `GET /servers/{serverId}/members`, usage, billing subscription, onboarding settings; action APIs exist. | Shared Server only calls `BridgeMembersApi.serverMembers()` and derives member/admin counts; profile fields are read-only session data; usage/billing/onboarding are pending strings. | Partial | Server page looks migrated but omits most real sections and action state. | Add shared Server data source/store that reuses native Settings repository contract: members, usage, billing subscription, onboarding. Preserve partial-section errors. |
| Server actions | Native repository exposes `PATCH /servers/{serverId}`, `POST /servers/{serverId}/invites`, `DELETE /servers/{serverId}` with permission/confirmation boundaries; Android UI had action feedback/in-progress state. | Shared rows call generic `settingsEvent` but no shared confirm/edit state or repository effects exist; dangerous actions disabled. | Missing/partial | Mutating server settings are no-op or disabled. | Model edit/invite/delete/onboarding intents in shared store; Android/OHOS host provide API capability; keep confirmation in commonMain. |
| Billing | Native `getBilling()` maps selected server plan/role and owner/admin permission into billing state. | Shared Billing uses selected server session plan and two static cards. No no-permission/error state from repository. | Partial | Permission and error semantics are lost; plan state can be stale or oversimplified. | Move Billing snapshot loading into shared Settings store through repository/capability; keep owner/admin no-permission state. |
| Mobile notification permission | Native Settings Mobile reads Android notification permission, request launcher availability, user toggle, Send Test notification, system settings fallback, locale and chat layout preference. | Shared Mobile is a static section with "Host pending" / "Disabled" / "Pending"; actions go through generic bridge only. | Missing | Mobile settings appear but cannot control notification/language/layout. | Define platform capability for notification permission/request/send-test/open-settings plus shared preference store for locale/chat layout. |
| Local preferences | Native has `LocaleRepository` and `ChatLayoutPreferenceRepository` for language and my-message alignment/large-screen navigation. | Shared Mobile lists language/alignment as pending. | Missing | User preferences already implemented natively are unavailable in KMP Settings. | Add common preference store intents and Android/OHOS persistence adapters. |
| Storage | Native `SettingsStorageScreen` uses `StorageManagementRepository`, reports database/image/temp sizes, refresh, and clear actions with feedback. | Shared Storage displays static rows: "Managed by Android", sizes `--`; clear/refresh callbacks in app shell are ignored. | Missing | Cache/storage management cannot be used from shared Settings. | Add storage capability bridge with snapshot, refresh, clear target, in-progress and feedback state. |
| About / Diagnostics | Native About shows app/device/session/server/socket/notification status and sanitized diagnostics logs with copy/share/clear/send feedback. | Shared About shows app name, signed-in user, selected server and pending diagnostics/feedback rows. No runtime info/logs/copy/share/clear. | Partial/missing | Diagnostics debugging and feedback entry are lost in shared shell. | Add About/Diagnostics shared route with host diagnostics buffer capability and feedback routing. |
| Loading/error behavior | Native Settings loads route states with `ScreenLoadState`, but many loads replace a page with loading; some detail sections preserve partial errors. | Shared Settings mostly has no store. Server members route-local state flips to `Loading` and can replace counts; other pages use static payloads. | Missing | Network failure can produce stale placeholders rather than clear state; no systematic cached-first behavior. | Shared Settings store should hold per-section content and refresh states; visible refresh must preserve existing content and show inline errors. |
| Cache/local DB | Native Settings repository is network/session/preference backed; no unified Settings local DB snapshot exists. Home/Thread cache work does not cover Settings. | Shared Settings has no local cache except auth session and Home relational store not used for Settings. | Missing | Offline/cold-start Settings cannot show last known real data. | Define which Settings data should be cached: Account profile, Computers, Server summary, Billing/usage, Mobile prefs, About diagnostics. Add local DB/native preference capability per slice. |
| Realtime | Native/server Web references include `server:plan-updated`, machine status/capabilities, daemon status; Android Settings mostly refreshes on open/retry. | Shared Settings does not subscribe/merge Settings-specific realtime events. | Missing | Server plan/machine status can go stale. | After data stores exist, route relevant realtime events into Settings store instead of raw UI updates. |
| MVI ownership | Native business state lives in Android `MainActivity`/repositories; shared Settings UI has route-local mutable state for server members and logout. | No `SharedSettingsStore` / `SharedSettingsViewModel` exists. | Missing | New shared Settings changes will keep accumulating UI-local state and bypass commonMain architecture. | First implementation slice should create shared Settings state/intent/effect/store, then migrate pages into it incrementally. |

## Immediate Implementation Split

### Phase 1: Establish Shared Settings MVI Boundary

1. Add `SharedSettingsStore` / `SharedSettingsViewModel`.
   - State: selected destination, route stack, per-section load states, action feedback, refresh flags.
   - Intents: `Start`, `OpenDestination`, `Back`, `Refresh`, `Logout`, `OpenComputer`, `RetryComputerDetail`, `RequestNotificationPermission`, `SetLocale`, `SetChatLayout`, `RefreshStorage`, `ClearStorage`, `CopyDiagnostics`, `ShareDiagnostics`, `ClearDiagnostics`, `SendFeedback`.
   - Effects: host navigation, permission request, system settings, clipboard/share, feedback route, destructive confirmations.

2. Add shared Settings capability/data source interfaces.
   - `SharedSettingsNetworkApi` or repository facade for Account, Computers, Server, Billing.
   - `SharedSettingsPlatformApi` for notification permission, local preferences, storage, diagnostics, share/clipboard.
   - Android adapter should delegate to existing `RealSettingsRepository`, `StorageManagementRepository`, `LocaleRepository`, `ChatLayoutPreferenceRepository`, and sanitized diagnostics buffer.

### Phase 2: Stop Product Capability Loss

3. Migrate Computers list + Computer detail first.
   - This is the clearest regression: native real API exists, shared product page is empty.
   - Preserve inline workspaces/agents errors and retry.

4. Migrate Mobile local preferences and notification controls.
   - Restore permission request/open settings/send test.
   - Restore language and my-message alignment persistence.

5. Migrate Storage and Diagnostics.
   - Restore real sizes, clear actions, feedback, sanitized log copy/share/clear.

### Phase 3: Finish Server/Billing/Account Parity

6. Migrate Server settings data source.
   - Reuse Web-confirmed endpoints and existing `RealSettingsRepository` semantics.
   - Keep pending invites/join links disabled unless endpoints are confirmed.

7. Migrate Server actions.
   - Profile save, invite create, onboarding toggle, delete server confirmation.
   - Keep all mutation and confirmation state in commonMain.

8. Migrate Account and Billing data states.
   - Account should use API profile/connected accounts, not only auth session.
   - Billing should preserve owner/admin permission and error semantics.

### Phase 4: Cache/Realtime Hardening

9. Decide Settings cache scope.
   - Cache Account/Computers/Server/Billing snapshots where useful.
   - Preferences and diagnostics already live in native stores and should expose cached state immediately.

10. Wire realtime refresh.
    - Server plan updates, machine status/capabilities, daemon status, and server membership updates should invalidate/merge into Settings store.

## Validation Gate For Settings Migration PRs

Every Settings KMP migration PR must include:

- Which native Settings behavior it replaces.
- Whether the state is `migrated`, `partial`, `missing`, or `intentionally unsupported`.
- The commonMain store/data source owner.
- Android/OHOS capability adapters required.
- Loading behavior: first load, refresh, network error, no permission.
- Cache behavior: cold start/offline if applicable.
- Action behavior: confirmation, in-progress, success, failure, and disabled states.
- Focused store/reducer tests or explicit device/live verification.
- Visual screenshots only as appearance support, not capability proof.

## Recommended Follow-up Tasks

| Priority | Task | Suggested owner | Output |
| --- | --- | --- | --- |
| P0 | Create `SharedSettingsStore` + Settings capability interfaces | Settings/KMP owner | commonMain MVI state/intent/effect boundary and adapter seam. |
| P0 | Restore Computers list/detail in shared Settings | Settings owner | Real machines/workspaces/agents data, retry, inline errors. |
| P0 | Restore Mobile notification + preference controls | Settings + Android/OHOS host owners | Permission/request/send-test/open-settings plus locale/chat layout persistence. |
| P1 | Restore Storage and Diagnostics | Settings + Android host owner | Real storage snapshot/clear feedback and sanitized diagnostics route. |
| P1 | Restore Server/Billing/Account repository-backed data | Settings owner | API-backed sections and no-permission/error parity. |
| P1 | Restore Server mutations | Settings owner with API review | Shared confirmations and repository effects for profile/invite/onboarding/delete. |
| P2 | Settings cache/realtime hardening | Settings/KMP owner | Cached snapshots where useful and event-driven invalidation/merge. |

## Bottom Line

Shared Settings is currently a partial KMP migration. The safe-area/header fix
and route clickability are in place, but data/action capability parity is not.
The highest-risk regressions are Computers, Mobile preferences/notifications,
Storage, and Diagnostics because native Android already had real product
behavior and shared Settings now shows pending/static content for those areas.
