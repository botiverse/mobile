// testbed_login_acceptance_result.mjs against a fake Testbed on loopback (no real network): it serves the
// GitHub OIDC token endpoint and POST /api/agent/run-cases/<id>/login-acceptance-result, records every
// request, and answers as the Testbed owner defined the endpoint (body exactly {id_token, verdict, reason}):
// 200 for the first write and for an identical resend, 409 once a different result is recorded, or the
// status a case gives. Login records are written with login_phase.mjs's own writers (writeRecord,
// markUnknown) in the shapes the login phase leaves them. Fake values only.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { LOGIN_ACCEPTANCE_REASONS, markUnknown, writeRecord } from "./login_phase.mjs";
import { RESULT_REASONS, reportLoginAcceptanceResult, resultFromRecord } from "./testbed_login_acceptance_result.mjs";
import { createTestbedReleaseClient } from "./testbed_release_settings.mjs";

const SCRIPT = fileURLToPath(new URL("./testbed_login_acceptance_result.mjs", import.meta.url));
const WORKFLOW = fileURLToPath(new URL("../../.github/workflows/qa-acceptance.yml", import.meta.url));
const RUN_CASE = "6d1f0c2b-3a4e-4b5c-8d6e-7f8091a2b3c4";
const OTHER_RUN_CASE = "00000000-0000-4000-8000-000000000000";
const ENDPOINT = `/api/agent/run-cases/${RUN_CASE}/login-acceptance-result`;
const EXECUTOR = "fake-executor-bearer";
const CLAIM = "fake-claim-credential";
const OIDC_REQUEST = "fake-oidc-request-bearer";
const REPLY = "fake-testbed-reply-body";
const RUN = { GITHUB_RUN_ID: "424242", GITHUB_RUN_ATTEMPT: "2" };
const BINDING = { case_id: "login-email-password", run_case_id: RUN_CASE, attempt: 1 };
const RECORDED = "result_recorded_by_fake";
// Nothing of these may be printed: credentials, the OIDC token, ids, Testbed's reply.
const PRIVATE = [EXECUTOR, CLAIM, OIDC_REQUEST, "fake-oidc-token", RUN_CASE, RUN.GITHUB_RUN_ID, REPLY];

// The endpoint as defined: the first result is recorded; the same result again is 200, another one 409.
function asTestbed(request, state) {
  const { verdict, reason } = JSON.parse(request.body);
  state.recorded ??= `${verdict}/${reason}`;
  return state.recorded === `${verdict}/${reason}` ? { status: 200, body: { state: RECORDED, echo: REPLY } }
    : { status: 409, body: { code: "LOGIN_ACCEPTANCE_RESULT_CONFLICT", echo: REPLY } };
}

// `reply(request, state)` -> { status, body, headers } (body a value, or a string sent as is) | "drop" (close the connection).
async function fakeTestbed(reply = asTestbed) {
  const requests = [];
  const state = { recorded: null };
  const server = http.createServer((req, res) => {
    let text = "";
    req.on("data", (chunk) => { text += chunk; });
    req.on("end", () => {
      const url = new URL(req.url, "http://127.0.0.1");
      if (url.pathname === "/oidc") {
        requests.push({ kind: "oidc", audience: url.searchParams.get("audience"), authorization: req.headers.authorization });
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ value: `fake-oidc-token-for-${url.searchParams.get("audience")}` }));
        return;
      }
      const request = { kind: "testbed", method: req.method, path: url.pathname, headers: req.headers, body: text };
      requests.push(request);
      const answer = reply(request, state);
      if (answer === "drop") { req.socket.destroy(); return; }
      res.writeHead(answer.status, { "content-type": "application/json", "cache-control": "no-store", ...answer.headers });
      res.end(typeof answer.body === "string" ? answer.body : JSON.stringify(answer.body ?? { echo: REPLY }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const sent = () => requests.filter((r) => r.kind === "testbed");
  return { origin, requests, sent, state, close: () => new Promise((resolve) => server.close(resolve)) };
}

// A job workspace (qa-evidence) and runner temp with this run case's claim credential (stamp_report.sh claim writes it there).
function job(origin, extra = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "login-acceptance-result-"));
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(runnerTemp, `stamp-claim-${RUN_CASE}`), CLAIM, { mode: 0o600 });
  const env = {
    PATH: process.env.PATH, RUNNER_TEMP: runnerTemp, STAMP_RUN_CASE_ID: RUN_CASE, STAMP_URL: origin, STAMP_TOKEN: EXECUTOR,
    GITHUB_SERVER_URL: "https://github.com", GITHUB_REPOSITORY: "botiverse/mobile", ...RUN,
    ACTIONS_ID_TOKEN_REQUEST_URL: `${origin}/oidc?api-version=2.0`, ACTIONS_ID_TOKEN_REQUEST_TOKEN: OIDC_REQUEST, ...extra,
  };
  return { root, runnerTemp, env, out: path.join(root, "qa-evidence"), close: () => fs.rmSync(root, { recursive: true, force: true }) };
}

