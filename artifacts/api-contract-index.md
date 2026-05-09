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
- [Settings Billing and Server sections contract](./settings/android-settings-billing-server-contract.md) - task #388

## Contract Doc Map

Current canonical doc slices for the analyzed API / mapper surface:

- task #385: [Socket.IO / realtime contract and merge semantics](./realtime/android-socketio-boundary.md)
- task #386: [message replyCount and channel thread-summary join contract](./messages/replycount-thread-summary-contract.md)
- task #387: members profiles, agents, reminders, and activity contracts
- task #388: [settings Billing and confirmed Server sections contracts](./settings/android-settings-billing-server-contract.md)
- task #389: inbox/home unread, channel list, Saved, and Tasks list contracts
- task #390: QA coverage and traceability review for repo contract docs

## Message And Thread Contract

### REST source

- `GET /messages/channel/{channelId}`
- `GET /messages/context/{messageId}`
- `GET /messages/sync`
- `POST /messages`
- thread-related list endpoints via `GET /channels/{channelId}/threads` and `POST /channels/{channelId}/threads`

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
- `message:updated`
- `thread:updated`
- `task:created`
- `task:updated`
- `task:deleted`
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

Known REST endpoints:

- `GET /servers/{serverId}/members`
- `GET /members/{memberId}/profile`
- `GET /channels/{channelId}/members`
- `GET /agents/{agentId}/agent-dms`

See existing member / avatar work for any field-specific mapping details. If a new field alias is reverse engineered, add it here and link the test that locks it down.

## Repo Rule

If a contract is only discussed in chat, it is not done. Add the source, aliases, semantics, mapper landing point, and regression test here or in a linked doc.
