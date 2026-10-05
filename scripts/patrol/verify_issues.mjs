#!/usr/bin/env node
// patrol/verify_issues — adversarial triage of auto-filed visual-drift issues.
//
// The patrol detects drift from the nightly AI analysis, which can over-report. This
// stage re-judges each OPEN, not-yet-verified drift issue with an INDEPENDENT 3x M3
// adversarial panel: "is this a real cross-platform problem worth fixing, or a false
// positive / acceptable / known-intentional difference?" Consensus decides:
//   - majority (>= threshold) say REAL   -> label `verified` (it enters the fix queue)
//   - otherwise                          -> close the issue as an auto-triaged false positive
//
// Needs PI_API_KEY/MINIMAX_API_KEY + @earendil-works/pi-ai (SLOCK_PI_AI_MODULE). Dry-run
// is the DEFAULT — without --live it prints the verdicts and changes nothing.
//
// Usage:
//   node scripts/patrol/verify_issues.mjs [--repo botiverse/mobile] [--comparison react__android]
//     [--voters 3] [--threshold 2] [--limit 60] [--live]

import { resolvePiModel } from "../qa/pi_model.mjs";
import { execFileSync } from "node:child_process";

const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const has = (f) => argv.includes(f);

const opts = {
  repo: arg("--repo", "botiverse/mobile"),
  comparison: arg("--comparison", "react__android"),
  voters: Number(arg("--voters", "3")),
  threshold: Number(arg("--threshold", "2")),
  // Fetch cap for the open-drift list. This is the FETCH size (newest-first), and
  // already-`verified` issues are filtered out after fetching, so with a backlog the
  // effective batch is (fetched - already-verified). 20 was too small once the queue
  // grew past ~20 open (older unverified issues never got a turn); 60 covers a full
  // day's backlog in one run. Daily runs skip the already-verified, so cost stays low.
  limit: Number(arg("--limit", "60")),
  issue: arg("--issue", ""),   // verify a single issue (event path); empty = batch all open unverified
  live: has("--live"),
};

const piKey = () => process.env.MINIMAX_API_KEY || process.env.PI_API_KEY || "";
function loadModels() {
  return import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all").then((p) => p.builtinModels());
}

function gh(args, opts2 = {}) {
  return execFileSync("gh", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...opts2 });
}

function analysisFromGhPages() {
  const out = execFileSync("git", ["-c", "submodule.recurse=false", "show",
    `origin/gh-pages:visual-testing/latest/analysis/${opts.comparison}.json`],
    { encoding: "utf8", maxBuffer: 128 * 1024 * 1024 });
  return JSON.parse(out).cases || [];
}

function toIssue(i) {
  return { number: i.number, title: i.title, body: i.body,
    labels: i.labels, caseId: (/\[visual-drift\]\s+([^:]+):/.exec(i.title) || [])[1]?.trim() };
}

function openDriftIssues() {
  // Single-issue (event) path: verify exactly that issue if it is an unverified visual-drift.
  if (opts.issue) {
    const out = gh(["issue", "view", opts.issue, "--repo", opts.repo, "--json", "number,title,labels,body,state"]);
    const i = JSON.parse(out);
    const isDrift = (i.labels || []).some((l) => l.name === "visual-drift");
    const verified = (i.labels || []).some((l) => l.name === "verified");
    if (i.state !== "OPEN" || !isDrift || verified) return [];
    return [toIssue(i)];
  }
  const out = gh(["issue", "list", "--repo", opts.repo, "--label", "visual-drift",
    "--state", "open", "--limit", String(opts.limit), "--json", "number,title,labels,body"]);
  return JSON.parse(out).filter((i) => !i.labels.some((l) => l.name === "verified")).map(toIssue);
}

