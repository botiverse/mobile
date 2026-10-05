#!/usr/bin/env node

// Independent Stamp evidence reviewer. It deliberately excludes the executor's
// verdict, reason, annotated screenshots, session transcript, and action log.
// Each invocation has a fresh model context and posts exactly one panel vote.

import { resolvePiModel } from "./pi_model.mjs";
import crypto from "node:crypto";
import fs from "node:fs";

const argv = process.argv.slice(2);
const arg = (name, fallback = "") => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : fallback;
};

const runId = arg("--run-id", process.env.STAMP_RUN_ID || "");
const reviewer = arg("--reviewer", process.env.STAMP_REVIEWER || "");
const outPath = arg("--out", process.env.STAMP_REVIEW_OUT || `panel-review-${reviewer || "unknown"}.json`);
const stampUrl = (process.env.STAMP_URL || "https://stamp.build").replace(/\/$/, "");
const stampToken = process.env.STAMP_TOKEN || "";
const providerName = String(process.env.PI_PROVIDER || "minimax");
// The workflow carries keys for more than one provider so a vars flip can
// switch back; the key must follow the ACTIVE provider or auth fails.
const keyOrder = providerName.startsWith("qwen")
  ? ["QWEN_TOKEN_PLAN_CN_API_KEY", "PI_API_KEY"]
  : ["MINIMAX_API_KEY", "PI_API_KEY"];
const piKey = keyOrder.map((name) => process.env[name] || "").find(Boolean) || "";
const modelName = String(process.env.PI_MODEL || "MiniMax-M3.1-Flash-Preview");
// Panel run 30102844933: three opaque 600s waits, 0/3 votes. Keep the budget
// but expose it as a dispatch-time knob so capacity incidents tune it without
// a code change. The workflow freezes the value once per execution; anything
// non-integer or outside sane bounds fails closed here rather than becoming a
// 0/NaN instant-abort budget.
const modelTimeoutMs = Number(process.env.PI_REVIEW_TIMEOUT_MS || 600000);
if (!Number.isInteger(modelTimeoutMs) || modelTimeoutMs < 60000 || modelTimeoutMs > 1800000) {
  throw new Error(`PI_REVIEW_TIMEOUT_MS must be an integer in [60000, 1800000], got: ${process.env.PI_REVIEW_TIMEOUT_MS}`);
}
const record = { run_id: runId, reviewer, provider: providerName, model: modelName, stage: "init" };

if (!runId || !reviewer) throw new Error("--run-id and --reviewer are required");
if (!stampToken) throw new Error("STAMP_TOKEN is required");
if (!piKey) throw new Error(`an API key for provider ${providerName} is required (${keyOrder.join("/")})`);

function saveRecord(patch = {}) {
  Object.assign(record, patch);
  fs.writeFileSync(outPath, JSON.stringify(record, null, 2));
}

async function stamp(path, init = {}) {
  const res = await fetch(`${stampUrl}${path}`, {
    ...init,
    headers: {
      authorization: `Bearer ${stampToken}`,
      ...(init.body ? { "content-type": "application/json" } : {}),
      ...(init.headers || {}),
    },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`Stamp ${res.status} ${path}: ${text.slice(0, 500)}`);
  return text ? JSON.parse(text) : {};
}

async function evidenceBytes(ev) {
  let res;
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      res = await fetch(`${stampUrl}/api/runs/${runId}/evidence/${ev.id}`, {
        headers: { authorization: `Bearer ${stampToken}` },
      });
      if (res.ok || res.status < 500) break;
      lastError = new Error(`HTTP ${res.status}`);
    } catch (error) {
      lastError = error;
    }
    if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, attempt * 1000));
  }
  if (!res) throw lastError || new Error(`evidence ${ev.id}: fetch failed`);
  if (!res.ok) throw new Error(`evidence ${ev.id}: HTTP ${res.status}`);
  const bytes = Buffer.from(await res.arrayBuffer());
  const actual = crypto.createHash("sha256").update(bytes).digest("hex");
  if (!ev.sha256 || actual !== ev.sha256) {
    throw new Error(`evidence ${ev.id} sha256 mismatch: manifest=${ev.sha256 || "missing"} actual=${actual}`);
  }
  return bytes;
}

