// testbed_login_acceptance_input.mjs against a fake Testbed on loopback (no real network): it serves the
// GitHub OIDC token endpoint and POST /api/agent/run-cases/<id>/login-acceptance-input, records every
// request, and answers as botiverse/testbed's handler (worker/src/login-acceptance.ts, routes/agent.ts):
// HTTP 200 with the six-field input, or another status with an error code. Fake values only; the input
// uses the shared app identity receipt vector, so the written file is what login_phase.mjs expects.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { readCredentialFile, readTestbedInput } from "./login_phase.mjs";
import {
  acceptedCredential,
  acceptedInput,
  acceptedInputWithCredential,
  credentialPath,
  fetchLoginAcceptanceCredential,
  fetchLoginAcceptanceInput,
  inputPath,
} from "./testbed_login_acceptance_input.mjs";

const SCRIPT = fileURLToPath(new URL("./testbed_login_acceptance_input.mjs", import.meta.url));
const vector = JSON.parse(fs.readFileSync(new URL("./fixtures/app-identity-receipt-vector.json", import.meta.url), "utf8"));
const RUN_CASE = "6d1f0c2b-3a4e-4b5c-8d6e-7f8091a2b3c4";
const ENDPOINT = `/api/agent/run-cases/${RUN_CASE}/login-acceptance-input`;
const EXECUTOR = "fake-executor-bearer";
const CLAIM = "fake-claim-credential";
const OIDC_REQUEST = "fake-oidc-request-bearer";
const INPUT = {
  case_id: "login-email-password", run_case_id: RUN_CASE, attempt: 1, attempt_id: vector.sample_receipt.attempt_id, nonce: vector.fixture.nonce,
  expected_target: { user_id: vector.fixture.subject, server_id: vector.fixture.server_id, server_slug: vector.fixture.server_slug },
};
// Nothing of these may be printed (the vector's slug "test" is too common a word to search output for).
const PRIVATE = [EXECUTOR, CLAIM, OIDC_REQUEST, INPUT.attempt_id, INPUT.nonce, INPUT.expected_target.user_id, INPUT.expected_target.server_id];

// `reply(request)` -> { status, body } (body a value, or a string sent as is) | "drop" (close the connection).
async function fakeTestbed(reply) {
  const requests = [];
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
      const answer = reply(request);
      if (answer === "drop") { req.socket.destroy(); return; }
      res.writeHead(answer.status, { "content-type": "application/json", "cache-control": "no-store" });
      res.end(typeof answer.body === "string" ? answer.body : JSON.stringify(answer.body));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  return { origin, requests, close: () => new Promise((resolve) => server.close(resolve)) };
}

// A runner temp with this run case's claim credential (stamp_report.sh claim writes it there).
function job(origin, extra = {}) {
  const runnerTemp = fs.mkdtempSync(path.join(os.tmpdir(), "login-acceptance-input-"));
  fs.writeFileSync(path.join(runnerTemp, `stamp-claim-${RUN_CASE}`), CLAIM, { mode: 0o600 });
  const env = {
    PATH: process.env.PATH, RUNNER_TEMP: runnerTemp, STAMP_RUN_CASE_ID: RUN_CASE, STAMP_URL: origin, STAMP_TOKEN: EXECUTOR,
    GITHUB_SERVER_URL: "https://github.com", GITHUB_REPOSITORY: "botiverse/mobile", GITHUB_RUN_ID: "424242",
    ACTIONS_ID_TOKEN_REQUEST_URL: `${origin}/oidc?api-version=2.0`, ACTIONS_ID_TOKEN_REQUEST_TOKEN: OIDC_REQUEST, ...extra,
  };
  return { env, runnerTemp, file: inputPath(env), credFile: credentialPath(env), close: () => fs.rmSync(runnerTemp, { recursive: true, force: true }) };
}

const cli = (env, args = []) => new Promise((resolve) => {
  const child = spawn(process.execPath, [SCRIPT, ...args], { env });
  let stdout = "", stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (status) => resolve({ status, stdout, stderr }));
});

