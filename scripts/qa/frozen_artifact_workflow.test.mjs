import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const ios = fs.readFileSync(new URL("../../.github/workflows/qa-acceptance-ios.yml", import.meta.url), "utf8");
const android = fs.readFileSync(new URL("../../.github/workflows/qa-acceptance.yml", import.meta.url), "utf8");
const infra = fs.readFileSync(new URL("../../.github/workflows/qa-infra-test.yml", import.meta.url), "utf8");

test("iOS frozen artifact inputs match Stamp dispatch and replacement is opt-in", () => {
  for (const name of ["app_run_id", "artifact_sha256", "artifact_source_commit", "artifact_version", "artifact_version_code", "artifact_package_id"]) {
    assert.match(ios, new RegExp(`\\n      ${name}:`));
  }
  assert.match(ios, /name: Build for testing \(app \+ loop harness\)[\s\S]*?name: Download frozen iOS Simulator app/);
  assert.match(ios, /name: Download frozen iOS Simulator app\n        if: \$\{\{ inputs\.app_run_id != '' \}\}/);
  assert.match(ios, /name: Replace app product with frozen artifact\n        if: \$\{\{ inputs\.app_run_id != '' \}\}/);
  assert.match(ios, /PINNED_METADATA: pinned-ios-app\/metadata\.json/);
  assert.match(ios, /name: Install app\n        run:/);
  assert.match(ios, /"artifact_sha256": "%s"[\s\S]*"binary_sha256": "%s"/);
  assert.match(ios, /"\$artifact_hash" "\$binhash" "\$bundle_id"/);
  const replaceRun = ios.match(/name: Replace app product with frozen artifact[\s\S]*?\n      - name: Install app/)?.[0] || "";
  assert.doesNotMatch(replaceRun, /run:[\s\S]*\$\{\{ inputs\.artifact_/);
  assert.doesNotMatch(ios, /\n      app_url:/);
});

test("iOS agent login fallback targets the QA account's current server", () => {
  assert.match(ios, /DEFAULT_GOAL:[\s\S]*?choose 'OranixSlockTest'/);
  assert.doesNotMatch(ios, /DEFAULT_GOAL:[\s\S]*?choose 'Raft Android'/);
});

test("the acceptance goal forbids the production server by name, not just by omission", () => {
  // Removing the production server from the goal is necessary but not sufficient.
  // The goal is a prompt: given only "choose 'OranixSlockTest'", a model that cannot
  // find that entry will reach for the nearest-looking name, and 'Raft Android' is
  // the production server (artin, 2026-08-31). The prohibition therefore has to be
  // stated, and stated here — a positive instruction alone leaves the failure mode open.
  //
  // Asserted separately from the line above because the two can fail independently:
  // that one dies if the production name is re-introduced as the target, this one dies
  // if the prohibition is dropped. Without this assertion the clause could be deleted
  // and every existing test would stay green.
  const goal = ios.match(/DEFAULT_GOAL: >-\n([\s\S]*?)\n\njobs:/)?.[1] || "";
  assert.notStrictEqual(goal, "", "DEFAULT_GOAL block not found — the assertions below would pass vacuously");
  assert.match(goal, /do NOT choose it/, "goal must explicitly forbid the production server");
  assert.match(goal, /'Raft Android'/, "the prohibition must name the server it forbids");
  assert.match(goal, /never\s+substitute the closest-looking name/, "goal must forbid nearest-match substitution");
});

test("the Android acceptance goal names its target server and forbids production", () => {
  // The iOS goal was hardened (PR #2277 removed the production target, PR #2282 added
  // the prohibition). The Android goal had NEITHER: no target server, no prohibition,
  // and no assertion — looser than iOS ever was, because without a named target there
  // is not even a "closest-looking name" to reason about. Same instruction from artin
  // (2026-08-31), same exposure, so the same three properties are pinned here.
  //
  // Asserted against `android`, not `ios`: the existing goal assertions only ever
  // looked at the iOS workflow, which is why this gap survived the iOS hardening.
  const goal = android.match(/DEFAULT_GOAL: >-\n([\s\S]*?)\n\njobs:/)?.[1] || "";
  assert.notStrictEqual(goal, "", "Android DEFAULT_GOAL block not found — the checks below would pass vacuously");
  assert.match(goal, /choose\s+'OranixSlockTest'/, "goal must name the server to select");
  assert.match(goal, /do NOT choose it/, "goal must explicitly forbid the production server");
  assert.match(goal, /'Raft Android'/, "the prohibition must name the server it forbids");
  assert.match(goal, /never\s+substitute the closest-looking name/, "goal must forbid nearest-match substitution");
});

test("neither goal can offer the production server as a selectable target", () => {
  // The guards above are ADJACENCY-based: iOS asserts the goal lacks the literal
  // `choose 'Raft Android'`. That shape misses the way this would realistically come
  // back (@LiBai, 2026-08-31) — production added as an *alternative*:
  //
  //     "If a server picker appears choose 'OranixSlockTest' or 'Raft Android'."
  //
  // never produces the literal `choose 'Raft Android'`, so it passes every existing
  // assertion. Line wrapping defeats adjacency too: the Android goal already wraps
  // between `choose` and `'OranixSlockTest'`, so the same evasion happens by accident.
  //
  // Pinned by COUNT instead of adjacency. After #2282/#2283 the production name appears
  // legitimately exactly once per goal — inside the prohibition. A second occurrence is,
  // by construction, somewhere that is not the prohibition, whatever words surround it.
  //
  // Both platforms in one loop deliberately: the original hole existed because the goal
  // assertions only ever read `ios`, so Android drifted looser unnoticed. A per-platform
  // test would let that happen again; this one cannot pass for one platform and skip the
  // other.
  for (const [platform, yml] of [["iOS", ios], ["Android", android]]) {
    const goal = yml.match(/DEFAULT_GOAL: >-\n([\s\S]*?)\n\njobs:/)?.[1] || "";
    assert.notStrictEqual(goal, "", `${platform} DEFAULT_GOAL block not found — the count below would be vacuous`);
    // Normalised so a re-wrap of the YAML block cannot change the verdict.
    const flat = goal.replace(/\s+/g, " ");
    const mentions = flat.match(/'Raft Android'/g) || [];
    assert.strictEqual(mentions.length, 1, `${platform} goal must name the production server exactly once, and only to forbid it — found ${mentions.length}`);
    assert.match(flat, /'Raft Android' appears, do NOT choose it/, `${platform} goal's single mention of the production server must be the prohibition itself`);
  }
});

test("Android exposes the same frozen identity names for strict claim overrides", () => {
  for (const name of ["apk_run_id", "apk_artifact_id", "artifact_sha256", "artifact_source_commit", "artifact_version", "artifact_version_code", "artifact_package_id"]) {
    assert.match(android, new RegExp(`\\n      ${name}:`));
  }
  assert.match(android, /name: Verify frozen Android artifact identity/);
  assert.match(android, /resolve_pinned_android_artifact\.mjs/);
  assert.match(android, /actions\/artifacts\/\$APK_ARTIFACT_ID\/zip/);
  assert.match(android, /gh run download "\$APK_RUN_ID"[\s\S]*-n "\$artifact_name"/);
  assert.match(android, /requires exactly one APK and one build-info\.json/);
  assert.match(android, /run-name: Stamp \$\{\{ inputs\.stamp_run_case_id/);
  assert.match(android, /name: Bind pre-claim workflow to Stamp/);
  assert.match(android, /name: Report pre-claim infrastructure failure/);
  assert.doesNotMatch(android, /STAMP_CLAIM_SOURCE_COMMIT: \$\{\{ inputs\.artifact_source_commit \}\}/);
});

test("QA infrastructure contract tests run on relevant pull requests", () => {
  for (const path of [".github/workflows/qa-acceptance.yml", ".github/workflows/qa-acceptance-ios.yml", "scripts/qa/**"]) {
    assert.ok(infra.includes(`- "${path}"`));
  }
  assert.match(infra, /pull_request:/);
  assert.match(infra, /node --test scripts\/qa\/\*\.test\.mjs/);
});
