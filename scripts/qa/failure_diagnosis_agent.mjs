#!/usr/bin/env node

// Independent post-verdict M3 diagnosis. Unlike the release review panel this
// pass intentionally reads the executor trajectory: its job is to explain why
// execution failed and propose the next immutable action, not to judge release.

import { resolvePiModel } from "./pi_model.mjs";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { runWithModelRetries, StampModelCapacityClient } from "./model_capacity.mjs";
import { validateReviewerDiagnosis } from "./failure_diagnosis_contract.mjs";
import { createVideoEvidenceWorkspace } from "./video_evidence_workspace.mjs";

const argv = process.argv.slice(2);
const arg = (name, fallback = "") => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : fallback; };
const runCaseId = arg("--run-case-id", process.env.STAMP_RUN_CASE_ID || "");
const stampUrl = (process.env.STAMP_URL || "https://stamp.build").replace(/\/$/, "");
const stampToken = process.env.STAMP_REVIEW_TOKEN || "";
const providerName = String(process.env.PI_PROVIDER || "minimax");
// Key must follow the ACTIVE provider (workflow carries keys for several so a
// vars flip can switch back without a code change).
const keyOrder = providerName.startsWith("qwen")
  ? ["QWEN_TOKEN_PLAN_CN_API_KEY", "PI_API_KEY"]
  : ["MINIMAX_API_KEY", "PI_API_KEY"];
