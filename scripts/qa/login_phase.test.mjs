// #222 login protection, end to end with fake values, a loopback server and no external account.
// A fake `adb` and its stdin-only form driver share one device model: the app's sign-in button opens
// a browser login form that shows what is typed, the screen recorder records every frame while it
// runs, logcat keeps timestamped lines, uiautomator writes its dump to a device file unless read
// from /dev/tty. A fake model records every payload; a fake curl records every upload.
// For the #216 login acceptance the same device also answers the app's identity receipt
// (AppIdentityReceiptProvider, computed as the app does and anchored to the shared vector fixture)
// and shows the server chooser; a fake `java` stands in for apksig's certificate selection.
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
    : s.screen === "system-dialog" ? n("Close app", "android", "[100,400][900,480]", "android.widget.Button", 'resource-id="android:id/aerr_close"')
      + n(s.dialogTitle || "", "android", "[100,200][900,280]", "android.widget.TextView", 'resource-id="android:id/alertTitle"')
      + (s.anr ? n("Wait", "android", "[100,500][900,580]", "android.widget.Button", 'resource-id="android:id/aerr_wait"') : "")
    : s.screen === "form" ? n(s.form.email, CHROME, "[100,300][900,400]", "android.widget.EditText", 'resource-id="login-email" password="false"') + n("*".repeat(s.form.password.length), CHROME, "[100,500][900,600]", "android.widget.EditText", 'resource-id="login-password" password="true"') + button("Sign in", s.fills ? 700 : 900)
    : s.screen === "save-prompt" ? n("Save password?", CHROME, "[100,280][900,380]", "android.widget.TextView", 'resource-id="com.android.chrome:id/title"') + n("Not now", CHROME, "[100,400][900,480]", "android.widget.Button", 'resource-id="com.android.chrome:id/negative_button"')
    : s.screen === "save-banner" ? n("Save password?", CHROME, "[100,80][700,180]", "android.widget.TextView", 'resource-id="com.android.chrome:id/message_title"')
      + n("Save", CHROME, "[700,120][900,200]", "android.widget.Button", 'resource-id="com.android.chrome:id/message_primary_button"')
      + n("Not now", CHROME, "[700,220][900,300]", "android.widget.Button", 'resource-id="com.android.chrome:id/negative_button"') + button("Confirm sign-in", 800)
    : s.screen === "approval" ? button("Allow", 100) + button("Confirm sign-in", 800)
    : s.screen === "return" ? button("Open", 150) + button("Open the app", 850)
    // The server chooser a fresh device shows after login: one row per server ("name" over "/slug").
    : s.screen === "chooser" ? n("Choose a server", APP, "[100,100][900,180]") + n("Signed in as " + s.displayName, APP, "[100,200][900,260]")
      + s.servers.map((v, i) => n(v.name, APP, "[100," + (400 + 200 * i) + "][900," + (460 + 200 * i) + "]") + n("/" + v.slug, APP, "[100," + (470 + 200 * i) + "][900," + (530 + 200 * i) + "]")).join("")
      + n("Sign out", APP, "[100,1800][900,1860]", "android.widget.Button")
    : n("Home", APP, "[0,0][1080,200]") + n("#all", APP, "[0,300][1080,400]");
  return '<?xml version="1.0"?><hierarchy>' + (s.uiBroken ? "" : nodes) + "</hierarchy>";
}
function submit(s) {
  if (s.screen !== "return" || !s.form.email || !s.form.password) return;
  s.screen = s.chooserAfterLogin ? "chooser" : "home";
  // The session the app's own login leaves (what its identity receipt reads): a fresh device has no server selected yet.
  if (s.account) s.session = { origin: s.loginOrigin || "app_login", user: s.account.user, server: s.chooserAfterLogin ? null : s.account.server };
  if (s.restartAfterLogin) { s.appPid += 1; s.startTicks = String(Number(s.startTicks) + 500); }
  if (s.noReceipt) return;
  log(s, "SlockAppLogin", "app_login_complete result=" + (s.refuse ? "login_error reason=exchange_failed" : "app_shell reason=exchanged"));
}
function choose(s, y) {
  if (y === 1830) { s.signOutTaps = (s.signOutTaps || 0) + 1; return; }
  const row = s.servers[(y - 500) / 200];
  if (row) { s.session.server = { id: row.id, slug: row.slug }; s.screen = "home"; }
}
// AppIdentityReceiptProvider's answer (buildAppIdentityReceipt): its keys in its order, the refs salted with the
// request's nonce (s.refNonce: an app that salts with another), nulls while signed out or without a selected server.
// s.refOverrides replaces the refs of a signed-in receipt (an app reporting a missing or malformed ref).
const crypto = require("crypto");
const ref = (...parts) => crypto.createHash("sha256").update(parts.join("\u0000"), "utf8").digest("hex");
function identityReceipt(s, request) {
  const session = s.session, salt = s.refNonce || request.nonce;
  const server = session && session.server && session.server.slug ? session.server : null;
  const receipt = {
    contract: "raft-app-identity-receipt/v1", attempt_id: request.attempt_id, nonce: request.nonce, request_id: s.echoRequestId || request.request_id,
    package: APP, version_name: "1.26.0", version_code: 12600, signer_sha256: s.appSigner, debuggable: false,
    pid: s.appPid, process_start_ticks: s.startTicks, signed_in: Boolean(session), session_origin: session ? session.origin : null,
    user_ref: session ? ref("raft-qa-readiness-user/v1", salt, session.user) : null,
    server_ref: server ? ref("raft-qa-readiness-server/v1", salt, server.id, server.slug) : null,
  };
  return JSON.stringify(session ? Object.assign(receipt, s.refOverrides) : receipt);
}
module.exports = { APP, CHROME, load, save, log, screen, frame, type, xml, submit, choose, identityReceipt };
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
  if (s.screen === "system-dialog") { out("  mCurrentFocus=Window{1a u0 " + s.dialogFocus + "}\n"); end(); }
  const waiting = s.screen === "home" && s.focusWaits > 0;
  if (waiting) s.focusWaits--;
  out("  mCurrentFocus=Window{1a u0 " + (waiting || s.screen === "form" ? D.CHROME : D.APP) + "/x.Main}\n"); end();
}
if (a === "exec-out uiautomator dump /dev/tty") {
  out(D.xml(s) + "UI hierchary dumped to: /dev/tty\n");
  if (s.saveDismissed && s.screen === "save-prompt") s.screen = "approval";
  if (s.screen === "save-banner") {
    s.bannerReads = (s.bannerReads || 0) + 1;
    if (s.bannerReads >= 2 && !s.stickyBanner) s.screen = "approval";
  }
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
  if (process.env.QA_TEST_PASSWORD || process.env.QA_TEST_EMAIL || process.env.QA_LOGIN_ACCEPTANCE_PASSWORD || process.env.QA_LOGIN_ACCEPTANCE_EMAIL) end(1);
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
  else if (s.screen === "form" && y === 740 && s.form.email === "fake.qa@example.test" && s.form.password === "FAKE-pw-7Qz") {
    s.formSubmits = (s.formSubmits || 0) + 1;
    s.screen = s.bannerAfterSubmit ? "save-banner" : "approval";
  }
  else if (s.screen === "save-banner") s.bannerTaps = (s.bannerTaps || 0) + 1;
  else if (s.screen === "approval" && y === 840) {
    s.confirmTaps = (s.confirmTaps || 0) + 1;
    s.screen = s.interceptConfirm && s.confirmTaps === 1 ? "save-prompt" : "return";
  } else if (s.screen === "save-prompt" && y === 440) s.saveDismissed = true;
  else if (s.screen === "return" && y === 890) D.submit(s);
  else if (s.screen === "chooser") D.choose(s, y);
  end();
}
if (a === "shell pidof build.raft.app") { out((s.appPid || 1234) + "\n"); end(); }
// The identity receipt: the request arrives on stdin only. s.provider: "enabled" (ships it enabled),
// "disabled" (an older package), absent (a build without it).
if (a === "shell -T") {
  const m = /^content call --uri content:\/\/build\.raft\.app\.identity-receipt --method receipt_v1 --extra attempt_id:s:(\S+) --extra nonce:s:(\S+) --extra request_id:s:(\S+); exit\n$/.exec(fs.readFileSync(0, "utf8"));
  s.identityReads = (s.identityReads || 0) + 1;
  out(m && s.provider === "enabled" ? "Result: Bundle[{receipt=" + D.identityReceipt(s, { attempt_id: m[1], nonce: m[2], request_id: m[3] }) + "}]\n" : "Error while accessing provider\n");
  end();
}
if (a === "shell content call --uri content://build.raft.app.identity-receipt --method probe") {
  out(s.provider === "enabled" ? "Result: Bundle[{error=unknown_method}]\n" : "java.lang.IllegalStateException: Could not find provider: build.raft.app.identity-receipt\n");
  end();
}
// What the OS says about the installed package (dumpsys, getprop, pm path, the pulled base.apk).
if (a === "shell dumpsys package build.raft.app") {
  out("Packages:\n  Package [build.raft.app] (5f3a):\n    versionCode=12600 minSdk=29 targetSdk=35\n" + (s.provider ? "  ContentProvider Authorities:\n    [build.raft.app.identity-receipt]:\n" : ""));
  end();
}
if (a === "shell getprop ro.build.version.sdk") { out("34\n"); end(); }
if (a === "shell getprop ro.build.version.codename") { out("REL\n"); end(); }
if (a === "shell pm path build.raft.app") { out("package:/data/app/~~fake==/build.raft.app-1/base.apk\n"); end(); }
if (args[0] === "pull") { fs.writeFileSync(args[2], "fake apk"); end(); }
const proc = /^shell cat \/proc\/(\d+)\/stat$/.exec(a);
if (proc) { out(proc[1] === String(s.appPid) ? proc[1] + " (build.raft.app) S " + Array(18).fill("1").join(" ") + " " + s.startTicks + " 0 0\n" : ""); end(); }
end();
`;

// apksig's certificate selection for the pulled APK (AppIdentityApkSigner.java's success line).
const FAKE_JAVA = String.raw`#!/usr/bin/env node
const fs = require("fs");
const s = JSON.parse(fs.readFileSync(process.env.FAKE_STATE, "utf8"));
fs.appendFileSync(process.env.FAKE_ARGV, JSON.stringify(["java", ...process.argv.slice(2)]) + "\n");
if (s.signerUnverified) { process.stdout.write('{"result":"APK_SIGNER_UNVERIFIED"}\n'); process.exit(1); }
process.stdout.write(JSON.stringify({ result: "SIGNER_SELECTED", android_sdk: Number(process.argv.at(-1)), signer_sha256: s.osSigner || s.appSigner }) + "\n");
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
  fs.writeFileSync(path.join(bin, "java"), FAKE_JAVA, { mode: 0o755 });
  fs.mkdirSync(path.join(root, "build-tools", "36.0.0", "lib"), { recursive: true });
  fs.writeFileSync(path.join(root, "build-tools", "36.0.0", "lib", "apksigner.jar"), "fake apksig");
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
    PATH: `${bin}:${path.dirname(process.execPath)}:/usr/bin:/bin`, HOME: root, RUNNER_TEMP: root, ANDROID_HOME: root, ...RUN,
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

