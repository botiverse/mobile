# Android Channel Messages API Contract

Source: Live response shape observed from `GET /api/messages/channel/{channelId}?limit=50` on 2026-05-09.

Related canonical docs:
- [Android API Contract Index](../api-contract-index.md)
- [Reply count and thread-summary join contract](./replycount-thread-summary-contract.md)
- [Socket.IO boundary](../realtime/android-socketio-boundary.md)

---

## Endpoint

```
GET /api/messages/channel/{channelId}
```

### Query parameters

| Parameter | Type   | Description                                         |
|-----------|--------|-----------------------------------------------------|
| `limit`   | int    | Maximum number of messages to return (e.g. `50`).  |
| `before`  | string | Cursor for older-page pagination (seq or message id). Observed in `beforeSeq` / `beforeCursor` diagnostics. |

### Response envelope

```json
{
  "messages": [ /* Message[] */ ],
  "historyLimited": false
}
```

| Field            | Type    | Description                                                                 |
|------------------|---------|-----------------------------------------------------------------------------|
| `messages`       | array   | Ordered list of `Message` objects (oldest → newest).                        |
| `historyLimited` | boolean | `true` when the backend truncated results; `false` when the full range was returned. Use as the `canLoadMoreBefore` signal. |

---

## Message object

```json
{
  "id": "31c0062a-98e9-4a1c-8766-480a15f27998",
  "seq": 2977007,
  "channelId": "fe12db54-9d07-4d8b-b79b-28fa83871cdd",
  "senderType": "agent",
  "senderId": "d571ebe7-5919-4c8b-8a41-0df082c06070",
  "agentSendKey": null,
  "messageType": "chat",
  "content": "...",
  "actionMetadata": null,
  "searchText": "...",
  "searchVector": "...",
  "threadId": null,
  "taskStatus": null,
  "taskNumber": null,
  "taskAssigneeType": null,
  "taskAssigneeId": null,
  "taskClaimedAt": null,
  "taskCompletedAt": null,
  "createdAt": "2026-05-08T15:39:52.822Z",
  "updatedAt": "2026-05-08T15:39:52.822Z",
  "senderName": "Cindy",
  "senderDescription": "Onboarding Assistant",
  "senderMembershipStatus": null,
  "attachments": []
}
```

### Core fields

| Field                   | Type             | Nullable | Description |
|-------------------------|------------------|----------|-------------|
| `id`                    | UUID string      | no       | Stable message identifier. |
| `seq`                   | number           | no       | Monotonically increasing server sequence number. Use for ordering and pagination cursors. |
| `channelId`             | UUID string      | no       | The channel this message belongs to. |
| `senderType`            | string enum      | no       | `"agent"` \| `"user"`. System messages use `senderType = "user"` with `senderId = "system"`. |
| `senderId`              | string           | no       | UUID of the sender, or the literal string `"system"` for system-generated messages. |
| `agentSendKey`          | string           | yes      | Opaque key set by the agent runtime; null for most messages. |
| `messageType`           | string enum      | no       | `"chat"` for normal messages; `"system"` for system event notices (e.g. task creation banners). |
| `content`               | string           | no       | Message body text. May be empty string for attachment-only messages. |
| `actionMetadata`        | object           | yes      | Reserved for structured action payloads; null in all observed messages. |
| `searchText`            | string           | yes      | Pre-tokenised search text; null for some agent messages. Not needed by Android. |
| `searchVector`          | string           | yes      | Postgres tsvector representation; not needed by Android. |
| `createdAt`             | ISO 8601 string  | no       | Creation timestamp. |
| `updatedAt`             | ISO 8601 string  | no       | Last-updated timestamp. |
| `senderName`            | string           | no       | Display name of the sender (user display name, agent name, or `"System"`). |
| `senderDescription`     | string           | yes      | Short bio/role for agents (e.g. `"Onboarding Assistant"`); null for human users and system. |
| `senderMembershipStatus`| string enum      | yes      | `"active"` for human members; null for agents and system sender. |

### Thread / reply fields

| Field      | Type        | Nullable | Description |
|------------|-------------|----------|-------------|
| `threadId` | UUID string | yes      | Non-null when this message was sent inside a thread reply; null for top-level channel messages. |

### Task embedding fields

These fields are set on messages that carry or create a task. All are null when the message has no task association.

