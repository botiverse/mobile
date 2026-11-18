// Wrap-up of scripts/qa/android_draft_kill_timing.mjs: the verdict is decided after the whole-run logcat
// statistics exist, the raw logcat is read in bounded chunks, and only filtered lines become evidence.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { acceptanceUnmet, channelActivityReport, createLogcatStats, forEachLogLine, isEvidenceLine, journalCoverage, parseMessagesResponse, wrapUpVerdict } from "./draft_kill_timing_analysis.mjs";

const PKG = "build.raft.app.alpha";
const PID = 4242;
const line = (sec, pid, tag, message) => `${sec.toFixed(3).padStart(10)}  ${pid}  ${pid} I ${tag}: ${message}`;
const journal = (sec, costUs, seq = 1, pid = PID) => line(sec, pid, "RaftDraftTiming", `draft_journal_written seq=${seq} path=sync costUs=${costUs} len=3 uptimeMs=${Math.round(sec * 1000)}`);
const journalFailed = (sec, seq, pid = PID) => line(sec, pid, "RaftDraftTiming", `draft_journal_write_failed seq=${seq} len=3 uptimeMs=${Math.round(sec * 1000)}`);
const change = (sec, seq, pid = PID) => line(sec, pid, "RaftDraftTiming", `change_received seq=${seq} len=3 uptimeMs=${Math.round(sec * 1000)}`);
/** One typed edit as the app logs it: change_received (the arrival), then its journal cost line, same seq. */
const edit = (sec, seq, costUs, pid = PID) => [change(sec, seq, pid), journal(sec, costUs, seq, pid)];
const config = { runsPerBucket: 1, fastRuns: 1, env: {} };
const RUN_TAG = "dk12345";
const ourMessage = (i) => ({ id: `m${i}`, content: `${RUN_TAG} send ${i}`, senderId: "qa", createdAt: `2026-11-18T10:00:0${i}Z` });
const cleanChannel = () => channelActivityReport({ status: 200, rows: [ourMessage(1), ourMessage(2)], limit: 100, runTag: RUN_TAG, selfId: "qa" });

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
  channel_activity: cleanChannel(),
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
  wrapUpVerdict(summary, statsOf([...edit(1, 1, 120), ...edit(2, 2, 300)]), config);
  assert.equal(summary.result, "pass");
  assert.deepEqual(summary.unmet, []);
  assert.equal(summary.journal_write_cost.samples, 2);
  assert.equal(summary.journal_write_cost.p95_us, 300);
});

