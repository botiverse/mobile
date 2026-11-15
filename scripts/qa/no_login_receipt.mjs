#!/usr/bin/env node
// Positive "no login window" receipt for workflow modes that structurally never
// sign in (no QA credentials in their step, no login flow in their code).
//
// The egress gate (secure_evidence.mjs) treats a missing capture-suppression
// record as "the whole run may be inside a login window". Modes that cannot
// enter one say so positively instead of by silence:
//
//   begin   (first line of the mode's own step) writes the receipt, NOT yet
//           completed: { mode, reason, binding (run id + attempt),
//           no_login_window: true, capture_suppressed: false, phases: [] }.
//           Refused for a mode that is not in NO_LOGIN_MODES.
//   finish  (separate always() step after the collectors, before the gate)
//           scans every text file of the evidence dir for login-like signals
//           (the app's web-login attempt, a secure text field, a web sign-in
//           sheet or Safari/auth-session surface, "Continue as ...", or a
//           marker a mode wrote when it saw one in-process). None: completed.
//           Any: the receipt latches UNKNOWN with a window from the mode's
//           start, so the gate withholds everything the mode captured.
//   latch   (in-process) the same UNKNOWN latch, for a mode that observes a
//           login-like surface itself (ios_tabpager_probe.mjs before every
//           capture; ios_qa_bootstrap.mjs smoke before its screenshot).
//
// The gate accepts the receipt only when its mode is allow-listed here AND
// equals the run's own mode (QA_MODE, from the workflow's inputs.mode) AND the
// binding is this run/attempt. Any other mode with a missing or {} record
// stays UNKNOWN.
//
// Usage: node scripts/qa/no_login_receipt.mjs begin|finish --mode <mode> --out qa-evidence

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { SUPPRESSION_CONTRACT, runBinding } from "./secure_input.mjs";

export const NO_LOGIN_KIND = "qa-no-login-receipt/v1";
// mode -> why it can never open a login window. Reviewed list: adding a mode
// here is the human-review boundary.
export const NO_LOGIN_MODES = Object.freeze({
  "tabpager-probe": "launches the TabPager probe @Page directly (QA_LOOP_FIXTURE_PAGE); its step gets no QA credentials and ios_tabpager_probe.mjs has no login flow",
  "bootstrap-smoke": "delivers fake-token session envelopes through the bootstrap inbox; its step gets no QA account or credentials and ios_qa_bootstrap.mjs smoke has no login flow",
});
export const LOGIN_SIGNAL_MARKER = "qa-login-signal-observed";
// Narrow on purpose: things that only appear when a login surface is up.
export const LOGIN_SIGNAL = new RegExp([
  "web_login_attempt phase=(?:opening|awaiting_callback)",
  "secure ?text ?field",
  "ASWebAuthenticationSession", "SFAuthenticationSession", "SFSafariViewController", "SafariViewService",
  "wants to use .{1,120} to sign in",
  "\\bcontinue as\\b",
  LOGIN_SIGNAL_MARKER,
].join("|"), "i");
const FILE = "capture-suppression.json";
const SKIP = new Set([FILE, "secure-evidence-gate.json"]);

const readReceipt = (out) => JSON.parse(fs.readFileSync(path.join(out, FILE), "utf8"));
const writeReceipt = (out, r) => fs.writeFileSync(path.join(out, FILE), JSON.stringify(r, null, 2));

export function beginNoLoginReceipt({ out, mode, env = process.env, now = Date.now() }) {
  if (!Object.hasOwn(NO_LOGIN_MODES, mode)) throw new Error(`mode ${JSON.stringify(mode)} is not an allow-listed no-login mode`);
  fs.mkdirSync(out, { recursive: true });
  const receipt = {
    contract: SUPPRESSION_CONTRACT, kind: NO_LOGIN_KIND, mode, reason: NO_LOGIN_MODES[mode],
    no_login_window: true, capture_suppressed: false, started_ts: now,
    unresolved: false, active: false, completed: false, binding: runBinding(env), phases: [],
  };
  writeReceipt(out, receipt);
  return receipt;
}

// Latch UNKNOWN: a window from the mode's start, never closed.
export function latchLoginSignal({ out, signal, now = Date.now() }) {
  let r;
  try { r = readReceipt(out); } catch { r = null; }
  if (!r || r.kind !== NO_LOGIN_KIND) return null; // no receipt: the gate already fails closed
  const start = Number.isFinite(r.started_ts) ? r.started_ts : 0;
  Object.assign(r, {
    no_login_window: false, unresolved: true, active: true,
    phases: [{ start_ts: start, status: "UNKNOWN", recording: "not_recorded", unknown_ts: now, reasons: [`login-like signal observed: ${String(signal).slice(0, 120)}`] }],
  });
  writeReceipt(out, r);
  return r;
}

function textFiles(dir) {
  const out = [];
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) { if (!/\.xcresult$/i.test(e.name)) walk(p); continue; }
      if (!e.isFile() || SKIP.has(e.name)) continue;
      const buf = fs.readFileSync(p);
      if (buf.includes(0)) continue;
      out.push([path.relative(dir, p), buf.toString("utf8")]);
    }
  };
  walk(dir);
  return out;
}

// Returns the final receipt (completed, or latched UNKNOWN).
export function finishNoLoginReceipt({ out, mode, env = process.env }) {
  let r;
  try { r = readReceipt(out); } catch { throw new Error("no receipt: begin never ran (the gate fails closed)"); }
  if (r.kind !== NO_LOGIN_KIND || r.mode !== mode) throw new Error("receipt is not this mode's no-login receipt");
  const b = runBinding(env);
  if (r.binding?.github_run_id !== b.github_run_id || r.binding?.github_run_attempt !== b.github_run_attempt) throw new Error("receipt belongs to another run or attempt");
  for (const [file, text] of textFiles(out)) {
    const m = LOGIN_SIGNAL.exec(text);
    if (m) {
      latchLoginSignal({ out, signal: `${m[0]} in ${file}` });
      r = readReceipt(out);
      break;
    }
  }
  r.completed = true;
  writeReceipt(out, r);
  return r;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [cmd, ...rest] = process.argv.slice(2);
  const opt = (f) => { const i = rest.indexOf(f); return i >= 0 ? rest[i + 1] : ""; };
  try {
    const out = opt("--out") || "qa-evidence";
    if (cmd === "begin") beginNoLoginReceipt({ out, mode: opt("--mode") });
    else if (cmd === "finish") {
      const r = finishNoLoginReceipt({ out, mode: opt("--mode") });
      console.log(`no-login receipt: ${r.unresolved ? `UNKNOWN (${r.phases[0].reasons[0]})` : "no login window"} mode=${r.mode}`);
    } else throw new Error("usage: no_login_receipt.mjs begin|finish --mode <mode> --out <dir>");
  } catch (error) {
    console.error(`no-login receipt: ${String(error?.message || error)}`);
    process.exit(1);
  }
}
