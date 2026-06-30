# Thread/Home API Parallel Loading Spec

Date: 2026-06-25
Owner: Android-App-Integrator
Source task: #对话流专修 task #378

## Problem

Thread and Home startup must not wait for full server member/profile enrichment before showing the primary UI. A server can have hundreds of members, so using `GET /servers/:serverId/members` as a prerequisite for message timeline rendering or Home sidebar rendering creates avoidable cold-start latency and makes slow member/profile APIs look like message API failures.

This spec defines the Android/KMP contract for API parallelization and identity enrichment:

- Message APIs provide enough data to render timeline text, author names, attachments, reactions, task badges, and thread counts.
- Avatar/profile/presence/member-candidate data is a secondary projection and must be fetched from independent stores.
- Thread/Home first content must render as soon as its own API returns, then patch avatars/statuses/member lists as separate requests complete.

## React / Server Evidence

React ThreadPanel loads timeline and auxiliary data as independent requests. On thread open it reads cached thread messages if present, then fetches either `GET /messages/channel/:threadChannelId?limit=50` or `GET /messages/context/:focusMessageId`; separately it fetches parent context with `GET /messages/context/:parentMessageId` and linked tasks with `GET /tasks/channel/:parentChannelId`. These requests are not serialized behind server-member hydration.

References:

- `packages/web/src/components/message/ThreadPanel.tsx` lines 445-457: `loadLatestThreadMessages()` fetches the thread page from `/messages/channel/:id`.
- `packages/web/src/components/message/ThreadPanel.tsx` lines 475-483: cached messages are rendered immediately when present.
- `packages/web/src/components/message/ThreadPanel.tsx` lines 534-558: initial thread load chooses `/messages/context/:focusId` or `/messages/channel/:threadChannelId`.
- `packages/web/src/components/message/ThreadPanel.tsx` lines 590-619: parent context and linked tasks are independent requests.

Server message routes also operate on message windows, not full member lists:

- `packages/server/src/routes/messages.ts` lines 261-347: `/messages/context/:messageId` returns `{ channelId, targetMessageId, hasOlder, hasNewer, messages, historyLimited, channelArchived }`.
- `packages/server/src/routes/messages.ts` lines 355-390: `/messages/channel/:channelId` verifies channel access and then loads a page.
- `packages/server/src/services/messageService.ts` lines 1925-1973: `getMessageContext()` builds a bounded context window and calls `enrichWithSenderNames(contextRows)`.
- `packages/server/src/services/messageService.ts` lines 2151-2191: `listMessages()` loads a bounded page and calls `enrichWithSenderNames(rows)`.
- `packages/server/src/services/messageService.ts` lines 1458-1600 and 1603-1795: `enrichWithSenderNames()` batch-loads only the sender/profile/membership/reaction/attachment data needed for the current message rows. It does not fetch a full channel or server member list.

React avatars are a consumer-side store lookup, not part of message API blocking. `MessageItem` reads `senderAgent` from `useAgentStore` and `senderMember` from `useServerStore`, then passes available avatar fields into `MessageSenderAvatar`; missing store rows degrade to name/default avatar. Server message rows include `senderName`, `senderDescription`, and `senderMembershipStatus`, but not `senderAvatarUrl`.

References:

- `packages/web/src/store/messageStore.ts` lines 77-111: message shape includes sender id/name/description/status but no sender avatar field.
- `packages/web/src/components/message/MessageItem.tsx` lines 2259-2275: sender agent/member is resolved from stores during render.
- `packages/web/src/components/message/MessageItem.tsx` lines 2338-2348 and 3334-3345: avatar props come from store lookup or current-user fallback.
- `packages/web/src/store/serverStore.ts` lines 133-153 and 276-287: server switch starts `loadMembers()`, but the call is fire-and-forget relative to view loading.
- `packages/web/src/components/layout/MainLayout.tsx` lines 1152-1164: channels, DMs, agents, followed threads, inbox, saved, announcements, and unread counts are kicked off independently.

## Current Android/KMP Risk

Android already parses the message wire contract correctly:

- `compose/shared/src/commonMain/kotlin/build/raft/app/data/MessageParser.kt` lines 24-44 documents `/api/messages` and strict `WireMessage` fields.
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/ThreadMessagesParser.kt` lines 19-45 mirrors the React/server enriched message shape and explicitly treats member-profile-only fields as dedicated upstream joins.
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/ConversationRepositoryDataSource.kt` lines 43-59 maps KMP repository calls directly to `/messages/channel/:id` and `/messages/context/:id`.

The risk is in KMP-side post-fetch enrichment:

- `SharedThreadStore.kt` lines 614-616, 1456-1458, 1497-1508, 1533-1535, and 1619-1620 call `.withSenderProfiles(session)` on initial/focused/thread snapshots.
- `SharedThreadStore.kt` lines 1710-1739 implements `.withSenderProfiles(session)` by calling `memberRepository.getMembers(DataRequestPriority.Visible, serverId)` and `agentsApiClient.agents()`, then caches all profiles.
- `SharedThreadStore.kt` lines 744-817 implements `enrichThreadMembers()` by calling `getChannelMembers(threadId)` and then `getMembers(serverId)` to build candidates/avatar fallbacks.
- `SharedThreadStore.kt` lines 2265-2341 calls `enrichThreadMembers()` from the members load path and patches the payload after it completes.
- `HomeDataSource.kt` lines 107-145 parses Home, then calls `.withAgentDirectMessageStatuses()` which fetches all server members when any DM is with an agent.

These calls are useful as secondary enrichment, but they must not be required for the first visible Thread/Home content.

## Contract

### Blocking Requests

Only these requests may block initial content:

