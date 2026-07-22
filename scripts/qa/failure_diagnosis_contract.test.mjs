import assert from "node:assert/strict";
import test from "node:test";
import { validateReviewerDiagnosis } from "./failure_diagnosis_contract.mjs";

const evidenceIndex = new Map([
  ["steps", { id: "steps", kind: "steps_log", step_index: null }],
  ["shot", { id: "shot", kind: "screenshot", step_index: 19 }],
]);
const context = {
  evidenceIndex,
  executorDiagnosis: { product_proof: { action_step: 19, action_summary: "tap:aaaaaaaaaaaa" } },
  trajectory: { checkpointCount: 1, performedActionCount: 3, distinctPostActionScreens: 3 },
};
const base = {
  confidence: "high", target_surface_reached: true, first_blocked_step: "save",
  summary: "evidence grounded", missing_guidance: [], evidence_ids: ["steps", "shot"],
};

test("review diagnosis rejects product without frozen contradictory evidence", () => {
  const product = {
    ...base, classification: "product", retry_plan: "review_product", recommended_budget: null,
    first_product_failure: { action: "tap:aaaaaaaaaaaa", action_step: 19, expected: "new", observed: "old", evidence_ids: ["steps", "shot"] },
  };
  assert.equal(validateReviewerDiagnosis(product, context), product);
  assert.throws(() => validateReviewerDiagnosis({ ...product, confidence: "medium" }, context));
  assert.throws(() => validateReviewerDiagnosis({ ...product, first_product_failure: { ...product.first_product_failure, action: "tap Save" } }, context));
  assert.throws(() => validateReviewerDiagnosis({ ...product, first_product_failure: { ...product.first_product_failure, evidence_ids: ["shot"] } }, context));
  assert.throws(() => validateReviewerDiagnosis({ ...product, first_product_failure: { ...product.first_product_failure, evidence_ids: ["forged"] } }, context));
  assert.throws(() => validateReviewerDiagnosis({ ...product, retry_plan: "retry_budget" }, context));
});

test("budget/navigation/fixture/infra contracts are exact and fail closed", () => {
  const budget = {
    ...base, classification: "budget", retry_plan: "retry_budget", first_product_failure: null,
    recommended_budget: { max_turns: 70, max_minutes: 30, no_progress_turns: 8 },
  };
  assert.equal(validateReviewerDiagnosis(budget, context), budget);
  assert.throws(() => validateReviewerDiagnosis(budget, { ...context, trajectory: { checkpointCount: 0, performedActionCount: 10, distinctPostActionScreens: 10 } }));
  assert.throws(() => validateReviewerDiagnosis(budget, { ...context, trajectory: { checkpointCount: 1, performedActionCount: 10, distinctPostActionScreens: 1 } }));
  assert.throws(() => validateReviewerDiagnosis({ ...budget, recommended_budget: { max_turns: 999, max_minutes: 30, no_progress_turns: 8 } }, context));
  const navigation = {
    ...base, classification: "navigation_contract", target_surface_reached: false,
    retry_plan: "revise_case", first_product_failure: null, recommended_budget: null,
    missing_guidance: ["Use exact #all node in CHANNELS section"],
  };
  assert.equal(validateReviewerDiagnosis(navigation, context), navigation);
  assert.throws(() => validateReviewerDiagnosis({ ...navigation, missing_guidance: [] }, context));
  assert.throws(() => validateReviewerDiagnosis({ ...navigation, evidence_ids: ["outside"] }, context));
});
