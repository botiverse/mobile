# Android Socket.IO Boundary

Task: #118
Referenced by: #383

## Contract

- Socket base URL: `https://api.slock.ai`
- Socket auth payload: `{ token, serverId }`
- Token and selected server id come from `AuthSessionStore` through `SessionRealtimeRepository`.
- Compose/UI must consume mapped `RealtimeEvent` values only. It must not hold raw socket events, bearer tokens, or `X-Server-Id`.

## Source Of Truth

- The realtime boundary was analyzed under task #118.
- Typed realtime models and mappers are the canonical source of truth for field
  handling. This note only records the historical Socket.IO boundary analysis
  from task #118/#383.

## Client Boundary

- `RealtimeRepository` is the data-layer entry point for app wiring:
  - `connect(serverId, lastSeq)`
  - `disconnect()`
  - `joinChannel(channelId)`
  - `observeEvents(listener)`
- `SessionRealtimeRepository` validates auth/session/selected-server state and injects auth into the realtime client.
- `SocketIoRealtimeClient` owns room join/resume behavior and maps socket events through `RealtimeEventMapper`.
- `RealtimeSocketTransport` is the adapter boundary for the actual Socket.IO Android library. The recommended adapter is `io.socket:socket.io-client` configured with websocket transport, reconnection enabled, and auth `{ token, serverId }`.

## Events

Mapped now:

- `message:new` -> `RealtimeEvent.MessageNew`
- `message:updated` -> `RealtimeEvent.MessageUpdated`
- `thread:updated` -> `RealtimeEvent.ThreadUpdated`
- `task:created` -> `RealtimeEvent.TaskCreated`
- `task:updated` -> `RealtimeEvent.TaskUpdated`
- `task:deleted` -> `RealtimeEvent.TaskDeleted`
- `agent:activity` -> `RealtimeEvent.MemberPresenceUpdated` (kind=Agent) — fires at high frequency (~multiple/sec per agent); deduplicated in `SocketIoRealtimeClient` before dispatch
- `server:member-added` / `server:member-updated` / `server:member:left` / `server:member-removed` -> `RealtimeEvent.MemberPresenceUpdated`
- `sync:resume:response` -> `RealtimeEvent.SyncResumeResponse`
- `heartbeat` -> `RealtimeEvent.Heartbeat`

### `task:created` / `task:updated`

- Mapper landing point: `RealtimeEventMapper.toTaskSummary()`
- Realtime task payloads should mirror the REST task item shape accepted by `GET /tasks/server`:
  - `id`, `taskNumber`, `title` / `content`
  - `status` / `taskStatus`
  - `channelType`
  - `channelId`, `channelName`
  - `messageId`
  - `threadId` or explicit thread aliases (`threadChannelId`, `replyThreadChannelId`, `replyThreadId`)
  - `claimedById`, `claimedByName`, `claimedByType`
- If a task has `messageId + threadId` but no `parentMessageId`, Android maps `parentMessageId = messageId` locally so Tasks can open the actual replies/thread surface and still offer an original-message jump.
- `closed` is a separate status from `done`.

### `agent:activity` deduplication

`agent:activity` arrives multiple times per second per agent (observed: 4+ events in ~100ms, all identical). Without deduplication this causes:

- 4–5 `MemberPresenceUpdated` dispatches per agent per second
- Each triggers `withThreadAuthorProfiles()` rebuild in `ThreadContent`
- Results in unnecessary recompositions while the user is reading

Fix: `SocketIoRealtimeClient` tracks the last dispatched `(presence, kind)` per `memberId`. If the incoming event carries the same value, dispatch is skipped and a `event_dedup` log line is emitted instead. The cache is cleared on `disconnect()` so stale presence does not carry over to a new server connection.

## Payload Aliases And Semantics

### `message:new` / `message:updated`

- Mapper landing point: `RealtimeEventMapper.toMessage()`
- Relevant model: `Message.replyCount`
- Accepted `replyCount` aliases:
  - `replyCount`
  - `reply_count`
  - `replies`
  - `threadReplyCount`
  - `thread_reply_count`
  - `repliesCount`
  - `replies_count`
  - `threadRepliesCount`
  - `thread_replies_count`
  - `commentsCount`
  - `comments_count`
  - `childCount`
  - `child_count`
  - nested under `thread`, `replyThread`, `messageThread`, or `repliesThread` with the same alias set
