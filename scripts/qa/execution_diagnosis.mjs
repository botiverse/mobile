const CLASSIFICATIONS = new Set(["product", "navigation_contract", "fixture", "budget", "infra"]);
const PRODUCT_ACTION_TYPES = new Set(["tap", "tap_text", "swipe", "long_press", "open_link", "text", "key", "clear"]);

export function normalizeSelfDiagnosis(value, scrub = (text) => String(text)) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const classification = String(value.classification ?? "").trim();
  const confidence = String(value.confidence ?? "").trim();
  if (!CLASSIFICATIONS.has(classification) || !["high", "medium", "low"].includes(confidence) || typeof value.target_surface_reached !== "boolean") return null;
  const clean = (input, max = 500) => scrub(String(input ?? "")).trim().slice(0, max);
  const steps = (input) => Array.isArray(input)
    ? [...new Set(input.map(Number).filter((step) => Number.isInteger(step) && step >= 0))].slice(0, 20)
    : [];
  const first = value.first_product_failure;
  const firstProductFailure = first && typeof first === "object" && !Array.isArray(first) &&
    clean(first.action) && clean(first.expected) && clean(first.observed)
    ? {
        action: clean(first.action),
        expected: clean(first.expected),
        observed: clean(first.observed),
        action_step: Number.isInteger(Number(first.action_step)) ? Number(first.action_step) : null,
        evidence_steps: steps(first.evidence_steps),
      }
    : null;
  return {
    classification,
    confidence,
    target_surface_reached: value.target_surface_reached,
    surface_evidence_steps: steps(value.surface_evidence_steps),
    surface_checkpoint_step: Number.isInteger(Number(value.surface_checkpoint_step)) ? Number(value.surface_checkpoint_step) : null,
    first_blocked_step: clean(value.first_blocked_step) || null,
    missing_guidance: Array.isArray(value.missing_guidance)
      ? value.missing_guidance.map((item) => clean(item)).filter(Boolean).slice(0, 12)
      : [],
    first_product_failure: firstProductFailure,
  };
}

export function effectiveExecutorVerdict(requested, diagnosis) {
  if (requested === "pass") return diagnosis == null ? "pass" : "harness";
  if (!diagnosis || diagnosis.classification !== "product" || diagnosis.confidence !== "high" ||
      !diagnosis.target_surface_reached || !diagnosis.product_proof) return "harness";
  return "fail";
}

export function bindDiagnosisToLedger(diagnosis, { actions, observations, checkpoints = new Map() }) {
  if (!diagnosis) return null;
  const surface = diagnosis.surface_evidence_steps ?? [];
  if (!surface.length || surface.some((step) => !observations.has(step))) return { ...diagnosis, product_proof: null };
  if (diagnosis.classification !== "product") return { ...diagnosis, product_proof: null };
  const failure = diagnosis.first_product_failure;
  const action = failure?.action_step == null ? null : actions.get(failure.action_step);
  const checkpoint = diagnosis.surface_checkpoint_step == null ? null : checkpoints.get(diagnosis.surface_checkpoint_step);
  const evidenceSteps = failure?.evidence_steps ?? [];
  if (!action?.performed || !PRODUCT_ACTION_TYPES.has(action.action_type) || !checkpoint || !surface.includes(diagnosis.surface_checkpoint_step) ||
      diagnosis.surface_checkpoint_step >= failure.action_step ||
      checkpoint.screenshot_sha256 !== observations.get(diagnosis.surface_checkpoint_step) ||
      !evidenceSteps.length || evidenceSteps.some((step) => !observations.has(step) || step < failure.action_step)) {
    return { ...diagnosis, product_proof: null };
  }
  return {
    ...diagnosis,
    first_product_failure: { ...failure, action: action.safe_summary },
    product_proof: {
      action_step: failure.action_step,
      action_identity_sha256: action.identity_sha256,
      action_type: action.action_type,
      action_summary: action.safe_summary,
      surface_checkpoint: {
        step: diagnosis.surface_checkpoint_step,
        milestone: checkpoint.milestone,
        screenshot_sha256: checkpoint.screenshot_sha256,
      },
      surface_evidence: surface.map((step) => ({ step, screenshot_sha256: observations.get(step) })),
      contradiction_evidence: evidenceSteps.map((step) => ({ step, screenshot_sha256: observations.get(step) })),
    },
  };
}

export function repeatedActionKey(action, scrub = (value) => String(value ?? "")) {
  const digest = (value) => {
    let hash = 2166136261;
    for (const char of scrub(value)) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
    return (hash >>> 0).toString(16).padStart(8, "0");
  };
  return JSON.stringify({
    action: action?.action ?? null,
    text: action?.text ?? null,
    direction: action?.direction ?? null,
    value_sha: action?.value == null ? null : digest(action.value),
    key_name: action?.name ?? null,
    url_sha: action?.url == null ? null : digest(action.url),
    wait_seconds: action?.seconds ?? null,
    milestone_sha: action?.milestone == null ? null : digest(action.milestone),
    note_sha: action?.note == null ? null : digest(action.note),
    x: action?.x == null ? null : Math.round(Number(action.x) / 20),
    y: action?.y == null ? null : Math.round(Number(action.y) / 20),
  });
}

export function observationFingerprint({ actionKey, screenshotSha256, feedback }) {
  return JSON.stringify({ actionKey, screenshotSha256, feedback: String(feedback ?? "").slice(0, 500) });
}

export function normalizeExecutionLimits({ maxTurns, maxMinutes, noProgressTurns }) {
  const values = [maxTurns, maxMinutes, noProgressTurns].map(Number);
  if (values.some((value) => !Number.isFinite(value) || !Number.isInteger(value))) return null;
  if (values[0] < 20 || values[0] > 160 || values[1] < 10 || values[1] > 90 || values[2] < 3 || values[2] > 30) return null;
  return { maxTurns: values[0], maxMinutes: values[1], noProgressTurns: values[2] };
}
