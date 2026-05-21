# KMP Home Capability Parity Audit

Date: 2026-05-22

Scope: Home shared shell parity after Android native Home migration. This audit covers channel/DM cache, warm state, refresh/loading behavior, and selected server restore. It is intentionally read-only: no product behavior was changed in this pass.

## Summary

KMP Home has a commonMain `SharedHomeStore` and it preserves visible Home content during in-process refreshes after content exists. However, the real `compose_slock_app_shell` Home route is not wired to the native/local database cache, and visible Home loads never use cached Home snapshots before network. This means the current implementation does not preserve the old Android Home cache capability across cold start, slow network, or offline visible startup.

## Findings

| Capability | Old Android native behavior | Current KMP/shared behavior | Status | Risk | Required follow-up |
| --- | --- | --- | --- | --- | --- |
| Selected server restore | Android auth/session store persisted selected server and native Home read `currentServerId` from session. | Shared app shell calls `restoreSession()`, requires `selectedServerId`, and `SharedHomeStore` reads `activeSession.currentServerId`. Server switch updates `authStore.updateSelectedServer(...)`. | Mostly migrated | Restore now happens inside the shared shell placeholder instead of app-owned splash; that is tracked by the parent migration audit, not this Home audit. | Keep selected-server persistence in commonMain; parent audit should decide whether shared restore UI must match splash expectations. |
| In-process Home warm state | Native Home kept `homeState` and route caches in Compose state; returning from Thread could keep existing Home. | `SharedHomeViewModel` is keyed as `shared-home`; `SharedHomeContentState.Content.asRefreshing()` keeps content and toggles `contentRefreshing`. Thread pop returns to main route without rebuilding the whole Activity. | Migrated for same shell instance | Warm state is process/viewmodel scoped only. It does not cover cold start or Activity recreation with cached Home data. | Add regression tests for Thread -> Home returning with existing content and no global Loading. |
| Refresh/loading without clearing existing content | Native `set*State` helpers kept previous content for non-content states; `publishHomeFromLocal(...)` could republish DB Home snapshots. | If current state is `Content`, refresh/network failure keeps the snapshot. If current state is `Loading`, `Empty`, or `Error`, visible load shows session-only shell or error; no cached Home snapshot is used. | Partially migrated | First visible load, offline visible load, or shell recreation can show empty/loading/error instead of cached channels/DMs. | Shared Home store/data source should read local cache before Visible network load and display it with `contentRefreshing=true`. |
| Channel/DM local database cache | Native Android creates `SlockLocalWriteThrough(createSlockLocalStore(context))`, has `publishHomeFromLocal(...)`, and writes Home refresh results into local DB. | commonMain has `ComposeLocalWriteThrough`, and tests prove it can read/write Home. But real `SlockAppShellContent` constructs `SharedHomeDataSource` without `localWriteThrough`, so product shared Home never reads or writes native DB Home snapshots. | Missing in product path | This is the main capability regression: channel/DM cache exists in code/tests but is not wired into the actual shared shell Home. | Add platform bridge/capability for `ComposeLocalDataStore` or native DB Home cache to shared shell, then pass `ComposeLocalWriteThrough` into `SharedHomeDataSource`. |
| Background cache read | Native code used local snapshots for optimistic/foreground reconciliation and Home re-publication. | `SharedHomeDataSource.loadHome()` reads cache only when `priority == Background`. `SharedHomeStore` always calls `DataRequestPriority.Visible` for Start/Refresh/SelectServer. | Not used by product Home | Existing cache-read branch is unreachable for normal Home rendering. | Either introduce `LoadCachedThenRefresh` flow or let Visible load emit cached content before network. |
| Server switch stale response gate | Native Android had stale server checks around selected server. | Shared `HomeServerSwitchState` gates result by request generation and server id. | Migrated | Needs continued test coverage but the state machine is in commonMain. | Keep existing generation tests; add cache/server-switch combined test if cache is wired. |

## Code Evidence

- Real shared shell creates Home data source without local write-through: `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt`, `SharedHomeDataSource(sessionStore, BridgeHomeApi, parseHome)` only.
- `SharedHomeDataSource.loadHome()` only returns cached Home when `priority == DataRequestPriority.Background`; otherwise it fetches network and writes through only if `localWriteThrough` is non-null.
- `SharedHomeStore.load()` calls `dataSource.loadHome(..., priority = DataRequestPriority.Visible)` for Start, Refresh, and SelectServer.
- Old Android `MainActivity` still shows the native capability that existed before migration: `SlockLocalWriteThrough(createSlockLocalStore(context))` plus `publishHomeFromLocal(...)` to restore Home from local DB.

## Recommended Fix Order

1. Wire native/local database Home cache into shared shell.
   - Define a shared capability/adapter boundary for Home cache read/write.
   - On Android, back it with existing `SlockLocalWriteThrough` / `SlockLocalStore`.
   - On OHOS, provide the equivalent native DB/store implementation or a no-cache explicit capability until implemented.

2. Change shared Home load flow to cached-first.
   - `Start`: if cache exists for selected server, emit `Content(cache, contentRefreshing = true)`, then visible network refresh.
   - `Refresh`: keep current content; if no current content, use cache before showing loading/error.
   - `SelectServer`: persist selected server, emit cache for target server if present with pending marker, then network refresh; stale response gate must remain.

3. Add regression coverage.
   - `SharedHomeStoreTest`: cached snapshot renders before visible network result.
   - `SharedHomeStoreTest`: visible network error with cached Home remains Content, not Error.
   - `HomeDataSourceTest`: product-priority cache path writes/reads through local store.
   - Android connected smoke or adb log-based check: cold start with existing DB and delayed network shows cached channels/DMs.

4. Keep parent audit separate for auth restore presentation.
   - MainActivity now launches shared shell immediately; shared shell shows `Restoring session...`.
   - Whether restore must appear as app splash instead of an in-shell panel belongs to the global migration parity task.

## Validation Done

- Static code audit against current `main` at `97e6972`.
- Verified an Android device is connected via adb (`emulator-5558`), but no live behavior was changed in this audit pass.

## Current Decision

Home KMP migration is not at native parity for cache/cold-start/offline behavior. It is safe for warm in-process route returns after content has loaded, but it is missing the actual product-path native DB cache wiring and cached-first visible load behavior.
