import test from "node:test";
import assert from "node:assert/strict";
import { assertionFrameRefs, evenly, selectReviewScreenshots } from "./review_evidence_selection.mjs";

// Shape of Stamp run 134da0bf (CI 43569038982): 21 steps, both checks asserted
// on step 19. Before the fix raw step 19 was never uploaded and the 12-image
// cap picked other frames.
const verdict = JSON.stringify({
  verdict: "pass",
  annotations: { assertion: [
    { assertion_id: "thread-content-visible", step: 19, result: "pass", label: "[thread-content-visible] x" },
    { step: 19, result: "pass", label: "[thread-composer-visible] y" },
  ] },
});
const shot = (step, extra = {}) => ({ id: `raw-${step}`, kind: "screenshot", run_case_id: "rc1", step_index: step, seq: step + 1, captured_at: 1000 + step, ...extra });
const cases = [{ run_case_id: "rc1", case_id: "open-thread", verdict_json: verdict }];

test("assertion refs keep only check id and step", () => {
  assert.deepEqual(assertionFrameRefs(verdict), [
    { assertion_id: "thread-content-visible", step: 19 },
    { assertion_id: "thread-composer-visible", step: 19 },
  ]);
  assert.deepEqual(assertionFrameRefs(null), []);
  assert.deepEqual(evenly([1, 2, 3].map((id) => ({ id })), 0), []);
});

test("assertion frame is always selected and labelled despite the cap", () => {
  const evidence = [...Array.from({ length: 21 }, (_, i) => shot(i)), { id: "ann-19", kind: "screenshot_annotated", run_case_id: "rc1", step_index: 19 }];
  const sel = selectReviewScreenshots(cases, evidence, 12);
  assert.equal(sel.shots.length, 12);
  assert(sel.shots.some((e) => e.id === "raw-19"));
  assert(!sel.shots.some((e) => e.kind !== "screenshot"));
  assert.deepEqual(sel.missingAssertionFrames, []);
  assert.deepEqual(sel.checksByEvidence.get("raw-19").map((f) => f.assertion_id), ["thread-content-visible", "thread-composer-visible"]);
});

test("a referenced frame with no raw screenshot is reported missing", () => {
  const evidence = Array.from({ length: 21 }, (_, i) => shot(i)).filter((e) => e.step_index !== 19);
  const sel = selectReviewScreenshots(cases, evidence, 12);
  assert.equal(sel.missingAssertionFrames.length, 2);
  assert.equal(sel.missingAssertionFrames[0].step, 19);
  // a raw frame of the same step from another run case does not count
  const other = selectReviewScreenshots(cases, [...evidence, shot(19, { id: "x", run_case_id: "rc2" })], 12);
  assert.equal(other.missingAssertionFrames.length, 2);
});
