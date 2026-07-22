const CLASSIFICATIONS = new Set(["product", "navigation_contract", "fixture", "budget", "infra"]);

export function normalizeSelfDiagnosis(value, scrub = (text) => String(text)) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const classification = String(value.classification ?? "").trim();
  if (!CLASSIFICATIONS.has(classification) || typeof value.target_surface_reached !== "boolean") return null;
  const clean = (input, max = 500) => scrub(String(input ?? "")).trim().slice(0, max);
  const first = value.first_product_failure;
  const firstProductFailure = first && typeof first === "object" && !Array.isArray(first) &&
    clean(first.action) && clean(first.expected) && clean(first.observed)
    ? {
        action: clean(first.action),
        expected: clean(first.expected),
        observed: clean(first.observed),
        evidence_step: Number.isInteger(Number(first.evidence_step)) ? Number(first.evidence_step) : null,
      }
    : null;
  return {
    classification,
    target_surface_reached: value.target_surface_reached,
    first_blocked_step: clean(value.first_blocked_step) || null,
    missing_guidance: Array.isArray(value.missing_guidance)
      ? value.missing_guidance.map((item) => clean(item)).filter(Boolean).slice(0, 12)
      : [],
    first_product_failure: firstProductFailure,
  };
}

export function effectiveExecutorVerdict(requested, diagnosis) {
  if (requested === "pass") return "pass";
  if (!diagnosis || diagnosis.classification !== "product" ||
      !diagnosis.target_surface_reached || !diagnosis.first_product_failure) return "harness";
  return "fail";
}

export function repeatedActionKey(action) {
  return JSON.stringify({
    action: action?.action ?? null,
    text: action?.text ?? null,
    direction: action?.direction ?? null,
    x: action?.x == null ? null : Math.round(Number(action.x) / 20),
    y: action?.y == null ? null : Math.round(Number(action.y) / 20),
  });
}
