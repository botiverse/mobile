// Release pre-login (release_prelogin_lib.mjs) with fake values only: no device, no network,
// no real account, server or credential.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  ALLOWLIST_CONTRACT,
  SIGN_IN_FLAGS,
  assessCleanup,
  assessReadiness,
  attemptOutcome,
  caseMetaFromYaml,
  componentStateFromDumpsys,
  newAttempt,
  parseReadinessLog,
  preloginEligibility,
  receiptsLeak,
  runPreloginCleanup,
  runPreloginLogin,
} from "./release_prelogin_lib.mjs";
import { DEFERRED_APP_SESSION, runOutcome, summarizeCleanup } from "./release_auto_login_lib.mjs";
import { readRecord, writeRecord } from "./login_phase.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const TARGET = { env: "staging", account_user_id: "usr-fake-1", server_id: "srv-fake-1", server_slug: "fake-slug", server_name: "Fake QA Server" };
let seed = 0;
const fakeRandom = (n) => Buffer.alloc(n, (seed += 1));
const attemptFor = (overrides = {}) => newAttempt({ caseId: "channel-switch-navigation", runCaseId: "rc-fake", target: TARGET, workflow: { runId: "100", runAttempt: "1" }, random: fakeRandom, ...overrides });
const ALLOW = JSON.parse(fs.readFileSync(path.join(ROOT, "scripts/qa/release_prelogin_allowlist.json"), "utf8"));

const PROCESS = "4242:987654";
const receiptLine = (attempt, over = {}, { pid = 4242, stamp = "11-17 10:00:05.000" } = {}) => {
  const receipt = {
    contract: "raft-qa-release-readiness/v1",
    attempt_id: attempt.attempt_id,
    nonce: attempt.nonce,
    package: "build.raft.app",
    version_name: "1.27.0",
    version_code: 1270001,
    pid: 4242,
    process_start_ticks: "987654",
    signed_in: true,
    user_id: TARGET.account_user_id,
    selected_server_id: TARGET.server_id,
    selected_server_slug: TARGET.server_slug,
    ...over,
  };
  return `${stamp}  ${pid}  ${pid + 7} I RaftQaReadiness: RAFT_QA_READINESS ${JSON.stringify(receipt)}`;
};
const INSTALLED = { package: "build.raft.app", version_name: "1.27.0", version_code: 1270001 };

const signInSummary = (over = {}) => ({
  phase: "sign-in",
  no_capture: true,
  defer_app_session: true,
  strict_cleanup: true,
  screenshots_written: 0,
  account_user_id: TARGET.account_user_id,
  result: "signed_in",
  steps: [
    { step: 6, name: "deliver-callback-to-app", result: "pass" },
    { step: 7, name: "verify-signed-in", result: "pass", detail: { exchange: "exchanged", screen: "choose_server" } },
  ],
  cleanup: { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: DEFERRED_APP_SESSION },
  ...over,
});

/** A fake device that records what was asked of it. */
function fakeDevice({ attemptRef, log, process = () => PROCESS, recorderStops = true, recorderAfter = false, summary = signInSummary(), captures = 0 } = {}) {
  const calls = [];
  let captured = 0;
  return {
    calls,
    captureCount: () => captured,
    stopRecorder: async () => { calls.push("stopRecorder"); return recorderStops; },
    recorderRunning: async () => recorderAfter,
    enableReceiver: async () => { calls.push("enableReceiver"); return true; },
    runSignIn: async (flags) => { calls.push(["runSignIn", flags]); captured += captures; return summary; },
    selectServer: async (name) => { calls.push(["selectServer", name]); },
    installed: async () => INSTALLED,
    appProcess: async () => process(),
    requestReadiness: async (req) => { calls.push(["requestReadiness", req]); },
    readReadinessLog: async () => (typeof log === "function" ? log(attemptRef()) : log ?? ""),
    sleep: async () => {},
  };
}