test("HTTP 200 with the six-field input: written 0600 in a 0700 directory, as the Testbed handler is called", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200, body: INPUT }));
  const j = job(testbed.origin);
  try {
    const r = await cli(j.env);
    assert.equal(r.status, 0, r.stderr);
    assert.equal(r.stdout, "testbed login acceptance: input received\n");
    for (const value of PRIVATE) assert.ok(!r.stdout.includes(value) && !r.stderr.includes(value), "nothing received or sent is printed");
    // The request: POST, the executor bearer, this run's job URL and the claim credential in the
    // x-testbed-* headers, and exactly {"id_token"} with a fresh token for this endpoint's full URL.
    const [oidc, call, ...rest] = testbed.requests;
    assert.deepEqual(rest, [], "one request");
    assert.deepEqual(oidc, { kind: "oidc", audience: `${testbed.origin}${ENDPOINT}`, authorization: `Bearer ${OIDC_REQUEST}` });
    assert.deepEqual([call.method, call.path], ["POST", ENDPOINT]);
    assert.equal(call.headers.authorization, `Bearer ${EXECUTOR}`);
    assert.equal(call.headers["x-testbed-ci-job-ref"], "https://github.com/botiverse/mobile/actions/runs/424242");
    assert.equal(call.headers["x-testbed-claim-token"], CLAIM);
    assert.match(call.headers["content-type"], /^application\/json/);
    assert.deepEqual(JSON.parse(call.body), { id_token: `fake-oidc-token-for-${testbed.origin}${ENDPOINT}` });
    // The file: a runner-owned 0700 directory, a 0600 file, nothing else left behind.
    assert.equal(fs.statSync(path.dirname(j.file)).mode & 0o777, 0o700);
    assert.equal(fs.statSync(j.file).mode & 0o777, 0o600);
    assert.deepEqual(fs.readdirSync(path.dirname(j.file)), ["input.json"]);
    assert.deepEqual(JSON.parse(fs.readFileSync(j.file, "utf8")), INPUT);
    // It is exactly what the required login phase takes: the vector's refs from the same nonce.
    const read = readTestbedInput(j.file, j.env, { required: true });
    assert.equal(read.problem, null);
    assert.deepEqual(read.expected, { userRef: vector.fixture.user_ref, serverRef: vector.fixture.server_ref });
  } finally {
    j.close();
    await testbed.close();
  }
});

test("any other status, a lost connection or another body writes nothing and removes an older input", async () => {
  const extra = { ...INPUT, note: "x" };
  const { expected_target: target, ...noTarget } = INPUT;
  for (const [name, answer, code] of [
    ["403 job not authorized", { status: 403, body: { code: "LOGIN_ACCEPTANCE_JOB_NOT_AUTHORIZED" } }, "login-acceptance-input_http_403"],
    ["409 attempt mismatch", { status: 409, body: { code: "LOGIN_ACCEPTANCE_ATTEMPT_MISMATCH" } }, "login-acceptance-input_http_409"],
    ["400 invalid request", { status: 400, body: { code: "INVALID_LOGIN_ACCEPTANCE_REQUEST" } }, "login-acceptance-input_http_400"],
    ["503 before a test target is configured", { status: 503, body: { code: "LOGIN_ACCEPTANCE_INPUT_UNAVAILABLE" } }, "login-acceptance-input_http_503"],
    ["201 is not the success status", { status: 201, body: INPUT }, "login-acceptance-input_http_201"],
    ["a lost connection", "drop", "login-acceptance-input_no_reply"],
    ["not JSON", { status: 200, body: "{" }, "login-acceptance-input_reply_unreadable"],
    ["a seventh field", { status: 200, body: extra }, "login-acceptance-input_reply_invalid"],
    ["a missing field", { status: 200, body: noTarget }, "login-acceptance-input_reply_invalid"],
    ["another case", { status: 200, body: { ...INPUT, case_id: "send-message-in-channel" } }, "login-acceptance-input_reply_invalid"],
    ["another run case", { status: 200, body: { ...INPUT, run_case_id: "00000000-0000-4000-8000-000000000000" } }, "login-acceptance-input_reply_invalid"],
    ["a malformed nonce", { status: 200, body: { ...INPUT, nonce: "ab" } }, "login-acceptance-input_reply_invalid"],
    ["a target with another key", { status: 200, body: { ...INPUT, expected_target: { ...target, email: "x" } } }, "login-acceptance-input_reply_invalid"],
  ]) {
    const testbed = await fakeTestbed(() => answer);
    const j = job(testbed.origin);
    try {
      fs.mkdirSync(path.dirname(j.file), { recursive: true });
      fs.writeFileSync(j.file, JSON.stringify(INPUT), { mode: 0o600 });
      await assert.rejects(fetchLoginAcceptanceInput({ env: j.env, retryDelaysMs: [0, 0] }), (e) => e.message === code, name);
      assert.equal(fs.existsSync(j.file), false, `${name}: no input, not even an older one`);
      // A refusal is never retried; a lost reply or a 5xx/other status is tried three times in all.
      const calls = testbed.requests.filter((r) => r.kind === "testbed").length;
      assert.equal(calls, /_http_4/.test(code) || /reply_invalid/.test(code) ? 1 : 3, name);
    } finally {
      j.close();
      await testbed.close();
    }
  }
});

