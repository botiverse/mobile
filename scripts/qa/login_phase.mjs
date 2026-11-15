#!/usr/bin/env node
// Login phase for the QA acceptance executors (#222). It runs on its own, BEFORE the
// screen recorder starts and before the agent loop takes its first screenshot, and writes
// qa-evidence/login-record.json. Three rules:
//
// 1. The secret never goes into a process argument or a file. Android types it through the
//    emulator console (`event text` over the console TCP socket from this process). Nothing
//    else has a safe path, so iOS and every driver refuse to type a secret (refuseSecret);
//    iOS legacy password login therefore ends UNKNOWN.
// 2. The login window captures nothing: no screenshot, no recording (the recorder is stopped
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
// Usage:
//   node scripts/qa/login_phase.mjs login --platform android|ios --password-login true|false \
//     --out qa-evidence [--package build.raft.app] [--device emulator-5554]
//   node scripts/qa/login_phase.mjs check --out qa-evidence    (exit 0 only for a valid record)
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

export const RECORD = "login-record.json";
const CONTRACT = "qa-login-record/v1";
export const THREADTIME = /^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s/;
// The app's own sign-in lines (WebLoginAttempt.kt, AppLogin.kt): a run that never signs in
// must not show them.
export const LOGIN_LOG_MARKERS = /\b(web_login_attempt|app_login_complete)\b/;
const LOGIN_TEXT = /\b(password|passcode|passkey|continue as|sign in with)\b/i;
const RECEIPT = /app_login_complete result=(\S+) reason=(\S+)/;
const OWNER_MARKER = "/data/local/tmp/qa-run-owner";

export class LoginWindowError extends Error {
  constructor(reason) { super(`login window: ${reason}`); this.loginWindow = true; this.reason = reason; }
}

export const runId = (env = process.env) => `${env.GITHUB_RUN_ID || "local"}:${env.GITHUB_RUN_ATTEMPT || "1"}`;
export const secretValues = (env = process.env) => [env.QA_TEST_PASSWORD, env.QA_TEST_EMAIL].filter((v) => v && v.length >= 4);

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

export function markUnknown(out, reason, env = process.env) {
  let mode = "unknown";
  try { mode = JSON.parse(fs.readFileSync(path.join(out, RECORD), "utf8")).mode || mode; } catch {}
  return writeRecord(out, { mode, status: "unknown", login_end: null, reason }, env);
}

export function readRecord(out, env = process.env) {
  let r;
  try { r = JSON.parse(fs.readFileSync(path.join(out, RECORD), "utf8")); } catch { return { ok: false, reason: "login record missing or unreadable" }; }
  if (!r || typeof r !== "object" || Array.isArray(r) || r.contract !== CONTRACT) return { ok: false, reason: "login record malformed" };
  if (r.run !== runId(env)) return { ok: false, reason: "login record belongs to another run" };
  if (r.mode === "password" && r.status === "complete" && THREADTIME.test(`${r.login_end} `)) return { ok: true, record: r };
  if (r.mode === "none" && r.status === "no-login") return { ok: true, record: r };
  return { ok: false, reason: `login ${r.status || "status missing"}${r.reason ? `: ${r.reason}` : ""}` };
}

