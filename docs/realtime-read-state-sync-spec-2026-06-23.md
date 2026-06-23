# Realtime Read State Sync Spec

Date: 2026-06-23

## Context

`third_party/slock-source` in this repository is a read-only projection for
client development. Do not land server implementation changes there. This spec
captures the read-state realtime contract proposal so the real server source can
implement it later.

The observed client bug is cross-device read-state drift:

- Machine A marks an activity/channel/thread as read.
- Machine B stays connected.
- Machine B Home / Activity unread projection does not clear until a later
  `/channels/unread` or inbox refresh.

React currently clears the current-device projection locally:

- `messageStore.markRead()` posts `/channels/:id/read-all`, then calls
  `clearUnread(channelId)`.
- `inboxStore.markRead()` locally clears the inbox item, calls
  `useThreadStore.clearThreadUnread()` for thread items, and calls
  `useMessageStore.clearUnread(channelId)`.
- `inboxStore.markAllRead()` clears current inbox projection and then refreshes
  unread counts / thread state.
- `threadStore.clearThreadUnread()` locally sets followed thread unread count to
  zero and persists through `/channels/:threadChannelId/read-all`.

There is no current server Socket.IO event for read/unread projection changes,
so another connected machine cannot converge immediately from realtime alone.

## Proposed Server Event

Event name:

```text
read:updated
```

Delivery:

```text
io.to(`user:${userId}`).emit("read:updated", payload)
```

Payload:

```ts
type ReadUpdatedPayload = {
  serverId: string;
  channelId?: string;
  seq?: number;
  unreadCount?: number;
  scope: "channel" | "inbox";
};
```

Suggested emit points in the real server source:

- `POST /channels/:id/read`
  - Emit `{ serverId, channelId, seq, scope: "channel" }`.
  - This is sparse because the endpoint only advances to a requested cursor.
- `POST /channels/:id/read-all`
  - Emit `{ serverId, channelId, seq, unreadCount: 0, scope: "channel" }`.
- `POST /channels/:id/unread`
  - Emit `{ serverId, channelId, unreadCount, scope: "channel" }`.
- `POST /channels/inbox/read-all`
  - Emit `{ serverId, unreadCount: 0, scope: "inbox" }`.

## KMP Client Contract

Once the real server emits the event, shared KMP should add a domain event:

```kotlin
@Serializable
data class ReadStateUpdated(
    val serverId: ServerId,
    val channelId: ChannelId? = null,
    val seq: Long? = null,
    val unreadCount: Int? = null,
    val scope: String = "channel"
) : RealtimeEvent
```

Transport coverage:

- Add `read:updated` to common `RealtimeSocketEvents.ServerWireEvents`.
- Android Socket.IO listener should consume it through the common wire list.
- OHOS bridge should mirror the same event in its wire-event allowlist.

Mapping:

- `RealtimeEventMapper` maps `read:updated` to `ReadStateUpdated`.
- `RealtimeCursorPolicy` may advance the applied cursor from `seq` when present.

Home / Activity behavior:

- If `channelId` and `unreadCount` are present, patch the Home conversation
  unread projection locally and write through to local cache.
- If the event is sparse, or has `scope = "inbox"` without a channel id, trigger
  a Home / Activity refresh rather than guessing channel-level deltas.
- Activity should refresh on `ReadStateUpdated`, because its unread total and
  filtered unread list are server-derived projections.

## Validation Plan

Client-side after server support lands:

- `./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin`
- Mapper unit coverage for:
  - channel read-all payload with `unreadCount = 0`
  - inbox read-all payload without `channelId`
- Cross-device manual proof:
  - Sign in on two machines.
  - Make an Activity item unread.
  - Mark it read on machine A.
  - Confirm machine B Home activities unread count clears without foreground
    refresh / reconnect.

Server-side in the real source repository:

- `pnpm --filter @slock-ai/server typecheck`
- Route or socket integration coverage for each emit point above.
