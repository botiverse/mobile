// Judgement logic for the iOS TabPager probe, extracted so it can be TESTED.
//
// Why this module exists (task #251): ios_tabpager_probe.mjs has no exports and no
// main-guard, so importing it EXECUTES it — it starts driving a device. That makes its
// verdict logic unreachable from a test, which is how G6's attribution field once shipped
// with two of its outcomes structurally unreachable while printing a plausible label
// (:361 assigned it, :367 replaced the whole object). Reading the code is not evidence;
// a test that asserts on the probe's source text would be a tautological guard. The only
// honest fix is to make the judgement importable and mutate it.
//
// Everything here is a PURE function of readings already collected. No I/O, no driver.
// Behaviour is preserved verbatim from the probe with ONE deliberate change, marked
// G5-FIX below.

// ---------------------------------------------------------------- dump parsers

// Hoisted out of group 7 so group 5 can record the same quantity. Group 5's retention
// criterion is about row positions, and it had no way to state one.
export const lowestRow = (dump) => {
  const ns = (dump.match(/row (\d+)/g) || []).map((s) => Number(s.slice(4)));
  return ns.length ? Math.min(...ns) : null;
};

// Which row does a y (in PIXELS) fall on, read from the dump's own bounds? Recording
// this at tap time is what makes `rowTap Alpha <n>` self-attributing without any
// inference about row height, visible-row count, or scroll state between groups
// (@LiBai: an arithmetic attribution rests on premises nobody verified; a recorded
// expectation is a lookup). Returns null when no row's bounds contain y — which is
// itself worth recording, because it means the tap was not aimed at a row at all.
export const rowBoundsFromDump = (dump) => {
  const out = [];
  for (const line of dump.split("\n")) {
    const m = /Alpha row (\d+)"?.*\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
    if (m) out.push({ row: Number(m[1]), y1: Number(m[3]), y2: Number(m[5]) });
  }
  return out.sort((a, b) => a.y1 - b.y1);
};

// Tab-agnostic sibling of rowBoundsFromDump. That one hardcodes "Alpha row", which is right
// for group 6 (pinned to Alpha) and wrong for group 4, which runs on whatever tab the earlier
// groups left selected — Gamma, in run 33385954957. Kept separate rather than generalising the
// original: a HorizontalPager can render an adjacent page, so a tab-agnostic match could mix
// two tabs' rows together and perturb group 6, which currently passes.
export const anyRowBounds = (dump) => {
  const out = [];
  for (const line of dump.split("\n")) {
    const m = /"(\w+) row (\d+)"?.*\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
    if (m) out.push({ tab: m[1], row: Number(m[2]), y1: Number(m[4]), y2: Number(m[6]) });
  }
  return out.sort((a, b) => a.y1 - b.y1);
};

export const rowAtY = (dump, yPx) => {
  const rows = rowBoundsFromDump(dump);
  const exact = rows.find((r) => yPx >= r.y1 && yPx <= r.y2);
  if (exact) return { row: exact.row, containment: "inside-text-bounds" };
  // Text bounds are inset by the row's padding, so a y can fall in a row's clickable
  // area yet outside its text. Attribute to the nearest row and SAY it was nearest,
  // so the distinction survives into the evidence instead of being smoothed over.
  let best = null;
  for (const r of rows) {
    const d = yPx < r.y1 ? r.y1 - yPx : yPx > r.y2 ? yPx - r.y2 : 0;
    if (!best || d < best.d) best = { row: r.row, d };
  }
  return best ? { row: best.row, containment: `nearest(+${best.d}px)` } : { row: null, containment: "no-rows-in-dump" };
};

// ---------------------------------------------------------------- group 4

// A null result ("the drag did not turn the page") is byte-identical to a frozen capture
// unless something in the SAME interval proves the app was still processing input. That
// is what `liveness` carries; without it this cell is unjudgeable, not passing.
export function judgeG4({ selBefore = null, selAfter = null, gesture = null, liveness = {} } = {}) {
  const alive = liveness.advanced === true || (liveness.freshRowTaps ?? 0) > 0;
  return {
    selBefore, selAfter,
    pagerMoved: selBefore !== null && selAfter !== null ? selBefore !== selAfter : null,
    gesture, liveness, judgeable: alive,
    reading: !alive
      ? "UNJUDGEABLE — nothing in this window proves the app was processing input, so " +
        "'the slow short drag did not turn the page' cannot be distinguished from " +
        "'the drag never arrived'"
      : (selBefore === selAfter
          ? "slow short drag did NOT turn the page, and the window is proven live"
          : "page TURNED on a slow short drag — below-threshold drag was treated as a page change"),
  };
}

// ---------------------------------------------------------------- group 5

