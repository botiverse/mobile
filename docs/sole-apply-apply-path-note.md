# Sole-apply apply-path + fallback canary — design note

> Origin (artin 2026-07-12): the sole-apply rollout does NOT need HAR capture or a passive shadow-collection
> pass — the server already has the producer wire, and #2/#3 edge states are dead code. The real-traffic
> validation is the **canary itself**: flip sync-core to authoritative on ONE server, new pipeline live but
> with **fallback to legacy on any divergence** + a rollback metric. The fallback rate IS the canary signal.
> Decision: "pure canary metrics" (no bounded parity log). 3-party converged: HanXin (sync-line) / 赵
> (contract face) / AD2 (platform).

Status: design note (docs-first, pre-head). Owner: HanXin. Contract-face + platform gatekeepers: 赵 + AD2.
Builds on: #709 (T-wrapper V1 persistence, §9.9 one-txn + crash-window; canonical fact codec; scope
rebaseline via `clearScope` + rebuild / `ingestSnapshot`) and the shadow-compare (`compareCanonicalShadow`).

## 1. Wiring point

The sole-apply selection lives at **exactly** the mapper boundary AD2 already isolated for shadow:
`RealtimeEventMapper.withShadowObserved(eventName, element): Message`. Today it is shadow-only — it runs
`messageShadowObserver.observe(...)` fail-open and returns the legacy `Message` (`this`) unchanged. Sole-apply
extends this one hook to **select** canonical-vs-legacy; nothing else in the mapper changes.

It reuses two things already present:
- the canonical pipeline `decodeCanonicalMessageWire(element) → CanonicalMessageRow → toAppMessage` (the same
  path `compareCanonicalShadow` runs), and
- that comparison's outcome `Parity / Divergent / DecodeFailed / EnvelopeRoundTripDivergent /
  EnvelopeDecodeFailed`.

## 2. Canary semantics (conservative)

Flag stages: `sync_core_messages_v0` is extended (or a `sole_apply` sub-flag added) so a scope can be `off`
→ `shadow` → `sole-apply`, targetable to one server.

When `sole-apply` is on, per realtime message:
- outcome **Parity** → the canonical `Message` is authoritative (returned to the store).
- outcome **Divergent / DecodeFailed / Envelope\* / any error** → **fallback**: the legacy `Message` reaches
  the UI (safe), a **rollback metric** is incremented, and core is reconciled (§3).

So during the canary the user always sees **legacy-or-equal** — zero user-visible risk — and the **fallback
rate is the canary signal**. Fail-open is preserved: any exception on the sole-apply path falls back to
legacy (AD2's mapper-boundary isolation invariant is not weakened).

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

## 6. Acceptance battery (RED-first)

- **Divergent fallback keeps core clean**: inject a deliberately-divergent event → fallback → assert the
  next normal event is NOT divergent (core did not drift), and core state deep-equals server canonical.
- **DecodeFailed fallback rebaselines**: inject a decode-failing wire → fallback → assert the scope
  rebaselines and core state+cursor/epoch deep-equals server canonical afterward (no cursor-over-missing-fact).
- **Crash atomicity**: crash before/after the fallback commit leaves the complete old-or-new frame (§9.9
  crash-window form).
- **Circuit breaker**: a burst of same-scope fallbacks trips the breaker (bounded rebaselines, no storm).
- **Rate gate**: the go-condition reads the real fallback-rate metric, not a constant.
