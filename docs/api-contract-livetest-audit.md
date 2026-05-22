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
| Home channels | `GET channels?archived=exclude/include/only` | `channelRouter.get("/")` | Typed first slice | Shared Home now passes list calls through `SlockHomeApiClient`; upstream returns a root array and validates `archived`. |
| Home DMs | `GET channels/dm` / `POST channels/dm` | `channelRouter.get/post("/dm")` | Typed first slice | Shared Home now rejects guessed wrapper objects before the parser. Canonical display fields are `peerName`, `peerDisplayName`, `peerAvatarUrl`, `peerType`; root `name` is not the peer display source. |
| Unread counts | `GET channels/unread`, `POST channels/{id}/read`, `read-all`, `unread` | `channelRouter.get("/unread")`, `post("/:id/read")`, etc. | Typed first slice | Shared Home requires the root unread-count response to be an object. `read` requires body `{ seq }`; `read-all` returns `{ ok, seq }`; `unread` returns `{ ok, unreadCount }`. |
| Server unread summary | `GET servers/unread-summary` | `serverRouter.get("/unread-summary")` | Typed first slice | Shared Home requires a root array of `{ serverId, unreadCount }`. |
| Messages page | `GET messages/channel/{channelId}?limit=&before=` | `messageRouter.get("/channel/:channelId")` | Match | Upstream also supports `after`; KMP currently uses `before` for older page and `sync` for newer page. |
| Message sync | `GET messages/sync?since_seq=&limit=&channel_id=` | `messageRouter.get("/sync")` | Match | Query names are authoritative: `since_seq`, `channel_id`, `limit`. |
| Message context | `GET messages/context/{messageId}` | `messageRouter.get("/context/:messageId")` | Match | Optional `channelId` query supports short-id context resolution; Android/KMP does not yet expose that optional query everywhere. |
| Send message | `POST messages` body `{ channelId, content, attachmentIds?, asTask? }` | `messageRouter.post("/")` | Match | Server rejects blank content and archived/read-only channels. |
| Attachments | `POST attachments/upload`, `GET attachments/{id}/url` | `attachmentRouter.post("/upload")`, public `get("/:id/url")` | Match | Multipart field name is `channelId`; response wrapper is `{ attachments: [...] }`; open target is `{ url, expiresAt }`. |
| Tasks | `GET tasks/server`, `GET tasks/channel/{channelId}`, `GET tasks/channel/{channelId}/number/{taskNumber}`, `POST tasks/channel/{channelId}`, `POST tasks/convert-message`, `PATCH tasks/{taskId}/claim/unclaim/status`, `DELETE tasks/{taskId}` | `taskRouter` | Typed first slice | Shared Tasks now uses `SlockTasksApiClient` for list/create/convert/claim/unclaim/status/delete/by-number. List/create responses must be `{ tasks }`, action responses must be `{ task }`, delete must be `{ ok: true }`. |
| Server members | `GET servers/{serverId}/members` | `serverRouter.get("/:id/members")` | Match | Upstream returns a direct array. Canonical member id field is `userId`, not generic `id`. |
| Human member profile | `GET servers/{serverId}/members/{memberId}/profile` | `serverRouter.get("/:id/members/:memberId/profile")` | Fixed in this pass | Android/KMP had guessed `members/{memberId}/profile`; no matching upstream public route exists. |
| Saved messages | `GET channels/saved`, `POST channels/saved`, `DELETE channels/saved/{messageId}` | `channelRouter.get/post("/saved")`, `delete("/saved/:messageId")` | Fixed in this pass | Android/KMP had guessed `messages/{id}/save`; no matching upstream public route exists. |
| Server settings | `PATCH servers/{serverId}`, usage, invites, onboarding | `serverRouter` | Typed first slice | Android Settings now uses a typed `SlockSettingsServerApi` boundary for profile patch, delete, usage, invite create, billing subscription, onboarding, machines, workspaces, and server admins. |
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
| Server invites/join links | `POST/GET/DELETE servers/:id/invites`, `POST/GET/DELETE join-links`, `join-community` | Partially typed | Missing | Typed Settings facade wires create invite only. Invite list/delete and join-link create/list/delete are real upstream routes but still disabled/not exposed in Android/KMP Settings. |
| Machines/runtime | `GET/POST/PATCH/DELETE machines`, rotate-key, workspaces, runtime-models | Partially typed | Missing | Typed Settings facade wires list machines and direct-array workspaces. Missing create/update/delete/rotate-key/runtime models. |
| Billing | `GET billing/subscription`, `POST checkout`, `POST portal` | Partially typed | Missing | Typed Settings facade wires subscription only. Checkout/portal are real upstream routes but not wired, so Billing actions cannot be called from Android/KMP yet. |
| Channels core | `GET/POST/PATCH channels`, detail, join/leave, archive/unarchive/delete, files | Partially typed | Partial | Shared Home list/DM/unread-summary reads are typed. Legacy channel create/join/leave/archive/delete/files still need typed API facades. Missing PATCH, detail, unarchive. |
| Channel members/agents | `GET/POST/DELETE channels/:id/members`, `GET/POST/DELETE agents`, stop-all/resume-all | Partially wired | Partial | Wired: members list/add/remove. Missing channel agents list/add/remove and stop/resume-all agents. |
| Channel inbox/followed/saved | inbox, inbox done/read-all, followed threads, saved list/save/delete/check | Partially wired | Partial | Wired: inbox list/read-all, followed/follow/unfollow/done/undone, saved list/save/delete. Missing inbox item done and saved check. Saved write paths fixed in this pass. |
| Threads | `POST channels/:id/threads`, `GET channels/:id/threads`, `GET channels/:id/threads/:messageId` | Partially wired | Partial | Wired create/open/list. Missing thread detail endpoint, and task #197/#196 cover pagination/realtime but not all action parity. |
| Messages | search, context, channel page, post, reactions, sync | Partially typed | Partial | Shared Thread now uses `SlockThreadApi` typed methods for channel page, sync, send, read-all, save, task status, and reply-thread create/open. Search/context still use older clients; missing reaction add/remove and `messageContext` `channelId` query exposure. |
| Attachments | upload, url, preview, raw, html-preview-url, html-preview | Partially typed | Partial | Shared Thread attachment open now requires canonical `{ url, expiresAt }` through `SlockThreadApi.attachmentUrl`; upload is still platform/bridge capability; missing preview/raw/html preview endpoints. |
| Tasks | list server/channel, task by number, create, claim, convert-message, unclaim, status, delete | Typed first slice | Partial | Shared Tasks facade covers all public task routes listed here. Product UI currently consumes server list and status update; create/convert/claim/unclaim/delete/by-number are exposed at the typed API boundary for follow-up UI wiring. |
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
- Thread first slice is now typed at the shared boundary:
  `SlockThreadApi.channelMessages/messageSync/sendMessage/attachmentUrl/markChannelReadAll/saveMessage/updateTaskStatus/createOrOpenThread`
  returns `ThreadMessagesPage`, `ThreadAttachmentOpenTarget`,
  `ThreadActionResult`, or `Unit`. Raw `HomeNetworkResponse` is confined to the
  lowest `SharedThreadNetworkApi` transport implemented by `BridgeThreadApi`.
