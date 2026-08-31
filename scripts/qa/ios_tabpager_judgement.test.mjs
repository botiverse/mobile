import test from "node:test";
import assert from "node:assert/strict";
import {
  lowestRow, rowBoundsFromDump, anyRowBounds, rowAtY,
  judgeG4, judgeG5, judgeG6, g6Attribution,
} from "./ios_tabpager_judgement.mjs";

// These tests exist because the probe's judgement was previously unreachable from any test
// (no exports, no main-guard => importing it drives a device). The specific defect this
// guards against: G6's `attribution` had two outcomes that could NEVER be produced, and it
// printed a plausible-sounding label instead, so nobody audited it. Hence the reachability
// enumerations below — for a derived label, "looks correct" is not the property; "every
// branch can actually be reached" is.

// ------------------------------------------------------------------ dump parsers

const DUMP = [
  'Other, "Alpha row 3", [10,700][300,760]',
  'Other, "Alpha row 4", [10,780][300,840]',
  'Other, "Gamma row 9", [10,900][300,960]',
].join("\n");

test("lowestRow returns the smallest row number, and null when there are none", () => {
  assert.equal(lowestRow(DUMP), 3);
  assert.equal(lowestRow("nothing here"), null);
});

test("rowBoundsFromDump is Alpha-only and sorted by y", () => {
  const rows = rowBoundsFromDump(DUMP);
  assert.deepEqual(rows.map((r) => r.row), [3, 4]);
  assert.ok(rows.every((r) => r.y1 < r.y2));
});

test("anyRowBounds is tab-agnostic — this is the whole reason it exists", () => {
  // group 4 runs on whatever tab the earlier groups left selected, so an Alpha-only
  // parser silently finds nothing there and the probe point falls back to a guess.
  const tabs = anyRowBounds(DUMP).map((r) => r.tab);
  assert.deepEqual(tabs, ["Alpha", "Alpha", "Gamma"]);
  assert.deepEqual(rowBoundsFromDump(DUMP).map((r) => r.row), [3, 4], "the Alpha-only parser must NOT see Gamma");
});

test("rowAtY distinguishes inside-text-bounds from nearest, and says which", () => {
  assert.deepEqual(rowAtY(DUMP, 730), { row: 3, containment: "inside-text-bounds" });
  const near = rowAtY(DUMP, 770); // in the padding gap between rows 3 and 4
  // The gap is carried INSIDE the containment string — `nearest(+10px)` — not as a separate
  // field. Pinned verbatim: an earlier draft of this module "tidied" it into
  // {containment:"nearest", gapPx} and the probe would have started emitting a different
  // shape into the evidence while every test here still passed.
  assert.match(near.containment, /^nearest\(\+\d+px\)$/);
  assert.equal(near.row, 3);
  // No rows at all is NOT null — it is a row-less reading that names itself, so the
  // evidence can distinguish "aimed at no row" from "we did not look".
  assert.deepEqual(rowAtY("no rows at all", 100), { row: null, containment: "no-rows-in-dump" });
});

// ------------------------------------------------------------------ group 4

test("G4: a null result is UNJUDGEABLE unless the SAME window proves liveness", () => {
  const dead = judgeG4({ selBefore: "1", selAfter: "1", liveness: { advanced: null, freshRowTaps: 0 } });
  assert.equal(dead.judgeable, false);
  assert.match(dead.reading, /UNJUDGEABLE/);
  // The point of the cell: "did not turn the page" and "never arrived" look identical.
  assert.match(dead.reading, /cannot be distinguished from/);
});

test("G4: liveness can be earned by either arm, and both are reachable", () => {
  const viaSeq = judgeG4({ selBefore: "1", selAfter: "1", liveness: { advanced: true, freshRowTaps: 0 } });
  const viaTap = judgeG4({ selBefore: "1", selAfter: "1", liveness: { advanced: null, freshRowTaps: 2 } });
  assert.equal(viaSeq.judgeable, true);
  assert.equal(viaTap.judgeable, true);
  for (const v of [viaSeq, viaTap]) assert.match(v.reading, /did NOT turn the page, and the window is proven live/);
});

test("G4: a page that DID turn is reported as the defect, not as a pass", () => {
  const moved = judgeG4({ selBefore: "1", selAfter: "2", liveness: { advanced: true } });
  assert.equal(moved.pagerMoved, true);
  assert.match(moved.reading, /page TURNED/);
});

// ------------------------------------------------------------------ group 5

test("G5-FIX: a MISSING starting-tab reading is reported as missing, not as 'never left'", () => {
  // Before this fix, startTab === null made leftTheTab null and the cell said "the pager
  // never left the starting tab" — sending the reader to investigate drag dispatch when
  // the real defect is that no `selected=` was captured.
  const v = judgeG5({ startTab: null, lowestRowStart: 9, lowestRowAfterScroll: 3, lowestRowOnReturn: 3 });
  assert.equal(v.judgeable, false);
  assert.match(v.reading, /no starting-tab reading/);
  assert.match(v.reading, /NOT a gesture that failed/);
  assert.doesNotMatch(v.reading, /never left the starting tab/,
    "a missing reading must not be phrased as a gesture that did not happen");
});

