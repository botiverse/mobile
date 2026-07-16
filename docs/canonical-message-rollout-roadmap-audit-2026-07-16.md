# Canonical message rollout roadmap audit — V2 envelope to sole-apply

Status: active implementation/rollout index, 2026-07-16.

This document reconciles the earlier canonical-message notes with the code that is actually on `main` after
PR #1017 (`6457db720fcde48fafbd4789c04f184d1097bd41`). It does not replace the behavioral contracts in
`realtime-consistency-contract-2026-07-14.md`; it records implementation status, later corrections, and the
remaining ordered path to a real sole-apply canary.

The short verdict is:

> The original route is still being followed: **host-ingest fold/persist wiring → normalized V2 envelope →
> committed-row sole-apply/UI drive → one-server canary**. The middle was expanded by necessary authority,
> durable-partition, failure-recovery, and ABA-safe sink work. Those additions did not change the destination;
> they closed defects that would have made the original shortcut unsafe.

No production activation or rollout is authorized by this document.

## 1. Source documents and authority

| Source | Current authority |
| --- | --- |
| `realtime-consistency-contract-2026-07-14.md` | Normative authority/grant/partition/sink contract. Sections 11–20 remain current. |
| `sole-apply-apply-path-note.md` | Normative canary intent, corrected by section 4 of this audit where later actor/failure rulings narrowed “fallback on any error”. |
| `t-wrapper-single-txn-persistence-note-2026-07-11.md` | Historical one-transaction design origin. Its sequencing/status and pre-migration assumptions are superseded by current code and this audit. |
| `network/sync/PERSISTENCE_DESIGN.md` | Historical Rev 3 implementation design. One-transaction/projector/fail-stop principles remain; old storage identity/schema assumptions are superseded by the durable partition migration. |
| This audit | Current implementation ledger and ordered remaining work. |

When prose and executable gates disagree, the executable fail-closed gates win until the prose is corrected.

## 2. Original route and current result

The route agreed on 2026-07-13 was:

1. **t0 host-ingest wiring**: raw canonical carry reaches the single host owner; fold + SQLite commit occurs
   before any future canonical fan-out. While V1 is ineligible, this is shadow-only and legacy remains visible.
2. **V2 normalization**: remove unbounded/receiver-private structures from the shared message fact, version the
   envelope, fail closed on old/ineligible bytes, and pin the new canonical bytes.
3. **Sole-apply/UI drive**: committed canonical rows become the only message-fact source for enabled scopes.
4. **One-server canary**: safe legacy fallback before canonical apply when comparison/decode cannot enter the
   canonical lane; canonical reconcile and bounded breaker semantics preserve core correctness.

That ordering remains correct. The work between steps 1 and 2 grew because a safe single owner required:

- durable `(serverId, principalId, consumerId)` partitioning;
- current-grant authority across the complete database transaction;
- per-grant ABA-safe sink registration and final-write validation;
- a bounded sole-apply actor whose queue, commit failure, rebaseline, revoke, and stale completion are modeled;
- real AppShell/Thread lifecycle tests and an actual-host exact-zero-egress gate.

These are prerequisites of the original route, not a replacement roadmap.

## 3. Implementation ledger on `main`

