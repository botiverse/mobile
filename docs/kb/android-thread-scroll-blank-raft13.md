# Thread fast-scroll blank rows: the reverseLayout re-anchor case

Owner: Mahua · Verified at: 2026-10-18 · Status: current

**Scope.** This article explains the share of fast-scroll blank rows that came from reverse-layout re-anchoring, and the fix for it. It is **not** a general explanation of blank rows during a hard fling: on a build that already contains the fix, a hard fling can still blank out a comparable area through frame production alone. See "Evidence method" for the measurement that separates the two.

Symptom fixed in this repo by PR **#3073** (Kuikly release set bump to `2.28.0-raft.13`); renderer fix upstream in KuiklyUI PR **#205**.

## Symptom

On Android, flinging a long message thread to the bottom left a large blank region (measured ~800 px) that persisted for seconds: rows had entered the viewport but their content was not painted. `kotlinFPS` dropped to ~20 during flings while `mainFPS` stayed healthy, i.e. the Compose-side (Kuikly) frame production could not keep up, not the platform renderer.

## Root cause (mechanism)

The thread list runs `LazyColumn(reverseLayout = true)` (shipped behind `mobile_thread_reverse_layout_v0`, default-on). The Kuikly fork's reverse native-scroll path anchors the native scroll offset to the **estimated** total content height. Message rows vary in height (markdown, code blocks, images), so the estimate keeps correcting mid-fling; every correction re-anchored the native offset and re-laid all placed children on the Kuikly thread. During a fling this re-anchor storm plus new-row composition blew the frame budget, so rows already inside the viewport waited multiple frames to paint.

The same mechanism, seen in one screenshot frame, also explained two companion symptoms: the top row clipped by the list's top edge and a "Back to bottom" pill shown while the latest row was already visible — both are what a content block shifted by an over-estimated anchor looks like.

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
