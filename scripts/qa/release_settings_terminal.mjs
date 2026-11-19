#!/usr/bin/env node
// The single terminal decision of the release settings lane (scripts/qa/release_settings_login.sh),
// taken before anything is published. Both exits (the Actions artifact and Stamp's evidence +
// verdict) go through secure_evidence.mjs, which reads the same login record; so:
//
//   complete   = the login driver exited 0, Testbed matched, the case ran in the matched app process
//                to its end (recorder stopped and joined), and every required local/web cleanup
//                succeeded (capture file, browser role, capture browser, the QA web session, the
//                receipt provider disabled). The case's own verdict (agent-rc.txt) stands.
//   otherwise  = the run's login record is invalidated (markUnknown): both exits withhold every
//                frame and file, verdict.json becomes "unknown" and agent-rc.txt 2 (not completed).
//
// The app's own session is reported separately (app_session: as requested and observed); it is
// not a required cleanup here. qa-evidence/release-login-summary.json gets the public projection.
//
// Usage: release_settings_terminal.mjs --summary <driver summary.json> --rc <driver exit> --evidence qa-evidence
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { RECORD, markUnknown } from "./login_phase.mjs";

export const REQUIRED_CLEANUP = {
  capture_file: ["deleted", "not_applicable"],
  browser: ["restored", "not_changed"],
  capture_browser: ["uninstalled", "not_installed_by_run"],
  web_session: ["signed_out"],
  identity_receipt_provider: ["disabled"],
};

export function terminalDecision({ summary, rc }) {
  const s = summary && typeof summary === "object" ? summary : {};
  const cleanup = s.cleanup ?? {};
  const failedCleanup = Object.keys(REQUIRED_CLEANUP).filter((k) => !REQUIRED_CLEANUP[k].includes(cleanup[k]));
  const state = s.identity_receipt?.state ?? "unknown";
  let reason = null;
  if (state !== "matched") reason = `Testbed decision ${state}`;
  else if (s.identity_process !== "same") reason = `app process ${s.identity_process ?? "not checked"} around the case`;
  else if (s.scenario_result !== "pass") reason = `the case did not run to its end (${s.scenario_result ?? "not_run"})`;
  else if (failedCleanup.length) reason = `required cleanup not confirmed: ${failedCleanup.map((k) => `${k}=${cleanup[k] ?? "missing"}`).join(", ")}`;
  else if (rc !== 0) reason = `release login exited ${rc}`;
  return {
    complete: reason === null,
    reason,
    public: {
      result: s.result ?? "missing",
      identity_receipt: s.identity_receipt ?? { state: "unknown" },
      identity_process: s.identity_process ?? "not_checked",
      scenario_result: s.scenario_result ?? "not_run",
      cleanup: Object.fromEntries(Object.keys(REQUIRED_CLEANUP).map((k) => [k, cleanup[k] ?? "missing"])),
      app_session: cleanup.app_session ?? "missing",
      terminal: reason === null ? "complete" : "not_completed",
    },
  };
}

/** Applies the decision to the evidence directory before either exit runs. */
export function applyTerminalDecision(evidence, decision, env = process.env) {
  fs.mkdirSync(evidence, { recursive: true });
  if (!decision.complete) {
    if (fs.existsSync(path.join(evidence, RECORD))) markUnknown(evidence, `release lane: ${decision.reason}`, env);
    fs.writeFileSync(path.join(evidence, "agent-rc.txt"), "2\n");
  }
  fs.writeFileSync(path.join(evidence, "release-login-summary.json"), `${JSON.stringify(decision.public, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const arg = (name) => { const i = process.argv.indexOf(`--${name}`); return i >= 0 ? process.argv[i + 1] : ""; };
  let summary = null;
  try { summary = JSON.parse(fs.readFileSync(arg("summary"), "utf8")); } catch {}
  const decision = terminalDecision({ summary, rc: Number(arg("rc")) });
  applyTerminalDecision(arg("evidence") || "qa-evidence", decision);
  console.log(decision.complete ? "release lane: complete; the case verdict stands" : `::error::release lane not completed: ${decision.reason}; evidence withheld from both exits`);
}
