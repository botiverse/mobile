---
name: raft-release-versioning
description: Use when deciding which version number a Raft Mobile release gets — a new minor (1.X.0), a patch (1.X.Y) or only a new build number — and what is allowed onto the release branch for it. Read before cutting a release branch, cherry-picking a fix onto one, or dispatching raft-android-release / raft-ios-release.
---

# Raft Mobile release versioning

Version numbers tell users and the stores what changed. Pick the smallest
bump that is honest about the change. Recorded at artin's request
(2026-11-02).

## Which bump

| Bump | When | What goes in | Cadence |
|---|---|---|---|
| **minor** `1.X.0` | New user-visible features | `release/1.X.0` cut from `main` | Weekly (weekday), once `main` full CI is green and the Alpha/internal build passes a smoke test |
| **patch** `1.X.Y` | Fixes on the live release: crashes, data loss, regressions vs the previous version, store-review blockers, security | Cherry-picks from `main` onto the live release branch; nothing beyond the fix (no new features, no unrelated strings) | As soon as the fix is verified; don't wait for the next minor |
| **build** same `1.X.Y`, new build number | No user-visible change: re-sign/repackage, store resubmission, CI or metadata fix, a build from the wrong commit, internal/test builds (Alpha, TestFlight internal from `main`) | Same content as the version it rebuilds | Any time |

- A feature never ships as a patch or a build number. If it isn't ready for
  the weekly minor, it waits for the next one. Features behind a server flag
  may ship with the flag off.
- Every patch fix lands on `main` first (or at the same time), then is
  cherry-picked. Verify each cherry-pick against its merge commit, not by
  grepping strings.
- Changelog: full one for a minor (`raft-release-changeset`); short, fixes
  only, for a patch; none for a build.

## Platforms move together

Android (Hands, Google Play) and iOS (TestFlight, App Store) share the version
number. A fix needed on only one platform still bumps the shared patch; the
other platform ships it when it next releases. Compose desktop builds follow
the same version.

## Who decides

Internal and preview channels (Hands preview, Play internal, TestFlight
internal) need no approval. Every external release — Hands main, Play
closed/production, TestFlight external, App Store — needs artin's explicit OK
for that version, and a smoke test of the exact build first.

Mechanics of building and publishing: `raft-android-release`,
`raft-ios-release`.
