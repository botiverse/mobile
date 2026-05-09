# Android API Contract Index

Task: #383

Canonical entry point for analyzed API and mapper contracts. Use this instead of re-deriving fields from chat threads.

## Shared Contract Template

Each contract entry should record:

- endpoint or source
- payload field aliases
- `null` / missing / `0` semantics
- REST first-load versus realtime delta mapping
- mapper landing point
- regression test coverage

## Known Contract Docs

- [Tasks API contract](./tasks/task-api-contract-2026-05-07.md)
- [Workspace file preview contract](./workspace/file-preview-api-contract.md)
- [Socket.IO boundary](./realtime/android-socketio-boundary.md) - task #118
- [Channel messages API contract](./messages/channel-messages-api-contract.md) - `GET /api/messages/channel/{channelId}`
- [Reply count and thread-summary join contract](./messages/replycount-thread-summary-contract.md) - task #386
- [Followed threads API contract](./messages/followed-threads-api-contract.md) - `GET /api/channels/threads/followed` (2026-05-09)
- [Message context and reply thread creation contract](./messages/message-context-thread-creation-contract.md) - task #71
- [Settings Billing and Server sections contract](./settings/android-settings-billing-server-contract.md) - task #388
- [Members, agents, DM, reminders, and activity contract](./members/members-agents-dm-reminders-activity-contract.md) - task #387 / #设置项专修 task #2

## Contract Doc Map

Current canonical doc slices for the analyzed API / mapper surface:

- task #385: [Socket.IO / realtime contract and merge semantics](./realtime/android-socketio-boundary.md)
- task #386: [message replyCount and channel thread-summary join contract](./messages/replycount-thread-summary-contract.md)
- task #386b: [followed threads API contract](./messages/followed-threads-api-contract.md) — `GET /channels/threads/followed`, dual-source join
- task #71: [message context and reply thread creation contract](./messages/message-context-thread-creation-contract.md) — `GET /messages/context/{messageId}` before `POST /channels/{channelId}/threads`
- task #387 / #设置项专修 task #2: [members profiles, agents, DM, reminders, and activity contracts](./members/members-agents-dm-reminders-activity-contract.md)
- task #388: [settings Billing and confirmed Server sections contracts](./settings/android-settings-billing-server-contract.md)
- task #389: inbox/home unread, channel list, Saved, and Tasks list contracts
- task #390: QA coverage and traceability review for repo contract docs

## Message And Thread Contract

### REST source

- `GET /messages/channel/{channelId}`
- `GET /messages/context/{messageId}`
- `GET /messages/sync?since_seq={seq}&limit={n}&channel_id={channelId}` — gap sync, returns messages newer than `since_seq`
- `POST /messages`
- `GET /channels/{channelId}/threads` — threads under a channel; `parentMessageId` may be absent
- `POST /channels/{channelId}/threads` — create or open a thread; for messages without an existing `threadChannelId`, first resolve the parent channel through `GET /messages/context/{messageId}`
- `GET /channels/threads/followed` — all threads followed by the current user; always includes `parentMessageId`
- `POST /channels/threads/follow` / `unfollow` / `done` / `undone` — follow-state mutations

### Realtime source

- `message:new`
- `message:updated`
- `thread:updated`
- `dm:new`
- `sync:resume:response`

### `replyCount` aliases currently accepted by mapper

Primary landing point:

- `Message.replyCount`
- `RealtimeEvent.ThreadUpdated.replyCount`

Aliases observed in `RealtimeEventMapper` and current tests:

- `replyCount`
- `reply_count`
- `replies`
- `threadReplyCount`
- `thread_reply_count`
- `repliesCount`
- `replies_count`
- `threadRepliesCount`
- `thread_replies_count`
- `commentsCount`
- `comments_count`
- `childCount`
- `child_count`
- nested under `thread`, `replyThread`, `messageThread`, or `repliesThread` with the same alias set above

### Semantics

- `0` means no replies.
- missing / null currently falls back to `0` in message mapping.
- thread inference only treats a message as a top-level reply thread when `replyCount > 0`.
- there is no separate UI-only fallback contract; the row-level affordance depends on the mapped count being present.

### Regression coverage

- `app/src/test/java/ai/slock/android/core/data/realtime/RealtimeRepositoryTest.kt`
  - verifies `thread.updated` and `message` mapping with reply count aliases
- `app/src/test/java/ai/slock/android/core/data/network/RealSlockRepositoriesTest.kt`
  - verifies threaded message snapshots keep reply count through REST mapping
- `app/src/androidTest/java/ai/slock/android/feature/thread/ThreadScreenReplyAffordanceTest.kt`
  - verifies the UI shows replies affordance only when appropriate

## Socket.IO Contract Notes

Canonical source:

- [Socket.IO boundary](./realtime/android-socketio-boundary.md)

Source and auth:

- Socket base URL: `https://api.slock.ai`
- auth payload: `{ token, serverId }`
- token and selected server id come from `AuthSessionStore` via `SessionRealtimeRepository`

Mapped events:

