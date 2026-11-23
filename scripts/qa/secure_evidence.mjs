#!/usr/bin/env node
// Evidence gate (#222). Runs before every exit of an evidence directory: stamp_report.sh
// report-evidence / report, and the workflows' artifact upload (which runs only if this
// exits 0). Idempotent, so running it twice is harmless.
//
// Valid login record of this run (login_phase.mjs readRecord):
//   password login (and the release lane's matched record): log lines (logcat*, system-log*) are kept only when their device
//     timestamp is strictly after login_end; a line at the same millisecond, a line in
//     another format or without a timestamp is dropped. .xcresult bundles (opaque) go.
//   no login: any sign-in line in the text evidence (web_login_attempt / app_login_complete)
//     means the run saw a login after all: UNKNOWN.
// Then, when the step has the QA credentials, the password anywhere is UNKNOWN and the
// email is redacted from text files.
// Anything else (missing, {}, malformed, foreign or unknown record) is UNKNOWN: everything
// except verdict.json, agent-rc.txt, the record and this gate's own record is deleted,
// verdict.json says "unknown", agent-rc.txt says 2, and the gate exits 1.
//
// Usage: node scripts/qa/secure_evidence.mjs --dir qa-evidence
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { LOGIN_LOG_MARKERS, RECORD, THREADTIME, readRecord, runId, secretValues } from "./login_phase.mjs";
import { readTerminal } from "./release_settings_terminal.mjs";

export const GATE = "evidence-gate.json";
const KEEP = new Set(["verdict.json", "agent-rc.txt", RECORD, GATE]);
const LOG_FILE = /^(logcat|system-log|syslog).*\.(txt|log|ndjson)$/i;
const TEXT_FILE = /\.(txt|log|json|jsonl|ndjson|xml|ips|md|csv)$/i;

const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
  const p = path.join(dir, e.name);
  return e.isDirectory() ? (e.name.endsWith(".xcresult") ? [p] : walk(p)) : [p];
});

// Keeps only lines whose threadtime stamp sorts strictly after loginEnd ("MM-DD HH:MM:SS.mmm").
// A year rollover makes later lines sort earlier: they are dropped (fails closed).
export function linesAfter(text, loginEnd) {
  return String(text).split("\n").filter((line) => {
    const stamp = THREADTIME.exec(line)?.[1];
    return stamp !== undefined && stamp > loginEnd;
  }).join("\n");
}

export function gate(dir, env = process.env) {
  fs.mkdirSync(dir, { recursive: true });
  const { ok, record, reason } = readRecord(dir, env);
  let why = ok ? null : reason;
  // Release lane: also this run's completion record (written last by release_settings_terminal.mjs).
  if (!why && record.mode === "release-prelogin") {
    const terminal = readTerminal(dir, record, env);
    if (!terminal.ok) why = terminal.reason;
  }
  const files = () => walk(dir).filter((f) => !KEEP.has(path.relative(dir, f)));
  // A release-prelogin record bounds the log the same way (lines strictly after login_end).
  if (!why && (record.mode === "password" || record.mode === "release-prelogin")) {
    for (const f of files()) {
      if (f.endsWith(".xcresult")) fs.rmSync(f, { recursive: true, force: true });
      else if (LOG_FILE.test(path.basename(f))) fs.writeFileSync(f, linesAfter(fs.readFileSync(f, "utf8"), record.login_end));
    }
  }
  if (!why && record.mode === "none") {
    const hit = files().find((f) => TEXT_FILE.test(f) && fs.statSync(f).isFile() && LOGIN_LOG_MARKERS.test(fs.readFileSync(f, "utf8")));
    if (hit) why = `sign-in signal in ${path.basename(hit)} although this run never signs in`;
  }
  const passwords = [env.QA_TEST_PASSWORD, env.QA_LOGIN_ACCEPTANCE_PASSWORD].filter((v) => v && v.length >= 4);
  const emails = [env.QA_TEST_EMAIL, env.QA_LOGIN_ACCEPTANCE_EMAIL].filter((v) => v && v.length >= 4);
  for (const f of why ? [] : files()) {
    if (!fs.statSync(f).isFile()) continue;
    const bytes = fs.readFileSync(f);
    if (passwords.some((password) => bytes.includes(password))) { why = `secret value in ${path.basename(f)}`; break; }
    if (emails.length && TEXT_FILE.test(f)) {
      let text = null;
      for (const email of emails) {
        if (bytes.includes(email)) text = (text ?? bytes.toString("utf8")).split(email).join("***");
      }
      if (text !== null) fs.writeFileSync(f, text);
    }
  }
  if (why) {
    for (const e of fs.readdirSync(dir)) if (!KEEP.has(e)) fs.rmSync(path.join(dir, e), { recursive: true, force: true });
    fs.writeFileSync(path.join(dir, "verdict.json"), `${JSON.stringify({ verdict: "unknown", reason: `UNKNOWN: ${why}; no evidence uploaded` }, null, 2)}\n`);
    fs.writeFileSync(path.join(dir, "agent-rc.txt"), "2\n");
  }
  fs.writeFileSync(path.join(dir, GATE), `${JSON.stringify({ ok: !why, run: runId(env), reason: why, secrets_checked: secretValues(env).length > 0 }, null, 2)}\n`);
  return { ok: !why, reason: why };
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const i = process.argv.indexOf("--dir");
  const result = gate(i >= 0 ? process.argv[i + 1] : "qa-evidence");
  console.log(result.ok ? "evidence gate: OK" : `evidence gate: UNKNOWN (${result.reason}); evidence withheld`);
  process.exit(result.ok ? 0 : 1);
}
