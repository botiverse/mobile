// End-to-end leak tests for the acceptance executor's secret input and login
// window. The real acceptance_agent.mjs runs against:
//   * a fake `adb` (records every argv + stdin) that simulates the device: the
//     app's sign-in screen, the external browser login page (fields echo what
//     is typed), a browser that may already hold a session, the keyboard's
//     learned suggestions, autofill, the clipboard, logcat and the workflow's
//     continuous screenrecord loop;
//   * a fake emulator console (TCP, hosted by this test) for typing;
//   * a fake model that records every request payload.
// After the agent, postRun() replays the workflow's post-run collectors
// (logcat dump, activity dump, recording pull, evidence gate when present).
// FAKE values only; the same test is red on main and green on this head.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { execFile, execFileSync, spawn } from "node:child_process";

const FAKE_PASSWORD = "FAKE-pw-7Q";
const FAKE_EMAIL = "fake.qa.user@example.test";
const here = path.dirname(new URL(import.meta.url).pathname);
const agentPath = path.join(here, "acceptance_agent.mjs");
const gatePath = path.join(here, "secure_evidence.mjs");
const APP = "build.raft.app";
const BROWSER = "com.android.chrome";
const IME = "com.google.android.inputmethod.latin";

// Shared device model (used by the fake adb AND the fake console).
const DEVICE_LIB = String.raw`
const fs = require("fs");
const APP = "build.raft.app", BROWSER = "com.android.chrome", IME = "com.google.android.inputmethod.latin";
const load = (f) => JSON.parse(fs.readFileSync(f, "utf8"));
const store = (f, s) => fs.writeFileSync(f, JSON.stringify(s));
const pad = (n, w = 2) => String(n).padStart(w, "0");
const local = (ms) => { const d = new Date(ms); return pad(d.getUTCMonth() + 1) + "-" + pad(d.getUTCDate()) + " " + pad(d.getUTCHours()) + ":" + pad(d.getUTCMinutes()) + ":" + pad(d.getUTCSeconds()); };
const log = (s, tag, msg) => s.logs.push({ t: Date.now(), tag, msg });
function screen(s) {
  if (s.focus === BROWSER) {
    if (s.page === "continue") return "browser: Continue as " + s.sessionEmail;
    return "browser: email=[" + s.form.email + "] password=[" + s.form.password + "]" + (s.keyboard && s.imeLearned ? " IME-SUGGEST=[" + s.imeLearned + "]" : "");
  }
  if (!s.loggedIn) return "app: sign-in screen";
  return "app: home" + (s.imeLearned ? " IME-SUGGEST=[" + s.imeLearned + "]" : "") + (s.autofillSaved ? " AUTOFILL-CHIP=[" + s.autofillSaved + "]" : "") + (s.clipboard ? " CLIP=[" + s.clipboard + "]" : "");
}
function record(s) {
  if (s.recPaused) return;
  const seg = s.segments[s.recCurrent] || (s.segments[s.recCurrent] = []);
  const frame = screen(s);
  if (seg.at(-1) !== frame) seg.push(frame);
}
function type(s, text) {
  if (s.focus !== BROWSER || !s.field) return;
  s.form[s.field] += text;
  s.imeLearned += (s.imeLearned ? " " : "") + text;      // keyboard learns typed words (suggestion strip)
  log(s, "LatinIME", "commitText learned=" + text);
  if (s.autofillService !== "null") { s.autofillSaved = text; log(s, "AutofillManager", "save request value=" + text); }
  log(s, "chromium", "[form] field " + s.field + " value=" + s.form[s.field]);
}
module.exports = { APP, BROWSER, IME, load, store, local, log, screen, record, type };
`;