// ---------- happy path ----------
test("happy path: allowed case, capture off, callback, app receipt for this attempt/process, cleanup, agent pass => pass", async () => {
  const eligibility = preloginEligibility({ caseId: "channel-switch-navigation", allowList: ALLOW, buildDebuggable: false });
  assert.equal(eligibility.eligible, true);
  const attempt = attemptFor();
  const device = fakeDevice({ attemptRef: () => attempt, log: (a) => receiptLine(a) });
  const login = await runPreloginLogin({ attempt, device });
  assert.equal(login.ok, true);
  assert.equal(login.capture.status, "off");
  assert.equal(login.callback.status, "ok");
  assert.equal(login.readiness.status, "ok");
  assert.equal(login.loginEnd, "11-17 10:00:05.000");
  // Recorder stopped and receiver enabled BEFORE the sign-in; sign-in run in no-capture, deferred mode.
  assert.deepEqual(device.calls.slice(0, 2), ["stopRecorder", "enableReceiver"]);
  assert.deepEqual(device.calls[2], ["runSignIn", SIGN_IN_FLAGS]);
  assert.deepEqual(device.calls[3], ["selectServer", TARGET.server_name]);
  assert.deepEqual(device.calls[4], ["requestReadiness", { attemptId: attempt.attempt_id, nonce: attempt.nonce }]);

  const cleanup = await runPreloginCleanup({
    signInSummary: login.signInSummary,
    device: { runSignOut: async () => ({ cleanup: { app_session: "app_reported_server_logout_2xx", app_logout_evidence: "app_reported_2xx" } }), disableReceiver: async () => true },
  });
  assert.equal(cleanup.status, "complete");
  assert.equal(cleanup.items.server_session, "app_reported_2xx");
  assert.deepEqual(attemptOutcome({ eligibility, ...login, cleanup, agentRc: 0 }), { outcome: "pass", rc: 0, reason: "all receipts ok and agent pass" });
  assert.equal(attemptOutcome({ eligibility, ...login, cleanup, agentRc: 1 }).outcome, "fail");
});

test("an attempt is not a workflow run: every plan makes a fresh attempt id + nonce, workflow run/rerun recorded apart", () => {
  const a = attemptFor();
  const b = attemptFor({ workflow: { runId: "100", runAttempt: "2" } });
  assert.match(a.attempt_id, /^pla_[0-9a-f]{32}$/);
  assert.match(a.nonce, /^[0-9a-f]{64}$/);
  assert.notEqual(a.attempt_id, b.attempt_id);
  assert.notEqual(a.nonce, b.nonce);
  assert.deepEqual(b.workflow, { run_id: "100", run_attempt: "2" });
  assert.throws(() => attemptFor({ target: { ...TARGET, server_slug: "" } }), /server_slug is required/);
  assert.throws(() => attemptFor({ target: { ...TARGET, refresh_token: "x" } }), /must not be in the target/);
});

// ---------- readiness receipt binding ----------
test("stale receipt from a previous attempt or nonce is rejected", () => {
  const previous = attemptFor();
  const current = attemptFor();
  const stale = parseReadinessLog(receiptLine(previous));
  assert.deepEqual(assessReadiness({ lines: stale, attempt: current, processBefore: PROCESS, processAfter: PROCESS, installed: INSTALLED }).reason, "stale_receipt_other_attempt_or_nonce");
  // Same attempt id, old nonce (e.g. a workflow rerun replaying an attempt id): still stale.
  const oldNonce = parseReadinessLog(receiptLine(current, { nonce: previous.nonce }));
  assert.equal(assessReadiness({ lines: oldNonce, attempt: current, processBefore: PROCESS, processAfter: PROCESS }).status, "unknown");
});

test("receipt from a different app process is rejected", () => {
  const attempt = attemptFor();
  const lines = parseReadinessLog(receiptLine(attempt));
  // The app restarted (same pid reused, other start time) before the request.
  assert.equal(assessReadiness({ lines, attempt, processBefore: "4242:111", processAfter: "4242:111" }).reason, "receipt_from_other_process");
  // Written by another pid than the line's writer claims.
  const forged = parseReadinessLog(receiptLine(attempt, {}, { pid: 5555 }));
  assert.equal(assessReadiness({ lines: forged, attempt, processBefore: PROCESS, processAfter: PROCESS }).reason, "receipt_from_other_process");
  // The process changed while the receipt was requested, or is unknown.
  assert.equal(assessReadiness({ lines, attempt, processBefore: PROCESS, processAfter: "4300:2" }).reason, "app_process_changed");
  assert.equal(assessReadiness({ lines, attempt, processBefore: null, processAfter: PROCESS }).reason, "app_process_unknown");
});

