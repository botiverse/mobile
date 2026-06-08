# Shared Store MVI Audit - 2026-06-08

Scope: Thread, Home, Settings, Tasks, and Members shared state paths.

## Changes landed

- Thread: `SharedThreadStore` now emits state only through `reduceState` / `reducePayload`.
  - Duplicate semantic patch no-ops do not emit and do not repeat cache/write-through.
  - Refresh, member enrichment, realtime, and older/newer pagination all use the same payload reducer entry.
  - Follow-up #116 removed the `SetPayload` compatibility bridge. `ThreadPage` now dispatches semantic send, reaction, task-status, member, refresh, and realtime intents/results; write-through happens after reducer acceptance.
- Home: `SharedHomeStore` now emits state only through `reduceState`.
  - This preserves the existing `HomeSnapshot` repository contract while gating equivalent state emissions.
  - Existing stable-row preservation remains in the content-snapshot reducer path.
- Tasks: no store code change was required.
  - `SharedTasksStore` already uses `MviStoreRuntime`, which gates equivalent reducer output.
  - Added coverage that duplicate `SetContent(TaskSnapshot)` emits once and then no-ops.

## Focused tests

- `SharedThreadStoreTest.messageReactionIntentPatchesOnlyPayloadSliceAndNoopsWhenEquivalent`
  - Covers semantic optimistic reaction patching.
  - Proves duplicate semantic reducer input keeps the same state object and preserves non-payload state.
- `SharedThreadStoreTest.appendSentPagePatchesTimelineAndPreservesHeaderIdentity`
  - Covers send success patching without replacing the route/header identity.
- `SharedThreadStoreTest.applyMessageMutationWritesCachedPayloadAfterReducer`
  - Covers reaction mutation write-through after reducer application.
- `SharedThreadStoreTest.applyTaskStatusWritesPersistentSnapshotAfterReducer`
  - Covers task status write-through after reducer application.
- `SharedThreadStoreTest.irrelevantRealtimeMessageDoesNotChangeVisibleThread`
  - Now also proves an irrelevant realtime message does not emit a new state object.
- `SharedTasksStoreTest.sharedTasksStoreNoopsEquivalentSeedContentSnapshot`
  - Covers non-Thread equivalent snapshot no-op behavior through `MviStoreRuntime`.
- Existing `SharedHomeStoreTest.duplicateHomeSnapshotStartDoesNotEmitState`
  - Continues to cover duplicate Home snapshot start behavior after centralizing Home state writes.

## Audit results

Command used:

```bash
rg -n "data class SetPayload|data class SetResult|data class SetContent|_state\\.value\\s*=" \
  compose/shared/src/commonMain/kotlin/ai/slock/compose/SharedThreadStore.kt \
  compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedHomeStore.kt \
  compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedTasksStore.kt \
  compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedSettingsStore.kt \
  compose/shared/src/commonMain/kotlin/ai/slock/compose/MembersPage.kt \
  compose/shared/src/commonMain/kotlin/ai/slock/compose/data/MemberRepositoryDataSource.kt
```

Current intentional hits:

- Thread `SetPayload`
  - Removed by follow-up #116.
  - Source/test verification: `rg -n "SetPayload" compose/shared/src/commonMain/kotlin/ai/slock/compose compose/shared/src/commonTest/kotlin/ai/slock/compose` returns no matches.
- `SharedTasksIntent.SetResult` / `SharedTasksIntent.SetContent`
  - Typed snapshot/result inputs into `MviStoreRuntime`; equivalent output is no-op gated.
  - `MergeRefreshResult` remains the partial refresh path.
  - Follow-up only if Tasks needs entity-normalized cross-route state rather than route-local task snapshots.
- `SharedThreadStore.kt` and `SharedHomeStore.kt` `_state.value = nextState`
  - Single reducer helper assignments.
  - These are the intended local mutation points.
- `SharedSettingsStore.kt` direct `_state.value = ...`
  - Explicit exemption for this pass: current writes are route, effect, action-state, or destination-slice updates, not external full-payload replacement or untyped `SetState`.
  - Follow-up: migrate Settings to `MviStoreRuntime` or add a local `reduceState` helper to centralize no-op logging if Settings jank/no-op telemetry becomes a priority.
- Members / agent detail
  - No standalone shared `MembersStore` exists in the audited source.
  - `SettingsHomePage` has a local `SharedMembersScreen` with `mutableStateOf<SharedMembersUiState>` fed by typed member responses.
  - `MemberRepositoryDataSource` returns typed snapshots/results rather than mutating shared UI state directly.
  - Follow-up: create a dedicated Members store task if Product wants Members normalized into a shared MVI store.

## Remaining risks / owners

- Thread store follow-up after #116: continue toward normalized entity selectors if more cross-route Thread jank appears; the old `SetPayload` bridge is gone.
- Settings central MVI runtime migration: owner should be Settings integration / KMP store owner. Current code is slice-oriented but lacks the standard transition logger/no-op reporting used by Tasks.
- Members dedicated store: owner should be Product/KMP if Members needs the same store semantics as Home/Thread/Tasks; there is no existing store to patch without introducing new architecture.