const FAKE_ADB = String.raw`#!/usr/bin/env node
const fs = require("fs");
const D = require(process.env.FAKE_DEVICE_LIB);
const env = process.env;
let args = process.argv.slice(2);
if (args[0] === "-s") args = args.slice(2);
let stdin = "";
try { if (!process.stdin.isTTY) stdin = fs.readFileSync(0, "utf8"); } catch {}
fs.appendFileSync(env.FAKE_ADB_LOG, JSON.stringify({ argv: process.argv.slice(2), stdin }) + "\n");
const s = D.load(env.FAKE_ADB_STATE);
const out = (x) => process.stdout.write(x);
const done = (code = 0) => { D.record(s); D.store(env.FAKE_ADB_STATE, s); process.exit(code); };
D.record(s);
const a = args.join(" ");
const nodes = () => {
  if (s.focus === D.BROWSER && s.page === "continue") {
    return '<node text="Continue as ' + s.sessionEmail + '" class="android.widget.Button" package="' + D.BROWSER + '" focused="false" clickable="true" password="false" bounds="[400,1000][680,1100]" />';
  }
  if (s.focus === D.BROWSER) {
    return '<node text="' + s.form.email + '" hint="Email" class="android.widget.EditText" package="' + D.BROWSER + '" focused="' + (s.field === "email") + '" clickable="true" password="false" bounds="[100,150][980,250]" />' +
      '<node text="' + s.form.password + '" hint="Password (shown)" class="android.widget.EditText" package="' + D.BROWSER + '" focused="' + (s.field === "password") + '" clickable="true" password="false" bounds="[100,350][980,450]" />';
  }
  if (!s.loggedIn) return '<node text="Sign in" class="android.widget.Button" package="' + D.APP + '" focused="false" clickable="true" password="false" bounds="[400,1700][680,1800]" />';
  return '<node text="Home" class="android.widget.TextView" package="' + D.APP + '" focused="false" clickable="false" password="false" bounds="[0,0][1080,200]" />';
};
const xml = () => '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">' + nodes() + '</hierarchy>';
if (a === "exec-out screencap -p") {
  const b = Buffer.alloc(24);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b, 0);
  b.writeUInt32BE(1080, 16); b.writeUInt32BE(2400, 20);
  process.stdout.write(Buffer.concat([b, Buffer.from("FRAME:" + D.screen(s))]));
  done();
}
if (a === "get-serialno") { out((s.serial || "emulator-5554") + "\n"); done(); }
if (a === "shell wm size") { out("Physical size: 1080x2400\n"); done(); }
if (a.startsWith("shell uiautomator dump")) { s.dumpFile = xml(); out("UI hierchary dumped to: /sdcard/qa_window_dump.xml\n"); done(); }
if (a === "exec-out cat /sdcard/qa_window_dump.xml") { out(s.dumpFile || xml()); done(); }
if (a === "exec-out uiautomator dump /dev/tty") { out(xml() + "UI hierchary dumped to: /dev/tty\n"); done(); }
if (args[0] === "shell" && args[1] === "input" && args[2] === "text") { D.type(s, args.slice(3).join(" ").replace(/%s/g, " ")); done(); }
if (args[0] === "shell" && args[1] === "input" && args[2] === "tap") {
  const x = +args[3], y = +args[4];
  if (s.focus === D.APP && !s.loggedIn && x >= 400 && x <= 680 && y >= 1700 && y <= 1800) {
    D.log(s, "SlockAppLogin", "web_login_attempt phase=opening");
    s.focus = D.BROWSER; s.browserRunning = true; s.page = s.sessionEmail ? "continue" : "login";
    D.log(s, "SlockAppLogin", "web_login_attempt phase=awaiting_callback");
  } else if (s.focus === D.BROWSER && s.page === "continue" && y >= 1000 && y <= 1100) {
    s.loggedIn = true; s.focus = D.APP;
    D.log(s, "SlockAppLogin", "app_login_complete result=app_shell reason=exchanged");
  } else if (s.focus === D.BROWSER && y >= 150 && y <= 250) { s.field = "email"; s.keyboard = true; }
  else if (s.focus === D.BROWSER && y >= 350 && y <= 450) { s.field = "password"; s.keyboard = true; }
  done();
}
if (a === "shell input keyevent 66" && s.focus === D.BROWSER && s.form.email && s.form.password) {
  s.loggedIn = !s.noReceipt; s.focus = D.APP; s.keyboard = s.keyboardStuck;
  if (!s.noReceipt) D.log(s, "SlockAppLogin", "app_login_complete result=app_shell reason=exchanged");
  done();
}
if (a === "shell input keyevent 111") { if (!s.keyboardStuck) s.keyboard = false; done(); }
if (a === "shell dumpsys window") { out("  mCurrentFocus=Window{1a2b u0 " + s.focus + "/" + (s.focus === D.APP ? "build.raft.app.MainActivity" : "org.chromium.chrome.browser.customtabs.CustomTabActivity") + "}\n"); done(); }
if (a === "shell dumpsys input_method") { out("  mInputShown=" + (s.keyboard ? "true" : "false") + "\n"); done(); }
if (a === "shell dumpsys activity top") { out("TASK " + s.focus + "\n  mText=" + (s.focus === D.BROWSER ? s.form.email + " " + s.form.password : "home") + "\n"); done(); }
if (a === "shell date +%s") { out(String(Math.floor(Date.now() / 1000)) + "\n"); done(); }
if (a === "shell date '+%m-%d %H:%M:%S'") { out(D.local(Date.now()) + "\n"); done(); }
if (args[0] === "logcat" && args.includes("epoch")) {
  for (const l of s.logs) if (l.tag === "SlockAppLogin") out((l.t / 1000).toFixed(3) + "  4242  4242 I SlockAppLogin: " + l.msg + "\n");
  done();
}
if (args[0] === "logcat" && args.includes("threadtime")) {
  for (const l of s.logs) out(D.local(l.t) + "." + String(l.t % 1000).padStart(3, "0") + "  4242  4242 I " + l.tag + ": " + l.msg + "\n");
  done();
}
if (a === "shell settings put secure autofill_service null") { s.autofillService = "null"; done(); }
if (a === "shell settings get secure autofill_service") { out(s.autofillService + "\n"); done(); }
if (a === "shell settings get secure default_input_method") { out(D.IME + "/com.android.inputmethod.latin.LatinIME\n"); done(); }
if (a === "shell pm clear " + D.IME) { s.imeLearned = ""; out("Success\n"); done(); }
if (a === "shell pm clear " + D.BROWSER) { s.form = { email: "", password: "" }; s.sessionEmail = ""; s.browserRunning = false; out("Success\n"); done(); }
if (a === "shell am force-stop " + D.BROWSER) { s.browserRunning = false; done(); }
if (a === "shell pidof " + D.BROWSER) { if (!s.browserRunning) done(1); out("777\n"); done(); }
if (a === "shell pidof screenrecord") done(1);
if (a === "shell pidof " + D.APP) { out("1234\n"); done(); }
if (/clipboard/.test(a)) { s.clipboard = a; done(); }
if (a === "shell touch /data/local/tmp/qa-rec-pause") { s.recPaused = true; done(); }
if (a === "shell rm -f /data/local/tmp/qa-rec-pause") { if (s.recPaused) { s.recPaused = false; s.recIndex = (s.recIndex || 1) + 1; s.recCurrent = "qa-rec-" + String(s.recIndex).padStart(2, "0") + ".mp4"; } done(); }
if (a === "shell ls /sdcard/qa-rec-*.mp4 2>/dev/null; echo LIST-END") { for (const n of Object.keys(s.segments).sort()) out("/sdcard/" + n + "\n"); out("LIST-END\n"); done(); }
if (args[0] === "shell" && args[1] === "rm" && args[2] === "-f" && /^\/sdcard\/qa-rec-\d+\.mp4$/.test(args[3] || "")) { delete s.segments[args[3].slice(8)]; done(); }
done();
`;

