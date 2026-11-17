// Release pre-login (release_prelogin_lib.mjs / release_prelogin.mjs) with fake values only: no
// device, no network, no real account, server or credential. Fake adb / aapt are small shell
// scripts in temp dirs. Covers the blockers of Argus's review of PR 4946 (round 1).
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { spawn, spawnSync } from "node:child_process";
import { setTimeout as wait } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import {
  ALLOWLIST_CONTRACT,
  SIGN_IN_FLAGS,
  SPEC_CONTRACT,
  UPLOAD_FILES,
  assessCaptureWindow,
  assessCleanup,
  assessReadiness,
  attemptFromSpec,
  attemptOutcome,
  canonicalJson,
  caseMetaFromYaml,
  componentStateFromDumpsys,
  installedFromDumpsys,
  parseReadinessLog,
  preloginEligibility,
  receiptsLeak,
  reportGate,
  runPreloginCleanup,
  runPreloginLogin,
  sha256Hex,
  uploadProblem,
  userRef,
} from "./release_prelogin_lib.mjs";
import { DEFERRED_APP_SESSION, runOutcome, summarizeCleanup } from "./release_auto_login_lib.mjs";
import { readRecord, writeRecord } from "./login_phase.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../..");
const CLI = path.join(HERE, "release_prelogin.mjs");
const mktemp = () => fs.mkdtempSync(path.join(os.tmpdir(), "prelogin-test-"));
const rm = (d) => fs.rmSync(d, { recursive: true, force: true });

// ---------- fixtures (fake) ----------
const CASE_YAML = "id: fake-ordinary-case\ntitle: fake\ntier: L1\nauth_fixture: alpha-qa\nandroid:\n  steps:\n    - goal: open home\n";
const GOAL = "fake goal: open home and two channels";
const ARTIFACT = { sha256: "a1".repeat(32), package_id: "build.raft.app", version_name: "9.9.9-qa", version_code: 999 };
const SPEC = {
  contract: SPEC_CONTRACT,
  run_case_id: "rc-fake-1",
  attempt: { attempt_id: `pla_${"1f".repeat(16)}`, nonce: "ab".repeat(32) },
  case: { id: "fake-ordinary-case", revision: 3, platform: "android", case_sha256: sha256Hex(CASE_YAML), goal_sha256: sha256Hex(GOAL), auth_mode: "release-prelogin" },
  target: { env: "staging", account_user_id: "usr-fake-1", server_id: "srv-fake-1", server_slug: "fake-slug", server_name: "Fake QA Server" },
  artifact: ARTIFACT,
};
const ALLOW = {
  contract: ALLOWLIST_CONTRACT,
  cases: { "fake-ordinary-case": { revision: 3, case_sha256: sha256Hex(CASE_YAML), platform: "android", intent: "ordinary", class: "ordinary", status: "compatible" } },
  never: { "login-email-password": "login-ui" },
};
const CLAIM = { spec_sha256: sha256Hex(canonicalJson(SPEC)), attempt_id: SPEC.attempt.attempt_id };
const BUILD = { ok: true, debuggable: false, ...ARTIFACT };
const elig = (over = {}) => preloginEligibility({
  spec: SPEC, claimRelease: CLAIM, runCaseId: "rc-fake-1", goal: GOAL, artifactSha256Input: ARTIFACT.sha256,
  build: BUILD, bootstrapRequired: false, caseBytes: CASE_YAML, allowList: ALLOW, ...over,
});
const ATTEMPT = attemptFromSpec(SPEC, { workflow: { runId: "100", runAttempt: "2" } });
const PROCESS = "4242:987654";
const REQ = "cd".repeat(16);
const INSTALLED = { ok: true, package: ARTIFACT.package_id, version_name: ARTIFACT.version_name, version_code: ARTIFACT.version_code };
const receiptLine = (over = {}, { pid = 4242, stamp = "11-17 10:00:05.000", requestId = REQ } = {}) => {
  const receipt = {
    contract: "raft-qa-release-readiness/v2", attempt_id: ATTEMPT.attempt_id, nonce: ATTEMPT.nonce, request_id: requestId,
    package: ARTIFACT.package_id, version_name: ARTIFACT.version_name, version_code: ARTIFACT.version_code,
    pid: 4242, process_start_ticks: "987654", signed_in: true, user_ref: userRef(ATTEMPT.nonce, "usr-fake-1"),
    selected_server_id: "srv-fake-1", selected_server_slug: "fake-slug", ...over,
  };
  return `${stamp}  ${pid}  ${pid + 7} I RaftQaReadiness: RAFT_QA_READINESS ${JSON.stringify(receipt)}`;
};
const ready = (over = {}) => assessReadiness({ lines: parseReadinessLog(receiptLine()), attempt: ATTEMPT, requestId: REQ, processBefore: PROCESS, processAfter: PROCESS, installed: INSTALLED, ...over });
const signInSummary = (over = {}) => ({
  phase: "sign-in", no_capture: true, defer_app_session: true, strict_cleanup: true, screenshots_written: 0,
  account_user_ref: userRef(ATTEMPT.nonce, "usr-fake-1"), result: "signed_in",
  steps: [{ step: 6, name: "deliver-callback-to-app", result: "pass" }, { step: 7, name: "verify-signed-in", result: "pass", detail: { exchange: "exchanged", screen: "choose_server" } }],
  cleanup: { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: DEFERRED_APP_SESSION },
  ...over,
});
const CLEAN = assessCleanup({ loginStarted: true, signInSummary: signInSummary(), signOutSummary: { cleanup: { app_session: "app_reported_server_logout_2xx" } }, receiverState: "disabled" });

