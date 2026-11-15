// Unit tests for the secure-input boundary and the evidence gate. Fake values only.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {
  CaptureGate,
  DEFAULT_COMPLETION_MARKER,
  emulatorConsolePort,
  emulatorConsoleType,
  encodeConsoleText,
  evaluateResumeReceipts,
  findCompletionReceipt,
  findLoginExit,
  formOutline,
  isExternalLoginSurface,
  loginLogState,
  parseFocusedPackage,
  parseImePackage,
  parseKeyboardShown,
  parseTextValue,
} from "./secure_input.mjs";
import { filterLogWindows, gateEvidence } from "./secure_evidence.mjs";

const env = { QA_TEST_EMAIL: "fake.user@example.test", QA_TEST_PASSWORD: "FAKE-pw-7Q" };
const APP = "build.raft.app";
const homeXml = `<hierarchy><node text="Home" class="android.widget.TextView" package="${APP}" focused="false" password="false" bounds="[0,0][10,10]" /></hierarchy>`;
const goodReceipts = {
  appPackage: APP, entrySurfaces: new Set(["com.android.chrome"]), loginExit: { kind: "login_completed", epoch: 1 },
  secretTyped: true, focusedPackage: APP, keyboardShown: false, externalSurfacesCleared: true, imeCleared: true, autofillOff: true,
  uiXml: homeXml, env,
};

test("secret references resolve only as a whole value from the allowlist", () => {
  assert.deepEqual(parseTextValue("${secret:QA_TEST_PASSWORD}", env), { kind: "secret", name: "QA_TEST_PASSWORD", value: env.QA_TEST_PASSWORD });
  assert.equal(parseTextValue("{{QA_EMAIL}}", env).name, "QA_TEST_EMAIL");
  assert.deepEqual(parseTextValue("hello world", env), { kind: "plain", value: "hello world" });
  assert.throws(() => parseTextValue("pw: ${secret:QA_TEST_PASSWORD}", env), /entire text value/);
  assert.throws(() => parseTextValue("${secret:GITHUB_TOKEN}", env), /allowlist/);
  assert.throws(() => parseTextValue("${secret:QA_TEST_PASSWORD}", {}), /not configured/);
  assert.throws(() => parseTextValue(`x${env.QA_TEST_PASSWORD}`, env), /configured secret value/);
});

