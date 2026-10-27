# 1.16.1 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Patch on `release/1.16.0` for two action-card fixes (#3720, #3739, via #3755). Requested by Lagoon 2026-10-27 05:38Z; approved by artin 06:35Z after Lagoon's Android smoke.
  - Android: Hands `main` only (no preview build for this patch).
  - iOS: TestFlight internal, then external Insiders.
  - No Google Play, no App Store.

## Coordinates

- Branch `release/1.16.0` at `37ac0469` (#3755 on top of `cf1bd7f1`). Tag `v1.16.1` → `37ac0469`.
- Same commit for both platforms. Android 1.16.0 was built from `bdc30c9f`, so Android 1.16.1 is the first Android build with the crash fixes from #3730 (#3725, #3727); iOS already shipped them in 1.16.0 (11600003).

## Notes

- `android/`: the action-card fix plus the two crash fixes. `ios/`: the action-card fix only.
- iOS What to Test = the iOS notes without the section emoji (App Store Connect rejects it).

## Shipped (2026-10-27)

- Android `main`: 11601001, release `c7201f96`, active, full scope. CI run 40457522125. Published with `scripts/finalize_android_hands_release.sh` (Hands CLI 0.5.32); channel guard passed. First real `release:published` notification for raft-android arrived in #proj-mobile-release.
- iOS 11601001: CI run 40457546921, TestFlight internal Botiverse, then external Insiders submitted for beta review with tester notification.
