# React vs KMP Alignment: Realtime Slice

Verified: 2026-06-27
Main HEAD used for KMP/Android verification: `fd6089cc`
React source used for comparison: `third_party/slock-source`

This document replaces the earlier 2026-06-26 draft. The old draft mixed several
then-active task states with architectural claims; several of those P0 items have
since landed or were reclassified by code review. Treat this file as the current
working snapshot, not as a historical task board.

## Scope

Included:
- socket connect / reconnect / room join behavior
- realtime event mapping and domain fanout
- `lastSeq` / heartbeat / visible-gap recovery
- agent activity freshness and stale snapshot reconciliation
- thread/channel message ownership and cache invalidation
- foreground/background notification hooks

Not included:
- visual presentation of notifications
- unrelated Quiver diagnostics work
- product UI changes that do not affect realtime state flow

## React Baseline

React keeps realtime state in a few focused stores and uses socket events as
candidate updates that may trigger store writes or scoped refreshes.

Important reference points:
- `agentStore.ts:190-207` defines `agentActivityObservedAt`,
  `agentActivityVersions`, and `agentActivitySeq`.
- `agentStore.ts:216-221` exposes `resetActivitySeq()`.
- `agentStore.ts:597-648` applies `agent:activity` with optional per-agent
  `serverSeq` dedup.
- `MainLayout.tsx:1198-1204` wires `agent:activity` to `updateActivity(...)`.
- `MainLayout.tsx:1413-1427` resets agent activity seq and reloads agents on
  socket connect.
- `MainLayout.tsx:1433-1464` emits and consumes `sync:resume` after rooms join.
- `MainLayout.tsx:1479-1484` uses heartbeat seq to call visible-scope sync when
  the server is ahead.
- `MainLayout.tsx:1592-1628` resets activity seq and runs pull reconciliation on
  focus/online/periodic status recovery.

The React pattern is therefore:
1. socket push is accepted only when it is fresh enough for the target store;
2. reconnect clears stale per-agent seq guards before a fresh pull;
3. `lastSeq` and heartbeat detect missed message gaps;
4. full/scope refresh remains the fallback when incremental state is incomplete.

## Current KMP / Android State

### App-Scope Realtime Owner

The old draft said native app-scope realtime was still mostly future work. That
is no longer accurate.

Current code has a shared app-scope realtime engine and Android owner:
- `SlockRealtimeAppScopeCoordinator.kt:14-42` defines the host connection/sink
  contracts.
- `SlockRealtimeAppScopeCoordinator.kt:74-120` owns the platform-independent
  app-scope engine.
- `SlockRealtimeAppScopeCoordinator.kt:357-385` connects with the selected
  `lastSeq` and logs the resulting resume seq.
- `SlockRealtimeAppScopeCoordinator.kt:433-449` handles mapped events, dynamic
  room joins, and background unread notification decisions.
- `SlockRealtimeAppScopeCoordinator.kt:451-460` fans raw realtime events back
  through the host.
- `AndroidAppScopeRealtimeOwner.kt:25-35` owns the Android singleton runtime.
- `AndroidAppScopeRealtimeOwner.kt:68-91` tracks foreground/background
  visibility.
- `AndroidAppScopeRealtimeOwner.kt:119-173` reconnects from stored session.
- `AndroidAppScopeRealtimeOwner.kt:180-210` accepts connect requests from the
  shared app shell.

Status: implemented. Remaining risk is behavioral validation, not missing
architecture.

### App Shell Decode and Domain Fanout

KMP now decodes app-shell realtime once and sends it through a shared domain
owner.

References:
- `SettingsHomePage.kt:1742-1751` holds app-shell `RealtimeEventMapper`,
  `realtimeLastSeq`, and session key state.
- `SettingsHomePage.kt:1937-1951` resets resume seq on session change, resets
  agent activity seq, and calls `bridge.realtimeConnect(...)`.
- `SettingsHomePage.kt:2281-2322` refreshes realtime auth and reconnects with
  the current last seq.
- `SettingsHomePage.kt:2346-2358` registers domain sinks for Home, Settings,
  agent activity, and members presence.
- `SettingsHomePage.kt:2427-2457` decodes realtime event data, resolves server
  identity, updates `realtimeLastSeq`, and accepts the event into the domain
  owner.
- `SlockSharedStoreProvider.kt:220-234` defines `RealtimeDomainEventOwner` and
  its sinks.
- `SlockSharedStoreProvider.kt:255-283` dedupes accepted events, patches shared
  task projection, applies Home/Settings, applies agent activity, joins new DM
  rooms, and emits thread cache invalidations.

Status: implemented. This is the KMP equivalent of React's "socket event ->
store update / scoped refresh" boundary.

### Agent Activity Freshness

The previous doc listed missing `agentActivityObservedAt`,
`agentActivityVersions`, and `agentActivitySeq` as P0. That is stale.

KMP now has the same shape:
- `SharedAgentDetailStore.kt:41-43` stores observed-at, local versions, and
  per-agent activity seq.