test("emulator console channel: value only on the socket, auth first, fails closed", async () => {
  assert.equal(encodeConsoleText(env.QA_TEST_PASSWORD), env.QA_TEST_PASSWORD);
  for (const bad of ["line\nbreak", " lead", "trail ", "tab\tx", "ünï"]) assert.throws(() => encodeConsoleText(bad), /cannot type exactly/);
  assert.equal(emulatorConsolePort("emulator-5556", {}), 5556);
  assert.equal(emulatorConsolePort("R58M123ABC", {}), null, "a physical device has no console");
  await assert.rejects(() => emulatorConsoleType({ port: null, secret: env.QA_TEST_PASSWORD }), /no emulator console/);

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "console-"));
  const tokenFile = path.join(dir, "token");
  fs.writeFileSync(tokenFile, "fake-console-token\n");
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
        else if (line.startsWith("event text ")) sock.write("OK\r\n");
      }
    });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  try {
    const { port } = server.address();
    await emulatorConsoleType({ port, secret: env.QA_TEST_PASSWORD, tokenFile });
    await new Promise((r) => setTimeout(r, 50));
    assert.deepEqual(lines.slice(0, 2), ["auth fake-console-token", `event text ${env.QA_TEST_PASSWORD}`]);
    fs.writeFileSync(tokenFile, "wrong");
    const err = await emulatorConsoleType({ port, secret: env.QA_TEST_PASSWORD, tokenFile }).catch((e) => e);
    assert.match(String(err.message), /refused/);
    assert.ok(!String(err.message).includes(env.QA_TEST_PASSWORD));
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("capture gate: suppressed from begin; UNKNOWN never resumes", () => {
  let t = 100;
  const gate = new CaptureGate(() => t++);
  assert.equal(gate.suppressed, false);
  gate.begin(2, "secret_text");
  assert.throws(() => gate.assertCaptureAllowed("screenshot"), /suppressed/);
  gate.resume(4, { login_exit: {} });
  assert.equal(gate.suppressed, false);
  gate.begin(6, "secret_text");
  gate.markUnknown(7, ["x"]);
  assert.equal(gate.suppressed, true);
  assert.equal(gate.resume(8, {}), null);
  assert.equal(gate.suppressed, true);
  assert.equal(gate.summary().unresolved, true);
});

test("resume requires every positive receipt; unknown or failed receipts stay suppressed; AX is advisory", () => {
  assert.equal(evaluateResumeReceipts(goodReceipts).ok, true);
  assert.deepEqual(Object.keys(evaluateResumeReceipts(goodReceipts).receipts).sort(),
    ["autofill_off", "ime_cleared", "inputs_cleared", "login_exit", "screen_switch"]);
  // A missing tree is not a receipt and not a blocker: the AX tree never proves anything.
  assert.equal(evaluateResumeReceipts({ ...goodReceipts, uiXml: "" }).ok, true);
  assert.equal(evaluateResumeReceipts({ ...goodReceipts, loginExit: { kind: "login_abandoned", epoch: 2 } }).ok, true, "sign-in exit without a session");
  for (const [k, v, why] of [
    ["loginExit", null, /login-complete/],
    ["focusedPackage", "com.android.chrome", /not the app/],
    ["focusedPackage", null, /not the app/],
    ["keyboardShown", true, /keyboard still shown/],
    ["keyboardShown", null, /keyboard state unreadable/],
    ["externalSurfacesCleared", false, /cleared/],
    ["externalSurfacesCleared", null, /cleared/],
    ["imeCleared", false, /suggestions/],
    ["imeCleared", null, /suggestions/],
    ["autofillOff", false, /autofill/],
    ["autofillOff", null, /autofill/],
    ["entrySurfaces", new Set([APP]), /cannot prove a screen switch/],
    ["entrySurfaces", new Set([null]), /cannot prove a screen switch/],
    ["entrySurfaces", new Set(), /login surface unknown/],
    ["uiXml", homeXml.replace("Home", "fake.user@example.test"), /advisory: secret/],
    ["uiXml", homeXml.replace('password="false"', 'password="true"'), /advisory: a password field/],
  ]) {
    const r = evaluateResumeReceipts({ ...goodReceipts, [k]: v });
    assert.equal(r.ok, false, `${k}=${String(v)}`);
    assert.ok(r.reasons.some((x) => why.test(x)), `${k}: ${r.reasons}`);
  }
  // A browser-only window (no login attempt, nothing typed) may close on browser_closed.
  const browserOnly = { ...goodReceipts, loginExit: null, secretTyped: false, loginAttempted: false, imeCleared: null, autofillOff: null };
  assert.equal(evaluateResumeReceipts(browserOnly).ok, true);
  assert.equal(evaluateResumeReceipts({ ...browserOnly, externalSurfacesCleared: null }).ok, false);
  assert.equal(evaluateResumeReceipts({ ...browserOnly, loginAttempted: true }).ok, false, "a login attempt needs its own exit receipt");
});

test("system-channel parsers fail closed on unknown output", () => {
  assert.equal(parseFocusedPackage("  mCurrentFocus=Window{1a u0 build.raft.app/build.raft.app.MainActivity}"), APP);
  assert.equal(parseFocusedPackage("mCurrentFocus=null"), null);
  assert.equal(parseKeyboardShown("mInputShown=false"), false);
  assert.equal(parseKeyboardShown("mInputShown=false\n mInputShown=true"), true);
  assert.equal(parseKeyboardShown("nothing"), null);
  assert.equal(parseImePackage("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n"), "com.google.android.inputmethod.latin");
  assert.equal(parseImePackage("null"), null);
  assert.equal(isExternalLoginSurface("com.android.chrome", APP), true);
  assert.equal(isExternalLoginSurface(APP, APP), false);
  assert.equal(isExternalLoginSurface("com.android.permissioncontroller", APP), false);
  const log = "1700000000.100  1  1 I SlockAppLogin: app_login_complete result=app_shell reason=exchanged\n";
  assert.ok(findCompletionReceipt(log, 1700000000, DEFAULT_COMPLETION_MARKER));
  assert.equal(findCompletionReceipt(log, 1700000001, DEFAULT_COMPLETION_MARKER), null, "older than the window");
  assert.equal(findCompletionReceipt(log.replace("app_shell reason=exchanged", "login_error reason=exchange_failed"), 0, DEFAULT_COMPLETION_MARKER), null);
  const opened = "1700000000.000  1  1 I SlockAppLogin: web_login_attempt phase=opening\n";
  assert.equal(findLoginExit(opened + log, 1700000000)?.kind, "login_completed");
  assert.equal(findLoginExit(log + "1700000001.000  1  1 I SlockAppLogin: web_login_attempt phase=opening\n", 1700000000), null, "a reopened page cancels the earlier exit");
  assert.equal(findLoginExit("1700000002.000  1  1 I SlockAppLogin: web_login_attempt phase=idle reason=browser_closed\n", 1700000000)?.kind, "login_abandoned");
  assert.equal(findLoginExit("1700000002.000  1  1 I SlockAppLogin: web_login_attempt phase=idle reason=callback_login_error\n", 1700000000), null, "a failed login is not an exit receipt");
  assert.equal(loginLogState(opened).open, true);
  assert.equal(loginLogState(opened + log).open, false);
  assert.equal(loginLogState(opened, 1699999999).openedAfter, 1700000000);
  assert.equal(loginLogState(opened, 1700000000).openedAfter, null);
});

test("form outline carries field bounds only — never text, hints or values", () => {
  const xml = `<hierarchy><node text="${env.QA_TEST_EMAIL}" hint="Email" class="android.widget.EditText" package="com.android.chrome" focused="true" password="false" bounds="[10,20][30,40]" />`
    + `<node text="••••" class="android.widget.EditText" package="com.android.chrome" focused="false" password="true" bounds="[10,50][30,70]" />`
    + `<node text="Signed in as someone" class="android.widget.TextView" package="com.android.chrome" bounds="[0,0][5,5]" /></hierarchy>`;
  const out = formOutline(xml);
  assert.equal(out, "form outline (bounds only, login window):\ninput#1 [FOCUSED] [10,20][30,40]\ninput#2 (password) [10,50][30,70]");
});

test("evidence gate redacts text, removes binaries holding a secret, and drops media after an unresolved window", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "secure-evidence-"));
  try {
    fs.writeFileSync(path.join(dir, "notes.txt"), `a ${env.QA_TEST_PASSWORD} b ${encodeURIComponent(env.QA_TEST_EMAIL)}\n`);
    fs.mkdirSync(path.join(dir, "run.xcresult"));
    fs.writeFileSync(path.join(dir, "run.xcresult", "data.0"), Buffer.concat([Buffer.from([0, 1, 2]), Buffer.from(env.QA_TEST_PASSWORD)]));
    fs.writeFileSync(path.join(dir, "step-00.png"), "before");
    const old = new Date(Date.now() - 60000);
    fs.utimesSync(path.join(dir, "step-00.png"), old, old);
    fs.writeFileSync(path.join(dir, "late.png"), "after");
    fs.writeFileSync(path.join(dir, "qa-rec-01.mp4"), "video");
    fs.writeFileSync(path.join(dir, "capture-suppression.json"), JSON.stringify({ unresolved: true, phases: [{ start_ts: Date.now() - 1000, status: "UNKNOWN", recording: "stopped", device_start_local: "11-15 10:00:00" }] }));
    const { ok, report } = gateEvidence(dir, env);
    assert.equal(ok, true);
    assert.equal(fs.readFileSync(path.join(dir, "notes.txt"), "utf8"), "a *** b ***\n");
    assert.ok(!fs.existsSync(path.join(dir, "run.xcresult", "data.0")));
    assert.ok(fs.existsSync(path.join(dir, "step-00.png")), "frames from before the window stay");
    assert.ok(!fs.existsSync(path.join(dir, "late.png")));
    assert.ok(!fs.existsSync(path.join(dir, "qa-rec-01.mp4")));
    assert.equal(report.suppression, "unresolved");
    assert.ok(!JSON.stringify(report).includes("FAKE-pw"));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("evidence gate: unconfirmed recording stop drops all recordings; a resolved window drops only its entry segment", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "secure-evidence-rec-"));
  try {
    fs.writeFileSync(path.join(dir, "qa-rec-01.mp4"), "video");
    fs.writeFileSync(path.join(dir, "capture-suppression.json"), JSON.stringify({ unresolved: false, active: false, phases: [{ start_ts: 1, status: "resumed", recording: "unconfirmed" }] }));
    assert.equal(gateEvidence(dir, {}).ok, true);
    assert.ok(!fs.existsSync(path.join(dir, "qa-rec-01.mp4")));
    for (const f of ["qa-rec-01.mp4", "qa-rec-02.mp4", "qa-rec-03.mp4"]) fs.writeFileSync(path.join(dir, f), "video");
    fs.writeFileSync(path.join(dir, "capture-suppression.json"), JSON.stringify({ unresolved: false, active: false, phases: [{ start_ts: 1, end_ts: 2, status: "resumed", recording: "stopped", dropped_segments: ["qa-rec-02.mp4"], device_start_local: "11-15 10:00:00", device_end_local: "11-15 10:01:00" }] }));
    assert.equal(gateEvidence(dir, {}).ok, true);
    assert.deepEqual(fs.readdirSync(dir).filter((f) => f.endsWith(".mp4")).sort(), ["qa-rec-01.mp4", "qa-rec-03.mp4"]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("log lines inside a login window are withheld (Android device-local and host-clock logs)", () => {
  const logcat = [
    "11-15 09:59:59.000  100  100 I Chromium: before",
    "11-15 10:00:01.000  200  200 I Gboard: suggestion strip learned FAKE-pw-7Q",
    "    continuation of that line",
    "11-15 10:00:30.000  300  300 I Autofill: save prompt",
    "11-15 10:01:01.000  100  100 I SlockApp: after",
  ].join("\n");
  const resolved = [{ status: "resumed", recording: "stopped", device_start_local: "11-15 10:00:00", device_end_local: "11-15 10:01:00" }];
  const out = filterLogWindows(logcat, resolved);
  assert.equal(out.withheld, 3);
  assert.equal(out.text, "11-15 09:59:59.000  100  100 I Chromium: before\n--------- capture suppressed: login window 11-15 10:00:00 .. 11-15 10:01:00\n11-15 10:01:01.000  100  100 I SlockApp: after");
  const open = filterLogWindows(logcat, [{ status: "UNKNOWN", recording: "stopped", device_start_local: "11-15 10:00:00" }]);
  assert.equal(open.withheld, 4, "an unresolved window withholds everything after its start");
  assert.equal(filterLogWindows(logcat, [{ status: "UNKNOWN", recording: "stopped" }]), null, "no boundary: the file is dropped");
  const start = Date.parse("2026-11-15T10:00:00Z");
  const ios = `2026-11-15 09:59:00.000000+0000 iosApp: before\n2026-11-15 10:00:05.000000+0000 iosApp: during\n{"timestamp":"2026-11-15 10:00:06.000000+0000","eventMessage":"during"}`;
  const iosOut = filterLogWindows(ios, [{ status: "UNKNOWN", recording: "not_recorded", start_ts: start }]);
  assert.equal(iosOut.withheld, 2);
  assert.match(iosOut.text, /^2026-11-15 09:59:00.000000\+0000 iosApp: before\n--------- capture suppressed/);
});
