# Realtime Single Owner / Domain Projection Spec

Date: 2026-06-23
Status: historical migration spec, partially implemented. Use
`docs/react-kmp-realtime-alignment-2026-06-26.md` plus current code for live
parity claims.
Scope: Android + OHOS Kuikly realtime ingress, AppShell, Thread, Home, Settings

## Current Status Snapshot - 2026-06-27

This spec still records the intended architecture: realtime is transport input,
not a generic product-state store. Domain stores remain the owners of product
state.

Implemented pieces now exist in common/shared code:

- `SlockSharedStoreProvider` owns a `SharedRealtimeDomainEventOwner`.
- `RealtimeDomainEventOwner` accepts typed `RealtimeEvent` values once per
  server/source and dedupes accepted keys.
- `RealtimeDomainEventSinks` fan accepted events into Home shell, Settings,
  agent activity/member detail refresh, dynamic channel join, and route markers.
- `SharedTasksDataStore` is updated by the domain owner for task events.
- Thread cache invalidation/readback has been removed. The remaining active
  Thread route effect dispatches accepted raw route events directly into
  `SharedThreadStore` and shared task/member stores.

For the current Android ingress-to-consumer code path, including the remaining
raw Thread route path, see `docs/android-realtime-code-path-2026-06-27.md`.

Do not treat the phase list below as unstarted work. Treat it as migration
history and a checklist for any remaining platform/page path that still handles
raw realtime payloads directly.

## Problem

The ANR investigation for #feedback task #33 showed Android realtime raw fanout reaching AppShell plus two Thread Activities at the same time (`consumer_count=3`). The native socket typed listener is single, but the raw event bridge forwards the same socket payload into every active Kuikly runtime consumer. Each page runtime can then decode, reduce, and persist the same event independently.

The current Android mitigation (`f928da00 fix(android): limit realtime fanout to foreground`) reduces the immediate blast radius:

- paused/background Kuikly Activities detach as raw consumers;
- heartbeat raw fanout is skipped because native typed realtime already updates cursor/health.

That mitigation is not the final architecture. The correct model is one long-lived owner that accepts socket events, advances cursor, and updates existing domain stores/repositories. Kuikly pages should subscribe to narrow domain projections/selectors from those stores, not act as independent raw realtime writers.

## Kuikly Runtime Findings

From the inspected Slock/Kuikly integration:

- `SlockKuiklyActivity` owns a `KuiklyRenderViewDelegator`; `onAttach(...)` creates/attaches a page runtime for one page surface.
- `BasePager.createExternalModules()` creates a new `BridgeModule`, `KuiklyMMKVModule`, and `SlockNetworkModule` for each Pager. Module instances are runtime/page scoped, not a built-in process-global store.
- Android `moduleExport(...)` registers native module factories per render/module request. A native module can call a process singleton, but the Kuikly module instance itself is not the singleton boundary.
- `IPagerEventObserver` and `kuiklyRenderViewDelegator.sendEvent(...)` are per-Pager event mechanisms. They are useful for page-specific control messages, but they are not a global cross-runtime event bus.
- OHOS `SlockOhosRealtimeBridge` is a platform singleton object, but it currently owns one `KRNativeRenderController` and forwards raw `slockRealtimeEvent` to that controller. That mirrors the same raw page-event model and should not become the canonical shared store boundary by itself.
- Existing Slock process-level mechanisms are Kotlin/native singletons and durable shared stores. In shared KMP, `SlockSharedStoreRegistry` already exposes a process-level provider containing `authStore` and `localDataStore`; on Android, `AndroidRealtimeBridgeManager` is a process-level singleton around the socket repository/client.

Conclusion: Kuikly does not appear to provide an app-wide cross-runtime store/event bus in the inspected APIs. The single-source boundary must therefore be a Slock-owned process/session owner. It should feed existing domain stores (`Home`, `Tasks`, `Members`, `Settings`, `Thread` projections) rather than introduce a generic realtime store as a second state graph.