const FAKE_MODEL = String.raw`
import fs from "node:fs";
export function builtinModels() {
  const script = JSON.parse(process.env.FAKE_MODEL_SCRIPT);
  let call = 0;
  return {
    getModel: (provider, name) => ({ id: name, provider }),
    async complete(model, { messages }) {
      fs.appendFileSync(process.env.FAKE_MODEL_LOG, JSON.stringify(messages) + "\n");
      const action = script[Math.min(call, script.length - 1)];
      call++;
      return { stopReason: "stop", content: [{ type: "text", text: JSON.stringify(action) }], usage: {} };
    },
  };
}
`;

// Fake emulator console: `event text X` types X on the fake device.
async function startConsole(root, statePath, libPath) {
  const D = (await import(libPath)).default;
  const lines = [];
  const server = net.createServer((sock) => {
    sock.setEncoding("utf8");
    sock.write("Android Console: Authentication required\r\nOK\r\n");
    let buf = "";
    sock.on("data", (d) => {
      buf += d;
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 1);
        lines.push(line);
        if (line.startsWith("auth ")) sock.write(line === "auth fake-console-token" ? "OK\r\n" : "KO: bad auth\r\n");
        else if (line.startsWith("event text ")) {
          const s = D.load(statePath);
          D.record(s);
          D.type(s, line.slice("event text ".length));
          D.record(s);
          D.store(statePath, s);
          sock.write("OK\r\n");
        } else if (line === "quit") sock.end();
      }
    });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const tokenFile = path.join(root, "console-token");
  fs.writeFileSync(tokenFile, "fake-console-token\n");
  return { port: server.address().port, tokenFile, lines, close: () => server.close() };
}