test("account or server mismatch => unknown", () => {
  const attempt = attemptFor();
  const check = (over) => assessReadiness({ lines: parseReadinessLog(receiptLine(attempt, over)), attempt, processBefore: PROCESS, processAfter: PROCESS, installed: INSTALLED });
  assert.equal(check({ user_id: "usr-fake-2" }).reason, "account_mismatch");
  assert.equal(check({ selected_server_id: "srv-fake-2" }).reason, "server_mismatch");
  assert.equal(check({ selected_server_slug: "other-slug" }).reason, "server_mismatch");
  assert.equal(check({ selected_server_id: null, selected_server_slug: null }).reason, "no_server_selected");
  assert.equal(check({ signed_in: false, user_id: null }).reason, "app_not_signed_in");
  assert.equal(check({ version_code: 1 }).reason, "build_mismatch");
  assert.equal(check({ access_token: "fake" }).reason, "receipt_carries_sensitive_field");
  // The web sign-in was for another account than Testbed froze: the callback receipt is unknown.
  const outcome = attemptOutcome({
    eligibility: { eligible: true },
    capture: { status: "off" },
    callback: { status: "unknown", reason: "account_mismatch" },
    readiness: { status: "ok" },
    cleanup: { status: "complete" },
    agentRc: 0,
  });
  assert.equal(outcome.outcome, "unknown");
});

test("missing receipt => unknown, and the login retries only the same attempt + nonce", async () => {
  const attempt = attemptFor();
  assert.equal(assessReadiness({ lines: [], attempt, processBefore: PROCESS, processAfter: PROCESS }).reason, "receipt_missing");
  const device = fakeDevice({ attemptRef: () => attempt, log: "" });
  const login = await runPreloginLogin({ attempt, device, readinessTries: 3 });
  assert.equal(login.ok, false);
  assert.equal(login.readiness.reason, "receipt_missing");
  const requests = device.calls.filter((c) => c[0] === "requestReadiness");
  assert.equal(requests.length, 3);
  assert.ok(requests.every(([, r]) => r.attemptId === attempt.attempt_id && r.nonce === attempt.nonce));
  assert.equal(attemptOutcome({ eligibility: { eligible: true }, ...login, cleanup: { status: "complete" }, agentRc: 0 }).outcome, "unknown");
});

// ---------- capture ----------
test("capture is off during login: recorder stopped first; any capture or a recorder that will not stop => unknown", async () => {
  const attempt = attemptFor();
  const log = (a) => receiptLine(a);
  // Recorder will not stop: nothing else happens (no receiver, no sign-in).
  const stuck = fakeDevice({ attemptRef: () => attempt, log, recorderStops: false });
  const r1 = await runPreloginLogin({ attempt, device: stuck });
  assert.equal(r1.capture.reason, "recorder_not_stopped_before_login");
  assert.deepEqual(stuck.calls, ["stopRecorder"]);
  assert.equal(r1.ok, false);
  // The sign-in run wrote a screenshot.
  const r2 = await runPreloginLogin({ attempt, device: fakeDevice({ attemptRef: () => attempt, log, summary: signInSummary({ screenshots_written: 1 }) }) });
  assert.equal(r2.capture.reason, "sign_in_run_captured");
  assert.equal(r2.ok, false);
  // A recorder ran again during the window.
  const r3 = await runPreloginLogin({ attempt, device: fakeDevice({ attemptRef: () => attempt, log, recorderAfter: true }) });
  assert.equal(r3.capture.reason, "recorder_ran_during_login");
  // The harness itself captured during the window.
  const r4 = await runPreloginLogin({ attempt, device: fakeDevice({ attemptRef: () => attempt, log, captures: 1 }) });
  assert.equal(r4.capture.reason, "harness_captured_during_login");
  // A sign-in run without --no-capture is not accepted as the callback receipt either.
  const r5 = await runPreloginLogin({ attempt, device: fakeDevice({ attemptRef: () => attempt, log, summary: signInSummary({ no_capture: false }) }) });
  assert.equal(r5.callback.reason, "sign_in_run_not_in_prelogin_mode");
  for (const r of [r1, r2, r3, r4, r5]) assert.equal(attemptOutcome({ eligibility: { eligible: true }, ...r, cleanup: { status: "complete" }, agentRc: 0 }).rc, 3);
});

