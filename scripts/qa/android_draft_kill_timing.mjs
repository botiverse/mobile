#!/usr/bin/env node
// Composer draft vs. force-stop timing acceptance (task #246), for .github/workflows/android-realtime-e2e.yml
// (input scenario=draft-kill-timing). Runs inside the emulator-runner action against one booted emulator, on a
// debuggable Alpha APK, which writes `RaftDraftTiming` logcat lines (RaftDraftTimingMarker: sequence, text
// length and SystemClock.uptimeMillis(), never text).
//
//   install -> sign in (QA account; the same web sign-in approved through the API as android_realtime_e2e.mjs)
//   -> open the QA test channel -> positive control (type, no kill: change_received and draft_written lines)
//   -> kill runs: the prefix of a marker is typed, then ONE `adb shell` does
//        `cmd input keyevent <last char>; sleep D; cmd activity force-stop <pkg>`
//      so no host round trip sits between the last keystroke and the kill. Δ = ActivityManager's
//      "Killing <old pid>:<pkg>" line (logcat -v monotonic) − the last change_received uptimeMs, both
//      CLOCK_MONOTONIC on the device. A run is a timing "hit" only when Δ is measured and inside the bucket.
//      Then the app is relaunched on the same channel and the composer text (UI tree) is compared with the marker.
//   Buckets D = 0.05 / 0.1 / 0.3 / 0.7 s (round robin), fast typing (3-5 s of keystrokes on the device, then
//   D = 0.05 / 0.1), and regressions: (a) wait 1 s then kill, (b) send then kill within 0.1 s (composer
//   empty, the sent text not back as a draft), (c) Home then kill.
//
// Output (--out): summary.json, results.md (the table), timing-lines.txt (RaftDraftTiming, the probe tag,
// ActivityManager lines of the package, libprocessgroup), step screenshots of failures. Never written:
// QA credentials, tokens, the app-login request id, account emails.
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { analyzeKillRun, bucketFor, markdownTable, parseKillLine, parseLogcatLine, summarize } from "./draft_kill_timing_analysis.mjs";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, index, all) => {
    if (value.startsWith("--")) pairs.push([value.slice(2), all[index + 1]]);
    return pairs;
  }, []),
);
const OUT = args.out ?? "draft-kill-timing-evidence";
const PKG = args.package ?? "build.raft.app.alpha";
const APK = args.apk;
const SERVER = args.server ?? "OranixSlockTest";
// The QA test channel of the QA server (docs/qa-acceptance/release-auto-login.md); never a work channel.
const CHANNEL = args.channel ?? "qa-test-create-7842";
const RUNS_PER_BUCKET = Number(args["runs-per-bucket"] ?? 10);
const FAST_RUNS = Number(args["fast-runs"] ?? 5);
const DELAYS = (args.delays ?? "0.05,0.1,0.3,0.7").split(",").map(Number).filter((d) => d > 0);
const FAST_DELAYS = [0.05, 0.1];
const RAFT_API = (process.env.QA_API_BASE || "https://api.raft.build/api/").replace(/\/?$/, "/");
const APP_LINK_BASE = (process.env.QA_APP_LINK_BASE || "https://app.raft.build").replace(/\/$/, "");
const RUN_TAG = (process.env.GITHUB_RUN_ID ?? String(Date.now())).slice(-5);
const SECRETS = [process.env.QA_TEST_EMAIL, process.env.QA_TEST_PASSWORD].filter(Boolean);
const LAST_CHAR = "q";
const LAST_KEY = "KEYCODE_Q";

fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const redact = (text) => SECRETS.reduce((s, secret) => s.split(secret).join("[redacted]"), String(text));
const summary = { scenario: "draft-kill-timing", package: PKG, server: SERVER, channel: CHANNEL, steps: [], control: null, runs: [] };
let currentStep = "setup";

function log(message) {
  console.log(`[draft-kill] ${redact(message)}`);
}

