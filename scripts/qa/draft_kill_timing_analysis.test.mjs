import test from "node:test";
import assert from "node:assert/strict";
import { WRITE_BUDGET_MS, WRITE_INTERVAL_MS, applyWriteDeadline, nearestRank, draftWriteCostStats, parseTimingMessage, requiredLenAtKill, typedBeforeKill, writeDeadline, writePathCounts, writeLatencyStats, analyzeKillRun, measuredBucket, summarizeByMeasuredBucket, exitInterval, parseEventPid, parseKillLine, parseLogcatLine, summarize, markdownTable } from "./draft_kill_timing_analysis.mjs";

const PKG = "build.raft.app.alpha";
const PID = 4242;
const line = (sec, pid, tag, message) => `${sec.toFixed(3).padStart(10)}  ${pid}  ${pid} I ${tag}: ${message}`;
const change = (sec, seq, len, pid = PID) => line(sec, pid, "RaftDraftTiming", `change_received seq=${seq} len=${len} uptimeMs=${Math.round(sec * 1000)}`);
const written = (sec, seq, len, pid = PID, costUs = null) =>
  line(sec, pid, "RaftDraftTiming", `draft_written seq=${seq} len=${len} path=background${costUs == null ? "" : ` costUs=${costUs}`} uptimeMs=${Math.round(sec * 1000)}`);
const killing = (sec, pid = PID, pkg = PKG) => line(sec, 600, "ActivityManager", `Killing ${pid}:${pkg}/u0a190 (adj 0): stop ${pkg} due to from pid 9000`);
const amKill = (sec, pid = PID) => line(sec, 600, "am_kill", `[0,${pid},${PKG},0,stop ${PKG} due to from pid 9000]`);
const procDied = (sec, pid = PID) => line(sec, 600, "am_proc_died", `[0,${pid},${PKG},0,17]`);
const gone = (sec, run = 1) => line(sec, 9001, "RaftDraftTimingProbe", `run=${run} pid_gone polls=3`);
const probe = (sec, run, what) => line(sec, 9000, "RaftDraftTimingProbe", `run=${run} ${what}`);
const base = { pkg: PKG, oldPid: PID, runIndex: 1, pidGone: true };

test("parses logcat -v monotonic lines, AM kill lines and events-buffer pids", () => {
  const parsed = parseLogcatLine(change(1234.5, 7, 12));
  assert.equal(parsed.tag, "RaftDraftTiming");
  assert.equal(parsed.tsMs, 1234500);
  assert.equal(parseKillLine(parseLogcatLine(killing(1, 77)).message, PKG), 77);
  // Another process of the app (`pkg:service`) or another app is not the main process.
  assert.equal(parseKillLine(`Killing 78:${PKG}:remote/u0a190 (adj 0): stop`, PKG), null);
  assert.equal(parseKillLine("Killing 79:com.other/u0a1 (adj 0): stop", PKG), null);
  assert.deepEqual(parseEventPid(parseLogcatLine(amKill(1, 80)).message), { pid: 80, name: PKG });
});

test("the exit interval is the latest pre-signal line to the earliest evidence of death", () => {
  const parsed = [killing(10.0), amKill(10.002), gone(10.009), procDied(10.03)].map(parseLogcatLine);
  const exit = exitInterval(parsed, { pkg: PKG, oldPid: PID, runIndex: 1 });
  assert.equal(exit.lower_ms, 10002);
  assert.equal(exit.upper_ms, 10009);
  assert.equal(exit.width_ms, 7);
  assert.equal(exit.lower_source, "am_kill");
  assert.equal(exit.upper_source, "pid_gone poll");
  // Another run's poller line is not evidence for this run.
  const other = exitInterval([killing(10.0), gone(10.005, 2), procDied(10.03)].map(parseLogcatLine), { pkg: PKG, oldPid: PID, runIndex: 1 });
  assert.equal(other.upper_ms, 10030);
  const cgroup = exitInterval([killing(10.0), line(10.004, 519, "libprocessgroup", `Successfully killed process cgroup uid 10192 pid ${PID} in 3ms`), procDied(10.03)].map(parseLogcatLine), { pkg: PKG, oldPid: PID, runIndex: 1 });
  assert.equal(cgroup.upper_ms, 10004);
  assert.equal(cgroup.upper_source, "cgroup empty");
});

