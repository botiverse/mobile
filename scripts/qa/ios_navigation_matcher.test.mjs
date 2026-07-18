import assert from "node:assert/strict";
import test from "node:test";
import { findAllChannelTarget, redactEditableValues } from "./ios_navigation_matcher.mjs";

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
