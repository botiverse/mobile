import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { readRecord, writeRecord } from "./login_phase.mjs";

// The deterministic repro never signs in (#222). It runs here with the REAL iOS driver against a
// fake agent-device (its accessibility snapshot) and a fake xcrun (records every screenshot).

const script = fileURLToPath(new URL("./ios_deterministic_repro.mjs", import.meta.url));
const RUN = { GITHUB_RUN_ID: "5151", GITHUB_RUN_ATTEMPT: "1" };

function runRepro(nodes) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ios-deterministic-repro-test-"));
  const bin = path.join(dir, "bin");
  fs.mkdirSync(bin);
  const calls = path.join(dir, "calls.log");
  fs.writeFileSync(path.join(bin, "agent-device"), `#!${process.execPath}
require("fs").appendFileSync(${JSON.stringify(calls)}, "agent-device " + process.argv.slice(2).join(" ") + "\\n");
if (process.argv[2] === "snapshot") process.stdout.write(JSON.stringify({ nodes: ${JSON.stringify(nodes)} }));
`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "xcrun"), `#!${process.execPath}
const fs = require("fs"), a = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)}, "xcrun " + a.join(" ") + "\\n");
if (a[1] === "io" && a[3] === "screenshot") { const b = Buffer.alloc(24); b.writeUInt32BE(1170, 16); b.writeUInt32BE(2532, 20); fs.writeFileSync(a[4], b); }
`, { mode: 0o755 });
  const out = path.join(dir, "out");
  writeRecord(out, { mode: "none", status: "no-login", login_end: null }, RUN);
  const result = spawnSync(process.execPath, [script, "--ios-io-dir", path.join(dir, "io"), "--ios-udid", "booted", "--out", out], {
    cwd: dir, encoding: "utf8", timeout: 30_000,
    env: { PATH: `${bin}:${process.env.PATH}`, HOME: dir, AGENT_DEVICE_BIN: path.join(bin, "agent-device"), ...RUN },
  });
  const verdictFile = path.join(out, "verdict.json");
  return {
    ...result,
    calls: fs.existsSync(calls) ? fs.readFileSync(calls, "utf8") : "",
    verdict: fs.existsSync(verdictFile) ? JSON.parse(fs.readFileSync(verdictFile, "utf8")) : null,
    record: readRecord(out, RUN),
  };
}

const rect = { x: 20, y: 200, width: 350, height: 44 };

test("a login surface at launch is UNKNOWN before any screenshot, and nothing is typed", () => {
  const r = runRepro([{ label: "Email", role: "TextField", rect }, { label: "", role: "SecureTextField", rect }]);
  assert.equal(r.status, 2, r.stderr);
  assert.equal(r.verdict?.verdict, "unknown");
  assert.equal(r.record.ok, false);
  assert.doesNotMatch(r.calls, /screenshot/, "no frame was captured");
  assert.doesNotMatch(r.calls, /agent-device (type|press|fill)/, "nothing typed or tapped");
  // The probe came first: the snapshot is the only read after opening the app.
  assert.match(r.calls, /agent-device open build\.raft\.app[^\n]*\nagent-device snapshot -i --json/);
});

test("a signed-out start is a harness error: the repro never signs in", () => {
  const r = runRepro([{ label: "Sign in to Raft", role: "Button", rect }]);
  assert.equal(r.status, 2, r.stderr);
  assert.equal(r.verdict?.verdict, "error");
  assert.match(r.verdict.reason, /never signs in/);
  assert.doesNotMatch(r.calls, /agent-device (type|fill)/);
  assert.equal(r.record.ok, true, "a signed-out landing page is not a login window");
});

test("the deterministic workflow step gets no QA credentials and records that it never signs in", () => {
  const wf = fs.readFileSync(new URL("../../.github/workflows/qa-acceptance-ios.yml", import.meta.url), "utf8");
  const start = wf.indexOf("      - name: Run deterministic repro\n");
  const step = wf.slice(start, wf.indexOf("\n      - name:", start + 1));
  assert.doesNotMatch(step, /QA_TEST_|secrets\./);
  assert.match(step, /login_phase\.mjs login --platform ios --password-login false --out qa-evidence\n[\s\S]*ios_deterministic_repro\.mjs/);
});