function evenly(items, limit) {
  if (items.length <= limit) return items;
  const out = [];
  for (let i = 0; i < limit; i++) out.push(items[Math.round((i * (items.length - 1)) / (limit - 1))]);
  return [...new Map(out.map((x) => [x.id, x])).values()];
}

function parseJson(text) {
  return JSON.parse(text.trim());
}

function plainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function validateVote(vote, cases) {
  if (!plainObject(vote)) throw new Error("review output must be a JSON object");
  if (!["passed", "failed"].includes(vote.verdict)) throw new Error(`invalid reviewer verdict: ${vote.verdict}`);
  if (typeof vote.reason !== "string" || !vote.reason.trim()) throw new Error("review reason must be non-empty");
  if (!plainObject(vote.detail_json)) throw new Error("detail_json must be an object keyed by case and assertion id");
  if (!Array.isArray(vote.anomalies) || vote.anomalies.some((x) => typeof x !== "string")) {
    throw new Error("anomalies must be an array of strings");
  }
  let failedAssertions = 0;
  for (const c of cases) {
    const details = vote.detail_json[c.case_id];
    if (!plainObject(details)) throw new Error(`detail_json.${c.case_id} is required`);
    for (const assertionId of c.assertion_ids) {
      const result = details[assertionId];
      if (!plainObject(result) || typeof result.ok !== "boolean" || typeof result.note !== "string" || !result.note.trim()) {
        throw new Error(`detail_json.${c.case_id}.${assertionId} requires boolean ok and non-empty note`);
      }
      if (!result.ok) failedAssertions++;
    }
  }
  if (vote.verdict === "passed" && (failedAssertions || vote.anomalies.length)) {
    throw new Error("passed vote requires every assertion ok=true and anomalies=[]");
  }
  if (vote.verdict === "failed" && !failedAssertions && !vote.anomalies.length) {
    throw new Error("failed vote requires at least one failed assertion or anomaly");
  }
}

