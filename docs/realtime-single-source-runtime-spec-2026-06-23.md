# Realtime Single Source / Cross-Runtime Store Spec

Date: 2026-06-23
Status: draft
Scope: Android + OHOS Kuikly realtime ingress, AppShell, Thread, Home, Settings

## Problem

The ANR investigation for #feedback task #33 showed Android realtime raw fanout reaching AppShell plus two Thread Activities at the same time (`consumer_count=3`). The native socket typed listener is single, but the raw event bridge forwards the same socket payload into every active Kuikly runtime consumer. Each page runtime can then decode, reduce, and persist the same event independently.

The current Android mitigation (`f928da00 fix(android): limit realtime fanout to foreground`) reduces the immediate blast radius:

- paused/background Kuikly Activities detach as raw consumers;
- heartbeat raw fanout is skipped because native typed realtime already updates cursor/health.

That mitigation is not the final architecture. The correct model is one source that accepts socket events, advances cursor, updates durable/cache state, and publishes typed state/projection changes. Kuikly pages should subscribe to that state/projection, not act as independent raw realtime writers.

## Kuikly Runtime Findings

From the inspected Slock/Kuikly integration:

- `SlockKuiklyActivity` owns a `KuiklyRenderViewDelegator`; `onAttach(...)` creates/attaches a page runtime for one page surface.
- `BasePager.createExternalModules()` creates a new `BridgeModule`, `KuiklyMMKVModule`, and `SlockNetworkModule` for each Pager. Module instances are runtime/page scoped, not a built-in process-global store.
- Android `moduleExport(...)` registers native module factories per render/module request. A native module can call a process singleton, but the Kuikly module instance itself is not the singleton boundary.
- `IPagerEventObserver` and `kuiklyRenderViewDelegator.sendEvent(...)` are per-Pager event mechanisms. They are useful for page-specific control messages, but they are not a global cross-runtime event bus.
- OHOS `SlockOhosRealtimeBridge` is a platform singleton object, but it currently owns one `KRNativeRenderController` and forwards raw `slockRealtimeEvent` to that controller. That mirrors the same raw page-event model and should not become the canonical shared store boundary by itself.
- Existing Slock process-level mechanisms are Kotlin/native singletons and durable shared stores. In shared KMP, `SlockSharedStoreRegistry` already exposes a process-level provider containing `authStore` and `localDataStore`; on Android, `AndroidRealtimeBridgeManager` is a process-level singleton around the socket repository/client.

Conclusion: Kuikly does not appear to provide an app-wide cross-runtime store/event bus in the inspected APIs. The app should build the single-source boundary in Slock's shared/native store layer, then let each Kuikly runtime observe projections from that layer.

## Target Architecture

Canonical realtime flow:

1. A platform process/session owner receives socket events once.
   - Android: `AndroidRealtimeBridgeManager` via `SocketIoRealtimeClient.observeEvents`.
   - OHOS: `SlockOhosRealtimeBridge` equivalent typed event owner.
2. The owner decodes/maps the event once, updates `RealtimeCursorPolicy` once, joins any derived rooms once, and passes the typed event to a shared realtime store.
3. The shared realtime store writes local durable/cache state once through `ComposeLocalWriteThrough` or a successor with the same local-first contract.
4. The shared realtime store emits typed projections/invalidations after the write.
5. AppShell, Home, Settings, Thread, Activity, Search, and Saved stores observe projections or reload from local cache/repositories.

Non-goals:

- Do not use route/pageData to pass product realtime state.
- Do not let every Kuikly runtime decode raw socket events and write the same store independently.
- Do not make Thread's visible reducer responsible for durable persistence of socket events.

## Shared Realtime Store

Add a shared `SharedRealtimeStore` owned by `SlockSharedStoreProvider`, or a sibling registry keyed by session/server if provider lifetime needs to remain smaller.

Suggested API shape:

```kotlin
internal interface SharedRealtimeStore {
    val projections: Flow<SharedRealtimeProjection>

    suspend fun accept(
        serverId: ServerId,
        event: RealtimeEvent,
        activeConversationId: ChannelId? = null
    )
}
```

