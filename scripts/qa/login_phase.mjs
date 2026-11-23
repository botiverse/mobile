#!/usr/bin/env node
// Login phase for the QA acceptance executors (#222). It runs on its own, BEFORE the
// screen recorder starts and before the agent loop takes its first screenshot, and writes
// qa-evidence/login-record.json. Three rules:
//
// 1. The secret never goes into a process argument or a file. Android sends it on stdin to
//    a UI Automator driver that sets the two fields on the expected Chrome site. Nothing
//    else has a safe path, so iOS and every driver refuse to type a secret (refuseSecret);
//    iOS legacy password login therefore ends UNKNOWN.
// 2. The login window allows only fixed structural diagnostics: no screenshot or recording (the recorder is stopped
//    and confirmed gone before the window and must still be gone after it; the workflow starts
//    it only after a complete record), no UI tree to the model or logs (the tree is read
//    in memory, from /dev/tty, only to find fields and buttons), and no log lines from the
//    window (login_end is the device timestamp of the app's own login-complete line;
//    secure_evidence.mjs keeps only log lines after it).
// 3. Anything uncertain means no evidence: a missing or unclear receipt, a failed probe or a
//    device this run cannot prove it owns ends with status "unknown"; the agent does not run
//    and secure_evidence.mjs uploads nothing.
//
// Modes that never sign in (bootstrap session, deterministic repro, TabPager probe,
// bootstrap smoke) write a run-bound "no-login" record instead; seeing anything login-like
// later turns it into "unknown" (markUnknown).
//
// Login acceptance (#216, agreed with Testbed). Only for an attempt whose run-bound Testbed input
// (--testbed-input <file>, see readTestbedInput) explicitly selects case_id "login-email-password";
// every other job, with or without some input, runs exactly as above. qa-acceptance.yml adds
// --require-acceptance login-email-password when the job's claimed Testbed case is that case: then a
// missing, unreadable, unprotected or invalid input, another case, or a mode that does not log in with
// the Android form ends unknown without logging in (never a pass, never the ordinary login). In this one process and one
// app process it proves, from the app's own identity receipt (app_identity_receipt_lib.mjs; the
// package must ship the provider enabled), that (1) the app started signed out (read on its sign-in
// page, before any tap), (2) this form was submitted, and (3) afterwards the app is signed in through
// its own login (session_origin app_login) as the expected account on the expected server. Every read
// shares Testbed's attempt id and nonce, has a new request id, goes to the app over adb's stdin, and
// is checked against the OS: the echo, the installed package and its signing certificate (apksig on
// the installed APK: build-tools 36.0.0 + java), the same pid and start time. The expected refs are
// computed from the same nonce. A fresh device has no server selected after login (server chooser):
// the phase taps the one row reading "/<expected slug>", then reads again. Both refs are checked in the
// library's format before any comparison: a signed-in receipt without a valid user ref, or with a
// malformed server ref, is unknown; only two valid refs that differ are a mismatch, and no server ref
// after the selection step is server_not_selected. Receipts, refs, the nonce,
// ids and screen text stay in this process. The record gets one more field, `acceptance`:
// {case_id, run_case_id, attempt, verdict: pass|fail|unknown, reason: <LOGIN_ACCEPTANCE_REASONS code>};
// only a pass completes the record (fail and unknown leave it "unknown", so nothing is captured). A later
// qa-acceptance.yml step reports that verdict and reason to Testbed (testbed_login_acceptance_result.mjs,
// through readRecordAcceptance).
//
// On Android the CI job first builds its driver with scripts/qa/build_login_form.sh
// "$RUNNER_TEMP/qa-login-form.jar" in a step without the account environment.
// Usage:
//   node scripts/qa/login_phase.mjs login --platform android|ios --password-login true|false \
//     --out qa-evidence [--package build.raft.app] [--device emulator-5554] [--testbed-input <file>] \
//     [--require-acceptance login-email-password]
//   node scripts/qa/login_phase.mjs check --out qa-evidence    (exit 0 only for a valid record)
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  APKSIG_BUILD_TOOLS,
  apkSignerInspectorArgs,
  createIdentityReceiptRunner,
  newReceiptRequest,
  parseApkSignerInspector,
  serverRef,
  userRef,
} from "./app_identity_receipt_lib.mjs";
import { ENVIRONMENTS, parseCredentialFile } from "./release_auto_login_lib.mjs";
import { detectForeignAndroidSystemBlocker } from "./system_blocker.mjs";

export const RECORD = "login-record.json";
const CONTRACT = "qa-login-record/v1";
export const THREADTIME = /^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s/;
// The app's own sign-in lines (WebLoginAttempt.kt, AppLogin.kt): a run that never signs in
// must not show them.
export const LOGIN_LOG_MARKERS = /\b(web_login_attempt|app_login_complete)\b/;
const LOGIN_TEXT = /\b(password|passcode|passkey|continue as|sign in with)\b/i;
const RECEIPT = /app_login_complete result=(\S+) reason=(\S+)/;
const OWNER_MARKER = "/data/local/tmp/qa-run-owner";

// Login acceptance (#216): the one Testbed case that turns it on, and every reason code a verdict
// can carry (the record's `acceptance.reason`). LOGIN_ACCEPTANCE_REASONS is the one allowlist of the
// verdict/reason pairs a record can hold (isLoginAcceptancePair): testbed_login_acceptance_result.mjs
// reports any other pair to Testbed as unknown/reason_unlisted.
export const LOGIN_ACCEPTANCE_CASE = "login-email-password";
export const LOGIN_ACCEPTANCE_REASONS = {
  pass: ["signed_in_as_expected"],
  fail: ["user_mismatch", "server_mismatch", "server_not_selected", "user_and_server_mismatch"],
  unknown: [
    // The Testbed input. Only with --require-acceptance (without it such an input is ignored): no file,
    // not a regular file read without a link, not a JSON object, another case or none. In both modes,
    // for an input of the case: readable by others, a field missing or of another format, another run
    // case than this job's.
    "input_missing", "input_unreadable", "input_invalid", "input_case_mismatch",
    "input_unprotected", "input_field_missing", "input_field_invalid", "input_run_case_mismatch",
    "login_mode_unsupported", // --require-acceptance in a mode that does not log in with the Android form
    "provider_unavailable", "os_facts_unavailable", "signer_unverified", // before the login window
    "start_read_missing", "start_read_failed", "start_not_signed_out", // the read on the sign-in page
    "receipt_echo_mismatch", "package_mismatch", "signer_mismatch", "process_changed", // any read
    "form_not_submitted", "no_login_receipt", "login_not_completed", // the form login itself
    "after_read_failed", "session_origin_not_app_login", "user_ref_invalid", "server_ref_invalid", // the read after login
    "not_finished", "record_invalidated", // the phase never finished; a later step invalidated the record
  ],
};
/** Whether verdict/reason is a pair of LOGIN_ACCEPTANCE_REASONS (strings only, own keys only). */
export const isLoginAcceptancePair = (verdict, reason) => typeof verdict === "string" && typeof reason === "string" &&
  Object.hasOwn(LOGIN_ACCEPTANCE_REASONS, verdict) && LOGIN_ACCEPTANCE_REASONS[verdict].includes(reason);
