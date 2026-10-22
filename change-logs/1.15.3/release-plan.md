# 1.15.3 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Intent (artin via Lagoon, 2026-10-22 13:22Z): internal builds first.
  - Android: Hands `preview` published so artin can install it; `main` untouched.
  - iOS: TestFlight internal group Botiverse only; no external testing, no App Store review.
- Hands `main`, TestFlight external and Google Play each wait for artin's approval after he verifies.

## Coordinates

- Branch `release/1.15.3` cut from `main` at `eb9ac9004`.
- Android versionCode starts at `11503001`. iOS 1.15.3, build numbers from 1.
- Android and iOS are built from the same source.

## Notes

- `android/` and `ios/` (same text), from Lagoon's final zh-CN plus HanXin's four additions (#3482, #3486, #3500, #3508).
- Left out on purpose: read receipts (#3510, needs the server flag) and the Share usage data change (#3475, minor settings tweak).
- Not in 1.15.3: Activity reply deep link (#3506), still waiting for device verification.

## Exceptions

None yet.

## Shipped (2026-10-22)

- Owner: artin tested the preview build and approved public release ("发布吧", via Lagoon, 15:24Z).
- Android 1.15.3 (11503001): Hands `preview` published 14:57Z; Hands `main` active 15:28Z. Same APK on both channels (SHA-256 `3f04ce8c…`), R8 mapping attached to both builds. CI run 39786608290.
- iOS 1.15.3 (11503001): TestFlight internal Botiverse 14:5xZ; external group Insiders submitted for beta review 15:2xZ (waiting for review). What to Test also lists the 1.15.2 items, since external testers were still on 1.15.1. CI run 39786615805.
- Google Play: not pushed. App Store: not submitted.
- Tag `v1.15.3` → `eb9ac9004`.
- Notes: "下载带进度" removed in all three languages after artin saw no progress bar on device; investigate in the next release.
- Announcements: #raft-mobile (zh, en), linking the fixed latest page.