- `message:new`
- `message:updated` — must preserve `replyCount` and `threadChannelId` using `maxOf` merge
- `thread:updated` — primary realtime reply-count update path
- `task:created`
- `task:updated`
- `task:deleted`
- `agent:activity` — member presence/activity; deduplicated by `(presence, kind)` in `SocketIoRealtimeClient` to prevent floods
- `sync:resume:response`
- `heartbeat`

Boundary-only lifecycle events:

- `connect`
- `disconnect`
- `connect_error`
- `rooms:joined`

Resume flow:

- after `rooms:joined`, client emits `sync:resume` with `{ lastSeq }` when `lastSeq > 0`
- heartbeat and resume response update latest sequence tracking
- app wiring should still do REST gap sync for active channel/thread after reconnect and de-dupe by message id/seq

Mapper landing point:

- `SocketIoRealtimeClient`
- `RealtimeEventMapper`
- `SessionRealtimeRepository`

Regression coverage:

- `app/src/test/java/ai/slock/android/core/data/realtime/RealtimeRepositoryTest.kt`
- `app/src/test/java/ai/slock/android/core/data/network/RealSlockRepositoriesTest.kt`

## Settings Contract Notes

Canonical Settings contract docs:

- [Settings Billing and Server sections contract](./settings/android-settings-billing-server-contract.md) - task #388

Confirmed REST endpoints in `SlockApi`:

- `GET /auth/me`
- `PATCH /auth/me`
- `GET /auth/sessions`
- `DELETE /auth/sessions/{sessionId}`
- `GET /servers/{serverId}/machines`
- `GET /servers/{serverId}/machines/{machineId}/workspaces`
- `GET /agents`

Repository behavior:

- session/auth state is resolved before requests
- selected server is required for server-scoped settings data
- mapper and repository should not leak raw tokens or headers into UI state

Open contract gaps to document when analyzed:

- exact profile field aliases for account/settings snapshots
- exact session row shape and revoke response shape
- settings section availability by role / permission
- any realtime settings events, if introduced later

Current Android exclusions:

- Browser settings are excluded from Android Settings even though the Web
  sidebar has a Browser entry.

## Members And Presence Contract Notes

Canonical source:

- [Members, agents, DM, reminders, and activity contract](./members/members-agents-dm-reminders-activity-contract.md)

Known REST endpoints:

- `GET /servers/{serverId}/members`
- `GET /members/{memberId}/profile`
- `GET /channels/{channelId}/members`
- `GET /agents` — full agent list for the server
- `GET /channels/dm`
- `POST /channels/dm` — confirmed Web open/create DM endpoint; body is `{ agentId }` or `{ userId }`
- `GET /agents/{agentId}/agent-dms` — present in Android API; not found in the current downloaded Web bundle
- `GET /reminders?ownerAgentId={id}&status=scheduled`
- `GET /agents/{agentId}/activity-log?limit=N` — confirmed Web activity-log endpoint

Important gap:

- Android currently has `GET /trajectoryLogs/{agentId}` for agent activity, but the downloaded Web bundle uses `GET /agents/{agentId}/activity-log?limit=50`. Treat `trajectoryLogs/{agentId}` as stale/unconfirmed until backend confirms it.

See the linked members contract for field aliases, null semantics, realtime events, mapper landing points, and regression coverage.

## HAR-Observed Endpoint Inventory (2026-05-09)

All endpoints observed from `debug.har` (web client session). Brotli-compressed bodies were not captured; shapes are inferred from `SlockApi.kt` and mapper code unless marked with a live sample.

### Auth

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/auth/me` | Current user profile. Polled on reconnect. |
| `POST` | `/api/auth/refresh` | Refreshes expired access tokens with the persisted refresh token. Body is `{ refreshToken }`; response returns `{ accessToken, refreshToken }`. Web retries `/auth/me` after this succeeds and does not clear local tokens on the first `/auth/me` 401. |
| `PATCH`| `/api/auth/me` | Update display name / avatar. |
| `GET`  | `/api/auth/sessions` | Active sessions list for Account settings. |
| `DELETE`| `/api/auth/sessions/{sessionId}` | Revoke a session. |

Android restore-session behavior:

- On app start, a stored session should first try `/auth/me` with the stored access token.
- If `/auth/me` returns 401, keep the stored refresh token, call `/auth/refresh`, save the new access/refresh tokens, then retry `/auth/me` to refresh user/server metadata.
- Clear the stored session only when the refresh request itself is unauthorized/invalid, or when the retried `/auth/me` is unauthorized after refresh.
- Do not log or persist real bearer tokens, refresh tokens, or raw auth headers in docs/diagnostics.

### Servers

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/servers` | List of servers the user belongs to. |
| `GET`  | `/api/servers/unread-summary` | Per-server unread counts. Polled frequently (seen 8+ times in session). |
| `GET`  | `/api/servers/{serverId}/members` | Full member list. |
| `GET`  | `/api/servers/{serverId}/sidebar-order` | Sidebar channel ordering. |
| `GET`  | `/api/servers/{serverId}/machines` | Machines linked to server. |
| `GET`  | `/api/servers/{serverId}/notification-settings` | Notification prefs for server. |
| `GET`  | `/api/servers/{serverId}/translation-settings` | Auto-translation config. |
| `GET`  | `/api/servers/{serverId}/onboarding-settings` | Onboarding flow config. |
| `PATCH`| `/api/servers/{serverId}` | Update server profile (name, avatar, etc.). |
| `DELETE`| `/api/servers/{serverId}` | Delete server. |