test("Android error dialogs report only the framework status and a known process category", async () => {
  for (const [anr, focus, owner] of [
    [true, "Application Not Responding: build.raft.app", "app"],
    [true, "Application Not Responding: com.android.systemui", "system-ui"],
    [false, "Application Error: com.android.chrome", "chrome"],
    [true, "Application Not Responding: private.test.package", "other"],
    [false, "build.raft.app/.MainActivity", "unknown"],
  ]) {
    const dialogTitle = owner === "system-ui" ? "System UI isn't responding" : "";
    const w = fakeWorld({ screen: "system-dialog", anr, dialogFocus: focus, dialogTitle });
    try {
      const summary = path.join(w.root, "summary.md");
      const result = await phase.androidPasswordLogin({ out: path.join(w.root, "ev"),
        env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, GITHUB_STEP_SUMMARY: summary } });
      assert.equal(result.ok, false);
      const data = fs.readFileSync(summary, "utf8");
      const json = JSON.parse(data.split("```json\n")[1].split("\n```")[0]);
      assert.equal(json.surface, anr ? "system-app-anr" : "system-app-error");
      assert.equal(json.affectedProcess, owner);
      assert.equal(json.systemBlocker, dialogTitle ? "system_ui_anr" : undefined);
      assert.equal(json.lastAction, "before-form");
      for (const raw of [focus, dialogTitle, EMAIL, PASSWORD, "private.test.package"].filter(Boolean)) assert.ok(!data.includes(raw));
      assert.doesNotMatch(fs.readFileSync(w.files.argv, "utf8"), /"tap"|"runtest"/);
    } finally { w.close(); }
  }
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

test("a Chrome save-password banner over the web confirmation receives no taps", async (t) => {
  for (const [stickyBanner, timeoutMs, expectedReads] of [[false, 15000, 2], [true, 12000, 3], [true, 180000, 10]]) {
    const w = fakeWorld({ screen: "form", bannerAfterSubmit: true, stickyBanner });
    const out = path.join(w.root, "ev");
    const summary = path.join(w.root, "summary.md");
    const start = Date.now();
    // Each fresh banner observation advances five seconds on the host. Child adb and its device clock are independent.
    const clock = stickyBanner && t.mock.method(Date, "now", () => start + (w.state().bannerReads || 0) * 5000);
    try {
      const result = await phase.androidPasswordLogin({ out,
        env: { ...w.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, GITHUB_STEP_SUMMARY: summary },
        pollMs: 10, timeoutMs });
      const state = w.state();
      assert.equal(state.formSubmits, 1);
      assert.equal(state.bannerReads, expectedReads);
      assert.equal(state.bannerTaps ?? 0, 0);
      assert.equal(result.ok, !stickyBanner);
      if (stickyBanner) {
        assert.equal(state.confirmTaps ?? 0, 0);
        assert.match(result.reason, /save-password prompt/);
        assert.match(fs.readFileSync(summary, "utf8"), /wait-save-password/);
        assert.equal(gate.gate(out, RUN).ok, false);
      } else {
        assert.equal(state.confirmTaps, 1);
        assert.equal(state.screen, "home");
      }
    } finally {
      if (clock) clock.mock.restore();
      w.close();
    }
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
  assert.match(lines[at(/acceptance_agent\.mjs/)], /if \[ ! -f qa-login-ok \]; then echo 2 > qa-evidence\/agent-rc\.txt; exit 0; fi;.*env -u QA_TEST_EMAIL -u QA_TEST_PASSWORD -u QA_LOGIN_ACCEPTANCE_EMAIL -u QA_LOGIN_ACCEPTANCE_PASSWORD -u ACTIONS_ID_TOKEN_REQUEST_URL -u ACTIONS_ID_TOKEN_REQUEST_TOKEN node scripts\/qa\/acceptance_agent\.mjs/);
  // Release settings lane: release_settings_login.sh runs the case inside the matched process; every
  // later capture/agent/post-run line of the workflow is skipped in that lane.
  for (const re of [/^\s+adb logcat -c$/, /screenrecord --size/, /acceptance_agent\.mjs/, /adb logcat -d -v threadtime/, /scope_logcat\.mjs/, /adb shell dumpsys activity top/, /dumpsys gfxinfo .* reset/, /perf-started\.txt$/, /gfxinfo\.txt 2>&1/]) {
    assert.match(lines[lines.findIndex((l) => re.test(l.replace(/^\s+\[ -f qa-release-lane \] && exit 0; /, "            ")))], /^\s+\[ -f qa-release-lane \] && exit 0; /, String(re));
  }
  assert.match(lines[login], /^\s+if \[ "\$\{QA_RELEASE_PRELOGIN:-false\}" = "true" \]; then bash scripts\/qa\/release_settings_login\.sh \|\| true; else rm -f qa-login-ok;/);
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
    if (file === "qa-acceptance.yml") {
      assert.match(src, /- name: Secure evidence gate\n\s+id: evidence-gate\n\s+if: always\(\)\n\s+env:\n\s+QA_LOGIN_ACCEPTANCE_EMAIL: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case == 'login-email-password' && secrets\.QA_LOGIN_ACCEPTANCE_EMAIL \|\| '' \}\}\n\s+QA_LOGIN_ACCEPTANCE_PASSWORD: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case == 'login-email-password' && secrets\.QA_LOGIN_ACCEPTANCE_PASSWORD \|\| '' \}\}\n\s+run: node scripts\/qa\/secure_evidence\.mjs --dir qa-evidence/, file);
    } else {
      assert.match(src, /- name: Secure evidence gate\n\s+id: evidence-gate\n\s+if: always\(\)\n\s+run: node scripts\/qa\/secure_evidence\.mjs --dir qa-evidence/, file);
    }
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
    ["acceptance password bytes in a screenshot", "step-04.png", `PNG...${PASSWORD}...`, { QA_LOGIN_ACCEPTANCE_PASSWORD: PASSWORD }],
  ]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gate-none-"));
    phase.writeRecord(dir, { mode: "none", status: "no-login", login_end: null }, RUN);
    fs.writeFileSync(path.join(dir, file), content);
    assert.equal(gate.gate(dir, { ...RUN, ...env }).ok, false, name);
    assert.equal(fs.existsSync(path.join(dir, file)), false, name);
  }
  // A clean no-login run uploads, with the email redacted from text (legacy and dedicated acceptance secrets).
  for (const env of [{ QA_TEST_EMAIL: EMAIL }, { QA_LOGIN_ACCEPTANCE_EMAIL: EMAIL }]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gate-clean-"));
    phase.writeRecord(dir, { mode: "none", status: "no-login", login_end: null }, RUN);
    fs.writeFileSync(path.join(dir, "steps.jsonl"), `{"note":"account ${EMAIL}"}\n`);
    assert.equal(gate.gate(dir, { ...RUN, ...env }).ok, true);
    assert.equal(fs.readFileSync(path.join(dir, "steps.jsonl"), "utf8"), '{"note":"account ***"}\n');
  }
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

