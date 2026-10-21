# 1.15.0 release plan (retrospective)

> **Retrospective record.** This plan, the release branch and the Notion ledger
> entry were written on 2026-10-21 after the Android carrier had already been
> published. The pre-release SOP steps (plan before branch cut, scope lock,
> branch before build, paired source) were **not** performed in order. Nothing
> below marks a skipped step as done; it records what actually happened.

## Owner and scope

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Scope: the first release after the full move to Compose Multiplatform, plus
  the 1.14.2 fixes (1.14.2 was not shipped on its own).
- Reviewed notes: `android/` and `ios/` in this directory (zh-CN, en, ja),
  approved by the owner on 2026-10-21 03:41Z.
- Accepted changesets: `changesets/` in this directory.

## Dates (Asia/Shanghai)

| Step | Date | Note |
|---|---|---|
| Release plan | 2026-10-21 | retrospective |
| Branch cut | 2026-10-21 | retrospective, after publication |
| Android preview publish | 2026-10-21 13:41 | build 11500009, after owner device check |
| Android main publish | 2026-10-21 13:55 | owner: "你直接发" |

## Source

| Carrier | Exact source | Branch |
|---|---|---|
| Android Hands 11500009 | `c9cbba53d5db28219a1043ab566b5056ea210267` | `release/1.15.0` (created at this commit) |
| Android Google Play 11500010 | `c9cbba53d5db28219a1043ab566b5056ea210267` | same source, Play variant |
| iOS 1.15.0 (15) | `8a28e0537` (main) | **not** on `release/1.15.0` |

## Platform state at time of writing

| Platform | Boundary reached | Not done |
|---|---|---|
| Android Hands `preview` | 11500009 active, 100% | — |
| Android Hands `main` | 11500009 active, 100% | — |
| Google Play | 11500010 on `internal`, 100% | `closed` and `production` blocked by a Hands Play adapter bug (re-uploads the bundle per track); fix in progress |
| iOS TestFlight internal | build 15 processed, internal group | — |
| iOS TestFlight external / App Store | owner reports external TestFlight submitted and in review | App Store production not submitted; Raft is not on the App Store |
| HarmonyOS | not in this train | removed from the repo; ArkTS rewrite planned |

## Exceptions

1. **No pre-release plan or branch.** Recorded retrospectively; owner: @artin, 2026-10-21.
2. **Android and iOS sources differ.** iOS build 15 was built from a later
   `main` (`8a28e0537`) than the Android carrier (`c9cbba53`). A later iOS
   candidate for 1.15.x should be built from `release/1.15.0` or a reviewed
   successor.
3. **Play versionCode differs from Hands.** Hands allows one release per
   versionCode, and 11500009 was already the Android `main` release, so the
   Play carrier is 11500010 from the same source.
4. **Push preview hiding depends on the server.** The "hide message content in
   notifications" setting needs slock #10483 in production; until it is
   deployed, only the in-app foreground banner is redacted.

## Incidents during the train

- Preview 11500007 crashed on launch (Kotlin 2.4.21 Compose compiler omits a
  group in `x && (composable() || ...)`). Rolled back to 0%, fixed in #3356 with
  a source constraint and regression test #3359; 11500008 shipped the fix.

## Known issues deferred to 1.15.x

- Open pages do not repaint when switching light/dark mode (Markdown rich-text cache ignores theme).
- Rotation can lose the open thread.
- Resume-from-background jank (fix #3381 is on `main`; its crash on Android 10–14 is being fixed in #3394). Neither is in the 1.15.0 carriers.
