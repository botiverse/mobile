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
// from Testbed via OIDC (never written to disk) — the password anywhere (including short passwords and
// JSON-escaped values in .json/.jsonl/.ndjson such as verdict.json) is UNKNOWN and the email is
// redacted from text files. For login-email-password, BOTH the email and the password must be present
// and scannable, and every structured evidence file (.json/.jsonl/.ndjson) must parse completely; if
// a credential field is unavailable/uncheckable or any structured file fails to parse (e.g. a
// truncated session.jsonl line), the gate fails closed (UNKNOWN, secrets_checked: false) rather than
// uploading partially scanned evidence.
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
const JSON_FILE = /\.json$/i;
const JSON_LINES_FILE = /\.(jsonl|ndjson)$/i;

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

const isNonEmptyString = (value) => typeof value === "string" && value.length > 0;

/** The interior of a JSON string literal for `value` (catches `\"`, `\\`, `\t`, etc. in raw JSON text). */
const jsonLiteralInterior = (value) => JSON.stringify(value).slice(1, -1);

/** Collects every string value and object key from a parsed JSON tree. */
function jsonTreeContainsAny(value, needles) {
  if (typeof value === "string") return needles.some((needle) => value.includes(needle));
  if (Array.isArray(value)) return value.some((item) => jsonTreeContainsAny(item, needles));
  if (value && typeof value === "object") {
    for (const [k, v] of Object.entries(value)) {
      if (needles.some((needle) => k.includes(needle)) || jsonTreeContainsAny(v, needles)) return true;
    }
  }
  return false;
}

/** Recursively replaces every needle inside string values and object keys of a parsed JSON tree. */
function redactJsonTree(value, needles) {
  let changed = false;
  const scrubString = (s) => {
    let out = s;
    for (const needle of needles) {
      if (out.includes(needle)) {
        out = out.split(needle).join("***");
        changed = true;
      }
    }
    return out;
  };
  const visit = (node) => {
    if (typeof node === "string") return scrubString(node);
    if (Array.isArray(node)) return node.map(visit);
    if (node && typeof node === "object") {
      const next = {};
      for (const [k, v] of Object.entries(node)) next[scrubString(k)] = visit(v);
      return next;
    }
    return node;
  };
  const result = visit(value);
  return { changed, result };
}

/**
 * Scans `bytes` of `filePath` for passwords — raw, JSON-escaped (`\"`, `\\`, etc.), and decoded
 * from parsed JSON / JSONL (including `\uXXXX` unicode escapes in `verdict.json`).
 * When `strictStructured` is true (login-email-password), any unparseable `.json`/`.jsonl`/`.ndjson`
 * returns `{ secretFound: false, parseError: true }` so the caller fails closed with
 * `secrets_checked: false`.
 */
function scanFileForPasswords(filePath, bytes, passwords, { strictStructured = false } = {}) {
  for (const password of passwords) {
    if (bytes.includes(password)) return { secretFound: true, parseError: false };
    const escaped = jsonLiteralInterior(password);
    if (escaped !== password && bytes.includes(escaped)) return { secretFound: true, parseError: false };
  }
  if (JSON_FILE.test(filePath)) {
    try {
      const parsed = JSON.parse(bytes.toString("utf8"));
      if (passwords.length > 0 && jsonTreeContainsAny(parsed, passwords)) {
        return { secretFound: true, parseError: false };
      }
    } catch {
      if (strictStructured) return { secretFound: false, parseError: true };
    }
  } else if (JSON_LINES_FILE.test(filePath)) {
    for (const line of bytes.toString("utf8").split("\n")) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      try {
        const parsed = JSON.parse(trimmed);
        if (passwords.length > 0 && jsonTreeContainsAny(parsed, passwords)) {
          return { secretFound: true, parseError: false };
        }
      } catch {
        if (strictStructured) return { secretFound: false, parseError: true };
      }
    }
  }
  return { secretFound: false, parseError: false };
}

/**
 * Redacts every email from a text file (raw, JSON-literal-escaped, and parsed JSON/JSONL escapes).
 * When `strictStructured` is true, returns `{ parseError: true }` if a structured file fails to parse.
 */
