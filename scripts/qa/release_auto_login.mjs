#!/usr/bin/env node
// Signs a RELEASE Android build in through the product's own browser login, with no QA hook in the
// app (the debuggable-only QaSessionBootstrapActivity is not in release builds):
//
//   1. the app (fresh, optionally pointed at a self-hosted/staging server through its own
//      "Sign in to a self-hosted server" sheet) is tapped through consent + "Sign in to Raft";
//      it starts an app-login request with ITS OWN PKCE verifier and opens the login URL in the
//      default browser;
//   2. the default browser on the test emulator is the QA capture browser
//      (scripts/qa/capture-browser), which only records that URL;
//   3. this script then does what the web login page does over HTTP with a QA account:
//      POST /auth/login, GET /auth/app-login/requests/:id, POST .../approve (the "Confirm" button);
//   4. the returned raft://login/callback?requestId=…&code=… goes to the app with an explicit
//      `am start … <package>`, as a browser redirect would; the app exchanges code + its verifier;
//   5. signed-in state is checked on screen (+ the app's app_login_complete log line);
//   6. cleanup, each step with its own status in summary.json `cleanup`: the web session the
//      script opened is signed out (and checked: its refresh token must be refused), the capture
//      file deleted, the browser role restored / browsers re-enabled, the capture browser
//      uninstalled when this run installed it, and with --revoke-app-session the app is signed out
//      through its own Sign out (fallback: `pm clear`). With --strict-cleanup any cleanup step that
//      did not succeed makes the run exit non-zero, and so does a signed-in screen without the
//      app's own app_login_complete exchange line (required for Testbed; on in the workflow).
//
// It stops instead of working around any server check: two-step verification, a rate limit, or
// an unexpected response ends the run. Credentials, tokens, the request id, the code and the
// callback URL are never printed or written to evidence (masked).
//
// Usage (inside an emulator job; adb on PATH):
//   QA_LOGIN_EMAIL=… QA_LOGIN_PASSWORD=… node scripts/qa/release_auto_login.mjs \
//     --capture-browser-apk build/capture-browser.apk [--apk app-release.apk] \
//     [--env staging|production] [--package build.raft.app] [--out release-auto-login-evidence]
//     [--strict-cleanup] [--revoke-app-session] [--keep-capture-browser]
//     [--scenario scripts/qa/scenarios/<name>.mjs [its options, e.g. --server-name X]]
//   (or --credential-file <testbed qa-account get --out file>)
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import {
  ENVIRONMENTS,
  callbackSchemeFor,
  createRedactor,
  parseAppLoginComplete,
  parseCredentialFile,
  runOutcome,
  validateCallbackUrl,
  validateCapturedLoginUrl,
} from "./release_auto_login_lib.mjs";

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(`--${name}`);
const option = (name, fallback) => {
  const index = argv.indexOf(`--${name}`);
  return index >= 0 && argv[index + 1] && !argv[index + 1].startsWith("--") ? argv[index + 1] : fallback;
};

const PKG = option("package", "build.raft.app");
const APK = option("apk", "");
const STUB_APK = option("capture-browser-apk", "");
const STUB_PKG = "build.qa.capturebrowser";
const ENV_NAME = option("env", "staging");
const OUT = option("out", "release-auto-login-evidence");
const KEEP_STUB = flag("keep-capture-browser");
const STRICT_CLEANUP = flag("strict-cleanup");
const REVOKE_APP_SESSION = flag("revoke-app-session");
// Optional: a module whose run(ctx) drives the signed-in app (and the QA account's web session)
// before cleanup; docs/qa-acceptance/release-auto-login.md "Scenarios".
const SCENARIO = option("scenario", "");
const preset = ENVIRONMENTS[ENV_NAME];
const WEB_URL = option("web-url", preset?.webUrl ?? "");
const API_BASE = (option("api-url", preset?.apiBaseUrl ?? "")).replace(/\/?$/, "/");
const SCHEME = option("callback-scheme", callbackSchemeFor(PKG));
const CAPTURE_FILE = "files/captured_url";

const redact = createRedactor();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const summary = {
  package: PKG,
  environment: ENV_NAME,
  web_host: WEB_URL ? new URL(WEB_URL).host : "",
  strict_cleanup: STRICT_CLEANUP,
  revoke_app_session: REVOKE_APP_SESSION,
  steps: [],
};
let stepIndex = 0;

class Stop extends Error {}
const stop = (message) => {
  throw new Stop(message);
};
const log = (message) => console.log(`[release-auto-login] ${redact(message)}`);

