#!/usr/bin/env node
// Deterministic Android in-app UPDATE e2e (no model, no Testbed) for
// .github/workflows/android-update-e2e.yml. Runs inside the emulator-runner
// action against one booted emulator.
//
//   install A -> sign in (QA account) -> seed a draft -> the in-app update prompt
//   offers B -> Download -> Install through Android's package installer ->
//   relaunch on B -> still signed in, same server, draft still there.
//
// Each step writes step-NN-<name>.png / .xml and a line to steps.jsonl; the first
// step that breaks fails the run with `FAILED at <step>: <why>`.
//
// Sign-in: the release fixture cannot use the debuggable-only QA session
// bootstrap, so this drives the product's own web sign-in: tap consent + "Sign in
// to Raft", read the app-login request id from the browser tab (Chrome DevTools
// socket, omnibox fallback), approve that request with the QA account through the
// Raft API (the same POST the web confirm page makes), and hand the returned
// raft:// callback to the app, which completes its own PKCE exchange.
//
// Never written to evidence: QA credentials, tokens, the app-login request id or
// code, or the Hands installation id.
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, index, all) => {
    if (value.startsWith("--")) pairs.push([value.slice(2), all[index + 1]]);
    return pairs;
  }, []),
);
const OUT = args.out ?? "update-e2e-evidence";
const PKG = args.package ?? "build.raft.app";
const APK_A = args["apk-a"];
const A_CODE = Number(args["a-code"]);
const B_CODE = Number(args["b-code"]);
const B_NAME = args["b-name"] ?? "";
const SERVER = args.server ?? "OranixSlockTest";
const HANDS_GROUP = args["hands-group"] ?? "";
const DEVICE_ID_FILE = args["device-id-file"] ?? "";
const RAFT_API = (process.env.QA_API_BASE || "https://api.raft.build/api/").replace(/\/?$/, "/");
const HANDS_BASE = process.env.HANDS_PUBLIC_BASE || "https://hands.build";
const DRAFT_TOKEN = `updatee2e${process.env.GITHUB_RUN_ID ?? Date.now()}`;
const SECRETS = [process.env.QA_TEST_EMAIL, process.env.QA_TEST_PASSWORD].filter(Boolean);

fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const redact = (text) => SECRETS.reduce((s, secret) => s.split(secret).join("[redacted]"), String(text));
let stepIndex = 0;
let currentStep = "setup";
const summary = { a_code: A_CODE, b_code: B_CODE, server: SERVER, steps: [] };

function log(message) {
  console.log(`[update-e2e] ${redact(message)}`);
}

function adb(argv, { allowFailure = false, timeoutMs = 60_000, binary = false } = {}) {
  const result = spawnSync("adb", argv, {
    encoding: binary ? undefined : "utf8",
    maxBuffer: 64 * 1024 * 1024,
    timeout: timeoutMs,
  });
  if (!allowFailure && (result.error || result.status !== 0)) {
    throw new Error(`adb ${argv.slice(0, 3).join(" ")} failed: ${redact(String(result.stderr || result.error || "").trim().slice(0, 400))}`);
  }
  return binary ? result.stdout : String(result.stdout ?? "");
}
const shell = (command, options) => adb(["shell", command], options);

class StepFailure extends Error {}
const fail = (message) => {
  throw new StepFailure(message);
};

// ---------- UI helpers (uiautomator) ----------
function dumpXml() {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    shell("uiautomator dump /sdcard/update-e2e.xml >/dev/null 2>&1", { allowFailure: true, timeoutMs: 30_000 });
    const xml = adb(["exec-out", "cat", "/sdcard/update-e2e.xml"], { allowFailure: true });
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
      id: attr("resource-id"),
      pkg: attr("package"),
      cls: attr("class"),
      clickable: attr("clickable") === "true",
      checked: attr("checked") === "true",
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
  if (!hits.length) return null;
  return hits.reduce((a, b) => (area(b) < area(a) ? b : a));
}

function tapNode(node) {
  const [x1, y1, x2, y2] = node.bounds;
  shell(`input tap ${Math.round((x1 + x2) / 2)} ${Math.round((y1 + y2) / 2)}`);
}

// Emulator boot noise: "<app> isn't responding" (often Pixel Launcher) covers the screen.
// It is not the app under test unless it names Raft, which is a real failure.
async function dismissSystemDialog(list) {
  const anr = find(list, (n) => /isn.t responding/.test(n.text));
  if (!anr) return false;
  if (/^Raft\b/.test(anr.text)) fail(`the app is not responding: ${anr.text}`);
  const button = find(list, "Close app") ?? find(list, "Wait");
  if (!button) return false;
  log(`dismissing system dialog "${anr.text}"`);
  tapNode(button);
  await sleep(1500);
  launchApp();
  await sleep(2000);
  return true;
}

