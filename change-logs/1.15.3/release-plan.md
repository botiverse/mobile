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
