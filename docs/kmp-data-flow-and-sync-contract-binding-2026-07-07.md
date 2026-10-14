# KMP Data Flow & Unified Sync Contract Binding (RFC 037 / RFC 038)

Date: 2026-07-07
Author: HanXin
Status: Proposed (mirror of upstream RFCs; adopted per-slice)
Upstream sources of truth:

- External `botiverse/slock` checkout:
  `<slock-source>/rfcs/037-frontend-data-flow-standard.md` (frontend data-flow
  standard, L1–L4)
- `<slock-source>/rfcs/038-unified-sync-contract.md` (sequence watermarks, gap
  repair, canonical resolution)
- Coordination channel: #raft-mobile-reconcile (contract DRI: see task #70)

This document is the **mobile/KMP binding** of those two RFCs. RFC 038 §4 makes
the client obligations normative for AD4/KMP by name — mobile does not get to
improvise; it adopts these obligations before shipping conversation-rendering
surfaces. This binding restates the obligations in this repo's vocabulary,
maps them onto existing shared code, and defines the adoption slices.

It does not replace existing mobile contracts; it federates them:

- `docs/slock-app-state-first-principles.md` — app-wide state rules
  (normalized entities, patch-first refresh, no-op gates). Still normative.
- `docs/shared-mvi-foundation.md` — `MviStoreRuntime`, store map. Still
  normative.
- `docs/mobile-message-seq-gap-sync-spec-2026-06-25.md` — messages-domain
  cursor/gap contract. Subsumed here as the messages-domain instance of the
  general contract.
- `docs/mobile-foreground-reconciliation-spec-2026-06-29.md` — the
  reconcile/re-baseline trigger path. Referenced by §4.

Where this document and an older doc disagree, this document wins; file a
follow-up to amend the older doc.

## 0. Why mobile must care now

1. RFC 038's motivating incidents are ours too: send-echo duplication
   (`8d69e4fea fix(thread): drop local send echo on merge` is a heuristic
   absorption), mis-navigation family (origin-message-not-available, thread
   串台 — entrance-private inference), and gap behavior invented per surface.
2. The server difference endpoints are **post-1.0** (only messages
   `sync:resume` exists today). Mobile's job now is to shape the data layer so
   the endpoints plug in without re-architecture — not to invent private gap
   semantics in the meantime.
3. The web side has landed its skeleton (`store/events/`,
   `store/projections/`, per-domain reducers on staging). The channel-domain
   event vocabulary is being finalized in the T10 S1 slice; KMP folds align to
   that vocabulary when it is published, not to current web function
   signatures.

## 1. Layer mapping (RFC 037 L1–L4 → KMP)

| RFC 037 layer | Web construct | KMP construct (this repo) | Rule |
| --- | --- | --- | --- |
| L1 Transport | socket handlers + HTTP bootstrap outside components | `SharedSlockNetworkClient`, `RealtimeEventMapper`, `SlockRealtimeAppScopeCoordinator`, repositories/data sources | Every payload becomes a typed domain event/intent. Composables never touch HTTP, sockets, tokens, or raw JSON (already the iron rule). |
| L2 Domain store | zustand + `applyXxxEvent` reducer | `Shared*Store` reducer (`accept`/`reduceState`), `MviStoreRuntime` where adopted | One reducer entrypoint per domain. Realtime enters ONLY through `ApplyRealtimeEvent`-style intents. No cross-store writes: if two domains react to one event, both reducers receive the event. |
| L3 Projection | `store/projections/` pure selectors | Pure projection functions/objects in shared (e.g. `SharedConversationHeaderProjection`); display models | Derived facts (unread/attention/badges/muted) are computed in ONE place and imported by surfaces. No composable-local re-derivation of a fact that has a projection. Projections are axis-preserving (muted / mention-pierced / followed are parameters, not baked in). |
| L4 Components | React components | Kuikly composables | Render display models; dispatch intents; ephemeral UI state only. Already policy via first-principles doc §3. |