// ---------- adb ----------
function adb(args, { allowFailure = false, timeoutMs = 60_000, binary = false, input } = {}) {
  const result = spawnSync("adb", args, {
    encoding: binary ? undefined : "utf8",
    input,
    maxBuffer: 64 * 1024 * 1024,
    timeout: timeoutMs,
  });
  if (!allowFailure && (result.error || result.status !== 0)) {
    throw new Error(`adb ${args.slice(0, 3).join(" ")} failed: ${redact(String(result.stderr || result.error || "").trim().slice(0, 400))}`);
  }
  return binary ? result.stdout : String(result.stdout ?? "");
}
const shell = (command, options) => adb(["shell", command], options);

// ---------- UI (uiautomator) ----------
function dumpXml() {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    shell("uiautomator dump /sdcard/release-auto-login.xml >/dev/null 2>&1", { allowFailure: true, timeoutMs: 30_000 });
    const xml = adb(["exec-out", "cat", "/sdcard/release-auto-login.xml"], { allowFailure: true });
    if (xml.includes("<hierarchy")) return xml;
  }
  return "";
}
const unescapeXml = (s) =>
  s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&#10;/g, "\n").replace(/&amp;/g, "&");
function nodes(xml = dumpXml()) {
  const list = [];
  for (const match of xml.matchAll(/<node [^>]*>/g)) {
    const tag = match[0];
    const attr = (name) => unescapeXml(new RegExp(`\\b${name}="([^"]*)"`).exec(tag)?.[1] ?? "");
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    list.push({
      text: attr("text"),
      desc: attr("content-desc"),
      pkg: attr("package"),
      cls: attr("class"),
      clickable: attr("clickable") === "true",
      enabled: attr("enabled") !== "false",
      bounds: b ? [+b[1], +b[2], +b[3], +b[4]] : null,
    });
  }
  return list;
}
const label = (n) => `${n.text} ${n.desc}`.trim();
const area = (n) => (n.bounds ? Math.max(1, n.bounds[2] - n.bounds[0]) * Math.max(1, n.bounds[3] - n.bounds[1]) : Infinity);
function find(list, matcher) {
  const test = typeof matcher === "function" ? matcher : (n) => n.text.trim() === matcher || n.desc.trim() === matcher;
  const hits = list.filter((n) => n.bounds && test(n));
  return hits.length ? hits.reduce((a, b) => (area(b) < area(a) ? b : a)) : null;
}
function tapNode(node) {
  const [x1, y1, x2, y2] = node.bounds;
  shell(`input tap ${Math.round((x1 + x2) / 2)} ${Math.round((y1 + y2) / 2)}`);
}
function launchApp() {
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`, { allowFailure: true });
}
// Emulator boot noise ("Pixel Launcher isn't responding"); a Raft ANR is a real failure.
async function dismissSystemDialog(list) {
  const anr = find(list, (n) => /isn.t responding/.test(n.text));
  if (!anr) return false;
  if (/^Raft\b/.test(anr.text)) stop(`the app is not responding: ${anr.text}`);
  const button = find(list, "Close app") ?? find(list, "Wait");
  if (!button) return false;
  tapNode(button);
  await sleep(1500);
  return true;
}
async function waitFor(description, matcher, { timeoutMs = 60_000, intervalMs = 1500 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let list = [];
  while (Date.now() < deadline) {
    list = nodes();
    const hit = find(list, matcher);
    if (hit) return hit;
    if (await dismissSystemDialog(list)) continue;
    await sleep(intervalMs);
  }
  const visible = list.map(label).filter(Boolean).slice(0, 30).join(" | ");
  stop(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${description}; on screen: ${visible || "(nothing labelled)"}`);
}
const PLACEHOLDERS = ["https://raft.company.com", "https://api.raft.company.com"];
const editTexts = () => nodes().filter((n) => n.cls === "android.widget.EditText" && n.bounds).sort((a, b) => a.bounds[1] - b.bounds[1]);
// Compose text fields drop characters from a long injected `input text`: type in short chunks and
// read the field back; clear and retype if it still came out wrong.
async function typeInto(fieldIndex, value) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const field = editTexts()[fieldIndex];
    if (!field) stop(`text field ${fieldIndex + 1} is gone`);
    tapNode(field);
    await sleep(500);
    if (field.text && !PLACEHOLDERS.includes(field.text.trim())) {
      shell(`input keyevent KEYCODE_MOVE_END ${"KEYCODE_DEL ".repeat(field.text.length + 5)}`, { timeoutMs: 60_000 });
      await sleep(300);
    }
    for (const chunk of value.replace(/'/g, "").match(/.{1,6}/g) ?? []) {
      shell(`input text '${chunk}'`);
      await sleep(250);
    }
    await sleep(500);
    const typed = editTexts()[fieldIndex]?.text ?? "";
    if (typed === value) return;
    log(`field ${fieldIndex + 1} reads ${typed.length} of ${value.length} characters after attempt ${attempt}; retyping`);
  }
  stop(`could not type ${value} into text field ${fieldIndex + 1}`);
}

