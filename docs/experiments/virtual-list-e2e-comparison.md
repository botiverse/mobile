# VirtualMessageTimeline End-to-End Experiment Report (`#proj-mobile:#268`)

- **Branch (non-merged experiment):** `exp/reef/virtual-list-e2e` (based on `main @ f685281055`)
- **Follow-up to:** `#proj-mobile:#259` (synthetic `LazyLayout` prototype)
- **Verified CI Run:** GitHub Actions `Test` run `45100855302` (`scope=pr`, `record_screenshots=true`, `android_unit_scope=all`, `run_ios=false` — zero macOS runner cost)
- **Test Results:** **9,128 / 9,128 passed, 0 failed** (`:shared:testDebugUnitTest` 8,275 tests / 0 failed, `:androidApp:testDebugUnitTest` 357 / 0, `:designsystem:desktopTest` 437 / 0, `:realtime-socket` 36 / 0, `:ui-cmp` 21 / 0, `:core` 2 / 0)
- **JVM Method Size Gate:** Passed (`ThreadMessagesListContent` = **26,524 bytes**, budget 51,200 bytes)

---

## 1. What Was Built

`VirtualMessageTimeline` (`LazyLayout` + persistent key-indexed Fenwick height cache + forward-ordered coalesced row projection + pre-draw single-pass jump & tail convergence + off-viewport pinning + native Compose scroll/fling physics with zero iOS bounce emulation on Android) was wired directly into the **real production `ThreadMessagesList`** behind `LocalThreadUseVirtualTimeline`:

- `LocalThreadUseVirtualTimeline = false` (baseline): existing `LazyColumn(reverseLayout = true, verticalArrangement = Arrangement.Bottom)` — behavior preserved 100%.
- `LocalThreadUseVirtualTimeline = true` (experimental): `VirtualMessageTimeline` sharing the **exact same production item composables** (`ThreadOriginCard`, `ThreadMessageCard`, `ThreadSystemMessageGroupHeader`, `ThreadSystemMessageRow`, `ThreadDateDivider`, `ThreadTimelineGapRow`, `ThreadTimeRevealRow`, `ThreadHistoryPaywall`, `ThreadNewerMessagesBoundaryRow`, `ThreadAgentTyping` bottom spacer, long-press/quick-reaction/task menus, in-place selection, share-capture bounds, etc.).

### Core Files Added / Wired
- `compose/shared/src/commonMain/kotlin/build/raft/app/thread/experiment/VirtualMessageTimeline.kt` (220 LOC)
- `compose/shared/src/commonMain/kotlin/build/raft/app/thread/experiment/VirtualMessageTimelineState.kt` (1,165 LOC)
- `compose/shared/src/commonMain/kotlin/build/raft/app/thread/experiment/VirtualTimelineHeightIndex.kt` (222 LOC)
- `compose/shared/src/commonMain/kotlin/build/raft/app/thread/experiment/ThreadVirtualRowProjection.kt` (302 LOC)
- Production integration touchpoints: `ThreadListViewport.kt`, `ThreadViewportScroll.kt`, `ThreadMessagesList.kt`, `ThreadMessagesListOverlays.kt`, `ThreadReadingAnchorKeeper.kt`, `ThreadHeaderOriginalTitle.kt`, `ThreadOpenTraceRecorder.kt`, `ThreadInitialFocusedPlacement.kt`, `RaftThreadImeLayout.kt`.
- Verification suites:
  - `compose/shared/src/commonTest/kotlin/build/raft/app/thread/experiment/ThreadVirtualRowProjectionTest.kt`
  - `compose/shared/src/commonTest/kotlin/build/raft/app/thread/experiment/VirtualTimelineCoreUnitTest.kt`
  - `compose/shared/src/androidUnitTest/kotlin/build/raft/app/ThreadVirtualTimelineE2eComparisonUiTest.kt` (7 real-`ThreadMessagesList` side-by-side Robolectric E2E scenarios)
  - `compose/shared/src/androidUnitTest/kotlin/build/raft/app/VirtualMessageTimelineComparisonUiTest.kt` (4 synthetic benchmarks from `#259`, all passing with `0` parent recompositions and `0` duplicate row recompositions during 25-step scrolling)

---

## 2. Side-by-Side Empirical Results (Real Production `ThreadMessagesList`)

All numbers below were measured directly in GitHub Actions run `45100855302` (`shared-home-screenshots/thread-screenshots/virtual-timeline-e2e-metrics.txt`) running the exact same real `ThreadMessagesList` and payloads on `LazyColumn(reverseLayout = true)` vs `VirtualMessageTimeline`.

### E2E-1: Cold Bottom Open + `LocalThreadAgentTyping` Overlay + 3 Realtime Appends

