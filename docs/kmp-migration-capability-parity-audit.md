# KMP Migration Capability Parity Audit

Date: 2026-05-22

Scope: global product-capability parity after moving Android product paths to the shared KMP/Kuikly shell. This document is the parent gate for Home, Thread, auth/session restore, and route-level loading/cache behavior. It should be read with:

- `docs/kmp-home-capability-parity-audit.md`
- `docs/kmp-thread-capability-parity-audit.md`
- `docs/kmp-settings-capability-parity-audit.md`
- `docs/android-ui-shared-migration-audit.md`

This audit is intentionally capability-focused. Visual parity and component screenshot parity are necessary, but they do not prove that native Android behavior was migrated.

## Decision

The KMP migration is not complete just because the shared shell opens and renders pages. Several high-value native Android capabilities were removed from the main product path before equivalent shared/commonMain state, cache, and effect handling existed.

The migration gate is now:

1. Business state and async flows live in `commonMain` MVI stores.
2. UI only collects state and emits intents.
3. Android/OHOS provide platform capabilities: secure store, network, local database, router, file/image picker, preview/open-url, and system insets.
4. Existing native Android capabilities must be explicitly marked `migrated`, `partial`, `missing`, or `intentionally unsupported`.
5. Loading/refresh must not clear already-rendered content unless the user explicitly switches identity/server and no cache exists.

## Current Capability Matrix

| Area | Capability | Current status | Evidence | Required follow-up |
| --- | --- | --- | --- | --- |
| Auth/session | Restore session gate | Partial | Shared shell calls restore and requires a selected server, but restore is now visible as an in-shell placeholder rather than the old app-owned splash/gate. | Define a shared restore-state UX contract: splash may stay host-only, but shared shell must own session/server restore without exposing a confusing intermediate panel. |
| Auth/session | Selected server persistence | Mostly migrated | Home audit confirms shared store/session updates selected server and gates stale switch responses. | Keep in commonMain; add regression for app restart and server switch stale response. |
| Home | Same-process warm state | Migrated | `shared-home` ViewModel keeps content on refresh and Thread -> Home can reuse the same shell instance. | Add explicit regression so Thread -> Home never shows global loading when content already exists. |
| Home | Cold-start/offline channel/DM cache | Missing | Real shared shell creates `SharedHomeDataSource` without local write-through; visible loads do not read cache. | Implement cached-first shared Home: wire native DB capability, emit cached content with `contentRefreshing=true`, then network refresh. |
| Home | Refresh/loading preserves existing content | Partial | Existing `Content` refresh is retained; first visible load and offline reload can still show global loading/error. | Same cached-first Home follow-up; visible network error with cache must stay `Content`. |
| Thread | Initial message renderer | Partial | Shared route can fetch `/messages/channel/{threadId}` and render parsed messages/attachments/markdown/footer. | Move route-local state into shared Thread MVI before expanding features. |
| Thread | Cached-first thread load | Missing | Thread audit confirms no product-path use of `ComposeLocalWriteThrough.readThreadSnapshot(...)`. | Add shared Thread store + data source; emit cached thread snapshot before visible network fetch. |
| Thread | Pagination / gap recovery | Missing | Shared shell disables load-more capabilities and has no shared load older/newer intents. | Add `LoadOlder`, `LoadNewer`, `SyncGap` intents and anchor preservation. |
| Thread | Realtime merge | Missing | commonMain realtime primitives exist, but shared Thread route does not collect/merge events into route state. | Feed app-shell realtime events into Home/Thread stores; merge message/task/reply updates in commonMain. |
| Thread | Send text | Partial | Plain text/as-task send exists, but no optimistic row, persisted draft, local write-through, or attachment IDs. | Move composer to shared MVI; add optimistic send, failure restore, cache write-through. |
| Thread | Attachments upload/preview/open | Partial/missing | Shared message attachment click fetches URL and calls `openUrl`; composer send has empty `attachmentIds`; no in-app preview parity. | Add file/image picker + upload capability and preview/open state with Android/OHOS host implementations. |
| Thread | Read receipts/unread reconciliation | Missing | Shared route does not call `ReadReceiptCoordinator` on visible/near-bottom messages. | Add visibility/read intents and reconcile Home/Inbox unread from commonMain. |
| Thread | Message menu/actions | Partial | Menu UI exists, but shared shell disables reply/open-in-channel/save/task/status/settings/add-member effects. | Re-enable each action only after shared intent/effect and repository capability exist. |
| Thread | Reply-thread/back stack | Missing/partial | Shared shell has simple Thread -> Home pop, not native reply stack/parent/open-in-channel behavior. | Model reply stack, parent context, overlays, and route back handling in shared state. |
| Tasks | Route MVI | Mostly migrated | Tasks reducer/store/display models and shared Tasks route are in commonMain; Android native Tasks top route/UI removed. | Continue long-tail route parity and visual/function regression, but this is not the current P0 gap. |
| Settings | Detail route/data | Partial | Settings detail routes and some server members hydration are in shared, but there is no `SharedSettingsStore`; Computers/Mobile/Storage/Diagnostics are static or pending compared with native Android. | Use `docs/kmp-settings-capability-parity-audit.md`; start with shared Settings MVI boundary, then restore Computers, Mobile prefs/notifications, Storage, Diagnostics, Server/Billing/Account data/actions. |

