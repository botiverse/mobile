#!/usr/bin/env node
// Deterministic Android realtime smoke (no model) for .github/workflows/android-realtime-e2e.yml. Runs inside
// the emulator-runner action against one booted emulator, on an Alpha APK (debug build type, where
// realtime_kmp_client_v1 defaults on: the common Kotlin Socket.IO client).
//
//   install -> sign in (QA account, the product's web sign-in approved through the API, as
//   android_update_e2e.mjs) -> open a channel -> the socket is the common client (logcat
//   `realtime_kmp_transport selected=kmp`) -> a message posted through the API from another session
//   appears live -> airplane mode: the socket drops; messages posted meanwhile -> network back: the
//   socket reconnects and the missed messages appear (resume / catch-up) -> switch to another server and
//   back through app links: each server connects on the common client, and a new message still arrives.
//
// Each step writes step-NN-<name>.png / .xml and a line to steps.jsonl; the first step that breaks fails
// the run with `FAILED at <step>: <why>`. A step that cannot run here (the QA account sees one server) is
// recorded as `skipped` with the reason.
//
// Never written to evidence: QA credentials, tokens, the app-login request id or code.
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, index, all) => {
    if (value.startsWith("--")) pairs.push([value.slice(2), all[index + 1]]);
    return pairs;
  }, []),
);
const OUT = args.out ?? "realtime-e2e-evidence";
const PKG = args.package ?? "build.raft.app.alpha";
const APK = args.apk;
const SERVER = args.server ?? "OranixSlockTest";
const CHANNEL = args.channel ?? "all";
const EXPECT_TRANSPORT = args["expect-transport"] ?? "kmp";
const RAFT_API = (process.env.QA_API_BASE || "https://api.raft.build/api/").replace(/\/?$/, "/");
const APP_LINK_BASE = (process.env.QA_APP_LINK_BASE || "https://app.raft.build").replace(/\/$/, "");
const RUN = process.env.GITHUB_RUN_ID ?? String(Date.now());
const SECRETS = [process.env.QA_TEST_EMAIL, process.env.QA_TEST_PASSWORD].filter(Boolean);

fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const redact = (text) => SECRETS.reduce((s, secret) => s.split(secret).join("[redacted]"), String(text));
let stepIndex = 0;
let currentStep = "setup";
const summary = { package: PKG, server: SERVER, channel: CHANNEL, expect_transport: EXPECT_TRANSPORT, steps: [] };

function log(message) {
  console.log(`[realtime-e2e] ${redact(message)}`);
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
class StepSkipped extends Error {}
const fail = (message) => {
  throw new StepFailure(message);
};
const skip = (message) => {
  throw new StepSkipped(message);
};

// ---------- UI helpers (uiautomator) ----------
function dumpXml() {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    shell("uiautomator dump /sdcard/realtime-e2e.xml >/dev/null 2>&1", { allowFailure: true, timeoutMs: 30_000 });
    const xml = adb(["exec-out", "cat", "/sdcard/realtime-e2e.xml"], { allowFailure: true });
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
  if (!hits.length) return null;
  return hits.reduce((a, b) => (area(b) < area(a) ? b : a));
}

function tapNode(node) {
  const [x1, y1, x2, y2] = node.bounds;
  shell(`input tap ${Math.round((x1 + x2) / 2)} ${Math.round((y1 + y2) / 2)}`);
}

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
    if (error instanceof StepSkipped) {
      evidence(`${name}-SKIPPED`);
      const record = { step: stepIndex, name, result: "skipped", ms: Date.now() - started, reason: redact(error.message) };
      summary.steps.push(record);
      fs.appendFileSync(path.join(OUT, "steps.jsonl"), `${JSON.stringify(record)}\n`);
      log(`step ${stepIndex} skipped: ${error.message}`);
      return null;
    }
    evidence(`${name}-FAILED`);
    const record = { step: stepIndex, name, result: "fail", ms: Date.now() - started, error: redact(error.message) };
    summary.steps.push(record);
    fs.appendFileSync(path.join(OUT, "steps.jsonl"), `${JSON.stringify(record)}\n`);
    throw error;
  }
}

