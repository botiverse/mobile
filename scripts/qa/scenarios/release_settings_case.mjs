// Release settings lane (qa-acceptance.yml login_mode=release-prelogin): the acceptance case runs
// as release_auto_login.mjs's scenario, i.e. after Testbed answered "matched" and inside the very
// app process that gave the matched receipt (release_auto_login.mjs checks the pid + start ticks
// before and after). Cleanup (web sign-out, capture browser, `pm disable` of the receipt
// provider, which kills the app) runs only after this returns. scripts/qa/release_settings_case.sh
// starts the recorder, runs the agent and takes every post-run read; its verdict is
// qa-evidence/agent-rc.txt. "pass" here only means the case ran to its end in that process.
import fs from "node:fs";
import { spawnSync } from "node:child_process";

export async function run(ctx) {
  // No QA account in the case's environment (the login read it from a consumed file anyway).
  const env = { ...process.env };
  for (const key of ["QA_LOGIN_EMAIL", "QA_LOGIN_PASSWORD", "QA_TEST_EMAIL", "QA_TEST_PASSWORD", "ACTIONS_ID_TOKEN_REQUEST_URL", "ACTIONS_ID_TOKEN_REQUEST_TOKEN"]) delete env[key];
  fs.rmSync("qa-evidence/agent-rc.txt", { force: true });
  const result = spawnSync("bash", ["scripts/qa/release_settings_case.sh"], { stdio: "inherit", env });
  const ran = result.status === 0 && fs.existsSync("qa-evidence/agent-rc.txt");
  ctx.summary.scenario = { result: ran ? "pass" : "fail", ...(ran ? {} : { error: "the release settings case did not run to its end" }) };
  return ctx.summary.scenario;
}