## Immediate Work Split

| Work item | Channel/task | Owner | Output |
| --- | --- | --- | --- |
| Home cache/warm loading parity audit | `#首页专修` task #128 | @Android-App-Integrator | Done/In Review; produced `docs/kmp-home-capability-parity-audit.md`. |
| Thread native capability parity audit | `#对话流专修` task #193 | @Android-Developer-1 | Done/In Review; produced `docs/kmp-thread-capability-parity-audit.md`. |
| Settings native capability parity audit | `#设置项专修` task #32/#33 | @Android-Settings-Integrator | Done/In Review; produced `docs/kmp-settings-capability-parity-audit.md`. |
| Global migration gate and tracking | `#KMP` task #67 | @Cindy | This document and follow-up task routing. |
| Kuikly ViewModel lifecycle rule | `#KMP` task #68 | @Cindy | Done/In Review; updated local skill and migration docs. |

## Follow-up Implementation Order

### Phase 1: Stop Visible Data Loss

1. Implement cached-first shared Home.
   - Android platform capability must expose existing native/local Home cache to commonMain.
   - Start/Refresh/SelectServer should emit cached content first when available.
   - Network error with cache should keep `Content`, not replace with global error.

2. Implement shared Thread cached-first store.
   - Create `SharedThreadStore` / `SharedThreadDataSource`.
   - Start should read cached thread snapshot before visible network fetch.
   - Successful network/send/realtime updates should write to local cache.

### Phase 2: Restore Live Conversation Behavior

3. Wire realtime event stream into shared Home/Thread stores.
4. Wire read receipts and unread reconciliation through `ReadReceiptCoordinator`.
5. Restore pagination and gap recovery with stable scroll anchors.

### Phase 3: Restore Interaction Parity

6. Move composer state into shared MVI: draft, as-task, pending attachments, optimistic send, failure restore.
7. Add attachment upload and preview/open host capabilities.
8. Re-enable message menu actions after each action has a shared intent/effect.
9. Restore reply-thread stack, parent pane, open-in-channel, and Thread-local back behavior.

### Phase 4: Continue Non-P0 Route Audits

10. Settings full MVI/data/action parity, starting with Computers, Mobile preferences/notifications, Storage, and Diagnostics because native Android already had real product capability there.
11. Tasks remaining long-tail parity and visual/function regressions.
12. Members/files/search/saved parity before deleting their Android native fallback paths.

## Validation Gate For Future KMP Migration PRs

Every KMP migration PR that replaces native Android product behavior must include:

- The native behavior being replaced.
- The shared/commonMain state owner.
- The platform capabilities required on Android and OHOS.
- Cache behavior: cold start, refresh, network error, server switch.
- Loading behavior: whether existing content is preserved.
- Live behavior: realtime/read receipt/event merge impact.
- A focused unit/store test or a live-device verification note.
- A visual-testing run only when visual parity is relevant; visual output cannot replace capability verification.

## Open Risks

- `SharedHomeDataSource` has cache support in code/tests, but the real shell does not inject it. This is easy to miss because warm in-process state can make Home look correct during manual testing.
- Thread has many fields/actions in the shared payload, which can make it look migrated while effects are deliberately disabled.
- Direct UI route patches inside `ThreadPage.kt` will keep increasing complexity unless a shared Thread store/data source becomes the owner.
- OHOS parity needs explicit capability state. If a native DB, image preview, upload, or realtime capability is not implemented on OHOS yet, the UI should expose a defined disabled/error state rather than silently dropping behavior.
