#!/usr/bin/env node
// The login acceptance input (#216) of the claimed Testbed case login-email-password, fetched by the
// protected qa-acceptance.yml step "Fetch the login acceptance input from Testbed" (after the
// ordinary claim, stamp_report.sh claim; before preflight and the emulator step's login phase):
//
//   POST <Testbed origin>/api/agent/run-cases/<run_case_id>/login-acceptance-input
//        {"id_token":"<OIDC>"}                                   (target input only)
//        {"id_token":"<OIDC>","include_credential":true}         (target input + runtime credential)
//
// through testbed_release_settings.mjs's client, so it is sent like the job's other Testbed calls: the
// executor bearer (STAMP_TOKEN), X-Testbed-CI-Job-Ref (this run's URL), X-Testbed-Claim-Token (the
// claim credential stamp_report.sh claim wrote), and a fresh GitHub OIDC token whose audience is this
// endpoint's full URL (botiverse/testbed worker/src/login-acceptance.ts, routes/agent.ts). Testbed
// answers HTTP 200 (Cache-Control: no-store) with exactly { case_id, run_case_id, attempt, attempt_id,
// nonce, expected_target } — plus credential: { email, password } when include_credential: true — only
// for this protected job's attempt (anything else, including before a test target is configured or a
// rotated/disabled/private pool account returning 503 LOGIN_ACCEPTANCE_CREDENTIAL_UNAVAILABLE, is
// another status); a repeat from the same job gets the same input.
//
// Only the six target input fields are written to $RUNNER_TEMP/testbed-login-acceptance/input.json;
// when --include-credential is passed, credential: { email, password } is written separately as
// "email\npassword\n" to $RUNNER_TEMP/login-acceptance-credential/account (each in a runner-owned 0700
// directory, as a new 0600 file renamed into place) so preflight and the device login consume the same
// file without keeping credentials in input.json or repository secrets. Any other status, a transport
// failure, or any missing/malformed field writes nothing (and older files are removed first), so
// preflight and login_phase.mjs --require-acceptance stop fail-closed. Nothing received or sent is
// printed: one status line.
//
// Usage (env: STAMP_RUN_CASE_ID, STAMP_URL, STAMP_TOKEN, RUNNER_TEMP, ACTIONS_ID_TOKEN_REQUEST_URL/_TOKEN):
//   node scripts/qa/testbed_login_acceptance_input.mjs [--include-credential]
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { LOGIN_ACCEPTANCE_CASE, testbedInputProblem } from "./login_phase.mjs";
import { TestbedReleaseError, claimTokenFile, createTestbedReleaseClient } from "./testbed_release_settings.mjs";

export const inputPath = (env = process.env) => path.join(env.RUNNER_TEMP || ".", "testbed-login-acceptance", "input.json");
export const credentialPath = (env = process.env) => path.join(env.RUNNER_TEMP || ".", "login-acceptance-credential", "account");

const FIELDS = ["attempt", "attempt_id", "case_id", "expected_target", "nonce", "run_case_id"];
const FIELDS_WITH_CREDENTIAL = ["attempt", "attempt_id", "case_id", "credential", "expected_target", "nonce", "run_case_id"];
const TARGET_FIELDS = ["server_id", "server_slug", "user_id"];
const CREDENTIAL_FIELDS = ["email", "password"];
const exactly = (value, keys) => Boolean(value) && typeof value === "object" && !Array.isArray(value) &&
  Object.keys(value).sort().join(",") === keys.join(",");

/** Testbed's answer counts only as exactly the six-field input of this run case's login-email-password attempt. */
export function acceptedInput(body, env = process.env) {
  if (!exactly(body, FIELDS) || !exactly(body.expected_target, TARGET_FIELDS)) return null;
  if (body.case_id !== LOGIN_ACCEPTANCE_CASE || !env.STAMP_RUN_CASE_ID || body.run_case_id !== env.STAMP_RUN_CASE_ID) return null;
  return testbedInputProblem(body, env) ? null : body;
}

/** The credential block: exactly { email, password }, both non-empty single-line strings. */
export function acceptedCredential(value) {
  if (!exactly(value, CREDENTIAL_FIELDS)) return null;
  const { email, password } = value;
  if (typeof email !== "string" || typeof password !== "string") return null;
  if (!email || !password || email !== email.trim() || !email.includes("@")) return null;
  if (/[\r\n\0]/.test(email) || /[\r\n\0]/.test(password)) return null;
  return { email, password };
}

