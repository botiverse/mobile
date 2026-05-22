# API Contract / Livetest Audit

Scope: Android/KMP API contract cleanup against the upstream Slock source at
`/Users/artin/0Workspace/github.com/botiverse/slock/`.

This audit is not a visual parity gate. It checks whether Android/KMP request
paths, response field mapping, and live contract coverage match the real server
and web contracts instead of migration-era guessed JSON aliases.

## Source Of Truth Checked

Upstream server routes:

- `packages/server/src/routes/messages.ts`
- `packages/server/src/routes/channels.ts`
- `packages/server/src/routes/attachments.ts`
- `packages/server/src/routes/tasks.ts`
- `packages/server/src/routes/servers.ts`
- `packages/server/src/services/messageService.ts`
- `packages/server/src/services/channelService.ts`
- `packages/server/src/services/serverService.ts`

Android/KMP consumers:

- `compose/shared/src/commonMain/kotlin/ai/slock/compose/network/SlockApiClient.kt`
- `app/src/main/java/ai/slock/android/core/data/network/SlockApi.kt`
- `app/src/main/java/ai/slock/android/core/data/network/RealSlockRepositories.kt`
- shared parsers/stores under `compose/shared/src/commonMain/kotlin/ai/slock/compose/`

## Request Contract Status

| Area | Android/KMP endpoint | Upstream endpoint | Status | Notes |
| --- | --- | --- | --- | --- |
| Home channels | `GET channels?archived=exclude/include/only` | `channelRouter.get("/")` | Match | Upstream returns an array and validates `archived`. |
| Home DMs | `GET channels/dm` / `POST channels/dm` | `channelRouter.get/post("/dm")` | Match | Canonical display fields are `peerName`, `peerDisplayName`, `peerAvatarUrl`, `peerType`. Root `name` is not the peer display source. |
| Unread counts | `GET channels/unread`, `POST channels/{id}/read`, `read-all`, `unread` | `channelRouter.get("/unread")`, `post("/:id/read")`, etc. | Match | `read` requires body `{ seq }`; `read-all` returns `{ ok, seq }`; `unread` returns `{ ok, unreadCount }`. |
| Server unread summary | `GET servers/unread-summary` | `serverRouter.get("/unread-summary")` | Match | Upstream returns an array of `{ serverId, unreadCount }`. |
| Messages page | `GET messages/channel/{channelId}?limit=&before=` | `messageRouter.get("/channel/:channelId")` | Match | Upstream also supports `after`; KMP currently uses `before` for older page and `sync` for newer page. |
| Message sync | `GET messages/sync?since_seq=&limit=&channel_id=` | `messageRouter.get("/sync")` | Match | Query names are authoritative: `since_seq`, `channel_id`, `limit`. |
| Message context | `GET messages/context/{messageId}` | `messageRouter.get("/context/:messageId")` | Match | Optional `channelId` query supports short-id context resolution; Android/KMP does not yet expose that optional query everywhere. |
| Send message | `POST messages` body `{ channelId, content, attachmentIds?, asTask? }` | `messageRouter.post("/")` | Match | Server rejects blank content and archived/read-only channels. |
| Attachments | `POST attachments/upload`, `GET attachments/{id}/url` | `attachmentRouter.post("/upload")`, public `get("/:id/url")` | Match | Multipart field name is `channelId`; response wrapper is `{ attachments: [...] }`; open target is `{ url, expiresAt }`. |
| Tasks | `GET tasks/server`, `GET tasks/channel/{channelId}`, `PATCH tasks/{taskId}/claim/status` | `taskRouter` | Match | Task actions return `{ task }`; list endpoints return `{ tasks }`. |
| Server members | `GET servers/{serverId}/members` | `serverRouter.get("/:id/members")` | Match | Upstream returns a direct array. Canonical member id field is `userId`, not generic `id`. |
| Server settings | `PATCH servers/{serverId}`, usage, invites, onboarding | `serverRouter` | Mostly match | Some Settings UI is still pending by capability task, but route paths are confirmed. |
| Machines/workspaces | `GET servers/{serverId}/machines`, `.../workspaces` | `serverRouter` | Match | Machine detail/log history is still not confirmed as a product capability. |

## Response Field Cleanup Findings

### Confirmed Real Fields

- DM rows: `id`, `name`, `type`, `description`, `createdAt`, `peerId`,
  `peerName`, `peerDisplayName`, `peerDescription`, `peerGravatarHash`,
  `peerAvatarUrl`, `peerType`, plus activity fields from `attachLastMessageAt`.
- Message rows from page/sync/context: DB message fields plus
  `senderName`, `senderDescription`, `senderMembershipStatus`, `reactions`,
  `attachments[]`. Attachment rows carry `id`, `filename`, `mimeType`,
  `sizeBytes`, `width`, `height`, `thumbnailUrl`.