// The records the login phase leaves, through its own writers: a pass completes the record; a fail leaves it
// unknown with the verdict; an unknown is the phase's "not finished" record turned unknown by markUnknown.
const STAMP = "11-21 10:00:00.000";
function writeAcceptance(out, env, verdict, reason, binding = BINDING) {
  if (verdict === "pass") {
    return writeRecord(out, { mode: "password", status: "complete", login_end: STAMP, reason: "app_login_complete result=app_shell reason=exchanged",
      acceptance: { ...binding, verdict, reason } }, env);
  }
  if (verdict === "fail") {
    return writeRecord(out, { mode: "password", status: "unknown", login_end: null, reason: `login acceptance fail: ${reason}`, acceptance: { ...binding, verdict, reason } }, env);
  }
  writeRecord(out, { mode: "password", status: "unknown", login_end: null, reason: "login phase started and did not finish",
    acceptance: { ...binding, verdict: "unknown", reason: "not_finished" } }, env);
  return markUnknown(out, `login acceptance unknown: ${reason}`, env, reason);
}
// Any acceptance object as is, on a record of the given status (complete for a pass, unknown otherwise).
const writeRaw = (out, env, acceptance, status = acceptance?.verdict === "pass" ? "complete" : "unknown") =>
  writeRecord(out, { mode: "password", status, login_end: status === "complete" ? STAMP : null, reason: "x", acceptance }, env);

const bodyOf = (request) => JSON.parse(request.body);
const idToken = (origin) => `fake-oidc-token-for-${origin}${ENDPOINT}`;

const cli = (env, cwd) => new Promise((resolve) => {
  const child = spawn(process.execPath, [SCRIPT], { env, cwd });
  let stdout = "", stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (status) => resolve({ status, stdout, stderr }));
});

test("HTTP 200: a pass is reported as recorded, with exactly the request the Testbed endpoint takes", async () => {
  const testbed = await fakeTestbed();
  const j = job(testbed.origin);
  try {
    const record = writeAcceptance(j.out, j.env, "pass", "signed_in_as_expected");
    const r = await cli(j.env, j.root);
    assert.equal(r.status, 0, r.stderr);
    assert.equal(r.stdout, "testbed login acceptance: result reported\n");
    assert.equal(r.stderr, "");
    for (const value of [...PRIVATE, JSON.stringify(record), "signed_in_as_expected"]) assert.ok(!r.stdout.includes(value) && !r.stderr.includes(value), value);
    // POST, the executor bearer, this run's job URL and the claim credential, and a fresh OIDC token for this endpoint's full URL.
    const [oidc, call, ...rest] = testbed.requests;
    assert.deepEqual(rest, [], "one request");
    assert.deepEqual(oidc, { kind: "oidc", audience: `${testbed.origin}${ENDPOINT}`, authorization: `Bearer ${OIDC_REQUEST}` });
    assert.deepEqual([call.method, call.path], ["POST", ENDPOINT]);
    assert.equal(call.headers.authorization, `Bearer ${EXECUTOR}`);
    assert.equal(call.headers["x-testbed-ci-job-ref"], "https://github.com/botiverse/mobile/actions/runs/424242");
    assert.equal(call.headers["x-testbed-claim-token"], CLAIM);
    assert.deepEqual(Object.keys(call.headers).filter((h) => h.startsWith("x-")).sort(), ["x-testbed-ci-job-ref", "x-testbed-claim-token"]);
    assert.match(call.headers["content-type"], /^application\/json/);
    // Exactly three keys: no attempt id, no ids; Testbed derives the attempt from the claim and the job.
    assert.deepEqual(Object.keys(bodyOf(call)).sort(), ["id_token", "reason", "verdict"]);
    assert.deepEqual(bodyOf(call), { id_token: idToken(testbed.origin), verdict: "pass", reason: "signed_in_as_expected" });
  } finally {
    j.close();
    await testbed.close();
  }
});

