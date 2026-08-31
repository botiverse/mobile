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
