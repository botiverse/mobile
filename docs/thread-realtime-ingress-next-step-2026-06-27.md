# Thread Realtime Ingress Next Step

Date: 2026-06-27
Status: explanatory note; no code change required by this document.

## What Was Just Refactored

Commit `9f00b8db refactor(thread): route realtime through store effects` removed
the old Thread realtime bridge:

- no `SharedThreadRealtimeEventSignal` prop passed from `ThreadPageScreen` to
  `SharedThreadRouteContent`;
- no `realtimeCacheInvalidationSequence` prop;
- no `ThreadRealtimeCacheInvalidationSignal`;
- no `SharedThreadIntent.ApplyRealtimeCacheSnapshot`;
- no `KuiklyThreadCacheInvalidationEvents` notify bus;
- no Thread cache invalidation/readback path.

After that refactor, channel and reply-thread rendering still share the same
Thread route/store:

```text
KuiklyThreadScreenPayload(route identity + intent)
  -> SharedThreadRouteContent
  -> SharedThreadViewModel / SharedThreadStore
  -> ThreadPageContent
```

Realtime now enters that same store directly as an intent, not as a Compose
prop or cache readback.

## What Still Remains

`SharedThreadRouteContent` still owns a route-level realtime effect:

```text
SharedThreadRouteContent
  -> KuiklyRealtimeEvents.addListener
  -> decodeSharedShellRealtimeEvent
  -> route/server filter
  -> SharedThreadIntent.ApplyRealtimeEvent / RecoverRealtimeGap
```

This is already much cleaner than the previous bridge, but it is still a
second realtime ingress path beside AppShell.

AppShell currently has the long-lived realtime path:

```text
SettingsHomePage / AppShell
  -> KuiklyRealtimeEvents.addListener
  -> decode raw realtime
  -> SharedRealtimeDomainEventOwner.accept(serverId, event, source = "app_shell")
  -> RealtimeDomainEventSinks
  -> Home / Settings / Tasks / Members / route markers
```

So the remaining difference is not the Thread rendering/store model. The
remaining difference is only where the realtime event is accepted and fanned
out.

## What "Move It Outward" Means

"Move it outward" means this:

```text
Current:

Thread route content decodes realtime itself
  -> Thread store intent

Next phase:

AppShell/domain owner decodes and dedupes realtime once
  -> route-matched active Thread sink
  -> Thread store intent
```

The target shape:

```text
Native socket / Kuikly realtime notify
  -> AppShell realtime ingress
  -> SharedRealtimeDomainEventOwner.accept(...)
  -> active Thread route sink
  -> SharedThreadStore.dispatch(...)
  -> Thread UI renders SharedThreadStore state
```

Thread should not own socket/raw realtime decoding in that end state. It should
only expose:

- current route identity: server id, channel id, parent channel id/message id,
  active reply-thread ids;
- a dispatch sink into the existing `SharedThreadStore`;
- optional room-join intent if the global owner needs to join a new route room.

The Thread state source remains `SharedThreadStore`. This does not introduce a
second Thread store.

## Why This Is A Separate Phase

This is separate because `SharedRealtimeDomainEventOwner` currently supports
only one registered `RealtimeDomainEventSinks` instance:

```kotlin
private var sinks: RealtimeDomainEventSinks? = null
```

That works for the current AppShell registration, but it is not enough if the
active Thread route also needs to register a sink independently.

There are two reasonable designs:

1. Multi-sink registry
   - `SharedRealtimeDomainEventOwner` stores a set/list of sink registrations.
   - AppShell registers Home/Settings/Tasks/Members sinks.
   - Active Thread registers a route-matched sink while it is visible.
   - `accept(...)` dedupes once, then fans out to registered sinks.

2. AppShell-owned active-thread sink
   - AppShell remains the single sink registration.
   - AppShell keeps the current active Thread route identity and dispatch
     callback.
   - Domain owner calls one AppShell sink; AppShell forwards matching events to
     the active Thread store.

The multi-sink registry is more general, but it changes the owner contract and
needs tests for registration/unregistration, duplicate event acceptance, and
fanout ordering. The AppShell-owned sink is narrower, but it couples active
Thread routing more tightly to AppShell.

That is why it should not be mixed into the small `9f00b8db` refactor.

## What Would Change In Code

A follow-up patch would likely do this:

1. Extend the domain fanout contract.
   - Either make `SharedRealtimeDomainEventOwner` support multiple sinks, or
     add an explicit active-thread sink owned by AppShell.

2. Add a route-matching Thread sink.
   - Match current server id.
   - Match channel/thread ids for `MessageNew`, `MessageUpdated`,
     `ThreadUpdated`, `SyncResumeResponse`, task updates, and heartbeat/gap
     recovery.
   - Dispatch existing store intents:
     - `SharedThreadIntent.RecoverRealtimeGap`
     - `SharedThreadIntent.ApplyRealtimeEvent`
     - `SharedTasksRouteIntent.ApplyTaskUpdate`
     - member presence/suggestion refresh intents

3. Remove the route-level raw listener from `SharedThreadRouteContent`.
   - No `KuiklyRealtimeEvents.addListener` in Thread route content.
   - No raw decode in Thread route content.
   - Thread route content only renders store state and sends UI/user intents.

4. Keep `SharedThreadStore` as the single Thread timeline state owner.
   - No cache readback.
   - No new public `SharedThreadTimelineStore`.
   - Internal reducer/file splitting is allowed, but not a second UI state
     source.

## What Should Not Be Done

- Do not reintroduce a removed write-through readback layer for the Thread timeline.
- Do not reintroduce `ThreadRealtimeCacheInvalidationSignal`.
- Do not add a second Thread UI state store.
- Do not make route/pageData carry message content, members, tasks, files, or
  other product facts.

## Current Recommendation

Stop at `9f00b8db` unless we explicitly want to do the single-ingress phase.

If we continue, the next task should be phrased narrowly:

> Move active Thread realtime ingress from `SharedThreadRouteContent` raw
> listener to AppShell/domain-owner fanout, keeping `SharedThreadStore` as the
> only Thread timeline state source.

That task should include owner fanout tests before removing the Thread route
listener.
