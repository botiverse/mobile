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
// On Android the CI job first builds its driver with scripts/qa/build_login_form.sh
// "$RUNNER_TEMP/qa-login-form.jar" in a step without the account environment.
// Usage:
//   node scripts/qa/login_phase.mjs login --platform android|ios --password-login true|false \
//     --out qa-evidence [--package build.raft.app] [--device emulator-5554]
//   node scripts/qa/login_phase.mjs check --out qa-evidence    (exit 0 only for a valid record)
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { ENVIRONMENTS } from "./release_auto_login_lib.mjs";
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

export async function androidPasswordLogin({ out, pkg = "build.raft.app", device = "", env = process.env, timeoutMs = 180000, pollMs = 2000, expectedOrigin = loginOrigin(env) }) {
  const { QA_TEST_EMAIL: email, QA_TEST_PASSWORD: password, ...adbEnv } = env;
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
  const unknown = (reason) => {
    markUnknown(out, reason, env);
    const dump = failureUi(controls, lastAction, reason);
    console.log(`Protected login UI: ${JSON.stringify(dump)}`);
    if (env.GITHUB_STEP_SUMMARY) {
      try { fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `\n### Android login failure — last UI observation\n\n\`\`\`json\n${JSON.stringify(dump, null, 2)}\n\`\`\`\n`); } catch { /* The same dump is in the job log. */ }
    }
    return { ok: false, reason };
  };
  writeRecord(out, { mode: "password", status: "unknown", login_end: null, reason: "login phase started and did not finish" }, env);
  try {
    if (!email || !password) return unknown("QA credentials missing");
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
        if (!submittedAt) return unknown("login receipt before this form was submitted");
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
      if (Date.now() > deadline) return unknown(submittedAt && controls.savePasswordPrompt ? SAVE_PROMPT_BLOCKED : "no login-complete receipt");
      if (submittedAt) {
        if (controls.savePasswordPrompt) {
          if (!savePromptSince) {
            savePromptSince = Date.now();
            confirmAfterDismissal = followed.has("confirm-sign-in");
            lastAction = "wait-save-password";
          }
          // Chrome's message banner offers Save and a settings menu. It dismisses itself.
          // Leave both the banner and the page underneath untouched until a fresh observation clears it.
          if (Date.now() - savePromptSince >= 45000) return unknown(SAVE_PROMPT_BLOCKED);
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
    if (!recorderGone()) return unknown("a screen recorder ran during the login window");
    writeRecord(out, { mode: "password", status: "complete", login_end: stamp, reason: "app_login_complete result=app_shell reason=exchanged" }, env);
    return { ok: true, stamp };
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
