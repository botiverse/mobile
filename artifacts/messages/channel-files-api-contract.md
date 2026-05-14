# Channel Files API Contract

Task: #142

Source inspected on 2026-05-14:

- `https://app.slock.ai`
- `https://app.slock.ai/assets/index-D7TCB7di.js`
- local copy: `artifacts/web-current/index-D7TCB7di.js`
- live API validation:
  - `LiveSlockDbFlowTest.liveChannelFilesListsUploadedAttachmentMetadata`
  - rerun successfully on 2026-05-14 after credential fix

## What changed on Web

The channel conversation page now has a third top tab:

- `Chat`
- `Tasks`
- `Files`

The `Files` tab renders a dedicated panel that is separate from the older agent workspace tree.

This is **not** backed by the previously documented agent workspace endpoints:

- `GET /agents/{agentId}/workspace-files`
- `GET /agents/{agentId}/workspace-files/read`

Instead, Web now calls a new conversation-scoped endpoint under `channels`.

## Confirmed endpoint

### List files attached in a channel / thread

`GET /channels/{channelId}/files?limit=50[&cursor={nextCursor}]`

Observed behavior from the current Web bundle and confirmed by live test coverage:

- initial load requests `limit=50`
- infinite scroll / manual pagination uses `cursor`
- response is read as:

```json
{
  "files": [],
  "nextCursor": "..."
}
```

Web uses:

- `files`: array, defaulting to `[]`
- `nextCursor`: nullable pagination token

If `nextCursor` is present, Web keeps loading more rows when the scroll container nears the bottom.

## Observed file item fields

The current bundle directly reads these fields from each `files[]` row:

```json
{
  "id": "attachment-id",
  "filename": "design.png",
  "mimeType": "image/png",
  "sizeBytes": 12345,
  "createdAt": "2026-05-14T10:00:00.000Z",
  "thumbnailUrl": "https://...",
  "width": 1600,
  "height": 900,
  "messageId": "message-id",
  "source": {
    "type": "channel",
    "parentMessageId": null,
    "parentMessageShortId": null
  }
}
```

Confirmed fields consumed by Web:

- top-level
  - `id`
  - `filename`
  - `mimeType`
  - `sizeBytes`
  - `createdAt`
  - `thumbnailUrl`
  - `width`
  - `height`
  - `messageId`
- nested `source`
  - `type`
  - `parentMessageId`
  - `parentMessageShortId`

## Live-confirmed payload fields

The new live test covers:

1. create temp channel
2. upload attachment
3. send message with `attachmentIds`
4. call `GET /channels/{channelId}/files?limit=50`
5. poll until the uploaded row appears

Fields now confirmed against the real API by test assertions:

- top-level row fields
  - `filename`
  - `messageId`
  - `sizeBytes`
  - `thumbnailUrl` (field presence confirmed)
  - `source` (object presence confirmed)
- nested `source`
  - `type` (non-blank)

The live test intentionally matches by uploaded `attachmentId` or `filename`, then asserts:

- returned `filename` equals uploaded filename
- returned `messageId` equals the sent message id
- returned `sizeBytes` equals uploaded byte count
- `source` exists
- `thumbnailUrl` key exists
- `source.type` is present

## UI semantics

### Item type / preview

Web derives the file kind from `mimeType` and filename extension:

- image
- video
- pdf
- archive
- other

Image preview rules:

- if `thumbnailUrl` is already present, Web uses it directly
- if the file is image-like but `thumbnailUrl` is absent, Web falls back to:
  - `GET /attachments/{attachmentId}/url`
- SVG only previews when `thumbnailUrl` exists

### Open original message

Clicking the row navigates back to the message that introduced the file:

- thread attachment with `source.type === "thread"` and `source.parentMessageId`:
  - Web opens the parent channel thread message via `toThreadMessage(...)`
- DM channel:
  - Web opens `toDmMessage(channelId, messageId)`
- regular channel:
  - Web opens `toMessage(channelId, messageId)`

### Download

The Download button does:

- `GET /attachments/{attachmentId}/url`
- then downloads the returned `url`

### Empty state

Empty-state copy in the current bundle:

> Attach files in Chat, or drag files into the message composer. They will appear here after the message is sent.

That strongly confirms the tab is an attachment index for the conversation, not a workspace browser.

## Android / KMP gap

Current Android shared client has:

- `GET /agents/{agentId}/workspace-files`
- `GET /agents/{agentId}/workspace-files/read`

Current Android shared client does **not** yet expose:

- `GET /channels/{channelId}/files`

So the new Web `Files` tab is currently outside the Android/KMP contract surface.

## Live-test status

The repo already has an opt-in real-API mechanism:

- `app/src/test/java/ai/slock/android/core/data/network/LiveSlockDbFlowTest.kt`
- `scripts/run_live_slock_db_flow.sh`

Current live coverage now includes `/channels/{channelId}/files` through:

- `LiveSlockDbFlowTest.liveChannelFilesListsUploadedAttachmentMetadata`

That means this contract is no longer only bundle-derived; the key row fields above are now backed by a passing live API test.

## Recommended Android contract surface

Add a conversation-scoped attachment index instead of reusing workspace models:

```kotlin
interface ChannelFilesRepository {
    suspend fun getInitial(channelId: ChannelId, limit: Int = 50): DataResult<ChannelFilesSnapshot>
    suspend fun getMore(channelId: ChannelId, cursor: String, limit: Int = 50): DataResult<ChannelFilesSnapshot>
}
```

Suggested row fields:

```kotlin
data class ChannelFileItem(
    val attachmentId: AttachmentId,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val createdAt: String?,
    val thumbnailUrl: String?,
    val width: Int?,
    val height: Int?,
    val messageId: MessageId,
    val sourceType: String?,
    val parentMessageId: MessageId?,
    val parentMessageShortId: String?
)
```

This should remain distinct from agent workspace tree / file-preview models.
