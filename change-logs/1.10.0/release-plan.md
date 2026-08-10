# Mobile 1.10.0 Release Plan

Plan opened: 2026-08-10 13:00 Asia/Shanghai

Inventory snapshot:

- `origin/main`: `e027d136a81d216d94d758b206e556621ac54be3`
- last distributed 1.9.1 source:
  `26bb45829e0f21de35613d965f39d38b48244d8d`
- open Mobile PRs: fresh-read 2026-08-10 13:32 Asia/Shanghai

This file reserves capacity and makes missing evidence visible. It does not
authorize a merge, branch cut, build, rollout, activation, TestFlight action,
App Store action, or announcement.

## Calendar

The release owner must replace every `TBD` before branch creation.

| Milestone | Date | Rule |
| --- | --- | --- |
| Target release | TBD, Asia/Shanghai | Fixed after owner approval |
| Reservation cutoff | TBD (`T-7` suggested) | New ordinary candidates stop entering |
| Scope lock | TBD (`T-5` suggested) | Only `accepted` rows remain |
| `release-1.10.0` branch cut | TBD (`T-4` suggested) | From reviewed main exact |
| Notes and source RC | TBD (`T-2` suggested) | Exact Hosted + independent review |
| Product acceptance cutoff | TBD (`T-1` suggested) | Missing evidence leaves the train |

Branch creation is blocked while the target date or scope lock is `TBD`.

## Delivery Intent

| Platform | Intended carrier | Intended audience/boundary | Owner approval |
| --- | --- | --- | --- |
| Android | `1.10.0`, versionCode assigned at freeze | Hands `main`; draft first | TBD |
| iOS | marketing version/build assigned at freeze; `release_notes_version=1.10.0` | TestFlight groups and notification intent TBD; iOS Hands activation not assumed | TBD |
| OHOS | Same shared source if included | No package or AGC action unless separately added here | TBD |
| Community | Reviewed zh-CN/en announcements | Only after the reachable platform boundary is proven | TBD |

## Reserved Main Inventory

These behaviors are already present in the snapshot candidate. `reserved`
means they must be accounted for before scope lock; it is not a readiness PASS.

| ID | Candidate | Source | Platforms | Owner | State | Missing before `accepted` |
| --- | --- | --- | --- | --- | --- | --- |
| R110-01 | Durable mounted avatar cache and cleanup fencing | PR #1989, PR #1994, PR #2008, commits `91d264aa3`/`27de7d799`/`4feeb7043` | Android/shared | Android cache lane | reserved | 1.10 changeset; physical stale/fresh, disk-only, invalid/404, restart and ordinary-image matrix |
| R110-02 | Non-intrusive Home notification center | PR #1997 / `24684517f` | Android/iOS/OHOS | Home lane | reserved | changeset; cross-platform navigation, unread and accessibility acceptance |
| R110-03 | Thread message font preference | PR #1999 / `64b407152` | Android/iOS/OHOS | Shared settings lane | reserved | changeset; persistence and representative rich-text/attachment regression |
| R110-04 | TextField state migration for search and short forms | PR #1995 / `14e6ad601`, PR #2004 / `33c58d5a8` | Android/iOS/OHOS | Shared input lane | reserved | changeset or explicit non-user-visible classification; IME/composition matrix |
| R110-05 | Thread bottom-tab, timestamp and unread-anchor corrections | PR #2025 / `46743b438`, PR #2021 / `59065a870`, PR #2022 / `c653c9455` | Android/iOS/OHOS | Thread lane | reserved | changesets; compact/regular layout, navigation and unread product evidence |
| R110-06 | Inline-selection stability | PR #2033 / `ec8b94538`, PR #2040 / `f829bfa65` | Android/iOS/OHOS | Thread/visual lane | reserved | changeset; real selection/copy/menu runtime matrix |
| R110-07 | Send-time native text snapshot and interactive suggestion underlay | PR #2046 / `40e6c33cf`, PR #2048 / `dfa0cfbdc` | iOS/shared | Composer lane | reserved | changesets; physical-device composition/send and suggestion interaction evidence |
| R110-08 | Offline-computer notification control | `cb6574031` | Android/iOS/OHOS | Notifications lane | reserved | changeset; default/migration/persistence and visible notification behavior |
| R110-09 | Network runtime diagnostics and complete raft.32 consumption | PR #2009 / `aa3154f8c`, PR #2035 / `ff7342329` | Android/iOS/OHOS | Network lane | reserved | classify as support-only; redaction and zero product-claim review |
| R110-10 | Preserve full image-annotation drafts without silent truncation | PR #2049 / `913e0ec33`, landed `e027d136a` | Android/iOS/OHOS | File preview lane | reserved | 1.10 changeset; long-draft editing/rasterization and layout visibility acceptance |

## Open Proposals

