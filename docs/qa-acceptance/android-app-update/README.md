# Android App Update acceptance standard

This directory defines the reusable Android full-APK update contract for Raft.
It turns an exact pair of release APKs into one owner-guarded Stamp fixture and
requires the real Android upgrade path to reach the installed target package.

The first version deliberately reuses existing Mobile and Hands behavior. It
does not add a Hands server resource and does not grant QA production rollout
authority. Until a server-enforced fixture lease exists, one named owner guards
the distribution tuple from build admission through terminal cleanup.

## Verdict boundary

The result has three independent layers:

1. `carrier_admission`: exact A/B source, bytes, package, version, signing, and
   distribution provenance;
2. `product_state_machine`: Download, Downloading, Retry, Install, Android
   installer, installed B, and B no-update behavior;
3. `terminal_cleanup`: target scope, DownloadManager state, app/device state,
   temporary access, and device lease are terminally cleaned.

Each layer is `PASS`, `FAIL`, or `NOT_RUN`. Missing fixture authority, evidence,
Android 12 capacity, or cleanup is harness/infrastructure incomplete. It is
never converted into a product failure or an inferred product pass.

## Keep full and delta lanes separate

The mandatory full-APK lane is:

- Gradle variant: `release`;
- package: `build.raft.app`;
- `HANDS_DELTA_UPDATE_ENABLED=false`;
- Hands carrier: `raft-android / debug`;
- signer: production canonical lineage;
- product path: `Download -> Downloading -> Retry/Install -> system installer`.

Target B is visible only to the dedicated Stamp update-device group. Baseline A
is installed directly from the verified CI artifact and does not need its own
Hands release.

The optional delta annex is owned separately. It uses `build.raft.app.alpha`,
the dedicated delta carrier, compile-time delta selection, and the public Hands
SDK `checkAndInstall()` path. Its UI reports coarse `Installing`. A delta pass
cannot substitute for the full-APK result.

## Source and artifact admission

Before any Hands write, freeze one manifest that validates against
[`android-app-update-fixture.v1.schema.json`](android-app-update-fixture.v1.schema.json).
The manifest must bind:

- one immutable 40-character Mobile source commit;
- workflow path and actual checkout commit for both builds;
- an owner/run-allocated monotonic fixture generation;
- A and B GitHub run, artifact, member, byte-size, and APK SHA-256;
- `aapt` package, version name, integer version code, min/target SDK;
- APK Signature Scheme v2/v3 verification and canonical signer lineage;
- the B-only Hands build/release/asset and exact device-group scope;
- an owner, deadline, rollback action, and required terminal receipt.

Semantic admission must additionally prove:

- A and B use the same source, release variant, package, and compatible signer
  lineage;
- signer lineage is ordered oldest-to-current and B's lineage has A's complete
  lineage as a prefix; unordered set intersection is not sufficient;
- `B.versionCode > A.versionCode` (the first fixture uses consecutive codes);
- the full carrier is exactly `raft-android / debug` and delta is disabled;
- B has no all/public scope, share, force-update, or unrelated group member;
- anonymous and non-member resolution remain negative.

JSON Schema cannot compare A and B fields, so these relations are mandatory
Stamp admission checks, not documentation-only expectations.

Do not dispatch two builds through a moving branch and assume they share source.
The reusable producer should accept an explicit `source_sha` (or immutable
durable ref) and record each job's actual checkout commit. A future build-only
`android-update-qa.yml` may emit both APKs and the manifest, but it must have no
publish job and no app/channel override input.

## Owner-guarded B-only provisioning

Before provisioning, the owner must:

1. name the single fixture owner;
2. allocate the generation and a deadline no later than four hours;
3. schedule a Raft reminder anchored to the canonical task thread;
4. prove the target group is empty and the resolver is negative;
5. obtain explicit authorization for this one B fixture mutation.

Provision in this order:

1. acquire a fresh Stamp device lease and install exact A from CI;
2. read back installed A identity and the non-public Hands installation ref;
3. create only B as a draft release with exact CI provenance;
4. set B's sole scope to the dedicated update-device group and read it back;
5. publish/activate B while the group is empty;
6. add only the leased installation ref;
7. prove that exact B resolves for the member and remains negative for
   anonymous and unrelated clients.

