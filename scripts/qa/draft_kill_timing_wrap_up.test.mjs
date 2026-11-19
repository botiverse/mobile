// Wrap-up of scripts/qa/android_draft_kill_timing.mjs: the verdict is decided after the whole-run logcat
// statistics exist, the raw logcat is read in bounded chunks, and only filtered lines become evidence.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { acceptanceUnmet, channelActivityReport, creditedBucket, errorBodySummary, queryWithReauth, sendDeliveryCount, createLogcatStats, forEachLogLine, isEvidenceLine, parseMessagesResponse, wrapUpVerdict } from "./draft_kill_timing_analysis.mjs";

const PKG = "build.raft.app.alpha";
const PID = 4242;
const line = (sec, pid, tag, message) => `${sec.toFixed(3).padStart(10)}  ${pid}  ${pid} I ${tag}: ${message}`;
const written = (sec, costUs, seq = 1, pid = PID) => line(sec, pid, "RaftDraftTiming", `draft_written seq=${seq} len=3 path=background costUs=${costUs} uptimeMs=${Math.round(sec * 1000)}`);
const change = (sec, seq, pid = PID) => line(sec, pid, "RaftDraftTiming", `change_received seq=${seq} len=3 uptimeMs=${Math.round(sec * 1000)}`);
/** One typed edit as the app logs it: change_received (the arrival), then the draft write of it with its own time. */
const edit = (sec, seq, costUs, pid = PID) => [change(sec, seq, pid), written(sec + 0.02, costUs, seq, pid)];
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

test("the write latency is measured at wrap-up (timings pass -> run passes) and only reported", () => {
  const summary = timingsPass();
  // The bug this guards: deciding before the capture is read always finds no latency samples.
  assert.deepEqual(acceptanceUnmet(summary, config), ["no change_received -> draft_written pairs: the write latency was not measured"]);
  wrapUpVerdict(summary, statsOf([...edit(1, 1, 120), ...edit(2, 2, 3300)]), config);
  assert.equal(summary.result, "pass");
  assert.deepEqual(summary.unmet, []);
  assert.equal(summary.draft_write_cost.samples, 2);
  assert.equal(summary.write_deadline.deadline_ms, 175);
  assert.equal(summary.write_deadline.measured_change_to_written_p95_ms, 20);
  assert.equal(summary.write_deadline.measured_write_cost_p95_us, 3300);
});

test("a change -> written p95 above the 135 ms budget is unmet; the deadline stays 175 ms", () => {
  const slow = [change(1, 1), written(1.2, 300, 1), change(2, 2), written(2.136, 300, 2)];
  const r = wrapUpVerdict(timingsPass(), statsOf(slow), config);
  assert.equal(r.result, "fail");
  assert.deepEqual(r.unmet, ["change -> draft_written p95 200 ms exceeds the 135 ms write budget"]);
  assert.equal(r.write_deadline.deadline_ms, 175);
  // At the budget exactly: met.
  assert.equal(wrapUpVerdict(timingsPass(), statsOf([change(1, 1), written(1.135, 300, 1)]), config).result, "pass");
});

test("an unread capture fails, a step failure is kept", () => {
  const unread = wrapUpVerdict(timingsPass(), null, config);
  assert.equal(unread.result, "fail");
  assert.deepEqual(unread.unmet, ["no change_received -> draft_written pairs: the write latency was not measured"]);
  assert.equal(unread.write_deadline.deadline_ms, 175);

  const stepFailed = wrapUpVerdict({ ...timingsPass(), result: "fail", failed_step: "kill-runs" }, statsOf(edit(1, 1, 10)), config);
  assert.equal(stepFailed.result, "fail");
  assert.equal(stepFailed.failed_step, "kill-runs");
  assert.equal(stepFailed.draft_write_cost.samples, 1);
});

