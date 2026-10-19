# Thread fast-scroll blank rows: the reverseLayout re-anchor case

Owner: Mahua · Verified at: 2026-10-19 · Status: current

**Scope.** This article explains the share of fast-scroll blank rows that came from reverse-layout re-anchoring, and the fix for it. It is **not** a general explanation of blank rows during a hard fling: on a build that already contains the fix, a hard fling can still blank out a comparable area through frame production alone — **narrowed but not yet explained**; see "The residual blank: what the data shows, and what it does not". See "Evidence method" for the measurement that separates the two.

Symptom fixed in this repo by PR **#3073** (Kuikly release set bump to `2.28.0-raft.13`); renderer fix upstream in KuiklyUI PR **#205**.

## Symptom

On Android, flinging a long message thread to the bottom left a large blank region (measured ~800 px) that persisted for seconds: rows had entered the viewport but their content was not painted. `kotlinFPS` dropped to ~20 during flings while `mainFPS` stayed healthy, i.e. the Compose-side (Kuikly) frame production could not keep up, not the platform renderer.

## Root cause (mechanism)

The thread list runs `LazyColumn(reverseLayout = true)` (shipped behind `mobile_thread_reverse_layout_v0`, default-on). The Kuikly fork's reverse native-scroll path anchors the native scroll offset to the **estimated** total content height. Message rows vary in height (markdown, code blocks, images), so the estimate keeps correcting mid-fling; every correction re-anchored the native offset and re-laid all placed children on the Kuikly thread. During a fling this re-anchor storm plus new-row composition blew the frame budget, so rows already inside the viewport waited multiple frames to paint.

The same mechanism, seen in one screenshot frame, also explained two companion symptoms: the top row clipped by the list's top edge and a "Back to bottom" pill shown while the latest row was already visible — both are what a content block shifted by an over-estimated anchor looks like.

## The residual blank: what the data shows, and what it does not (2026-10-19)

The Scope above says a hard fling can still blank on a build containing the fix, "through frame production alone". That was honest but unnamed, and it is **still unnamed**: a per-frame trace narrows the residual considerably, but the cause is **not** established.

### Observations (device data)

Hands `39100538`, alpha `10020002`, Huawei CMU-AL10, 1320x2848; 537 `thread_fling_frame` samples over 9 flings.

- `outOfRange` and `overscroll` were **0 throughout**, so the residual is neither over-scrolling nor "queued but never drawn".
- On one up-fling the content view held **5 rows** against an estimated content height of 9000. The native offset fell ~100 px per frame, and the hole above the viewport grew 63 → 876 → … → **1777 px (89% of a 1993 px viewport)** over about 180 ms, frames 3-12.
- Then **480 ms with no frame at all** (51.529 → 52.012). When frames resumed, the content height had become 14037 and the viewport was fully covered.
- In the same window: `jank_frame durationMs=478 route=kuikly_thread operation=thread_first_frame`, preceded by `thread_markdown_preparse scope=visible_overscan durationMs=377` and a markdown row body recompose for a 1536-character message.
- Two further flings blanked with **no jank reported** (37 consecutive frames, max 698 px; and 567 px). These are a second, separate phenomenon and must not be merged into the fling above.

### What this rules out

**Markdown parsing on the Compose thread is ruled out.** `prepareMarkdownContent` already dispatches through `SlockMarkdownParseDispatcher`, which on Android is `Dispatchers.Default.limitedParallelism(...)` (see `SlockMarkdownParseDispatcher.android.kt`). So `thread_markdown_preparse durationMs=377` is wall-clock time spent *awaiting* background parsing, not time occupying the Compose thread. "Move the parse off the main thread" is therefore not a fix for this — it is already off it.

### What remains open

The 478 ms `jank_frame` is **one frame on the Android UI thread**, and the same window contains a `thread_markdown_body_recompose` for a 1536-character message.

**What the 478 ms is:** `jank_frame` comes from JankStats, not from the FrameMetrics listener (that is a separate log). `SlockPerformanceDiagnostics.kt` reads `frameData.frameDurationUiNanos` and prints `jank_frame`; by API definition `frameDurationUiNanos` is the time this frame spent on the UI thread that owns the window. So the number is a main-thread frame, full stop.

**What `operation=thread_first_frame` is not:** `operation` is an `AtomicReference("idle")` set by `updateOperation(...)`; `jank_frame` prints whatever value it held at the moment of the jank. It records "the most recently set label", **not** what this frame was doing, and not which thread was doing it. **`operation` also has no expiry** — if nothing calls `updateOperation` again, a frame ten seconds later still prints the stale label. A reader cannot tell `operationAgeMs=3` from `operationAgeMs=9400` in the current output.

**So do not read a causal link into the co-occurrence.** "A 478 ms main-thread frame happened while the operation label read `thread_first_frame`" is supported. "`thread_first_frame` occupied the main thread for 478 ms" is not.

**What is separate and still true:** Kuikly's Compose runs on `HRContextQueueHandlerThread` (`SlockKuiklyThreading.kt`), which is **not** the Android main thread. That is a fact about where Compose runs. It does **not** mean the 478 ms was spent there — the 478 ms is a main-thread measurement, and **whether the main thread was doing the work itself or synchronously waiting on the Kuikly thread is not established.**

**Making that last question decidable** is what the next probe addresses: when the main thread stalls ≥150 ms, capture the main-thread stack and the Kuikly-thread stack from a **single `getAllStackTraces()`** call, and mark which one is merely waiting in its looper. Note that a stack sample must be read as busy-or-idle — a stack parked in `MessageQueue.nativePollOnce` means "nothing was dispatched", not "the thread was held".

