#!/usr/bin/env node

// Independent post-verdict M3 diagnosis. Unlike the release review panel this
// pass intentionally reads the executor trajectory: its job is to explain why
// execution failed and propose the next immutable action, not to judge release.

import crypto from "node:crypto";
import fs from "node:fs";

const argv = process.argv.slice(2);
const arg = (name, fallback = "") => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : fallback; };
const runCaseId = arg("--run-case-id", process.env.STAMP_RUN_CASE_ID || "");
const stampUrl = (process.env.STAMP_URL || "https://stamp.build").replace(/\/$/, "");
const stampToken = process.env.STAMP_REVIEW_TOKEN || process.env.STAMP_TOKEN || "";
const piKey = process.env.MINIMAX_API_KEY || process.env.PI_API_KEY || "";
const ciJobRef = process.env.GITHUB_SERVER_URL && process.env.GITHUB_REPOSITORY && process.env.GITHUB_RUN_ID
  ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}` : "";
const out = arg("--out", "failure-diagnosis.json");
if (!runCaseId || !stampToken || !piKey || !ciJobRef) throw new Error("run-case id, reviewer token, model key, and GitHub run identity are required");

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

async function acquireCapacity() {
  while (true) {
    const res = await fetch(`${stampUrl}/api/diagnoses/${runCaseId}/model-capacity/acquire`, {
      method: "POST",
      headers: { authorization: `Bearer ${stampToken}`, "content-type": "application/json", "x-stamp-ci-job-ref": ciJobRef },
      body: JSON.stringify({ provider: "minimax", model: "MiniMax-M3" }),
    });
    const body = await res.json();
    if (res.status === 200) return body.lease_id;
    if (res.status !== 202) throw new Error(`capacity acquire ${res.status}: ${JSON.stringify(body)}`);
    await new Promise((resolve) => setTimeout(resolve, Number(body.retry_after_ms || 5000)));
  }
}

async function releaseCapacity(leaseId) {
  if (!leaseId) return;
  let last = "";
  for (let attempt = 1; attempt <= 3; attempt++) {
    const res = await fetch(`${stampUrl}/api/diagnoses/${runCaseId}/model-capacity/release`, {
      method: "POST",
      headers: { authorization: `Bearer ${stampToken}`, "content-type": "application/json", "x-stamp-ci-job-ref": ciJobRef },
      body: JSON.stringify({ lease_id: leaseId }),
    });
    last = await res.text();
    if (res.ok) {
      const body = last ? JSON.parse(last) : {};
      if (body.released || body.alreadyReleased) return;
    }
    if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, 1000 * attempt));
  }
  throw new Error(`capacity release was not confirmed: ${last.slice(0, 300)}`);
}

function validate(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("diagnosis must be an object");
  if (!["product", "navigation_contract", "fixture", "budget", "infra"].includes(value.classification)) throw new Error("invalid classification");
  if (!["high", "medium", "low"].includes(value.confidence)) throw new Error("invalid confidence");
  if (typeof value.target_surface_reached !== "boolean" || typeof value.summary !== "string" || !value.summary.trim()) throw new Error("missing diagnosis facts");
  if (!Array.isArray(value.missing_guidance) || !Array.isArray(value.evidence_ids)) throw new Error("missing arrays");
  return value;
}

async function main() {
  const context = await stamp(`/api/diagnoses/${runCaseId}/context`);
  const rc = context.run_case;
  const evidence = context.evidence;
  const shots = evenly(evidence.filter((item) => item.kind === "screenshot"), 12);
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
    '{"classification":"product|navigation_contract|fixture|budget|infra","confidence":"high|medium|low","target_surface_reached":true|false,"first_blocked_step":"step/id or null","summary":"evidence-grounded root cause","missing_guidance":["specific addition"],"recommended_budget":{"max_turns":20-160,"max_minutes":10-90,"no_progress_turns":3-30}|null,"retry_plan":"review_product|revise_case|repair_fixture|retry_budget|retry_infra","evidence_ids":["ids used"],"first_product_failure":{"action":"performed action","expected":"expected state","observed":"fresh contradictory state","evidence_ids":["ids"]}|null}',
  ].join("\n");
  const content = [{ type: "text", text: prompt }];
  for (const item of included) {
    const value = bytes.get(item.id);
    if (item.kind === "screenshot") {
      content.push({ type: "text", text: `SCREENSHOT id=${item.id} seq=${item.seq} step=${item.step_index ?? "?"}` });
      content.push({ type: "image", data: value.toString("base64"), mimeType: "image/png" });
    } else {
      content.push({ type: "text", text: `${item.kind.toUpperCase()} id=${item.id} seq=${item.seq}\n${sanitized(value)}` });
    }
  }

  const providers = await import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all");
  const models = providers.builtinModels();
  const model = models.getModel(String(process.env.PI_PROVIDER || "minimax"), String(process.env.PI_MODEL || "MiniMax-M3"));
  let diagnosis, raw = "", leaseId;
  try {
    leaseId = await acquireCapacity();
    for (let attempt = 1; attempt <= 3; attempt++) {
      const response = await models.complete(model, { messages: [{ role: "user", content, timestamp: Date.now() }] }, { apiKey: piKey, maxTokens: 3000, timeoutMs: 180000 });
      raw = response.content.filter((block) => block.type === "text").map((block) => block.text).join("\n");
      try { diagnosis = validate(JSON.parse(raw.trim())); break; } catch (error) {
        if (attempt === 3) throw error;
        content.unshift({ type: "text", text: "FORMAT CORRECTION: output exactly one valid JSON object matching the schema." });
      }
    }
  } finally {
    await releaseCapacity(leaseId);
  }
  const submitted = await stamp(`/api/diagnoses/${runCaseId}`, { method: "POST", body: JSON.stringify(diagnosis) });
  fs.writeFileSync(out, JSON.stringify({ run_case_id: runCaseId, diagnosis, submitted, raw_model_output: raw }, null, 2));
  console.log(JSON.stringify({ run_case_id: runCaseId, diagnosis, submitted }, null, 2));
}

main().catch((error) => {
  fs.writeFileSync(out, JSON.stringify({ run_case_id: runCaseId, error: String(error?.stack || error) }, null, 2));
  console.error(error);
  process.exit(1);
});
