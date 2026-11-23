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
// Then, when the step has the QA credentials — or for login-email-password (#216), fetched in memory
// from Testbed via OIDC (never written to disk) — the password anywhere is UNKNOWN and the email is
// redacted from text files. If login-email-password's runtime credential cannot be fetched for a
// passing login record, the gate fails closed (UNKNOWN) rather than uploading unscanned evidence.
// Anything else (missing, {}, malformed, foreign or unknown record) is UNKNOWN: everything
// except verdict.json, agent-rc.txt, the record and this gate's own record is deleted,
// verdict.json says "unknown", agent-rc.txt says 2, and the gate exits 1.
//
// Usage: node scripts/qa/secure_evidence.mjs --dir qa-evidence
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { LOGIN_ACCEPTANCE_CASE, LOGIN_LOG_MARKERS, RECORD, THREADTIME, markUnknown, readRecord, runId } from "./login_phase.mjs";
import { readTerminal } from "./release_settings_terminal.mjs";
import { fetchLoginAcceptanceCredential } from "./testbed_login_acceptance_input.mjs";
import { TestbedReleaseError } from "./testbed_release_settings.mjs";

export const GATE = "evidence-gate.json";
// Files that survive a failed gate (verdict.json and agent-rc.txt are overwritten on failure).
const KEEP_ON_FAILURE = new Set(["verdict.json", "agent-rc.txt", RECORD, GATE]);
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

const isLoginAcceptanceRun = (record, env) =>
  env?.STAMP_CLAIMED_CASE === LOGIN_ACCEPTANCE_CASE || record?.acceptance?.case_id === LOGIN_ACCEPTANCE_CASE;

export function gate(dir, env = process.env, { credentials = null } = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const { ok, record, reason } = readRecord(dir, env);
  let why = ok ? null : reason;
  // Release lane: also this run's completion record (written last by release_settings_terminal.mjs).
  if (!why && record.mode === "release-prelogin") {
    const terminal = readTerminal(dir, record, env);
    if (!terminal.ok) why = terminal.reason;
  }
  const requiresAcceptanceCred = !why && isLoginAcceptanceRun(record, env);
  if (requiresAcceptanceCred) {
    if (credentials?.problem) {
      why = `login acceptance credential unavailable for evidence scan (${credentials.problem})`;
    } else if (!credentials?.email || !credentials?.password) {
      why = "login acceptance credential missing for evidence scan";
    }
  }
  // Separate what is kept on failure from what is scanned before publishing: verdict.json
  // carries free-text from the agent and is both uploaded and echoed to $GITHUB_STEP_SUMMARY,
  // so every publishable file (all files except this gate's own output) must be scanned.
  const evidenceFiles = () => walk(dir).filter((f) => !KEEP_ON_FAILURE.has(path.relative(dir, f)));
  const publishableFiles = () => walk(dir).filter((f) => path.relative(dir, f) !== GATE);
  // A release-prelogin record bounds the log the same way (lines strictly after login_end).
  if (!why && (record.mode === "password" || record.mode === "release-prelogin")) {
    for (const f of evidenceFiles()) {
      if (f.endsWith(".xcresult")) fs.rmSync(f, { recursive: true, force: true });
      else if (LOG_FILE.test(path.basename(f))) fs.writeFileSync(f, linesAfter(fs.readFileSync(f, "utf8"), record.login_end));
    }
  }
  if (!why && record.mode === "none") {
    const hit = evidenceFiles().find((f) => TEXT_FILE.test(f) && fs.statSync(f).isFile() && LOGIN_LOG_MARKERS.test(fs.readFileSync(f, "utf8")));
    if (hit) why = `sign-in signal in ${path.basename(hit)} although this run never signs in`;
  }
  const passwords = [env.QA_TEST_PASSWORD, credentials?.password].filter((v) => typeof v === "string" && v.length >= 4);
  const emails = [env.QA_TEST_EMAIL, credentials?.email].filter((v) => typeof v === "string" && v.length >= 4);
  for (const f of why ? [] : publishableFiles()) {
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
    if (ok) markUnknown(dir, why, env);
    for (const e of fs.readdirSync(dir)) if (!KEEP_ON_FAILURE.has(e)) fs.rmSync(path.join(dir, e), { recursive: true, force: true });
    fs.writeFileSync(path.join(dir, "verdict.json"), `${JSON.stringify({ verdict: "unknown", reason: `UNKNOWN: ${why}; no evidence uploaded` }, null, 2)}\n`);
    fs.writeFileSync(path.join(dir, "agent-rc.txt"), "2\n");
  }
  const secretsChecked = passwords.length > 0 || emails.length > 0;
  fs.writeFileSync(path.join(dir, GATE), `${JSON.stringify({ ok: !why, run: runId(env), reason: why, secrets_checked: secretsChecked }, null, 2)}\n`);
  return { ok: !why, reason: why };
}

/** Async gate entry: for login-email-password, fetches the runtime credential in memory via Testbed OIDC before scanning. */
export async function runGate(dir, env = process.env, { fetchImpl = fetch, retryDelaysMs, credentials = null } = {}) {
  let resolvedCredentials = credentials;
  const check = readRecord(dir, env);
  if (check.ok && isLoginAcceptanceRun(check.record, env) && !resolvedCredentials) {
    try {
      resolvedCredentials = await fetchLoginAcceptanceCredential({ env, fetchImpl, retryDelaysMs });
    } catch (error) {
      const code = error instanceof TestbedReleaseError ? error.message : "unexpected_error";
      resolvedCredentials = { problem: code };
    }
  }
  return gate(dir, env, { credentials: resolvedCredentials });
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const i = process.argv.indexOf("--dir");
  const result = await runGate(i >= 0 ? process.argv[i + 1] : "qa-evidence");
  console.log(result.ok ? "evidence gate: OK" : `evidence gate: UNKNOWN (${result.reason}); evidence withheld`);
  process.exit(result.ok ? 0 : 1);
}
