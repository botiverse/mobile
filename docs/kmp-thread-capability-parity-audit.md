# KMP Thread Capability Parity Audit

Date: 2026-05-22

Scope: Thread/conversation flow migration from legacy Android native UI/state to the shared KMP shell. This audit covers data source, cache/warm loading, realtime, send, attachments, read receipts, menu/actions, pagination, and back stack/reply-thread behavior.

This pass is intentionally read-only. It does not change product behavior.

## Summary

The shared KMP Thread route can open a conversation, fetch `/messages/channel/{threadId}`, render parsed messages/attachments/markdown/footer metadata, and send plain text or task messages. That is not native parity yet.

The legacy Android Thread path still has substantially richer product behavior: cached thread snapshots, stale-while-refresh loading, older/newer paging, realtime merge and gap recovery, read receipt reconciliation, optimistic sends, attachment upload/preview/open states, reply-thread split-pane/back stack, task/status actions, message save/open actions, and richer error/no-permission handling.

The highest-risk gap is that shared Thread is still a route-local composable state machine, not a commonMain MVI store backed by repository/cache/realtime flows. It directly calls `BridgeThreadApi`, clears into `loading` on route fetch, and disables several action/paging capabilities through `withSharedShellEventCapabilities()`.

## Findings

| Capability | Old Android native behavior | Current KMP/shared behavior | Status | User risk | Required follow-up |
| --- | --- | --- | --- | --- | --- |
| Thread data source contract | Uses `ConversationRepository.getThread(...)`, `getMessageContext(...)`, `loadOlderMessages(...)`, `loadNewerMessages(...)`, `sendMessage(...)`, `uploadAttachments(...)`, and `getAttachmentOpenTarget(...)` with typed `DataResult<ThreadSnapshot>`. | `SharedThreadRouteContent` constructs `BridgeThreadApi` directly and calls `/messages/channel/{threadId}` / `POST /messages` / `/attachments/{id}/url`. It bypasses the typed conversation repository and does not expose a shared Thread data source/store. | Partial | New shared path can parse simple message responses, but it loses repository-level behavior, typed error/no-permission handling, local write-through, and endpoint parity from native. | Add `SharedThreadStore` / `SharedThreadDataSource` in commonMain. UI should dispatch intents; data source should use typed repository/capability APIs, not direct UI fetch/parse. |
| Cache and warm loading | Native reads `localThreadUiState(...)` before fetch, writes `localWriteThrough.writeThreadResult(...)`, and keeps current content while refresh status changes. | Shared route keeps only route-local `payload` state. On route load it does `payload = initialPayload.copy(status = "loading")`, then replaces with network result or error. It does not read or write `ComposeLocalWriteThrough.readThreadSnapshot(...)` in the product route. | Missing in product path | Opening Thread after cold start, slow network, or shell recreation can show a blank/loading shell instead of cached messages. Loading/error can look like data loss. | Cached-first shared Thread: emit cached snapshot if present with `contentRefreshing`, then network refresh. Write successful fetch/send/realtime results through the local store. |
| Pagination / history gap recovery | Native supports `canLoadMoreBefore/After`, `loadOlderThreadMessages(...)`, `loadNewerThreadMessages(...)`, anchor restoration, gap sync on heartbeat, and local write-through for loaded pages. | Shared payload has pagination fields, but `withSharedShellEventCapabilities()` forces `canLoadMoreBefore=false` and `canLoadMoreAfter=false`. No shared load older/newer intent is wired. | Missing | Users cannot page older/newer history from the KMP route; realtime gaps require full refresh or are missed. | Add `LoadOlder`, `LoadNewer`, and `SyncGap` intents to shared Thread store and keep scroll anchor/offset in state. |
| Realtime merge | Native subscribes to realtime events, writes them into local cache, updates Home/Inbox/Search/Tasks/Thread, joins DM channels, performs heartbeat gap sync, and marks near-bottom messages read. | commonMain has realtime abstractions and `ComposeLocalWriteThrough.writeRealtimeEvent(...)`, but shared Thread route does not collect realtime events or merge them into the current payload. | Missing | New/updated messages, reply count changes, task updates, and socket resume events may not appear in the shared Thread until manual refresh/reopen. | Wire shared shell realtime session/event stream into Thread/Home stores. Merge `MessageNew`, `MessageUpdated`, `ThreadUpdated`, `TaskCreated/Updated`, and `SyncResumeResponse` in commonMain. |
| Send / composer state | Native supports composer drafts per thread, pending attachment files, upload-before-send, optimistic message rows, local send write-through, failure restore, no-permission state, member/channel suggestions, and reply-pane send. | Shared route has route-local draft/asTask state. It supports plain text/asTask `POST /messages`, clears the draft during send, refreshes messages or appends send response, and restores draft on network failure. `buildSharedThreadSendMessageBody(...)` always sends `attachmentIds = emptyList()`. | Partial | Text send can work, but no optimistic row, no attachment upload, no persisted draft, no local cache write, and weaker no-permission/error UI. | Move composer into shared MVI. Add attachment upload capability, optimistic send model, failure restore, draft persistence, and local send write-through. |
| Attachments and preview/open | Native image attachments open in an in-app preview modal, text attachments can preview text, non-image files use external `ACTION_VIEW`, and each attachment has loading/error/open state. Composer can pick/upload image/file attachments. | Shared message attachments render and image tiles click `openAttachment`; shared route fetches URL and calls `bridge.openUrl(url)`. There is no in-app image/text preview state, and composer pending attachment UI exists but the app-shell send path never fills or uploads attachments. | Partial | Image/file clicks may leave the app or fail without native preview parity; users cannot send attachments from shared Thread. | Add shared attachment action state plus platform preview/open capability. Wire file/image picker and upload flow into shared composer store. |
| Read receipts / unread reconciliation | Native `ReadReceiptCoordinator` marks visible threads read to seq/all, optimistically clears Home/Inbox/Search/Saved unread, reconciles failures, and marks realtime messages read when near bottom. | Shared route does not call `ReadReceiptCoordinator` or a read-receipt repository when messages become visible/near bottom. Read receipt coordinator exists in commonMain but is not connected to `compose_thread`. | Missing | Opening or reading Thread in KMP may leave unread counts stale; Home/DM badges can remain wrong until a later native/reconcile path. | Add visibility/near-bottom read intent in shared Thread store and route it through commonMain `ReadReceiptCoordinator`. |
| Message menu and actions | Native supports compact action menu, copy, reply thread, save message, task open/status changes, attachment actions, parent/open-in-channel actions, and scoped back/overlay behavior. | Shared menu has recent size/anchor fixes, but shared app shell intentionally disables reply/open-in-channel/save/task-open/task-status/settings/add-member actions in `withSharedShellEventCapabilities()`. Unsupported events are logged. | Partial | Menu appears but many actions that existed in native are absent or no-op. Users can lose reply, task status, save, and navigation operations. | Re-enable capabilities only after their shared intents and platform/repository effects are implemented. Track each action as a parity item. |
| Reply threads / parent pane / back stack | Native keeps `replyThreadBackStack`, parent jump target, split-pane parent thread, open-in-channel action, parent pane composer, and tab state. | Shared shell uses a single NavHost route for Thread and Back pops to Home. `showReplyActions=false` and `showOpenInChannelAction=false` in shared shell. | Missing / partial | Reply-thread navigation and parent-context workflows regress to a flat thread page. | Model reply thread stack in shared Thread store or an app-shell route store; preserve parent message, stack entries, and open-in-channel actions. |
| Members/settings/files/tasks tabs | Native Thread exposes members/settings modals, add/remove member flows, Files tab data source, Tasks tab data source/status updates, and channel danger actions. | Shared payload still has fields for tabs/members/settings/files/tasks, but app-shell capabilities disable settings/add-member and route-local files payload is parsed from payload only. There is no shared fetch flow for files or thread tasks in this route. | Partial | Tabs/actions can render incomplete or stale data; settings/members/files/tasks parity is not guaranteed. | Split follow-ups: shared members/settings action store, files data source, thread tasks data source/status reducer. |
| Back stack / shell integration | Native had separate route state, return targets, draft restore, attachment preview dismissal, menu dismissal, reply-pane back handling, and thread restore after recreate. | Shared shell handles simple Thread -> Home pop and some main route back rules, but Thread-local overlay/draft/cache state is not modeled as a durable commonMain state machine. | Partial | Back can close the route but lose intermediate Thread state, drafts, or overlays after recreate. | Fold Thread route, overlay, draft, selected tab, and reply stack into shared MVI state with restore keys. |

