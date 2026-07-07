# KMP Thread Capability Parity Audit

Date: 2026-05-22
Last refreshed: 2026-06-02

Status: historical snapshot. Do not use this file as the current Thread parity
source of truth without refreshing it against code, the task board, and
`docs/react-kmp-feature-map-2026-06-26.md`.

Superseded note, 2026-06-27: several concrete store/composer/realtime claims
below are no longer current. In particular, composer draft persistence now has
`SharedThreadComposerDraftStore` and `SharedThreadComposerDraftPersistSlice`,
Thread realtime is routed through the shared domain owner / cache-invalidation
path, and attachment upload/send behavior has moved forward since this audit.
Use `docs/shared-mvi-foundation.md`,
`docs/react-kmp-realtime-alignment-2026-06-26.md`, and current code for live
store ownership. Keep this file only as a historical migration snapshot.

Scope: Thread/conversation flow migration from legacy Android native UI/state to the shared KMP shell. This audit covers data source, cache/warm loading, realtime, send, attachments, read receipts, menu/actions, pagination, and back stack/reply-thread behavior.

This pass is intentionally read-only. It does not change product behavior.

## Summary

The shared KMP Thread route can open a conversation, render parsed
messages/attachments/markdown/footer metadata, send plain text or task
messages, and now routes most business state through `SharedThreadStore` /
`SharedThreadDataSource` in commonMain. It also wires
repository/local-store paths for cached snapshots, read-all/unread clearing,
and realtime cache updates.

This is a material improvement over the original audit, which predated the
shared Thread store. The remaining native-parity risk is no longer "no shared
store"; it is the narrower set of Thread product behaviors that still need
focused validation or follow-up: pagination/gap recovery, durable draft and
optimistic send, attachment upload/send plus preview/open parity, reply/open-in-
channel action depth, and #219 long-message bottom-anchor behavior.

## Findings

