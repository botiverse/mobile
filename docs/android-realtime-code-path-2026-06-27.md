# Android Realtime Code Path - 2026-06-27

This note records the current Android/KMP realtime path from Socket.IO ingress to
the concrete consumers. It is a code map for cleanup work, not a target
architecture.

## Ingress

1. `SocketIoSocketTransport` is the Android Socket.IO binding.
   - `connect(auth)` creates the socket with WebSocket transport and auth.
   - `on(eventName, listener)` binds listeners for socket events and manager
     reconnect events.
   - `emit(eventName, payload)` sends room commands such as `join_channel` and
     `leave_channel`.

2. `SocketIoRealtimeClient` owns the typed realtime client state.
   - `connect(RealtimeConnectRequest)` stores server auth, initializes
     `RealtimeCursorPolicy` from `lastSeq`, installs transport listeners, and
     starts the heartbeat watchdog.
   - For every `RealtimeSocketEvents.ServerWireEvents` event, it first rejects a
     top-level `serverId` mismatch, maps the wire payload through
     `RealtimeEventMapper`, updates cursor state, and dispatches the typed
     `RealtimeEvent` to `observeEvents` listeners.
   - It separately fans raw payloads to `observeRawEvents` listeners. Heartbeat
     raw fanout is skipped unless the heartbeat reports the server is ahead.
   - Room lifecycle is tracked in `joinedChannels`; reconnect replays joined
     rooms and `rooms_joined` emits `sync_resume` from the resume cursor.

3. `SessionRealtimeRepository` adapts the Android session store to
   `RealtimeRepository`.
   - It refreshes auth before connecting when an `AuthTokenRefresher` is present.
   - It exposes `observeEvents` from the client.
   - Room join/leave calls delegate to the client.

## App-Scope Runtime

4. `AndroidAppScopeRealtimeOwner` is the process-level Android owner.
   - It bootstraps a `SlockRealtimeAppScopeRuntime` from stored session or from
     the shared AppShell connect request.
   - It tracks foreground/background visibility and network callbacks.
   - It does not itself reduce product state; it owns the native lifecycle and
     delegates event decisions to shared runtime code.

5. `AndroidAppScopeRealtimeHostContext` implements
   `SlockRealtimeHostContext`.
   - `connect()` calls `SocketIoRealtimeClient.connect(...)`.
   - `observeEvents()` reads typed events through `SessionRealtimeRepository`.
   - `observeRawEvents()` reads raw payloads directly from the client.
   - `fanOutRawRealtimeEvent()` bridges raw events into Kuikly with
     `sendKuiklyEvent(SlockRealtimeNotifyContract.EventName, ...)`.

6. `SlockRealtimeAppScopeRuntime` subscribes to the host connection.
   - Typed events go to `SlockRealtimeAppScopeCoordinator.onMappedEvent`.
   - Raw events go to `SlockRealtimeAppScopeCoordinator.onRawEvent`.

7. `SlockRealtimeAppScopeCoordinator` and engine own app-scope decisions.
   - `connectDecision` dedupes same-session connect requests and chooses the
     resume seq.
   - `roomCommandDecision` dedupes room joins/leaves from Kuikly notify events.
   - `onMappedEvent` updates last seq, dynamically joins new DM rooms, and
     triggers background unread notification candidates for `MessageNew`.
   - `onRawEvent` still allows raw fanout back into Kuikly pages.

## Shared AppShell Consumer

8. `SettingsHomePage` is the current AppShell realtime consumer.
   - It listens to `KuiklyRealtimeEvents`, decodes raw event data with
     `decodeSharedShellRealtimeEvent`, resolves server identity, updates
     `realtimeLastSeq`, and calls
     `sharedStores.realtimeDomainEventOwner.accept(serverId, event, "app_shell")`.
   - It registers `RealtimeDomainEventSinks` for Home shell, Settings, agent
     activity, member detail refresh, dynamic channel join, and route markers.

9. `SharedRealtimeDomainEventOwner` is the current typed domain fanout.
   - It dedupes accepted events by stable event keys.
   - `Heartbeat` is logged and exits before domain sinks.
   - Task events patch `SharedTasksDataStore`.
   - Accepted events dispatch into `SharedHomeShellStore` and
     `SharedSettingsStore`.
   - `DirectMessageCreated` requests a room join.
   - Agent presence/activity updates `SharedAgentDetailStore` and
     `SharedServerMembersStore`.
The old provider-level Thread cache invalidation bus has been removed. Active
Thread route realtime now dispatches directly into `SharedThreadStore`, while
AppShell/domain fanout keeps owning Home, Settings, Tasks, member detail, agent
activity, and route markers.

## Concrete Consumers

10. Home and Activity
    - `SharedHomeShellStore.ApplyRealtimeEvent` dispatches the event to
      `SharedHomeStore` and, when the Activity surface should update, to
      `SharedActivityStore`.
    - `HomeDataSource.applyRealtimeEvents` writes message/thread events through
      current repository/local-store paths and can reload the cached Home
      snapshot.
    - Realtime cache handling ignores transient presence, skips heartbeat
      writes, writes new/updated messages, applies `SyncResumeResponse`, and
      writes thread metadata for `ThreadUpdated`.

11. Settings and Members
    - `SharedSettingsStore.ApplyRealtimeEvent` handles settings/machine/server
      relevant changes.
    - Agent presence/activity goes through `SharedAgentDetailStore` and
      `SharedServerMembersStore` via the registered AppShell sinks.

12. Tasks
    - `SharedRealtimeDomainEventOwner.patchSharedTaskProjection` updates
      `SharedTasksDataStore` for task create/update/delete events before route
      stores consume their task projections.

13. Thread
    - `SharedThreadRouteContent` joins the active route rooms with
      `KuiklyRealtimeEvents`.
    - It listens to raw bridged realtime events as a route effect. The
      route-local `shouldDeliverToActiveThreadRealtimeSignal` keeps heartbeat
      for gap recovery while filtering noisy presence from the raw-event
      reducer path.
    - Non-task, non-presence raw events still dispatch
      `SharedThreadIntent.ApplyRealtimeEvent`.
    - Task updates dispatch to the shared Tasks projection route store. Member
      presence and suggestion refreshes dispatch to the shared member stores.

## Current Cleanup Boundary

The provider-side thread invalidation bus and Thread cache snapshot readback are
removed. There is no remaining `ThreadRealtimeCacheInvalidationSignal` ->
`SharedThreadIntent.ApplyRealtimeCacheSnapshot` path.

This does not remove the need for `RealtimeRoutePolicy.shouldForwardToActiveThread`
inside `ThreadPage` while Thread still has a raw visible-event path. Removing
that filter before deleting the raw path would reintroduce noisy presence-driven
Thread reducer work.

The larger remaining SSOT gap is that Thread can still persist raw realtime
events through `SharedThreadIntent.ApplyRealtimeEvent`. That should be handled
as a separate cleanup: Thread should either apply a temporary visible projection
only, or consume cache snapshots/selectors after the app-scope/domain owner has
performed the durable write.