/** A fake device: answers each readiness request with a receipt line for THAT request. */
function fakeDevice({ summary = signInSummary(), recorderStops = true, watch = { polls: 5, seen: false, failed: false }, after = false, files = [], captures = 0, receipt = {}, noAnswer = false, extraLog = "" } = {}) {
  const calls = [];
  let captured = 0;
  let lastReq = null;
  let n = 0;
  return {
    calls,
    captureCount: () => captured,
    stopRecorder: async () => { calls.push("stopRecorder"); return recorderStops; },
    startWindow: async () => { calls.push("startWindow"); return true; },
    watchRecorder: () => { calls.push("watchRecorder"); return { stop: async () => watch }; },
    recorderRunning: async () => after,
    filesWrittenSince: async () => files,
    enableReceiver: async () => { calls.push("enableReceiver"); return "enabled"; },
    runSignIn: async (flags) => { calls.push(["runSignIn", flags]); captured += captures; return summary; },
    selectServer: async (name) => { calls.push(["selectServer", name]); },
    installed: async () => INSTALLED,
    appProcess: async () => PROCESS,
    newRequestId: () => (n += 1).toString(16).padStart(32, "0"),
    requestReadiness: async (req) => { calls.push(["requestReadiness", req]); lastReq = req.requestId; },
    readReadinessLog: async () => `${extraLog}\n${noAnswer ? "" : receiptLine(receipt, { requestId: lastReq })}`,
    sleep: async () => {},
  };
}

// ---------- shared vector ----------
test("userRef matches the Kotlin receiver's fixed vector (nonce-bound sha256, minimisation)", () => {
  assert.equal(userRef("ab".repeat(32), "usr-fake-1"), "1b12673dbe0d552e3de10c8725bc8caaff65a00216c29226f2ead7aaa856f225");
  assert.notEqual(userRef("cd".repeat(32), "usr-fake-1"), userRef("ab".repeat(32), "usr-fake-1"));
});

// ---------- blocker 3: Testbed-frozen spec + attempt ----------
test("happy path eligibility: claim-bound spec, goal, artifact, case bytes, compatible allow-list entry", () => {
  assert.deepEqual(elig(), { eligible: true, reason: "testbed_frozen_and_mobile_compatible" });
  assert.equal(ATTEMPT.attempt_id, SPEC.attempt.attempt_id);
  assert.equal(ATTEMPT.nonce, SPEC.attempt.nonce);
  assert.deepEqual(ATTEMPT.workflow, { run_id: "100", run_attempt: "2" });
  assert.equal(ATTEMPT.target.account_user_ref, userRef(SPEC.attempt.nonce, "usr-fake-1"));
  assert.ok(!JSON.stringify(ATTEMPT).includes("usr-fake-1"), "the raw account id never enters the attempt record");
});

test("a caller-asserted spec is refused: no claim binding, wrong digest, wrong attempt, run case, goal", () => {
  assert.equal(elig({ claimRelease: null }).reason, "spec_not_bound_by_testbed_claim");
  assert.equal(elig({ claimRelease: { ...CLAIM, spec_sha256: "00".repeat(32) } }).reason, "claim_binding_mismatch");
  assert.equal(elig({ claimRelease: { ...CLAIM, attempt_id: `pla_${"2e".repeat(16)}` } }).reason, "claim_binding_mismatch");
  assert.equal(elig({ spec: { ...SPEC, target: { ...SPEC.target, server_id: "srv-other" } } }).reason, "claim_binding_mismatch");
  assert.equal(elig({ runCaseId: "" }).reason, "run_case_mismatch");
  assert.equal(elig({ goal: "log in with a password" }).reason, "goal_mismatch");
  assert.match(elig({ spec: { ...SPEC, attempt: { attempt_id: "pla_x", nonce: "y" } } }).reason, /^spec_invalid: .*attempt\.attempt_id/);
  assert.match(elig({ spec: { ...SPEC, case: { ...SPEC.case, auth_mode: "alpha-qa" } } }).reason, /case\.auth_mode/);
  assert.match(elig({ spec: { ...SPEC, target: { ...SPEC.target, refresh_token: "x" } } }).reason, /must not be in the spec/);
});

test("artifact and build: aapt failure, debuggable, wrong sha or identity are refused (never assumed release)", () => {
  assert.equal(elig({ build: { ok: false } }).reason, "build_identity_unknown");
  assert.equal(elig({ build: { ...BUILD, debuggable: true } }).reason, "debuggable_build_keeps_alpha_fixtures");
  assert.equal(elig({ build: { ...BUILD, debuggable: undefined } }).reason, "build_kind_unknown");
  assert.equal(elig({ build: { ...BUILD, sha256: "b2".repeat(32) } }).reason, "artifact_sha256_mismatch");
  assert.equal(elig({ artifactSha256Input: "" }).reason, "artifact_sha256_mismatch");
  assert.equal(elig({ build: { ...BUILD, version_code: 1 } }).reason, "artifact_identity_mismatch");
  assert.equal(elig({ bootstrapRequired: true }).reason, "bootstrap_case");
  assert.equal(elig({ bootstrapRequired: undefined }).reason, "bootstrap_mode_unknown");
});