// ---- #216 login acceptance: signed out at the start, the expected account and server after, one process ----
// The fake device answers the app's identity receipt from the shared vector: Testbed's sample receipt (package,
// version, signer, pid, start ticks) for an app that this form signs in as the fixture subject on the fixture server.
const vector = JSON.parse(fs.readFileSync(path.join(here, "fixtures", "app-identity-receipt-vector.json"), "utf8"));
const { fixture, sample_receipt: sample } = vector;
const control = (name) => vector.controls.find((c) => c.name === name);
const serverOf = (c) => ({ id: c.server_id, slug: c.server_slug });
const FIXTURE_SERVER = serverOf(fixture);
const RUN_CASE_ID = "5b0f6a52-8d1e-4c7b-9a3f-2e6d1c0b9a87";
const BINDING = { case_id: "login-email-password", run_case_id: RUN_CASE_ID, attempt: 1 };
const V1_KEYS = ["contract", "run", "mode", "status", "login_end", "reason"];
// What a read carries that identifies the account, the server or Testbed's request: never in argv, output or the record.
const PRIVATE = [fixture.nonce, fixture.user_ref, fixture.server_ref, fixture.subject, fixture.server_id, sample.attempt_id];

function identityWorld(state = {}) {
  return fakeWorld({ provider: "enabled", appSigner: sample.signer_sha256, appPid: sample.pid, startTicks: sample.process_start_ticks,
    session: null, account: { user: fixture.subject, server: FIXTURE_SERVER }, ...state });
}

// Testbed's run-bound input selecting the case; `patch` replaces top-level fields.
function writeTestbedInput(dir, patch = {}, mode = 0o600) {
  const file = path.join(dir, "testbed-login-input.json");
  fs.writeFileSync(file, JSON.stringify({ case_id: "login-email-password", run_case_id: RUN_CASE_ID, attempt: 1, attempt_id: sample.attempt_id,
    nonce: fixture.nonce, expected_target: { user_id: fixture.subject, server_id: fixture.server_id, server_slug: fixture.server_slug }, ...patch }), { mode });
  fs.chmodSync(file, mode);
  return file;
}

const loginWith = (w, acceptance, options = {}) => phase.androidPasswordLogin({ out: path.join(w.root, "ev"),
  env: {
    ...w.env,
    ...(acceptance
      ? { QA_LOGIN_ACCEPTANCE_EMAIL: EMAIL, QA_LOGIN_ACCEPTANCE_PASSWORD: PASSWORD, QA_TEST_EMAIL: "wrong.legacy@example.test", QA_TEST_PASSWORD: "wrong-legacy-pw" }
      : { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, QA_LOGIN_ACCEPTANCE_EMAIL: "wrong.accept@example.test", QA_LOGIN_ACCEPTANCE_PASSWORD: "wrong-accept-pw" }),
    ...(options.env ?? {}),
  },
  pollMs: 5, chooserTimeoutMs: 100, acceptance, ...options, ...(options.env ? { env: { ...w.env, ...options.env } } : {}) });
