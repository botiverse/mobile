import assert from "node:assert/strict";
import test from "node:test";
import {
  composerHasNewPreview,
  countThreadImageNodes,
  diffUiNodeStates,
  exactCellCandidates,
  findAllChannelTarget,
  findComposerImageTarget,
  findPhotoCellTarget,
  hasPhotoPickerGate,
  hasRealPhotoPicker,
  photosFullAccessAlertDecision,
  redactEditableValues,
  targetTrackingCandidates,
  uiNodeSignature,
} from "./ios_navigation_matcher.mjs";

const header = '"CHANNELS" [90,600][270,650]';

test("finds a single-node #all channel row", () => {
  const target = findAllChannelTarget(`${header}\n"#all" [110,680][260,740]`);
  assert.equal(target.mode, "single-node");
  assert.deepEqual({ x: target.x, y: target.y }, { x: 185, y: 710 });
});

test("finds split # icon plus all text", () => {
  const target = findAllChannelTarget([
    header,
    '"#" [72,680][102,740]',
    '"all" [120,680][220,740]',
  ].join("\n"));
  assert.equal(target.mode, "split-node");
  assert.match(target.splitHashLine, /^"#"/);
  assert.deepEqual({ x: target.x, y: target.y }, { x: 170, y: 710 });
});

test("ignores message-body all and #all outside the CHANNELS band", () => {
  const target = findAllChannelTarget([
    header,
    '"#" [72,680][102,740]',
    '"all" [120,680][220,740]',
    '"all" [350,1300][430,1360]',
    '"#all" [250,1450][360,1510]',
  ].join("\n"));
  assert.equal(target.y, 710);
});

test("fails closed when only an out-of-band all node exists", () => {
  assert.throws(
    () => findAllChannelTarget(`${header}\n"all" [350,1300][430,1360]`),
    /found 0/,
  );
});

test("fails closed when duplicate all targets exist in the CHANNELS band", () => {
  assert.throws(
    () => findAllChannelTarget([
      header,
      '"all" [120,680][220,740]',
      '"all" [120,770][220,830]',
    ].join("\n")),
    /found 2/,
  );
});

test("redacts plain and secure editable values while preserving flags and geometry", () => {
  const redacted = redactEditableValues([
    '[INPUT] "account name with spaces" [138,810][1008,888]',
    '[INPUT] [FOCUSED] "secure \\"quoted\\" value ••••" [138,1044][1008,1122]',
    '"Saved" [100,100][200,150]',
  ].join("\n"));
  assert.equal(redacted, [
    '[INPUT] "[REDACTED]" [138,810][1008,888]',
    '[INPUT] [FOCUSED] "[REDACTED]" [138,1044][1008,1122]',
    '"Saved" [100,100][200,150]',
  ].join("\n"));
});

test("recognizes an app-scoped photo picker dump", () => {
  assert.equal(hasPhotoPickerGate({ appDump: '"Recents" [0,0][1170,200]' }), true);
});

test("recognizes the system-owned Photos permission alert from unified log", () => {
  assert.equal(hasPhotoPickerGate({
    appDump: '"Message #all" [24,2161][1140,2382]',
    systemLog: 'controllerTitle = “Raft” would like full access to your Photo Library.;',
  }), true);
});

test("does not treat an unchanged composer dump or unrelated log as a picker gate", () => {
  assert.equal(hasPhotoPickerGate({
    appDump: '"Message #all" [24,2161][1140,2382]',
    systemLog: "network request completed",
  }), false);
});

test("derives the image target from real composer bounds when icon nodes are absent", () => {
  const target = findComposerImageTarget([
    '[BTN] "" [0,2071][1170,2119]',
    '"Message #all" [24,2161][1140,2382]',
  ].join("\n"));
  assert.equal(target.mode, "composer-relative");
  assert.deepEqual({ x: target.x, y: target.y }, { x: 95, y: 2311 });
});

test("prefers the leftmost contained semantic button over geometry fallback", () => {
  const target = findComposerImageTarget([
    '"Message #all" [24,2161][1140,2382]',
    '[BTN] "" [48,2268][138,2358]',
    '[BTN] "" [150,2268][240,2358]',
  ].join("\n"));
  assert.equal(target.mode, "semantic-button");
  assert.deepEqual({ x: target.x, y: target.y }, { x: 93, y: 2313 });
});

