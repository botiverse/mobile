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

// Verifies a 200 payload from GET /api/runs/{run_id}/cases/{run_case_id}/spec
// against the expected attempt identity and recomputed UTF-8 SHA-256 digests.
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
export async function fetchAttemptSpecification({ getJson, runId, runCase, defaultPlatform, parseYaml }) {
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
    },
    parseYaml,
  );
}
