import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

test("filters threadtime logcat to observed app pids and injects verdict scope", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "scope-logcat-"));
  fs.writeFileSync(path.join(dir, "full.txt"), [
    "07-19 10:00:00.000  1234  1250 I Raft: app line",
    "07-19 10:00:00.100  9999  9999 E Other: unrelated crash",
  ].join("\n"));
  fs.writeFileSync(path.join(dir, "pids.txt"), "1234\n");
  fs.writeFileSync(path.join(dir, "verdict.json"), '{"verdict":"pass"}');
  execFileSync(process.execPath, [
    new URL("./scope_logcat.mjs", import.meta.url).pathname,
    "--input", path.join(dir, "full.txt"),
    "--output", path.join(dir, "scoped.txt"),
    "--verdict", path.join(dir, "verdict.json"),
    "--pids", path.join(dir, "pids.txt"),
    "--package", "build.raft.app.alpha",
    "--generation", "29600000000:1",
  ]);
  assert.match(fs.readFileSync(path.join(dir, "scoped.txt"), "utf8"), /app line/);
  assert.doesNotMatch(fs.readFileSync(path.join(dir, "scoped.txt"), "utf8"), /unrelated crash/);
  const verdict = JSON.parse(fs.readFileSync(path.join(dir, "verdict.json"), "utf8"));
  assert.deepEqual(verdict.log_scope, {
    filtered: true, pid: 1234, pids: [1234], package_id: "build.raft.app.alpha", generation: "29600000000:1",
  });
});

test("iOS NDJSON rejects stale generation-window and wrong/restarted pids", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "scope-ios-log-"));
  fs.writeFileSync(path.join(dir, "full.txt"), [
    JSON.stringify({ timestamp: "2026-07-19 09:59:59.000+0000", processID: 1234, process: "iosApp", eventMessage: "previous attempt" }),
    JSON.stringify({ timestamp: "2026-07-19 10:00:01.000+0000", processID: 9999, process: "iosApp", eventMessage: "old pid after restart" }),
    JSON.stringify({ timestamp: "2026-07-19 10:00:02.000+0000", processID: 1234, process: "iosApp", eventMessage: "current generation" }),
  ].join("\n"));
  fs.writeFileSync(path.join(dir, "pids.txt"), "1234\n");
  fs.writeFileSync(path.join(dir, "verdict.json"), '{"verdict":"pass"}');
  execFileSync(process.execPath, [
    new URL("./scope_logcat.mjs", import.meta.url).pathname,
    "--input", path.join(dir, "full.txt"), "--output", path.join(dir, "scoped.txt"),
    "--verdict", path.join(dir, "verdict.json"), "--pids", path.join(dir, "pids.txt"),
    "--package", "build.raft.app", "--generation", "99:1",
    "--format", "ios-ndjson", "--started-at", "2026-07-19T10:00:00.000Z",
  ]);
  const scoped = fs.readFileSync(path.join(dir, "scoped.txt"), "utf8");
  assert.match(scoped, /current generation/);
  assert.doesNotMatch(scoped, /previous attempt|old pid after restart/);
});