The event vocabulary is closed and shared with web:

| Event | Source | Semantics |
| --- | --- | --- |
| `hydrate` | HTTP initial load / pagination | Replace or extend the loaded window; server totals win; response MUST carry its watermark where the domain has one |
| `patch` | realtime frame | Local incremental transform; never triggers network I/O (I1); idempotent (I2) |
| `reconcile` | HTTP re-pull on reconnect / foreground / gap-repair miss | Server truth wins; diff-apply; log divergence |

Existing intents don't need mass renaming; new domain work names its intents
after this vocabulary, and migrating stores adopt it when their slice lands.

## 2. The sync contract triple (RFC 038 §3) — what mobile consumes

Every push-shaped domain the app renders (messages, agent activity, machine
status, tasks) will expose, server-side:

1. **Watermark** — per-scope monotonic `scopeSeq` + `epoch` discriminator on
   every push frame and every snapshot response. Scope = the domain's natural
   consistency boundary: channel for messages, agent for activity, machine for
   status, server for tasks.
2. **Difference endpoint** — `GET <domain>/difference?scope&sinceSeq&epoch`
   returning ordered events after the watermark, or `snapshotRequired` when
   the distance is unserviceable.
3. **Aggregate snapshot** — the hydrate endpoint, returning state + watermark
   in the same payload. **An unversioned snapshot cannot be safely patched;
   mobile must not build features that patch snapshots lacking watermarks.**

