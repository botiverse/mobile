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
  ]);
  assert.match(fs.readFileSync(path.join(dir, "scoped.txt"), "utf8"), /app line/);
  assert.doesNotMatch(fs.readFileSync(path.join(dir, "scoped.txt"), "utf8"), /unrelated crash/);
  const verdict = JSON.parse(fs.readFileSync(path.join(dir, "verdict.json"), "utf8"));
  assert.deepEqual(verdict.log_scope, {
    filtered: true, pid: 1234, pids: [1234], package_id: "build.raft.app.alpha",
  });
});
