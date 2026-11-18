// The web login's answer -> the web_session cleanup status, on the existing standalone command with
// its default flags. Runs the ACTUAL release_auto_login.mjs with a fake adb (shell script in a temp
// dir) and an in-process mock fetch (--require): no device, no network, synthetic values only.
// Adapted from Argus's offline replay of PR #4946 (login-response-control.mjs).
//
// Only change from main: a LOST answer (no HTTP response) or an INCOMPLETE one (a 2xx without a
// usable access token / unreadable body) is login_outcome_unknown, because the request may still
// have opened a session. Every other answer keeps main's status (not_opened); that is NOT a claim
// that a 4xx/5xx proves no session was created.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const FAKE_ADB = `#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_ROOT/adb.calls"
case "$*" in
  'shell cmd role get-role-holders '*) echo build.qa.capturebrowser;;
  'shell cmd package resolve-activity '*) echo build.qa.capturebrowser/.CaptureActivity;;
  'shell dumpsys package build.raft.app') printf 'Package [build.raft.app] (x):\\n versionName=1\\n versionCode=1\\n pkgFlags=[]\\n';;
  'shell uiautomator dump '*) :;;
  'exec-out cat /sdcard/release-auto-login.xml') printf '%s\\n' '<?xml version="1.0"?><hierarchy><node text="Sign in to Raft" package="build.raft.app" clickable="true" enabled="true" bounds="[0,0][100,50]" /><node text="I agree to the Terms" package="build.raft.app" bounds="[100,100][300,150]" /></hierarchy>';;
  'shell input tap 50 25') echo 'https://app.raft.build/login/app?request=fake-request' > "$FAKE_ROOT/request";;
  'shell run-as build.qa.capturebrowser cat files/captured_url 2>/dev/null') cat "$FAKE_ROOT/request" 2>/dev/null;;
  'shell run-as build.qa.capturebrowser rm -f '*) rm -f "$FAKE_ROOT/request";;
  'shell run-as build.qa.capturebrowser sh -c '*) echo absent;;
  'shell pm path build.qa.capturebrowser') :;;
  'logcat -v '*) sleep 5;;
  *) :;;
esac
`;

// Every request the script makes goes to this mock; only POST auth/login is expected (it stops there).
const MOCK_FETCH = `
const fs = require("fs");
global.fetch = async (url, options) => {
  fs.appendFileSync(process.env.FAKE_ROOT + "/http.calls", (options && options.method) + " " + new URL(url).pathname + "\\n");
  const kind = process.env.FAKE_KIND;
  if (kind === "lost") throw new Error("synthetic transport loss");
  const answers = {
    "200-empty": [200, {}], "204": [204, null], "200-2fa": [200, { secondFactorRequired: true }],
    "401": [401, { code: "invalid_credentials" }], "403": [403, { code: "synthetic_rejected" }],
    "429": [429, { code: "rate_limited" }], "500": [500, { code: "internal" }],
  };
  if (kind === "200-not-json") return { status: 200, json: async () => { throw new SyntaxError("synthetic"); } };
  const [status, body] = answers[kind];
  return { status, json: async () => { if (body === null) throw new SyntaxError("empty"); return body; } };
};
`;

async function replay(kind) {
  const t = fs.mkdtempSync(path.join(os.tmpdir(), "fake-web-response-"));
  try {
    const bin = path.join(t, "bin");
    fs.mkdirSync(bin);
    fs.writeFileSync(path.join(bin, "adb"), FAKE_ADB, { mode: 0o755 });
    fs.writeFileSync(path.join(t, "mock.cjs"), MOCK_FETCH);
    const out = path.join(t, "out");
    // The existing default command (as in release-auto-login.yml), no new flags.
    const p = spawn(process.execPath, [path.join(HERE, "release_auto_login.mjs"), "--env", "production", "--strict-cleanup", "--revoke-app-session", "--capture-browser-apk", "fake.apk", "--out", out], {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_ROOT: t, FAKE_KIND: kind, NODE_OPTIONS: `--require ${path.join(t, "mock.cjs")}`, QA_LOGIN_EMAIL: "synthetic@example.invalid", QA_LOGIN_PASSWORD: "synthetic-password" },
    });
    let stderr = "";
    p.stderr.on("data", (d) => { stderr += d; });
    const code = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { p.kill("SIGKILL"); reject(new Error("fake replay timeout")); }, 60_000);
      p.on("close", (c) => { clearTimeout(timer); resolve(c); });
    });
    const http = fs.readFileSync(path.join(t, "http.calls"), "utf8").trim().split("\n");
    return { code, stderr, http, summary: JSON.parse(fs.readFileSync(path.join(out, "summary.json"), "utf8")) };
  } finally {
    fs.rmSync(t, { recursive: true, force: true });
  }
}

const CASES = {
  // Changed from main (main: not_opened): the request was sent and no usable answer came back.
  lost: "login_outcome_unknown",
  "200-empty": "login_outcome_unknown",
  "200-not-json": "login_outcome_unknown",
  204: "login_outcome_unknown",
  // Unchanged from main.
  401: "not_opened",
  403: "not_opened",
  429: "not_opened",
  500: "not_opened",
  "200-2fa": "not_opened",
};

test("default command: lost / incomplete login answers are login_outcome_unknown; every other answer keeps main's status", async () => {
  const results = await Promise.all(Object.keys(CASES).map(async (kind) => [kind, await replay(kind)]));
  for (const [kind, r] of results) {
    assert.equal(r.code, 1, `${kind}: the run still fails\n${r.stderr}`);
    assert.deepEqual(r.http, ["POST /api/auth/login"], `${kind}: only the login request was made`);
    assert.equal(r.summary.result, "failed", kind);
    assert.equal(r.summary.cleanup.web_session, CASES[kind], kind);
    // Nothing else in the cleanup record depends on the login answer.
    assert.equal(r.summary.cleanup.app_session, "not_signed_in", kind);
    assert.equal(r.summary.cleanup.capture_file, "not_applicable", kind);
    assert.deepEqual(Object.keys(r.summary).sort(), ["cleanup", "cleanup_failures", "cleanup_ok", "environment", "error", "package", "result", "revoke_app_session", "steps", "strict_cleanup", "web_host"], `${kind}: no new summary field`);
    const webFailure = r.summary.cleanup_failures.some((f) => f.step === "web_session");
    assert.equal(webFailure, CASES[kind] === "login_outcome_unknown", `${kind}: only an unknown outcome is a web_session cleanup failure`);
    assert.ok(!JSON.stringify(r.summary).includes("synthetic-password"), kind);
  }
});