Today only messages has a prototype (`sync:resume` +
`GET /messages/sync?since_seq=…` — see the seq-gap spec). Agents domain (#75)
landed the pilot contract server-side. Others come post-1.0.

## 3. KMP client obligations (RFC 038 §4, restated as repo rules)

These apply per domain, inside the domain's shared reducer/fold — never in
composables, never in platform hosts.

1. **Ordered apply within an epoch.** Frames apply in `scopeSeq` order.
   `seq <= appliedSeq` → idempotent drop (no state change, reference-stable
   no-op per the MVI no-op gate).
2. **Gap → stop and repair, never guess.** For a dense domain, a frame with
   `seq > appliedSeq + 1` is NOT applied;
   the store records the deferred frame, requests the difference endpoint
   (today for messages: `/messages/sync` from the visible-stream tail per the
   seq-gap spec), applies the repair batch, then re-stitches deferred frames.
   The repair batch may already contain frames the client is holding as
   deferred — re-stitching therefore MUST re-run watermark validation
   (ordered apply + idempotent drop make this naturally safe; do not
   "optimize" the re-check away by assuming deferred frames are still ahead
   of the watermark after repair).
   Sparse domains (agent activity) skip gap detection but keep ordered apply
   and idempotent drop.
   **Density ruling for the messages domain (contract DRI + HanXin, 2026-07-07,
   normative — same text in the RFC 038 amendment):** the messages density
   declaration does NOT take effect until the per-scope difference endpoints
   land (server implementation window, Stone). On the current wire,
   `messages.seq` is a server-global `bigserial` and delivery is
   visibility-filtered, so every user's received stream legitimately has
   holes — the domain is effectively **sparse + visibility-filtered**.
   Clients may only do **max-advance + repair-trigger** (a suspected gap may
   trigger `sync:resume`/full re-pull, frames still fan out immediately;
   consumers dedup by message id). **Stop-gating is forbidden** — a strict
   `seq == applied + 1` gate at ingress wedges realtime fan-out permanently
   on the first hole (caught in the task #222 / PR #260 review).
3. **Epoch discipline.** Watermarks are meaningful only within one epoch.
   Server restart / scope reset / client re-baseline starts a new epoch; a
   frame from a previous epoch arriving after re-baseline must never apply.
   Reconnect and foreground return are re-baseline points — this is the
   contract behind `docs/mobile-foreground-reconciliation-spec-2026-06-29.md`;
   the reconciler re-baselines, it does not trust in-memory watermarks across
   a reconnect.
4. **Request-generation tokens.** An in-flight hydrate response that crosses a
   reconnect/server-switch must be dropped, not applied. Use `MviStaleToken`
   (already in the MVI foundation) on every hydrate/reconcile path. This is a
   live web-side bug class; KMP inherits the rule, and mostly has the
   primitive already.
5. **Timestamps never decide.** `timestamp`/`observedAt` are display/log
   metadata. Ordering, dedup, and conflict decisions use ids + seq + epoch
   only (M-15 rule). Any existing reducer comparing clocks to decide apply
   order is a bug to file.
6. **Non-comparable frames.** A frame the client cannot order goes to
   log-side projections only + triggers reconcile; it never overwrites
   current state on a guess.
7. **Equal seq.** Same seq + same content ⇒ idempotent drop. Same seq +
   different content ⇒ producer violation: deterministically drop the
   later-arriving frame and request reconcile. The client does not arbitrate.

## 4. Send side: pending shadow + `random_id` (RFC 038 §6)

Target contract: a locally-sent message is a **pending shadow**, never a
canonical row. Every send carries a client-generated **`random_id`** (field
name ruled by artin in task #83 — Telegram's original name, not a translated
alias; wire casing ruled by artin 2026-07-07 (#raft-mobile-reconcile): the
wire field is **`randomId`** (repo camelCase); `random_id` remains the
concept name); the canonical echo returns it verbatim; shadow
absorption matches on that id alone. This closes both failure modes:
duplicate rows on echo, and the "RPC response lost but message delivered"
trap (shadow stays retryable; the push echo absorbs it by id).

**Current server reality (confirmed with the contract DRI, 2026-07-07): the
server does not accept or echo `random_id` yet.** A server-side additive
slice is being filed by the contract DRI; progress tracked in #proj-frontend /
#raft-mobile-reconcile.

Mobile rules until then:

- Keep heuristic echo absorption, but **consolidate it into a single shared
  function** (one call site policy), marked deprecated with a pointer to this
  section — so the switch to exact matching touches exactly one place.
- Do not add new content/attachment/task-context matching heuristics anywhere
  else. New send paths must route through the single absorption point.
- When the server slice lands: generate `random_id` per send in the shared
  send path, persist it on the pending shadow, absorb by exact id, and demote
  the heuristic to a migration fallback with a removal milestone.

## 5. Canonical resolution: ConversationResolver binding (RFC 038 §5)

Contract (platform-neutral):

```text
resolveOpenRequest(serverId, sourceMessageIdentity)
  -> { conversationKind, parentIdentity, threadRoot?, focusedMessage,
       hydrationContext }
```

Mobile rules:

- **All mobile entrances** — notification taps, Activity rows, Home rows,
  search/saved hits, thread opens, deep links — resolve `conversationKind`
  (DM vs channel vs thread vs DM-thread) through one shared resolver.
  **Entrance-private inference is forbidden.** This is the structural fix for
  the current mis-navigation family (origin-message-not-available, wrong
  thread opened from conversation flow).
- If the resolver lacks context, it returns a pending verdict with a
  conservative fallback; the entrance may render the fallback but the OPEN
  still routes through the resolver after hydration. (This matches the
  launch-contract rule: payloads carry identity + intent only.)
- **Pending reasons are tiered (three-state resolution, landed with
  slock-mobile #256 / task #419, LiBai ruling 2026-07-07):**
  - `conversation_hydrating` — directory projection miss; existing
    raw/source fallback or wait-for-hydration paths apply.
  - `thread_parent_unresolved` — projection hit but the thread parent is
    not resolvable AND the source carries no trusted typed parent kind.
    This must never reach Resolved (the #415 family is structurally dead
    here).
  - **Typed-parent qualification**: if the source carries a
    server-enriched, typed `parentConversationKind` (e.g. Activity
    InboxItem, message `conversationContext`), raw rebuild to Resolved is
    permitted — that is qualification against server facts, not
    entrance-private guessing. User-initiated entrances must never
    silently no-op (C13 ruling: MentionAction keeps its explicit product
    default with a comment; it is not a parse fallback).
  - Raw identity builders guard `kind == Thread &&
    parentConversationKind == null → null`; no null-parent thread
    identity may be constructed anywhere.
  - **User-initiated entrances (landed with #272 / task #419, t5 A3/A4):**
    row clicks resolve with the REAL `serverId + conversationDirectory`
    (explicitly passed — no new globals). A `Pending` verdict at a
    user-initiated entrance is an **explainable failure**: loud
    source-tagged log + user-visible toast + no navigation. Direct
    identity construction that bypasses `resolveOpenRequest` is forbidden
    (the old zero-arg entry and the Pending→direct-builder fallback are
    deleted; the violation is no longer expressible in the types).
    Remaining payload-layer kind defaults are defensive positions only and
    must emit `thread_launch_parent_kind_defaulted` with a source tag —
    a hit means a resolver bypass and escalates to hardening.
    Pending must never lose data: `sourceMessageIdentity.originFallback`
    (parentChannelId / parentMessageId / preview / sender) is preserved
    through a Pending verdict.
  - **Identity-priority corollary (LiBai + HanXin ruling 2026-07-07; fixes
    landing as #226 and #429):**
    - Where a surface has **self-attestation** (the route payload declares
      its own `surfaceKind`), that declaration is authoritative for
      "what surface am I" decisions. Projections/directory may enrich
      missing data; they may never rewrite the declared kind (a
      parent-channel projection must not turn a reply-thread page into a
      channel page — the #226 bell-gate leak).
    - Where a surface has **no self-attestation** (e.g. foreground banner
      over raw `message:new`, which carries no kind — the reason task #86
      exists), the adjudicator (directory/context) decides by an EXPLICIT
      priority table: canonical declarations beat alias declarations (a
      ReplyThread canonical must not be displaced by a DM stream-alias —
      the #429 banner misroute).
    - In all cases: a projection never silently rewrites an established
      identity.
- Acceptance includes **cross-surface resolution equality** (artin's
  requirement): for a corpus of source message identities, every entrance's
  resolver output must be identical. Mobile owes the KMP half of that test.

## 6. Determinism test obligations (RFC 038 §7, KMP shape)

Per adopting domain, as part of migration acceptance:

1. **Anchored fold first.** Write the canonical-order fold (the executable
   definition of the domain's semantics) before the implementation.
2. **Convergence property.** Any arrival order of the same event set — with
   difference-repair batches injected as events for dense domains —
   converges to the anchored terminal state. Small N: exhaustive
   permutations. Large N: seeded property tests (fixed seed, failures print
   the seed, no silent reseeding; kotlin.random with pinned seed or
   kotest-property with a pinned seed both qualify). Shrunk counterexamples
   become named example tests.
3. **Properties hit the pure fold only** — never store wiring, never
   composables, never the realtime mapper. Wiring is covered by named
   regression tests and behavior baselines (this repo's existing style).
4. **Standing properties:** idempotence; no-op reference identity (the MVI
   no-op gate observable in `MviTransition`); cross-epoch isolation (a
   pre-re-baseline frame never applies).
5. **Template:** the #75 agents-domain pilot (web
   `packages/web/src/store/events/agentActivityEvents.ts` + its tests) is the
   reference shape to translate, not to transliterate.

## 7. Current-state gap table (mobile, 2026-07-07)

| Contract piece | Mobile today | Gap / action |
| --- | --- | --- |
| Messages watermark + difference | `RealtimeCursorPolicy`, `sync:resume`, `/messages/sync`, `SyncResumeResponse`, per-stream tails (seq-gap spec) | Closest to contract. Missing: explicit epoch discriminator (server-side too), named single gap policy, deferred-frame re-stitch as a tested fold rule |
| Agent activity seq | `agent:activity` carries per-agent `serverSeq`; mobile applies as presence patches | Sparse domain: adopt ordered-apply + idempotent drop in the fold; no gap detection; pull reconciliation stays authoritative |
| Machine status | `statusVersion` consumed ad hoc | Await server contract-ization; do not build a private sequence mechanism |
| Tasks | refresh-shaped | Await server watermark; keep patch-first rules from first-principles doc |
| Epoch / re-baseline | Foreground reconciliation spec (triggers exist) | Make re-baseline explicit: on reconnect/foreground, drop in-memory watermarks, re-hydrate or difference-from-persisted-cursor; add stale-token guards on hydrate paths that lack them |
| Send echo | Heuristic absorption in thread merge path | Consolidate to single shared absorption point, mark deprecated (§4); switch to `random_id` when server lands it |
| ConversationResolver | Per-entrance resolution logic; launch-contract identity-only rule | Introduce shared resolver; migrate notification taps + Activity rows first (the active bug family) |
| Projections | `SharedConversationHeaderProjection` exists; unread/badge logic partly in composables (`SlockHomeComponents`) | New derived facts (e.g. activity-mute quiet badges, task #553) go through shared projections; migrate existing badge derivations opportunistically |
| Transition observability | `MviTransition` + structured logs | Future: align naming with web's `slock.state.transition` trace family (RFC 040, pending publication) |

## 8. Adoption slices (each independently landable)

- **B0 — this document.** Binding + vocabulary agreed.
- **B1 — send-echo single point.** Consolidate heuristic absorption into one
  deprecated shared function; add the regression test that a canonical echo
  absorbs the shadow exactly once. (Blocked switch-over: server
  `random_id` slice.)
- **B2 — messages fold hardening.** Name the gap policy per the seq-gap spec
  slices A–D; add the convergence property with repair-batches-as-events for
  the thread timeline fold; stale tokens on all hydrate paths.
- **B3 — ConversationResolver.** Shared resolver + migrate notification-tap
  and Activity-row entrances; add the cross-surface equality test corpus.
- **B4 — channel-domain vocabulary alignment.** When web T10 S1 publishes the
  channel event vocabulary, align `SharedHomeStore`/channel folds' intent
  naming and event shapes (includes per-receiver notification prefs /
  activity-mute as domain state — currently no realtime event; hydrate/
  reconcile only, no polling).
- **B5 — new domains on the contract.** As server difference endpoints land
  (agents first, per #75), bind each domain's fold to
  watermark/difference/epoch instead of bespoke refresh.

Ordering: B1–B3 are independent; B4/B5 are event-driven by upstream landings.
Every slice: behavior baseline pinned before surgery; user-visible behavior
unchanged (RFC 037's invariant, same as this repo's existing rule).

## 9. PR review checklist additions

For any PR touching push-shaped domain state, reviewers check — in addition
to the first-principles checklist:

- New state field: stored or derived? If derived, which single projection
  computes it?
- Does realtime enter only through the domain reducer intent? No composable
  or host-side socket handling?
- Any ordering/dedup decision using timestamps? (Reject.)
- Any new send path bypassing the single echo-absorption point? (Reject.)
- Any entrance inferring conversation kind privately? (Reject once B3 lands;
  flag as debt before.)
- Hydrate/reconcile responses guarded by a stale/generation token?
- For dense-domain folds: does the change keep gap → stop-and-repair
  semantics, and is the convergence property updated?

## 10. Open questions (tracked in #raft-mobile-reconcile)

- Server timeline for `random_id` echo slice (contract DRI filing; B1 switch-over
  depends on it).
- Channel-domain event vocabulary publication (web T10 S1, expected this
  week) — B4 trigger.
- Epoch discriminator surface for messages `sync:resume` retrofit (server
  post-1.0); until then reconnect = full re-baseline for mobile.
- Whether KMP transition traces adopt web's RFC 040 observation classes
  verbatim or a mapped subset.
