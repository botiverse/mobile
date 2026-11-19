#!/usr/bin/env node
// The single terminal decision of the release settings lane (scripts/qa/release_settings_login.sh),
// taken before anything is published. Both exits (the Actions artifact and Stamp's evidence +
// verdict) go through secure_evidence.mjs, which reads the same login record; so:
//
//   complete   = the login driver exited 0, Testbed matched, the case ran in the matched app process
//                to its end, this run's recording is complete (started, joined, every segment
//                collected: recording.json), and every required local/web cleanup
//                succeeded (capture file, browser role, capture browser, the QA web session, the
//                receipt provider disabled). The case's own verdict (agent-rc.txt) stands.
//   otherwise  = the run's login record is invalidated (markUnknown): both exits withhold every
//                frame and file, verdict.json becomes "unknown" and agent-rc.txt 2 (not completed).
//
// Only a complete run gets release-terminal.json, written last and atomically; the evidence gate
// requires it for a release record, so an interrupted run publishes nothing.
// The app's own session is reported separately (app_session: as requested and observed); it is
// not a required cleanup here. qa-evidence/release-login-summary.json gets the public projection.
//
// Usage: release_settings_terminal.mjs --summary <driver summary.json> --rc <driver exit> --evidence qa-evidence
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { RECORD, markUnknown, readRecord, runId } from "./login_phase.mjs";

// Written LAST, atomically (temp file + rename), and only for a complete run. secure_evidence.mjs
// (both exits) requires it, bound to this run and to its release login record: a run that was
// interrupted or cancelled before this point has none, and publishes nothing.
export const TERMINAL = "release-terminal.json";
export const TERMINAL_CONTRACT = "qa-release-terminal/v1";

/** This run's completion record, if present and valid for `record` (the release login record). */
export function readTerminal(dir, record, env = process.env) {
  let t;
  try { t = JSON.parse(fs.readFileSync(path.join(dir, TERMINAL), "utf8")); } catch { return { ok: false, reason: "release lane: no completion record of this run" }; }
  if (!t || t.contract !== TERMINAL_CONTRACT || t.run !== runId(env) || t.complete !== true) return { ok: false, reason: "release lane: completion record missing, incomplete or of another run" };
  if (t.login_end !== record?.login_end || t.exchange_at !== record?.exchange_at) return { ok: false, reason: "release lane: completion record does not match the login record" };
  return { ok: true };
}

/** This run's recording receipt from release_settings_case.sh. */
function recordingComplete(evidence, env) {
  try {
    const r = JSON.parse(fs.readFileSync(path.join(evidence, "recording.json"), "utf8"));
    return r.status === "complete" && Number.isInteger(r.segments) && r.segments >= 1 && r.run === runId(env) ? "complete" : String(r.status ?? "missing");
  } catch {
    return "missing";
  }
}

export const REQUIRED_CLEANUP = {
  capture_file: ["deleted", "not_applicable"],
  browser: ["restored", "not_changed"],
  capture_browser: ["uninstalled", "not_installed_by_run"],
  web_session: ["signed_out"],
  identity_receipt_provider: ["disabled"],
};

export function terminalDecision({ summary, rc, recording = "complete" }) {
  const s = summary && typeof summary === "object" ? summary : {};
  const cleanup = s.cleanup ?? {};
  const failedCleanup = Object.keys(REQUIRED_CLEANUP).filter((k) => !REQUIRED_CLEANUP[k].includes(cleanup[k]));
  const state = s.identity_receipt?.state ?? "unknown";
  let reason = null;
  if (state !== "matched") reason = `Testbed decision ${state}`;
  else if (s.identity_process !== "same") reason = `app process ${s.identity_process ?? "not checked"} around the case`;
  else if (s.scenario_result !== "pass") reason = `the case did not run to its end (${s.scenario_result ?? "not_run"})`;
  else if (recording !== "complete") reason = `recording ${recording}`;
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
      recording,
      terminal: reason === null ? "complete" : "not_completed",
    },
  };
}

/** Applies the decision to the evidence directory before either exit runs. */
export function applyTerminalDecision(evidence, decision, env = process.env) {
  fs.mkdirSync(evidence, { recursive: true });
  fs.rmSync(path.join(evidence, TERMINAL), { force: true });
  const login = readRecord(evidence, env);
  if (decision.complete && !(login.ok && login.record.mode === "release-prelogin")) {
    decision = { ...decision, complete: false, reason: "no valid release login record", public: { ...decision.public, terminal: "not_completed" } };
  }
  fs.writeFileSync(path.join(evidence, "release-login-summary.json"), `${JSON.stringify(decision.public, null, 2)}\n`);
  if (!decision.complete) {
    if (fs.existsSync(path.join(evidence, RECORD))) markUnknown(evidence, `release lane: ${decision.reason}`, env);
    fs.writeFileSync(path.join(evidence, "agent-rc.txt"), "2\n");
    return decision;
  }
  // Last step: the completion record, atomically.
  const tmp = path.join(evidence, `.${TERMINAL}.${process.pid}.tmp`);
  fs.writeFileSync(tmp, `${JSON.stringify({ contract: TERMINAL_CONTRACT, run: runId(env), complete: true, exchange_at: login.record.exchange_at, login_end: login.record.login_end })}\n`);
  fs.renameSync(tmp, path.join(evidence, TERMINAL));
  return decision;
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const arg = (name) => { const i = process.argv.indexOf(`--${name}`); return i >= 0 ? process.argv[i + 1] : ""; };
  let summary = null;
  try { summary = JSON.parse(fs.readFileSync(arg("summary"), "utf8")); } catch {}
  const evidence = arg("evidence") || "qa-evidence";
  const decision = applyTerminalDecision(evidence, terminalDecision({ summary, rc: Number(arg("rc")), recording: recordingComplete(evidence, process.env) }));
  console.log(decision.complete ? "release lane: complete; the case verdict stands" : `::error::release lane not completed: ${decision.reason}; evidence withheld from both exits`);
}
