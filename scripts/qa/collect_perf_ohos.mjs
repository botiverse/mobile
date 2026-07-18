#!/usr/bin/env node
// qa/collect_perf_ohos — assemble a self-describing perf.json for an OHOS
// acceptance run executed via HDC (external-executor lane: Ark's bare-metal
// emulator or a future physical device).
//
// OHOS v1 of task #14 (artin: "ohos呢"). Contract locked with Ark (OHOS lane):
//   - signals come from HILOG (`hdc -t <target> shell hilog -x`), which on
//     HarmonyOS carries REAL system jank: ProcessJank/frame warnings — plus
//     input-subsystem events (InputMethod/KeyEvent/HMKeyboard_*).
//   - A run with ZERO jank signal is NOT a pass: it is
//     "signal_absent/insufficient_evidence" and the raw HILOG must accompany
//     the verdict. No estimated-FPS fabrication.
//   - identity records LIVE facts: device model, OS/software version, API
//     level, app PID at capture time, HAP sha256, and whether the run is
//     emulator-only (physical-device gates stay separate).
//   - app-level unified FPS markers are a known_gap (SlockPerformanceDiagnostics
//     is Android-only; the shared-marker port is a product change, KMP lane).
//
// Same header discipline as collect_perf.mjs / collect_perf_ios.mjs.
// Record-only, advisory — no thresholds.
//
// Usage:
//   collect_perf_ohos.mjs --hilog artifacts/raft-ohos-hilog.txt \
//     --steps steps.jsonl --bundle build.raft.mobile \
//     --device-json '{"model":"Raft API23","os":"HarmonyOS 6.1.0","api":"23","pid":1234,"hap_sha256":"...","emulator_only":true}' \
//     --environment OranixSlockTest --started <epoch_ms> --out perf.json

import fs from "node:fs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const hilogPath = arg("--hilog", "");
const stepsPath = arg("--steps", "");
const bundle = arg("--bundle", "build.raft.mobile");
const environment = arg("--environment", "unknown");
const outPath = arg("--out", "");
const startedMs = Number(arg("--started", "0")) || null;
let device = {};
try { device = JSON.parse(arg("--device-json", "{}")); } catch { device = {}; }

const RECORDER = { name: "hilog-steps-ohos-v1", version: "1.0.0", thresholds: "none (advisory record-only)" };

const JANK_PATTERNS = {
  process_jank: /ProcessJank/i,
  frame_jank: /frame.*(jank|skip|drop)|jank.*frame|SkippedFrames/i,
  vsync_miss: /miss.*vsync|vsync.*miss/i,
};
const INPUT_PATTERNS = {
  input_method: /InputMethod/i,
  key_event: /KeyEvent/i,
  hm_keyboard: /HMKeyboard/i,
};

const jank = {};
const inputSignals = {};
let jankTotal = 0;
if (hilogPath && fs.existsSync(hilogPath)) {
  const lines = fs.readFileSync(hilogPath, "utf8").split("\n");
  for (const [name, re] of Object.entries(JANK_PATTERNS)) {
    const hits = lines.filter((l) => re.test(l));
    jank[name] = { count: hits.length, firstLines: hits.slice(0, 10).map((l) => l.slice(0, 220)) };
    jankTotal += hits.length;
  }
  for (const [name, re] of Object.entries(INPUT_PATTERNS)) {
    inputSignals[name] = { count: lines.filter((l) => re.test(l)).length };
  }
}

// Ark contract: zero jank signal is insufficient_evidence, never a pass.
const jankAssessment = jankTotal > 0
  ? { status: "signals_present", note: `${jankTotal} jank-related HILOG lines — review firstLines; NOT auto-fail (emulator jank != physical jank)` }
  : { status: "signal_absent/insufficient_evidence", note: "no ProcessJank/frame lines in capture window — cannot conclude smoothness; attach raw HILOG to the verdict" };

let stepWindows = [];
if (stepsPath && fs.existsSync(stepsPath)) {
  const rows = fs.readFileSync(stepsPath, "utf8").split("\n").filter(Boolean);
  const ts = [];
  for (const row of rows) {
    try {
      const j = JSON.parse(row);
      if (j.ts) ts.push({ ts: j.ts, action: j.action?.action || j.action?.type || j.type || "?" });
    } catch { /* tolerate partial lines */ }
  }
  for (let i = 1; i < ts.length; i++) stepWindows.push({ from: ts[i - 1].action, durationMs: ts[i].ts - ts[i - 1].ts });
  stepWindows.sort((a, b) => b.durationMs - a.durationMs);
  stepWindows = stepWindows.slice(0, 5);
}

const report = {
  recorder: RECORDER,
  capture_window: { clock_domain: "wall_clock_epoch_ms", started_ms: startedMs, ended_ms: Date.now() },
  identity: {
    bundle,
    device: {
      model: device.model || "unknown",
      os: device.os || "unknown",
      api: device.api || "unknown",
      pid_at_capture: device.pid || null,
    },
    hap_sha256: device.hap_sha256 || null,
    emulator_only: device.emulator_only !== false,
    environment,
  },
  jank_signals: jank,
  jank_assessment: jankAssessment,
  input_signals: inputSignals,
  slowest_step_windows: stepWindows,
  known_gap: "No app-level unified FPS markers (SlockPerformanceDiagnostics is Android-only; shared-marker port is a product change, KMP lane). System HILOG ProcessJank is the only jank source in v1.",
  note: "Advisory record-only sample. Emulator jank signals are not physical-device evidence; physical gates stay separate.",
};

if (outPath) fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