const RUN_CASE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ATTEMPT_ID = /^pla_[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{64}$/;
// Identity reads after login while the app has no server selected yet (the provider answers at
// most 16 per process; the read on the sign-in page is one more).
const AFTER_READS = 6;
const HERE = path.dirname(fileURLToPath(import.meta.url));

export class LoginWindowError extends Error {
  constructor(reason) { super(`login window: ${reason}`); this.loginWindow = true; this.reason = reason; }
}

export const runId = (env = process.env) => `${env.GITHUB_RUN_ID || "local"}:${env.GITHUB_RUN_ATTEMPT || "1"}`;
export const secretValues = (env = process.env) => [
  env.QA_TEST_PASSWORD,
  env.QA_TEST_EMAIL,
  env.QA_LOGIN_ACCEPTANCE_PASSWORD,
  env.QA_LOGIN_ACCEPTANCE_EMAIL,
].filter((v) => v && v.length >= 4);

// Every driver that would put text into a process argument (adb input text, agent-device type,
// the XCUITest file protocol) calls this first.
export function refuseSecret(value, env = process.env) {
  if (secretValues(env).some((secret) => String(value).includes(secret))) {
    throw new Error("refused: this driver has no safe path for a secret");
  }
}

// nodes: [{ text|label|value|hint, password, role }] from uiautomator or the iOS describe tree.
export function loginLike(nodes) {
  return nodes.some((n) => n.password === true || /secure/i.test(String(n.role || ""))
    || LOGIN_TEXT.test([n.text, n.desc, n.label, n.value, n.hint].filter(Boolean).join(" ")));
}

export function writeRecord(out, fields, env = process.env) {
  fs.mkdirSync(out, { recursive: true });
  const record = { contract: CONTRACT, run: runId(env), ...fields };
  fs.writeFileSync(path.join(out, RECORD), `${JSON.stringify(record, null, 2)}\n`);
  return record;
}

// The run binding of a login acceptance block (the Testbed input or a record's `acceptance`): the
// case and only those values that have the agreed format, so nothing else can travel through it.
function acceptanceBinding(block) {
  if (!block || typeof block !== "object" || block.case_id !== LOGIN_ACCEPTANCE_CASE) return null;
  return {
    case_id: LOGIN_ACCEPTANCE_CASE,
    run_case_id: typeof block.run_case_id === "string" && RUN_CASE.test(block.run_case_id) ? block.run_case_id : null,
    attempt: Number.isSafeInteger(block.attempt) && block.attempt >= 1 ? block.attempt : null,
  };
}

// A record that carried a login acceptance keeps its run binding; its verdict becomes unknown.
export function markUnknown(out, reason, env = process.env, acceptanceReason = "record_invalidated") {
  let mode = "unknown";
  let binding = null;
  try {
    const prior = JSON.parse(fs.readFileSync(path.join(out, RECORD), "utf8"));
    mode = prior.mode || mode;
    binding = acceptanceBinding(prior.acceptance);
  } catch {}
  const acceptance = binding ? { acceptance: { ...binding, verdict: "unknown", reason: acceptanceReason } } : {};
  return writeRecord(out, { mode, status: "unknown", login_end: null, reason, ...acceptance }, env);
}

export function readRecord(out, env = process.env) {
  let r;
  try { r = JSON.parse(fs.readFileSync(path.join(out, RECORD), "utf8")); } catch { return { ok: false, reason: "login record missing or unreadable" }; }
  return checkRecord(r, env);
}

/**
 * The login acceptance (#216) of the record in `out`, whatever the record's status, for the report to
 * Testbed (testbed_login_acceptance_result.mjs): null when there is no readable qa-login-record/v1 object
 * or it has no acceptance object; else { run: the record's run, acceptance: the object as written,
 * completed: whether readRecord accepts this same record as a completed password login, which with an
 * acceptance it does only for a pass (a pass counts only on such a record) }.
 */
export function readRecordAcceptance(out, env = process.env) {
  let r;
  try { r = JSON.parse(fs.readFileSync(path.join(out, RECORD), "utf8")); } catch { return null; }
  if (!r || typeof r !== "object" || Array.isArray(r) || r.contract !== CONTRACT) return null;
  const acceptance = r.acceptance;
  if (!acceptance || typeof acceptance !== "object" || Array.isArray(acceptance)) return null;
  let completed = false;
  try {
    completed = r.mode === "password" && r.status === "complete" && checkRecord(r, env).ok;
  } catch { /* A field readRecord cannot even read (an object for login_end): no completed login. */ }
  return { run: r.run, acceptance, completed };
}

// readRecord's rules for a parsed record.
function checkRecord(r, env) {
  if (!r || typeof r !== "object" || Array.isArray(r) || r.contract !== CONTRACT) return { ok: false, reason: "login record malformed" };
  if (r.run !== runId(env)) return { ok: false, reason: "login record belongs to another run" };
  if (r.mode === "password" && r.status === "complete" && THREADTIME.test(`${r.login_end} `)) {
    // A login acceptance on a complete record counts only as a pass (#216).
    if (r.acceptance === undefined || (r.acceptance?.case_id === LOGIN_ACCEPTANCE_CASE && r.acceptance.verdict === "pass")) return { ok: true, record: r };
    return { ok: false, reason: "login acceptance did not pass" };
  }
  if (r.mode === "none" && r.status === "no-login") return { ok: true, record: r };
  // Release settings lane (scenarios/release_settings_case.mjs): the release APK's own browser
  // login, then Testbed's "matched" for this run's receipt. exchange_at is the app's own
  // exchange line, login_end the device time at the match; nothing up to it is evidence.
  if (r.mode === "release-prelogin" && r.status === "matched" && THREADTIME.test(`${r.exchange_at} `) &&
      THREADTIME.test(`${r.login_end} `) && r.exchange_at <= r.login_end) return { ok: true, record: r };
  return { ok: false, reason: `login ${r.status || "status missing"}${r.reason ? `: ${r.reason}` : ""}` };
}

/**
 * Why a parsed Testbed input cannot be used for the login acceptance (#216), or null: another case
 * (or none), a field missing or empty, a field of another format, or another run case than this job's
 * (STAMP_RUN_CASE_ID, when the job has one). testbed_login_acceptance_input.mjs checks Testbed's answer
 * with the same rules before it writes the input file.
 */
export function testbedInputProblem(input, env = process.env) {
  const binding = input && typeof input === "object" && !Array.isArray(input) ? acceptanceBinding(input) : null;
  if (!binding) return "input_case_mismatch";
  const target = input.expected_target && typeof input.expected_target === "object" ? input.expected_target : {};
  const fields = [input.run_case_id, input.attempt, input.attempt_id, input.nonce, target.user_id, target.server_id, target.server_slug];
  if (fields.some((value) => value === undefined || value === null || value === "")) return "input_field_missing";
  if (!binding.run_case_id || !binding.attempt || !ATTEMPT_ID.test(String(input.attempt_id)) || !HEX64.test(String(input.nonce)) ||
      [target.user_id, target.server_id, target.server_slug].some((value) => typeof value !== "string")) return "input_field_invalid";
  if (env.STAMP_RUN_CASE_ID && env.STAMP_RUN_CASE_ID !== input.run_case_id) return "input_run_case_mismatch";
  return null;
}

/**
 * Testbed's run-bound input for the login acceptance (#216): the JSON file given as --testbed-input
 * (qa-acceptance.yml: $RUNNER_TEMP/testbed-login-acceptance/input.json, which
 * testbed_login_acceptance_input.mjs writes 0600 from Testbed's answer), exactly:
 *   { "case_id": "login-email-password", "run_case_id": "<uuid>", "attempt": <Testbed's attempt number>,
 *     "attempt_id": "pla_<32 hex>", "nonce": "<64 hex>",
 *     "expected_target": { "user_id": "...", "server_id": "...", "server_slug": "..." } }
 * The pre-emulator account check can read it without consuming it. The login phase deletes it
 * immediately after reading, whatever it holds. Neither reader follows a link.
 * Without `required`, only an input that explicitly selects case_id "login-email-password" turns the
 * acceptance on; anything else (no file, unreadable, not a JSON object, another case) returns null and
 * the login phase runs as it does without one. With `required` (--require-acceptance: the job's
 * claimed Testbed case is login-email-password) each of those is a problem instead, bound to this job's
 * run case: the verdict is unknown and the phase ends without logging in.
 * Returns null, { binding, problem } when the input cannot be used, or { binding, problem: null, the
 * request ids, the expected refs (computed from the same nonce the reads send, as the app does) and the
 * slug of the expected server }; the raw ids end here.
 */
export function readTestbedInput(file, env = process.env, { required = false, consume = true } = {}) {
  let text = null;
  let state = "missing";
  let guarded = false;
  if (file) {
    try {
      const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
      try {
        const stat = fs.fstatSync(fd);
        state = stat.isFile() ? "read" : "unreadable";
        if (stat.isFile()) {
          text = fs.readFileSync(fd, "utf8");
          guarded = (stat.mode & 0o077) === 0 && (typeof process.getuid !== "function" || stat.uid === process.getuid());
        }
      } finally {
        fs.closeSync(fd);
      }
    } catch (error) {
      state = error?.code === "ENOENT" ? "missing" : "unreadable"; // a link (ELOOP), a directory, no permission
    }
    if (consume) {
      try { fs.rmSync(file, { force: true }); } catch { /* The job also removes the private directory. */ }
    }
  }
  const unusable = (problem) => (required ? { binding: acceptanceBinding({ case_id: LOGIN_ACCEPTANCE_CASE, run_case_id: env.STAMP_RUN_CASE_ID }), problem } : null);
  if (state !== "read") return unusable(state === "missing" ? "input_missing" : "input_unreadable");
  let input;
  try { input = JSON.parse(text); } catch { return unusable("input_invalid"); }
  if (!input || typeof input !== "object" || Array.isArray(input)) return unusable("input_invalid");
  const binding = acceptanceBinding(input);
  if (!binding) return unusable("input_case_mismatch");
  const problem = guarded ? testbedInputProblem(input, env) : "input_unprotected";
  if (problem) return { binding, problem };
  const target = input.expected_target;
  return {
    binding, problem: null, attemptId: input.attempt_id, nonce: input.nonce, serverSlug: target.server_slug,
    expected: { userRef: userRef(input.nonce, target.user_id), serverRef: serverRef(input.nonce, target.server_id, target.server_slug) },
  };
}

// uiautomator XML -> nodes. Read from /dev/tty: the tree (which holds what was typed) never
// lands in a file on the device.
export function parseUiNodes(xml) {
  return [...String(xml).matchAll(/<node[^>]*>/g)].map(([tag]) => {
    const attr = (k) => new RegExp(`\\s${k}="([^"]*)"`).exec(tag)?.[1] ?? "";
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    return {
      id: attr("resource-id"), focused: attr("focused") === "true",
      text: attr("text"), desc: attr("content-desc"), hint: attr("hint"), cls: attr("class"), pkg: attr("package"),
      password: attr("password") === "true", checkable: attr("checkable") === "true", checked: attr("checked") === "true",
      enabled: attr("enabled") !== "false", editable: /EditText/.test(attr("class")),
      bounds: b ? [+b[1], +b[2], +b[3], +b[4]] : null,
    };
  });
}

// The app's own verdict (AppLogin.kt, as parseAppLoginComplete): { stamp } of the exchange line,
// {} while nothing or only `retry` was logged, { refused } for any other result.
export function parseReceipt(logText) {
  const lines = String(logText).split("\n").filter((l) => RECEIPT.test(l));
  const exchanged = lines.findLast((l) => / result=app_shell reason=exchanged\b/.test(l));
  if (exchanged) {
    const stamp = THREADTIME.exec(exchanged)?.[1];
    return stamp ? { stamp } : { refused: "receipt without a device timestamp" };
  }
  const last = lines.at(-1);
  if (!last) return {};
  const [, result, reason] = RECEIPT.exec(last);
  return result === "retry" ? {} : { refused: `${result}/${reason}` };
}

// ---- Login acceptance (#216): the app's identity receipt ----------------------------------------

const empty = (value) => value === null || value === "";
// The receipt of a signed-out app: no session, no account ref, no server ref.
const signedOut = (receipt) => receipt.signed_in === false && empty(receipt.session_origin) && empty(receipt.user_ref) && empty(receipt.server_ref);

// One read, cross-checked as the release lane does (crossCheckReceipt) for what this lane checks:
// the echo of this request, the installed package and certificate as the OS has them (never the
// app's word), and one OS process around the call. null when all hold.
function identityReadProblem({ receipt, os: proc }, request, facts) {
  if (receipt.attempt_id !== request.attemptId || receipt.nonce !== request.nonce || receipt.request_id !== request.requestId) return "receipt_echo_mismatch";
  if (receipt.package !== facts.packageName || String(receipt.version_code) !== facts.versionCode) return "package_mismatch";
  if (!HEX64.test(receipt.signer_sha256 ?? "") || receipt.signer_sha256 !== facts.signerSha256) return "signer_mismatch";
  const samePid = proc.pidBefore != null && String(receipt.pid) === String(proc.pidBefore) && String(proc.pidBefore) === String(proc.pidAfter);
  const sameStart = proc.startTicksBefore != null && receipt.process_start_ticks === proc.startTicksBefore && proc.startTicksBefore === proc.startTicksAfter;
  return samePid && sameStart ? null : "process_changed";
}

// The after-login refs in the library's ref format (64 lowercase hex), checked before any comparison:
// a signed-in app always has a user ref; a server ref is empty until a server is selected and
// well-formed otherwise. A code when either is not, else null.
const refProblem = (receipt) => (!HEX64.test(receipt.user_ref ?? "") ? "user_ref_invalid"
  : !empty(receipt.server_ref) && !HEX64.test(receipt.server_ref) ? "server_ref_invalid" : null);

// The verdict on the after-login receipt (every read valid, one process, refs well-formed). Only two
// valid refs that differ are a mismatch; no server ref after the selection step is server_not_selected.
function acceptanceVerdict(receipt, expected) {
  if (empty(receipt.server_ref)) return { verdict: "fail", code: "server_not_selected" };
  const user = receipt.user_ref === expected.userRef;
  const server = receipt.server_ref === expected.serverRef;
  if (user && server) return { verdict: "pass", code: "signed_in_as_expected" };
  return { verdict: "fail", code: user ? "server_mismatch" : server ? "user_mismatch" : "user_and_server_mismatch" };
}

// What the OS says about the installed package, never the app: its versionCode, and the certificate a
// released platform of the device's SDK uses for the installed base.apk (AppIdentityApkSigner.java on
// apksig of build-tools 36.0.0, as the release lane). What cannot be read stays null.
function installedPackageFacts(adb, pkg, env) {
  const versionCode = /versionCode=([1-9][0-9]{0,19})\b/.exec(adb("shell", "dumpsys", "package", pkg))?.[1] ?? null;
  const facts = { packageName: pkg, versionCode, signerSha256: null };
  const sdkText = adb("shell", "getprop", "ro.build.version.sdk").trim();
  const sdk = /^[1-9][0-9]*$/.test(sdkText) ? Number(sdkText) : null;
  const released = adb("shell", "getprop", "ro.build.version.codename").trim() === "REL";
  const base = adb("shell", "pm", "path", pkg).split("\n").map((l) => l.trim()).filter((l) => l.startsWith("package:"))
    .map((l) => l.slice("package:".length)).find((p) => p.endsWith("/base.apk"));
  const sdkRoot = env.ANDROID_HOME || env.ANDROID_SDK_ROOT;
  const jar = sdkRoot ? path.join(sdkRoot, "build-tools", APKSIG_BUILD_TOOLS, "lib", "apksigner.jar") : "";
  if (!versionCode || !sdk || !released || !base || !jar || !fs.existsSync(jar)) return facts;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "login-identity-apk-"));
  try {
    const apk = path.join(dir, "base.apk");
    adb("pull", base, apk);
    const inspector = spawnSync("java", apkSignerInspectorArgs({ apksignerJar: jar, script: path.join(HERE, "AppIdentityApkSigner.java"), apk, sdk }),
      { encoding: "utf8", timeout: 120000, env });
    facts.signerSha256 = parseApkSignerInspector(inspector.stdout, { status: inspector.status, sdk });
  } catch {
    facts.signerSha256 = null;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  return facts;
}

