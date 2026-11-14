// Failure and positive controls for the review of #4806 (Argus's replay probes, fake data only):
// logout evidence bound to the app's process / route / result, the sign-out probe, the scenarios'
// write and overall-result rules, and the Hands serve path being gone from this lane.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assessAppLogout, logoutProbeProblem } from "./release_auto_login_lib.mjs";
import { classifyWrite, overallResult } from "./scenarios/scenario_outcome.mjs";
import { artifactOverall } from "./scenarios/artifact_reuse.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const PID = 4313;
const TAP = 1794690530.0;
// One logcat -v epoch line of the app's process (or another pid / time).
const line = (msg, { pid = PID, t = TAP + 1, tag = "SlockSharedAuth" } = {}) => `  ${t.toFixed(3)}  ${pid}  ${pid + 40} I ${tag}: ${msg}`;
const good = [
  line("auth_logout_enter"),
  line("shared_network slock_http_execute_finish method=POST path=https://api.raft.build/api/auth/logout status=200 code=200 kind=none traceId=168cc934ee55307d8419237104ba46f4", { tag: "SlockNetwork" }),
  line("auth_logout_api_result result=success"),
  line("auth_logout_cleared serverRevocation=success"),
];
const assess = (lines, opts = {}) => assessAppLogout(lines.join("\n"), { pid: PID, sinceEpoch: TAP, ...opts });

test("logout evidence: the app's own POST auth/logout 2xx + result=success is the only pass", () => {
  const r = assess(good);
  assert.equal(r.verified, true);
  assert.equal(r.reason, "app_reported_2xx");
  assert.deepEqual(r.http_statuses, [200]);
  // The relative route the HTTP layer also logs counts the same.
  assert.equal(assess([line("method=POST path=auth/logout status=204"), line("auth_logout_api_result result=success")]).verified, true);
  // Kept lines are rebuilt from fields: no query, no other text.
  assert.ok(r.lines.every((l) => !/\?|Bearer|refresh/i.test(l)));
});

test("logout evidence: Argus probe — one unrelated GET /diagnostics/auth/logout 200 line is not a logout", () => {
  const r = assessAppLogout("W UnrelatedApp: method=GET path=/diagnostics/auth/logout status=200\n", { pid: PID, sinceEpoch: TAP });
  assert.equal(r.verified, false);
  // Even from the app's own pid, after the tap, with the app's success line: wrong method / route.
  assert.equal(assess([line("method=GET path=/diagnostics/auth/logout status=200"), line("auth_logout_api_result result=success")]).verified, false);
  assert.equal(assess([line("method=POST path=/diagnostics/auth/logout status=200"), line("auth_logout_api_result result=success")]).verified, false);
  assert.equal(assess([line("method=POST path=https://api.raft.build/api/auth/logout-all status=200"), line("auth_logout_api_result result=success")]).verified, false);
});

test("logout evidence: fake 503 / 403, failure results and conflicts are not verified", () => {
  for (const status of [503, 403, 401]) {
    const r = assess([line(`method=POST path=auth/logout status=${status}`), line(`auth_logout_api_result result=failure code=${status}`)]);
    assert.equal(r.verified, false, `HTTP ${status}`);
  }
  // A 2xx line next to a 503 for the same request: conflicting.
  assert.equal(assess([...good, line("method=POST path=auth/logout status=503")]).reason, "conflicting_http_status");
  // Success result + a failure result.
  assert.equal(assess([...good, line("auth_logout_api_result result=error errorType=IOException")]).verified, false);
  // serverRevocation says otherwise.
  assert.equal(assess([...good.slice(0, 3), line("auth_logout_cleared serverRevocation=failure_code_500")]).reason, "conflicting_server_revocation");
  // Only one half of the evidence.
  assert.equal(assess([good[1]]).reason, "no_logout_result_line");
  assert.equal(assess([good[2]]).reason, "no_post_auth_logout_line");
});

test("logout evidence: another process, a line before the tap, empty or unreadable logs are not verified", () => {
  assert.equal(assess(good.map((l) => l.replace(` ${PID} `, " 9999 "))).verified, false);
  assert.equal(assess([line(good[1].split(": ").slice(1).join(": "), { t: TAP - 5 }), line("auth_logout_api_result result=success", { t: TAP - 5 })]).verified, false);
  assert.equal(assessAppLogout("", { pid: PID, sinceEpoch: TAP }).verified, false);
  assert.equal(assessAppLogout(null, { pid: PID, sinceEpoch: TAP }).verified, false);
  assert.equal(assessAppLogout(good.join("\n"), { pid: null, sinceEpoch: TAP }).verified, false);
  assert.equal(assessAppLogout(good.join("\n"), { pid: PID, sinceEpoch: NaN }).verified, false);
});

test("sign-out probe: logcat clear failure, pid reuse / restart, extra taps -> not bound", () => {
  const ok = { logcatCleared: true, taps: 1, before: "4313:88120", atTap: "4313:88120", after: "4313:88120" };
  assert.equal(logoutProbeProblem(ok), null);
  assert.equal(logoutProbeProblem({ ...ok, logcatCleared: false }), "logcat_clear_failed");
  // Same pid, different start time: the pid was reused by a new process.
  assert.equal(logoutProbeProblem({ ...ok, after: "4313:91000" }), "app_process_changed");
  // The app restarted between the start of the sign-out and the tap.
  assert.equal(logoutProbeProblem({ ...ok, atTap: "5120:93000", after: "5120:93000" }), "app_process_changed");
  assert.equal(logoutProbeProblem({ ...ok, before: null }), "app_process_unknown");
  assert.equal(logoutProbeProblem({ ...ok, taps: 2 }), "logout_taps_2");
  assert.equal(logoutProbeProblem({ ...ok, taps: 0 }), "logout_taps_0");
});

