# Mobile Message Seq / Gap Sync Spec

Date: 2026-06-25
Task: #390

Status: historical recovery proposal. The global-resume versus visible-stream
distinction remains valid, but the canonical visible-stream cursor/target,
coalescing, sparse-sequence, and terminal rules are now Thread gap recovery v1
in `realtime-consistency-contract-2026-07-14.md`.

## Problem

React already uses server message `seq` as a recovery cursor. Mobile/KMP has parts of the same contract, but the behavior is not yet treated as one explicit product rule. That makes it easy for Thread/Home changes to rely only on realtime delivery or page refresh and miss messages after reconnect, backgrounding, standalone Thread launch, or high-volume channels.

The mobile contract should be:

- Use `serverId` plus stream identity as the cache/recovery key.
- Use server message `seq` only as a recovery cursor and ordering key, not as route state.
- Recover missed messages with `/messages/sync?since_seq=...`.
- Keep global session resume and visible-stream gap recovery separate.
- Let route/page state select from canonical local data instead of owning independent message timelines.

This spec is intentionally implementation-light because #385-#388 are already moving Thread content toward global store-derived state.

## Existing Server / React Contract

Server:

- Socket `sync:resume` accepts `{ lastSeq }` and returns `sync:resume:response` with `{ messages, currentSeq, hasMore }`.
- `GET /api/messages/sync?since_seq=<seq>&limit=<n>&channel_id=<optional>` returns messages with `messages.seq > since_seq`.
- Without `channel_id`, sync is server-scoped and visibility-filtered for the current user.
- With `channel_id`, sync is scoped to one channel/thread stream and validates access.

React:

- Maintains global `messageStore.lastSeq`.
- On `rooms:joined`, emits socket `sync:resume` with global `lastSeq`.
- On `sync:resume:response`, batch-adds missed messages and advances global `lastSeq`.
- On heartbeat where server `seq > lastSeq`, triggers visible-scope sync.
- For current channel / open thread, `syncGap(channelId)` starts from the loaded window tail (`getChannelMaxSeq(channelMessages[channelId])`), not the global `lastSeq`.
- Channel-scoped gap sync paginates until complete and stitches deferred socket messages back into sequence order.

Important React invariant: global `lastSeq` is not a safe cursor for a specific channel window. A channel with loaded max seq `10` must sync from `10` even if global lastSeq is `100`.

## Current KMP / Android State

Already present:

- Android realtime client has `RealtimeCursorPolicy` and sends socket `sync:resume` after rooms join using `lastSeq`.
- Shared model has `SyncResumeResponse(messages, currentSeq, hasMore)` and `Heartbeat(seq)`.
- Current repository/local-store realtime handlers must merge `MessageNew`, `MessageUpdated`, and `SyncResumeResponse` into local Home/Thread snapshots.
- Thread network APIs already support `messageSync(channelId, sinceSeq, limit)` over `/messages/sync`.
- Thread newer paging already uses `messageSync` with `afterSeq`.
- Local `ThreadSnapshot.lastSeq` is persisted and patch-merged.

Gaps:

- There is no explicit server-scoped global message cursor persisted as a canonical mobile data-source field.
- There is no single policy that says when heartbeat/server-ahead should call global resume versus visible stream sync.
- `RealtimeResumeRequest.channelGaps` exists in shared model, but the current server socket contract ignores it; Android currently emits only `{ lastSeq }`.
- Thread route/store logic has been in flux: #385-#388 are moving toward canonical Thread cache/store identity and away from route-owned message timelines.
- KMP has pieces of React's behavior but not a named, tested "global resume + visible stream gap sync" contract.

## Required Mobile Contract

### 1. Cursor Identity

Persist cursors under server-scoped identities:

- Global resume cursor: `serverId -> appliedLastSeq`.
- Stream cursor/window tail: `serverId + streamChannelId -> loadedMaxSeq`.

For Thread/DM/channel pages, `streamChannelId` must be the canonical message stream:

- Channel: channel id.
- DM: DM channel id.
- Reply thread: reply content channel id when present, otherwise parent channel id + parent message id as a temporary identity until resolved.

Do not store cursor state in route/pageData. Route/pageData may carry only identity and intent.

### 2. Global Resume

When a server realtime session reconnects and rooms are joined:

1. Read `serverId -> appliedLastSeq`.
2. Emit `sync:resume { lastSeq }` when `lastSeq > 0`.
3. Apply returned `messages` through the same global local write-through path as realtime messages.
4. Advance `appliedLastSeq` to `max(currentSeq, max(message.seq))`.
5. If `hasMore`, schedule follow-up recovery. Server socket only returns one page, so mobile should not assume one response is exhaustive.

Global resume is for "what did this client miss while disconnected/backgrounded". It is not a visible Thread rendering source by itself.

### 3. Visible Stream Gap Sync

When a visible Thread/channel/DM stream detects server-ahead, reconnect, or a deferred socket gap:

1. Resolve the visible stream identity to `serverId + streamChannelId`.
2. Compute `sinceSeq` from the loaded local window tail for that stream, not global `appliedLastSeq`.
3. Call `/messages/sync?since_seq=<sinceSeq>&limit=200&channel_id=<streamChannelId>`.
4. Merge returned messages into the canonical thread cache/store.
5. Repeat while the page is full, advancing cursor to the max returned seq.
6. Re-stitch any deferred realtime messages that arrived above a gap.

This mirrors React's `syncGap(channelId, { sinceSeq: getChannelMaxSeq(...) })` behavior.

### 4. Ordering And Dedup

- Sort timeline messages by `seq` where present, with existing createdAt/id fallback only for seq-less local optimistic rows.
- Merge by message id first.
- Do not drop a socket message only because its seq is below global `appliedLastSeq`; a channel window may still need it if it was not loaded locally.
- Keep optimistic send rows separate until server ack replaces them by id/client reference.

### 5. Relation To #385-#388

#385-#388 should be the implementation vehicle for Thread page state:

- Phase 1 canonical cache identity should include `serverId`.
- Phase 2 Thread routes should select messages from canonical store snapshots keyed by `serverId + streamChannelId`.
- Phase 3 realtime/REST/send/pagination writes should be the only durable writers for that canonical store.

This spec adds the recovery cursor rules that the global store must obey. It does not require each Thread route to consume raw realtime or own a private store.

## Non-Goals

- Do not introduce product data into launch/pageData.
- Do not make every route own its own message sync cursor.
- Do not add client-side support for socket `channelGaps` until the server contract exists.
- Do not use global `lastSeq` as a replacement for visible stream gap sync.

## Proposed Implementation Slices

### Slice A: Shared Cursor Policy

Add a shared `MessageSeqRecoveryPolicy` or extend `RealtimeCursorPolicy` with named operations:

- `advanceGlobal(serverId, event)`
- `visibleStreamSinceSeq(serverId, streamChannelId, localSnapshot)`
- `shouldSyncVisibleStream(heartbeatSeq, globalAppliedSeq, visibleLoadedMaxSeq)`

Tests should pin the React invariant: channel scoped sync starts from stream max seq, not global lastSeq.

### Slice B: Local Cursor Persistence

Persist `serverId -> appliedLastSeq` in the local data source or local preference store. If SQLDelight already owns message rows, prefer deriving it from persisted message max seq only when that is server-scoped and visibility-complete. Otherwise keep an explicit cursor row.

### Slice C: Global Resume Follow-Up

When `SyncResumeResponse.hasMore == true`, schedule REST `/messages/sync` follow-up from `currentSeq` or continue socket resume if server adds pagination. Until then, log `hasMore` as a recoverable incomplete global resume condition.

### Slice D: Visible Stream Gap Sync

After #385-#388 canonical Thread store lands, add one global pipeline entry that can run:

```text
syncVisibleStream(serverId, streamChannelId, sinceSeq)
```

It should write into canonical Thread cache/store and emit no route-owned durable state.

## Tests

Minimum regression tests:

- Global resume applies `SyncResumeResponse` and advances server-scoped cursor.
- Channel scoped sync uses stream snapshot `lastSeq`, not global cursor.
- Heartbeat server-ahead triggers visible stream sync when visible stream loaded max seq is behind.
- `SyncResumeResponse.hasMore` records/schedules incomplete recovery instead of silently treating resume as complete.
- Switching server resets or namespaces cursors; no server A `lastSeq` applies to server B.
- Reply thread identity uses reply content channel when available and does not collapse into parent channel cursor.

## Open Questions

- Should global applied cursor live in SQLDelight, MMKV/local preference, or be derived from local message rows?
- Should server extend socket `sync:resume` to accept `channelGaps`, or should mobile keep visible stream recovery on REST `/messages/sync`?
- Do we need a server "current max seq" REST endpoint for startup recovery before socket rooms join?
