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
//     --ios-udid "$UDID" --package build.raft.app --out qa-evidence [--groups 1,2,3,4,5,6,7,8]

import fs from "node:fs";
import path from "node:path";
import { createIosDriver } from "./ios_driver.mjs";
import { redactEditableValues } from "./ios_navigation_matcher.mjs";
import {
  lowestRow, anyRowBounds, rowAtY,
  judgeG4, judgeG5, judgeG6,
} from "./ios_tabpager_judgement.mjs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const ioDir = arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || "");
const udid = arg("--ios-udid", "booted");
const bundle = arg("--package", "build.raft.app");
const outDir = arg("--out", "qa-evidence");
const groups = arg("--groups", "1,2,3,4,5,6,7,8").split(",").map((s) => s.trim()).filter(Boolean);
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
// Dump parsers and the g4/g5/g6 judgement live in ios_tabpager_judgement.mjs so they can
// be unit-tested: this file has no exports and no main-guard, so importing it would drive
// a device (task #251). Behaviour is unchanged except G5's no-starting-tab branch.

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
    // Left-edge yield: start hard against the left edge, i.e. the interactive-pop zone.
    // ⚠️ `w` is PIXELS (drv.screenSize()), so this is 10 px — about 3.3 pt on a 3x
    // device, NOT the "10pt" this line used to claim. The behaviour is unchanged and
    // the intent still holds (10 px is well inside the pop zone), but the number is
    // now described in the unit it is actually in, and the point value is recorded.
    const edgeX = 10 / w;
    const edgePt = drv.screenSizePt();
    rec("group 3: left-edge yield", {
      fromNormX: edgeX, fromPx: 10,
      fromPt: edgePt ? Number((10 / edgePt.scale).toFixed(2)) : null,
      pointScale: edgePt ? edgePt.scale : null,
    });
    const beforeDump = await drv.dumpUi();
    const seqBefore = maxSeq(beforeDump);
    const selBefore = (/selected=(\d+)/.exec(beforeDump) || [])[1] ?? null;
    await drv.drag({ from: { x: edgeX, y: 0.60 }, to: { x: 0.60, y: 0.60 }, durationSeconds: 0.35 });
    await wait(1000);
    const afterDump = await capture("step-03-after-edge-yield");
    const seqAfter = maxSeq(afterDump);
    const selAfter = (/selected=(\d+)/.exec(afterDump) || [])[1] ?? null;

    // ⚠️ IN-INTERVAL liveness. This group's criterion is "nothing happened", and run
    // 33369432716 showed why that is not self-supporting: step-02 and step-03 came
    // back byte-identical in BOTH channels (dump diff empty, screenshot md5 equal,
    // maxSeq 13->13). "the guard correctly claimed the drag so the pager did not
    // move" and "the gesture was never delivered at all" produce exactly that data.
    // Liveness of the ADJACENT steps does not cover this window — the element has to
    // sit INSIDE the interval whose conclusion is "no change". The usual fallback
    // anchor (status-bar clock) is unavailable: the fixture mechanism hides it.
    //
    // So: after the drag, issue an action that is KNOWN to leave a trace (a row tap —
    // group 6 established it writes `rowTap` and advances #N). If that trace appears,
    // the app was processing input during this window and "no change from the drag"
    // is a reading. If it does not, the whole window is unjudgeable and is reported
    // as such — NOT as a negative, and NOT as a pass.
    let liveness = { probe: "row-tap-after-drag", performed: false, seqBeforeProbe: seqAfter, seqAfterProbe: null, advanced: null };
    // Same inset point as group 6, derived here from points (edgePt.w is the width in
    // POINTS) — never `24 / w`, which is 24 pixels.
    const inset24Ready = edgePt ? 12 / edgePt.w : null;
    if (inset24Ready !== null) {
      const rowTapsPre = (afterDump.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
      await drv.tap(Math.round(inset24Ready * w), Math.round(0.60 * h));
      await wait(1000);
      const probeDump = await drv.dumpUi();
      const seqAfterProbe = maxSeq(probeDump);
      const rowTapsPost = (probeDump.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
      liveness = {
        probe: "row-tap-after-drag", performed: true,
        seqBeforeProbe: seqAfter, seqAfterProbe,
        advanced: seqAfter !== null && seqAfterProbe !== null ? seqAfterProbe > seqAfter : null,
        freshRowTaps: rowTapsPost - rowTapsPre,
      };
      fs.writeFileSync(path.join(outDir, "step-03b-liveness-probe-dump.txt"), redactEditableValues(probeDump));
    }
    const alive = liveness.advanced === true || (liveness.freshRowTaps ?? 0) > 0;
    verdict.g3 = {
      fromPt: edgePt ? Number((10 / edgePt.scale).toFixed(2)) : null,
      selBefore, selAfter,
      pagerMoved: selBefore !== null && selAfter !== null ? selBefore !== selAfter : null,
      seqBefore, seqAfter, seqAdvancedDuringDrag: seqBefore !== null && seqAfter !== null ? seqAfter > seqBefore : null,
      liveness,
      judgeable: alive,
      reading: !alive
        ? "UNJUDGEABLE — nothing in this window proves the app was processing input, so " +
          "'pager did not move' cannot be distinguished from 'the gesture never arrived'"
        : (selBefore === selAfter
            ? "pager did NOT move, and the window is proven live"
            : "pager MOVED (selected changed) — the guard did not claim the edge drag"),
    };
    rec("group 3: result", verdict.g3);
    verdict.performed.push(3);
  }

  if (groups.includes("4")) {
    // Short slow drag; criterion is that nothing changes. Slow on purpose — a
    // fast short flick can still be read as a page change.
    // The whole point of this group is SLOWNESS: a fast short drag is a flick and
    // a flick turning the page is correct behaviour, so the speed must be in the
    // record, not assumed. drv.drag returns the velocity it actually used.
    // ⚠️ Group 4's criterion is "nothing changes" — the same NULL-RESULT shape that made
    // group 3 unjudgeable, and it was carrying no in-interval liveness of its own. Run
    // 33379004411 looked live (maxSeq 13 -> 16 across this step), but all three lines were
    // `#14 rowTap Gamma 5` plus its settle bookkeeping — i.e. GROUP 3's liveness probe,
    // whose trace lands inside step-04's capture. The group-4 drag itself logs nothing.
    // Adjacent-step liveness does not cover this interval; "sel unchanged" was byte-identical
    // to "the drag was never delivered".
    //
    // It went unnoticed because nobody treated G4 as load-bearing until round-8. When a cell's
    // weight changes, instrumentation that was good enough stops being good enough.
    const selBefore4 = (/selected=(\d+)/.exec(await drv.dumpUi()) || [])[1] ?? null;
    const g4 = await drv.drag({ from: { x: 0.45, y: 0.60 }, to: { x: 0.55, y: 0.60 }, durationSeconds: 1.0 });
    rec("group 4: slow short drag (0.45,0.60) -> (0.55,0.60)", g4);
    verdict.gestureG4 = g4;
    await wait(1000);
    const afterDrag4 = await drv.dumpUi();
    const selAfter4 = (/selected=(\d+)/.exec(afterDrag4) || [])[1] ?? null;
    const seqAfter4 = maxSeq(afterDrag4);
    const rowTapsPre4 = (afterDrag4.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
    await capture("step-04-after-short-drag");

    // In-interval, attributable liveness. The point is DERIVED from the dump, not guessed:
    // run 33385954957 tapped a hardcoded y=0.28 -> 709px, while the topmost row began at
    // 765px, so the tap landed in the header and produced no rowTap at all. The cell reported
    // UNJUDGEABLE, which is the design working — but the probe still needs to hit a row.
    // Aim at the middle of a row whose bounds are read from this dump, and record which row,
    // so the resulting `rowTap <tab> <n>` names its own producer.
    const rows4 = anyRowBounds(afterDrag4);
    const target4 = rows4.length ? rows4[Math.min(1, rows4.length - 1)] : null;
    const probeY4 = target4 ? Math.round((target4.y1 + target4.y2) / 2) : Math.round(0.28 * h);
    rec("group 4: liveness probe aim", { target: target4, yPx: probeY4, rowsSeen: rows4.length });
    await drv.tap(Math.round(0.50 * w), probeY4);
    await wait(1000);
    const probe4 = await drv.dumpUi();
    const seqAfterProbe4 = maxSeq(probe4);
    const rowTapsPost4 = (probe4.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
    fs.writeFileSync(path.join(outDir, "step-04b-liveness-probe-dump.txt"), redactEditableValues(probe4));
    const liveness4 = {
      probe: "row-tap-after-slow-drag-derived-row", performed: true,
      probeExpectedRow: target4, probeYPx: probeY4, rowsVisible: rows4.length,
      seqBeforeProbe: seqAfter4, seqAfterProbe: seqAfterProbe4,
      advanced: seqAfter4 !== null && seqAfterProbe4 !== null ? seqAfterProbe4 > seqAfter4 : null,
      freshRowTaps: rowTapsPost4 - rowTapsPre4,
    };
    const alive4 = liveness4.advanced === true || (liveness4.freshRowTaps ?? 0) > 0;
    verdict.g4 = judgeG4({ selBefore: selBefore4, selAfter: selAfter4, gesture: g4, liveness: liveness4 });
    rec("group 4: result", verdict.g4);
    verdict.performed.push(4);
  }

  if (groups.includes("5")) {
    // ⚠️ Pin the starting tab first. Run 33163848659 scrolled whichever tab group 4
    // happened to leave selected — that was Gamma, the LAST tab, so the "leave"
    // leg had nowhere to go and the "return" landed on a different tab that had
    // never been scrolled. The reading looked like lost scroll position and was
    // actually a sequence that never performed the round trip. Retention has to
    // be tested on a tab that has a neighbour to leave to.
    const alphaLine = (await drv.dumpUi()).split("\n").find((l) => /"Alpha"/.test(l));
    if (alphaLine) {
      const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(alphaLine);
      if (m) {
        const pt = { x: Math.round((Number(m[1]) + Number(m[3])) / 2), y: Math.round((Number(m[2]) + Number(m[4])) / 2) };
        await drv.tap(pt.x, pt.y);
        await wait(1200);
        rec("group 5: pinned start tab to Alpha", { at: pt });
      }
    }
    const startDump = await drv.dumpUi();
    const startSel = (/selected=(\d+)/.exec(startDump) || [])[1];
    verdict.g5StartTab = startSel ?? null;
    rec("group 5: scroll retention — two upward drags, leave tab, return", { startTab: startSel });
    // ⚠️ Group 5 recorded NOTHING except the starting tab — three screenshots and a human.
    // @LiBai: it is the same null-result class as group 4 and weaker, because half of it is
    // self-evidencing and half is not. The scroll half shows up in row numbers; the
    // load-bearing half (leave the tab, come back, position kept) had nothing proving the
    // LEAVE happened. If both horizontal drags failed to dispatch, the run never leaves the
    // tab, 05c is naturally identical to 05a, and "scroll position retained" reads as PASS.
    // "Position was kept" and "we never left" are identical in the evidence.
    // A half-self-evidencing criterion is worse than none: the visible half lends the whole
    // cell an air of positive evidence.
    const rowStart5 = lowestRow(startDump);
    for (const n of [1, 2]) {
      await drv.drag({ from: { x: 0.5, y: 0.75 }, to: { x: 0.5, y: 0.30 }, durationSeconds: 0.3 });
      rec(`group 5: scroll drag ${n}/2`);
      await wait(500);
    }
    const dumpA = await drv.dumpUi();
    await capture("step-05a-after-scroll");
    const rowA = lowestRow(dumpA);

    await drv.drag({ from: { x: 0.80, y: 0.60 }, to: { x: 0.20, y: 0.60 }, durationSeconds: 0.3 });
    await wait(1000);
    const dumpB = await drv.dumpUi();
    await capture("step-05b-next-tab");
    const selB = (/selected=(\d+)/.exec(dumpB) || [])[1] ?? null;

    await drv.drag({ from: { x: 0.20, y: 0.60 }, to: { x: 0.80, y: 0.60 }, durationSeconds: 0.3 });
    await wait(1000);
    const dumpC = await drv.dumpUi();
    await capture("step-05c-returned");
    const rowC = lowestRow(dumpC);
    const selC = (/selected=(\d+)/.exec(dumpC) || [])[1] ?? null;

    const scrolled5 = rowStart5 !== null && rowA !== null ? rowA !== rowStart5 : null;
    const left5 = selB !== null && startSel !== undefined ? selB !== startSel : null;
    const returned5 = selC !== null && startSel !== undefined ? selC === startSel : null;
    // `left5` is the one that turns "we came back to the same place" from an assumption into
    // a reading. Without it the cell asserts "nothing changed across two drags", which is
    // trivially true whenever the drags do nothing.
    verdict.g5 = judgeG5({
      startTab: startSel ?? null,
      lowestRowStart: rowStart5, lowestRowAfterScroll: rowA, lowestRowOnReturn: rowC,
      selectedAtOtherTab: selB, selectedOnReturn: selC,
    });
    rec("group 5: result", verdict.g5);
    verdict.performed.push(5);
  }

  // Groups 6-7 (task #246 / PR #2224 review): the edge guard must claim ONLY
  // horizontal drags. Group 3 proves it yields to an edge drag; these two prove
  // it does not swallow the other two things that start in the same inset — a
  // stationary tap, and a vertical drag.
  //
  // ⚠️ UNITS. These are specified in POINTS, and drv.screenSize() is PIXELS.
  // `24 / screenSize().w` would ask for 24 px = 8 pt on a 3x device, i.e. a
  // different inset than the one the case is named after. inset24 is derived
  // from screenSizePt() and the resulting point/pixel/normalized values are all
  // recorded, so where it actually touched is readable from the evidence rather
  // than inferred from the case name.
  const pt = drv.screenSizePt();
  // ⚠️ NOT 24. The iOS start inset IS 24dp (slockPlatformTabPagerStartEdgeInsetDp)
  // and EdgeGuard renders it as Box(Modifier.width(24.dp)) — a region of [0,24),
  // i.e. pixels 0..71 at 3x. Touching at exactly 24pt lands on pixel 72, the first
  // pixel OUTSIDE the guard, so run 33369432716's groups 6/7 proved only that a
  // touch beside the guard reaches the row — which was never in question.
  // 12pt (pixel 36) is the midpoint of the strip: unambiguously inside, and far
  // enough from both ends that ±1px rounding and the half-open bound cannot move
  // it out. The case name must never be the same number as the region width.
  const insetPt = 12;
  const inset24 = pt ? insetPt / pt.w : null;

  if (groups.includes("6") && inset24 !== null) {
    // A stationary tap at x=24pt must reach the row underneath and emit
    // `rowTap <Label> <row>`. Rows are fillMaxWidth().clickable, so the only
    // thing that can eat this tap is the guard.
    const before = await drv.dumpUi();
    const rowTapsBefore = (before.match(/rowTap [A-Za-z]+ \d+/g) || []);
    const seqBefore = maxSeq(before);
    rec("group 6: stationary tap inside the edge inset", {
      insetPt, normX: inset24, pxX: Math.round(inset24 * w), pointScale: pt.scale,
      rowTapsBefore: rowTapsBefore.length,
    });
    const insideYPx = Math.round(0.60 * h);
    const insideExpected = rowAtY(before, insideYPx);
    rec("group 6: inside tap aim", { xPx: Math.round(inset24 * w), yPx: insideYPx, expected: insideExpected });
    await drv.tap(Math.round(inset24 * w), insideYPx);
    await wait(1200);
    const after = await drv.dumpUi();
    const rowTapsAfter = (after.match(/rowTap [A-Za-z]+ \d+/g) || []);
    const seqAfter = maxSeq(after);
    // Report the DELTA, not the presence: earlier groups may already have left
    // rowTap lines in the log, and "a rowTap line exists" would then be true
    // regardless of whether this tap did anything.
    const fresh = rowTapsAfter.slice(rowTapsBefore.length);
    // Liveness control for the absence half: if the log did not advance at all,
    // "no rowTap" is indistinguishable from a stale capture and is NOT evidence.
    const advanced = seqBefore !== null && seqAfter !== null ? seqAfter > seqBefore : null;
    // ⚠️ IN-INTERVAL liveness, added after run 33372287234. That run showed the
    // 12pt tap leaving NO trace at all (no rowTap, seq 30->30, dump byte-identical
    // to the previous step) — which is what a guard swallowing the down event looks
    // like AND what a tap that never arrived looks like. Reporting "no rowTap" as a
    // product failure on that evidence would convict the product on an ambiguity;
    // reporting it as a pass would hide a real one.
    //
    // So probe with a tap OUTSIDE the strip (36pt, px 108 — group 8's arm), which is
    // not the thing under test and is expected to reach a row. If the outside tap
    // registers while the inside tap did not, input was being delivered and the
    // difference is the guard: that is a real finding. If the outside tap also
    // leaves nothing, the whole window is unjudgeable and the product is not blamed.
    let g6Liveness = { probe: "outside-arm-tap-36pt", performed: false, advanced: null, freshRowTaps: null };
    const outsideN = 36 / pt.w;
    {
      // ⚠️ DIFFERENT ROW, deliberately. Run 33374638591 put both taps at y=0.60, so both
      // would emit `rowTap Alpha 41` and the only way to attribute the single line that
      // appeared was WHICH SAMPLE FIRST SAW IT — i.e. timing. That is not attribution:
      // an inside tap whose log lands later than its own 1200ms sample is indistinguishable
      // from an outside tap producing it, and the verdict string claimed the guard had
      // swallowed the tap on exactly that ambiguity. Probing a different row makes the
      // label itself say who produced it.
      const probeY = 0.35;
      const preDump = await drv.dumpUi();
      fs.writeFileSync(path.join(outDir, "step-06a-after-inside-tap-dump.txt"), redactEditableValues(preDump));
      const preTaps = (preDump.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
      const preSeq = maxSeq(preDump);
      const probeYPx = Math.round(probeY * h);
      const probeExpected = rowAtY(preDump, probeYPx);
      rec("group 6: probe aim", { xPx: Math.round(outsideN * w), yPx: probeYPx, expected: probeExpected });
      g6Liveness.probeExpectedRow = probeExpected;
      await drv.tap(Math.round(outsideN * w), probeYPx);
      await wait(1200);
      const postDump = await drv.dumpUi();
      const postTaps = (postDump.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
      const postSeq = maxSeq(postDump);
      // ⚠️ REPLACES the object, so anything assigned onto g6Liveness above this point is
      // destroyed. `probeExpectedRow` was set ~6 lines up and silently lost that way in run
      // 33379004411: the attribution below reads it, so `prR` was always null, `hitProbe`
      // always false, and the `both` / `probe only` branches were UNREACHABLE — the whole
      // point of the round-7 attribution fix had never executed once. It still printed a
      // plausible, favourable verdict ("inside only — the guard let the tap through"), which
      // is why nothing flagged it. Carry the field through explicitly; do not re-introduce
      // an assign-then-replace pair here.
      g6Liveness = {
        probe: "outside-arm-tap-36pt-different-row", performed: true,
        probeExpectedRow: probeExpected,
        outsidePt: 36, outsidePx: Math.round(outsideN * w), probeY: 0.35,
        insideRowsAtProbeTime: (preDump.match(/rowTap [A-Za-z]+ \d+/g) || []),
        rowsAfterProbe: (postDump.match(/rowTap [A-Za-z]+ \d+/g) || []),
        seqBefore: preSeq, seqAfter: postSeq,
        advanced: preSeq !== null && postSeq !== null ? postSeq > preSeq : null,
        freshRowTaps: postTaps - preTaps,
      };
      fs.writeFileSync(path.join(outDir, "step-06b-outside-arm-dump.txt"), redactEditableValues(postDump));
    }
    // Attribution by LABEL, not by which sample first saw a line. The inside tap is at
    // y=0.60 and the probe at y=0.35, so they hit different rows and emit different
    // `rowTap Alpha <n>` lines. A line that appears late is still attributable, because
    // its row number says who produced it — which is exactly what the previous design
    // could not do.
    const rowsAll = (g6Liveness.rowsAfterProbe || []);
    const rowNums = rowsAll.map((l) => Number(l.split(" ")[2]));
    const insideRowGuess = fresh.map((l) => Number(l.split(" ")[2]));
    // Everything visible by the end, minus what was already there before the inside tap.
    const beforeCount = (before.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
    const producedThisGroup = rowsAll.slice(beforeCount);
    g6Liveness.producedThisGroup = producedThisGroup;
    g6Liveness.distinctRows = [...new Set(producedThisGroup.map((l) => l.split(" ")[2]))];
    // Two distinct rows => both taps landed. One row => whichever row it is tells us which
    // tap landed. Zero => nothing landed at all and the window is unjudgeable.
    const outsideWorked = producedThisGroup.length > 0;
    verdict.g6 = judgeG6({
      insetPt, normX: inset24, pointScale: pt.scale,
      insideExpectedRow: insideExpected,
      freshRowTaps: fresh,
      seqBefore, seqAfter, logAdvanced: advanced,
      liveness: g6Liveness,
    });
    rec("group 6: result", verdict.g6);
    await capture("step-06-after-inset-tap");
    verdict.performed.push(6);
  }

  if (groups.includes("7") && inset24 !== null) {
    // A vertical drag starting in the same inset must scroll the list. Read the
    // lowest visible row index before/after: the rows render "<Label> row N", so
    // the scroll is a number, not an impression from the screenshot.
    const before = await drv.dumpUi();
    const rowBefore = lowestRow(before);
    const g7 = await drv.drag({ from: { x: inset24, y: 0.75 }, to: { x: inset24, y: 0.30 }, durationSeconds: 0.5 });
    await wait(1200);
    const after = await drv.dumpUi();
    const rowAfter = lowestRow(after);
    verdict.g7 = {
      insetPt, normX: inset24, pointScale: pt.scale,
      lowestRowBefore: rowBefore, lowestRowAfter: rowAfter,
      scrolled: rowBefore !== null && rowAfter !== null ? rowAfter > rowBefore : null,
      gesture: g7,
    };
    rec("group 7: vertical drag inside the edge inset", verdict.g7);
    await capture("step-07-after-inset-vdrag");
    verdict.performed.push(7);
  }
  if (groups.includes("8") && pt) {
    // Outside arm for group 6. A two-armed comparison needs BOTH arms' side to be
    // known, and both far enough from the boundary that neither rounding nor the
    // half-open bound can move them: 12pt (px 36) sits mid-strip, 36pt (px 108) is
    // a full 36px clear of it. Run 33369432716's 24pt touch is not reusable as this
    // arm — it aimed at exactly px 72, the boundary, so which side it hit is
    // unmeasured (@LiBai). An arm of unknown side cannot serve as either arm.
    // ⚠️ Under the CURRENT implementation both arms are expected to reach the row,
    // so this pair does NOT discriminate a working guard from a neutered one — it
    // discriminates the OLD bug, where the strip was dead to taps. Recorded as such,
    // not as evidence that the guard behaves correctly.
    const outsidePt = 36;
    const outsideN = outsidePt / pt.w;
    const before = await drv.dumpUi();
    const tapsBefore = (before.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
    const seqB = maxSeq(before);
    await drv.tap(Math.round(outsideN * w), Math.round(0.60 * h));
    await wait(1200);
    const after = await drv.dumpUi();
    const tapsAfter = (after.match(/rowTap [A-Za-z]+ \d+/g) || []).length;
    const seqA = maxSeq(after);
    verdict.g8 = {
      arm: "outside-the-inset", outsidePt, normX: outsideN,
      pxX: Math.round(outsideN * w), insetPx: Math.round(24 * pt.scale),
      freshCount: tapsAfter - tapsBefore,
      seqBefore: seqB, seqAfter: seqA,
      logAdvanced: seqB !== null && seqA !== null ? seqA > seqB : null,
      judgeable: (tapsAfter - tapsBefore) > 0 || (seqB !== null && seqA !== null && seqA > seqB),
      note: "pairs with group 6 (12pt, inside). Both arms are expected to pass under the " +
            "current implementation; the pair only discriminates the pre-fix guard.",
    };
    rec("group 8: outside-arm tap", verdict.g8);
    await capture("step-08-after-outside-tap");
    verdict.performed.push(8);
  }

  if ((groups.includes("6") || groups.includes("7")) && inset24 === null) {
    rec("groups 6/7 SKIPPED: point scale unknown (no describe yet) — refusing to " +
        "guess a pixel inset for a case specified in points");
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
