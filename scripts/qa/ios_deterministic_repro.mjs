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

// Deterministic login for a cold simulator: Sign In screen → email/password →
// Sign In → (optional) server picker → home. Kuikly fields do not expose
// native editability consistently at the first snapshot, so resolve them by
// the stable label-relative position (field sits below Email/Password).
async function ensureLoggedIn() {
  let dump = await drv.dumpUi();
  fs.writeFileSync(path.join(outDir, "sign-in-dump.txt"), redactEditableValues(dump));
  if (!/Sign In|Email|Password/.test(dump)) { rec("already past sign-in screen"); return; }
  const email = process.env.QA_TEST_EMAIL;
  const password = process.env.QA_TEST_PASSWORD;
  if (!email || !password) throw new Error("QA_TEST_EMAIL/QA_TEST_PASSWORD not set for login step");
  rec("sign-in screen detected, logging in deterministically");

  const fieldUnder = (label) => {
    const line = dump.split("\n").find((l) => {
      const t = /"([^"]*)"/.exec(l)?.[1] ?? "";
      return t.trim() === label;
    });
    if (!line) throw new Error(`"${label}" label not found in sign-in dump`);
    const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
    // tap the field below the label: label center-x, label bottom + ~45px
    return { x: Math.round((Number(m[1]) + Number(m[3])) / 2), y: Number(m[4]) + 45 };
  };

  let p = fieldUnder("Email");
  await drv.tap(p.x, p.y);
  await drv.text(email);
  rec(`email typed at [${p.x},${p.y}]`);
  p = fieldUnder("Password");
  await drv.tap(p.x, p.y);
  try {
    await drv.text(password);
  } catch {
    // Never let the driver's error text (which may echo the typed value)
    // reach the steps/verdict output.
    throw new Error("typing the password failed");
  }
  rec(`password typed at [${p.x},${p.y}]`);

  // On this auth form, agent-device keyboard dismiss taps Return/Go and
  // already submits. Name that action: keyboard enter. Do not press Sign In
  // afterwards — the button is gone.
  const enterOut = await drv.key("enter");
  rec(`submitting via keyboard enter stdout: ${String(enterOut || "").replace(/\s+/g, " ").slice(0, 300)}`);
  await new Promise((r2) => setTimeout(r2, 8000));

  if (typeof drv.dismissSystemAlerts === "function") {
    const alerts = await drv.dismissSystemAlerts();
    rec(`system alerts: present=${Boolean(alerts?.present)} accepted=${Boolean(alerts?.accepted)}`);
  }

  dump = await drv.dumpUi();
  const stillLogin = /"Email"/.test(dump) && /"Password"/.test(dump);
  if (stillLogin) {
    // Do NOT screenshot here: the form holds the typed email (secret hygiene —
    // r3 artifact was flagged for exactly this). Steps carry the failure.
    writeVerdict("fail", "login did not complete: Sign In page still visible after keyboard enter");
    process.exit(1);
  }

  dump = await drv.dumpUi();
  if (/Choose server|OranixSlockTest/.test(dump) && !dump.includes('"Message #')) {
    const rowLine = dump.split("\n").find((l) => /OranixSlockTest/.test(l));
    if (!rowLine) throw new Error("server picker shown but OranixSlockTest row not found");
    p = parseBounds(rowLine);
    await drv.tap(p.x, p.y);
    rec("server OranixSlockTest selected");
    await new Promise((r2) => setTimeout(r2, 6000));
  }
}

const verdict = { verdict: "error", reason: "", steps };
// Secret hygiene: the verdict and its console echo must never carry the QA
// password, whatever an error message contains.
const redactSecrets = (text) => {
  const pw = process.env.QA_TEST_PASSWORD;
  const s = String(text);
  return pw ? s.split(pw).join("***") : s;
};
const writeVerdict = (v, reason) => {
  verdict.verdict = v;
  verdict.reason = redactSecrets(reason);
  fs.writeFileSync(path.join(outDir, "steps.jsonl"), steps.map((s) => JSON.stringify(s)).join("\n") + "\n");
  fs.writeFileSync(path.join(outDir, "verdict.json"), JSON.stringify(verdict, null, 2));
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
  fs.writeFileSync(path.join(outDir, "home-dump.txt"), redactEditableValues(dump));
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
  fs.writeFileSync(path.join(outDir, "channel-dump.txt"), redactEditableValues(dump));
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
  writeVerdict("error", `harness/script error: ${String(e).slice(0, 300)}`);
  process.exit(2);
}
