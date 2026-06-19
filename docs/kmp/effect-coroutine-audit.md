# KMP Effect / Coroutine Hot-Path Audit

Date: 2026-06-19
Task: #185 follow-up for closed #146
Audited commit: `e4b01c3e`

## Scope

This is a current-main re-audit of the old #146 effect/coroutine report. It
focuses on loops, repeated side effects, unstable effect keys, and selector
boundary violations in `compose/shared/src/commonMain/kotlin/ai/slock/compose`.

Current scan counts:

- `LaunchedEffect`: 137 occurrences
- `DisposableEffect`: 15 occurrences
- `snapshotFlow`: 5 occurrences
- `rememberCoroutineScope` / `.launch {`: 99 occurrences

The audit intentionally prioritizes hot UI paths over exhaustive low-risk
logging effects.

## Current Findings

### P0: Thread bottom-anchor feedback loop still exists

Files:

- `ThreadPage.kt:3623` starts a `snapshotFlow` keyed by
  `payload.instanceId, latestItemIndex`.
- `ThreadPage.kt:3679` starts the bottom-anchor effect keyed by
  `payload.instanceId, latestMessageLayoutKey, latestItemIndex,
  allowBottomAutoAnchor`.

Current shape:

```kotlin
LaunchedEffect(payload.instanceId, latestItemIndex) {
    snapshotFlow {
        listState.isScrollInProgress to listState.isLatestItemAtRenderedBottom(latestItemIndex)
    }.collect { (isScrollInProgress, isAtBottom) ->
        if (isScrollInProgress && !isAtBottom) {
            allowBottomAutoAnchor = false
        } else if (isAtBottom) {
            allowBottomAutoAnchor = true
        }
    }
}

LaunchedEffect(payload.instanceId, latestMessageLayoutKey, latestItemIndex, allowBottomAutoAnchor) {
    ...
    if (listState.isScrollInProgress && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
        allowBottomAutoAnchor = false
        return@LaunchedEffect
    }
    ...
}
```

Why this is still P0:

- `allowBottomAutoAnchor` is written by the collector and is also a key of the
  anchor effect.
- The collector does not use `distinctUntilChanged`, so repeated scroll/layout
  observations can write the same boolean repeatedly.
- New realtime messages change `latestItemIndex`, restarting the collector and
  the anchor effect on the thread's hottest path.
- The anchor effect itself can scroll, which changes list state and feeds the
  collector.

Minimum fix path:

- Remove `allowBottomAutoAnchor` from the anchor effect key.
- Add `distinctUntilChanged()` to both Thread `snapshotFlow` collectors.
- Guard assignments (`if (allowBottomAutoAnchor != next) ...`) or move the
  decision into a derived/selector state whose output is equality-gated.
- Keep the effect keyed by stable render-axis fields only:
  `payload.instanceId, latestMessageLayoutKey, latestItemIndex`.

Acceptance proof:

- Appending realtime messages while already at bottom should not create an
  alternating `allowBottomAutoAnchor` restart loop.
- Dragging away from bottom should set auto-anchor false once.
- Returning to bottom should set auto-anchor true once.

### P0: `MarkVisibleReadAll` still dispatches per message-count change

Files:

- `ThreadPage.kt:1713`
- `SharedThreadStore.kt:1659`

Current shape:

```kotlin
LaunchedEffect(renderedThread.sourceThreadId, renderedThread.status, renderedThread.messages.size) {
    if (renderedThread.status == "content" && renderedThread.messages.isNotEmpty()) {
        threadViewModel.dispatch(SharedThreadIntent.MarkVisibleReadAll)
    }
}
```

`SharedThreadStore.markVisibleReadAll()` always calls:

```kotlin
dataSource.markChannelReadAll(channelId)
```

Why this is P0:

- Realtime appends increase `renderedThread.messages.size`.
- Each append dispatches `MarkVisibleReadAll`.
- The store does not have an in-flight guard or a last-marked message/seq guard.
- A busy active thread can therefore issue one read-all network call per
  incoming message even when previous read-all work is still active.

Minimum fix path:

- Store a read marker key such as `(channelId, latestVisibleMessageId or
  latestSeq)`.
- Skip if the same marker is already marked or in flight.
- Debounce/coalesce realtime bursts if the API remains "read all".
- Keep initial "thread becomes visible content" read marking, but avoid
  all-message-count-triggered full read-all calls.

Acceptance proof:

- Opening a thread calls read-all once.
- Appending N messages while active coalesces to one in-flight call or one
  latest marker, not N parallel calls.
- Switching channel resets the marker key.

### P1: Thread older-autoload `snapshotFlow` lacks equality gating

File: `ThreadPage.kt:3634`

Current shape:

```kotlin
snapshotFlow { listState.isThreadOlderAutoLoadThreshold(boundaryIndex) }
    .collect { shouldLoad ->
        if (shouldLoad) {
            allowBottomAutoAnchor = false
            onThreadEvent("loadOlder", null, null, null)
        }
    }
```

Why this is P1:

- The collector emits a Boolean from scroll/layout state.
- Without `distinctUntilChanged`, repeated `true` emissions can call
  `loadOlder` repeatedly until the parent payload reflects loading state.
- There is a parent guard on `olderMessagesStatus`, so this is lower risk than
  the bottom-anchor loop, but it is still a hot scroll path.

Minimum fix path:

- Add `distinctUntilChanged()`.
- Optionally call `onThreadEvent("loadOlder", ...)` only on a false -> true
  threshold transition.

### P1: Tasks presentation pruning keys are full option lists

File: `SharedTasksPage.kt:295`, `302`, `311`

Current shape:

```kotlin
LaunchedEffect(visibleContent.channelOptions) { ... }
LaunchedEffect(visibleContent.creatorOptions) { ... }
LaunchedEffect(visibleContent.assigneeOptions) { ... }
```

Why this is P1:

- `visibleContent` is recomputed from sorted tasks/filter/presentation state.
- The effects only need option identity sets, not the full item objects.
- The body has a no-op state guard, so this is not a loop, but it can restart
  more often than necessary on Tasks refreshes.

Minimum fix path:

- Derive stable signatures:
  - `channelOptionIds = remember(...) { visibleContent.channelOptions.toSet() }`
  - `creatorOptionIds = remember(...) { visibleContent.creatorOptions.map { it.tasksIdentityFilterId() }.toSet() }`
  - `assigneeOptionIds = remember(...) { visibleContent.assigneeOptions.map { it.id.value }.toSet() }`
- Use those signatures as effect keys and reducer inputs.

### P1: Members activity bottom-anchor collector has same pattern as Thread

File: `MembersPage.kt:2590`

Current shape:

```kotlin
LaunchedEffect(detail?.agentId, rows.size) {
    snapshotFlow {
        listState.isScrollInProgress to listState.isMembersActivityAtBottom(rows.lastIndex)
    }.collect { (isScrollInProgress, isAtBottom) ->
        ...
    }
}
```

Why this is P1:

- Agent activity can append while an agent detail panel is open.
- The collector is keyed by row count and lacks `distinctUntilChanged`.
- It writes `allowBottomAnchor`, which is consumed by the scroll-to-bottom
  effect at `MembersPage.kt:2601`.

Minimum fix path:

- Add `distinctUntilChanged()`.
- Guard boolean assignment.
- Consider removing `rows.size` from the collector key and reading latest row
  index through a stable derived value.

## Downgraded / Fixed Since The Old #146 Report

### Thread render logging no longer keys on full payload

Old issue: `LaunchedEffect(renderedPayload)` emitted render diagnostics on
metadata-only payload changes.

Current state:

- `ThreadPage.kt:1694` builds a `ThreadRenderSignature`.
- `ThreadPage.kt:1705` keys the render log on that signature.
- `ThreadRenderSignature.kt` documents that non-render metadata is excluded.

Status: fixed/downgraded.

### Thread composer members no longer consume full shared members state

Old/new issue from #182: Thread composer suggestion UI could consume arbitrary
shared member state.

Current state:

- `SharedMembersStore` stores member suggestion slices keyed by
  `(serverId, mentionChannelId, memberScopedMentions)`.
- `ThreadPage.kt:1244` consumes `memberSuggestionSliceFlow(...)` and
  `channelSuggestionSliceFlow()`, both equality-gated.
- `AGENTS.md` now records the selector-boundary rule.

Status: fixed.

### Bottom-anchor list-object key was partly narrowed

Old issue: bottom anchor keyed on full `payload.messages` list identity.

Current state:

- `ThreadPage.kt:3551` computes `latestItemIndex` from
  `payload.originStatus, payload.messages.size`.
- `ThreadPage.kt:3577` computes `latestMessageLayoutKey` from specific last-row
  fields.

Status: partly fixed. Remaining loop is the `allowBottomAutoAnchor` feedback
described above.

### Settings/auth draft sync effects are acceptable

Examples:

- `AuthPages.kt:673`, `AuthPages.kt:678`
- `SettingsHomePage.kt:2288` through `2303`

These use scalar keys and copy prop/state into draft fields. They are not on a
hot realtime path and mostly have no-op/equality semantics through scalar keys.

Status: keep as P3 unless a concrete recomposition trace shows otherwise.

## Recommended Follow-Up Order

1. Fix Thread bottom-anchor feedback loop (`ThreadPage.kt:3623`, `3679`).
2. Add read-all coalescing / last-marker guard (`ThreadPage.kt:1713`,
   `SharedThreadStore.kt:1659`).
3. Add `distinctUntilChanged()` to remaining Thread/Members `snapshotFlow`
   collectors.
4. Narrow `SharedTasksPage` option-effect keys to stable id-set selectors.

## Notes For Future Reviews

- Effects should be keyed by the fields the effect body actually consumes.
- State written by an effect or its sibling collector should not also be a key
  of an effect that can write it again.
- `snapshotFlow` on scroll/layout state should have `distinctUntilChanged()`
  unless every emission is intentionally meaningful.
- Shared stores that serve multiple route/channel/member scopes must expose
  scoped selectors or slice flows with equality/no-op gates.