async function runAgent({ script, state = {}, console: withConsole = true, killWhen = null }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "qa-secure-input-"));
  const bin = path.join(root, "bin");
  const out = path.join(root, "qa-evidence");
  fs.mkdirSync(bin);
  const libPath = path.join(root, "device-lib.cjs");
  fs.writeFileSync(libPath, DEVICE_LIB);
  fs.writeFileSync(path.join(bin, "adb"), FAKE_ADB, { mode: 0o755 });
  const modelModule = path.join(root, "fake-model.mjs");
  fs.writeFileSync(modelModule, FAKE_MODEL);
  const adbLog = path.join(root, "device", "adb.log");
  const adbState = path.join(root, "device", "state.json");
  fs.mkdirSync(path.dirname(adbLog));
  fs.writeFileSync(adbLog, "");
  fs.writeFileSync(adbState, JSON.stringify({
    focus: APP, loggedIn: false, page: "", field: "", keyboard: false, form: { email: "", password: "" },
    sessionEmail: "", browserRunning: false, imeLearned: "", autofillService: "com.google.android.gms/.autofill.AutofillService",
    autofillSaved: "", clipboard: "", logs: [], segments: {}, recCurrent: "qa-rec-01.mp4", recPaused: false, ...state,
  }));
  const modelLog = path.join(root, "model.log");
  fs.writeFileSync(modelLog, "");
  const consoleSrv = withConsole ? await startConsole(root, adbState, libPath) : null;
  const env = {
    PATH: `${bin}:${path.dirname(process.execPath)}:/usr/bin:/bin`,
    HOME: root,
    TMPDIR: path.join(root, "tmp"),
    PI_API_KEY: "fake-model-key",
    SLOCK_PI_AI_MODULE: modelModule,
    FAKE_MODEL_SCRIPT: JSON.stringify(script),
    FAKE_MODEL_LOG: modelLog,
    FAKE_ADB_LOG: adbLog,
    FAKE_ADB_STATE: adbState,
    FAKE_DEVICE_LIB: libPath,
    QA_TEST_EMAIL: FAKE_EMAIL,
    QA_TEST_PASSWORD: FAKE_PASSWORD,
    QA_PLAYBOOK: path.join(root, "no-playbook.md"),
    ...(consoleSrv ? { QA_EMULATOR_CONSOLE_PORT: String(consoleSrv.port), QA_EMULATOR_CONSOLE_TOKEN_FILE: consoleSrv.tokenFile } : {}),
  };
  fs.mkdirSync(env.TMPDIR);
  const result = await new Promise((resolve) => {
    let stdout = "", stderr = "";
    const child = spawn(process.execPath, [agentPath, "--goal", "log in and confirm home", "--max-turns", "20", "--max-minutes", "10", "--out", out],
      { cwd: root, env, stdio: ["ignore", "pipe", "pipe"] });
    child.stdout.on("data", (d) => { stdout += d; });
    child.stderr.on("data", (d) => { stderr += d; });
    const timer = setTimeout(() => child.kill("SIGKILL"), 120000);
    let poll = null;
    if (killWhen) {
      poll = setInterval(() => {
        try { if (killWhen({ out, state: JSON.parse(fs.readFileSync(adbState, "utf8")) })) { clearInterval(poll); child.kill("SIGTERM"); } } catch { /* not yet */ }
      }, 100);
    }
    child.on("exit", (code) => { clearTimeout(timer); if (poll) clearInterval(poll); resolve({ code, stdout, stderr }); });
  });
  consoleSrv?.close();
  const run = {
    root, out, ...result, env, adbState, consoleLines: consoleSrv?.lines ?? [],
    adbCalls: () => fs.readFileSync(adbLog, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)),
    modelCalls: fs.readFileSync(modelLog, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)),
    device: () => JSON.parse(fs.readFileSync(adbState, "utf8")),
  };
  postRun(run);
  return run;
}