test("a kill run that lost text older than the deadline fails the round; younger text may go", () => {
  const killRun = (restored, ageOfLast) => ({
    run: 9, group: "D=0.1", mode: "bucket", timing: "hit", pass: restored === 6,
    typed_len: 6, restored_len: restored, restored_is_prefix: true, written_len_before_kill: 5,
    changes_before_kill: [{ seq: 1, len: 5, age_ms: 400 }, { seq: 2, len: 6, age_ms: ageOfLast }],
  });
  // Deadline 40 + 135 ms, whatever the writes below measured.
  const lines = [...edit(1, 1, 300), ...edit(2, 2, 900)];
  const young = wrapUpVerdict({ ...timingsPass(), runs: [...timingsPass().runs, killRun(5, 174)] }, statsOf(lines), config);
  assert.equal(young.result, "pass", JSON.stringify(young.unmet));
  assert.equal(young.runs.at(-1).survival, "lost 1 char(s) younger than 175 ms");

  const old = wrapUpVerdict({ ...timingsPass(), runs: [...timingsPass().runs, killRun(5, 175)] }, statsOf(lines), config);
  assert.equal(old.result, "fail");
  assert.deepEqual(old.unmet, ["run 9 (D=0.1): not restored as expected"]);
  assert.match(old.runs.at(-1).survival, /lost text older than 175 ms \(kept len 5, required 6\)/);

  // Home: one lost char fails even though it was younger than the deadline.
  const home = wrapUpVerdict({ ...timingsPass(), runs: [...timingsPass().runs, { ...killRun(5, 30), group: "c1: Home, kill", mode: "home", requires_full_text: true }] }, statsOf(lines), config);
  assert.equal(home.result, "fail");
  assert.deepEqual(home.unmet, ["run 9 (c1: Home, kill): not restored as expected"]);
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
    fs.writeSync(fd, `${edit(i + 1, i + 1, 100 + i).join("\n")}\n`);
  }
  fs.closeSync(fd);
  const moduleUrl = new URL("./draft_kill_timing_analysis.mjs", import.meta.url).href;
  const child = `
    import { createLogcatStats, forEachLogLine } from ${JSON.stringify(moduleUrl)};
    const stats = createLogcatStats();
    const read = forEachLogLine(process.argv[1], 0, (l) => stats.add(l));
    const used = process.memoryUsage();
    console.log(JSON.stringify({ read, cost: stats.result().draft_write_cost, heapUsed: used.heapUsed, rss: used.rss }));
  `;
  const run = spawnSync(process.execPath, ["--max-old-space-size=32", "--input-type=module", "-e", child, file], { encoding: "utf8" });
  assert.equal(run.status, 0, `wrap-up child failed: ${run.stderr.slice(-500)}`);
  const out = JSON.parse(run.stdout);
  assert.ok(out.read.bytes > 96 * (1 << 20) * 0.99, `read ${out.read.bytes} bytes`);
  assert.equal(out.cost.samples, 96);
  assert.equal(out.cost.max_us, 195);
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

test("a kill run counts for a bucket only when the whole exit interval lies in an enabled window", () => {
  const round = [0.1, 0.3, 0.7];
  assert.equal(creditedBucket(80, 120, 0.1, round), 0.1);
  // Partly outside the aimed window: no credit there; credit elsewhere only if wholly inside another window.
  assert.equal(creditedBucket(60, 120, 0.1, round), null);
  assert.equal(creditedBucket(260, 340, 0.1, round), 0.3);
  assert.equal(creditedBucket(120, 260, 0.3, round), null);
  // 50 ms is not in this round: an interval inside [20, 80] counts nowhere, even though it fits 0.05.
  assert.equal(creditedBucket(30, 60, 0.1, round), null);
  // Inside both 0.05 and 0.1 windows ([70, 80]): credited to 0.1, the aimed and enabled one.
  assert.equal(creditedBucket(72, 78, 0.1, round), 0.1);
  assert.equal(creditedBucket(null, 100, 0.1, round), null);
});

test("the send query re-authenticates once on 401 and retries only the query", async () => {
  const answers = [
    { at: "t1", http: 401, ok: false, problem: "HTTP 401", body_summary: { error: "token expired" }, message: null },
    { at: "t2", http: 200, ok: true, matches: 1, message: { id: "m1" } },
    { at: "t3", http: 401, ok: false, problem: "HTTP 401", message: null },
  ];
  let queries = 0;
  let reauths = 0;
  const run = async () => answers[queries++];
  const state = { reauthed: false };
  const first = await queryWithReauth("before relaunch", run, async () => { reauths += 1; }, state);
  assert.equal(queries, 2);
  assert.equal(reauths, 1);
  assert.deepEqual(first.records.map((r) => [r.phase, r.http, r.matches]), [["before relaunch", 401, 0], ["before relaunch: retry after re-auth", 200, 1]]);
  assert.deepEqual(first.records[0].body_summary, { error: "token expired" });
  assert.equal(state.reauth, "ok");
  // A later 401 never signs in again.
  const later = await queryWithReauth("after relaunch", run, async () => { reauths += 1; }, state);
  assert.equal(reauths, 1);
  assert.equal(later.records.length, 1);

  // A failed re-auth: recorded, no retry.
  const failing = { reauthed: false };
  let n = 0;
  const r = await queryWithReauth("before relaunch", async () => (n++, answers[0]), async () => { throw new Error("HTTP 403"); }, failing);
  assert.equal(n, 1);
  assert.equal(r.records.length, 1);
  assert.deepEqual(failing, { reauthed: true, reauth: "failed", reauth_error: "HTTP 403" });
});

test("delivery must be exactly once; a duplicate fails, not found or no answer is unknown", () => {
  const q = (phase, ok, matches) => ({ phase, at: `${phase}-${matches}`, ok, matches, http: ok ? 200 : 401 });
  assert.deepEqual(sendDeliveryCount([q("before relaunch", false, 0), q("before relaunch: retry after re-auth", true, 1)]), { delivered: "exactly once", matches: 1 });
  assert.deepEqual(sendDeliveryCount([q("before relaunch", true, 0), q("after relaunch", true, 2)]), { delivered: "more than once", matches: 2 });
  assert.equal(sendDeliveryCount([q("before relaunch", true, 0), q("after relaunch", true, 0)]).delivered, "unknown");
  assert.equal(sendDeliveryCount([q("before relaunch", false, 0)]).delivered, "unknown");
});

test("error bodies are summarised without tokens", () => {
  assert.deepEqual(errorBodySummary({ error: "unauthorized", code: "TOKEN_EXPIRED", accessToken: "secret", nested: { a: 1 } }), { error: "unauthorized", code: "TOKEN_EXPIRED" });
  assert.equal(errorBodySummary(null), null);
  assert.equal(errorBodySummary([1]), null);
  assert.equal(errorBodySummary({ message: "x".repeat(500) }).message.length, 120);
});

test("the recents gate applies only when the round runs the recents swipe", () => {
  const noRecents = { ...timingsPass(), runs: timingsPass().runs.filter((r) => r.mode !== "recents") };
  assert.equal(wrapUpVerdict(structuredClone(noRecents), statsOf(edit(1, 1, 100)), { ...config, recents: false }).result, "pass");
  assert.equal(wrapUpVerdict(structuredClone(noRecents), statsOf(edit(1, 1, 100)), config).result, "fail");
});
