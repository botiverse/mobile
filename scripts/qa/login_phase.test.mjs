// #222 login protection, end to end with fake values, a loopback server and no external account.
// A fake `adb` and its stdin-only form driver share one device model: the app's sign-in button opens
// a browser login form that shows what is typed, the screen recorder records every frame while it
// runs, logcat keeps timestamped lines, uiautomator writes its dump to a device file unless read
// from /dev/tty. A fake model records every payload; a fake curl records every upload.
//
// The first test runs the pipeline the workflow runs. Against main's scripts (no login_phase.mjs:
// the model logs in with placeholders, recorder already on) it finds the fake password and email
// in adb argv, device files, screenshots, the recording, model payloads and the Stamp upload.
// On this branch it finds them nowhere.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { spawn, spawnSync } from "node:child_process";

const here = path.dirname(new URL(import.meta.url).pathname);
const PASSWORD = "FAKE-pw-7Qz";
const EMAIL = "fake.qa@example.test";
const RUN = { GITHUB_RUN_ID: "4242", GITHUB_RUN_ATTEMPT: "1" };
const hasLoginPhase = fs.existsSync(path.join(here, "login_phase.mjs"));
const phase = hasLoginPhase ? await import("./login_phase.mjs") : null;
const gate = fs.existsSync(path.join(here, "secure_evidence.mjs")) ? await import("./secure_evidence.mjs") : null;

// Only Chrome's read-only tab metadata crosses this local socket; the form values use adb stdin.
const tabMetadata = http.createServer((req, res) => {
  res.writeHead(req.url === "/json/list" ? 200 : 404);
  res.end(JSON.stringify([{ type: "page", url: "https://app.raft.build/login/app" }]));
});
await new Promise((resolve) => tabMetadata.listen(0, "127.0.0.1", resolve));
test.after(() => new Promise((resolve) => tabMetadata.close(resolve)));

const DEVICE = String.raw`
const fs = require("fs");
const APP = "build.raft.app", CHROME = "com.android.chrome";
const pad = (n, w = 2) => String(n).padStart(w, "0");
const stamp = (d = new Date()) => pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds()) + "." + pad(d.getMilliseconds(), 3);
const load = (f) => JSON.parse(fs.readFileSync(f, "utf8"));
const save = (f, s) => fs.writeFileSync(f, JSON.stringify(s));
const log = (s, tag, msg) => s.logs.push(stamp() + "  4242  4242 I " + tag + ": " + msg);
const screen = (s) => s.screen === "form" ? "browser login email=[" + s.form.email + "] password=[" + "*".repeat(s.form.password.length) + "]" : "app " + s.screen;
function frame(s) { if (s.recording) s.frames.push(screen(s)); }
function type(s, text) {
  if (s.screen !== "form" || !s.field) return;
  s.form[s.field] += text;
  if (s.recordOnType) s.recording = true;               // something starts recording inside the window
  s.ime += " " + text;                                  // the keyboard learns what is typed
  log(s, "chromium", "form field " + s.field + " changed value=" + s.form.email);
}
function xml(s) {
  const n = (text, pkg, b, cls = "android.widget.TextView", extra = "") => '<node text="' + text + '" class="' + cls + '" package="' + pkg + '" ' + extra + ' bounds="' + b + '" />';
  const button = (text, y) => n(text, CHROME, "[100," + y + "][900," + (y + 80) + "]", "android.widget.Button");
  const nodes = s.screen === "signin" ? n("Sign in to Raft", APP, "[100,1000][900,1100]")
    : s.screen === "form" ? n(s.form.email, CHROME, "[100,300][900,400]", "android.widget.EditText", 'resource-id="login-email" password="false"') + n("*".repeat(s.form.password.length), CHROME, "[100,500][900,600]", "android.widget.EditText", 'resource-id="login-password" password="true"') + button("Sign in", s.fills ? 700 : 900)
    : s.screen === "save-prompt" ? n("Save password?", CHROME, "[100,280][900,380]", "android.widget.TextView", 'resource-id="com.android.chrome:id/message_title"') + n("Not now", CHROME, "[100,400][900,480]", "android.widget.Button", 'resource-id="com.android.chrome:id/negative_button"')
    : s.screen === "approval" ? button("Allow", 100) + button("Confirm sign-in", 800)
    : s.screen === "return" ? button("Open", 150) + button("Open the app", 850)
    : n("Home", APP, "[0,0][1080,200]") + n("#all", APP, "[0,300][1080,400]");
  return '<?xml version="1.0"?><hierarchy>' + (s.uiBroken ? "" : nodes) + "</hierarchy>";
}
function submit(s) {
  if (s.screen !== "return" || !s.form.email || !s.form.password) return;
  s.screen = "home";
  if (s.noReceipt) return;
  log(s, "SlockAppLogin", "app_login_complete result=" + (s.refuse ? "login_error reason=exchange_failed" : "app_shell reason=exchanged"));
}
module.exports = { APP, CHROME, load, save, log, screen, frame, type, xml, submit };
`;

