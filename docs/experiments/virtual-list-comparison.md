# Virtual Message Timeline Comparison Experiment (`#proj-mobile:#259`)

> **Branch:** `exp/reef/virtual-list-comparison` (non-merged comparison experiment)  
> **Scope:** Virtualization, jump convergence, in-thread search coordination, selection pinning, and scroll-path overhead on Compose Multiplatform 1.12.2 (`androidx.compose.foundation` 1.12.1).  
> **Physics rule (per `@Artea` & `@artin` in `#proj-mobile:1dfc9454`):** Never mimic iOS scroll physics/bounce animations on Android. Both the baseline and the experimental prototype use native Compose `Modifier.scrollable`, `ScrollableDefaults.flingBehavior()`, and `rememberOverscrollEffect()`.

---

## 1. Design Summary: Web `VirtualList` + `fluid-scroll` vs Compose `LazyLayout` vs `LazyColumn`

### Web reference invariants (`botiverse/slock`)
Inspecting `packages/web/src/components/message/VirtualMessageTimeline.tsx`, `messageTimelineShared.ts`, `timelineVisibleRows.ts`, `useThreadSearch.ts`, and `store/selectionStore.ts` highlights five core virtualization invariants:

1. **Key-indexed persistent height model & above-view compensation:** `@botiverse/raft-ui` `VirtualList` maintains per-key measured heights with an estimated fallback (`ESTIMATED_ROW_PX = 72`, `MIN_ROW_PX = 20`) and automatically absorbs size changes above the viewport (`keepAnchorOnItemsChange`, `itemResizable`) so prepends and async image/reaction resizes do not jump the reader's position (`VirtualMessageTimeline.tsx:5-10`, `:745-755`).
2. **Coalesced collapsed system-message rows:** `buildMessageTimelineRows` (`messageTimelineShared.ts:115-126`) emits **one** virtual row per collapsed system-message run and drops folded members from the virtual row list completely, mapping folded message IDs back to their summary row key via `foldedMessageIds` (`messageTimelineRowKeyOfMessageId`, `:145-152`).
3. **Pre-paint exact key jumps + pagination barrier:** `scrollTo({ key, align: "start" | "center" | "end", behavior: "instant" })` converges before the first paint (`VirtualMessageTimeline.tsx:9-10`, `:223-234`), while `sentinelAutoLoadBlockKey` (`:194-201`, `:309`, `:372`) blocks edge auto-load pagination until a genuine user gesture occurs after the jump.
4. **Externalized search corpus & geometry queries:** `useThreadSearch.ts` hydrates and searches the full thread corpus completely decoupled from mounted DOM nodes; `timelineVisibleRows.ts` (`visibleMessageIdsOfList`, `rowViewportPositionOfList`) queries `getOffsetTopOfKey` + binary search rather than scanning DOM rows.
5. **Externalized multi-select + pinned focus/selection rows:** Multi-select lives outside mounted rows in Zustand `useSelectionStore` (`selectionStore.ts:79-165`), while active text selection / focus rows are kept mounted off-viewport by `VirtualList` itself (`VirtualMessageTimeline.tsx:109-111`).

### `fluid-scroll` hot-path principle
Hot-path scroll offset math should be **zero-allocation and decoupled from composition/reconciliation**: scroll deltas update numeric offsets and drive layout/placement directly without triggering parent recomposition or linear item scans.

### Compose Multiplatform mapping
| Capability | Current `LazyColumn` (`ThreadMessagesList`) | Experimental `VirtualMessageTimeline` (`LazyLayout`) |
|---|---|---|
| Height model | Visible-window only; no persistent height index across jumps | Persistent `HashMap<Any, Int>` height cache + Fenwick tree (`IntArray`) |
| Visible range lookup | Anchor item + sequential walk from `firstVisibleItemIndex` | `O(log N)` Fenwick binary lifting (`visibleRange` into caller `IntArray`, zero heap alloc) |
| Scroll hot path | `LazyListState.onScroll` -> `Remeasurement.forceRemeasure()` | `ScrollableState` -> `scrollOffsetPx` -> `Remeasurement.forceRemeasure()` (0 parent recompositions) |
| Cold jump (`Center` / `Top`) | Reversed `scrollToItem` lands bottom first, then polls `awaitVisible` + `scrollBy` (or multi-frame retry loop) | Single-pass pre-draw convergence inside `LazyLayoutMeasurePolicy` (`< 2px` on Frame 1) |
| Collapsed system runs | 1 `LazyColumn` item per message; folded members draw empty `Box` | 1 `CollapsedSystemGroupRow` per run (`runLength - 1` zero-height rows eliminated) |
| Off-viewport pinning | `LazyLayoutPinnableItem` (only active when a child calls `LocalPinnableContainer.pin()`) | Both `LazyLayoutPinnableItem` AND explicit `pinnedKeys: Set<Any>` (measured & clipped off-viewport) |
| Native scroll & fling | Native `Modifier.scrollableArea` / `ScrollableDefaults.flingBehavior()` | Native `Modifier.scrollable` + `ScrollableDefaults.flingBehavior()` + `rememberOverscrollEffect()` |