function verifyPrompt(drift) {
  return [
    "You are an INDEPENDENT adversarial reviewer. Judge whether a reported cross-platform",
    "visual DRIFT is a REAL problem worth fixing, or NOT (false positive / capture-or-crop",
    "artifact / fixture-data difference / intentional platform difference / trivially cosmetic).",
    "Be skeptical and specific. Default to real=false unless the evidence shows a genuine",
    "parity defect a user would notice and that should be fixed.",
    "",
    `Case: ${drift.caseId || "unknown"}   Comparison: ${opts.comparison}`,
    "Reported drift (from nightly analysis):",
    ...(drift.summary || []).map((s) => `- ${s}`),
    "Likely causes:",
    ...(drift.likelyCauses || []).map((s) => `- ${s}`),
    `Analysis severity: ${drift.severity}   metricMismatch: ${drift.metricMismatch ?? "n/a"}`,
    "",
    'Respond STRICT JSON only: {"real": <bool>, "reason": "<one concrete sentence>", "confidence": <0..1>}',
  ].join("\n");
}

async function runVoter(models, model, drift) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120000);
  try {
    const msg = await models.complete(model,
      { messages: [{ role: "user", content: [{ type: "text", text: verifyPrompt(drift) }], timestamp: Date.now() }] },
      { apiKey: piKey(), maxTokens: 800, signal: controller.signal, timeoutMs: 120000 });
    if (msg.stopReason === "error" || msg.stopReason === "aborted") return { error: msg.errorMessage || msg.stopReason };
    const text = msg.content.filter((b) => b.type === "text").map((b) => b.text).join("\n");
    const m = /\{[\s\S]*\}/.exec(text);
    const p = JSON.parse(m ? m[0] : text);
    return { real: !!p.real, reason: p.reason, confidence: p.confidence };
  } catch (e) {
    return { error: String(e?.message || e).slice(0, 200) };
  } finally { clearTimeout(timer); }
}

async function main() {
  if (!piKey()) { console.log(JSON.stringify({ error: "no PI_API_KEY/MINIMAX_API_KEY" })); process.exit(2); }
  const cases = analysisFromGhPages();
  const byId = new Map(cases.map((c) => [c.id, c]));
  const issues = openDriftIssues();
  const models = await loadModels();
  const model = resolvePiModel(models, String(process.env.PI_PROVIDER || "minimax"), String(process.env.PI_MODEL || "MiniMax-M3.1-Flash-Preview"));

  const results = [];
  for (const iss of issues) {
    const drift = { ...(byId.get(iss.caseId) || {}), caseId: iss.caseId };
    const votes = await Promise.all(Array.from({ length: opts.voters }, () => runVoter(models, model, drift)));
    const valid = votes.filter((v) => !v.error);
    const realCount = valid.filter((v) => v.real).length;
    let decision;
    if (valid.length < Math.ceil(opts.voters / 2)) decision = "ESCALATE"; // too many voter errors
    else if (realCount >= opts.threshold) decision = "VERIFIED";
    else decision = "CLOSE";

    if (opts.live) {
      if (decision === "VERIFIED") {
        try { gh(["issue", "edit", String(iss.number), "--repo", opts.repo, "--add-label", "verified"]); } catch {}
      } else if (decision === "CLOSE") {
        const body = `Auto-triaged by the 3× M3 adversarial verify panel: ${realCount}/${valid.length} judged this a real problem (< ${opts.threshold}). Closing as a likely false positive / acceptable difference. Reopen if this is wrong.`;
        try { gh(["issue", "close", String(iss.number), "--repo", opts.repo, "--comment", body, "--reason", "not planned"]); } catch {}
      }
    }
    results.push({ issue: iss.number, caseId: iss.caseId, realCount, valid: valid.length, decision, votes });
  }

  console.log(JSON.stringify({
    mode: opts.live ? "LIVE" : "DRY_RUN", comparison: opts.comparison,
    totals: { reviewed: results.length,
      verified: results.filter((r) => r.decision === "VERIFIED").length,
      closed: results.filter((r) => r.decision === "CLOSE").length,
      escalated: results.filter((r) => r.decision === "ESCALATE").length },
    results,
    note: opts.live ? "Labels/closes applied." : "DRY RUN — no labels/closes. Re-run with --live to apply.",
  }, null, 2));
}

main().catch((e) => { console.log(JSON.stringify({ error: String(e?.message || e) })); process.exit(1); });