- The typed Thread parser accepts canonical upstream shapes only for the covered
  Thread routes: `messages/channel/:channelId` must be a root object with
  `messages`, `messages/sync` must be a root array, attachment URL must use
  root `url`, and `channels/:id/threads` must return root `threadChannelId`.
  Guessed nested wrappers such as `data.messages`, `data.thread.threadChannelId`,
  and attachment aliases such as `downloadUrl` / `href` now fail with explicit
  contract mismatch codes at the API boundary.
- Settings/Billing/Server first slice is now typed at the Android repository
  boundary: `SlockSettingsServerApi.computers/machineWorkspaces/serverAdmins/serverUsage/billingSubscription/serverOnboarding/updateServerProfile/createServerInvite/deleteServer`
  returns typed Settings snapshots/action results before `RealSettingsRepository`
  sees them. The raw `SlockApi` response remains at the lowest network boundary.
- The typed Settings parser accepts canonical upstream shapes only for the
  covered Settings routes: machines must be a root object with `machines`;
  machine workspaces must be a root array; server members must be a root array
  with canonical admin id `userId`; server usage must be root
  `{ agents, machines, channels }`; billing subscription must be root
  `{ subscription, stripeConfigured }`; onboarding must use root
  `setupModalReminderOptOut` or the upstream server-provided compatibility alias
  `onboardingReminderOptOut`; delete must return `{ ok: true }`.
  Migration-era wrappers such as `data.nodes`, `members`, `usage.rows`,
  `billing.plan`, and generic `ok/success/sent` invite responses now fail at the
  typed API boundary for these paths.