---

## 2. Concrete Gaps Audited in Current Mobile `ThreadMessagesList`

Every item below was verified directly in `compose/shared/src/commonMain/kotlin/build/raft/app/`:

1. **Dead `activeListBeyondBoundsItems` calculation**
   - `thread/ThreadMessagesList.kt:974-981` computes `activeListBeyondBoundsItems` from `threadLazyWindowConsumerCounts.listBeyondBoundsItems` (`thread/ThreadMessagesListModel.kt:259-295`), gating it on `postFirstFrameBeyondBoundsEnabled` and `shareSelectionCaptureActive`.
   - Its **only** read in the entire file is `ThreadMessagesList.kt:2293` (`listState.threadLazyWindowStats(..., beyondBoundsItems = activeListBeyondBoundsItems)`), which writes a diagnostic log string. It is never passed to `LazyColumn` (which relies on the default `DetachSafeLazyListPrefetchStrategy` wrapping `LazyListPrefetchStrategy()`).
2. **Zero-height placeholder `Box` rows for collapsed system messages**
   - `thread/ThreadMessagesList.kt:4113-4123` (matching the design note in `thread/ThreadSystemMessageGroups.kt:25-27`) keeps every folded `ThreadSystemMessageFold.Member` as its own `LazyColumn` `items(...)` slot and renders `Box(Modifier.fillMaxWidth().onGloballyPositioned { recordMessageItemHeight(...); keepReadingAnchorAfterLayout() })`.
   - Because each folded member measures `0px` tall, `LazyColumn` fills the viewport straight through **every consecutive zero-height item at once**: a collapsed run of 100 system events immediately composes, lays out, and attaches `onGloballyPositioned` observers to all 99 hidden placeholders in one frame.
3. **Multi-frame cold jumps, reversed-layout bottom flash, and polling drift loops**
   - `thread/ThreadListViewport.kt:173-196`: on `reverseLayout = true`, jumping to an unmounted item via `scrollToItem(index, scrollOffset)` calls `raw.scrollToItem(reversedIndex)` (placing the row at physical bottom `rawOffset = 0`), suspends in `awaitVisible(index)` polling every `16ms` (`:222-227`), and only then issues `scrollBy(delta)`.
   - `thread/ThreadViewportScroll.kt:371-404`: `scrollThreadItemToFocusedViewport` runs `repeat(ThreadFocusedMessageCenterAttempts)` (`6` attempts) with `delay(16ms)` per attempt to converge after measuring the row.
   - `thread/ThreadMessagesList.kt:2740-2857`: after focusing, a dedicated polling loop (`while (true) { delay(FocusedAnchorSessionRecheckIntervalMs) ... }`) watches for post-jump drift and dispatches re-anchor commands.
   - Note: `#proj-mobile:#258` (`thread/ThreadInitialFocusedPlacement.kt:32-153`) already eliminated Frame-1 flash for **cached initial open** via a synchronous `onGloballyPositioned` + `CoroutineStart.UNDISPATCHED` `scrollRawToItem`, but mid-session jumps (in-thread search hops, message links, outline jumps) still go through the multi-frame path.