test("every verdict/reason pair login_phase.mjs can record (pass, fail, unknown) is sent as recorded, and nothing else of the record", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200 }));
  try {
    const pairs = Object.entries(LOGIN_ACCEPTANCE_REASONS).flatMap(([verdict, reasons]) => reasons.map((reason) => [verdict, reason]));
    assert.ok(pairs.length > 30 && ["pass", "fail", "unknown"].every((v) => pairs.some(([verdict]) => verdict === v)));
    const cases = pairs.map(([verdict, reason]) => [`${verdict}/${reason}`, (j) => writeAcceptance(j.out, j.env, verdict, reason), { verdict, reason }]);
    cases.push(
      // What the agent does when a login surface shows up after a pass (markUnknown): the record's unknown, as recorded.
      ["a pass invalidated later", (j) => { writeAcceptance(j.out, j.env, "pass", "signed_in_as_expected"); markUnknown(j.out, "login-like surface on screen", j.env); },
        { verdict: "unknown", reason: "record_invalidated" }],
      // Other fields of the acceptance never travel.
      ["extra fields", (j) => writeRaw(j.out, j.env, { ...BINDING, verdict: "fail", reason: "user_mismatch", note: "fake.qa@example.test", attempt_id: `pla_${"0".repeat(32)}` }),
        { verdict: "fail", reason: "user_mismatch" }],
    );
    for (const [name, write, expected] of cases) {
      const j = job(testbed.origin);
      try {
        write(j);
        testbed.requests.length = 0;
        assert.deepEqual(await reportLoginAcceptanceResult({ env: j.env, out: j.out }), expected, name);
        assert.deepEqual(testbed.sent().map(bodyOf), [{ id_token: idToken(testbed.origin), ...expected }], name);
      } finally { j.close(); }
    }
  } finally {
    await testbed.close();
  }
});

test("no record, an unreadable one or one without an acceptance is reported as unknown/record_missing", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200 }));
  const file = (j) => path.join(j.out, "login-record.json");
  const text = (value) => (j) => { fs.mkdirSync(j.out, { recursive: true }); fs.writeFileSync(file(j), value); };
  try {
    for (const [name, write] of [
      ["no evidence directory", () => {}],
      ["no record file", (j) => fs.mkdirSync(j.out, { recursive: true })],
      ["a directory in its place", (j) => fs.mkdirSync(file(j), { recursive: true })],
      ["not JSON", text("{")],
      ["a JSON array", text("[]")],
      ["JSON null", text("null")],
      ["another contract", text(JSON.stringify({ contract: "qa-login-record/v0", run: "424242:2", acceptance: { ...BINDING, verdict: "pass", reason: "signed_in_as_expected" } }))],
      // The v1 records without an acceptance: the ordinary password login and a mode that never signs in.
      ["a complete record without an acceptance", (j) => writeRecord(j.out, { mode: "password", status: "complete", login_end: STAMP, reason: "x" }, j.env)],
      ["a no-login record", (j) => writeRecord(j.out, { mode: "none", status: "no-login", login_end: null, reason: "this mode never signs in" }, j.env)],
      ["acceptance null", (j) => writeRaw(j.out, j.env, null)],
      ["acceptance a string", (j) => writeRaw(j.out, j.env, "pass")],
      ["acceptance an array", (j) => writeRaw(j.out, j.env, [BINDING])],
    ]) {
      const j = job(testbed.origin);
      try {
        write(j);
        testbed.requests.length = 0;
        assert.deepEqual(resultFromRecord(j.out, j.env), { verdict: "unknown", reason: "record_missing" }, name);
        await reportLoginAcceptanceResult({ env: j.env, out: j.out });
        assert.deepEqual(testbed.sent().map(bodyOf), [{ id_token: idToken(testbed.origin), verdict: "unknown", reason: "record_missing" }], name);
      } finally { j.close(); }
    }
  } finally {
    await testbed.close();
  }
});

