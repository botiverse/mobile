// Fetches and verifies the attempt-bound frozen specification for a Testbed
// run case (`GET /api/runs/{run_id}/cases/{run_case_id}/spec`, contract
// `testbed-attempt-spec/v1`, botiverse/testbed@93f821e2 docs/case-model.md).
//
// Rules (#raft-mobile-reconcile:#212, #257):
//   - Reviewers MUST read the frozen attempt specification rather than
//     `GET /api/cases/{case_id}` + revision comparison. Catalog upgrades must
//     not break review of an earlier attempt.
//   - Before using any returned bytes, verify contract_version, binding_status,
//     run_id, run_case_id, case_id, case_revision, attempt, platform, exact
//     UTF-8 SHA-256 digests of full_yaml, platform_slice_yaml, rendered_goal,
//     the canonical spec_sha256 document digest, and execution_budget.
//   - 409 LEGACY_UNBOUND or 409 FROZEN_SPEC_UNAVAILABLE is incomplete review
//     provenance (never fall back to `/api/cases/...`).
//   - 404 ATTEMPT_NOT_FOUND, any other non-200 status, or any identity/digest
//     mismatch fails closed (throws; refuses to vote).

import crypto from "node:crypto";

export const ATTEMPT_SPEC_CONTRACT_VERSION = "testbed-attempt-spec/v1";
export const INCOMPLETE_SPEC_CODES = new Set(["LEGACY_UNBOUND", "FROZEN_SPEC_UNAVAILABLE"]);

const SHA256_RE = /^[0-9a-f]{64}$/;

export class AttemptSpecError extends Error {}

export function sha256Utf8(text) {
  return crypto.createHash("sha256").update(Buffer.from(String(text), "utf8")).digest("hex");
}

export function platformForAppSlug(appSlug) {
  const s = String(appSlug || "");
  if (/ios/i.test(s)) return "ios";
  if (/ohos|harmony/i.test(s)) return "ohos";
  return "android";
}

function plainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function normalizeAttemptExecutionBudget(value) {
  if (!plainObject(value)) return null;
  const maxTurns = value.max_turns;
  const maxMinutes = value.max_minutes;
  const noProgressTurns = value.no_progress_turns;
  if (
    !Number.isInteger(maxTurns) || maxTurns <= 0 ||
    !Number.isInteger(maxMinutes) || maxMinutes <= 0 ||
    !Number.isInteger(noProgressTurns) || noProgressTurns <= 0
  ) {
    return null;
  }
  return {
    max_turns: maxTurns,
    max_minutes: maxMinutes,
    no_progress_turns: noProgressTurns,
  };
}

// Canonical attempt_specifications document hashed by Testbed
// (botiverse/testbed worker/src/attempt-specification.ts captureSpecification).
export function canonicalAttemptSpecDocument(spec) {
  return JSON.stringify({
    contract_version: spec.contract_version,
    case_id: spec.case_id,
    case_revision: spec.case_revision,
    platform: spec.platform,
    full_yaml: spec.full_yaml,
    full_yaml_sha256: spec.full_yaml_sha256,
    platform_slice_yaml: spec.platform_slice_yaml,
    platform_slice_sha256: spec.platform_slice_sha256,
    rendered_goal: spec.rendered_goal,
    rendered_goal_sha256: spec.rendered_goal_sha256,
  });
}

export function attemptSpecPath(runId, runCaseId) {
  return `/api/runs/${encodeURIComponent(runId)}/cases/${encodeURIComponent(runCaseId)}/spec`;
}

export function parseAttemptExecutionBudgetJson(raw, label = "execution_budget_json") {
  let parsed = raw;
  if (typeof raw === "string") {
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new AttemptSpecError(`${label} is not valid JSON`);
    }
  }
  const budget = normalizeAttemptExecutionBudget(parsed);
  if (!budget) {
    throw new AttemptSpecError(`${label} is invalid: ${JSON.stringify(raw)}`);
  }
  return budget;
}

function sameExecutionBudget(a, b) {
  return a.max_turns === b.max_turns &&
    a.max_minutes === b.max_minutes &&
    a.no_progress_turns === b.no_progress_turns;
}