## Target Architecture

Canonical realtime flow:

1. A platform process/session owner receives socket events once.
   - Preferred shared owner: AppShell while it is the long-lived page/runtime.
   - Native owner must remain the fallback for cold-start standalone Thread or if AppShell is absent.
   - Android: `AndroidRealtimeBridgeManager` remains the socket lifecycle/transport owner, but must forward accepted typed events to exactly one shared/domain owner.
   - OHOS: `SlockOhosRealtimeBridge` must follow the same single-owner rule.
2. The owner decodes/maps the event once, updates `RealtimeCursorPolicy` once, joins any derived rooms once, and dispatches typed domain intents.
3. Domain stores/repositories consume their own event slice:
   - `SharedHomeShellStore` / `SharedHomeStore` updates Home, Activity, Inbox/Saved/Search projections and durable repository/local-store caches.
   - `SharedTasksStore` consumes `TaskCreated` / `TaskUpdated` / `TaskDeleted` and exposes task selectors.
   - `SharedSettingsStore` consumes machine/settings/session relevant invalidations.
   - `SharedMembersStore` / agent detail stores consume member, presence, agent, and machine projections.
   - Thread screens consume only route-matching projections from the domain stores. They do not persist the socket event again.
4. UI code subscribes to narrow selectors, e.g. `subscribeTaskById(taskId)`, `subscribeThreadProjection(threadId)`, `subscribeHomeConversation(conversationId)`, instead of reading a full raw event stream.
5. Durable/cache writes happen once in the owning domain repository/write-through path. Projection subscribers are read-side consumers only.

Non-goals:

- Do not use route/pageData to pass product realtime state.
- Do not let every Kuikly runtime decode raw socket events and write the same store independently.
- Do not make Thread's visible reducer responsible for durable persistence of socket events.
- Do not add a generic `SharedRealtimeStore` as a new source of truth. Realtime is an input transport; domain stores remain the owners of domain state.

## Domain Owner And Selectors

The implementation should add a small owner/coordinator, not a new data store. Suggested naming: `RealtimeDomainEventOwner` or `AppShellRealtimeEventOwner`. It can live beside AppShell wiring first, then move behind a provider only if cold-start standalone routes require it.

Suggested API shape:

```kotlin
internal interface RealtimeDomainEventOwner {
    suspend fun accept(
        serverId: ServerId,
        event: RealtimeEvent,
        activeConversationId: ChannelId? = null
    )
}

internal interface TaskProjectionSource {
    fun subscribeTaskById(taskId: TaskId): StateFlow<TaskSummary?>
    fun subscribeTaskByNumber(channelId: ChannelId, taskNumber: Int): StateFlow<TaskSummary?>
}

internal interface ThreadProjectionSource {
    fun subscribeThreadProjection(identity: ThreadProjectionIdentity): StateFlow<ThreadProjection>
}
```

Responsibilities:

- keep and advance the applied realtime cursor;
- dedupe by event identity/sequence where available;
- route each event to existing domain stores, not to a generic realtime store;
- call durable write-through exactly once through the domain owner that owns that entity;
- expose narrow selectors/projections for UI consumers;
- keep UI-thread work small by batching noisy projections such as presence/member activity and by avoiding full payload recomposition for one task/status change;
- expose enough diagnostics to prove one accept, one write owner, and bounded projection emissions.

Subscriber responsibilities:

- AppShell/Home owns broad realtime ingestion and subscribes to its own Home/Activity/Search/Saved state.
- Settings subscribes only to settings/machine/auth relevant projections.
- Thread subscribes to projections matching its route identity (`sourceThreadId`, `streamChannelId`, or parent channel/message pair) and to per-row task selectors (`subscribeTaskById` / `subscribeTaskByNumber`). It updates visible state but does not persist the same realtime event again.
- Members and computer detail pages subscribe to member/machine projections or reload through their existing stores.

