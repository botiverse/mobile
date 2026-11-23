# Programmatic Scroll Ownership Contract

Programmatic scrolling is permitted only when the caller can name the viewport intent it owns and prove that direct user movement has not replaced that intent.

## Application-level invariant

Every automatic `scrollToItem`, `animateScrollToItem`, `scrollBy`, or app-level wrapper must have both:

1. an explicit intent owner, such as a user action, route focus request, initial route placement, key/offset restoration, or a parked feed-tail/feed-head session; and
2. a user-override fence that prevents a delayed data/layout effect from scrolling after a drag or another viewport-replacement intent.

The fence must be appropriate to the intent. A top feed commonly needs `parked + genuine top update + !isScrollInProgress`. A bottom feed needs a retained bottom-ownership state that is retired when the user leaves the tail. A delayed restore or route correction needs an identity or user-scroll generation captured when the intent began.

Do not mechanically convert every caller to one shared top-feed policy. Thread focus, older-page key/offset restoration, local send, initial placement, and keyboard correction have different ownership lifetimes.

## Required review questions

- What event created the scroll intent: direct user action, route request, initial placement, data append, layout correction, or restoration?
- Is the data change genuine for that intent, or only pagination, read-state mutation, busy-state mutation, or list replacement?
- What retires ownership: pointer movement, leaving the parked edge, a newer route nonce, another viewport-replacement action, or a bounded timeout?
- If the effect suspends, how does it prove that ownership is still current after resumption?
- Does the target include structural rows such as loading/busy sentinels?
- Is there an executable negative test covering the policy decision that a drag or irrelevant mutation does not scroll?

## Application inventory snapshot

Last mechanically audited on 2026-07-15 at
`origin/main@457ee1be23bdc46f6c985ae1fe620a1cda5bf4f6`. This table is
an evidence snapshot, not a claim that later call sites are automatically
compliant. New or changed viewport mutations must answer the review questions
above and update the inventory when they establish a new ownership pattern.