Open PR state is a snapshot, not a readiness verdict. Each proposal must rebase
onto current main, add a 1.10 changeset when user-visible, and return fresh exact
checks/review before it can become `accepted`.

| ID | Proposal | PR exact at snapshot | State | Current blocker / acceptance evidence |
| --- | --- | --- | --- | --- |
| P110-02 | Recover from a newer local database without LaunchScreen hang | PR #2044 `58d545b4289006952f10dadd957228502a0d5f33` | proposed-hold | Product contract rejected reset/quarantine/empty-database recovery because pending, offline, and draft state remains unavailable; replace it with pre-write forced-upgrade/retry blocking or obtain a new explicit product contract, then re-review |
| P110-03 | Publish canonical DM header identity on first open | PR #2045 `04508be12356471b1db04c6871ede80595a11a93` | proposed | Draft; require final review, changeset, first-entry/re-entry/account isolation runtime |
| P110-04 | System Share to Raft extension | PR #2003 `5e752c92ea82edbdd847108f09b7c0b84974abcc` | proposed | Draft; require authorized extension matrix, attachment/text/deep-link contract, privacy and physical iOS evidence |
| P110-05 | Responsive channel member grid | PR #1983 `8db96c5f56fe32bc4662a358551129894debd407` | proposed | Draft/latest checks unstable; require rebase, responsive matrix, accessibility and changeset |
| P110-06 | Typed Android avatar cache follow-up | PR #1980 `aaf5282b1fb65cd7544f41225827f58d985eb44d` | proposed | Conflicts with current main and R110-01; owner must declare successor or close as superseded |
| P110-07 | Camera on image long press | PR #1977 `55c890ff3d36d918ab89a7acb1c128ece84596c2` | proposed | Draft/conflicting; require gesture arbitration, permission/cancel matrix and changeset |
| P110-08 | Password reveal and login-payload correction | PR #1975 `64ca343fe818639107342ec817545bf6ef0c1692` | proposed | Draft/conflicting with failed final CI; require fresh root cause, credential-safe tests, rebase and review |
| P110-09 | Android Markdown table dividers | PR #1952 `74b22597f47f55c6b0b513865c6b93b0ececa69b` | proposed | Draft; require current-main rebase, table matrix, changeset and independent review |
| P110-10 | Single keyboard-shrink owner | PR #1985 `4b6d1795370346470edc3e9cb6fc58d73c1490d7` | proposed | Conflicting/stale; reconcile with shipped 1.9.1 IME policy and prove causal runtime need |
| P110-11 | Focused-origin/composer correction ordering | PR #1976 `9b67d74a67be7603715bf6abb0533740271b959e` | proposed | Conflicting/stale; owner must revalidate against current Thread state or close |
| P110-12 | Android Thread viewport diagnostics | PR #1992 `baad1f645fad54e21c53dbbb81c75d79fd8c1245` | proposed-support | Conflicting; diagnostic-only unless it proves a required white-viewport causal fix |

## Safety Holds

| Hold | Owner | Current fact | Lift condition |
| --- | --- | --- | --- |
| iOS Kuikly rich-text selector crash | iOS/Kuikly root-cause task #131 | Official 1000015 contains the old guard but still crashes on physical `KRRichTextView`; provenance/carrier bytes PASS, product fix FAIL | Falsifiable root cause and causal fix, reviewed Kuikly exact, Mobile carrier binding, then authorized physical same-path PASS |
| Android white viewport family | Thread/Android diagnostic lane | Existing production family remains product RED; diagnostics PR #1992 is not a fix | Causal source fix plus clean physical-device product matrix |
| Share-to-Raft release acceptance | iOS native lane | Source proposal exists, full authorized product matrix incomplete | Exact accepted PR plus named physical matrix and privacy review |

No held item may be represented as shipped or ready because its source tests are
green. A hold may leave the train; it does not move the train date by default.

## Already Shipped Or Not A 1.10 Claim

The following are present in ancestry or merge-back but must not be announced
again as 1.10 features:

- 1.9.1 channel join state, restored composer draft ownership, and iOS IME
  final-message visibility;
- 1.9.1 release AppShell full-bleed and timestamp fixes;
- iOS minimum deployment target 15.0 and release-note workflow plumbing;
- CI, test, visual-fixture, and diagnostics-only changes without user behavior.

## Scope-Lock Checklist

- [ ] Real target date and all calendar milestones filled.
- [ ] Platform delivery intent and maximum authorized boundary filled.
- [ ] Every reserved main behavior has a changeset or explicit support-only
      classification.
- [ ] Every accepted proposal is merged into main with terminal exact checks
      and independent review.
- [ ] Dependencies and live holds have owners and lift conditions.
- [ ] Removed items record the reason and next train.
- [ ] Android/iOS/OHOS acceptance matrices are named and resourced.
- [ ] `release-1.10.0` branch point is a reviewed 40-character main exact.