function sanitizedEvidenceText(bytes, limit = 120000) {
  return bytes.toString("utf8")
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, "<redacted-email>")
    .replace(/(sk_(?:agent|machine)_)[A-Za-z0-9_-]+/g, "$1<redacted>")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, "<redacted-jwt>")
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, "$1<redacted>")
    .replace(/((?:password|passwd|token|authorization)["'=:\s]+)[^\s"']+/gi, "$1<redacted>")
    .slice(0, limit);
}

function scanLog(text) {
  const patterns = {
    fatal: /FATAL EXCEPTION|AndroidRuntime:\s*FATAL/gi,
    render_error: /UPDATE_VIEW_TREE|KuiklyRenderException|KuiklyUnhandledException/gi,
    crash: /SIGSEGV|SIGABRT|Process .* has died/gi,
    assertion_error: /AssertionError|IllegalStateException/gi,
  };
  return Object.fromEntries(Object.entries(patterns).map(([k, re]) => [k, (text.match(re) || []).length]));
}

async function main() {
  saveRecord();
  const data = await stamp(`/api/runs/${runId}`);
  if (!data.run) throw new Error("run not found");
  const existingReview = (data.reviews || []).find((r) => r.role === "panel" && r.reviewer === reviewer);
  if (!["running", "judging", "in_review"].includes(data.run.status) && !existingReview) {
    throw new Error(`run is not reviewable: ${data.run.status}`);
  }

  const yamlModule = await import(process.env.SLOCK_YAML_MODULE || "yaml");
  const parseYaml = yamlModule.parse || yamlModule.default?.parse;
  const stringifyYaml = yamlModule.stringify || yamlModule.default?.stringify;
  if (typeof parseYaml !== "function" || typeof stringifyYaml !== "function") throw new Error("YAML parser unavailable");
  const platform = String(data.run.app_slug || "").includes("ios") ? "ios" : String(data.run.app_slug || "").includes("ohos") ? "ohos" : "android";
  const cases = [];
  for (const rc of data.cases || []) {
    const current = await stamp(`/api/cases/${encodeURIComponent(rc.case_id)}`);
    if (Number(current.revision) !== Number(rc.case_revision)) {
      throw new Error(`case ${rc.case_id} revision drift: run=${rc.case_revision} current=${current.revision}; frozen-spec API required`);
    }
    const spec = parseYaml(current.spec_yaml || "");
    const assertionIds = Array.isArray(spec?.assertions) ? spec.assertions.map((a) => a?.id).filter((id) => typeof id === "string" && id) : [];
    if (!assertionIds.length) throw new Error(`case ${rc.case_id} has no stable assertion ids`);
    const reviewSpec = { id: spec.id, title: spec.title, goal: spec.goal, tier: spec.tier, assertions: spec.assertions, [platform]: spec[platform] };
    cases.push({
      run_case_id: rc.id,
      case_id: rc.case_id,
      title: rc.title,
      platform,
      frozen_revision: rc.case_revision,
      current_revision: current.revision,
      spec_yaml: current.spec_yaml,
      review_spec_yaml: stringifyYaml(reviewSpec),
      spec_sha256: crypto.createHash("sha256").update(current.spec_yaml || "").digest("hex"),
      assertion_ids: assertionIds,
    });
  }

  const excludedEvidence = (data.evidence || [])
    .filter((e) => !["screenshot", "ui_dump", "dump_layout", "logcat", "system_log"].includes(e.kind))
    .map((e) => ({ id: e.id, kind: e.kind, reason: "not in raw-evidence allowlist" }));
  const rawShots = evenly(
    (data.evidence || []).filter((e) => e.kind === "screenshot").sort((a, b) => Number(a.captured_at || a.created_at || 0) - Number(b.captured_at || b.created_at || 0)),
    12,
  );
  if (!rawShots.length) throw new Error("no raw screenshot evidence; refusing to review annotated/self-reported evidence only");

  const rawDumps = (data.evidence || []).filter((e) => e.kind === "ui_dump" || e.kind === "dump_layout");
  const rawLogs = (data.evidence || []).filter((e) => e.kind === "logcat" || e.kind === "system_log");
  const byteCache = new Map();
  const bytesFor = async (ev) => {
    if (!byteCache.has(ev.id)) byteCache.set(ev.id, await evidenceBytes(ev));
    return byteCache.get(ev.id);
  };
  const logScans = [];
  for (const ev of rawLogs) {
    const bytes = await bytesFor(ev);
    logScans.push({ evidence_id: ev.id, kind: ev.kind, ...scanLog(bytes.toString("utf8")) });
  }

  const reviewedEvidence = [...rawShots, ...rawDumps, ...rawLogs]
    .sort((a, b) => a.id.localeCompare(b.id));
  // Keep evidence acquisition bounded and deterministic. A large run can have
  // dozens of objects; a single Promise.all burst has caused transient R2/fetch
  // failures before the reviewer ever reached the model.
  for (const ev of reviewedEvidence) await bytesFor(ev);
  const evidenceManifest = reviewedEvidence.map((e) => ({ id: e.id, kind: e.kind, sha256: e.sha256 }));
  const evidenceManifestSha256 = crypto.createHash("sha256").update(JSON.stringify(evidenceManifest)).digest("hex");
  const reviewInput = {
    run_id: runId,
    app_slug: data.run.app_slug,
    source_commit: data.run.source_commit || null,
    apk_sha256: data.run.apk_sha256 || null,
    cases: cases.map((c) => ({ case_id: c.case_id, revision: c.frozen_revision, spec_sha256: c.spec_sha256, assertion_ids: c.assertion_ids })),
    evidence_manifest_sha256: evidenceManifestSha256,
  };
  const reviewInputSha256 = crypto.createHash("sha256").update(JSON.stringify(reviewInput)).digest("hex");
  saveRecord({
    stage: "input_verified",
    evidence_manifest_sha256: evidenceManifestSha256,
    review_input_sha256: reviewInputSha256,
    review_input: reviewInput,
    evidence_manifest: evidenceManifest,
    included_evidence: reviewedEvidence.map((e) => ({ id: e.id, kind: e.kind })),
    excluded_evidence: excludedEvidence,
    raw_ui_dump_available: rawDumps.length > 0,
  });
  if (existingReview) {
    const existingDetail = typeof existingReview.detail_json === "string" ? JSON.parse(existingReview.detail_json) : existingReview.detail_json;
    if (!plainObject(existingDetail) || existingDetail.review_input_sha256 !== reviewInputSha256) {
      throw new Error(`existing vote ${existingReview.id} input hash does not match current frozen review input`);
    }
    const reconstructedVote = {
      verdict: existingReview.verdict,
      reason: existingReview.comment || "existing panel vote",
      detail_json: existingDetail.assertions || {},
      anomalies: existingDetail.anomalies || [],
    };
    validateVote(reconstructedVote, cases);
    saveRecord({
      stage: "already_submitted",
      source: "remote_existing_vote",
      parsed_vote: reconstructedVote,
      existing_review: {
        id: existingReview.id,
        reviewer: existingReview.reviewer,
        verdict: existingReview.verdict,
        created_at: existingReview.created_at,
        review_input_sha256: existingDetail.review_input_sha256,
      },
    });
    console.log(JSON.stringify(record, null, 2));
    return;
  }

  const prompt = [
    "You are an INDEPENDENT, REFUTE-BIASED QA reviewer.",
    "Judge only the original evidence attached below against the frozen acceptance requirements.",
    "The executor's verdict, explanation, annotations, action transcript, and model session are intentionally hidden.",
    "Evidence insufficiency is a failure. A pass requires visible support for EVERY assertion and no contradictory/error evidence.",
    "Do not infer success from missing frames. Do not reward effort. Look for wrong server/account, stale screens, error UI, crashes, and broken layout.",
    "You are not the executor and have no shared context with it.",
    "",
    `Run: ${runId}`,
    `App/platform: ${data.run.app_slug}`,
    `Source: ${data.run.source_commit || "unknown"}`,
    `APK sha256: ${data.run.apk_sha256 || "unknown"}`,
    "",
    "Frozen case requirements (YAML; review only the platform variant named above):",
    ...cases.flatMap((c) => [
      `--- CASE ${c.case_id} (${c.title || ""}), platform=${c.platform}, frozen revision=${c.frozen_revision} ---`,
      c.review_spec_yaml || "(spec unavailable)",
    ]),
    "",
    `Mechanical error scan (counts only; inspect screenshots too): ${JSON.stringify(logScans)}`,
    "",
    `Evidence manifest SHA-256: ${evidenceManifestSha256}`,
    `Complete review input SHA-256: ${reviewInputSha256}`,
    "Return STRICT JSON only with this shape:",
    '{"verdict":"passed|failed","reason":"one concrete overall reason","detail_json":{"case-id":{"stable-assertion-id":{"ok":true|false,"note":"concrete evidence or insufficiency"}}},"anomalies":["..."]}',
  ].join("\n");

  const content = [{ type: "text", text: prompt }];
  for (const ev of rawShots) {
    const bytes = await bytesFor(ev);
    const rc = cases.find((c) => c.run_case_id === ev.run_case_id);
    content.push({ type: "text", text: `RAW SCREENSHOT evidence=${ev.id} case=${rc?.case_id || ev.run_case_id} seq=${ev.seq} captured_at=${ev.captured_at || "unknown"}` });
    content.push({ type: "image", data: bytes.toString("base64"), mimeType: "image/png" });
  }
  for (const ev of rawDumps) {
    const bytes = await bytesFor(ev);
    content.push({ type: "text", text: `RAW UI DUMP evidence=${ev.id}\n${sanitizedEvidenceText(bytes)}` });
  }
  for (const ev of rawLogs) {
    const bytes = await bytesFor(ev);
    const scan = logScans.find((x) => x.evidence_id === ev.id);
    content.push({ type: "text", text: `RAW LOG evidence=${ev.id} scan=${JSON.stringify(scan)} (credential-scrubbed, capped)\n${sanitizedEvidenceText(bytes)}` });
  }

  const providers = await import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all");
  const models = providers.builtinModels();
  const model = resolvePiModel(models, providerName, modelName);
  let response;
  let text = "";
  let vote;
  const modelAttempts = [];
  let lastError;
  for (let attempt = 1; attempt <= 2; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), modelTimeoutMs);
    let attemptText = "";
    let attemptResponse;
    try {
      const attemptContent = attempt === 1 ? content : [
        { type: "text", text: "FORMAT CORRECTION: return exactly one bare JSON object. No prose, no Markdown fence. anomalies must be a top-level array of strings. Include every required assertion." },
        ...content,
      ];
      attemptResponse = await models.complete(model,
        { messages: [{ role: "user", content: attemptContent, timestamp: Date.now() }] },
        { apiKey: piKey, maxTokens: 8000, reasoningEffort: "low", signal: controller.signal, timeoutMs: modelTimeoutMs });
      if (attemptResponse.stopReason === "error" || attemptResponse.stopReason === "aborted") {
        throw new Error(attemptResponse.errorMessage || attemptResponse.stopReason);
      }
      attemptText = attemptResponse.content.filter((b) => b.type === "text").map((b) => b.text).join("\n");
      const parsed = parseJson(attemptText);
      validateVote(parsed, cases);
      response = attemptResponse;
      text = attemptText;
      vote = parsed;
      modelAttempts.push({ attempt, raw_model_output: attemptText, parsed_vote: parsed, validation: { ok: true }, usage: attemptResponse.usage || null });
      saveRecord({ stage: "model_validated", model_attempts: modelAttempts });
      break;
    } catch (error) {
      lastError = error;
      modelAttempts.push({ attempt, raw_model_output: attemptText || null, error: error?.stack || String(error), usage: attemptResponse?.usage || null });
      saveRecord({ stage: "model_invalid", model_attempts: modelAttempts });
    } finally {
      clearTimeout(timer);
    }
  }
  if (!vote) throw lastError || new Error("reviewer exhausted strict JSON attempts");
  saveRecord({ stage: "model_returned", raw_model_output: text, usage: response.usage || null });
  saveRecord({ stage: "parsed", parsed_vote: vote });
  saveRecord({ stage: "validated", validation: { ok: true } });

  const detailJson = {
    assertions: vote.detail_json || {},
    anomalies: vote.anomalies || [],
    evidence_manifest_sha256: evidenceManifestSha256,
    review_input_sha256: reviewInputSha256,
    evidence_manifest: evidenceManifest,
    raw_evidence_ids: rawShots.map((e) => e.id),
    log_scans: logScans,
    provider: providerName,
    model: modelName,
    usage: response.usage || null,
  };
  const posted = await stamp(`/api/runs/${runId}/panel-review`, {
    method: "POST",
    body: JSON.stringify({
      reviewer,
      verdict: vote.verdict,
      reason: String(vote.reason || "").slice(0, 1000),
      detail_json: detailJson,
    }),
  });
  saveRecord({ stage: "submitted", post_receipt: posted });
  console.log(JSON.stringify(record, null, 2));
}

main().catch((error) => {
  const message = error?.stack || String(error);
  try {
    saveRecord({ stage: "error", error: message });
  } catch {}
  console.error(message);
  process.exit(1);
});
