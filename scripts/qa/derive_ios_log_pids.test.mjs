import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";

const script = new URL("./derive_ios_log_pids.mjs", import.meta.url).pathname;
const scopeScript = new URL("./scope_logcat.mjs", import.meta.url).pathname;

function fixture(lines) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "derive-ios-log-pids-"));
  const input = path.join(dir, "logcat-iosApp.txt");
  const output = path.join(dir, "app-pids.txt");
  fs.writeFileSync(input, `${lines.join("\n")}\n`);
  return { input, output };
}

test("derives a deduplicated app PID from current-window iosApp NDJSON", () => {
  const { input, output } = fixture([
    JSON.stringify({ timestamp: "2026-07-20 01:00:01.000+0000", processID: 4001, process: "iosApp", eventMessage: "first" }),
    JSON.stringify({ timestamp: "2026-07-20 01:00:02.000+0000", processIdentifier: 4001, process: "iosApp", eventMessage: "second" }),
  ]);
  execFileSync(process.execPath, [script, "--input", input, "--output", output, "--started-at", "2026-07-20T01:00:00.000Z"]);
  assert.equal(fs.readFileSync(output, "utf8"), "4001\n");

  const dir = path.dirname(input);
  const scoped = path.join(dir, "logcat.txt");
  const verdict = path.join(dir, "verdict.json");
  fs.writeFileSync(verdict, '{"verdict":"pass"}');
  execFileSync(process.execPath, [
    scopeScript,
    "--input", input, "--output", scoped, "--verdict", verdict,
    "--pids", output, "--package", "build.raft.app", "--generation", "29705633909:1",
    "--format", "ios-ndjson", "--started-at", "2026-07-20T01:00:00.000Z",
  ]);
  assert.match(fs.readFileSync(scoped, "utf8"), /first/);
  assert.match(fs.readFileSync(scoped, "utf8"), /second/);
  assert.deepEqual(JSON.parse(fs.readFileSync(verdict, "utf8")).log_scope, {
    filtered: true,
    pid: 4001,
    pids: [4001],
    package_id: "build.raft.app",
    generation: "29705633909:1",
  });
});

test("rejects stale NDJSON even when it contains a positive PID", () => {
  const { input, output } = fixture([
    JSON.stringify({ timestamp: "2026-07-20 00:59:59.999+0000", pid: 4001, process: "iosApp" }),
  ]);
  const result = spawnSync(process.execPath, [script, "--input", input, "--output", output, "--started-at", "2026-07-20T01:00:00.000Z"], { encoding: "utf8" });
  assert.equal(result.status, 1);
  assert.equal(fs.readFileSync(output, "utf8"), "");
  assert.match(result.stderr, /no positive iOS app PID/);
});

test("rejects current-window NDJSON without a positive PID", () => {
  const { input, output } = fixture([
    JSON.stringify({ timestamp: "2026-07-20 01:00:01.000+0000", process: "iosApp", eventMessage: "missing pid" }),
    JSON.stringify({ timestamp: "2026-07-20 01:00:02.000+0000", processID: 0, process: "iosApp", eventMessage: "invalid pid" }),
  ]);
  const result = spawnSync(process.execPath, [script, "--input", input, "--output", output, "--started-at", "2026-07-20T01:00:00.000Z"], { encoding: "utf8" });
  assert.equal(result.status, 1);
  assert.equal(fs.readFileSync(output, "utf8"), "");
  assert.match(result.stderr, /no positive iOS app PID/);
});
