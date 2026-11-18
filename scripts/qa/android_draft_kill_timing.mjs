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
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { channelActivityReport, parseMessagesResponse, createLogcatStats, forEachLogLine, isEvidenceLine, wrapUpVerdict, BUCKETS, SEND_WINDOW, analyzeKillRun, bucketFor, isForceStopReason, killReason, markdownTable, parseEventPid, parseLogcatLine, summarize } from "./draft_kill_timing_analysis.mjs";

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
// A new private channel per run, created here, used for nothing else and archived at the end: never a
// shared QA channel (clearing its composer could clobber another run's draft) and never a work channel.
const CHANNEL = `qa-draft-246-${process.env.GITHUB_RUN_ID ?? Date.now()}-${process.env.GITHUB_RUN_ATTEMPT ?? "1"}`;
const RUNS_PER_BUCKET = Number(args["runs-per-bucket"] ?? 10);
const FAST_RUNS = Number(args["fast-runs"] ?? Math.min(5, RUNS_PER_BUCKET));
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

/** One-off app sheets ("What's new" and the like) that cover the page after sign-in or relaunch. */
async function dismissAppSheet(list) {
  const button = find(list, (n) => n.pkg === PKG && n.enabled && ["Got It", "Got it", "Not now", "Maybe later", "Later"].includes(n.text.trim()));
  if (!button) return false;
  log(`dismissing app sheet button "${button.text}"`);
  tapNode(button);
  await sleep(1000);
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
    if (await dismissAppSheet(list)) continue;
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

const isHome = (n) => n.pkg === PKG && (n.text === "Drafts" || n.text === "Home");
const serverVisible = (list, name = SERVER) => list.some((n) => n.pkg === PKG && label(n).includes(name));

// ---------- logcat (-v monotonic: CLOCK_MONOTONIC seconds, the clock of SystemClock.uptimeMillis) ----------
let logcatChild = null;
// Outside the evidence directory: if the process dies before stopCapture, the unfiltered logcat is not
// uploaded (only the filtered timing lines are evidence).
const LOGCAT_RAW = path.join(os.tmpdir(), `draft-kill-logcat-raw-${process.pid}.txt`);

function startCapture() {
  adb(["logcat", "-c"], { allowFailure: true });
  const fd = fs.openSync(LOGCAT_RAW, "w");
  logcatChild = spawn("adb", ["logcat", "-v", "monotonic", "-b", "main", "-b", "system", "-b", "crash", "-b", "events"], { stdio: ["ignore", fd, fd] });
}

/** A mark is a byte offset into the raw logcat file: cheap, no read of the whole file. */
const logcatMark = () => {
  try {
    return fs.statSync(LOGCAT_RAW).size;
  } catch {
    return 0;
  }
};
/** Only the lines written since [mark] (a run's window), never the whole growing file; read in bounded chunks. */
const linesSince = (mark) => {
  const lines = [];
  try {
    forEachLogLine(LOGCAT_RAW, mark, (line) => lines.push(line));
  } catch {}
  return lines;
};


async function waitForTiming(description, predicate, { fromMark, timeoutMs = 15_000 }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = linesSince(fromMark).find((line) => line.includes("RaftDraftTiming") && predicate(line));
    if (hit) return hit;
    await sleep(250);
  }
  return null;
}

/**
 * Stops the capture and makes one streaming pass over the whole raw logcat: the wrap-up statistics and the
 * evidence lines are taken line by line (bounded memory however long the run was), then the raw file is
 * deleted. Returns the statistics, or null when there was no capture.
 */
async function stopCapture() {
  try {
    logcatChild?.kill("SIGTERM");
  } catch {}
  await sleep(1000);
  if (!fs.existsSync(LOGCAT_RAW)) return null;
  const stats = createLogcatStats();
  const appPids = { has: (pid) => APP_PIDS.has(pid) || stats.appPids.has(pid) };
  // Written to a temporary name in OUT and renamed only when complete; the upload allow-list does not
  // include the temporary name, and every line in it is already filtered and redacted.
  const partial = path.join(OUT, "timing-lines.txt.partial");
  const out = fs.openSync(partial, "w");
  try {
    const read = forEachLogLine(LOGCAT_RAW, 0, (line) => {
      stats.add(line);
      // The app's pids: those the script saw plus every pid that logged a RaftDraftTiming line before this one.
      if (isEvidenceLine(line, PKG, appPids)) fs.writeSync(out, `${redact(line)}\n`);
    });
    summary.logcat_wrap_up = read;
  } finally {
    fs.closeSync(out);
  }
  fs.renameSync(partial, path.join(OUT, "timing-lines.txt"));
  fs.rmSync(LOGCAT_RAW);
  return stats.result();
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
  // Text first: an empty or unparsable body is reported as such (parseError), never as an empty list.
  let json = null;
  let parseError = false;
  try {
    const text = await response.text();
    json = text.trim() ? JSON.parse(text) : null;
    if (json == null) parseError = true;
  } catch {
    parseError = true;
  }
  return { status: response.status, json, parseError };
}

async function apiLogin() {
  const login = await raftJson("POST", "auth/login", { body: { email: process.env.QA_TEST_EMAIL, password: process.env.QA_TEST_PASSWORD } });
  if (login.status !== 200 || !login.json?.accessToken) fail(`QA account API login failed (HTTP ${login.status})`);
  return login.json.accessToken;
}

const listOf = (body, key) => (Array.isArray(body) ? body : body?.[key]) ?? [];

