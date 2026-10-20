---
name: raft-bug-triage
description: Use when triaging a Raft Mobile bug report — stale or missing realtime updates, a page showing the wrong conversation/data after open or resume, unexpected or jumping scroll, or "is the fix in the build the user has". Gives the first files, contracts and evidence to check for each symptom class.
---

# Raft Mobile bug triage

Start from evidence, not from the latest code. Pin the build first, then pick
the symptom class.

## 1. Pin the build and its source

- From a feedback ticket: read `raft-diagnostics-summary.txt`
  (Version / Build / Source commit) — see `raft-feedback-diagnostics`.
- Is fix X in that build? Resolve the build's exact source commit and test
  containment; the version string is not enough (release branches do not
  follow `main`):
  ```bash
  git fetch origin
  git merge-base --is-ancestor <fix-commit> <build-source-commit> && echo contained
  ```
  For squash-merged PRs check the squash commit, or `git log <source> --grep '#<PR>'`.
  Detail: `docs/kb/release-provenance-containment.md`.

## 2. Realtime: update missing, late or duplicated

1. Confirm the event is subscribed on that platform (Android
   `RealtimeSocketEvents.ServerWireEvents`; iOS `serverWireEvents` in
   `compose/iosApp/iosApp/RaftIosRealtimeHost.swift`) and mapped in
   `RealtimeEventMapper.kt`. Checklist: `raft-realtime-event`.
2. Only the current server holds a socket; events for other servers are
   expected to be absent (`docs/kb/mobile-server-switch-realtime-scope.md`).
3. Gaps/resume: behaviour is defined by Thread gap recovery v1 and realtime
   foundation v1.2 in `docs/realtime-consistency-contract-2026-07-14.md`.
   Missing events after background are recovered by resume/reconcile, not by
   trusting the socket.
4. In logs filter `realtime_domain_accept*` (`SlockRealtimeDomain` tag) and
   compare with the server emit time.
5. A fix must route through the Store reducer; never patch UI state from the
   socket callback or freeze the list for the scroll duration.

## 3. Wrong / stale content after open, back or resume

Treat in-memory cache, durable cache, realtime payloads, sparse responses and
legacy fallback context as **candidate snapshots**, not canonical state
(AGENTS.md "Route / PageData MVI Contract Rule"):

- Check the snapshot was matched to the current route identity (server id,
  channel id, thread stream id, parent channel id + parent message id) before
  use. A mismatch must be discarded and reloaded through MVI.
- A matching snapshot may only fill missing fields; it must never override
  canonical store state.
- Route/launch/pageData carry identity and intent only. If product content
  travels in a launch payload (e.g. `WireThreadLaunchPayload`), that is the bug.
- Thread opened on the wrong surface: in diagnostics, `thread_start` with
  `surface=channel` and empty `parentMessageId` instead of `surface=thread`.
- Selector over-subscription (unrelated slice causes recompute) violates the
  Shared MVI contract in AGENTS.md; check `distinctUntilChanged` gates.

## 4. Scroll jumps, fights the user, or lands wrong

Read `docs/programmatic-scroll-ownership-contract.md` before changing any
`scrollToItem` / `animateScrollToItem` / `scrollBy`, restore, pager snap or
native offset correction.

- Name the intent owner (user action, route focus, initial placement,
  restore, parked feed edge) and its user-override fence.
- Ask the contract's "Required review questions"; for framework/native
  corrections apply its "Mechanical framework review checklist".
- Native `isDragging == false` is not enough during a fling; check Compose
  `isScrollInProgress`. Force bypasses only the active-scroll fence.
- Do not compensate with a changing-index `scrollToItem`.
- Fast-scroll blank rows on Android: `docs/kb/android-thread-scroll-blank-raft13.md`
  (one cause fixed; a residual is still unexplained — don't claim it).

## 5. Report

State the evidence tier and what you did not verify, in the same sentence
(`docs/kb/declaring-your-verification-ceiling.md`); a fixture screenshot is
not device evidence (`docs/kb/mobile-visual-evidence-ceiling.md`).