| Surface | Intent owner | Required fence / current status |
|---|---|---|
| Home server list position restore | server-position restore | user-scroll generation + active-scroll fence; task #541 |
| Home title and system scroll-to-top | direct user/system action | explicit viewport replacement; compliant |
| Home / Tasks / Members bottom-tab double-tap | direct user action | per-surface monotonic one-shot request; remount replay fenced; list animation yields to a newer user gesture |
| Activity automatic top anchor | parked top feed + genuine top update | pagination/read mutation excluded; drag fenced; compliant (#928) |
| Activity entry/refresh top anchor (task #47) | `ActivityEntryTopAnchorOwner`: panel activation (cold start, route return, foreground) or the user's pull-to-refresh, or (canonical order, stable order off) a list parked at the top following the head like a live feed, + genuine head update (`activityHasNewTopRow` / pending markers) | first observed `isScrollInProgress` retires the session; parked at index 0 offset 0 and not scrolling at decision time; same-snapshot `requestScrollToItem(0)`, no delayed job; a scrolled reader gets the “new activity” pill instead, shown only while an arrived row (new, or with new activity: `activityArrivedTopRowKeys`; a reorder alone never counts) is laid out above the first visible item (`activityNewRowsAboveViewport`); `ActivityRefreshTopAnchorTest`, `ActivityNewActivityPillLayoutTest` |
| Activity “new updates” badge | direct user action | explicit viewport replacement; compliant |
| Activity list parked at the top (Hands b205bd89; title tap, tab re-tap, "new activity" pill just animate to index 0) | root rule `LazyListState.keepTopIfParked`: no owner object, the list position itself | a list at index 0 / offset 0 and not scrolling stays at index 0 across any change that moves its head row (same-snapshot `requestScrollToItem(0)`, in every presentation mode; the canonical-order decision is separate); a scroll in progress or a reader scrolled away is never pinned; diagnostics `SlockHomeActivity` `activity_keep_top_pin` (`fromIndex` the list would have been carried to); `ActivityKeepTopIfParkedLayoutTest` |
| Activity head tap / Activity tab re-tap: unread-row walk (artin 2026-10-21); double tap: back to the top (artin 2026-10-22) | direct user action, one `SharedActivityList` request generation per tap (`ActivityUnreadJumpRequest` through `AppShellScrollToTopRequestFence`: no remount replay, inactive filter pages consume without acting); `ActivityRetapGesture` (one per tap target, tab and head; timed in shared code for the Compose bar/rail and the iOS UITabBar alike) marks the second tap within `ViewConfiguration.doubleTapTimeoutMillis` of a single tap as `toTop` — the single tap is never delayed, the second tap's newer generation cancels its still-running walk and animates to the top | target decided per tap from the live layout and a keyed walk state (`activityNextUnreadJumpTarget`; the jumped-to row is found by key, never re-issued from an old index); a settled jump records the viewport it left, so any later manual scroll restarts the walk from the viewport; may request the next page and decide again once it lands (bounded pages + timeout); `animateScrollToItem` under the scroll mutex, so a drag cancels it and its row mark; a newer tap restarts the effect; nothing is marked read; `ActivityUnreadJumpTest` (incl. single / double / slow-double tap on virtual time) |
| Settings Activity/Search/Saved titles | direct user action | explicit viewport replacement; compliant |
| Settings search result: page opened at a setting | route intent: one `SettingsSearchLocator` per opened page (`settingsSearchAnchor` + nonce in page data, never content) | a lazy page scrolls on by most of a screen until the setting's row is composed, then the row's `bringIntoView` places it and marks it located; the person's own scroll (`isScrollInProgress` between steps) ends the seek for good, bounded by 6 s, and a row that never shows puts the page back at its top; `settingsSearchSeekStep` in `RaftSettingsSearchIndexTest`, `SettingsSearchUiTest` |
| Quiver Feedback conversation tail | parked bottom feed + genuine conversation append | busy-only changes excluded; busy sentinel included; task #541 |
| Members selected tab reveal | user/route tab selection | active-scroll fence; compliant |
| Search filter strip: an applied filter kept in view (`RaftScrollIntoViewGroup`, raft-ui FitToolbar `useScrollGroup`) | initial placement: the strip's first show with filters applied (the first of them); direct user action: the filter just applied | one instant `ScrollState.scrollTo` (default priority, so a drag cancels it); a resize of that filter or of the strip re-reveals it only while `!isScrollInProgress` and the scroll is still where the last reveal left it (the person has not scrolled since); `SearchFilterStripRevealUiTest` |
| Composer @ / # suggestion list: the keyboard highlight kept in view (web `useAutocomplete` `scrollIntoView({ block: "nearest" })`; `ThreadComposerSuggestionRevealEffect`) | direct user action: the ↑ / ↓ press that moved the highlight, or the edit that put it back on the first row | runs once per highlighted row (effect keyed on it), never for a list or data change alone, so a wheel or drag scroll of the list stays until the next key; one instant `scrollBy` of the overflow, or `scrollToItem` when the row is not laid out, only when the row is not wholly in view; `ThreadComposerSuggestionKeysTest` |
| Members Agent Activity tail | parked bottom feed | leaving tail/drag retires ownership; compliant |
| Members Agent Activity Overview tail and "New turn ↓" | first placement of a loaded overview; parked bottom feed + a genuine new latest turn (finished or running key); the hint is a direct user action | `membersAgentActivityNextBottomAnchor` retires following on leaving the tail or a drag; `membersActivityOverviewNewTurnAction` follows only when parked and not scrolling, otherwise shows the hint (never moves a reader); earlier pages load above by key with no programmatic scroll; `MembersAgentActivityOverviewFormatTest` |
| Thread long-message expand/collapse anchor | direct expand/collapse action | toggled row visible + active-scroll fence; collapse yields to FollowingLatest; same-frame `requestScrollToItem` + controller `Anchor(message top)`; expanding (even the newest row) replaces FollowingLatest with that anchor, so auto-follow of new messages stops until the user returns to the bottom; compliant |
| Thread system-message group open/close (folded runs of adjacent system messages) | direct user action on the group's summary row | summary row laid out + active-scroll fence; a close yields to FollowingLatest; `keepReadingAnchorFor(summary)` (an opening while FollowingLatest first anchors the controller on the summary, so the reading anchor keeper owns it), so the opening/closing rows grow/shrink below the summary and the HeightChange replay agrees; no `requestScrollToItem` of its own; an opening then hands to the expand reveal owner below (artin dm:@artin:#151). A jump or search match into a closed group only opens it (state, once per request); the focused-message session keeps the scroll; folded rows stay lazy items (keys/indexes unchanged) and draw nothing while closed |
| Thread reading anchor: in-place height change of a laid-out row on the reversed list (Saved marker, reactions, edits, link/app cards and images resolving, expand/collapse; artin #proj-mobile #201) | `ThreadReadingAnchorOwner` / `ThreadReadingAnchorKeeper` (ThreadReadingAnchorKeeper.kt), the one owner of this case. Anchor: the viewport controller's anchored message when laid out (the row a settled drag was reading — `threadReadingAnchorMessage`, the topmost message whose top is in view — a focused message, or the row whose control was tapped: `keepReadingAnchorFor(messageId)` = `keeper.anchorOn` + controller `Anchor`, the API row actions such as Save, reactions and expand call), else the topmost row whose top is in view | owns only while not FollowingLatest (bottom stays pinned there, unchanged), no scroll in progress, list live (not retired, page not leaving), initial placement done, no share capture / adaptive restore. User-override fence: a correction is made only when the list's own scroll position (bottom row key + offset), viewport size and paddings are unchanged since the previous layout pass, so any drag, fling, jump, restore or keyboard resize re-reads the anchor instead of being undone. Runs from the list's and rows' position callbacks once per layout pass; the correction is `ThreadListViewport.scrollRawToItem` started undispatched (forced remeasure before the frame is drawn), no delayed job; the controller's HeightChange replay then targets the same row and is a no-op. Transient UI that closes on scroll (the author popover) listens to the user's drag only (`ThreadUserScrollEffect`, `DragInteraction.Start`), so a correction never closes it. `ThreadReadingAnchorUiTest` (next-frame row tops: save/unsave, reaction add/remove, late card growth, tapped row, following latest, scroll not undone; locked out for a new message and the keyboard opening while reading history; popover kept through a correction, closed by a drag); draw-level evidence is device-only |
| Thread expand reveal (`ThreadExpandRevealOwner`, diagnostics `thread_expand_reveal`; artin dm:@artin:#151): system-message group open, long-message "Show more", a system line's details | direct user expand: one `ThreadExpandRevealRequest` per tap (a newer tap replaces it, a collapse cancels it), run by `ThreadExpandRevealEffect` after the toggle made the row the reading anchor (`keepReadingAnchorFor`) | user-scroll generation captured at the tap; owner liveness (`ThreadListScrollOwnerEffect`) and live `isScrollInProgress` after each suspension; waits (bounded 1 s) for the row to change size and the controller's pending command to land; then `animateScrollBy` forward by `threadExpandRevealDelta` = min(hidden part below the viewport, room above the tapped control minus an 8dp margin), never backward; the scroll mutex lets a drag cancel it; the reading anchor keeper does not fight it (a scroll in progress and a changed scroll position both re-read its anchor instead of correcting); afterwards `keepReadingAnchorFor(row)` re-anchors keeper and controller at the row's new top, or `FollowLatest` when the reveal reached the tail; `ThreadExpandRevealUiTest` |
| Thread header, inline search, back-to-bottom | direct user action | explicit viewport replacement; compliant |
| Thread unread-thread jump pill (Next / Earliest Unread Thread) | direct user action | dispatches `SharedThreadIntent.JumpToMessage(parent)`, so the scroll and highlight are owned by the route focused-message session below (nonce identity, user-scroll generation, active-scroll fence, bounded cap); the pill itself never scrolls; that one request (its nonce is `SharedThreadState.unreadThreadJumpFocusNonce`) uses `LazyViewportAnchorPlacement.FocusedThreadReplies`, which lands the parent's bottom (replies summary) above a bottom reserve instead of top-aligning it (task #73; offset re-derived from the measured row inside the controller command, under the same command-liveness checks); `ThreadJumpPillsTest` covers the target choice, `ThreadRepliesFocusPlacementTest` the placement |
| Thread share capture | bounded capture workflow | capture session owns viewport; compliant |
| Thread older-page restore | key/offset restoration | captured/current user-scroll generation; after its layout-frame wait it also requires `scrollOwnerLive` (no restore once the page is leaving, e.g. the page landed after Back), source-reviewed; compliant |
| Thread ordinary bottom correction | parked bottom session | active-scroll + focused-owner fences; compliant |
| Thread local-send bottom session | explicit local send | anchor identity + user-scroll generation; compliant |
| Thread focused-message session | route `(messageId, nonce)` | consumed/interrupted identity, generation, active-scroll fence, bounded cap; owner lifetime (Hands 712d8386): the command effect is a `ThreadListScrollOwnerEffect` (keyed on `LocalRaftStackPageLeaving`, nothing runs on a leaving page; retires the list via `ThreadListViewportRetireOnDispose` before its job is cancelled), and after every suspension it requires `ownerLive()` (active job, list not retired, page not leaving) before its own currency checks; `ThreadListViewport` itself refuses to scroll from a cancelling job or a retired list; `ThreadFocusScrollOwnerLifetimeUiTest` composes that same `ThreadListScrollOwnerEffect` (the command body itself is source-reviewed); compliant |
| Thread initial/keyboard bottom placement | initial route or parked layout intent | drag/focus yield for initial placement; parked-tail gate for keyboard; on a reversed list the keyboard correction never replays an `Anchored` intent: tail parked at the rendered bottom retires it to `FollowingLatest` (cannot override `UserDragging`), mid-history keeps the rendered position (`resolveThreadComposerInsetViewportAction`, `ThreadComposerInsetViewportActionTest`); compliant |
| Thread list end under the typing / working line (web AgentTypingListEnd; #proj-mobile:#271) | the list's end row, the line's 30dp slot taller only while the line shows (`threadBottomSpacerAndAnchorTrailingHeight`); the reversed list keeps a reader resting on the end there by itself (the rows ride with it) and a reader in the history never sees it change, so no scroll is issued for either. When the line comes while the end row is in view (the newest message whole on screen), or goes while the end rests at the rendered bottom, under a non-following intent (opened on the newest message, a focused tail, a drag let go just above the end), that intent is retired to `FollowingLatest`, as the keyboard correction retires a parked tail, so the reading anchor keeper does not hold the old position under the line | decided once per change of the line in a `SideEffect`, after the composition and before the layout that applies the new end, from the last layout (end row found by key: in view, or at the rendered bottom within `ThreadBackToBottomTolerancePx`); only with no scroll in progress, a settled placement (initial anchor done, no share capture, no adaptive restore), the live tail loaded (`!canLoadMoreAfter`, no newer-page cycle) and never over a drag (`FollowLatest(canOverrideUserDragging = false)`); no delayed job, owner tag `typing_line_end`; its Latest command closes what is left of the gap to the end (nothing when the reader rested on it). `threadTypingLineEndFollowsLatest` (`ThreadAgentTypingTest`), `ThreadAgentTypingLineLayoutUiTest` (each change checked in the frame that lays it out and after the effects ran) |
| File preview image pager (gallery of one message's images) | the page's selected image (`RaftFilePreviewPayload.imageGalleryIndex`) when it changes without a drag | `scrollToPage` only while `!isScrollInProgress` and the pager is not already on that page; the effect re-runs when scrolling stops; a drag is driven by `raftBackSwipePaging` under `MutatePriority.UserInput` and reports its own settle (`settledPage`) back to the selection, so the user's page wins; `RaftBackSwipePagingUiTest` |
| Thread forward-layout tail anchor (IME resize / realtime append) | parked bottom feed: `FollowingLatest` and, in the previous layout, the tail flush with the viewport end or all content fitting the viewport | applied inside the measure pass that sees the shrink/append (`requestScrollToItem`), so no delayed job exists; an active newer-page cycle (load or settle restore) suppresses it for every change, like the keyboard bottom correction; `canLoadMoreAfter` only marks appends as pagination; active scroll, leaving the tail, prepend and list replacement excluded; disabled entirely under the `SuppressWhileImeOpen` kill-switch policy; reverse layout never needs it; `RaftThreadImeLayoutTest` |

Framework-internal Kuikly corrections and test/probe pages are not app-level inventory entries. Framework corrections still must preserve the same user-override invariant at their own ownership boundary.

## Framework and native-bridge offset ownership

A framework correction that changes a native scroll offset, content offset,
virtual-window base, or Compose/native offset mapping is a viewport mutation
even when no app-level `scrollToItem` API is called.

The correction owns only a temporary alignment intent. It does not own the
user's drag or fling. A non-forced alignment must yield to current user-scroll
ownership; a forced alignment has only the narrow exception defined below.

### Four framework invariants

1. **Live user scrolling wins over non-forced alignment.** A delayed
   non-forced correction must not mutate the native offset while Compose
   reports `isScrollInProgress`. This includes the native `SETTLING` interval,
   where a host such as Android may already report `isDragging == false` while
   the fling still owns the viewport. Native dragging state alone is not proof
   that user ownership ended.
2. **Force is a narrow override.** An explicitly forced correction may bypass
   the active-scroll fence. It may not bypass request-generation,
   owner-lifetime, native-view-reuse, target-validity, or geometry checks.
   “Forced” means “allowed during active scrolling,” not “allowed after the
   request became stale.”
3. **Scroll end schedules a fresh retry.** An eligible real `scrollEnd` handler
   schedules exactly one fresh non-forced alignment attempt, including after
   active scrolling suppressed earlier work. It does not resume the skipped
   coroutine or require a remembered “was suppressed” flag. The new request
   replaces pending work and repeats the normal delay, live-state, generation,
   owner, and geometry checks; unnecessary attempts become geometry no-ops.
   “Exactly once” means one retry dispatch per eligible handler invocation, not
   one native end event per physical gesture.
4. **Only the current owner generation may apply.** Each deferred request
   belongs to one coordinator generation and one current
   scroll-state/native-view binding. Replacement, explicit
   cancellation/invalidation, reset, or
   view reuse invalidates the prior generation. A disposal path must either
   perform equivalent invalidation or make the target binding unreachable
   before cancellation. Job cancellation is best effort: a late completion
   that ignores cancellation must still fail the current-generation or
   target-reachability check before any mutation.

The user-visible invariant these rules are intended to protect is stable
anchoring across structural list changes. While the first-visible stable key
remains in the current canonical projection, and no current explicit
viewport-replacement intent owns a different target, that item must retain its
item-relative viewport offset. Numeric indices and absolute native offsets may
legitimately change to compensate for rows inserted before the anchor; the
stable identity and visible position may not jump merely because a delayed
alignment job fired.

If deletion, permission loss, filtering, or another canonical projection
change removes the key, apply that canonical update and use a deterministic
successor-anchor policy appropriate to the surface, such as a retained stable
neighbor or an explicitly owned edge. Do not freeze the canonical patch or
compensate from the removed item's old numeric index.

### Execution-time revalidation

A schedule-time check is never sufficient for delayed bridge work. Every
suspension, timer, callback, or asynchronous handoff is a
time-of-check/time-of-use boundary.

Immediately after every such boundary, revalidate the minimum ownership gates
for the next mutation phase:

- the request is still the coordinator's current generation;
- the coordinator still owns the current scroll state and native view;
- live Compose `isScrollInProgress`, unless the request is forced;
- the target view has not been detached, disposed, or rebound.

Before a geometry-derived mutation, also prove that the geometry used by that
specific mutation belongs to the current owner and phase. Do not require a
later phase to recompute a content-size transaction that was intentionally
committed before its platform wait; do require the later phase to repeat the
ownership gates before any subsequent offset mutation.

The standard delayed-alignment path must read scroll progress after its wait,
not capture only the value from scheduling time. A platform-specific second
wait, such as a content-size propagation window, must repeat the
generation, binding, and live scroll/force decisions after that second wait
before its subsequent offset write.

### Coordinator and native-view lifetime

The coordinator lifetime follows the scroll owner, not merely the coroutine,
render node, or recycled native view.

- Advance the generation before cancelling a replaced request.
- Cancellation without a successor also advances the generation and clears
  the pending reference.
- Reject a stale request even when cancellation was not observed.
- On disposal, either invalidate and clear the current binding before scope
  cancellation, or prove that the target view is already unreachable. Scope
  cancellation alone is not evidence of generation fencing.
- During native-view reuse, invalidate the old coordinator and the incoming
  coordinator before binding or restoring the new owner.
- If old and incoming coordinators are the same object, invalidate it once.
- If owners differ, detach the old owner's native `scrollView` reference before
  the reused view can serve the new owner.
- Bind the new owner and restore content size/offset only after invalidation,
  so an old completion cannot mutate the newly bound view.

### Prohibited alternatives

Do not:

- use native `isDragging` as the only user-ownership signal;
- decide once at scheduling time and apply after a fixed delay without reading
  live state again;
- treat a longer delay as an ownership fence;
- let `force` bypass generation, binding, reuse, or geometry checks;
- rely on coroutine/job cancellation as the correctness boundary;
- resume the old skipped coroutine from `scrollEnd`;
- invalidate only one side of a native-view reuse boundary;
- invalidate after binding the reused view;
- retain a distinct old owner's native-view reference after reuse;
- issue a compensating app-level `scrollToItem` from a changing numeric index
  to conceal a framework ownership bug;
- freeze all list data, realtime patches, or canonical store projection for an
  entire scroll as a substitute for fixing the bridge owner;
- claim that absence of a blank frame alone proves offset ownership is correct.

The two app-level workarounds above are especially unsafe. Numeric indices are
not stable identities, so compensating scroll calls can race the same gesture
they are meant to protect. Freezing canonical updates couples product state to
one renderer's gesture lifetime, makes unread/deletion/permission state stale,
and releases a larger structural batch when scrolling ends.

### Mechanical framework review checklist

For every delayed native-offset, content-size, or virtual-window correction,
verify mechanically:

- [ ] The alignment intent and whether it is forced are explicit.
- [ ] Non-forced execution checks live Compose `isScrollInProgress`.
- [ ] Native `isDragging` is not substituted for Compose scroll progress.
- [ ] The live check occurs after the main delay and before mutation.
- [ ] Every additional platform wait repeats the current generation, current
      binding/target reachability, and live-scroll/force checks.
- [ ] Every successor receives a monotonically newer generation.
- [ ] Replacement cancels pending work and makes a non-cooperative late
      completion harmless.
- [ ] Explicit cancellation without a successor also invalidates generation.
- [ ] Disposal either invalidates generation or makes the target binding
      unreachable before scope cancellation; evidence states which boundary
      applies.
- [ ] The apply block can prove its request is still current.
- [ ] A forced request bypasses only the active-scroll test.
- [ ] One eligible `scrollEnd` handler invocation schedules one fresh retry
      path rather than resuming old work.
- [ ] A retry is non-forced unless a new explicit force intent is created; it
      does not inherit an earlier request's force flag.
- [ ] Possible duplicate native `scrollEnd` events and any required host-level
      deduplication are documented; handler-level cardinality is not described
      as gesture-level cardinality.
- [ ] Pager or other separately owned end-event paths are explicitly included
      or excluded.
- [ ] Reuse invalidates old and incoming owners before binding.
- [ ] Same-owner reuse invalidates once; distinct-owner reuse invalidates both.
- [ ] A distinct old owner loses its native-view reference.
- [ ] Stable PTR, pager, snap-animation, and programmatic-force behaviors are
      unchanged.
- [ ] Policy/coordinator tests cover live-state changes, force, stale
      completion, cancellation without successor, retry dispatch, and reuse.
- [ ] Source review verifies production wiring and mutation ordering;
      helper-only tests are not described as end-to-end integration proof.
- [ ] A deterministic bridge test drives the relevant native state transition,
      structural growth, offset-write cardinality, stale completion, and reuse.
- [ ] Real-device acceptance separately records the affected transition and
      stable first-visible identity/item-relative offset.

### Test and evidence boundary

Policy and coordinator tests establish state-machine decisions at their test
boundary. Source review establishes that production call sites are wired to
those decisions. Neither alone proves native event cardinality, scheduler
timing under device load, full render-node reuse, or device-visible anchor
stability.

Use precise evidence language:

- A post-wait policy test proves that its closure rereads live state after an
  injected suspension boundary; it does not reproduce a real timer under load.
- One injected call to a production `scrollEnd` handler proves one retry for
  that invocation; it does not prove one native event per gesture.
- A reuse helper test proves invalidation decisions at that helper; source or
  bridge integration must verify invalidation-before-bind and old-reference
  detachment in the real update path.
- A run with no observed blank reports only that no blank was seen in that
  run. It does not establish generally sufficient render supply or prove that
  the visible anchor/native offset remained correct.

Deterministic bridge coverage and real-device acceptance are separate gates
for a native state-transition incident; neither waives the other. If either is
deferred, the task and release handoff must name that missing evidence instead
of reporting it as covered. For a structural-growth fling regression, device
acceptance records the first-visible stable key, its item-relative pixel
offset, native offset/maxOffset, and the absence of a jump toward the list end.
Blank-content absence alone is not a sufficient result.

### Reference evidence for the count-growth fling race

The framework contract above was reviewed against Kuikly merge exact
`14b3a142e78be30f8c877191bb04de14cfd4a90f` (source PR #47 exact
`c67f4ee64dff147f06db0f9a77300429c4a803cc`). That implementation reads live
scroll progress after the alignment delay, limits force to the progress fence,
generation-fences stale completions, schedules a fresh retry from real
`scrollEnd`, invalidates old/incoming owners before reuse binding, detaches a
distinct old owner's native view, and repeats generation/progress checks after
the platform-specific second wait.

Its common tests cover those policy/coordinator boundaries and adversarial
scenarios. Separate source mutations made key gates fail, but neither form of
evidence claims to reproduce a physical Android Pin/add during `SETTLING` or to
prove first-visible key/pixel stability. This evidence snapshot also does not
claim a generation-advancing production disposal hook; disposal must satisfy
the conditional invalidation-or-unreachable boundary when implemented or
reviewed.

The Android deterministic bridge transition seam (task #291) is post-merge CI
hardening and does not waive task #282/task #540's physical Alpha gate. The
physical gate likewise does not complete the deterministic bridge task. They
are separate evidence gates with separate owners and completion records.

## Minimum regression matrix for data-driven feed anchors

An executable policy test should cover at least these decisions at its policy
boundary:

1. genuine edge append while parked scrolls to the correct structural target;
2. pagination or an irrelevant mutation does not scroll;
3. the same genuine append during direct user movement does not scroll; and
4. leaving the parked edge retires ownership until the user returns to it.
