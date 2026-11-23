#!/usr/bin/env node
// Testbed's release settings lane (docs/android-smoke.md "Pair the release settings job with the
// confirmed QA target", Testbed #316/#318) for the bound qa-acceptance.yml job
// "Acceptance run (M3 agent on emulator)". Every request carries the GitHub Actions executor
// bearer (STAMP_TOKEN) and a fresh GitHub OIDC token whose audience is the Testbed origin + the
// exact endpoint path, so each token is good for one endpoint only:
//
//   bind       POST .../release-settings-job          { id_token }                    -> { state: "bound" }
//   claim      POST .../release-settings-claim        { id_token, ci_job_ref, apk_sha256, package_id, version_code }
//                                                      -> { claim_token, login_mode: "release-prelogin" }
//   challenge  POST .../release-settings-observation  { id_token, action: "challenge" } -> { attempt_id, nonce, pairing_salt }
//   observe    POST .../release-settings-observation  { id_token, action: "observe", request_id, receipt, os } -> { state: "recorded" }
//   decide     POST .../release-settings-decision     { id_token, attempt_id, request_id } -> { state, attempt_id, request_id }
//
// The same client fetches the login acceptance input (#216) for the claimed case login-email-password
// (testbed_login_acceptance_input.mjs) and reports its result (testbed_login_acceptance_result.mjs), with
// the claim credential of stamp_report.sh claim:
//   input      POST .../login-acceptance-input        { id_token, include_credential? } -> HTTP 200 only: the six-field input,
//                                                      plus credential: { email, password } when include_credential: true
//                                                      (botiverse/testbed worker/src/login-acceptance.ts; 503
//                                                      LOGIN_ACCEPTANCE_CREDENTIAL_UNAVAILABLE when the pool row is unavailable)
//   result     POST .../login-acceptance-result       { id_token, verdict, reason }   -> HTTP 200 only (also for an identical
//                                                      resend); 409: Testbed already holds another result
//
// (all under /api/agent/run-cases/{run_case_id}/; after the claim also X-Testbed-CI-Job-Ref and
// X-Testbed-Claim-Token). Any refusal, missing field or transport failure is a stop: nothing here
// ever turns into a credential, an open capture gate or a pass. Replies are private: never logged.
import fs from "node:fs";
import path from "node:path";