test("the command line prints one fixed line, never a reply or a credential, and exits 1 when nothing was written", async () => {
  const testbed = await fakeTestbed(() => ({ status: 403, body: { code: "LOGIN_ACCEPTANCE_JOB_NOT_AUTHORIZED", echo: INPUT } }));
  const j = job(testbed.origin);
  try {
    const r = await cli(j.env);
    assert.equal(r.status, 1);
    assert.equal(r.stdout, "");
    assert.equal(r.stderr, "::error title=login acceptance input::not received (login-acceptance-input_http_403); the login phase ends unknown (input_missing)\n");
    assert.equal(fs.existsSync(j.file), false);
  } finally {
    j.close();
    await testbed.close();
  }
});

test("without this run case's claim credential or a GitHub OIDC token nothing is sent", async () => {
  const testbed = await fakeTestbed(() => ({ status: 200, body: INPUT }));
  try {
    for (const [name, patch, code, sent] of [
      ["no claim credential", (j) => fs.rmSync(path.join(j.runnerTemp, `stamp-claim-${RUN_CASE}`)), "claim_token_missing", 0],
      ["no OIDC request URL", (j) => { delete j.env.ACTIONS_ID_TOKEN_REQUEST_URL; }, "oidc_unavailable", 0],
      ["no Testbed configuration", (j) => { delete j.env.STAMP_TOKEN; }, "testbed_not_configured", 0],
    ]) {
      const j = job(testbed.origin);
      try {
        patch(j);
        testbed.requests.length = 0;
        await assert.rejects(fetchLoginAcceptanceInput({ env: j.env }), (e) => e.message === code, name);
        assert.equal(testbed.requests.filter((r) => r.kind === "testbed").length, sent, name);
        assert.equal(fs.existsSync(j.file), false, name);
      } finally { j.close(); }
    }
  } finally {
    await testbed.close();
  }
});

test("only exactly the six fields of this run case's login-email-password attempt are accepted", () => {
  const env = { STAMP_RUN_CASE_ID: RUN_CASE };
  assert.deepEqual(acceptedInput(INPUT, env), INPUT);
  assert.equal(acceptedInput(INPUT, {}), null, "no run case of this job: nothing to bind it to");
  for (const body of [null, [], "x", { ...INPUT, attempt: 0 }, { ...INPUT, attempt: "1" }, { ...INPUT, attempt_id: "pla_x" },
    { ...INPUT, expected_target: { ...INPUT.expected_target, user_id: "" } }, { ...INPUT, expected_target: [] }]) {
    assert.equal(acceptedInput(body, env), null, JSON.stringify(body));
  }
});