- Home sidebar: session/current server + channel/DM/home payload required to show the sidebar shell and rows.
- Channel/thread timeline: `GET /messages/channel/:channelId` for latest page, or `GET /messages/context/:messageId` for focused window.
- Reply thread open: `POST /channels/:parentChannelId/threads` may block only when no trusted thread channel id is known.
- Message send/reaction/task mutation: the mutation request itself may block the local mutation result, but not unrelated profile/member refreshes.

### Non-Blocking Requests

These must run independently and patch state when they complete:

- `GET /servers/:serverId/members`
- `GET /agents`
- `GET /channels/:channelId/members`
- agent status/activity refresh
- saved-message status lookup
- tasks linked to a parent message
- followed-thread summaries and inbox/saved lists
- avatar image downloads

Failures from non-blocking requests must not convert a successfully loaded timeline/sidebar into `thread_network` or `home_network` error. They should leave placeholders, stale cache, or local fallback labels in place and expose retry only on the surface that owns that data.

### Message Timeline Identity

For each `WireMessage`, the timeline can render with:

- `senderType`
- `senderId`
- `senderName`
- `senderDescription`
- `senderMembershipStatus`
- local current-user fallback, when the sender is the current user

Avatar fields are optional enhancements. Timeline rows must support these fallback levels:

1. current cached member/agent profile by sender id
2. Home DM avatar cache by peer/member id
3. current authenticated user avatar/gravatar for self-authored human messages
4. deterministic/default avatar from sender name/id

Do not fetch the full server member list solely to render message avatars.

### Home Identity

Home rows must render from the Home/channel/DM payload first. Agent DM status should come from the row payload or an agent-status store when available. If the current API still requires a member-list fallback for agent DM status, it must run as a background patch, not as a continuation of `loadHome()` before content is returned.

### Thread Members Panel

The Thread Members tab/modal owns member-list hydration. Opening the thread message timeline must not run `GET /channels/:threadId/members` or `GET /servers/:serverId/members` unless the member surface is actually visible or explicitly prefetching at background priority.

When the member surface opens:

- Fetch `GET /channels/:threadId/members` for actual channel/thread members.
- Use cached server members for add-candidate list immediately if present.
- Refresh `GET /servers/:serverId/members` in parallel/background for candidates and avatar fallback.
- Patch candidates/avatar fallbacks after server members arrive.
- If server members fail, keep actual channel members and show candidate retry/empty state; do not fail the whole thread.

## Android/KMP Implementation Requirements

1. Split Thread first-content load from sender-profile enrichment.

   `fetchThreadPayloadFromRepository()` and initial/focused thread paths should return content after the message snapshot plus cached/local profile patch. Replace blocking `.withSenderProfiles(session)` with:

   - immediate `.withCachedSenderProfiles(session)`
   - optional background `RefreshSenderProfiles` action that calls members/agents stores and patches avatars/status only if route identity is still current

2. Make sender-profile refresh bounded by visible senders.

   Prefer an eventual batch profile endpoint such as `GET /profiles?userIds=&agentIds=`. Until such an endpoint exists, if KMP uses `GET /servers/:serverId/members`, it must be a background store refresh shared by Home/Members/Thread, not a per-thread blocking call.

3. Move Home agent DM status enrichment out of `loadHome()`'s blocking chain.

   `SharedHomeDataSource.loadHome()` should return parsed Home content without waiting for `memberRepository.getMembers()`. Agent DM status enrichment can update rows from cached members/agents immediately and then refresh in background.

4. Keep Thread Members independent.

   `loadMembers()` may keep its current status machine, but it should only run for the members surface or explicit background prefetch. Its server-member candidate refresh should not block actual thread member rows.

5. Preserve stale content on secondary failures.

   Member/profile/avatar failures should log diagnostics such as `thread_sender_profiles_refresh_failed` or `home_agent_status_refresh_failed`, then leave existing content in place. They must not clear timeline messages or Home rows.

6. Keep route guards.

   Any background patch must check the same typed route identity/current thread key used by the store before applying, so late profile/member results cannot patch the wrong thread after navigation.

## Desired Request Timeline

### Home Cold Start

1. Read session/current server.
2. Start in parallel:
   - Home/channel/DM payload
   - agents snapshot
   - server members snapshot
   - unread counts / followed threads / inbox / saved as their own stores need
3. Render Home as soon as Home/channel/DM payload returns.
4. Patch agent statuses, avatars, member-derived labels, and candidates as their stores return.

### Thread Open, Known Thread Channel Id

1. Render cached thread messages if present.
2. Fetch `/messages/channel/:threadId?limit=50`.
3. In parallel/background:
   - fetch parent context if the header needs it
   - fetch linked task status
   - refresh sender profiles for visible sender ids
   - join socket room
4. Render messages when message API returns.
5. Patch avatars/statuses/task header as secondary data arrives.

### Thread Open, Focused Message

1. Fetch `/messages/context/:focusedMessageId`.
2. Render context window when it returns.
3. Fetch latest page only if focus validation fails or the user asks to jump latest.
4. Run sender profile/member/task enrichment in parallel/background.

## Non-Goals

- This spec does not add a new backend endpoint by itself.
- This spec does not change React behavior.
- This spec does not remove Thread Members or Add Members functionality.
- This spec does not require avatars to be embedded in every message row.

## Acceptance Checks

- A 500-member server can open Home and a thread timeline without waiting for full server-member fetch completion.
- If `/servers/:serverId/members` is artificially delayed or fails, message rows still render with `senderName` and fallback avatars.
- Thread Members tab can still load actual members and add-candidates when opened.
- Agent DM rows keep their existing status behavior when member/agent stores are available, and degrade without blocking Home content when they are not.
- Logs distinguish primary content failures (`thread_network`, `home_network`) from secondary enrichment failures.
