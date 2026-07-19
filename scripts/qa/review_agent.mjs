#!/usr/bin/env node

// Independent Stamp evidence reviewer. It deliberately excludes the executor's
// verdict, reason, annotated screenshots, session transcript, and action log.
// Each invocation has a fresh model context and posts exactly one panel vote.

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
const piKey = process.env.MINIMAX_API_KEY || process.env.PI_API_KEY || "";

if (!runId || !reviewer) throw new Error("--run-id and --reviewer are required");
if (!stampToken) throw new Error("STAMP_TOKEN is required");
if (!piKey) throw new Error("PI_API_KEY/MINIMAX_API_KEY is required");

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
  const res = await fetch(`${stampUrl}/api/runs/${runId}/evidence/${ev.id}`, {
    headers: { authorization: `Bearer ${stampToken}` },
  });
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
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(text)?.[1];
  const candidate = fenced || /\{[\s\S]*\}/.exec(text)?.[0] || text;
  return JSON.parse(candidate);
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
  const data = await stamp(`/api/runs/${runId}`);
  if (!data.run) throw new Error("run not found");
  if (!["running", "judging", "in_review"].includes(data.run.status)) {
    throw new Error(`run is not reviewable: ${data.run.status}`);
  }
  if ((data.reviews || []).some((r) => r.role === "panel" && r.reviewer === reviewer)) {
    console.log(JSON.stringify({ skipped: true, reason: "reviewer already voted", reviewer }));
    return;
  }

  const cases = [];
  for (const rc of data.cases || []) {
    const current = await stamp(`/api/cases/${encodeURIComponent(rc.case_id)}`);
    if (Number(current.revision) !== Number(rc.case_revision)) {
      throw new Error(`case ${rc.case_id} revision drift: run=${rc.case_revision} current=${current.revision}; frozen-spec API required`);
    }
    cases.push({
      run_case_id: rc.id,
      case_id: rc.case_id,
      title: rc.title,
      platform: String(data.run.app_slug || "").includes("ios") ? "ios" : String(data.run.app_slug || "").includes("ohos") ? "ohos" : "android",
      frozen_revision: rc.case_revision,
      current_revision: current.revision,
      spec_yaml: current.spec_yaml,
    });
  }

  const rawShots = evenly(
    (data.evidence || []).filter((e) => e.kind === "screenshot").sort((a, b) => (a.captured_at || a.created_at) - (b.captured_at || b.created_at)),
    12,
  );
  if (!rawShots.length) throw new Error("no raw screenshot evidence; refusing to review annotated/self-reported evidence only");

  const logScans = [];
  for (const ev of (data.evidence || []).filter((e) => e.kind === "logcat" || e.kind === "system_log")) {
    const bytes = await evidenceBytes(ev);
    logScans.push({ evidence_id: ev.id, kind: ev.kind, ...scanLog(bytes.toString("utf8")) });
  }

  const reviewedEvidence = [...rawShots, ...(data.evidence || []).filter((e) => e.kind === "logcat" || e.kind === "system_log")]
    .sort((a, b) => a.id.localeCompare(b.id));
  const evidenceManifest = reviewedEvidence.map((e) => ({ id: e.id, kind: e.kind, sha256: e.sha256 }));
  const evidenceManifestSha256 = crypto.createHash("sha256").update(JSON.stringify(evidenceManifest)).digest("hex");

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
      c.spec_yaml || "(spec unavailable)",
    ]),
    "",
    `Mechanical error scan (counts only; inspect screenshots too): ${JSON.stringify(logScans)}`,
    "",
    `Evidence manifest SHA-256: ${evidenceManifestSha256}`,
    "Return STRICT JSON only with this shape:",
    '{"verdict":"passed|failed","reason":"one concrete overall reason","detail_json":{"case-id":{"stable-assertion-id":{"ok":true|false,"note":"concrete evidence or insufficiency"}}},"anomalies":["..."]}',
  ].join("\n");

  const content = [{ type: "text", text: prompt }];
  for (const ev of rawShots) {
    const bytes = await evidenceBytes(ev);
    const rc = cases.find((c) => c.run_case_id === ev.run_case_id);
    content.push({ type: "text", text: `RAW SCREENSHOT evidence=${ev.id} case=${rc?.case_id || ev.run_case_id} seq=${ev.seq} captured_at=${ev.captured_at || "unknown"}` });
    content.push({ type: "image", data: bytes.toString("base64"), mimeType: "image/png" });
  }

  const providers = await import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all");
  const models = providers.builtinModels();
  const model = models.getModel(String(process.env.PI_PROVIDER || "minimax"), String(process.env.PI_MODEL || "MiniMax-M3"));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 180000);
  let response;
  try {
    response = await models.complete(model,
      { messages: [{ role: "user", content, timestamp: Date.now() }] },
      { apiKey: piKey, maxTokens: 2500, signal: controller.signal, timeoutMs: 180000 });
  } finally {
    clearTimeout(timer);
  }
  if (response.stopReason === "error" || response.stopReason === "aborted") {
    throw new Error(response.errorMessage || response.stopReason);
  }
  const text = response.content.filter((b) => b.type === "text").map((b) => b.text).join("\n");
  const vote = parseJson(text);
  if (!['passed', 'failed'].includes(vote.verdict)) throw new Error(`invalid reviewer verdict: ${vote.verdict}`);

  const detailJson = {
    assertions: vote.detail_json || {},
    anomalies: vote.anomalies || [],
    evidence_manifest_sha256: evidenceManifestSha256,
    evidence_manifest: evidenceManifest,
    raw_evidence_ids: rawShots.map((e) => e.id),
    log_scans: logScans,
    model: String(process.env.PI_MODEL || "MiniMax-M3"),
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
  const output = {
    run_id: runId,
    reviewer,
    evidence_manifest_sha256: evidenceManifestSha256,
    evidence_manifest: evidenceManifest,
    raw_model_output: text,
    parsed_vote: vote,
    post_receipt: posted,
    usage: response.usage || null,
  };
  fs.writeFileSync(outPath, JSON.stringify(output, null, 2));
  console.log(JSON.stringify(output, null, 2));
}

main().catch((error) => {
  const message = error?.stack || String(error);
  try {
    fs.writeFileSync(outPath, JSON.stringify({ run_id: runId, reviewer, error: message }, null, 2));
  } catch {}
  console.error(message);
  process.exit(1);
});