const recordOf = (w) => JSON.parse(fs.readFileSync(path.join(w.root, "ev", "login-record.json"), "utf8"));

test("#216 the Testbed input is read once and deleted; only case login-email-password selects the acceptance, with the vector's refs", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "testbed-input-"));
  try {
    const selecting = writeTestbedInput(dir);
    const input = phase.readTestbedInput(selecting, {});
    assert.equal(fs.existsSync(selecting), false, "consumed");
    // The expected refs come from the same nonce the reads send, exactly as the vector computes them.
    assert.deepEqual(input, { binding: BINDING, problem: null, attemptId: sample.attempt_id, nonce: fixture.nonce, serverSlug: fixture.server_slug,
      expected: { userRef: fixture.user_ref, serverRef: fixture.server_ref } });
    // Anything that does not explicitly select the case selects nothing, and is consumed too.
    const write = (name, text) => { const f = path.join(dir, name); fs.writeFileSync(f, text, { mode: 0o600 }); return f; };
    const fields = JSON.parse(fs.readFileSync(writeTestbedInput(dir), "utf8"));
    const link = path.join(dir, "link.json");
    fs.symlinkSync(write("target.json", JSON.stringify(fields)), link);
    for (const file of [write("other-case.json", JSON.stringify({ ...fields, case_id: "send-message-in-channel" })),
      write("no-case.json", JSON.stringify({ ...fields, case_id: undefined })), write("not-json.json", "{"), write("array.json", "[]"), link, path.join(dir, "missing.json")]) {
      assert.equal(phase.readTestbedInput(file, {}), null, file);
      assert.ok(!fs.readdirSync(dir).includes(path.basename(file)), `${file} consumed`);
    }
    assert.ok(fs.existsSync(path.join(dir, "target.json")), "a link is removed, never followed");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("#216 without an input that selects the case nothing is read and the record is byte for byte what it was", async () => {
  const w = fakeWorld();
  try {
    const result = await loginWith(w, null);
    assert.deepEqual(Object.keys(result), ["ok", "stamp"]);
    // Byte-for-byte compatibility is the point here: exactly the six qa-login-record/v1 fields writeRecord wrote before #216.
    assert.equal(fs.readFileSync(path.join(w.root, "ev", "login-record.json"), "utf8"), `${JSON.stringify({ contract: "qa-login-record/v1", run: "4242:1",
      mode: "password", status: "complete", login_end: result.stamp, reason: "app_login_complete result=app_shell reason=exchanged" }, null, 2)}\n`);
    const calls = fs.readFileSync(w.files.argv, "utf8");
    for (const read of ["content call", "dumpsys package", "pm path", '"pull"', '"java"', '"shell","-T"]']) assert.ok(!calls.includes(read), read);
  } finally { w.close(); }
});

test("#216 pass: signed out on the sign-in page, the form submitted, then the expected account on the expected server in the same app process", async () => {
  // A fresh device: after login the app shows the server chooser; the phase picks the expected server's row (not the first).
  const w = identityWorld({ chooserAfterLogin: true, displayName: "Fixture Person", servers: [{ name: "Other team", id: "server-9", slug: "other" }, { name: "Fixture team", ...FIXTURE_SERVER }] });
  try {
    const input = writeTestbedInput(w.root);
    const ev = path.join(w.root, "qa-evidence");
    // As qa-acceptance.yml runs it for the claimed case: the required switch, the job's run case, and the dedicated acceptance secrets.
    const r = await runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--platform", "android", "--password-login", "true", "--device", "emulator-5554",
      "--out", ev, "--testbed-input", input, "--require-acceptance", "login-email-password"],
      { QA_LOGIN_ACCEPTANCE_EMAIL: EMAIL, QA_LOGIN_ACCEPTANCE_PASSWORD: PASSWORD, QA_TEST_EMAIL: "wrong.legacy@example.test", QA_TEST_PASSWORD: "wrong-legacy-pw", STAMP_RUN_CASE_ID: RUN_CASE_ID });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /^login phase: OK$/m);
    assert.match(r.stdout, /^login acceptance: pass \(signed_in_as_expected\)$/m);
    assert.equal(fs.existsSync(input), false, "the input is consumed");
    const text = fs.readFileSync(path.join(ev, "login-record.json"), "utf8");
    const record = JSON.parse(text);
    assert.deepEqual(Object.keys(record), [...V1_KEYS, "acceptance"]);
    assert.equal(record.status, "complete");
    assert.deepEqual(record.acceptance, { ...BINDING, verdict: "pass", reason: "signed_in_as_expected" });
    assert.equal(phase.readRecord(ev, RUN).ok, true, "a pass opens capture as before");
    const s = w.state();
    assert.equal(s.formSubmits, 1);
    assert.deepEqual(s.session.server, FIXTURE_SERVER, "the expected server's row was chosen");
    assert.equal(s.signOutTaps, undefined);
    assert.equal(s.identityReads, 3, "the sign-in page, after login, after the choice");
    const calls = fs.readFileSync(w.files.argv, "utf8");
    assert.doesNotMatch(calls, /"(pm enable|pm disable)/, "the provider is used as the package ships it");
    for (const [where, content] of Object.entries({ argv: calls, stdout: r.stdout, stderr: r.stderr, record: text })) {
      for (const value of [...PRIVATE, "Fixture Person", "Other team", EMAIL, PASSWORD]) assert.ok(!content.includes(value), `${value} in ${where}`);
    }
    // The fake device is the vector: asked the vector's request, the signed-in app answers Testbed's sample receipt.
    const asked = spawnSync("adb", ["shell", "-T"], { env: w.env, encoding: "utf8",
      input: `content call --uri content://build.raft.app.identity-receipt --method receipt_v1 --extra attempt_id:s:${sample.attempt_id} --extra nonce:s:${sample.nonce} --extra request_id:s:${sample.request_id}; exit\n` });
    assert.deepEqual(JSON.parse(/receipt=(\{.*\})\}\]/.exec(asked.stdout)[1]), sample);
  } finally { w.close(); }
});