// The workflow's post-run collectors (qa-acceptance.yml "Run acceptance agent").
function postRun(run) {
  const adb = (args) => execFileSync("adb", args, { env: run.env, encoding: "utf8", cwd: run.root });
  fs.mkdirSync(run.out, { recursive: true });
  fs.writeFileSync(path.join(run.out, "logcat-full.txt"), adb(["logcat", "-d", "-v", "threadtime"]));
  // Activity dump: same condition as the workflow on this head; main always dumps.
  const supp = path.join(run.out, "capture-suppression.json");
  let dump = !fs.existsSync(gatePath);
  try { const v = JSON.parse(fs.readFileSync(supp, "utf8")); dump = v.unresolved === false && v.active === false; } catch { /* keep main behaviour */ }
  if (dump) fs.writeFileSync(path.join(run.out, "activity-top.txt"), adb(["shell", "dumpsys", "activity", "top"]));
  // Pull every screenrecord segment still on the device.
  for (const [name, frames] of Object.entries(run.device().segments)) fs.writeFileSync(path.join(run.out, name), `MP4:${frames.join("|")}`);
  if (fs.existsSync(gatePath)) {
    try { execFileSync(process.execPath, [gatePath, "--dir", run.out], { env: run.env, stdio: "ignore" }); } catch { run.gateFailed = true; }
  }
}

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  const files = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) files.push(...walk(p)); else files.push(p);
  }
  return files;
}

function payloadBytes(messages) {
  const parts = [];
  for (const m of messages) for (const c of Array.isArray(m.content) ? m.content : [{ type: "text", text: m.content }]) {
    if (c.type === "image") parts.push(Buffer.from(c.data, "base64").toString("latin1"));
    else parts.push(String(c.text ?? ""));
  }
  return parts.join("\n");
}

const lastUserHasImage = (messages) => {
  const last = messages.at(-1);
  return Array.isArray(last?.content) && last.content.some((c) => c.type === "image");
};

// Every exit, collected so a red run names all of them at once.
function leaks(run) {
  const found = [];
  for (const [label, secret] of [["password", FAKE_PASSWORD], ["email", FAKE_EMAIL]]) {
    for (const call of run.adbCalls()) {
      if (call.argv.join(" ").includes(secret)) found.push(`${label}: process args (adb ${call.argv.slice(0, 3).join(" ")} ...)`);
      if (call.stdin.includes(secret)) found.push(`${label}: adb stdin (device shell)`);
    }
    for (const file of [...walk(run.out), ...walk(path.join(run.root, "tmp"))]) {
      if (!fs.readFileSync(file).toString("latin1").includes(secret)) continue;
      const rel = path.relative(run.root, file);
      const kind = /\.png$/.test(rel) ? "saved screenshot" : /\.mp4$/.test(rel) ? "screen recording" : /logcat|activity-top/.test(rel) ? "device log / screen dump" : /tmp\//.test(rel) ? "temp/command file" : "evidence file";
      found.push(`${label}: ${kind} ${rel}`);
    }
    run.modelCalls.forEach((messages, i) => {
      if (payloadBytes(messages).includes(secret)) found.push(`${label}: model request payload #${i + 1}`);
    });
    if (run.stdout.includes(secret) || run.stderr.includes(secret)) found.push(`${label}: executor stdout/stderr`);
    if (run.device().clipboard.includes(secret)) found.push(`${label}: device clipboard`);
  }
  return [...new Set(found)];
}

