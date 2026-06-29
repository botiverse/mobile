# Mobile Foreground Reconciliation Spec

Date: 2026-06-29

## Problem

When a user keeps a mobile conversation page open for a long time, backgrounds the app, and later returns, the app may not have consumed all realtime `agent:activity`, member presence, thread updates, and room membership events that happened while it was backgrounded or disconnected.

The foreground path must therefore reconcile canonical server state. It should not rely on old page data, stale route payloads, or the assumption that realtime delivered every intermediate event.

## Current Behavior

### AppShell / Home

- Android `SlockKuiklyActivity.onResume()` calls `AndroidAppScopeRealtimeOwner.onAppShown(...)` for all pages.
- Android only sends the shared Kuikly lifecycle event when the current page is `PAGE_APP_SHELL`:
  - `sendAppShellLifecycleEvent("resume")`
  - Notify payload: `action=appLifecycle`, `event=resume`.
- `SettingsHomePage` listens for that app-shell lifecycle event and calls `refreshVisibleAppShellRoute("app_lifecycle_resume")`.
- The AppShell foreground refresh behavior is scoped to the retained selected route:
  - Home primary: `SharedHomeShellIntent.ForegroundRefresh`
  - Activity: `SharedActivityIntent.Refresh`
  - Saved: `RefreshSaved`
  - Search: intentionally skipped
  - Tasks: `SharedTasksRouteIntent.Refresh`
  - Members: increments members refresh keys
  - Settings: `SharedSettingsIntent.Refresh`

For Home primary, `SharedHomeIntent.ForegroundRefresh` force-loads Home without the pull indicator. A successful Home load then kicks off the existing agent/member status projection refresh:

- `SharedHomeStore.launchAgentDirectMessageStatusRefresh(...)`
- `SharedHomeDataSource.refreshAgentDirectMessageStatuses(...)`
- `SharedServerMembersStore.refreshMembers(serverId, priority=Background, force=true)`

That bulk member refresh uses `RealMemberRepository.getMembers()`, which combines:

- `servers/{serverId}/members`
- `agents`
- `servers/{serverId}/machines`

Home agent dots subscribe through `slockAgentStatusResolver(serverMembersStore, serverId)`, so status updates flow from `SharedServerMembersStore`.

### Standalone Thread / Conversation Flow

Thread pages own their route identity and load canonical content through `SharedThreadStore.Start` / `Refresh`. Route/pageData content candidates are stripped or validated before rendering.

Thread realtime currently works like this:

- `ThreadPage` posts `slockRealtimeRooms` join commands for `payload.sourceThreadId` and `payload.streamChannelId` in a `DisposableEffect`.
- `ThreadPage` listens to the shared realtime Notify event and applies content events with `SharedThreadIntent.ApplyRealtimeEvent`.
- `Heartbeat` can trigger `SharedThreadIntent.RecoverRealtimeGap`.
- `MessageNew` / `SyncResumeResponse` seq gaps can trigger `recoverRealtimeGap(...)`.
- `MemberPresenceUpdated` updates `serverMembersStore.applyPresence(event)`.

However, a standalone Thread page does not currently receive the AppShell `appLifecycle` refresh event, because Android only emits that lifecycle Notify when `pageName == PAGE_APP_SHELL`. Returning from background while still on a standalone conversation page therefore does not automatically dispatch `SharedThreadIntent.Refresh`, does not force a member/status refresh, and does not necessarily re-emit room joins if the native runtime already believes those rooms are joined.

### Realtime Owner

`AndroidAppScopeRealtimeOwner.onAppShown(...)` does run for every Android activity resume. It performs a foreground health check and can reconnect if the client is disconnected or failed.

This is necessary but not sufficient as a state reconciliation guarantee:

- If the connection is already considered connected, foreground health check can skip.
- If native runtime still has a joined-room set, rejoining a room may be skipped as `already_joined`.
- If events were missed while backgrounded, only later realtime events or visible gap detection can recover thread messages.
- Agent activity/status reconciliation is not guaranteed unless a foreground store refresh is also triggered.

## State Layers

Foreground reconciliation should separate two different product needs.

### Agent Status / Presence

This is a server-wide current-state problem. It is appropriate to refresh on every foreground return for the active server.

Canonical source:

- `SharedServerMembersStore.refreshMembers(serverId, force=true)`
- Bulk endpoints: `servers/{serverId}/members`, `agents`, `servers/{serverId}/machines`

Recommended behavior:

- Any mobile foreground return with an authenticated current server should schedule a background members/status refresh.
- This should be independent of whether the visible page is Home, Thread, Members, or Settings.
- The refresh should be coalesced per server to avoid duplicate calls from AppShell + Thread + Members.

### Agent Activity Log

This is not currently a server-wide cheap refresh path. The available canonical detail refresh is per agent:

- `agents/{agentId}/activity-log?limit=50`
- plus current activity fallback from the agent list path

Realtime `agent:activity` maps to `MemberPresenceUpdated` and can update `SharedAgentDetailStore`, but missed historical entries cannot be reconstructed from the Home status refresh alone.

Recommended behavior:

- Do not fetch activity logs for every agent on Home foreground.
- Refresh an agent activity log only when an agent detail/profile surface is visible, or when a focused surface explicitly needs that agent's detail.
- For Home/Thread rows, treat the bulk `agents` refresh as the current-state fallback: status/activity label can be current, but full activity history is not guaranteed.

### Thread Messages / Room Membership

This is route-scoped. It must be reconciled from the visible thread identity, not from stale page payload content.

Recommended behavior:

- On Thread foreground return, dispatch `SharedThreadIntent.Refresh` for the visible route.
- Re-emit room join intent for the visible thread rooms after foreground reconnect or foreground refresh.
- Room join should support a "force/reconcile" reason so native runtime can re-emit `join:channel` even when the room is in the local joined set but the socket may have reconnected or lost server-side room membership.
- Keep route identity checks in `SharedThreadStore.routeStillCurrent(...)`; do not pass full message/member payloads through route/pageData.

## Proposed Contract

Add a shared foreground reconciliation contract, separate from AppShell-only route refresh:

```kotlin
data class SharedForegroundReconcileRequest(
    val serverId: ServerId,
    val reason: String,
    val visibleSurface: SharedForegroundSurface,
    val threadRooms: List<ChannelId> = emptyList(),
    val forceRoomRejoin: Boolean = false
)
```

Required effects:

1. Always coalesce and schedule server member/status refresh:
   - `serverMembersStore.refreshMembers(serverId, Background, force=true)`
2. If AppShell Home primary is visible:
   - dispatch existing `SharedHomeShellIntent.ForegroundRefresh`
3. If AppShell Activity is visible:
   - dispatch existing `SharedActivityIntent.Refresh`
4. If AppShell Saved is visible:
   - dispatch existing `RefreshSaved`
5. If AppShell Members is visible:
   - refresh member list/detail keys, but share the same member-store refresh instead of issuing a separate duplicate members call where possible.
6. If a Thread page is visible:
   - dispatch `SharedThreadIntent.Refresh`
   - force/reconcile join visible rooms: `sourceThreadId`, `streamChannelId`, and resolved auxiliary stream if present
7. If an Agent detail page is visible:
   - refresh that agent detail/activity, preserving live sequenced activity entries already applied by realtime.

This can be implemented either as:

- a shared store/coordinator in `SlockSharedStoreProvider`, or
- a small common foreground reconciler object wired by AppShell and Thread pages.

It should not live in native Android as product logic. Native should emit the foreground signal and keep realtime transport healthy; shared/KMP should decide which stores reload.

## Native Realtime Room Rejoin

Current room commands dedupe in `SlockRealtimeAppScopeCoordinator.joinChannelDecision()` through `state.markChannelJoined(channelId)`.

Foreground reconciliation needs a separate path:

- `joinChannel(channelId, reason="foreground_reconcile", force=true)`, or
- a new action such as `rejoinChannel`.

For `force=true`, native should emit `join:channel` even if the room is already in the local joined set, while keeping the joined set idempotent. This protects the case where local state says "joined" but the server-side socket room was lost across background/reconnect.

## Server API Needs

Confirmed server routes are enough for status/current-state reconciliation:

- `GET /api/servers/{serverId}/members`
  - Mounted behind auth, verification, and active server context.
  - Returns the current server human roster from `serverService.getServerMembers(...)`.
  - This endpoint is not a human online-presence feed; it is part of the roster/member projection.