Never put private app/channel/group IDs, installation refs, signed URLs,
credentials, ADB endpoints, or keys in this repository or public evidence.
The runnable Stamp fixture carries opaque refs in its protected run record.

## Canonical product matrix

[`android-app-update-full-success.yaml`](android-app-update-full-success.yaml)
is the first logical case.

- Android 12 real/cloud device is the hard gate because the original missed
  update report came from Android 12.
- Android 14 is a control, not a replacement for Android 12.
- Coordinates freeze server-owned device facts, not caller-provided labels:
  Android 12/API 31 and Android 14/API 34, Android platform, eligible
  real/cloud-phone provider class, and the dedicated device executor lane.
- Targeted allocation revalidates those same facts; it cannot bypass capability
  matching by naming an incompatible instance.
- An unavailable required coordinate makes the logical result incomplete.

The case proves:

- exact A before launch and exact B resolver offer;
- one DownloadManager owner despite repeated taps;
- same-interaction `Downloading` plus disabled action;
- progress and foreground/background ownership;
- bounded network failure, actionable Retry, and successful continuation;
- Install and the Android unknown-source/system-installer boundary;
- cold B package/version/signer readback;
- preserved login/server/draft/cache state;
- B no-update and zero stale A callback;
- zero app crash/ANR;
- terminal distribution and device cleanup.

Installer-cancel/retry and delta success/corrupt-fallback remain separate
focused cases. They must not dilute or replace the full-success matrix.

## Required evidence

The case requires all of the following:

- uncut `recording` from A identity through B readback;
- checkpoint `screenshot` frames;
- `ui_dump` at interactive states;
- app-scoped `logcat` bound to package, PID, and process generation;
- executor `steps_log`;
- machine-readable `build_info` for both carriers and before/after package;
- `checkpoint` receipts for resolver admission and cleanup;
- a final `report` with the three result layers.

Evidence must identify the single DownloadManager ID and show that repeated
taps did not create another owner. Missing or unconfirmed required evidence is
harness incomplete. A visual `Downloading` label by itself is not a pass.

## Terminal cleanup

Cleanup runs in `finally`, even after timeout or product failure:

1. remove the leased installation ref from the target group;
2. prove anonymous, random non-member, and former-member resolver negatives;
3. cancel/deactivate B and read its terminal status; A has no release to cancel;
4. clear fixture DownloadManager rows and temporary APK/cache state;
5. uninstall or reset the high-version QA package;
6. restore network and unknown-source permission state;
7. force-stop the app, stop isolated ADB, delete one-time keys, release the
   device lease, and read terminal `released`;
8. store an immutable run-bound cleanup receipt.

`terminal_cleanup != PASS` keeps the overall result on HOLD even when every UI
assertion passed.

## Current integration gap

The checked-in case and schema are the acceptance contract. A runnable Stamp
case still requires four P0 platform changes:

1. one immutable per-run A/B bundle authority (canonical JSON, digest, size
   cap, frozen timestamp, and fixture-generation uniqueness). Every cell and
   attempt binds the same digest; claim/get-run expose canonical bytes; retry
   reuses rather than rewrites it. Existing single-artifact columns must equal
   baseline A;
2. closed semantic validation, including ordered signer-prefix compatibility,
   full/delta carrier isolation, `B.versionCode > A.versionCode`, and owner
   activation/deadline/expiry ordering within the four-hour window;
3. server-owned required device facts frozen on every run case and enforced by
   ordinary and targeted allocation. The case's entire required-coordinate set
   must be parsed and enforced; missing Android 12 cannot disappear from the
   logical denominator or let Android 14 appear complete;
4. one persisted terminal cleanup receipt bound to run case, attempt, bundle
   digest, B-only distribution cleanup, device allocation, and terminal release.
   A boolean `cleanup_ready` is not evidence.

Production also needs an admitted Android 14 executor coordinate and real
Android 12 capacity; a name in YAML is not a capability.

Until those exist and an exact B fixture is authorized, this case is not
eligible for scheduled execution and its product verdict remains `NOT_RUN`.