const CHROME = "com.android.chrome";
const SAVE_PROMPT_BLOCKED = "Chrome's save-password prompt is blocking the return to the app";
const FORM_JAR = "qa-login-form.jar";
const APP_REFUSALS = new Set([
  "ignored/no_pending", "ignored/return_uri_mismatch", "stale/request_id_mismatch",
  "login_error/environment_changed", "login_error/expired", "login_error/verifier_invalid",
  "login_error/callback_error", "login_error/callback_no_code", "login_error/exchange_failed",
  "receipt without a device timestamp",
]);
const DEVICE_JAR = `/data/local/tmp/${FORM_JAR}`;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const CHROME_DISMISS = /^(Use (?:Chrome )?without an account|No,? thanks|Not now|Skip)$/i;
const CHROME_CONTINUE = /^(Accept & continue|Got it|Continue)$/i;
const SYSTEM_RETURN = /^(Open|Always|Just once)$/i;
const labelIs = (node, re) => [node.text, node.desc].some((value) => re.test(value.replaceAll("&amp;", "&").trim()));
const FORM_MESSAGES = new Map([
  ["Invalid email format", "invalid-email"], ["Password is required", "missing-password"],
  ["Sign-in failed", "sign-in-failed"], ["Incorrect email or password.", "incorrect-credentials"],
  ["This sign-in request has expired. Start sign-in again from the app.", "request-expired"],
  ["This sign-in request was already handled. Start sign-in again from the app if needed.", "request-already-handled"],
  ["This sign-in link isn't valid. Start sign-in again from the app.", "request-invalid"],
]);
const visible = (node) => node.bounds && node.bounds[2] > node.bounds[0] && node.bounds[3] > node.bounds[1];

