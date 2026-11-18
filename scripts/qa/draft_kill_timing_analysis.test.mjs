import test from "node:test";
import assert from "node:assert/strict";
import { analyzeKillRun, parseKillLine, parseLogcatLine, summarize, markdownTable } from "./draft_kill_timing_analysis.mjs";

const PKG = "build.raft.app.alpha";
const line = (sec, pid, tag, message) => `${sec.toFixed(3).padStart(10)}  ${pid}  ${pid} I ${tag}: ${message}`;
const change = (sec, seq, len, pid = 4242) => line(sec, pid, "RaftDraftTiming", `change_received seq=${seq} len=${len} uptimeMs=${Math.round(sec * 1000)}`);
const written = (sec, seq, len, pid = 4242) => line(sec, pid, "RaftDraftTiming", `draft_written seq=${seq} len=${len} uptimeMs=${Math.round(sec * 1000)}`);
const forceStop = (sec) => line(sec, 600, "ActivityManager", `Force stopping ${PKG} appid=10190 user=0: from pid 9000`);
const kill = (sec, pid = 4242, pkg = PKG) => line(sec, 600, "ActivityManager", `Killing ${pid}:${pkg}/u0a190 (adj 0): stop ${pkg} due to from pid 9000`);

test("parses logcat -v monotonic lines and AM kill lines", () => {
  const parsed = parseLogcatLine(change(1234.5, 7, 12));
  assert.equal(parsed.tag, "RaftDraftTiming");
  assert.equal(parsed.tsMs, 1234500);
  assert.equal(parsed.pid, 4242);
  assert.equal(parseKillLine(parseLogcatLine(kill(1, 77)).message, PKG), 77);
  // Another process of the app (`pkg:service`) or another app is not the main process.
  assert.equal(parseKillLine(`Killing 78:${PKG}:remote/u0a190 (adj 0): stop`, PKG), null);
  assert.equal(parseKillLine("Killing 79:com.other/u0a1 (adj 0): stop", PKG), null);
});

test("a kill 60 ms after the last change with the write in between is a 0.05 s hit", () => {
  const r = analyzeKillRun({
    lines: [change(100.0, 1, 11), written(100.002, 1, 11), change(100.5, 2, 12), written(100.503, 2, 12), forceStop(100.559), kill(100.56)],
    pkg: PKG, oldPid: 4242, expectedLen: 12, delaySec: 0.05, pidGone: true,
  });
  assert.equal(r.timing, "hit");
  assert.equal(r.delta_ms, 60);
  assert.equal(r.written_before_kill, true);
  assert.equal(r.write_after_change_ms, 3);
  assert.equal(r.kill_pid, 4242);
  assert.equal(r.clock_skew_ms, 0);
});

test("a Δ outside the bucket is measured but unconfirmed for that bucket", () => {
  const r = analyzeKillRun({ lines: [change(10, 1, 5), written(10.001, 1, 5), kill(10.4)], pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.1, pidGone: true });
  assert.equal(r.timing, "unconfirmed");
  assert.equal(r.delta_ms, 400);
  assert.match(r.reason, /outside bucket/);
});

test("no change line for the full text before the kill is unconfirmed, and a late one is named", () => {
  const none = analyzeKillRun({ lines: [change(10, 1, 4), kill(10.05)], pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.05, pidGone: true });
  assert.equal(none.timing, "unconfirmed");
  assert.match(none.reason, /no change_received/);
  const late = analyzeKillRun({ lines: [kill(10.05), change(10.06, 2, 5)], pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.05, pidGone: true });
  assert.match(late.reason, /only after the kill/);
});

test("no kill line for the old pid, a surviving pid, or a skewed clock are unconfirmed", () => {
  const other = analyzeKillRun({ lines: [change(10, 1, 5), kill(10.05, 1111)], pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.05, pidGone: true });
  assert.match(other.reason, /no ActivityManager kill line/);
  const alive = analyzeKillRun({ lines: [change(10, 1, 5), kill(10.05)], pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.05, pidGone: false });
  assert.match(alive.reason, /still alive/);
  const skewed = analyzeKillRun({
    lines: [line(10, 4242, "RaftDraftTiming", "change_received seq=1 len=5 uptimeMs=9000"), kill(10.05)],
    pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.05, pidGone: true,
  });
  assert.match(skewed.reason, /clocks disagree/);
});

test("a write after the kill line does not count as written before the kill", () => {
  const r = analyzeKillRun({ lines: [change(10, 1, 5), kill(10.05), written(10.06, 1, 5)], pkg: PKG, oldPid: 4242, expectedLen: 5, delaySec: 0.05, pidGone: true });
  assert.equal(r.written_before_kill, false);
});

test("summary counts hits, unconfirmed and passes per group; the table has no text columns beyond the marker", () => {
  const runs = [
    { group: "D=0.05", timing: "hit", pass: true, written_before_kill: true, delta_ms: 60, expected_desc: "len 12", observed_desc: "len 12" },
    { group: "D=0.05", timing: "unconfirmed", pass: true, written_before_kill: false, delta_ms: null, reason: "x", expected_desc: "len 12", observed_desc: "len 12" },
    { group: "D=0.1", timing: "hit", pass: false, written_before_kill: true, delta_ms: 110, expected_desc: "len 12", observed_desc: "len 11" },
  ];
  const s = summarize(runs);
  assert.deepEqual(s.find((g) => g.group === "D=0.05"), {
    group: "D=0.05", runs: 2, hits: 1, unconfirmed: 1, restored: 2, restored_when_hit: 1, written_before_kill: 1,
    delta_min_ms: 60, delta_median_ms: 60, delta_max_ms: 60,
  });
  assert.equal(s.find((g) => g.group === "D=0.1").restored, 0);
  const md = markdownTable(runs, s);
  assert.match(md, /\| D=0\.05 \| 2 \| 1 \| 1 \| 2 \| 1 \| 1 \|/);
});