test("writes: 2xx ok, a definite 4xx refused, 5xx / 408 / 429 / no answer unknown", () => {
  assert.equal(classifyWrite({ status: 201 }), "ok");
  assert.equal(classifyWrite({ status: 204 }), "ok");
  assert.equal(classifyWrite({ status: 403 }), "refused");
  assert.equal(classifyWrite({ status: 404 }), "refused");
  for (const status of [500, 502, 503, 408, 429, 0]) assert.equal(classifyWrite({ status }), "unknown", String(status));
  assert.equal(classifyWrite(new Error("The operation was aborted due to timeout")), "unknown");
  assert.equal(classifyWrite(undefined), "unknown");
});

test("overall result: pass only when everything requested passed and cleanup is done", () => {
  const all = { s1: { result: "pass" }, s2: { result: "pass" } };
  assert.equal(overallResult({ requested: ["s1", "s2"], results: all, cleanup: [{ what: "t", outcome: "ok" }] }).result, "pass");
  // Argus probe: everything not_covered is not a pass.
  const nc = overallResult({ requested: ["s1", "s2"], results: { s1: { result: "not_covered" }, s2: { result: "not_covered" } } });
  assert.equal(nc.result, "partial");
  assert.deepEqual(nc.reasons, ["s1: not_covered", "s2: not_covered"]);
  assert.equal(overallResult({ requested: ["s1", "s2"], results: { s1: { result: "pass" } } }).result, "partial");
  assert.equal(overallResult({ requested: ["s1"], results: { s1: { result: "fail" } } }).result, "fail");
  // Cleanup: refused -> fail, no clear answer -> unknown.
  assert.equal(overallResult({ requested: ["s1"], results: all, cleanup: [{ what: "delete #1", outcome: "refused", status: 403 }] }).result, "fail");
  assert.equal(overallResult({ requested: ["s1"], results: all, cleanup: [{ what: "delete #1", outcome: "unknown", status: 503 }] }).result, "unknown");
  assert.equal(overallResult({ requested: ["s1"], results: all, unknownWrites: ["create (HTTP 503)"] }).result, "unknown");
});

test("artifact_reuse overall: unconfirmed content, missing session evidence or session not covered are partial", () => {
  const open = (step, expected, shows, result = "pass") => ({ step, expected, shows, result });
  const opens = [open("r1-A", "A", "A"), open("r1-B", "B", "B"), open("bg-return", "A", "A")];
  assert.equal(artifactOverall({ opens, session: { result: "pass" } }).result, "pass");
  assert.equal(artifactOverall({ opens: [...opens, open("r2-A", "A", null, "pass_unconfirmed_content")], session: { result: "pass" } }).result, "partial");
  assert.equal(artifactOverall({ opens: [...opens, open("r2-A", "A", null)], session: { result: "pass" } }).result, "partial");
  assert.equal(artifactOverall({ opens, session: { result: "pass_without_session_evidence" } }).result, "partial");
  assert.equal(artifactOverall({ opens, session: { result: "not_covered" } }).result, "partial");
  assert.equal(artifactOverall({ opens }).result, "partial");
  // The background step came back to B: a failed step.
  assert.equal(artifactOverall({ opens: [open("r1-A", "A", "A"), open("bg-return", "A", "B", "fail")], session: { result: "pass" } }).result, "fail");
});

test("artifact_reuse: the background step asserts the viewer still shows A", () => {
  const src = fs.readFileSync(path.join(ROOT, "scripts/qa/scenarios/artifact_reuse.mjs"), "utf8");
  assert.match(src, /if \(shown\.key !== "A"\) throw new Error\(`after the background the viewer shows/);
});

test("Argus probes for the Hands serve path: removed from this lane", () => {
  const wf = fs.readFileSync(path.join(ROOT, ".github/workflows/release-auto-login.yml"), "utf8");
  for (const gone of ["hands_serve_extra", "extra_apk_artifact", "HANDS_AUTH_TOKEN", "_BEARER_TOKEN", "android_update_hands_fixture.sh", "hands-cli", "hands-joiner"]) {
    assert.ok(!wf.includes(gone), `${gone} is still in release-auto-login.yml`);
  }
  assert.ok(!fs.existsSync(path.join(ROOT, "scripts/qa/scenarios/min_build_gate.mjs")), "min_build_gate.mjs is still in this lane");
});

test("workflow: one exact test server + channel from inputs; never from scenario_args", () => {
  const wf = fs.readFileSync(path.join(ROOT, ".github/workflows/release-auto-login.yml"), "utf8");
  assert.match(wf, /scenario_flags=--scenario \$\{SCENARIO\} --server-name \$\{TEST_SERVER\} --channel \$\{TEST_CHANNEL\} \$\{SCENARIO_ARGS\}/);
  assert.match(wf, /set the server and channel with test_server \/ test_channel, not in scenario_args/);
  assert.match(wf, /--server-name "\$\{TEST_SERVER\}" --channel "\$\{TEST_CHANNEL\}"/);
});
