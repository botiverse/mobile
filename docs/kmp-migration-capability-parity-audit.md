# KMP Migration Capability Parity Audit

Date: 2026-05-22
Last refreshed: 2026-06-02

Scope: global product-capability parity after moving Android product paths to the shared KMP/Kuikly shell. This document is the parent gate for Home, Thread, auth/session restore, and route-level loading/cache behavior. It should be read with:

- `docs/kmp-home-capability-parity-audit.md`
- `docs/kmp-thread-capability-parity-audit.md`
- `docs/kmp-settings-capability-parity-audit.md`
- `docs/android-ui-shared-migration-audit.md`

This audit is intentionally capability-focused. Visual parity and component screenshot parity are necessary, but they do not prove that native Android behavior was migrated.

## 2026-06-02 Refresh

The release gate and checklist remain valid, but the original route-level gap
summary is now stale after the late-May shared shell work. The current product
path has real `ComposeLocalWriteThrough` wiring in `SlockAppShellContent`, a
`SharedThreadStore`, and a real `RealSettingsRepository` passed into
`SharedSettingsDataSource`.

The migration is no longer blocked by "no shared store" or "no local
write-through" for Home, Thread, or Settings. The remaining gaps are narrower:

- Home: local write-through is wired, but visible startup still needs explicit
  cached-first/offline regression coverage because `loadHome(Visible)` does not
  directly return cached content before network.
- Thread: shared store/cache/realtime/read-all hooks are wired, but pagination
  gap recovery, reply/action parity, attachments, and #219 bottom-anchor
  validation remain the main Thread risks.
- Settings: shared MVI plus repository-backed data slices are wired through
  #35-#38. task #39 remains the explicit Server mutations + confirmations
  follow-up even though several typed endpoints already exist.

## Decision

The KMP migration is not complete just because the shared shell opens and renders pages. Several high-value native Android capabilities were removed from the main product path before equivalent shared/commonMain state, cache, and effect handling existed.

The migration gate is now:

1. Business state and async flows live in `commonMain` MVI stores.
2. UI only collects state and emits intents.
3. Android/OHOS provide platform capabilities: secure store, network, local database, router, file/image picker, preview/open-url, and system insets.
4. Existing native Android capabilities must be explicitly marked `migrated`, `partial`, `missing`, or `intentionally unsupported`.
5. Loading/refresh must not clear already-rendered content unless the user explicitly switches identity/server and no cache exists.

The detailed app-wide state rules live in
[`docs/slock-app-state-first-principles.md`](slock-app-state-first-principles.md).
Use that document as the review checklist for new Home, Thread, Search/Saved,
Tasks, Members, Settings, and realtime state changes.

## Current Capability Matrix