## Code Evidence

- Shared app-shell Thread route is in `compose/shared/src/commonMain/kotlin/ai/slock/compose/ThreadPage.kt`, `SharedThreadRouteContent`.
- Shared route directly constructs `BridgeThreadApi` and route-local mutable state for `payload`, composer draft, as-task flag, sync nonce, sending state, and highlighted mentions.
- Shared route sets `payload = initialPayload.copy(status = "loading")` before fetching `/messages/channel/{threadId}`.
- `BridgeThreadApi` in `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/BridgeAuth.kt` only exposes `channelMessages(...)`, `sendMessage(...)`, and `attachmentUrl(...)`; `sendMessage(...)` builds a body with empty `attachmentIds`.
- `withSharedShellEventCapabilities()` in `ThreadPage.kt` disables reply, open-in-channel, save message, task open/status, pagination, add members, and settings actions for the shared shell route.
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt` already has `readThreadSnapshot(...)`, `writeThreadResult(...)`, and `writeRealtimeEvent(...)`, but the current shared Thread product route does not use them.
- Legacy native behavior remains visible in `app/src/main/java/ai/slock/android/MainActivity.kt`: `openThread(...)`, `refreshSelectedThread(...)`, `loadOlderThreadMessages(...)`, `loadNewerThreadMessages(...)`, realtime event merge, read receipt coordinator, attachment open/preview, attachment upload/send, and local write-through calls.
- Native thread UI state in `app/src/main/java/ai/slock/android/feature/thread/ThreadUiState.kt` includes pagination status, refresh status, reply action error, parent message, new update count, composer attachment state, and realtime merge helpers that are not yet represented by a shared Thread store.

## Recommended Fix Order

1. Create a commonMain Thread MVI store.
   - State: snapshot content, refresh status, pagination status/anchors, realtime connection/gap status, composer/draft/attachments, read receipt pending state, selected tab, reply stack, overlays/actions.
   - Intents: `Start`, `Refresh`, `LoadOlder`, `LoadNewer`, `RealtimeEvent`, `Send`, `PickAttachment`, `RemoveAttachment`, `OpenAttachment`, `MarkVisibleRead`, `OpenReply`, `Back`, `SelectTab`, `TaskAction`, `SettingsAction`.

2. Add a shared Thread data source/repository boundary.
   - Use typed `ConversationRepository`-shape capabilities in commonMain.
   - Back it with Android existing repositories/local DB first.
   - Provide OHOS equivalents or explicit no-cache/no-upload capability flags until implemented.

3. Restore cached-first rendering.
   - On `Start`, read cached thread by `ThreadId`.
   - Emit cached content with `contentRefreshing=true`.
   - Fetch network in foreground and write result to cache.
   - On network failure with cached content, keep content and expose inline refresh error instead of replacing with global error.

4. Restore realtime/read receipt parity.
   - Feed app-shell realtime events into shared stores.
   - Merge messages/tasks/reply counts in commonMain.
   - Use `ReadReceiptCoordinator` from commonMain when the visible list is near bottom or message seq becomes visible.

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
| Thread MVI store + cached-first initial load | KMP/shared owner | No UI redesign; migrate state/effects first. |
| Android Thread cache capability bridge | Android integration owner | Reuse existing `SlockLocalWriteThrough` / `SlockLocalStore` behavior behind shared capability. |
| Thread realtime merge into shared store | KMP + API/realtime owner | Reuse commonMain realtime models and native merge rules. |
| Read receipt shared wiring | KMP + Android integration owner | Use existing commonMain `ReadReceiptCoordinator`. |
| Composer attachment upload and optimistic send | Thread owner + API owner | Must preserve native send error/no-permission behavior. |
| Attachment preview/open capability | Android/OHOS host owners | Android should keep in-app image/text preview parity; OHOS fallback must be explicit. |
| Reply thread/back stack and action menu parity | Thread owner | Re-enable menu capabilities after effects exist. |
| Files/tasks/members/settings tabs in Thread | Per-route owners | Do not mark as migrated because fields render; require data/effect parity. |

## Validation Done

- Static audit against current `main` at `c3815d5`.
- Compared shared Thread route/data bridge against legacy Android `MainActivity` / `ThreadUiState` / repository surfaces.
- No product behavior changed.
- `git diff --check` should be enough for this documentation-only audit.

## Current Decision

KMP Thread is not at native capability parity. It has a working initial message renderer and basic text send path, but cache, realtime, pagination, read receipts, attachment upload/preview, reply/thread action workflows, and commonMain MVI ownership remain incomplete. The next implementation should start with shared Thread store + cached-first data source rather than continuing route-local patches inside `ThreadPage.kt`.