test("release_auto_login --no-capture: no screenshot / dump / login log is written", () => {
  const src = fs.readFileSync(path.join(ROOT, "scripts/qa/release_auto_login.mjs"), "utf8");
  assert.match(src, /function evidence\(name\) \{\n  if \(NO_CAPTURE\) return;/);
  assert.match(src, /if \(!NO_CAPTURE\) fs\.writeFileSync\(path\.join\(OUT, "logcat-app-login\.txt"\)/);
});

// ---------- cleanup ----------
test("cleanup unproven => unknown; the server session is never recorded as ok", async () => {
  const unproven = await runPreloginCleanup({
    signInSummary: signInSummary(),
    device: { runSignOut: async () => ({ cleanup: { app_session: "local_cleared_server_unconfirmed" } }), disableReceiver: async () => true },
  });
  assert.equal(unproven.status, "unknown");
  assert.equal(unproven.items.server_session, "unknown");
  assert.deepEqual(unproven.unproven, ["server_session"]);
  // A sign-out run that crashed, a receiver that is not read back disabled, a web session not proven signed out.
  const crashed = await runPreloginCleanup({ signInSummary: signInSummary({ cleanup: { ...signInSummary().cleanup, web_session: "failed" } }), device: { runSignOut: async () => { throw new Error("adb gone"); }, disableReceiver: async () => false } });
  assert.deepEqual(crashed.unproven.sort(), ["receipt_writer", "server_session", "web_session"]);
  for (const c of [unproven, crashed]) assert.ok(!Object.values(c.items).includes("ok") || c.items.server_session !== "ok");
  const ready = { eligibility: { eligible: true }, capture: { status: "off" }, callback: { status: "ok" }, readiness: { status: "ok" } };
  assert.equal(attemptOutcome({ ...ready, cleanup: unproven, agentRc: 0 }).outcome, "unknown");
  assert.equal(attemptOutcome({ ...ready, cleanup: null, agentRc: 0 }).outcome, "unknown");
  // A product FAIL on a correctly prepared app stays a FAIL, cleanup reported beside it.
  assert.match(attemptOutcome({ ...ready, cleanup: unproven, agentRc: 1 }).reason, /cleanup unknown/);
  assert.equal(assessCleanup({ signInSummary: signInSummary(), signOutSummary: { cleanup: { app_session: "app_reported_server_logout_2xx" } }, receiverDisabled: true }).items.server_session, "app_reported_2xx");
});

// ---------- eligibility ----------
test("a case that is not allow-listed refuses the pre-login; never classes and Alpha builds refuse too", () => {
  const ask = (caseId, extra = {}) => preloginEligibility({ caseId, allowList: ALLOW, buildDebuggable: false, ...extra });
  assert.deepEqual(ask("send-message-in-channel"), { eligible: false, reason: "not_allow_listed" });
  assert.deepEqual(ask("login-email-password"), { eligible: false, reason: "never_class", class: "login-ui" });
  assert.deepEqual(ask("server-switch"), { eligible: false, reason: "never_class", class: "cross-server" });
  assert.equal(ask("server-create").class, "server-create");
  // Listed, but its own tags put it in a never class.
  for (const [tag, cls] of [["logout", "logout"], ["upgrade", "upgrade"], ["multi-server", "cross-server"], ["canary", "bootstrap-canary"], ["provisioning", "server-create"]]) {
    assert.deepEqual(ask("channel-switch-navigation", { caseMeta: { tags: [tag] } }), { eligible: false, reason: "never_class", class: cls });
  }
  assert.equal(ask("channel-switch-navigation", { caseMeta: { authFixture: "none" } }).reason, "case_runs_signed_out");
  // Listed with another intent, or a never class in the entry.
  const custom = { contract: ALLOWLIST_CONTRACT, cases: { a: { intent: "login" }, b: { intent: "ordinary", class: "logout" } }, never: {} };
  assert.equal(preloginEligibility({ caseId: "a", allowList: custom, buildDebuggable: false }).reason, "intent_not_ordinary");
  assert.equal(preloginEligibility({ caseId: "b", allowList: custom, buildDebuggable: false }).class, "logout");
  // Alpha / debuggable builds keep their fixtures; unknown build kind refuses.
  assert.equal(ask("channel-switch-navigation", { buildDebuggable: true }).reason, "debuggable_build_keeps_alpha_fixtures");
  assert.equal(ask("channel-switch-navigation", { buildDebuggable: undefined }).reason, "build_kind_unknown");
  assert.equal(preloginEligibility({ caseId: "channel-switch-navigation", allowList: null, buildDebuggable: false }).reason, "allow_list_missing_or_malformed");
  assert.equal(preloginEligibility({ caseId: "constructor", allowList: ALLOW, buildDebuggable: false }).reason, "not_allow_listed");
  assert.equal(attemptOutcome({ eligibility: ask("send-message-in-channel") }).outcome, "not_run");
});

test("every case in the repo: only allow-listed ordinary cases are eligible", () => {
  const dir = path.join(ROOT, "docs/qa-acceptance/cases");
  const eligible = fs.readdirSync(dir).filter((f) => f.endsWith(".yaml")).map((f) => caseMetaFromYaml(fs.readFileSync(path.join(dir, f), "utf8")))
    .filter((meta) => preloginEligibility({ caseId: meta.id, caseMeta: meta, allowList: ALLOW, buildDebuggable: false }).eligible)
    .map((meta) => meta.id);
  assert.deepEqual(eligible, Object.keys(ALLOW.cases));
  assert.deepEqual(caseMetaFromYaml("id: server-create\ntags: [manual-only, provisioning]\nauth_fixture: none\n"), { id: "server-create", tags: ["manual-only", "provisioning"], authFixture: "none" });
});

// ---------- wiring into the existing login record / sign-in run ----------
test("login record: a release-prelogin record is complete only with its attempt id and a device stamp", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "prelogin-"));
  const env = { GITHUB_RUN_ID: "100", GITHUB_RUN_ATTEMPT: "1" };
  try {
    writeRecord(dir, { mode: "release-prelogin", status: "complete", login_end: "11-17 10:00:05.000", attempt_id: attemptFor().attempt_id }, env);
    assert.equal(readRecord(dir, env).ok, true);
    writeRecord(dir, { mode: "release-prelogin", status: "complete", login_end: "11-17 10:00:05.000" }, env);
    assert.equal(readRecord(dir, env).ok, false);
    writeRecord(dir, { mode: "release-prelogin", status: "unknown", login_end: null, attempt_id: attemptFor().attempt_id }, env);
    assert.equal(readRecord(dir, env).ok, false);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("release_auto_login: a deferred app session counts only in the deferred sign-in run; sign-out phase needs the app's report", () => {
  const cleanup = { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: DEFERRED_APP_SESSION };
  assert.equal(summarizeCleanup(cleanup).ok, false);
  assert.equal(summarizeCleanup(cleanup, { deferAppSession: true }).ok, true);
  assert.equal(runOutcome({ signedIn: true, cleanup, strict: true }).result, "cleanup_failed");
  assert.equal(runOutcome({ signedIn: true, cleanup, strict: true, deferAppSession: true }).result, "signed_in");
  const signOut = { capture_file: "not_applicable", browser: "not_changed", capture_browser: "not_installed_by_run", web_session: "not_opened" };
  assert.equal(runOutcome({ phase: "sign-out", cleanup: { ...signOut, app_session: "app_reported_server_logout_2xx" } }).result, "signed_out");
  assert.equal(runOutcome({ phase: "sign-out", cleanup: { ...signOut, app_session: "local_cleared_server_unconfirmed" } }).exitCode, 1);
  assert.equal(runOutcome({ phase: "sign-out", cleanup: { ...signOut, app_session: "not_requested" } }).exitCode, 1);
});

test("receipts never carry the QA secrets", () => {
  assert.equal(receiptsLeak(['{"user_id":"usr-fake-1"}'], ["fake-password-1", undefined]), false);
  assert.equal(receiptsLeak(['{"x":"fake-password-1"}'], ["fake-password-1"]), true);
});

test("workflow: pre-login only with the release_prelogin input; Alpha path unchanged", () => {
  const wf = fs.readFileSync(path.join(ROOT, ".github/workflows/qa-acceptance.yml"), "utf8");
  assert.match(wf, /release_prelogin:/);
  assert.match(wf, /node scripts\/qa\/release_prelogin\.mjs plan /);
  assert.match(wf, /node scripts\/qa\/release_prelogin\.mjs login /);
  assert.match(wf, /node scripts\/qa\/release_prelogin\.mjs cleanup /);
  assert.match(wf, /node scripts\/qa\/release_prelogin\.mjs verdict /);
  // The existing password / bootstrap login line still runs when no pre-login is asked for.
  assert.match(wf, /node scripts\/qa\/login_phase\.mjs login --platform android --password-login "\$PW"/);
});

test("receipt writer state is read back from dumpsys, section by section", () => {
  const cls = "build.raft.app.core.data.auth.QaReleaseReadinessReceiver";
  const dump = (enabled, disabled) => [
    "Packages:",
    "  Package [build.raft.app] (abc):",
    "    User 0: installed=true hidden=false enabled=0",
    "      gids=[3003]",
    ...(disabled.length ? ["      disabledComponents:", ...disabled.map((c) => `        ${c}`)] : []),
    ...(enabled.length ? ["      enabledComponents:", ...enabled.map((c) => `        ${c}`)] : []),
    "    overlay paths:",
    `      ${cls}`,
  ].join("\n");
  assert.equal(componentStateFromDumpsys(dump([cls], []), cls), "enabled");
  assert.equal(componentStateFromDumpsys(dump([], [cls]), cls), "disabled");
  assert.equal(componentStateFromDumpsys(dump(["other.Cls"], ["x.Y"]), cls), "default");
  // A disabled list followed by an enabled list that does not name it: still disabled.
  assert.equal(componentStateFromDumpsys(dump(["other.Cls"], [cls]), cls), "disabled");
});