function adb(argv, { allowFailure = false, timeoutMs = 60_000, binary = false } = {}) {
  const result = spawnSync("adb", argv, { encoding: binary ? undefined : "utf8", maxBuffer: 64 * 1024 * 1024, timeout: timeoutMs });
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

// ---------- UI helpers (uiautomator; as android_realtime_e2e.mjs) ----------
function dumpXml() {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    shell("uiautomator dump /sdcard/draft-kill.xml >/dev/null 2>&1", { allowFailure: true, timeoutMs: 30_000 });
    const xml = adb(["exec-out", "cat", "/sdcard/draft-kill.xml"], { allowFailure: true });
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
      hint: attr("hint"),
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
const center = (n) => [Math.round((n.bounds[0] + n.bounds[2]) / 2), Math.round((n.bounds[1] + n.bounds[3]) / 2)];

function find(list, matcher) {
  const test = typeof matcher === "function" ? matcher : (n) => n.text.trim() === matcher || n.desc.trim() === matcher;
  const hits = list.filter((n) => n.bounds && test(n));
  if (!hits.length) return null;
  return hits.reduce((a, b) => (area(b) < area(a) ? b : a));
}

function tapNode(node) {
  const [x, y] = center(node);
  shell(`input tap ${x} ${y}`);
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
  const visible = list.map(label).filter(Boolean).slice(0, 40).join(" | ");
  fail(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${description}; on screen: ${visible || "(nothing labelled)"}`);
}

async function tap(description, matcher, options) {
  const node = await waitFor(description, matcher, options);
  tapNode(node);
  await sleep(1200);
  return node;
}

function screenshot(name) {
  try {
    fs.writeFileSync(path.join(OUT, `${name}.png`), adb(["exec-out", "screencap", "-p"], { binary: true, allowFailure: true }));
  } catch (error) {
    log(`screenshot failed: ${error.message}`);
  }
}

async function step(name, body) {
  currentStep = name;
  const started = Date.now();
  log(`step: ${name}`);
  try {
    const detail = await body();
    summary.steps.push({ name, result: "pass", ms: Date.now() - started, ...(detail ? { detail } : {}) });
    return detail;
  } catch (error) {
    screenshot(`step-${name}-FAILED`);
    summary.steps.push({ name, result: "fail", ms: Date.now() - started, error: redact(error.message) });
    throw error;
  }
}

function launchApp() {
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`, { allowFailure: true });
}

const isHome = (n) => n.text === "Drafts" && n.pkg === PKG;
const serverVisible = (list, name = SERVER) => list.some((n) => n.pkg === PKG && label(n).includes(name));

// ---------- logcat (-v monotonic: CLOCK_MONOTONIC seconds, the clock of SystemClock.uptimeMillis) ----------
let logcatChild = null;
const LOGCAT_RAW = path.join(OUT, "logcat-raw.txt");

function startCapture() {
  adb(["logcat", "-c"], { allowFailure: true });
  const fd = fs.openSync(LOGCAT_RAW, "w");
  logcatChild = spawn("adb", ["logcat", "-v", "monotonic", "-b", "main", "-b", "system", "-b", "crash"], { stdio: ["ignore", fd, fd] });
}

const logcatText = () => {
  try {
    return fs.readFileSync(LOGCAT_RAW, "utf8");
  } catch {
    return "";
  }
};
const logcatMark = () => logcatText().length;
const linesSince = (mark) => logcatText().slice(mark).split("\n");

/** Lines kept as evidence: no other app or framework chatter, nothing that could carry a token. */
const isTimingEvidence = (line) =>
  /\bRaftDraftTiming(Probe)?\s*:/.test(line) ||
  (/\bActivityManager\s*:/.test(line) && line.includes(PKG)) ||
  /\blibprocessgroup\s*:/.test(line);

async function waitForTiming(description, predicate, { fromMark, timeoutMs = 15_000 }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = linesSince(fromMark).find((line) => line.includes("RaftDraftTiming") && predicate(line));
    if (hit) return hit;
    await sleep(250);
  }
  return null;
}

async function stopCapture() {
  try {
    logcatChild?.kill("SIGTERM");
  } catch {}
  await sleep(1000);
  if (fs.existsSync(LOGCAT_RAW)) {
    const kept = redact(fs.readFileSync(LOGCAT_RAW, "utf8")).split("\n").filter(isTimingEvidence);
    fs.writeFileSync(path.join(OUT, "timing-lines.txt"), `${kept.join("\n")}\n`);
    fs.rmSync(LOGCAT_RAW);
  }
}

// ---------- Raft API (QA account; as android_realtime_e2e.mjs) ----------
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
  try {
    json = await response.json();
  } catch {}
  return { status: response.status, json };
}

async function apiLogin() {
  const login = await raftJson("POST", "auth/login", { body: { email: process.env.QA_TEST_EMAIL, password: process.env.QA_TEST_PASSWORD } });
  if (login.status !== 200 || !login.json?.accessToken) fail(`QA account API login failed (HTTP ${login.status})`);
  return login.json.accessToken;
}

