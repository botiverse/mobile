# Thread fast-scroll blank rows: the reverseLayout re-anchor case

Owner: Mahua · Verified at: 2026-10-18 · Status: current

**Scope.** This article explains the share of fast-scroll blank rows that came from reverse-layout re-anchoring, and the fix for it. It is **not** a general explanation of blank rows during a hard fling: on a build that already contains the fix, a hard fling can still blank out a comparable area through frame production alone — **that residual is now identified** (Markdown pre-parse blocking the Kotlin thread; see "The residual blank, now identified"). See "Evidence method" for the measurement that separates the two.

Symptom fixed in this repo by PR **#3073** (Kuikly release set bump to `2.28.0-raft.13`); renderer fix upstream in KuiklyUI PR **#205**.

## Symptom

On Android, flinging a long message thread to the bottom left a large blank region (measured ~800 px) that persisted for seconds: rows had entered the viewport but their content was not painted. `kotlinFPS` dropped to ~20 during flings while `mainFPS` stayed healthy, i.e. the Compose-side (Kuikly) frame production could not keep up, not the platform renderer.

## Root cause (mechanism)

The thread list runs `LazyColumn(reverseLayout = true)` (shipped behind `mobile_thread_reverse_layout_v0`, default-on). The Kuikly fork's reverse native-scroll path anchors the native scroll offset to the **estimated** total content height. Message rows vary in height (markdown, code blocks, images), so the estimate keeps correcting mid-fling; every correction re-anchored the native offset and re-laid all placed children on the Kuikly thread. During a fling this re-anchor storm plus new-row composition blew the frame budget, so rows already inside the viewport waited multiple frames to paint.

The same mechanism, seen in one screenshot frame, also explained two companion symptoms: the top row clipped by the list's top edge and a "Back to bottom" pill shown while the latest row was already visible — both are what a content block shifted by an over-estimated anchor looks like.

## The residual blank, now identified (2026-10-19)

The Scope above says a hard fling can still blank on a build containing the fix, "through frame production alone". That was honest but unnamed. A per-frame trace from a real device (Hands `39100538`, alpha `10020002`, Huawei CMU-AL10, 1320x2848; 537 `thread_fling_frame` samples over 9 flings) names it, and it is **not** the reverse path:

- `outOfRange` and `overscroll` were **0 throughout**, so the residual is neither over-scrolling nor "queued but never drawn".
- On one up-fling the content view held **5 rows** against an estimated content height of 9000. The native offset fell ~100 px per frame, and the hole above the viewport grew 63 → 876 → … → **1777 px (89% of a 1993 px viewport)** over about 180 ms, frames 3-12.
- Then **480 ms with no frame at all** (51.529 → 52.012). When frames resumed, the content height had become 14037 and the viewport was fully covered.
- In the same window: `jank_frame durationMs=478 route=kuikly_thread operation=thread_first_frame`, immediately preceded by `thread_markdown_preparse scope=visible_overscan durationMs=377` and a markdown re-layout for a 1536-character message.

**Mechanism: the Kotlin thread was blocked ~480 ms by Markdown pre-parse and first-frame composition.** Native scrolling kept advancing during the stall, new rows could not be placed, and the viewport showed what was underneath — blank. Once the thread recovered, the rows arrived in one batch. So this is **"rows could not be placed in time"**, not "rows failed to paint".

Two other flings on the same trace also blanked (37 consecutive frames, max 698 px; and 567 px), so it is not a single outlier.

**What this does and does not change.** It confirms the Scope's claim that the residual is layout-independent — the reverse-path fix is still the correct explanation of the ~700 px regression this article is about. It does **not** support reading the fixed-build toggle result as evidence about this residual's size. Fix direction for this mechanism (moving `visible_overscan` pre-parse off the scroll-critical path, more row prefetch) is tracked separately; **as of 2026-10-19 no fix for it has landed**, so on any current build expect the residual to persist.

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