const FAKE_ADB = String.raw`#!/usr/bin/env node
const fs = require("fs");
const D = require(process.env.FAKE_DEVICE);
const f = process.env.FAKE_STATE;
let args = process.argv.slice(2);
fs.appendFileSync(process.env.FAKE_ARGV, JSON.stringify(args) + "\n");
if (args[0] === "-s") args = args.slice(2);
const s = D.load(f), a = args.join(" "), out = (x) => process.stdout.write(x);
const end = (code = 0) => { D.frame(s); D.save(f, s); process.exit(code); };
if (s.adbDown) { process.stderr.write("error: device offline\n"); process.exit(1); }
if (a === "forward tcp:0 localabstract:chrome_devtools_remote") { out(process.env.FAKE_TAB_PORT); end(); }
if (a === "get-serialno") { out(s.serial + "\n"); end(); }
if (a.startsWith("shell cat /data/local/tmp/qa-run-owner")) { out((s.owner || "") + "\n"); end(); }
if (a === "shell pidof screenrecord; echo rc=$?") { out(s.recording ? "999\nrc=0\n" : "rc=1\n"); end(); }
if (a === "shell pkill -INT screenrecord; true" || a === "shell pkill -INT screenrecord") { s.recording = Boolean(s.recorderLoop); end(); }
if (a.includes("screenrecord --size")) { s.recording = true; end(); }
if (a === "logcat -c") { s.logs = []; if (s.earlyReceipt) D.log(s, "SlockAppLogin", "app_login_complete result=app_shell reason=exchanged"); end(); }
if (args[0] === "logcat") { if (s.logcatBroken) end(1); out(s.logs.filter((l) => !args.includes("-s") || l.includes("SlockAppLogin")).join("\n") + "\n"); end(); }
if (a === "shell settings put secure autofill_service null") { s.autofill = false; end(); }
if (a === "shell settings get secure default_input_method") { out("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n"); end(); }
if (args[1] === "pm" && args[2] === "clear") { if (args[3] === D.CHROME) s.form = { email: "", password: "" }; else s.ime = ""; s.cleared.push(args[3]); out("Success\n"); end(); }
if (a === "shell dumpsys window") {
  const waiting = s.screen === "home" && s.focusWaits > 0;
  if (waiting) s.focusWaits--;
  out("  mCurrentFocus=Window{1a u0 " + (waiting || s.screen === "form" ? D.CHROME : D.APP) + "/x.Main}\n"); end();
}
if (a === "exec-out uiautomator dump /dev/tty") {
  out(D.xml(s) + "UI hierchary dumped to: /dev/tty\n");
  if (s.saveDismissed && s.screen === "save-prompt") s.screen = "approval";
  end();
}
if (a.startsWith("shell uiautomator dump")) { s.files.dump = D.xml(s); end(); }
if (a.startsWith("exec-out cat /sdcard/qa_window_dump.xml")) { out(s.files.dump || ""); end(); }
if (a === "exec-out screencap -p") {
  const b = Buffer.alloc(24); Buffer.from("\x89PNG\r\n\x1a\n", "latin1").copy(b); b.writeUInt32BE(1080, 16); b.writeUInt32BE(2400, 20);
  process.stdout.write(Buffer.concat([b, Buffer.from("FRAME " + D.screen(s))])); end();
}
if (a === "shell wm size") { out("Physical size: 1080x2400\n"); end(); }
if (args[1] === "input" && args[2] === "text") { D.type(s, args.slice(3).join(" ").replace(/%s/g, " ")); end(); }
if (a.startsWith("shell -T uiautomator runtest ") && args.includes("RaftQaLoginForm#testFill")) {
  if (process.env.QA_TEST_PASSWORD || process.env.QA_TEST_EMAIL) end(1);
  const [email, password] = fs.readFileSync(0, "utf8").split("\n");
  if (s.driverResult === "site-unverified") { out("QA_LOGIN_SITE_UNVERIFIED\n"); end(); }
  if (s.screen !== "form" || s.refuseField || s.driverResult === "no-markers") end();
  s.field = "email"; D.type(s, email); out("QA_LOGIN_EMAIL_SET\n");
  if (s.driverResult === "email-only") end();
  s.field = "password"; D.type(s, password); out("QA_LOGIN_PASSWORD_SET\n");
  s.fills = (s.fills || 0) + 1; end();
}
if (args[1] === "input" && args[2] === "tap") {
  const y = Number(args[4]);
  if (s.screen === "signin" && y >= 1000 && y <= 1100) { s.screen = "form"; D.log(s, "SlockAppLogin", "web_login_attempt phase=opening"); }
  else if (s.screen === "form" && y === 740 && s.form.email === "fake.qa@example.test" && s.form.password === "FAKE-pw-7Qz") s.screen = "approval";
  else if (s.screen === "approval" && y === 840) {
    s.confirmTaps = (s.confirmTaps || 0) + 1;
    s.screen = s.interceptConfirm && s.confirmTaps === 1 ? "save-prompt" : "return";
  } else if (s.screen === "save-prompt" && y === 440) s.saveDismissed = true;
  else if (s.screen === "return" && y === 890) D.submit(s);
  end();
}
if (a === "shell pidof build.raft.app") { out("1234\n"); end(); }
end();
`;