test("a record bound to another case, run case or GitHub run, with an attempt other than 1, or a pass not on a completed login record, is unknown/record_invalidated", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200 }));
  const pass = { verdict: "pass", reason: "signed_in_as_expected" };
  try {
    for (const [name, write, extra = {}] of [
      ["another case", (j) => writeRaw(j.out, j.env, { ...BINDING, case_id: "send-message-in-channel", verdict: "fail", reason: "user_mismatch" })],
      ["another case, a pass", (j) => writeRaw(j.out, j.env, { ...BINDING, case_id: "send-message-in-channel", ...pass })],
      ["no case", (j) => writeRaw(j.out, j.env, { run_case_id: RUN_CASE, attempt: 1, verdict: "unknown", reason: "no_login_receipt" })],
      ["another run case", (j) => writeAcceptance(j.out, j.env, "fail", "user_mismatch", { ...BINDING, run_case_id: OTHER_RUN_CASE })],
      ["no run case (markUnknown keeps only a well-formed one)", (j) => writeAcceptance(j.out, j.env, "unknown", "provider_unavailable", { ...BINDING, run_case_id: null })],
      ["a job without a run case", (j) => writeAcceptance(j.out, j.env, "pass", "signed_in_as_expected"), { STAMP_RUN_CASE_ID: "" }],
      // The required login phase without a readable input binds this job's run case but has no attempt.
      ["no attempt (the input never arrived)", (j) => writeAcceptance(j.out, j.env, "unknown", "input_missing", { ...BINDING, attempt: null })],
      ["attempt 0", (j) => writeRaw(j.out, j.env, { ...BINDING, attempt: 0, ...pass })],
      // Testbed takes the first attempt only: a record written for attempt 2 is not this job's result.
      ["attempt 2", (j) => writeAcceptance(j.out, j.env, "fail", "user_mismatch", { ...BINDING, attempt: 2 })],
      ["attempt as a string", (j) => writeRaw(j.out, j.env, { ...BINDING, attempt: "1", ...pass })],
      ["a fractional attempt", (j) => writeRaw(j.out, j.env, { ...BINDING, attempt: 1.5, verdict: "fail", reason: "server_mismatch" })],
      ["a record of another GitHub run", (j) => writeAcceptance(j.out, { ...j.env, GITHUB_RUN_ID: "1" }, "pass", "signed_in_as_expected")],
      ["a record of another run attempt", (j) => writeAcceptance(j.out, { ...j.env, GITHUB_RUN_ATTEMPT: "1" }, "fail", "user_mismatch")],
      ["a record without a GitHub run, read without one", (j) => writeAcceptance(j.out, { ...j.env, GITHUB_RUN_ID: "" }, "pass", "signed_in_as_expected"), { GITHUB_RUN_ID: "" }],
      // Its run reads "local:2" (login_phase.mjs runId): it still is no GitHub run.
      ["a record without a GitHub run, read where the run id is local", (j) => writeAcceptance(j.out, { ...j.env, GITHUB_RUN_ID: "" }, "pass", "signed_in_as_expected"),
        { GITHUB_RUN_ID: "local" }],
      // A pass counts only on a record readRecord accepts as a completed password login (the one capture opens on).
      ["a pass on a record that is not complete", (j) => writeRaw(j.out, j.env, { ...BINDING, ...pass }, "unknown")],
      ["a pass without the login end", (j) => writeRecord(j.out, { mode: "password", status: "complete", login_end: null, reason: "x", acceptance: { ...BINDING, ...pass } }, j.env)],
      ["a pass on a no-login record", (j) => writeRecord(j.out, { mode: "none", status: "no-login", login_end: null, reason: "x", acceptance: { ...BINDING, ...pass } }, j.env)],
      // readRecord's own rules cannot even read this login end (it throws): still reported, never a pass.
      ["a pass whose login end is an object", (j) => writeRecord(j.out, { mode: "password", status: "complete", login_end: { toString: 1 }, reason: "x", acceptance: { ...BINDING, ...pass } }, j.env)],
    ]) {
      const j = job(testbed.origin);
      try {
        write(j);
        const env = { ...j.env, ...extra };
        assert.deepEqual(resultFromRecord(j.out, env), { verdict: "unknown", reason: "record_invalidated" }, name);
        if (!env.STAMP_RUN_CASE_ID || !env.GITHUB_RUN_ID) continue; // Such a job cannot report at all (no claim of its own, no job ref).
        testbed.requests.length = 0;
        await reportLoginAcceptanceResult({ env, out: j.out });
        assert.deepEqual(testbed.sent().map(bodyOf), [{ id_token: idToken(testbed.origin), verdict: "unknown", reason: "record_invalidated" }], name);
      } finally { j.close(); }
    }
  } finally {
    await testbed.close();
  }
});

