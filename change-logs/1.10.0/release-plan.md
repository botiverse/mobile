# Mobile 1.10.0 Release Plan

Plan opened: 2026-08-10 13:00 Asia/Shanghai

Inventory refreshed: 2026-08-18 16:05 Asia/Shanghai

## Snapshot And Authority

- Candidate inventory: `origin/main@f711a9815c1c5fbb8cd2b61a84ae70d01f37b097`
  (tree `cae2aabaa85f0e8cedd5eaa3e72e29f44abfc74d`) plus the two landed docs
  commits `4b2abfb5c` (PR #2114) and `1c5d11feb` (PR #2174).
- Frozen exact: `1c5d11feba7f516604613b6458ff653ed41db40d`, cut as
  `release-1.10.0` on 2026-08-18 by owner directive ("直接切分支，同步发布").
  The head's delta over cumulative-green `f711a9815` is docs-only plus the
  reviewed one-line Staging `webUrl` correction; the freeze-exact push Test
  `32113956897` must still reach terminal green before any carrier build.
- Last distributed formal release: `1.9.1`, source
  `26bb45829e0f21de35613d965f39d38b48244d8d`.
- Next formal version: `1.10.0`; the stabilization branch is `release-1.10.0`.
- Platform build numbers and the signed carriers are not yet allocated; no
  distribution boundary is authorized.

This file is the planning ledger required by
`docs/mobile-paired-release-sop.md`. It accounts for landed behavior, open
proposals, dependencies, evidence gaps, and known limitations. It does not
authorize a merge, branch cut, build, Hands activation, TestFlight action,
store action, rollout, or announcement.

`reserved` means a landed behavior is accounted for and has capacity in this
train. It is not a product PASS. Only rows explicitly changed to `accepted` at
scope lock may be represented in release notes as ready.

## Calendar

The release owner must replace every `TBD` before branch creation.

| Milestone | Date | Rule |
| --- | --- | --- |
| Target release | Owner approved an immediate train 2026-08-18 15:47 Asia/Shanghai; marketing date follows distribution authorization | Distribution is separately authorized |
| Reservation cutoff | 2026-08-18 15:58 Asia/Shanghai | Owner closed scope: #2089/#2060/#2003 not taken |
| Scope lock | 2026-08-18 Asia/Shanghai, at freeze | Only `accepted` rows remain |
| `release-1.10.0` branch cut | 2026-08-18 Asia/Shanghai, at freeze | Cut from one reviewed main exact |
| Notes and source RC | TBD (`T-2` suggested) | Exact Hosted checks plus independent review |
| Product acceptance cutoff | TBD (`T-1` suggested) | Missing evidence requires removal or a recorded owner exception |

Branch creation remains blocked while the target date or scope lock is `TBD`.

## Delivery Intent

| Platform | Version coordinate | Intended boundary | Approval |
| --- | --- | --- | --- |
| Android | `1.10.0`; versionCode assigned at final freeze | Hands `main`, draft first | TBD |
| iOS | Marketing version and build assigned at final freeze; `release_notes_version=1.10.0` | Hands/TestFlight group and notification intent TBD; no App Store production assumption | TBD |
| OHOS | `1.10.0`; code assigned at final freeze if included | Signed HAP/Hands/AppGallery boundary TBD | TBD |
| Community | Reviewed zh-CN/en copy | Only after the named platform audience can reach the exact carrier | TBD |

All included platform carriers must embed the same 40-character source exact.
Authorization for a draft build does not authorize activation or distribution.

## Dependency Carrier

The old staging/source-build dependency hold is closed for Mobile consumption:

- Kuikly is pinned to immutable `2.24.0-raft.1` releases: Android/Apple use
  `2.24.0-raft.1-2.1.21`; OHOS uses
  `2.24.0-raft.1-2.0.21-ohos`.
- The checked-in closure contains 37 exact GAVs. The admitted Raft Artifacts
  release set also contains the iOS XCFramework/Pod and OHOS renderer, with a
  complete 920-object manifest.
- Kuikly gitlink/source compilation, Kuikly `mavenLocal` hydration, local Pod,
  checked-in HAR, and active fallback paths are removed from the consumer.
- The frozen candidate must still pass the manifest admission and empty-cache
  Android/KMP, Apple, and OHOS resolution gates. `latest`, ranges, gitlink,
  `mavenLocal`, local Pod/HAR, and source fallback remain forbidden for Kuikly.

Other shared fork prebuilds are not implicitly covered by the Kuikly rule. The
final receipt must identify every private fork used by the carrier and prove
its own immutable provenance.

## Landed Reserved Scope

Every row below is present in the snapshot. The rows group related commits so
that release scope stays readable; the source history remains authoritative.
None is `accepted` yet because `change-logs/1.10.0/changesets/` and the final
carrier acceptance matrix have not been completed.

| ID | User-visible behavior | Representative landed source | Platforms | State | Evidence still required |
| --- | --- | --- | --- | --- | --- |
| R110-01 | Durable mounted avatar delivery, session fencing, identity binding, accepted-cache seeding, upload invalidation, and retry recovery | PRs #1989/#1994/#2008/#2139/#2161; `1d84cfb1f`, `971747c2a`, `2fc435c9d`, `0cd1357d5`, `bd14bf5d8`, `191b04ebb`, `453448e58` | Android/shared | reserved | Consolidated changeset; stale/fresh, disk-only, invalid/404, retry, upload, restart, and account-switch runtime matrix |
| R110-02 | Non-intrusive Home notification center, clearer server-drawer row actions, and stable workspace-chevron placement | PR #1997; `24684517f`, `3b8fb35cc`, `adc925c4b` | Android/iOS/OHOS | reserved | Changeset; navigation, unread, long-name, row-action, and accessibility acceptance |
| R110-03 | Thread reading controls and layout corrections: message font preference, bottom rows, timestamp sizing, unread anchors, keyboard spacing, iOS IME viewport, and exact parent header | PRs #1999/#2021/#2022/#2025/#2118; `64b407152`, `59065a870`, `c653c9455`, `46743b438`, `e892e6522`, `15b1623fc`, `ad451675a`, `b3e5df3cd` | Android/iOS/OHOS as implemented | reserved | Changeset; compact/regular, IME, unread navigation, parent, rich text, and attachment matrix |
| R110-04 | Stable inline selection and toolbar behavior, including composer-safe placement and share actions | PRs #2033/#2040; `ec8b94538`, `f829bfa65`, `e95d6da57`, `f4dc11411`, `c9593e5ad`, `417078cec` | Android/iOS/OHOS | reserved | Changeset; physical selection, copy, share, menu, and gesture arbitration |
| R110-05 | Composer reliability: native send-time text snapshot, interactive suggestions, Enter acceptance, batched mention actions, mention scope recovery, and actionable failure feedback | PRs #2046/#2048/#2056/#2120/#2121; `40e6c33cf`, `dfa0cfbdc`, `148de3124`, `0bbefa377`, `620b8aa99`, `ec25eb6eb` | Android/iOS/OHOS | reserved | Changeset; IME composition/send, keyboard and pointer suggestion selection, batched mention, retry, and failure-feedback matrix |
| R110-06 | Safer transient message send and reply-origin recovery | `cb16c4e3a`, `4101d34a2`, `e213bf949` | Android/iOS/OHOS | reserved | Changeset; timeout, reconnect, duplicate-send, and reply-origin acceptance |
| R110-07 | Attachment and preview improvements: full annotation drafts, standard image annotation, server size limits, camera on image long press, MOV preview, canonical names, and durable preview identity | PRs #1977/#2049/#2085/#2107/#2162; `e027d136a`, `36aa853ae`, `ca93ba206`, `dab919dc4`, `c62c032da`, `107361006`, `4758689a6` | Android/iOS/OHOS as implemented | reserved | Changesets; per-platform file/image/video and annotation matrix; Android physical camera matrix remains NOT RUN |
| R110-08 | Direct-message and reference navigation: canonical DM header, quoted Raft permalink previews, direct-message thread links, exact conversation headers, and restored-route ownership fences | PR #2045; `aa1d6ffc0`, `b82d41b00`, `dd1a6cac6`, `48f40b4ea`, `b8a51696d`, `62c8e3f1d` | Android/iOS/OHOS | reserved | Changeset; first open/re-entry, account isolation, restored authentication, quote, missing-target, and back-navigation matrix |
| R110-09 | Computer discovery and management: shared search results, canonical naming, profile navigation, direct-message settings, and optional offline notifications | PR #2068; `cb6574031`, `126166f61`, `b702f8f8e`, `ae69aecec`, `3f5839547` | Android/iOS/OHOS | reserved | Changeset; search/profile/settings, persistence, notification default/migration, and navigation matrix |
| R110-10 | Members and presence correctness across reconnect and joint-channel settings | `1a48c04ef`, `d97eaf7d4` | Android/iOS/OHOS | reserved | Changeset; reconnect race, owner isolation, start/stop/profile, joint-channel, and roster acceptance |
| R110-11 | Activity navigation, mute settings, system-event anchors, and per-server unread contract | PR #2119; `3f52b2171`, `ef4f52eb1`, `0e5c9bc5`, `0d3072709` | Android/iOS/OHOS | reserved | Changeset must state current production behavior honestly; exact numeric unread is not live until the server field reaches production |
| R110-12 | Safer local storage and input state: unsupported database downgrade is blocked and search/short forms preserve text selection/composition | PRs #1995/#2004/#2044; `14e6ad601`, `33c58d5a8`, `55204ec09` | Android/iOS/OHOS | reserved | Changeset; downgrade/upgrade, offline-state preservation, IME composition, and selection matrix |
| R110-13 | Markdown tables render the real bold header cut | PR #2140; `d46affae8` | Android/iOS/OHOS | reserved | Changeset or explicit support-only classification; representative table visual acceptance |
| R110-14 | Settings and authentication cleanup: remove the dead message-row Reactions toggle, reveal committed iOS Settings WebViews, and keep registration terms reachable | `cebdacec2`, `1a567c21a`, `dc82ddb18` | Android/iOS/OHOS as implemented | reserved | Changeset or explicit non-note classification; settings migration, WebView loading, narrow-layout, and accessibility acceptance |
| R110-15 | Platform-specific viewport polish for Android sharing and affected Huawei insets animation | PR #2058; `d2a747986`, `7f91c208a` | Android | reserved | Changeset or explicit non-note classification; affected-device navigation-bar and IME/insets runtime |
| R110-16 | Consistent Simplified Chinese Raft terminology and voice | PRs #2061/#2076/#2147; `532bbafc7`, `8fe12c5cd`, `9da9618ac` | Android/iOS/OHOS | reserved | Localized changeset or explicit editorial classification; locale scan and representative UI review |
| R110-17 | Network requests follow runtime proxy-environment changes | PR #2086; `0f9e9ba36` | Android/iOS/OHOS | reserved | Changeset or explicit support-only classification; proxy change, reconnect, and no-proxy regression matrix |
| R110-18 | Reply composer access follows the selected reply thread instead of stale Activity or cold-restored route state | PR #2151; `dc1d423a2` | Android/iOS/OHOS | reserved | Changeset; Activity entry, cold restore, selected-thread access, denied access, and account-switch acceptance |
| R110-19 | Server switcher restores numeric unread badges while exact Activity totals are absent, preserves authoritative zero, and shares the `99+` cap | PR #2165; `c8108aa7a` | Android/iOS/OHOS | reserved | Changeset; exact positive/zero/absent, broader fallback, cap, production wire, and visual acceptance |
| R110-20 | Composer preserves the insertion cursor and mention panel when the legacy native text callback reports an untrusted zero selection | PR #2166; `2d263d12d` | Android/iOS/OHOS | reserved | Changeset; new-carrier first-character `@`, explicit cursor zero, delete, composition, stale-session, and selection-race acceptance. Feedback logs did not record the affected Thread selection, so the causal link remains a mechanism inference pending runtime replay. |
| R110-21 | Saving an agent runtime model updates the mounted detail immediately, re-entry resolves the current catalog label, and owner/generation fences reject stale completion | PR #2160; `0554daaf6` | Android/iOS/OHOS | reserved | Changeset; save completion, immediate projection, re-entry label, unknown/raw model, owner switch, generation race, and account isolation acceptance. Existing visual fixtures bypass the changed live-store path and therefore do not prove these production behaviors. |
| R110-22 | Channel member caches are partitioned by environment/account/server/channel, retiring on auth-clear and rejecting late old-owner writes | PR #2145; `ec8bf4b623b697d7a46c96e643328164a5ab693f` | Android/iOS/OHOS | reserved | Changeset (`members-cache-auth-owner-isolation.md`); account-switch and late-response acceptance on a new carrier |
| R110-23 | iOS durable mounted avatar cache with atomic index, bounded storage, and closed admission | PR #2137; `63141eac86b44fadf8b3dc3912345a98accec0b7` | iOS | reserved | Changeset (`ios-durable-mounted-avatar-cache.md`); physical iPhone restart/re-entry and sign-out retirement runtime NOT RUN |
| R110-24 | Android composer preserves selection/cursor while `@`/`#` suggestions load | PR #2170; `a42695f7f16a2c26e7ba6be7370067686facffc1` | Android | reserved | Changeset (`composer-selection-suggestions-load.md`); Huawei Android 16 new-carrier replay NOT RUN |
| R110-25 | Channel Members page renders the cached roster on first frame and keeps it across refresh/failure; row-local Add state; canonical Add permission | PR #2130; `aeb2ae17147367056f0d70656ed6281c648c14cf` | Android/iOS/OHOS | reserved | Changeset (`members-first-frame-cached-roster.md`); new-carrier runtime must specifically verify the persistent "members (0)" report (task #430), not only the transient first-frame flash; physical replay NOT RUN |
| R110-26 | Auth refresh retires a flight before publishing its result, so immediate retries cannot suppress the authoritative unauthorized consequence | PR #2168; `fea7056861c00d4ceccb8df65c08b0b16d67161d` | Android/iOS/OHOS | reserved | Changeset (`auth-refresh-publication-ordering.md`); auth/retry runtime acceptance NOT RUN |
| R110-27 | Joint Channel Task V2 parity: one canonical task fact with per-server local routes, monotonic revisions with fail-closed conflict refetch, deduped Activity/tray facts, explicit-server mutations | PR #2156; `2e4be50774120555285100b8330b86d8e2430be3` | Android/iOS/OHOS | reserved | Changeset (`joint-channel-task-v2-parity.md`); two-Server staging runtime NOT RUN; three landed risks tracked in Raft task #254 (owner accepted for this train 2026-08-18) |
| R110-28 | Personalized Search Home: account-scoped recent queries and 90-day frequent Channel/DM/Human/Agent ranking with authoritative re-projection | PR #2064; `7476abf320652950b2f5468ce2cb63fed2b0f116` | Android/iOS/OHOS | reserved | Changeset (`search-home-personalization.md`); product acceptance on a new carrier NOT RUN |

## Support-Only Landings

The following work is in the candidate ancestry but is not a `1.10.0` product
claim by itself:

- immutable Kuikly and shared-fork artifact consumption, release-manifest
  admission, and Hands SDK migration;
- CI tiering, emulator scheduling, source constraints, diagnostics, and test
  harness changes;
- CI pull-request comment publication through the least-privilege GraphQL
  PullRequest surface after the installation rejected the REST Issues Comments
  API (PR #2169; `f6ea96ed0`);
- visual-fixture source-set migrations and their compile-order repair;
- Settings and visual-host source refactors that preserve existing behavior;
- visual-fixture quarantine of the bistable compact-menu cases (PR #2171;
  `90562bb4e`);
- the Staging environment entry's own `webUrl` correction plus its test (PR
  #2114; `4b2abfb5c`). The Production entry and the default environment
  selection are untouched, so there is no production-behavior change; the
  earlier exclusion of this PR was written from its misleading title and is
  corrected here after diff review;
- release-plan and changeset documentation for this train (PRs #2152, #2164,
  #2172, #2173, #2174);
- pure source organization with no changed route, state, or presentation
  contract.

Support-only changes still participate in exact CI, dependency provenance,
packaging, and rollback analysis.

## Open Work Not In The Snapshot

Open PR state was re-read on 2026-08-18 16:00 Asia/Shanghai. At the 2026-08-18
15:58 owner directive the reservation cutoff closed: none of the remaining
open PRs enters this train. They must not appear in `1.10.0` notes.

| PR | Proposal | Disposition (owner, 2026-08-18) |
| --- | --- | --- |
| #2089 | Gate Home on server setup during onboarding | Not taken; author absent from the server roster; next train with an accountable owner |
| #2060 | Open installed app from download links | Not taken; author absent from the server roster; next train |
| #2003 | System Share to Raft extension | Not taken; still draft, iOS owner @Jony continues outside this train |

Everything previously listed here (#2170, #2168, #2156, #2145, #2137, #2130,
#2064) has since landed and is accounted as R110-22 through R110-28 above.
PRs #2152, #2164, #2172, #2173, and #2174 are documentation-only and landed as
support-only. PR #2114 landed as `4b2abfb5c` and is reclassified support-only
after diff review (see above).

## Preflight Carriers

No existing carrier is a `1.10.0` release candidate.

| Carrier | Source | Result | Release meaning |
| --- | --- | --- | --- |
| Android `1.0.2-alpha (1000213)` | `bd14bf5d89c84dee1ee7f33fa4c6db58fb23fa6d` | Signed APK and symbols built; no Hands activation | Internal preflight only; source predates this inventory |
| OHOS `1.0.2-alpha (1000213)` | `bd14bf5d89c84dee1ee7f33fa4c6db58fb23fa6d` | Signed HAP and symbols built; Hands step intentionally cancelled | Internal preflight only; no store action |
| iOS | none for this Alpha | NOT RUN | No iOS evidence can be inherited |

Android versionCode, iOS build number, and OHOS code for `1.10.0` will be
allocated only after the final source freeze. Alpha `1000213` is not inherited
as a formal version coordinate.

## Known Limitations And Holds

| Item | Current fact | Required release treatment / lift condition |
| --- | --- | --- |
| iOS `KRRichTextView` selector crash | The distributed 1.9.1 TestFlight build `1000015` has physical crash evidence. The causal source fix is in the immutable Kuikly line consumed by this snapshot, but no new final iOS carrier has physical same-path proof. | Build the final frozen iOS carrier and pass physical Settings/rapid-tab plus send-last-character replay before claiming the crash closed. No evidence from `1000015` is a PASS for 1.10.0. |
| Android camera long press | Source and automated gates landed at `36aa853ae`. The matrix splits in two: cloud cells (gallery → annotation → upload, recreation, fail-closed, duplicate-action, callback-replay, crash/ANR) are routable to @HuaTuo's Alibaba pool; camera-hardware cells (capture success, cancel, denial, unavailable-camera) cannot run on redroid (no camera hardware). The owner accepted the hardware cells personally: "相机我自己跑" (2026-08-18). | @HuaTuo runs the cloud cells on the signed carrier; artin runs the camera-hardware cells on his authorized physical Android. Any cell not executed stays `NOT RUN` in the receipt; no emulator evidence upgrades to PASS. |
| Joint Channel Task V2 landed risks | PR #2156 merged with three unruled risks recorded in Raft task #254: (1) a wedged `needsCanonicalRefetch` path when an authoritative refetch answers a lower revision (no test, silent stale UI), (2) realtime folds overwrite projection routes and hardcode `canClaim=true`, flipping HTTP-denied Claim affordances, (3) single-server deletes clear every server's projection without a revision guard. The author is absent from the server roster. | Owner accepted the risks for this train on 2026-08-18 ("开始发布吧" after the risk list was posted). task #254 must close or carry a recorded owner exception with scope and expiry before the next train's scope lock; do not describe the three behaviors as correct in notes. |
| Main-history Commit Sign-off red | The #2156 squash `2e4be5077` carries seven `Signed-off-by` trailers and fails the exactly-one policy; the red is permanent in main history. | Owner exception recorded 2026-08-18: no history rewrite. Cumulative-green accounting at freeze uses the terminal Test/constraints runs on the freeze exact, in which this workflow is green. |
| Members page persistent zero | task #430's "members (0)" on Alpha VC1000213 is root-caused to the pre-#2130 route-local store. The fix `aeb2ae171` is in the freeze, but no carrier with it has runtime proof yet. The changeset describes the transient first-frame flash; the user report was persistent within the visit (mechanism inference). | On the new carrier, specifically verify the persistent-zero path (open Members with a populated unified store), not only first-frame behavior. Until then task #430 keeps reproducing on old packages and must not be called closed. |
| Joint task two-server runtime | PR #2156's remaining acceptance (one fact/two routes, N+1 stale resistance, per-server mutation authorities, host/peer realtime fold, notification open through either local route) has no staging proof. | Run the two-Server staging matrix on the signed carrier or record `NOT RUN`. |
| iOS durable avatar cache | PR #2137 landed without physical iPhone runtime (restart/re-entry render, sign-out retirement). | Physical iPhone replay on the signed carrier or record `NOT RUN`. |
| Composer selection (#2170) | Huawei Android 16 new-carrier replay was NOT RUN at landing. | Replay on the signed carrier or record `NOT RUN`. |
| Personalized Search Home | PR #2064 landed without product acceptance (recents recording, frequent ranking re-projection, hidden-entity filtering, clear-preserves-frequent). | Acceptance pass on the signed carrier or record `NOT RUN`. |
| Auth refresh ordering (#2168) | The publication-ordering fix has no dedicated runtime acceptance beyond unit/hosted gates. | Auth-expiry guided re-login replay on the signed carrier or record `NOT RUN`. |
| Activity exact unread number | Client support landed, but the required server field is not in production. When the field is absent, the server switcher now displays the legacy broader unread number; an authoritative exact zero remains hidden and an exact positive value wins when present. The fallback can differ from the Activity total. | Do not claim exact per-server Activity counts are live in 1.10.0 notes. Describe the landed behavior as a temporary broader-number fallback. Exact Activity numbers activate only after a separately governed server production promotion. This does not by itself hold the client train. |
| OHOS feedback attachments over 50 MB | Memory work is deferred to task #418 before the following release and was explicitly classified non-blocking for this train. | Keep the existing product limit/behavior honest; do not claim the deferred memory improvement in 1.10.0. |

A green source build cannot convert any `NOT RUN`, old-carrier RED, or absent
server capability into product acceptance.

## Already Shipped Or Not A 1.10 Claim

Do not announce these again as `1.10.0` features:

- 1.9.1 canonical channel join refresh, restored composer draft ownership, and
  iOS final-message keyboard visibility;
- 1.9.1 AppShell full-bleed and timestamp fixes;
- iOS minimum deployment target 15.0 and release-note workflow plumbing;
- CI, test, visual-fixture, diagnostics, and artifact migration work listed as
  support-only above.

## Scope-Lock And Release Checklist

- [ ] Target date, cutoff, scope lock, branch cut, RC, and acceptance dates are
      real Asia/Shanghai dates. (Cutoff, scope lock, and branch cut are
      2026-08-18 by owner directive; target release, RC, and acceptance dates
      follow distribution authorization.)
- [ ] Maximum authorized delivery boundary is recorded separately for Android,
      iOS, OHOS, and community communication.
- [x] Every landed user-visible row is either `accepted`, removed/reverted from
      the branch point, or covered by an explicit owner exception. (Owner closed
      scope and accepted R110-01..R110-28 on 2026-08-18; the task #254 risks
      and the main-history Commit Sign-off red carry recorded owner exceptions
      above.)
- [x] Every accepted row has a reviewed
      `change-logs/1.10.0/changesets/<name>.md` with equivalent zh-CN/en copy.
      (Seven changesets were backfilled by the release lane under the owner's
      2026-08-18 assignment and are inside the freeze exact.)
- [ ] Open proposals selected for the train are landed with terminal checks and
      independent review on the exact source included by the branch point.
      (True for #2145/#2137/#2170/#2130/#2168; #2156 and #2064 were direct
      owner merges whose prior fresh-exact reviews were void — recorded as
      owner decisions, not as completed gates; #2114 is support-only.)
- [x] `release-1.10.0` is created from one reviewed 40-character main exact;
      Android, iOS, and included OHOS carriers embed that same exact.
      (`1c5d11feba7f516604613b6458ff653ed41db40d`; cut by owner directive while
      its push Test was pending — carriers wait for terminal green.)
- [ ] Cumulative exact CI is terminal green, including Kuikly manifest admission
      and empty-cache Android/KMP, Apple, and OHOS resolution. (Green on
      `f711a9815`; freeze-exact Test `32113956897` pending at cut time.)
- [ ] Every private fork dependency has immutable source, manifest, size, and
      SHA-256 provenance with zero unapproved local/source fallback.
- [ ] Android/iOS/OHOS build coordinates are newly allocated after freeze and
      are strictly newer than superseded carriers.
- [ ] Reviewed Android/iOS locale files are generated from accepted changesets;
      OHOS/community notes are added only for their approved boundary.
- [ ] Signed artifacts, symbols, package/bundle identity, signing, Build IDs or
      UUIDs, and GitHub-to-Hands byte equality are recorded in a new receipt.
- [ ] Targeted runtime covers baseline release paths plus every accepted row;
      unrun physical cases remain explicitly `NOT RUN`.
- [ ] The iOS crash and Android camera rows above are closed or carry an explicit
      release-owner decision with scope and expiry.
- [ ] Activation, TestFlight groups/notification, store action, rollout, and
      announcement each have separate current authorization and readback.
