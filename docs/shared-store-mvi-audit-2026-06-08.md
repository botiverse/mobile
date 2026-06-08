# Shared Store MVI Audit - 2026-06-08

Scope: Thread, Home, Settings, Tasks, and Members shared state paths.

## Changes landed

- Thread: `SharedThreadStore` now emits state only through `reduceState` / `reducePayload`.
  - Duplicate `SetPayload` no-ops do not emit and do not repeat cache/write-through.
  - Refresh, member enrichment, realtime, and older/newer pagination all use the same payload reducer entry.
  - `SetPayload` remains as a compatibility bridge for current `ThreadPage` optimistic send/action call sites, but it is no longer a raw `_state.value.copy(payload = ...)` write.
- Home: `SharedHomeStore` now emits state only through `reduceState`.
  - This preserves the existing `HomeSnapshot` repository contract while gating equivalent state emissions.
  - Existing stable-row preservation remains in the content-snapshot reducer path.
- Tasks: no store code change was required.
  - `SharedTasksStore` already uses `MviStoreRuntime`, which gates equivalent reducer output.
  - Added coverage that duplicate `SetContent(TaskSnapshot)` emits once and then no-ops.

## Focused tests

- `SharedThreadStoreTest.setPayloadPatchesOnlyPayloadSliceAndNoopsWhenEquivalent`
  - Covers send-style optimistic payload patching.
  - Proves duplicate payload reducer input keeps the same state object and preserves non-payload state.
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

- `SharedThreadIntent.SetPayload`
  - Compatibility bridge for current `ThreadPage` local optimistic patches.
  - Safe under this task because the store reducer gates equivalent payloads and cache writes.
  - Follow-up: split `ThreadPage` call sites into semantic intents such as send optimistic/success/failure, message action result, task status result, and refresh result.
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

- Thread semantic-intent split: owner should be the Thread/KMP UI owner. This is larger than this safe reducer pass because `ThreadPage` currently uses `SetPayload` for optimistic sends, reactions, save, reply-thread open, task status, and refresh rollback.
- Settings central MVI runtime migration: owner should be Settings integration / KMP store owner. Current code is slice-oriented but lacks the standard transition logger/no-op reporting used by Tasks.
- Members dedicated store: owner should be Product/KMP if Members needs the same store semantics as Home/Thread/Tasks; there is no existing store to patch without introducing new architecture.