test("#216 fail: valid reads in one process, but the app is signed in as another account or on another server (vector controls)", async () => {
  const nonce = control("different nonce (user)").nonce;
  for (const [name, state, code] of [
    ["different subject", { account: { user: control("different subject").subject, server: FIXTURE_SERVER } }, "user_mismatch"],
    ["different server ID", { account: { user: fixture.subject, server: serverOf(control("different server ID")) } }, "server_mismatch"],
    ["different slug", { account: { user: fixture.subject, server: serverOf(control("different slug")) } }, "server_mismatch"],
    ["missing slug", { account: { user: fixture.subject, server: serverOf(control("missing slug")) } }, "server_not_selected"],
    ["refs salted with a different nonce", { refNonce: nonce }, "user_and_server_mismatch"],
    // The chooser row of the expected server was tapped, and the app still reports no server ref.
    ["empty server ref after the selection step", { chooserAfterLogin: true, displayName: "Fixture Person", servers: [{ name: "Fixture team", ...FIXTURE_SERVER }],
      refOverrides: { server_ref: null } }, "server_not_selected"],
  ]) {
    const w = identityWorld(state);
    try {
      const result = await loginWith(w, phase.readTestbedInput(writeTestbedInput(w.root), w.env));
      if (state.chooserAfterLogin) assert.deepEqual([w.state().session.server, w.state().signOutTaps], [FIXTURE_SERVER, undefined], `${name}: the expected row was chosen`);
      assert.deepEqual(result.acceptance, { verdict: "fail", reason: code }, name);
      assert.equal(result.ok, false, name);
      const record = recordOf(w);
      assert.deepEqual(Object.keys(record), [...V1_KEYS, "acceptance"], name);
      assert.deepEqual([record.status, record.login_end, record.reason], ["unknown", null, `login acceptance fail: ${code}`], `${name}: nothing is captured for it`);
      assert.deepEqual(record.acceptance, { ...BINDING, verdict: "fail", reason: code }, name);
      assert.equal(phase.readRecord(path.join(w.root, "ev"), RUN).ok, false, name);
      assert.equal(w.state().formSubmits, 1, `${name}: the form was submitted`);
      assert.ok(phase.LOGIN_ACCEPTANCE_REASONS.fail.includes(code));
    } finally { w.close(); }
  }
});

