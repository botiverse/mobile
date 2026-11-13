import test from "node:test";
import assert from "node:assert/strict";
import { commitFromVersionName, dexHexStrings, observeApkSourceCommit } from "./observe_apk_source_commit.mjs";

const full = "52e0b3b4930000000000000000000000000000aa";

function dexWith(...strings) {
  const parts = [Buffer.from([0x64, 0x65, 0x78, 0x0a, 0x00])];
  for (const value of strings) parts.push(Buffer.from([value.length]), Buffer.from(value, "latin1"), Buffer.from([0]));
  return Buffer.concat(parts);
}

test("parses +sha from alpha versionName", () => {
  assert.deepEqual(commitFromVersionName("1.0.0-alpha+52e0b3b493"), { commit: "52e0b3b493", dirty: false });
  assert.deepEqual(commitFromVersionName("1.0.0-alpha+52e0b3b493-dirty"), { commit: "52e0b3b493", dirty: true });
  assert.equal(commitFromVersionName("1.5.0"), null);
});

test("verifies from versionName prefix", () => {
  assert.deepEqual(observeApkSourceCommit({ versionName: "1.0.0-alpha+52e0b3b493", expected: full }), { verified: true, observed: "52e0b3b493", method: "versionName", reason: "" });
});

test("fails on a contradicting versionName commit", () => {
  assert.throws(() => observeApkSourceCommit({ versionName: "1.0.0-alpha+deadbeef12", dex: dexWith("52e0b3b493"), expected: full }), /does not match/);
});

test("dirty build is unverified", () => {
  const result = observeApkSourceCommit({ versionName: "1.0.0-alpha+52e0b3b493-dirty", expected: full });
  assert.equal(result.verified, false);
  assert.match(result.reason, /dirty/);
});

test("extracts hex strings from dex string data", () => {
  assert.deepEqual(dexHexStrings(dexWith("hello", "52e0b3b493", "XYZ1234567", "abc")), ["52e0b3b493"]);
});

test("falls back to BuildConfig.GIT_COMMIT in dex", () => {
  const result = observeApkSourceCommit({ versionName: "1.5.0", dex: dexWith("cafebabe00", "52e0b3b493"), expected: full });
  assert.deepEqual(result, { verified: true, observed: "52e0b3b493", method: "dex:BuildConfig.GIT_COMMIT", reason: "" });
});

test("unverified when no embedded commit matches", () => {
  const result = observeApkSourceCommit({ versionName: "1.5.0", dex: dexWith("unknown", "cafebabe00"), expected: full });
  assert.equal(result.verified, false);
  assert.match(result.reason, /no embedded commit/);
});

test("unverified when nothing is readable", () => {
  assert.equal(observeApkSourceCommit({ versionName: "1.5.0", dex: null, expected: full }).verified, false);
});

test("rejects a non-sha frozen commit", () => {
  assert.throws(() => observeApkSourceCommit({ versionName: "1.5.0", expected: "main" }), /40-char/);
});

test("short embedded sha below the minimum is unverified, not trusted", () => {
  const fromName = observeApkSourceCommit({ versionName: "1.0.0-alpha+52e0b3b", expected: full });
  assert.equal(fromName.verified, false);
  assert.match(fromName.reason, /shorter than 10/);
  const fromDex = observeApkSourceCommit({ versionName: "1.5.0", dex: dexWith("52e0b3b"), expected: full });
  assert.equal(fromDex.verified, false);
  assert.match(fromDex.reason, /shorter than 10/);
});

test("prefers the longest matching dex string", () => {
  const result = observeApkSourceCommit({ versionName: "1.5.0", dex: dexWith("52e0b3b", "52e0b3b4930000"), expected: full });
  assert.equal(result.observed, "52e0b3b4930000");
  assert.equal(result.verified, true);
});