const listOf = (body, key) => (Array.isArray(body) ? body : body?.[key]) ?? [];

async function namedChannel(token, server, name) {
  let channels = listOf((await raftJson("GET", "channels", { token, serverId: server.id })).json, "channels");
  if (!channels.length) {
    const inbox = (await raftJson("GET", "channels/inbox?limit=50&offset=0", { token, serverId: server.id })).json;
    channels = listOf(inbox, "items").map((row) => row?.channel ?? row);
  }
  // Exactly the named QA test channel: never fall back to another (possibly a work) channel.
  return channels.find((c) => c?.id && c?.name === name && c?.type !== "dm") ?? null;
}

async function recentMessageTexts(api) {
  const res = await raftJson("GET", `messages/channel/${api.channel.id}?limit=30`, { token: api.token, serverId: api.server.id });
  const rows = listOf(res.json, "messages");
  return rows.map((m) => String(m?.content ?? ""));
}

// ---------- sign-in (as android_realtime_e2e.mjs) ----------
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

async function signIn(api) {
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
    if (row) tapNode(row);
    else if (!now.some((n) => n.pkg === PKG)) launchApp();
    await sleep(2000);
  }
  fail(`did not reach Home on server ${SERVER} after the sign-in callback`);
}

// ---------- composer ----------
let INPUT = "input";
let FORCE_STOP = "am force-stop";
const placeholder = () => `Message #${CHANNEL}`;
const isComposer = (n) => n.pkg === PKG && n.cls.includes("EditText");
/** The composer's text from the UI tree; the placeholder reads as empty. */
const composerText = (n) => (n.text === placeholder() || n.text === n.hint ? "" : n.text);

async function openChannel(api) {
  shell(`am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${APP_LINK_BASE}/s/${api.server.slug}/channel/${api.channel.id}' ${PKG} >/dev/null`, { allowFailure: true });
  return waitFor(`the composer of #${CHANNEL}`, isComposer, { timeoutMs: 60_000, intervalMs: 1000 });
}

function pidOf() {
  const out = shell(`pidof ${PKG}`, { allowFailure: true }).trim().split(/\s+/).filter(Boolean).map(Number);
  return out.length === 1 ? out[0] : out[0] ?? null;
}

/** Composer open, focused and empty (cleared through key events, so the clear goes through the draft too). */
async function readyEmptyComposer(api) {
  let composer = find(nodes(), isComposer) ?? (await openChannel(api));
  for (let attempt = 0; attempt < 3; attempt += 1) {
    tapNode(composer);
    await sleep(400);
    const text = composerText(composer);
    if (!text) return composer;
    shell(`${INPUT} keyevent KEYCODE_MOVE_END`, { allowFailure: true });
    shell(`${INPUT} keyevent ${Array(text.length + 4).fill("67").join(" ")}`, { allowFailure: true });
    await sleep(1200);
    composer = find(nodes(), isComposer) ?? (await openChannel(api));
  }
  if (composerText(composer)) fail(`could not clear the composer (len ${composerText(composer).length})`);
  return composer;
}

/** After relaunch: the composer's text once it equals [expected], or the last text seen in [timeoutMs]. */
async function composerAfterRelaunch(api, expected, timeoutMs = 15_000) {
  const composer = await openChannel(api);
  let seen = composerText(composer);
  const deadline = Date.now() + timeoutMs;
  while (seen !== expected && Date.now() < deadline) {
    await sleep(1000);
    const node = find(nodes(), isComposer);
    if (node) seen = composerText(node);
  }
  return seen;
}

const describeText = (text) => (text === "" ? "(empty)" : /^[a-z0-9]+$/.test(text) ? `${text} (len ${text.length})` : `(other text, len ${text.length})`);

// ---------- runs ----------
let runIndex = 0;
const markerPrefix = () => `dk${RUN_TAG}r${String(runIndex).padStart(2, "0")}x`;

/**
 * One kill run. [mode]: "bucket" (prefix typed, then last key + sleep D + kill in one shell),
 * "fast" (the whole text typed key by key on the device for 3-5 s, then last key + sleep D + kill),
 * "home" (last key, Home, sleep 0.1, kill), "send" (tap Send, sleep 0.1, kill).
 */