### Channels

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/channels?archived=include` | All channels (including archived). |
| `GET`  | `/api/channels/dm` | DM channels. |
| `POST` | `/api/channels/dm` | Open/create a DM. Body is `{ agentId }` or `{ userId }`. |
| `GET`  | `/api/channels/unread` | Unread message counts per channel. |
| `GET`  | `/api/channels/inbox?filter=all&limit=30&offset=0` | Inbox items. `filter` values: `all`, `unread`. |
| `GET`  | `/api/channels/saved?limit=20&offset=0` | Saved messages/items. |
| `GET`  | `/api/channels/{channelId}/members` | Members of a specific channel. |
| `POST` | `/api/channels/{channelId}/read` | Mark channel read up to a seq. Body: `{ seq }`. |
| `POST` | `/api/channels/{channelId}/read-all` | Mark entire channel as read. |
| `POST` | `/api/channels/{channelId}/unread` | Mark channel as unread. |
| `POST` | `/api/channels/inbox/read-all` | Mark all inbox items as read. |
| `POST` | `/api/channels` | Create a new channel. |
| `POST` | `/api/channels/{channelId}/join` | Join a channel. |
| `POST` | `/api/channels/{channelId}/members` | Add a member to a channel. |
| `DELETE`| `/api/channels/{channelId}/members/{kind}/{memberId}` | Remove a member. |

### Threads

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/channels/{channelId}/threads?limit=N` | Threads under a channel. `parentMessageId` **may be absent**. |
| `GET`  | `/api/channels/threads/followed?limit=N` | Threads followed by the user. `parentMessageId` **always present**. See [followed-threads-api-contract.md](./messages/followed-threads-api-contract.md). |
| `POST` | `/api/channels/{channelId}/threads` | Create or open a thread. Body: `{ parentMessageId }`. |
| `POST` | `/api/channels/threads/follow` | Follow a thread. |
| `POST` | `/api/channels/threads/unfollow` | Unfollow a thread. |
| `POST` | `/api/channels/threads/done` | Mark thread done. |
| `POST` | `/api/channels/threads/undone` | Mark thread undone. |

### Messages

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/messages/channel/{channelId}?limit=50&before={seq}` | Channel messages page. See [channel-messages-api-contract.md](./messages/channel-messages-api-contract.md). |
| `GET`  | `/api/messages/context/{messageId}` | Message context (surrounding messages for highlight). |
| `GET`  | `/api/messages/sync?since_seq={seq}&limit=200&channel_id={id}` | Gap sync — returns messages newer than `since_seq`. |
| `GET`  | `/api/messages/search?q={q}&limit=N&offset=N` | Full-text search. |
| `POST` | `/api/messages` | Send a message. |

### Tasks

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/tasks/channel/{channelId}` | Tasks scoped to a channel. Observed in HAR alongside channel message load. |
| `GET`  | `/api/tasks/server` | All tasks for the server. |
| `PATCH`| `/api/tasks/{taskId}/claim` | Claim a task. |
| `PATCH`| `/api/tasks/{taskId}/status` | Update task status. Body: `{ status }`. |

### Agents

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/agents` | All agents available to the server. |
| `GET`  | `/api/agents/{agentId}/agent-dms` | DM channels with a specific agent. |
| `GET`  | `/api/agents/{agentId}/activity-log?limit=N` | Web-confirmed persisted agent activity log endpoint. |

### Attachments

| Method | Path | Notes |
|--------|------|---------|
| `POST` | `/api/attachments/upload` | Multipart upload. Parts: `channelId`, `files[]`. |
| `GET`  | `/api/attachments/{attachmentId}/url` | Signed download URL for an attachment. |

### Misc

| Method | Path | Notes |
|--------|------|---------|
| `GET`  | `/api/announcements/active` | Active server announcements. |
| `GET`  | `/api/push/vapid-key` | VAPID public key for Web Push. Android uses FCM, not VAPID. |
| `GET`  | `/api/servers/{serverId}/usage` | Server usage stats (for billing). |
| `GET`  | `/api/billing/subscription` | Current billing subscription. |
| `POST` | `/api/servers/{serverId}/invites` | Create a server invite link. |
| `GET`  | `/api/reminders?ownerAgentId={id}&status=scheduled` | Scheduled reminders for an agent. |
| `GET`  | `/api/trajectoryLogs/{agentId}` | Stale/unconfirmed Android activity path; downloaded Web bundle uses `/api/agents/{agentId}/activity-log?limit=N`. |
| `GET`  | `/api/servers/{serverId}/machines/{machineId}/workspaces` | Workspaces for a machine. |

## Repo Rule

If a contract is only discussed in chat, it is not done. Add the source, aliases, semantics, mapper landing point, and regression test here or in a linked doc.
