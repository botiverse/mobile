---
name: Mobile release
about: One issue per mobile version. Scope authority and ledger from plan to closeout (docs/mobile-release-sop.md).
title: "Release X.Y.Z"
labels: release
---

Raft Mobile **X.Y.Z**. Procedure: `docs/mobile-release-sop.md`. This issue is
the scope authority and the ledger: tick each step with a link (PR, run, Hands
release, receipt) and post status changes as comments. Close it when the train
is closed (SOP §9).

## Owner and dates

- Release owner: artin · Release decisions: mobile DRI <name> (delegated by
  product owner decision 2026-11-14, SOP §Release decisions) · Operator: <name>   <!-- no @ before names -->
- Rollback, data-changing and risky calls: release owner
- Timezone: Asia/Shanghai
- Scope lock / branch cut: YYYY-MM-DD · Target release: YYYY-MM-DD
- Bump: minor / patch / build (SOP §2)
- Delivery intent:
  - Android: Hands `preview` → `main` after the release go; Play internal by default
  - iOS: TestFlight internal (Botiverse) → external only after the exact build was tested and the release go
  - App Store: <yes/no; screenshots>

## Branch and source

- Branch: `release/X.Y.Z` cut from `main` @ `<40-char commit>`
- Carrier source exact: `<40-char commit>` (both platforms)
- Release-branch fixes (PR → merge commit):

## Scope

States: proposed · reserved · accepted · removed · shipped.

| Changeset | PR | Platforms | Type | Rollout | State | Owner |
|---|---|---|---|---|---|---|
| `name` | #N | android, ios | fixed | full | accepted | name |

Pending / exceptions (approver, expiry, lift condition):

-

## Builds

| Carrier | Version / code | Run | Hands build / release | SHA-256 | State |
|---|---|---|---|---|---|
| Android `preview` | | | | | |
| Android `main` | | | | | |
| Google Play AAB | | | | | |
| iOS IPA | | | | | |

States: building · draft · smoke · approved · distributed · superseded.

## Checklist

- [ ] 0. Issue opened before branch/dispatch
- [ ] 1. Every accepted item has a changeset with its real PR; scope locked
- [ ] 1.1 Open PRs and issues checked: each merged, deferred (version + reason) or closed; recorded in the scope table
- [ ] 3. Branch cut and recorded; `test.yml scope=full` on it
- [ ] 4. Notes `android/` and `ios/` {zh-CN,en,ja} reviewed; mobile DRI approved (Play ≤500 chars/language)
- [ ] 5. Drafts built: Android preview, Android main, Play AAB, iOS
- [ ] 8. 10-minute interval reminder running on the release thread
- [ ] 6. Bytes verified (Android, iOS)
- [ ] 6. Smoke on the exact carriers (who, device, link) — Android
- [ ] 6. Smoke on the exact carriers (who, device, link) — iOS
- [ ] 6. Xcode/SDK changed? Tab bar on a regular iPhone, iOS 26 and iOS 27
- [ ] 6.3 Trend check vs previous release: IPA / APK / AAB size, iOS K/N link time and heap; IPA < 100 MiB unless Hands direct upload (hands-cli version named)
- [ ] 7. Android `preview` finalized
- [ ] 7.2.1 `Android Update E2E` green on the carrier source exact (run id)
- [ ] 7.2.1 Preview update test: previous preview → new via in-app prompt (device, from → to versionCode, pass/fail, screenshot or recording); a fail blocks `main`
- [ ] 7. Google Play internal (receipt)
- [ ] 7. TestFlight internal: `VALID`, Botiverse, `IN_BETA_TESTING`
- [ ] 7. Release go (no new crashes · related feedback fixed · server dependencies live): Android `main` finalized + `vX.Y.Z` tag + GitHub Release
- [ ] 7. Release go: Google Play closed / open / production (review status read; any `--cancel-in-review` confirmed by the release owner)
- [ ] 7. Release go (after testing the exact build): TestFlight external, notification receipt + `IN_BETA_TESTING`
- [ ] 7. iOS audit tag `ios-vX.Y.Z-b<build>`
- [ ] 7. Release go: App Store submission / release (screenshots via Hands)
- [ ] 7. Release go: announcements (message ids)
- [ ] 9. Merge-back PR squash-merged; content verified against `vX.Y.Z`
- [ ] 9. Version closed; branch locked (ruleset id, revocation condition)
- [ ] 9. Reminder cancelled; final summary posted; issue closed

## Receipts

<!-- run ids, Hands ids, Play receipt/edit id, ASC upload id, notification receipt, message ids -->