// ---------- evidence ----------
fs.mkdirSync(OUT, { recursive: true });
function evidence(name) {
  const base = path.join(OUT, `step-${String(stepIndex).padStart(2, "0")}-${name}`);
  try {
    fs.writeFileSync(`${base}.png`, adb(["exec-out", "screencap", "-p"], { binary: true, allowFailure: true }));
    fs.writeFileSync(`${base}.xml`, redact(dumpXml()));
  } catch (error) {
    log(`evidence capture failed: ${error.message}`);
  }
}
async function step(name, body, { screenshot = true } = {}) {
  stepIndex += 1;
  const started = Date.now();
  log(`step ${stepIndex}: ${name}`);
  try {
    const detail = await body();
    if (screenshot) evidence(name);
    summary.steps.push({ step: stepIndex, name, result: "pass", ms: Date.now() - started, ...(detail ? { detail } : {}) });
    return detail;
  } catch (error) {
    // While the browser leg is in flight nothing on screen holds the code (the capture browser
    // shows nothing and the callback has not been delivered), so a failure screenshot is safe.
    if (screenshot) evidence(`${name}-FAILED`);
    summary.steps.push({ step: stepIndex, name, result: "fail", ms: Date.now() - started, error: redact(error.message) });
    throw error;
  }
}