test("a run is a hit only when the whole exit interval is inside the bucket", () => {
  const hit = analyzeKillRun({
    ...base, expectedLen: 12, delaySec: 0.05,
    lines: [change(100.0, 1, 11), written(100.002, 1, 11), change(100.5, 2, 12), written(100.503, 2, 12), killing(100.556), amKill(100.557), gone(100.565)],
  });
  assert.equal(hit.timing, "hit");
  assert.equal(hit.delta_lower_ms, 57);
  assert.equal(hit.delta_upper_ms, 65);
  assert.equal(hit.exit_width_ms, 8);
  assert.equal(hit.written_before_kill, true);
  assert.equal(hit.write_after_change_ms, 3);
  assert.equal(hit.clock_skew_ms, 0);

  // The lower bound is in the 0.05 bucket but the process may have lived past 100 ms.
  const straddle = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.05, lines: [change(10, 1, 5), killing(10.09), gone(10.12)] });
  assert.equal(straddle.timing, "unconfirmed");
  assert.match(straddle.reason, /not inside bucket/);
});

test("missing bounds, a changed process, a surviving pid or a skewed clock are unconfirmed", () => {
  const cases = [
    [{ lines: [change(10, 1, 5), killing(10.05, 1111), gone(10.06)] }, /no pre-signal force-stop kill line/],
    [{ lines: [change(10, 1, 5), killing(10.05)] }, /not bounded above/],
    [{ lines: [change(10, 1, 5), killing(10.05), gone(10.06)], samePid: false }, /not the same process/],
    [{ lines: [change(10, 1, 5), killing(10.05), gone(10.06)], pidGone: false }, /still alive/],
    [{ lines: [line(10, PID, "RaftDraftTiming", "change_received seq=1 len=5 uptimeMs=9000"), killing(10.05), gone(10.06)] }, /clocks disagree/],
    [{ lines: [change(10, 1, 4), killing(10.05), gone(10.06)] }, /no change_received/],
    [{ lines: [killing(10.05), change(10.06, 2, 5), gone(10.07)] }, /only after the kill signal/],
  ];
  for (const [input, reason] of cases) {
    const r = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.05, ...input });
    assert.equal(r.timing, "unconfirmed");
    assert.match(r.reason, reason);
  }
});

test("a write after the pre-signal line does not count as written before the kill", () => {
  const r = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.05, lines: [change(10, 1, 5), killing(10.05), written(10.051, 1, 5), gone(10.06)] });
  assert.equal(r.written_before_kill, false);
});

test("the send regression is measured from its tap probe, not bucketed", () => {
  const r = analyzeKillRun({ ...base, expectedLen: 5, delaySec: null, refProbe: "send_tap", lines: [probe(20, 1, "send_tap"), killing(20.11), gone(20.115)] });
  assert.equal(r.timing, "measured");
  assert.equal(r.delta_lower_ms, 110);
  assert.equal(r.delta_upper_ms, 115);
});

test("summary counts hits, unconfirmed and passes per group and renders a table", () => {
  const runs = [
    { group: "D=0.05", timing: "hit", pass: true, written_before_kill: true, delta_upper_ms: 60, exit_width_ms: 5, expected_desc: "len 12", observed_desc: "len 12" },
    { group: "D=0.05", timing: "unconfirmed", pass: true, written_before_kill: false, delta_upper_ms: null, reason: "x", expected_desc: "len 12", observed_desc: "len 12" },
    { group: "D=0.1", timing: "hit", pass: false, written_before_kill: true, delta_upper_ms: 110, exit_width_ms: 9, expected_desc: "len 12", observed_desc: "len 11" },
  ];
  const s = summarize(runs);
  assert.deepEqual(s.find((g) => g.group === "D=0.05"), {
    group: "D=0.05", runs: 2, hits: 1, unconfirmed: 1, excluded: 0, restored: 2, restored_when_hit: 1, written_before_kill: 1,
    delta_upper_min_ms: 60, delta_upper_median_ms: 60, delta_upper_max_ms: 60, exit_width_median_ms: 5, exit_width_max_ms: 5,
  });
  assert.equal(s.find((g) => g.group === "D=0.1").restored, 0);
  assert.match(markdownTable(runs, s), /\| D=0\.05 \| 2 \| 1 \| 1 \| 0 \| 2 \| 1 \| 1 \|/);
});