- `GET /api/agents`
  - Mounted behind auth, verification, and `requireServer`.
  - Returns all agents for the active server.
  - The route already resolves each agent's current `activity` / `activityDetail` through `AgentOrchestrator.getActivity(...)`, then batch-enriches creator profile data.
  - `getActivity(...)` uses owner-local cache, Redis replica mirror, recent durable activity hint, then derived fallback; this is the canonical bulk current-state path for agent dots/activity labels.
- `GET /api/servers/{serverId}/machines`
  - Returns the server machine roster enriched through `buildMachineReadModel(...)`.
  - The read model includes `status`, `statusVersion`, daemon version, computer version, and computer-vs-daemon classification.

Existing APIs are also enough for visible agent detail:

- `GET /api/agents/{agentId}`
  - Returns one agent plus current `activity` / `activityDetail`.
- `GET /api/agents/{agentId}/activity-log?limit=50`
  - Single-agent durable trajectory log only.
  - The server checks that the requester can inspect private agent surfaces; creator/admin can view, ordinary server membership is not enough.
  - Limit is clamped to 1..200 and defaults to 50.
- `GET /api/agents/{agentId}/agent-dms`
  - Single-agent private detail surface with the same creator/admin gate.
- `GET /api/reminders?ownerAgentId=...`

No new API is required for Home agent status.

Realtime/server-room behavior:

- On socket connection, the server auto-joins `user:{userId}`, `server:{serverId}`, and all visible channel/DM rooms, then emits `rooms:joined`.
- `sync:resume` accepts `{ lastSeq }` and replies with `sync:resume:response` containing missed messages from `messageService.syncMessages(...)`, `currentSeq`, and `hasMore`.
- `sync:resume` is message-sequence recovery. It does not return agent presence/activity-log events.
- `join:channel` exists and verifies access for private, joint, DM, and thread channels before joining `channel:{channelId}`.
- `agent:activity` is broadcast to `server:{serverId}` with `agentId`, `activity`, `detail`, `timestamp`, optional `entries`, and per-agent `serverSeq`. The push is best-effort; correctness still depends on pull reconciliation.

A new API would only be useful if product wants a Home-level "recent agent activity for all agents" feed without per-agent fan-out. That would be a separate server-side batch endpoint, for example:

- `servers/{serverId}/agents/activity-summary?sinceSeq=...`
- returns latest status/activity entry per agent plus server seq

Until that exists, mobile should not fan out `activity-log` requests for every agent on foreground.

## Acceptance Criteria

1. From a standalone Thread page, background the app for long enough that realtime may reconnect, then foreground it.
   - The app performs realtime foreground health check.
   - The visible Thread store refreshes from canonical route identity.
   - Visible thread rooms are force-rejoined/reconciled.
   - Server member/agent status refresh runs in background.

2. From Home primary, background and foreground.
   - Home foreground refresh runs without pull indicator.
   - Activity/Saved badge summaries refresh as today.
   - Server member/agent status refresh runs exactly once after coalescing.

3. From Activity tab, background and foreground.
   - Activity list refreshes.
   - Server member/agent status refresh also runs, even though Home primary is not visible.

4. From Members / agent detail, background and foreground.
   - Members roster/status refreshes via shared member store.
   - Visible agent detail refreshes that agent's activity/detail only.

5. Search remains excluded from automatic query refresh.
   - Foreground reconciliation may refresh server member/status store, but it must not rerun arbitrary Search queries unless the user submits/changes filters.

## Test Plan

- Common unit tests:
  - AppShell lifecycle dispatch still skips Search query refresh.
  - Foreground reconciliation always requests server member refresh for authenticated current server.
  - Thread foreground reconciliation dispatches `SharedThreadIntent.Refresh` and room rejoin commands for route identity rooms.
  - Room rejoin force path emits even when the local room set already contains the channel.

- Android/KMP integration smoke:
  - Open Thread, background, foreground, assert logs:
    - `foreground_reconcile_start surface=thread`
    - `thread_refresh_start`
    - `room_join_emit reason=foreground_reconcile`
    - `store_load domain=server_members ... force=true priority=Background`

- Regression guard:
  - Keep launch-contract tests ensuring Thread route/pageData carries identity/intent only and that refresh uses MVI/store state, not full payload content.