- `SharedAgentDetailStore.kt:220-224` applies activity updates and exposes
  `ResetAgentActivitySeq`.
- `SharedAgentDetailStore.kt:398-415` applies agent presence only when it beats
  the previous seq, updates observed-at/version, and records `serverSeq`.
- `RealtimeEventMapper.kt:392-399` maps `agent:activity` payload `serverSeq`.
- `RealtimeEventMapper.kt:635-647` carries `serverSeq` into realtime activity
  entries.

Status: implemented for agent-detail/member presence flow. Validation should
focus on full user-flow smoke, not on missing seq fields.

### Agent Status Consumers

The follow-up consumer audit did not find a current KMP surface that renders the
agent status indicator directly from a stale route/pageData snapshot once the
shared resolver has data.

Current references:
- `SlockAgentStatusIndicator.kt:27-39` builds `slockAgentStatusResolver(...)`
  from `SharedServerMembersStore.memberStatusFlow(...)`.
- `SlockHomeComponents.kt:316-323` creates the Home resolver from the shared
  server-members store and current server id.
- `SlockHomeComponents.kt:2164-2168` renders agent DM status dots from the
  resolver, falling back to offline only when no resolver value exists.
- `ThreadPage.kt:2914-2920` creates thread message/member modal status
  resolvers from `SharedServerMembersStore`.
- `ThreadPage.kt:8852-8855` resolves message author status through the resolver
  instead of `authorAgentStatus` payload data.
- `MembersPage.kt:812-818` and `MembersPage.kt:1303-1316` create Members list
  and member-detail resolvers and background-load the same server members store.
- `MembersPage.kt:2122-2126`, `MembersPage.kt:5330-5331`, and
  `MembersPage.kt:6803-6807` prefer resolver-backed status for members,
  created-agent rows, and profile/detail surfaces, with static profile/detail
  status used only as first-paint fallback.
- `SettingsHomePage.kt:796-802` and `SettingsHomePage.kt:11428-11443` use the
  same resolver path for Home/Search entity leading avatars.

Status: agent-status consumption is aligned enough to remove it from the open
validation list. Remaining risk is end-to-end smoke around room joins,
foreground/background reconnect, and whether app-shell realtime reaches these
stores under every entry point.

### LastSeq, Heartbeat, and Gap Recovery

KMP has both app-shell cursor update and thread visible-gap recovery.

References:
- `RealtimeCursorPolicy.kt:23-63` updates applied seq from `MessageNew`,
  `MessageUpdated`, and `SyncResumeResponse`; heartbeat only updates server max.
- `RealtimeCursorPolicy.kt:71-88` creates visible-stream gap requests when a
  visible stream has a lower max seq than the server.
- `SettingsHomePage.kt:2456` updates `realtimeLastSeq` from each decoded event.
- `SharedThreadStore.kt:2585-2634` persists realtime events, detects a newer
  visible gap, and calls recovery.
- `SharedThreadStore.kt:2979-3028` starts realtime-gap fetch from visible max
  seq and applies newer payloads until the gap closes.

Status: implemented for thread visible streams. The important remaining
alignment question is whether top-level channel and reply-thread streams use the
same cursor/recovery semantics in all entry points. Task #435 was the right
framing for that validation.

### Thread / Channel Ownership

The earlier analysis treated thread leaks as still broad/open. Recent fixes have
made the core ownership rule explicit:

- Server/React timeline ownership is `channelId`.
- `messages.threadId` can be a reply-thread parent anchor and must not be used
  as the rendered reply timeline owner when `channelId` is present.

References:
- `RealtimeEventMapperTest.kt:78-99` pins `message:updated` to prefer
  `channelId` for timeline identity when `threadId` is the reply stream.
- `SharedThreadStoreTest.kt:4560-4622` pins that a realtime update for the
  parent/origin does not append the parent into the reply timeline.
- `SharedThreadStore.kt:2617-2620` persists realtime events with the active
  route timeline identity.

Status: core regression is covered. Remaining risk is new edge cases, not the
old "thread/channel ownership unknown" state.

### Home and Activity Projection

The old draft said Activity still had standalone local realtime state. Current
code has Home shell dispatching the same realtime event into Home and Activity:

- `SharedHomeShellStore.kt:30-40` has one `ApplyRealtimeEvent(serverId, event)`
  shell intent.
- `SharedHomeShellStore.kt:109` routes that intent through
  `applyRealtimeEvent(...)`.
- `SharedHomeShellStore.kt:165-168` dispatches to Home and, when applicable, to
  Activity.
- `SharedActivityStore.kt:73-82` accepts `ApplyRealtimeEvent`.
- `SharedActivityStore.kt:655-667` refreshes Activity for message/thread/task
  events and sync-resume.
- `HomeDataSource.kt:189-207` writes realtime events through local
  write-through and can read the updated cached Home snapshot.
- `HomeDataSource.kt:316-331` defines which realtime events update Home.

Status: implemented enough to remove the old "Activity standalone P0" claim.
Open validation remains: Activity entry points must still hydrate the same
thread/task/member state as direct channel entry points.

