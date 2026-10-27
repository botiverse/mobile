# Sole-apply apply-path + fallback canary — design note

> **Superseded (2026-10-27).** Replaced by the message storage design
> (`notes/message-storage-design-2026-10-27.md`, HanXin; artin #wg-mobile 2c500bfc / 0e405b6c): every
> fetched or received message is stored, display is never held for a commit, and the dormant
> MessageSoleApplyEngine / MessageSoleApplyLane / `CANONICAL_FACT_SOLE_APPLY_ELIGIBLE` were deleted in
> Phase 0c. Kept for history only.

> Origin (artin 2026-07-12): the sole-apply rollout does NOT need HAR capture or a passive shadow-collection
> pass — the server already has the producer wire, and #2/#3 edge states are dead code. The real-traffic
> validation is the **canary itself**: flip sync-core to authoritative on ONE server, new pipeline live but
> with **fallback to legacy on any divergence** + a rollback metric. The fallback rate IS the canary signal.
> Decision: "pure canary metrics" (no bounded parity log). 3-party converged: HanXin (sync-line) / 赵
> (contract face) / AD2 (platform).

Status: design note (docs-first, pre-head). Owner: HanXin. Contract-face + platform gatekeepers: 赵 + AD2.
Builds on: #709 (T-wrapper V1 persistence, §9.9 one-txn + crash-window; canonical fact codec; scope
rebaseline via `clearScope` + rebuild / `ingestSnapshot`) and the shadow-compare (`compareCanonicalShadow`).

> Current authority/status: RFC 043 in `botiverse/slock` is normative. Mobile V2 implementation and RED
> evidence are tracked in [canonical-message-v2-implementation-tracker.md](canonical-message-v2-implementation-tracker.md).
> This older note does not authorize production binding, eligibility, flags or rollout.

## 1. Wiring point — the host-ingest apply owner (NOT the mapper hook)

> **Correction (AD2 + MingQi t0).** The first draft wired sole-apply into
> `RealtimeEventMapper.withShadowObserved(...)`. That is the wrong layer. The mapper hook is a **synchronous
> `Message → Message` diagnostic**: no transaction, no snapshot/queue, no breaker lifecycle, and it is
> **fail-open** (any exception returns legacy and moves on). Sole-apply is a **stop-apply** decision with a
> *persisted* side effect, and its fault path must **reconcile**, not fail-open-and-forget — the two
> lifecycles are incompatible. So sole-apply does not extend the mapper; it moves to the ingest owner.

