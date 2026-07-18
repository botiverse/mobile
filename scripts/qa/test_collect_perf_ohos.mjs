#!/usr/bin/env node
// qa/test_collect_perf_ohos — executable fixtures + assertions for
// collect_perf_ohos.mjs (task #14 OHOS v1). Builds three HILOG fixtures in a
// temp dir and asserts the contract LiBai's review pinned:
//   1. jank-bearing HILOG → jank_assessment.status === "signals_present",
//      per-pattern counts present, and jankTotal has NO double-count when one
//      line matches several patterns.
//   2. empty HILOG → "signal_absent/insufficient_evidence", capture="empty"
//      (never a smoothness PASS — Ark's contract).
//   3. missing HILOG path → same insufficient status but capture="missing"
//      (operator error must not masquerade as a quiet window).
// Exit 0 = all assertions hold.
//
// Usage: node scripts/qa/test_collect_perf_ohos.mjs

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const COLLECTOR = new URL("./collect_perf_ohos.mjs", import.meta.url).pathname;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ohos-perf-test-"));

const JANK_HILOG = [
  "07-18 12:00:01.123 1234 1234 I A0c0d/Raft: app start",
  "07-18 12:00:02.456 1234 1234 W C03911/Ace: ProcessJank: Frame skipped 3 vsync, duration 50ms",
  "07-18 12:00:03.789 1234 1234 I InputMethod: show soft keyboard",
  "07-18 12:00:04.012 1234 1234 I HMKeyboard_HardwareInputManager: key down 67",
  "07-18 12:00:05.345 1234 1234 I KeyEvent: KeyEvent { action=down, keyCode=2055 }",
  "07-18 12:00:06.678 1234 1234 W C03911/Ace: ProcessJank: Frame skipped 12 frames in a row",
].join("\n") + "\n";
const EMPTY_HILOG = "";

const run = (args) => {
  const out = execFileSync("node", [COLLECTOR, ...args], { encoding: "utf8" });
  return JSON.parse(out);
};

let failures = 0;
const check = (name, cond, detail) => {
  if (cond) { console.log(`ok   ${name}`); }
  else { failures += 1; console.error(`FAIL ${name}${detail ? ` — ${detail}` : ""}`); }
};

// 1. jank-bearing fixture
const jankFile = path.join(dir, "jank.txt");
fs.writeFileSync(jankFile, JANK_HILOG);
const r1 = run(["--hilog", jankFile, "--environment", "OranixSlockTest"]);
check("jank fixture → signals_present", r1.jank_assessment.status === "signals_present", r1.jank_assessment.status);
check("process_jank count = 2", r1.jank_signals.process_jank.count === 2, String(r1.jank_signals.process_jank.count));
check("no double-count in jank_total", r1.jank_total === 2, `got ${r1.jank_total} (lines match multiple patterns must count once)`);
check("capture = present", r1.capture === "present", r1.capture);
check("input signals counted", r1.input_signals.key_event.count === 1 && r1.input_signals.hm_keyboard.count === 1);
check("recorder identity", r1.recorder.name === "hilog-steps-ohos-v1" && typeof r1.recorder.version === "string");

// 2. empty fixture — never a pass
const emptyFile = path.join(dir, "empty.txt");
fs.writeFileSync(emptyFile, EMPTY_HILOG);
const r2 = run(["--hilog", emptyFile]);
check("empty fixture → insufficient_evidence", r2.jank_assessment.status === "signal_absent/insufficient_evidence", r2.jank_assessment.status);
check("empty fixture capture = empty", r2.capture === "empty", r2.capture);
check("empty fixture jank_total = 0", r2.jank_total === 0);

// 3. missing path — operator error must be distinguishable
const r3 = run(["--hilog", path.join(dir, "does-not-exist.txt")]);
check("missing path → insufficient_evidence", r3.jank_assessment.status === "signal_absent/insufficient_evidence", r3.jank_assessment.status);
check("missing path capture = missing", r3.capture === "missing", r3.capture);
check("missing path note flags operator error", /operator error/.test(r3.jank_assessment.note), r3.jank_assessment.note);

if (!process.argv.includes("--keep")) fs.rmSync(dir, { recursive: true, force: true });
if (failures) { console.error(`${failures} assertion(s) failed`); process.exit(1); }
console.log("all assertions hold");
