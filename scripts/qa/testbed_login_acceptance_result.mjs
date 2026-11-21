#!/usr/bin/env node
// The login acceptance result (#216) of the claimed Testbed case login-email-password, reported by the
// protected qa-acceptance.yml step "Report the login acceptance result to Testbed" (after the emulator
// step, whose login phase wrote the verdict; before "Report verdict" and "Report pre-claim infrastructure
// failure", which end the claim):
//
//   POST <Testbed origin>/api/agent/run-cases/<run_case_id>/login-acceptance-result
//        {"id_token":"<OIDC>","verdict":"pass|fail|unknown","reason":"<code>"}
//
// through testbed_release_settings.mjs's client, authenticated like the input fetch
// (testbed_login_acceptance_input.mjs): the executor bearer (STAMP_TOKEN), X-Testbed-CI-Job-Ref (this run's
// URL), X-Testbed-Claim-Token (the claim credential stamp_report.sh claim wrote) and a fresh GitHub OIDC token
// whose audience is this endpoint's full URL. Nothing else is sent: Testbed derives the attempt from the
// claim and the approved GitHub job.
//
// The verdict and reason come from this run's login record (qa-evidence/login-record.json, read through
// login_phase.mjs readRecordAcceptance), sent as recorded only when the record is bound to this job: its
// acceptance case_id is login-email-password, its run_case_id is STAMP_RUN_CASE_ID, its attempt 1 (Testbed
// delivers the input only to the first attempt), the record's run is this GitHub run (GITHUB_RUN_ID:GITHUB_RUN_ATTEMPT), its verdict/reason pair is
// one login_phase.mjs can record (LOGIN_ACCEPTANCE_REASONS, isLoginAcceptancePair), and a pass is on a record
// readRecord accepts as a completed password login. Otherwise the verdict is unknown, with one of these reason
// codes (RESULT_REASONS):
//   record_missing      no record, unreadable, not a qa-login-record/v1 object, or no acceptance object
//   record_invalidated  bound to another case, run case or GitHub run, an attempt other than 1, or a pass on a
//                       record readRecord does not accept as a completed login
//   reason_unlisted     a verdict/reason pair login_phase.mjs cannot produce
//
// Testbed answers 200 to the first write and to an identical resend; 409 when it already holds a different
// result (never retried); a lost reply or a 5xx is tried three times in all; any other status stops at once.
// One fixed status line: never the token, an id, a ref, the record or Testbed's reply.
//
// Usage (env: STAMP_RUN_CASE_ID, STAMP_URL, STAMP_TOKEN, RUNNER_TEMP, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT,
// ACTIONS_ID_TOKEN_REQUEST_URL/_TOKEN; run where qa-evidence is):
//   node scripts/qa/testbed_login_acceptance_result.mjs      (exit 0 only when Testbed answered 200)
import fs from "node:fs";
import { pathToFileURL } from "node:url";
import { LOGIN_ACCEPTANCE_CASE, isLoginAcceptancePair, readRecordAcceptance } from "./login_phase.mjs";
import { TestbedReleaseError, claimTokenFile, createTestbedReleaseClient } from "./testbed_release_settings.mjs";

export const EVIDENCE_DIR = "qa-evidence";
// The unknown reasons this report adds to the record's own (record_invalidated is also markUnknown's).
export const RESULT_REASONS = { missing: "record_missing", invalidated: "record_invalidated", unlisted: "reason_unlisted" };

const POSITIVE = /^[1-9][0-9]*$/;
// Testbed accepts the result of the first attempt only (rc.attempt = 1): any other attempt is not this job's.
const TESTBED_ATTEMPT = 1;

/** The { verdict, reason } to report for the login record in `out`, checked against this job (env). */
export function resultFromRecord(out = EVIDENCE_DIR, env = process.env) {
  const unknown = (reason) => ({ verdict: "unknown", reason });
  const read = readRecordAcceptance(out, env);
  if (!read) return unknown(RESULT_REASONS.missing);
  const { acceptance } = read;
  const thisRun = POSITIVE.test(env.GITHUB_RUN_ID ?? "") && POSITIVE.test(env.GITHUB_RUN_ATTEMPT ?? "") &&
    read.run === `${env.GITHUB_RUN_ID}:${env.GITHUB_RUN_ATTEMPT}`;
  const bound = acceptance.case_id === LOGIN_ACCEPTANCE_CASE && Boolean(env.STAMP_RUN_CASE_ID) && acceptance.run_case_id === env.STAMP_RUN_CASE_ID &&
    acceptance.attempt === TESTBED_ATTEMPT;
  if (!thisRun || !bound) return unknown(RESULT_REASONS.invalidated);
  if (!isLoginAcceptancePair(acceptance.verdict, acceptance.reason)) return unknown(RESULT_REASONS.unlisted);
  if (acceptance.verdict === "pass" && !read.completed) return unknown(RESULT_REASONS.invalidated);
  return { verdict: acceptance.verdict, reason: acceptance.reason };
}

/** Reports this job's result to Testbed; resolves with what was sent only when Testbed answered 200. */
export async function reportLoginAcceptanceResult({ env = process.env, fetchImpl = fetch, retryDelaysMs, out = EVIDENCE_DIR } = {}) {
  const result = resultFromRecord(out, env);
  let claimToken = "";
  try {
    claimToken = fs.readFileSync(claimTokenFile(env, env.STAMP_RUN_CASE_ID), "utf8").trim();
  } catch { /* No claim of this run case: refused below. */ }
  if (!claimToken) throw new TestbedReleaseError("claim_token_missing");
  const client = createTestbedReleaseClient({ env, fetchImpl, claimToken, ...(retryDelaysMs ? { retryDelaysMs } : {}) });
  await client.loginAcceptanceResult(result);
  return result;
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  try {
    await reportLoginAcceptanceResult();
    console.log("testbed login acceptance: result reported");
  } catch (error) {
    // A fixed code only: never a reply, a token, an id or the record.
    const code = error instanceof TestbedReleaseError ? error.message : "unexpected_error";
    console.error(`::error title=login acceptance result::not reported (${code})`);
    process.exitCode = 1;
  }
}