function loginControls(nodes, pkg) {
  const screen = nodes.filter(visible);
  const chrome = screen.filter((n) => n.pkg === CHROME);
  const chromeSetup = chrome.filter((n) => n.enabled && n.id.startsWith(`${CHROME}:id/`));
  const button = (label) => chrome.find((n) => n.cls === "android.widget.Button" && !n.id.startsWith(`${CHROME}:id/`) && labelIs(n, label));
  const saveTitle = chromeSetup.find((n) => labelIs(n, /^(Save (?:this )?password(?: to Google(?: Password Manager)?)?\??)$/i));
  const systemDialog = screen.filter((n) => n.pkg === "android");
  const errorDialog = systemDialog.some((n) => n.id === "android:id/aerr_close");
  const waitControl = systemDialog.some((n) => n.id === "android:id/aerr_wait");
  const errorSurface = waitControl ? "system-app-anr" : "system-app-error";
  const systemBlocker = errorDialog && waitControl && detectForeignAndroidSystemBlocker(systemDialog.map((n) => ({ ...n, resourceId: n.id })));
  return {
    systemBlocker: systemBlocker?.code,
    surface: errorDialog ? errorSurface : chrome.length ? "chrome" : screen.some((n) => n.pkg === pkg) ? "app" : screen.length ? "other" : "unreadable",
    savePasswordPrompt: Boolean(saveTitle),
    dismissSave: saveTitle && saveTitle.id !== `${CHROME}:id/message_title` && chromeSetup.find((n) =>
      n.id === `${CHROME}:id/negative_button` && n.cls === "android.widget.Button" && labelIs(n, /^(Never|Not now|No,? thanks|Cancel|Dismiss)$/i)),
    passwordInputVisible: chrome.some((n) => n.editable && n.password),
    message: chrome.map((n) => FORM_MESSAGES.get(n.text) || FORM_MESSAGES.get(n.desc)).find(Boolean) || "none-recognized",
    consent: screen.find((n) => n.pkg === pkg && n.checkable && !n.checked && n.enabled),
    launch: screen.find((n) => n.pkg === pkg && n.enabled && labelIs(n, /^Sign in to Raft$/i)),
    setup: chromeSetup.find((n) => labelIs(n, CHROME_DISMISS)) || chromeSetup.find((n) => labelIs(n, CHROME_CONTINUE)),
    email: chrome.find((n) => n.id === "login-email" && n.editable && !n.password),
    password: chrome.find((n) => n.id === "login-password" && n.editable && n.password),
    submit: button(/^Sign in$/i),
    confirm: button(/^Confirm sign-in$/i),
    openApp: button(/^Open the app$/i),
    systemReturn: screen.find((n) => ["android", "com.android.intentresolver"].includes(n.pkg) && n.enabled && labelIs(n, SYSTEM_RETURN)),
  };
}

