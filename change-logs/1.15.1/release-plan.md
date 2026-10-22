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
| Scope lock / branch cut | 2026-10-21 19:06 (`release/1.15.1` @ `eaa49a95`) |
| Release candidate | 2026-10-21 (Android 11501001, iOS 1.15.1 (1)) |

## Coordinates

- Android versionCode starts at `11501001` (`scripts/ci/version_code.sh 1 15 1 1`).
- iOS marketing version 1.15.1, build numbers from 1.
- Android and iOS are built from the same exact source on `release/1.15.1`.

## Scope (shipped, `release/1.15.1` @ `eaa49a9520bc4cb9c5dae4945932b23774ed15a4`)

| Item | PR | State | Changeset |
|---|---|---|---|
| Crash on Android 10–14 when returning to the foreground | #3394 | shipped | none: the crash came from #3381 and never reached users |
| Smoother return from the background | #3381 | shipped | `resume-smoother` |
| Search as a tab; Home head identity | #3393 | shipped | `search-tab-head` |
| iOS pull-to-refresh back to the native style | #3397 | shipped (iOS) | `ios-native-pull-to-refresh` |
| Stop / migrate agents | #3398 | shipped | `agent-stop-and-migrate` |
| Activity "New activity" / future times | #3401 | shipped | `activity-top-follow` |
| Open pages repaint after a light/dark switch (Bug C) | #3402 | shipped | `theme-switch-message-text` |
| Joint channels (invite, accept, create) | #3392 | shipped | `joint-channels` |
| Product analytics events, only with "Share usage data" on | #3400 | shipped | `usage-data-analytics` (listed in the notes at Lagoon's request: the new privacy policy covers it) |

Reviewed notes: `android/` and `ios/` in this directory (zh-CN, en, ja).

## Carriers

| Carrier | Coordinates | State |
|---|---|---|
| Android Hands `preview` | 11501001, release `8e2a78ac` | active 100% (same APK as `main`, SHA-256 `d8c5bd2d…`) |
| Android Hands `main` | 11501001, release `012955d1` | active 100% (owner approved via Lagoon, task #61; artin "ok" 2026-10-21 12:44Z) |
| Google Play | 11501002 (Play variant, same source) | `internal` only; `closed` / `production` wait for the Hands Play adapter fix |
| iOS TestFlight | 1.15.1 (1) internal; **1.15.1 (2) external** | (1) passed Beta App Review 2026-10-22 06:14Z. Owner chose to ship (2) to the external group Insiders instead (pinyin fix verified by artin); auto-approved and testers notified 06:15Z |

QA: Lagoon Android smoke on staging passed (install, cold start, login, tabs, search keyboard, 3 resumes, Activity, send, pull-to-refresh, live theme switch). #3394 verified on API 34/31 emulators and an Android 12 cloud phone. Not covered: agent stop/migrate (no online agent on staging).

## Release checks carried over from 1.15.0

- Push preview hiding: confirm slock #10483 is in production before the notes
  describe it as working for background pushes.
- Google Play: only `internal` this release. `closed` and `production` need the
  Hands Play adapter fix that stops re-uploading the bundle per track.

## Exceptions

1. **iOS external carrier is 1.15.1 (2), not from `release/1.15.1`.** It was built from Lagoon's branch `agent/lagoon/ios-ime-textfieldstate` @ `53054b59` (#3439, pinyin input fix, plus newer `main`). Owner: artin, 2026-10-21 21:50Z ("直接用这个包"). Its What to Test is the full 1.15.1 list plus the (2) additions (Lagoon's combined list); see `ios/` notes.