async function killRun(api, { group, mode, delaySec }) {
  runIndex += 1;
  const record = { group, mode, delay_sec: delaySec, run: runIndex };
  try {
    const composer = await readyEmptyComposer(api);
    const prefix = markerPrefix();
    let expected = `${prefix}${LAST_CHAR}`;
    let fastChars = "";
    if (mode === "fast") {
      fastChars = "abcdefghijklmnoprstuvwxyz0123456789abcdefghijklmnoprstuvwxyz"; // 60 keys, no q
      expected = `${prefix}${fastChars}${LAST_CHAR}`;
    }
    if (mode === "send") {
      // Whole text in the composer and durable, then Send and kill.
      let mark = logcatMark();
      shell(`${INPUT} text ${expected}`);
      if (!(await waitForTiming("the full text written", (l) => l.includes(`draft_written`) && l.includes(` len=${expected.length} `), { fromMark: mark }))) {
        record.note = "full text never reported written before send";
      }
      const send = find(nodes(), (n) => n.pkg === PKG && n.desc === "Send" && n.enabled);
      if (!send) fail("no enabled Send button");
      const [x, y] = center(send);
      const oldPid = pidOf();
      mark = logcatMark();
      const out = shell(
        `log -t RaftDraftTimingProbe "run=${runIndex} send_tap"; ${INPUT} tap ${x} ${y}; sleep 0.1; ${FORCE_STOP} ${PKG}; ` +
          `if [ -d /proc/${oldPid} ]; then echo PID_ALIVE; else echo PID_GONE; fi`,
        { timeoutMs: 30_000 },
      );
      await sleep(2000);
      const pidGone = !shell(`ls -d /proc/${oldPid} 2>/dev/null`, { allowFailure: true }).trim();
      // The text was typed (and written) before the mark, so Δ here is the Send tap → kill: the probe line
      // logged right before the tap to ActivityManager's kill line, both on the device's monotonic clock.
      const parsed = linesSince(mark).map(parseLogcatLine).filter(Boolean);
      const probe = parsed.find((l) => l.tag === "RaftDraftTimingProbe" && l.message.includes(`run=${runIndex} send_tap`));
      const kill = parsed.find((l) => l.tag === "ActivityManager" && parseKillLine(l.message, PKG) === oldPid);
      Object.assign(record, {
        old_pid: oldPid,
        kill_pid: kill ? oldPid : null,
        pid_gone: pidGone,
        pid_gone_immediately: out.includes("PID_GONE"),
        delta_ms: probe && kill ? Math.round((kill.tsMs - probe.tsMs) * 1000) / 1000 : null,
        delta_from: "send tap",
        timing: probe && kill && pidGone ? "measured" : "unconfirmed",
        reason: !kill ? "no ActivityManager kill line for the old pid" : !probe ? "no send_tap probe line" : !pidGone ? "old pid still alive after the kill" : null,
      });
      const seen = await composerAfterRelaunch(api, "", 8_000);
      const sentOnServer = (await recentMessageTexts(api)).some((t) => t.includes(expected));
      record.sent_on_server = sentOnServer;
      record.expected_desc = sentOnServer ? "(empty)" : `(empty) or ${expected} (not sent)`;
      record.observed_desc = describeText(seen);
      record.new_pid = pidOf();
      record.pass = sentOnServer ? seen === "" : seen === "" || seen === expected;
      if (!sentOnServer) record.reason = `${record.reason ? `${record.reason}; ` : ""}message not on server after the kill`;
      return record;
    }

    let mark = logcatMark();
    if (mode !== "fast") {
      shell(`${INPUT} text ${prefix}`);
      if (!(await waitForTiming("the prefix", (l) => l.includes("change_received") && l.includes(` len=${prefix.length} `), { fromMark: mark }))) {
        record.note = "no change_received for the prefix";
      }
      await sleep(300);
    } else {
      shell(`${INPUT} text ${prefix}`);
      await waitForTiming("the prefix", (l) => l.includes("change_received") && l.includes(` len=${prefix.length} `), { fromMark: mark });
      await sleep(300);
    }
    const oldPid = pidOf();
    if (!oldPid) fail("the app has no pid before the kill");
    mark = logcatMark();
    const typeFast = mode === "fast" ? `for c in ${fastChars.split("").join(" ")}; do ${INPUT} text $c; sleep 0.03; done; ` : "";
    const home = mode === "home" ? `${INPUT} keyevent KEYCODE_HOME; ` : "";
    const sleepSec = mode === "home" ? 0.1 : delaySec;
    const out = shell(
      `log -t RaftDraftTimingProbe "run=${runIndex} mode=${mode} D=${sleepSec} start"; ${typeFast}` +
        `${INPUT} keyevent ${LAST_KEY}; ${home}sleep ${sleepSec}; ${FORCE_STOP} ${PKG}; ` +
        `if [ -d /proc/${oldPid} ]; then echo PID_ALIVE; else echo PID_GONE; fi`,
      { timeoutMs: 60_000 },
    );
    await sleep(2000);
    const pidGone = !shell(`ls -d /proc/${oldPid} 2>/dev/null`, { allowFailure: true }).trim();
    const lines = linesSince(mark);
    const analysis = analyzeKillRun({ lines, pkg: PKG, oldPid, expectedLen: expected.length, delaySec: mode === "home" ? null : delaySec, pidGone });
    Object.assign(record, analysis, { pid_gone_immediately: out.includes("PID_GONE") });
    if (mode === "fast") {
      const changes = lines.filter((l) => l.includes("RaftDraftTiming") && l.includes("change_received")).map((l) => Number(/uptimeMs=(\d+)/.exec(l)?.[1]));
      record.typing_ms = changes.length > 1 ? changes.at(-1) - changes[0] : null;
    }
    const seen = await composerAfterRelaunch(api, expected);
    record.expected_desc = describeText(expected);
    record.observed_desc = describeText(seen);
    record.new_pid = pidOf();
    record.pass = seen === expected;
    return record;
  } catch (error) {
    record.timing = record.timing ?? "unconfirmed";
    record.pass = false;
    record.error = redact(error.message).slice(0, 400);
    record.reason = record.reason ?? "run error";
    record.expected_desc = record.expected_desc ?? "-";
    record.observed_desc = record.observed_desc ?? "-";
    screenshot(`run-${runIndex}-ERROR`);
    return record;
  } finally {
    log(`run ${runIndex} ${group}: timing=${record.timing} Δ=${record.delta_ms ?? "-"}ms written=${record.written_before_kill} pass=${record.pass}${record.reason ? ` (${record.reason})` : ""}`);
  }
}