4. **In-place selection & row-local state lifecycle across scroll-out**
   - `thread/ThreadInPlaceTextSelection.kt:650-661` and `thread/ThreadMessageCard.kt:893-899`: `rememberThreadInPlaceSelectionController(messageId, enabled)` allocates `ThreadInPlaceSelectionController` (owning `val selectionState = SelectionState()`) inside each message card via `remember(host, messageId)` and releases it on `DisposableEffect.onDispose`.
   - `ThreadInPlaceTextSelectionHost` (`ThreadInPlaceTextSelection.kt:109-181`) only tracks `private var active: ThreadInPlaceSelectionTarget? = null`; it does not hoist `SelectionState` or explicitly pin the active row.
   - **Important Compose Foundation 1.12.1 nuance (verified in bytecode):** `androidx.compose.foundation` 1.12.1 sets `ComposeFoundationFlags.isSelectionAutoScrollEnabled = true`, so `SelectionManager.setSelection` calls `LocalPinnableContainer.current?.pin()` on active `SelectionContainer` selectables, which `LazyColumn` honors via `LazyLayoutPinnableItem`. Thus, active `SelectionContainer` text selections survive scroll-out in Compose 1.12.1 *only* as a side effect of `SelectionManager`'s internal flag, whereas **any** row-local state not owned by `SelectionManager` (custom selection/controller state, active audio playback, inline form/focus state) is immediately disposed by `LazyColumn` once scrolled beyond the viewport/prefetch window. Multi-select (`selectedShareMessageIds`) is already safely hoisted into page state.
5. **In-thread search jump & pagination barrier coordination**
   - `data/SharedThreadFindStore.kt` and `thread/ThreadFind.kt` already mirror web `useThreadSearch` cleanly: the full thread corpus is hydrated and matched outside `LazyColumn`, and `ThreadMessagesList.kt:1379-1391` dispatches `ThreadFindJumpAction` when an active hit is outside the loaded window.
   - However, hopping between loaded or context-loaded hits relies on the multi-frame `LazyColumn` focus path above and lacks a unified `sentinelAutoLoadBlockKey` barrier tied to user scroll gestures (currently older/newer auto-load guards are split across `olderPageSettledUserScrollGeneration` and `newerGestureDemandTracker` in `ThreadMessagesList.kt:2413-2560`).

---

## 3. Experimental Prototype Architecture

All prototype code lives isolated under `compose/shared/src/commonMain/kotlin/build/raft/app/thread/experiment/` (no production call sites modified):

| File | Responsibility |
|---|---|
| `VirtualTimelineHeightIndex.kt` | Key-indexed persistent height cache (`HashMap<Any, Int>`) + 1-indexed Fenwick tree (`IntArray`) supporting `O(log N)` prefix sums (`offsetOf`), `O(log N)` binary-lifting `indexAtOffset`, and zero-allocation `visibleRange(..., outRange: IntArray)`. |
| `VirtualTimelineRowCoalescer.kt` | Coalesces consecutive foldable system messages (`>= 2`) into one `CollapsedSystemGroupRow` virtual row, tracking `foldedMemberToRowKey` / `foldedMemberToGroupId` for jumps and reporting `collapsedZeroHeightRowsEliminated`. |
| `VirtualTimelineSearchCoordinator.kt` | Externalized search corpus & highlight state decoupled from mounted items; resolves loaded hits directly (expanding folded groups if requested) or requests context-window hydration while arming a pagination-suppressed pending jump. |
| `VirtualMessageTimelineState.kt` | `ScrollableState` implementation driving `Remeasurement.forceRemeasure()` on scroll, single-pass pre-draw jump convergence (`jumpToKey` for `Top`/`Center`/`End` with `< 2px` tolerance), above-anchor height-delta compensation, pagination suppression until user scroll, and off-viewport pinned-slot measurement/placement. |
| `VirtualMessageTimeline.kt` | `@Composable` built on stable CMP 1.12.2 `LazyLayout(itemProvider, modifier, prefetchState, measurePolicy)` + `LazyLayoutPinnableItem` + native `Modifier.scrollable` / `ScrollableDefaults.flingBehavior()` / `rememberOverscrollEffect()`. |

### Verification & Benchmark Suites (executed in CI `:shared:testDebugUnitTest`)