test("#216 unknown: anything the reads cannot prove, with its reason; nothing is typed before the app said signed out", async () => {
  for (const [name, state, input, options, code, typed] of [
    ["an input without the nonce", {}, { patch: { nonce: undefined } }, {}, "input_field_missing", false],
    ["an input without the server slug", {}, { patch: { expected_target: { user_id: fixture.subject, server_id: fixture.server_id } } }, {}, "input_field_missing", false],
    // Only values of the agreed format travel through the binding: the record says attempt null.
    ["an input whose attempt is not a number", {}, { patch: { attempt: "1" }, binding: { attempt: null } }, {}, "input_field_invalid", false],
    ["an input others can read", {}, { mode: 0o644 }, {}, "input_unprotected", false],
    ["an input of another run case", {}, { env: { STAMP_RUN_CASE_ID: "00000000-0000-4000-8000-000000000000" } }, {}, "input_run_case_mismatch", false],
    ["a package that ships the provider disabled", { provider: "disabled" }, {}, {}, "provider_unavailable", false],
    ["an APK certificate apksig cannot select", { signerUnverified: true }, {}, {}, "signer_unverified", false],
    ["an installed certificate other than the app's", { osSigner: "0".repeat(64) }, {}, {}, "signer_mismatch", false],
    ["a receipt for another request", { echoRequestId: "0".repeat(32) }, {}, {}, "receipt_echo_mismatch", false],
    ["a login form before the app's sign-in page", { screen: "form" }, {}, {}, "start_read_missing", false],
    ["an app already signed in at the start", { session: { origin: "app_login", user: fixture.subject, server: FIXTURE_SERVER } }, {}, {}, "start_not_signed_out", false],
    ["another app process after login", { restartAfterLogin: true }, {}, {}, "process_changed", true],
    ["a session that is not the app's own login", { loginOrigin: "qa_bootstrap" }, {}, {}, "session_origin_not_app_login", true],
    // The refs are checked in the library's format before any comparison: never a mismatch from a bad ref.
    ["a signed-in receipt without a user ref", { refOverrides: { user_ref: null } }, {}, {}, "user_ref_invalid", true],
    ["a malformed user ref", { refOverrides: { user_ref: fixture.user_ref.toUpperCase() } }, {}, {}, "user_ref_invalid", true],
    ["a malformed server ref", { refOverrides: { server_ref: fixture.server_ref.slice(1) } }, {}, {}, "server_ref_invalid", true],
    ["no app_login_complete receipt", { noReceipt: true }, {}, { timeoutMs: 4000 }, "no_login_receipt", true],
  ]) {
    const w = identityWorld(state);
    try {
      const acceptance = phase.readTestbedInput(writeTestbedInput(w.root, input.patch, input.mode), { ...w.env, ...input.env });
      const result = await loginWith(w, acceptance, options);
      assert.deepEqual(result.acceptance, { verdict: "unknown", reason: code }, name);
      const record = recordOf(w);
      assert.equal(record.status, "unknown", name);
      assert.deepEqual(record.acceptance, { ...BINDING, ...input.binding, verdict: "unknown", reason: code }, name);
      assert.equal(phase.readRecord(path.join(w.root, "ev"), RUN).ok, false, name);
      const calls = fs.readFileSync(w.files.argv, "utf8");
      if (code.startsWith("input_")) assert.equal(calls, "", `${name}: no device call`);
      assert.equal(calls.includes("RaftQaLoginForm#testFill"), typed, `${name}: typed`);
      assert.doesNotMatch(calls, /"(pm enable|pm disable)/, name);
      for (const value of PRIVATE) assert.ok(!calls.includes(value) && !JSON.stringify(record).includes(value), `${name}: ${value}`);
      assert.ok(phase.LOGIN_ACCEPTANCE_REASONS.unknown.includes(code), code);
    } finally { w.close(); }
  }
});

test("#216 a later invalidation keeps the run binding and makes the verdict unknown; a complete record opens capture only with a pass", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "acceptance-record-"));
  const stamp = "11-21 10:00:00.000";
  const complete = (fields) => phase.writeRecord(dir, { mode: "password", status: "complete", login_end: stamp, reason: "x", ...fields }, RUN);
  const read = () => JSON.parse(fs.readFileSync(path.join(dir, "login-record.json"), "utf8"));
  try {
    complete({ acceptance: { ...BINDING, verdict: "pass", reason: "signed_in_as_expected" } });
    assert.equal(phase.readRecord(dir, RUN).ok, true);
    phase.markUnknown(dir, "login-like surface on screen", RUN); // what the agent does on a later login surface
    assert.deepEqual(read().acceptance, { ...BINDING, verdict: "unknown", reason: "record_invalidated" });
    assert.equal(phase.readRecord(dir, RUN).ok, false);
    for (const acceptance of [{ ...BINDING, verdict: "fail", reason: "user_mismatch" }, { ...BINDING, verdict: "unknown" }, { verdict: "pass" }, "pass", null]) {
      complete({ acceptance });
      assert.equal(phase.readRecord(dir, RUN).ok, false, JSON.stringify(acceptance));
    }
    // Only values of the agreed format travel through the binding.
    complete({ acceptance: { ...BINDING, run_case_id: EMAIL, attempt: "1", verdict: "pass", reason: "signed_in_as_expected", note: EMAIL } });
    phase.markUnknown(dir, "y", RUN);
    assert.deepEqual(read().acceptance, { case_id: "login-email-password", run_case_id: null, attempt: null, verdict: "unknown", reason: "record_invalidated" });
    // A record without a login acceptance is invalidated exactly as before.
    complete({});
    phase.markUnknown(dir, "y", RUN);
    assert.deepEqual(Object.keys(read()), V1_KEYS);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("#216 readRecordAcceptance: any record's acceptance, completed only where readRecord accepts a completed login; isLoginAcceptancePair: listed pairs only", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "acceptance-read-"));
  const stamp = "11-21 10:00:00.000";
  const pass = { ...BINDING, verdict: "pass", reason: "signed_in_as_expected" };
  const write = (fields) => phase.writeRecord(dir, { mode: "password", status: "complete", login_end: stamp, reason: "x", ...fields }, RUN);
  try {
    assert.equal(phase.readRecordAcceptance(dir, RUN), null, "no record");
    write({ acceptance: pass });
    assert.deepEqual(phase.readRecordAcceptance(dir, RUN), { run: "4242:1", acceptance: pass, completed: true });
    assert.equal(phase.readRecordAcceptance(dir, { ...RUN, GITHUB_RUN_ATTEMPT: "2" }).completed, false, "readRecord refuses another run's record");
    phase.markUnknown(dir, "login-like surface on screen", RUN);
    assert.deepEqual(phase.readRecordAcceptance(dir, RUN), { run: "4242:1", acceptance: { ...BINDING, verdict: "unknown", reason: "record_invalidated" }, completed: false });
    // readRecord accepts a no-login record whatever it carries: that is no completed login.
    write({ mode: "none", status: "no-login", login_end: null, acceptance: pass });
    assert.deepEqual([phase.readRecord(dir, RUN).ok, phase.readRecordAcceptance(dir, RUN).completed], [true, false]);
    for (const acceptance of [undefined, null, "pass", [pass]]) {
      write({ acceptance });
      assert.equal(phase.readRecordAcceptance(dir, RUN), null, JSON.stringify(acceptance));
    }
    fs.writeFileSync(path.join(dir, "login-record.json"), JSON.stringify({ contract: "qa-login-record/v0", run: "4242:1", acceptance: pass }));
    assert.equal(phase.readRecordAcceptance(dir, RUN), null, "another contract");
    // The one allowlist of the pairs a record can hold: every listed pair, nothing else.
    for (const [verdict, reasons] of Object.entries(phase.LOGIN_ACCEPTANCE_REASONS)) {
      for (const reason of reasons) assert.equal(phase.isLoginAcceptancePair(verdict, reason), true, `${verdict}/${reason}`);
    }
    for (const pair of [["pass", "user_mismatch"], ["fail", "signed_in_as_expected"], ["unknown", "record_missing"], [["pass"], "signed_in_as_expected"],
      ["pass", ["signed_in_as_expected"]], ["constructor", "constructor"], ["__proto__", "user_mismatch"], [undefined, undefined]]) {
      assert.equal(phase.isLoginAcceptancePair(...pair), false, JSON.stringify(pair));
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ---- #216 --require-acceptance: the claimed case login-email-password makes the acceptance mandatory ----

test("#216 --require-acceptance: no usable input ends unknown before any device call; without it the same input selects nothing", async () => {
  const env = { STAMP_RUN_CASE_ID: RUN_CASE_ID };
  const at = (dir) => path.join(dir, "input.json");
  const write = (dir, text) => { fs.writeFileSync(at(dir), text, { mode: 0o600 }); return at(dir); };
  const selecting = (dir, patch, mode) => fs.renameSync(writeTestbedInput(dir, patch, mode), at(dir)) ?? at(dir);
  for (const [name, make, code] of [
    ["no input file", (dir) => at(dir), "input_missing"],
    ["no --testbed-input at all", () => "", "input_missing"],
    ["a directory", (dir) => { fs.mkdirSync(at(dir)); return at(dir); }, "input_unreadable"],
    ["a link to a valid input", (dir) => { fs.symlinkSync(writeTestbedInput(fs.mkdtempSync(path.join(dir, "target-"))), at(dir)); return at(dir); }, "input_unreadable"],
    ["invalid JSON", (dir) => write(dir, "{"), "input_invalid"],
    ["a JSON array", (dir) => write(dir, "[]"), "input_invalid"],
    ["another case", (dir) => selecting(dir, { case_id: "send-message-in-channel" }), "input_case_mismatch"],
    ["no case_id", (dir) => selecting(dir, { case_id: undefined }), "input_case_mismatch"],
    ["an input others can read", (dir) => selecting(dir, {}, 0o640), "input_unprotected"],
  ]) {
    // Without the switch: an input that does not select the case is ignored, so the ordinary login runs.
    const plain = fs.mkdtempSync(path.join(os.tmpdir(), "testbed-plain-"));
    try {
      const result = phase.readTestbedInput(make(plain), env);
      if (code === "input_unprotected") assert.equal(result.problem, code, name);
      else assert.equal(result, null, `${name}: ignored without the switch`);
    } finally { fs.rmSync(plain, { recursive: true, force: true }); }
    const w = identityWorld();
    try {
      const result = await loginWith(w, phase.readTestbedInput(make(w.root), env, { required: true }));
      assert.deepEqual(result.acceptance, { verdict: "unknown", reason: code }, name);
      const record = recordOf(w);
      assert.deepEqual([record.status, record.login_end], ["unknown", null], name);
      // Bound to this job's run case; the attempt is the input's only when the input itself was readable.
      assert.deepEqual(record.acceptance, { ...BINDING, attempt: code === "input_unprotected" ? 1 : null, verdict: "unknown", reason: code }, name);
      assert.equal(phase.readRecord(path.join(w.root, "ev"), RUN).ok, false, name);
      assert.equal(fs.readFileSync(w.files.argv, "utf8"), "", `${name}: no device call, nothing typed`);
      assert.ok(phase.LOGIN_ACCEPTANCE_REASONS.unknown.includes(code), code);
    } finally { w.close(); }
  }
});

test("#216 --credential-file reads a 0600 staged file, removes it immediately (including on errors/early exits), and never falls back to QA_TEST_*", async () => {
  const w = identityWorld({ chooserAfterLogin: true, displayName: "Fixture Person", servers: [{ name: "Fixture team", ...FIXTURE_SERVER }] });
  try {
    const credFile = path.join(w.root, "account");
    fs.writeFileSync(credFile, `${EMAIL}\n${PASSWORD}\n`, { mode: 0o600 });
    fs.chmodSync(credFile, 0o600);
    const input = writeTestbedInput(w.root);
    const ev = path.join(w.root, "ev-staged");
    const r = await runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--platform", "android", "--password-login", "true", "--device", "emulator-5554",
      "--out", ev, "--testbed-input", input, "--require-acceptance", "login-email-password", "--credential-file", credFile, "--consume-credential-file"],
      { QA_TEST_EMAIL: "wrong.legacy@example.test", QA_TEST_PASSWORD: "wrong-legacy-pw", STAMP_RUN_CASE_ID: RUN_CASE_ID });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.equal(fs.existsSync(credFile), false, "staged credential file removed on read");
    assert.match(r.stdout, /^login acceptance: pass \(signed_in_as_expected\)$/m);
    for (const value of [EMAIL, PASSWORD, ...PRIVATE]) assert.ok(!r.stdout.includes(value) && !r.stderr.includes(value), value);

    // Unprotected, symlink, malformed or missing staged files all fail closed before any device call and delete the file.
    for (const [name, setup, problem] of [
      ["missing file", (p) => p, "credential_file_missing"],
      ["group-readable file", (p) => { fs.writeFileSync(p, `${EMAIL}\n${PASSWORD}\n`, { mode: 0o640 }); fs.chmodSync(p, 0o640); return p; }, "credential_file_unprotected"],
      ["symlink", (p) => { const t = path.join(w.root, "real-target"); fs.writeFileSync(t, `${EMAIL}\n${PASSWORD}\n`, { mode: 0o600 }); fs.symlinkSync(t, p); return p; }, "credential_file_unreadable"],
      ["malformed file", (p) => { fs.writeFileSync(p, "not-an-email\n", { mode: 0o600 }); fs.chmodSync(p, 0o600); return p; }, "credential_file_invalid"],
    ]) {
      const p = path.join(w.root, `bad-${problem}`);
      setup(p);
      const read = phase.readCredentialFile(p, { consume: true });
      assert.equal(read.problem, problem, name);
      assert.equal(fs.existsSync(p), false, `${name}: file removed`);
      const w2 = identityWorld();
      try {
        const acceptance = phase.readTestbedInput(writeTestbedInput(w2.root), { ...w2.env, STAMP_RUN_CASE_ID: RUN_CASE_ID }, { required: true });
        const res = await phase.androidPasswordLogin({ out: path.join(w2.root, "ev"), env: { ...w2.env, QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD }, pollMs: 5, chooserTimeoutMs: 100, acceptance, credentials: read });
        assert.equal(res.ok, false, name);
        assert.match(res.reason, new RegExp(problem), name);
        assert.equal(fs.readFileSync(w2.files.argv, "utf8"), "", `${name}: no device call`);
      } finally { w2.close(); }
    }

    // Even when the mode exits early (--password-login false), --consume-credential-file still deletes the file.
    const earlyCred = path.join(w.root, "early-account");
    fs.writeFileSync(earlyCred, `${EMAIL}\n${PASSWORD}\n`, { mode: 0o600 });
    fs.chmodSync(earlyCred, 0o600);
    const early = await runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--platform", "android", "--password-login", "false",
      "--out", path.join(w.root, "ev-early"), "--testbed-input", writeTestbedInput(w.root), "--require-acceptance", "login-email-password",
      "--credential-file", earlyCred, "--consume-credential-file"], { STAMP_RUN_CASE_ID: RUN_CASE_ID });
    assert.equal(early.status, 1);
    assert.equal(fs.existsSync(earlyCred), false, "staged credential file removed even on early unsupported-mode exit");
  } finally { w.close(); }
});