test("case bytes: missing file, other bytes, block/quoted YAML forms and never classes are refused", () => {
  assert.equal(elig({ caseBytes: null }).reason, "case_file_missing");
  assert.equal(elig({ caseBytes: `${CASE_YAML}# edited\n` }).reason, "case_bytes_mismatch");
  // Reviewer's counterexample: a block-style tag list and a quoted "none" were invisible before.
  assert.deepEqual(caseMetaFromYaml('id: x\ntags:\n  - logout\nauth_fixture: "none"\n'), { id: "x", tags: ["logout"], authFixture: "none", problem: null });
  assert.equal(caseMetaFromYaml("id: x\ntags: &a [ok]\n").problem, "tags uses an unsupported YAML form");
  assert.equal(caseMetaFromYaml("id: x\nid: y\n").problem, "duplicate id");
  const variant = (yaml) => {
    const spec = { ...SPEC, case: { ...SPEC.case, case_sha256: sha256Hex(yaml) } };
    const allowList = { ...ALLOW, cases: { "fake-ordinary-case": { ...ALLOW.cases["fake-ordinary-case"], case_sha256: sha256Hex(yaml) } } };
    return elig({ spec, caseBytes: yaml, allowList, claimRelease: { ...CLAIM, spec_sha256: sha256Hex(canonicalJson(spec)) } });
  };
  assert.deepEqual(variant("id: fake-ordinary-case\ntags:\n  - logout\nauth_fixture: alpha-qa\n"), { eligible: false, reason: "never_class", class: "logout" });
  assert.equal(variant('id: fake-ordinary-case\nauth_fixture: "none"\n').reason, "case_runs_signed_out_or_unknown");
  assert.match(variant("id: fake-ordinary-case\nauth_fixture: |\n  none\n").reason, /^case_meta_unparseable/);
  for (const [tag, cls] of [["upgrade", "upgrade"], ["multi-server", "cross-server"], ["canary", "bootstrap-canary"], ["provisioning", "server-create"]]) {
    assert.equal(variant(`id: fake-ordinary-case\ntags: [${tag}]\nauth_fixture: alpha-qa\n`).class, cls);
  }
});

test("the Mobile allow-list only narrows: unlisted, other revision, candidate status are refused", () => {
  assert.equal(elig({ allowList: { ...ALLOW, cases: {} } }).reason, "not_allow_listed");
  assert.equal(elig({ allowList: { ...ALLOW, cases: { "fake-ordinary-case": { ...ALLOW.cases["fake-ordinary-case"], revision: 2 } } } }).reason, "allow_list_pins_other_revision");
  assert.equal(elig({ allowList: { ...ALLOW, cases: { "fake-ordinary-case": { ...ALLOW.cases["fake-ordinary-case"], status: "candidate" } } } }).reason, "allow_list_status_candidate");
  assert.equal(elig({ allowList: { ...ALLOW, never: { "fake-ordinary-case": "logout" } } }).class, "logout");
  assert.equal(elig({ allowList: { ...ALLOW, cases: { constructor: {} } } }).reason, "not_allow_listed");
});

test("repo allow-list: channel-switch-navigation r3 is pinned to its bytes but only a candidate (not eligible)", () => {
  const list = JSON.parse(fs.readFileSync(path.join(HERE, "release_prelogin_allowlist.json"), "utf8"));
  const entry = list.cases["channel-switch-navigation"];
  const bytes = fs.readFileSync(path.join(ROOT, "docs/qa-acceptance/cases/case-03-channel-switch-navigation.yaml"));
  assert.equal(sha256Hex(bytes), entry.case_sha256);
  assert.equal(entry.status, "candidate");
  assert.match(entry.note, /read state/);
  assert.equal(caseMetaFromYaml(bytes.toString("utf8")).authFixture, "alpha-qa", "not retagged to pass admission");
  for (const [id, meta] of Object.entries(list.cases)) assert.notEqual(meta.status, "compatible", `${id} must not be compatible before review`);
});

// ---------- the actual CLI plan (fake aapt, fake claim) ----------
function planCli({ spec = SPEC, claim = { release_prelogin: CLAIM }, apkBytes = "synthetic apk", aaptBody, allow = ALLOW, caseYaml = CASE_YAML, goal = GOAL } = {}) {
  const tmp = mktemp();
  const apk = path.join(tmp, "app.apk");
  fs.writeFileSync(apk, apkBytes);
  const aapt = path.join(tmp, "aapt");
  fs.writeFileSync(aapt, aaptBody ?? `#!/bin/sh\necho "package: name='build.raft.app' versionCode='${ARTIFACT.version_code}' versionName='${ARTIFACT.version_name}'"\n`);
  fs.chmodSync(aapt, 0o755);
  const cases = path.join(tmp, "cases");
  fs.mkdirSync(cases);
  if (caseYaml) fs.writeFileSync(path.join(cases, "x.yaml"), caseYaml);
  fs.writeFileSync(path.join(tmp, "allow.json"), JSON.stringify(allow));
  if (claim) fs.writeFileSync(path.join(tmp, "claim.json"), JSON.stringify({ claim_token: "fake-claim-token-not-real", ...claim }));
  const sha = sha256Hex(Buffer.from(apkBytes));
  const s = { ...spec, artifact: { ...spec.artifact, sha256: sha } };
  const c = claim ? { release_prelogin: { ...claim.release_prelogin, spec_sha256: claim.release_prelogin.spec_sha256 === CLAIM.spec_sha256 ? sha256Hex(canonicalJson(s)) : claim.release_prelogin.spec_sha256 } } : null;
  if (c) fs.writeFileSync(path.join(tmp, "claim.json"), JSON.stringify({ claim_token: "fake-claim-token-not-real", ...c }));
  const out = path.join(tmp, "out");
  const r = spawnSync(process.execPath, [CLI, "plan", "--out", out, "--run-case-id", "rc-fake-1", "--claim-response", path.join(tmp, "claim.json"), "--bootstrap-required", "false", "--apk", apk, "--aapt", aapt, "--allow-list", path.join(tmp, "allow.json"), "--cases-dir", cases], {
    encoding: "utf8", env: { ...process.env, RELEASE_PRELOGIN: JSON.stringify(s), QA_GOAL: goal, ARTIFACT_SHA256: sha, GITHUB_RUN_ID: "100", GITHUB_RUN_ATTEMPT: "2" },
  });
  return { tmp, out, r };
}

