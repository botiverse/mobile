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
//   5. signed-in state is checked on screen (+ the app's app_login_complete log line), the web
//      session the script opened is signed out, and the device is put back (capture file removed,
//      browser role restored, capture browser uninstalled when this run installed it).
//
// It stops instead of working around any server check: two-step verification, a rate limit, or
// an unexpected response ends the run. Credentials, tokens, the request id, the code and the
// callback URL are never printed or written to evidence (masked).
//
// Usage (inside an emulator job; adb on PATH):
//   QA_LOGIN_EMAIL=… QA_LOGIN_PASSWORD=… node scripts/qa/release_auto_login.mjs \
//     --capture-browser-apk build/capture-browser.apk [--apk app-release.apk] \
//     [--env staging|production] [--package build.raft.app] [--out release-auto-login-evidence]
//   (or --credential-file <testbed qa-account get --out file>)
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import {
  ENVIRONMENTS,
  callbackSchemeFor,
  createRedactor,
  parseCredentialFile,
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
const preset = ENVIRONMENTS[ENV_NAME];
const WEB_URL = option("web-url", preset?.webUrl ?? "");
const API_BASE = (option("api-url", preset?.apiBaseUrl ?? "")).replace(/\/?$/, "/");
const SCHEME = option("callback-scheme", callbackSchemeFor(PKG));
const CAPTURE_FILE = "files/captured_url";

const redact = createRedactor();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const summary = { package: PKG, environment: ENV_NAME, web_host: WEB_URL ? new URL(WEB_URL).host : "", steps: [] };
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
async function web(method, pathname, { token, body } = {}) {
  const response = await fetch(`${API_BASE}${pathname}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      Origin: new URL(WEB_URL).origin,
      "User-Agent": "raft-mobile-qa-release-auto-login",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
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

async function cleanup() {
  const done = {};
  try {
    removeCapture();
    done.capture_file_removed = !readCapture();
  } catch {}
  try {
    for (const other of restore.disabledBrowsers) shell(`pm enable --user 0 ${other}`, { allowFailure: true });
    if (restore.disabledBrowsers.length) done.browsers_reenabled = restore.disabledBrowsers;
  } catch {}
  try {
    if (restore.browserHolders) {
      shell(`cmd role remove-role-holder --user 0 android.app.role.BROWSER ${STUB_PKG}`, { allowFailure: true });
      for (const holder of restore.browserHolders) {
        if (holder !== STUB_PKG) shell(`cmd role add-role-holder --user 0 android.app.role.BROWSER ${holder}`, { allowFailure: true });
      }
      done.browser_role_restored_to = restore.browserHolders.filter((h) => h !== STUB_PKG);
    }
  } catch {}
  try {
    if (restore.stubInstalledByRun && !KEEP_STUB) {
      shell(`pm uninstall ${STUB_PKG}`, { allowFailure: true });
      done.capture_browser_uninstalled = !shell(`pm path ${STUB_PKG}`, { allowFailure: true }).trim();
    }
  } catch {}
  shell("rm -f /sdcard/release-auto-login.xml", { allowFailure: true });
  summary.cleanup = done;
}

// ---------- the flow ----------
async function main() {
  if (!preset && !(option("web-url") && option("api-url"))) stop(`unknown --env ${ENV_NAME}; use staging|production or pass --web-url and --api-url`);
  if (!STUB_APK && !shell(`pm path ${STUB_PKG}`, { allowFailure: true }).trim()) stop("--capture-browser-apk is required (build it with scripts/qa/capture-browser/build.sh)");
  const account = credentials();
  let webSession = null;
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
      webSession = { accessToken: login.json.accessToken, refreshToken: login.json.refreshToken ?? "" };
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
      adb(["shell"], { input: `am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${callback}' ${PKG} >/dev/null 2>&1; exit\n`, allowFailure: true });
    }, { screenshot: false });

    await step("verify-signed-in", async () => {
      const deadline = Date.now() + 120_000;
      let lastLabels = "";
      while (Date.now() < deadline) {
        const lines = fs.existsSync(logcatFile) ? fs.readFileSync(logcatFile, "utf8") : "";
        const complete = /app_login_complete result=(\S+) reason=(\S+)/.exec(lines);
        if (complete && complete[1] !== "app_shell") stop(`the app refused the callback: result=${complete[1]} reason=${complete[2]}`);
        const list = nodes();
        await dismissSystemDialog(list);
        const inApp = list.some((n) => n.pkg === PKG);
        const signInVisible = list.some((n) => n.pkg === PKG && /^(Sign in to Raft|Signing in…)$/.test(n.text));
        const failed = find(list, (n) => n.text.startsWith("Unable to finish sign-in"));
        if (failed) stop("the app shows \"Unable to finish sign-in\"");
        const home = find(list, (n) => n.pkg === PKG && n.text === "Drafts");
        const chooser = find(list, (n) => n.pkg === PKG && n.text.startsWith("Signed in as "));
        if (inApp && !signInVisible && (home || chooser || complete)) {
          if (!complete) await sleep(3000); // logcat reaches the file a moment after the screen
          const logged = /app_login_complete result=(\S+)/.exec(fs.existsSync(logcatFile) ? fs.readFileSync(logcatFile, "utf8") : "")?.[1];
          if (logged && logged !== "app_shell") stop(`the app refused the callback: result=${logged}`);
          return { exchange: logged ?? "not logged", screen: home ? "home" : chooser ? "choose_server" : "other" };
        }
        if (!inApp) launchApp();
        lastLabels = list.map(label).filter(Boolean).slice(0, 30).join(" | ");
        await sleep(2000);
      }
      stop(`still not signed in after 120s; on screen: ${lastLabels || "(nothing labelled)"}`);
    });
  } finally {
    if (webSession?.refreshToken) {
      // Sign the script's own web session out (what the web "Sign out" sends); the app's session is separate.
      try {
        const out = await web("POST", "auth/logout", { token: webSession.accessToken, body: { refreshToken: webSession.refreshToken } });
        summary.web_session_signed_out = out.status === 200;
      } catch {
        summary.web_session_signed_out = false;
      }
    }
    try { logcat?.kill("SIGTERM"); } catch {}
    if (fs.existsSync(logcatFile)) {
      fs.writeFileSync(path.join(OUT, "logcat-app-login.txt"), redact(fs.readFileSync(logcatFile, "utf8")));
      fs.rmSync(logcatFile);
    }
    await cleanup();
  }
}

main()
  .then(() => {
    summary.result = "signed_in";
    log("signed in");
  })
  .catch((error) => {
    summary.result = "failed";
    summary.error = redact(error instanceof Stop ? error.message : error.stack ?? error.message);
    console.error(`[release-auto-login] FAILED: ${summary.error}`);
    process.exitCode = 1;
  })
  .finally(() => {
    fs.writeFileSync(path.join(OUT, "summary.json"), `${redact(JSON.stringify(summary, null, 2))}\n`);
  });
