import assert from "node:assert/strict";
import test from "node:test";
import {
  composerHasNewPreview,
  countThreadImageNodes,
  findAllChannelTarget,
  findComposerImageTarget,
  findPhotoCellTarget,
  hasPhotoPickerGate,
  hasRealPhotoPicker,
  redactEditableValues,
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

test("findPhotoCellTarget fails closed when the picker header is absent", () => {
  assert.throws(
    () => findPhotoCellTarget('"Cancel" [1000,100][1140,150]\n"" [24,200][300,476]'),
    /header .* not found/,
  );
});

test("findPhotoCellTarget rejects tiles above the header", () => {
  assert.throws(
    () => findPhotoCellTarget('"Recents" [24,400][200,450]\n"Cancel" [1000,400][1140,450]\n"" [24,100][300,376]'),
    /no plausible photo tile/,
  );
});

test("findPhotoCellTarget rejects oversized container tiles", () => {
  assert.throws(
    () => findPhotoCellTarget('"Recents" [24,100][200,150]\n"Cancel" [1000,100][1140,150]\n"" [24,200][900,900]'),
    /no plausible photo tile/,
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
