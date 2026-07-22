#!/usr/bin/env node

// Independent post-verdict M3 diagnosis. Unlike the release review panel this
// pass intentionally reads the executor trajectory: its job is to explain why
// execution failed and propose the next immutable action, not to judge release.

import crypto from "node:crypto";
import fs from "node:fs";
import { runWithModelRetries, StampModelCapacityClient } from "./model_capacity.mjs";
import { validateReviewerDiagnosis } from "./failure_diagnosis_contract.mjs";

const argv = process.argv.slice(2);
const arg = (name, fallback = "") => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : fallback; };
const runCaseId = arg("--run-case-id", process.env.STAMP_RUN_CASE_ID || "");
const stampUrl = (process.env.STAMP_URL || "https://stamp.build").replace(/\/$/, "");
const stampToken = process.env.STAMP_REVIEW_TOKEN || "";
const piKey = process.env.MINIMAX_API_KEY || process.env.PI_API_KEY || "";
const providerName = String(process.env.PI_PROVIDER || "minimax");
const modelName = String(process.env.PI_MODEL || "MiniMax-M3");
const ciJobRef = process.env.GITHUB_SERVER_URL && process.env.GITHUB_REPOSITORY && process.env.GITHUB_RUN_ID
  ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}` : "";
const out = arg("--out", "failure-diagnosis.json");
if (!runCaseId || !stampToken || !piKey || !ciJobRef) throw new Error("run-case id, dedicated STAMP_REVIEW_TOKEN, model key, and GitHub run identity are required");

async function stamp(path, init = {}) {
  const res = await fetch(`${stampUrl}${path}`, {
    ...init,
    headers: { authorization: `Bearer ${stampToken}`, ...(init.body ? { "content-type": "application/json" } : {}), ...(init.headers || {}) },
  });
  const body = await res.text();
  if (!res.ok) throw new Error(`Stamp ${res.status} ${path}: ${body.slice(0, 500)}`);
  return body ? JSON.parse(body) : {};
}

function sanitized(bytes, limit = 100000) {
  return bytes.toString("utf8")
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, "<redacted-email>")
    .replace(/(sk_(?:agent|machine)_)[A-Za-z0-9_-]+/g, "$1<redacted>")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, "<redacted-jwt>")
    .replace(/((?:password|passwd|token|authorization)["'=:\s]+)[^\s"']+/gi, "$1<redacted>")
    .slice(0, limit);
}

function evenly(items, limit) {
  if (items.length <= limit) return items;
  return [...new Map(Array.from({ length: limit }, (_, i) => items[Math.round(i * (items.length - 1) / (limit - 1))]).map((item) => [item.id, item])).values()];
}

async function evidenceBytes(runId, evidence) {
  const res = await fetch(`${stampUrl}/api/runs/${runId}/evidence/${evidence.id}`, { headers: { authorization: `Bearer ${stampToken}` } });
  if (!res.ok) throw new Error(`evidence ${evidence.id}: HTTP ${res.status}`);
  const bytes = Buffer.from(await res.arrayBuffer());
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== evidence.sha256) throw new Error(`evidence ${evidence.id} sha256 mismatch`);
  return bytes;
}

async function main() {
  const context = await stamp(`/api/diagnoses/${runCaseId}/context`);
  const rc = context.run_case;
  const evidence = context.evidence;
  const shots = evenly(evidence.filter((item) => ["screenshot", "screenshot_annotated"].includes(item.kind)), 12);
  const textEvidence = evidence.filter((item) => ["steps_log", "session_log", "ui_dump", "report", "logcat", "checkpoint"].includes(item.kind));
  const included = [...shots, ...textEvidence];
  const bytes = new Map();
  for (const item of included) bytes.set(item.id, await evidenceBytes(rc.run_id, item));

  const prompt = [
    "You are an independent failure-diagnosis M3 reviewer. You are NOT deciding release and must not reward or punish the executor.",
    "Explain why this immutable attempt did not pass and choose the next system action.",
    "An unverified requirement is never enough for product. Product requires high confidence, target surface reached, a performed action, and fresh contradictory evidence.",
    "Use navigation_contract for missing/ambiguous entry or locator; fixture for seed/login/permission/assertion/environment; budget only when the target surface was reached and the trajectory kept making useful progress; infra for runner/provider/system failures.",
    "For budget, recommend a bounded case-specific max_turns/max_minutes from observed progress plus margin and retain a no-progress fuse. Do not globally increase time.",
    "Old attempt/revision remains immutable. Product -> human review only; navigation -> new case revision; fixture -> repair then retry; budget/infra -> new immutable retry.",
    "",
    `Run case: ${runCaseId}; case=${rc.case_id}; revision=${rc.case_revision}; attempt=${rc.attempt}; coordinate=${rc.coordinate_key}`,
    `Executor verdict: ${rc.verdict_json}`,
    `Mechanical quality gate: ${rc.quality_gate_json || "null"}`,
    "Frozen case YAML:", rc.spec_yaml,
    "",
    "Return strict JSON only:",
    '{"classification":"product|navigation_contract|fixture|budget|infra","confidence":"high|medium|low","target_surface_reached":true|false,"first_blocked_step":"step/id or null","summary":"evidence-grounded root cause","missing_guidance":["specific addition"],"recommended_budget":{"max_turns":20-160,"max_minutes":10-90,"no_progress_turns":3-30}|null,"retry_plan":"review_product|revise_case|repair_fixture|retry_budget|retry_infra","evidence_ids":["ids used"],"first_product_failure":{"action":"exact executor-owned product_proof.action_summary","action_step":19,"expected":"expected state","observed":"fresh contradictory state","evidence_ids":["steps_log id","post-action screenshot/ui id"]}|null}',
  ].join("\n");
  const content = [{ type: "text", text: prompt }];
  for (const item of included) {
    const value = bytes.get(item.id);
    if (["screenshot", "screenshot_annotated"].includes(item.kind)) {
      content.push({ type: "text", text: `SCREENSHOT id=${item.id} seq=${item.seq} step=${item.step_index ?? "?"}` });
      content.push({ type: "image", data: value.toString("base64"), mimeType: "image/png" });
    } else {
      content.push({ type: "text", text: `${item.kind.toUpperCase()} id=${item.id} seq=${item.seq}\n${sanitized(value)}` });
    }
  }

  const providers = await import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all");
  const models = providers.builtinModels();
  const model = models.getModel(providerName, modelName);
  let raw = "";
  const capacity = new StampModelCapacityClient({
    stampUrl, stampToken, runCaseId, claimToken: "", ciJobRef,
    provider: providerName, model: modelName, outDir: ".",
    capacityPath: `/api/diagnoses/${runCaseId}/model-capacity`,
    queueTimeoutMs: 600_000, releaseAttempts: 3,
  });
  const evidenceIndex = new Map(included.map((item) => [item.id, item]));
  let executorDiagnosis = null;
  try { executorDiagnosis = JSON.parse(rc.verdict_json || "null")?.execution_diagnosis ?? null; } catch { executorDiagnosis = null; }
  const actionFacts = [];
  let checkpointCount = 0;
  for (const item of textEvidence.filter((entry) => entry.kind === "steps_log")) {
    for (const line of sanitized(bytes.get(item.id), 2_000_000).split("\n")) {
      try {
        const fact = JSON.parse(line);
        if (fact.event === "action_fact" && fact.performed === true) actionFacts.push(fact);
        if (fact.event === "surface_checkpoint") checkpointCount++;
      } catch { /* malformed legacy lines do not create progress facts */ }
    }
  }
  const trajectory = {
    checkpointCount,
    performedActionCount: actionFacts.length,
    distinctPostActionScreens: new Set(actionFacts.map((fact) => fact.post_action_screenshot_sha256).filter(Boolean)).size,
  };
  const diagnosis = await runWithModelRetries({
    capacity,
    maxAttempts: 3,
    retryBudgetMs: 300_000,
    invoke: async (attempt) => {
      const attemptContent = attempt === 1 ? content : [
        { type: "text", text: "FORMAT/CONTRACT CORRECTION: return exactly one JSON object matching every classification, retry-plan, budget, and evidence-id constraint." },
        ...content,
      ];
      const response = await models.complete(model,
        { messages: [{ role: "user", content: attemptContent, timestamp: Date.now() }] },
        { apiKey: piKey, maxTokens: 3000, timeoutMs: 180000 });
      if (response.stopReason === "error" || response.stopReason === "aborted") throw new Error(response.errorMessage || response.stopReason);
      raw = response.content.filter((block) => block.type === "text").map((block) => block.text).join("\n");
      return validateReviewerDiagnosis(JSON.parse(raw.trim()), { evidenceIndex, executorDiagnosis, trajectory });
    },
  });
  const capacityBytes = fs.readFileSync("model-capacity.jsonl");
  diagnosis.provenance = {
    schema_version: "failure-diagnosis/v1",
    prompt_version: "failure-diagnosis-prompt/v1",
    model: `${providerName}/${modelName}`,
    source_commit: process.env.GITHUB_SHA || "",
    workflow_run_id: process.env.GITHUB_RUN_ID || "",
    workflow_run_attempt: process.env.GITHUB_RUN_ATTEMPT || "",
    workflow_ref: process.env.GITHUB_WORKFLOW_REF || "",
    capacity_evidence_sha256: crypto.createHash("sha256").update(capacityBytes).digest("hex"),
    capacity_evidence_jsonl: capacityBytes.toString("utf8"),
  };
  const submitted = await stamp(`/api/diagnoses/${runCaseId}`, { method: "POST", body: JSON.stringify(diagnosis) });
  fs.writeFileSync(out, JSON.stringify({ run_case_id: runCaseId, diagnosis, submitted, raw_model_output: raw }, null, 2));
  console.log(JSON.stringify({ run_case_id: runCaseId, diagnosis, submitted }, null, 2));
}

main().catch((error) => {
  fs.writeFileSync(out, JSON.stringify({ run_case_id: runCaseId, error: String(error?.stack || error) }, null, 2));
  console.error(error);
  process.exit(1);
});
