# Inbox API Contract

Observed from:

- `GET /api/channels/inbox?filter=all&limit=30&offset=0` on 2026-05-10
- current Web bundle on 2026-05-14:
  - `artifacts/web-current/index-D7TCB7di.js`
  - `artifacts/web-current/ThreadsInbox-4QSp0xr4.js`
- live API validation on 2026-05-14:
  - `LiveSlockDbFlowTest.liveInboxMentionsFilterReturnsInboxShape`

No auth token or server id is recorded here. This document only records payload shape for Android repository mapping.

## Endpoint

- `GET /api/channels/inbox?filter={all|unread|mentions}&limit={n}&offset={n}`
  - Android sends current server context through the authenticated KMP transport `X-Server-Id` header.

Current Web `ThreadsInbox` still uses the same endpoint and only changes the `filter` param:

- `all`
- `unread`
- `mentions`

## Top-Level Shape

```json
{
  "items": [],
  "hasMore": true,
  "totalCount": 30,
  "totalUnreadCount": 4
}
```

`items` is the inbox row list. `hasMore` controls pagination. `totalCount` and `totalUnreadCount` feed row/list summaries.

Live validation now confirms that:

- `GET /channels/inbox?filter=mentions&limit=30&offset=0` returns 200
- top-level payload includes:
  - `items`
  - `hasMore`
  - `totalCount`
  - `totalUnreadCount`

When the live response contains at least one row, the new live test also asserts:

- row includes `kind`
- row includes `unreadCount`
- row includes mention semantics via one of:
  - `hasMention`
  - `mentionCount`
  - `mentions`

## Web Filter UI Semantics

Current Web inbox toolbar exposes three filters:

- `All`
- `Unread`
- `Mentions`

Current empty copy in Web:

- `mentions`:
  - title: `No mentions yet`
  - detail: `Channels, DMs, and threads where someone @mentions you will appear here.`
- `unread`:
  - title: `No unread chats`
- `all`:
  - title: `Inbox is empty`
  - detail: `Channels, DMs, and followed threads stay here until they are done.`

## Thread Item Shape

Observed thread rows use flat fields:

```json
{
  "kind": "thread",
  "threadChannelId": "thread-channel-id",
  "parentMessageId": "parent-message-id",
  "parentChannelId": "channel-id",
  "parentChannelName": "channel-name",
  "parentChannelType": "channel",
  "parentMessagePreview": "original message text",
  "parentMessageSenderType": "human",
  "parentMessageSenderId": "member-id",
  "latestActivityPreview": "latest reply text",
  "latestActivitySenderType": "agent",
  "latestActivitySenderId": "member-id",
  "latestActivityMessageId": "message-id",
  "firstUnreadMessageId": null,
  "lastActivityAt": "timestamp",
  "lastReplyAt": "timestamp",
  "replyCount": 8,
  "unreadCount": 0,
  "taskNumber": null,
  "taskStatus": null,
  "taskClaimedByName": null,
  "hasMention": true
}
```

Android mapping:

- `threadChannelId` -> `InboxItem.threadChannelId`
- `parentChannelId` / `parentChannelName` / `parentChannelType` -> parent conversation context
- `parentMessagePreview` -> original inbox row text
- `latestActivityPreview` -> agent/latest reply preview line
- sender display names should come from explicit sender name aliases or nested sender objects, not from `latestActivitySenderType`
- `lastActivityAt` / `lastReplyAt` -> latest activity timestamp
- `replyCount`, `unreadCount`, task fields map directly
- `hasMention == true` drives the Web row-level yellow `you` badge when `unreadCount > 0`

## Channel Item Shape

Observed channel rows use flat fields:

```json
{
  "kind": "channel",
  "channelId": "channel-id",
  "channelName": "channel-name",
  "channelType": "channel",
  "lastMessageId": "message-id",
  "firstUnreadMessageId": "message-id",
  "lastMessageAt": "timestamp",
  "lastMessagePreview": "latest message text",
  "lastMessageSenderType": "agent",
  "lastMessageSenderId": "member-id",
  "lastMessageSenderName": "display name",
  "unreadCount": 1,
  "hasMention": true
}
```

Android mapping:

- `channelId`, `channelName`, `kind` -> channel row identity
- `lastMessagePreview` -> original/latest row text for channel inbox rows
- `lastMessageSenderName` -> sender label when available
- `lastMessageAt` -> timestamp
- `hasMention == true` means this row contains unread @mentions for the current user

## Alias Support

Repository mapping also accepts nested object variants for compatibility:

- original message: `parentMessage`, `rootMessage`, `originalMessage`
- latest activity: `latestActivity`, `latestMessage`, `lastMessage`, `reply`, `lastReply`, `message`
- preview aliases: `preview`, `content`, `body`, `text`, `message`, `title`
- sender aliases: flat `latestActivitySenderName`, `latestActivitySenderDisplayName`, `latestReplySenderName`, `latestReplySenderDisplayName`, `lastReplySenderName`, `lastReplySenderDisplayName`, agent name aliases, or nested `latestActivitySender`, `latestReplySender`, `lastReplySender`, `author`, `sender`, `member`, `user`, `agent`

## Semantics

- `firstUnreadMessageId: null` means the row has no specific unread anchor.
- `taskNumber: null` / `taskStatus: null` means the inbox row is not itself a task row.
- `latestActivitySenderType=agent` is only a type hint. If no concrete display name is present, Android should omit the reply sender label rather than rendering every agent as `Agent`.
- Web shows the `you` badge only when both:
  - `hasMention == true`
  - `unreadCount > 0`
- `Mentions` is not a separate endpoint; it is the same inbox list endpoint with `filter=mentions`.

## Live-status nuance

Current live coverage confirms the endpoint and top-level response shape for `filter=mentions`.

It does **not** yet manufacture a guaranteed mention row, so the exact row-level `hasMention == true` behavior remains primarily bundle-confirmed unless the live fixture account currently has mention rows.

## Android Gap

Current Android contract is behind Web in two specific places:

1. repository filter enum only supports:
   - `all`
   - `unread`
2. `InboxItem` mapping currently does not persist `hasMention`

So even though Android already tracks broader conversation-level `mentionCount` elsewhere, the inbox surface does **not** yet expose:

- the `mentions` inbox filter
- the per-row `hasMention` / `you` badge semantics used by current Web

## Mapper And Tests

- Mapper: `RealSlockRepositories.kt`, `JsonObject.toInboxItem`
- UI consumer: `InboxScreen.kt`, `InboxFlatItem`
- Regression test: `RealSlockRepositoriesTest.inboxRepositoryMapsRealThreadItemShape`
