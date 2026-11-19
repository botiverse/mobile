// Selects the CURRENT attempt out of one Testbed run report for the panel
// reviewer (review_agent.mjs). A run report keeps the whole retry history:
// superseded run-case rows, their evidence and the panel votes cast on them.
//
// Testbed contract (botiverse/testbed #325 docs/final-review-context.md and
// worker/src/attempt-history.ts latestReviewAttempts; vote binding agreed with
// Argus on raft-mobile-reconcile #214):
//   - report.review_context.attempt_ids: sorted current run-case ids, one per
//     case and coordinate, taken from the same report.cases rows. null when the
//     history is empty, forked or broken.
//   - report.reviews[].attempt_ids: the attempt ids a vote was bound to.
//   - POST /api/runs/:id/panel-review takes top-level expected_attempt_ids, in
//     the report's order.
//
// Modes:
//   - "attempt_bound": review_context.attempt_ids is a non-empty array. Only
//     those cases, their evidence and votes bound to exactly that set are used.
//   - "legacy": the report has no review_context at all (an older Testbed).
//     Everything is used as before, and the caller logs it.
//   - review_context present but attempt_ids null/empty/unusable: the current
//     execution record cannot be identified; refuse to review (throws).

export class AttemptScopeError extends Error {}

const sameSet = (a, b) => a.length === b.length && [...a].sort().join("\n") === [...b].sort().join("\n");

export function scopeReviewReport(report) {
  const cases = report?.cases || [];
  const evidence = report?.evidence || [];
  const reviews = report?.reviews || [];
  if (!report || !Object.hasOwn(report, "review_context")) {
    return {
      mode: "legacy",
      attemptIds: null,
      cases,
      evidence,
      reviews,
      notice: "run report has no review_context (older Testbed): reviewing the full case/evidence/vote history without attempt binding",
    };
  }
  const ids = report.review_context?.attempt_ids;
  if (!Array.isArray(ids) || !ids.length) {
    throw new AttemptScopeError(
      `run report review_context.attempt_ids is ${ids === undefined ? "missing" : JSON.stringify(ids)}: ` +
      "the current attempts cannot be uniquely determined (empty, forked or broken retry history); refusing to vote",
    );
  }
  if (ids.some((id) => typeof id !== "string" || !id) || new Set(ids).size !== ids.length) {
    throw new AttemptScopeError(`run report review_context.attempt_ids is malformed: ${JSON.stringify(ids)}; refusing to vote`);
  }
  const current = new Set(ids);
  const scopedCases = cases.filter((c) => current.has(c.id));
  const missing = ids.filter((id) => !scopedCases.some((c) => c.id === id));
  if (missing.length) {
    throw new AttemptScopeError(`review_context.attempt_ids not found in report cases: ${missing.join(", ")}; refusing to vote`);
  }
  const retried = cases.some((c) => !current.has(c.id));
  // A vote counts for this round only when it is bound to exactly this attempt
  // set. A vote without bound ids predates attempt binding: it can only refer
  // to this round when no attempt has been superseded.
  const scopedReviews = reviews.filter((r) =>
    Array.isArray(r.attempt_ids) ? sameSet(r.attempt_ids, ids) : !retried);
  return {
    mode: "attempt_bound",
    attemptIds: [...ids],
    cases: scopedCases,
    evidence: evidence.filter((e) => current.has(e.run_case_id)),
    reviews: scopedReviews,
    superseded: {
      case_ids: cases.filter((c) => !current.has(c.id)).map((c) => c.id),
      evidence_ids: evidence.filter((e) => !current.has(e.run_case_id)).map((e) => e.id),
      review_ids: reviews.filter((r) => !scopedReviews.includes(r)).map((r) => r.id),
    },
  };
}

// The panel-review POST body. Attempt-bound reviews carry the exact attempt set
// they judged so Testbed can reject the vote if a retry changed it meanwhile.
export function panelReviewBody(scope, vote) {
  return {
    ...vote,
    ...(scope.mode === "attempt_bound" ? { expected_attempt_ids: [...scope.attemptIds] } : {}),
  };
}