function assertNoLeak(run) {
  const found = leaks(run);
  if (found.length) console.error(`LEAKS (${found.length}):\n  ${found.join("\n  ")}`);
  assert.deepEqual(found, [], "secret leaks");
}

const readJson = (run, f) => JSON.parse(fs.readFileSync(path.join(run.out, f), "utf8"));
const cleanup = (run) => fs.rmSync(run.root, { recursive: true, force: true });

// Sign in -> browser login page -> type both -> enter -> close the window.
const LOGIN_SCRIPT = [
  { action: "tap", x: 540, y: 1750 },
  { action: "dump_ui" },
  { action: "tap", x: 540, y: 200 },
  { action: "text", value: "{{QA_EMAIL}}" },
  { action: "tap", x: 540, y: 400 },
  { action: "text", value: "{{QA_PASSWORD}}" },
  { action: "key", name: "enter" },
  { action: "secret_phase", state: "end" },
  { action: "wait", seconds: 1 },
  { action: "finish", verdict: "pass", reason: "home visible", diagnosis: null, annotations: [] },
];

test("login with fake credentials: never in process args, files, screenshots, recordings, logs, model payloads, IME, autofill or clipboard", async () => {
  const run = await runAgent({ script: LOGIN_SCRIPT });
  try {
    assertNoLeak(run);
    // The values still reached the device: once each, over the console socket.
    assert.deepEqual(run.consoleLines.filter((l) => l.startsWith("event text ")), [`event text ${FAKE_EMAIL}`, `event text ${FAKE_PASSWORD}`]);
    const device = run.device();
    assert.equal(device.loggedIn, true);
    assert.equal(device.imeLearned, "", "keyboard learned data cleared");
    assert.equal(device.autofillSaved, "", "autofill was off while typing");
    assert.equal(device.browserRunning, false, "browser login surface force-stopped + cleared");
    // From the sign-in tap (turn 1) through the window close: no frame saved or sent.
    const shots = fs.readdirSync(run.out).filter((f) => /^step-\d+\.png$/.test(f)).sort();
    assert.deepEqual(shots, ["step-00.png", "step-08.png", "step-09.png"]);
    for (let call = 1; call <= 7; call++) assert.ok(!lastUserHasImage(run.modelCalls[call]), `model got a screenshot inside the login window (call #${call + 1})`);
    // dump_ui inside the window: bounds only.
    assert.match(payloadBytes(run.modelCalls[2]), /form outline \(bounds only, login window\):\ninput#1 \[100,150\]\[980,250\]\ninput#2 \[100,350\]\[980,450\]/);
    const supp = readJson(run, "capture-suppression.json");
    assert.equal(supp.unresolved, false);
    assert.equal(supp.phases.length, 1);
    const [phase] = supp.phases;
    assert.equal(phase.status, "resumed");
    assert.equal(phase.reason, "login_page");
    assert.ok(phase.end_ts >= phase.start_ts);
    assert.deepEqual(Object.keys(phase.receipts).sort(), ["autofill_off", "ime_cleared", "inputs_cleared", "login_exit", "screen_switch"]);
    assert.equal(phase.receipts.login_exit.kind, "login_completed");
    // The segment that recorded the login-page entry was deleted whole on the device; recording resumed in a new one.
    assert.deepEqual(phase.dropped_segments, ["qa-rec-01.mp4"]);
    assert.deepEqual(Object.keys(device.segments), ["qa-rec-02.mp4"]);
    assert.match(fs.readFileSync(path.join(run.out, "logcat-full.txt"), "utf8"), /capture suppressed: login window/);
    const steps = fs.readFileSync(path.join(run.out, "steps.jsonl"), "utf8");
    assert.match(steps, /"event":"capture_suppressed","phase":"begin"/);
    assert.match(steps, /"event":"capture_suppressed","phase":"end"/);
    assert.equal(readJson(run, "verdict.json").verdict, "pass");
  } finally {
    cleanup(run);
  }
});

test("browser already holds a session: its page (account identity) is never captured; window closes on the app's receipt", async () => {
  const run = await runAgent({
    script: [
      { action: "tap", x: 540, y: 1750 },
      { action: "tap", x: 540, y: 1050 },
      { action: "secret_phase", state: "end" },
      { action: "finish", verdict: "pass", reason: "home visible", diagnosis: null, annotations: [] },
    ],
    state: { sessionEmail: FAKE_EMAIL },
  });
  try {
    assertNoLeak(run);
    assert.equal(run.consoleLines.length, 0, "nothing typed");
    const [phase] = readJson(run, "capture-suppression.json").phases;
    assert.equal(phase.status, "resumed");
    assert.equal(phase.receipts.login_exit.kind, "login_completed");
    assert.equal(run.device().browserRunning, false);
  } finally {
    cleanup(run);
  }
});

test("a failed login receipt keeps capture suppressed and ends UNKNOWN (no frame, dump or recording afterwards)", async () => {
  const run = await runAgent({ script: LOGIN_SCRIPT, state: { noReceipt: true } });
  try {
    assertNoLeak(run);
    const shots = fs.readdirSync(run.out).filter((f) => /^step-\d+\.png$/.test(f)).sort();
    assert.deepEqual(shots, ["step-00.png"]);
    for (let i = 1; i < run.modelCalls.length; i++) assert.ok(!lastUserHasImage(run.modelCalls[i]), `model call #${i + 1} has a screenshot`);
    const verdict = readJson(run, "verdict.json");
    assert.equal(verdict.verdict, "error");
    assert.equal(verdict.secure_input?.status, "UNKNOWN");
    assert.ok(verdict.secure_input.reasons.some((r) => /login-complete/.test(r)));
    assert.equal(readJson(run, "capture-suppression.json").unresolved, true);
    assert.ok(!fs.existsSync(path.join(run.out, "activity-top.txt")), "no post-run screen dump while unresolved");
    assert.equal(run.code, 2);
  } finally {
    cleanup(run);
  }
});

test("an unconfirmed cleanup (keyboard stuck) is not a receipt: stays suppressed, UNKNOWN", async () => {
  const run = await runAgent({ script: LOGIN_SCRIPT, state: { keyboardStuck: true } });
  try {
    assertNoLeak(run);
    const supp = readJson(run, "capture-suppression.json");
    assert.equal(supp.unresolved, true);
    assert.ok(supp.phases[0].reasons.some((r) => /keyboard/.test(r)));
    assert.ok(!fs.existsSync(path.join(run.out, "step-08.png")));
  } finally {
    cleanup(run);
  }
});

test("a device without a secure input channel refuses to type (fail closed) and the window ends UNKNOWN", async () => {
  const run = await runAgent({ script: LOGIN_SCRIPT, console: false, state: { serial: "R58M00FAKE" } });
  try {
    assertNoLeak(run);
    assert.equal(run.device().form.email, "", "nothing typed");
    assert.equal(readJson(run, "verdict.json").secure_input?.status, "UNKNOWN");
  } finally {
    cleanup(run);
  }
});

test("interrupted inside the login window (SIGTERM after the password): browser form, IME and logs never leave", async () => {
  const run = await runAgent({
    script: [...LOGIN_SCRIPT.slice(0, 6), { action: "wait", seconds: 10 }, { action: "wait", seconds: 10 }],
    killWhen: ({ state }) => state.form.password === FAKE_PASSWORD,
  });
  try {
    assertNoLeak(run);
    const supp = readJson(run, "capture-suppression.json");
    assert.equal(supp.unresolved, true);
    assert.ok(supp.phases[0].reasons.some((r) => /interrupted \(SIGTERM\)/.test(r)));
    // The device still shows the filled form and the keyboard learned the values: only the gate stands between.
    assert.equal(run.device().form.password, FAKE_PASSWORD);
    assert.ok(!fs.existsSync(path.join(run.out, "activity-top.txt")));
  } finally {
    cleanup(run);
  }
});

export { leaks, runAgent, LOGIN_SCRIPT };