test("CLI plan: binds Testbed's attempt to the workflow run; a spec without a claim binding or case file is refused", () => {
  const ok = planCli();
  try {
    assert.equal(ok.r.status, 0, ok.r.stderr);
    const attempt = JSON.parse(fs.readFileSync(path.join(ok.out, "attempt.json"), "utf8"));
    assert.equal(attempt.attempt_id, SPEC.attempt.attempt_id);
    assert.deepEqual(attempt.workflow, { run_id: "100", run_attempt: "2" });
    assert.ok(!fs.readFileSync(path.join(ok.out, "attempt.json"), "utf8").includes("fake-claim-token"));
  } finally { rm(ok.tmp); }
  for (const [label, args, reason] of [
    ["no claim", { claim: null }, "spec_not_bound_by_testbed_claim"],
    ["no case file", { caseYaml: null }, "case_file_missing"],
    ["aapt fails", { aaptBody: "#!/bin/sh\nexit 42\n" }, "build_identity_unknown"],
    ["debuggable", { aaptBody: `#!/bin/sh\necho "package: name='build.raft.app' versionCode='999' versionName='9.9.9-qa'"\necho application-debuggable\n` }, "debuggable_build_keeps_alpha_fixtures"],
    ["goal", { goal: "something else" }, "goal_mismatch"],
  ]) {
    const run = planCli(args);
    try {
      assert.equal(run.r.status, 3, `${label}: ${run.r.stderr}`);
      assert.equal(JSON.parse(fs.readFileSync(path.join(run.out, "refusal.json"), "utf8")).reason, reason, label);
      assert.ok(!fs.existsSync(path.join(run.out, "attempt.json")), `${label}: no attempt when refused`);
    } finally { rm(run.tmp); }
  }
});

// ---------- readiness (blocker 4 + binding) ----------
test("readiness happy path and the binding rules", () => {
  assert.equal(ready().status, "ok");
  assert.equal(ready().facts.receipt_stamp, "11-17 10:00:05.000");
});

test("stale receipt from a previous attempt/nonce, or an earlier request, is rejected", () => {
  const other = { ...ATTEMPT, attempt_id: `pla_${"2e".repeat(16)}`, nonce: "ef".repeat(32), target: { ...ATTEMPT.target } };
  assert.equal(ready({ attempt: other }).reason, "stale_receipt_other_attempt_or_nonce");
  // A no-op request: the only line answers an EARLIER request of the same attempt + process.
  assert.equal(ready({ requestId: "ee".repeat(16) }).reason, "no_answer_to_this_request");
  assert.equal(ready({ requestId: undefined }).reason, "request_id_missing");
});

test("receipt from a different process is rejected", () => {
  assert.equal(ready({ processBefore: "4242:111", processAfter: "4242:111" }).reason, "receipt_from_other_process");
  assert.equal(ready({ lines: parseReadinessLog(receiptLine({}, { pid: 5555 })) }).reason, "receipt_from_other_process");
  assert.equal(ready({ processAfter: "4300:2" }).reason, "app_process_changed");
  assert.equal(ready({ processBefore: null }).reason, "app_process_unknown");
});

test("account/server mismatch => unknown", () => {
  const with_ = (over) => ready({ lines: parseReadinessLog(receiptLine(over)) });
  assert.equal(with_({ user_ref: userRef(ATTEMPT.nonce, "usr-fake-2") }).reason, "account_mismatch");
  assert.equal(with_({ selected_server_id: "srv-fake-2" }).reason, "server_mismatch");
  assert.equal(with_({ selected_server_slug: "other" }).reason, "server_mismatch");
  assert.equal(with_({ selected_server_id: null }).reason, "no_server_selected");
  assert.equal(with_({ signed_in: false, user_ref: null }).reason, "app_not_signed_in");
  assert.equal(with_({ user_id: "usr-fake-1" }).reason, "receipt_carries_sensitive_field");
  assert.equal(with_({ version_name: "wrong" }).reason, "build_mismatch");
});

test("missing readings are unknown, never safe: receipt, installed name/code, component state, capture scan", () => {
  assert.equal(ready({ lines: [] }).reason, "receipt_missing");
  // Reviewer's counterexample: a null installed versionName used to pass.
  assert.equal(ready({ installed: { ...INSTALLED, version_name: null, ok: false } }).reason, "installed_build_unknown");
  assert.equal(ready({ installed: { ok: false } }).reason, "installed_build_unknown");
  assert.equal(ready({ installed: { ...INSTALLED, version_code: 1 } }).reason, "installed_build_not_frozen_artifact");
  assert.deepEqual(installedFromDumpsys("Packages:\n  Package [build.raft.app] (x):\n    versionCode=999 minSdk=26\n", "build.raft.app").ok, false);
  assert.equal(installedFromDumpsys("Package [build.raft.app] (x):\n versionCode=999\n versionName=9.9.9-qa\n", "build.raft.app").ok, true);
  // Reviewer's counterexample: an empty / failed dumpsys used to read as "default".
  assert.equal(componentStateFromDumpsys("", "x.Y"), "unknown");
  const cls = "build.raft.app.core.data.auth.QaReleaseReadinessReceiver";
  const dump = (section) => `Packages:\n  Package [build.raft.app] (abc):\n    User 0: installed=true\n${section}    overlay paths:\n      ${cls}\n`;
  assert.equal(componentStateFromDumpsys(dump(`      disabledComponents:\n        ${cls}\n`), cls), "disabled");
  assert.equal(componentStateFromDumpsys(dump(`      enabledComponents:\n        ${cls}\n`), cls), "enabled");
  assert.equal(componentStateFromDumpsys(dump(""), cls), "default");
});

test("missing receipt => unknown; every retry is a new request of the same attempt", async () => {
  const device = fakeDevice({ noAnswer: true });
  const login = await runPreloginLogin({ attempt: ATTEMPT, device, readinessTries: 3 });
  assert.equal(login.ok, false);
  assert.equal(login.readiness.reason, "receipt_missing");
  const reqs = device.calls.filter((c) => c[0] === "requestReadiness").map(([, r]) => r);
  assert.equal(reqs.length, 3);
  assert.equal(new Set(reqs.map((r) => r.requestId)).size, 3);
  assert.ok(reqs.every((r) => r.attemptId === ATTEMPT.attempt_id && r.nonce === ATTEMPT.nonce));
  // A line left by an earlier request is not an answer (no-op broadcast).
  const replay = await runPreloginLogin({ attempt: ATTEMPT, device: fakeDevice({ noAnswer: true, extraLog: receiptLine() }), readinessTries: 2 });
  assert.equal(replay.readiness.reason, "no_answer_to_this_request");
});

