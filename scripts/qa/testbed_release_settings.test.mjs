import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  RELEASE_LOGIN_MODE,
  TestbedReleaseError,
  claimBody,
  createTestbedReleaseClient,
  parseChallengeReply,
  parseClaimReply,
  releaseSettingsAudience,
  writePrivateFile,
} from "./testbed_release_settings.mjs";

const RUN_CASE = "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d";
const env = {
  STAMP_RUN_CASE_ID: RUN_CASE,
  STAMP_URL: "https://testbed.example.invalid/some/path",
  STAMP_TOKEN: "executor-bearer",
  GITHUB_SERVER_URL: "https://github.com",
  GITHUB_REPOSITORY: "botiverse/mobile",
  GITHUB_RUN_ID: "123",
  ACTIONS_ID_TOKEN_REQUEST_URL: "https://oidc.example.invalid/token?api-version=2.0",
  ACTIONS_ID_TOKEN_REQUEST_TOKEN: "oidc-request-bearer",
};
const identity = { sha256: "a1".repeat(32), packageId: "build.raft.app", versionCode: "12700001" };
const BASE = `https://testbed.example.invalid/api/agent/run-cases/${RUN_CASE}`;

// A fake network: OIDC tokens are "oidc:<audience>", Testbed replies come from `replies`.
function fakeFetch(replies) {
  const calls = [];
  const impl = async (url, init = {}) => {
    const u = new URL(url);
    if (u.host === "oidc.example.invalid") {
      calls.push({ kind: "oidc", audience: u.searchParams.get("audience"), auth: init.headers?.Authorization });
      return new Response(JSON.stringify({ value: `oidc:${u.searchParams.get("audience")}` }), { status: 200 });
    }
    const endpoint = u.pathname.split("/").pop();
    const body = JSON.parse(init.body);
    calls.push({ kind: "testbed", url: String(url), endpoint, method: init.method, headers: init.headers, body });
    const reply = typeof replies[endpoint] === "function" ? replies[endpoint](body) : replies[endpoint];
    return reply instanceof Response ? reply : new Response(JSON.stringify(reply), { status: 200 });
  };
  return { impl, calls };
}

test("each request carries a fresh OIDC token for exactly its endpoint path", () => {
  assert.equal(releaseSettingsAudience(env.STAMP_URL, RUN_CASE, "release-settings-claim"), `${BASE}/release-settings-claim`);
  assert.throws(() => releaseSettingsAudience(env.STAMP_URL, RUN_CASE, "claim"), TestbedReleaseError);
  assert.throws(() => releaseSettingsAudience(env.STAMP_URL, "../x", "release-settings-claim"), TestbedReleaseError);
});

test("claim request: executor bearer, job ref, OIDC for release-settings-claim, the stamp_report build facts", async () => {
  const net = fakeFetch({ "release-settings-claim": { claim_token: "ct-1", login_mode: RELEASE_LOGIN_MODE, run_case_id: RUN_CASE } });
  const client = createTestbedReleaseClient({ env, fetchImpl: net.impl });
  assert.deepEqual(await client.claim(identity), { claimToken: "ct-1", loginMode: "release-prelogin" });
  const [oidc, claim] = net.calls;
  assert.deepEqual(oidc, { kind: "oidc", audience: `${BASE}/release-settings-claim`, auth: "Bearer oidc-request-bearer" });
  assert.equal(claim.method, "POST");
  assert.equal(claim.url, `${BASE}/release-settings-claim`);
  assert.deepEqual(claim.headers, {
    Authorization: "Bearer executor-bearer",
    "Content-Type": "application/json",
    "X-Testbed-CI-Job-Ref": "https://github.com/botiverse/mobile/actions/runs/123",
  });
  assert.deepEqual(claim.body, {
    id_token: `oidc:${BASE}/release-settings-claim`,
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/123",
    apk_sha256: identity.sha256,
    package_id: "build.raft.app",
    version_code: 12700001,
  });
});

