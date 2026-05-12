# Android Members, Agents, DM, Reminders, And Activity Contract

Task: #2 in `#设置项专修` - "Android API docs: Members profiles, agents, reminders, and activity contracts"

Sources inspected on 2026-05-09:

- `/Users/artin/Downloads/Data/Slock_files/index-EpAFmlk5.js`
- `/Users/artin/Downloads/Data/Server_files/index-BaD09UKb.js`
- `debug.har` endpoint inventory; response bodies were not retained in HAR
- Android `SlockApi`, `RealMemberRepository`, `RealChannelRepository`, `MembersScreen`

This document separates confirmed Web endpoints from field aliases inferred by the current Android mapper/tests. Do not add new API calls directly from chat memory; update this doc and the mapper tests when the contract changes.

## Auth And Scope

All requests use the existing authenticated API client.

- Web sends `Authorization: Bearer ...` and `X-Server-Id` from its axios interceptor.
- Android must keep token/header handling inside the shared network client/session store.
- Compose/UI must consume repository snapshots only; UI must not read raw response bodies, Bearer tokens, or `X-Server-Id`.
- Server-scoped requests require a selected server in session; missing session/server maps to `auth_required` / `server_required`.

## Confirmed Endpoint Inventory

### Members

| Method | Path | Source | Android status |
|--------|------|--------|----------------|
| `GET` | `/servers/{serverId}/members` | Web store + HAR | Implemented in `SlockApi.serverMembers`; mapped by `RealMemberRepository.getMembers`. |
| `GET` | `/members/{memberId}/profile` | Web bundle path reference + Android API | Implemented in `SlockApi.memberProfile`; used to enrich human member rows. |
| `GET` | `/channels/{channelId}/members` | Web channel member hook + HAR | Implemented in `SlockApi.channelMembers`; used by thread/channel members panel. |
| `POST` | `/channels/{channelId}/members` | Web channel member hook | Implemented. Body is `{ "agentId": id }` for agents or `{ "userId": id }` for humans. |
| `DELETE` | `/channels/{channelId}/members/{kind}/{memberId}` | Web channel member hook | Implemented. `kind` is `agent` or `user`. |

### Agents

| Method | Path | Source | Android status |
|--------|------|--------|----------------|
| `GET` | `/agents` | Web agent store + HAR | Partially implemented as `settingsAgents()` and merged into Members. No dedicated real `AgentRepository` implementation yet. |
| `POST` | `/agents` | Web agent store | Not in Android scope for this analysis. |
| `PATCH` | `/agents/{agentId}` | Web agent store | Not in Android scope for this analysis. |
| `POST` | `/agents/{agentId}/start` | Web agent store | Not in Android scope for this analysis. |
| `POST` | `/agents/{agentId}/stop` | Web agent store | Not in Android scope for this analysis. |
| `POST` | `/agents/{agentId}/reset` | Web agent store | Not in Android scope for this analysis. Body includes `{ "mode": ... }`. |
| `DELETE` | `/agents/{agentId}` | Web agent store | Not in Android scope for this analysis. |

### Direct Messages

| Method | Path | Source | Android status |
|--------|------|--------|----------------|
| `GET` | `/channels/dm` | Web channel store + HAR | Implemented in `SlockApi.directMessages`; used by Home DM list. |
| `POST` | `/channels/dm` | Web channel store | Implemented in `SlockApi.openDirectMessage`; repository exposes `openAgentDirectMessage(agentId)` / `openUserDirectMessage(userId)`. This is the confirmed "open/create DM" endpoint. |
| `GET` | `/agents/{agentId}/agent-dms` | Existing Android API/index | Present in Android, but not found in current downloaded Web bundle. Treat as backend/API-known but not Web-confirmed from this snapshot. |

`POST /channels/dm` body:

```json
{ "agentId": "agent-uuid" }
```

or

```json
{ "userId": "user-uuid" }
```

Web behavior after success:

- response is a DM channel object
- Web adds/replaces it in `dmChannels`
- Web emits `join:channel` for the returned channel id
- route opens `/s/{serverSlug}/dm/{channelId}`

Android implementation:

- `ChannelRepository` has `getDirectMessages`, `openAgentDirectMessage(agentId)`, and `openUserDirectMessage(userId)`.
- `RealChannelRepository` posts `{agentId}` / `{userId}` to `POST /channels/dm`, maps the returned channel through `ConversationSummary`, and merges it into an in-memory DM cache.
- `MainActivity.openAgentDirectMessage` joins the returned realtime channel, merges the DM row into Home state, and opens the returned thread.
- Members agent detail exposes an "Open direct message" affordance backed by this repository path.

### Reminders

| Method | Path | Source | Android status |
|--------|------|--------|----------------|
| `GET` | `/reminders?ownerAgentId={agentId}&status=scheduled` | Existing Android API/index | Implemented in `SlockApi.reminders`; not found in current downloaded Web bundle. |

Semantics:

- Query by `ownerAgentId`.
- Current Android requests `status=scheduled`.
- Empty list means "no scheduled reminders", not a page failure.
- 403 maps to no-permission for the Reminders panel.

### Agent Activity

| Method | Path | Source | Android status |
|--------|------|--------|----------------|
| `GET` | `/agents/{agentId}/activity-log?limit={n}` | Web agent store | Implemented in `SlockApi.agentActivityLog`; `RealMemberRepository.getAgentActivity()` calls it with `limit=50`. |
| `GET` | `/trajectoryLogs/{agentId}` | Existing Android API/index | Present in Android, but not found in current downloaded Web bundle. Should be treated as stale/unconfirmed until backend confirms it. |

Web behavior:

- initial agent list carries `activity` and `activityDetail` fields
- realtime `agent:activity` updates current activity and appends transient activity log entries
- `GET /agents/{agentId}/activity-log?limit=50` loads persisted trajectory/activity entries
- Web deduplicates persisted entries by `timestamp + JSON.stringify(entry)`, sorts by timestamp, and keeps the last 500 entries

Android implementation:

- `SlockApi.agentActivityLog(agentId, limit=50)` replaces the stale Android `trajectoryLogs/{agentId}` path for persisted activity.
- `RealMemberRepository.getAgentActivity()` still accepts legacy/wrapper aliases (`activityLog`, `activity`, `trajectoryLogs`, `logs`, `items`, `entries`, `data`) and merges entries into an in-memory per-agent cache.

## Response Shape And Field Aliases

### Member row / profile

Confirmed/inferred wrappers:

- `members`
- `humans`
- `users`
- `agents`
- `items`
- nested `data`
- nested profile object under `profile`, `user`, `account`, `member`, or `agent`

Android accepted fields:

| Snapshot field | Accepted aliases |
|----------------|------------------|
| `id` | `id`, `userId`, `memberId`, agent id fields when explicitly agent |
| `displayName` | `displayName`, `name`, `username`, `email` fallback |
| `handle` | `handle`, `username`, `email`, fallback display name |
| `kind` | explicit `kind` / `type` / `memberType`, `userId`, nested `user`/`member`, `agentId`, nested `agent`, `isAgent` |
| `roleDescription` | `description`, `roleDescription`, `role` |
| `avatar` | `avatarUrl`, `avatar`, `avatar_url`, `imageUrl`, `profileImageUrl`, `profileAvatar` |
| `gravatarHash` | `gravatarHash`, `gravatar_hash`, `gravatar`, `gravatarMd5`, `gravatar_md5` |
| `presence` | `presence`, `status`, `activity`, `availability` |

Semantics:

- Channel member response from the current Web bundle reads split arrays as `agents` and `humans` from `GET /channels/{channelId}/members`; Android also keeps the older `users` alias for compatibility.
- Human profile enrichment uses `GET /members/{memberId}/profile`.
- Agent rows should not be fetched through member profile.
- When a backend row contains both same id human and agent records, Android must keep them distinct by `(kind, id)`.
- Human avatars should not render presence/offline indicators in UI, but agent DM/home presence should remain visible when the peer is an agent.

### Agent list row

Web `GET /agents` consumes at least:

| Field | Semantics |
|-------|-----------|
| `id` | Agent id. |
| `name` / `displayName` | Agent display name. |
| `description` / `roleDescription` | Agent role/subtitle. |
| `avatarUrl` / `avatar` | Avatar wire value. |
| `status` | Runtime status; Web maps `active` to working when no explicit activity exists. |
| `activity` | `online`, `thinking`, `working`, `error`, `offline`. |
| `activityDetail` | Short activity text. |
| `machineId` | Runtime/machine association; used by machine status recovery. |
| `deletedAt` | Soft-deleted agents are hidden/marked inactive in Web. |
| `runtime`, `model`, `reasoningEffort` | Agent runtime configuration; used by create/edit surfaces. |