/** Creates the run's own channel (private first; public on the QA server if private is refused). */
async function createRunChannel(api) {
  const attempts = [];
  for (const visibility of ["private", "public"]) {
    const res = await raftJson("POST", "channels", {
      token: api.token, serverId: api.server.id,
      body: { name: CHANNEL, description: "Task #246 draft-kill timing acceptance (CI); archived when the run ends.", visibility },
    });
    const channel = res.json?.channel ?? res.json;
    attempts.push({ visibility, status: res.status, code: res.json?.code ?? null });
    if (res.status < 300 && channel?.id) return { id: channel.id, name: channel.name ?? CHANNEL, visibility, attempts };
  }
  fail(`could not create channel #${CHANNEL}: ${JSON.stringify(attempts)}`);
}

/** Channels an earlier run of this scenario created and could not archive (`qa-draft-246-*`): archive them now. */
/** Status of GitHub Actions run [runId] of this repository (read-only, the job's GITHUB_TOKEN), or null. */
async function githubRunStatus(runId) {
  const repo = process.env.GITHUB_REPOSITORY;
  const token = process.env.GH_RUN_STATUS_TOKEN;
  if (!repo || !token) return null;
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/actions/runs/${runId}`, {
      headers: { Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json" },
      signal: AbortSignal.timeout(15_000),
    });
    if (res.status !== 200) return `http_${res.status}`;
    return (await res.json())?.status ?? null;
  } catch {
    return null;
  }
}

/**
 * Channels an earlier run of this scenario created and left unarchived (`qa-draft-246-<run>-<attempt>`):
 * archived only when that GitHub run has completed, so a parallel run's live channel is never closed.
 * Each candidate is recorded: name, id, run id, run status, archive result.
 */
async function archiveLeftoverRunChannels(api) {
  const list = listOf((await raftJson("GET", "channels", { token: api.token, serverId: api.server.id })).json, "channels");
  const results = [];
  for (const c of list.filter((c) => c?.id && /^qa-draft-246-\d+-\d+$/.test(c?.name ?? "") && c?.name !== CHANNEL && !c?.archivedAt && !c?.archived)) {
    const runId = /^qa-draft-246-(\d+)-\d+$/.exec(c.name)[1];
    const runStatus = await githubRunStatus(runId);
    if (runStatus !== "completed") {
      results.push({ name: c.name, id: c.id, run_id: runId, run_status: runStatus ?? "unknown", archive: "skipped: run in progress/unknown" });
      continue;
    }
    const res = await raftJson("POST", `channels/${c.id}/archive`, { token: api.token, serverId: api.server.id });
    results.push({ name: c.name, id: c.id, run_id: runId, run_status: runStatus, archive: res.status < 300 ? "archived" : "failed", http: res.status });
  }
  return results;
}

async function archiveRunChannel(api) {
  if (!api.channel?.id) return { archived: false, reason: "no channel was created" };
  try {
    const res = await raftJson("POST", `channels/${api.channel.id}/archive`, { token: api.token, serverId: api.server.id });
    return { archived: res.status < 300, status: res.status, code: res.json?.code ?? null };
  } catch (error) {
    return { archived: false, error: redact(error.message).slice(0, 200) };
  }
}

async function channelMessages(api, limit = 100) {
  const res = await raftJson("GET", `messages/channel/${api.channel.id}?limit=${limit}`, { token: api.token, serverId: api.server.id });
  return parseMessagesResponse(res.status, res.json, res.parseError);
}

/** The server's copy of this run's marker message: id, exact text and createdAt (the marker is ours, not user content). */
async function markerMessage(api, marker) {
  const at = new Date().toISOString();
  try {
    const { status, ok, rows, problem } = await channelMessages(api, 30);
    // Only a recognisable messages array is a successful query; anything else is "unavailable".
    if (!ok) return { at, http: status, ok: false, problem, message: null };
    const m = rows.find((r) => String(r?.content ?? "").includes(marker));
    return {
      at,
      http: status,
      ok: true,
      message: m ? { id: m.id ?? null, text: String(m.content), created_at: m.createdAt ?? m.created_at ?? null, random_id: m.randomId ?? null } : null,
    };
  } catch (error) {
    return { at, http: `error: ${String(error?.name ?? "error")}`, ok: false, message: null };
  }
}

/**
 * Concurrency check: anything in the run's channel that this run did not post (the shared QA account may
 * be in use elsewhere), and how much of the channel the read covered (channelActivityReport). Only
 * counts, kinds and the covered window are recorded, never other content.
 */
const ACTIVITY_LIMIT = 100;
async function channelActivityCheck(api) {
  const { status, ok, problem, rows } = await channelMessages(api, ACTIVITY_LIMIT);
  return channelActivityReport({ status, ok, problem, rows, limit: ACTIVITY_LIMIT, runTag: `dk${RUN_TAG}`, selfId: api.selfId });
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
    if (await dismissAppSheet(now)) continue;
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
  const link = `${APP_LINK_BASE}/s/${api.server.slug}/channel/${api.channel.id}`;
  for (let attempt = 1; ; attempt += 1) {
    shell(`am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${link}' ${PKG} >/dev/null`, { allowFailure: true });
    try {
      return await waitFor(`the composer of #${CHANNEL}`, isComposer, { timeoutMs: 20_000, intervalMs: 1000 });
    } catch (error) {
      if (attempt >= 3) throw error;
      log(`composer not shown after the app link (attempt ${attempt}); retrying`);
      // A browser left on top from sign-in must not take the link.
      shell("am force-stop com.android.chrome", { allowFailure: true });
    }
  }
}