test("--include-credential writes target-only input.json and separate 0600 credential file, never printing secrets", async () => {
  const CRED = { email: "runtime.qa@example.invalid", password: "Runtime-Fake-Password-9x!" };
  const testbed = await fakeTestbed(() => ({ status: 200, body: { ...INPUT, credential: CRED } }));
  const j = job(testbed.origin);
  try {
    const r = await cli(j.env, ["--include-credential"]);
    assert.equal(r.status, 0, r.stderr);
    assert.equal(r.stdout, "testbed login acceptance: input and credential received\n");
    for (const value of [...PRIVATE, CRED.email, CRED.password]) {
      assert.ok(!r.stdout.includes(value) && !r.stderr.includes(value), `leaked ${value}`);
    }
    const [oidc, call, ...rest] = testbed.requests;
    assert.deepEqual(rest, [], "one request");
    assert.deepEqual(oidc, { kind: "oidc", audience: `${testbed.origin}${ENDPOINT}`, authorization: `Bearer ${OIDC_REQUEST}` });
    assert.deepEqual(JSON.parse(call.body), { id_token: `fake-oidc-token-for-${testbed.origin}${ENDPOINT}`, include_credential: true });
    // Target input file holds ONLY the six original fields (never credential).
    assert.equal(fs.statSync(path.dirname(j.file)).mode & 0o777, 0o700);
    assert.equal(fs.statSync(j.file).mode & 0o777, 0o600);
    assert.deepEqual(JSON.parse(fs.readFileSync(j.file, "utf8")), INPUT);
    assert.ok(!fs.readFileSync(j.file, "utf8").includes(CRED.password));
    // Credential file is separate 0600 inside 0700 dir, readable by preflight & login_phase.
    assert.equal(fs.statSync(path.dirname(j.credFile)).mode & 0o777, 0o700);
    assert.equal(fs.statSync(j.credFile).mode & 0o777, 0o600);
    assert.deepEqual(fs.readdirSync(path.dirname(j.credFile)), ["account"]);
    assert.deepEqual(readCredentialFile(j.credFile, { consume: false }), { email: CRED.email, password: CRED.password, problem: null });
    assert.equal(fs.existsSync(j.credFile), true, "preflight non-consuming read preserves file for device login");
    const read = readTestbedInput(j.file, j.env, { required: true });
    assert.equal(read.problem, null);
    assert.deepEqual(read.expected, { userRef: vector.fixture.user_ref, serverRef: vector.fixture.server_ref });
  } finally {
    j.close();
    await testbed.close();
  }
});

test("--include-credential stops fail-closed on missing/malformed credential or 503 LOGIN_ACCEPTANCE_CREDENTIAL_UNAVAILABLE and removes stale files", async () => {
  const CRED = { email: "runtime.qa@example.invalid", password: "Runtime-Fake-Password-9x!" };
  for (const [name, answer, code, expectedCalls] of [
    ["503 credential unavailable (rotated/disabled/private/missing)", { status: 503, body: { code: "LOGIN_ACCEPTANCE_CREDENTIAL_UNAVAILABLE" } }, "login-acceptance-input_credential_unavailable", 1],
    ["200 without credential object", { status: 200, body: INPUT }, "login-acceptance-input_reply_invalid", 1],
    ["200 with missing email", { status: 200, body: { ...INPUT, credential: { password: CRED.password } } }, "login-acceptance-input_reply_invalid", 1],
    ["200 with empty password", { status: 200, body: { ...INPUT, credential: { email: CRED.email, password: "" } } }, "login-acceptance-input_reply_invalid", 1],
    ["200 with newline in password", { status: 200, body: { ...INPUT, credential: { email: CRED.email, password: "a\nb" } } }, "login-acceptance-input_reply_invalid", 1],
    ["200 with extra credential field", { status: 200, body: { ...INPUT, credential: { ...CRED, extra: "1" } } }, "login-acceptance-input_reply_invalid", 1],
    ["409 attempt mismatch", { status: 409, body: { code: "LOGIN_ACCEPTANCE_ATTEMPT_MISMATCH" } }, "login-acceptance-input_http_409", 1],
  ]) {
    const testbed = await fakeTestbed(() => answer);
    const j = job(testbed.origin);
    try {
      fs.mkdirSync(path.dirname(j.file), { recursive: true });
      fs.writeFileSync(j.file, JSON.stringify(INPUT), { mode: 0o600 });
      fs.mkdirSync(path.dirname(j.credFile), { recursive: true });
      fs.writeFileSync(j.credFile, `${CRED.email}\n${CRED.password}\n`, { mode: 0o600 });
      await assert.rejects(fetchLoginAcceptanceInput({ env: j.env, retryDelaysMs: [0, 0], includeCredential: true }), (e) => e.message === code, name);
      assert.equal(fs.existsSync(j.file), false, `${name}: stale input removed`);
      assert.equal(fs.existsSync(j.credFile), false, `${name}: stale credential removed`);
      assert.equal(testbed.requests.filter((r) => r.kind === "testbed").length, expectedCalls, name);
    } finally {
      j.close();
      await testbed.close();
    }
  }
  assert.equal(acceptedCredential({ email: "not-an-email", password: "pw" }), null);
  assert.equal(acceptedInputWithCredential({ ...INPUT, credential: { email: "a@b.c", password: "pw" } }, { STAMP_RUN_CASE_ID: RUN_CASE })?.credential.email, "a@b.c");
});

