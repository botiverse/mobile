import test from "node:test";
import assert from "node:assert/strict";
import { verifyPinnedAndroidIdentity } from "./verify_pinned_android_identity.mjs";

const observed = { sha256: "8".repeat(64), version: "1.0", versionCode: "1", packageId: "build.raft.app" };
const manifest = { platform: "android", source_commit: "4".repeat(40), source_commit_verified: true, source_commit_observed: "4444444444", apk_sha256: observed.sha256, app_version: "1.0", version_code: "1", package: "build.raft.app" };
const expected = { ...observed, sourceCommit: manifest.source_commit };

test("derives strict claim identity from manifest plus observed APK", () => {
  assert.deepEqual(verifyPinnedAndroidIdentity({ manifest, observed, expected }), expected);
});

for (const key of Object.keys(expected)) {
  test(`rejects frozen ${key} mismatch`, () => {
    assert.throws(() => verifyPinnedAndroidIdentity({ manifest, observed, expected: { ...expected, [key]: "wrong" } }), /mismatch/);
  });
}

test("rejects an unverified synthesized source manifest", () => {
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: { ...manifest, source_commit_verified: false }, observed, expected }), /does not verify/);
});

test("rejects manifest SHA drift from downloaded APK", () => {
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: { ...manifest, apk_sha256: "9".repeat(64) }, observed, expected }), /SHA mismatch/);
});

test("rejects a manifest without an artifact SHA binding", () => {
  const { apk_sha256, ...unbound } = manifest;
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: unbound, observed, expected }), /invalid manifest apk_sha256/);
});

test("rejects a non-Android artifact manifest", () => {
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: { ...manifest, platform: "ios" }, observed, expected }), /platform is not android/);
});

test("rejects a manifest that never claims verification", () => {
  const { source_commit_verified, ...unclaimed } = manifest;
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: unclaimed, observed, expected }), /does not verify/);
});

test("surfaces why the source commit could not be verified", () => {
  assert.throws(
    () => verifyPinnedAndroidIdentity({ manifest: { ...manifest, source_commit_verified: false, source_commit_unverified_reason: "no embedded commit" }, observed, expected }),
    /does not verify source commit: no embedded commit/,
  );
});

test("rejects a verified manifest without the APK-embedded commit", () => {
  const { source_commit_observed, ...bare } = manifest;
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: bare, observed, expected }), /invalid manifest source_commit_observed/);
});

test("rejects an APK-embedded commit that is not a prefix of the manifest commit", () => {
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: { ...manifest, source_commit_observed: "5555555" }, observed, expected }), /embedded commit mismatch/);
});

test("rejects an APK-embedded short sha below 10 hex chars", () => {
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest: { ...manifest, source_commit_observed: "4444444" }, observed, expected }), /embedded commit mismatch/);
});

test("accepts a short frozen sha the workflow resolved to the manifest's full sha", () => {
  const short = "4".repeat(12);
  const result = verifyPinnedAndroidIdentity({ manifest: { ...manifest, source_commit_expected: short }, observed, expected: { ...expected, sourceCommit: short } });
  assert.equal(result.sourceCommit, manifest.source_commit);
});

test("rejects a short frozen sha the manifest did not record as resolved", () => {
  assert.throws(() => verifyPinnedAndroidIdentity({ manifest, observed, expected: { ...expected, sourceCommit: "4".repeat(12) } }), /frozen sourceCommit mismatch/);
});
