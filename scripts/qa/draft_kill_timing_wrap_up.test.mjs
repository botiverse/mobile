// Wrap-up of scripts/qa/android_draft_kill_timing.mjs: the verdict is decided after the whole-run logcat
// statistics exist, the raw logcat is read in bounded chunks, and only filtered lines become evidence.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { acceptanceUnmet, createLogcatStats, forEachLogLine, isEvidenceLine, wrapUpVerdict } from "./draft_kill_timing_analysis.mjs";

const PKG = "build.raft.app.alpha";
const PID = 4242;
const line = (sec, pid, tag, message) => `${sec.toFixed(3).padStart(10)}  ${pid}  ${pid} I ${tag}: ${message}`;
const journal = (sec, costUs) => line(sec, PID, "RaftDraftTiming", `draft_journal_written path=sync costUs=${costUs} len=3 uptimeMs=${Math.round(sec * 1000)}`);
const config = { runsPerBucket: 1, fastRuns: 1, env: {} };

/** A finished run whose timing conditions all hold; only the logcat-derived statistics are missing. */
const timingsPass = () => ({
  runs: [
    { run: 1, group: "0.05", mode: "bucket", timing: "hit", pass: true },
    { run: 2, group: "fast 0.05", mode: "fast", timing: "hit", pass: true, typing_in_3_to_5_s: true },
    { run: 3, group: "send", mode: "send", timing: "hit", pass: true },
    { run: 4, group: "recents", mode: "recents", timing: "measured", pass: true, recents_swipe_killed: true },
  ],
  bucket_progress: { 0.05: { valid: 1, attempted: 1 } },
  fast_progress: { 0.05: { valid: 1, attempted: 1 } },
  apk: { file_sha256: "aa", installed_base_apk_sha256: "aa" },
});
const statsOf = (lines) => {
  const stats = createLogcatStats();
  lines.forEach((l) => stats.add(l));
  return stats.result();
};

test("the crash-journal gate sees the cost measured at wrap-up (timings pass -> run passes)", () => {
  const summary = timingsPass();
  // The bug this guards: deciding before the capture is read always finds no journal samples.
  assert.deepEqual(acceptanceUnmet(summary, config), ["no draft_journal_written lines: the crash journal's cost was not measured"]);
  wrapUpVerdict(summary, statsOf([journal(1, 120), journal(2, 300)]), config);
  assert.equal(summary.result, "pass");
  assert.deepEqual(summary.unmet, []);
  assert.equal(summary.journal_write_cost.samples, 2);
  assert.equal(summary.journal_write_cost.p95_us, 300);
});

test("the crash-journal gate still fails a slow journal, an unread capture and keeps a step failure", () => {
  const slow = wrapUpVerdict(timingsPass(), statsOf([journal(1, 1000)]), config);
  assert.equal(slow.result, "fail");
  assert.deepEqual(slow.unmet, ["crash-journal write p95 1000 µs is not below 1000 µs"]);

  const unread = wrapUpVerdict(timingsPass(), null, config);
  assert.equal(unread.result, "fail");
  assert.deepEqual(unread.unmet, ["no draft_journal_written lines: the crash journal's cost was not measured"]);

  const stepFailed = wrapUpVerdict({ ...timingsPass(), result: "fail", failed_step: "kill-runs" }, statsOf([journal(1, 10)]), config);
  assert.equal(stepFailed.result, "fail");
  assert.equal(stepFailed.failed_step, "kill-runs");
  assert.equal(stepFailed.journal_write_cost.samples, 1);
});

/** fs whose readSync records every requested length. */
const recordingFs = (requests) => ({ ...fs, readSync: (fd, buffer, offset, length, position) => (requests.push(length), fs.readSync(fd, buffer, offset, length, position)) });
const tmpFile = (t, content) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "draft-kill-wrap-up-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, "logcat.txt");
  fs.writeFileSync(file, content);
  return file;
};

