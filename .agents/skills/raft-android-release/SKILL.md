---
name: raft-android-release
description: Use when cutting, building, smoking, publishing or closing an Android release of Raft on Hands (raft-android) or Google Play — release issue, release branch, versionCode allocation, draft-first dispatch, reviewed notes, smoke gate, owner-approved activation, audit tag and merge-back. For a quick installable QA build use the Android Alpha Validation workflow instead; for a paired Android+iOS train also follow raft-ios-release.
---

# Raft Android release (Hands, Google Play)

Follow `docs/mobile-release-sop.md` in order: §0 release issue, §2
versionCode, §3 branch, §4 notes, §5.1 Android draft + Play carrier, §6
smoke, §7.2 finalize + `vX.Y.Z` tag, §7.3 Play tracks, §8 interval reminder,
§9 merge-back and lock.

- CI only makes drafts. `main` activation, Play beyond internal, and
  announcements each need an explicit release go from the mobile DRI (SOP
  §Release decisions). Rollback and `--cancel-in-review` go to artin.
- One APK per Hands channel; publish only with
  `scripts/finalize_android_hands_release.sh`.
- Never publish the Hands draft that carries a Play promotion.
