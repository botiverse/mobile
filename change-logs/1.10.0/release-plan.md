# Mobile 1.10.0 Release Plan

Plan opened: 2026-08-10 13:00 Asia/Shanghai

Inventory refreshed: 2026-08-18 00:47 Asia/Shanghai

## Snapshot And Authority

- Candidate inventory: `origin/main@f6ea96ed07f3dfb9632ed32227b421e2a5de72b7`
  (tree `787acac3f6c7e55dc34c847eff80300b782d0022`).
- Last distributed formal release: `1.9.1`, source
  `26bb45829e0f21de35613d965f39d38b48244d8d`.
- Next formal version: `1.10.0`; the stabilization branch will be
  `release-1.10.0` after scope lock.
- No `1.10.0` source exact, release branch, platform build number, signed
  carrier, or distribution boundary has been frozen yet.

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
| Target release | TBD, Asia/Shanghai | Fixed after owner approval |
| Reservation cutoff | TBD (`T-7` suggested) | Ordinary new candidates stop entering |
| Scope lock | TBD (`T-5` suggested) | Only `accepted` rows remain |
| `release-1.10.0` branch cut | TBD (`T-4` suggested) | Cut from one reviewed main exact |
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
- pure source organization with no changed route, state, or presentation
  contract.

Support-only changes still participate in exact CI, dependency provenance,
packaging, and rollback analysis.

## Open Work Not In The Snapshot

Open PR state was read on 2026-08-18 00:47 Asia/Shanghai. These PRs are not in
the candidate inventory and must not appear in `1.10.0` notes unless they land
before the chosen freeze exact and satisfy all normal gates.

| PR | Proposal | Snapshot classification |
| --- | --- | --- |
| #2170 | Preserve composer selection while asynchronous suggestions load | Proposed; requires current-main exact, review, changeset, and first-character/selection/composition runtime acceptance |
| #2168 | Fix authentication refresh publication ordering and coroutine race tests | Proposed; source, exact checks, review, changeset, and auth/retry runtime incomplete |
| #2156 | Joint Channel Task V2 parity | Proposed; source, exact checks, review, changeset, and product acceptance incomplete |
| #2145 | Isolate channel cache by authenticated owner | Proposed; requires current-main exact, review, changeset, and account-switch matrix |
| #2137 | Durable mounted avatar cache on iOS | Draft; not part of the landed Android/shared avatar scope |
| #2130 | Render cached member roster on first frame | Proposed; requires current-main exact, review, changeset, and first-frame matrix |
| #2089 | Gate Home on server setup during onboarding | Proposed; requires current product contract and full onboarding matrix |
| #2064 | Personalized Search Home | Proposed; required checks not terminal green at snapshot |
| #2060 | Open installed app from download links | Proposed; requires platform/deep-link acceptance and changeset |
| #2003 | System Share to Raft extension | Draft; requires authorized iOS extension, privacy, and physical-device matrix |

PRs #2152 and #2164 are documentation-only and are not product candidates. PR
#2114 changes the web origin to staging and is excluded from a production train
unless the release owner records an explicit environment exception.

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
| Android camera long press | Source and automated gates landed at `36aa853ae`, but task #415 remains NOT RUN because no authorized controllable physical Android is available; emulator/redroid has no qualifying camera hardware. | Run gallery tap, camera long press, capture-to-annotation/upload, cancel, denial, unavailable-camera, duplicate-action, recreation, cleanup, callback-replay, crash/ANR matrix on an authorized physical device, or record a release-owner scope exception/removal decision. |
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
      real Asia/Shanghai dates.
- [ ] Maximum authorized delivery boundary is recorded separately for Android,
      iOS, OHOS, and community communication.
- [ ] Every landed user-visible row is either `accepted`, removed/reverted from
      the branch point, or covered by an explicit owner exception.
- [ ] Every accepted row has a reviewed
      `change-logs/1.10.0/changesets/<name>.md` with equivalent zh-CN/en copy.
- [ ] Open proposals selected for the train are landed with terminal checks and
      independent review on the exact source included by the branch point.
- [ ] `release-1.10.0` is created from one reviewed 40-character main exact;
      Android, iOS, and included OHOS carriers embed that same exact.
- [ ] Cumulative exact CI is terminal green, including Kuikly manifest admission
      and empty-cache Android/KMP, Apple, and OHOS resolution.
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