// Only these literals cross the protected window. No node text, lengths, URLs, identifiers,
// coordinates or log lines are copied. The normal evidence gate is still closed on failure.
function failureUi(controls, lastAction, reason) {
  const state = (n) => !n ? "absent" : !n.enabled ? "disabled" : n.focused ? "focused" : "visible";
  const dump = { format: "android-login-ui/v1", lastAction, reason, surface: controls?.surface ?? "unobserved" };
  if (controls) {
    dump.message = controls.message;
    if (controls.affectedProcess) dump.affectedProcess = controls.affectedProcess;
    if (controls.systemBlocker) dump.systemBlocker = controls.systemBlocker;
    dump.savePasswordPrompt = controls.savePasswordPrompt;
    dump.passwordInputVisible = controls.passwordInputVisible;
    for (const key of ["consent", "launch", "setup", "email", "password", "submit", "confirm", "openApp", "systemReturn"]) dump[key] = state(controls[key]);
  }
  return dump;
}

function loginOrigin(env) {
  const api = (env.QA_API_BASE || ENVIRONMENTS.production.apiBaseUrl).replace(/\/$/, "");
  return Object.values(ENVIRONMENTS).find((e) => e.apiBaseUrl.replace(/\/$/, "") === api)?.webUrl;
}

// Chrome elides schemes in its toolbar. Corroborate it with the actual origin of tabs at the displayed host
// on this job emulator. Neither URLs nor debugging data leave this process.
async function chromeAtOrigin(adb, origin) {
  const port = adb("forward", "tcp:0", "localabstract:chrome_devtools_remote").trim();
  if (!/^[1-9]\d{0,4}$/.test(port) || Number(port) > 65535) return false;
  try {
    const res = await fetch(`http://127.0.0.1:${port}/json/list`, { redirect: "error", signal: AbortSignal.timeout(3000) });
    if (!res.ok) return false;
    const host = new URL(origin).hostname;
    const atHost = (await res.json()).filter((tab) => tab.type === "page")
      .map((tab) => new URL(tab.url)).filter((url) => url.hostname === host);
    return atHost.length > 0 && atHost.every((url) => url.origin === origin);
  } catch {
    return false;
  } finally {
    adb("forward", "--remove", `tcp:${port}`);
  }
}

function androidErrorOwner(adb, pkg) {
  // Android names the affected process in the focused framework dialog's window title.
  // An arbitrary process name or an unrelated foreground application must never enter the report.
  let focus;
  try { focus = adb("shell", "dumpsys", "window").split("\n").find((l) => l.includes("mCurrentFocus=Window{")); } catch { return "unknown"; }
  const owner = / u\d+ Application (?:Not Responding|Error): ([A-Za-z][\w.:_-]*)\}/.exec(focus ?? "")?.[1];
  if (!owner) return "unknown";
  if (owner === pkg || owner.startsWith(`${pkg}:`)) return "app";
  if (owner === CHROME || owner.startsWith(`${CHROME}:`)) return "chrome";
  if (owner === "com.android.systemui") return "system-ui";
  if (owner === "system" || owner === "system_server") return "android-system";
  if (owner === "com.google.android.apps.nexuslauncher") return "pixel-launcher";
  return "other";
}

function clearLoginStores(adb) {
  let ok = true;
  let ime;
  try { ime = adb("shell", "settings", "get", "secure", "default_input_method").trim().split("/")[0]; } catch { /* Still clear Chrome. */ }
  for (const target of [CHROME, ime]) {
    if (!target || !/^[A-Za-z][\w]*(?:\.[A-Za-z][\w]*)+$/.test(target)) { ok = false; continue; }
    try { if (adb("shell", "pm", "clear", target).trim() !== "Success") ok = false; } catch { ok = false; }
  }
  return ok;
}

