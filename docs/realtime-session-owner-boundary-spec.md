# Realtime Session Owner Boundary Spec

Status: historical migration proposal. The canonical authority/grant/scope/sink
model is now `realtime-consistency-contract-2026-07-14.md` (foundation v1.2).
Use this document only for transport-lifecycle motivation and migration history.

This spec tightens the realtime lifecycle boundary after the Android standalone
Thread Pager path added host-side realtime for `compose_thread`.

## Problem

Realtime is a session/server capability. It must not be owned by a transient
Kuikly page, shell, route, or Activity instance.

As of `upstream/main` including `95d848ef fix(android): host realtime for
standalone thread`, Android `RaftLegacyHostActivity` treats both
`PAGE_APP_SHELL` and `PAGE_THREAD` as realtime host pages:

- `onCreate` / `onResume` call `startRealtimeBridgeIfNeeded()`.
- `onPause` / `onDestroy` call `stopRealtimeBridge()`.
- `stopRealtimeBridge()` closes event subscriptions, calls
  `realtimeRepository?.disconnect()`, and clears `realtimeRepository`,
  `realtimeServerId`, and `realtimeSessionKey`.
- `PAGE_THREAD` then joins foreground rooms from `sourceThreadId`,
  `streamChannelId`, and `threadId`.

That means the mobile standalone path can reconnect on normal navigation:

- AppShell opens standalone Thread: AppShell pauses and disconnects; Thread
  starts and connects.
- Thread closes back to AppShell: Thread pauses/destroys and disconnects;
  AppShell resumes and connects.
- AppShell-internal Home/Channel/Thread route switches do not necessarily
  reconnect while they remain in the same Activity, but any cross-Activity
  shell boundary can.

This was acceptable as a short unblock for realtime delivery in standalone
Thread proof work, but it is not the long-term ownership model.

OHOS is closer to the target model today: `SlockOhosRealtimeBridge` is exported
as a singleton, keeps `connectParams` / `joinedChannels`, reuses an existing
socket when `(serverId, accessToken)` is unchanged, and suspends/resumes on app
page show/hide. However shared `realtimeDisconnect(...)` commands still mean
the product contract must explicitly define who is allowed to disconnect.

## Ownership Rule

There is exactly one realtime connection owner per process/session/server.

The owner is keyed by:

- `serverId`.
- an auth identity such as an access-token hash or auth generation.
- socket base URL if it can vary.

The owner is not keyed by:

- Kuikly page name.
- AppShell route.
- standalone Thread Pager instance.
- Android Activity instance.
- tab selection.
- modal or sub-panel lifecycle.

## Allowed Connect And Disconnect Triggers

The session owner may connect or reconnect for:

- first authenticated foreground session for a selected server.
- server switch.
- login/logout/session restore boundary.
- access-token refresh that changes auth identity.
- explicit auth invalid / server membership removed / server deleted.
- transport reconnect after heartbeat silence, network failure, or Socket.IO
  close.
- process teardown.
- app background/foreground policy if the platform deliberately suspends the
  socket; this must be app lifecycle, not route lifecycle.

The session owner must not disconnect for:

- Home -> Channel -> Home navigation.
- Home/Tasks -> standalone Thread Activity and back.
- Thread tab changes.
- opening/closing Thread panels, member sheets, files, tasks, or composer
  surfaces.
- switching between AppShell routes inside the same selected server.

## Consumers

Activities and Kuikly pages attach as consumers. They do not own the socket.

Recommended interface:

```kotlin
interface RealtimeBridgeManager {
    fun ensureConnected(session: RealtimeSessionIdentity, lastSeq: Long = 0L)
    fun disconnectForSessionEnd(reason: String)
    fun attachConsumer(surfaceId: String, sink: RealtimeEventSink)
    fun detachConsumer(surfaceId: String)
    fun declareRooms(surfaceId: String, rooms: Set<ChannelId>)
}
```

`attachConsumer` / `detachConsumer` only affects fan-out. It must not disconnect
the underlying socket.

## Cross-Platform Contract

The shared contract is the source of truth. Android and OHOS may use different
socket transports, but they must implement the same owner/consumer semantics.

Common contract concepts:

- `RealtimeSessionIdentity`: `serverId`, auth identity / token generation, and
  socket base URL.
- `RealtimeSurfaceId`: a stable id for one Activity/page/render surface while
  it is attached.
- `RealtimeRoomDeclaration`: the foreground rooms a surface wants to receive.
- allowed disconnect reasons: logout, auth invalid, server switch, server
  membership removed, server deleted, and process teardown.
- diagnostics names for connect, disconnect, consumer attach/detach, room
  declare/join/skip, raw event receive, and per-consumer fan-out.

Platform-specific pieces:

- Android uses `SessionRealtimeRepository`, `SocketIoRealtimeClient`, and
  `SocketIoSocketTransport`.
- OHOS uses `SlockOhosRealtimeBridge` and `OhosSocketIoClient`.
- Platform bridge code converts shared commands into the common contract, then
  delegates to its process/session owner.

