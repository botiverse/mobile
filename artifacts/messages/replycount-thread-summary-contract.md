# Android Message Reply Count and Thread-Summary Join Contract

Task: #386

This doc records the analyzed contract for reply counts on message rows and the thread-summary join path that backfills those counts into visible rows.

Source of truth:

- `app/src/main/java/ai/slock/android/core/data/network/RealSlockRepositories.kt`
- `app/src/main/java/ai/slock/android/core/data/realtime/RealtimeEventMapper.kt`
- `app/src/test/java/ai/slock/android/core/data/network/RealSlockRepositoriesTest.kt`
- `app/src/test/java/ai/slock/android/core/data/realtime/RealtimeRepositoryTest.kt`
- `app/src/androidTest/java/ai/slock/android/feature/thread/ThreadScreenReplyAffordanceTest.kt`

Related canonical docs:

- [Android API Contract Index](../api-contract-index.md)
- [Android Socket.IO Boundary](../realtime/android-socketio-boundary.md)

## Why this contract exists

Channel message rows show a replies affordance only when the mapped reply count is greater than zero. If the first REST page omits reply count and the thread-summary join path misses an alias, the row is treated as `0` replies and the affordance disappears. This doc locks the field aliases and merge semantics so the mapping does not have to be reverse engineered again.

## REST sources that feed the contract

The reply count can be sourced from the initial REST snapshot or from the thread summary join path:

- `GET /messages/channel/{channelId}`
- `GET /messages/context/{messageId}`
- `GET /channels/{channelId}/threads`
- `POST /messages`
- `POST /channels/{channelId}/threads`

The REST sources above may return reply-count data directly on the message object or via nested thread/summary objects.

## Mapper landing points

### Message rows

`RealSlockRepositories.kt`:

- `toMessage()` maps the main message shape into `Message.replyCount`
- `replyCountFromAliases()` normalizes reply-count aliases for REST snapshots and summary joins

`RealtimeEventMapper.kt`:

- `toMessage()` maps `message:new` / `message:updated` into `Message.replyCount`
- `toThreadUpdated()` maps `thread:updated` into `RealtimeEvent.ThreadUpdated.replyCount`

### Thread summary join

`RealSlockRepositories.kt`:

- `toThreadSnapshot()` builds the visible thread message list from the REST message snapshot
- `withReplySummaries()` merges `/channels/{channelId}/threads` results back into visible message rows
- merge rule: preserve the larger value with `maxOf(message.replyCount, summary.replyCount)`

This means the `/channels/{channelId}/threads` mapper must recognize the same reply-count aliases as the message mapper, otherwise a summary payload with a different alias will backfill as `0`.

## Accepted reply-count aliases

Current canonical alias set used by the repository helper:

- `replyCount`
- `reply_count`
- `replies`
- `replies_count`
- `threadReplyCount`
- `thread_reply_count`
- `repliesCount`
- `threadRepliesCount`
- `thread_replies_count`
- `commentsCount`
- `comments_count`
- `childCount`
- `child_count`

Nested carriers currently recognized by the repository helper:

- `thread`
- `replyThread`
- `messageThread`
- `repliesThread`
- `summary`
- `data`
- `meta`

Realtime message mapping currently uses the same top-level alias set for `replyCount`, while the nested thread-object path is narrower. If the backend starts nesting reply counts inside a realtime thread object, expand that path in lockstep with the repository helper.

## Semantics

- `0` means no replies.
- `null` / missing currently collapse to `0` in the app mapping.
- Display affordance should be hidden when the mapped count is `0`.
- The contract is display-safe but not source-of-truth safe: if the backend introduces a new alias that is not in the accepted set, the app will treat it as `0` until the mapper is updated.

## Merge rules

- REST first load seeds the message/thread snapshot.
- `/channels/{channelId}/threads` thread-summary rows backfill the visible message rows.
- Realtime `message:new` / `message:updated` update the same message model.
- Realtime `thread:updated` updates reply count and should merge with `max(old, event.replyCount)` to avoid regressions.
- Channel list rows and thread rows consume the mapped count directly; no UI layer should try to infer reply counts from raw payloads.

## Regression coverage

- `app/src/test/java/ai/slock/android/core/data/network/RealSlockRepositoriesTest.kt`
  - `conversationRepositoryBackfillsReplyCountFromThreadSummaryAliases()`
  - verifies `/channels/{channelId}/threads` summary data using `reply_count` backfills `Message.replyCount`
- `app/src/test/java/ai/slock/android/core/data/realtime/RealtimeRepositoryTest.kt`
  - `mapperHandlesTaskAndThreadEvents()`
  - `mapperPreservesTopLevelReplyThreadFromRawThreadId()`
  - verifies realtime `thread:updated` and `message:new` reply-count mapping
- `app/src/androidTest/java/ai/slock/android/feature/thread/ThreadScreenReplyAffordanceTest.kt`
  - `messageRowShowsReplyCountAffordanceOnlyWhenRepliesExist()`
  - `messageRowDoesNotShowMisleadingAffordanceWhenNoReplies()`

## Implementation note

The reply-count contract is intentionally shared across:

- REST first page message rows
- `/channels/{channelId}/threads` summary join
- realtime thread/message updates

Keeping the alias set and merge rule identical across those paths prevents the row affordance from disappearing when one source omits the count.