test("#216 missing dedicated acceptance secrets fail closed before any device call without falling back to QA_TEST_*", async () => {
  for (const [name, creds] of [
    ["both missing with legacy set", { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD }],
    ["email missing with legacy set", { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, QA_LOGIN_ACCEPTANCE_PASSWORD: PASSWORD }],
    ["password missing with legacy set", { QA_TEST_EMAIL: EMAIL, QA_TEST_PASSWORD: PASSWORD, QA_LOGIN_ACCEPTANCE_EMAIL: EMAIL }],
  ]) {
    const w = identityWorld();
    try {
      const acceptance = phase.readTestbedInput(writeTestbedInput(w.root), { ...w.env, STAMP_RUN_CASE_ID: RUN_CASE_ID }, { required: true });
      const result = await phase.androidPasswordLogin({ out: path.join(w.root, "ev"), env: { ...w.env, ...creds }, pollMs: 5, chooserTimeoutMs: 100, acceptance });
      assert.equal(result.ok, false, name);
      assert.match(result.reason, /QA_LOGIN_ACCEPTANCE_EMAIL \/ QA_LOGIN_ACCEPTANCE_PASSWORD missing/, name);
      assert.deepEqual(result.acceptance, { verdict: "unknown", reason: "login_not_completed" }, name);
      const record = recordOf(w);
      assert.equal(record.status, "unknown", name);
      assert.deepEqual(record.acceptance, { ...BINDING, verdict: "unknown", reason: "login_not_completed" }, name);
      assert.equal(fs.readFileSync(w.files.argv, "utf8"), "", `${name}: no device call`);
    } finally { w.close(); }
  }
});

test("#216 --require-acceptance on the command line: no input or a mode that cannot run it is unknown; another case is a usage error", async () => {
  const w = identityWorld();
  try {
    const login = (args, extra = {}) => runAsync(w, [path.join(here, "login_phase.mjs"), "login", "--out", path.join(w.root, "ev"), ...args],
      { QA_LOGIN_ACCEPTANCE_EMAIL: EMAIL, QA_LOGIN_ACCEPTANCE_PASSWORD: PASSWORD, STAMP_RUN_CASE_ID: RUN_CASE_ID, ...extra });
    const missing = await login(["--platform", "android", "--password-login", "true", "--require-acceptance", "login-email-password"]);
    assert.equal(missing.status, 1);
    assert.match(missing.stdout, /^login acceptance: unknown \(input_missing\)$/m);
    assert.deepEqual(recordOf(w).acceptance, { ...BINDING, attempt: null, verdict: "unknown", reason: "input_missing" });
    assert.equal(fs.readFileSync(w.files.argv, "utf8"), "", "no device call");
    for (const [mode, args] of [["none", ["--platform", "android", "--password-login", "false"]], ["password", ["--platform", "ios", "--password-login", "true"]]]) {
      const input = writeTestbedInput(w.root);
      const r = await login([...args, "--testbed-input", input, "--require-acceptance", "login-email-password"]);
      assert.equal(r.status, 1, mode);
      assert.match(r.stdout, /^login acceptance: unknown \(login_mode_unsupported\)$/m, mode);
      assert.equal(fs.existsSync(input), false, `${mode}: the input is consumed`);
      const record = recordOf(w);
      assert.deepEqual([record.mode, record.status], [mode, "unknown"], mode);
      assert.deepEqual(record.acceptance, { ...BINDING, verdict: "unknown", reason: "login_mode_unsupported" }, mode);
    }
    fs.rmSync(path.join(w.root, "ev"), { recursive: true, force: true });
    const other = await login(["--platform", "android", "--password-login", "true", "--require-acceptance", "send-message-in-channel"]);
    assert.equal(other.status, 2);
    assert.equal(fs.existsSync(path.join(w.root, "ev", "login-record.json")), false);
  } finally { w.close(); }
});