test("a claim without the credential, without release-prelogin, refused or unanswered stops", async () => {
  for (const reply of [null, {}, { claim_token: "" }, { claim_token: "ct", login_mode: "executor-login" }, { claim_token: "ct" },
    { claim_token: "ct", login_mode: RELEASE_LOGIN_MODE, run_case_id: "other" }]) {
    assert.throws(() => parseClaimReply(reply, RUN_CASE), TestbedReleaseError, JSON.stringify(reply));
  }
  const refused = fakeFetch({ "release-settings-claim": new Response("{}", { status: 409 }) });
  await assert.rejects(createTestbedReleaseClient({ env, fetchImpl: refused.impl }).claim(identity), /release-settings-claim_http_409/);
  const offline = async () => { throw new TypeError("fetch failed"); };
  await assert.rejects(createTestbedReleaseClient({ env, fetchImpl: offline }).claim(identity));
  // Missing build facts stop before any request.
  assert.throws(() => claimBody({ ciJobRef: "x", identity: { ...identity, versionCode: "" } }), /claim_inputs_missing/);
  // No OIDC permission on the job: stop.
  const noOidc = fakeFetch({});
  await assert.rejects(createTestbedReleaseClient({ env: { ...env, ACTIONS_ID_TOKEN_REQUEST_URL: "" }, fetchImpl: noOidc.impl }).claim(identity), /oidc_unavailable/);
  assert.equal(noOidc.calls.length, 0);
});

test("after the claim: challenge, private observe and decision carry the claim credential", async () => {
  const challenge = { attempt_id: `pla_${"0".repeat(32)}`, nonce: "ab".repeat(32), pairing_salt: "cd".repeat(32) };
  const request = { attemptId: challenge.attempt_id, requestId: "ef".repeat(16) };
  const net = fakeFetch({
    "release-settings-observation": (body) => (body.action === "challenge" ? challenge : { state: "recorded", attempt_id: challenge.attempt_id, request_id: body.request_id }),
    "release-settings-decision": (body) => ({ state: "matched", attempt_id: body.attempt_id, request_id: body.request_id }),
  });
  const client = createTestbedReleaseClient({ env, fetchImpl: net.impl, claimToken: "ct-1" });
  assert.deepEqual(await client.challenge(), challenge);
  const receipt = { attempt_id: challenge.attempt_id, request_id: request.requestId };
  assert.ok(await client.observe({ request_id: request.requestId, receipt, os: { deviceCodename: "REL" } }));
  assert.equal((await client.decide(request)).state, "matched");
  const testbed = net.calls.filter((c) => c.kind === "testbed");
  assert.deepEqual(testbed.map((c) => [c.endpoint, c.body.action ?? null]), [
    ["release-settings-observation", "challenge"], ["release-settings-observation", "observe"], ["release-settings-decision", null],
  ]);
  for (const call of testbed) {
    assert.equal(call.headers["X-Testbed-Claim-Token"], "ct-1");
    assert.equal(call.body.id_token, `oidc:${BASE}/${call.endpoint}`);
  }
  assert.deepEqual(testbed[2].body, { id_token: `oidc:${BASE}/release-settings-decision`, attempt_id: request.attemptId, request_id: request.requestId });
  // An acknowledgement for another request does not count as recorded.
  const other = fakeFetch({ "release-settings-observation": { state: "recorded", attempt_id: challenge.attempt_id, request_id: "00".repeat(16) } });
  assert.equal(await createTestbedReleaseClient({ env, fetchImpl: other.impl, claimToken: "ct-1" }).observe({ request_id: request.requestId, receipt, os: {} }), null);
  // Without the claim credential nothing is sent.
  await assert.rejects(createTestbedReleaseClient({ env, fetchImpl: net.impl }).challenge(), /claim_token_missing/);
  for (const bad of [null, { ...challenge, nonce: "x" }, { ...challenge, pairing_salt: challenge.nonce }, { ...challenge, attempt_id: "pla_1" }]) {
    assert.throws(() => parseChallengeReply(bad), TestbedReleaseError);
  }
});

test("private files are fresh and 0600", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "testbed-release-"));
  const file = path.join(dir, "claim");
  writePrivateFile(file, "ct");
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.throws(() => writePrivateFile(file, "again"), /EEXIST/);
  fs.rmSync(dir, { recursive: true });
});