// Verifies a 200 payload from GET /api/runs/{run_id}/cases/{run_case_id}/spec
// against the expected attempt identity, recomputed UTF-8 SHA-256 digests,
// the run report's own run_cases row (`spec_sha256`, `execution_budget_json`),
// and the first-dispatch `admission` record when present.
export function verifyAttemptSpecification(payload, expected, parseYaml) {
  if (!plainObject(payload)) {
    throw new AttemptSpecError(`attempt ${expected.runCaseId} spec payload is not a JSON object`);
  }
  if (payload.contract_version !== ATTEMPT_SPEC_CONTRACT_VERSION) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} unsupported contract_version: ${JSON.stringify(payload.contract_version)}`
    );
  }
  if (payload.binding_status !== "verified") {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} binding_status is not verified: ${JSON.stringify(payload.binding_status)}`
    );
  }
  if (payload.run_id !== expected.runId) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} run_id mismatch: expected=${expected.runId} actual=${payload.run_id}`
    );
  }
  if (payload.run_case_id !== expected.runCaseId) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} run_case_id mismatch: expected=${expected.runCaseId} actual=${payload.run_case_id}`
    );
  }
  if (payload.case_id !== expected.caseId) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} case_id mismatch: expected=${expected.caseId} actual=${payload.case_id}`
    );
  }
  const expectedRevision = Number(expected.caseRevision);
  if (!Number.isInteger(payload.case_revision) || payload.case_revision <= 0 || payload.case_revision !== expectedRevision) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} case_revision mismatch: expected=${expected.caseRevision} actual=${payload.case_revision}`
    );
  }
  if (!Number.isInteger(payload.attempt) || payload.attempt <= 0) {
    throw new AttemptSpecError(`attempt ${expected.runCaseId} invalid attempt number: ${JSON.stringify(payload.attempt)}`);
  }
  if (expected.attempt != null && payload.attempt !== Number(expected.attempt)) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} attempt mismatch: expected=${expected.attempt} actual=${payload.attempt}`
    );
  }
  if (typeof payload.platform !== "string" || payload.platform !== expected.platform) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} platform mismatch: expected=${expected.platform} actual=${payload.platform}`
    );
  }

  for (const [textField, hashField] of [
    ["full_yaml", "full_yaml_sha256"],
    ["platform_slice_yaml", "platform_slice_sha256"],
    ["rendered_goal", "rendered_goal_sha256"],
  ]) {
    if (typeof payload[textField] !== "string") {
      throw new AttemptSpecError(`attempt ${expected.runCaseId} missing string ${textField}`);
    }
    if (typeof payload[hashField] !== "string" || !SHA256_RE.test(payload[hashField])) {
      throw new AttemptSpecError(`attempt ${expected.runCaseId} invalid ${hashField}: ${JSON.stringify(payload[hashField])}`);
    }
    const actual = sha256Utf8(payload[textField]);
    if (actual !== payload[hashField]) {
      throw new AttemptSpecError(
        `attempt ${expected.runCaseId} ${hashField} mismatch: declared=${payload[hashField]} actual=${actual}`
      );
    }
  }

  if (typeof payload.spec_sha256 !== "string" || !SHA256_RE.test(payload.spec_sha256)) {
    throw new AttemptSpecError(`attempt ${expected.runCaseId} invalid spec_sha256: ${JSON.stringify(payload.spec_sha256)}`);
  }
  const actualSpecSha = sha256Utf8(canonicalAttemptSpecDocument(payload));
  if (actualSpecSha !== payload.spec_sha256) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} spec_sha256 mismatch: declared=${payload.spec_sha256} actual=${actualSpecSha}`
    );
  }

  const budget = normalizeAttemptExecutionBudget(payload.execution_budget);
  if (!budget) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} invalid execution_budget: ${JSON.stringify(payload.execution_budget)}`
    );
  }

  // Independent cross-check against the run report's own run_cases row:
  // for every 200 bound specification, the report row must carry a valid
  // spec_sha256 and execution_budget_json matching /spec.
  if (typeof expected.reportSpecSha256 !== "string" || !SHA256_RE.test(expected.reportSpecSha256)) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} run report spec_sha256 is missing, invalid, or unbound: ${JSON.stringify(expected.reportSpecSha256 ?? null)}`
    );
  }
  if (expected.reportSpecSha256 !== payload.spec_sha256) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} spec_sha256 disagrees with run report: report=${expected.reportSpecSha256} spec=${payload.spec_sha256}`
    );
  }
  if (
    expected.reportExecutionBudgetJson === undefined ||
    expected.reportExecutionBudgetJson === null ||
    expected.reportExecutionBudgetJson === ""
  ) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} run report execution_budget_json is missing or empty`
    );
  }
  const reportBudget = parseAttemptExecutionBudgetJson(
    expected.reportExecutionBudgetJson,
    `attempt ${expected.runCaseId} run report execution_budget_json`,
  );
  if (!sameExecutionBudget(reportBudget, budget)) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} execution_budget disagrees with run report: report=${JSON.stringify(reportBudget)} spec=${JSON.stringify(budget)}`
    );
  }

  // First-dispatch checked runs also carry `report.admission`: verify the
  // admission's identity and original YAML, goal, and spec document digests.
  // For confirmed first-dispatch attempts (attempt === 1), admission.run_case_id
  // must match this attempt; genuine retries (attempt > 1) keep their own
  // run_case_id, spec_sha256, and execution_budget while admission still refers
  // to an earlier attempt.
  if (expected.admission !== undefined && expected.admission !== null) {
    if (!plainObject(expected.admission)) {
      throw new AttemptSpecError(`attempt ${expected.runCaseId} run report admission is malformed`);
    }
    const admissionRunCaseId = typeof expected.admission.run_case_id === "string" && expected.admission.run_case_id.trim()
      ? expected.admission.run_case_id.trim()
      : null;
    if (!admissionRunCaseId) {
      throw new AttemptSpecError(`attempt ${expected.runCaseId} run report admission is missing run_case_id`);
    }
    if (expected.admission.confirmation_id !== undefined && expected.admission.confirmation_id !== expected.runId) {
      throw new AttemptSpecError(
        `attempt ${expected.runCaseId} admission confirmation_id mismatch: admission=${expected.admission.confirmation_id} run=${expected.runId}`
      );
    }
    const admissionAttempt = expected.admission.attempt === undefined || expected.admission.attempt === null
      ? 1
      : Number(expected.admission.attempt);
    if (!Number.isInteger(admissionAttempt) || admissionAttempt <= 0) {
      throw new AttemptSpecError(
        `attempt ${expected.runCaseId} admission attempt is invalid: ${JSON.stringify(expected.admission.attempt)}`
      );
    }
    const mustMatchAdmission =
      payload.attempt === 1 ||
      admissionRunCaseId === expected.runCaseId ||
      admissionAttempt === payload.attempt;
    if (mustMatchAdmission) {
      if (admissionRunCaseId !== expected.runCaseId) {
        throw new AttemptSpecError(
          `attempt ${expected.runCaseId} admission run_case_id mismatch: admission=${admissionRunCaseId} expected=${expected.runCaseId}`
        );
      }
      if (admissionAttempt !== payload.attempt) {
        throw new AttemptSpecError(
          `attempt ${expected.runCaseId} admission attempt mismatch: admission=${admissionAttempt} spec=${payload.attempt}`
        );
      }
      const sel = expected.admission.selection;
      if (!plainObject(sel)) {
        throw new AttemptSpecError(`attempt ${expected.runCaseId} run report admission.selection is malformed`);
      }
      if (sel.case_id !== undefined && sel.case_id !== payload.case_id) {
        throw new AttemptSpecError(
          `attempt ${expected.runCaseId} admission case_id mismatch: admission=${sel.case_id} spec=${payload.case_id}`
        );
      }
      if (sel.case_revision !== undefined && Number(sel.case_revision) !== payload.case_revision) {
        throw new AttemptSpecError(
          `attempt ${expected.runCaseId} admission case_revision mismatch: admission=${sel.case_revision} spec=${payload.case_revision}`
        );
      }
      if (sel.platform !== undefined && sel.platform !== payload.platform) {
        throw new AttemptSpecError(
          `attempt ${expected.runCaseId} admission platform mismatch: admission=${sel.platform} spec=${payload.platform}`
        );
      }
      for (const [admissionField, specField] of [
        ["case_sha256", "full_yaml_sha256"],
        ["goal_sha256", "rendered_goal_sha256"],
        ["spec_sha256", "spec_sha256"],
      ]) {
        if (typeof sel[admissionField] !== "string" || !SHA256_RE.test(sel[admissionField])) {
          throw new AttemptSpecError(
            `attempt ${expected.runCaseId} admission.selection.${admissionField} is missing or invalid: ${JSON.stringify(sel[admissionField])}`
          );
        }
        if (sel[admissionField] !== payload[specField]) {
          throw new AttemptSpecError(
            `attempt ${expected.runCaseId} admission.selection.${admissionField} disagrees with ${specField}: admission=${sel[admissionField]} spec=${payload[specField]}`
          );
        }
      }
    } else if (admissionAttempt > payload.attempt) {
      throw new AttemptSpecError(
        `attempt ${expected.runCaseId} admission attempt ${admissionAttempt} exceeds spec attempt ${payload.attempt}`
      );
    }
  }

  const sliceDoc = parseYaml(payload.platform_slice_yaml);
  const fullDoc = parseYaml(payload.full_yaml);
  if (!plainObject(sliceDoc) || !plainObject(fullDoc)) {
    throw new AttemptSpecError(`attempt ${expected.runCaseId} frozen YAML did not parse to an object`);
  }
  if (sliceDoc.id !== undefined && sliceDoc.id !== expected.caseId) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} platform_slice_yaml id mismatch: expected=${expected.caseId} actual=${sliceDoc.id}`
    );
  }
  if (fullDoc.id !== undefined && fullDoc.id !== expected.caseId) {
    throw new AttemptSpecError(
      `attempt ${expected.runCaseId} full_yaml id mismatch: expected=${expected.caseId} actual=${fullDoc.id}`
    );
  }
  const assertionSource = Array.isArray(sliceDoc.assertions) ? sliceDoc.assertions : fullDoc.assertions;
  const assertionIds = Array.isArray(assertionSource)
    ? assertionSource.map((a) => a?.id).filter((id) => typeof id === "string" && id)
    : [];
  if (!assertionIds.length) {
    throw new AttemptSpecError(`case ${expected.caseId} (attempt ${expected.runCaseId}) has no stable assertion ids`);
  }

  return {
    status: "verified",
    contract_version: payload.contract_version,
    binding_status: payload.binding_status,
    run_id: payload.run_id,
    run_case_id: payload.run_case_id,
    case_id: payload.case_id,
    frozen_revision: payload.case_revision,
    attempt: payload.attempt,
    platform: payload.platform,
    spec_sha256: payload.spec_sha256,
    full_yaml: payload.full_yaml,
    full_yaml_sha256: payload.full_yaml_sha256,
    platform_slice_yaml: payload.platform_slice_yaml,
    platform_slice_sha256: payload.platform_slice_sha256,
    rendered_goal: payload.rendered_goal,
    rendered_goal_sha256: payload.rendered_goal_sha256,
    execution_budget: budget,
    assertion_ids: assertionIds,
  };
}

// Fetches one attempt's frozen specification from Testbed.
// `getJson(path)` must return `{ status, body }`.
export async function fetchAttemptSpecification({ getJson, runId, runCase, admission = null, defaultPlatform, parseYaml }) {
  if (!runCase?.id) {
    throw new AttemptSpecError(`case ${runCase?.case_id || "unknown"} is missing run_case_id; cannot fetch frozen spec`);
  }
  const expectedPlatform = (typeof runCase.platform === "string" && runCase.platform.trim())
    ? runCase.platform.trim()
    : defaultPlatform;
  const path = attemptSpecPath(runId, runCase.id);
  const { status, body } = await getJson(path);
  if (status === 409 && plainObject(body) && INCOMPLETE_SPEC_CODES.has(body.code)) {
    return {
      status: "incomplete",
      http_status: 409,
      code: body.code,
      run_id: runId,
      run_case_id: runCase.id,
      case_id: runCase.case_id,
      frozen_revision: Number(runCase.case_revision),
      attempt: runCase.attempt != null ? Number(runCase.attempt) : null,
      platform: expectedPlatform,
    };
  }
  if (status !== 200) {
    const code = plainObject(body) && typeof body.code === "string" ? ` (${body.code})` : "";
    throw new AttemptSpecError(
      `Stamp ${status} ${path}${code}: ${JSON.stringify(body ?? null).slice(0, 500)}`
    );
  }
  return verifyAttemptSpecification(
    body,
    {
      runId,
      runCaseId: runCase.id,
      caseId: runCase.case_id,
      caseRevision: runCase.case_revision,
      attempt: runCase.attempt,
      platform: expectedPlatform,
      reportSpecSha256: runCase.spec_sha256,
      reportExecutionBudgetJson: runCase.execution_budget_json,
      ...(admission !== null && admission !== undefined ? { admission } : {}),
    },
    parseYaml,
  );
}