const FAKE_MODEL = String.raw`
import fs from "node:fs";
export function builtinModels() {
  const script = JSON.parse(process.env.FAKE_MODEL_SCRIPT);
  let call = 0;
  return {
    getModel: (provider, id) => ({ id, provider }),
    async complete(model, { messages }) {
      // What the runner sends (its own earlier replies are the model's, not the runner's).
      const seen = messages.filter((m) => m.role === "user").flatMap((m) => m.content).map((b) => b.type === "image" ? Buffer.from(b.data, "base64").toString("latin1") : b.text);
      fs.appendFileSync(process.env.FAKE_MODEL_LOG, JSON.stringify(seen) + "\n");
      const action = script[Math.min(call++, script.length - 1)];
      return { stopReason: "stop", content: [{ type: "text", text: JSON.stringify(action) }], usage: {} };
    },
  };
}
`;

function fakeWorld(state = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "qa-login-"));
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin);
  const files = {
    device: path.join(root, "device.cjs"), state: path.join(root, "state.json"), argv: path.join(root, "argv.jsonl"),
    model: path.join(root, "model.mjs"), modelLog: path.join(root, "model.jsonl"), uploads: path.join(root, "uploads"),
  };
  fs.writeFileSync(files.device, DEVICE);
  fs.writeFileSync(path.join(bin, "adb"), FAKE_ADB, { mode: 0o755 });
  fs.writeFileSync(files.model, FAKE_MODEL);
  fs.writeFileSync(files.argv, "");
  fs.writeFileSync(files.modelLog, "");
  fs.mkdirSync(files.uploads);
  fs.writeFileSync(path.join(root, "qa-login-form.jar"), "fake driver");
  // Fake Stamp: keeps every evidence body it is sent.
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
url=""; for a in "$@"; do case "$a" in https://*) url="$a";; esac; done
case "$url" in *"/evidence?"*) for a in "$@"; do case "$a" in @*) cp "\${a#@}" "${files.uploads}/$RANDOM-$(basename "\${a#@}")";; esac; done;; esac
exit 0
`, { mode: 0o755 });
  const D = createRequire(import.meta.url)(files.device);
  D.save(files.state, { serial: "emulator-5554", owner: `${RUN.GITHUB_RUN_ID}:${RUN.GITHUB_RUN_ATTEMPT}`, screen: "signin", form: { email: "", password: "" },
    ime: "", autofill: true, recording: false, frames: [], logs: [], files: {}, cleared: [], ...state });
  const env = {
    PATH: `${bin}:${path.dirname(process.execPath)}:/usr/bin:/bin`, HOME: root, RUNNER_TEMP: root, ...RUN,
    FAKE_TAB_PORT: String(tabMetadata.address().port), FAKE_DEVICE: files.device, FAKE_STATE: files.state, FAKE_ARGV: files.argv, FAKE_MODEL_LOG: files.modelLog,
  };
  return { root, files, env, D, state: () => D.load(files.state), close: () => fs.rmSync(root, { recursive: true, force: true }) };
}

const runAsync = (world, args, extraEnv = {}) => new Promise((resolve) => {
  const child = spawn(process.execPath, args, { cwd: world.root, env: { ...world.env, ...extraEnv } });
  let stdout = "", stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (status) => resolve({ status, stdout, stderr }));
});

const allBytes = (dir) => fs.readdirSync(dir, { withFileTypes: true, recursive: true })
  .filter((e) => e.isFile()).map((e) => fs.readFileSync(path.join(e.parentPath ?? e.path, e.name), "latin1")).join("\n");

test("the fake password and email appear nowhere: argv, device files, screenshots, recording, logs, model payloads, upload", async () => {
  const w = fakeWorld({ focusWaits: 2, interceptConfirm: true });
  const ev = path.join(w.root, "qa-evidence");
  const creds = { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD };
  const outputs = [];
  try {
    let loginOk = true;
    if (hasLoginPhase) {
      const login = await runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--platform", "android", "--password-login", "true", "--device", "emulator-5554", "--out", ev], creds);
      outputs.push(login.stdout, login.stderr);
      loginOk = login.status === 0;
      assert.equal(login.status, 0, login.stdout + login.stderr);
    }
    // The workflow starts the recorder only after a complete login record (main: before the agent).
    if (loginOk) spawnSync("adb", ["shell", "nohup sh -c 'screenrecord --size 540x1200 /sdcard/qa-rec-01.mp4' &"], { env: w.env });
    const script = [
      { action: "tap", x: 500, y: 1050 }, { action: "tap", x: 500, y: 350 }, { action: "text", value: "{{QA_EMAIL}}" },
      { action: "tap", x: 500, y: 550 }, { action: "text", value: "{{QA_PASSWORD}}" }, { action: "text", value: PASSWORD },
      { action: "key", name: "enter" }, { action: "dump_ui" }, { action: "finish", verdict: "pass", reason: "home visible", diagnosis: null, annotations: [] },
    ];
    // Worst case: the agent itself has the credentials (the workflow strips them with env -u).
    const agent = await runAsync(w, [path.join(here, "acceptance_agent.mjs"), "--goal", "open #all", "--out", ev, "--max-turns", "20",
      "--max-minutes", "10", "--no-progress-turns", "3", "--playbook", "none.md", "--device", "emulator-5554"],
    { ...creds, PI_API_KEY: "fake-key", SLOCK_PI_AI_MODULE: w.files.model, FAKE_MODEL_SCRIPT: JSON.stringify(script) });
    outputs.push(agent.stdout, agent.stderr);
    // Post-run collection as in the workflow (no second `logcat -c`: the gate must drop window lines itself).
    fs.mkdirSync(ev, { recursive: true });
    fs.writeFileSync(path.join(ev, "logcat-full.txt"), spawnSync("adb", ["logcat", "-d", "-v", "threadtime"], { env: w.env, encoding: "utf8" }).stdout);
    fs.writeFileSync(path.join(ev, "qa-rec-01.mp4"), w.state().frames.join("\n"));
    const stamp = { ...creds, STAMP_RUN_CASE_ID: "rc-fake", STAMP_URL: "https://stamp.invalid", STAMP_TOKEN: "fake", RUNNER_TEMP: w.root };
    fs.writeFileSync(path.join(w.root, "stamp-claim-rc-fake"), "fake-lease");
    fs.writeFileSync(path.join(w.root, "stamp-bootstrap-spec-rc-fake.json"), JSON.stringify({ required: false }));
    if (gate) outputs.push(spawnSync(process.execPath, [path.join(here, "secure_evidence.mjs"), "--dir", ev], { env: { ...w.env, ...creds }, encoding: "utf8" }).stdout);
    outputs.push(spawnSync("bash", [path.join(here, "stamp_report.sh"), "report-evidence", ev], { cwd: w.root, env: { ...w.env, ...stamp }, encoding: "utf8" }).stderr);

    const places = {
      "adb argv": fs.readFileSync(w.files.argv, "utf8"),
      "device files": JSON.stringify(w.state().files),
      "screen recording": w.state().frames.join("\n"),
      "model payloads": fs.readFileSync(w.files.modelLog, "latin1"),
      "evidence (screenshots, logs, records)": allBytes(ev),
      "Stamp upload": allBytes(w.files.uploads),
      "process output": outputs.join("\n"),
    };
    const leaks = Object.entries(places).flatMap(([where, text]) => [PASSWORD, EMAIL].filter((v) => text.includes(v)).map((v) => `${v === PASSWORD ? "password" : "email"} in ${where}`));
    assert.deepEqual(leaks, []);
    // The form driver submitted once, then both web buttons returned to the app.
    assert.equal(w.state().screen, "home");
    assert.equal(w.state().fills, 1);
    assert.equal(w.state().focusWaits, 0);
    assert.equal(w.state().confirmTaps, 2);
    assert.deepEqual(w.state().cleared, ["com.android.chrome", "com.google.android.inputmethod.latin"]);
    assert.equal(w.state().ime, "");
    assert.equal(w.state().autofill, false);
    // Capture started only after the window: the recording and every screenshot show the app, never the form.
    assert.doesNotMatch(places["screen recording"] + places["evidence (screenshots, logs, records)"], /browser login/);
    assert.doesNotMatch(fs.readFileSync(path.join(ev, "logcat-full.txt"), "utf8"), /web_login_attempt|chromium/);
    assert.ok(fs.readdirSync(w.files.uploads).some((f) => /step-\d+\.png$/.test(f)), "the post-login evidence is still uploaded");
  } finally {
    w.close();
  }
});

test("a failed form driver retains only the allowlisted UI summary and keeps the evidence gate closed", async () => {
  const w = fakeWorld({ screen: "form", refuseField: true, form: { email: EMAIL, password: PASSWORD } });
  const out = path.join(w.root, "qa-evidence");
  const summary = path.join(w.root, "summary.md");
  const result = await runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--password-login", "true", "--out", out],
    { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, GITHUB_STEP_SUMMARY: summary });
  assert.equal(result.status, 1);
  const json = JSON.parse(fs.readFileSync(summary, "utf8").split("```json\n")[1].split("\n```")[0]);
  assert.deepEqual(json, { format: "android-login-ui/v1", lastAction: "fill-email", reason: "could not fill the expected browser email field", surface: "chrome", message: "none-recognized", savePasswordPrompt: false, passwordInputVisible: true, consent: "absent", launch: "absent", setup: "absent", email: "visible", password: "visible", submit: "visible", confirm: "absent", openApp: "absent", systemReturn: "absent" });
  fs.writeFileSync(path.join(out, "raw.xml"), EMAIL + PASSWORD);
  assert.equal(gate.gate(out, RUN).ok, false);
  assert.ok(!fs.existsSync(path.join(out, "raw.xml")));
  for (const text of [fs.readFileSync(summary, "utf8"), result.stdout, result.stderr, allBytes(out)]) {
    assert.ok(!text.includes(EMAIL) && !text.includes(PASSWORD));
  }
  assert.ok(!fs.readFileSync(w.files.argv, "utf8").includes("screencap"));
  w.close();
});

test("unknown site, partial fill and missing driver markers all refuse submission and clear owned input stores", async () => {
  for (const [driverResult, expectedReason] of [
    ["site-unverified", /browser address could not be matched/],
    ["email-only", /expected browser password field/],
    ["no-markers", /expected browser email field/],
  ]) {
    const w = fakeWorld({ screen: "form", driverResult });
    const out = path.join(w.root, "ev");
    const result = await phase.androidPasswordLogin({ out, env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD }, pollMs: 5 });
    assert.equal(result.ok, false);
    assert.match(result.reason, expectedReason);
    assert.equal(w.state().confirmTaps, undefined);
    assert.deepEqual(w.state().cleared, ["com.android.chrome", "com.google.android.inputmethod.latin"]);
    assert.deepEqual(w.state().form, { email: "", password: "" });
    w.close();
  }
});

test("a matching browser hostname with a different scheme cannot receive credentials", async () => {
  const w = fakeWorld({ screen: "form" });
  try {
    const result = await phase.androidPasswordLogin({ out: path.join(w.root, "ev"), expectedOrigin: "http://app.raft.build",
      env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD }, pollMs: 5 });
    assert.equal(result.ok, false);
    assert.match(result.reason, /could not confirm the login page origin/);
    const calls = fs.readFileSync(w.files.argv, "utf8");
    assert.match(calls, /"forward","--remove"/);
    assert.doesNotMatch(calls, /"runtest"|"push"/);
    assert.deepEqual(w.state().cleared, []);
  } finally { w.close(); }
});

test("an exchange before a form submission cannot unlock capture", async () => {
  const w = fakeWorld({ earlyReceipt: true });
  const out = path.join(w.root, "qa-evidence");
  const result = await phase.androidPasswordLogin({ out, env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD }, pollMs: 2 });
  assert.equal(result.ok, false);
  assert.match(result.reason, /before this form was submitted/);
  assert.equal(w.state().fills, undefined);
  w.close();
});

// ---- Rule 1: the secret never goes into a process argument or a file --------------------------

test("rule 1: no safe path, no typing — iOS password login, a foreign device, an untypeable secret all end UNKNOWN", async () => {
  const ios = fakeWorld();
  const r = await runAsync(ios, [path.join(here, "login_phase.mjs"), "login", "--platform", "ios", "--password-login", "true", "--out", "ev"], { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD });
  assert.equal(r.status, 1);
  assert.match(phase.readRecord(path.join(ios.root, "ev"), RUN).reason, /no safe way to type a secret/);
  ios.close();
  for (const [name, patch, creds] of [
    ["no ownership marker", { owner: "" }, {}],
    ["another run's emulator", { owner: "111:1" }, {}],
    ["not an emulator", { serial: "R58M123ABC" }, {}],
    ["password with a space", {}, { QA_TEST_PASSWORD: "fake pw 7Q" }],
  ]) {
    const w = fakeWorld(patch);
    const device = patch.serial || "emulator-5554";
    const result = await phase.androidPasswordLogin({ out: path.join(w.root, "ev"), device, env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, ...creds }, pollMs: 50 });
    assert.equal(result.ok, false, name);
    assert.equal(phase.readRecord(path.join(w.root, "ev"), RUN).ok, false, name);
    assert.deepEqual(w.state().form, { email: "", password: "" }, `${name}: nothing typed`);
    assert.deepEqual(w.state().cleared, [], `${name}: nothing cleared`);
    w.close();
  }
});

test("rule 1: drivers refuse to put a secret into a process argument", async () => {
  const { guardLoginWindow } = await import("./ios_driver.mjs");
  const typed = [];
  const drv = guardLoginWindow({ probeNodes: async () => [{ label: "Home" }], text: async (v) => typed.push(v) }, "", { retryMs: 0 });
  const env = { QA_TEST_PASSWORD: PASSWORD, QA_TEST_EMAIL: EMAIL };
  const saved = { ...process.env };
  Object.assign(process.env, env);
  try {
    await assert.rejects(drv.text(PASSWORD), /no safe path/);
    await assert.rejects(drv.text(`x${EMAIL}`), /no safe path/);
    await drv.text("hello");
    assert.deepEqual(typed, ["hello"]);
  } finally {
    for (const k of Object.keys(env)) if (saved[k] === undefined) delete process.env[k]; else process.env[k] = saved[k];
  }
});

// ---- Rule 2: the login window captures nothing ------------------------------------------------

test("rule 2: a recorder that restarts after the stop, or appears during the window, ends UNKNOWN", async () => {
  for (const [name, patch, during] of [["recorder restarts after a pause", { recording: true, recorderLoop: true }, false], ["recorder starts inside the window", { recordOnType: true }, true]]) {
    const w = fakeWorld(patch);
    const r = await runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--platform", "android", "--password-login", "true", "--device", "emulator-5554", "--out", "ev"], { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD });
    assert.equal(r.status, 1, name);
    assert.match(phase.readRecord(path.join(w.root, "ev"), RUN).reason, /recorder/, name);
    if (!during) assert.equal(w.state().form.email, "", `${name}: nothing typed while recording`);
    w.close();
  }
});

test("rule 2: the workflow starts the recorder, the agent and every post-run read only after a complete login record", () => {
  const wf = fs.readFileSync(path.join(here, "../../.github/workflows/qa-acceptance.yml"), "utf8");
  const lines = wf.split("\n");
  const at = (re) => lines.findIndex((l) => re.test(l));
  const login = at(/login_phase\.mjs login --platform android/);
  assert.ok(login > 0 && login < at(/screenrecord --size/) && login < at(/acceptance_agent\.mjs/));
  assert.match(lines[login], /&& touch qa-login-ok/);
  for (const re of [/screenrecord --size/, /adb logcat -d -v threadtime/, /adb shell dumpsys activity top/, /scope_logcat\.mjs/]) assert.match(lines[at(re)], /\[ -f qa-login-ok \]/, String(re));
  assert.match(lines[at(/acceptance_agent\.mjs/)], /if \[ ! -f qa-login-ok \]; then echo 2 > qa-evidence\/agent-rc\.txt; exit 0; fi;.*env -u QA_TEST_EMAIL -u QA_TEST_PASSWORD node scripts\/qa\/acceptance_agent\.mjs/);
  assert.ok(at(/qa-run-owner/) < login, "the ownership marker is written before the login phase");
  // iOS: every mode writes its record before it runs; none gets the QA account.
  const ios = fs.readFileSync(path.join(here, "../../.github/workflows/qa-acceptance-ios.yml"), "utf8");
  const step = (name) => { const i = ios.indexOf(`      - name: ${name}\n`); return ios.slice(i, ios.indexOf("\n      - name:", i + 1)); };
  for (const name of ["Run TabPager probe (task #232/#233)", "Run deterministic repro", "Run QA session bootstrap smoke"]) {
    assert.match(step(name), /login_phase\.mjs login --platform ios --password-login false --out qa-evidence/, name);
    assert.doesNotMatch(step(name), /QA_TEST_/, name);
  }
  assert.match(step("Run acceptance agent"), /login_phase\.mjs login --platform ios --password-login "\$QA_PASSWORD_LOGIN" --out qa-evidence \|\| \{ echo 2 > qa-evidence\/agent-rc\.txt; exit 0; \}\n\s+node scripts\/qa\/acceptance_agent\.mjs/);
  assert.match(step("Collect harness log + system log + crashes + xcresult"), /login_phase\.mjs check --out qa-evidence \|\| exit 0\n/);
  for (const file of ["qa-acceptance.yml", "qa-acceptance-ios.yml"]) {
    const src = fs.readFileSync(path.join(here, "../../.github/workflows", file), "utf8");
    assert.match(src, /- name: Secure evidence gate\n\s+id: evidence-gate\n\s+if: always\(\)\n\s+run: node scripts\/qa\/secure_evidence\.mjs --dir qa-evidence/, file);
    assert.match(src, /- name: Upload evidence\n\s+if: \$\{\{ always\(\) && steps\.evidence-gate\.outcome == 'success' \}\}/, file);
  }
});

test("rule 2: an iOS screen is probed before the first frame; a login surface or an unknown probe captures nothing", async () => {
  const { guardLoginWindow } = await import("./ios_driver.mjs");
  for (const [name, probe] of [
    ["secure text field", async () => [{ label: "Email" }, { label: "", role: "SecureTextField" }]],
    ["Continue as", async () => [{ label: "Continue as fake.qa" }]],
    ["probe throws", async () => { throw new Error("snapshot failed"); }],
    ["empty tree", async () => []],
  ]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ios-probe-"));
    phase.writeRecord(dir, { mode: "none", status: "no-login", login_end: null }, RUN);
    const calls = [];
    const inner = { probeNodes: probe, waitReady: async () => calls.push("waitReady"), readGeometry: () => calls.push("FRAME"), screenshotPng: () => calls.push("FRAME"), dumpUi: () => calls.push("TREE") };
    const saved = { ...process.env };
    Object.assign(process.env, RUN);
    try {
      const drv = guardLoginWindow(inner, dir, { retryMs: 0 });
      await assert.rejects(drv.waitReady(), (e) => e.loginWindow === true, name);
      await assert.rejects(drv.screenshotPng(), (e) => e.loginWindow === true, name);
      await assert.rejects(drv.dumpUi(), (e) => e.loginWindow === true, name);
      assert.deepEqual(calls, ["waitReady"], `${name}: no frame, no tree`);
      assert.equal(phase.readRecord(dir, RUN).ok, false, name);
    } finally {
      for (const k of Object.keys(RUN)) if (saved[k] === undefined) delete process.env[k]; else process.env[k] = saved[k];
    }
  }
});

test("rule 2: an Android probe failure or a login surface stops the agent before its first screenshot", async () => {
  for (const [name, patch] of [["uiautomator returns nothing", { screen: "home", uiBroken: true }], ["login form on screen", { screen: "form" }]]) {
    const w = fakeWorld(patch);
    const ev = path.join(w.root, "qa-evidence");
    phase.writeRecord(ev, { mode: "none", status: "no-login", login_end: null }, RUN);
    const r = await runAsync(w, [path.join(here, "acceptance_agent.mjs"), "--goal", "g", "--out", ev, "--max-turns", "20", "--max-minutes", "10", "--no-progress-turns", "3", "--playbook", "none.md"],
      { PI_API_KEY: "fake", SLOCK_PI_AI_MODULE: w.files.model, FAKE_MODEL_SCRIPT: "[]" });
    assert.equal(r.status, 2, name);
    assert.equal(JSON.parse(fs.readFileSync(path.join(ev, "verdict.json"), "utf8")).verdict, "unknown", name);
    assert.ok(!fs.readdirSync(ev).some((f) => f.endsWith(".png")), `${name}: no screenshot`);
    assert.ok(!fs.readFileSync(w.files.argv, "utf8").includes("screencap"), `${name}: screencap never ran`);
    assert.equal(fs.readFileSync(w.files.modelLog, "utf8"), "", `${name}: nothing sent to the model`);
    w.close();
  }
});

// ---- Rule 3: anything uncertain means no evidence -----------------------------------------------

test("rule 3: a missing, {}, malformed, foreign or unknown record uploads nothing and says UNKNOWN", async () => {
  for (const [name, record] of [
    ["missing", null], ["{}", "{}"], ["not JSON", "{"], ["array", "[]"],
    ["another run", JSON.stringify({ contract: "qa-login-record/v1", run: "1:1", mode: "none", status: "no-login" })],
    ["unknown", JSON.stringify({ contract: "qa-login-record/v1", run: "4242:1", mode: "password", status: "unknown", reason: "x" })],
    ["complete without a timestamp", JSON.stringify({ contract: "qa-login-record/v1", run: "4242:1", mode: "password", status: "complete", login_end: null })],
  ]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gate-"));
    if (record !== null) fs.writeFileSync(path.join(dir, "login-record.json"), record);
    fs.writeFileSync(path.join(dir, "step-00.png"), "frame");
    fs.writeFileSync(path.join(dir, "verdict.json"), JSON.stringify({ verdict: "pass" }));
    fs.mkdirSync(path.join(dir, "Test.xcresult", "Data"), { recursive: true });
    fs.writeFileSync(path.join(dir, "Test.xcresult", "Data", "blob"), "opaque");
    const r = gate.gate(dir, RUN);
    assert.equal(r.ok, false, name);
    assert.deepEqual(fs.readdirSync(dir).sort(), ["agent-rc.txt", "evidence-gate.json", "verdict.json", ...(record !== null ? ["login-record.json"] : [])].sort(), name);
    assert.equal(JSON.parse(fs.readFileSync(path.join(dir, "verdict.json"), "utf8")).verdict, "unknown", name);
  }
  // The agent itself refuses to start without a valid record: nothing captured.
  const w = fakeWorld({ screen: "home" });
  for (const record of [null, "{}"]) {
    const ev = fs.mkdtempSync(path.join(w.root, "ev-"));
    if (record) fs.writeFileSync(path.join(ev, "login-record.json"), record);
    const r = await runAsync(w, [path.join(here, "acceptance_agent.mjs"), "--goal", "g", "--out", ev, "--max-turns", "20", "--max-minutes", "10", "--no-progress-turns", "3"],
      { PI_API_KEY: "fake", SLOCK_PI_AI_MODULE: w.files.model, FAKE_MODEL_SCRIPT: "[]" });
    assert.equal(r.status, 2);
    assert.equal(JSON.parse(fs.readFileSync(path.join(ev, "verdict.json"), "utf8")).verdict, "unknown");
  }
  assert.equal(fs.readFileSync(w.files.argv, "utf8"), "", "no device call at all");
  w.close();
});

test("rule 3: a refused, missing or unreadable receipt ends UNKNOWN; nothing is cleaned on a device it cannot vouch for", async () => {
  const line = (t, rest) => `11-15 10:00:0${t}.000  1  1 I SlockAppLogin: app_login_complete ${rest}`;
  assert.deepEqual(phase.parseReceipt(line(1, "result=retry reason=runtime_unavailable")), {}, "retry is still pending");
  assert.deepEqual(phase.parseReceipt([line(1, "result=app_shell reason=exchanged"), line(2, "result=ignored reason=no_pending")].join("\n")), { stamp: "11-15 10:00:01.000" });
  assert.deepEqual(phase.parseReceipt("I SlockAppLogin: app_login_complete result=app_shell reason=exchanged"), { refused: "receipt without a device timestamp" });
  assert.ok(phase.parseReceipt(line(1, "result=login_error reason=exchange_failed")).refused);
  for (const [name, patch, timeoutMs] of [["app refuses the login", { refuse: true }, 20000], ["no receipt", { noReceipt: true }, 1500], ["logcat unreadable", { logcatBroken: true }, 20000]]) {
    const w = fakeWorld(patch);
    const result = await phase.androidPasswordLogin({ out: path.join(w.root, "ev"), device: "emulator-5554", env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD }, pollMs: 50, timeoutMs });
    assert.equal(result.ok, false, name);
    assert.equal(phase.readRecord(path.join(w.root, "ev"), RUN).ok, false, name);
    assert.deepEqual(w.state().cleared, w.state().fills ? ["com.android.chrome", "com.google.android.inputmethod.latin"] : [], name);
    w.close();
  }
});

test("rule 3: log lines survive only strictly after login complete; same-millisecond and other formats are dropped", () => {
  const end = "11-15 10:00:00.500";
  const text = [
    "11-15 10:00:00.499  1  1 I chromium: typed fake.qa@example.test",
    "11-15 10:00:00.500  1  1 I SlockAppLogin: app_login_complete result=app_shell reason=exchanged",
    "11-15 10:00:00.500  1  1 I chromium: same millisecond as the receipt",
    "11-15 10:00:00.501  1  1 I Raft: home loaded",
    "--------- beginning of main",
    "1731664800.600  1  1 I Raft: epoch format line",
    "I/Raft( 1): brief format line",
    '{"timestamp":"2026-11-15 10:00:01.000000+0000","eventMessage":"ndjson"}',
    "    continuation without a timestamp",
    "01-01 00:00:00.000  1  1 I Raft: after a year rollover (sorts earlier: dropped)",
  ].join("\n");
  assert.equal(gate.linesAfter(text, end), "11-15 10:00:00.501  1  1 I Raft: home loaded");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gate-pw-"));
  phase.writeRecord(dir, { mode: "password", status: "complete", login_end: end }, RUN);
  fs.writeFileSync(path.join(dir, "logcat-full.txt"), text);
  fs.writeFileSync(path.join(dir, "logcat.txt"), text);
  fs.mkdirSync(path.join(dir, "Run.xcresult"));
  fs.writeFileSync(path.join(dir, "Run.xcresult", "Info.plist"), "opaque");
  assert.equal(gate.gate(dir, RUN).ok, true);
  for (const f of ["logcat-full.txt", "logcat.txt"]) assert.equal(fs.readFileSync(path.join(dir, f), "utf8"), "11-15 10:00:00.501  1  1 I Raft: home loaded");
  assert.equal(fs.existsSync(path.join(dir, "Run.xcresult")), false, "opaque bundle dropped");
  assert.equal(gate.gate(dir, RUN).ok, true, "idempotent");
});

test("rule 3: a mode that never signs in but shows a sign-in signal, or evidence holding the password, is UNKNOWN", () => {
  for (const [name, file, content, env] of [
    ["web login in a no-login run", "logcat-iosApp.txt", '{"eventMessage":"web_login_attempt phase=opening"}', {}],
    ["app login line in a no-login run", "system-log-raft.txt", "SlockAppLogin: app_login_complete result=app_shell", {}],
    ["password bytes in a screenshot", "step-03.png", `PNG...${PASSWORD}...`, { QA_TEST_PASSWORD: PASSWORD }],
  ]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gate-none-"));
    phase.writeRecord(dir, { mode: "none", status: "no-login", login_end: null }, RUN);
    fs.writeFileSync(path.join(dir, file), content);
    assert.equal(gate.gate(dir, { ...RUN, ...env }).ok, false, name);
    assert.equal(fs.existsSync(path.join(dir, file)), false, name);
  }
  // A clean no-login run uploads, with the email redacted from text.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gate-clean-"));
  phase.writeRecord(dir, { mode: "none", status: "no-login", login_end: null }, RUN);
  fs.writeFileSync(path.join(dir, "steps.jsonl"), `{"note":"account ${EMAIL}"}\n`);
  assert.equal(gate.gate(dir, { ...RUN, QA_TEST_EMAIL: EMAIL }).ok, true);
  assert.equal(fs.readFileSync(path.join(dir, "steps.jsonl"), "utf8"), '{"note":"account ***"}\n');
});

test("rule 3: Stamp gets no evidence and no pass for an UNKNOWN run", () => {
  const w = fakeWorld();
  const ev = path.join(w.root, "qa-evidence");
  fs.mkdirSync(ev);
  fs.writeFileSync(path.join(ev, "step-00.png"), "frame");
  fs.writeFileSync(path.join(ev, "verdict.json"), JSON.stringify({ verdict: "pass" }));
  fs.writeFileSync(path.join(w.root, "stamp-claim-rc-fake"), "fake-lease");
  const curlLog = path.join(w.root, "curl.log");
  fs.writeFileSync(path.join(w.root, "bin", "curl"), `#!/usr/bin/env bash\nprintf '%s\\n' "$*" >> "${curlLog}"\nfor a in "$@"; do case "$a" in @*) cat "\${a#@}" >> "${curlLog}"; echo >> "${curlLog}";; esac; done\nexit 0\n`, { mode: 0o755 });
  const r = spawnSync("bash", [path.join(here, "stamp_report.sh"), "report", "qa-evidence"], { cwd: w.root, encoding: "utf8",
    env: { ...w.env, STAMP_RUN_CASE_ID: "rc-fake", STAMP_URL: "https://stamp.invalid", STAMP_TOKEN: "fake", RUNNER_TEMP: w.root } });
  const calls = fs.readFileSync(curlLog, "utf8");
  assert.notEqual(r.status, null);
  assert.doesNotMatch(calls, /\/evidence\?/);
  assert.match(calls, /"verdict":"harness","reason":"UNKNOWN/);
  assert.doesNotMatch(calls, /"verdict":"pass"/);
  w.close();
});
