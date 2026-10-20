---
name: raft-realtime-event
description: Use when adding or changing a Socket.IO realtime event (a new RealtimeEvent subtype, a new server wire event name, or a new payload field) in Raft Mobile, or when a realtime event "never arrives" on Android or iOS.
---

# Adding a realtime event

Missing one step compiles fine and fails in a different layer (bit #360 and
#387 the same day). Contract: `docs/realtime-consistency-contract-2026-07-14.md`
(authority, gap recovery, sinks); AGENTS.md "Adding a RealtimeEvent subtype".

## 0. Verify the wire contract

Read the emitter in an external `botiverse/slock` checkout
(`$SLOCK_SOURCE_REPO/packages/server/src`, `packages/shared/`) and record its
SHA. Use the exact event name and camelCase fields; no guessed aliases.

## 1. Subscribe on both hosts

Each transport subscribes ONLY to its list; an unlisted event is silently
never received.

- Event name: add it to `compose/realtime-contract/realtime_contract.json`
  (`socketEvents` + `serverWireEvents`) and regenerate both outputs:
  ```bash
  python3 compose/realtime-contract/generate_realtime_contract.py
  ```
  (writes `compose/core/.../data/realtime/GeneratedRealtimeContract.kt` and
  `compose/iosApp/iosApp/GeneratedRealtimeContract.swift`). If you cannot
  regenerate, add a literal to the extra list in
  `compose/core/src/commonMain/kotlin/build/raft/app/data/realtime/RealtimeSocketEvents.kt`
  (`ServerWireEvents`) with a comment, as the existing literals do.
- Android consumes `RealtimeSocketEvents.ServerWireEvents` automatically.
- iOS keeps its OWN list: add the name to `serverWireEvents` in
  `compose/iosApp/iosApp/RaftIosRealtimeHost.swift`. Forgetting this is the
  classic "works on Android, never on iOS".

## 2. Model and map (shared)

1. Add the subtype to `sealed interface RealtimeEvent` in
   `compose/shared/src/commonMain/kotlin/build/raft/app/model/RaftModels.kt`.
2. Map name + payload in
   `compose/shared/src/commonMain/kotlin/build/raft/app/data/realtime/RealtimeEventMapper.kt`
   with a `@Serializable` payload DTO. Test it in
   `compose/shared/src/commonTest/.../data/realtime/RealtimeEventMapperTest.kt`
   using the upstream payload shape.
3. Route it to its owning store through the Store reducer
   (`SharedRealtimeDomainEventOwner` and the feature store). Never mutate UI
   state directly from the socket callback.

## 3. Fill every exhaustive `when`

- `compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt`:
  `performanceMarkerName()` and `performanceMarkerPhase()`.
- `compose/androidApp/src/main/java/build/raft/app/core/data/realtime/SocketIoRealtimeClient.kt`:
  `realtimeDiagnosticName()`. Only the androidApp compile checks it; the
  shared compile passes without it.
- Home while hidden: decide explicitly in
  `RealtimeEvent.updatesHomeSnapshot()` (`.../data/HomeDataSource.kt`) and
  `homeRealtimeDirtyReason()` (`.../data/SharedHomeStore.kt`). Both have
  `else` branches; do not let them default silently.

## 4. Validate

- Ordinary PRs do not compile iOS. Run the full tier on your branch
  (`gh workflow run test.yml --ref <branch> -f scope=full`, see
  `raft-ci-validation`) so the Swift list and the androidApp `when` both
  compile.
- Runtime evidence: Android logcat/diagnostics show `realtime_domain_accept*`
  for the event (see `raft-feedback-diagnostics` for the log format).