async function waitFor(description, matcher, { timeoutMs = 60_000, intervalMs = 1500, onTick } = {}) {
  const deadline = Date.now() + timeoutMs;
  let list = [];
  while (Date.now() < deadline) {
    list = nodes();
    const hit = find(list, matcher);
    if (hit) return hit;
    if (await dismissSystemDialog(list)) continue;
    if (onTick) await onTick(list);
    await sleep(intervalMs);
  }
  const visible = list.map(label).filter(Boolean).slice(0, 40).join(" | ");
  fail(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${description}; on screen: ${visible || "(nothing labelled)"}`);
}

async function tap(description, matcher, options) {
  const node = await waitFor(description, matcher, options);
  tapNode(node);
  await sleep(1200);
  return node;
}

function evidence(name) {
  const base = path.join(OUT, `step-${String(stepIndex).padStart(2, "0")}-${name}`);
  try {
    fs.writeFileSync(`${base}.png`, adb(["exec-out", "screencap", "-p"], { binary: true, allowFailure: true }));
    fs.writeFileSync(`${base}.xml`, redact(dumpXml()));
  } catch (error) {
    log(`evidence capture failed: ${error.message}`);
  }
}

async function step(name, body) {
  stepIndex += 1;
  currentStep = name;
  const started = Date.now();
  log(`step ${stepIndex}: ${name}`);
  try {
    const detail = await body();
    evidence(name);
    const record = { step: stepIndex, name, result: "pass", ms: Date.now() - started, ...(detail ? { detail } : {}) };
    summary.steps.push(record);
    fs.appendFileSync(path.join(OUT, "steps.jsonl"), `${JSON.stringify(record)}\n`);
    return detail;
  } catch (error) {
    evidence(`${name}-FAILED`);
    const record = { step: stepIndex, name, result: "fail", ms: Date.now() - started, error: redact(error.message) };
    summary.steps.push(record);
    fs.appendFileSync(path.join(OUT, "steps.jsonl"), `${JSON.stringify(record)}\n`);
    throw error;
  }
}

function installedVersionCode() {
  const out = shell(`dumpsys package ${PKG}`, { allowFailure: true });
  const match = /versionCode=(\d+)/.exec(out);
  return match ? Number(match[1]) : null;
}

function installedVersionName() {
  return /versionName=(\S+)/.exec(shell(`dumpsys package ${PKG}`, { allowFailure: true }))?.[1] ?? "";
}

// Which app-private files hold the draft text (names only; root). Tells "never written to disk"
// apart from "written, then lost by the upgrade".
function draftFilesOnDisk() {
  return shell(`grep -rl ${DRAFT_TOKEN} /data/data/${PKG}/ 2>/dev/null`, { allowFailure: true })
    .split(/\s+/).filter(Boolean).map((f) => f.replace(`/data/data/${PKG}/`, ""));
}

function launchApp() {
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`, { allowFailure: true });
}

const isHome = (n) => n.text === "Drafts" && n.pkg === PKG;
const serverVisible = (list) => list.some((n) => n.pkg === PKG && label(n).includes(SERVER));

async function backToHome() {
  for (let i = 0; i < 5; i += 1) {
    const list = nodes();
    if (find(list, isHome)) return;
    if (!list.some((n) => n.pkg === PKG)) launchApp();
    else shell("input keyevent 4");
    await sleep(1500);
  }
  await waitFor("the Home page (Drafts row)", isHome, { timeoutMs: 20_000 });
}

// ---------- background recording + logcat ----------
function startCapture() {
  shell("rm -f /sdcard/update-e2e-rec-*.mp4", { allowFailure: true });
  shell(
    "nohup sh -c 'i=1; while [ $i -le 12 ]; do screenrecord --size 540x1200 --bit-rate 2000000 --time-limit 170 /sdcard/update-e2e-rec-$(printf %02d $i).mp4; i=$((i+1)); done' >/dev/null 2>&1 &",
    { allowFailure: true },
  );
  adb(["logcat", "-c"], { allowFailure: true });
  const logcat = fs.openSync(path.join(OUT, "logcat-raw.txt"), "w");
  const child = spawn("adb", ["logcat", "-v", "threadtime"], { stdio: ["ignore", logcat, logcat] });
  return child;
}

