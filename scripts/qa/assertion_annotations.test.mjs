import test from "node:test";
import assert from "node:assert/strict";
import { normalizeAssertionAnnotation } from "./assertion_annotations.mjs";

test("preserves an explicit stable assertion id", () => {
  assert.equal(normalizeAssertionAnnotation({ assertion_id: "home-visible", label: "Home" }).assertion_id, "home-visible");
});

test("recovers a stable id from the bracketed label contract", () => {
  assert.equal(normalizeAssertionAnnotation({ label: "[server-correct] test server" }).assertion_id, "server-correct");
});

test("does not invent an id for a legacy free-form label", () => {
  assert.equal(normalizeAssertionAnnotation({ label: "home visible" }).assertion_id, undefined);
});