test("a verdict/reason pair login_phase.mjs cannot produce is reported as unknown/reason_unlisted, never as recorded", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200 }));
  const ref = "ab".repeat(32);
  try {
    for (const [verdict, reason] of [
      ["pass", "user_mismatch"], ["fail", "signed_in_as_expected"], ["unknown", "user_mismatch"], ["fail", "input_missing"],
      // This report's own codes are never a record's.
      ["unknown", "record_missing"], ["unknown", "reason_unlisted"],
      ["unknown", "not_a_code"], ["fail", ""], ["unknown", "USER_MISMATCH"], ["unknown", ref], ["fail", undefined], ["unknown", ["record_invalidated"]],
      ["maybe", "signed_in_as_expected"], ["PASS", "signed_in_as_expected"], [undefined, "signed_in_as_expected"], [["pass"], "signed_in_as_expected"],
      ["constructor", "constructor"], ["__proto__", "user_mismatch"], ["toString", "toString"],
    ]) {
      const name = JSON.stringify([verdict, reason]) ?? "undefined";
      const j = job(testbed.origin);
      try {
        writeRaw(j.out, j.env, { ...BINDING, verdict, reason });
        testbed.requests.length = 0;
        assert.deepEqual(resultFromRecord(j.out, j.env), { verdict: "unknown", reason: "reason_unlisted" }, name);
        await reportLoginAcceptanceResult({ env: j.env, out: j.out });
        assert.deepEqual(testbed.sent().map(bodyOf), [{ id_token: idToken(testbed.origin), verdict: "unknown", reason: "reason_unlisted" }], name);
        assert.ok(!testbed.sent()[0].body.includes(ref), name);
      } finally { j.close(); }
    }
    assert.deepEqual(Object.values(RESULT_REASONS), ["record_missing", "record_invalidated", "reason_unlisted"]);
  } finally {
    await testbed.close();
  }
});

test("Testbed answers 200 to the first write and to an identical resend; a different result afterwards is a 409, never retried", async () => {
  const testbed = await fakeTestbed();
  const j = job(testbed.origin);
  try {
    writeAcceptance(j.out, j.env, "fail", "server_not_selected");
    const report = () => reportLoginAcceptanceResult({ env: j.env, out: j.out, retryDelaysMs: [0, 0] });
    assert.deepEqual(await report(), { verdict: "fail", reason: "server_not_selected" });
    assert.deepEqual(await report(), { verdict: "fail", reason: "server_not_selected" }, "an identical resend succeeds");
    assert.equal(testbed.state.recorded, "fail/server_not_selected");
    // A later step invalidated the record: another result for the same attempt.
    markUnknown(j.out, "login-like surface on screen", j.env);
    await assert.rejects(report(), (e) => e.message === "login-acceptance-result_conflict");
    assert.deepEqual(testbed.sent().map((r) => bodyOf(r).verdict), ["fail", "fail", "unknown"], "the 409 is not retried");
  } finally {
    j.close();
    await testbed.close();
  }
});

test("a 5xx or a lost connection is tried three times in all, then fails; a 5xx or a lost reply followed by 200 succeeds", async () => {
  for (const [name, answers, code] of [
    ["503 three times", [503, 503, 503], "login-acceptance-result_http_503"],
    ["a dropped connection three times", ["drop", "drop", "drop"], "login-acceptance-result_no_reply"],
    ["500, a dropped connection, 502", [500, "drop", 502], "login-acceptance-result_http_502"],
    ["503, then 200", [503, 200], null],
    ["a dropped connection, then 200", ["drop", 200], null],
    ["503, a dropped connection, then 200", [503, "drop", 200], null],
  ]) {
    let n = 0;
    const testbed = await fakeTestbed(() => {
      const answer = answers[Math.min(n++, answers.length - 1)];
      return answer === "drop" ? "drop" : { status: answer };
    });
    const j = job(testbed.origin);
    try {
      writeAcceptance(j.out, j.env, "pass", "signed_in_as_expected");
      const report = reportLoginAcceptanceResult({ env: j.env, out: j.out, retryDelaysMs: [0, 0] });
      if (code) await assert.rejects(report, (e) => e.message === code, name);
      else assert.deepEqual(await report, { verdict: "pass", reason: "signed_in_as_expected" }, name);
      assert.equal(testbed.sent().length, answers.length, name);
      // The same request each time.
      assert.equal(new Set(testbed.sent().map((r) => r.body)).size, 1, name);
    } finally {
      j.close();
      await testbed.close();
    }
  }
});