test("an exit that is not the force-stop is excluded, not a hit or a miss", () => {
  const dependency = line(10.03, 600, "ActivityManager", `Killing ${PID}:${PKG}/u0a192 (adj 0): depends on provider com.google.android.gms/.fonts.provider.FontsProvider in dying proc com.google.android.gms.persistent (adj -10000)`);
  const dep = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.05, lines: [change(10, 1, 5), dependency, procDied(10.04), killing(10.05), gone(10.06)] });
  assert.equal(dep.timing, "excluded");
  assert.match(dep.reason, /exit not by force-stop \(system kill: depends on provider/);
  const crash = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.05, lines: [change(10, 1, 5), line(10.02, PID, "AndroidRuntime", "FATAL EXCEPTION: main"), gone(10.06)] });
  assert.equal(crash.timing, "excluded");
  assert.match(crash.reason, /app crash/);
  // The force-stop's own lines still decide an ordinary run.
  const ok = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.05, lines: [change(10, 1, 5), killing(10.05), amKill(10.051), gone(10.06)] });
  assert.equal(ok.timing, "hit");
  assert.equal(summarize([{ group: "g", ...dep, pass: true }])[0].excluded, 1);
});

test("the measured bucket is the tightest window holding the whole interval", () => {
  assert.equal(measuredBucket(25, 41), 0.05);
  assert.equal(measuredBucket(12, 41), null);
  assert.equal(measuredBucket(75, 120), 0.1);
  assert.equal(measuredBucket(90, 160), null);
  assert.equal(measuredBucket(250, 330), 0.3);
  const r = analyzeKillRun({ ...base, expectedLen: 5, delaySec: 0.1, lines: [change(10, 1, 5), killing(10.025), gone(10.041)] });
  assert.equal(r.timing, "unconfirmed");
  assert.equal(r.measured_bucket, 0.05);
  assert.deepEqual(summarizeByMeasuredBucket([{ ...r, pass: true }]), [{ bucket: 0.05, samples: 1, restored: 1, written_before_kill: 0 }]);
});

test("the send regression has its own 100 ms window; a recents swipe may kill by remove task", () => {
  const slow = analyzeKillRun({ ...base, expectedLen: 5, delaySec: null, refProbe: "send_tap", window: { lo_ms: 0, hi_ms: 100 }, lines: [probe(20, 1, "send_tap"), killing(20.11), gone(20.115)] });
  assert.equal(slow.timing, "unconfirmed");
  assert.match(slow.reason, /not inside required \[0, 100\]/);
  const fast = analyzeKillRun({ ...base, expectedLen: 5, delaySec: null, refProbe: "send_tap", window: { lo_ms: 0, hi_ms: 100 }, lines: [probe(20, 1, "send_tap"), killing(20.06), gone(20.07)] });
  assert.equal(fast.timing, "hit");
  const removeTask = line(10.2, 600, "ActivityManager", `Killing ${PID}:${PKG}/u0a192 (adj 900): remove task`);
  const swipe = analyzeKillRun({ ...base, expectedLen: 5, delaySec: null, extraReasons: ["remove task"], lines: [change(10, 1, 5), removeTask, gone(10.21)] });
  assert.equal(swipe.timing, "measured");
  assert.equal(swipe.kill_reason, "remove task");
  // Without the swipe allowance the same exit is not this run's force-stop.
  assert.equal(analyzeKillRun({ ...base, expectedLen: 5, delaySec: null, lines: [change(10, 1, 5), removeTask, gone(10.21)] }).timing, "excluded");
});

test("write latency pairs each change with its own write", () => {
  const stats = writeLatencyStats([change(1, 1, 1), written(1.02, 1, 1), change(2, 2, 2), change(2.01, 3, 3), written(2.05, 3, 3), change(3, 4, 4)]);
  assert.deepEqual(stats, { samples: 2, min_ms: 20, p50_ms: 20, p95_ms: 40, p99_ms: 40, max_ms: 40 });
});