- Semantics:
  - `0` means no replies
  - missing / null falls back to `0`
  - top-level reply thread inference only treats the message as a thread when `replyCount > 0`

### `thread:updated`

- Mapper landing point: `RealtimeEventMapper.toThreadUpdated()`
- Relevant model: `RealtimeEvent.ThreadUpdated.replyCount`
- Accepted reply-count aliases mirror the message mapping set above
- **Observed production payload shape (2026-05-09)**:
  ```json
  {
    "parentMessageId": "6fc1a24d-2cea-41d2-8f62-143f47703722",
    "threadChannelId": "1a20853e-106a-4bed-b587-c33f4aca6374",
    "replyCount": 8,
    "lastReplyAt": "2026-05-08 18:59:02.316841+00",
    "participantIds": ["8ce087db-...", "a09a2cd2-...", "d571ebe7-..."]
  }
  ```
  - `parentMessageId`: ID of the top-level message that has replies
  - `threadChannelId`: channel where thread replies live; note this **may be the same UUID** as the parent's `channelId` in some server configurations
  - Fired by the server every time a new reply is added to a thread (accompanies the `message:new` for the reply)
- Semantics:
  - `0` means no replies; missing / null falls back to `0`
  - merge rule: `maxOf(existing.replyCount, event.replyCount)` — never decrease
  - matching: find the parent message by `message.id == event.parentMessageId` OR `message.threadChannelId == event.threadChannelId`
  - realtime thread summaries should merge into existing REST-loaded thread rows rather than replace the whole conversation object

Boundary-only / lifecycle events:

- `connect`
- `disconnect`
- `connect_error`
- `rooms:joined`

## Resume And Gap Sync

- On `rooms:joined`, the client emits `sync:resume` with `{ lastSeq }` when `lastSeq > 0`.
- Heartbeat and resume response update the client's latest sequence tracking.
- Thread/channel gap sync remains a repository/app lifecycle follow-up: app wiring should request REST page gaps for the active channel/thread after reconnect, then de-dupe by message id/seq before updating UI state.

## REST Versus Realtime Merge

- REST first load provides the initial message/thread snapshot.
- Realtime events update the same mapped models and should be merged into the existing list/state.
- `message:new` and `message:updated` should map into the same `Message` model; updates should preserve existing message fields unless the realtime payload explicitly replaces them.
- **`message:updated` must preserve `replyCount` and `threadChannelId`**: the backend often omits these fields on edit events (e.g. body edit). App wiring must merge with `maxOf(existing.replyCount, incoming.replyCount)` and `incoming.threadChannelId ?: existing.threadChannelId` to prevent the reply affordance from disappearing after a message is edited.
- `thread:updated` should merge into existing thread/message rows, updating `replyCount`, `lastReplyAt`, and participant ids without requiring a full thread reopen.
- For thread summary rows, realtime reply-count updates should refresh the row affordance without requiring a full thread reopen.
- Attachment/media payloads are preserved by the message mapper; realtime should not strip them when a message is updated.
- When `sync:resume:response` returns batched messages, the app should de-dupe by message id and seq before appending/prepending into the visible state.

## Non-goals In This Phase

- No MainActivity or foreground/background lifecycle wiring.
- No Compose direct socket consumption.
- No real Socket.IO dependency adapter yet; `RealtimeSocketTransport` is the compile-safe adapter boundary for that follow-up.

## Regression Coverage

- `app/src/test/java/ai/slock/android/core/data/realtime/RealtimeRepositoryTest.kt`
  - session injection into realtime auth
  - join/resume replay behavior
  - heartbeat / sync resume / message mapping
  - reply-count alias mapping and thread update mapping
- `app/src/test/java/ai/slock/android/core/data/network/RealSlockRepositoriesTest.kt`
  - REST thread/message mapping with reply counts preserved
  - create-or-open thread mapping
- `app/src/androidTest/java/ai/slock/android/feature/thread/ThreadScreenReplyAffordanceTest.kt`
  - row-level reply affordance visibility
