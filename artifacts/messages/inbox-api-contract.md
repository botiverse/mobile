# Inbox API Contract

Observed from `GET /api/channels/inbox?filter=all&limit=30&offset=0` on 2026-05-10.

No auth token or server id is recorded here. This document only records payload shape for Android repository mapping.

## Endpoint

- `GET /api/channels/inbox?filter={all|unread}&limit={n}&offset={n}`

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
  "taskClaimedByName": null
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
  "unreadCount": 1
}
```

Android mapping:

- `channelId`, `channelName`, `kind` -> channel row identity
- `lastMessagePreview` -> original/latest row text for channel inbox rows
- `lastMessageSenderName` -> sender label when available
- `lastMessageAt` -> timestamp

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

## Mapper And Tests

- Mapper: `RealSlockRepositories.kt`, `JsonObject.toInboxItem`
- UI consumer: `InboxScreen.kt`, `InboxFlatItem`
- Regression test: `RealSlockRepositoriesTest.inboxRepositoryMapsRealThreadItemShape`
