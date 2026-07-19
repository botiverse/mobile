import test from "node:test";
import assert from "node:assert/strict";
import { validateFrozenInputs } from "./validate_frozen_inputs.mjs";

const full = { sha256: "s", sourceCommit: "c", version: "1", versionCode: "2", packageId: "p" };
const empty = { sha256: "", sourceCommit: "", version: "", versionCode: "", packageId: "" };

test("accepts a complete frozen identity only with one pinned source", () => {
  assert.deepEqual(validateFrozenInputs({ primaryPin: "run", identity: full }), { pinned: true, strictIdentity: true });
});

test("rejects identity on an ordinary ref build", () => {
  assert.throws(() => validateFrozenInputs({ identity: full }), /requires a pinned/);
});

test("rejects every partial identity shape", () => {
  for (const key of Object.keys(full)) {
    assert.throws(() => validateFrozenInputs({ primaryPin: "run", identity: { ...full, [key]: "" } }), /complete/);
  }
});

test("preserves legacy pinned and ordinary paths with no frozen identity", () => {
  assert.deepEqual(validateFrozenInputs({ primaryPin: "legacy", identity: empty }), { pinned: true, strictIdentity: false });
  assert.deepEqual(validateFrozenInputs({ identity: empty }), { pinned: false, strictIdentity: false });
});

test("rejects two competing pinned sources", () => {
  assert.throws(() => validateFrozenInputs({ primaryPin: "run", secondaryPin: "url", identity: full }), /multiple/);
});

test("rejects newline injection and treats a quote as inert data", () => {
  assert.throws(() => validateFrozenInputs({ primaryPin: "run", identity: { ...full, version: "1\nrun: pwn" } }), /single-line/);
  assert.deepEqual(validateFrozenInputs({ primaryPin: "run", identity: { ...full, version: "1'$(touch pwn)" } }), { pinned: true, strictIdentity: true });
});