// ---------- orchestration happy path ----------
test("happy path: capture off, callback, readiness, cleanup complete, agent pass => pass", async () => {
  const device = fakeDevice();
  const login = await runPreloginLogin({ attempt: ATTEMPT, device });
  assert.equal(login.ok, true, JSON.stringify(login));
  assert.deepEqual(device.calls.slice(0, 4), ["stopRecorder", "startWindow", "watchRecorder", "enableReceiver"]);
  assert.deepEqual(device.calls[4], ["runSignIn", [...SIGN_IN_FLAGS, "--account-ref-nonce", ATTEMPT.nonce]]);
  assert.deepEqual(device.calls[5], ["selectServer", "Fake QA Server"]);
  assert.equal(CLEAN.local_cleanup.status, "complete");
  const outcome = attemptOutcome({ eligibility: elig(), ...login, cleanup: CLEAN, agentRc: 0, agentVerdict: "pass" });
  assert.equal(outcome.outcome, "pass");
  assert.equal(outcome.notes.app_server_revocation, "app_reported_2xx");
});

// ---------- capture (blocker 2/4) ----------
test("capture off during login: a recorder seen at any poll, an unpolled window, a failed or dirty file scan => unknown", async () => {
  const run = (o) => runPreloginLogin({ attempt: ATTEMPT, device: fakeDevice(o) });
  const stuck = fakeDevice({ recorderStops: false });
  const r0 = await runPreloginLogin({ attempt: ATTEMPT, device: stuck });
  assert.equal(r0.capture.reason, "recorder_not_stopped_before_login");
  assert.deepEqual(stuck.calls, ["stopRecorder"]);
  // Reviewer's counterexample: a recorder active only between the before/after probes.
  assert.equal((await run({ watch: { polls: 9, seen: true, failed: false } })).capture.reason, "recorder_ran_during_login");
  assert.equal((await run({ watch: { polls: 0, seen: false, failed: false } })).capture.reason, "recorder_watch_missing");
  assert.equal((await run({ watch: { polls: 4, seen: false, failed: true } })).capture.reason, "recorder_watch_missing");
  assert.equal((await run({ after: null })).capture.reason, "recorder_state_unknown_after");
  assert.equal((await run({ files: null })).capture.reason, "device_file_scan_missing");
  const dirty = await run({ files: ["release-auto-login.xml"] });
  assert.equal(dirty.capture.reason, "files_written_during_login");
  assert.equal(dirty.ok, false);
  assert.equal((await run({ captures: 1 })).capture.reason, "harness_captured_during_login");
  assert.equal((await run({ summary: signInSummary({ screenshots_written: 1 }) })).capture.reason, "sign_in_run_captured");
  assert.equal((await run({ summary: signInSummary({ no_capture: false }) })).callback.reason, "sign_in_run_not_in_prelogin_mode");
  assert.equal(assessCaptureWindow({ recorderStoppedBefore: true, recorderWatch: { polls: 1 }, recorderRunningAfter: false, filesWritten: [], harnessCaptures: undefined }).reason, "harness_capture_count_unknown");
});

/** Fake adb for the actual scripts: records every call; writes a "device file" only if asked to dump to one. */
function fakeAdb(tmp, { dumpFail = false, pmFail = false } = {}) {
  const bin = path.join(tmp, "bin");
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, "adb"), `#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_ROOT/calls.txt"
case "$*" in
  'shell cmd role get-role-holders '*) echo build.qa.capturebrowser;;
  'shell cmd package resolve-activity '*) echo build.qa.capturebrowser/.CaptureActivity;;
  'shell pm path build.qa.capturebrowser') echo package:/fake.apk;;
  'shell dumpsys package build.raft.app') if [ "${dumpFail ? 1 : 0}" = 1 ]; then exit 1; fi; printf 'Package [build.raft.app] (x):\\n versionName=1\\n versionCode=1\\n pkgFlags=[]\\n';;
  'shell pm disable '*|'shell pm enable '*) [ "${pmFail ? 1 : 0}" = 1 ] && exit 1; exit 0;;
  'shell uiautomator dump '*) mkdir -p "$FAKE_ROOT/sdcard"; echo '<hierarchy/>' > "$FAKE_ROOT/sdcard/release-auto-login.xml";;
  'exec-out uiautomator dump /dev/tty') printf '%s\\n' '<?xml version="1.0"?><hierarchy><node text="Sign in to Raft" package="build.raft.app" bounds="[0,0][20,20]" /></hierarchy>UI hierchary dumped to: /dev/tty';;
  'logcat -v '*) echo '11-17 10:00:00.000 fake synthetic login log line'; sleep 30;;
  *) :;;
esac
`);
  fs.chmodSync(path.join(bin, "adb"), 0o755);
  return bin;
}

test("--no-capture writes nothing during login, even when the sign-in run is SIGKILLed mid-way", async () => {
  const tmp = mktemp();
  try {
    const bin = fakeAdb(tmp);
    const out = path.join(tmp, "out");
    const p = spawn(process.execPath, [path.join(HERE, "release_auto_login.mjs"), "--no-capture", "--defer-app-session", "--strict-cleanup", "--capture-browser-apk", "/fake.apk", "--out", out], {
      stdio: "ignore", env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_ROOT: tmp, QA_LOGIN_EMAIL: "synthetic@example.invalid", QA_LOGIN_PASSWORD: "FAKE-pw-123" },
    });
    const calls = () => (fs.existsSync(path.join(tmp, "calls.txt")) ? fs.readFileSync(path.join(tmp, "calls.txt"), "utf8") : "");
    for (let i = 0; i < 100 && !/exec-out uiautomator dump \/dev\/tty/.test(calls()); i += 1) await wait(100);
    await wait(500);
    p.kill("SIGKILL");
    await new Promise((resolve) => p.once("close", resolve));
    assert.match(calls(), /exec-out uiautomator dump \/dev\/tty/, "the UI tree is read through stdout");
    assert.doesNotMatch(calls(), /shell uiautomator dump \/sdcard/);
    assert.doesNotMatch(calls(), /^logcat -v threadtime -s/m, "no streamed login log");
    assert.equal(fs.existsSync(path.join(tmp, "sdcard")), false, "no UI dump file on the (fake) device");
    const left = fs.existsSync(out) ? fs.readdirSync(out, { recursive: true }) : [];
    assert.deepEqual(left, [], "no file on the runner from the login window");
  } finally { rm(tmp); }
});

