#!/usr/bin/env node
// Records and verifies the frozen specification & execution budget returned by
// Testbed's ordinary executor claim (`POST /api/agent/run-cases/{id}/claim`,
// botiverse/testbed@93f821e2, #raft-mobile-reconcile:#257).
//
// Legacy/unchecked attempts carry neither `spec_sha256` nor
// `rendered_goal_sha256` in their claim reply (`bound: false`).
// Checked/bound attempts carry `spec_sha256`, `rendered_goal_sha256`,
// `spec_yaml` (the frozen platform slice), and `guardrails` (the attempt's
// `execution_budget`). Any missing/malformed field or mismatch against the
// job's received goal (`QA_GOAL`) or budget (`QA_MAX_TURNS`, `QA_MAX_MINUTES`,
// `QA_NO_PROGRESS_TURNS`) fails closed before execution begins.

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { normalizeAttemptExecutionBudget, sha256Utf8 } from "./review_attempt_spec.mjs";

export const CLAIM_SPEC_BINDING_CONTRACT = "mobile-claim-spec-binding/v1";
const SHA256_RE = /^[0-9a-f]{64}$/;
const CASE_ID_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

export class ClaimSpecBindingError extends Error {}

function plainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function parsePositiveIntInput(raw, name) {
  if (raw === undefined || raw === null || String(raw) === "") return null;
  if (!/^[1-9][0-9]*$/.test(String(raw))) {
    throw new ClaimSpecBindingError(`invalid dispatched ${name}: ${JSON.stringify(raw)}`);
  }
  return Number(raw);
}

export function extractClaimSpecBinding(claimResponse, { runCaseId = null } = {}) {
  if (!plainObject(claimResponse)) {
    throw new ClaimSpecBindingError("claim response is not a JSON object");
  }
  const hasSpecSha = claimResponse.spec_sha256 !== undefined && claimResponse.spec_sha256 !== null;
  const hasGoalSha = claimResponse.rendered_goal_sha256 !== undefined && claimResponse.rendered_goal_sha256 !== null;
  const expectedRunCaseId = typeof runCaseId === "string" && runCaseId.trim() ? runCaseId.trim() : null;
  const hasResponseRunCaseId = claimResponse.run_case_id !== undefined && claimResponse.run_case_id !== null;
  const responseRunCaseId = typeof claimResponse.run_case_id === "string" && claimResponse.run_case_id.trim()
    ? claimResponse.run_case_id.trim()
    : null;
  if (hasResponseRunCaseId && !responseRunCaseId) {
    throw new ClaimSpecBindingError(`claim response has invalid run_case_id: ${JSON.stringify(claimResponse.run_case_id)}`);
  }
  if (expectedRunCaseId && responseRunCaseId && responseRunCaseId !== expectedRunCaseId) {
    throw new ClaimSpecBindingError(
      `claim response run_case_id mismatch: expected=${expectedRunCaseId} actual=${responseRunCaseId}`
    );
  }
  const resolvedCaseId = typeof claimResponse.case_id === "string" && CASE_ID_RE.test(claimResponse.case_id)
    ? claimResponse.case_id
    : null;
  if (!hasSpecSha && !hasGoalSha) {
    return {
      contract_version: CLAIM_SPEC_BINDING_CONTRACT,
      bound: false,
      run_case_id: responseRunCaseId || expectedRunCaseId || null,
      case_id: resolvedCaseId,
    };
  }
  if (!expectedRunCaseId) {
    throw new ClaimSpecBindingError("bound claim requires non-empty requested run_case_id");
  }
  if (!responseRunCaseId) {
    throw new ClaimSpecBindingError("bound claim response is missing run_case_id");
  }
  if (typeof claimResponse.spec_sha256 !== "string" || !SHA256_RE.test(claimResponse.spec_sha256)) {
    throw new ClaimSpecBindingError(`claim response has invalid spec_sha256: ${JSON.stringify(claimResponse.spec_sha256)}`);
  }
  if (typeof claimResponse.rendered_goal_sha256 !== "string" || !SHA256_RE.test(claimResponse.rendered_goal_sha256)) {
    throw new ClaimSpecBindingError(`claim response has invalid rendered_goal_sha256: ${JSON.stringify(claimResponse.rendered_goal_sha256)}`);
  }
  if (typeof claimResponse.spec_yaml !== "string" || !claimResponse.spec_yaml.length) {
    throw new ClaimSpecBindingError("bound claim response is missing non-empty spec_yaml");
  }
  const budget = normalizeAttemptExecutionBudget(claimResponse.guardrails);
  if (!budget) {
    throw new ClaimSpecBindingError(`bound claim response has invalid guardrails: ${JSON.stringify(claimResponse.guardrails)}`);
  }
  return {
    contract_version: CLAIM_SPEC_BINDING_CONTRACT,
    bound: true,
    run_case_id: responseRunCaseId,
    case_id: resolvedCaseId,
    spec_sha256: claimResponse.spec_sha256,
    platform_slice_sha256: sha256Utf8(claimResponse.spec_yaml),
    rendered_goal_sha256: claimResponse.rendered_goal_sha256,
    execution_budget: budget,
  };
}