test("write paths are counted from the marker lines", () => {
  const l = (sec, msg) => line(sec, PID, "RaftDraftTiming", msg);
  const counts = writePathCounts([
    l(1, "draft_written seq=1 len=1 path=sync uptimeMs=1000"),
    l(2, "draft_written seq=2 len=2 path=background uptimeMs=2000"),
    l(3, "sync_write_fallback seq=3 len=3 uptimeMs=3000"),
    l(4, "draft_written seq=3 len=3 path=background uptimeMs=4000"),
    l(5, "draft_written seq=4 len=4 uptimeMs=5000"),
  ]);
  assert.deepEqual(counts, { sync: 1, background: 2, flush: 0, unlabeled: 1, sync_write_fallback: 1 });
});

test("draft_written lines carry the write's own time; lines of builds without it still parse", () => {
  assert.deepEqual(parseTimingMessage("draft_written seq=3 len=7 path=background costUs=1234 uptimeMs=99"), { kind: "draft_written", seq: 3, len: 7, path: "background", costUs: 1234, uptimeMs: 99 });
  assert.deepEqual(parseTimingMessage("draft_written seq=3 len=7 path=flush uptimeMs=99"), { kind: "draft_written", seq: 3, len: 7, path: "flush", costUs: null, uptimeMs: 99 });
  // The removed crash journal's line is not a timing line.
  assert.equal(parseTimingMessage("draft_journal_written seq=3 path=sync costUs=12 len=7 uptimeMs=99"), null);
});

test("draft write cost distribution comes from draft_written costUs, every path", () => {
  const l = (sec, msg) => line(sec, PID, "RaftDraftTiming", msg);
  const stats = draftWriteCostStats([
    l(1, "change_received seq=1 len=3 uptimeMs=1000"),
    l(1, "draft_written seq=1 len=3 path=background costUs=120 uptimeMs=1001"),
    l(2, "draft_written seq=2 len=4 path=flush costUs=800 uptimeMs=2000"),
    // No cost on the line (older build): counted as a write, not as a cost sample.
    l(3, "draft_written seq=3 len=5 path=background uptimeMs=3000"),
  ]);
  assert.deepEqual(stats, { samples: 2, min_us: 120, p50_us: 120, p95_us: 800, p99_us: 800, max_us: 800 });
});

test("percentiles are nearest-rank: rank ceil(q * n), so 1..100 gives p50/p95/p99 = 50/95/99", () => {
  const probe = Array.from({ length: 100 }, (_, i) => i + 1);
  assert.deepEqual([0.5, 0.95, 0.99, 1].map((q) => nearestRank(probe, q)), [50, 95, 99, 100]);
  assert.equal(nearestRank([7], 0.95), 7);
  assert.equal(nearestRank([1, 2], 0.5), 1);
  assert.equal(nearestRank([], 0.5), null);
  const stats = draftWriteCostStats(probe.map((v) => written(v, v, 1, PID, v)));
  assert.deepEqual([stats.p50_us, stats.p95_us, stats.p99_us, stats.max_us], [50, 95, 99, 100]);
});

test("the write deadline is a fixed 40 ms interval plus the 135 ms budget; measurements are only reported", () => {
  assert.equal(WRITE_INTERVAL_MS, 40);
  assert.equal(WRITE_BUDGET_MS, 135);
  const measured = writeDeadline({ samples: 20, p95_ms: 400 }, { samples: 20, p95_us: 90_000 });
  assert.equal(measured.deadline_ms, 175);
  assert.equal(measured.measured_change_to_written_p95_ms, 400);
  assert.equal(measured.measured_write_cost_p95_us, 90_000);
  // A slow or a fast or a missing measurement never moves it.
  assert.equal(writeDeadline({ samples: 20, p95_ms: 3 }).deadline_ms, 175);
  assert.equal(writeDeadline().deadline_ms, 175);
  assert.equal(writeDeadline().measured_change_to_written_p95_ms, null);
});

