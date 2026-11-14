---
name: raft-ios-release
description: Use when building an iOS release of Raft (ios-release.yml) into a Hands draft (raft-ios), uploading it to TestFlight, distributing to the internal or external group, submitting to the App Store, or creating the iOS audit tag. Covers the workflow inputs including macos_runner and testflight_internal.
---

# Raft iOS release + TestFlight

Follow `docs/mobile-release-sop.md`: §0 release issue, §2 build numbers, §5.2
`ios-release.yml` inputs (Xcode 27.1 on `xcode-27-xlarge`,
`testflight_internal`), §6 smoke, §7.4 TestFlight states and audit tag, §7.5
App Store, §8 interval reminder.

- TestFlight internal (Botiverse) is the default; report to the mobile DRI
  (Lagoon) and stop.
- External only after that exact build was tested and the mobile DRI gave a
  separate release go (SOP §Release decisions; delegated by artin 2026-11-14);
  done means `notification=sent|already_sent` and `IN_BETA_TESTING`.
- Never export the ASC credential, activate the Hands draft, or submit to the
  App Store without its own go.