| Engine | InitRetries | OpenScrolls | After3AppendsScrolls | TypingClear | TypingShiftPx | VirtualMeasurePasses |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `LazyColumn(reverse)` | 0 | 0 | 0 | `true` | `0.0` | N/A |
| `VirtualMessageTimeline` | 0 | 1 | 4 | `true` | `0.0` | 15 |

*Note:* Both engines keep the newest message clear of the typing line with `0.0px` vertical shift and `0` initial-anchor retries. `VirtualMessageTimeline` recorded `4` `issuedScrolls` only because the coexisting `LazyViewportController` still emits `FollowLatest` commands on open and on each `ContentMutated(Append)` even though `followBottom = true` had already pinned the tail in-pass.

### E2E-2: Back-to-Bottom Jump Over Real `SharedThreadStore` + Delayed `TailApi` (`m25 -> m130`)

| Engine | LandedOnTail130 | IssuedScrolls | VirtualMeasurePasses |
| --- | ---: | ---: | ---: |
| `LazyColumn(reverse)` | `true` | 2 | N/A |
| `VirtualMessageTimeline` | `true` | 2 | 9 |

### E2E-3: 120 Real Heterogeneous Rows (Markdown / Code Blocks / Short Chat) — Cold Focus (`m20`) + 2 Mid-Session Jumps (`m85`, `m12`)

| Engine | Frame1Vis | Frame1Off(px) | Settled(m20/m85/m12) | IssuedScrolls | LoadOlder | VirtualPasses |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `LazyColumn(reverse)` | `true` | 0 | `0/0/0` | 5 | 0 | N/A |
| `VirtualMessageTimeline` | `true` | 0 | `0/0/0` | **3** | 0 | 11 |

*Note:* `VirtualMessageTimeline` executes exactly 1 jump per target (`3` vs `5` multi-step `scrollToItem` + `scrollBy` pairs on reversed `LazyColumn`) with `0px` Frame-1 error and `0` unintended `loadOlder` triggers.

### E2E-4: 15 Chat + 100 Consecutive Collapsed System Messages + 15 Chat (130 Source Messages)

| Engine | CollapsedItems | PlaceholdersEliminated | ReadTail | ExpandedItems | FoldedJumpAutoExpand |
| --- | ---: | ---: | ---: | ---: | ---: |
| `LazyColumn(reverse)` | 132 | 0 | 129 | 132 | `true` |
| `VirtualMessageTimeline` | **33** | **99** | 129 | 132 | `true` |

*Note:* Eliminates 99 zero-height `Box` placeholder items (**75% fewer layout items**) while preserving exact `onVisibleReadTailChange` (`129`), interactive toggle expansion, and automatic group expansion when jumping directly to a folded member (`sys65`).

### E2E-5: Long-Press Menu Survival (Realtime Edit + Append) + Top-Edge (`scrollOffset = 0`) & Mid-History (`m45`) Older Prepends

| Engine | MenuSurvived | TopEdgeDrift(px) | MidHistDrift(px) | ManualOlderRestoreScrolls |
| --- | ---: | ---: | ---: | ---: |
| `LazyColumn(reverse)` | `true` | 0 | 0 | 2 |
| `VirtualMessageTimeline` | `true` | 0 | 0 | **0** |

*Note:* Both keep the open long-press menu intact across realtime edit/append and hold `0px` anchor drift on both top-edge and mid-history prepends. `VirtualMessageTimeline` does this natively in-pass via Fenwick height compensation (`0` external `thread_older_anchor_restore` coroutine scrolls vs `2` on `LazyColumn`).

### E2E-6: IME Viewport Shrink (`700dp -> 400dp`) — Bottom Tail Pinning vs Scrolled-Up History (`m15`)

| Engine | BottomTailPinned | HistoryRowPreserved | HistoryYankedToBottom |
| --- | ---: | ---: | ---: |
| `LazyColumn(reverse)` | `true` | **`false`** | `false` |
| `VirtualMessageTimeline` | `true` | **`true`** | `false` |

*Key architectural difference uncovered by E2E:* On `LazyColumn(reverseLayout = true)`, the viewport anchors to the **bottom** edge, so shrinking `700dp -> 400dp` slices 300dp off the top and pushes a top-anchored reading row (`m15`) off-screen. On forward `VirtualMessageTimeline` with `followBottom`, shrinking `700dp -> 400dp` keeps `m40` pinned flush at the bottom when at the tail (`followBottom = true`) **and** keeps `m15` pinned at the top when reading history (`followBottom = false`).

### E2E-7: Reply Thread `ThreadOriginCard` + Multi-Day `ThreadDateDivider` + `ThreadHeaderOriginalReporter` + Leftward `ThreadTimeReveal` Swipe

| Engine | OriginOffAtBottom | OriginVisibleAtTop | TimeRevealShown | TimeRevealReset |
| --- | ---: | ---: | ---: | ---: |
| `LazyColumn(reverse)` | `true` | `true` | `true` | `true` |
| `VirtualMessageTimeline` | `true` | `true` | `true` | `true` |

