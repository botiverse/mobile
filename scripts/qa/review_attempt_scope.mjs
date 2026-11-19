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
//   - Attempt-bound votes go to POST /api/runs/:id/attempt-panel-review
//     (botiverse/testbed#327 @ 4292b69b) with
//     top-level expected_attempt_ids, in the report's order. Its receipt echoes
//     the attempt_ids the vote was bound to. Legacy votes keep the old
//     POST /api/runs/:id/panel-review URL and carry no attempt ids.
//     During a mixed deployment an older Worker ignores expected_attempt_ids on
//     the old URL and still answers ok. So an attempt-bound vote never uses the
//     old URL, and it counts only when the receipt names exactly its attempts.
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

// Where a vote is POSTed. The attempt-bound route exists only on Workers that
// enforce expected_attempt_ids; the old route stays for reports without
// review_context.
export function panelReviewPath(scope, runId) {
  return scope.mode === "attempt_bound"
    ? `/api/runs/${runId}/attempt-panel-review`
    : `/api/runs/${runId}/panel-review`;
}

// The attempt ids a receipt says the vote was bound to. Testbed
// botiverse/testbed#327 @ 4292b69b (worker/src/panel-review.ts panelReview)
// answers { ok, review_id, attempt_ids, duplicate, votes, finalized, ... } with
// attempt_ids at the top level. Undefined when the receipt does not have one.
export function receiptAttemptIds(receipt) {
  return receipt && typeof receipt === "object" && Object.hasOwn(receipt, "attempt_ids") ? receipt.attempt_ids : undefined;
}

const sameList = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => x === b[i]);

// Posts one panel vote and reports whether it counts.
//   post(path, body) -> { status, body } where body is the parsed JSON (or {}).
// Result:
//   { stage: "submitted", path, receipt }: legacy vote accepted, or an
//     attempt-bound vote whose receipt names exactly scope.attemptIds (same
//     order) and a review_id (docs/final-review-context.md "Panel opinions").
//   { stage: "unconfirmed", path, status, reason, receipt }: an attempt-bound
//     vote that is not confirmed (404 = Worker without the route, or receipt
//     without / with different attempt_ids, or without review_id). It must not be counted, and the
//     old URL must not be tried.
// Any other non-2xx status throws, as before.
export async function submitPanelVote({ scope, runId, vote, post }) {
  const path = panelReviewPath(scope, runId);
  const { status, body } = await post(path, panelReviewBody(scope, vote));
  const bound = scope.mode === "attempt_bound";
  if (bound && status === 404) {
    return { stage: "unconfirmed", path, status, receipt: body ?? null,
      reason: `${path} returned 404: this Testbed Worker cannot bind a vote to attempts; vote not counted (old panel-review URL deliberately not used)` };
  }
  if (status < 200 || status >= 300) {
    throw new Error(`Stamp ${status} ${path}: ${JSON.stringify(body ?? null).slice(0, 500)}`);
  }
  if (!bound) return { stage: "submitted", path, receipt: body };
  const echoed = receiptAttemptIds(body);
  if (echoed === undefined) {
    return { stage: "unconfirmed", path, status, receipt: body,
      reason: `${path} receipt has no attempt_ids; expected ${JSON.stringify(scope.attemptIds)}; vote not counted` };
  }
  if (!sameList(echoed, scope.attemptIds)) {
    return { stage: "unconfirmed", path, status, receipt: body,
      reason: `${path} receipt attempt_ids ${JSON.stringify(echoed)} differ from expected ${JSON.stringify(scope.attemptIds)}; vote not counted` };
  }
  if (typeof body?.review_id !== "string" || !body.review_id) {
    return { stage: "unconfirmed", path, status, receipt: body,
      reason: `${path} receipt has no review_id; vote not counted` };
  }
  return { stage: "submitted", path, receipt: body };
}