// Reads a runner-owned 0600 two-line credential file (email\npassword\n) staged for a single login
// invocation (same pattern as release_auto_login.mjs). Opens with O_NOFOLLOW to reject symlinks
// atomically, checks regular file + owner + mode 0600, and when `consume` is true always removes
// the file in `finally` even on error.
export function readCredentialFile(filePath, { consume = false } = {}) {
  const remove = () => {
    if (!consume || !filePath) return;
    try { fs.rmSync(filePath, { force: true }); } catch { /* Best-effort cleanup. */ }
  };
  try {
    if (!filePath) return { email: "", password: "", problem: "credential_file_missing" };
    let fd;
    try {
      fd = fs.openSync(filePath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    } catch (error) {
      return { email: "", password: "", problem: error?.code === "ENOENT" ? "credential_file_missing" : "credential_file_unreadable" };
    }
    let text;
    try {
      const stat = fs.fstatSync(fd);
      if (!stat.isFile()) return { email: "", password: "", problem: "credential_file_unreadable" };
      if ((stat.mode & 0o077) !== 0 || (typeof process.getuid === "function" && stat.uid !== process.getuid())) {
        return { email: "", password: "", problem: "credential_file_unprotected" };
      }
      text = fs.readFileSync(fd, "utf8");
    } catch {
      return { email: "", password: "", problem: "credential_file_unreadable" };
    } finally {
      fs.closeSync(fd);
    }
    try {
      const { email, password } = parseCredentialFile(text);
      return { email, password, problem: null };
    } catch {
      return { email: "", password: "", problem: "credential_file_invalid" };
    }
  } finally {
    remove();
  }
}

// `acceptance`: readTestbedInput's result (#216); null runs the login exactly as without it.
// `credentials`: optional result of readCredentialFile() when staged via --credential-file.
export async function androidPasswordLogin({ out, pkg = "build.raft.app", device = "", env = process.env, timeoutMs = 180000, pollMs = 2000, expectedOrigin = loginOrigin(env), acceptance = null, credentials = null, chooserTimeoutMs = 30000 }) {
  const {
    QA_TEST_EMAIL: legacyEmail,
    QA_TEST_PASSWORD: legacyPassword,
    QA_LOGIN_ACCEPTANCE_EMAIL: _unusedAcceptanceEmail,
    QA_LOGIN_ACCEPTANCE_PASSWORD: _unusedAcceptancePassword,
    ...adbEnv
  } = env;
  // #216: login-email-password takes runtime credentials only via --credential-file, never QA_TEST_* or legacy env secrets.
  const email = credentials ? credentials.email : (acceptance ? "" : legacyEmail);
  const password = credentials ? credentials.password : (acceptance ? "" : legacyPassword);
  let serial = device;
  const execAdb = (args, input) => execFileSync("adb", [...(serial ? ["-s", serial] : []), ...args], {
    env: adbEnv, encoding: "utf8", timeout: 60000, input, stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
  });
  const adb = (...args) => execAdb(args);
  const recorderGone = () => /rc=1\s*$/.test(adb("shell", "pidof screenrecord; echo rc=$?"));
  const tap = (n) => adb("shell", "input", "tap", String((n.bounds[0] + n.bounds[2]) >> 1), String((n.bounds[1] + n.bounds[3]) >> 1));
  let lastAction = "before-form";
  let controls;
  let installedDriver = false;
  let inputMayRemain = false;
  const owned = () => adb("shell", `cat ${OWNER_MARKER} 2>/dev/null`).trim() === runId(env);
  // `code`: the acceptance verdict's reason when this attempt runs the login acceptance.
  const unknown = (reason, code = "login_not_completed") => {
    markUnknown(out, reason, env, code);
    const dump = failureUi(controls, lastAction, reason);
    console.log(`Protected login UI: ${JSON.stringify(dump)}`);
    if (env.GITHUB_STEP_SUMMARY) {
      try { fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `\n### Android login failure — last UI observation\n\n\`\`\`json\n${JSON.stringify(dump, null, 2)}\n\`\`\`\n`); } catch { /* The same dump is in the job log. */ }
    }
    return { ok: false, reason, ...(acceptance ? { acceptance: { verdict: "unknown", reason: code } } : {}) };
  };

  // #216 login acceptance: reads of the app's identity receipt. The command carrying the nonce goes
  // over adb's stdin; receipts stay in this process.
  const adbResult = (args, input) => {
    const r = spawnSync("adb", [...(serial ? ["-s", serial] : []), ...args], {
      env: adbEnv, encoding: "utf8", timeout: 60000, input, stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
    });
    return { status: r.status ?? 1, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
  };
  let identity = null;
  let startReceipt = null;
  // Before the login window, on this run's own emulator: the provider as the package ships it (enabling
  // a disabled one kills the app, which is already on screen) and the OS facts every read is checked with.
  const prepareIdentity = () => {
    let runner;
    try {
      runner = createIdentityReceiptRunner({ pkg, shell: (command) => adbResult(["shell", command]), shellPrivate: (command) => adbResult(["shell", "-T"], `${command}; exit\n`) });
      runner.probe();
    } catch {
      return { problem: "provider_unavailable" };
    }
    if (runner.state.startState !== "enabled_by_package") return { problem: "provider_unavailable" };
    let facts;
    try { facts = installedPackageFacts(adb, pkg, adbEnv); } catch { return { problem: "os_facts_unavailable" }; }
    if (!facts.versionCode) return { problem: "os_facts_unavailable" };
    if (!facts.signerSha256) return { problem: "signer_unverified" };
    return { runner, facts };
  };
  // One read: Testbed's attempt id and nonce, a new request id. { receipt } or { problem }.
  const readIdentity = (failed) => {
    let request;
    let answer;
    try {
      request = newReceiptRequest({ attemptId: acceptance.attemptId, nonce: acceptance.nonce });
      answer = identity.runner.call(request);
    } catch {
      return { problem: failed };
    }
    if (answer.error) return { problem: failed };
    const problem = identityReadProblem(answer, request, identity.facts);
    return problem ? { problem } : { receipt: answer.receipt };
  };
  // A fresh device lands on the server chooser after login (no server selected yet). Its rows read
  // "/<slug>"; tap the one row of the expected server, nothing else (never "Sign out").
  const chooseExpectedServer = async () => {
    lastAction = "choose-expected-server";
    const row = `/${acceptance.serverSlug}`;
    for (const deadline = Date.now() + chooserTimeoutMs; ; await sleep(pollMs)) {
      const rows = parseUiNodes(adb("exec-out", "uiautomator", "dump", "/dev/tty"))
        .filter((n) => visible(n) && n.pkg === pkg && n.enabled && (n.text === row || n.desc === row));
      if (rows.length === 1) {
        tap(rows[0]);
        return true;
      }
      if (rows.length > 1 || Date.now() > deadline) return false;
    }
  };
  // After login, same app process as the read on the sign-in page: signed in through the app's own
  // login, then the expected account and server.
  const afterLoginIdentity = async () => {
    lastAction = "verify-app-identity";
    let choseServer = false;
    for (let reads = 1; ; reads++) {
      const after = readIdentity("after_read_failed");
      if (!after.receipt) return { verdict: "unknown", code: after.problem };
      const r = after.receipt;
      if (String(r.pid) !== String(startReceipt.pid) || r.process_start_ticks !== startReceipt.process_start_ticks) return { verdict: "unknown", code: "process_changed" };
      if (r.signed_in !== true || r.session_origin !== "app_login") return { verdict: "unknown", code: "session_origin_not_app_login" };
      const malformed = refProblem(r);
      if (malformed) return { verdict: "unknown", code: malformed };
      if (!empty(r.server_ref) || reads >= AFTER_READS) return acceptanceVerdict(r, acceptance.expected);
      if (!choseServer) {
        choseServer = true;
        if (!await chooseExpectedServer()) return acceptanceVerdict(r, acceptance.expected);
      }
      await sleep(pollMs);
    }
  };

  writeRecord(out, { mode: "password", status: "unknown", login_end: null, reason: "login phase started and did not finish",
    ...(acceptance ? { acceptance: { ...acceptance.binding, verdict: "unknown", reason: "not_finished" } } : {}) }, env);
  try {
    if (acceptance?.problem) return unknown(`login acceptance unknown: ${acceptance.problem}`, acceptance.problem);
    if (credentials?.problem) {
      return unknown(`login acceptance credential file error: ${credentials.problem}`);
    }
    if (!email || !password) {
      return unknown(acceptance ? "login acceptance credentials missing" : "QA credentials missing");
    }
    if ([email, password].some((value) => /[^!-~]/.test(value))) return unknown("unsupported credential format");
    if (!expectedOrigin) return unknown("unknown QA account environment");
    serial ||= adb("get-serialno").trim();
    if (!/^emulator-\d+$/.test(serial)) return unknown("not an emulator; cannot tell the device is this run's own");
    if (!owned()) return unknown("cannot tell the emulator is this run's own");
    adb("shell", "pkill -INT screenrecord; true");
    for (let i = 0; i < 5 && !recorderGone(); i++) await sleep(1000);
    if (!recorderGone()) return unknown("screen recorder did not stop before the login window");
    adb("logcat", "-c");
    adb("shell", "settings", "put", "secure", "autofill_service", "null");
    if (acceptance) {
      lastAction = "prepare-app-identity";
      identity = prepareIdentity();
      if (identity.problem) return unknown(`login acceptance unknown: ${identity.problem}`, identity.problem);
    }

    let filled = false;
    let submittedAt = 0;
    const followed = new Set();
    let confirmAfterDismissal = false;
    let savePromptSince = 0;
    let tappedSaveDismissal = false;
    let stamp;
    for (const deadline = Date.now() + timeoutMs; !stamp; await sleep(pollMs)) {
      const receipt = parseReceipt(adb("logcat", "-d", "-v", "threadtime", "-s", "SlockAppLogin:I"));
      if (receipt.refused) {
        return unknown(APP_REFUSALS.has(receipt.refused) ? `app refused the login (${receipt.refused})` : "unrecognized app login refusal");
      }
      if (receipt.stamp) {
        if (!submittedAt) return unknown("login receipt before this form was submitted", "form_not_submitted");
        stamp = receipt.stamp;
        break;
      }
      // If the read throws, the failure dump must not present the preceding screen as current.
      controls = undefined;
      controls = loginControls(parseUiNodes(adb("exec-out", "uiautomator", "dump", "/dev/tty")), pkg);
      if (controls.surface === "system-app-error" || controls.surface === "system-app-anr") {
        controls.affectedProcess = androidErrorOwner(adb, pkg);
        return unknown(controls.surface === "system-app-anr" ? "Android is showing an app-not-responding dialog" : "Android is showing an app error dialog");
      }
      if (Date.now() > deadline) return unknown(submittedAt && controls.savePasswordPrompt ? SAVE_PROMPT_BLOCKED : "no login-complete receipt", "no_login_receipt");
      if (submittedAt) {
        if (controls.savePasswordPrompt) {
          if (!savePromptSince) {
            savePromptSince = Date.now();
            confirmAfterDismissal = followed.has("confirm-sign-in");
            lastAction = "wait-save-password";
          }
          // Chrome's message banner offers Save and a settings menu. It dismisses itself.
          // Leave both the banner and the page underneath untouched until a fresh observation clears it.
          if (Date.now() - savePromptSince >= 45000) return unknown(SAVE_PROMPT_BLOCKED, "no_login_receipt");
          if (!tappedSaveDismissal && controls.dismissSave) {
            lastAction = "dismiss-save-password";
            tap(controls.dismissSave);
            tappedSaveDismissal = true;
          }
          continue;
        }
        if (controls.password && Date.now() - submittedAt > 20000) return unknown("the login form is still there after submitting");
        const next = [["confirm-sign-in", controls.confirm], ["open-app", controls.openApp], ["return-to-app", controls.systemReturn]]
          .find(([, node]) => node?.enabled);
        if (!controls.password && next) {
          const [action, node] = next;
          // Retry confirmation only when a known, dismissed native prompt intercepted the first tap.
          if (!followed.has(action) || (action === "confirm-sign-in" && confirmAfterDismissal)) {
            confirmAfterDismissal = false;
            lastAction = action;
            followed.add(action);
            tap(node);
          }
        }
        continue;
      }
      if (filled) {
        if (controls.submit?.enabled && controls.password) {
          lastAction = "submit-form";
          tap(controls.submit);
          submittedAt = Date.now();
        }
        continue;
      }
      if (controls.email?.enabled && controls.password?.enabled) {
        // #216: no secret is typed before the app's own receipt said signed out.
        if (identity && !startReceipt) return unknown("login acceptance unknown: start_read_missing", "start_read_missing");
        lastAction = "fill-email";
        if (!env.RUNNER_TEMP || !fs.existsSync(path.join(env.RUNNER_TEMP, FORM_JAR))) return unknown("protected login form driver missing");
        if (!await chromeAtOrigin(adb, expectedOrigin)) return unknown("Chrome could not confirm the login page origin");
        adb("push", path.join(env.RUNNER_TEMP, FORM_JAR), DEVICE_JAR);
        installedDriver = true;
        adb("shell", "chmod", "444", DEVICE_JAR);
        inputMayRemain = true;
        const result = execAdb(["shell", "-T", "uiautomator", "runtest", "/system/framework/android.test.base.jar", "/system/framework/android.test.runner.jar", DEVICE_JAR,
          "-c", "RaftQaLoginForm#testFill", "-e", "origin", expectedOrigin], `${email}\n${password}\n`);
        if (/^QA_LOGIN_SITE_UNVERIFIED\r?$/m.test(result)) return unknown("the browser address could not be matched to the QA environment");
        if (!/^QA_LOGIN_EMAIL_SET\r?$/m.test(result)) return unknown("could not fill the expected browser email field");
        lastAction = "fill-password";
        if (!/^QA_LOGIN_PASSWORD_SET\r?$/m.test(result)) return unknown("could not fill the expected browser password field");
        filled = true;
        continue;
      }
      // #216: the app's sign-in page is up and nothing was tapped yet: its own receipt must say signed out.
      if (identity && !startReceipt && (controls.consent || controls.launch)) {
        lastAction = "read-start-identity";
        const start = readIdentity("start_read_failed");
        if (!start.receipt) return unknown(`login acceptance unknown: ${start.problem}`, start.problem);
        if (!signedOut(start.receipt)) return unknown("login acceptance unknown: start_not_signed_out", "start_not_signed_out");
        startReceipt = start.receipt;
      }
      const next = controls.consent || controls.launch || controls.setup;
      if (next) {
        lastAction = next === controls.consent ? "app-consent" : next === controls.launch ? "launch-browser" : "browser-setup";
        tap(next);
      }
    }

    if (!owned() || !clearLoginStores(adb)) return unknown("could not clear the browser or keyboard on the owned emulator");
    inputMayRemain = false;
    lastAction = "verify-app-focus";
    const appFocused = () => (/mCurrentFocus=Window\{[^}]*\}/.exec(adb("shell", "dumpsys", "window"))?.[0] ?? "").includes(` ${pkg}/`);
    // Android can log the callback before it finishes handing the window back from Chrome.
    const focusDeadline = Date.now() + 10000;
    while (!appFocused()) {
      if (Date.now() >= focusDeadline) {
        controls = loginControls(parseUiNodes(adb("exec-out", "uiautomator", "dump", "/dev/tty")), pkg);
        return unknown("the app is not in front after login");
      }
      await sleep(250);
    }
    // #216: still before any capture (the recorder check below covers this step too).
    let verdict = null;
    if (identity) {
      controls = undefined;
      verdict = await afterLoginIdentity();
    }
    if (!recorderGone()) return unknown("a screen recorder ran during the login window");
    if (verdict?.verdict === "unknown") return unknown(`login acceptance unknown: ${verdict.code}`, verdict.code);
    if (verdict?.verdict === "fail") {
      // A valid answer, the wrong account or server: nothing is captured for it either.
      const reason = `login acceptance fail: ${verdict.code}`;
      writeRecord(out, { mode: "password", status: "unknown", login_end: null, reason, acceptance: { ...acceptance.binding, verdict: "fail", reason: verdict.code } }, env);
      return { ok: false, reason, acceptance: { verdict: "fail", reason: verdict.code } };
    }
    const passed = verdict ? { acceptance: { ...acceptance.binding, verdict: "pass", reason: verdict.code } } : {};
    writeRecord(out, { mode: "password", status: "complete", login_end: stamp, reason: "app_login_complete result=app_shell reason=exchanged", ...passed }, env);
    return { ok: true, stamp, ...(verdict ? { acceptance: { verdict: "pass", reason: verdict.code } } : {}) };
  } catch {
    return unknown("login probe failed");
  } finally {
    if (installedDriver) {
      let cleared = false;
      try {
        if (owned()) {
          if (inputMayRemain) cleared = clearLoginStores(adb);
          adb("shell", "rm", "-f", DEVICE_JAR);
        }
      } catch { /* The job discards its emulator. */ }
      if (inputMayRemain) console.log(`Protected login local cleanup: ${cleared ? "cleared" : "unconfirmed"}`);
    }
  }
}

