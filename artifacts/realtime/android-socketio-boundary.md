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
- This doc is the canonical repo artifact for the already analyzed Socket.IO / realtime contract.
- Chat threads may summarize decisions, but they are not the source of truth.
- The repo-wide contract index is `artifacts/api-contract-index.md` and references this file from task #383.

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
- `sync:resume:response` -> `RealtimeEvent.SyncResumeResponse`
- `heartbeat` -> `RealtimeEvent.Heartbeat`

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
- Semantics:
  - `0` means no replies
  - missing / null falls back to `0`
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