test("what was typed before the kill: the run's own changes with their age, and the longest written text", () => {
  const lines = [
    change(9, 1, 20), written(9.01, 1, 20), // the previous text, before the composer was cleared
    change(9.5, 2, 0),
    change(10, 3, 5), written(10.002, 3, 5),
    change(10.3, 4, 6),
    killing(10.36), gone(10.37),
    change(10.4, 5, 7), // after the signal: not part of what was typed before the kill
  ];
  const typed = typedBeforeKill(lines, PID, 10360);
  assert.deepEqual(typed.changes_before_kill, [{ seq: 3, len: 5, age_ms: 360 }, { seq: 4, len: 6, age_ms: 60 }]);
  assert.equal(typed.written_len_before_kill, 5);
  assert.equal(typedBeforeKill(lines, PID, null).changes_before_kill, null);
  // Another process's lines never count.
  assert.deepEqual(typedBeforeKill(lines.map((l) => l.replace(` ${PID}  ${PID} `, " 77  77 ")), PID, 10360).changes_before_kill, []);
  const run = analyzeKillRun({ ...base, expectedLen: 6, delaySec: 0.05, lines: lines.slice(5, 8), history: lines });
  assert.equal(run.timing, "hit");
  assert.deepEqual(run.changes_before_kill, typed.changes_before_kill);
});

test("required text at the kill: every change older than the deadline and anything written before it", () => {
  const run = { typed_len: 6, written_len_before_kill: 4, changes_before_kill: [{ len: 4, age_ms: 300 }, { len: 5, age_ms: 50 }, { len: 6, age_ms: 20 }] };
  assert.equal(requiredLenAtKill(run, 43), 5);
  assert.equal(requiredLenAtKill(run, 60), 4);
  assert.equal(requiredLenAtKill(run, 10), 6);
  // A write reported before the kill is required even when younger than the deadline.
  assert.equal(requiredLenAtKill({ ...run, written_len_before_kill: 6 }, 43), 6);
  // Kill time unknown: nothing proves a loss was inside the window, so the whole text is required.
  assert.equal(requiredLenAtKill({ ...run, changes_before_kill: null }, 43), 6);
});

test("each kill run is decided against the deadline: younger text may be lost, older text and other text never", () => {
  const changes = [{ len: 5, age_ms: 340 }, { len: 6, age_ms: 174 }];
  const summary = {
    // A huge measured write time does not widen the 175 ms deadline.
    write_latency: { samples: 10, p95_ms: 900 },
    draft_write_cost: { samples: 10, p95_us: 800_000 },
    runs: [
      { run: 1, typed_len: 6, restored_len: 6, restored_is_prefix: true, written_len_before_kill: 6, changes_before_kill: changes, pass: true },
      { run: 2, typed_len: 6, restored_len: 5, restored_is_prefix: true, written_len_before_kill: 5, changes_before_kill: changes, pass: false },
      { run: 3, typed_len: 6, restored_len: 5, restored_is_prefix: true, written_len_before_kill: 6, changes_before_kill: changes, pass: false },
      { run: 4, typed_len: 6, restored_len: 4, restored_is_prefix: true, written_len_before_kill: 4, changes_before_kill: changes, pass: false },
      { run: 5, typed_len: 6, restored_len: 6, restored_is_prefix: false, written_len_before_kill: 6, changes_before_kill: changes, pass: false },
      { run: 6, typed_len: 6, restored_len: 5, restored_is_prefix: true, written_len_before_kill: 5, changes_before_kill: null, pass: false },
      { run: 7, mode: "send", pass: true },
      // Home / wait 1 s: the whole text, even when the lost change is younger than the deadline.
      { run: 8, mode: "home", requires_full_text: true, typed_len: 6, restored_len: 5, restored_is_prefix: true, written_len_before_kill: 5, changes_before_kill: changes, pass: false },
      { run: 9, mode: "home", requires_full_text: true, typed_len: 6, restored_len: 6, restored_is_prefix: true, written_len_before_kill: 6, changes_before_kill: changes, pass: true },
    ],
  };
  assert.equal(applyWriteDeadline(summary).deadline_ms, 175);
  assert.deepEqual(summary.runs.map((r) => r.pass), [true, true, false, false, false, false, true, false, true]);
  assert.equal(summary.runs[1].survival, "lost 1 char(s) younger than 175 ms");
  assert.match(summary.runs[2].survival, /lost text older than 175 ms \(kept len 5, required 6\)/);
  assert.equal(summary.runs[7].survival, "lost text where the whole text is required (kept len 5 of 6)");
  assert.equal(summary.runs[7].required_len, 6);
  assert.match(summary.runs[3].survival, /required 5/);
  assert.equal(summary.runs[4].survival, "restored text is not what was typed");
  assert.equal(summary.runs[6].required_len, undefined);
});