test("G5: every UNJUDGEABLE branch is reachable, and they are distinguishable", () => {
  const base = { startTab: "0", lowestRowStart: 9, lowestRowAfterScroll: 3, lowestRowOnReturn: 3, selectedAtOtherTab: "1", selectedOnReturn: "0" };
  const noScroll = judgeG5({ ...base, lowestRowAfterScroll: 9 });
  const noLeave  = judgeG5({ ...base, selectedAtOtherTab: "0" });
  const noReturn = judgeG5({ ...base, selectedOnReturn: "1" });
  assert.match(noScroll.reading, /never scrolled/);
  assert.match(noLeave.reading, /never left the starting tab/);
  assert.match(noReturn.reading, /did not return to the starting tab/);
  for (const v of [noScroll, noLeave, noReturn]) assert.equal(v.judgeable, false);
  // distinguishable => a reader can tell WHICH precondition failed
  assert.equal(new Set([noScroll.reading, noLeave.reading, noReturn.reading]).size, 3);
});

test("G5: retained and LOST are both reachable, and only when the round trip is proven", () => {
  const base = { startTab: "0", lowestRowStart: 9, selectedAtOtherTab: "1", selectedOnReturn: "0" };
  const kept = judgeG5({ ...base, lowestRowAfterScroll: 3, lowestRowOnReturn: 3 });
  const lost = judgeG5({ ...base, lowestRowAfterScroll: 3, lowestRowOnReturn: 9 });
  assert.equal(kept.judgeable, true);
  assert.equal(kept.retained, true);
  assert.match(kept.reading, /retained across leaving and returning/);
  assert.equal(lost.retained, false);
  assert.match(lost.reading, /scroll position LOST/);
});

// ------------------------------------------------------------------ group 6

test("G6 attribution: ALL SIX outcomes are reachable — the original defect was two that were not", () => {
  const inside = { row: 4 }, probe = { row: 9 };
  const cases = {
    none: g6Attribution({ distinctRows: [], insideExpectedRow: inside, probeExpectedRow: probe }),
    incomplete: g6Attribution({ distinctRows: ["4"], insideExpectedRow: inside, probeExpectedRow: null }),
    both: g6Attribution({ distinctRows: ["4", "9"], insideExpectedRow: inside, probeExpectedRow: probe }),
    insideOnly: g6Attribution({ distinctRows: ["4"], insideExpectedRow: inside, probeExpectedRow: probe }),
    probeOnly: g6Attribution({ distinctRows: ["9"], insideExpectedRow: inside, probeExpectedRow: probe }),
    unattributable: g6Attribution({ distinctRows: ["7"], insideExpectedRow: inside, probeExpectedRow: probe }),
  };
  assert.match(cases.none, /none — no rowTap at all/);
  assert.match(cases.incomplete, /INSTRUMENT INCOMPLETE/);
  assert.match(cases.both, /^both \(inside=4, probe=9\)/);
  assert.match(cases.insideOnly, /^inside only \(row 4\)/);
  assert.match(cases.probeOnly, /^probe only \(row 9\)/);
  assert.match(cases.unattributable, /^unattributable/);
  // The enumeration itself is the assertion: six inputs, six DISTINCT labels. `both` and
  // `probe only` were the two that could never be produced before, while the field still
  // printed a confident-looking answer.
  assert.equal(new Set(Object.values(cases)).size, 6, "every attribution outcome must be distinct and reachable");
});

test("G6 attribution: a missing aim record NAMES the missing input instead of guessing", () => {
  const both = g6Attribution({ distinctRows: ["4"], insideExpectedRow: null, probeExpectedRow: null });
  assert.match(both, /insideExpectedRow and probeExpectedRow missing/);
  assert.match(both, /judge from distinctRows, not from this field/);
  const onlyProbeMissing = g6Attribution({ distinctRows: ["4"], insideExpectedRow: { row: 4 }, probeExpectedRow: null });
  assert.match(onlyProbeMissing, /probeExpectedRow missing/);
  assert.doesNotMatch(onlyProbeMissing, /inside only/,
    "an unmeasured cell must not degrade into a measured negative");
});

test("G6: judgeable follows distinctRows, and zero rows is UNJUDGEABLE not a pass", () => {
  const zero = judgeG6({ liveness: { distinctRows: [] } });
  assert.equal(zero.judgeable, false);
  assert.match(zero.reading, /UNJUDGEABLE/);
  assert.match(zero.reading, /cannot be separated from/);

  const two = judgeG6({ liveness: { distinctRows: ["4", "9"], probeExpectedRow: { row: 9 } }, insideExpectedRow: { row: 4 } });
  assert.equal(two.judgeable, true);
  assert.match(two.reading, /BOTH taps landed/);

  const one = judgeG6({ liveness: { distinctRows: ["9"], probeExpectedRow: { row: 9 } }, insideExpectedRow: { row: 4 } });
  assert.equal(one.judgeable, true);
  assert.match(one.reading, /ONLY ONE tap landed — row 9/);
  assert.match(one.reading, /Do not infer from timing/);
});

test("G6: the liveness object is carried through, so attribution has an auditable source", () => {
  // The original defect was an object REPLACEMENT that dropped probeExpectedRow after it
  // had been assigned. Pin that the field survives into the verdict.
  const v = judgeG6({ liveness: { distinctRows: ["4"], probeExpectedRow: { row: 9 } }, insideExpectedRow: { row: 4 } });
  assert.deepEqual(v.liveness.probeExpectedRow, { row: 9 });
  assert.match(v.attribution, /inside only/);
});