test("ignores an unrelated button outside the composer", () => {
  const target = findComposerImageTarget([
    '[BTN] "" [48,183][138,273]',
    '"Message #all" [24,2161][1140,2382]',
  ].join("\n"));
  assert.equal(target.mode, "composer-relative");
});

test("fails closed for duplicate composer nodes", () => {
  assert.throws(
    () => findComposerImageTarget([
      '"Message #all" [24,2161][1140,2382]',
      '"Message #all" [24,1900][1140,2100]',
    ].join("\n")),
    /found 2/,
  );
});

test("fails closed for implausibly small composer bounds", () => {
  assert.throws(
    () => findComposerImageTarget('"Message #all" [24,2161][300,2220]'),
    /implausible/,
  );
});

test("findPhotoCellTarget picks the top-left tile below the Recents header", () => {
  const target = findPhotoCellTarget([
    '"Recents" [24,100][200,150]',
    '"Cancel" [1000,100][1140,150]',
    '"" [24,200][300,476]',
    '"" [320,200][596,476]',
    '"Photo, March 12" [616,200][892,476]',
  ].join("\n"));
  assert.equal(target.x, 162);
  assert.equal(target.y, 338);
});

test("findPhotoCellTarget fails closed when no picker variant is proven", () => {
  assert.throws(
    () => findPhotoCellTarget('"Cancel" [1000,100][1140,150]\n"" [24,200][300,476]'),
    /no proven PhotosUI picker variant/,
  );
});

test("findPhotoCellTarget rejects tiles above the legacy header", () => {
  assert.throws(
    () => findPhotoCellTarget('"Recents" [24,400][200,450]\n"Cancel" [1000,400][1140,450]\n"" [24,100][300,376]'),
    /no proven PhotosUI picker variant/,
  );
});

test("findPhotoCellTarget rejects oversized container tiles", () => {
  assert.throws(
    () => findPhotoCellTarget('"Recents" [24,100][200,150]\n"Cancel" [1000,100][1140,150]\n"" [24,200][900,900]'),
    /no proven PhotosUI picker variant/,
  );
});

test("findPhotoCellTarget fails closed on an ambiguous top-left", () => {
  assert.throws(
    () => findPhotoCellTarget('"Recents" [24,100][200,150]\n"Cancel" [1000,100][1140,150]\n"" [24,200][300,476]\n"" [28,204][304,480]'),
    /ambiguous top-left/,
  );
});

test("hasRealPhotoPicker requires header + chrome + tile combination", () => {
  const good = '"Recents" [24,100][200,150]\n"Cancel" [1000,100][1140,150]\n"" [24,200][300,476]';
  assert.equal(hasRealPhotoPicker(good), true);
  assert.equal(hasRealPhotoPicker('"Recents" [24,100][200,150]\n"" [24,200][300,476]'), false); // no chrome
  assert.equal(hasRealPhotoPicker('"Cancel" [1000,100][1140,150]\n"" [24,200][300,476]'), false); // no header
  assert.equal(hasRealPhotoPicker('"Recents" [24,100][200,150]\n"Cancel" [1000,100][1140,150]'), false); // no tile
});

test("hasRealPhotoPicker rejects ordinary app panels with generic words", () => {
  assert.equal(hasRealPhotoPicker('"Select Plan" [24,100][300,180]\n"Cancel" [1000,100][1140,150]'), false);
});