test("#216 qa-acceptance requires the login acceptance only for the claimed case login-email-password", () => {
  const source = fs.readFileSync(path.join(here, "../../.github/workflows/qa-acceptance.yml"), "utf8");
  const step = (name) => { const i = source.indexOf(`      - name: ${name}\n`); assert.ok(i > 0, name); return source.slice(i, source.indexOf("\n      - name:", i + 1)); };
  const forCase = "if: ${{ steps.stamp-claim.outputs.claimed_case == 'login-email-password' }}";
  // The claim keeps the claimed case (stamp_report.sh keeps the claim response's case_id) as a step
  // output, format-checked, written to the same output file as bootstrap_required.
  const claim = step("Claim Stamp run case and resolve auth mode");
  assert.match(claim, /claimed_case=\$\(node -e '.*typeof v\.case_id==="string"&&\/\^\[a-z0-9\]\[a-z0-9-\]\{0,63\}\$\/\.test\(v\.case_id\)\?v\.case_id:""\)' "\$spec"\)\n/);
  const outputFile = /echo "bootstrap_required=\$required" >> ("\$\w+")/.exec(claim)[1];
  assert.ok(claim.includes(`echo "claimed_case=$claimed_case" >> ${outputFile}\n`), "claimed_case is a step output");
  // Only that case sets up the certificate check and fetches its input. The account check precedes the emulator.
  for (const name of ["Setup JDK 17 for the login acceptance certificate check", "Install build-tools 36.0.0 for the login acceptance certificate check"]) {
    assert.ok(step(name).includes(forCase), name);
    assert.ok(source.indexOf(`- name: ${name}`) > source.indexOf("- name: Stage release login credential") && source.indexOf(`- name: ${name}`) < source.indexOf("- name: Run acceptance agent"), name);
  }
  assert.match(step("Install build-tools 36.0.0 for the login acceptance certificate check"), /run: sdkmanager "build-tools;36\.0\.0"/);
  const fetchStep = step("Fetch the login acceptance input from Testbed");
  assert.ok(fetchStep.includes(forCase));
  const claimAt = source.indexOf("- name: Claim Stamp run case and resolve auth mode");
  const inputAt = source.indexOf("- name: Fetch the login acceptance input from Testbed");
  const probeAt = source.indexOf("- name: Preflight — QA account must not see the production server");
  assert.ok(claimAt < inputAt && inputAt < probeAt && probeAt < source.indexOf("- name: Run acceptance agent"));
  const preflight = step("Preflight — QA account must not see the production server");
  assert.match(preflight, /QA_TEST_EMAIL: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case != 'login-email-password' && secrets\.QA_TEST_EMAIL \|\| '' \}\}/);
  assert.match(preflight, /QA_TEST_PASSWORD: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case != 'login-email-password' && secrets\.QA_TEST_PASSWORD \|\| '' \}\}/);
  assert.match(preflight, /QA_LOGIN_ACCEPTANCE_EMAIL: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case == 'login-email-password' && secrets\.QA_LOGIN_ACCEPTANCE_EMAIL \|\| '' \}\}/);
  assert.match(preflight, /QA_LOGIN_ACCEPTANCE_PASSWORD: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case == 'login-email-password' && secrets\.QA_LOGIN_ACCEPTANCE_PASSWORD \|\| '' \}\}/);
  assert.match(preflight, /if \[ "\$STAMP_CLAIMED_CASE" = "login-email-password" \]; then[\s\S]*if \[ -z "\$QA_LOGIN_ACCEPTANCE_EMAIL" \] \|\| \[ -z "\$QA_LOGIN_ACCEPTANCE_PASSWORD" \]; then[\s\S]*missing_login_acceptance_secrets[\s\S]*exit 2[\s\S]*node scripts\/qa\/visible_servers_probe\.mjs --testbed-input/);
  assert.match(fetchStep, /continue-on-error: true\n/);
  assert.match(fetchStep.split("\n      #")[0].trimEnd(), /run: node scripts\/qa\/testbed_login_acceptance_input\.mjs$/);
  assert.deepEqual([...fetchStep.matchAll(/^ {10}([A-Z_]+):/gm)].map((m) => m[1]), ["STAMP_RUN_CASE_ID", "STAMP_URL", "STAMP_TOKEN"], "no QA account in the fetch step");
  assert.ok(step("Remove login acceptance credential").includes("if: ${{ always() && steps.stamp-claim.outputs.claimed_case == 'login-email-password' }}"));
  assert.ok(step("Remove the login acceptance input").includes("if: ${{ always() && steps.stamp-claim.outputs.claimed_case == 'login-email-password' }}"));
  assert.match(step("Stage login acceptance credential"), /umask 077[\s\S]*RUNNER_TEMP\/login-acceptance-credential[\s\S]*QA_LOGIN_ACCEPTANCE_EMAIL[\s\S]*QA_LOGIN_ACCEPTANCE_PASSWORD/);
  assert.doesNotMatch(step("Run acceptance agent"), /QA_LOGIN_ACCEPTANCE_EMAIL:|\bQA_LOGIN_ACCEPTANCE_PASSWORD:/, "emulator step env never carries login acceptance secrets");
  assert.match(step("Run acceptance agent"), /STAMP_CLAIMED_CASE: \$\{\{ steps\.stamp-claim\.outputs\.claimed_case \|\| '' \}\}/);
  assert.match(step("Report login acceptance evidence to Stamp"), /steps\.evidence-gate\.outcome/);
  assert.ok(source.indexOf("- name: Secure evidence gate") < source.indexOf("- name: Report login acceptance evidence to Stamp") &&
    source.indexOf("- name: Report login acceptance evidence to Stamp") < source.indexOf("- name: Report verdict"));

  // The login line itself, run as the emulator action runs it (sh -c), with a stand-in for node.
  const line = source.split("\n").find((l) => /login_phase\.mjs login --platform android/.test(l)).trim();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "login-line-"));
  try {
    fs.mkdirSync(path.join(dir, "bin"));
    fs.writeFileSync(path.join(dir, "bin", "node"), `#!/bin/sh\nprintf '%s\\n' "$@" > "${path.join(dir, "argv")}"\nexit 0\n`, { mode: 0o755 });
    const run = (env) => {
      fs.rmSync(path.join(dir, "qa-login-ok"), { force: true });
      const r = spawnSync("sh", ["-c", line], { cwd: dir, encoding: "utf8", env: { PATH: `${path.join(dir, "bin")}:/usr/bin:/bin`, RUNNER_TEMP: "/runner-temp", ...env } });
      assert.equal(r.status, 0, r.stderr);
      return { argv: fs.readFileSync(path.join(dir, "argv"), "utf8").trim().split("\n"), ok: fs.existsSync(path.join(dir, "qa-login-ok")) };
    };
    const today = ["scripts/qa/login_phase.mjs", "login", "--platform", "android", "--password-login", "true", "--package", "build.raft.app", "--out", "qa-evidence"];
    for (const claimed of [undefined, "", "send-message-in-channel"]) {
      assert.deepEqual(run(claimed === undefined ? {} : { STAMP_CLAIMED_CASE: claimed }), { argv: today, ok: true }, `claimed ${claimed}: the unchanged command`);
    }
    assert.deepEqual(run({ STAMP_CLAIMED_CASE: "login-email-password" }), {
      argv: [...today, "--testbed-input", "/runner-temp/testbed-login-acceptance/input.json", "--require-acceptance", "login-email-password",
        "--credential-file", "/runner-temp/login-acceptance-credential/account", "--consume-credential-file"],
      ok: true,
    });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