async function main() {
  const argv = process.argv.slice(2);
  const arg = (f, d = "") => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
  const out = arg("--out", "qa-evidence");
  if (argv[0] === "check") {
    const r = readRecord(out);
    if (!r.ok) console.error(`login record not OK: ${r.reason}`);
    process.exit(r.ok ? 0 : 1);
  }
  if (argv[0] !== "login") { console.error("usage: login_phase.mjs login|check ..."); process.exit(2); }
  // --require-acceptance login-email-password: the job's claimed Testbed case (qa-acceptance.yml sets it
  // for that case only) makes the acceptance mandatory. No usable input, or a mode that does not log in
  // with the Android form, then ends unknown without logging in; never a pass, never the ordinary login.
  const required = argv.includes("--require-acceptance");
  if (required && arg("--require-acceptance") !== LOGIN_ACCEPTANCE_CASE) { console.error(`--require-acceptance takes only ${LOGIN_ACCEPTANCE_CASE}`); process.exit(2); }
  // Testbed's run-bound input (#216): read and deleted first, in every mode; only Android password
  // login runs the acceptance it may select. The staged credential file is likewise read and
  // removed immediately on entry so early exits never leave it on disk.
  const testbedInput = arg("--testbed-input");
  const acceptance = testbedInput || required ? readTestbedInput(testbedInput, process.env, { required }) : null;
  const credentialFile = arg("--credential-file");
  const consumeCredentialFile = argv.includes("--consume-credential-file");
  const credentials = credentialFile ? readCredentialFile(credentialFile, { consume: consumeCredentialFile }) : null;
  const platform = arg("--platform", "android");
  const passwordLogin = arg("--password-login");
  if (!["true", "false"].includes(passwordLogin)) { console.error("--password-login true|false is required"); process.exit(2); }
  const unsupported = (mode) => {
    const reason = "login acceptance unknown: login_mode_unsupported";
    writeRecord(out, { mode, status: "unknown", login_end: null, reason, acceptance: { ...acceptance.binding, verdict: "unknown", reason: "login_mode_unsupported" } });
    return { ok: false, reason, acceptance: { verdict: "unknown", reason: "login_mode_unsupported" } };
  };
  let result;
  if (passwordLogin === "false") {
    if (required) result = unsupported("none");
    else {
      writeRecord(out, { mode: "none", status: "no-login", login_end: null, reason: "this mode never signs in" });
      result = { ok: true };
    }
  } else if (platform === "android") {
    result = await androidPasswordLogin({ out, pkg: arg("--package", "build.raft.app"), device: arg("--device"), acceptance, credentials });
  } else if (required) {
    result = unsupported("password");
  } else {
    const reason = `${platform} has no safe way to type a secret; password login fails closed`;
    markUnknown(out, reason);
    result = { ok: false, reason };
  }
  console.log(`login phase: ${result.ok ? "OK" : `UNKNOWN (${result.reason})`}`);
  // The verdict and its reason code only: no id, ref or account value.
  if (result.acceptance) console.log(`login acceptance: ${result.acceptance.verdict} (${result.acceptance.reason})`);
  process.exit(result.ok ? 0 : 1);
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) await main();
