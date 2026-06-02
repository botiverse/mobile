# KMP Home Capability Parity Audit

Date: 2026-05-22
Last refreshed: 2026-06-02

Scope: Home shared shell parity after Android native Home migration. This audit covers channel/DM cache, warm state, refresh/loading behavior, and selected server restore. It is intentionally read-only: no product behavior was changed in this pass.

## Summary

KMP Home has a commonMain `SharedHomeStore`, preserves visible Home content
during in-process refreshes after content exists, and the real
`compose_slock_app_shell` now wires `ComposeLocalWriteThrough` into
`SharedHomeDataSource`.

The remaining risk is more specific than the original audit: visible Home loads
still do not directly return a cached Home snapshot before network, because
`SharedHomeDataSource.loadHome(...)` only short-circuits to cache for
`DataRequestPriority.Background`. This means cache plumbing exists, but
cold-start/offline visible behavior still needs explicit product-path
regression coverage.

## Findings

| Capability | Old Android native behavior | Current KMP/shared behavior | Status | Risk | Required follow-up |
| --- | --- | --- | --- | --- | --- |
| Selected server restore | Android auth/session store persisted selected server and native Home read `currentServerId` from session. | Shared app shell calls `restoreSession()`, requires `selectedServerId`, and `SharedHomeStore` reads `activeSession.currentServerId`. Server switch updates `authStore.updateSelectedServer(...)`. | Mostly migrated | Restore now happens inside the shared shell placeholder instead of app-owned splash; that is tracked by the parent migration audit, not this Home audit. | Keep selected-server persistence in commonMain; parent audit should decide whether shared restore UI must match splash expectations. |
| In-process Home warm state | Native Home kept `homeState` and route caches in Compose state; returning from Thread could keep existing Home. | `SharedHomeViewModel` is keyed as `shared-home`; `SharedHomeContentState.Content.asRefreshing()` keeps content and toggles `contentRefreshing`. Thread pop returns to main route without rebuilding the whole Activity. | Migrated for same shell instance | Warm state is process/viewmodel scoped only. It does not cover cold start or Activity recreation with cached Home data. | Add regression tests for Thread -> Home returning with existing content and no global Loading. |
| Refresh/loading without clearing existing content | Native `set*State` helpers kept previous content for non-content states; `publishHomeFromLocal(...)` could republish DB Home snapshots. | If current state is `Content`, refresh/network failure keeps the snapshot. Route-return/automatic refresh has also been made silent. If current state is `Loading`, `Empty`, or `Error`, visible load still does not directly emit cache first. | Mostly migrated | First visible load, offline visible load, or shell recreation can still show loading/error instead of cached channels/DMs. | Add product-path test: visible startup with existing cache and network failure/delay must stay content or emit cached content first. |
| Channel/DM local database cache | Native Android creates `SlockLocalWriteThrough(createSlockLocalStore(context))`, has `publishHomeFromLocal(...)`, and writes Home refresh results into local DB. | commonMain has `ComposeLocalWriteThrough`; real `SlockAppShellContent` now constructs a shared `ComposeLocalWriteThrough` and passes it to `SharedHomeDataSource`. | Wired / needs validation | The old "not wired in product path" risk is resolved, but cache-first visible semantics still need verification. | Keep the shared shell wiring; add cold-start/offline regression coverage around visible priority. |
| Background cache read | Native code used local snapshots for optimistic/foreground reconciliation and Home re-publication. | `SharedHomeDataSource.loadHome()` reads cache only when `priority == Background`; `SharedHomeStore` normal Start/Refresh/SelectServer still use `DataRequestPriority.Visible`. | Partial | Existing cache-read branch is available but not the normal visible startup path. | Either introduce `LoadCachedThenRefresh` flow or let Visible load emit cached content before network. |
| Server switch stale response gate | Native Android had stale server checks around selected server. | Shared `HomeServerSwitchState` gates result by request generation and server id. | Migrated | Needs continued test coverage but the state machine is in commonMain. | Keep existing generation tests; add cache/server-switch combined test if cache is wired. |

## Code Evidence

- Real shared shell creates `ComposeLocalWriteThrough` from `RelationalComposeLocalDataStore` and passes it into `SharedHomeDataSource` in `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt`.
- `SharedHomeDataSource.loadHome()` only returns cached Home when `priority == DataRequestPriority.Background`; otherwise it fetches network and writes through the now-injected `localWriteThrough`.
- `SharedHomeStore.load()` calls `dataSource.loadHome(..., priority = DataRequestPriority.Visible)` for Start, Refresh, and SelectServer.
- Old Android `MainActivity` still shows the native capability that existed before migration: `SlockLocalWriteThrough(createSlockLocalStore(context))` plus `publishHomeFromLocal(...)` to restore Home from local DB.

## Recommended Fix Order

1. Verify native/local database Home cache in the shared product shell.
   - The shared `ComposeLocalWriteThrough` injection exists.
   - Android and OHOS should both be checked for real persistence across
     Activity/app restart, not only same-process warm state.

2. Change or verify shared Home visible load flow as cached-first.
   - `Start`: if cache exists for selected server, emit `Content(cache,
     contentRefreshing = true)`, then visible network refresh.
   - `Refresh`: keep current content; if no current content, use cache before
     showing loading/error.
   - `SelectServer`: persist selected server, emit cache for target server if
     present with pending marker, then network refresh; stale response gate must
     remain.

3. Add regression coverage.
   - `SharedHomeStoreTest`: cached snapshot renders before visible network result.
   - `SharedHomeStoreTest`: visible network error with cached Home remains Content, not Error.
   - `HomeDataSourceTest`: product-priority cache path writes/reads through local store.
   - Android connected smoke or adb log-based check: cold start with existing DB and delayed network shows cached channels/DMs.

4. Keep parent audit separate for auth restore presentation.
   - MainActivity now launches shared shell immediately; shared shell shows `Restoring session...`.
   - Whether restore must appear as app splash instead of an in-shell panel belongs to the global migration parity task.

## Validation Done

- Static code refresh against current `main` at `aa516ee`.
- Verified an Android device is connected via adb (`emulator-5558`), but no live behavior was changed in this audit pass.

## Current Decision

Home KMP migration is no longer missing product-path cache wiring. It is safe
for warm in-process route returns after content has loaded, and it has shared
local write-through injected. The remaining parity question is cached-first
visible startup/offline behavior: verify or fix that path before calling Home
cache parity complete.
