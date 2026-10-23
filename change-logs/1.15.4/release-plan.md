# 1.15.4 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Intent (artin via Lagoon, 2026-10-23 04:11Z):
  - Android: Hands `preview` first so artin can test; `main` only after his approval.
  - iOS: TestFlight internal group Botiverse only. No external submission: the
    1.15.3 external review is still pending and stays as is (SOP 9b).
  - Community announcement: Android 1.15.4 only.
- Google Play, App Store and iOS external each need the owner's approval.

## Scope

- `main` after 1.15.3: the PRs merged since `eb9ac9004`.
- Waiting before the branch cut: sindresorhus's changesets for his merged PRs,
  and #3554 (read receipt shows a check mark) if its full test run is green by
  2026-10-23 05:15Z; otherwise it moves to the next release.
- This release carries `device_class`; Lagoon notifies Nova when it ships.

## Coordinates

- Branch `release/1.15.4` cut from `main` at the commit recorded below.
- Android versionCode starts at `11504001`. iOS 1.15.4, build numbers from 1.
- Android and iOS are built from the same source.

## Notes

- `android/` and `ios/` in zh-CN, en and ja, from `changesets/`, reviewed by Lagoon.

## Exceptions

None yet.
