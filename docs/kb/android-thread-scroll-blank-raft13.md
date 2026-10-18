# Thread fast-scroll blank rows: the reverseLayout re-anchor case

Owner: Mahua · Verified at: 2026-10-18 · Status: current

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
- **Hypothesis isolation by toggle**: disabling the bottom-up (reverse) layout on the unfixed build collapsed the blank to near-zero, pointing the investigation at the reverse path instead of markdown rendering, image loading, or the renderer release bump itself.
- **Evidence grading**: the original strict criterion (a stronger synthetic fling) could not be re-run because the input-injection rig degraded mid-testing; the result was recorded as PASS at downgraded evidence level, with the un-run stricter test listed explicitly rather than silently upgraded.