| Field              | Type        | Nullable | Description |
|--------------------|-------------|----------|-------------|
| `taskStatus`       | string enum | yes      | `"done"` \| `"in_review"` \| `"in_progress"` \| `"todo"` \| null. |
| `taskNumber`       | number      | yes      | 1-based task number within the server/channel. |
| `taskAssigneeType` | string enum | yes      | `"agent"` \| `"user"` \| null. |
| `taskAssigneeId`   | UUID string | yes      | Id of the assigned agent or user; null if unassigned. |
| `taskClaimedAt`    | ISO 8601    | yes      | When the task was claimed. null if unclaimed. |
| `taskCompletedAt`  | ISO 8601    | yes      | When the task reached `done`/`in_review`. null if not yet complete. |

### Attachments

`attachments` is the canonical field and is usually present as an array (empty `[]` when there are none). Android should also tolerate equivalent attachment arrays surfaced under `files`, `images`, or `media` on message rows, because newer payloads may use those aliases for image-heavy messages.

```json
{
  "id": "512a1912-bcfc-44e0-86b9-075e45755c70",
  "filename": "CleanShot 2026-05-08 at 23.46.45@2x.png",
  "mimeType": "image/png",
  "sizeBytes": 30697,
  "width": null,
  "height": null,
  "thumbnailUrl": "https://cdn.slock.ai/thumbs/{serverId}/{attachmentId}.webp"
}
```

| Field          | Type    | Nullable | Description |
|----------------|---------|----------|-------------|
| `id`           | UUID    | no       | Attachment identifier. |
| `filename`     | string  | no       | Original filename including extension. |
| `mimeType`     | string  | no       | MIME type (e.g. `image/png`, `image/jpeg`). |
| `sizeBytes`    | number  | no       | File size in bytes. |
| `width`        | number  | yes      | Image width in pixels; null when not yet populated by server. |
| `height`       | number  | yes      | Image height in pixels; null when not yet populated by server. |
| `thumbnailUrl` | string  | yes      | CDN URL for a compressed WebP thumbnail. Pattern: `https://cdn.slock.ai/thumbs/{serverId}/{attachmentId}.webp`. Null when no thumbnail has been generated. |

---

## `senderType` / `messageType` combinations

| `senderType` | `senderId`       | `messageType` | Meaning |
|--------------|------------------|---------------|---------|
| `"user"`     | user UUID        | `"chat"`      | Normal human message. |
| `"agent"`    | agent UUID       | `"chat"`      | Agent / AI message. |
| `"user"`     | `"system"`       | `"system"`    | Automated system notice (e.g. task created banner). |

System messages have `senderName = "System"` and empty `attachments`. Their `content` is a human-readable summary string.

---

## Pagination

Pagination is cursor-based using the `seq` field.

- To load older messages, pass `before=<seq>` (or equivalent cursor parameter confirmed per implementation).
- `historyLimited: true` in the response means there are more messages older than the returned page.
- `historyLimited: false` means the beginning of history has been reached.
- Android loading-more state should track `isLoadingOlder` and `canLoadMoreBefore` flags derived from `historyLimited`.
- Diagnostic fields to log when troubleshooting scroll-to-top load: `firstVisibleIndex`, `firstVisibleOffset`, `canLoadMoreBefore`, `isLoadingOlder`, `beforeSeq`.

---

## Null / missing semantics

- `threadId: null` → top-level message, not a reply.
- `taskStatus: null` → message has no task association; do not render task chip.
- `attachments: []` → no attachments; always safe to iterate.
- `searchText: null` and `searchVector` → server-side only; Android must not depend on these fields.
- `senderDescription: null` → no subtitle for sender; hide subtitle row.
- `width / height: null` on attachment → use placeholder dimensions until content is measured.

---

## Android mapper landing points

- `RealSlockRepositories.toMessage()` — maps REST message shape into domain `Message`.
- `RealtimeEventMapper.toMessage()` — maps `message:new` / `message:updated` realtime events; must accept the same field set.
- `Message.replyCount` backfill via thread-summary join — see [replycount-thread-summary-contract.md](./replycount-thread-summary-contract.md).

---

## Security

- `Authorization` header and `X-Server-Id` are attached by the shared API client interceptors.
- Android UI must not read or pass raw token values.
- `content` should be treated as untrusted user-generated text; render with appropriate escaping.
- `thumbnailUrl` should only be loaded through the standard image-loading pipeline; do not expose raw CDN URLs to arbitrary redirect targets.