// ---------- cleanup (blocker 4 + decision 4) ----------
test("cleanup unproven => unknown; the three cleanup records stay separate; app server revoke is never 'revoked'", async () => {
  const run = (signOut, receiverState = "disabled", sum = signInSummary()) => runPreloginCleanup({ loginStarted: true, signInSummary: sum, device: { runSignOut: async () => signOut, disableReceiver: async () => receiverState } });
  const local = await run({ cleanup: { app_session: "local_cleared_server_unconfirmed" } });
  assert.equal(local.local_cleanup.status, "complete");
  assert.equal(local.app_server_revocation, "unknown");
  assert.equal(local.web_session, "signed_out_verified");
  const crashed = await runPreloginCleanup({ loginStarted: true, signInSummary: signInSummary(), device: { runSignOut: async () => { throw new Error("adb gone"); }, disableReceiver: async () => { throw new Error("adb gone"); } } });
  assert.equal(crashed.local_cleanup.status, "unknown");
  assert.deepEqual(crashed.local_cleanup.unproven.sort(), ["app_signed_out_on_device", "receipt_writer"]);
  assert.equal((await run({ cleanup: { app_session: "app_reported_server_logout_2xx" } }, "default")).local_cleanup.items.receipt_writer, "unknown");
  assert.equal((await run({ cleanup: { app_session: "app_reported_server_logout_2xx" } }, "unknown")).local_cleanup.status, "unknown");
  const noSignIn = await run(null, "disabled", null);
  assert.equal(noSignIn.local_cleanup.status, "unknown", "a killed sign-in (no summary) cannot be cleaned up 'complete'");
  assert.deepEqual(assessCleanup({ loginStarted: false }), { local_cleanup: { status: "not_started", items: {} }, web_session: "not_opened", app_server_revocation: "no_app_session" });
  for (const c of [local, crashed, CLEAN]) assert.ok(!/revoked|"ok"/.test(JSON.stringify(c.app_server_revocation)));
  const prepared = { eligibility: { eligible: true }, capture: { status: "off" }, callback: { status: "ok" }, readiness: { status: "ok" } };
  assert.equal(attemptOutcome({ ...prepared, cleanup: crashed, agentRc: 0, agentVerdict: "pass" }).outcome, "incomplete");
  assert.equal(attemptOutcome({ ...prepared, cleanup: { ...CLEAN, web_session: "unknown" }, agentRc: 0, agentVerdict: "pass" }).outcome, "incomplete");
  // A product fail on a verified preparation stays fail; cleanup state is a note beside it.
  const fail = attemptOutcome({ ...prepared, cleanup: local, agentRc: 1, agentVerdict: "fail" });
  assert.equal(fail.outcome, "fail");
  assert.equal(fail.notes.app_server_revocation, "unknown");
});

test("CLI cleanup with failing pm + dumpsys reads the receipt writer as unknown, not disabled", () => {
  const tmp = mktemp();
  try {
    const bin = fakeAdb(tmp, { dumpFail: true, pmFail: true });
    const out = path.join(tmp, "out");
    fs.mkdirSync(out);
    fs.writeFileSync(path.join(out, "attempt.json"), JSON.stringify(ATTEMPT));
    fs.writeFileSync(path.join(out, "login-started.json"), JSON.stringify({ attempt_id: ATTEMPT.attempt_id }));
    const priv = path.join(tmp, "private");
    fs.mkdirSync(priv);
    fs.writeFileSync(path.join(priv, "sign-in-summary.json"), JSON.stringify(signInSummary()));
    const r = spawnSync(process.execPath, [CLI, "cleanup", "--out", out, "--private-dir", priv, "--package", "build.raft.app"], { encoding: "utf8", timeout: 60_000, env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_ROOT: tmp } });
    assert.notEqual(r.status, 0);
    const c = JSON.parse(fs.readFileSync(path.join(out, "receipt-cleanup.json"), "utf8"));
    assert.equal(c.local_cleanup.items.receipt_writer, "unknown");
    assert.equal(c.local_cleanup.status, "unknown");
  } finally { rm(tmp); }
});

test("CLI login refuses without a reviewed pre-login credential and touches nothing on the device", () => {
  const tmp = mktemp();
  try {
    const bin = fakeAdb(tmp);
    const out = path.join(tmp, "out");
    fs.mkdirSync(out);
    fs.writeFileSync(path.join(out, "attempt.json"), JSON.stringify(ATTEMPT));
    const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_ROOT: tmp, QA_TEST_EMAIL: "fake@example.invalid", QA_TEST_PASSWORD: "FAKE-shared-pw" };
    delete env.QA_PRELOGIN_EMAIL;
    delete env.QA_PRELOGIN_PASSWORD;
    const r = spawnSync(process.execPath, [CLI, "login", "--out", out, "--evidence", path.join(tmp, "ev"), "--private-dir", path.join(tmp, "p")], { encoding: "utf8", env });
    assert.equal(r.status, 1);
    assert.equal(fs.existsSync(path.join(tmp, "calls.txt")), false, "no adb call");
    assert.equal(fs.existsSync(path.join(out, "login-started.json")), false);
    assert.equal(JSON.parse(fs.readFileSync(path.join(out, "receipt-callback.json"), "utf8")).reason, "prelogin_credentials_not_configured");
  } finally { rm(tmp); }
});