---

## 3. Code Complexity Trade-Offs

- **Bypassed `LazyColumn` workaround layers (~1,100–1,200 LOC):**
  - `ThreadReadingAnchorKeeper.kt` (251 LOC): `onGloballyPositioned` reversed-layout counter-scroll.
  - `ThreadInitialFocusedPlacement.kt` (153 LOC): pre-first-draw reversed-layout focus patch.
  - `RaftThreadImeLayout.kt` (376 LOC): `raftThreadTailLayoutAnchor` forward IME/tail layout hack.
  - `ReversedThreadLayoutInfo`, `reversedThreadListIndex`, `reversedThreadListItemOffset`, and 6-iteration `delay(16ms)` focus/bottom convergence loops across `ThreadListViewport.kt` (412 LOC) and `ThreadViewportScroll.kt` (816 LOC).
- **Added virtual engine code (1,909 LOC core):**
  - `VirtualMessageTimeline.kt` (220) + `VirtualMessageTimelineState.kt` (1,165) + `VirtualTimelineHeightIndex.kt` (222) + `ThreadVirtualRowProjection.kt` (302).
  - Net complexity only shrinks meaningfully if `LazyColumn` is eventually **replaced** (allowing the ~1,200 LOC workaround layer and redundant `LazyViewportController` `FollowLatest` / `OlderRestore` code to be deleted) rather than maintaining both engines permanently.

---

## 4. Real Edge Cases & Costs Surfaced During E2E Wiring

Wiring into real `ThreadMessagesList` caught 5 concrete engineering traps that synthetic tests alone missed:

1. **Dynamic row-count growth & coordinate-space isolation:** Omitting folded system messages means `totalRowCount` jumps from `33 -> 132` when a group expands (unlike `LazyColumn`'s fixed placeholder slots). Every index consumer (`canScrollToThreadItem`, `ThreadExpandReveal`, `shareSelectionCapture`, A4 adaptive fallback, `visibleReadTailMessageIndex`, `visibleMessageIndexRange`, markdown preparse) must read `ThreadVirtualRowProjection` and `ThreadListViewport.itemCount` (synced before measure), never falling through to `renderPayload.messages` indices.
2. **`SubcomposeLayout` (`BoxWithConstraints`) re-entrancy:** Calling `remeasurement.forceRemeasure()` inside a `SideEffect` (`updatePinnedKeys`) inside `BoxWithConstraints` risks violating Compose's `!duringMeasureLayout` invariant. Composition-phase invalidations must bump `measurementInvalidator` (`mutableIntStateOf`), reserving `forceRemeasure()` for input/coroutine scroll & jump calls.
3. **Cold overscan over-composition:** On cold opens with 72dp estimates over heterogeneous `48dp..420dp` rows, `visibleRange` initially requested 15 items when 6 already filled viewport + overscan; without early-stop during `measure()`, rows `6..14` were composed, immediately disposed on the first scroll step, and recomposed later. Early-stop trimmed cold measure cleanly and restored `0` duplicate row recompositions.
4. **Prepend vs append anchor selection (`isAnchorable`):** Anchoring on the first visible item naively locked onto stationary `HistoryBoundary`/`Origin` on top-edge prepends; anchoring on the first index-shifted item wrongly locked onto `BottomSpacer` during realtime appends when slightly scrolled up. Introducing `isAnchorable = { row is ThreadVirtualRow.Message }` resolved all three cases cleanly.
5. **Coexistence overhead with `LazyViewportController`:** Keeping both backends behind a single `CompositionLocal` leaves redundant `FollowLatest` command dispatches on appends (`E2E-1`: 4 vs 0) and allocates both states unless guarded.

---

## 5. What Robolectric Still Cannot Prove (Next Step)

Robolectric validates geometry, state machines, semantics, and zero-recomposition invariants, but does **not** prove:
- Real-device 60/120Hz fling smoothness (`gfxinfo` jank / frame pacing) under rapid scrolling with heavy Markdown and images.
- Visual smoothness during async image decode height changes (Coil) and live long-message expand/collapse.
- Real touch interaction of Compose `SelectionContainer` drag handles when a selected message is pinned off-viewport.
- iOS UIKit interop (`keyboardDismissModeInteractiveIOS`, status-bar scroll-to-top, overscroll stretch feel).

### Recommendation
1. **Do not merge into `main` for 1.29.**
2. **Test real-device feel first** using an `optimized=true` R8 `.alpha` APK built from `exp/reef/virtual-list-e2e` (with `LocalThreadUseVirtualTimeline` enabled in `RaftCmpActivity` on this experimental branch only).
3. If real-device fling, async media, and selection feel match or beat `LazyColumn`, pursue a phased production migration (Phase 1: feature-flagged rollout with open-trace/jank telemetry; Phase 2: remove `reverseLayout` workaround files and simplify `LazyViewportController` once stable).