## Current Classification

### Aligned / Landed

- App-scope native realtime coordinator and Android owner.
- Shared app-shell realtime decoding and domain fanout.
- Agent activity observed/version/seq fields and reconnect reset.
- Home/Activity shared realtime dispatch.
- Thread parent update guard and `channelId`-first message mapping.
- Thread visible-gap recovery from `lastSeq` / heartbeat style signals.
- Dynamic DM room join through realtime domain owner.

### Needs Validation

1. **Top-level channel vs reply-thread gap recovery**
   - Verify both surfaces recover missed newer messages with equivalent cursor
     semantics.
   - If not, consolidate the channel path onto the same `RealtimeCursorPolicy`
     / visible-stream recovery rule.

2. **Agent status end-to-end smoke**
   - The store and resolver consumers are wired. Validate the live user flow:
     agent activity push reaches Home DM dots, thread author dots, Members list,
     member detail, and search/profile surfaces after reconnect and room joins.
   - Track any failure here as a room-join/domain-sink delivery bug unless a
     specific consumer bypass is found.

3. **Notification title/body quality**
   - App-scope notification hooks exist, but the content quality issue should
     be tracked as notification presentation/data mapping, not as core React
     realtime parity.

4. **Cross-entry thread hydration**
   - Activity/Search/Saved/Task entry points should select canonical
     thread/channel/task/member state from stores and repositories. Route/pageData
     must remain identity/intent only.

### No Longer Correct as P0

These claims from the older draft should not be repeated:
- "KMP lacks `agentActivityObservedAt` / `agentActivityVersions` /
  `agentActivitySeq`." It has them.
- "Native app-scope realtime owner is still future cleanup." It exists.
- "Activity realtime is standalone local state." The shell dispatches realtime
  into Activity.
- "Thread realtime ownership root cause is still unknown." The `channelId` vs
  reply-stream `threadId` contract is now tested.

## Verification Performed

2026-06-27 focused Gradle verification passed:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests build.raft.app.data.SharedAgentDetailStoreTest \
  --tests build.raft.app.data.realtime.RealtimeEventMapperTest.agentActivityPreservesServerSeq \
  --tests build.raft.app.data.realtime.RealtimeEventMapperTest.agentActivityCompactionEntryProjectsWorkingStatus \
  --tests build.raft.app.data.realtime.RealtimeEventMapperTest.agentActivityPreservesTrajectoryEntries \
  --tests build.raft.app.data.SharedServerMembersStoreTest.applyPresencePatchesCachedAgentRoster \
  --tests build.raft.app.data.SharedServerMembersStoreTest.applyPresenceRetainsFactBeforeRosterLoads \
  --tests build.raft.app.data.SharedHomeStoreTest.realtimePresenceFlushKeepsNewestSequencedPresence
```

What this pins:
- `RealtimeEventMapper` preserves `agent:activity.serverSeq` and structured
  activity entries.
- `SharedAgentDetailStore` rejects stale server seq, increments observed/version
  state, resets seq on reconnect, applies unsequenced legacy pushes, and protects
  newer sequenced realtime activity from stale REST detail snapshots.
- `SharedServerMembersStore` applies and retains presence facts across roster
  loads.
- `SharedHomeStore` keeps the newest sequenced presence in memory without
  writing transient presence back to durable Home cache.

2026-06-27 agent-status consumer focused verification also passed on
`fd6089cc`:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests build.raft.app.data.SharedServerMembersStoreTest.memberStatusFlowEmitsOnlyForTargetMemberStatusChanges \
  --tests build.raft.app.ThreadPayloadTest.serverMembersPresenceFactResolvesAgentStatusWithoutLoadedRoster \
  --tests build.raft.app.ThreadPayloadTest.threadAgentStatusLookupUsesServerMembersStateInsteadOfPayloadSnapshot
```

What this pins:
- `memberStatusFlow` emits only for the targeted member and updates from
  `applyPresence`.
- thread status lookup prefers server-members / presence state over static
  payload `authorAgentStatus`.
- presence facts can resolve an agent status even before the roster has loaded.

## Recommended Next Work

1. Keep the focused tests above in the regression gate for realtime/status
   changes, plus the thread ownership tests:
   - `RealtimeEventMapperTest.messageUpdatedPrefersChannelIdForTimelineIdentityWhenThreadIdIsReplyStream`
   - `SharedThreadStoreTest.realtimeParentOriginUpdateDoesNotAppendToReplyThreadTimeline`

2. Add or update tests only for gaps that are not already pinned:
   - channel visible-gap recovery parity with reply-thread recovery;
   - app-shell `realtimeLastSeq` reset on server switch / token change;
   - end-to-end agent status push after reconnect/room joins reaches Home,
     Thread, Members, detail/profile, and Search surfaces.

3. Keep future route/thread fixes inside the launch contract:
   route/pageData carries only identity and intent; product state is loaded from
   MVI stores/repositories and candidate snapshots must match server + canonical
   thread/channel identity before use.