// ---------- verdict layer (blocker 5) ----------
test("outcome table: incomplete preparation is never a product fail or pass; agent rc and verdict must agree", () => {
  const prepared = { eligibility: { eligible: true }, capture: { status: "off" }, callback: { status: "ok" }, readiness: { status: "ok" }, cleanup: CLEAN };
  assert.equal(attemptOutcome({ ...prepared, readiness: { status: "unknown", reason: "receipt_missing" }, agentRc: 1, agentVerdict: "fail" }).outcome, "incomplete");
  assert.equal(attemptOutcome({ ...prepared, capture: { status: "unknown" }, agentRc: 0, agentVerdict: "pass" }).outcome, "incomplete");
  assert.equal(attemptOutcome({ ...prepared, agentRc: 0, agentVerdict: "fail" }).outcome, "incomplete");
  assert.equal(attemptOutcome({ ...prepared, agentRc: 1, agentVerdict: "pass" }).outcome, "incomplete");
  assert.equal(attemptOutcome({ ...prepared, agentRc: NaN, agentVerdict: null }).outcome, "incomplete");
  assert.equal(attemptOutcome({ ...prepared, agentRc: 0, agentVerdict: "pass" }).outcome, "pass");
  assert.equal(attemptOutcome({ ...prepared, eligibility: { eligible: false, reason: "not_allow_listed" } }).outcome, "not_run");
});

test("report gate recomputes from receipts: tampered outcome, foreign attempt, changed agent result => 3", () => {
  const prepared = { eligibility: { eligible: true }, capture: { status: "off" }, callback: { status: "ok" }, readiness: { status: "ok" }, cleanup: CLEAN };
  const files = (agentRc, agentVerdict, outcomeOver = {}) => {
    const o = attemptOutcome({ ...prepared, agentRc, agentVerdict });
    const tag = (v) => ({ attempt_id: ATTEMPT.attempt_id, ...v });
    return {
      "eligibility.json": { eligible: true }, "attempt.json": ATTEMPT,
      "receipt-capture.json": tag(prepared.capture), "receipt-callback.json": tag(prepared.callback), "receipt-ready.json": tag(prepared.readiness), "receipt-cleanup.json": tag(CLEAN),
      "outcome.json": tag({ ...o, ...outcomeOver }),
    };
  };
  assert.deepEqual(reportGate({ files: files(1, "fail"), agentRc: 1, agentVerdict: "fail" }).rc, 1);
  assert.equal(reportGate({ files: files(0, "pass"), agentRc: 0, agentVerdict: "pass" }).rc, 0);
  // Reviewer's counterexample: agent rc=1 with an outcome that says incomplete -> never a product fail.
  const unknownReady = { ...files(1, "fail"), "receipt-ready.json": { attempt_id: ATTEMPT.attempt_id, status: "unknown", reason: "receipt_missing" } };
  assert.equal(reportGate({ files: unknownReady, agentRc: 1, agentVerdict: "fail" }).rc, 3);
  assert.equal(reportGate({ files: files(1, "fail", { outcome: "pass", rc: 0 }), agentRc: 1, agentVerdict: "fail" }).rc, 3);
  assert.equal(reportGate({ files: files(0, "pass"), agentRc: 1, agentVerdict: "fail" }).rc, 3);
  assert.equal(reportGate({ files: { ...files(0, "pass"), "receipt-ready.json": { attempt_id: `pla_${"9a".repeat(16)}`, status: "ok" } }, agentRc: 0, agentVerdict: "pass" }).rc, 3);
  assert.equal(reportGate({ files: {}, agentRc: 0, agentVerdict: "pass" }).rc, 3);
});

// ---------- upload boundary (blocker 2) ----------
test("upload boundary: only allow-listed, clean JSON receipts; anything sensitive-looking is refused", () => {
  const good = { attempt_id: ATTEMPT.attempt_id, status: "ok", reason: "app_reports_frozen_account_and_server", facts: { user_ref: userRef(ATTEMPT.nonce, "u"), process: "4242:987654", receipt_stamp: "11-17 10:00:05.000" } };
  assert.equal(uploadProblem("receipt-ready.json", JSON.stringify(good)), null);
  assert.equal(uploadProblem("attempt.json", JSON.stringify(ATTEMPT)), null);
  assert.equal(uploadProblem("receipt-cleanup.json", JSON.stringify({ attempt_id: ATTEMPT.attempt_id, ...CLEAN })), null);
  assert.equal(uploadProblem("outcome.rc", "3\n"), null);
  assert.equal(uploadProblem("sign-in-run/summary.json", "{}"), "not_allow_listed");
  assert.equal(uploadProblem(".logcat-raw.txt", "x"), "not_allow_listed");
  assert.match(uploadProblem("outcome.json", JSON.stringify({ reason: "raft://login/callback?requestId=a&code=b" })), /unsafe_value/);
  assert.match(uploadProblem("outcome.json", JSON.stringify({ accessToken: "x" })), /sensitive_key/);
  assert.match(uploadProblem("outcome.json", JSON.stringify({ reason: "eyJhbGciOiJIUzI1NiJ9.e30.x" })), /unsafe_value/);
  assert.match(uploadProblem("outcome.json", JSON.stringify({ reason: "Q2xhdWRlIGlzIGEgZmFrZSB0b2tlbg9" })), /opaque_value/);
  assert.match(uploadProblem("outcome.json", JSON.stringify({ reason: "who: fake@example.invalid" })), /unsafe_value/);
  assert.equal(uploadProblem("outcome.json", JSON.stringify({ reason: "has FAKE-pw-123" }), ["FAKE-pw-123"]), "secret_value");
  assert.equal(uploadProblem("outcome.rc", "pass"), "rc_malformed");
  assert.equal(receiptsLeak(['{"x":"FAKE-pw-123"}'], ["FAKE-pw-123"]), true);
});