// Verbatim app dump of the real iOS 26 picker (run 29660133478
// post-tap-dump.txt) — the offline-recompute baseline for task #26: the
// pre-fix matcher judged this "no picker" because it only knew the legacy
// Recents/All Photos header vocabulary.
const ios26RealPickerDump = [
  '"Raft" [0,0][1170,2532]',
  '"Cancel" [60,231][168,339]',
  '"Photos" [300,213][870,357]',
  '"Photos" [300,213][585,357]',
  '"Collections" [585,213][870,357]',
  '"Done" [1002,231][1110,339]',
  '"Photos" [0,171][1170,2532]',
  '"Photo, March 30, 2018, 7:14 PM, Photo, August 08, 2012, 9:55 PM, Photo, August 08, 2012, 9:29 PM, Photo, August 08, 2012, 6:52 PM, Photo, March 13, 2011, 12:17 AM, Photo, October 09, 2009, 9:09 PM" [0,405][1170,1184]',
  '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
  '"Photo, August 08, 2012, 9:55 PM" [392,405][778,792]',
  '"Photo, August 08, 2012, 9:29 PM" [783,405][1170,792]',
  '"Photo, August 08, 2012, 6:52 PM" [0,797][387,1184]',
  '"Photo, March 13, 2011, 12:17 AM" [392,797][778,1184]',
  '"Photo, October 09, 2009, 9:09 PM" [783,797][1170,1184]',
  '"Vertical scroll bar, 1 page" [1071,405][1161,2274]',
  '"Horizontal scroll bar, 1 page" [124,2175][1045,2265]',
  '"Vertical scroll bar, 1 page" [1071,405][1161,2274]',
  '"Horizontal scroll bar, 1 page" [124,2175][1045,2265]',
  '"Toolbar" [0,2274][1170,2532]',
  '"Sort and Filter" [99,2319][213,2433]',
  '"Search" [957,2319][1071,2433]',
  '"Select Photos" [437,2349][734,2403]',
].join("\n");

const ios26Chrome = [
  '"Cancel" [60,231][168,339]',
  '"Done" [1002,231][1110,339]',
  '"Photos" [300,213][585,357]',
  '"Collections" [585,213][870,357]',
  '"Select Photos" [437,2349][734,2403]',
].join("\n");

test("iOS 26 variant: real picker dump recomputes to picker=true with top-left cell target", () => {
  assert.equal(hasRealPhotoPicker(ios26RealPickerDump), true);
  const target = findPhotoCellTarget(ios26RealPickerDump);
  assert.equal(target.variant, "ios26");
  assert.deepEqual({ x: target.x, y: target.y }, { x: 194, y: 599 });
  assert.equal(target.label, "Photo, March 30, 2018, 7:14 PM");
  assert.deepEqual(target.bounds, [0, 405, 387, 792]);
  assert.match(target.line, /^"Photo, March 30, 2018, 7:14 PM" \[0,405\]\[387,792\]$/);
});

test("iOS 26 variant: full chrome with only the aggregate container is NOT a picker", () => {
  const dump = `${ios26Chrome}\n"Photo, March 30, 2018, 7:14 PM, Photo, August 08, 2012, 9:55 PM" [0,405][1170,1184]`;
  assert.equal(hasRealPhotoPicker(dump), false);
  assert.throws(() => findPhotoCellTarget(dump), /no proven PhotosUI picker variant/);
});

test("iOS 26 variant: independent cells without the full chrome set are NOT a picker", () => {
  // a page merely mentioning Photos, plus a photo-ish tile, proves nothing
  assert.equal(hasRealPhotoPicker('"Photos" [0,171][1170,2532]\n"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]'), false);
  // partial chrome (Cancel/Done but no tabs/Select Photos) still fails
  assert.equal(hasRealPhotoPicker('"Cancel" [60,231][168,339]\n"Done" [1002,231][1110,339]\n"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]'), false);
});

test("iOS 26 variant: full chrome without any independent cell is NOT a picker", () => {
  assert.equal(hasRealPhotoPicker(ios26Chrome), false);
});

test("iOS 26 variant: ambiguous top-left cells fail closed", () => {
  const dump = [
    ios26Chrome,
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
    '"Photo, August 08, 2012, 9:55 PM" [4,409][391,796]',
  ].join("\n");
  assert.equal(hasRealPhotoPicker(dump), true);
  assert.throws(() => findPhotoCellTarget(dump), /ambiguous top-left/);
});

test("legacy variant still classifies with its header-gated rules", () => {
  const dump = '"Recents" [24,100][200,150]\n"Cancel" [1000,100][1140,150]\n"" [24,200][300,476]';
  assert.equal(hasRealPhotoPicker(dump), true);
  assert.equal(findPhotoCellTarget(dump).variant, "legacy");
});

