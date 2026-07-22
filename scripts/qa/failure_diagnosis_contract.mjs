const PLANS = {
  product: "review_product",
  navigation_contract: "revise_case",
  fixture: "repair_fixture",
  budget: "retry_budget",
  infra: "retry_infra",
};

export function validBudget(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  return Number.isInteger(value.max_turns) && value.max_turns >= 20 && value.max_turns <= 160 &&
    Number.isInteger(value.max_minutes) && value.max_minutes >= 10 && value.max_minutes <= 90 &&
    Number.isInteger(value.no_progress_turns) && value.no_progress_turns >= 3 && value.no_progress_turns <= 30;
}

export function validateReviewerDiagnosis(value, { evidenceIndex, executorDiagnosis, trajectory }) {
  const includedEvidenceIds = new Set(evidenceIndex.keys());
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("diagnosis must be an object");
  if (!Object.hasOwn(PLANS, value.classification)) throw new Error("invalid classification");
  if (!["high", "medium", "low"].includes(value.confidence)) throw new Error("invalid confidence");
  if (typeof value.target_surface_reached !== "boolean" || typeof value.summary !== "string" || !value.summary.trim()) throw new Error("missing diagnosis facts");
  if (!Array.isArray(value.missing_guidance) || value.missing_guidance.some((item) => typeof item !== "string")) throw new Error("missing_guidance must be strings");
  if (!Array.isArray(value.evidence_ids) || !value.evidence_ids.length || value.evidence_ids.some((id) => typeof id !== "string" || !includedEvidenceIds.has(id))) {
    throw new Error("every diagnosis evidence id must be in the downloaded frozen evidence set");
  }
  if (value.retry_plan !== PLANS[value.classification]) throw new Error("retry plan does not match classification");
  if (value.classification === "product") {
    if (value.confidence !== "high" || !value.target_surface_reached || value.recommended_budget != null) throw new Error("product requires high-confidence reached surface and no budget retry");
    const first = value.first_product_failure;
    if (!first || typeof first !== "object" || !String(first.action || "").trim() || !String(first.expected || "").trim() || !String(first.observed || "").trim()) {
      throw new Error("product requires a concrete first contradictory action");
    }
    if (!Array.isArray(first.evidence_ids) || !first.evidence_ids.length || first.evidence_ids.some((id) => !includedEvidenceIds.has(id))) {
      throw new Error("product failure evidence must be downloaded and frozen");
    }
    const proof = executorDiagnosis?.product_proof;
    if (!Number.isInteger(first.action_step) || first.action_step !== proof?.action_step ||
        String(first.action || "") !== String(proof?.action_summary || "")) {
      throw new Error("product failure must cite the executor-owned performed action fact");
    }
    const cited = first.evidence_ids.map((id) => evidenceIndex.get(id));
    if (!cited.some((item) => item?.kind === "steps_log") ||
        !cited.some((item) => ["screenshot", "screenshot_annotated", "ui_dump"].includes(item?.kind) && Number.isInteger(item?.step_index) && item.step_index >= first.action_step)) {
      throw new Error("product failure requires steps_log plus fresh post-action visual evidence");
    }
  } else if (value.first_product_failure != null) {
    throw new Error("non-product diagnosis cannot carry a product failure");
  }
  if (value.classification === "budget") {
    if (!value.target_surface_reached || !validBudget(value.recommended_budget)) throw new Error("budget requires reached surface and bounded budget");
    if (!trajectory?.checkpointCount || trajectory.performedActionCount < 2 || trajectory.distinctPostActionScreens < 2) {
      throw new Error("budget requires a checkpoint and continuing measurable trajectory progress");
    }
  } else if (value.recommended_budget != null) {
    throw new Error("only budget classification can recommend execution budget");
  }
  if (value.classification === "navigation_contract" && !value.missing_guidance.length) throw new Error("navigation diagnosis requires concrete guidance additions");
  return value;
}