function redactEmailsInTextFile(filePath, bytes, emails, { strictStructured = false } = {}) {
  if (emails.length === 0 || !TEXT_FILE.test(filePath)) return { parseError: false };
  let text = null;
  for (const email of emails) {
    for (const form of new Set([email, jsonLiteralInterior(email)])) {
      if (bytes.includes(form) || (text !== null && text.includes(form))) {
        text = (text ?? bytes.toString("utf8")).split(form).join("***");
      }
    }
  }
  const current = text ?? bytes.toString("utf8");
  if (JSON_FILE.test(filePath)) {
    try {
      const parsed = JSON.parse(current);
      const { changed, result } = redactJsonTree(parsed, emails);
      if (changed) text = `${JSON.stringify(result, null, 2)}\n`;
    } catch {
      if (strictStructured) return { parseError: true };
    }
  } else if (JSON_LINES_FILE.test(filePath)) {
    const lines = current.split("\n");
    let anyChanged = false;
    const rewritten = [];
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) {
        rewritten.push(line);
        continue;
      }
      try {
        const parsed = JSON.parse(trimmed);
        const { changed, result } = redactJsonTree(parsed, emails);
        if (changed) {
          anyChanged = true;
          rewritten.push(JSON.stringify(result));
        } else {
          rewritten.push(line);
        }
      } catch {
        if (strictStructured) return { parseError: true };
        rewritten.push(line);
      }
    }
    if (anyChanged) text = rewritten.join("\n");
  }
  if (text !== null) fs.writeFileSync(filePath, text);
  return { parseError: false };
}

export function gate(dir, env = process.env, { credentials = null } = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const { ok, record, reason } = readRecord(dir, env);
  let why = ok ? null : reason;
  // Release lane: also this run's completion record (written last by release_settings_terminal.mjs).
  if (!why && record.mode === "release-prelogin") {
    const terminal = readTerminal(dir, record, env);
    if (!terminal.ok) why = terminal.reason;
  }
  const requiresAcceptanceCred = isLoginAcceptanceRun(record, env);
  const acceptanceEmail = isNonEmptyString(credentials?.email) ? credentials.email : "";
  const acceptancePassword = isNonEmptyString(credentials?.password) ? credentials.password : "";
  const acceptanceCredComplete = Boolean(acceptanceEmail && acceptancePassword && !credentials?.problem);
  if (!why && requiresAcceptanceCred) {
    if (credentials?.problem) {
      why = `login acceptance credential unavailable for evidence scan (${credentials.problem})`;
    } else if (!acceptanceEmail || !acceptancePassword) {
      why = "login acceptance credential incomplete for evidence scan";
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
  // Runtime acceptance credentials are checked in full (any non-empty password/email, no length >= 4 drop);
  // legacy QA_TEST_* env values keep their >= 4 guard.
  const passwords = [
    ...(typeof env.QA_TEST_PASSWORD === "string" && env.QA_TEST_PASSWORD.length >= 4 ? [env.QA_TEST_PASSWORD] : []),
    ...(acceptancePassword ? [acceptancePassword] : []),
  ];
  const emails = [
    ...(typeof env.QA_TEST_EMAIL === "string" && env.QA_TEST_EMAIL.length >= 4 ? [env.QA_TEST_EMAIL] : []),
    ...(acceptanceEmail ? [acceptanceEmail] : []),
  ];
  const scanRan = !why;
  let structuredParseFailed = false;
  for (const f of scanRan ? publishableFiles() : []) {
    if (!fs.statSync(f).isFile()) continue;
    const bytes = fs.readFileSync(f);
    const pwScan = scanFileForPasswords(f, bytes, passwords, { strictStructured: requiresAcceptanceCred });
    if (pwScan.secretFound) { why = `secret value in ${path.basename(f)}`; break; }
    if (pwScan.parseError) { structuredParseFailed = true; why = `unparseable structured evidence in ${path.basename(f)}`; break; }
    const emailScan = redactEmailsInTextFile(f, bytes, emails, { strictStructured: requiresAcceptanceCred });
    if (emailScan.parseError) { structuredParseFailed = true; why = `unparseable structured evidence in ${path.basename(f)}`; break; }
  }
  if (why) {
    if (ok) markUnknown(dir, why, env);
    for (const e of fs.readdirSync(dir)) if (!KEEP_ON_FAILURE.has(e)) fs.rmSync(path.join(dir, e), { recursive: true, force: true });
    fs.writeFileSync(path.join(dir, "verdict.json"), `${JSON.stringify({ verdict: "unknown", reason: `UNKNOWN: ${why}; no evidence uploaded` }, null, 2)}\n`);
    fs.writeFileSync(path.join(dir, "agent-rc.txt"), "2\n");
  }
  const secretsChecked = requiresAcceptanceCred
    ? (scanRan && acceptanceCredComplete && !structuredParseFailed)
    : (passwords.length > 0 || emails.length > 0);
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
