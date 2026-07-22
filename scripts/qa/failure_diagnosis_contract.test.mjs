import assert from "node:assert/strict";
import test from "node:test";
import { validateReviewerDiagnosis } from "./failure_diagnosis_contract.mjs";

const ids = new Set(["steps", "shot"]);
const base = {
  confidence: "high", target_surface_reached: true, first_blocked_step: "save",
  summary: "evidence grounded", missing_guidance: [], evidence_ids: ["steps", "shot"],
};

test("review diagnosis rejects product without frozen contradictory evidence", () => {
  const product = {
    ...base, classification: "product", retry_plan: "review_product", recommended_budget: null,
    first_product_failure: { action: "tap Save", expected: "new", observed: "old", evidence_ids: ["shot"] },
  };
  assert.equal(validateReviewerDiagnosis(product, ids), product);
  assert.throws(() => validateReviewerDiagnosis({ ...product, confidence: "medium" }, ids));
  assert.throws(() => validateReviewerDiagnosis({ ...product, first_product_failure: { ...product.first_product_failure, evidence_ids: ["forged"] } }, ids));
  assert.throws(() => validateReviewerDiagnosis({ ...product, retry_plan: "retry_budget" }, ids));
});

test("budget/navigation/fixture/infra contracts are exact and fail closed", () => {
  const budget = {
    ...base, classification: "budget", retry_plan: "retry_budget", first_product_failure: null,
    recommended_budget: { max_turns: 70, max_minutes: 30, no_progress_turns: 8 },
  };
  assert.equal(validateReviewerDiagnosis(budget, ids), budget);
  assert.throws(() => validateReviewerDiagnosis({ ...budget, recommended_budget: { max_turns: 999, max_minutes: 30, no_progress_turns: 8 } }, ids));
  const navigation = {
    ...base, classification: "navigation_contract", target_surface_reached: false,
    retry_plan: "revise_case", first_product_failure: null, recommended_budget: null,
    missing_guidance: ["Use exact #all node in CHANNELS section"],
  };
  assert.equal(validateReviewerDiagnosis(navigation, ids), navigation);
  assert.throws(() => validateReviewerDiagnosis({ ...navigation, missing_guidance: [] }, ids));
  assert.throws(() => validateReviewerDiagnosis({ ...navigation, evidence_ids: ["outside"] }, ids));
});
