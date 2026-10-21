# Common SQLDelight Schema Review

This documents the current Slock local storage schema owned by `compose/shared/commonMain`.

## Direction

- Common owns `.sq` schema and generated queries.
- Android and iOS only provide `SqlDriver`.
- JS is intentionally unsupported until a concrete local-store driver is chosen.
- `compose/androidApp` must not define its own SQLDelight schema or generated database.

## Server Source Of Truth

The server stores conversations in one `channels` table:

- `channels.type` is `channel`, `private`, `dm`, or `thread`.
- A thread is not a separate stream entity. It is a channel row with `type = 'thread'` and `parentMessageId` set.
- API names such as `threadChannelId` mean "the id of the thread channel row".
- `messages.channelId` points to `channels.id`. Normal messages point to a normal channel/DM/private channel. Thread replies point to the thread channel id.
- Thread parent context is derived through the parent message: `threadChannel.parentMessageId -> messages.id -> messages.channelId`.

## Schema Principles

- Field names follow server/channel/thread terminology instead of introducing a separate stream concept.
- IDs stay as typed string values (`serverId`, `channelId`, `messageId`, `memberId`) rather than guessed fallback identifiers.
- Avoid synthetic local identity columns when the typed identity tuple is already clear.
- High-churn IM data is normalized into entities:
  - `channelEntity`
  - `channelReadStateEntity`
  - `threadFollowEntity`
  - `messageEntity`
  - `attachmentEntity`
  - `messageReactionEntity`
  - `savedMessageEntity`
  - `taskSummaryEntity`
  - `memberProfileEntity`
  - `syncCursorEntity`
  - `outboxMessageEntity`
- `localSnapshotEntity` remains only as a compatibility bridge for shared snapshots that have not yet been normalized.

## Channel Model

`channelEntity` mirrors the server's single channel table model. It covers normal channels, private channels, DMs, and thread channels through `type`.

For home rendering, `channelEntity` stores local-first summary fields such as `lastMessagePreview`, `lastMessageSenderName`, `lastMessageId`, `updatedAtLabel`, unread counters, and draft state. These are cached projections, not the message body source of truth.

For thread channels:

- `channelId` is the server thread channel id.
- `type` is `thread`.
- `parentMessageId` is the root message id.
- `parentChannelId` is an optional denormalized helper for local routing; the server can derive the same relation from the parent message.

## Read And Inbox State

The schema follows a Mattermost-style split between message facts and per-principal channel state:

- `messageEntity` does not store read/unread facts.
- `channelEntity.unreadCount`, `mentionCount`, and `firstUnreadMessageId` are local-first display projections for the Home list.
- `channelReadStateEntity` stores the current principal's channel read/inbox state: `lastReadSeq`, `lastReadMessageId`, `lastViewedAtMs`, and `doneAtMs`.
- `threadFollowEntity` stores thread attention state from server `thread_follows`, because thread follow/done/unfollow is not channel membership.
- `messageReactionEntity` and `savedMessageEntity` are edge tables so reactions and saved state can be upserted/deleted without rewriting the message body row.

## Identity And Capability Encoding

- `channelEntity` uses `(serverId, channelId)` as its primary key.
- `channelReadStateEntity` uses `(serverId, channelId, principalKind, principalId)` as its primary key.
- `threadFollowEntity` uses `(serverId, threadChannelId, followerKind, followerId)` as its primary key.
- `messageEntity` stores message body data and links to the current message-list channel through `channelId`.
- `messageReactionEntity` uses `(messageId, reactorKind, reactorId, emoji)` as its primary key.
- `savedMessageEntity` uses `(serverId, messageId, ownerKind, ownerId)` as its primary key.
- `taskSummaryEntity.channelId` points to the channel that owns the task message; for a task inside a thread, this is the thread channel id.
- `outboxMessageEntity.channelId` is the channel target for sending. For a thread reply, this is also the thread channel id.
- `memberProfileEntity` is a server-level user/agent profile cache, not a channel membership table. `membershipStatus` is a server membership projection such as active/removed.
- Channel permissions are stored as `channelEntity.capabilities INTEGER`: `1 = read`, `2 = send`, `4 = attach`, `8 = create task`.
- Task row actions use `taskSummaryEntity.capabilities`; currently `1 = claim`.

## Current Validation

- Android shared generation works:
  `cd compose && ./gradlew --no-daemon --console=plain -Dkotlin.compiler.execution.strategy=in-process :shared:compileDebugKotlinAndroid`
