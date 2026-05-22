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
- `packages/server/src/routes/auth.ts`
- `packages/server/src/routes/agents.ts`
- `packages/server/src/routes/announcements.ts`
- `packages/server/src/routes/actions.ts`
- `packages/server/src/routes/billing.ts`
- `packages/server/src/routes/integrations.ts`
- `packages/server/src/routes/push.ts`
- `packages/server/src/routes/reminders.ts`
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
| Human member profile | `GET servers/{serverId}/members/{memberId}/profile` | `serverRouter.get("/:id/members/:memberId/profile")` | Fixed in this pass | Android/KMP had guessed `members/{memberId}/profile`; no matching upstream public route exists. |
| Saved messages | `GET channels/saved`, `POST channels/saved`, `DELETE channels/saved/{messageId}` | `channelRouter.get/post("/saved")`, `delete("/saved/:messageId")` | Fixed in this pass | Android/KMP had guessed `messages/{id}/save`; no matching upstream public route exists. |
| Server settings | `PATCH servers/{serverId}`, usage, invites, onboarding | `serverRouter` | Mostly match | Some Settings UI is still pending by capability task, but route paths are confirmed. |
| Machines/workspaces | `GET servers/{serverId}/machines`, `.../workspaces` | `serverRouter` | Match | Machine detail/log history is still not confirmed as a product capability. |
| Auth refresh | `POST auth/refresh` | `authRouter.post("/refresh")` | Match | Upstream rotates the refresh token and returns `{ accessToken, refreshToken }`; Android/KMP restore/token refresh should keep using this route. |
| Settings active sessions | Removed: old guessed `GET auth/sessions`, `DELETE auth/sessions/{sessionId}` | No public route in current `auth.ts` | Removed from client contract | These were migration-era Settings/session-management guesses, not the token refresh chain. Client request methods and mock Settings surface were deleted until upstream/web exposes a real public sessions management route. |

## Full Public API Coverage Matrix

This table answers the broader "all APIs closed loop" question. `Wired` means
the Android/KMP client has a request method using the current upstream route
shape. It does not automatically mean UI parity or livetest coverage is done.

Typed API closure means the client API module parses the upstream response into
explicit DTO/domain types before repositories or UI stores see it. Raw transport
responses are still acceptable only at the lowest network/bridge boundary.