export function verifyClaimSpecBinding(
  binding,
  dispatched = {},
  { requireGoal = false, requireBudget = false, expectedRunCaseId = null } = {},
) {
  if (!plainObject(binding) || binding.contract_version !== CLAIM_SPEC_BINDING_CONTRACT) {
    throw new ClaimSpecBindingError("claim spec binding file is missing or has unsupported contract_version");
  }
  const expectedId = typeof expectedRunCaseId === "string" && expectedRunCaseId.trim()
    ? expectedRunCaseId.trim()
    : null;
  const bindingRunCaseId = typeof binding.run_case_id === "string" && binding.run_case_id.trim()
    ? binding.run_case_id.trim()
    : null;
  if (expectedId && bindingRunCaseId && bindingRunCaseId !== expectedId) {
    throw new ClaimSpecBindingError(
      `claim spec binding run_case_id mismatch: expected=${expectedId} actual=${bindingRunCaseId}`
    );
  }
  if (!binding.bound) {
    return { ...binding, verified: true, mode: "legacy_unbound" };
  }
  if (!expectedId) {
    throw new ClaimSpecBindingError("bound claim verification requires non-empty requested run_case_id");
  }
  if (!bindingRunCaseId) {
    throw new ClaimSpecBindingError("bound claim spec binding is missing run_case_id");
  }
  if (bindingRunCaseId !== expectedId) {
    throw new ClaimSpecBindingError(
      `claim spec binding run_case_id mismatch: expected=${expectedId} actual=${bindingRunCaseId}`
    );
  }
  if (typeof binding.spec_sha256 !== "string" || !SHA256_RE.test(binding.spec_sha256)) {
    throw new ClaimSpecBindingError("claim spec binding has invalid spec_sha256");
  }
  if (typeof binding.platform_slice_sha256 !== "string" || !SHA256_RE.test(binding.platform_slice_sha256)) {
    throw new ClaimSpecBindingError("claim spec binding has invalid platform_slice_sha256");
  }
  if (typeof binding.rendered_goal_sha256 !== "string" || !SHA256_RE.test(binding.rendered_goal_sha256)) {
    throw new ClaimSpecBindingError("claim spec binding has invalid rendered_goal_sha256");
  }
  const claimedBudget = normalizeAttemptExecutionBudget(binding.execution_budget);
  if (!claimedBudget) {
    throw new ClaimSpecBindingError("claim spec binding has invalid execution_budget");
  }

  let receivedGoalSha = null;
  const hasGoal = typeof dispatched.goal === "string" && dispatched.goal.length > 0;
  if (requireGoal && !hasGoal) {
    throw new ClaimSpecBindingError("bound claim requires non-empty dispatched QA_GOAL");
  }
  if (hasGoal) {
    receivedGoalSha = sha256Utf8(dispatched.goal);
    if (receivedGoalSha !== binding.rendered_goal_sha256) {
      throw new ClaimSpecBindingError(
        `dispatched goal sha256 mismatch: claimed=${binding.rendered_goal_sha256} received=${receivedGoalSha}`
      );
    }
  }

  const dispatchedBudget = {
    max_turns: parsePositiveIntInput(dispatched.maxTurns, "QA_MAX_TURNS"),
    max_minutes: parsePositiveIntInput(dispatched.maxMinutes, "QA_MAX_MINUTES"),
    no_progress_turns: parsePositiveIntInput(dispatched.noProgressTurns, "QA_NO_PROGRESS_TURNS"),
  };
  for (const [key, value] of Object.entries(dispatchedBudget)) {
    if (requireBudget && value === null) {
      throw new ClaimSpecBindingError(`bound claim requires dispatched budget field ${key}`);
    }
    if (value !== null && value !== claimedBudget[key]) {
      throw new ClaimSpecBindingError(
        `dispatched execution budget mismatch for ${key}: claimed=${claimedBudget[key]} received=${value}`
      );
    }
  }

  return {
    ...binding,
    execution_budget: claimedBudget,
    received_goal_sha256: receivedGoalSha,
    dispatched_budget: dispatchedBudget,
    verified: true,
    mode: "bound",
  };
}

