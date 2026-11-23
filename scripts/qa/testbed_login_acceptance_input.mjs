#!/usr/bin/env node
// The login acceptance input (#216) of the claimed Testbed case login-email-password, fetched by the
// protected qa-acceptance.yml step "Fetch the login acceptance input from Testbed" (after the
// ordinary claim, stamp_report.sh claim; before the emulator step's login phase):
//
//   POST <Testbed origin>/api/agent/run-cases/<run_case_id>/login-acceptance-input   {"id_token":"<OIDC>"}
//
// through testbed_release_settings.mjs's client, so it is sent like the job's other Testbed calls: the
// executor bearer (STAMP_TOKEN), X-Testbed-CI-Job-Ref (this run's URL), X-Testbed-Claim-Token (the
// claim credential stamp_report.sh claim wrote), and a fresh GitHub OIDC token whose audience is this
// endpoint's full URL (botiverse/testbed worker/src/login-acceptance.ts, routes/agent.ts). Testbed
// answers HTTP 200 with exactly { case_id, run_case_id, attempt, attempt_id, nonce, expected_target }
// only for this protected job's attempt (anything else, including before a test target is configured,
// is another status); a repeat from the same job gets the same input.
//
// Only that answer is written, to $RUNNER_TEMP/testbed-login-acceptance/input.json: a runner-owned 0700
// directory, a new 0600 file renamed into place. Any other status, a transport failure or any other
// body writes nothing (and an older file is removed first), so login_phase.mjs --require-acceptance
// ends unknown (input_missing), never a pass. Nothing received or sent is printed: one status line.
//
// Usage (env: STAMP_RUN_CASE_ID, STAMP_URL, STAMP_TOKEN, RUNNER_TEMP, ACTIONS_ID_TOKEN_REQUEST_URL/_TOKEN):
//   node scripts/qa/testbed_login_acceptance_input.mjs      (exit 0 only when the input was written)
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { LOGIN_ACCEPTANCE_CASE, testbedInputProblem } from "./login_phase.mjs";
import { TestbedReleaseError, claimTokenFile, createTestbedReleaseClient } from "./testbed_release_settings.mjs";

export const inputPath = (env = process.env) => path.join(env.RUNNER_TEMP || ".", "testbed-login-acceptance", "input.json");

const FIELDS = ["attempt", "attempt_id", "case_id", "expected_target", "nonce", "run_case_id"];
const TARGET_FIELDS = ["server_id", "server_slug", "user_id"];
const exactly = (value, keys) => Boolean(value) && typeof value === "object" && !Array.isArray(value) &&
  Object.keys(value).sort().join(",") === keys.join(",");

/** Testbed's answer counts only as exactly the six-field input of this run case's login-email-password attempt. */
export function acceptedInput(body, env = process.env) {
  if (!exactly(body, FIELDS) || !exactly(body.expected_target, TARGET_FIELDS)) return null;
  if (body.case_id !== LOGIN_ACCEPTANCE_CASE || !env.STAMP_RUN_CASE_ID || body.run_case_id !== env.STAMP_RUN_CASE_ID) return null;
  return testbedInputProblem(body, env) ? null : body;
}

/** A runner-owned 0700 directory and a new 0600 file, renamed into place (no reader sees it half written). */
export function writeInput(file, input) {
  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const stat = fs.lstatSync(dir);
  if (!stat.isDirectory() || (typeof process.getuid === "function" && stat.uid !== process.getuid())) {
    throw new TestbedReleaseError("input_directory_not_owned");
  }
  fs.chmodSync(dir, 0o700);
  const partial = path.join(dir, `.input-${process.pid}.partial`);
  fs.rmSync(partial, { force: true });
  try {
    fs.writeFileSync(partial, `${JSON.stringify(input)}\n`, { mode: 0o600, flag: "wx" });
    fs.chmodSync(partial, 0o600);
    fs.renameSync(partial, file);
  } finally {
    fs.rmSync(partial, { force: true });
  }
}

export async function fetchLoginAcceptanceInput({ env = process.env, fetchImpl = fetch, retryDelaysMs } = {}) {
  const file = inputPath(env);
  // A failed fetch must not leave an older input behind for the login phase.
  fs.rmSync(file, { force: true });
  let claimToken = "";
  try {
    claimToken = fs.readFileSync(claimTokenFile(env, env.STAMP_RUN_CASE_ID), "utf8").trim();
  } catch { /* No claim of this run case: refused below. */ }
  if (!claimToken) throw new TestbedReleaseError("claim_token_missing");
  const client = createTestbedReleaseClient({ env, fetchImpl, claimToken, ...(retryDelaysMs ? { retryDelaysMs } : {}) });
  const input = acceptedInput(await client.loginAcceptanceInput(), env);
  if (!input) throw new TestbedReleaseError("login-acceptance-input_reply_invalid");
  writeInput(file, input);
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  try {
    await fetchLoginAcceptanceInput();
    console.log("testbed login acceptance: input received");
  } catch (error) {
    // A fixed code only: never a reply, a token or an id.
    const code = error instanceof TestbedReleaseError ? error.message : "unexpected_error";
    console.error(`::error title=login acceptance input::not received (${code}); the login phase ends unknown (input_missing)`);
    process.exitCode = 1;
  }
}