test("fetchLoginAcceptanceCredential fetches runtime credentials in memory without writing any files", async () => {
  const CRED = { email: "mem.qa@example.invalid", password: "Mem-Only-Password-7k!" };
  const testbed = await fakeTestbed(() => ({ status: 200, body: { ...INPUT, credential: CRED } }));
  const j = job(testbed.origin);
  try {
    const cred = await fetchLoginAcceptanceCredential({ env: j.env, retryDelaysMs: [0, 0] });
    assert.deepEqual(cred, CRED);
    assert.equal(fs.existsSync(j.file), false, "no input file written");
    assert.equal(fs.existsSync(j.credFile), false, "no credential file written");
  } finally {
    j.close();
    await testbed.close();
  }
});

test("3xx redirects on OIDC token or login-acceptance-input fail immediately without following or writing files", async () => {
  const CRED = { email: "runtime.qa@example.invalid", password: "Runtime-Fake-Password-9x!" };
  for (const [name, mode, expectedCode] of [
    ["302 on OIDC token endpoint", "oidc-redirect", "oidc_redirect_refused"],
    ["302 on login-acceptance-input endpoint", "input-redirect", "login-acceptance-input_http_302"],
    ["307 on login-acceptance-input endpoint", "input-redirect-307", "login-acceptance-input_http_307"],
  ]) {
    const hits = [];
    const server = http.createServer((req, res) => {
      let text = "";
      req.on("data", (c) => { text += c; });
      req.on("end", () => {
        const u = new URL(req.url, "http://127.0.0.1");
        hits.push(u.pathname);
        if (u.pathname === "/redirected") {
          res.writeHead(200, { "content-type": "application/json" });
          res.end(JSON.stringify({ value: "should-never-be-followed", ...INPUT, credential: CRED }));
          return;
        }
        if (u.pathname === "/oidc") {
          if (mode === "oidc-redirect") {
            res.writeHead(302, { location: `${origin}/redirected` });
            res.end();
            return;
          }
          res.writeHead(200, { "content-type": "application/json" });
          res.end(JSON.stringify({ value: `fake-oidc-token-for-${u.searchParams.get("audience")}` }));
          return;
        }
        res.writeHead(mode === "input-redirect-307" ? 307 : 302, { location: `${origin}/redirected` });
        res.end();
      });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    const j = job(origin);
    try {
      fs.mkdirSync(path.dirname(j.file), { recursive: true });
      fs.writeFileSync(j.file, JSON.stringify(INPUT), { mode: 0o600 });
      fs.mkdirSync(path.dirname(j.credFile), { recursive: true });
      fs.writeFileSync(j.credFile, `${CRED.email}\n${CRED.password}\n`, { mode: 0o600 });
      await assert.rejects(
        fetchLoginAcceptanceInput({ env: j.env, retryDelaysMs: [0, 0], includeCredential: true }),
        (e) => e.message === expectedCode,
        name,
      );
      assert.equal(hits.includes("/redirected"), false, `${name}: redirect target must never be requested`);
      assert.deepEqual(
        hits,
        mode === "oidc-redirect" ? ["/oidc"] : ["/oidc", ENDPOINT],
        `${name}: stopped on first 3xx without retries`,
      );
      assert.equal(fs.existsSync(j.file), false, `${name}: no input file left behind`);
      assert.equal(fs.existsSync(j.credFile), false, `${name}: no credential file left behind`);
    } finally {
      j.close();
      await new Promise((resolve) => server.close(resolve));
    }
  }
});
