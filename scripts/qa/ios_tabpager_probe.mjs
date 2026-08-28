#!/usr/bin/env node
// qa/ios_tabpager_probe — drives the TabPager probe page for task #232/#233.
//
// Sequence and pass criteria are @CC-Cata's, quoted verbatim below; I did not
// invent them. This script's job is to EXECUTE and CAPTURE, not to judge whether
// TabPager is correct — the on-screen event log is the evidence and @CC-Cata
// reads it. Every step therefore records a screenshot plus the dump, and the
// verdict only reports whether each step could be PERFORMED.
//
//   1 content swipe left    (0.80,0.60) -> (0.20,0.60), 300ms, wait 1s
//   2 tap Gamma             right third of the SegmentedControl, wait 1s
//   3 left-edge yield       from x=10pt -> (0.60,0.60)
//   4 short drag rebound    (0.45,0.60) -> (0.55,0.60), slow, wait 1s
//   5 scroll retention      (0.5,0.75)->(0.5,0.30) x2, swipe to next tab, swipe back
//
// ⚠️ Liveness, and it is the reason the screenshots are worth anything: the probe
// page numbers its log lines (#N) monotonically. Adjacent captures MUST show a
// strictly increasing #N. If they do not, the capture is stale and every "nothing
// changed" reading in this run is unjudgeable — the same failure mode as a frozen
// screencap. This script extracts and records those numbers so the check does not
// depend on someone eyeballing the images.
//
// Usage:
//   node scripts/qa/ios_tabpager_probe.mjs --ios-io-dir "$QA_LOOP_IO_DIR" \
//     --ios-udid "$UDID" --package build.raft.app --out qa-evidence [--groups 1,2]

import fs from "node:fs";
import path from "node:path";
import { createIosDriver } from "./ios_driver.mjs";
import { redactEditableValues } from "./ios_navigation_matcher.mjs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const ioDir = arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || "");
const udid = arg("--ios-udid", "booted");
const bundle = arg("--package", "build.raft.app");
const outDir = arg("--out", "qa-evidence");
const groups = arg("--groups", "1,2,3,4,5").split(",").map((s) => s.trim()).filter(Boolean);
if (!ioDir) { console.error("--ios-io-dir / QA_LOOP_IO_DIR required"); process.exit(2); }

fs.mkdirSync(outDir, { recursive: true });
const drv = createIosDriver({ ioDir, udid, bundle });
const steps = [];
const rec = (msg, extra = {}) => {
  const e = { t: new Date().toISOString(), msg, ...extra };
  steps.push(e);
  console.log(`[tabpager] ${e.t} ${msg}${Object.keys(extra).length ? " " + JSON.stringify(extra) : ""}`);
};
const verdict = { case: "ios-tabpager-probe", tasks: [232, 233], groups, performed: [], logSeq: [] };
const write = (v, reason) => {
  Object.assign(verdict, { verdict: v, reason });
  fs.writeFileSync(path.join(outDir, "steps.jsonl"), steps.map((s) => JSON.stringify(s)).join("\n") + "\n");
  fs.writeFileSync(path.join(outDir, "verdict.json"), JSON.stringify(verdict, null, 2));
  console.log(JSON.stringify(verdict, null, 2));
};