test("409 and every other status but 200 stop at once with a fixed code; a redirect is not followed", async () => {
  for (const [status, code, headers] of [
    [409, "login-acceptance-result_conflict"],
    [400, "login-acceptance-result_http_400"],
    [401, "login-acceptance-result_http_401"],
    [403, "login-acceptance-result_http_403"],
    [404, "login-acceptance-result_http_404"],
    [422, "login-acceptance-result_http_422"],
    [201, "login-acceptance-result_http_201"],
    [204, "login-acceptance-result_http_204"],
    [302, "login-acceptance-result_http_302", { location: "/elsewhere" }],
    [307, "login-acceptance-result_http_307", { location: `${ENDPOINT}-moved` }],
  ]) {
    const testbed = await fakeTestbed((request) => (request.path === ENDPOINT ? { status, headers, body: status === 204 ? "" : undefined } : { status: 200 }));
    const j = job(testbed.origin);
    try {
      writeAcceptance(j.out, j.env, "unknown", "no_login_receipt");
      await assert.rejects(reportLoginAcceptanceResult({ env: j.env, out: j.out, retryDelaysMs: [0, 0] }), (e) => e.message === code, String(status));
      assert.deepEqual(testbed.sent().map((r) => r.path), [ENDPOINT], `${status}: one request, never retried or redirected`);
    } finally {
      j.close();
      await testbed.close();
    }
  }
});

test("the command line prints one fixed line, never a credential, an id, the record or Testbed's reply; exit 1 when not reported", async () => {
  // A 409 whose reply echoes everything it was sent.
  const testbed = await fakeTestbed((request) => ({ status: 409, body: { code: "LOGIN_ACCEPTANCE_RESULT_CONFLICT", echo: REPLY, request } }));
  const j = job(testbed.origin);
  try {
    const record = writeAcceptance(j.out, j.env, "fail", "user_and_server_mismatch");
    const r = await cli(j.env, j.root);
    assert.equal(r.status, 1);
    assert.equal(r.stdout, "");
    assert.equal(r.stderr, "::error title=login acceptance result::not reported (login-acceptance-result_conflict)\n");
    for (const value of [...PRIVATE, JSON.stringify(record), "user_and_server_mismatch"]) assert.ok(!r.stderr.includes(value), value);
    assert.equal(testbed.sent().length, 1);
  } finally {
    j.close();
    await testbed.close();
  }
  // Without a record the job still reports (unknown/record_missing), and says only that it did.
  const ok = await fakeTestbed();
  const k = job(ok.origin);
  try {
    const r = await cli(k.env, k.root);
    assert.deepEqual([r.status, r.stdout, r.stderr], [0, "testbed login acceptance: result reported\n", ""]);
    assert.deepEqual(ok.sent().map(bodyOf), [{ id_token: idToken(ok.origin), verdict: "unknown", reason: "record_missing" }]);
  } finally {
    k.close();
    await ok.close();
  }
});

test("without this run case's claim credential, a GitHub OIDC token or the Testbed configuration nothing is sent", async () => {
  const testbed = await fakeTestbed();
  try {
    for (const [name, patch, code, oidc] of [
      ["no claim credential", (j) => fs.rmSync(path.join(j.runnerTemp, `stamp-claim-${RUN_CASE}`)), "claim_token_missing", 0],
      ["an empty claim credential", (j) => fs.writeFileSync(path.join(j.runnerTemp, `stamp-claim-${RUN_CASE}`), "\n"), "claim_token_missing", 0],
      ["no OIDC request URL", (j) => { delete j.env.ACTIONS_ID_TOKEN_REQUEST_URL; }, "oidc_unavailable", 0],
      ["no Testbed configuration", (j) => { delete j.env.STAMP_TOKEN; }, "testbed_not_configured", 0],
    ]) {
      const j = job(testbed.origin);
      try {
        writeAcceptance(j.out, j.env, "pass", "signed_in_as_expected");
        patch(j);
        testbed.requests.length = 0;
        await assert.rejects(reportLoginAcceptanceResult({ env: j.env, out: j.out }), (e) => e.message === code, name);
        assert.equal(testbed.sent().length, 0, name);
        assert.equal(testbed.requests.length, oidc, name);
      } finally { j.close(); }
    }
  } finally {
    await testbed.close();
  }
});