const piKey = keyOrder.map((name) => process.env[name] || "").find(Boolean) || "";
const modelName = String(process.env.PI_MODEL || "MiniMax-M3.1-Flash-Preview");
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
  const recordings = evidence.filter((item) => item.kind === "recording");
  const textEvidence = evidence.filter((item) => ["steps_log", "session_log", "ui_dump", "report", "logcat", "checkpoint"].includes(item.kind));
  const included = [...shots, ...textEvidence, ...recordings];
  const bytes = new Map();
  for (const item of included) bytes.set(item.id, await evidenceBytes(rc.run_id, item));
  const workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-diagnosis-"));
  const videoWorkspace = createVideoEvidenceWorkspace({ root: workspaceRoot, recordings, bytesById: bytes });
  const textWorkspaceManifest = textEvidence.map((item) => {
    const filename = `${String(item.id).replace(/[^A-Za-z0-9._-]/g, "_")}-${item.kind}.txt`;
    const value = sanitized(bytes.get(item.id), 2_000_000);
    fs.writeFileSync(path.join(workspaceRoot, filename), value, { mode: 0o600 });
    return { evidence_id: item.id, source_sha256: item.sha256, kind: item.kind, filename, size_bytes: Buffer.byteLength(value) };
  });
  fs.writeFileSync(path.join(workspaceRoot, "README.txt"), [
    "This workspace contains SHA-verified original MP4 evidence and redacted text evidence.",
    "The diagnosis agent chooses whether and where to inspect recordings through the provided ffprobe/ffmpeg tools.",
    "No platform-selected fixed frames are present.",
  ].join("\n"), { mode: 0o600 });

  const prompt = [
    "You are an independent failure-diagnosis M3 reviewer. You are NOT deciding release and must not reward or punish the executor.",
    "Explain why this immutable attempt did not pass and choose the next system action.",
    "An unverified requirement is never enough for product. Product requires high confidence, target surface reached, a performed action, and fresh contradictory evidence.",
    "Use navigation_contract for missing/ambiguous entry or locator; fixture for seed/login/permission/assertion/environment; budget only when the target surface was reached and the trajectory kept making useful progress; infra for runner/provider/system failures.",
    "For budget, recommend a bounded case-specific max_turns/max_minutes from observed progress plus margin and retain a no-progress fuse. Do not globally increase time.",
    "Old attempt/revision remains immutable. Product -> human review only; navigation -> new case revision; fixture -> repair then retry; budget/infra -> new immutable retry.",
    "Original SHA-verified MP4 recordings are available in your diagnosis workspace. The provider does not accept native video content, so use inspect_video and extract_video_frame yourself at timestamps chosen from the trajectory; no platform-selected fixed frames exist. Cite the original recording evidence id, not a derived frame filename.",
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
    } else if (item.kind !== "recording") {
      content.push({ type: "text", text: `${item.kind.toUpperCase()} id=${item.id} seq=${item.seq}\n${sanitized(value)}` });
    }
  }
  content.push({ type: "text", text: `DIAGNOSIS WORKSPACE MANIFEST\n${JSON.stringify({ recordings: videoWorkspace.manifest, text_evidence: textWorkspaceManifest })}` });

  const providers = await import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all");
  const models = providers.builtinModels();
  const model = resolvePiModel(models, providerName, modelName);
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
      const messages = [{ role: "user", content: attemptContent, timestamp: Date.now() }];
      const tools = videoWorkspace.manifest.length ? [
        {
          name: "inspect_video",
          description: "Inspect one original SHA-verified MP4 with ffprobe before choosing timestamps.",
          parameters: { type: "object", additionalProperties: false, required: ["evidence_id"], properties: { evidence_id: { type: "string" } } },
        },
        {
          name: "extract_video_frame",
          description: "Extract one JPEG from an original MP4 at an agent-chosen exact timestamp in milliseconds. Use only after inspect_video.",
          parameters: { type: "object", additionalProperties: false, required: ["evidence_id", "timestamp_ms"], properties: { evidence_id: { type: "string" }, timestamp_ms: { type: "integer", minimum: 0 } } },
        },
      ] : [];
      let response = null;
      for (let turn = 0; turn < 24; turn++) {
        response = await models.complete(model, { messages, tools }, { apiKey: piKey, maxTokens: 8000, reasoningEffort: "low", timeoutMs: 180000 });
        if (response.stopReason === "error" || response.stopReason === "aborted") throw new Error(response.errorMessage || response.stopReason);
        messages.push(response);
        const calls = response.content.filter((block) => block.type === "toolCall");
        if (!calls.length) break;
        for (const call of calls) {
          try {
            if (call.name === "inspect_video") {
              const result = videoWorkspace.inspectVideo(call.arguments.evidence_id);
              messages.push({ role: "toolResult", toolCallId: call.id, toolName: call.name, content: [{ type: "text", text: JSON.stringify(result) }], isError: false, timestamp: Date.now() });
            } else if (call.name === "extract_video_frame") {
              const frame = videoWorkspace.extractFrame(call.arguments.evidence_id, call.arguments.timestamp_ms);
              messages.push({ role: "toolResult", toolCallId: call.id, toolName: call.name, content: [
                { type: "text", text: JSON.stringify({ evidence_id: frame.evidence_id, source_sha256: frame.source_sha256, timestamp_ms: frame.timestamp_ms }) },
                { type: "image", data: frame.bytes.toString("base64"), mimeType: "image/jpeg" },
              ], isError: false, timestamp: Date.now() });
            } else {
              throw new Error(`unsupported tool: ${call.name}`);
            }
          } catch (error) {
            messages.push({ role: "toolResult", toolCallId: call.id, toolName: call.name, content: [{ type: "text", text: String(error?.message || error) }], isError: true, timestamp: Date.now() });
          }
        }
      }
      if (!response || response.stopReason === "toolUse") throw new Error("diagnosis agent exhausted tool turns without a final answer");
      raw = response.content.filter((block) => block.type === "text").map((block) => block.text).join("\n");
      return validateReviewerDiagnosis(JSON.parse(raw.trim()), { evidenceIndex, executorDiagnosis, trajectory });
    },
  });
  const capacityBytes = fs.readFileSync("model-capacity.jsonl");
  diagnosis.provenance = {
    schema_version: "failure-diagnosis/v2",
    prompt_version: "failure-diagnosis-prompt/v2",
    model: `${providerName}/${modelName}`,
    source_commit: process.env.GITHUB_SHA || "",
    video_workspace: {
      native_video_input: false,
      original_recordings: videoWorkspace.manifest,
      text_evidence: textWorkspaceManifest,
      agent_tool_calls: videoWorkspace.toolLog,
    },
    workflow_run_id: process.env.GITHUB_RUN_ID || "",
    workflow_run_attempt: process.env.GITHUB_RUN_ATTEMPT || "",
    workflow_ref: process.env.GITHUB_WORKFLOW_REF || "",
    capacity_evidence_sha256: crypto.createHash("sha256").update(capacityBytes).digest("hex"),
    capacity_evidence_jsonl: capacityBytes.toString("utf8"),
  };
  fs.writeFileSync(out, JSON.stringify({ stage: "ready_to_submit", run_case_id: runCaseId, diagnosis, raw_model_output: raw }, null, 2));
  const submitted = await stamp(`/api/diagnoses/${runCaseId}`, { method: "POST", body: JSON.stringify(diagnosis) });
  fs.writeFileSync(out, JSON.stringify({ stage: "submitted", run_case_id: runCaseId, diagnosis, submitted, raw_model_output: raw }, null, 2));
  fs.rmSync(workspaceRoot, { recursive: true, force: true });
  console.log(JSON.stringify({ run_case_id: runCaseId, diagnosis, submitted }, null, 2));
}

main().catch((error) => {
  let prior = {};
  try { prior = JSON.parse(fs.readFileSync(out, "utf8")); } catch { prior = {}; }
  fs.writeFileSync(out, JSON.stringify({ ...prior, run_case_id: runCaseId, error: String(error?.stack || error) }, null, 2));
  console.error(error);
  process.exit(1);
});
