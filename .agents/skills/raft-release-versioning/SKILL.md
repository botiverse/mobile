---
name: raft-release-versioning
description: Use when deciding which version number a Raft Mobile release gets — a new minor (1.X.0), a patch (1.X.Y) or only a new build number — and what is allowed onto the release branch for it. Read before cutting a release branch, cherry-picking a fix onto one, or dispatching raft-android-release / raft-ios-release.
---

# Raft Mobile release versioning

Read `docs/mobile-release-sop.md` §2 (bump table, one version line,
versionCode formula) and §3 (release branch, what may enter it).

- minor = new features, weekly from `main`; patch = fixes cherry-picked from
  `main`; build = same content, new build number. A feature never ships as a
  patch or build.
- Internal/preview channels need no approval; every external release needs
  a release go (the mobile DRI's, by artin's delegation) for that version and
  a smoke of the exact build (SOP hard rule 2).
