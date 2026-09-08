---
name: kuikly-sdk-upgrade
description: Upgrade and validate Raft Mobile's immutable Kuikly release set across Gradle/KMP, Android renderer, iOS XCFramework/Pod, OHOS KLIB/HAR/lockfile and CI admission. Use for Kuikly version bumps, mixed-version/linkage failures, renderer alignment or release-manifest consumption; normal Mobile upgrades must consume an exact Raft Artifacts manifest with no gitlink, MavenLocal, local Pod/HAR or source-build fallback.
---

# Kuikly SDK Upgrade

## Authority model

Raft Mobile consumes an immutable Kuikly release set. The completion manifest is the authority for source identity, artifact closure and byte digests; a Kuikly source checkout is provenance only, never a consumer fallback.

Current main uses `2.24.0-raft.2` as an example, but every task must discover the live pins instead of copying that value.

Normal consumer work must not:

- build or publish `third_party/kuikly-ui`;
- run `publishToMavenLocal` or restore the deleted `scripts/build_kuikly_fork.sh` path;
- point iOS at a local source pod;
- point OHOS at a checked-in/staging HAR;
- silently fall through to Tencent Maven or another repo for `com.tencent.kuikly-open`.

The repository can still contain `mavenLocal()` for unrelated dependencies. Kuikly safety comes from `exclusiveContent` routing of the Kuikly groups to `RaftArtifactsKuiklyRelease`, not from a repository-wide ban on MavenLocal.

Publishing a new Kuikly release set is a separate, explicitly authorized producer workflow. Do not turn a Mobile version-bump request into publication.

## Discover the live release contract

From the Mobile repository root:

```bash
rg -n "KUIKLY_VERSION|kuiklyAndroid|kuiklyOhos|kuikly_release|@kuikly-open/render" \
  compose/buildSrc compose/gradle compose/iosApp/Podfile compose/ohosApp/entry -S

sed -n '1,120p' compose/gradle/kuikly-release-closure.json
rg -n "KUIKLY_RELEASE_VERSION|DEFAULT_RELEASE|RaftArtifactsKuiklyRelease" \
  scripts/ci compose/settings.gradle.kts compose/settings.ohos.gradle.kts -S
```

Record:

- release-set version;
- normal Kotlin plane and OHOS Kotlin plane;
- complete GAV closure count;
- manifest locator/digest and frozen Kuikly source commit/tree/tag;
- iOS binary/podspec and OHOS HAR coordinates/digests.

For the current v1 contract, the checked-in closure has 37 GAVs (28 normal, 9 OHOS), while the complete manifest enumerates 920 immutable objects. Treat these as schema assertions in the existing gate, not timeless marketing constants.

## Admission before editing pins

The target release set must already be complete and immutable in Raft Artifacts:

```bash
KUIKLY_RELEASE_VERSION=<target> \
  scripts/ci/verify_kuikly_release_manifest.sh
```

Default mode validates the completion locator, schema, source identity and exact closure. For release/audit evidence, read and hash the full object set:

```bash
KUIKLY_RELEASE_VERSION=<target> KUIKLY_FULL_READBACK=1 \
  scripts/ci/verify_kuikly_release_manifest.sh
```

`ALL_ABSENT`, `PARTIAL`, `CONFLICT`, a missing object or a digest mismatch blocks consumption. Do not substitute a local build.

## Update the consumer as one atomic family

Inspect the current source contract first; the affected files normally include:

- `compose/buildSrc/src/main/java/KotlinBuildVar.kt`
- `compose/gradle/libs.versions.toml`
- `compose/gradle/kuikly-release-closure.json`
- default release values in `scripts/ci/verify_kuikly_release_manifest.sh`, `kuikly_release_manifest_gate.py`, `fetch_kuikly_release_artifact.py`, and renderer verification
- `compose/iosApp/Podfile` and `Podfile.lock`
- `compose/ohosApp/entry/oh-package.json5` and `oh-package-lock.json5`
- tests/workflows that intentionally pin the exact release contract

Keep normal and OHOS artifact suffixes distinct. The iOS Podfile must fetch the manifest-named binary podspec/XCFramework; the OHOS package must point to the manifest-fetched HAR under `compose/build/kuikly`, with lockfile identity matching it.

Do not hand-edit generated lockfiles into a desired answer. Run the repository's normal Pod/OHPM resolution after manifest-derived artifacts are present, then review the exact diff.

## Consumer gates

Run the repository's existing source contracts before expensive builds:

```bash
python3 scripts/ci/test_kuikly_raft_consumer_contract.py
scripts/ci/verify_kuikly_release_manifest.sh
scripts/ci/verify_kuikly_ohos_renderer.sh
```

These must prove:

- exact pins and closure agree;
- Kuikly groups resolve exclusively from Raft Artifacts;
- no consumer gitlink or deleted local publisher remains;
- iOS has no source-pod fallback;
- OHOS has no staging/checked-in HAR fallback;
- release and test workflows run manifest admission;
- fetched podspec/XCFramework/HAR bytes match manifest size and SHA-256.

Then compile every consumed plane on the exact head:

- shared Android/KMP and Android host;
- iOS KMP targets plus actual iOS host/Pod integration;
- OHOS shared KLIB plus Hvigor host/HAR consumption;
- any JS/macOS plane actually retained in the closure and touched by the bump.

Use an empty dependency cache for the authoritative cross-platform readback. A warm local build is useful diagnostics, not proof that the immutable release is complete.

## Mixed-version diagnosis

For `IrLinkageError`, missing renderer types/selectors, or platform-only crashes:

1. Record the resolved coordinate and byte digest for the caller and callee artifacts.
2. Compare both to the same manifest release/source identity.
3. Inspect the actual KLIB/XCFramework/HAR symbol only after identity is known.
4. Fix the release-set or consumer pin mismatch; do not delete a valid callsite to hide stale artifacts.

Build/HAR/Pod success does not prove device runtime. Bind runtime evidence to Mobile source SHA, release-manifest digest, signed carrier SHA and device identity.

## Handoff

Report old/new release set, normal/OHOS planes, manifest/source identity, closure and full-readback result, resolved iOS/OHOS binary digests, consumer-gate results, platform compiles and physical-device status. Keep publication, Mobile consumption, app release and deployment authorizations separate.