| Capability | Old Android native behavior | Current KMP/shared behavior | Status | User risk | Required follow-up |
| --- | --- | --- | --- | --- | --- |
| Thread data source contract | Uses `ConversationRepository.getThread(...)`, `getMessageContext(...)`, `loadOlderMessages(...)`, `loadNewerMessages(...)`, `sendMessage(...)`, `uploadAttachments(...)`, and `getAttachmentOpenTarget(...)` with typed `DataResult<ThreadSnapshot>`. | `SharedThreadDataSource` and `SharedThreadStore` now own the shared route. It uses typed repositories where available and keeps `BridgeThreadApi` as the platform network bridge. | Mostly migrated | Repository-level behavior is much closer to native, but some endpoints/actions still need focused parity checks. | Keep new Thread work in `SharedThreadStore`/data-source boundaries; avoid adding route-local business state in `ThreadPage.kt`. |
| Cache and warm loading | Native reads `localThreadUiState(...)` before fetch, writes through the local repository/store layer, and keeps current content while refresh status changes. | Product route uses `SharedThreadDataSource`; the store reads/writes thread snapshots and applies realtime/read-all effects through current repository/local-store paths. | Partial/migrated | Cached-first exists, but #219 showed long-message initial bottom-anchor UX can still expose layout/scroll instability. | Keep cached-first tests and device validation for long text, markdown/code/list, and attachment-heavy threads. |
| Pagination / history gap recovery | Native supports `canLoadMoreBefore/After`, `loadOlderThreadMessages(...)`, `loadNewerThreadMessages(...)`, anchor restoration, gap sync on heartbeat, and local write-through for loaded pages. | `SharedThreadIntent.LoadOlder` exists and `canLoadMoreBefore` is now consumed by the UI, but full newer-page/gap recovery parity is not yet proven. | Partial | Users may still hit missing older/newer history or realtime gap recovery cases even if basic Thread load works. | Add focused tests and product feedback tasks for older/newer pagination, gap sync, and anchor preservation instead of reopening broad Thread migration. |
| Realtime merge | Native subscribes to realtime events, writes them into local cache, updates Home/Inbox/Search/Tasks/Thread, joins DM channels, performs heartbeat gap sync, and marks near-bottom messages read. | commonMain realtime abstractions, Android/OHOS realtime bridges, and `SharedThreadStore.writeRealtimeEvent(...)` are wired. | Partial/migrated | Merge coverage still depends on event class: messages, tasks, replies, counts, and resume/gap events need explicit validation. | Verify `MessageNew`, `MessageUpdated`, `ThreadUpdated`, `TaskCreated/Updated`, and socket resume behavior on Android/OHOS. |
| Send / composer state | Native supports composer drafts per thread, pending attachment files, upload-before-send, optimistic message rows, local send write-through, failure restore, no-permission state, member/channel suggestions, and reply-pane send. | Shared route has route-local draft/asTask state. It supports plain text/asTask `POST /messages`, clears the draft during send, refreshes messages or appends send response, and restores draft on network failure. `buildSharedThreadSendMessageBody(...)` always sends `attachmentIds = emptyList()`. | Partial | Text send can work, but no optimistic row, no attachment upload, no persisted draft, no local cache write, and weaker no-permission/error UI. | Move composer into shared MVI. Add attachment upload capability, optimistic send model, failure restore, draft persistence, and local send write-through. |
| Attachments and preview/open | Native image attachments open in an in-app preview modal, text attachments can preview text, non-image files use external `ACTION_VIEW`, and each attachment has loading/error/open state. Composer can pick/upload image/file attachments. | Shared message attachments render, image preview overlay has been improved, and attachment URL/open paths exist. Composer upload/send attachment parity remains incomplete. | Partial | Image preview is no longer purely external-open, but upload/send and non-image preview/open parity remain at risk. | Add shared attachment action state plus platform preview/open fallback. Wire file/image picker and upload flow into shared composer store. |
| Read receipts / unread reconciliation | Native `ReadReceiptCoordinator` marks visible threads read to seq/all, optimistically clears Home/Inbox/Search/Saved unread, reconciles failures, and marks realtime messages read when near bottom. | Shared store can clear conversation unread through local write-through/read-all paths. Full visible-message read receipt parity still needs device validation. | Partial | Opening or reading Thread may still leave some unread counts stale if visible/near-bottom read triggers are incomplete. | Verify Home/DM unread reconciliation after opening Thread and after realtime messages. Add intent/effect gaps only where validation fails. |
| Message menu and actions | Native supports compact action menu, copy, reply thread, save message, task open/status changes, attachment actions, parent/open-in-channel actions, and scoped back/overlay behavior. | Shared menu has size/anchor fixes and several effects have been restored across later tasks; capabilities should now be judged action by action. | Partial | A visible menu can still hide unsupported or partial actions. | Keep unsupported actions disabled/explicit; require a shared intent/effect and repository/platform capability before marking each action migrated. |
| Reply threads / parent pane / back stack | Native keeps `replyThreadBackStack`, parent jump target, split-pane parent thread, open-in-channel action, parent pane composer, and tab state. | Shared shell has route payload replacement/back stack improvements, but native split parent/open-in-channel depth is not fully proven. | Partial | Parent-context workflows can still regress even if basic Thread navigation works. | Model missing reply/open-in-channel workflows only where Product/QA still sees real gaps. |
| Members/settings/files/tasks tabs | Native Thread exposes members/settings modals, add/remove member flows, Files tab data source, Tasks tab data source/status updates, and channel danger actions. | Shared Thread now owns the Files tab through `SharedThreadStore` / `SharedThreadDataSource` and renders `ThreadFilesContent` inside `compose_thread`; the standalone `compose_thread_files` wrapper has been retired. Members/settings/tasks should still be judged by their concrete action/data-source parity, not by field presence alone. | Partial/migrated by slice | Files is no longer a standalone-page risk, but members/settings/tasks can still regress if action effects or typed data refresh are incomplete. | Keep Files inside the shared Thread route; split remaining follow-ups by concrete members/settings/tasks action/data-source gaps. |
| Back stack / shell integration | Native had separate route state, return targets, draft restore, attachment preview dismissal, menu dismissal, reply-pane back handling, and thread restore after recreate. | Shared shell handles simple Thread -> Home pop and some main route back rules, but Thread-local overlay/draft/cache state is not modeled as a durable commonMain state machine. | Partial | Back can close the route but lose intermediate Thread state, drafts, or overlays after recreate. | Fold Thread route, overlay, draft, selected tab, and reply stack into shared MVI state with restore keys. |

## Code Evidence