test("iOS 26 chrome: a fullscreen Photos ancestor alone does not satisfy the tab pair", () => {
  const dump = [
    '"Cancel" [60,231][168,339]',
    '"Done" [1002,231][1110,339]',
    '"Photos" [0,171][1170,2532]', // fullscreen ancestor — must NOT count as a tab
    '"Collections" [585,213][870,357]',
    '"Select Photos" [437,2349][734,2403]',
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
  ].join("\n");
  assert.equal(hasRealPhotoPicker(dump), false);
});

test("iOS 26 chrome: Cancel/Done must be small TOP nodes", () => {
  const dump = [
    '"Cancel" [60,2200][168,2308]', // bottom node — must NOT count
    '"Done" [1002,231][1110,339]',
    '"Photos" [300,213][585,357]',
    '"Collections" [585,213][870,357]',
    '"Select Photos" [437,2349][734,2403]',
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
  ].join("\n");
  assert.equal(hasRealPhotoPicker(dump), false);
});

test("iOS 26 chrome: Photos/Collections tabs must share a row with similar height", () => {
  const dump = [
    '"Cancel" [60,231][168,339]',
    '"Done" [1002,231][1110,339]',
    '"Photos" [300,213][585,357]',
    '"Collections" [585,500][870,644]', // different row — must NOT pair
    '"Select Photos" [437,2349][734,2403]',
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
  ].join("\n");
  assert.equal(hasRealPhotoPicker(dump), false);
});

test("findPhotoCellTarget rejects large bounds overlap even when centers are >12px apart", () => {
  const dump = [
    ios26Chrome,
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
    '"Photo, August 08, 2012, 9:55 PM" [100,405][487,792]', // center 100px away, 74% shared width
  ].join("\n");
  assert.throws(() => findPhotoCellTarget(dump), /ambiguous top-left/);
});

test("findPhotoCellTarget rejects duplicate nodes with identical bounds", () => {
  const dump = [
    ios26Chrome,
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
  ].join("\n");
  assert.throws(() => findPhotoCellTarget(dump), /ambiguous top-left/);
});

test("findPhotoCellTarget accepts edge-adjacent cells (zero-area shared border)", () => {
  const dump = [
    ios26Chrome,
    '"Photo, March 30, 2018, 7:14 PM" [0,405][387,792]',
    '"Photo, August 08, 2012, 9:55 PM" [387,405][774,792]', // shares only the x=387 edge
  ].join("\n");
  const target = findPhotoCellTarget(dump);
  assert.deepEqual({ x: target.x, y: target.y }, { x: 194, y: 599 });
});

test("uiNodeSignature keys identifier+type+bounds, falls back to type+label+bounds", () => {
  assert.equal(uiNodeSignature({ identifier: "abc", type: "button", label: "X", bounds: [1, 2, 3, 4] }), "id:abc|button|1,2,3,4");
  assert.equal(uiNodeSignature({ identifier: "  ", type: "cell", label: "P", bounds: [1, 2, 3, 4] }), "anon:cell|P|1,2,3,4");
});

test("diffUiNodeStates detects an enabled flip on the same node", () => {
  const before = [{ identifier: "done-btn", type: "button", label: "Done", bounds: [1002, 231, 1110, 339], enabled: false, selected: false, value: "" }];
  const after = [{ ...before[0], enabled: true }];
  const diff = diffUiNodeStates(before, after);
  assert.equal(diff.added.length, 0);
  assert.equal(diff.removed.length, 0);
  assert.equal(diff.stateChanged.length, 1);
  assert.equal(diff.stateChanged[0].signature, "id:done-btn|button|1002,231,1110,339");
  assert.deepEqual(diff.stateChanged[0].changed, ["enabled"]);
  assert.equal(diff.stateChanged[0].before.enabled, false);
  assert.equal(diff.stateChanged[0].after.enabled, true);
});