/** Testbed's include_credential: true answer: the exact six target fields plus valid credential: { email, password }. */
export function acceptedInputWithCredential(body, env = process.env) {
  if (!exactly(body, FIELDS_WITH_CREDENTIAL)) return null;
  const { credential, ...targetInput } = body;
  const input = acceptedInput(targetInput, env);
  const parsedCredential = acceptedCredential(credential);
  if (!input || !parsedCredential) return null;
  return { input, credential: parsedCredential };
}

/** A runner-owned 0700 directory and a new 0600 file, renamed into place (no reader sees it half written). */
function writePrivateTargetFile(file, content, prefix, errorCode) {
  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const stat = fs.lstatSync(dir);
  if (!stat.isDirectory() || (typeof process.getuid === "function" && stat.uid !== process.getuid())) {
    throw new TestbedReleaseError(errorCode);
  }
  fs.chmodSync(dir, 0o700);
  const partial = path.join(dir, `.${prefix}-${process.pid}.partial`);
  fs.rmSync(partial, { force: true });
  try {
    fs.writeFileSync(partial, content, { mode: 0o600, flag: "wx" });
    fs.chmodSync(partial, 0o600);
    fs.renameSync(partial, file);
  } finally {
    fs.rmSync(partial, { force: true });
  }
}

export function writeInput(file, input) {
  writePrivateTargetFile(file, `${JSON.stringify(input)}\n`, "input", "input_directory_not_owned");
}

export function writeCredentialFile(file, credential) {
  writePrivateTargetFile(file, `${credential.email}\n${credential.password}\n`, "credential", "credential_directory_not_owned");
}

function readClaimToken(env) {
  let claimToken = "";
  try {
    claimToken = fs.readFileSync(claimTokenFile(env, env.STAMP_RUN_CASE_ID), "utf8").trim();
  } catch { /* No claim of this run case: refused below. */ }
  if (!claimToken) throw new TestbedReleaseError("claim_token_missing");
  return claimToken;
}

/** In-memory runtime credential fetch for secure_evidence.mjs (never writes to disk). */
export async function fetchLoginAcceptanceCredential({ env = process.env, fetchImpl = fetch, retryDelaysMs } = {}) {
  const claimToken = readClaimToken(env);
  const client = createTestbedReleaseClient({ env, fetchImpl, claimToken, ...(retryDelaysMs ? { retryDelaysMs } : {}) });
  const parsed = acceptedInputWithCredential(await client.loginAcceptanceInput({ includeCredential: true }), env);
  if (!parsed) throw new TestbedReleaseError("login-acceptance-input_reply_invalid");
  return parsed.credential;
}

export async function fetchLoginAcceptanceInput({ env = process.env, fetchImpl = fetch, retryDelaysMs, includeCredential = false } = {}) {
  const file = inputPath(env);
  const credFile = credentialPath(env);
  // A failed fetch must not leave older files behind for preflight or the login phase.
  fs.rmSync(file, { force: true });
  if (includeCredential) fs.rmSync(credFile, { force: true });
  const claimToken = readClaimToken(env);
  const client = createTestbedReleaseClient({ env, fetchImpl, claimToken, ...(retryDelaysMs ? { retryDelaysMs } : {}) });
  const reply = await client.loginAcceptanceInput({ includeCredential });
  if (includeCredential) {
    const parsed = acceptedInputWithCredential(reply, env);
    if (!parsed) throw new TestbedReleaseError("login-acceptance-input_reply_invalid");
    writeInput(file, parsed.input);
    try {
      writeCredentialFile(credFile, parsed.credential);
    } catch (error) {
      fs.rmSync(file, { force: true });
      fs.rmSync(credFile, { force: true });
      throw error;
    }
    return;
  }
  const input = acceptedInput(reply, env);
  if (!input) throw new TestbedReleaseError("login-acceptance-input_reply_invalid");
  writeInput(file, input);
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const args = process.argv.slice(2);
  const includeCredential = args.length === 1 && args[0] === "--include-credential";
  if (args.length > 0 && !includeCredential) {
    console.error("usage: testbed_login_acceptance_input.mjs [--include-credential]");
    process.exitCode = 2;
  } else {
    try {
      await fetchLoginAcceptanceInput({ includeCredential });
      console.log(includeCredential ? "testbed login acceptance: input and credential received" : "testbed login acceptance: input received");
    } catch (error) {
      // A fixed code only: never a reply, a token, a credential or an id.
      const code = error instanceof TestbedReleaseError ? error.message : "unexpected_error";
      console.error(`::error title=login acceptance input::not received (${code}); the login phase ends unknown (input_missing)`);
      process.exitCode = 1;
    }
  }
}
