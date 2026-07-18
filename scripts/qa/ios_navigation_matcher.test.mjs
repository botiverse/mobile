import assert from "node:assert/strict";
import test from "node:test";
import {
  findAllChannelTarget,
  findComposerImageTarget,
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