- Shared app-shell Thread route is in `compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt`, `SharedThreadRouteContent`.
- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedThreadStore.kt` now owns the shared Thread state/intents and uses `SharedThreadDataSource`.
- Product route uses `SharedThreadDataSource` and the current repository/local-store paths for Thread snapshots.
- Thread snapshot read/write, realtime event application, and unread clear helpers should be checked against current code; this historical audit predates later cache-layer removal.
- `ThreadPage.kt` remains the large UI surface for renderer, overlays, composer, and menu wiring, so new business behavior should still prefer the shared store/data-source boundary.
- Legacy native behavior remains visible in `compose/androidApp/src/main/java/build/raft/app/MainActivity.kt`: `openThread(...)`, `refreshSelectedThread(...)`, `loadOlderThreadMessages(...)`, `loadNewerThreadMessages(...)`, realtime event merge, read receipt coordinator, attachment open/preview, attachment upload/send, and local write-through calls.
- Native thread UI state in `compose/androidApp/src/main/java/build/raft/app/feature/thread/ThreadUiState.kt` includes pagination status, refresh status, reply action error, parent message, new update count, composer attachment state, and realtime merge helpers that are not yet represented by a shared Thread store.

## Recommended Fix Order

1. Keep extending the existing commonMain Thread MVI store.
   - Do not recreate the store; it exists.
   - Remaining state gaps should be added only for concrete missing behavior:
     gap recovery, durable drafts, upload attachments, reply/open-in-channel
     depth, and action-specific pending/error states.

2. Keep the shared Thread data-source/repository boundary as the owner.
   - Use typed `ConversationRepository`-shape capabilities where they already
     exist.
   - Back missing Android/OHOS host capabilities with explicit disabled/error
     state rather than silent no-ops.

3. Validate cached-first rendering and #219 bottom-anchor behavior.
   - Cached snapshots and write-through are wired.
   - Long text, markdown/code/list, and attachment-heavy threads still need
     device validation that users do not see repeated scroll correction.

4. Validate realtime/read receipt parity by event class.
   - Feed app-shell realtime events into shared stores.
   - Verify messages/tasks/reply counts and socket resume/gap behavior.
   - Verify Home/DM unread reconciliation after visible Thread use.

5. Restore composer and attachment parity.
   - Add upload-before-send and optimistic message model.
   - Keep failed attachment IDs/draft restore.
   - Route preview/open through a platform capability that can show in-app image/text preview on Android and a defined OHOS fallback.

6. Re-enable actions only after effects exist.
   - Reply/open-in-channel/save/task status/settings/add-member should stay disabled until their shared intents are wired.
   - Each action needs a regression test or live-device verification note before it is marked migrated.

## Suggested Task Split

| Follow-up | Owner type | Notes |
| --- | --- | --- |
| Thread pagination/gap recovery | KMP/shared owner | `LoadOlder` exists; verify full older/newer/gap behavior before adding more correction logic. |
| Thread bottom-anchor validation | KMP/shared owner | #219 fixed visible scroll correction; verify long text, markdown/code/list, and attachments on device. |
| Thread realtime merge verification | KMP + API/realtime owner | Reuse commonMain realtime models and verify Android/OHOS event classes. |
| Read receipt/unread verification | KMP + Android integration owner | Confirm Home/DM unread clears after visible Thread use; add missing effects only where validation fails. |
| Composer attachment upload and optimistic send | Thread owner + API owner | Must preserve native send error/no-permission behavior. |
| Attachment preview/open capability | Android/OHOS host owners | Android should keep in-app image/text preview parity; OHOS fallback must be explicit. |
| Reply thread/back stack and action menu parity | Thread owner | Re-enable menu capabilities after effects exist. |
| Tasks/members/settings tabs in Thread | Per-route owners | Files is now part of shared Thread route/data-source; remaining tab work should track concrete tasks/members/settings effects and data parity. |

## Validation Done

- Static code refresh against current `main` at `aa516ee`.
- Compared shared Thread route/data bridge against legacy Android `MainActivity` / `ThreadUiState` / repository surfaces.
- No product behavior changed.
- `git diff --check` should be enough for this documentation-only audit.

## Current Decision

KMP Thread now has commonMain MVI ownership, cache/write-through, realtime
hooks, basic send, and image preview overlay improvements. It is still not full
native capability parity: pagination/gap recovery, action-by-action menu
parity, attachment upload/send, durable composer behavior, and #219
bottom-anchor validation remain the focused risks. The next implementation
should extend `SharedThreadStore`/data-source for those concrete gaps rather
than restarting the broad "move Thread to shared store" work.
