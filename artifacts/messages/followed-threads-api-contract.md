# Android Followed Threads API Contract

Source: Live response shape observed from `GET /api/channels/threads/followed` on 2026-05-09.

Related canonical docs:
- [Android API Contract Index](../api-contract-index.md)
- [Reply count and thread-summary join contract](./replycount-thread-summary-contract.md)
- [Channel messages API contract](./channel-messages-api-contract.md)

---

## Endpoint

```
GET /api/channels/threads/followed
```

### Query parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `limit`   | int  | Maximum number of threads to return (e.g. `50`). |

### Response envelope

```json
{
  "threads": [ /* FollowedThread[] */ ]
}
```

| Field     | Type  | Description                                             |
|-----------|-------|---------------------------------------------------------|
| `threads` | array | Ordered list of `FollowedThread` objects (most-recently-active first). |

---

## FollowedThread object

Live observed shape:

```json
{
  "threadChannelId": "fdf0b57f-24b1-4412-bc87-148f260c4953",
  "parentMessageId": "f7c4d29e-a4c7-437a-b893-d274713ee795",
  "parentChannelId": "cf2e25ee-ccf7-4bdb-81ca-4743dd4710c9",
  "parentChannelName": "首页专修",
  "parentChannelType": "channel",
  "parentMessagePreview": "...",
  "parentMessageSenderType": "user",
  "parentMessageSenderId": "a09a2cd2-359b-47dd-bcab-68f2005b40cf",
  "latestActivityPreview": "...",
  "latestActivitySenderType": "agent",
  "latestActivitySenderId": "18a2ab37-0844-420c-a9dd-4daeb23c758c",
  "latestActivityMessageId": "e762e2a1-2c1d-4f3a-9ccb-34d1bdeb5018",
  "firstUnreadMessageId": "c705ebaa-fac9-45c2-9e8c-ecbc6521837e",
  "lastActivityAt": "2026-05-09 02:05:46.335644+00",
  "replyCount": 2,
  "lastReplyAt": "2026-05-09 02:05:46.335644+00",
  "unreadCount": 2,
  "taskNumber": null,
  "taskStatus": null,
  "taskClaimedByName": null
}
```

### Fields

| Field                     | Type        | Nullable | Description |
|---------------------------|-------------|----------|-------------|
| `threadChannelId`         | UUID string | no       | The channel ID of the thread itself. Used to open the thread view. |
| `parentMessageId`         | UUID string | no       | ID of the message that started this thread. **Key field for reply-count join** — always present on this endpoint. |
| `parentChannelId`         | UUID string | no       | Channel ID of the channel containing the parent message. Used to filter threads relevant to a channel. |
| `parentChannelName`       | string      | no       | Display name of the parent channel. |
| `parentChannelType`       | string enum | no       | `"channel"` \| `"dm"`. |
| `parentMessagePreview`    | string      | yes      | Truncated preview text of the parent message. |
| `parentMessageSenderType` | string enum | no       | `"user"` \| `"agent"`. |
| `parentMessageSenderId`   | UUID string | no       | Sender ID of the parent message author. |
| `latestActivityPreview`   | string      | yes      | Truncated preview text of the most recent reply. |
| `latestActivitySenderType`| string enum | yes      | `"user"` \| `"agent"`. Null if no replies yet. |
| `latestActivitySenderId`  | UUID string | yes      | Sender ID of the most recent reply author. |
| `latestActivityMessageId` | UUID string | yes      | Message ID of the most recent reply. |
| `firstUnreadMessageId`    | UUID string | yes      | Message ID of the first unread reply; null if fully read. |
| `lastActivityAt`          | string      | no       | ISO 8601 / Postgres timestamp of the most recent reply activity. |
| `replyCount`              | number      | no       | Total number of replies in the thread. Always a concrete integer, not null. |
| `lastReplyAt`             | string      | no       | ISO 8601 / Postgres timestamp of the last reply. |
| `unreadCount`             | number      | no       | Number of unread replies in this thread. |
| `taskNumber`              | number      | yes      | Task number if this thread is linked to a task; null otherwise. |
| `taskStatus`              | string enum | yes      | Task status string if linked; null otherwise. |
| `taskClaimedByName`       | string      | yes      | Display name of the member who claimed the task; null if unclaimed or not task-linked. |

---

## Critical distinction from `GET /channels/{channelId}/threads`

| Aspect                | `/channels/{channelId}/threads`          | `/channels/threads/followed`                      |
|-----------------------|------------------------------------------|---------------------------------------------------|
| Scope                 | All threads under one channel            | All threads the current user follows, any channel |
| `parentMessageId`     | May be absent — backend does not always populate it | **Always present**                        |
| `parentChannelId`     | Implicit (same as `{channelId}`)         | Explicit field — required for cross-channel join  |
| Reply-count join      | Unreliable without `parentMessageId`     | Reliable — `parentMessageId` is always a UUID     |
| Primary use           | Thread list within one channel           | Followed Threads inbox + reply-count join fallback |

---

## Android usage

### Followed Threads screen

`GET /channels/threads/followed` is the data source for the Followed Threads inbox screen (`FollowedThreadsScreen`). Each row shows the parent message preview, thread channel, and reply/unread counts.

### Reply-count join fallback (added 2026-05-09)

`loadChannelMessages()` in `RealSlockRepositories.kt` now calls **both** `/channels/{channelId}/threads` and `/channels/threads/followed` on every initial channel load:

1. Fetch `/channels/{channelId}/threads` → `channelThreads`
2. Fetch `/channels/threads/followed` → filter by `parentChannelId == channelId` → `followedForChannel`
3. Merge: `(channelThreads + followedForChannel).distinctBy { it.key }`
4. Pass merged list to `withReplySummaries()`

Because `/channels/threads/followed` always has `parentMessageId`, even when `/channels/{channelId}/threads` omits it, the join succeeds and the "x replies" chip renders correctly.

Diagnostic log key: `thread_summaries_loaded source=... channel=N followed_filtered=M merged=P`

### Mapper landing point

- `toThreadListSnapshot()` → `toInboxItem()` in `RealSlockRepositories.kt`
- `toInboxItem()` reads `threadChannelId` (maps to `InboxItem.threadChannelId`), `parentMessageId` (maps to `InboxItem.parentMessageId`), `parentChannelId` (maps to `InboxItem.parentChannelId`), and `replyCount` via `replyCountFromAliases()`
- `withReplySummaries()` then joins `InboxItem` rows back to `Message` rows using `matchesParentMessage()`

---

## Null / missing semantics

- `firstUnreadMessageId: null` → thread is fully read.
- `taskNumber: null` → thread has no task association.
- `taskStatus: null` → no task or task has no status.
- `taskClaimedByName: null` → task not claimed or no task.
- `latestActivityPreview` may be truncated or null for threads with no activity after creation.

---

## Security

- `Authorization` header and `X-Server-Id` are attached by the shared API client interceptors.
- `parentMessagePreview` and `latestActivityPreview` must be treated as untrusted user-generated text; render with appropriate escaping.
- This endpoint is scoped to the authenticated user's followed threads only — no server-wide data is leaked.