const RUN_CASE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ATTEMPT_ID = /^pla_[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const REQUEST_ID = /^[0-9a-f]{32}$/;
// A login acceptance verdict and the shape of its reason code (lowercase words joined by "_", at most 40
// characters): nothing else, no id or 64-hex ref, can travel in a result report.
const ACCEPTANCE_VERDICTS = ["pass", "fail", "unknown"];
const REASON_CODE = /^(?=.{1,40}$)[a-z]+(?:_[a-z]+)*$/;
export const RELEASE_LOGIN_MODE = "release-prelogin";
export const ENDPOINTS = ["release-settings-job", "release-settings-claim", "release-settings-observation", "release-settings-decision",
  "login-acceptance-input", "login-acceptance-result"];

export class TestbedReleaseError extends Error {}
const fail = (code) => { throw new TestbedReleaseError(code); };

/** The OIDC audience Testbed checks: its origin + the exact endpoint path. */
export function releaseSettingsAudience(testbedUrl, runCaseId, endpoint) {
  if (!ENDPOINTS.includes(endpoint)) fail("unknown_endpoint");
  if (!RUN_CASE.test(String(runCaseId ?? ""))) fail("invalid_run_case_id");
  return `${new URL(testbedUrl).origin}/api/agent/run-cases/${runCaseId}/${endpoint}`;
}

/** A GitHub OIDC token for one audience (needs `permissions: id-token: write` on the job). Never follows redirects. */
export async function requestOidcToken({ env = process.env, fetchImpl = fetch, audience }) {
  const url = env.ACTIONS_ID_TOKEN_REQUEST_URL;
  const bearer = env.ACTIONS_ID_TOKEN_REQUEST_TOKEN;
  if (!url || !bearer) fail("oidc_unavailable");
  const target = new URL(url);
  target.searchParams.set("audience", audience);
  const response = await fetchImpl(target, {
    headers: { Authorization: `Bearer ${bearer}` },
    redirect: "manual",
    signal: AbortSignal.timeout(20_000),
  });
  if (response.status >= 300 && response.status < 400) {
    await response.body?.cancel().catch(() => {});
    fail("oidc_redirect_refused");
  }
  const json = response.ok ? await response.json().catch(() => null) : null;
  if (typeof json?.value !== "string" || !json.value) fail("oidc_unavailable");
  return json.value;
}

/** The claim reply counts only with a claim credential and the explicit release pre-login mode. */
export function parseClaimReply(reply, runCaseId) {
  if (!reply || typeof reply !== "object") fail("claim_reply_missing");
  if (typeof reply.claim_token !== "string" || !reply.claim_token || reply.claim_token.length > 512) fail("claim_token_missing");
  if (reply.login_mode !== RELEASE_LOGIN_MODE) fail("login_mode_not_release_prelogin");
  if (reply.run_case_id !== undefined && reply.run_case_id !== runCaseId) fail("claim_for_another_run_case");
  return { claimToken: reply.claim_token, loginMode: reply.login_mode };
}

/** The challenge: Testbed's attempt id, fresh nonce and a distinct pairing salt. */
export function parseChallengeReply(reply) {
  if (!reply || typeof reply !== "object" || !ATTEMPT_ID.test(reply.attempt_id ?? "") || !HEX64.test(reply.nonce ?? "") ||
      !HEX64.test(reply.pairing_salt ?? "") || reply.nonce === reply.pairing_salt) fail("challenge_invalid");
  return { attempt_id: reply.attempt_id, nonce: reply.nonce, pairing_salt: reply.pairing_salt };
}

/** Claim body: the same build facts stamp_report.sh claims with (observed by the frozen identity step). */
export function claimBody({ ciJobRef, identity }) {
  const versionCode = /^[1-9][0-9]*$/.test(String(identity?.versionCode ?? "")) ? Number(identity.versionCode) : null;
  if (!ciJobRef || !HEX64.test(identity?.sha256 ?? "") || !identity?.packageId || !versionCode) fail("claim_inputs_missing");
  return { ci_job_ref: ciJobRef, apk_sha256: identity.sha256, package_id: identity.packageId, version_code: versionCode };
}

export function createTestbedReleaseClient({ env = process.env, fetchImpl = fetch, claimToken = "", retryDelaysMs = [1000, 3000] } = {}) {
  const runCaseId = env.STAMP_RUN_CASE_ID;
  const testbedUrl = env.STAMP_URL;
  const executorToken = env.STAMP_TOKEN;
  if (!RUN_CASE.test(String(runCaseId ?? "")) || !testbedUrl || !executorToken) fail("testbed_not_configured");
  const jobRef = `${env.GITHUB_SERVER_URL || "https://github.com"}/${env.GITHUB_REPOSITORY || ""}/actions/runs/${env.GITHUB_RUN_ID || ""}`;
  // `retry`: the same request (same job signature, same body) again after a transport failure,
  // a timeout, a 5xx or an unreadable reply, a bounded number of times. Testbed answers a repeated
  // dedicated claim from the same job with the original result, so a claim whose reply was lost
  // is recovered rather than abandoned. A 4xx is a refusal: never retried. `status`: the only
  // success status the endpoint has (any other 2xx is a failure too). `statusOnly`: the status is the
  // whole answer and the body is never read; only `status` succeeds, only a transport failure, a
  // timeout or a 5xx is retried, and any other status (a redirect too: never followed) stops at once,
  // as `refusals[status]` where the endpoint names it.
  const post = async (endpoint, body, { claimed = true, retry = false, status = null, statusOnly = false, refusals = {} } = {}) => {
    const audience = releaseSettingsAudience(testbedUrl, runCaseId, endpoint);
    const idToken = await requestOidcToken({ env, fetchImpl, audience });
    if (claimed && !claimToken) fail("claim_token_missing");
    const request = {
      method: "POST",
      headers: {
        Authorization: `Bearer ${executorToken}`,
        "Content-Type": "application/json",
        "X-Testbed-CI-Job-Ref": jobRef,
        ...(claimed ? { "X-Testbed-Claim-Token": claimToken } : {}),
      },
      body: JSON.stringify({ id_token: idToken, ...body }),
      redirect: "manual",
    };
    const delays = retry ? retryDelaysMs : [];
    let lastFailure = `${endpoint}_no_reply`;
    for (let attempt = 0; attempt <= delays.length; attempt += 1) {
      if (attempt > 0) await new Promise((resolve) => setTimeout(resolve, delays[attempt - 1]));
      let response;
      try {
        response = await fetchImpl(audience, { ...request, signal: AbortSignal.timeout(30_000) });
      } catch {
        lastFailure = `${endpoint}_no_reply`;
        continue;
      }
      if (statusOnly) {
        await response.body?.cancel().catch(() => {});
        if (response.status === status) return null;
        if (response.status < 500) fail(refusals[response.status] ?? `${endpoint}_http_${response.status}`);
        lastFailure = `${endpoint}_http_${response.status}`;
        continue;
      }
      if (response.status >= 300 && response.status < 400) {
        await response.body?.cancel().catch(() => {});
        fail(`${endpoint}_http_${response.status}`);
      }
      if (response.status >= 400 && response.status < 500) fail(`${endpoint}_http_${response.status}`);
      if (!response.ok || (status !== null && response.status !== status)) {
        if (response.status === 503 && endpoint === "login-acceptance-input") {
          const errBody = await response.json().catch(() => null);
          if (errBody?.code === "LOGIN_ACCEPTANCE_CREDENTIAL_UNAVAILABLE") fail("login-acceptance-input_credential_unavailable");
        }
        lastFailure = `${endpoint}_http_${response.status}`;
        continue;
      }
      const json = await response.json().catch(() => undefined);
      if (json === undefined) {
        lastFailure = `${endpoint}_reply_unreadable`;
        continue;
      }
      return json;
    }
    fail(lastFailure);
  };
  return {
    jobRef,
    runCaseId,
    async bind() {
      const reply = await post("release-settings-job", {}, { claimed: false });
      if (reply?.state !== "bound" || reply.run_case_id !== runCaseId) fail("bind_not_confirmed");
      return reply;
    },
    async claim(identity) {
      return parseClaimReply(await post("release-settings-claim", claimBody({ ciJobRef: jobRef, identity }), { claimed: false, retry: true }), runCaseId);
    },
    async challenge() {
      return parseChallengeReply(await post("release-settings-observation", { action: "challenge" }));
    },
    /** The complete receipt + OS facts, privately. Returns the reply only when it is this request's. */
    async observe({ request_id, receipt, os }) {
      const reply = await post("release-settings-observation", { action: "observe", request_id, receipt, os });
      return reply?.state === "recorded" && reply.attempt_id === receipt.attempt_id && reply.request_id === request_id ? reply : null;
    },
    async decide(request) {
      if (!ATTEMPT_ID.test(request.attemptId) || !REQUEST_ID.test(request.requestId)) fail("decision_request_invalid");
      return post("release-settings-decision", { attempt_id: request.attemptId, request_id: request.requestId });
    },
    /**
     * The login acceptance input of this attempt (#216): HTTP 200 only (Testbed delivers nothing
     * before a test target is configured); the caller checks the body. When `includeCredential` is
     * true, sends `{ id_token, include_credential: true }` so Testbed also delivers the registered
     * pool credential for this attempt (or 503 LOGIN_ACCEPTANCE_CREDENTIAL_UNAVAILABLE). Testbed
     * answers a repeat from the same job with the same input, so a lost reply is retried like the claim.
     */
    async loginAcceptanceInput({ includeCredential = false } = {}) {
      return post("login-acceptance-input", includeCredential ? { include_credential: true } : {}, { retry: true, status: 200 });
    },
    /**
     * The login acceptance result of this attempt (#216): the body is exactly { id_token, verdict, reason }
     * (Testbed derives the attempt from the claim and the approved GitHub job). Success is HTTP 200 only,
     * which Testbed also answers to an identical resend, so a lost reply or a 5xx is retried like the
     * claim. 409 means Testbed already holds a different result: login-acceptance-result_conflict, never
     * retried. Any other status stops at once.
     */
    async loginAcceptanceResult({ verdict, reason }) {
      if (!ACCEPTANCE_VERDICTS.includes(verdict) || typeof reason !== "string" || !REASON_CODE.test(reason)) fail("login-acceptance-result_request_invalid");
      await post("login-acceptance-result", { verdict, reason }, {
        retry: true, status: 200, statusOnly: true, refusals: { 409: "login-acceptance-result_conflict" },
      });
    },
  };
}

/** Private files (0600, fresh). */
export function writePrivateFile(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text, { mode: 0o600, flag: "wx" });
  fs.chmodSync(file, 0o600);
}

