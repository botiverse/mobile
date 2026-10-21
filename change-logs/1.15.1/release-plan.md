# 1.15.1 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Decision (Lagoon, 2026-10-21 10:24Z): 1.15.1 is cut from `main` as a new
  `release/1.15.1` branch, not cherry-picked onto `release/1.15.0`.
- Delivery intent: Android Hands `main` + `preview` (the same main-channel APK
  on both, so preview users move to `main`), Google Play, iOS TestFlight
  internal. Every step past a draft needs its own owner approval.

## Dates (Asia/Shanghai)

| Step | Date |
|---|---|
| Scope lock / branch cut | when every accepted item below is on `main` (Lagoon notifies) |
| Release candidate | TBD by the owner |

## Coordinates

- Android versionCode starts at `11501001` (`scripts/ci/version_code.sh 1 15 1 1`).
- iOS marketing version 1.15.1, build numbers from 1.
- Android and iOS are built from the same exact source on `release/1.15.1`.

## Scope

| Item | PR | State | Note |
|---|---|---|---|
| Crash on Android 10–14 when returning to the foreground (`SystemClock.uptimeNanos()`) | #3394 | accepted | required; regression from #3381 |
| Resume-from-background jank | #3381 | accepted | ships only together with #3394 |
| Search as a tab | #3393 | accepted | |
| iOS pull-to-refresh back to the native style | #3397 | accepted | |
| Stop / migrate agents | #3398 | accepted | |
| Activity shows "New activity" by mistake | TBD | accepted | fix in progress |
| Open pages repaint after a light/dark switch (Bug C) | TBD | accepted | deferred from 1.15.0 |

Each user-visible item adds a changeset under `changesets/` before scope lock.

## Release checks carried over from 1.15.0

- Push preview hiding: confirm slock #10483 is in production before the notes
  describe it as working for background pushes.
- Google Play: `closed` and `production` need the Hands Play adapter fix that
  stops re-uploading the bundle per track.

## Exceptions

None yet.