// uiautomator XML -> nodes. Read from /dev/tty: the tree (which holds what was typed) never
// lands in a file on the device.
export function parseUiNodes(xml) {
  return [...String(xml).matchAll(/<node[^>]*>/g)].map(([tag]) => {
    const attr = (k) => new RegExp(`\\s${k}="([^"]*)"`).exec(tag)?.[1] ?? "";
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    return {
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

const CONSOLE_SAFE = /^[\x21-\x7e]+$/;
// Types text through the emulator console. Only printable ASCII without spaces: anything else
// could not be typed faithfully (or would split the console command), so it is refused.
export async function consoleType(port, text, { tokenFile = path.join(os.homedir(), ".emulator_console_auth_token"), timeoutMs = 15000 } = {}) {
  if (!CONSOLE_SAFE.test(text)) throw new Error("the console cannot type this value safely");
  const token = fs.existsSync(tokenFile) ? fs.readFileSync(tokenFile, "utf8").trim() : "";
  const sock = net.connect(Number(port), "127.0.0.1");
  sock.setTimeout(timeoutMs, () => sock.destroy(new Error("emulator console timed out")));
  let buf = "";
  let waiter = null;
  const settle = () => {
    const m = waiter && /(?:^|\n)(OK|KO[^\n]*)\r?\n/.exec(buf);
    if (!m) return;
    buf = buf.slice(m.index + m[0].length);
    const w = waiter; waiter = null;
    if (m[1] === "OK") w.resolve(); else w.reject(new Error("emulator console refused a command"));
  };
  const ok = () => new Promise((resolve, reject) => { waiter = { resolve, reject }; settle(); });
  sock.on("data", (d) => { buf += d; settle(); });
  sock.on("error", (e) => waiter?.reject(e));
  try {
    await ok(); // banner
    if (token) { sock.write(`auth ${token}\n`); await ok(); }
    sock.write(`event text ${text}\n`);
    await ok();
  } finally {
    sock.end("quit\n");
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const BUTTONS = /^(Sign in to Raft|Accept & continue|Use without an account|No thanks|Continue|Confirm|Approve|Allow|Open|Always|Just once)$/i;

export async function androidPasswordLogin({ out, pkg = "build.raft.app", device = "", env = process.env, timeoutMs = 180000, pollMs = 2000 }) {
  const base = device ? ["-s", device] : [];
  const adb = (...args) => execFileSync("adb", [...base, ...args], { env, encoding: "utf8", timeout: 60000, stdio: ["ignore", "pipe", "pipe"] });
  const unknown = (reason) => { markUnknown(out, reason, env); return { ok: false, reason }; };
  const recorderGone = () => /rc=1\s*$/.test(adb("shell", "pidof screenrecord; echo rc=$?"));
  const tap = (n) => adb("shell", "input", "tap", String((n.bounds[0] + n.bounds[2]) >> 1), String((n.bounds[1] + n.bounds[3]) >> 1));
  writeRecord(out, { mode: "password", status: "unknown", login_end: null, reason: "login phase started and did not finish" }, env);
  try {
    const [email, password] = [env.QA_TEST_EMAIL || "", env.QA_TEST_PASSWORD || ""];
    if (!email || !password) return unknown("QA credentials missing");
    if (!CONSOLE_SAFE.test(email) || !CONSOLE_SAFE.test(password)) return unknown("a credential the console cannot type faithfully");
    // Only on the CI emulator this job created: the workflow writes this run's id to the device
    // right after boot. Without it nothing on the device is changed and nothing is typed.
    const serial = device || adb("get-serialno").trim();
    const port = /^emulator-(\d+)$/.exec(serial)?.[1];
    if (!port) return unknown("not an emulator; cannot tell the device is this run's own");
    if (adb("shell", `cat ${OWNER_MARKER} 2>/dev/null`).trim() !== runId(env)) return unknown("cannot tell the emulator is this run's own");
    // Explicit, acknowledged stop: no recorder may run during the window.
    adb("shell", "pkill -INT screenrecord; true");
    for (let i = 0; i < 5 && !recorderGone(); i++) await sleep(1000);
    if (!recorderGone()) return unknown("screen recorder did not stop before the login window");
    adb("logcat", "-c");
    adb("shell", "settings", "put", "secure", "autofill_service", "null");

    let typedAt = 0;
    let stamp = null;
    for (const deadline = Date.now() + timeoutMs; !stamp; await sleep(pollMs)) {
      if (Date.now() > deadline) return unknown("no login-complete receipt");
      const receipt = parseReceipt(adb("logcat", "-d", "-v", "threadtime", "-s", "SlockAppLogin:I"));
      if (receipt.refused) return unknown(`app refused the login (${receipt.refused})`);
      if ((stamp = receipt.stamp)) break;
      const nodes = parseUiNodes(adb("exec-out", "uiautomator", "dump", "/dev/tty"));
      const pw = nodes.find((n) => n.editable && n.password && n.bounds);
      if (pw && typedAt) {
        if (Date.now() - typedAt > 20000) return unknown("the login form is still there after submitting");
        continue;
      }
      if (pw) {
        const field = nodes.filter((n) => n.editable && !n.password && n.bounds && n.bounds[1] < pw.bounds[1]).at(-1);
        if (!field) return unknown("no email field above the password field");
        tap(field); await consoleType(port, email);
        tap(pw); await consoleType(port, password);
        adb("shell", "input", "keyevent", "66");
        typedAt = Date.now();
        continue;
      }
      const consent = nodes.find((n) => n.pkg === pkg && n.checkable && !n.checked && n.bounds);
      const button = nodes.find((n) => n.enabled && n.bounds && BUTTONS.test((n.text || n.desc).trim()));
      if (consent) tap(consent); else if (button) tap(button);
    }

    // Clear what the browser and keyboard learned. Owned emulator only (checked above).
    const ime = adb("shell", "settings", "get", "secure", "default_input_method").trim().split("/")[0];
    for (const target of ["com.android.chrome", ime]) {
      if (!target || !/Success/.test(adb("shell", "pm", "clear", target))) return unknown(`could not clear ${target || "the keyboard"}`);
    }
    if (!recorderGone()) return unknown("a screen recorder ran during the login window");
    const focus = /mCurrentFocus=Window\{[^}]*\}/.exec(adb("shell", "dumpsys", "window"))?.[0] ?? "";
    if (!focus.includes(` ${pkg}/`)) return unknown("the app is not in front after login");
    writeRecord(out, { mode: "password", status: "complete", login_end: stamp, reason: "app_login_complete result=app_shell reason=exchanged" }, env);
    return { ok: true, stamp };
  } catch (error) {
    // Never echo the error text: a tool's message could carry what it was given.
    return unknown(`login probe failed (${error?.code || error?.name || "error"})`);
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
  const platform = arg("--platform", "android");
  const passwordLogin = arg("--password-login");
  if (!["true", "false"].includes(passwordLogin)) { console.error("--password-login true|false is required"); process.exit(2); }
  let result;
  if (passwordLogin === "false") {
    writeRecord(out, { mode: "none", status: "no-login", login_end: null, reason: "this mode never signs in" });
    result = { ok: true };
  } else if (platform === "android") {
    result = await androidPasswordLogin({ out, pkg: arg("--package", "build.raft.app"), device: arg("--device") });
  } else {
    const reason = `${platform} has no safe way to type a secret; password login fails closed`;
    markUnknown(out, reason);
    result = { ok: false, reason };
  }
  console.log(`login phase: ${result.ok ? "OK" : `UNKNOWN (${result.reason})`}`);
  process.exit(result.ok ? 0 : 1);
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) await main();
