// Release settings lane (qa-acceptance.yml login_mode=release-prelogin): the acceptance case runs
// as release_auto_login.mjs's scenario, i.e. after Testbed answered "matched" and inside the very
// app process that gave the matched receipt (release_auto_login.mjs checks the pid + start ticks
// before and after). Cleanup (web sign-out, capture browser and, for an older package whose
// receipt provider this run enabled, its `pm disable`, which kills the app) runs only after this
// returns.
//
// First it writes this run's qa-evidence/login-record.json (login_phase.mjs contract, mode
// "release-prelogin", status "matched"), which the agent and both evidence exits
// (secure_evidence.mjs: the artifact upload and stamp_report.sh) require. It carries no account
// data: the app's exchange line time and the device time at the match (login_end); the evidence
// gate keeps only log lines after login_end. release_settings_terminal.mjs invalidates it when
// the run cannot be vouched for, which withholds everything from both exits. Then
// scripts/qa/release_settings_case.sh starts the recorder, runs the agent and takes every
// post-run read; its verdict is qa-evidence/agent-rc.txt. "pass" here only means the case ran to
// its end in that process.
import fs from "node:fs";
import { spawnSync } from "node:child_process";
import { THREADTIME, writeRecord } from "../login_phase.mjs";

export const EVIDENCE = "qa-evidence";

/** "MM-DD HH:MM:SS.mmm" (logcat threadtime) from the device's `date +'%m-%d %H:%M:%S.%N'`. */
export function threadtimeStamp(deviceDate) {
  const m = /^(\d\d-\d\d \d\d:\d\d:\d\d)\.(\d{3})\d*$/.exec(String(deviceDate ?? "").trim());
  const stamp = m ? `${m[1]}.${m[2]}` : "";
  return THREADTIME.test(`${stamp} `) ? stamp : null;
}

/**
 * This run's login record for the release lane (login_phase.mjs readRecord validates it): the
 * app's observed exchange and the device time at the match. Text only, no account data.
 */
export function writeReleaseLoginRecord(out, { exchangeAt, loginEnd }, env = process.env) {
  if (!THREADTIME.test(`${exchangeAt} `)) throw new Error("release login: no observed app exchange");
  if (!THREADTIME.test(`${loginEnd} `) || exchangeAt > loginEnd) throw new Error("release login: no device time for login_end");
  return writeRecord(out, { mode: "release-prelogin", status: "matched", exchange_at: exchangeAt, login_end: loginEnd, reason: null }, env);
}

export async function run(ctx) {
  const loginEnd = threadtimeStamp(ctx.shell("date +'%m-%d %H:%M:%S.%N'", { allowFailure: true }));
  writeReleaseLoginRecord(EVIDENCE, { exchangeAt: ctx.releaseLogin?.exchangeAt, loginEnd });
  // No QA account in the case's environment (the login read it from a consumed file anyway).
  const env = { ...process.env };
  for (const key of ["QA_LOGIN_EMAIL", "QA_LOGIN_PASSWORD", "QA_TEST_EMAIL", "QA_TEST_PASSWORD", "QA_LOGIN_ACCEPTANCE_EMAIL", "QA_LOGIN_ACCEPTANCE_PASSWORD", "ACTIONS_ID_TOKEN_REQUEST_URL", "ACTIONS_ID_TOKEN_REQUEST_TOKEN"]) delete env[key];
  fs.rmSync(`${EVIDENCE}/agent-rc.txt`, { force: true });
  const result = spawnSync("bash", ["scripts/qa/release_settings_case.sh"], { stdio: "inherit", env });
  const ran = result.status === 0 && fs.existsSync(`${EVIDENCE}/agent-rc.txt`);
  ctx.summary.scenario = { result: ran ? "pass" : "fail", ...(ran ? {} : { error: "the release settings case did not run to its end" }) };
  return ctx.summary.scenario;
}
