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
// Login and capture policy (secure input, #222): this mode NEVER signs in and
// takes no capture before the same login-window gate the agent uses. Before
// every screenshot and every dump written to the evidence dir it probes the
// screen (ios_driver loginSurfaceVisible, in memory): a login surface (secure
// field, web sign-in sheet, "Continue as ...", provider button) or an
// unreadable/empty tree stops the run UNKNOWN with nothing captured from then
// on. A signed-out start ends as a harness error (use the Stamp session
// bootstrap). It writes capture-suppression.json like the agent, finalised
// only at its end, so the egress gate can tell a clean run from a crashed one.
//
// Usage:
//   node scripts/qa/ios_deterministic_repro.mjs \
//     --ios-io-dir "$QA_LOOP_IO_DIR" --ios-udid "$UDID" \
//     --package build.raft.app --out qa-evidence

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { createIosDriver } from "./ios_driver.mjs";
import { CaptureGate, redactSecrets as redactConfiguredSecrets } from "./secure_input.mjs";
import {
  dumpShowsComposer,
  findAllChannelTarget,
  findComposerImageTarget,
  hasPhotoPickerGate,
  redactEditableValues,
} from "./ios_navigation_matcher.mjs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const ioDir = arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || "");
const udid = arg("--ios-udid", "booted");
const bundle = arg("--package", "build.raft.app");
const outDir = arg("--out", "qa-evidence");
if (!ioDir) { console.error("--ios-io-dir / QA_LOOP_IO_DIR required"); process.exit(2); }

fs.mkdirSync(outDir, { recursive: true });
const gate = new CaptureGate();
const suppressionPath = path.join(outDir, "capture-suppression.json");
const writeSuppression = () => fs.writeFileSync(suppressionPath, JSON.stringify(gate.summary(), null, 2));
writeSuppression();
const drv = createIosDriver({ ioDir, udid, bundle });
const steps = [];
const rec = (msg) => { steps.push({ ts: Date.now(), msg }); console.error(msg); };

class LoginWindowStop extends Error {}
// The trusted gate before ANY capture: false = safe; true or unknown = stop.
const assertCaptureSafe = async (what) => {
  let visible = null;
  try { visible = await drv.loginSurfaceVisible(); } catch { visible = null; }
  if (visible === false) return;
  const reason = visible === true ? `login surface on screen before ${what}` : `login-surface probe unavailable before ${what}`;
  const phase = gate.markUnknown(0, [reason]);
  phase.recording = "not_recorded";
  writeSuppression();
  throw new LoginWindowStop(reason);
};
const shot = async (name) => {
  await assertCaptureSafe(name);
  const png = await drv.screenshotPng();
  fs.writeFileSync(path.join(outDir, name), png);
  rec(`captured ${name}`);
};
const saveDump = async (name, dump) => {
  await assertCaptureSafe(name);
  fs.writeFileSync(path.join(outDir, name), redactEditableValues(dump));
};

const processAlive = () => {
  try {
    const out = execFileSync("xcrun", ["simctl", "spawn", udid, "launchctl", "list"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30000,
    });
    return out.includes(bundle);
  } catch { return null; } // spawn failed — unknown, treat as unknown not dead
};

// SpringBoard owns the initial Photos permission alert, so drv.dumpUi() (which
// is scoped to the app under test) can remain unchanged even though the gate is
// visibly open. Query only the short post-tap unified-log window and pass the
// text through the same pure marker detector used for app dumps.
const recentPhotoGateLog = () => {
  try {
    return execFileSync("xcrun", [
      "simctl", "spawn", udid, "log", "show",
      "--last", "30s",
      "--style", "compact",
      "--predicate", 'eventMessage CONTAINS[c] "Photo Library"',
    ], { encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30000 });
  } catch (error) {
    rec(`photo gate log query failed: ${String(error).slice(0, 120)}`);
    return "";
  }
};

function parseBounds(line) {
  const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
  return m ? { x: Math.round((Number(m[1]) + Number(m[3])) / 2), y: Math.round((Number(m[2]) + Number(m[4])) / 2) } : null;
}

// Never signs in: credentials cannot be typed safely on iOS (no argv-free
// channel; ios_driver refuses), so a signed-out start is a harness error. The
// sign-in screen is only inspected in memory, after the capture gate.
async function ensureLoggedIn() {
  await assertCaptureSafe("sign-in check");
  const dump = await drv.dumpUi();
  if (/Sign In|Email|Password/.test(dump)) {
    writeVerdict("error", "signed-out start: deterministic mode never signs in (use the Stamp session bootstrap)");
    process.exit(2);
  }
  rec("already past sign-in screen");
  // A signed-in start may still ask for the server: pick the QA server row
  // only, never another row as a fallback.
  if (/Choose server|OranixSlockTest/.test(dump) && !dump.includes('"Message #')) {
    const rowLine = dump.split("\n").find((l) => /OranixSlockTest/.test(l));
    if (!rowLine) throw new Error("server picker shown but OranixSlockTest row not found");
    const p = parseBounds(rowLine);
    await drv.tap(p.x, p.y);
    rec("server OranixSlockTest selected");
    await new Promise((r2) => setTimeout(r2, 6000));
  }
}