/** Every pid the app had during the run: evidence lines of other processes are never kept. */
const APP_PIDS = new Set();
function pidOf() {
  const out = shell(`pidof ${PKG}`, { allowFailure: true }).trim().split(/\s+/).filter(Boolean).map(Number);
  out.forEach((pid) => APP_PIDS.add(pid));
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

// ---------- the kill (one device-side shell) ----------
/** Field 22 of /proc/<pid>/stat: the process start time in clock ticks since boot (identifies the process, not just the pid). */
function startTimeOf(pid) {
  return shell(`cut -d' ' -f22 /proc/${pid}/stat 2>/dev/null`, { allowFailure: true }).trim() || null;
}

/**
 * The device-side script of one kill: [before] (keystrokes), sleep, then a background poller on
 * /proc/<pid> (every ~5 ms, bounded to ~20 s) started right before the force-stop, which logs
 * `run=<n> pid_gone` the moment the old process is gone: the upper bound of its exit on the device clock.
 * The start time of the pid is checked before the keystrokes, so a recycled pid cannot pass for the old process.
 */
function killScript({ oldPid, startTime, before, sleepSec, probeLabel = null }) {
  const P = `/proc/${oldPid}`;
  return (
    `if [ "$(cut -d' ' -f22 ${P}/stat 2>/dev/null)" = "${startTime}" ]; then echo SAME_PROCESS; else echo OTHER_PROCESS; fi; ` +
    `log -t RaftDraftTimingProbe "run=${runIndex} start"; ` +
    (probeLabel ? `log -t RaftDraftTimingProbe "run=${runIndex} ${probeLabel}"; ` : "") +
    `${before}sleep ${sleepSec}; ` +
    // Dead = /proc/<pid> gone or the process a zombie (Z/X: it runs no more code; only its parent has not
    // reaped it yet). `read` is a shell builtin, so a poll forks only the 5 ms sleep.
    // ~1 ms between polls (the fork of `sleep` dominates), bounded to ~20 s.
    `( i=0; st=R; while [ $i -lt 20000 ]; do st=; read -r l 2>/dev/null < ${P}/stat && set -- $l && st=$3; ` +
    `case "$st" in ""|Z|X) break;; esac; sleep 0.001; i=$((i+1)); done; ` +
    `case "$st" in ""|Z|X) log -t RaftDraftTimingProbe "run=${runIndex} pid_gone state=\${st:-none} polls=$i";; ` +
    `*) log -t RaftDraftTimingProbe "run=${runIndex} pid_alive polls=$i";; esac ) & ` +
    `${FORCE_STOP} ${PKG}; wait; ` +
    `if [ -d ${P} ]; then echo PID_ALIVE; else echo PID_GONE; fi`
  );
}

/** Runs [script] for the old process, then decides the run (exit interval, timing) and the relaunch pid. */
async function killAndAnalyze({ oldPid, startTime, script, expectedLen, delaySec, refProbe = null, mark, window = null, extraReasons = [] }) {
  const out = shell(script, { timeoutMs: 90_000 });
  await sleep(1500);
  const pidGone = !shell(`ls -d /proc/${oldPid} 2>/dev/null`, { allowFailure: true }).trim();
  const lines = linesSince(mark);
  const analysis = analyzeKillRun({
    lines, pkg: PKG, oldPid, samePid: out.includes("SAME_PROCESS"), runIndex, expectedLen, delaySec, pidGone, refProbe, window, extraReasons,
  });
  return { analysis: { ...analysis, old_start_time: startTime, pid_gone_after_poll: out.includes("PID_GONE") }, lines };
}

/**
 * After relaunch: a new process (pid or start time differs from the killed one), and any death of an app
 * process during the run that was not this run's force-stop (e.g. dependency_died when Play services'
 * FontsProvider process dies) is named in the run, so it can never pass for a force-stop sample.
 */
function foreignAppDeaths(mark, oldPid, extraReasons = []) {
  const foreign = [];
  for (const l of linesSince(mark).map(parseLogcatLine).filter(Boolean)) {
    const ev = l.tag === "am_kill" ? parseEventPid(l.message) : null;
    if (!ev || ev.name !== PKG) continue;
    const reason = killReason(l, PKG, ev.pid);
    if (!isForceStopReason(reason, PKG) && !extraReasons.some((r) => reason?.startsWith(r))) foreign.push(`pid ${ev.pid}${ev.pid === oldPid ? " (old)" : ""}: ${reason}`);
  }
  return foreign;
}

function relaunchIdentity(record, oldPid, oldStart, mark) {
  const foreign = foreignAppDeaths(mark, oldPid, record.mode === "recents" ? ["remove task"] : []);
  if (foreign.length) record.foreign_app_deaths = foreign;
  record.new_pid = pidOf();
  const newStart = record.new_pid ? startTimeOf(record.new_pid) : null;
  record.new_process = Boolean(record.new_pid) && (record.new_pid !== oldPid || newStart !== oldStart);
  if (!record.new_process) record.reason = `${record.reason ? `${record.reason}; ` : ""}relaunch is not a new process`;
}

// ---------- runs ----------
let runIndex = 0;
const median = (values, fallback) => {
  if (!values.length) return fallback;
  const sorted = [...values].sort((x, y) => x - y);
  return sorted[Math.floor(sorted.length / 2)];
};
/** Per mode: (exit Δ lower − sleep) and exit-interval widths of earlier runs, to aim the next sleep. */
const aim = { bucket: { overhead: [], width: [] }, fast: { overhead: [], width: [] }, send: { overhead: [], width: [] } };
/**
 * The sleep that puts the whole exit interval in [lo_ms, hi_ms]: aim the lower bound at the window centre
 * minus half the median width, less the measured overhead between the sleep's end and the kill line.
 * Δ is always measured afterwards; the sleep is only aiming.
 */
function aimedSleepSec(mode, window) {
  const a = aim[mode];
  const targetLower = (window.lo_ms + window.hi_ms) / 2 - median(a.width, 30) / 2;
  return Math.max(0, Math.round(targetLower - median(a.overhead, 35)) / 1000);
}
function learn(mode, analysis, sleepSec) {
  if (analysis.delta_lower_ms == null || !analysis.same_process) return;
  aim[mode].overhead.push(analysis.delta_lower_ms - sleepSec * 1000);
  if (analysis.exit_width_ms != null) aim[mode].width.push(analysis.exit_width_ms);
}
/** Keys typed in a fast run: tuned after each fast run so the burst spans ~4 s (agreed 3-5 s). */
let fastKeyCount = 45;
const FAST_TARGET_MS = 4000;
const FAST_ALPHABET = "abcdefghijklmnoprstuvwxyz0123456789"; // no q: q is the last key
/** KEYCODE_ name of a marker character ([a-z0-9]). */
const keycodeOf = (c) => (/[0-9]/.test(c) ? `KEYCODE_${c}` : `KEYCODE_${c.toUpperCase()}`);
/** Types [text] as one key event per character in one device-side command (no `input text`). */
const typeKeys = (text) => shell(`${INPUT} keyevent ${text.split("").map(keycodeOf).join(" ")}`);
const markerPrefix = () => `dk${RUN_TAG}r${String(runIndex).padStart(2, "0")}x`;
let SCREEN = { w: 1080, h: 2400 };

/**
 * One kill run. [mode]:
 *  "bucket": prefix typed, then one device-side shell: last key, sleep, force-stop.
 *  "fast":   one device-side shell: ~4 s of single keystrokes, last key, sleep, force-stop.
 *  "home":   last key, Home, sleep 0.1 s, force-stop.
 *  "recents": last key, recents (KEYCODE_APP_SWITCH), swipe the task card away, then force-stop if the
 *             process is still there; the swipe's own kill ("remove task") counts as this run's kill.
 *  "send":   full text typed and written; one device-side shell: tap Send, sleep, force-stop;
 *            required window: exit within [0, 100] ms of the tap.
 */
async function killRun(api, { group, mode, delaySec }) {
  runIndex += 1;
  const record = { group, mode, delay_sec: delaySec, run: runIndex };
  const runMark = logcatMark();
  try {
    await readyEmptyComposer(api);
    const prefix = markerPrefix();
    let expected = `${prefix}${LAST_CHAR}`;
    let fastChars = "";
    if (mode === "fast") {
      fastChars = Array.from({ length: fastKeyCount }, (_, i) => FAST_ALPHABET[i % FAST_ALPHABET.length]).join("");
      expected = `${prefix}${fastChars}${LAST_CHAR}`;
      record.fast_keys = fastChars.length + 1;
    }
    if (mode === "send") {
      let mark = logcatMark();
      typeKeys(expected);
      if (!(await waitForTiming("the full text written", (l) => l.includes(`draft_written`) && l.includes(` len=${expected.length} `), { fromMark: mark }))) {
        record.note = "full text never reported written before send";
      }
      const send = find(nodes(), (n) => n.pkg === PKG && n.desc === "Send" && n.enabled);
      if (!send) fail("no enabled Send button");
      const [x, y] = center(send);
      const oldPid = pidOf();
      if (!oldPid) fail("the app has no pid before the kill");
      const startTime = startTimeOf(oldPid);
      const sleepSec = aimedSleepSec("send", SEND_WINDOW);
      record.sleep_sec = sleepSec;
      mark = logcatMark();
      // Tap and force-stop in the same device-side shell; Δ from the send_tap probe logged right before the tap.
      const killStartedAtMs = Date.now();
      const { analysis } = await killAndAnalyze({
        oldPid, startTime, mark, expectedLen: expected.length, delaySec: null, refProbe: "send_tap", window: SEND_WINDOW,
        script: killScript({ oldPid, startTime, before: `${INPUT} tap ${x} ${y}; `, sleepSec, probeLabel: "send_tap" }),
      });
      Object.assign(record, analysis);
      learn("send", analysis, sleepSec);
      // Host wall clock (UTC) around the kill: comparable with the server's createdAt to within clock skew.
      record.kill_shell_started_at = new Date(killStartedAtMs).toISOString();
      // Every server query is kept with its time and HTTP status.
      const queries = [];
      const query = async (phase) => {
        const q = await markerMessage(api, expected);
        queries.push({ phase, at: q.at, http: q.http, ok: q.ok, ...(q.problem ? { problem: q.problem } : {}), found: Boolean(q.message) });
        return q;
      };
      const beforeRelaunch = await query("before relaunch");
      const relaunchedAtMs = Date.now();
      record.relaunched_at = new Date(relaunchedAtMs).toISOString();
      const relaunchMark = logcatMark();
      const seen = await composerAfterRelaunch(api, "", 8_000);
      let found = beforeRelaunch.message;
      const deadline = Date.now() + 15_000;
      while (!found && Date.now() < deadline) {
        await sleep(1000);
        found = (await query("after relaunch")).message;
      }
      record.server_queries = queries;
      record.server_message = found ?? null;
      const allQueriesFailed = queries.every((q) => !q.ok);
      // The app's own trace of a re-send by the pending-send journal after the restart.
      const drained = linesSince(relaunchMark).filter((l) => l.includes("thread_pending_send_drained "));
      record.drainer_trace = drained.map((l) => /thread_pending_send_drained .*$/.exec(l)?.[0] ?? l.trim());
      // Only an explicit id correspondence (the server message's randomId in the app's drain line) names the mechanism.
      const drainedMatches = found?.random_id ? drained.some((l) => l.includes(`randomId=${found.random_id}`)) : false;
      record.delivery = found
        ? beforeRelaunch.message
          ? "observed before the relaunch"
          : drainedMatches
            ? `observed after the relaunch, re-sent by the pending-send journal (drainer trace: ${record.drainer_trace.join(" | ")})`
            : "observed after the relaunch (mechanism unconfirmed)"
        : allQueriesFailed
          ? "query unavailable"
          : queries.at(-1)?.ok && queries.filter((q) => q.phase === "after relaunch").every((q) => q.ok)
            ? "not observed by the deadline (15 s after relaunch; every query succeeded)"
            : `unknown (queries unavailable after ${queries.filter((q) => q.ok).at(-1)?.at ?? "the start"})`;
      record.sent_on_server = Boolean(found);
      record.observed_desc = describeText(seen);
      relaunchIdentity(record, oldPid, startTime, mark);
      // Composer must be empty (the journal owns the text) and the text must reach the server.
      record.expected_desc = "composer (empty); message on the server (possibly late)";
      record.pass = seen === "" && Boolean(found);
      if (!found) record.reason = `${record.reason ? `${record.reason}; ` : ""}message ${record.delivery}`;
      return record;
    }

    let mark = logcatMark();
    typeKeys(prefix);
    if (!(await waitForTiming("the prefix", (l) => l.includes("change_received") && l.includes(` len=${prefix.length} `), { fromMark: mark }))) {
      record.note = "no change_received for the prefix";
    }
    await sleep(300);
    const oldPid = pidOf();
    if (!oldPid) fail("the app has no pid before the kill");
    const startTime = startTimeOf(oldPid);
    mark = logcatMark();
    let before = `${INPUT} keyevent ${LAST_KEY}; `;
    let sleepSec;
    let extraReasons = [];
    if (mode === "fast") {
      // One key event per character, on the device, back to back (no host round trip).
      before = `for k in ${fastChars.split("").map(keycodeOf).join(" ")}; do ${INPUT} keyevent $k; done; ${before}`;
      sleepSec = aimedSleepSec("fast", bucketFor(delaySec));
    } else if (mode === "home") {
      before += `${INPUT} keyevent KEYCODE_HOME; `;
      sleepSec = 0.1;
    } else if (mode === "recents") {
      const x = Math.round(SCREEN.w / 2);
      before += `${INPUT} keyevent KEYCODE_APP_SWITCH; sleep 1; ${INPUT} swipe ${x} ${Math.round(SCREEN.h * 0.55)} ${x} ${Math.round(SCREEN.h * 0.05)} 150; `;
      sleepSec = 1;
      extraReasons = ["remove task"];
    } else {
      sleepSec = aimedSleepSec("bucket", bucketFor(delaySec));
    }
    record.sleep_sec = sleepSec;
    const bucketed = mode === "bucket" || mode === "fast";
    const { analysis, lines } = await killAndAnalyze({
      oldPid, startTime, mark, expectedLen: expected.length, delaySec: bucketed ? delaySec : null, extraReasons,
      script: killScript({ oldPid, startTime, before, sleepSec }),
    });
    Object.assign(record, analysis);
    if (bucketed) learn(mode, analysis, sleepSec);
    if (mode === "recents") {
      record.recents_swipe_killed = analysis.kill_reason?.startsWith("remove task") ?? false;
      record.note = record.recents_swipe_killed
        ? "recents swipe removed the task and killed the process (remove task)"
        : `recents swipe did not kill the process; it ended by ${analysis.kill_reason ?? "unknown"}`;
    }
    if (mode === "fast") {
      // The burst on the device clock: first to last change_received of the old process after the run started.
      const changes = lines.map(parseLogcatLine).filter((l) => l && l.tag === "RaftDraftTiming" && l.pid === oldPid && l.message.startsWith("change_received"))
        .map((l) => Number(/uptimeMs=(\d+)/.exec(l.message)?.[1]));
      record.typing_span_ms = changes.length > 1 ? changes.at(-1) - changes[0] : null;
      record.typing_changes = changes.length;
      record.typing_in_3_to_5_s = record.typing_span_ms != null && record.typing_span_ms >= 3000 && record.typing_span_ms <= 5000;
      if (record.typing_span_ms && changes.length > 1) {
        const perKey = record.typing_span_ms / (changes.length - 1);
        fastKeyCount = Math.min(120, Math.max(15, Math.round(FAST_TARGET_MS / perKey)));
      }
    }
    const seen = await composerAfterRelaunch(api, expected);
    record.expected_desc = describeText(expected);
    record.observed_desc = describeText(seen);
    relaunchIdentity(record, oldPid, startTime, mark);
    if (record.last_change_seq == null && /no change_received/.test(record.reason ?? "")) {
      // The last keystroke never reached the composer before the kill, so the app never had the full text:
      // not a draft-survival sample. The draft must still hold everything the app did receive (the prefix).
      record.sample = "last key not received before the kill";
      record.expected_desc = `${describeText(expected)}, or ${describeText(expected.slice(0, -1))} if the last key never arrived`;
      record.pass = seen === expected || seen === expected.slice(0, -1);
    } else {
      record.pass = seen === expected;
    }
    return record;
  } catch (error) {
    record.timing = record.timing ?? "unconfirmed";
    record.pass = false;
    record.error = redact(error.message).slice(0, 400);
    const foreign = foreignAppDeaths(runMark, record.old_pid ?? -1);
    if (foreign.length) record.foreign_app_deaths = foreign;
    record.reason = record.reason ?? "run error";
    record.expected_desc = record.expected_desc ?? "-";
    record.observed_desc = record.observed_desc ?? "-";
    screenshot(`run-${runIndex}-ERROR`);
    return record;
  } finally {
    log(`run ${runIndex} ${group}: sleep=${record.sleep_sec ?? "-"} timing=${record.timing} exitΔ=[${record.delta_lower_ms ?? "-"}, ${record.delta_upper_ms ?? "-"}]ms bucket=${record.measured_bucket ?? "-"} pass=${record.pass}${record.reason ? ` (${record.reason})` : ""}`);
  }
}

async function positiveControl(api) {
  const composer = await readyEmptyComposer(api);
  runIndex += 1;
  const text = `${markerPrefix()}${LAST_CHAR}`;
  const mark = logcatMark();
  typeKeys(text);
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
  // Acceptance needs to know which code it ran: the APK's source must be checked against a required commit.
  if (!process.env.REQUIRE_COMMIT) fail("REQUIRE_COMMIT (workflow input require_commit) is required for draft-kill-timing");
  if (!process.env.QA_TEST_EMAIL || !process.env.QA_TEST_PASSWORD) fail("QA_TEST_EMAIL / QA_TEST_PASSWORD are not set");
  const api = { token: "", server: null, channel: null, selfId: null };
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
        screen: (() => {
          const m = /(\d+)x(\d+)/.exec(shell("wm size", { allowFailure: true }));
          if (m) SCREEN = { w: Number(m[1]), h: Number(m[2]) };
          return SCREEN;
        })(),
      };
    });

    await step("api-fixture", async () => {
      api.token = await apiLogin();
      const servers = listOf((await raftJson("GET", "servers", { token: api.token })).json, "servers");
      api.server = servers.find((s) => s?.name === SERVER) ?? null;
      if (!api.server?.id || !api.server.slug) fail(`the QA account does not see server ${SERVER} (with a slug)`);
      const me = (await raftJson("GET", "auth/me", { token: api.token })).json;
      api.selfId = me?.id ?? me?.user?.id ?? null;
      api.channel = await createRunChannel(api);
      summary.channel_created = { name: api.channel.name, visibility: api.channel.visibility };
      return { channel: api.channel.name, visibility: api.channel.visibility };
    });

    await step("archive-leftover-run-channels", async () => {
      api.token = await apiLogin();
      summary.leftover_channels = await archiveLeftoverRunChannels(api);
      return { candidates: summary.leftover_channels.length };
    });

    await step("install", async () => {
      shell(`pm uninstall ${PKG}`, { allowFailure: true });
      adb(["install", "-r", "-g", APK], { timeoutMs: 240_000 });
      const dump = shell(`dumpsys package ${PKG}`, { allowFailure: true });
      const version = /versionName=(\S+)/.exec(dump)?.[1] ?? "";
      if (!version) fail(`${PKG} is not installed after adb install`);
      // Identity of what is actually installed, not just the Alpha label.
      const basePath = /package:(\S+)/.exec(shell(`pm path ${PKG}`, { allowFailure: true }))?.[1] ?? "";
      summary.apk = {
        file_sha256: createHash("sha256").update(fs.readFileSync(APK)).digest("hex"),
        installed_base_apk_sha256: basePath ? shell(`sha256sum ${basePath}`, { allowFailure: true }).trim().split(/\s+/)[0] : null,
        version_name: version,
        version_code: /versionCode=(\d+)/.exec(dump)?.[1] ?? null,
        downloaded_sha256_from_workflow: process.env.APK_SHA256 || null,
        artifact: process.env.APK_ARTIFACT || null,
        source_commit: process.env.APK_SOURCE_SHA || null,
        required_commit: process.env.REQUIRE_COMMIT || null,
        required_commit_compare: process.env.REQUIRE_COMMIT_STATUS || null,
        debuggable: /pkgFlags=\[[^\]]*DEBUGGABLE/.test(dump),
      };
      return summary.apk;
    });

    startCapture();
    await step("sign-in", async () => {
      const result = await signIn(api);
      // The sign-in browser is done; it must not come back on top of a relaunch.
      shell("am force-stop com.android.chrome", { allowFailure: true });
      return result;
    });
    await step("open-channel", async () => {
      await openChannel(api);
      return { channel: CHANNEL };
    });
    await step("positive-control", () => positiveControl(api));
    // The device's own `sleep` accuracy (probe line to probe line, device clock), so the aim is not a guess.
    await step("sleep-calibration", async () => {
      const result = {};
      for (const d of [0, 0.02, 0.05, 0.1]) {
        const mark = logcatMark();
        for (let i = 0; i < 5; i += 1) shell(`log -t RaftDraftTimingProbe "cal d=${d} i=${i} a"; sleep ${d}; log -t RaftDraftTimingProbe "cal d=${d} i=${i} b"`);
        await sleep(500);
        const parsed = linesSince(mark).map(parseLogcatLine).filter((l) => l?.tag === "RaftDraftTimingProbe" && l.message.startsWith(`cal d=${d} `));
        const overshoot = [];
        for (let i = 0; i < 5; i += 1) {
          const a = parsed.find((l) => l.message === `cal d=${d} i=${i} a`);
          const b = parsed.find((l) => l.message === `cal d=${d} i=${i} b`);
          if (a && b) overshoot.push(Math.round(b.tsMs - a.tsMs - d * 1000));
        }
        result[`sleep_${d}_overshoot_ms`] = overshoot;
      }
      summary.sleep_calibration = result;
      return result;
    });

    // Bucket runs go on until each bucket has RUNS_PER_BUCKET valid samples (the whole measured exit interval
    // inside that bucket's tolerance, whatever D was aimed at) or its attempts run out.
    const maxAttempts = Math.max(RUNS_PER_BUCKET * 3, RUNS_PER_BUCKET + 5);
    summary.bucket_progress = Object.fromEntries(DELAYS.map((d) => [d, { valid: 0, attempted: 0, max_attempts: maxAttempts }]));
    const fastMaxAttempts = Math.max(FAST_RUNS * 3, FAST_RUNS + 5);
    summary.fast_progress = Object.fromEntries(FAST_DELAYS.map((d) => [d, { valid: 0, attempted: 0, max_attempts: fastMaxAttempts }]));
    const fixed = [];
    fixed.push({ group: "a: wait 1 s", mode: "bucket", delaySec: 1 });
    for (let i = 0; i < 5; i += 1) fixed.push({ group: "b: send, kill within 100 ms", mode: "send", delaySec: null, untilHit: true });
    fixed.push({ group: "c1: Home, kill", mode: "home", delaySec: null });
    fixed.push({ group: "c2: recents swipe (task removed)", mode: "recents", delaySec: null });

    await step("kill-runs", async () => {
      let consecutiveErrors = 0;
      const keep = (record) => {
        summary.runs.push(record);
        fs.appendFileSync(path.join(OUT, "runs.jsonl"), `${JSON.stringify(record)}\n`);
        consecutiveErrors = record.error ? consecutiveErrors + 1 : 0;
        if (consecutiveErrors >= 3) fail(`three runs in a row errored; last: ${record.error}`);
      };
      const progress = summary.bucket_progress;
      const open = () => DELAYS.filter((d) => progress[d].valid < RUNS_PER_BUCKET && progress[d].attempted < maxAttempts);
      while (open().length) {
        for (const d of open()) {
          progress[d].attempted += 1;
          const record = await killRun(api, { group: `D=${d}`, mode: "bucket", delaySec: d });
          if (record.measured_bucket != null && progress[record.measured_bucket]) progress[record.measured_bucket].valid += 1;
          keep(record);
        }
      }
      // Fast typing: per bucket until FAST_RUNS valid samples (whole exit interval inside the tolerance and
      // the burst on the device clock within 3-5 s), or the attempts run out.
      const fast = summary.fast_progress;
      const fastOpen = () => FAST_DELAYS.filter((d) => fast[d].valid < FAST_RUNS && fast[d].attempted < fastMaxAttempts);
      while (fastOpen().length) {
        for (const d of fastOpen()) {
          fast[d].attempted += 1;
          const record = await killRun(api, { group: `fast D=${d}`, mode: "fast", delaySec: d });
          if (record.measured_bucket != null && fast[record.measured_bucket] && record.typing_in_3_to_5_s) fast[record.measured_bucket].valid += 1;
          keep(record);
        }
      }
      let sendHit = false;
      for (const item of fixed) {
        if (item.untilHit && sendHit) continue;
        const record = await killRun(api, item);
        if (item.mode === "send" && record.timing === "hit") sendHit = true;
        keep(record);
      }
      return { runs: summary.runs.length };
    });

    // The verdict is decided in `finally`, after stopCapture: the crash-journal gate needs the
    // whole-run statistics, which exist only once the capture has been read.
  } catch (error) {
    summary.result = "fail";
    summary.failed_step = currentStep;
    summary.error = redact(error.message);
    console.error(`::error title=Draft kill timing failed at ${currentStep}::${redact(error.message).split("\n")[0]}`);
  } finally {
    if (api.channel?.id) {
      try {
        // The run outlives the access token: sign in to the API again for the checks and the archive.
        api.token = await apiLogin();
        summary.channel_activity = await channelActivityCheck(api);
      } catch (error) {
        summary.channel_activity = { error: redact(error.message).slice(0, 200) };
      }
      summary.channel_archive = await archiveRunChannel(api);
      if (!summary.channel_archive.archived) console.error(`::warning::channel #${CHANNEL} was not archived: ${JSON.stringify(summary.channel_archive)}`);
    }
    let logcatStats = null;
    try {
      logcatStats = await stopCapture();
    } catch (error) {
      summary.logcat_wrap_up = { error: redact(error.message).slice(0, 200) };
    }
    // "pass" only when every measured condition holds; each unmet one is listed.
    wrapUpVerdict(summary, logcatStats, { runsPerBucket: RUNS_PER_BUCKET, fastRuns: FAST_RUNS, env: process.env });
    summary.buckets = BUCKETS;
    summary.send_window = SEND_WINDOW;
    summary.summary = summarize(summary.runs);
    fs.writeFileSync(path.join(OUT, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
    const header =
      `## Draft kill timing (task #246)\n\nResult: **${summary.result}**${summary.failed_step ? ` (failed at ${summary.failed_step}: ${summary.error})` : ""}\n\n` +
      (summary.unmet?.length ? `Unmet conditions:\n${summary.unmet.map((u) => `- ${u}`).join("\n")}\n\n` : "") +
      (summary.unconfirmed?.length ? `Unconfirmed (no pass without them):\n${summary.unconfirmed.map((u) => `- ${u}`).join("\n")}\n\n` : "") +
      (summary.journal_coverage ? `Crash-journal coverage, paired by (process, seq): ${summary.journal_coverage.edits} edits (change_received), ` +
        `${summary.journal_coverage.paired} with a cost line, ${summary.journal_coverage.failures} write failures, ${summary.journal_coverage.likely_interrupted} likely interrupted ` +
        `(the process's last edit, no journal line, its death logged after that edit), ${summary.journal_coverage.missing} without a result (allowed ${summary.journal_coverage.allowed_missing}), ` +
        `${summary.journal_coverage.unpaired} unpaired journal lines (excluded).\n\n` : "") +
      `Exit interval on the device clock (logcat -v monotonic): lower = latest pre-signal line of the run's own kill (AM "Killing" / am_kill "stop <pkg>"; ` +
      `recents swipe: also "remove task"), upper = earliest evidence of death (device-side /proc poll: gone or zombie; am_proc_died; cgroup empty). ` +
      `Exit Δ = interval − last change_received uptimeMs (b: − the send_tap probe logged right before the tap). A sample is valid for a bucket only when ` +
      `the WHOLE exit Δ interval is inside its tolerance: ${Object.entries(BUCKETS).map(([d, b]) => `${d} s = [${b.lo_ms}, ${b.hi_ms}] ms`).join("; ")}; ` +
      `b: [${SEND_WINDOW.lo_ms}, ${SEND_WINDOW.hi_ms}] ms after the tap. The device-side sleep is only aim (window centre minus measured overhead), never counted as measured. ` +
      `Exits not caused by the run's own kill (dependency_died, crash, ANR) are "excluded".\n\n` +
      `Leftover run channels: ${summary.leftover_channels?.length ? summary.leftover_channels.map((c) => `#${c.name} (id ${c.id}, run ${c.run_id} ${c.run_status}): ${c.archive}${c.http ? ` HTTP ${c.http}` : ""}`).join("; ") : "none"}.\n\n` +
      `Final channel archive: ${JSON.stringify(summary.channel_archive ?? null)}; concurrency check: ${JSON.stringify(summary.channel_activity ?? null)}.\n\n` +
      (summary.write_latency ? `Persistence window on this device (change_received → draft_written of the same change, all changes of the run): ` +
        `n=${summary.write_latency.samples}, min ${summary.write_latency.min_ms}, p50 ${summary.write_latency.p50_ms}, p95 ${summary.write_latency.p95_ms}, p99 ${summary.write_latency.p99_ms}, max ${summary.write_latency.max_ms} ms.\n\n` : "") +
      (summary.sleep_calibration ? `Device sleep overshoot (ms, 5 each): ${JSON.stringify(summary.sleep_calibration)}.\n\n` : "") +
      (summary.apk ? `APK: ${summary.apk.artifact ?? "?"}; downloaded sha256 ${summary.apk.file_sha256}; installed base.apk sha256 ${summary.apk.installed_base_apk_sha256} ` +
        `(${summary.apk.file_sha256 === summary.apk.installed_base_apk_sha256 ? "match" : "MISMATCH"}); versionName ${summary.apk.version_name}; versionCode ${summary.apk.version_code}; ` +
        `debuggable ${summary.apk.debuggable}; source commit ${summary.apk.source_commit ?? "?"}` +
        `${summary.apk.required_commit ? `; contains ${summary.apk.required_commit}: ${summary.apk.required_commit_compare ?? "unknown"}` : ""}.\n\n` : "") +
      (summary.journal_write_cost ? `Crash-journal write on the input thread (draft_journal_written costUs, encode + one store write; paired completed samples only, ${summary.journal_write_cost.unpaired_excluded ?? 0} unpaired lines excluded): ` +
        `n=${summary.journal_write_cost.samples}, min ${summary.journal_write_cost.min_us}, p50 ${summary.journal_write_cost.p50_us}, p95 ${summary.journal_write_cost.p95_us}, ` +
        `p99 ${summary.journal_write_cost.p99_us}, max ${summary.journal_write_cost.max_us} µs (target p95 < 1000 µs).\n\n` : "") +
      (summary.write_paths ? `Draft writes by path (app marker lines): ${JSON.stringify(summary.write_paths)}.\n\n` : "") +
      (summary.fast_progress ? `Fast typing valid / attempted (target ${FAST_RUNS} each; valid = whole exit interval inside the tolerance and burst 3-5 s): ` +
        `${Object.entries(summary.fast_progress).map(([d, p]) => `${d} s: ${p.valid} / ${p.attempted}`).join("; ")}.\n\n` : "") +
      (summary.bucket_progress ? `Valid / attempted per bucket (target ${RUNS_PER_BUCKET} valid): ${Object.entries(summary.bucket_progress).map(([d, p]) => `${d} s: ${p.valid} / ${p.attempted}`).join("; ")} ` +
        `(a sample counts for the bucket its measured interval proves, so "attempted" is runs aimed at that bucket).\n\n` : "") +
      (() => {
        const fast = summary.runs.filter((r) => r.mode === "fast");
        return fast.length ? `Fast typing bursts (device clock, first to last change_received): ${fast.map((r) => `${r.typing_span_ms ?? "?"} ms/${r.typing_changes ?? "?"} changes`).join(", ")}; within 3-5 s: ${fast.filter((r) => r.typing_in_3_to_5_s).length}/${fast.length}.\n\n` : "";
      })() +
      `Channel: #${CHANNEL} (created for this run; archived: ${summary.channel_archive?.archived ?? "n/a"}). ` +
      `Other activity in it during the run: ${summary.channel_activity?.other_activity ?? "unknown"}` +
      `${summary.channel_activity?.other_kinds ? ` ${JSON.stringify(summary.channel_activity.other_kinds)}` : ""}; ` +
      `coverage of that check: ${JSON.stringify(summary.channel_activity?.coverage ?? null)}.\n\n` +
      (summary.control ? `Positive control: change_received ${summary.control.last_change_received ? "present" : "missing"}, draft_written ${summary.control.draft_written ? "present" : "missing"} (write ${summary.control.write_after_change_ms ?? "-"} ms after the change).\n\n` : "");
    fs.writeFileSync(path.join(OUT, "results.md"), header + markdownTable(summary.runs, summary.summary));
  }
  process.exit(summary.result === "pass" ? 0 : 1);
}

main();