| Area | State on `main` | Evidence / boundary |
| --- | --- | --- |
| Canonical wire decode, fold, projection, shadow compare | Done | Canonical data DTOs live in `canonical.message`; decode/compare/fold behavior remains in `network.sync`. |
| One-ingest/one-transaction SQLite persistence | Done | `FoldPersistence`, `SqlDelightFoldPersistStore`, crash/continuation gates. |
| Host-ingest shadow fold/persist | Done, flag-gated | `SyncCoreMessageShadowFoldLane`; `sync_core_messages_v0=false` is zero behavior. |
| Durable partition | Done | `DurablePartitionKey(serverId, principalId, consumerId)` and `SyncScopeKey(partition, scopeId, scopeKind)`; migration landed with PR #950. |
| Current-grant `BEGIN → COMMIT → shadow` | Done | `CurrentGrantMessageShadowCommitLane`, PR #964, same authority domain as the grant registry. |
| Canonical-final sink router and lifecycle | Mechanically done, production activation still dormant | PR #1017; ABA-safe directory/router/sidecar, AppShell + Thread mounts, actual-host gate. The normal production provider still passes no `canonicalFinalIntegrationFactory`; the actual-host fixture injects one explicitly. |
| Sole-apply reducer/actor | Done but structurally dormant | `MessageSoleApplyEngine` and `MessageSoleApplyLane`; recovery hardening landed after the original stage-A merge. `soleApplyLaneEligible = CANONICAL_FACT_SOLE_APPLY_ELIGIBLE && perScopeControl`. |
| V2 reaction normalization | Partially done in shadow | `ReactionRosterNormalization`, `SyncCoreReactionShadowLane`, and the exact reaction-subtree tripwire in `CanonicalMessageV2ReactionEnvelope`. This is not a complete V2 message envelope. |
| Full V2 message envelope | Not done | `CanonicalFactCodec` still writes V1; `commentRef`/replies have not moved to a typed discussion graph; full-envelope migration/fresh reload/golden bytes are absent. |
| Sole-apply eligibility | Intentionally false | `CANONICAL_FACT_SOLE_APPLY_ELIGIBLE=false`; never flip this constant alone. |
| Production rollout control | Incomplete | Feature flags are boolean per server/platform. T3 shadow uses `sync_core_messages_v0`; the T4 seam requires a distinct key but no production key/factory binding is registered. The dormant two-flag route does not currently AND the prepared route with full-V2 global eligibility. |
| Canary metrics/go gate | Not activated | No production canary window has run; no legacy retirement is authorized. |

### 3.1 What “PR #1017 complete” means

PR #1017 completed the scoped T4 mechanical delivery and merged its exact tested tree. It did **not** mean:

- the normal production provider constructs the canonical-final integration;
- a T4 product flag exists or is enabled;
- V1 became sole-apply eligible;
- the UI is globally driven by sync-core;
- legacy can be retired.

The code/review/CI task is complete; activation remains a separate product and rollout gate.

### 3.2 V2 reaction boundary already established

The current V2 reaction sub-contract correctly separates public/shared and receiver-private data:

- shared bounded fact: `ReactionFact(emoji, count, previewK)`;
- receiver-private fact: `ReactionViewerOverlaySnapshot(reactedEmojis)`;
- roster/read model: typed `ReactionActorsDiscussionRef` and bounded read cache/page seam.

Therefore `viewerReacted` must **not** be copied into a shared room fact. It is derived from the private viewer
overlay. Earlier shorthand saying “the node keeps viewerReacted” is corrected by this boundary.

`previewK` is bounded (`CANONICAL_REACTION_PREVIEW_LIMIT`) and is not the complete roster. The complete roster
must never re-enter the durable shared node fact.

## 4. Later rulings that correct older prose

### 4.1 “Any error falls back to legacy” is too broad

The canary note's user-safe fallback remains valid **before a frame enters canonical commit ordering**, for
example a projection divergence or decode/envelope rejection that is routed to legacy while canonical state is
reconciled.

It is not valid after an activated scope has accepted a frame into the canonical actor. These failures must
remain fail-closed and hold the scope:

- actor mailbox overflow or closed mailbox;
- SQLite commit failure or unknown commit outcome;
- cursor/fact readback corruption;
- rebaseline failure;
- stale or out-of-order completion.

For those cases, showing the same event through legacy would expose a fact that canonical storage did not
commit and would violate commit-before-fan-out. The correct behavior is hold + single-flight server-canonical
rebaseline + breaker diagnostics. A return to legacy is legal only after control-plane revocation and a
successful canonical rebaseline barrier.

### 4.2 The old persistence identity is superseded

Historical notes that describe `SyncScopeKey(serverId, scopeId, scopeKind)` are obsolete. Current durable
identity is:

```text
DurablePartitionKey(serverId, principalId, consumerId)
  └── SyncScopeKey(partition, scopeId, scopeKind)
```

No selected-session principal fallback, whitespace normalization, or server-only cursor is conforming.

### 4.3 “No migration pre-release” is obsolete

The current database has a real forward migration for durable partitioning. Historical statements that the
entire cache can always be discarded instead of migrated must not guide new work. New V2 storage/version work
must preserve the current migration and rollback/retry guarantees.

### 4.4 One boolean flag does not implement three rollout stages

The current feature-flag client returns booleans. The intended stage table is implemented by two independent
facts, not by pretending one boolean is an enum:

| T3 `sync_core_messages_v0` | T4/sole-apply control | Route |
| --- | --- | --- |
| off/unresolved | any | legacy without current-grant canonical commit |
| on | off/unresolved | current-grant shadow commit, legacy visible |
| on | on **and** full V2 global eligibility true | prepared canonical-final/sole-apply route |

T4 on while T3 is off must remain legacy. Unresolved/mismatched server snapshots default off.

The separate T4 control key and normal production factory binding do not exist yet.
The current dormant `realtimeCanonicalFinalFlagRoute` checks only the T3 and T4 booleans; before a normal
production binding exists, the prepared route must also require the full V2 mechanical eligibility gate. The
actual-host fixture may exercise the dormant mechanics with test flags, but that is not rollout authorization.

### 4.5 Passive V1 shadow is optional evidence, not the sole-apply go gate

The earlier decision that a new passive collection phase is not required remains valid. Existing V1 shadow can
be enabled internally for diagnostics, but it cannot prove V2 eligibility and does not replace the real canary.

## 5. Ordered remaining delivery

### Phase A — freeze the complete V2 cross-end envelope

Owner of the canonical contract: Zhao/web alignment; mobile implements only after the field/version shape is
agreed.

Required closure:

1. Promote the existing normalized reaction sub-contract into the complete message envelope.
2. Move `commentRef`/reply children out of the shared node fact into one typed discussion graph/read model.
3. Define the full envelope version/kind and exact field set; do not reuse the reaction-subtree tripwire as if it
   were the whole-message schema.
4. Define V1 read behavior: reject as sole-apply input, invalidate the affected canonical scope, and request a
   server-canonical fresh reload/rebaseline. Do not reverse-project V1 legacy UI state into V2.
5. Pin independent golden bytes and a manifest/field-presence gate for the full V2 envelope.
6. Flip `CANONICAL_FACT_SOLE_APPLY_ELIGIBLE` only in the same reviewed change that makes every production
   constructor emit and validate the complete V2 shape.

### Phase B — production binding and rollout control

1. Define and register a distinct T4/sole-apply server feature-flag key.
2. Bind `SlockSharedCanonicalFinalIntegrationFactory` in the normal production host; keep construction lazy and
   flags default-off.
3. Require one immutable per-server flag snapshot to decide T3 and T4 for a frame, and require full-V2 global
   eligibility before returning the prepared route.
4. Prove flag off/unresolved, server mismatch, missing canonical carry, and missing registrations preserve the
   specified legacy route without constructing the router.
5. Prove enabled scopes consume committed-row projections plus explicit non-message overlays; the original mapper
   `Message` must not remain a second message-fact truth.

### Phase C — RED/hosted acceptance before activation

At minimum:

- independent full-V2 oracle and golden bytes;
- V1/unknown/extra/missing-field rejection and fresh reload;
- shared reaction fact vs private viewer overlay isolation;
- discussion graph identity and restart behavior;
- committed projection completeness and warm-start continuation;
- queue overflow/control close, commit failure, unknown commit, rebaseline, stale completion;
- no sink visibility before commit; no legacy leak after canonical acceptance;
- revoke-to-legacy only after successful rebaseline barrier;
- current-grant rotation/release/ABA and exact-zero-egress actual-host gate.

### Phase D — one-server mobile canary

Target one internal server and `platform=mobile`. User-visible semantics are legacy-or-equal only when a frame is
rejected **before** canonical acceptance; post-accept failures hold/rebaseline.

The go condition must hold over a defined observation window:

```text
attempted > 0
fallbacks / attempted ≈ 0, tagged by outcome
paused scopes / eligible scopes ≈ 0
```

Paused scopes are excluded from the fallback denominator but included in the coverage clause. No expansion or
legacy retirement may be inferred from a zero fallback numerator when most scopes are paused.

## 6. Explicit non-goals of the next change

- No flag enablement or server rollout in the docs/contract PR.
- No direct edit of `CANONICAL_FACT_SOLE_APPLY_ELIGIBLE` before full V2 closure.
- No revival of a parallel fact store, second message writer, or mapper-owned apply decision.
- No shared-room authorship of receiver-private reaction state.
- No “availability fallback” that leaks an uncommitted canonical frame through legacy.
- No claim that PR #1017 already made the production provider construct the final integration.