test("the logcat is read in bounded chunks; lines split across reads (also inside a UTF-8 character) stay whole", (t) => {
  const lines = ["first line", "zweite — ünïcödé ✓", "", "third\r", "x".repeat(40)];
  const file = tmpFile(t, `skipped\n${lines.join("\n")}\nunterminated`);
  const requests = [];
  const seen = [];
  const read = forEachLogLine(file, "skipped\n".length, (l) => seen.push(l), { chunkBytes: 5, maxLineBytes: 30, fsImpl: recordingFs(requests) });
  assert.deepEqual(seen, ["first line", "zweite — ünïcödé ✓", "", "third", "x".repeat(30), "unterminated"]);
  assert.equal(read.lines, 6);
  assert.equal(read.truncated, 1);
  assert.ok(requests.length > 10 && requests.every((n) => n === 5), `reads: ${requests}`);
});

test("wrap-up over a logcat far larger than the heap finishes with bounded memory", (t) => {
  // 96 MiB of framework chatter with the app's lines spread through it. With a 32 MiB old-space limit the
  // former whole-file read (one Buffer -> one string -> split) cannot complete.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "draft-kill-wrap-up-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, "logcat.txt");
  const noise = `${line(1, 900, "SurfaceFlinger", "frame ".repeat(14))}\n`;
  const block = Buffer.from(noise.repeat(Math.floor((1 << 20) / noise.length)));
  const fd = fs.openSync(file, "w");
  for (let i = 0; i < 96; i += 1) {
    fs.writeSync(fd, block);
    fs.writeSync(fd, `${journal(i + 1, 100 + i)}\n`);
  }
  fs.closeSync(fd);
  const moduleUrl = new URL("./draft_kill_timing_analysis.mjs", import.meta.url).href;
  const child = `
    import { createLogcatStats, forEachLogLine } from ${JSON.stringify(moduleUrl)};
    const stats = createLogcatStats();
    const read = forEachLogLine(process.argv[1], 0, (l) => stats.add(l));
    const used = process.memoryUsage();
    console.log(JSON.stringify({ read, journal: stats.result().journal_write_cost, heapUsed: used.heapUsed, rss: used.rss }));
  `;
  const run = spawnSync(process.execPath, ["--max-old-space-size=32", "--input-type=module", "-e", child, file], { encoding: "utf8" });
  assert.equal(run.status, 0, `wrap-up child failed: ${run.stderr.slice(-500)}`);
  const out = JSON.parse(run.stdout);
  assert.ok(out.read.bytes > 96 * (1 << 20) * 0.99, `read ${out.read.bytes} bytes`);
  assert.equal(out.journal.samples, 96);
  assert.equal(out.journal.max_us, 195);
  assert.ok(out.heapUsed < 32 * (1 << 20), `heap ${out.heapUsed}`);
  assert.ok(out.rss < 96 * (1 << 20), `rss ${out.rss}`);
});

test("only allow-listed lines become evidence: no other app, framework chatter or credentials", () => {
  const raw = [
    line(1, PID, "RaftDraftTiming", "change_received seq=1 len=1 uptimeMs=1000"),
    line(1.1, 9000, "RaftDraftTimingProbe", "run=1 pid_gone polls=3"),
    line(2, 600, "ActivityManager", `Killing ${PID}:${PKG}/u0a190 (adj 0): stop ${PKG}`),
    line(2, 600, "ActivityManager", "Killing 77:com.other/u0a1 (adj 0): stop com.other"),
    line(2.1, 600, "am_proc_died", `[0,${PID},${PKG},0,17]`),
    line(2.2, 600, "libprocessgroup", "Successfully killed process cgroup uid 10190 pid 4242 in 3ms"),
    line(3, PID, "OkHttp", "Authorization: Bearer secret-token"),
    line(3, PID, "chromium", "login code 123456"),
    line(4, PID, "AndroidRuntime", "FATAL EXCEPTION: main"),
  ];
  assert.deepEqual(raw.filter((l) => isEvidenceLine(l, PKG)), [raw[0], raw[1], raw[2], raw[4], raw[5], raw[8]]);
});