1. **`compose/shared/src/commonTest/kotlin/build/raft/app/thread/experiment/VirtualTimelineCoreUnitTest.kt`**
   - Fenwick tree prefix sums & binary-lifting `indexAtOffset`/`visibleRange` verified against `O(N)` linear ground truth across 400 heterogeneous rows (`48..420px`) and `<= 10` BIT steps.
   - Persistent cached height preservation across 40-row prepend.
   - 100 collapsed system events coalesced to 1 virtual row (99 placeholder rows eliminated) + folded jump key resolution + expand restoration.
   - 320-message unmounted search corpus matching, context-load trigger with auto-load suppression, and next/prev wraparound.
2. **`compose/shared/src/androidUnitTest/kotlin/build/raft/app/VirtualMessageTimelineComparisonUiTest.kt`** (Robolectric `w400dp-h800dp`, `runRobolectricComposeUiTest`)
   - **Scenario A (`compareColdHeterogeneousJumpAccuracyAndOverhead`):** 320 heterogeneous rows (`48dp..420dp`, target `#250` at `320dp`, second target `#40` at `420dp`) comparing:
     - `LazyColumn` 1-pass estimated Center jump
     - `LazyColumn` 2-pass multi-frame Center jump (Frame-1 flash vs Frame-2 settled)
     - Production `ThreadListViewport(reverse = true)` Top jump
     - `VirtualMessageTimeline` single-pass pre-draw convergence (Center, Top, and second cold Center jump), asserting `< 2px` error on Frame 1 and `0` edge auto-loads while suppressed.
   - **Scenario B (`compareVirtualizedInThreadSearchAcrossUnmountedCorpus`):** 320-message corpus with 5 hits `[8, 95, 182, 265, 312]`, starting with only tail `220..319` loaded; proves full-corpus match discovery, unloaded hit context-load handoff with pagination suppression, and 6 consecutive multi-hop jumps all landing `< 2px` with `< 35` mounted rows and preserved externalized highlight.
   - **Scenario C (`compareSelectionSurvivalAcrossScrollOutAndScrollBack`):** 300 rows (`80dp`), scrolling `m5 -> m250 -> m5` across:
     - Item-local unpinned selection range (`LazyColumn` disposes & loses state vs `VirtualMessageTimeline(pinnedKeys)` 0 disposals, exact range preserved, and clean disposal upon clear/unpin).
     - Real `ThreadInPlaceSelectionController` + `SelectionContainer` side-by-side in Compose 1.12.1 `LazyColumn` vs `VirtualMessageTimeline(pinnedKeys)` (empirically capturing Compose 1.12.1's `LocalPinnableContainer` pinning behavior and verifying clean unpin disposal).
     - Externalized multi-select store (3 IDs surviving with 0 pinned slots).
   - **Scenario D (`compareCollapsedSystemRowCoalescingAndScrollOverhead`):**
     - **D1:** 100 consecutive collapsed system events between 20+20 regular messages (`LazyColumn` zero-height `Box` pattern composes/lays out ~99 hidden boxes at once vs `VirtualMessageTimeline` 41 virtual rows, 0 zero-height boxes).
     - **D2:** 25 incremental `scrollBy(160f)` steps (`4000px`) on 300 heterogeneous rows measuring parent recompositions (`0` on `VirtualMessageTimeline`), unchanged-item recompositions (`0`), measure passes, and `O(log N)` Fenwick lookup depth (`<= 10` steps).

> **CI capture:** Running `:shared:testDebugUnitTest` prints formatted comparison tables for Scenarios A, B, C, D1, and D2 directly to the test output log.

---

## 4. Honest Engineering Tradeoff Analysis: Full `LazyLayout` Replacement vs Targeted `ThreadMessagesList` Upgrades

| Dimension | Option A: Full `LazyLayout` Replacement | Option B: Targeted Upgrades to `ThreadMessagesList` (`LazyColumn`) |
|---|---|---|
| **Cold jump accuracy (`Center`/`Top`)** | Native single-pass pre-draw `< 2px` convergence via Fenwick tree + in-pass measurement; no `reverseLayout` index flipping needed. | Extend `ThreadInitialFocusedPlacementOwner`'s synchronous `onGloballyPositioned` + `UNDISPATCHED` `scrollRawToItem` pattern to mid-session focus/search jumps (1 extra synchronous remeasure, no visible wrong frame). |
| **Collapsed system-message bursts** | Eliminates 100% of zero-height rows via `coalesceVirtualTimelineRows`. | **Identical win** with a small projection refactor in `ThreadMessagesList.kt`: build coalesced `items()` list and map folded IDs to summary row keys (same pattern as web `buildMessageTimelineRows`). |
| **In-place selection & row keep-alive** | First-class `pinnedKeys` + `LazyLayoutPinnableItem`. | Compose 1.12.1 `LazyColumn` **already wraps every item in `LazyLayoutPinnableItem`** (`LocalPinnableContainer`). Calling `LocalPinnableContainer.current?.pin()` inside active `ThreadInPlaceSelectionController` / inline audio rows (or hoisting `SelectionState` / `TextRange` to `ThreadInPlaceTextSelectionHost`) achieves the exact same off-viewport survival in ~20 lines. |
| **Beyond-bounds / prefetch window** | Direct control via `overscanPx` + `LazyLayoutPrefetchState`. | Wire `activeListBeyondBoundsItems` to Compose 1.12.1's `LazyListState(cacheWindow = LazyLayoutCacheWindow(...))` (verified present in CMP 1.12.2 foundation) instead of leaving it diagnostics-only. |
| **Hot-path scroll & recomposition** | Zero parent/item recompositions, `O(log N)` BIT lookups, zero-alloc `visibleRange`. | `LazyColumn` already uses `Remeasurement.forceRemeasure()` for zero-recomposition scroll; most remaining mobile scroll jank comes from item body work (markdown/images/zero-height folded boxes/derivedState reads), not `LazyColumn`'s internal `O(visible)` anchor walk. |
| **Production parity & regression risk** | **High cost & risk.** `ThreadMessagesList.kt` (5,514 lines) relies on `LazyListState` across `LazyViewportController`, `ThreadReadingAnchorKeeper`, `RaftThreadImeLayout`, `ThreadShareImage` capture, `LazyLayoutSemantics` (TalkBack/VoiceOver), `BringIntoViewSpec`, `LazyListItemAnimator`, interactive iOS keyboard dismiss, and `DetachSafeLazyListPrefetch`. Re-proving all of these on Android + iOS is a multi-week effort. | **Low risk, incremental, immediately shippable.** Every identified defect can be closed independently behind existing unit + Robolectric + device tests without destabilizing IME anchoring, TalkBack/VoiceOver semantics, or iOS scroll interop. |

### Recommendation

Unless real-device Perfetto / Instruments profiling after coalescing zero-height system rows and wiring `LazyLayoutCacheWindow` still attributes frame drops specifically to `LazyColumn`'s internal layout pass (unlikely, because both `LazyColumn` and `VirtualMessageTimeline` execute on the exact same Compose `SubcomposeLayout` / `LazyLayout` core and `Remeasurement.forceRemeasure()` path):

1. **Do NOT replace `ThreadMessagesList`'s `LazyColumn` wholesale with a custom `LazyLayout` right now.** The maintenance cost of re-implementing `LazyLayoutSemantics`, `BringIntoViewSpec`, item animations, IME tail pinning, and `ThreadShareImage` capture outweighs the marginal layout-pass savings on Android/iOS.
2. **Adopt the four high-ROI architectural wins proven by this experiment directly inside `ThreadMessagesList`:**
   - **P0 — Coalesce collapsed system-message runs in the `items()` projection** (eliminating zero-height `Box` placeholders at `ThreadMessagesList.kt:4113-4123`, matching web `buildMessageTimelineRows`).
   - **P1 — Unify mid-session focus & `ThreadFind` jumps onto synchronous pre-draw convergence + explicit `sentinelAutoLoadBlockKey` pagination barrier** (extending `ThreadInitialFocusedPlacementOwner` so search hops and message links stop flashing or running 6-frame `delay(16ms)` loops).
   - **P1 — Explicitly pin active row controllers via `LocalPinnableContainer.current?.pin()` (or hoist range state)** rather than relying implicitly on `ComposeFoundationFlags.isSelectionAutoScrollEnabled`, ensuring active selection / focus / media rows survive scroll-out deterministically and release cleanly on clear.
   - **P2 — Wire `activeListBeyondBoundsItems` to Compose 1.12.1 `LazyLayoutCacheWindow`** (or remove the dead calculation) and adopt a lightweight key-indexed height cache only if multi-viewport jump estimation (e.g., scrollbar thumb / outline jump / long-thread progress) needs exact pixel offsets before mounting.