// `leftTheTab` is the one that turns "we came back to the same place" from an assumption
// into a reading. Without it the cell asserts "nothing changed across two drags", which is
// trivially true whenever the drags do nothing.
export function judgeG5({
  startTab = null,
  lowestRowStart = null, lowestRowAfterScroll = null, lowestRowOnReturn = null,
  selectedAtOtherTab = null, selectedOnReturn = null,
} = {}) {
  const scrolled = lowestRowStart !== null && lowestRowAfterScroll !== null
    ? lowestRowAfterScroll !== lowestRowStart : null;
  const left = selectedAtOtherTab !== null && startTab !== null
    ? selectedAtOtherTab !== startTab : null;
  const returned = selectedOnReturn !== null && startTab !== null
    ? selectedOnReturn === startTab : null;
  return {
    startTab,
    lowestRowStart, lowestRowAfterScroll, lowestRowOnReturn,
    selectedAtOtherTab, selectedOnReturn,
    scrolled, leftTheTab: left, returnedToStartTab: returned,
    retained: lowestRowAfterScroll !== null && lowestRowOnReturn !== null
      ? lowestRowAfterScroll === lowestRowOnReturn : null,
    judgeable: startTab !== null && scrolled === true && left === true && returned === true,
    // G5-FIX (task #251): a MISSING starting-tab reading must be reported as a missing
    // reading, not as a gesture that did not happen. Previously startTab absent made
    // `left` null and the cell reported "the pager never left the starting tab" — which
    // sends the reader to investigate horizontal drag dispatch when the actual defect is
    // that no `selected=` was captured. This branch is ordered FIRST for that reason.
    reading: startTab === null
      ? "UNJUDGEABLE — no starting-tab reading was captured, so neither leaving nor " +
        "returning can be established. This is a missing reading, NOT a gesture that failed."
      : scrolled !== true
        ? "UNJUDGEABLE — the list never scrolled, so there is no position to retain"
        : left !== true
          ? "UNJUDGEABLE — the pager never left the starting tab, so 'position retained' cannot " +
            "be distinguished from 'we never went anywhere'"
          : returned !== true
            ? "UNJUDGEABLE — did not return to the starting tab, so the comparison is against a different tab"
            : (lowestRowAfterScroll === lowestRowOnReturn
                ? "scroll position retained across leaving and returning, and the round trip is proven"
                : `scroll position LOST — row ${lowestRowAfterScroll} before leaving, row ${lowestRowOnReturn} on return`),
  };
}

// ---------------------------------------------------------------- group 6

// Attribution is a LOOKUP against the row each tap was aimed at, recorded from the dump at
// tap time — no inference about row height, visible-row count, or whether anything scrolled.
export function g6Attribution({ distinctRows, insideExpectedRow, probeExpectedRow }) {
  const rows = (distinctRows || []).map(Number);
  const inR = insideExpectedRow?.row;
  const prR = probeExpectedRow?.row;
  if (!rows.length) return "none — no rowTap at all";
  // A missing aim record must not degrade into a confident label. Previously prR was
  // silently null and every run fell through to "inside only", which reads as a finding
  // rather than as a broken instrument. Name the missing input instead: an unmeasured
  // cell is not a measured negative.
  if (inR == null || prR == null) {
    return `INSTRUMENT INCOMPLETE — cannot attribute: ${inR == null ? "insideExpectedRow" : ""}` +
      `${inR == null && prR == null ? " and " : ""}${prR == null ? "probeExpectedRow" : ""}` +
      ` missing. Saw rows ${rows.join(",")}; judge from distinctRows, not from this field.`;
  }
  const hitInside = rows.includes(inR);
  const hitProbe = rows.includes(prR);
  if (hitInside && hitProbe) return `both (inside=${inR}, probe=${prR})`;
  if (hitInside) return `inside only (row ${inR}) — the guard let the tap through`;
  if (hitProbe) return `probe only (row ${prR}) — input was live, the inside tap was swallowed`;
  return `unattributable — saw ${rows.join(",")}, expected inside=${inR} probe=${prR}`;
}

export function judgeG6({
  insetPt = null, normX = null, pointScale = null,
  insideExpectedRow = null, freshRowTaps = [],
  seqBefore = null, seqAfter = null, logAdvanced = null,
  liveness = {},
} = {}) {
  const distinct = liveness.distinctRows;
  return {
    insetPt, normX, pointScale,
    insideExpectedRow,
    freshRowTaps, freshCount: freshRowTaps.length,
    seqBefore, seqAfter, logAdvanced,
    liveness,
    judgeable: distinct ? distinct.length > 0 : false,
    attribution: g6Attribution({
      distinctRows: distinct,
      insideExpectedRow,
      probeExpectedRow: liveness.probeExpectedRow,
    }),
    reading: !distinct || distinct.length === 0
      ? "UNJUDGEABLE — neither tap produced a rowTap line, so 'the guard swallowed it' " +
        "cannot be separated from 'no touch was delivered at all'"
      : distinct.length >= 2
        ? "BOTH taps landed (two distinct rows) => the guard does not swallow a stationary " +
          "tap inside the inset"
        : "ONLY ONE tap landed — row " + distinct[0] + ". Attribute it by row: " +
          "the inside tap was at y=0.60, the probe at y=0.35. If the single row is the PROBE's, " +
          "input was being delivered and the inside tap was swallowed (blocker 2 NOT fixed); " +
          "if it is the INSIDE tap's, the guard is fine and the probe is what failed to land. " +
          "Do not infer from timing.",
  };
}
