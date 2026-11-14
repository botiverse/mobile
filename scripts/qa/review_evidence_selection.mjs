// Pure screenshot selection for the independent panel reviewer (review_agent.mjs).
//
// The executor references one frame per check (verdict annotations.assertion:
// assertion_id + step). The reviewer never sees the executor's verdict, labels
// or annotated copies, but it MUST see the raw frame each check was asserted
// on: run 134da0bf (CI 43569038982) asserted both checks on step 19, and the
// panel judged on steps 15/17/18/20 without it. Assertion frames are therefore
// always included (outside the image cap) and labelled; a referenced frame with
// no raw screenshot makes the review input incomplete.

import { normalizeAssertionAnnotation } from "./assertion_annotations.mjs";

export function evenly(items, limit) {
  if (limit <= 0) return [];
  if (items.length <= limit) return items;
  if (limit === 1) return [items[items.length - 1]];
  const out = [];
  for (let i = 0; i < limit; i++) out.push(items[Math.round((i * (items.length - 1)) / (limit - 1))]);
  return [...new Map(out.map((x) => [x.id, x])).values()];
}

function parseMaybeJson(value) {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

// Only frame references (assertion id + step) leave the executor verdict; the
// verdict, reason, labels, bounds and pass/fail results stay hidden.
export function assertionFrameRefs(verdictJson) {
  const verdict = parseMaybeJson(verdictJson);
  const raw = verdict?.annotations?.assertion;
  if (!Array.isArray(raw)) return [];
  const refs = new Map();
  for (const entry of raw) {
    if (!entry || typeof entry !== "object") continue;
    const a = normalizeAssertionAnnotation(entry);
    const assertionId = String(a.assertion_id || "").trim();
    const step = Number(a.step);
    const key = `${assertionId}@${a.step}`;
    if (!refs.has(key)) refs.set(key, { assertion_id: assertionId, step: Number.isInteger(step) && step >= 0 ? step : null });
  }
  return [...refs.values()];
}

const capturedOrder = (a, b) =>
  Number(a.captured_at || a.created_at || 0) - Number(b.captured_at || b.created_at || 0) || Number(a.seq || 0) - Number(b.seq || 0);

// runCases: [{ run_case_id, case_id, verdict_json }]; evidence: Stamp run evidence rows.
export function selectReviewScreenshots(runCases, evidence, limit = 12) {
  const raw = (evidence || []).filter((e) => e.kind === "screenshot").sort(capturedOrder);
  const assertionFrames = [];
  for (const rc of runCases) {
    for (const ref of assertionFrameRefs(rc.verdict_json)) {
      const ev = ref.step === null ? null : raw.find((e) => e.run_case_id === rc.run_case_id && Number(e.step_index) === ref.step);
      assertionFrames.push({ case_id: rc.case_id, run_case_id: rc.run_case_id, assertion_id: ref.assertion_id, step: ref.step, evidence_id: ev?.id || null });
    }
  }
  const required = new Set(assertionFrames.map((f) => f.evidence_id).filter(Boolean));
  const requiredShots = raw.filter((e) => required.has(e.id));
  const rest = evenly(raw.filter((e) => !required.has(e.id)), limit - requiredShots.length);
  const shots = [...requiredShots, ...rest].sort(capturedOrder);
  const checksByEvidence = new Map();
  for (const f of assertionFrames) {
    if (!f.evidence_id) continue;
    checksByEvidence.set(f.evidence_id, [...(checksByEvidence.get(f.evidence_id) || []), f]);
  }
  return {
    shots,
    assertionFrames,
    missingAssertionFrames: assertionFrames.filter((f) => !f.evidence_id),
    checksByEvidence,
  };
}
