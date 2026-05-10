# Android Read Receipts Contract

Source: Web bundle in `~/Downloads/index-EpAFmlk5.js` / `~/Downloads/chat_files/index-EpAFmlk5.js`, reviewed 2026-05-10.

Related docs:
- [Android API Contract Index](../api-contract-index.md)
- [Channel messages API contract](./channel-messages-api-contract.md)
- [Socket.IO boundary](../realtime/android-socketio-boundary.md)

---

## Endpoints

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `GET` | `/api/channels/unread` | none | Load unread counts per channel. |
| `POST` | `/api/channels/{channelId}/read` | `{ "seq": number }` | Mark one channel read up to the highest visible message sequence. |
| `POST` | `/api/channels/{channelId}/read-all` | none | Mark the whole channel/thread read when seq-specific marking is not used. |
| `POST` | `/api/channels/{channelId}/unread` | none | Manually mark a channel unread. |
| `POST` | `/api/channels/inbox/read-all` | none | Mark every Inbox item read. |

All requests use the normal Android data-layer auth/session boundary: `Authorization` and `X-Server-Id` are injected by the API client/interceptor, not by UI code.

---

## Web Behavior

The Web message store keeps two local structures:

- A pending read-seq map keyed by `channelId`.
- An in-flight request map keyed by `channelId` so only one read request per channel is active.

When Web sees a channel as read up to a sequence, it stores `max(previousSeq, seq)` and posts:

```http
POST /api/channels/{channelId}/read
Content-Type: application/json

{ "seq": 123 }
```

If another newer seq arrives while that request is in flight, Web sends a follow-up request after the first finishes. Failed requests are swallowed locally and retried when the same mark/read path is hit later.

Known Web triggers:

- Initial `loadMessages(channelId)` calls `POST /channels/{channelId}/read { seq: maxLoadedSeq }` after loading messages unless the channel is locally marked unread.
- Loading newer messages marks read to the new max seq when there is no newer page and the user is near the bottom.
- Realtime `message:new` marks read immediately when the current channel is open, the user is near the bottom, and there is no newer/gap state.
- `setNearBottom(true)` calls `markCurrentChannelRead()`, which marks read to the current visible max seq.
- Opening a sidebar channel with existing unread count calls `markRead(channelId)`, which posts `/channels/{channelId}/read-all` and clears local unread.
- Inbox `markRead(item)` posts `/channels/{channelId}/read-all`; for thread items it uses `threadChannelId`, otherwise `channelId`.
- Inbox `markAllRead()` posts `/channels/inbox/read-all` and then refreshes unread counts and followed threads.

---

## Android State As Of 2026-05-10

Android already has repository/API boundaries:

- `SlockApi.markChannelReadToSeq(channelId, body)` -> `POST channels/{channelId}/read`
- `SlockApi.markChannelReadAll(channelId)` -> `POST channels/{channelId}/read-all`
- `SlockApi.markChannelUnread(channelId)` -> `POST channels/{channelId}/unread`
- `SlockApi.markInboxReadAll()` -> `POST channels/inbox/read-all`
- `ReadReceiptRepository` and `RealReadReceiptRepository`

Current Android UI integration:

- `MainActivity.markThreadReadIfLoaded(content)` chooses `markChannelReadToSeq(channelId, maxSeq)` when loaded messages have seq values; otherwise it falls back to `markChannelReadAll(channelId)`.
- `ReadReceiptCoordinator` owns Web-style per-channel coalescing: it keeps the highest pending seq, allows only one in-flight read request per channel, and sends a follow-up request when a higher seq arrives while the first request is still running.
- It clears local Home / Inbox / followed thread / Saved / Search unread state optimistically before the network call.
- It reconciles from `/channels/unread` when the network mark-read call fails.
- It is called after several thread-open paths, when a realtime message is received for the currently visible thread while near bottom, and when the current thread reports that it is near bottom again.

Current gaps versus Web:

- Android does not clearly mark read from a generic channel load / current channel selection path; most call sites are thread/open-thread oriented.
- Android only marks realtime messages read when `homeRoute == HomeRoute.Thread` and `selectedThreadNearBottom`; channel-level foreground/near-bottom behavior should be checked against the actual screen route.
- Android has repository support for Inbox read-all, but the current app wiring should be verified for explicit Inbox item mark-read and Inbox mark-all actions.

---

## Mapper Semantics

`ReadReceiptResult` accepts these response aliases:

- channel id: `channelId`, `id`
- seq: `seq`, `readSeq`, `lastReadSeq`
- unread count: `unreadCount`, `unread`, `count`
- changed/success: `changed`, `ok`, `success`

When the response omits these fields, Android falls back to the request channel id / seq and treats the unread count as `0`.

---

## Regression Coverage

Existing unit coverage:

- `app/src/test/java/ai/slock/android/core/data/repository/ReadReceiptCoordinatorTest.kt`
  - single-flight per channel while a request is in progress
  - follow-up request uses the highest pending seq and does not regress
  - non-positive seq falls back to read-all
  - failed read calls the failure/reconcile callback
- `app/src/test/java/ai/slock/android/core/data/network/RealSlockRepositoriesTest.kt`
  - `RealReadReceiptRepository.markChannelReadToSeq(...)`
  - `markChannelReadAll(...)`
  - `markChannelUnread(...)`
  - `markInboxReadAll(...)`

Recommended follow-up coverage:

- Fake repository/UI test that opens a real channel/thread with unread messages and asserts `markChannelReadToSeq(channelId, maxVisibleSeq)` is called.
- Realtime visible-message test: while the current thread/channel is open and near bottom, receiving `message:new` with seq should call mark-read to that seq.
- Scroll/near-bottom test: moving back to bottom after new messages should mark read to the current max seq.
- Inbox action test: item-level mark read should call `/read-all` for that item's channel/thread; mark-all should call `/channels/inbox/read-all`.