const verdict = { verdict: "error", reason: "", steps };
// Secret hygiene: the verdict and its console echo must never carry a
// configured QA secret, whatever an error message contains.
const redactSecrets = (text) => redactConfiguredSecrets(String(text));
const writeVerdict = (v, reason) => {
  // A login window (or an unknown probe) overrides every outcome.
  if (gate.unresolved) {
    verdict.verdict = "error";
    verdict.reason = redactSecrets(`secret-input phase UNKNOWN: capture stays suppressed (${gate.phases.flatMap((p) => p.reasons ?? []).join("; ")})`);
    verdict.secure_input = { status: "UNKNOWN", reasons: gate.phases.flatMap((p) => p.reasons ?? []) };
  } else {
    verdict.verdict = v;
    verdict.reason = redactSecrets(reason);
    verdict.secure_input = { status: "not_used", phases: 0 };
  }
  fs.writeFileSync(path.join(outDir, "steps.jsonl"), steps.map((s) => redactSecrets(JSON.stringify(s))).join("\n") + "\n");
  fs.writeFileSync(path.join(outDir, "verdict.json"), JSON.stringify(verdict, null, 2));
  gate.completed = true;
  writeSuppression();
  console.log(JSON.stringify(verdict, null, 2));
};

try {
  await drv.waitReady();
  rec("harness ready");
  await shot("step-00-launch.png");

  await ensureLoggedIn();
  await shot("step-01-loggedin.png");

  // Open #all deterministically: find the channel row whose label (space-
  // stripped) is "#all" and tap it. If we are already inside #all (composer
  // placeholder "Message #all"), skip navigation.
  // Do not alert-get on Home: iOS runner get/accept ignore timeout and use a
  // 10s main-thread budget that Kuikly Home exceeds.
  let dump = await drv.dumpUi();
  await saveDump("home-dump.txt", dump);
  if (!dumpShowsComposer(dump)) {
    let target;
    try {
      target = findAllChannelTarget(dump);
    } catch (error) {
      writeVerdict("fail", `#all navigation precondition failed: ${String(error)}`);
      process.exit(1);
    }
    rec(`opening #all via ${target.mode} target at [${target.x},${target.y}]`);
    await drv.tap(target.x, target.y);
    await new Promise((r) => setTimeout(r, 4000));
    dump = await drv.dumpUi();
  }
  await saveDump("channel-dump.txt", dump);
  if (!dumpShowsComposer(dump)) {
    writeVerdict("fail", "#all composer [INPUT] not visible after open (agent-device dumps have no placeholder)");
    process.exit(1);
  }
  rec("#all open, composer visible");
  await shot("step-01-channel.png");

  // Assert the image button and mark the process state before the single tap.
  let btn;
  try {
    btn = findComposerImageTarget(dump);
  } catch (error) {
    writeVerdict("fail", `composer image-button precondition failed: ${String(error)}`);
    process.exit(1);
  }
  const aliveBefore = processAlive();
  rec(`image button via ${btn.mode} at [${btn.x},${btn.y}]; process alive before tap: ${aliveBefore}`);

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
  const systemPhotoGateLog = recentPhotoGateLog();
  if (hasPhotoPickerGate({ appDump: after, systemLog: systemPhotoGateLog })
      || (after && after.split("\n").length > dump.split("\n").length + 5)) {
    const evidenceMode = hasPhotoPickerGate({ systemLog: systemPhotoGateLog })
      ? "SpringBoard Photos permission alert"
      : "app dump photo-picker markers or a new large panel";
    rec(`picker gate evidence: ${evidenceMode}`);
    writeVerdict("pass", `picker gate reached after single deterministic tap (${evidenceMode}). Scope note: select/preview/send NOT asserted.`);
    process.exit(0);
  }
  writeVerdict("fail", "picker did not open after single tap (app alive, no picker markers in app dump or SpringBoard log)");
  process.exit(1);
} catch (e) {
  if (e instanceof LoginWindowStop) {
    writeVerdict("error", e.message);
    process.exit(2);
  }
  writeVerdict("error", `harness/script error: ${String(e).slice(0, 300)}`);
  process.exit(2);
}
