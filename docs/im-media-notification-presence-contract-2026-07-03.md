# IM Media, Notification, And Presence Contract

Date: 2026-07-03
Owner: Android-Developer-3
Status: proposal

## Problem

The shortcomings analysis names attachments, notifications, and presence as
separate weak spots. In an IM product they are connected:

- media is message content with lifecycle state
- notifications are message lifecycle presentation decisions
- presence is context around who is talking, not a global invalidation trigger

This document defines the shared projection boundary for those three areas.

## Message Media Group

Current attachment UI is mostly file-entry driven. IM needs a stable media group
projection derived from a message:

```text
MessageMediaGroup(
  serverId,
  streamChannelId,
  messageId,
  groupId,
  items,
  presentation,
  cacheState,
  permissions
)
```

### Item State

Each item should expose:

```text
kind = image | video | file | text | unknown
previewState = unavailable | loading | ready | failed | expired
downloadState = not_downloaded | downloading | downloaded | failed
cacheState = memory | disk | remote | expired | unknown
permissionState = allowed | blocked | revoked | unknown
```

Rules:

- group identity is stable for the message; preview navigation should not depend
  on a temporary single attachment payload
- preview URLs are candidate snapshots and may expire; canonical reload comes
  from message/attachment repository state
- image cache success does not prove attachment permission is still valid
- files in deleted/redacted messages must not remain navigable from stale cache
  unless product explicitly allows local-only retention

### Media Actions

Actions should be typed:

- preview
- download/save
- copy link
- share/export
- open source message
- retry load

Do not infer action availability only from file extension. Use item kind,
permission state, and cache state.

## Notification Presentation Intent

Native push transport is covered by push docs. Shared/KMP still needs a product
intent that says whether and how to present:

```text
NotificationPresentationIntent(
  serverId,
  streamChannelId,
  threadId?,
  messageId?,
  category,
  title,
  body,
  routeIdentity,
  replacementKey,
  badgeDeltaOrTotal,
  suppressReason?
)
```

Categories:

- direct_message
- channel_message
- followed_thread_reply
- mention
- task_mention
- system
- recovery_summary

Suppress reasons:

- authored_by_current_user
- active_visible_stream
- muted_server
- muted_channel
- notification_disabled
- stale_or_duplicate
- permission_unknown
- session_expired

Rules:

- KMP/native foreground banners and killed-app server push should share
  category and route identity semantics.
- Native transport details such as FCM/APNs/OHOS provider ids do not belong in
  shared presentation state.
- In-app foreground banners may suppress visible-stream messages; server push
  eligibility still comes from durable server target resolution.
- Notification click route payload must carry identity and intent only.

## App Badge And Unread

App badge is a projection of unread state, not a notification count accumulator.

Current `AppBadgeCount` already records:

- count
- source
- selected server id
- last updated
- stale flag

This should remain the host boundary. Host platforms must not recalculate badge
meaning from notification delivery callbacks.

Recommended addition for diagnostics:

```text
BadgeFreshness = fresh | stale_held | unknown | disabled
```

## Presence And Activity Budget

Presence/activity should be scoped by user value:

```text
VisibleHomeRows
VisibleThreadAuthors
VisibleDetailSurface
ForegroundReconcile
ExplicitMemberList
```

Rules:

- Home should resolve only visible/list-needed presence and agent status
  projections.
- Thread should resolve current visible authors/participants, not every server
  member.
- Agent detail may fetch activity log for one agent.
- Foreground reconcile can refresh current member/agent status in bulk, but
  should be coalesced per server.
- Background presence changes should not force durable Thread/Home cache writes
  unless they affect visible projections.

Do not subscribe every page to every presence event just because the event is
available. Treat presence as a soft contextual projection.

## What This Adds Beyond Existing Docs

Existing push docs focus on transport and server target eligibility. This doc
adds:

- one shared notification product intent
- foreground/killed click route identity parity
- app badge as unread projection, not provider callback state
- media group identity and permission/cache lifecycle
- explicit presence subscription budget

## Acceptance Tests

Shared tests:

- media group id is stable across message refresh
- expired preview URL transitions to `expired`/`failed` without deleting the
  message row
- deleted/redacted message disables media preview actions
- notification intent suppresses active visible stream in foreground
- notification click route contains identity only
- app badge stale fetch keeps previous count with `isStale = true`
- presence update for a non-visible member does not mutate visible Thread row
  projection

Android connected probes:

- multi-image message opens gallery from the message group
- notification click opens the right conversation and focused message
- foreground visible Thread does not show duplicate local notification
- large Home list receives presence refresh without scroll jank

## Open Questions

- Should media group support cross-message swiping in V1, or only within a
  single message?
- Should notification replacement key group by stream, thread, or message
  category?
- Should presence freshness be visible to users or only diagnostics?

## Non-Goals

- Do not implement FCM/APNs/OHOS transport here.
- Do not define provider credential storage here.
- Do not make attachment cache a source of permission truth.
- Do not require presence for every message row.