async function positiveControl(api) {
  const composer = await readyEmptyComposer(api);
  runIndex += 1;
  const text = `${markerPrefix()}${LAST_CHAR}`;
  const mark = logcatMark();
  shell(`${INPUT} text ${text}`);
  await sleep(2500);
  const lines = linesSince(mark).filter((l) => l.includes("RaftDraftTiming"));
  const change = lines.filter((l) => l.includes("change_received") && l.includes(` len=${text.length} `)).at(-1);
  const seq = Number(/seq=(\d+)/.exec(change ?? "")?.[1] ?? 0);
  const written = lines.find((l) => l.includes("draft_written") && l.includes(` len=${text.length} `) && Number(/seq=(\d+)/.exec(l)?.[1]) >= seq);
  const control = {
    typed_len: text.length,
    change_received_lines: lines.filter((l) => l.includes("change_received")).length,
    last_change_received: change?.trim() ?? null,
    draft_written: written?.trim() ?? null,
    write_after_change_ms: change && written ? Number(/uptimeMs=(\d+)/.exec(written)[1]) - Number(/uptimeMs=(\d+)/.exec(change)[1]) : null,
    pass: Boolean(change && written),
    composer: composer ? "found" : "missing",
  };
  summary.control = control;
  if (!control.pass) fail(`positive control: no change_received/draft_written for len ${text.length} (is this a debuggable build with the marker?)`);
  return control;
}

