// Drives the standalone entry point (release_auto_login.mjs --identity-receipt) against a fake
// `adb` on PATH: an ordinary pre-match sign-in timeout and a run missing its private output must
// leave no UI tree on the device, quote no screen text anywhere, capture nothing, and report a
// requested scenario as not_run.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("./release_auto_login.mjs", import.meta.url));
const SECRET_LABEL = "Private Inbox Preview 7319";
const NONCE = "ab".repeat(32);
const ATTEMPT = `pla_${"0123456789abcdef".repeat(2)}`;

const FAKE_ADB = `#!/usr/bin/env node
const fs = require("node:fs");
const args = process.argv.slice(2);
let input = "";
try { if (args[0] === "shell" && args[1] === "-T" || args.length === 1 && args[0] === "shell") input = fs.readFileSync(0, "utf8"); } catch {}
fs.appendFileSync(process.env.FAKE_ADB_LOG, JSON.stringify({ args, input }) + "\\n");
const out = (text) => { process.stdout.write(text); process.exit(0); };
const pkg = "build.raft.app";
const screen = '<?xml version="1.0"?><hierarchy rotation="0"><node index="0" text="${SECRET_LABEL}" content-desc="" package="com.example.other" class="android.widget.TextView" clickable="false" enabled="true" bounds="[0,0][100,100]" /></hierarchy>';
if (args[0] === "exec-out" && args[1] === "uiautomator") out(screen + "UI hierchary dumped to: /dev/tty\\n");
if (args[0] === "exec-out") out(args.includes("/sdcard/release-auto-login.xml") ? screen : "");
if (args[0] !== "shell") out("");
const cmd = args.slice(1).join(" ");
if (cmd.startsWith("pm path build.qa.capturebrowser")) out("package:/data/app/stub/base.apk\\n");
if (cmd.startsWith("run-as build.qa.capturebrowser sh -c")) out("absent\\n");
if (cmd.startsWith("cmd package resolve-activity")) out("priority=0\\nbuild.qa.capturebrowser/.CaptureActivity\\n");
if (cmd === "pm clear " + pkg) out("Success\\n");
if (cmd === "dumpsys package " + pkg) out("Packages:\\n  Package [" + pkg + "]\\n    versionCode=12700001 minSdk=29\\n    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\\n  ContentProvider Authorities:\\n    [" + pkg + ".identity-receipt]:\\n");
if (cmd.startsWith("pm enable " + pkg + "/")) out("Component {" + args[1].split(" ")[2] + "} new state: enabled\\n");
if (cmd.startsWith("pm disable " + pkg + "/")) out("Component {" + args[1].split(" ")[2] + "} new state: disabled\\n");
if (cmd === "getprop ro.build.version.sdk") out("35\\n");
out("");
`;

function runStandalone({ env = {}, args = [], privateInsideOut = false } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "release-login-identity-"));
  const bin = path.join(dir, "bin");
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, "adb"), FAKE_ADB, { mode: 0o755 });
  const log = path.join(dir, "adb.log");
  const out = path.join(dir, "evidence");
  const privateOut = path.join(dir, "private", "identity.json");
  fs.mkdirSync(path.dirname(privateOut));
  const result = spawnSync(process.execPath, [SCRIPT, "--identity-receipt", "--out", out, "--scenario", "scripts/qa/scenarios/none.mjs", ...args], {
    encoding: "utf8",
    timeout: 60_000,
    env: {
      PATH: `${bin}${path.delimiter}${process.env.PATH}`,
      HOME: process.env.HOME ?? dir,
      FAKE_ADB_LOG: log,
      RELEASE_AUTO_LOGIN_TEST_TIME_SCALE: "0.002",
      QA_LOGIN_EMAIL: "qa@example.invalid",
      QA_LOGIN_PASSWORD: "not-a-real-password",
      QA_ATTEMPT_ID: ATTEMPT,
      QA_ATTEMPT_NONCE: NONCE,
      QA_IDENTITY_PRIVATE_OUT: privateInsideOut ? path.join(out, "identity.json") : privateOut,
      ...env,
    },
  });
  const adbCalls = fs.existsSync(log) ? fs.readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : [];
  const summaryFile = path.join(out, "summary.json");
  const summaryText = fs.existsSync(summaryFile) ? fs.readFileSync(summaryFile, "utf8") : "";
  const files = fs.existsSync(out) ? fs.readdirSync(out) : [];
  const privateExists = fs.existsSync(privateOut);
  fs.rmSync(dir, { recursive: true, force: true });
  return { result, adbCalls, summaryText, summary: summaryText ? JSON.parse(summaryText) : null, files, privateExists };
}

test("a pre-match sign-in timeout writes no UI tree to the device and quotes no screen text", () => {
  const run = runStandalone();
  assert.equal(run.result.status, 1, run.result.stderr);
  const step = run.summary.steps.find((s) => s.name === "open-sign-in-page");
  assert.equal(step?.result, "fail", JSON.stringify(run.summary.steps));
  assert.match(step.error, /^timed out after 120s waiting for "Sign in to Raft"$/);
  // Nothing on screen leaves the process: not stderr/stdout, not the summary, not a file.
  for (const text of [run.result.stdout, run.result.stderr, run.summaryText]) {
    assert.ok(!text.includes(SECRET_LABEL), "screen text leaked");
    assert.ok(!text.includes("on screen"), "screen text section present");
    assert.ok(!text.includes(NONCE), "nonce leaked");
  }
  // The hierarchy is read only through adb's stdout: never dumped to (or removed from) shared storage.
  const flat = run.adbCalls.map((c) => `${c.args.join(" ")} ${c.input}`);
  assert.ok(flat.some((c) => c.startsWith("exec-out uiautomator dump /dev/tty")), "transient dump used");
  assert.ok(!flat.some((c) => c.includes("/sdcard")), flat.filter((c) => c.includes("/sdcard")).join("\n"));
  assert.ok(!flat.some((c) => c.includes("screencap")), "no screenshot");
  // No evidence captured before a match; only the summary (and the app-login log) are written.
  assert.deepEqual(run.files.filter((f) => /\.(png|xml)$/.test(f)), []);
  assert.equal(run.summary.scenario_result, "not_run");
  assert.equal(run.summary.result, "failed");
  // The provider was enabled before sign-in and disabled again in cleanup.
  assert.equal(run.summary.cleanup.identity_receipt_provider, "disabled");
  assert.equal(run.privateExists, false, "no private output without a receipt");
});

test("a run stopped before the scenario (missing private output setting) reports scenario_result not_run", () => {
  const run = runStandalone({ env: { QA_IDENTITY_PRIVATE_OUT: "" } });
  assert.equal(run.result.status, 1);
  assert.deepEqual(run.summary.steps, []);
  assert.equal(run.summary.result, "failed");
  assert.equal(run.summary.scenario_result, "not_run");
  assert.match(run.summary.error, /QA_IDENTITY_PRIVATE_OUT is required/);
  assert.ok(!run.adbCalls.some((c) => c.args[0] === "shell" && /^pm enable/.test(c.args[1] ?? "")), "nothing enabled");
});

test("the private output must be a fresh file outside the evidence directory", () => {
  const inside = runStandalone({ privateInsideOut: true });
  assert.equal(inside.result.status, 1);
  assert.match(inside.summary.error, /outside the evidence directory/);
  assert.equal(inside.summary.scenario_result, "not_run");
});