Web activity normalization:

- accepted explicit activity values: `online`, `thinking`, `working`, `error`, `offline`
- if explicit activity is missing and `status == "active"`, display activity is `working`
- otherwise display activity is `offline`

### DM channel row

`GET /channels/dm` returns channel rows. Web treats each row as a normal channel plus `type = "dm"`.

Android accepted aliases for Home DM mapping include:

| Snapshot field | Accepted aliases |
|----------------|------------------|
| id/channel id | `id`, `channelId` |
| name | `name`, `channelName`, `peerDisplayName`, `peerName`, `agentName`, `agentDisplayName`, `agentUsername`, `userDisplayName`, `username`, `title`, nested peer display fields |
| peer presence | `presence`, `status`, `activity`, `availability`, `onlineStatus`, `connectionStatus`, `state`, `peerPresence`, `peerStatus`, `agentPresence`, `agentStatus`, `userPresence`, `userStatus`, nested peer aliases |
| peer id | `presenceMemberId`, `peerId`, `agentId`, `userId`, `memberId`, nested peer id |
| last message | `lastMessagePreview`, `preview`, `body`, `content`, `text`, `lastMessageAt`, `updatedAt`, `latestActivityAt`, `createdAt` |

Semantics:

- DM rows are joined/read through the same channel message APIs after the DM channel id is known.
- Realtime `dm:new` carries `channelId`; Web joins that channel and refreshes `/channels/dm`.
- Android should preserve Home DM agent presence for agent peers and continue hiding human presence indicators.

### Agent DM summary

Existing Android `GET /agents/{agentId}/agent-dms` mapper accepts:

| Snapshot field | Accepted aliases |
|----------------|------------------|
| `id` | `id`, `dmId`, `channelId`, `threadId` |
| `peerId` | `peerId`, `peerUserId`, `memberId`, `userId`, nested peer id |
| `peerName` | `peerName`, `peerDisplayName`, `displayName`, `name`, nested peer display fields |
| `peerAvatarUrl` | `peerAvatarUrl`, `peerAvatar`, `avatarUrl`, `avatar`, nested avatar aliases |
| `lastMessageAt` | `lastMessageAt`, `createdAt`, `updatedAt` |
| `lastMessagePreview` | `lastMessagePreview`, `preview`, `body`, `content`, `text` |

Status:

- This endpoint exists in Android API, but the current Web bundle did not call it.
- If Android keeps this panel, treat it as supplemental agent-detail data, not the primary open/create DM path.
- Primary open/create DM should use `POST /channels/dm`.

### Reminder row

Existing Android mapper accepts:

| Snapshot field | Accepted aliases |
|----------------|------------------|
| `id` | `reminderId`, `id`, `_id` |
| `title` | `title`, `body`, `text`, `content` |
| `fireAt` | `fireAt`, `fire_at`, `scheduledAt`, `scheduled_at` |
| `recurrenceDescription` | `recurrenceDescription`, `recurrence_description`, nested `recurrence.description`, nested `recurrence.label` |
| `messageRef` | `msgRef`, `messageRef`, `messageId` |
| `messagePermalink` | `msgPermalink`, `permalink`, `messagePermalink` |
| `ownerAgentId` | `ownerAgentId`, `agentId` |
| `status` | `status` |

Null semantics:

- missing `fireAt` -> render "No scheduled time" or hide timestamp detail
- missing recurrence -> non-recurring/single reminder
- empty array -> empty reminder panel

### Activity row

Existing Android mapper accepts wrappers:

- `activityLog`
- `activity`
- `trajectoryLogs`
- `logs`
- `items`
- `entries`
- `data`

Web persisted activity-log rows may be direct entry objects or trajectory wrapper objects:

```json
{ "timestamp": 1715216400000, "entry": { "type": "thinking", "message": "..." } }
```

Android unwraps `entry` / `activity` / `event` / `data` for the activity fields while keeping the wrapper timestamp as the row timestamp.

Accepted fields:

| Snapshot field | Accepted aliases |
|----------------|------------------|
| `type` | `type`, `eventType`, `kind`, `activity`, fallback `status` |
| `id` | `id`, `entryId`, `eventId`, fallback `{type}:{timestamp}` |
| `title` | `title`, `label`, fallback by known type |
| `body` | `body`, `text`, `content`, `message`, `detail`, `activityDetail`, `status`, `activity` |
| `timestamp` | `timestamp`, `createdAt`, `created_at`, `time` |

When `GET /agents/{agentId}/activity-log?limit=50` is empty, Android falls back to the current `/agents` row for that agent and renders `activity` / `activityDetail` as a current-status activity entry. This mirrors Web's agent list store where live activity can be visible before persisted trajectory rows exist.

Known activity types currently rendered by Android:

- `thinking`
- `tool_start`
- `text`
- `system`
- `status`
- `working`
- `online`
- `active`
- `offline`
- `idle`
- `error`
- `compaction_started`
- `compaction_finished`

Unknown activity types are rendered with a generated title rather than dropped, so backend-added activity kinds remain visible.

## Realtime Events

Web listens for:

| Event | Use |
|-------|-----|
| `agent:activity` | Update agent activity/status, append transient activity/trajectory entries. Payload includes `agentId`, `activity`, `detail`, optional `serverSeq`, optional `entries`, optional `timestamp`. |
| `dm:new` | New DM channel available. Payload includes `channelId`; client joins that channel and refreshes DM list. |
| `agent:created` | Reload `/agents`. |
| `agent:deleted` | Reload `/agents`. |
| `server:member-added` | Reload members if server id matches current server. |
| `server:member:left` | Reload members if server id matches current server. |
| `server:member-removed` | Reload members if server id matches current server. |
| `server:member-updated` | Reload members if server id matches current server. |
| `channel:members-updated` | Reload channel members and channels. |

Android realtime boundary maps `dm:new` without nested message payloads to `RealtimeEvent.DirectMessageCreated(channelId)`. `MainActivity` joins the channel and triggers a background Home/DM refresh; `dm:new` with a nested message still maps through the normal `MessageNew` path.

## Android Implementation Gaps

Implemented in task #2 follow-up:

1. Added `SlockApi.openDirectMessage(@Body)` for `POST /channels/dm`.
2. Added `ChannelRepository.openAgentDirectMessage(agentId)` and `openUserDirectMessage(userId)`.
3. Mapped the returned DM channel through `ConversationSummary`, merged it into Home DM state, joined the realtime channel, and opened the returned DM thread from agent detail.
4. Replaced the persisted activity fetch with `GET /agents/{agentId}/activity-log?limit=50`.
5. Added in-memory cache/merge for Home DM rows and agent activity entries.
6. Added regression tests for:
   - `POST /channels/dm` request body with `{agentId}`
   - `POST /channels/dm` request body with `{userId}`
   - DM cache merge preserving open-created and fetched rows without duplicates
   - activity-log endpoint calls with `limit=50` and cached activity merge/dedupe
   - Web trajectory wrapper rows `{timestamp, entry}` mapping into visible activity entries
   - empty activity-log fallback to `/agents` current `activity/activityDetail`
   - pre-existing Home DM agent presence retained while human DM presence stays hidden
   - pre-existing members merge preserving same-id human and agent rows as separate entries

Remaining follow-up:

- Either implement a real `AgentRepository.getAgents()` or rename/reuse `settingsAgents()` so agents are not conceptually tied to Settings.

## Current Android Landing Points

- API interface: `app/src/main/java/ai/slock/android/core/data/network/SlockApi.kt`
- Members and agent detail data: `RealMemberRepository`
- Home/DM list data: `RealChannelRepository`
- Members detail UI: `MainActivity.openMemberProfile`, `MembersScreen`
- Snapshot models: `RepositorySnapshots.kt`
- Regression tests: `RealSlockRepositoriesTest`

## Safe Diagnostics

Allowed log fields:

- source name (`server_members`, `member_profile`, `channels_dm`, `agent_activity`, etc.)
- endpoint category
- HTTP status
- retryable flag
- server id / channel id / agent id / member id
- item counts
- root keys / field path for shape mismatch

Do not log:

- Authorization header
- access token / refresh token
- raw response body
- message body or private reminder text in diagnostics
- email address unless already intentionally displayed in UI state
