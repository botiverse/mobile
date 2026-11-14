// Shared, side-effect-free rules for the device scenarios' results (tested in
// scripts/qa/scenario_outcome.test.mjs).

/**
 * A write's answer: "ok" (2xx), "refused" (a definite 4xx other than 408/429: nothing was written),
 * or "unknown" (5xx, 408, 429, no status, a thrown/timed-out request: it may or may not have been
 * written). An unknown write is never retried or sent elsewhere; the scenario stops and reports it.
 */
export function classifyWrite(outOrError) {
  if (outOrError instanceof Error || outOrError == null) return "unknown";
  const status = Number(outOrError.status);
  if (!Number.isFinite(status) || status === 0) return "unknown";
  if (status >= 200 && status < 300) return "ok";
  if (status === 408 || status === 429 || status >= 500) return "unknown";
  return "refused";
}

/**
 * The scenario's overall result. "pass" only when every requested step ran and passed, no write was
 * left unknown and cleanup completed. Otherwise, in this order:
 *   unknown – a write (create / delete / push) had no clear answer;
 *   fail    – a step failed, or cleanup was refused;
 *   partial – a step was not covered or only partly confirmed (e.g. not_covered,
 *             pass_unconfirmed_content, pass_without_session_evidence).
 * `reasons` lists every step / write / cleanup that kept it from "pass".
 */
export function overallResult({ requested = [], results = {}, unknownWrites = [], cleanup = [] } = {}) {
  const reasons = [];
  for (const w of unknownWrites) reasons.push(`unknown write: ${w}`);
  let failed = false;
  let partial = false;
  for (const id of requested) {
    const r = results[id]?.result ?? "not_run";
    if (r === "pass") continue;
    if (r === "fail") failed = true;
    else partial = true;
    reasons.push(`${id}: ${r}`);
  }
  let cleanupUnknown = false;
  for (const c of cleanup) {
    if (c.outcome === "ok") continue;
    if (c.outcome === "unknown") cleanupUnknown = true;
    else failed = true;
    reasons.push(`cleanup ${c.what}: ${c.outcome}${c.status !== undefined ? ` (HTTP ${c.status})` : ""}`);
  }
  const result = unknownWrites.length || cleanupUnknown ? "unknown" : failed ? "fail" : partial ? "partial" : "pass";
  return { result, reasons };
}