| Upstream area | Representative public routes | Android/KMP wiring | Livetest status | Follow-up |
| --- | --- | --- | --- | --- |
| Auth core | `POST auth/login`, `register`, `forgot-password`, `refresh`, `logout`, `GET/PATCH auth/me` | Partially typed | Partial | Login, current user, refresh, logout, server list, and create-server now pass through `SlockAuthApi` typed methods before shared repositories/stores consume them. Register, forgot/reset password, patch me, and avatar/profile mutations still need typed API methods and live closure. `POST auth/refresh` is real upstream/web behavior, not a guessed chain. Add guarded live `auth/me` read/update once a reversible profile fixture exists. |
| Auth providers/invites/email | `auth/providers`, `identities`, provider start/link/callback/complete, `verify-email`, `resend-verification`, `reset-password`, `accept-invite`, `invite-info`, `me/avatar` | Not wired in Android/KMP product API | Missing | Required before social login, invite acceptance, email verification, reset-password completion, and profile avatar upload can be called native/shared. |
| Settings active sessions | No upstream public route for listing/revoking active sessions | Not wired | Not applicable | Deleted guessed `auth/sessions` client route and Settings mock/session UI copy. Do not re-add without a source-backed server/web contract. |
| Servers list/create | `GET servers`, `POST servers` | Wired through Android auth API, not `SlockApiClient` | Indirect | Shared app shell restore uses selected server; add a live contract test for list/create only if using a safe disposable server fixture. |
| Server profile/settings | `GET/PATCH/DELETE servers/:id`, `leave`, agreement, onboarding, translation, notification, usage, sidebar-order, scope-attestation | Partially wired | Partial | Wired: profile patch, delete, usage, onboarding. Missing: GET detail, leave, agreement, translation, notification, sidebar order, scope attestation. |
| Server members/admin | `GET/POST servers/:id/members`, `PATCH/DELETE members/:memberId`, `GET member-graph`, `GET members/:memberId/profile` | Partially wired | Partial | Wired: list, profile. Missing: add/update/remove server members and member graph. Profile path fixed in this pass. |
| Server invites/join links | `POST/GET/DELETE servers/:id/invites`, `POST/GET/DELETE join-links`, `join-community` | Partially wired | Missing | Wired only create invite and join-community through auth API. Missing invite list/delete and join-link flows. |
| Machines/runtime | `GET/POST/PATCH/DELETE machines`, rotate-key, workspaces, runtime-models | Partially wired | Missing | Wired: list machines and workspaces. Missing create/update/delete/rotate-key/runtime models. |
| Billing | `GET billing/subscription`, `POST checkout`, `POST portal` | Partially wired | Missing | Wired only subscription. Checkout/portal are not wired, so Billing actions cannot be called from Android/KMP yet. |
| Channels core | `GET/POST/PATCH channels`, detail, join/leave, archive/unarchive/delete, files | Partially wired | Partial | Wired: list/create/join/leave/archive/delete/files. Missing PATCH, detail, unarchive. |
| Channel members/agents | `GET/POST/DELETE channels/:id/members`, `GET/POST/DELETE agents`, stop-all/resume-all | Partially wired | Partial | Wired: members list/add/remove. Missing channel agents list/add/remove and stop/resume-all agents. |
| Channel inbox/followed/saved | inbox, inbox done/read-all, followed threads, saved list/save/delete/check | Partially wired | Partial | Wired: inbox list/read-all, followed/follow/unfollow/done/undone, saved list/save/delete. Missing inbox item done and saved check. Saved write paths fixed in this pass. |
| Threads | `POST channels/:id/threads`, `GET channels/:id/threads`, `GET channels/:id/threads/:messageId` | Partially wired | Partial | Wired create/open/list. Missing thread detail endpoint, and task #197/#196 cover pagination/realtime but not all action parity. |
| Messages | search, context, channel page, post, reactions, sync | Partially wired | Partial | Wired search/context/page/post/sync. Missing reaction add/remove and `messageContext` `channelId` query exposure. |
| Attachments | upload, url, preview, raw, html-preview-url, html-preview | Partially wired | Partial | Wired upload and URL. Missing preview/raw/html preview endpoints; attachment opening therefore is not fully native parity. |
| Tasks | list server/channel, task by number, create, claim, convert-message, unclaim, status, delete | Partially wired | Partial | Wired list/claim/status. Missing by-number, create, convert-message, unclaim, delete. |
| Agents | list/create/detail/activity/agent-dms/update/avatar/start/stop/reset/delete/assign-machine/workspace-files/skills/scopes/bootstrap-tokens | Partially wired | Partial | Wired list/update/activity/agent-dms/workspace files/read. Missing create/detail/avatar/start/stop/reset/delete/assign-machine/skills/scopes/bootstrap tokens unless handled by internal agent tooling outside Android product. |
| Announcements | `GET announcements/active`, `POST announcements/:id/dismiss` | Not wired | Missing | Product announcement surfaces cannot be considered closed. |
| Actions | action card execute/event/mark-executed | Not wired | Missing | Android/KMP cannot execute action-card flows through the product API yet. |
| Integrations/OAuth clients | overview, clients, approve/deny/revoke, agent grants | Not wired | Missing | No Android/KMP capability yet. |
| Push | vapid key, prompt events, subscribe/unsubscribe | Not wired | Missing | Native notification work is not closed against upstream push routes. |
| Reminders | `GET reminders` | Read-only wired | Missing | Only list is wired. Agent reminder mutations live under internal agent routes, not public Android/KMP product API. |
| Internal agent/computer/OAuth protocol | `/internal/*`, `/oauth/*`, `/daemon/*` | Out of Android product scope | Not applicable | Should be tracked by agent/daemon CLI contracts, not Android app parity, unless the app starts owning those screens. |

## Response Field Cleanup Findings

### Typed API Layer Status

- Auth first slice is now typed at the shared boundary:
  `SlockAuthApi.login/currentUser/refresh/logout/servers/createServer`
  returns `AuthLoginResponse`, `AuthRefreshResponse`, `StoredAuthUser`,
  `StoredAuthServer`, or `Unit`. The raw `AuthNetworkResponse` transport is
  confined to `SlockAuthTransport` and platform bridge implementations.
- The typed Auth parser accepts canonical upstream shapes only for token fields:
  login requires root `user`, `accessToken`, `refreshToken`; refresh requires
  `accessToken`, `refreshToken`; server list requires a root JSON array.
  Legacy aliases such as `token` / `refresh_token` now fail with an explicit
  contract mismatch instead of being silently accepted.
- Home, Thread, Tasks, Settings, Billing, Machines, Push, Integrations, Actions,
  Announcements, and Reminders still need the same typed API facade treatment.
  They should not keep expanding raw JSON aliases inside repositories or UI
  stores without a source-backed contract.

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
- Settings active-session management must not use the old guessed
  `auth/sessions` route. Upstream auth refresh/session rotation is
  `POST auth/refresh`; listing/revoking other sessions has no current public
  route in `auth.ts`.
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
