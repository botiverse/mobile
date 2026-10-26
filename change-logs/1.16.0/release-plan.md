# 1.16.0 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Scope set by Lagoon (2026-10-26 04:49Z); cut after the last scoped PRs merged (#3676, #3706, #3713, #3717). #3721 deferred to the next version.
  - Android: Hands `preview` and `main`, one APK per channel; `main` only after artin's approval.
  - iOS: TestFlight internal first; external Insiders after artin's approval.
  - No Google Play, no App Store.

## Coordinates

- Branch `release/1.16.0` cut from `main` at `bdc30c9f` (#3717).
- Merged into the branch afterwards:
  - #3723 (`8d5f6063`, iOS release CI only): strip Associated Domains with PlistBuddy; `plutil -remove` read the dotted key as a path and failed every archive since #3680. Same fix for `main`: #3724.
  - #3730 (`cf1bd7f1`): cherry-picks of two iOS crash fixes requested by artin after 1.16.0 Android shipped: #3725 (opening a message with inline images under a larger Dynamic Type size) and #3727 (editing a name in a dialog). Both are already on `main`, so Android gets them in 1.17.0.
- Commits per platform:
  - Android (both channels): `bdc30c9f`. Android was not rebuilt after #3723 or #3730.
  - iOS 11600003 (the build in both TestFlight groups): `cf1bd7f1` = `bdc30c9f` + #3723 + #3730.
- Tag `v1.16.0` → `bdc30c9f`, i.e. the Android build. The iOS build commit is `cf1bd7f1`; it has no tag of its own.

## Notes

- `android/` and `ios/` in zh-CN, en and ja. There were no `changesets/` for 1.16.0, so the notes were written from the merged PRs and reviewed by Lagoon and artin.
- Left out on purpose:
  - Features still behind a flag or only visible where an App is installed (artin, 06:33Z): message retraction (delegated sends only), joint-channel direct connect and peer avatars, agent migration copy (`agent_migration_v0`), @mention read check (`read_receipts_v0`), App link cards and popover, Marketplace install confirmation, App logo.
  - "Raft message links open in the app" (Lagoon): links still open the browser until `open.raft.build` serves the association files (task #28).
- iOS-only lines: the live connection no longer drops when briefly leaving the app (#3648); the two crash fixes from #3730 (11600003 onwards).
- iOS external What to Test = the iOS notes (last external build was 1.15.5).

## Shipped (2026-10-26)

- Android `main`: 11600002 (`hands_channel=main`), release `85771d1e`, active, full scope. CI run 40277043351.
- Android `preview`: 11600001 (`hands_channel=preview`), release `a07ec7d5`, active, full scope. CI run 40278185232 (the first run 40277036069 hit a Hands 504 at upload and left an orphan pending build).
- Both Android releases were published with `scripts/finalize_android_hands_release.sh` (Hands CLI 0.5.32 from npm, #3704); the channel guard passed for both.
- iOS 11600003: final iOS build, in TestFlight internal Botiverse and external Insiders. CI run 40292623971 from `cf1bd7f1`, signed with the regenerated App Store profile that includes Associated Domains. Submitted to Insiders as soon as 11600002 cleared beta review (Apple allows one build in review at a time); approved immediately, testers notified.
- iOS 11600002: CI run 40279941362 from `8d5f6063`, same profile. Went through external beta review first (submitted 07:05Z, approved 10-26 evening) and was superseded by 11600003 right after.
- Announcements in #raft-mobile: Android (zh `224ec8a4`, en `cbc97311`); iOS 11600003 after external approval (zh `88b9bc84`, en `01e5f896`).

## Exceptions

- iOS 11600001 (CI run 40278819176) was built with the provisioning profile that was invalidated when Associated Domains was enabled, and has no Universal Links. It reached TestFlight internal only and is not distributed externally.