| Area | Capability | Current status | Evidence | Required follow-up |
| --- | --- | --- | --- | --- |
| Auth/session | Restore session gate | Partial | Shared shell calls restore and requires a selected server, but restore is now visible as an in-shell placeholder rather than the old app-owned splash/gate. | Define a shared restore-state UX contract: splash may stay host-only, but shared shell must own session/server restore without exposing a confusing intermediate panel. |
| Auth/session | Selected server persistence | Mostly migrated | Home audit confirms shared store/session updates selected server and gates stale switch responses. | Keep in commonMain; add regression for app restart and server switch stale response. |
| Home | Same-process warm state | Migrated | `shared-home` ViewModel keeps content on refresh and Thread -> Home can reuse the same shell instance. | Add explicit regression so Thread -> Home never shows global loading when content already exists. |
| Home | Cold-start/offline channel/DM cache | Partial | Real shared shell now passes `ComposeLocalWriteThrough` into `SharedHomeDataSource`, and `readCachedHome(...)` exists. Visible `loadHome(...)` still returns cached content only for `DataRequestPriority.Background`. | Add/verify cached-first visible Home startup and offline regression: cached content should render before or instead of a failed network refresh. |
| Home | Refresh/loading preserves existing content | Mostly migrated | Existing `Content` refresh is retained and route-return refresh can be silent. The remaining risk is first visible load/offline reload when only local cache exists. | Add focused tests for visible network error with cached Home and Activity/shell recreation. |
| Thread | Initial message renderer | Migrated/active | Shared route now uses `SharedThreadStore`/`SharedThreadDataSource`, parses messages/attachments/markdown/footer, and keeps image preview overlay state in shared Thread UI. | Continue Thread parity through store capabilities rather than route-local patches. |
| Thread | Cached-first thread load | Partial | `SharedThreadStore` reads `ComposeLocalWriteThrough.readThreadSnapshot(...)` and writes successful results. Some startup/anchor UX still depends on validation for #219. | Keep cached-first store coverage and verify long-message/attachment startup on device. |
| Thread | Pagination / gap recovery | Partial | `LoadOlder` exists, but gap recovery and complete older/newer parity are still not equivalent to the old native path. | Keep `LoadOlder`/anchor tests; add gap recovery and newer-page behavior only as concrete Thread follow-ups. |
| Thread | Realtime merge | Partial | `SharedThreadStore.writeRealtimeEvent(...)` and app-shell realtime bridges exist, including OHOS realtime after #97. | Verify message/task/reply merge semantics on Android/OHOS; do not reopen broad realtime migration unless a concrete event class fails. |
| Thread | Send text | Partial | Shared composer can send and restore failure state, and typed payload handling is stabilized. Optimistic row, durable draft, and attachment upload/send remain incomplete. | Track optimistic send/draft/attachment work as focused Thread tasks. |
| Thread | Attachments upload/preview/open | Partial | Image preview overlay has been improved in shared Thread; upload/send attachment parity and non-image preview/open state are still incomplete. | Add upload capability and explicit Android/OHOS preview/open fallback states. |
| Thread | Read receipts/unread reconciliation | Partial | Shared store now clears conversation unread through local write-through/read-all paths. Full visible-message read receipt parity still needs device verification. | Verify Home/DM unread reconciliation after opening Thread and after realtime messages. |
| Thread | Message menu/actions | Partial | Several actions have been re-enabled over later tasks, but reply/open-in-channel/save/task/settings/add-member should be judged individually by capability and tests. | Keep disabled states for unsupported actions; avoid generic "menu parity complete" claims. |
| Thread | Reply-thread/back stack | Partial | Shared shell has route payload replacement/back stack improvements, but native split parent/open-in-channel workflows remain a focused parity risk. | Model missing reply/open-in-channel details only where Product/QA still sees a real workflow gap. |
| Tasks | Route MVI | Mostly migrated | Tasks reducer/store/display models and shared Tasks route are in commonMain; Android native Tasks top route/UI removed. | Continue long-tail route parity and visual/function regression, but this is not the current P0 gap. |
| Settings | Detail route/data | Mostly migrated | `SharedSettingsStore` exists and the product shell passes a real `RealSettingsRepository` into `SharedSettingsDataSource`. #35-#38/#43/#44/#48/#49 are In Review. | Use task #39 for the remaining Server mutations + confirmations slice; do not treat Settings as missing a shared store/repository. |

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

1. Verify cached-first shared Home in the actual product path.
   - Android/OHOS already construct `ComposeLocalWriteThrough`.
   - Start/Refresh/SelectServer should emit cached content first when
     available, or at least keep cached content on network failure.
   - Network error with cache should keep `Content`, not replace with global
     error.

2. Keep shared Thread store/cache behavior focused on remaining regressions.
   - `SharedThreadStore` / `SharedThreadDataSource` already exist.
   - Remaining work should be pagination/gap recovery, attachment send/preview
     parity, and #219 bottom-anchor validation rather than another broad store
     migration.

### Phase 2: Restore Live Conversation Behavior

3. Verify realtime event stream behavior in shared Home/Thread stores on both
   Android and OHOS.
4. Verify read receipts and unread reconciliation through visible Thread
   usage.
5. Restore pagination and gap recovery with stable scroll anchors where still
   failing.

### Phase 3: Restore Interaction Parity

6. Move composer state into shared MVI: draft, as-task, pending attachments, optimistic send, failure restore.
7. Add attachment upload and preview/open host capabilities.
8. Re-enable message menu actions after each action has a shared intent/effect.
9. Restore reply-thread stack, parent pane, open-in-channel, and Thread-local back behavior.

### Phase 4: Continue Non-P0 Route Audits

10. Settings task #39: Server mutations with shared confirmations. Computers,
    Mobile preferences/notifications, Storage, Diagnostics, Account/Billing,
    and Server data are no longer broad missing slices; they are in review or
    awaiting focused follow-up.
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

- `SharedHomeDataSource` is injected with local write-through, but Visible
  startup still needs explicit cached-first/offline validation.
- Thread has a shared store now, but fields/actions in payload can still make
  partial features look migrated before their effects are fully implemented.
- `ThreadPage.kt` remains large; focused Thread changes should still avoid
  route-local business logic when a shared store/data source is the right owner.
- OHOS parity needs explicit capability state. If a native DB, image preview, upload, or realtime capability is not implemented on OHOS yet, the UI should expose a defined disabled/error state rather than silently dropping behavior.