Example Thread row usage:

```kotlin
@Composable
fun ThreadTaskChip(message: KuiklyThreadMessageItem, tasks: TaskProjectionSource) {
    val task by remember(message.taskId, message.taskNumber) {
        when {
            message.taskId != null -> tasks.subscribeTaskById(TaskId(message.taskId))
            message.taskNumber != null -> tasks.subscribeTaskByNumber(message.channelId, message.taskNumber)
            else -> flowOf(null)
        }
    }.collectAsState(null)

    val status = task?.status ?: message.taskStatus
    val assignee = task?.assignee?.displayName ?: message.taskAssigneeName
    TaskMetaChip(status = status, assignee = assignee)
}
```

This is intentionally selector-shaped. A task status update should invalidate only rows that subscribe to that task identity, not the full Thread payload.

## Migration Plan

Phase 0: update this spec and keep the current Android mitigation in place. It is a pressure reduction, not the final model.

Phase 1: remove the `SharedRealtimeStore` direction. Keep `SlockSharedStoreProvider` as auth/local-data provider only. Do not add generic realtime state there.

Phase 2: make AppShell the single shared realtime consumer when present. Its `IPagerEventObserver` should decode raw `slockRealtimeEvent` once and call `RealtimeDomainEventOwner.accept(...)` / domain store intents once.

Phase 3: remove `SharedThreadStore` realtime persistence from `ApplyRealtimeEvent`. The Thread reducer may keep a temporary visible projection for route-matching events, but durable writes must already have happened in the AppShell/domain owner path.

Phase 4: add domain selectors:

- task selectors by id / number for Thread footer chips;
- thread projection selectors by route identity;
- conversation/unread selectors by conversation id;
- member/agent/machine selectors by member/machine id.

Phase 5: update Thread UI to subscribe to selectors instead of deriving footer/status from a full `payload.tasks` list. This addresses the performance concern behind `const taskStatus = subscribeByTaskId()`.

Phase 6: keep raw page fanout only for temporary visual compatibility while migrating. When a route receives a raw event, it must treat it as a read-side projection signal only and must not write local cache.

Phase 7: apply the same owner/write/projection model to OHOS. `SlockOhosRealtimeBridge` should be the platform ingress/control owner, while typed accepted state flows into the same domain owner/selector boundary rather than raw page events.

Phase 8: remove legacy raw socket page observers once Android and OHOS both use domain projections. Keep per-Pager events for route-local lifecycle, pageData updates, and host control messages only.

## Validation

Automated checks:

- owner unit test: accepting one event with two visible subscribers calls the domain write owner once and emits bounded projections;
- duplicate sequence/event test: the same event delivered twice does not double-write;
- Thread route identity tests: thread projections only reach matching channel/thread/reply routes;
- task selector tests: changing task #N updates only subscribers for that task id/number;
- heartbeat test: cursor/health updates do not create UI raw events or durable home/thread writes;
- presence/member activity batching test: noisy facts produce bounded projection emissions.

Android diagnostics:

- expected: `event_dispatch listeners=1` remains true in socket client logs;
- expected: new `realtime_domain_accept event=... owner=app_shell writes=1 projections=N` or equivalent;
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

- AppShell is the preferred owner, but cold-start standalone Thread must not depend on AppShell existing first. If standalone Thread can exist without AppShell, native ingress must host the same `RealtimeDomainEventOwner` and expose selectors to the page.
- `SlockSharedStoreRegistry` is currently one process-wide provider. Domain owner/projection sources must be scoped by active session/server if multiple sessions can coexist.
- Kuikly thread confinement must be explicit. Domain writes can run off the page runtime, but each Kuikly runtime must collect projections on its own valid Kuikly/Compose coroutine boundary before mutating UI state.
- OHOS needs parity proof because its current bridge owns one controller and raw event dispatch path; migration should verify how multiple page controllers are represented before changing lifecycle ownership.