Sole-apply lives in the **single host-ingest apply owner** — the one authoritative ingest/apply lane
(MingQi's **t0**). Its contract:

- **One ingest = core fold + SQLite commit, atomic (#709 §9.9 one-txn), and the result becomes visible to the
  UI / observers ONLY AFTER the commit succeeds.** There is exactly **one** apply path; the mapper and the
  legacy direct-writer must not form a second one (the t0 no-second-path invariant).
- The **mapper stays a pure candidate producer**: it decodes the wire to a canonical candidate
  (`decodeCanonicalMessageWire(element) → CanonicalMessageRow → toAppMessage` — the same path
  `compareCanonicalShadow` already runs) and hands it to the owner. It does **not** decide apply-vs-fallback
  and does **not** persist.
- The owner is a **per-scope serial lane** that holds the queue / single-flight / circuit-breaker lifecycle
  (§3) the mapper hook structurally cannot.

It still reuses the comparison outcome already present — `Parity / Divergent / DecodeFailed /
EnvelopeRoundTripDivergent / EnvelopeDecodeFailed` — but the **branch on that outcome and its persisted
consequence live in the owner, never in the mapper.**

## 2. Canary semantics (conservative)

Flag stages: `sync_core_messages_v0` is extended (or a `sole_apply` sub-flag added) so a scope can be `off`
→ `shadow` → `sole-apply`, targetable to one server.

When `sole-apply` is on, per realtime message:
- outcome **Parity** → the canonical `Message` is authoritative (returned to the store).
- outcome **Divergent / DecodeFailed / Envelope\* / any error** → **fallback**: the legacy `Message` reaches
  the UI (safe), a **rollback metric** is incremented, and core is reconciled (§3).

So during the canary the user always sees **legacy-or-equal** — zero user-visible risk — and the **fallback
rate is the canary signal**. Note the asymmetry: the path is **user-facing fail-safe** (any exception shows
the user legacy, like the mapper's old fail-open) but **core fail-closed** — the core does NOT silently skip
the fact, it reconciles (§3), so it never advances a cursor over a fact it did not absorb. That stop-apply +
reconcile lifecycle is the owner's (§1); it is exactly what the fail-open mapper hook cannot host.

## 3. Fallback reconcile — the load-bearing invariant

> **A fallback is NOT a core no-op, and NOT a bare cursor bump.** (HanXin's first draft said no-op — wrong;
> 赵/AD2 caught it.) If core does not reach a clean baseline on a fallback, it drifts one fact behind and
> every subsequent message reports a **spurious** divergence → the rate never reaches zero → false-red. And
> merely advancing the cursor/epoch NUMBER over a fact core never absorbed is a **cursor-over-missing-fact**:
> the next message, even if surface-parity, sits on a wrong baseline → false-green.

**Reconcile target = SERVER canonical, never legacy.** The legacy `Message` is a lossy projection (it lacks
much of the 19-field canonical row), so it cannot be re-projected back into canonical core state. The truth
core must converge to is the server's canonical fact (the wire, or a server snapshot). Two states:

- **Divergent — decode succeeded, only the projection differs.** Core already decoded the wire's canonical
  row (= server truth). **Atomically persist that decoded canonical row**; UI uses legacy for this message;
  the rollback metric keeps exposing the projection difference. No rebaseline — core is already = server
  canonical.
- **DecodeFailed / Envelope\* / poison — core never absorbed the fact.** Stop further core apply for that
  scope, run a **single-flight snapshot rebaseline** (`clearScope` + rebuild / `ingestSnapshot`) from the
  server-authoritative canonical, then resume. The cursor must NOT cross the missing fact before the
  rebaseline lands.

First version MAY route all fallbacks through rebaseline for simplicity (fallbacks are rare on a healthy
canary); the Divergent→persist-decoded optimization can come later.

**Circuit breaker (AD2).** Rebaseline is scope-level **single-flight**; a burst of fallbacks on one scope
(a bad producer event) must **trip a breaker** (back off / pause that scope's canary + record the metric)
rather than hammer snapshot after snapshot into a storm.

## 4. Invariants (contract face — 赵 + AD2 gatekeep)

For every message, whether applied or fallen back:
1. Core `state + cursor/epoch` is **deep-equal to the SERVER canonical** at that position (not to "legacy
   canonical projection" — that does not exist; legacy is lossy).
2. The reconcile is **atomic** — reuse #709 §9.9: one transaction, commit-before-return, mid-fault full
   rollback (crash leaves the complete old-or-new frame, never a torn one).
3. The **next** event is a clean comparison (core did not fall a fact behind).
4. No out-of-order apply while a scope's rebaseline is in flight.

## 5. Go / no-go gate

**`fallback rate → 0` is a MECHANICAL go-condition, not a dashboard number** (赵). Only when the real-traffic
fallback rate converges to ≈0 and holds is sync-core allowed to become authoritative and legacy retired;
non-zero = not ready → legacy keeps its fallback role → no flip. The metric is the gate.

**Denominator discipline (or the gate lies).** rate = `fallbacks / messages-the-owner-actually-attempted`.
A scope whose breaker is **paused** (§3) is attempting nothing, so its messages must be **excluded from the
denominator** — otherwise a paused (i.e. known-bad) scope silently deflates the rate toward 0 and the gate
reads green on a scope that never applied.

**But excluding paused scopes only moves the false-green from rate to COVERAGE (赵).** With no bound on *how
many* are paused, the gate passes on a slice of healthy scopes while most are parked: 100 scopes, 60 breaker-
tripped, 40 live all Parity → `0/40 = 0` → flip + retire legacy → **the 60 paused scopes now have no fallback
target and no working path.** This is the empty-skip false-green (a CI matrix that "passes" by skipping most
of its cells) moved onto scope coverage. So the go-condition needs **three** clauses, all held over a window:

> **`attempted > 0` AND `fallbacks ≈ 0` AND `paused-fraction ≈ 0`** (equivalently: a bounded, near-zero count
> of paused scopes). Legacy may be retired only when nearly every scope is *live and applying cleanly* — not
> when the few that still run happen to be clean.

**Tag the fallback rate by outcome, not one scalar (赵 refine).** Break it out `Divergent / DecodeFailed /
Envelope*` (aligns with Rainsky's gate outcome-grouping): a non-zero `Divergent` rate is projection-parity
work, a non-zero `DecodeFailed` rate is decode/producer work — a single scalar can't route the fix.

## 6. Acceptance battery (RED-first)

- **Divergent fallback keeps core clean**: inject a deliberately-divergent event → fallback → assert the
  next normal event is NOT divergent (core did not drift), and core state deep-equals server canonical.
- **DecodeFailed fallback rebaselines**: inject a decode-failing wire → fallback → assert the scope
  rebaselines and core state+cursor/epoch deep-equals server canonical afterward (no cursor-over-missing-fact).
- **Crash atomicity**: crash before/after the fallback commit leaves the complete old-or-new frame (§9.9
  crash-window form).
- **Circuit breaker**: a burst of same-scope fallbacks trips the breaker (bounded rebaselines, no storm).
- **Rate gate**: the go-condition reads the real fallback-rate metric, not a constant — AND a paused scope's
  messages are excluded from the denominator (§5): assert a fully-paused canary does NOT read green.
- **Coverage gate (paused-fraction)**: construct a canary where the live scopes are all Parity but a large
  fraction of scopes are breaker-paused; assert the go-condition does NOT read green (`paused-fraction ≈ 0`
  is a required clause, so a "clean on the 40% still running" canary must fail the gate).
- **Independent (non-tautological) oracle**: the "server canonical" the acceptance asserts against must be an
  **independent fixture** — a hand-authored expected row / a second decode implementation — NOT the same
  `decodeCanonicalMessageWire` output the owner just applied. Deep-equal against your own decode proves the
  decode equals itself; it cannot catch a decode bug. The oracle must be able to disagree.
- **Ordering + stale-completion**: while a scope's rebaseline is in flight, (a) a newer event must not apply
  out of order, and (b) a rebaseline that **completes after** a newer event already landed must **not clobber**
  the newer state — inject `rebaseline(e_n)` that finishes after `apply(e_{n+1})` and assert `e_{n+1}` survives
  (the stale-completion form of the decide/apply-once discipline).
