import test from "node:test";
import assert from "node:assert/strict";
import { analyzeKillRun, measuredBucket, summarizeByMeasuredBucket, exitInterval, parseEventPid, parseKillLine, parseLogcatLine, summarize, markdownTable } from "./draft_kill_timing_analysis.mjs";

const PKG = "build.raft.app.alpha";
const PID = 4242;
const line = (sec, pid, tag, message) => `${sec.toFixed(3).padStart(10)}  ${pid}  ${pid} I ${tag}: ${message}`;
const change = (sec, seq, len, pid = PID) => line(sec, pid, "RaftDraftTiming", `change_received seq=${seq} len=${len} uptimeMs=${Math.round(sec * 1000)}`);
const written = (sec, seq, len, pid = PID) => line(sec, pid, "RaftDraftTiming", `draft_written seq=${seq} len=${len} uptimeMs=${Math.round(sec * 1000)}`);
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