function launchApp() {
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`, { allowFailure: true });
}

const isHome = (n) => n.text === "Drafts" && n.pkg === PKG;
const serverVisible = (list, name = SERVER) => list.some((n) => n.pkg === PKG && label(n).includes(name));

// ---------- logcat ----------
let logcatChild = null;
const LOGCAT_RAW = path.join(OUT, "logcat-raw.txt");

function startCapture() {
  adb(["logcat", "-c"], { allowFailure: true });
  const fd = fs.openSync(LOGCAT_RAW, "w");
  logcatChild = spawn("adb", ["logcat", "-v", "threadtime"], { stdio: ["ignore", fd, fd] });
}

function logcatText() {
  try {
    return fs.readFileSync(LOGCAT_RAW, "utf8");
  } catch {
    return "";
  }
}

/** Lines of the realtime stack (both socket implementations and the client above them). */
function realtimeLines(text = logcatText()) {
  return text.split("\n").filter((line) => /realtime_kmp|client_connected|client_reconnected|client_disconnected|rooms_joined|sync_resume|sync_horizon|catch_up|transport_connect|transport_disconnect/.test(line));
}

/** Waits until [predicate] holds for the logcat lines written after [fromLength] characters. */
async function waitForLog(description, predicate, { fromLength = 0, timeoutMs = 60_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const lines = realtimeLines(logcatText().slice(fromLength));
    const hit = predicate(lines);
    if (hit) return hit;
    await sleep(1000);
  }
  const tail = realtimeLines(logcatText().slice(fromLength)).slice(-15).join("\n");
  fail(`timed out after ${Math.round(timeoutMs / 1000)}s waiting in logcat for ${description}; last realtime lines:\n${tail || "(none)"}`);
}

async function stopCapture() {
  try { logcatChild?.kill("SIGTERM"); } catch {}
  await sleep(1000);
  if (fs.existsSync(LOGCAT_RAW)) {
    const clean = redact(fs.readFileSync(LOGCAT_RAW, "utf8"))
      .replace(/(code|request|state|token|access_token|refresh_token)=[^&\s"]+/gi, "$1=[redacted]");
    fs.writeFileSync(path.join(OUT, "logcat.txt"), clean);
    fs.writeFileSync(path.join(OUT, "realtime-lines.txt"), realtimeLines(clean).join("\n"));
    fs.rmSync(LOGCAT_RAW);
  }
}

// ---------- network ----------
function setNetwork(online) {
  if (online) {
    shell("cmd connectivity airplane-mode disable", { allowFailure: true });
    shell("svc wifi enable", { allowFailure: true });
    shell("svc data enable", { allowFailure: true });
  } else {
    shell("cmd connectivity airplane-mode enable", { allowFailure: true });
    shell("svc wifi disable", { allowFailure: true });
    shell("svc data disable", { allowFailure: true });
  }
}

// ---------- Raft API ----------
async function raftJson(method, pathname, { token, serverId, body } = {}) {
  const response = await fetch(`${RAFT_API}${pathname}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(serverId ? { "X-Server-Id": serverId } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  let json = null;
  try { json = await response.json(); } catch {}
  return { status: response.status, json };
}

async function apiLogin() {
  const login = await raftJson("POST", "auth/login", { body: { email: process.env.QA_TEST_EMAIL, password: process.env.QA_TEST_PASSWORD } });
  if (login.status !== 200 || !login.json?.accessToken) fail(`QA account API login failed (HTTP ${login.status})`);
  return login.json.accessToken;
}

const listOf = (body, key) => (Array.isArray(body) ? body : body?.[key]) ?? [];

async function channelOf(token, server, preferred) {
  const inbox = await raftJson("GET", "channels/inbox?filter=channels&limit=50&offset=0", { token, serverId: server.id });
  const channels = listOf(inbox.json, "channels");
  return channels.find((c) => c?.name === preferred) ?? channels.find((c) => c?.id && c?.name) ?? null;
}

async function postMessage(api, server, channel, text) {
  const posted = await raftJson("POST", "messages", { token: api.token, serverId: server.id, body: { channelId: channel.id, content: text } });
  if (posted.status >= 300) fail(`posting a message through the API failed (HTTP ${posted.status} ${posted.json?.code ?? ""})`);
  return posted.json?.id ?? posted.json?.message?.id ?? null;
}

// ---------- sign-in (as android_update_e2e.mjs) ----------
function requestIdFromUrl(url) {
  try {
    const parsed = new URL(url);
    if (!parsed.pathname.includes("/login/app")) return null;
    return parsed.searchParams.get("request");
  } catch {
    return null;
  }
}

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

async function openAppLink(server, channel) {
  shell(`am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${APP_LINK_BASE}/s/${server.slug}/channel/${channel.id}' ${PKG} >/dev/null`, { allowFailure: true });
  await sleep(2000);
}

async function waitForMessage(text, timeoutMs) {
  return waitFor(`the message "${text}" on screen`, (n) => n.pkg === PKG && label(n).includes(text), { timeoutMs, intervalMs: 2000 });
}

// ---------- the flow ----------
async function main() {
  if (!APK) fail("--apk is required");
  if (!process.env.QA_TEST_EMAIL || !process.env.QA_TEST_PASSWORD) fail("QA_TEST_EMAIL / QA_TEST_PASSWORD are not set");
  const api = { token: "", home: null, homeChannel: null, other: null, otherChannel: null };
  try {
    await step("prepare-device", async () => {
      adb(["root"], { allowFailure: true });
      adb(["wait-for-device"]);
      await sleep(3000);
      const flags = "_ --disable-fre --no-default-browser-check --no-first-run --disable-signin-promo";
      shell(`echo '${flags}' > /data/local/chrome-command-line; echo '${flags}' > /data/local/tmp/chrome-command-line; chmod 644 /data/local/chrome-command-line /data/local/tmp/chrome-command-line`, { allowFailure: true });
      shell("am set-debug-app --persistent com.android.chrome", { allowFailure: true });
      shell("input keyevent 82", { allowFailure: true });
      setNetwork(true);
      return { sdk: shell("getprop ro.build.version.sdk").trim(), abi: shell("getprop ro.product.cpu.abi").trim() };
    });

    await step("api-fixture", async () => {
      api.token = await apiLogin();
      const servers = listOf((await raftJson("GET", "servers", { token: api.token })).json, "servers");
      api.home = servers.find((s) => s?.name === SERVER) ?? null;
      if (!api.home?.id) fail(`the QA account does not see server ${SERVER}`);
      api.homeChannel = await channelOf(api.token, api.home, CHANNEL);
      if (!api.homeChannel?.id) fail(`no channel found on ${SERVER}`);
      api.other = servers.find((s) => s?.id && s.id !== api.home.id && s.slug) ?? null;
      if (api.other) api.otherChannel = await channelOf(api.token, api.other, "all");
      // Names only (no ids) in the evidence.
      return { servers: servers.length, channel: api.homeChannel.name, other_server: api.other?.name ?? null };
    });

    await step("install", async () => {
      shell(`pm uninstall ${PKG}`, { allowFailure: true });
      adb(["install", "-r", "-g", APK], { timeoutMs: 240_000 });
      const version = /versionName=(\S+)/.exec(shell(`dumpsys package ${PKG}`, { allowFailure: true }))?.[1] ?? "";
      if (!version) fail(`${PKG} is not installed after adb install`);
      return { version_name: version };
    });

    startCapture();

    await step("sign-in", async () => {
      launchApp();
      await waitFor('"Sign in to Raft"', "Sign in to Raft", { timeoutMs: 90_000 });
      const list = nodes();
      const agreement = find(list, (n) => n.text.startsWith("I agree to the"));
      if (!agreement) fail('consent text "I agree to the ..." is not on the sign-in page');
      const box = find(list, (n) => n.clickable && !n.text && n.bounds[2] <= agreement.bounds[0] + 4 &&
        n.bounds[1] < agreement.bounds[3] && n.bounds[3] > agreement.bounds[1]);
      if (box) tapNode(box);
      else shell(`input tap ${Math.max(5, agreement.bounds[0] - 40)} ${agreement.bounds[1] + 30}`);
      await sleep(800);
      await tap('"Sign in to Raft"', (n) => n.text === "Sign in to Raft" && n.enabled);
      const request = await chromeLoginRequestId();
      log(`app-login request found via ${request.via}`);
      const approve = await raftJson("POST", `auth/app-login/requests/${encodeURIComponent(request.id)}/approve`, { token: api.token });
      const redirect = approve.json?.redirectUrl;
      if (approve.status !== 200 || !redirect) fail(`approving the app-login request failed (HTTP ${approve.status} ${approve.json?.code ?? ""})`);
      shell(`am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${redirect.replace(/'/g, "")}' ${PKG} >/dev/null`);
      const deadline = Date.now() + 90_000;
      while (Date.now() < deadline) {
        const now = nodes();
        if (find(now, isHome) && serverVisible(now)) return { via: request.via };
        const row = find(now, (n) => n.pkg === PKG && label(n).includes(SERVER) && !find(now, isHome));
        if (row) {
          log(`choosing server ${SERVER}`);
          tapNode(row);
        } else if (!now.some((n) => n.pkg === PKG)) {
          launchApp();
        }
        await sleep(2000);
      }
      fail(`did not reach Home on server ${SERVER} after the sign-in callback`);
    });

    await step("open-channel", async () => {
      if (api.home.slug) await openAppLink(api.home, api.homeChannel);
      else await tap(`the channel row "${api.homeChannel.name}"`, (n) => n.pkg === PKG && n.text === api.homeChannel.name, { timeoutMs: 30_000 });
      const composer = await waitFor(`the composer of #${api.homeChannel.name}`, (n) => n.pkg === PKG && (n.text === `Message #${api.homeChannel.name}` || n.cls.includes("EditText")), { timeoutMs: 60_000 });
      return { composer: composer.text || composer.cls };
    });

    await step("socket-is-common-client", async () => {
      const selected = await waitForLog("realtime_kmp_transport selected=", (lines) => lines.find((l) => l.includes("realtime_kmp_transport selected=")), { timeoutMs: 60_000 });
      const kind = /selected=(\w+)/.exec(selected)?.[1];
      const source = /source=(\w+)/.exec(selected)?.[1];
      if (kind !== EXPECT_TRANSPORT) fail(`the realtime socket is ${kind} (source=${source}); expected ${EXPECT_TRANSPORT}: ${selected.trim()}`);
      await waitForLog("rooms_joined on the connected socket", (lines) => lines.find((l) => l.includes("rooms_joined")), { timeoutMs: 60_000 });
      const connected = realtimeLines().filter((l) => l.includes("realtime_kmp transport_connected")).length;
      if (EXPECT_TRANSPORT === "kmp" && connected === 0) fail("no `realtime_kmp transport_connected` line: the common client never connected");
      return { selected: kind, source, kmp_connected_lines: connected };
    });

    await step("receive-live-message", async () => {
      const text = `realtime e2e live ${RUN}`;
      const started = Date.now();
      await postMessage(api, api.home, api.homeChannel, text);
      await waitForMessage(text, 45_000);
      return { shown_after_ms: Date.now() - started };
    });

    const offlineTexts = [`realtime e2e offline A ${RUN}`, `realtime e2e offline B ${RUN}`];
    let offlineMark = 0;
    await step("network-down", async () => {
      offlineMark = logcatText().length;
      setNetwork(false);
      // The socket notices the loss (OkHttp / Engine.IO ping); the client logs its disconnect.
      const down = await waitForLog("the socket dropping", (lines) =>
        lines.find((l) => /client_disconnected|realtime_kmp transport_disconnected/.test(l)), { fromLength: offlineMark, timeoutMs: 90_000 }).catch((error) => {
        log(`no disconnect line yet (${error.message.split(";")[0]}); posting anyway`);
        return null;
      });
      for (const text of offlineTexts) await postMessage(api, api.home, api.homeChannel, text);
      await sleep(5000);
      const leaked = nodes().some((n) => n.pkg === PKG && offlineTexts.some((t) => label(n).includes(t)));
      return { disconnect_logged: Boolean(down), shown_while_offline: leaked };
    });

    await step("network-back-reconnect-and-catch-up", async () => {
      const mark = logcatText().length;
      const started = Date.now();
      setNetwork(true);
      const reconnected = await waitForLog("the socket reconnecting (rooms_joined after the outage)", (lines) => lines.find((l) => l.includes("rooms_joined")), { fromLength: mark, timeoutMs: 120_000 });
      for (const text of offlineTexts) await waitForMessage(text, 90_000);
      const after = realtimeLines(logcatText().slice(offlineMark));
      return {
        reconnect_ms: Date.now() - started,
        resume_emitted: after.some((l) => l.includes("sync_resume_emit")),
        catch_up: after.some((l) => /catch_up|RealtimeRefreshRequired/.test(l)),
        kmp_reconnect_lines: after.filter((l) => l.includes("realtime_kmp transport_connected")).length,
        rooms_joined: reconnected.trim().slice(-160),
      };
    });

    await step("switch-server-and-back", async () => {
      if (!api.other || !api.otherChannel?.id) skip("the QA account sees no second server with a channel");
      if (!api.home.slug) skip(`no slug for ${SERVER} in GET servers: the app link switch cannot be built`);
      let mark = logcatText().length;
      await openAppLink(api.other, api.otherChannel);
      const otherSelected = await waitForLog(`a socket for ${api.other.name}`, (lines) =>
        lines.find((l) => l.includes("realtime_kmp_transport selected=") && l.includes(`serverId=${api.other.id}`)), { fromLength: mark, timeoutMs: 90_000 });
      await waitForLog(`rooms_joined on ${api.other.name}`, (lines) => lines.find((l) => l.includes("rooms_joined") && l.includes(api.other.id)), { fromLength: mark, timeoutMs: 90_000 });
      evidence("on-other-server");
      mark = logcatText().length;
      await openAppLink(api.home, api.homeChannel);
      const homeSelected = await waitForLog(`a socket for ${SERVER} again`, (lines) =>
        lines.find((l) => l.includes("realtime_kmp_transport selected=") && l.includes(`serverId=${api.home.id}`)), { fromLength: mark, timeoutMs: 90_000 });
      await waitForLog(`rooms_joined on ${SERVER}`, (lines) => lines.find((l) => l.includes("rooms_joined") && l.includes(api.home.id)), { fromLength: mark, timeoutMs: 90_000 });
      const text = `realtime e2e after switch ${RUN}`;
      await postMessage(api, api.home, api.homeChannel, text);
      await waitForMessage(text, 45_000);
      const kind = (line) => /selected=(\w+)/.exec(line)?.[1];
      if (kind(otherSelected) !== EXPECT_TRANSPORT || kind(homeSelected) !== EXPECT_TRANSPORT) {
        fail(`after the switch the sockets were ${kind(otherSelected)} / ${kind(homeSelected)}; expected ${EXPECT_TRANSPORT}`);
      }
      return { other_server: api.other.name, other: kind(otherSelected), back: kind(homeSelected) };
    });

    summary.result = "pass";
  } catch (error) {
    summary.result = "fail";
    summary.failed_step = currentStep;
    summary.error = redact(error.message);
    console.error(`::error title=Android realtime e2e failed at ${currentStep}::${redact(error.message).split("\n")[0]}`);
    console.error(`FAILED at ${currentStep}: ${redact(error.message)}`);
  } finally {
    setNetwork(true);
    await stopCapture();
    fs.writeFileSync(path.join(OUT, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
  }
  process.exit(summary.result === "pass" ? 0 : 1);
}

main();