### Two stalls, timing arithmetic, and what it excludes (2026-10-19)

Two long-message recomposes sit immediately before the two longest stalls in the same trace:

| # | `jank_frame` reported | duration | **implied frame start** (report − duration) | `body_recompose` log | gap |
|---|---|---|---|---|---|
| 1 | 52.047 | 478 ms | **51.569** | 51.568 (`bodyLen=1536`) | **+1 ms** |
| 2 | 53.510 | 285 ms | **53.225** | 53.184 (`bodyLen=1966`) | +41 ms |

Shorter messages in the same trace show **no frame above 110 ms**, so the pairing has a negative control.

Two separate facts, and only one of them needs the arithmetic:

**Structural (no timing needed):** Compose composition runs on Kuikly's context thread, not on the Android main thread (see `SlockKuiklyThreading.kt`). So a main-thread frame cannot be composition itself. That conclusion holds regardless of any timestamps.

**Temporal (this is what the arithmetic buys):** the stall *begins* 1 ms and 41 ms **after** that row's composition completed. `thread_markdown_body_recompose` is emitted from a `SideEffect`, which runs after composition commits, and the implied frame start follows it. So the cost is in what happens **to the composed output next** — first measure / layout / draw — not in building it, and the ordering is what rules out "the stall overlaps composition."

Keep the two apart: the structural fact is a property of the runtime; the 1 ms / 41 ms ordering is an observation about this trace.

**Metadata a reader needs to re-derive this:**

- Both lines go through the same writer (`SlockAndroidLocalDiagnostics.recordLog`) into the same JSONL with one wall-clock `ts`, recording **write time**. The two channels are structurally one clock; that is not an assumption.
- `jank_frame` is written from the JankStats callback **at frame end**, so its `ts` is the frame's end. The frame start above is `ts − frameDurationUiNanos`.
- **The derivation is an inference at ±1–2 frames, not a measurement.** There is log-write latency between the two events, and the gap itself varies (1 ms vs 41 ms), so it is not a constant.

**What it still does not say:** that the recompose or the 1536/1966-character message *caused* the stall. It locates the stall to the window after composition; the main-thread stack is what names the operation.

A candidate worth testing, from reading the fork (**unconfirmed**): Kuikly's `KRRichTextView` can re-measure an entire paragraph with `StaticLayout` on the main thread in `setShadow`/`setLayoutParams` when the layout width and the background-measured text width disagree (`KRRichTextView.kt` ~214–258). If a stack shows `initTextLayout → measureLayoutExactly`, that is this path.

**Also an inference, not an observation:** that native scrolling kept advancing during the stall. The gap is unsampled; the evidence is only the state before and after it. The current probe has no `gapSinceLastFrameMs`, so the blind spot cannot report its own width yet.

**"Rows could not be placed in time" is well supported. "Composition blocked by X" is not**, for any particular X, on this evidence.

**What this does and does not change.** It confirms the Scope's claim that the residual is layout-independent — the reverse-path fix is still the correct explanation of the ~700 px regression this article is about. It does **not** support reading the fixed-build toggle result as evidence about this residual's size. **As of 2026-10-19 no fix for the residual has landed**, so on any current build expect it to persist.

## Fix

KuiklyUI #205 defers the reverse-layout re-anchor until the scroll **settles**: out-of-bounds corrections still apply immediately, but estimate-driven corrections queue and flush once at scroll end. Mid-fling frames no longer pay the re-anchor cost.

## Evidence method (worth reusing)

- **Paired A/B on the same device and fixture**: the release build (before the fix) reproduced ~700 px blank sustained ~1 s per fling; the fixed build showed only single-frame transient blanks (≤360 px), the same magnitude as the known-good configuration (prefetch toggle off, ~210 px for one frame).
- **Hypothesis isolation by toggle, on the build before the fix**: disabling the bottom-up (reverse) layout on the *unfixed* build collapsed the blank to near-zero, pointing the investigation at the reverse path instead of markdown rendering, image loading, or the renderer release bump itself.

  The build matters, and this is easy to misread. **The same toggle on a build that already contains the fix no longer separates anything**: measured on `1.14.1` build 3 (`2.28.0-raft.13`), flinging with the toggle off still produced 626/754 rows of blank versus 767/647 with it on — the same magnitude, in both scroll directions. The reading was taken with the user setting forced off (`settings_standalone thread_reverse_layout_user_choice enabled=false`, then `thread_reverse_layout enabled=false gateReady=true`), so the layout really was forward.

  The two readings are compatible with one explanation — the fix removed the reverse-path share and the residual is something else — but they are **not the same evidence**, and neither should be read as proving the other:

  - the unfixed-build toggle shows the reverse path **was** the dominant cost there;
  - the fixed-build toggle shows the remaining blank is **independent of layout direction**.

  "The toggle stopped separating anything on a fixed build" is an observation that other explanations can also fit — for instance residual blank large enough to swamp an existing difference. **The fix being effective is established by the paired A/B in this article (same device, same fixture, ~700 px before versus single-frame ≤360 px after), not derived from the toggle losing its discriminating power.**
- **Evidence grading**: the original strict criterion (a stronger synthetic fling) could not be re-run because the input-injection rig degraded mid-testing; the result was recorded as PASS at downgraded evidence level, with the un-run stricter test listed explicitly rather than silently upgraded.
