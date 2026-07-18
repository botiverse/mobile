#!/usr/bin/env node
// qa/ios_deterministic_repro — scripted (no-M3) deterministic drive for the
// iOS loop harness. The acceptance agent's M3 model keeps getting lost on
// navigation (permission dialogs, back-gesture exits, channel timeouts — three
// runs burned). This script drives ios_driver.mjs directly with a fixed,
// auditable step sequence:
//
//   waitReady → open #all → assert composer + image button → marker →
//   single tap on the image button → liveness + UI state → verdict
//
// Outcome branches:
//   - process died (launchctl no longer lists the bundle, or describe throws
//     repeatedly)  → verdict fail, naming the exact step of death
//   - a picker/permission UI appeared (photo-library markers in the dump) →
//     verdict pass ("reached picker gate" only — select/preview/send are NOT
//     asserted here; that is the case's remaining scope)
//   - app alive but no picker → verdict fail "picker did not open"
//
// Usage:
//   node scripts/qa/ios_deterministic_repro.mjs \
//     --ios-io-dir "$QA_LOOP_IO_DIR" --ios-udid "$UDID" \
//     --package build.raft.app --out qa-evidence

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { createIosDriver } from "./ios_driver.mjs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const ioDir = arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || "");
const udid = arg("--ios-udid", "booted");
const bundle = arg("--package", "build.raft.app");
const outDir = arg("--out", "qa-evidence");
if (!ioDir) { console.error("--ios-io-dir / QA_LOOP_IO_DIR required"); process.exit(2); }

fs.mkdirSync(outDir, { recursive: true });
const drv = createIosDriver({ ioDir, udid, bundle });
const steps = [];
const rec = (msg) => { steps.push({ ts: Date.now(), msg }); console.error(msg); };

const shot = async (name) => {
  const png = await drv.screenshotPng();
  fs.writeFileSync(path.join(outDir, name), png);
  rec(`captured ${name}`);
};

const processAlive = () => {
  try {
    const out = execFileSync("xcrun", ["simctl", "spawn", udid, "launchctl", "list"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30000,
    });
    return out.includes(bundle);
  } catch { return null; } // spawn failed — unknown, treat as unknown not dead
};

const PICKER_MARKERS = /photos|recents|photo library|选择|照片|允许|allow|cancel|取消|select/i;

function findComposerImageBtn(dump) {
  // dump lines: `[BTN] "label" [x1,y1][x2,y2]` — the composer input is the
  // `"Message #..."` node; the image button is the unlabeled [BTN] in the same
  // row band, left of the input field.
  const lines = dump.split("\n");
  const composer = lines.find((l) => l.includes('"Message #'));
  if (!composer) return null;
  const cy = Number(/\[\d+,(\d+)\]\[\d+,\d+\]/.exec(composer)?.[1] ?? -1);
  let best = null;
  for (const l of lines) {
    if (!l.includes("[BTN]")) continue;
    const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(l);
    if (!m) continue;
    const [x1, y1, x2, y2] = m.slice(1).map(Number);
    if (cy > 0 && Math.abs(y1 - cy) > 80) continue; // same row band as composer
    if (!best || x1 < best.x1) best = { x1, y1, x2, y2 };
  }
  return best ? { x: Math.round((best.x1 + best.x2) / 2), y: Math.round((best.y1 + best.y2) / 2) } : null;
}

const verdict = { verdict: "error", reason: "", steps };
const writeVerdict = (v, reason) => {
  verdict.verdict = v;
  verdict.reason = reason;
  fs.writeFileSync(path.join(outDir, "steps.jsonl"), steps.map((s) => JSON.stringify(s)).join("\n") + "\n");
  fs.writeFileSync(path.join(outDir, "verdict.json"), JSON.stringify(verdict, null, 2));
  console.log(JSON.stringify(verdict, null, 2));
};

try {
  await drv.waitReady();
  rec("harness ready");
  await shot("step-00-launch.png");

  // Open #all deterministically: find the channel row whose label (space-
  // stripped) is "#all" and tap it. If we are already inside #all (composer
  // placeholder "Message #all"), skip navigation.
  let dump = await drv.dumpUi();
  if (!dump.includes('"Message #all"')) {
    const rowLine = dump.split("\n").find((l) => {
      const label = /"([^"]*)"/.exec(l)?.[1] ?? "";
      return label.replace(/\s/g, "").toLowerCase() === "#all";
    });
    if (!rowLine) { writeVerdict("fail", "#all channel row not found in home dump"); process.exit(1); }
    const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(rowLine);
    const x = Math.round((Number(m[1]) + Number(m[3])) / 2);
    const y = Math.round((Number(m[2]) + Number(m[4])) / 2);
    rec(`opening #all at [${x},${y}]`);
    await drv.tap(x, y);
    await new Promise((r) => setTimeout(r, 4000));
    dump = await drv.dumpUi();
  }
  if (!dump.includes('"Message #all"')) { writeVerdict("fail", "#all composer placeholder not visible after open"); process.exit(1); }
  rec("#all open, composer visible");
  await shot("step-01-channel.png");

  // Assert the image button and mark the process state before the single tap.
  const btn = findComposerImageBtn(dump);
  if (!btn) { writeVerdict("fail", "composer image button not found in dump (icon-only BTN resolution)"); process.exit(1); }
  const aliveBefore = processAlive();
  rec(`image button at [${btn.x},${btn.y}]; process alive before tap: ${aliveBefore}`);

  // The single deterministic tap.
  await drv.tap(btn.x, btn.y);
  rec("single tap on image button issued");
  await new Promise((r) => setTimeout(r, 8000));
  await shot("step-02-after-tap.png");

  // Observe.
  const aliveAfter = processAlive();
  rec(`process alive after tap: ${aliveAfter}`);
  if (aliveAfter === false) {
    writeVerdict("fail", `process ${bundle} died after the single image-button tap (before=${aliveBefore}, after=false) — attribute via .ips`);
    process.exit(1);
  }
  let after = "";
  try { after = await drv.dumpUi(); } catch (e) { rec(`describe after tap failed: ${String(e).slice(0, 120)}`); }
  if (after && (PICKER_MARKERS.test(after) || after.split("\n").length > dump.split("\n").length + 5)) {
    writeVerdict("pass", "picker gate reached after single deterministic tap (dump shows photo-picker markers or a new large panel). Scope note: select/preview/send NOT asserted.");
    process.exit(0);
  }
  writeVerdict("fail", "picker did not open after single tap (app alive, no picker markers in dump)");
  process.exit(1);
} catch (e) {
  writeVerdict("error", `harness/script error: ${String(e).slice(0, 300)}`);
  process.exit(2);
}
