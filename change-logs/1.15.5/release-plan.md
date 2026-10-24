# 1.15.5 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Intent (artin via Lagoon, 2026-10-24 03:36Z): test builds first for artin, then release.
  - Android: Hands `preview` and `main`, one APK per channel (SOP 9c); `main` only after artin's approval.
  - iOS: TestFlight internal first; external Insiders after artin's approval.
  - No Google Play, no App Store.

## Coordinates

- Branch `release/1.15.5` cut from `main` at `07ee5699a` (#3641). Later `main` merges are not in this release (for example #3648, #3650).
- Fixes merged into the branch afterwards: #3658 (iOS plain-logo launch screen), #3660 (weak-network image open, iOS stuck-connection recovery), #3663 (WebView warm-up after first frame), plus changeset PRs #3656 and #3659.
- Final source: `221a2435`. Tag `v1.15.5` → `221a2435`.

## Notes

- `android/` and `ios/` in zh-CN, en and ja, written from `changesets/` and reviewed by Lagoon (artin's edits: read mark applies to agents only; attachment-comments item left out; "agent is typing" kept).
- iOS external What to Test covers everything since the last external build (1.15.3), so it adds a 1.15.4 section (SOP 9b).

## Shipped (2026-10-24)

- First test round from `07ee5699` (Android 11505001 preview / 11505002 main, iOS 11505002) was superseded after images failed to open on both platforms. The two Android drafts were cancelled. 11505001's first CI run hit a Hands 503 and left an orphan pending build.
- Final round from `221a2435`, released after artin's approval (via Lagoon, 06:41Z):
  - Android `main`: 11505004 (`hands_channel=main`), active 100%. CI run 40067426286.
  - Android `preview`: 11505003 (`hands_channel=preview`), active 100%. CI run 40067423586.
  - iOS 11505003: TestFlight internal Botiverse, then external Insiders submitted for beta review with tester notification. CI run 40067429166.
- The channel guard from #3564 was run by hand before both Android publishes: both passed, and a deliberate preview-build-to-main pairing was refused.
- Announcement: #raft-mobile, Android only. The iOS announcement follows beta approval.

## Exceptions

None.