test("diffUiNodeStates detects a selected flip on a cell", () => {
  const cell = { identifier: "", type: "cell", label: "Photo, March 30, 2018, 7:14 PM", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  const diff = diffUiNodeStates([cell], [{ ...cell, selected: true }]);
  assert.equal(diff.stateChanged.length, 1);
  assert.equal(diff.stateChanged[0].signature, "anon:cell|Photo, March 30, 2018, 7:14 PM|0,405,387,792");
  assert.deepEqual(diff.stateChanged[0].changed, ["selected"]);
});

test("diffUiNodeStates reports added and removed signatures", () => {
  const a = { identifier: "a", type: "image", label: "", bounds: [0, 0, 10, 10], enabled: true, selected: false, value: "" };
  const b = { identifier: "b", type: "image", label: "", bounds: [0, 0, 10, 10], enabled: true, selected: false, value: "" };
  const diff = diffUiNodeStates([a], [b]);
  assert.deepEqual(diff.added, ["id:b|image|0,0,10,10"]);
  assert.deepEqual(diff.removed, ["id:a|image|0,0,10,10"]);
  assert.equal(diff.stateChanged.length, 0);
});

test("diffUiNodeStates stays silent on identical snapshots", () => {
  const nodes = [{ identifier: "x", type: "button", label: "Cancel", bounds: [60, 231, 168, 339], enabled: true, selected: false, value: "" }];
  assert.deepEqual(diffUiNodeStates(nodes, nodes.map((n) => ({ ...n }))), {
    added: [], removed: [], stateChanged: [], ambiguousBefore: [], ambiguousAfter: [],
  });
});

test("diffUiNodeStates does not collapse same-identifier nodes with different bounds", () => {
  const c1 = { identifier: "x", type: "cell", label: "A", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  const c2 = { identifier: "x", type: "cell", label: "B", bounds: [392, 405, 778, 792], enabled: true, selected: false, value: "" };
  const diff = diffUiNodeStates([c1, c2], [{ ...c1 }, { ...c2, selected: true }]);
  assert.deepEqual(diff.ambiguousBefore, []);
  assert.deepEqual(diff.ambiguousAfter, []);
  assert.equal(diff.stateChanged.length, 1);
  assert.equal(diff.stateChanged[0].signature, "id:x|cell|392,405,778,792");
  assert.deepEqual(diff.stateChanged[0].changed, ["selected"]);
});

test("diffUiNodeStates routes fully duplicated signatures to ambiguous with no state conclusion", () => {
  const dup = { identifier: "d", type: "button", label: "Done", bounds: [1002, 231, 1110, 339], enabled: false, selected: false, value: "" };
  const diff = diffUiNodeStates([dup, { ...dup }], [{ ...dup, enabled: true }]);
  assert.deepEqual(diff.ambiguousBefore, ["id:d|button|1002,231,1110,339"]);
  assert.deepEqual(diff.ambiguousAfter, []);
  assert.equal(diff.stateChanged.length, 0); // the enabled flip must NOT be concluded
  assert.equal(diff.added.length, 0);
  assert.equal(diff.removed.length, 0);
});

test("exactCellCandidates binds by exact label+bounds only", () => {
  const cell = { identifier: "", type: "cell", label: "Photo, March 30, 2018, 7:14 PM", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  // a smaller child INSIDE the cell (image/checkmark) must never impersonate it
  const child = { identifier: "", type: "image", label: "Photo, March 30, 2018, 7:14 PM", bounds: [20, 425, 120, 525], enabled: true, selected: false, value: "" };
  const target = { label: "Photo, March 30, 2018, 7:14 PM", bounds: [0, 405, 387, 792] };
  const found = exactCellCandidates([cell, child], target);
  assert.equal(found.length, 1);
  assert.equal(found[0], cell);
});

test("exactCellCandidates returns zero on a 1px bounds drift and two on duplicates", () => {
  const cell = { identifier: "", type: "cell", label: "P", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  assert.equal(exactCellCandidates([cell], { label: "P", bounds: [0, 405, 387, 793] }).length, 0);
  assert.equal(exactCellCandidates([cell, { ...cell }], { label: "P", bounds: [0, 405, 387, 792] }).length, 2);
});

test("targetTrackingCandidates keeps fully duplicated after-nodes as an explicit ambiguity", () => {
  const target = { identifier: "", type: "cell", label: "Photo, A", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  // two raw nodes with the same signature AND the same bounds must survive as
  // candidates=2 (the script then sets ambiguous=true, targetCellAfter=null)
  const found = targetTrackingCandidates([target, { ...target }], target);
  assert.equal(found.length, 2);
});

test("targetTrackingCandidates counts a node matching BOTH signature and bounds only once", () => {
  const target = { identifier: "c", type: "cell", label: "A", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  const sameNode = { ...target, selected: true }; // same signature AND same bounds
  assert.equal(targetTrackingCandidates([sameNode], target).length, 1);
});

test("targetTrackingCandidates tracks an anonymous cell through a label flip via exact bounds", () => {
  const before = { identifier: "", type: "cell", label: "Photo, A", bounds: [0, 405, 387, 792], enabled: true, selected: false, value: "" };
  const after = { ...before, label: "Photo, A (selected)" }; // anon signature changes with the label
  const found = targetTrackingCandidates([after], before);
  assert.equal(found.length, 1);
  assert.equal(found[0], after);
});

test("composerHasNewPreview only accepts a NEW node inside the composer", () => {
  const before = '"Message #all" [24,2161][1140,2382]';
  const noNew = '"Message #all" [24,2161][1140,2382]';
  const withNew = '"Message #all" [24,2161][1140,2382]\n"" [40,2180][260,2320]';
  assert.equal(composerHasNewPreview(before, noNew), false); // picker closed ≠ preview
  assert.equal(composerHasNewPreview(before, withNew), true);
});

test("countThreadImageNodes counts only large nodes above the composer", () => {
  const dump = [
    '"img" [24,400][400,700]',
    '"small" [24,750][100,800]',
    '"Message #all" [24,2161][1140,2382]',
    '"" [40,2180][260,2320]',
  ].join("\n");
  assert.equal(countThreadImageNodes(dump), 1);
});

test("composerHasNewPreview uses the before-frozen composer rect only", () => {
  const before = '"Message #all" [24,2161][1140,2382]';
  // after has no composer at all + a new node far outside the frozen rect
  const afterOutside = '"" [24,100][300,376]';
  assert.equal(composerHasNewPreview(before, afterOutside), false);
  // after has composer removed but a new node inside the frozen rect
  const afterInside = '"" [40,2180][260,2320]';
  assert.equal(composerHasNewPreview(before, afterInside), true);
  // only the composer's own bounds changed
  const afterComposerOnly = '"Message #all" [24,2100][1140,2382]';
  assert.equal(composerHasNewPreview(before, afterComposerOnly), false);
  // no unique composer in before dump
  assert.equal(composerHasNewPreview('"Message #all" [24,2161][1140,2382]\n"Message #all" [24,100][1140,300]', afterInside), false);
});

test("composerHasNewPreview ignores tiny new nodes", () => {
  const before = '"Message #all" [24,2161][1140,2382]';
  const afterTiny = '"." [40,2180][60,2200]';
  assert.equal(composerHasNewPreview(before, afterTiny), false);
});

test("photosFullAccessAlertDecision accepts the exact happy path", () => {
  assert.deepEqual(photosFullAccessAlertDecision({
    alertCount: 1,
    alertTitle: "“Raft” would like full access to your Photo Library.",
    allowFullAccessCount: 1,
    alertDismissedAfterTap: true,
  }), { ok: true });
});

test("photosFullAccessAlertDecision fails closed on every contract violation", () => {
  assert.equal(photosFullAccessAlertDecision({ alertCount: 0 }).ok, false); // no alert
  assert.equal(photosFullAccessAlertDecision({ alertCount: 2, alertTitle: "Photo Library", allowFullAccessCount: 1 }).ok, false); // duplicate alerts
  assert.equal(photosFullAccessAlertDecision({ alertCount: 1, alertTitle: "“Raft” would like to access Contacts", allowFullAccessCount: 1 }).ok, false); // not Photos
  assert.equal(photosFullAccessAlertDecision({ alertCount: 1, alertTitle: "Photo Library", allowFullAccessCount: 0 }).ok, false); // only Limit/Don't Allow
  assert.equal(photosFullAccessAlertDecision({ alertCount: 1, alertTitle: "Photo Library", allowFullAccessCount: 2 }).ok, false); // duplicate Allow
  assert.equal(photosFullAccessAlertDecision({ alertCount: 1, alertTitle: "Photo Library", allowFullAccessCount: 1, alertDismissedAfterTap: false }).ok, false); // alert survives
});