async function stopCapture(logcatChild) {
  try { logcatChild.kill("SIGTERM"); } catch {}
  shell("pkill -INT screenrecord", { allowFailure: true });
  await sleep(4000);
  const files = shell("ls /sdcard/update-e2e-rec-*.mp4 2>/dev/null", { allowFailure: true }).split(/\s+/).filter(Boolean);
  for (const file of files) adb(["pull", file, OUT], { allowFailure: true, timeoutMs: 120_000 });
  // Strip anything credential-shaped the app could have logged.
  const raw = path.join(OUT, "logcat-raw.txt");
  if (fs.existsSync(raw)) {
    fs.writeFileSync(path.join(OUT, "logcat.txt"), redact(fs.readFileSync(raw, "utf8"))
      .replace(/(code|request|state|token|access_token|refresh_token)=[^&\s"]+/gi, "$1=[redacted]")
      .replace(/X-Hands-Device-Id[^\n]*/gi, "X-Hands-Device-Id [redacted]"));
    fs.rmSync(raw);
  }
}

// ---------- sign-in helpers ----------
async function chromeLoginRequestId(timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  adb(["forward", "tcp:9222", "localabstract:chrome_devtools_remote"], { allowFailure: true });
  while (Date.now() < deadline) {
    try {
      const response = await fetch("http://127.0.0.1:9222/json/list", { signal: AbortSignal.timeout(3000) });
      for (const tab of await response.json()) {
        const id = requestIdFromUrl(tab.url);
        if (id) return { id, via: "chrome-devtools" };
      }
    } catch {
      adb(["forward", "tcp:9222", "localabstract:chrome_devtools_remote"], { allowFailure: true });
    }
    const list = nodes();
    for (const n of list) {
      const id = requestIdFromUrl(n.text.startsWith("http") ? n.text : `https://${n.text}`);
      if (id) return { id, via: "omnibox" };
    }
    // Chrome first-run / sign-in / notification interstitials.
    const interstitial = find(list, (n) =>
      ["Use without an account", "Accept & continue", "No thanks", "No, thanks", "Got it", "Continue", "Not now", "Skip"].includes(n.text.trim()) &&
      n.pkg.includes("chrome"));
    if (interstitial) {
      log(`dismissing Chrome interstitial "${interstitial.text}"`);
      tapNode(interstitial);
    }
    await sleep(2000);
  }
  fail("the browser never showed the app-login page (no /login/app?request= tab via DevTools or the omnibox)");
}

function requestIdFromUrl(url) {
  try {
    const parsed = new URL(url);
    if (!parsed.pathname.includes("/login/app")) return null;
    return parsed.searchParams.get("request");
  } catch {
    return null;
  }
}

async function raftJson(method, pathname, { token, body } = {}) {
  const response = await fetch(`${RAFT_API}${pathname}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  let json = null;
  try { json = await response.json(); } catch {}
  return { status: response.status, json };
}

// ---------- the flow ----------
async function main() {
  if (!APK_A || !A_CODE || !B_CODE) fail("--apk-a, --a-code and --b-code are required");
  if (!process.env.QA_TEST_EMAIL || !process.env.QA_TEST_PASSWORD) fail("QA_TEST_EMAIL / QA_TEST_PASSWORD are not set");
  let logcatChild = null;
  try {
    await step("prepare-device", async () => {
      adb(["root"], { allowFailure: true });
      adb(["wait-for-device"]);
      await sleep(3000);
      const rooted = shell("id -u").trim() === "0";
      if (!rooted) fail("adbd is not root; this test needs a google_apis (not playstore) image to read the Hands installation id");
      const flags = "_ --disable-fre --no-default-browser-check --no-first-run --disable-signin-promo";
      shell(`echo '${flags}' > /data/local/chrome-command-line; echo '${flags}' > /data/local/tmp/chrome-command-line; chmod 644 /data/local/chrome-command-line /data/local/tmp/chrome-command-line`, { allowFailure: true });
      shell("am set-debug-app --persistent com.android.chrome", { allowFailure: true });
      shell("input keyevent 82", { allowFailure: true });
      const chrome = shell("pm path com.android.chrome", { allowFailure: true }).trim();
      return { sdk: shell("getprop ro.build.version.sdk").trim(), abi: shell("getprop ro.product.cpu.abi").trim(), chrome: Boolean(chrome) };
    });

    await step("install-A", async () => {
      shell(`pm uninstall ${PKG}`, { allowFailure: true });
      adb(["install", "-r", "-g", APK_A], { timeoutMs: 240_000 });
      const code = installedVersionCode();
      if (code !== A_CODE) fail(`installed versionCode is ${code}, expected A=${A_CODE}`);
      return { version_code: code, version_name: installedVersionName() };
    });

    logcatChild = startCapture();

    await step("open-A-sign-in-page", async () => {
      launchApp();
      await waitFor('"Sign in to Raft"', "Sign in to Raft", { timeoutMs: 90_000 });
    });

    await step("sign-in", async () => {
      const list = nodes();
      const agreement = find(list, (n) => n.text.startsWith("I agree to the"));
      if (!agreement) fail('consent text "I agree to the ..." is not on the sign-in page');
      // The checkbox is the unlabelled 18dp box left of the agreement text.
      const box = find(list, (n) => n.clickable && !n.text && n.bounds[2] <= agreement.bounds[0] + 4 &&
        n.bounds[1] < agreement.bounds[3] && n.bounds[3] > agreement.bounds[1]);
      if (box) tapNode(box);
      else shell(`input tap ${Math.max(5, agreement.bounds[0] - 40)} ${agreement.bounds[1] + 30}`);
      await sleep(800);
      await tap('"Sign in to Raft"', (n) => n.text === "Sign in to Raft" && n.enabled);
      const request = await chromeLoginRequestId();
      log(`app-login request found via ${request.via}`);
      const login = await raftJson("POST", "auth/login", { body: { email: process.env.QA_TEST_EMAIL, password: process.env.QA_TEST_PASSWORD } });
      if (login.status !== 200 || !login.json?.accessToken) fail(`QA account API login failed (HTTP ${login.status})`);
      const approve = await raftJson("POST", `auth/app-login/requests/${encodeURIComponent(request.id)}/approve`, { token: login.json.accessToken });
      const redirect = approve.json?.redirectUrl;
      if (approve.status !== 200 || !redirect) fail(`approving the app-login request failed (HTTP ${approve.status} ${approve.json?.code ?? ""})`);
      shell(`am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${redirect.replace(/'/g, "")}' ${PKG} >/dev/null`);
      // Post-login: either Home directly or the server picker.
      const deadline = Date.now() + 90_000;
      while (Date.now() < deadline) {
        const now = nodes();
        if (find(now, isHome) && serverVisible(now)) return { via: request.via };
        const row = find(now, (n) => n.pkg === PKG && label(n).includes(SERVER) && !find(now, isHome));
        if (row) {
          log(`choosing server ${SERVER}`);
          tapNode(row);
        } else if (!now.some((n) => n.pkg === PKG)) {
          // Still in the browser: bring the app back; the callback already ran.
          launchApp();
        }
        await sleep(2000);
      }
      fail(`did not reach Home on server ${SERVER} after the sign-in callback`);
    });

    const deviceId = await step("hands-installation", async () => {
      let id = "";
      for (let i = 0; i < 30 && !id; i += 1) {
        const prefs = shell(`cat /data/data/${PKG}/shared_prefs/quiver_update.xml`, { allowFailure: true });
        id = /name="device_id">([^<]+)</.exec(prefs)?.[1] ?? "";
        if (!id) await sleep(2000);
      }
      if (!id) fail("the app never created its Hands installation id (shared_prefs/quiver_update.xml)");
      if (DEVICE_ID_FILE) fs.writeFileSync(DEVICE_ID_FILE, id, { mode: 0o600 });
      if (HANDS_GROUP) {
        const add = spawnSync("bash", ["scripts/qa/android_update_hands_fixture.sh", "add-member", HANDS_GROUP, id, `gha-run-${process.env.GITHUB_RUN_ID ?? "local"}`], { encoding: "utf8" });
        if (add.status !== 0) fail(`adding the emulator to the Hands device group failed: ${String(add.stderr).slice(0, 300)}`);
      }
      // Server-side check, exactly as the app asks: B must be offered to this installation.
      const url = `${HANDS_BASE}/public/v2/apps/raft-android/updates/check?channel=debug&product_type=android-apk&current_version_code=${A_CODE}&platform=android&filetype=apk&arch=x86_64`;
      let offered = 0;
      for (let i = 0; i < 10 && offered !== B_CODE; i += 1) {
        const response = await fetch(url, { headers: { accept: "application/json", "X-Hands-Device-Id": id } });
        const body = await response.json().catch(() => ({}));
        offered = body?.latest?.version_code ?? 0;
        if (offered !== B_CODE) await sleep(3000);
      }
      if (offered !== B_CODE) fail(`Hands offers versionCode ${offered || "nothing"} to this installation, expected B=${B_CODE}`);
      return { hands_offers: offered };
    });
    void deviceId;

    let channelName = "";
    await step("seed-draft", async () => {
      await backToHome();
      const channel = await waitFor("a channel row (# all)", (n) => n.pkg === PKG && n.text === "all", { timeoutMs: 30_000 })
        .catch(() => waitFor("any channel row", (n) => n.pkg === PKG && /^[a-z0-9][a-z0-9-]{2,}$/.test(n.text) && n.bounds[1] > 600, { timeoutMs: 10_000 }));
      channelName = channel.text;
      tapNode(channel);
      const composer = await waitFor(`the composer "Message #${channelName}"`, (n) => n.pkg === PKG && (n.text === `Message #${channelName}` || n.cls.includes("EditText")), { timeoutMs: 45_000 });
      tapNode(composer);
      await sleep(1200);
      shell(`input text ${DRAFT_TOKEN}`);
      await sleep(1500);
      await waitFor("the typed draft in the composer", (n) => n.text.includes(DRAFT_TOKEN), { timeoutMs: 10_000 });
      shell("input keyevent 111");
      await sleep(800);
      evidence("draft-typed");
      await backToHome();
      // Let the draft store flush. Diagnostic only: which app files hold the draft text
      // (an empty list before the update means the draft never reached storage).
      await sleep(5000);
      return { channel: channelName, draft: DRAFT_TOKEN, draft_files_before_update: draftFilesOnDisk() };
    });

    await step("update-prompt-offers-B", async () => {
      let relaunched = false;
      const banner = await waitFor('the "App update available" prompt on Home', (n) => n.pkg === PKG && n.text === "App update available", {
        timeoutMs: 180_000,
        intervalMs: 3000,
        onTick: async () => {
          // The check runs when Home loads; it may have run before the device joined the group.
          if (!relaunched) {
            relaunched = true;
            shell(`am force-stop ${PKG}`);
            await sleep(1000);
            launchApp();
            await sleep(5000);
          }
        },
      });
      tapNode(banner);
      const offered = await waitFor(`the update page naming B (${B_CODE})`, (n) => n.pkg === PKG && (n.text.includes(String(B_CODE)) || (B_NAME && n.text.includes(B_NAME))), { timeoutMs: 60_000 });
      return { offered_text: offered.text };
    });

    const installer = { permission_toggle: false, installer_confirm: false, installer_open: false };
    await step("download-and-install-B", async () => {
      await tap('the "Download" button', (n) => n.pkg === PKG && n.text === "Download" && n.enabled, { timeoutMs: 60_000 });
      const deadline = Date.now() + 15 * 60_000;
      let lastState = "";
      while (Date.now() < deadline) {
        if (installedVersionCode() === B_CODE) return installer;
        const list = nodes();
        const pkgs = new Set(list.map((n) => n.pkg));
        // 1. Unknown-sources settings page the app opens when it may not install yet.
        const allow = find(list, (n) => n.text === "Allow from this source");
        if (allow && pkgs.has("com.android.settings")) {
          const allowed = () => /:\s*allow/.test(shell(`appops get ${PKG} REQUEST_INSTALL_PACKAGES`, { allowFailure: true }));
          if (!allowed()) {
            // API 34 Settings is Compose (SpaActivity): the switch is not a Switch view, so tap
            // the row's switch area at the right edge, on the label's line.
            const toggle = find(list, (n) => n.cls.includes("Switch") && n.pkg === "com.android.settings");
            log("granting 'Install unknown apps' in Settings");
            if (toggle) tapNode(toggle);
            else {
              const width = Number(/(\d+)x\d+/.exec(shell("wm size"))?.[1] ?? 1080);
              shell(`input tap ${width - 90} ${Math.round((allow.bounds[1] + allow.bounds[3]) / 2)}`);
            }
            await sleep(1500);
            if (!allowed()) {
              tapNode(allow);
              await sleep(1500);
            }
            if (!allowed()) fail('could not switch on "Allow from this source" in Settings');
            installer.permission_toggle = true;
            evidence("unknown-sources-allowed");
          }
          shell("input keyevent 4");
          await sleep(2500);
          continue;
        }
        // 2. Android's package installer.
        const installerPkg = [...pkgs].find((p) => /packageinstaller/.test(p));
        if (installerPkg) {
          const confirm = find(list, (n) => n.pkg === installerPkg && ["Update", "UPDATE", "Install", "INSTALL"].includes(n.text) && n.enabled);
          const open = find(list, (n) => n.pkg === installerPkg && ["Open", "OPEN"].includes(n.text));
          if (confirm && !installer.installer_confirm) {
            evidence("system-installer");
            log(`system installer: tapping "${confirm.text}"`);
            tapNode(confirm);
            installer.installer_confirm = true;
          } else if (open) {
            installer.installer_open = true;
            evidence("installer-done");
            tapNode(open);
          }
          await sleep(2500);
          continue;
        }
        // 3. Our update page: once the download is done the action turns into Install.
        const install = find(list, (n) => n.pkg === PKG && n.text === "Install" && n.enabled);
        if (install && !installer.installer_confirm) {
          log('update page: tapping "Install"');
          tapNode(install);
          await sleep(2500);
          continue;
        }
        const state = list.filter((n) => n.pkg === PKG && /Download|Install|Retry|Preparing|Checking|failed/i.test(n.text)).map((n) => n.text).join(",");
        if (state !== lastState) {
          log(`update page state: ${state || "(none)"}`);
          lastState = state;
        }
        const retry = find(list, (n) => n.pkg === PKG && n.text === "Retry");
        if (retry && /failed/i.test(state)) fail(`the download failed in the app (${state})`);
        await sleep(3000);
      }
      fail(`B (${B_CODE}) was not installed within 15 minutes; installed versionCode is ${installedVersionCode()}; installer seen: ${JSON.stringify(installer)}`);
    });

    await step("reopen-on-B", async () => {
      const code = installedVersionCode();
      if (code !== B_CODE) fail(`installed versionCode is ${code}, expected B=${B_CODE}`);
      await sleep(3000);
      if (!nodes().some((n) => n.pkg === PKG)) launchApp();
      const list = await (async () => {
        await waitFor("Home after the update", (n) => isHome(n) || n.text === "Sign in to Raft", { timeoutMs: 90_000 });
        return nodes();
      })();
      if (find(list, "Sign in to Raft")) fail("B opened signed out: the session did not survive the update");
      if (!serverVisible(list)) fail(`B opened on Home but not on server ${SERVER}`);
      return { version_code: code, version_name: installedVersionName(), draft_files_after_update: draftFilesOnDisk() };
    });

    await step("draft-preserved", async () => {
      await backToHome();
      await tap(`the channel row "${channelName}"`, (n) => n.pkg === PKG && n.text === channelName, { timeoutMs: 30_000 });
      await waitFor(`the draft "${DRAFT_TOKEN}" in #${channelName}'s composer`, (n) => n.pkg === PKG && n.text.includes(DRAFT_TOKEN), { timeoutMs: 45_000 });
      // Clear it again so the shared QA account does not collect drafts.
      const field = find(nodes(), (n) => n.text.includes(DRAFT_TOKEN));
      if (field) {
        tapNode(field);
        shell("input keyevent 123");
        shell(`input keyevent ${Array(DRAFT_TOKEN.length + 5).fill("67").join(" ")}`);
        shell("input keyevent 111");
      }
      await backToHome();
    });

    await step("settings-about-on-B", async () => {
      // Best effort screenshot of Settings > About; the versionCode verdict comes from the package manager.
      try {
        const list = nodes();
        const tabs = list.filter((n) => n.pkg === PKG && n.bounds && n.bounds[1] > 2000 && label(n));
        const me = tabs.sort((a, b) => b.bounds[0] - a.bounds[0])[0];
        if (me) {
          tapNode(me);
          await sleep(2000);
          const about = find(nodes(), (n) => n.pkg === PKG && /^About/.test(n.text));
          if (about) {
            tapNode(about);
            await sleep(2500);
          }
        }
        const shown = nodes().some((n) => n.text.includes(String(B_CODE)));
        return { about_shows_b_code: shown };
      } catch (error) {
        return { about_error: error.message };
      }
    });

    summary.result = "pass";
  } catch (error) {
    summary.result = "fail";
    summary.failed_step = currentStep;
    summary.error = redact(error.message);
    console.error(`::error title=Android update e2e failed at ${currentStep}::${redact(error.message)}`);
    console.error(`FAILED at ${currentStep}: ${redact(error.message)}`);
  } finally {
    if (logcatChild) await stopCapture(logcatChild);
    fs.writeFileSync(path.join(OUT, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
  }
  process.exit(summary.result === "pass" ? 0 : 1);
}

main();