A platform may implement the owner with a worker thread, coroutine actor,
serial dispatcher, or synchronized manager. That is an implementation detail.
The required property is ownership: route/page lifecycle can only attach or
detach consumers and declare rooms; it cannot own or disconnect the socket.

## Room Membership

Room membership is the union of rooms declared by attached consumers plus any
durable app-level rooms the owner needs for recovery.

Examples:

- AppShell declares the currently visible channel/thread rooms.
- Standalone Thread declares `sourceThreadId`, `streamChannelId`, and
  `threadId` when present.
- Direct-message creation can add the new DM channel id idempotently.

The owner keeps an active room set or ref-counted room table:

- duplicate declarations do not emit duplicate `join:channel`.
- switching surfaces only joins newly needed rooms.
- leaving a surface may remove only rooms no remaining surface needs.
- reconnect replays the current active room set once.

If the server does not support `leave:channel`, the owner may keep joined rooms
for the session and rely on client-side routing filters, but this must be
explicit and measured.

## Event Fan-Out

The socket owner receives raw events once and fans them out to attached
consumers:

- AppShell consumer decodes events and dispatches Home/Settings/Tasks/Thread
  store updates.
- Standalone Thread consumer receives the same host event shape currently sent
  through `THREAD_REALTIME_EVENT`.
- Consumers still use `RealtimeRoutePolicy` and store reducers to decide
  whether an event applies to their route.

The owner must not parse route payloads for product behavior beyond declared
room ids. Product state still belongs to shared stores/repositories.

## Android Migration Plan

Phase 1: extract a process-scoped manager.

- Move `realtimeRepository`, subscriptions, `realtimeServerId`,
  `realtimeSessionKey`, `realtimeLastSeq`, and connect job out of
  `RaftLegacyHostActivity`.
- Host the manager under application scope or `RaftAdapters`, not an
  Activity instance.
- Keep `SessionRealtimeRepository`, `SocketIoRealtimeClient`, and
  `AuthTokenRefresher` as the transport stack.
- Make `RaftLegacyHostActivity` call `attachConsumer(surfaceId, sink)` in
  `onCreate`/`onStart` and `detachConsumer(surfaceId)` in `onDestroy`.
- Replace Activity `stopRealtimeBridge()` with consumer detach. Do not call
  repository `disconnect()` on `onPause` or normal `onDestroy`.
- Keep explicit shared `realtimeDisconnect(reason)` mapped only to session-end
  reasons such as logout, server removal, or server deletion.

Phase 2: model foreground rooms.

- AppShell declares rooms from active shell route.
- Standalone Thread declares launch identity rooms from whitelisted PagerData.
- Manager de-dupes and rejoins after reconnect.
- Add diagnostics for `room_declared`, `room_join_emit`, `room_join_skip`, and
  `consumer_count`.

Phase 3: unify Android/OHOS contract.

- Keep OHOS singleton behavior, but align command semantics with Android:
  page hide suspends app lifecycle only, and route/surface teardown detaches a
  consumer rather than disconnecting the session.
- Ensure shared `BridgeModule.realtimeDisconnect(...)` is not emitted by route
  disposal except for real session/server boundary changes.

## Verification Matrix

Android log proof must include:

| Scenario | Expected connection behavior |
| --- | --- |
| App start into AppShell | one connect for selected server |
| Home -> `#all` standalone Thread | zero disconnect; zero second connect for same session |
| Thread close -> AppShell | zero disconnect; zero second connect for same session |
| Tasks -> task Thread -> back | zero disconnect; zero second connect for same session |
| AppShell route Home -> Channel -> Home | zero disconnect; no duplicate join/apply |
| Server switch | one intentional disconnect/reconnect pair |
| logout | one intentional disconnect |
| token refresh | reconnect only if auth identity changes or force-reconnect is required |
| app background/foreground | platform policy only; no route-driven disconnect |

Functional proof must include:

- standalone Thread receives `message:new`, `message:updated`, reaction, saved,
  and task update events.
- AppShell Home/Tasks state remains warm after returning from standalone Thread.
- events are applied once; duplicate raw event fan-out does not double-append or
  double-patch messages/tasks.
- heartbeat resume/gap recovery still uses the latest accepted `lastSeq`.
- direct-message creation still joins the new DM channel once.

Diagnostics to collect:

- `connect_result` count by `serverId` and session key hash.
- `disconnect_requested` / transport disconnect reason.
- `join_foreground_room` or replacement room diagnostics.
- consumer attach/detach counts.
- raw event count and per-consumer fan-out count.

## Risks This Removes

- avoid connection churn when users open and close standalone Thread pages.
- avoid missed events during Activity handoff gaps.
- avoid server room/presence storms from repeated `join:channel`.
- avoid inconsistent auth refresh ownership across AppShell and standalone
  Thread.
- make future two-pane / multi-window layouts possible, where Home and Thread
  are visible at the same time and cannot be represented by a single route
  owner.