test("the client sends only a verdict and a reason code: anything else is refused before any request", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200 }));
  const j = job(testbed.origin);
  try {
    const client = createTestbedReleaseClient({ env: j.env, claimToken: CLAIM, retryDelaysMs: [0, 0] });
    // A 64-hex ref never fits: digits, or (all letters) longer than any code.
    for (const [verdict, reason] of [["maybe", "user_mismatch"], [undefined, "user_mismatch"], ["fail", ""], ["fail", "0f".repeat(32)], ["fail", "ab".repeat(32)],
      ["fail", "a".repeat(41)], ["fail", "user mismatch"], ["fail", "User_mismatch"], ["fail", "user_mismatch_"], ["fail", 7], ["fail", ["user_mismatch"]]]) {
      await assert.rejects(client.loginAcceptanceResult({ verdict, reason }), (e) => e.message === "login-acceptance-result_request_invalid", JSON.stringify([verdict, reason]));
    }
    assert.equal(testbed.requests.length, 0, "not even an OIDC token");
    await client.loginAcceptanceResult({ verdict: "unknown", reason: "reason_unlisted", extra: "x" });
    assert.deepEqual(testbed.sent().map(bodyOf), [{ id_token: idToken(testbed.origin), verdict: "unknown", reason: "reason_unlisted" }]);
  } finally {
    j.close();
    await testbed.close();
  }
});

test("qa-acceptance reports the result only for the claimed case login-email-password, after the emulator step and before the claim ends", () => {
  const source = fs.readFileSync(WORKFLOW, "utf8");
  const at = (name) => {
    const i = source.indexOf(`      - name: ${name}\n`);
    assert.ok(i > 0 && source.indexOf(`      - name: ${name}\n`, i + 1) < 0, `one step ${name}`);
    return i;
  };
  // A step's own lines, comments dropped.
  const step = (name) => source.slice(at(name), source.indexOf("\n      - name:", at(name) + 1)).split("\n").filter((l) => !/^\s*#/.test(l)).join("\n");
  const name = "Report the login acceptance result to Testbed";
  const report = step(name);
  assert.match(report, /\n {8}if: \$\{\{ always\(\) && steps\.stamp-claim\.outputs\.claimed_case == 'login-email-password' \}\}\n/);
  assert.match(report, /\n {8}continue-on-error: true\n/, "a failed report must not stop the verdict report");
  assert.match(report, /\n {8}run: node scripts\/qa\/testbed_login_acceptance_result\.mjs\n*$/);
  assert.deepEqual(report.split("\n").filter((l) => /^ {8}[a-z-]+:/.test(l)).map((l) => l.trim().split(":")[0]), ["if", "continue-on-error", "env", "run"]);
  // The env of the input fetch, and only that: no QA account, no model key.
  const envOf = (text) => [...text.matchAll(/^ {10}([A-Z_]+): (.*)$/gm)].map((m) => `${m[1]}: ${m[2]}`);
  assert.deepEqual(envOf(report), ["STAMP_RUN_CASE_ID: ${{ inputs.stamp_run_case_id }}", "STAMP_URL: ${{ secrets.STAMP_URL }}", "STAMP_TOKEN: ${{ secrets.STAMP_TOKEN }}"]);
  assert.deepEqual(envOf(report), envOf(step("Fetch the login acceptance input from Testbed")));
  // After the emulator step (whose login phase writes the record) and the input cleanup; before both steps that end the claim.
  assert.ok(at("Run acceptance agent") < at("Remove the login acceptance input") && at("Remove the login acceptance input") < at(name));
  assert.ok(at(name) < at("Report verdict") && at(name) < at("Report pre-claim infrastructure failure"));
  assert.match(step("Report verdict"), /stamp_report\.sh report-verdict/);
  assert.match(step("Report pre-claim infrastructure failure"), /stamp_report\.sh workflow-error/);
  // The job may request the OIDC token.
  const job = source.slice(source.indexOf("\n  acceptance:\n"), source.indexOf("\n    steps:\n", source.indexOf("\n  acceptance:\n")));
  assert.match(job, /\n {4}permissions:\n {6}contents: read\n {6}actions: read\n {6}id-token: write$/);
});