test("CLI finalize: emulator died before cleanup => cleanup 'not reached' (unknown), raw files dropped, receipts sealed", () => {
  const tmp = mktemp();
  try {
    const out = path.join(tmp, "out");
    fs.mkdirSync(path.join(out, "sign-in-run"), { recursive: true });
    fs.writeFileSync(path.join(out, "attempt.json"), JSON.stringify(ATTEMPT));
    fs.writeFileSync(path.join(out, "eligibility.json"), JSON.stringify({ eligible: true, reason: "testbed_frozen_and_mobile_compatible" }));
    fs.writeFileSync(path.join(out, "login-started.json"), JSON.stringify({ attempt_id: ATTEMPT.attempt_id }));
    fs.writeFileSync(path.join(out, "sign-in-run", ".logcat-raw.txt"), "fake raw login log");
    fs.writeFileSync(path.join(out, "stray.txt"), "anything");
    const r = spawnSync(process.execPath, [CLI, "finalize", "--out", out, "--agent-rc", path.join(tmp, "none"), "--verdict", path.join(tmp, "none")], { encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
    const left = fs.readdirSync(out).sort();
    assert.ok(left.every((f) => UPLOAD_FILES.includes(f)), left.join(","));
    const cleanup = JSON.parse(fs.readFileSync(path.join(out, "receipt-cleanup.json"), "utf8"));
    assert.equal(cleanup.local_cleanup.status, "unknown");
    assert.equal(cleanup.app_server_revocation, "unknown");
    const outcome = JSON.parse(fs.readFileSync(path.join(out, "outcome.json"), "utf8"));
    assert.equal(outcome.outcome, "incomplete");
    assert.equal(outcome.attempt_id, ATTEMPT.attempt_id, "attempt identity kept for later resolution");
    // A receipt with something token-shaped: everything is withheld.
    fs.writeFileSync(path.join(out, "receipt-ready.json"), JSON.stringify({ attempt_id: ATTEMPT.attempt_id, status: "ok", reason: "Bearer fake" }));
    spawnSync(process.execPath, [CLI, "finalize", "--out", out], { encoding: "utf8" });
    assert.deepEqual(fs.readdirSync(out).sort(), ["outcome.json", "outcome.rc"]);
    assert.match(fs.readFileSync(path.join(out, "outcome.json"), "utf8"), /withheld/);
  } finally { rm(tmp); }
});

// ---------- wiring into existing records ----------
test("login record: a release-prelogin record is complete only with its attempt id and a device stamp", () => {
  const dir = mktemp();
  const env = { GITHUB_RUN_ID: "100", GITHUB_RUN_ATTEMPT: "1" };
  try {
    writeRecord(dir, { mode: "release-prelogin", status: "complete", login_end: "11-17 10:00:05.000", attempt_id: ATTEMPT.attempt_id }, env);
    assert.equal(readRecord(dir, env).ok, true);
    writeRecord(dir, { mode: "release-prelogin", status: "complete", login_end: "11-17 10:00:05.000" }, env);
    assert.equal(readRecord(dir, env).ok, false);
  } finally { rm(dir); }
});

test("release_auto_login: deferred app session counts only in the deferred sign-in run; sign-out phase needs the app's report", () => {
  const cleanup = { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: DEFERRED_APP_SESSION };
  assert.equal(summarizeCleanup(cleanup).ok, false);
  assert.equal(summarizeCleanup(cleanup, { deferAppSession: true }).ok, true);
  assert.equal(runOutcome({ signedIn: true, cleanup, strict: true, deferAppSession: true }).result, "signed_in");
  const signOut = { capture_file: "not_applicable", browser: "not_changed", capture_browser: "not_installed_by_run", web_session: "not_opened" };
  assert.equal(runOutcome({ phase: "sign-out", cleanup: { ...signOut, app_session: "app_reported_server_logout_2xx" } }).result, "signed_out");
  assert.equal(runOutcome({ phase: "sign-out", cleanup: { ...signOut, app_session: "not_requested" } }).exitCode, 1);
});

test("store builds compile the receiver out: only debug + qaRelease source sets, only the qaRelease manifest", () => {
  const gradle = fs.readFileSync(path.join(ROOT, "compose/androidApp/build.gradle.kts"), "utf8");
  assert.deepEqual([...gradle.matchAll(/getByName\("(\w+)"\) \{\s*java\.srcDir\("src\/qaReadiness\/java"\)/g)].map((m) => m[1]).sort(), ["debug", "qaRelease"]);
  assert.match(gradle, /onVariants\(selector\(\)\.withBuildType\("release"\)\) \{ variant ->\s*registerQaReadinessReceiverCheck\(variant, expectPresent = false\)/);
  assert.match(gradle, /onVariants\(selector\(\)\.withBuildType\("play"\)\) \{ variant ->\s*registerQaReadinessReceiverCheck\(variant, expectPresent = false\)/);
  for (const m of ["main", "play"]) assert.doesNotMatch(fs.readFileSync(path.join(ROOT, `compose/androidApp/src/${m}/AndroidManifest.xml`), "utf8"), /QaReleaseReadinessReceiver/);
  assert.match(fs.readFileSync(path.join(ROOT, "compose/androidApp/src/qaRelease/AndroidManifest.xml"), "utf8"), /QaReleaseReadinessReceiver"\s+android:enabled="false"\s+android:exported="true"\s+android:permission="android.permission.DUMP"/);
  assert.ok(!fs.existsSync(path.join(ROOT, "compose/androidApp/src/main/java/build/raft/app/core/data/auth/QaReleaseReadinessReceiver.kt")));
  const ci = fs.readFileSync(path.join(ROOT, ".github/workflows/test.yml"), "utf8");
  for (const t of ["verifyReleaseHasNoQaReadinessReceiver", "verifyPlayHasNoQaReadinessReceiver", "verifyQaReleaseHasQaReadinessReceiver"]) assert.ok(ci.includes(`:androidApp:${t}`), t);
});
