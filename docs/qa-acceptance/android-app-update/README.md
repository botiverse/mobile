# Android App Update QA

This directory defines the repeatable, owner-guarded Android in-app-update
acceptance contract. It proves a real same-package, same-signer A-to-B system
upgrade. A rendered update page or a delta-only result is not sufficient.

## Carrier lanes

The mandatory full-APK lane uses release `build.raft.app`, the canonical
production signer lineage, `HANDS_DELTA_UPDATE_ENABLED=false`, and the existing
Hands `raft-android/debug` test carrier. Baseline A is installed directly from
the verified CI artifact. Only target B may receive a draft release targeted to
the `Palm Stamp QA devices` group.

The optional delta lane uses `build.raft.app.alpha` and
`raft-android-alpha-delta/alpha-delta`. It reports patch/fallback and coarse
`Installing` behavior separately and can never substitute for the full
`Download -> Downloading -> Install` verdict.

## Producing A/B

Run `Android App Update QA Fixture` with a reviewed full `source_sha`, a
positive owner/run-monotonic `fixture_generation`, and A/B build components.
The workflow builds both release APKs from the same immutable checkout, never
publishes to Hands, and emits `android-update-qa-fixture` plus uniquely named A
and B APK artifacts. Any source/package/version/signer/artifact mismatch voids
the whole pair.

The build-pair manifest is admission evidence, not authorization to publish.
Before the first external write, independently verify the APK bytes with AAPT
and apksigner and complete the final fixture described by
`android-app-update-fixture.v1.schema.json`.

The JSON Schema is only the portable wire contract. Stamp must additionally
canonicalize the JSON with a size cap, persist one run-level digest, return the
same bytes/digest from create, claim, get-run, and retry, and reject a reused
fixture generation whose digest differs. Signer lineage is ordered oldest to
current; B's lineage must have A's lineage as a prefix. Full mode rejects delta
assets. These are server validator requirements, not reviewer conventions.

## Required product assertions

1. Install and read back exact A identity.
2. Resolve exact B only for the scoped test installation.
3. The first Download tap enters `Downloading` in the same frame.
4. Repeated taps create no second DownloadManager id.
5. Bytes/progress advance and foreground/background retain one owner.
6. Failure or cancellation exposes an actionable Retry.
7. Completion exposes Install and covers unknown-sources permission handling.
8. Android's system installer upgrades A to exact B.
9. Relaunch reads back B package/version/signers and preserved user state.
10. B reports no update and no stale A callback rewrites B state.
11. Crash/ANR scans are clean.
12. Terminal distribution and device cleanup passes.

For the task #134 acceptance cycle, Android 14 is the single required device
coordinate. Android 12 is explicitly owner-waived and must be reported
`NOT RUN`, never inferred as PASS. This denominator change is bound to the owner
decision in `#反馈:1bc169a5` message `84dc5c7f`; it avoids procuring a device only
to satisfy the earlier matrix convention and does not weaken any product,
provenance, retry, installer, or cleanup assertion.

Stamp must freeze and enforce the non-empty Android 14 required-coordinate set
at run creation. A coordinate label alone is not admission: server-owned
required facts must select an exact Android/API/provider capability, and
targeted allocations must be rechecked against the same facts. Missing Android
14, provenance, or cleanup can never be upgraded to product PASS. A future
Android 12 run is additional coverage and must be reported independently.

The fixture must also carry the Android 12 waiver as machine-readable coverage:
coordinate `android12-update-hard`, status `NOT_RUN`, reason `owner-waived`, and
durable decision ref `#反馈:1bc169a5:84dc5c7f`. Missing the waiver or reporting
that coordinate as PASS fails admission; prose alone is not sufficient.

## Single-writer window and cleanup

Version 1 is a repeatable owner-guarded SOP, not concurrency-safe arbitrary
self-service. Before targeting B, record one named owner, a monotonic
generation, a deadline no later than four hours, and an anchored reminder.

Cleanup is part of the verdict, in this order:

1. Remove the test installation from the QA device group.
2. Prove anonymous, random non-member, and former-member resolver negatives.
3. Cancel/deactivate the B fixture; A has no Hands release.
4. Clear DownloadManager entries, cached APKs, app data or uninstall the high
   QA version, and restore unknown-sources permission and network state.
5. Stop isolated ADB, delete the one-time key, and release the Palm lease to
   terminal `released`.

If terminal cleanup is not PASS, the overall result remains HOLD.

The cleanup gate consumes one unique run-bound checkpoint tied to run case,
attempt, fixture digest, B-only Hands scope/cancel readback, and the exact
device allocation/release. A boolean `cleanup_ready` value by itself is not a
terminal receipt.

## Evidence

The canonical case requires recording, screenshots, UI dumps, scoped logcat,
steps log, build info, cleanup checkpoint, and a layered report. Never upload
credentials, signed URLs, raw ADB keys/endpoints, account values, or private
installation identifiers.

Use `android-app-update-full-success.v1.yaml` as the versioned full-lane case
contract. `android-app-update-fixture.v1.schema.json` defines the immutable
A/B + B-only distribution coordinate consumed by Stamp.