test("the crash-journal gate still fails a slow journal, an unread capture and keeps a step failure", () => {
  const slow = wrapUpVerdict(timingsPass(), statsOf(edit(1, 1, 1000)), config);
  assert.equal(slow.result, "fail");
  assert.deepEqual(slow.unmet, ["crash-journal write p95 1000 µs is not below 1000 µs"]);

  const unread = wrapUpVerdict(timingsPass(), null, config);
  assert.equal(unread.result, "fail");
  assert.deepEqual(unread.unmet, ["no draft_journal_written lines: the crash journal's cost was not measured"]);

  const stepFailed = wrapUpVerdict({ ...timingsPass(), result: "fail", failed_step: "kill-runs" }, statsOf(edit(1, 1, 10)), config);
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

test("only allow-listed lines of the app's own processes become evidence", () => {
  const appPids = new Set([PID]);
  const kept = [
    line(1, PID, "RaftDraftTiming", "change_received seq=1 len=1 uptimeMs=1000"),
    line(1.1, 9000, "RaftDraftTimingProbe", "run=1 pid_gone polls=3"),
    line(2, 600, "ActivityManager", `Killing ${PID}:${PKG}/u0a190 (adj 0): stop ${PKG}`),
    line(2.1, 600, "am_proc_died", `[0,${PID},${PKG},0,17]`),
    line(2.2, 600, "libprocessgroup", `Successfully killed process cgroup uid 10190 pid ${PID} in 3ms`),
    line(4, PID, "AndroidRuntime", "FATAL EXCEPTION: main"),
    line(4, PID, "AndroidRuntime", `Process: ${PKG}, PID: ${PID}`),
  ];
  const dropped = [
    line(2, 600, "ActivityManager", "Killing 77:com.other/u0a1 (adj 0): stop com.other"),
    line(2.2, 600, "libprocessgroup", "Successfully killed process cgroup uid 10077 pid 77 in 2ms"),
    line(2.3, 600, "libprocessgroup", "Failed to kill process cgroup uid 10077 pid 77 in 251ms, 1 processes remain"),
    line(4, 77, "AndroidRuntime", "FATAL EXCEPTION: main"),
    line(4, 77, "AndroidRuntime", "Process: com.other, PID: 77"),
    line(4, PID, "AndroidRuntime", "java.lang.IllegalStateException: token=secret"),
    line(3, PID, "OkHttp", "Authorization: Bearer secret-token"),
    line(3, PID, "chromium", "login code 123456"),
  ];
  assert.deepEqual([...kept, ...dropped].filter((l) => isEvidenceLine(l, PKG, appPids)), kept);
});

test("the channel concurrency check gates the run: other activity, a failed or a full-page read is unconfirmed", () => {
  const verdict = (channel_activity) => wrapUpVerdict({ ...timingsPass(), channel_activity }, statsOf(edit(1, 1, 100)), config);

  assert.equal(verdict(cleanChannel()).result, "pass");
  assert.equal(cleanChannel().coverage.complete, true);

  const other = verdict(channelActivityReport({ status: 200, rows: [ourMessage(1), { id: "x", content: "hello", senderId: "someone" }], limit: 100, runTag: RUN_TAG, selfId: "qa" }));
  assert.equal(other.result, "unconfirmed");
  assert.match(other.unconfirmed.join("\n"), /other activity in the run's channel: 1 item/);

  const unauthorized = verdict(channelActivityReport({ status: 401, limit: 100, runTag: RUN_TAG, selfId: "qa" }));
  assert.equal(unauthorized.result, "unconfirmed");
  assert.match(unauthorized.unconfirmed.join("\n"), /HTTP 401/);

  // A full page: older items may be cut off, so even an all-ours page is not "no other activity".
  const full = channelActivityReport({ status: 200, rows: Array.from({ length: 5 }, (_, i) => ourMessage(i)), limit: 5, runTag: RUN_TAG, selfId: "qa" });
  assert.equal(full.other_activity, 0);
  assert.equal(full.coverage.complete, false);
  assert.equal(full.coverage.oldest_created_at, "2026-11-18T10:00:00Z");
  assert.equal(verdict(full).result, "unconfirmed");

  assert.equal(verdict(undefined).result, "unconfirmed");
  assert.equal(verdict({ error: "fetch failed" }).result, "unconfirmed");
});

test("journal coverage pairs by (process, seq): a kill during the last journal write is not a missing sample", () => {
  assert.deepEqual(journalCoverage({ edits: 200, paired: 198, failures: 0, missing: 2 }), { edits: 200, paired: 198, failures: 0, interrupted: 0, missing: 2, unpaired: 0, allowed_missing: 2, covered: true });
  assert.equal(journalCoverage({ edits: 200, paired: 197, failures: 0, missing: 3 }).covered, false);
  assert.equal(journalCoverage({ edits: 0, paired: 0, failures: 0, missing: 0 }).covered, false);

  // Killed during the journal write of its last edit: the marker is there, the cost line is not.
  const killed = wrapUpVerdict(timingsPass(), statsOf([...edit(1, 1, 100), ...edit(2, 2, 110), change(3, 3), ...edit(4, 1, 90, 5000)]), config);
  assert.equal(killed.result, "pass", JSON.stringify(killed.unconfirmed));
  assert.deepEqual(killed.journal_coverage, { edits: 4, paired: 3, failures: 0, interrupted: 1, missing: 0, unpaired: 0, allowed_missing: 0, covered: true });
});

test("journal coverage: a missing sample mid-run is unconfirmed even when extra lines of other changes make the totals match", () => {
  // Edit 2 has no cost line; edit 1 logged two and a line of seq 9 has no edit. Totals: 3 edits, 3 cost lines.
  const lines = [change(1, 1), journal(1, 100, 1), journal(1.1, 100, 1), change(2, 2), journal(2.5, 100, 9), ...edit(3, 3, 100)];
  const masked = wrapUpVerdict(timingsPass(), statsOf(lines), config);
  assert.equal(masked.result, "unconfirmed");
  assert.deepEqual(masked.journal_coverage, { edits: 3, paired: 2, failures: 0, interrupted: 0, missing: 1, unpaired: 1, allowed_missing: 0, covered: false });
  assert.match(masked.unconfirmed.join("\n"), /2 paired of 3 edits, 1 missing/);

  // The same seq in another process is another change.
  const otherProcess = statsOf([...edit(1, 1, 100), change(2, 2), journal(2, 100, 2, 5000), ...edit(3, 3, 100)]).journal_coverage;
  assert.equal(otherProcess.missing, 1);
  assert.equal(otherProcess.unpaired, 1);
});

test("a failed journal write fails the run, paired or not", () => {
  const failed = wrapUpVerdict(timingsPass(), statsOf([...edit(1, 1, 100), change(2, 2), journalFailed(2, 2), ...edit(3, 3, 100)]), config);
  assert.equal(failed.result, "fail");
  assert.deepEqual(failed.unmet, ["crash-journal write failed 1 time(s) (draft_journal_write_failed)"]);
  assert.equal(failed.journal_coverage.missing, 0);
});

test("a channel read counts only as a messages array: empty body, malformed JSON and other shapes are unavailable", () => {
  const read = (status, json, parseError) => {
    const r = parseMessagesResponse(status, json, parseError);
    return channelActivityReport({ ...r, limit: 100, runTag: RUN_TAG, selfId: "qa" });
  };
  const emptyBody = read(200, null, true);
  // raftJson reads the body as text: an empty one and malformed JSON both arrive as json=null, parseError=true.
  const malformed = read(200, null, true);
  const wrongShape = read(200, { items: [] });
  const nullMessages = read(200, { messages: null });
  for (const r of [emptyBody, malformed, wrongShape, nullMessages]) {
    assert.equal(r.other_activity, null);
    assert.equal(r.coverage.complete, false);
    assert.equal(wrapUpVerdict({ ...timingsPass(), channel_activity: r }, statsOf(edit(1, 1, 100)), config).result, "unconfirmed");
  }
  assert.match(emptyBody.coverage.reason, /empty or unparsable body/);
  assert.match(wrongShape.coverage.reason, /without a messages array/);

  // Only a real (empty) array is an empty channel.
  for (const body of [[], { messages: [] }]) {
    const empty = read(200, body);
    assert.equal(empty.other_activity, 0);
    assert.equal(empty.coverage.complete, true);
    assert.equal(wrapUpVerdict({ ...timingsPass(), channel_activity: empty }, statsOf(edit(1, 1, 100)), config).result, "pass");
  }
  assert.deepEqual(parseMessagesResponse(200, [], false), { status: 200, ok: true, rows: [] });
  assert.equal(parseMessagesResponse(500, [], false).ok, false);
});