- Server member rows: `userId`, `name`, `displayName`, `description`,
  `avatarUrl`, `role`, `joinedAt`, `email`, `gravatarHash`.
- Inbox rows are explicitly contracted in upstream `channelService.InboxItem`.
  Thread inbox rows use `threadChannelId`, `parentMessageId`,
  `parentChannelId`, `parentChannelName`, `parentMessagePreview`,
  `latestActivityPreview`, `latestActivityMessageId`, `replyCount`,
  `unreadCount`, `taskNumber`, `taskStatus`, `taskClaimedByName`.

### Android/KMP Guessing To Retire Gradually

The Android repository still contains broad migration-era alias parsing. Some
aliases are useful for backwards compatibility, but they should not hide a
broken upstream contract. High-risk examples:

- Home/DM display fallback to root `name`/`channelName`/`title` can recreate the
  old DM bug where current user name is shown for the peer. Peer display should
  use `peerDisplayName ?: peerName` and treat missing peer fields as an API
  defect or explicit placeholder.
- Message author parsing should prefer server canonical `senderType`,
  `senderId`, `senderName`, `senderDescription`, and membership status. The
  current shared thread parser also accepts nested author/member aliases because
  earlier migration payloads were inconsistent; new product code should not add
  more aliases without pointing to upstream source.
- Server members should use `userId` as the canonical id. Generic `id` fallback
  can stay as a defensive parser path but should not be required by livetest.
- Thread pagination must use `before` for older channel page and
  `messages/sync?since_seq=&channel_id=` for newer/gap sync. Any `afterSeq`,
  `cursor`, or `since` alias should be treated as legacy/internal unless
  upstream exposes it on the public route.
- Task list/action consumers should expect `{ tasks }` and `{ task }` wrappers,
  not raw arrays for tasks.

## Livetest Coverage

Before this pass the only Android connected live API contract test was:

- `LiveInboxApiContractTest`

This covered inbox mapping only. It did not close the main request chain for
Home, Thread, Settings/member identity, Tasks, unread/read receipts, message
sync, or attachment URL resolution.

This pass adds:

- `LiveApiContractClosureTest.liveHomeAndMemberContractsMatchServerShapes`
- `LiveApiContractClosureTest.liveThreadContractsMatchServerShapes`
- `LiveApiContractClosureTest.liveTaskAndUnreadContractsMatchServerShapes`

The new tests are read-only and skip when no signed-in app session exists. They
exercise the real app auth/session store and `RealSlockApiFactory`, then assert
canonical server shapes directly:

- channels array and canonical channel `type`
- DM peer fields (`peerId`, `peerName`, `peerType`)
- server members direct array with `userId`, `name`, `role`
- message page wrapper `{ messages, historyLimited }`, positive `seq`,
  canonical sender fields, attachment array
- sync array from `messages/sync?since_seq=&channel_id=`
- optional attachment `{ url, expiresAt }` when live data contains attachments
- tasks `{ tasks }`
- server unread summary `[{ serverId, unreadCount }]`

## Remaining Gaps

These should become follow-up implementation or livetest tasks, not implicit
parser aliases:

1. `messageContext` short-id + `channelId` query is not covered by Android
   livetest. It matters for `#channel:shortid` markdown thread refs.
2. Mutation/action chains are still mostly unit-tested or manually verified:
   send message, upload attachment, save/unsave, mark read/unread, task
   claim/status, channel join/leave/archive/delete. Add guarded live mutation
   tests only when a safe test server/channel convention exists.
3. Settings route coverage is partial. Members are now live-checked; account,
   sessions, billing, machines/workspaces, usage, onboarding, and invite flows
   need either read-only live tests or explicitly disabled capability gates.
4. Realtime/socket contract is not closed by REST livetests. The existing
   realtime unit tests should be complemented by a live server-scope test that
   validates event payloads against `SharedThreadStore` and Home cache updates.
5. Upstream web e2e already tests several critical contracts under
   `packages/web/tests/e2e/tests/thread`, `sidebar`, and `message-list-stability`.
   Android/KMP should reference those cases when adding route-level parity tests
   instead of inventing separate guessed schemas.

## Gate For Future Contract Changes

Any future Android/KMP API contract change should include:

1. Link to the upstream route/service file or web e2e case that proves the
   request and response shape.
2. Removal or demotion of any parser alias that is no longer backed by upstream.
3. A focused unit/parser test for the exact canonical shape.
4. A live connected contract test when the endpoint is read-only or can be
   safely exercised with existing signed-in app state.
5. A release note that distinguishes "contract verified" from "capability
   parity complete".