export const claimTokenFile = (env, runCaseId) => path.join(env.RUNNER_TEMP || ".", `stamp-claim-${runCaseId}`);
export const challengeFile = (env, runCaseId) => path.join(env.RUNNER_TEMP || ".", `testbed-release-challenge-${runCaseId}.json`);

// CLI for the workflow steps. Output is a fixed status line; a failure prints only its code.
//   bind
//   claim --identity apks/observed-identity.json   (writes the claim token + a no-bootstrap spec)
//   challenge                                       (writes the private challenge file)
async function main(argv) {
  const [command] = argv;
  const option = (name) => { const i = argv.indexOf(`--${name}`); return i >= 0 ? argv[i + 1] : ""; };
  const env = process.env;
  if (command === "bind") {
    await createTestbedReleaseClient({ env }).bind();
    console.log("testbed release settings: job bound");
    return;
  }
  if (command === "claim") {
    const identity = JSON.parse(fs.readFileSync(option("identity"), "utf8"));
    const client = createTestbedReleaseClient({ env });
    const { claimToken } = await client.claim(identity);
    writePrivateFile(claimTokenFile(env, client.runCaseId), claimToken);
    // stamp_report.sh's later phases read the same claim file and this spec (no auth bootstrap).
    writePrivateFile(path.join(env.RUNNER_TEMP || ".", `stamp-bootstrap-spec-${client.runCaseId}.json`), `${JSON.stringify({ required: false, run_case_id: client.runCaseId })}\n`);
    console.log(`testbed release settings: claimed (login_mode=${RELEASE_LOGIN_MODE})`);
    return;
  }
  if (command === "challenge") {
    const runCaseId = env.STAMP_RUN_CASE_ID;
    const claimToken = fs.readFileSync(claimTokenFile(env, runCaseId), "utf8").trim();
    const challenge = await createTestbedReleaseClient({ env, claimToken }).challenge();
    writePrivateFile(challengeFile(env, runCaseId), `${JSON.stringify(challenge)}\n`);
    console.log("testbed release settings: challenge received");
    return;
  }
  console.error("usage: testbed_release_settings.mjs bind | claim --identity <observed-identity.json> | challenge");
  process.exitCode = 2;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main(process.argv.slice(2)).catch((error) => {
    const code = error instanceof TestbedReleaseError ? error.message : "unexpected_error";
    console.error(`::error title=testbed release settings::${process.argv[2] ?? "?"} stopped: ${code}`);
    process.exitCode = 1;
  });
}