// ---------- the flow ----------
async function main() {
  if (!APK) fail("--apk is required");
  if (!process.env.QA_TEST_EMAIL || !process.env.QA_TEST_PASSWORD) fail("QA_TEST_EMAIL / QA_TEST_PASSWORD are not set");
  const api = { token: "", server: null, channel: null };
  try {
    await step("prepare-device", async () => {
      adb(["root"], { allowFailure: true });
      adb(["wait-for-device"]);
      await sleep(3000);
      const flags = "_ --disable-fre --no-default-browser-check --no-first-run --disable-signin-promo";
      shell(`echo '${flags}' > /data/local/chrome-command-line; echo '${flags}' > /data/local/tmp/chrome-command-line; chmod 644 /data/local/chrome-command-line /data/local/tmp/chrome-command-line`, { allowFailure: true });
      shell("am set-debug-app --persistent com.android.chrome", { allowFailure: true });
      shell("input keyevent 82", { allowFailure: true });
      // Native `cmd` clients: no app_process JVM start between the keystroke, the sleep and the kill.
      const services = shell("cmd -l", { allowFailure: true });
      if (/^\s*input\s*$/m.test(services)) INPUT = "cmd input";
      if (/^\s*activity\s*$/m.test(services)) FORCE_STOP = "cmd activity force-stop";
      return {
        sdk: shell("getprop ro.build.version.sdk").trim(),
        abi: shell("getprop ro.product.cpu.abi").trim(),
        model: shell("getprop ro.product.model").trim(),
        input: INPUT,
        force_stop: FORCE_STOP,
      };
    });

    await step("api-fixture", async () => {
      api.token = await apiLogin();
      const servers = listOf((await raftJson("GET", "servers", { token: api.token })).json, "servers");
      api.server = servers.find((s) => s?.name === SERVER) ?? null;
      if (!api.server?.id || !api.server.slug) fail(`the QA account does not see server ${SERVER} (with a slug)`);
      api.channel = await namedChannel(api.token, api.server, CHANNEL);
      if (!api.channel?.id) fail(`no channel #${CHANNEL} on ${SERVER}`);
      return { channel: api.channel.name };
    });

    await step("install", async () => {
      shell(`pm uninstall ${PKG}`, { allowFailure: true });
      adb(["install", "-r", "-g", APK], { timeoutMs: 240_000 });
      const dump = shell(`dumpsys package ${PKG}`, { allowFailure: true });
      const version = /versionName=(\S+)/.exec(dump)?.[1] ?? "";
      if (!version) fail(`${PKG} is not installed after adb install`);
      return { version_name: version, debuggable: /pkgFlags=\[[^\]]*DEBUGGABLE/.test(dump) };
    });

    startCapture();
    await step("sign-in", () => signIn(api));
    await step("open-channel", async () => {
      await openChannel(api);
      return { channel: CHANNEL };
    });
    await step("positive-control", () => positiveControl(api));

    const plan = [];
    for (let i = 0; i < RUNS_PER_BUCKET; i += 1) for (const d of DELAYS) plan.push({ group: `D=${d}`, mode: "bucket", delaySec: d });
    for (let i = 0; i < FAST_RUNS; i += 1) for (const d of FAST_DELAYS) plan.push({ group: `fast D=${d}`, mode: "fast", delaySec: d });
    plan.push({ group: "a: wait 1 s", mode: "bucket", delaySec: 1 });
    plan.push({ group: "b: send, kill 0.1 s", mode: "send", delaySec: null });
    plan.push({ group: "c: Home, kill", mode: "home", delaySec: null });

    await step("kill-runs", async () => {
      let consecutiveErrors = 0;
      for (const item of plan) {
        const record = await killRun(api, item);
        summary.runs.push(record);
        fs.appendFileSync(path.join(OUT, "runs.jsonl"), `${JSON.stringify(record)}\n`);
        consecutiveErrors = record.error ? consecutiveErrors + 1 : 0;
        if (consecutiveErrors >= 3) fail(`three runs in a row errored; last: ${record.error}`);
      }
      return { runs: summary.runs.length };
    });

    summary.result = summary.runs.every((r) => r.pass) ? "pass" : "fail";
  } catch (error) {
    summary.result = "fail";
    summary.failed_step = currentStep;
    summary.error = redact(error.message);
    console.error(`::error title=Draft kill timing failed at ${currentStep}::${redact(error.message).split("\n")[0]}`);
  } finally {
    await stopCapture();
    summary.buckets = Object.fromEntries([...DELAYS, ...FAST_DELAYS, 1].map((d) => [d, bucketFor(d)]));
    summary.summary = summarize(summary.runs);
    fs.writeFileSync(path.join(OUT, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
    const header =
      `## Draft kill timing (task #246)\n\nResult: **${summary.result}**${summary.failed_step ? ` (failed at ${summary.failed_step}: ${summary.error})` : ""}\n\n` +
      `Δ = ActivityManager "Killing <old pid>" (logcat -v monotonic) − last change_received uptimeMs. Hit = Δ inside the bucket window ` +
      `(${Object.entries(summary.buckets).map(([d, b]) => `D=${d}: (${b.lo}, ${b.hi}] s`).join("; ")}); Δ for b/c is measured, not bucketed.\n\n` +
      (summary.control ? `Positive control: change_received ${summary.control.last_change_received ? "present" : "missing"}, draft_written ${summary.control.draft_written ? "present" : "missing"} (write ${summary.control.write_after_change_ms ?? "-"} ms after the change).\n\n` : "");
    fs.writeFileSync(path.join(OUT, "results.md"), header + markdownTable(summary.runs, summary.summary));
  }
  process.exit(summary.result === "pass" ? 0 : 1);
}

main();