Responsibilities:

- keep and advance the applied realtime cursor;
- dedupe by event identity/sequence where available;
- call `ComposeLocalWriteThrough.writeRealtimeEvents(...)` exactly once per accepted event/batch;
- publish typed projections such as home invalidation, thread message update, task update, member/presence update, settings/machine update, and auth/session invalidation;
- keep UI-thread work small by batching noisy projections such as presence and member activity;
- expose enough diagnostics to prove one accept/write with N subscribers.

Subscriber responsibilities:

- AppShell/Home subscribes to Home/Activity/Search/Saved projections or reloads its local store after invalidation.
- Settings subscribes only to settings/machine/auth relevant projections.
- Thread subscribes to projections matching its route identity (`sourceThreadId`, `streamChannelId`, or parent channel/message pair). It updates visible state but does not persist the same realtime event again.
- Members and computer detail pages subscribe to member/machine projections or reload through their existing stores.

## Migration Plan

Phase 0: keep the current Android mitigation in place while the store contract lands. It is a pressure reduction, not the final model.

Phase 1: introduce `SharedRealtimeStore` and diagnostics while raw page fanout still exists. Gate durable writes through the shared store so two page subscribers cannot double-write.

Phase 2: move Android socket typed events into `SharedRealtimeStore.accept(...)`. Keep `sendEvent("slockRealtimeEvent")` only for lifecycle/control actions that are page-local, such as auth refresh and app foreground.

Phase 3: update Thread to observe shared projections/cache. Remove `SharedThreadStore` realtime persistence from `ApplyRealtimeEvent`; visible state becomes projection-driven.

Phase 4: update AppShell/Home/Settings to observe projections instead of decoding raw socket payloads from `IPagerEventObserver`.

Phase 5: apply the same owner/write/projection model to OHOS. `SlockOhosRealtimeBridge` should be the platform ingress/control owner, but typed accepted state should still flow into the shared store boundary rather than raw page events.

Phase 6: remove legacy raw socket page observers once Android and OHOS both use shared projections. Keep per-Pager events for route-local lifecycle, pageData updates, and host control messages only.

## Validation

Automated checks:

- shared store unit test: accepting one event with two subscribers performs one write-through and emits two projections;
- duplicate sequence/event test: the same event delivered twice does not double-write;
- Thread route identity tests: thread projections only reach matching channel/thread/reply routes;
- heartbeat test: cursor/health updates do not create UI raw events or durable home/thread writes;
- presence/member activity batching test: noisy facts produce bounded projection emissions.

Android diagnostics:

- expected: `event_dispatch listeners=1` remains true in socket client logs;
- expected: new `shared_realtime_accept event=... writes=1 subscribers=N` or equivalent;
- expected: no socket-event `raw_event_forwarded` for normal realtime payloads after migration;
- expected: opening AppShell plus multiple Thread Activities does not increase durable writes per event.

OHOS diagnostics:

- expected: socket events are accepted by one platform ingress owner;
- expected: page controllers receive state/projection changes, not raw socket payloads;
- expected: page show/hide remains page-local lifecycle control, not data ownership.

Manual smoke:

- open AppShell, one channel Thread, and one reply Thread;
- send a message/reply/task update from another client;
- verify one local cache update, live visible update on the matching screen, no duplicate unread/message/task changes, and no sustained main-thread realtime fanout.

## Open Questions

- `SlockSharedStoreRegistry` is currently one process-wide provider. If multiple accounts, servers, or app runtimes can coexist in one process, the realtime store/provider should be keyed by active session/server rather than a single nullable provider.
- Kuikly thread confinement must be explicit. Shared store writes can run off the page runtime, but each Kuikly runtime must collect projections on its own valid Kuikly/Compose coroutine boundary before mutating UI state.
- Cold-start standalone Thread must not depend on AppShell existing first. Native ingress and shared store creation must be lazy and independent of any specific page.
- OHOS needs parity proof because its current bridge owns one controller and raw event dispatch path; migration should verify how multiple page controllers are represented before changing lifecycle ownership.
