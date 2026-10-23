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

## Shipped (2026-10-23)

- `release/1.15.4` cut at `6c74574` (includes #3554). sindresorhus's changesets had not arrived; Lagoon listed his user-visible changes for the notes instead.
- Android 1.15.4:
  - `preview`: 11504001 (built with `hands_channel=preview`), published ~05:30Z. CI run 39904890700.
  - `main`: 11504002 (rebuilt with `hands_channel=main`), active ~05:48Z after artin's approval (via Lagoon, 05:34Z). CI run 39906586095. R8 mapping attached to both builds.
- iOS 1.15.4 (11504001): TestFlight internal Botiverse, What to Test reset to the final notes. External waits for artin after he tests (1.15.3 external was approved 2026-10-23 04:55Z, so 1.15.4 may go external under SOP 9b).
- Tag `v1.15.4` → `6c74574`. Notes: `android/` and `ios/` (Lagoon's edits: read receipts and joint-channel avatars left out, server-gated). Announcement: #raft-mobile, Android only.

## Exceptions

1. **Android update channel.** 1.15.3 (11503001) was built for `preview` and the same APK was published to `main`, so 1.15.3 installs poll `preview` and received 1.15.4 11504001. 1.15.4 `main` was rebuilt as 11504002 with `hands_channel=main`. The ~3 active 1.15.3 devices stay on `preview` until they install a `main` build (Lagoon, option B). SOP 9c added (#3562).