function argValue(argv, flag) {
  const i = argv.indexOf(flag);
  if (i < 0 || i + 1 >= argv.length) return "";
  const val = argv[i + 1];
  return val.startsWith("--") ? "" : val;
}

function writePrivateJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  fs.chmodSync(filePath, 0o600);
}

function main(argv) {
  const [command] = argv;
  if (command === "record") {
    const responsePath = argValue(argv, "--response");
    const outPath = argValue(argv, "--out");
    const runCaseId = argValue(argv, "--run-case-id") || process.env.STAMP_RUN_CASE_ID || "";
    if (!responsePath || !outPath) {
      throw new ClaimSpecBindingError("usage: claim_spec_binding.mjs record --response <file> --out <file>");
    }
    const response = JSON.parse(fs.readFileSync(responsePath, "utf8"));
    const binding = extractClaimSpecBinding(response, { runCaseId });
    const hasDispatched = Boolean(
      process.env.QA_GOAL ||
      process.env.QA_MAX_TURNS ||
      process.env.QA_MAX_MINUTES ||
      process.env.QA_NO_PROGRESS_TURNS
    );
    const recorded = hasDispatched
      ? verifyClaimSpecBinding(
          binding,
          {
            goal: process.env.QA_GOAL || "",
            maxTurns: process.env.QA_MAX_TURNS,
            maxMinutes: process.env.QA_MAX_MINUTES,
            noProgressTurns: process.env.QA_NO_PROGRESS_TURNS,
          },
          { expectedRunCaseId: runCaseId },
        )
      : binding;
    writePrivateJson(outPath, recorded);
    return;
  }
  if (command === "verify") {
    const bindingPath = argValue(argv, "--binding");
    const outPath = argValue(argv, "--out");
    const runCaseId = argValue(argv, "--run-case-id") || process.env.STAMP_RUN_CASE_ID || "";
    if (!bindingPath) {
      throw new ClaimSpecBindingError("usage: claim_spec_binding.mjs verify --binding <file> [--out <file>]");
    }
    const binding = JSON.parse(fs.readFileSync(bindingPath, "utf8"));
    const verified = verifyClaimSpecBinding(
      binding,
      {
        goal: process.env.QA_GOAL || "",
        maxTurns: process.env.QA_MAX_TURNS,
        maxMinutes: process.env.QA_MAX_MINUTES,
        noProgressTurns: process.env.QA_NO_PROGRESS_TURNS,
      },
      { requireGoal: true, requireBudget: true, expectedRunCaseId: runCaseId },
    );
    if (outPath) writePrivateJson(outPath, verified);
    if (verified.bound) {
      console.log(
        `claim spec binding: verified spec=${verified.spec_sha256.slice(0, 12)} slice=${verified.platform_slice_sha256.slice(0, 12)} goal=${verified.rendered_goal_sha256.slice(0, 12)} budget=${verified.execution_budget.max_turns}/${verified.execution_budget.max_minutes}/${verified.execution_budget.no_progress_turns}`
      );
    } else {
      console.log("claim spec binding: legacy unbound attempt; skipping frozen goal/budget check");
    }
    return;
  }
  throw new ClaimSpecBindingError("usage: claim_spec_binding.mjs <record|verify> ...");
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(`claim_spec_binding: ${error?.message || String(error)}`);
    process.exit(1);
  }
}