// ---------- the web login page's requests ----------
// Exactly what packages/web does (store/authStore login, pages/AppLoginPage): JSON over the API
// with the access token as a bearer header. Origin is the web app's, as a browser would send it.
async function web(method, pathname, { token, body, serverId } = {}) {
  const response = await fetch(`${API_BASE}${pathname}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      Origin: new URL(WEB_URL).origin,
      "User-Agent": "raft-mobile-qa-release-auto-login",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(serverId ? { "X-Server-Id": serverId } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  let json = null;
  try {
    json = await response.json();
  } catch {}
  if (response.status === 429) stop(`the server rate-limited ${method} ${pathname} (HTTP 429); not retrying — wait for the limit to reset`);
  return { status: response.status, json };
}

function credentials() {
  const file = option("credential-file", "");
  const value = file
    ? parseCredentialFile(fs.readFileSync(file, "utf8"))
    : { email: process.env.QA_LOGIN_EMAIL ?? "", password: process.env.QA_LOGIN_PASSWORD ?? "" };
  if (!value.email || !value.password) stop("no QA credentials: set QA_LOGIN_EMAIL / QA_LOGIN_PASSWORD or pass --credential-file");
  redact.add(value.email);
  redact.add(value.password);
  return value;
}

// ---------- device state the run changes, and puts back ----------
const restore = { stubInstalledByRun: false, browserHolders: null, disabledBrowsers: [] };

function browserRoleHolders() {
  return shell("cmd role get-role-holders --user 0 android.app.role.BROWSER", { allowFailure: true })
    .split(/[\s;]+/).map((s) => s.trim()).filter(Boolean);
}
function resolvedBrowser() {
  const out = shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://example.com/", { allowFailure: true });
  return out.trim().split("\n").pop()?.split("/")[0] ?? "";
}
function browserCandidates() {
  const out = shell("cmd package query-activities --brief --user 0 -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://example.com/", { allowFailure: true });
  return [...new Set(out.split("\n").map((l) => l.trim()).filter((l) => /^[\w.]+\/[\w.$]+$/.test(l)).map((l) => l.split("/")[0]))];
}
function readCapture() {
  return shell(`run-as ${STUB_PKG} cat ${CAPTURE_FILE} 2>/dev/null`, { allowFailure: true }).trim();
}
function removeCapture() {
  shell(`run-as ${STUB_PKG} rm -f ${CAPTURE_FILE} files/captured_url.tmp`, { allowFailure: true });
}

// Each step records its own status (CLEANUP_OK in the lib lists the ones that count as done);
// a step that throws records "failed" and the next one still runs.
const cleanupStatus = {
  capture_file: "not_applicable",
  browser: "not_changed",
  capture_browser: "not_installed_by_run",
  web_session: "not_opened",
  app_session: REVOKE_APP_SESSION ? "not_signed_in" : "not_requested",
};
const cleanupDetail = {};
async function cleanupStep(key, body) {
  try {
    cleanupStatus[key] = await body();
  } catch (error) {
    cleanupStatus[key] = "failed";
    cleanupDetail[`${key}_error`] = redact(error.message).slice(0, 300);
  }
  log(`cleanup ${key}: ${cleanupStatus[key]}`);
}
const stubInstalled = () => Boolean(shell(`pm path ${STUB_PKG}`, { allowFailure: true }).trim());
function captureFileState() {
  const out = shell(`run-as ${STUB_PKG} sh -c 'if [ -e ${CAPTURE_FILE} ] || [ -e files/captured_url.tmp ]; then echo present; else echo absent; fi'`, { allowFailure: true }).trim();
  return out.endsWith("absent") ? "absent" : out.endsWith("present") ? "present" : "unknown";
}

async function cleanupDevice() {
  await cleanupStep("capture_file", async () => {
    if (!stubInstalled()) return "not_applicable";
    removeCapture();
    const state = captureFileState();
    if (state !== "absent") throw new Error(`capture file is ${state} after rm`);
    return "deleted";
  });
  await cleanupStep("browser", async () => {
    if (!restore.browserHolders && !restore.disabledBrowsers.length) return "not_changed";
    for (const other of restore.disabledBrowsers) shell(`pm enable --user 0 ${other}`, { allowFailure: true });
    if (restore.browserHolders) {
      shell(`cmd role remove-role-holder --user 0 android.app.role.BROWSER ${STUB_PKG}`, { allowFailure: true });
      for (const holder of restore.browserHolders) {
        if (holder !== STUB_PKG) shell(`cmd role add-role-holder --user 0 android.app.role.BROWSER ${holder}`, { allowFailure: true });
      }
    }
    await sleep(500);
    const expected = (restore.browserHolders ?? []).filter((h) => h !== STUB_PKG);
    const holders = browserRoleHolders();
    const stillDisabled = shell("pm list packages -d --user 0", { allowFailure: true })
      .split("\n").map((l) => l.replace(/^package:/, "").trim()).filter((p) => restore.disabledBrowsers.includes(p));
    cleanupDetail.browser_role_holders = holders;
    cleanupDetail.browsers_reenabled = restore.disabledBrowsers.filter((p) => !stillDisabled.includes(p));
    const problems = [
      ...expected.filter((h) => !holders.includes(h)).map((h) => `${h} does not hold the browser role`),
      ...(holders.includes(STUB_PKG) ? [`${STUB_PKG} still holds the browser role`] : []),
      ...stillDisabled.map((p) => `${p} is still disabled`),
    ];
    if (problems.length) throw new Error(problems.join("; "));
    return "restored";
  });
  await cleanupStep("capture_browser", async () => {
    if (!restore.stubInstalledByRun) return "not_installed_by_run";
    if (KEEP_STUB) return "kept";
    shell(`pm uninstall ${STUB_PKG}`, { allowFailure: true });
    if (stubInstalled()) throw new Error(`${STUB_PKG} is still installed`);
    return "uninstalled";
  });
  shell("rm -f /sdcard/release-auto-login.xml", { allowFailure: true });
}

// The script's own web session: what the web's Sign out sends, then proof: the server must refuse
// that refresh token (401). /auth/logout answers 200 even for an unknown token, so 200 alone is not.
async function signOutWebSession(webSession) {
  await cleanupStep("web_session", async () => {
    if (!webSession) return "not_opened";
    if (!webSession.refreshToken) throw new Error("the login returned no refresh token to sign out");
    const out = await web("POST", "auth/logout", { token: webSession.accessToken, body: { refreshToken: webSession.refreshToken } });
    if (out.status !== 200) throw new Error(`POST /auth/logout answered HTTP ${out.status}`);
    const check = await web("POST", "auth/refresh", { body: { refreshToken: webSession.refreshToken } });
    cleanupDetail.web_session_refresh_check = `${check.status}${check.json?.code ? ` ${check.json.code}` : ""}`;
    if (check.status === 200) {
      // Still live (and now rotated): sign the rotated one out too, and report the failure.
      if (check.json?.refreshToken) {
        redact.add(check.json.refreshToken);
        redact.add(check.json.accessToken);
        await web("POST", "auth/logout", { body: { refreshToken: check.json.refreshToken } }).catch(() => {});
      }
      throw new Error("the web session's refresh token still worked after sign out");
    }
    if (check.status !== 401) throw new Error(`refresh check answered HTTP ${check.status}`);
    return "signed_out";
  });
}

// The app's own session. The server has no session list a client can read or revoke by id (slock
// staging: only POST /auth/logout with the session's refresh token, which stays inside the
// non-debuggable app), so the app's own Sign out is what revokes it: the app sends POST
// /auth/logout itself. Verified by the app being back on its sign-in page; the app's own report of
// that call is recorded when it reaches logcat.
const onSignInPage = (list) =>
  list.some((n) => n.pkg === PKG && (n.text === "Sign in to Raft" || n.text.startsWith("Sign in through ")));
async function signOutAppViaUi() {
  const deadline = Date.now() + 120_000;
  const route = [];
  let swipes = 0;
  let serverTaps = 0;
  let lastLabels = "";
  const tap = (node, what) => {
    tapNode(node);
    if (route[route.length - 1] !== what) route.push(what);
  };
  launchApp();
  await sleep(1500);
  while (Date.now() < deadline) {
    const list = nodes();
    if (await dismissSystemDialog(list)) continue;
    if (onSignInPage(list)) return route;
    const mine = list.filter((n) => n.pkg === PKG);
    const confirmMessage = find(mine, (n) => n.text.startsWith("Sign out on this device?"));
    const chooserSignOut = find(mine, (n) => n.text === "Sign Out" && n.enabled);
    const signOut = mine.filter((n) => n.bounds && n.enabled && n.text === "Sign out");
    const settingsTitle = find(mine, (n) => n.text === "Settings");
    const account = find(mine, (n) => n.text === "Account");
    const more = find(mine, (n) => n.text === "More" || n.desc === "More");
    const currentServer = find(mine, (n) => n.desc.startsWith("Current server: "));
    if (confirmMessage) {
      // Settings > Account > Sign out asks first; its button sits under the message.
      const button = signOut.filter((n) => n.bounds[1] >= confirmMessage.bounds[3]).sort((a, b) => b.bounds[1] - a.bounds[1])[0];
      if (!button) stop("the sign-out confirmation shows no Sign out button");
      tap(button, "confirm:Sign out");
    } else if (chooserSignOut) {
      // "Choose server … Signed in as …" (an account on several servers): Sign Out, no confirmation.
      tap(chooserSignOut, "choose_server:Sign Out");
    } else if (signOut.length) {
      tap(signOut.sort((a, b) => b.bounds[1] - a.bounds[1])[0], "account:Sign out");
    } else if (account && settingsTitle) {
      tap(account, "settings:Account");
    } else if (find(mine, (n) => n.text === "Delete account")) {
      // On Account, Sign out can be below the fold.
      if (swipes++ > 6) stop("Sign out is not on the Account page");
      shell("input swipe 540 1700 540 600 400", { allowFailure: true });
      if (route[route.length - 1] !== "account:scroll") route.push("account:scroll");
    } else if (settingsTitle) {
      tap(settingsTitle, "Settings");
    } else if (more) {
      tap(more, "More");
    } else if (currentServer && serverTaps < 3) {
      // A signed-in server's Home has no More tab: its server button (bottom bar) leads to Settings / Sign Out.
      serverTaps += 1;
      tap(currentServer, "Current server");
    } else if (!mine.length) {
      launchApp();
    }
    lastLabels = mine.map(label).filter(Boolean).slice(0, 25).join(" | ");
    await sleep(1800);
  }
  stop(`not back on the sign-in page after 120s (route: ${route.join(" > ") || "none"}); on screen: ${lastLabels || "(nothing labelled)"}`);
}

async function revokeAppSession(appMayBeSignedIn) {
  await cleanupStep("app_session", async () => {
    if (!REVOKE_APP_SESSION) return "not_requested";
    if (!appMayBeSignedIn) return "not_signed_in";
    shell("logcat -c", { allowFailure: true });
    try {
      cleanupDetail.app_sign_out_route = (await signOutAppViaUi()).join(" > ");
      // The app's own report of its POST /auth/logout (AuthDataSource.logout), when it is logged.
      const logged = /auth_logout_cleared serverRevocation=(\S+)/.exec(shell("logcat -d -s SlockKuikly:* 2>/dev/null | tail -300", { allowFailure: true }))?.[1];
      cleanupDetail.app_reported_server_logout = logged ?? "not_logged";
      if (logged && logged !== "success") throw new Error(`the app signed out locally but reported its server logout as ${logged}`);
      return "signed_out_via_app";
    } catch (error) {
      cleanupDetail.app_sign_out_error = redact(error.message).slice(0, 300);
      log(`app sign out through its UI did not complete (${cleanupDetail.app_sign_out_error}); clearing app data instead`);
      const out = shell(`pm clear ${PKG}`, { allowFailure: true }).trim();
      if (!/Success/.test(out)) throw new Error(`pm clear ${PKG} answered: ${out.slice(0, 120) || "nothing"}`);
      return "local_cleared_server_unconfirmed";
    }
  });
}

// ---------- scenario (optional) ----------
// The scenario gets the device helpers and the QA account's web session (refreshed on a 401; the
// same session is signed out in cleanup). Its result goes to summary.json `scenario`; a scenario
// that throws or reports result "fail" makes the run exit non-zero after cleanup.
let scenarioFailed = false;
async function runScenario(webSession) {
  const authed = async (method, pathname, { body, serverId } = {}) => {
    let out = await web(method, pathname, { token: webSession.accessToken, body, serverId });
    if (out.status === 401 && webSession.refreshToken) {
      const refreshed = await web("POST", "auth/refresh", { body: { refreshToken: webSession.refreshToken } });
      if (refreshed.status === 200 && refreshed.json?.accessToken) {
        redact.add(refreshed.json.accessToken);
        redact.add(refreshed.json.refreshToken);
        webSession.accessToken = refreshed.json.accessToken;
        webSession.refreshToken = refreshed.json.refreshToken ?? webSession.refreshToken;
        out = await web(method, pathname, { token: webSession.accessToken, body, serverId });
      }
    }
    return out;
  };
  const ctx = {
    PKG, OUT, summary, option, flag, adb, shell, nodes, find, label, tapNode, waitFor, launchApp, dismissSystemDialog,
    sleep, log, stop, step, evidence, redact,
    nextStepIndex: () => stepIndex + 1,
    web: authed,
    account: { userId: webSession.userId, displayName: webSession.displayName },
  };
  try {
    const mod = await import(path.resolve(SCENARIO));
    const report = await mod.run(ctx);
    if (report?.result === "fail") scenarioFailed = true;
  } catch (error) {
    scenarioFailed = true;
    summary.scenario = { ...(summary.scenario ?? {}), result: "fail", error: redact(error?.message ?? String(error)).slice(0, 600) };
    log(`scenario failed: ${error?.message ?? error}`);
  }
}

// ---------- the flow ----------
async function main() {
  if (!preset && !(option("web-url") && option("api-url"))) stop(`unknown --env ${ENV_NAME}; use staging|production or pass --web-url and --api-url`);
  if (!STUB_APK && !shell(`pm path ${STUB_PKG}`, { allowFailure: true }).trim()) stop("--capture-browser-apk is required (build it with scripts/qa/capture-browser/build.sh)");
  const account = credentials();
  let webSession = null;
  let callbackSent = false;
  let logcat = null;
  const logcatFile = path.join(OUT, ".logcat-raw.txt");
  try {
    await step("prepare-device", async () => {
      adb(["wait-for-device"]);
      if (STUB_APK && !shell(`pm path ${STUB_PKG}`, { allowFailure: true }).trim()) {
        adb(["install", "-r", STUB_APK], { timeoutMs: 120_000 });
        restore.stubInstalledByRun = true;
      }
      removeCapture();
      restore.browserHolders = browserRoleHolders();
      const added = shell(`cmd role add-role-holder --user 0 android.app.role.BROWSER ${STUB_PKG} 2>&1`, { allowFailure: true }).trim();
      await sleep(1000);
      let browser = resolvedBrowser();
      let via = "browser-role";
      if (browser !== STUB_PKG) {
        // The role refused the stub (some images only grant it to qualifying system browsers): disable
        // the other browsers for the run instead, so the stub is the only one. Re-enabled in cleanup.
        const roleError = shell("logcat -d -t 200 2>/dev/null | grep -i -m 5 'role' || true", { allowFailure: true }).trim();
        log(`browser role not taken (${added || "no output"}); ${roleError ? `role log: ${roleError.split("\n")[0].slice(0, 200)}` : "no role log"}`);
        for (const other of browserCandidates().filter((p) => p !== STUB_PKG)) {
          shell(`pm disable-user --user 0 ${other}`, { allowFailure: true });
          restore.disabledBrowsers.push(other);
        }
        await sleep(1000);
        browser = resolvedBrowser();
        via = "other-browsers-disabled";
      }
      if (browser !== STUB_PKG) stop(`the capture browser is not the default browser (resolves to ${browser || "nothing"})`);
      if (APK) {
        shell(`pm uninstall ${PKG}`, { allowFailure: true });
        adb(["install", "-r", APK], { timeoutMs: 240_000 });
      } else {
        shell(`pm clear ${PKG}`, { allowFailure: true });
      }
      const dump = shell(`dumpsys package ${PKG}`, { allowFailure: true });
      if (!dump.includes("versionCode=")) stop(`${PKG} is not installed`);
      const flags = /pkgFlags=\[([^\]]*)\]/.exec(dump)?.[1] ?? "";
      return {
        sdk: shell("getprop ro.build.version.sdk").trim(),
        app_version: /versionName=(\S+)/.exec(dump)?.[1] ?? "",
        app_version_code: Number(/versionCode=(\d+)/.exec(dump)?.[1] ?? 0),
        app_debuggable: /\bDEBUGGABLE\b/.test(flags),
        previous_browser: restore.browserHolders.join(",") || "(none)",
        capture_browser_via: via,
        disabled_browsers: restore.disabledBrowsers,
      };
    });

    adb(["logcat", "-c"], { allowFailure: true });
    logcat = spawn("adb", ["logcat", "-v", "threadtime", "-s", "SlockAppLogin:*"], { stdio: ["ignore", fs.openSync(logcatFile, "w"), "ignore"] });

    await step("open-sign-in-page", async () => {
      launchApp();
      await waitFor('"Sign in to Raft"', "Sign in to Raft", { timeoutMs: 120_000 });
    });

    if (ENV_NAME !== "production" || option("web-url")) {
      await step("point-app-at-server", async () => {
        // The product's own self-hosted sheet (PrivateDeploymentSheet): web + API address, Save.
        await waitFor("the self-hosted entry", "Sign in to a self-hosted server").then(tapNode);
        await waitFor('the "Self-hosted server" dialog', "Self-hosted server");
        const count = editTexts().length;
        if (count < 2) stop(`the self-hosted dialog shows ${count} text fields, expected 2`);
        await typeInto(0, WEB_URL);
        await typeInto(1, new URL(API_BASE).origin);
        await sleep(800);
        let save = find(nodes(), (n) => n.text === "Save" && n.enabled);
        if (!save) {
          shell("input keyevent KEYCODE_BACK", { allowFailure: true }); // the keyboard covers the footer
          save = await waitFor("Save", (n) => n.text === "Save" && n.enabled, { timeoutMs: 10_000 });
        }
        tapNode(save);
        const host = new URL(WEB_URL).host;
        await waitFor(`"Sign in through ${host}"`, (n) => n.text.includes(`Sign in through ${host}`), { timeoutMs: 60_000 });
        return { web_host: host };
      });
    }

    const { requestId } = await step("app-opens-login-url", async () => {
      const list = nodes();
      const agreement = find(list, (n) => n.text.startsWith("I agree to the"));
      if (!agreement) stop('consent text "I agree to the ..." is not on the sign-in page');
      const box = find(list, (n) => n.clickable && !n.text && n.bounds[2] <= agreement.bounds[0] + 4 &&
        n.bounds[1] < agreement.bounds[3] && n.bounds[3] > agreement.bounds[1]);
      if (box) tapNode(box);
      else shell(`input tap ${Math.max(5, agreement.bounds[0] - 40)} ${agreement.bounds[1] + 30}`);
      await sleep(800);
      await waitFor('an enabled "Sign in to Raft"', (n) => n.text === "Sign in to Raft" && n.enabled).then(tapNode);
      const deadline = Date.now() + 60_000;
      let captured = "";
      while (Date.now() < deadline && !captured) {
        captured = readCapture();
        if (!captured) await sleep(1000);
      }
      if (!captured) stop("the app never opened a URL in the browser (no capture within 60s)");
      removeCapture();
      const parsed = validateCapturedLoginUrl(captured, { webUrl: WEB_URL });
      redact.add(parsed.requestId);
      return { requestId: parsed.requestId, host: parsed.host };
    }).then((detail) => {
      // Keep the id out of the summary.
      const id = detail.requestId;
      delete detail.requestId;
      return { requestId: id };
    });

    const callback = await step("web-login-and-confirm", async () => {
      const login = await web("POST", "auth/login", { body: { email: account.email, password: account.password } });
      if (login.status === 401) stop("the QA account's email/password were refused (HTTP 401)");
      if (login.status !== 200) stop(`POST /auth/login answered HTTP ${login.status} ${login.json?.code ?? ""}`);
      if (login.json?.secondFactorRequired || login.json?.challenge) {
        stop("the QA account has two-step verification on; this script does not answer second factors — use an account without it");
      }
      if (!login.json?.accessToken) stop("POST /auth/login answered without an access token");
      webSession = {
        accessToken: login.json.accessToken,
        refreshToken: login.json.refreshToken ?? "",
        userId: login.json.user?.id ?? "",
        displayName: login.json.user?.displayName || login.json.user?.name || "",
      };
      redact.add(webSession.accessToken);
      redact.add(webSession.refreshToken);

      const described = await web("GET", `auth/app-login/requests/${encodeURIComponent(requestId)}`, { token: webSession.accessToken });
      if (described.status !== 200) stop(`the app-login request is not usable (HTTP ${described.status} ${described.json?.code ?? ""})`);

      const approved = await web("POST", `auth/app-login/requests/${encodeURIComponent(requestId)}/approve`, { token: webSession.accessToken });
      if (approved.status === 403 && approved.json?.code === "second_factor_step_up_required") {
        stop("the server asks for a two-step verification step-up before approval; not bypassing it");
      }
      if (approved.status !== 200 || !approved.json?.redirectUrl) stop(`approving the app-login request failed (HTTP ${approved.status} ${approved.json?.code ?? ""})`);
      const redirectUrl = String(approved.json.redirectUrl);
      redact.add(redirectUrl);
      const { code } = validateCallbackUrl(redirectUrl, { scheme: SCHEME, requestId });
      redact.add(code);
      return { redirectUrl, client: described.json?.client?.name ?? null };
    }, { screenshot: false }).then((detail) => {
      const url = detail.redirectUrl;
      delete detail.redirectUrl;
      return url;
    });

    await step("deliver-callback-to-app", async () => {
      // Explicit package: only this app can receive it. Sent on adb's stdin so the code is not on
      // the host's command line, and am's output (which echoes the intent) is discarded.
      callbackSent = true;
      adb(["shell"], { input: `am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${callback}' ${PKG} >/dev/null 2>&1; exit\n`, allowFailure: true });
    }, { screenshot: false });

    await step("verify-signed-in", async () => {
      // The app's own verdict (app_login_complete) is read from a logcat dump on every poll: the
      // streamed file can lag the screen (adb's output to a file is buffered), which made an
      // exchanged sign-in read as "not logged".
      const appLog = () =>
        `${fs.existsSync(logcatFile) ? fs.readFileSync(logcatFile, "utf8") : ""}\n${adb(["logcat", "-d", "-v", "threadtime", "-s", "SlockAppLogin:*"], { allowFailure: true })}`;
      const deadline = Date.now() + 120_000;
      let lastLabels = "";
      let screenSince = 0;
      let screen = "";
      while (Date.now() < deadline) {
        const verdict = parseAppLoginComplete(appLog());
        if (verdict.status === "refused") stop(`the app refused the callback: result=${verdict.result} reason=${verdict.reason}`);
        const list = nodes();
        await dismissSystemDialog(list);
        const inApp = list.some((n) => n.pkg === PKG);
        const signInVisible = list.some((n) => n.pkg === PKG && /^(Sign in to Raft|Signing in…)$/.test(n.text));
        if (find(list, (n) => n.text.startsWith("Unable to finish sign-in"))) stop("the app shows \"Unable to finish sign-in\"");
        const home = find(list, (n) => n.pkg === PKG && n.text === "Drafts");
        const chooser = find(list, (n) => n.pkg === PKG && n.text.startsWith("Signed in as "));
        if (inApp && !signInVisible && (home || chooser)) {
          screen = home ? "home" : "choose_server";
          screenSince ||= Date.now();
          if (verdict.status === "exchanged") return { exchange: "exchanged", exchange_log: `result=${verdict.result} reason=${verdict.reason}`, screen };
          // Signed-in screen but no verdict line yet: give the log 30s to catch up, then say so.
          if (Date.now() - screenSince > 30_000) {
            const note = `signed-in screen (${screen}) but the app logged no app_login_complete line (last: ${verdict.status})`;
            if (STRICT_CLEANUP) stop(`${note}; strict mode needs the app's own exchange verdict`);
            log(`WARNING: ${note}`);
            return { exchange: "not_logged", exchange_verified: false, screen };
          }
        } else {
          screenSince = 0;
          if (!inApp) launchApp();
        }
        lastLabels = list.map(label).filter(Boolean).slice(0, 30).join(" | ");
        await sleep(2000);
      }
      stop(`still not signed in after 120s; on screen: ${lastLabels || "(nothing labelled)"}`);
    });

    if (SCENARIO) await runScenario(webSession);
  } finally {
    // Every cleanup step runs whatever happened above; each records its own status.
    await signOutWebSession(webSession);
    try { logcat?.kill("SIGTERM"); } catch {}
    if (fs.existsSync(logcatFile)) {
      fs.writeFileSync(path.join(OUT, "logcat-app-login.txt"), redact(fs.readFileSync(logcatFile, "utf8")));
      fs.rmSync(logcatFile);
    }
    await revokeAppSession(callbackSent);
    await cleanupDevice();
    summary.cleanup = { ...cleanupStatus, ...cleanupDetail };
  }
}

let signedIn = false;
main()
  .then(() => {
    signedIn = true;
    log("signed in");
  })
  .catch((error) => {
    summary.error = redact(error instanceof Stop ? error.message : error.stack ?? error.message);
    console.error(`[release-auto-login] FAILED: ${summary.error}`);
  })
  .finally(() => {
    const outcome = runOutcome({ signedIn, cleanup: cleanupStatus, strict: STRICT_CLEANUP });
    summary.result = outcome.result;
    summary.cleanup_ok = outcome.cleanupOk;
    summary.cleanup_failures = outcome.cleanupFailures;
    if (!outcome.cleanupOk) {
      const list = outcome.cleanupFailures.map((f) => `${f.step}=${f.status}`).join(", ");
      console.error(`[release-auto-login] cleanup incomplete: ${list}${STRICT_CLEANUP ? " (strict: failing the run)" : ""}`);
    }
    process.exitCode = outcome.exitCode || (scenarioFailed ? 1 : 0);
    if (SCENARIO) summary.scenario_result = scenarioFailed ? "fail" : (summary.scenario?.result ?? "pass");
    fs.writeFileSync(path.join(OUT, "summary.json"), `${redact(JSON.stringify(summary, null, 2))}\n`);
  });