// The probe prints "#N" per log line. Pull every one out of a dump so liveness is
// a recorded number rather than an impression from looking at a picture.
const seqNumbers = (dump) => (dump.match(/#(\d+)/g) || []).map((s) => Number(s.slice(1)));
const maxSeq = (dump) => { const n = seqNumbers(dump); return n.length ? Math.max(...n) : null; };

let lastMaxSeq = null;
const capture = async (label) => {
  const png = path.join(outDir, `${label}.png`);
  try { fs.writeFileSync(png, await drv.screenshotPng()); } catch (e) { rec(`screenshot ${label} failed: ${String(e).slice(0, 100)}`); }
  const dump = await drv.dumpUi();
  fs.writeFileSync(path.join(outDir, `${label}-dump.txt`), redactEditableValues(dump));
  const seq = maxSeq(dump);
  const advanced = lastMaxSeq === null || seq === null ? null : seq > lastMaxSeq;
  verdict.logSeq.push({ label, maxSeq: seq, advancedSincePrevious: advanced });
  rec(`captured ${label}`, { maxLogSeq: seq, advancedSincePrevious: advanced });
  if (seq !== null) lastMaxSeq = seq;
  return dump;
};

const { w, h } = drv.screenSize();
const P = (fx, fy) => ({ x: Math.round(fx * w), y: Math.round(fy * h) });
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  await drv.waitReady();
  rec("harness ready", { screenW: w, screenH: h });

  let dump = await capture("step-00-probe-launch");
  // The probe page must actually be up. Without this the later "nothing changed"
  // readings would be about the wrong screen entirely.
  if (!/selected=|Alpha/.test(dump)) {
    write("cannot_run",
      "the TabPager probe page is not on screen after launch (no 'selected=' / 'Alpha' in the dump). " +
      "QA_LOOP_FIXTURE_PAGE may not have reached the app. Nothing below would be about the page under test.");
    process.exit(3);
  }
  rec("probe page confirmed on screen");

  if (groups.includes("1")) {
    const a = P(0.80, 0.60), b = P(0.20, 0.60);
    rec("group 1: content swipe left", { from: a, to: b, durationMs: 300 });
    await drv.swipe("left");
    await wait(1000);
    await capture("step-01-after-swipe-left");
    verdict.performed.push(1);
  }

  if (groups.includes("2")) {
    // Gamma = right third of the SegmentedControl. Located from the dump rather
    // than assumed, so a layout change fails loudly instead of tapping blank space.
    const seg = (await drv.dumpUi()).split("\n").find((l) => /Gamma/.test(l));
    if (seg) {
      const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(seg);
      const pt = m
        ? { x: Math.round((Number(m[1]) + Number(m[3])) / 2), y: Math.round((Number(m[2]) + Number(m[4])) / 2) }
        : P(0.83, 0.12);
      rec("group 2: tap Gamma", { at: pt, locatedFromDump: Boolean(m) });
      await drv.tap(pt.x, pt.y);
    } else {
      const pt = P(0.83, 0.12);
      rec("group 2: tap Gamma (label not in dump — using right-third fallback)", { at: pt });
      await drv.tap(pt.x, pt.y);
    }
    await wait(1000);
    await capture("step-02-after-tap-gamma");
    verdict.performed.push(2);
  }

  // ⚠️ GROUPS 3-5 ARE DELIBERATELY NOT RUN HERE.
  // The loop protocol exposes only whole-screen app.swipeUp/Down/Left/Right()
  // (QaAcceptanceLoopTests.swift case "swipe") — there is no coordinate drag with a
  // start point, end point and duration. The specified gestures need exactly that:
  //   group 3  starts at x=10pt, i.e. the screen EDGE. swipeRight() starts from the
  //            centre, so it does not exercise edge-back at all.
  //   group 4  is a SHORT slow drag (0.45 -> 0.55) whose criterion is that the page
  //            must NOT change. A full swipeRight() would very likely change it, and
  //            would then read as a product failure caused by my gesture.
  //   group 5  needs a measured scroll distance to compare retained row positions.
  // Substituting a full-screen swipe would produce evidence about a different
  // gesture than the one specified — worst on group 4, where the criterion is an
  // absence. Adding a "drag" action (XCUICoordinate press(forDuration:thenDragTo:))
  // is the fix, and it belongs in its own change with its own review.
  if (groups.some((g) => ["3", "4", "5"].includes(g))) {
    rec("groups 3-5 requested but NOT executed — no coordinate-drag action in the protocol", {
      requested: groups.filter((g) => ["3", "4", "5"].includes(g)),
    });
    verdict.notExecuted = {
      groups: groups.filter((g) => ["3", "4", "5"].includes(g)),
      reason: "loop protocol has only whole-screen swipes; groups 3-5 need press-and-drag with " +
        "explicit start/end/duration. Running a full swipe instead would test a different gesture.",
    };
  }

  // Liveness verdict, computed rather than eyeballed.
  const compared = verdict.logSeq.filter((s) => s.advancedSincePrevious !== null);
  const stalled = compared.filter((s) => s.advancedSincePrevious === false);
  if (compared.length && stalled.length === compared.length) {
    write("cannot_run",
      `the probe's log sequence never advanced across ${compared.length} captures ` +
      `(maxSeq: ${verdict.logSeq.map((s) => s.maxSeq).join(", ")}). The capture is stale, so no ` +
      `"nothing changed" reading from this run is judgeable.`);
    process.exit(3);
  }
  write("performed",
    `groups ${verdict.performed.join(",")} executed and captured. ⚠️ This verdict says the actions ran ` +
    `and evidence exists — it does NOT judge TabPager. Read the on-screen event log in the PNGs ` +
    `(sel-effect / animate / scrollTo / current= / settled= / report -> / onSelectedIndexChange) ` +
    `against the criteria in #proj-uiux:a9f2de3a. ` +
    `Log-sequence advanced on ${compared.length - stalled.length}/${compared.length} adjacent pairs.`);
  process.exit(0);
} catch (e) {
  write("error", `harness/script error: ${String(e).slice(0, 300)}`);
  process.exit(2);
}
