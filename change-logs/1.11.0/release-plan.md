# Mobile 1.11.0 Release Plan

Plan opened: 2026-09-04 Asia/Shanghai

## Snapshot and authority

- Frozen source branch: `release/1.11.0`
- Frozen source SHA: `4f78d2c4fe8da6b5a34ff334b11a5bc09750f6d5`
- Frozen tree SHA: `0059f46a55fa4a92f38de92d3b51259b31a42bb9`
- The branch was created from a fresh `origin/main` read and pushed before any build.
- This plan authorizes no build, upload, activation, rollout, migration, or store submission by itself.

## Scope

The release candidate includes only changes reachable from the frozen source above and represented by the 1.11.0 changesets. Members/presence landed ranges are recorded in source history; their runtime evidence remains separately classified.

## Explicit exclusions and limitations

- `#2350` (offline content banner) is after the freeze tip and is **excluded from 1.11.0**; it follows the next release unless an explicit exception reopens the freeze.
- Offline-cache changes `#2342`, `#2347`, and `#2349` are carried but dormant behind `sync_core_messages_v0` (currently missing/disabled). Their runtime path has not executed in this release; first flag enablement is a future test, not 1.11.0 acceptance.
- Android device visual checks that require a qualifying device/object/permission are `NOT RUN`; iOS/OHOS visual checks without a provider are `NOT COVERED`. Do not collapse these into PASS.
- Google Play promotion/review is **SKIPPED** for this release.

## Phase 0 — source/release freeze

- [ ] Obtain owner approval for this plan and the 1.11.0 scope.
- [x] Create and push isolated `release/1.11.0` from the frozen main exact above.
- [ ] Reconcile release/main diff and reject unauthorized changes.
- [ ] Reconcile every `change-logs/1.11.0/changesets/` entry and release note.
- [ ] Produce and freeze a release manifest/digest.

## Allocated version coordinates

These are CI dispatch inputs. Frozen source may keep placeholder defaults
(`1.0.0` / `1000000` on Android and OHOS; iOS `MARKETING_VERSION=1.0`). Do
not rewrite those placeholders into this tree.

| Platform | versionName | versionCode | Hands live high-water | Notes |
| --- | --- | ---: | ---: | --- |
| Android | `1.11.0` | `1110000` | `1100007` | Inject `version_major=1 version_minor=11 version_patch=0 version_build=0` |
| OHOS | `1.11.0` | `1110000` | `1100003` | CI rewrites `AppScope/app.json5` at dispatch |
| iOS | `1.0.0` | `1000022` | `1000021` | Keep App Store marketing `1.0.0`; `CFBundleVersion` only. Owner may later choose iOS marketing `1.11.0` / `1110000` instead |

## Phase 1 — preflight (fail closed)

- [ ] Confirm the allocated coordinates above are monotonic and unused. Do not fail because the source tree still shows placeholder `1.0.0` / `1000000`. After build, verify artifacts match the allocated inputs.
- [ ] Admit Kuikly/Hands from the immutable Raft Artifacts release set (`exclusiveContent` + manifest). Silent fallback of those pinned coordinates to gitlink, mavenLocal, local Pod/HAR, or source build blocks. Declaring `mavenLocal()` in Gradle repository lists is not a blocker. Other shared forks keep their own provenance.
- [ ] Verify Android package/variant/Google Services (production + Alpha), signing lineage, zipalign, 16 KiB, v3/v4, R8/DEX.
- [ ] Verify iOS bundle/signature/IPA/dSYM/source marker.
- [ ] Verify OHOS App Pack/HAP/signature/module/lock/resource/ABI provenance.
- [ ] Require terminal three-platform CI, DCO, path/manifest/resource/contract checks; skipped is not PASS.

## Phase 2 — build and artifact verification

- [ ] Build once from the frozen exact: Android AAB/APK, iOS IPA/dSYM, OHOS App Pack/HAP.
- [ ] Record run/artifact IDs, source marker, version, size, SHA-256, and signer.
- [ ] Independently download and byte/SHA compare every artifact.
- [ ] Upload and read back Android symbols and iOS symbols/dSYM in Hands (OHOS symbols per carrier contract).

## Phase 3 — Hands draft and QA

- [ ] Create inactive, rollout-0 drafts with locked zh-CN/en/ja notes.
- [ ] Read back Hands build/release metadata and artifact SHA equality.
- [ ] Run scoped Android/iOS/OHOS QA; record NOT RUN vs NOT COVERED explicitly.
- [ ] Include 21.74 MiB task-list pagination timing only if the current candidate implements it; otherwise record the known full-download limitation.

## Phase 4 — activation

- [ ] Submit source/CI/artifact/Hands/QA and limitation report for activation approval.
- [ ] Activate only the explicitly approved platform(s), order, rollout, and notification policy.
- [ ] Fresh-read revision, rollout, scope, share, and artifact SHA after each activation.
- [ ] Keep Google Play promotion/review skipped in this train.

## Stop and rollback

- [ ] Stop on any source/tree/manifest/check/artifact/signature/readback drift.
- [ ] Roll back only to the previous verified release revision; preserve audit receipts.
- [ ] Do not perform unapproved secret, migration, track, or production writes.
