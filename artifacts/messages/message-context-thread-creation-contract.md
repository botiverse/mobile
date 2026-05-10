# Android Message Context And Reply Thread Creation Contract

Source: Web bundle and live Chrome request sequence observed on 2026-05-09, plus Android task #71.

Related canonical docs:
- [Android API Contract Index](../api-contract-index.md)
- [Reply count and thread-summary join contract](./replycount-thread-summary-contract.md)
- [Followed threads API contract](./followed-threads-api-contract.md)

---

## Purpose

Opening the Reply menu on a message may need to create the reply thread first. Android must not guess the parent channel from the currently visible thread/channel when the selected message has no `threadChannelId` yet.

The Web flow resolves the message context first, then calls the thread endpoint with the parent channel id:

1. `GET /api/messages/context/{messageId}`
2. `POST /api/channels/{channelId}/threads` with `{"parentMessageId":"{messageId}"}`

This matters for zero-reply messages: an empty body or a message with `replyCount = 0` is still reply-able and should create a new thread under the message's real parent channel.

---

## Endpoint: message context

```
GET /api/messages/context/{messageId}
```

### Android usage

Before creating/opening a thread for a message without an existing `threadChannelId`, call message context and resolve the parent channel from the returned data.

Resolution order:

1. The focused message from `messages[]` or `message` whose `id == messageId`, using its `channelId`.
2. `conversation.channelId` from the context response.
3. `conversation.threadId` from the context response, treated as a channel id only as a compatibility fallback.
4. The original row fallback: `message.channelId ?: message.threadId`.

The resolved message should be used as the pinned parent/origin row so author, avatar, channel id, body, and timestamp come from the context snapshot when available.

---

## Endpoint: create/open reply thread

```
POST /api/channels/{channelId}/threads
Content-Type: application/json

{"parentMessageId":"{messageId}"}
```

### Semantics

- `{channelId}` must be the parent channel containing `{messageId}`, not an existing thread id and not the action row id.
- `parentMessageId` is always the selected message id.
- The response must provide an explicit thread channel id via one of the accepted aliases below.
- A `replyCount` of `0` does not mean the Reply action is unavailable. It only means there are no existing replies yet.

### Accepted response aliases

Thread channel id aliases accepted by Android:

- `threadChannelId`
- `thread_channel_id`
- `replyThreadChannelId`
- `reply_thread_channel_id`
- `repliesThreadChannelId`
- `replies_thread_channel_id`
- `childThreadChannelId`
- `child_thread_channel_id`
- `replyChannelId`
- `reply_channel_id`
- `replyThreadId`
- `reply_thread_id`
- `commentsThreadChannelId`
- `comments_thread_channel_id`
- nested under `channel`, `threadChannel`, `replyThread`, or `thread` as `threadChannelId`, `thread_channel_id`, `channelId`, `channel_id`, `id`, or `_id`

Do not use a generic top-level `id` from the action response as the thread channel id. Some responses use `id` as an action/summary row id, not as the reply thread channel.

Reply count aliases are documented in [Reply count and thread-summary join contract](./replycount-thread-summary-contract.md).

---

## UI Contract

- Message overflow menu must include `Reply` whenever `onOpenThread` is available, including `replyCount = 0` messages.
- Inside an already-open Threads/reply panel, child reply message action overlays do not show `Reply`. Nested thread creation from reply rows is intentionally disabled in that surface; keep Copy, Save, Share, and task actions available.
- Footer `N replies` chip is display-only for existing replies and still renders only when `replyCount > 0`.
- Tapping `Reply` on a zero-reply message creates/opens the thread; it must not be disabled as an anti-empty-thread workaround.
- Existing messages with `threadChannelId` can still open directly without the pre-create context call.
- After a new reply thread is created, `GET /messages/channel/{threadChannelId}` can legitimately return an empty message list until the first reply is sent. Android must keep the reply-thread screen in content mode with the pinned parent/origin message and composer visible. It must not replace that screen with the global `No messages yet` empty state.

---

## Diagnostics

Android logs the creation path under `SlockThreadReplies`:

- `open_message_thread_context_start messageId=... fallbackChannelId=... messageThreadId=...`
- `open_message_thread_context_result messageId=... result=... resolvedChannelId=... contextThreadId=... contextMessageCount=...`
- `open_message_thread_create_start parentMessageId=... channelId=...`
- `thread_action_result source=thread_create_or_open threadChannelId=... parentMessageId=... replyCount=...`

These logs are safe for adb debugging and should not include auth tokens.

---

## Regression Coverage

- `RealSlockRepositoriesTest.conversationRepositoryMapsMessageContextChannelForThreadCreation`
- `RealSlockRepositoriesTest.conversationRepositoryDoesNotUseGenericActionIdAsThreadChannelId`
- `ThreadScreenPaginationTest.channelReplyActionCanCreateThreadWithoutExistingReplies`
- `ThreadScreenPaginationTest.replyThreadWithNoRepliesKeepsOriginInsteadOfEmptyState`
- `ThreadScreenPaginationTest.replyThreadPinsOriginOutsideScrollableReplies`
- `ThreadScreenReplyAffordanceTest.messageBodyTextClickShowsActionOverlayAndReplyOpensThread`