- Home first slice is now typed at the shared product boundary:
  `SlockHomeApiClient.homePayload` wraps `SlockHomeApi.channels/directMessages/channelUnreadCounts/serverUnreadSummary`
  before `SharedHomeDataSource` calls the Home parser. The upstream shapes are
  enforced before parser aliases run: `GET channels` and `GET channels/dm` must
  be root arrays, `GET channels/unread` must be a root object, and
  `GET servers/unread-summary` must be a root array. Guessed wrappers such as
  `{ channels: [...] }`, `{ directMessages: [...] }`, unread arrays, and
  `{ unreadCount }` server-summary objects now fail with explicit contract
  mismatch errors on the shared Home product path.
- Tasks first slice is now typed at the shared boundary:
  `SlockTasksApiClient.serverTasks/channelTasks/taskByNumber/createTasks/claimTask/convertMessage/unclaimTask/updateTaskStatus/deleteTask`
  wraps the raw `SlockTasksApi` transport. Canonical list/create responses must
  be root `{ tasks: [...] }`, action responses must be root `{ task: ... }`,
  and delete must be root `{ ok: true }`. Guessed wrappers such as
  `{ data: [...] }`, `{ data: { task... } }`, `{ result: ... }`, raw task
  objects, and `{ success: true }` delete acknowledgements now fail with
  explicit contract mismatch errors at the shared Tasks API boundary.
- Files/attachments/search/reactions, Push, Integrations, Actions,
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

- Home parser direct unit tests still cover historical wrapper shapes, but the
  shared Home data source no longer accepts those shapes from network responses.
  Keep any future aliases out of product paths unless backed by upstream source.
- Home/DM display fallback to root `name`/`channelName`/`title` can recreate the
  old DM bug where current user name is shown for the peer. Peer display should
  use `peerDisplayName ?: peerName` and treat missing peer fields as an API
  defect or explicit placeholder.
- Message author parsing should prefer server canonical `senderType`,
  `senderId`, `senderName`, `senderDescription`, and membership status. The
  current shared thread parser also accepts nested author/member aliases because
  earlier migration payloads were inconsistent; new product code should not add
  more aliases without pointing to upstream source.
- Server members should use `userId` as the canonical id. For the typed
  Settings admin path, generic `id` fallback has been removed; older broad
  member parsers remain only in legacy/non-Settings paths until their typed
  facade slices are migrated.
- Settings active-session management must not use the old guessed
  `auth/sessions` route. Upstream auth refresh/session rotation is
  `POST auth/refresh`; listing/revoking other sessions has no current public
  route in `auth.ts`.
- Thread pagination must use `before` for older channel page and
  `messages/sync?since_seq=&channel_id=` for newer/gap sync. Any `afterSeq`,
  `cursor`, or `since` alias should be treated as legacy/internal unless
  upstream exposes it on the public route.
- Shared Thread save-message now uses upstream `POST channels/saved` with body
  `{ messageId }`. The guessed `messages/{id}/save` route has no matching
  upstream route and must not be reintroduced.
- Settings machines/workspaces/admins/usage/billing/onboarding consumers should
  not accept guessed `data.nodes`, `workspaces` wrappers for workspace arrays,
  `billing.plan`, or nested `settings/onboarding` wrappers. The first Settings
  typed slice now rejects those shapes with explicit contract mismatch errors.
- Task list/action consumers should expect `{ tasks }` and `{ task }` wrappers,
  not raw arrays, `data` wrappers, or `result` wrappers. Shared Tasks now
  enforces this; Android legacy `RealTaskRepository` remains a compatibility
  adapter until that route is removed or migrated behind the same facade.

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
3. Settings route coverage is partial. Members are live-checked, and the
   Android Settings repository now has typed unit coverage for canonical
   machines/workspaces/server-admins/usage/billing/onboarding/invite/delete
   shapes. Account/profile, billing checkout/portal, invite list/delete,
   join-link management, machine mutations/rotate-key/runtime models, and
   remaining Settings mutations still need live tests or explicit disabled
   capability gates.
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
