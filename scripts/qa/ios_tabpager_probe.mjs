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
//     --ios-udid "$UDID" --package build.raft.app --out qa-evidence [--groups 1,2,3,4,5]

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
    rec("group 1: content drag left (0.80,0.60) -> (0.20,0.60) over 300ms");
    await drv.drag({ from: { x: 0.80, y: 0.60 }, to: { x: 0.20, y: 0.60 }, durationSeconds: 0.3 });
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

  // Groups 3-5 now use the drag action added for this case. Before it existed
  // these were skipped rather than approximated with a whole-screen swipe: a
  // centre-origin flick never touches the screen edge, and it would almost
  // certainly move the page in group 4 — whose criterion is that the page must
  // NOT move. Substituting the gesture would have produced a product "failure"
  // manufactured by the harness.
  if (groups.includes("3")) {
    // Left-edge yield: start at x=10pt (absolute), i.e. the interactive-pop zone.
    const edgeX = 10 / w;
    rec("group 3: left-edge yield", { fromNormX: edgeX, note: "x=10pt absolute" });
    await drv.drag({ from: { x: edgeX, y: 0.60 }, to: { x: 0.60, y: 0.60 }, durationSeconds: 0.35 });
    await wait(1000);
    await capture("step-03-after-edge-yield");
    verdict.performed.push(3);
  }

  if (groups.includes("4")) {
    // Short slow drag; criterion is that nothing changes. Slow on purpose — a
    // fast short flick can still be read as a page change.
    // The whole point of this group is SLOWNESS: a fast short drag is a flick and
    // a flick turning the page is correct behaviour, so the speed must be in the
    // record, not assumed. drv.drag returns the velocity it actually used.
    const g4 = await drv.drag({ from: { x: 0.45, y: 0.60 }, to: { x: 0.55, y: 0.60 }, durationSeconds: 1.0 });
    rec("group 4: slow short drag (0.45,0.60) -> (0.55,0.60)", g4);
    verdict.gestureG4 = g4;
    await wait(1000);
    await capture("step-04-after-short-drag");
    verdict.performed.push(4);
  }

  if (groups.includes("5")) {
    rec("group 5: scroll retention — two upward drags, leave tab, return");
    for (const n of [1, 2]) {
      await drv.drag({ from: { x: 0.5, y: 0.75 }, to: { x: 0.5, y: 0.30 }, durationSeconds: 0.3 });
      rec(`group 5: scroll drag ${n}/2`);
      await wait(500);
    }
    await capture("step-05a-after-scroll");
    await drv.drag({ from: { x: 0.80, y: 0.60 }, to: { x: 0.20, y: 0.60 }, durationSeconds: 0.3 });
    await wait(1000);
    await capture("step-05b-next-tab");
    await drv.drag({ from: { x: 0.20, y: 0.60 }, to: { x: 0.80, y: 0.60 }, durationSeconds: 0.3 });
    await wait(1000);
    await capture("step-05c-returned");
    verdict.performed.push(5);
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
