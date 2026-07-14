# Realtime Consistency Contract: Gap Recovery and Session Authority

- Status: **standard / frozen contract**
- Contract versions: **Thread gap recovery v1** and **realtime foundation v1.2**
- Scope: shared KMP realtime state, Android/OHOS hosts, SQLite persistence,
  projection sinks, and any future platform binding of the same contracts.
- Change control: a semantic change requires a version bump, a new adversarial
  test/mutation, and renewed contract review. A production patch or passing
  happy path does not amend this document implicitly.

This document is the canonical contract for two coupled correctness boundaries:

1. recovering a visible message stream while live events, pagination, route
   changes, and terminal cleanup race; and
2. deciding which authenticated scope, connection family, grant, persistence
   transaction, and projection sink is allowed to mutate state.

The shared principle is:

> An asynchronous result may mutate state only while the exact authority that
> created it is still current, and the authority check must be linearized with
> the mutation it protects.

`MUST`, `MUST NOT`, `SHOULD`, and `MAY` below are normative.

## 1. Relationship to older documents

This contract supersedes the ownership model in
`realtime-session-owner-boundary-spec.md`. That document remains useful as
migration history and transport-lifecycle motivation, but its process/session
owner sketch is not the current grant or scope model.

This contract also supersedes the visible-stream recovery algorithm in
`mobile-message-seq-gap-sync-spec-2026-06-25.md`. That document's distinction
between a server-global resume cursor and a visible-stream cursor remains valid.
The cursor/target, sparse-sequence, coalescing, and terminal rules in this
document are authoritative.

## 2. Common terminology

- **Route identity**: the complete identity of a visible message stream,
  including server and canonical channel/thread scope.
- **Route generation**: an opaque generation (also called a stale token) that
  changes when the same route is refreshed, restarted, cleared, or recreated.
- **Recovery id**: an opaque id for one visible-stream recovery job.
- **Authority transition**: a serialized operation that creates, replaces, or
  revokes an auth revision, scope lease, connection family, grant, or sink.
- **Fact**: canonical data that may be persisted and projected.
- **Projection**: a view derived from facts. It is not a second authority.
- **Terminal transition**: the state transition that ends a recovery, request,
  grant, or registration and performs guarded cleanup.
- **Current**: equal to the current authoritative identity, not merely similar
  in server, route, principal, status, or UI selection.

## Part I — Thread visible-stream gap recovery v1

### 3. Recovery state

One active recovery record has at least this logical shape:

```text
ActiveRecovery(
  routeIdentity,
  routeGeneration,
  recoveryId,
  networkCursor,
  pendingTarget,
  targetRevision
)
```

The implementation MAY use different names, but it MUST preserve the following
separations:

- `networkCursor` is the maximum sequence from the most recently accepted
  **network page**. It MUST NOT be derived from the merged visible payload.
- `pendingTarget` is the highest missing-through target known to the recovery.
- `targetRevision` changes only when `pendingTarget` advances strictly.
- live high-water belongs to the live event path. A live event MUST NOT advance
  `networkCursor`.
- visible payload, cache payload, loading/error status, and `canLoadMoreAfter`
  are projections guarded by the active recovery identity; none of them is the
  owner identity itself.

`status == loading` MUST NOT be treated as proof that a recovery owns the
stream. Ordinary `LoadNewer`, a route refresh, and gap recovery are distinct
operations even when they share a visible loading state.

### 4. Target semantics on sparse sequence spaces

Message sequence numbers are ordered but not dense. Therefore
`pendingTarget` is a missing-through boundary, not a promise that every integer
below it exists in the stream.

- If gap event `N` has not been applied live, recovery MUST advance
  `pendingTarget` to `max(previousPendingTarget, N)`.
- If a new high-water event `N` has already been applied live, recovery MUST
  advance `pendingTarget` to `max(previousPendingTarget, N - 1)`.
- A stable short page MUST terminate even with
  `networkCursor < pendingTarget`.
  That is not proof of a missing message because the skipped sequence numbers
  may belong to another stream or may not be visible to this receiver.
- Tests MUST use real sparse fixtures. A fixture with contiguous `+1` sequence
  values is not evidence that the production sequence space is dense.

The target revision MUST advance only on a strict target increase. Duplicate,
out-of-order, or already-applied events MUST NOT extend the recovery.

### 5. Start, join, replace, and owner handoff

The active identity is the tuple:

```text
(routeIdentity, routeGeneration, recoveryId)
```

The following rules are mandatory:

1. A gap observed for the same route identity and route generation MUST join
   the active recovery by advancing its target, or remain a no-op when the
   target does not advance. It MUST NOT create a second recovery owner.
2. A new route generation or different route identity MUST replace the active
   recovery with a new recovery id.
3. An old recovery's response, terminal transition, exception handler, or
   `finally` block MUST NOT clear, overwrite, or mark idle a successor record.
4. Replacement and cleanup MUST use identity compare-and-set semantics, or an
   equivalent single linearization point.
5. A live gap that arrives while ordinary `LoadNewer` is parked MUST be retained
   and handed off after that owner completes, or both operations MUST use one
   explicit owner protocol. Treating `loading` as the owner and dropping the
   live gap is forbidden.

Refresh, Start, route switch, clear, and cancellation MUST invalidate the old
generation. Invalidation MUST eventually leave neither a permanent active
record nor a permanent loading projection.

### 6. Page application and cursor progress

For every accepted network response:

1. The response size MUST be `<= requestedLimit`.
2. The page cursor is the maximum sequence in that fetched page only.
3. Cursor progress MUST be strict. An empty page, missing cursor, equal cursor,
   or regressing cursor is a no-progress terminal condition.
4. The fetched page is merged into the latest visible/cache payload, not into a
   stale snapshot captured before the request.
5. Merge remains idempotent by message identity, so a message applied live and
   returned later by recovery appears exactly once.
6. Every payload, cache, error, `Empty`, `NoPermission`, idle, loading, and
   `canLoadMoreAfter` write MUST revalidate both the current route generation
   and the active operation id: the recovery id for gap recovery, or the
   explicit pagination owner id for ordinary `LoadNewer`.

No network await may occur while holding the state/authority mutex. The mutex
protects short identity and state transitions, not transport latency.

### 7. Continue and terminate

At request start, capture the current target revision. After accepting a page,
continue only when all of these are true:

- the page made strict cursor progress;
- the cursor is still below the current target; and
- either the page was full, or the target revision advanced while that request
  was in flight.

A stable short page terminates even when the cursor is numerically below the
target, because the sequence space is sparse. `Empty`, `Error`,
`NoPermission`, cancellation, and no-progress responses also terminate. Their
terminal writes remain identity-guarded.

### 8. Terminal/event linearization

The race between a terminal transition and a new gap event MUST have one
linearization point:

- **event first**: the event updates the active recovery's target/revision, and
  that recovery continues or performs an explicit handoff;
- **terminal first**: the terminal transition clears only its own recovery id,
  and the event creates a new recovery.

Neither order may lose the event, apply it twice, strand loading, or leave a
successor without a job. An implementation that checks the target and clears
the job in separate unguarded steps is non-conforming.

### 9. Route-successor behavior

Route B may begin and receive an event before route A's old network job reaches
its terminal path. The required outcome is:

- B may start recovery immediately under B's identity;
- B's result may land before A completes;
- A's late response/terminal/finally performs zero B-visible/cache/status
  mutation; and
- A cannot clear B's active record or suppress B's event because a generic job
  or loading flag is non-null.

This applies both to a different route and to the same route recreated with a
new generation.

### 10. Recovery acceptance matrix

Every production implementation MUST exercise the matrix below on the real
store/request path. Helper-only tests are insufficient.

| Axis | Required cases |
| --- | --- |
| Page shape | single page; full page plus later page; stable short snapshot plus later live target |
| Live interleaving | event before response; event after response; event at terminal linearization |
| Successor | same route/new generation; different route; successor before old terminal; old terminal before successor |
| Competing owner | ordinary `LoadNewer` parked while a high gap arrives |
| Terminal | `Empty`; `Error`; `NoPermission`; no progress; cancellation |
| Event class | non-gap; first gap; duplicate; out-of-order |
| Sparse paging | real page sizes `<= limit`; full first page; sparse second/short page; no dense `+1` assumption |
| Projection | visible payload; cache; status; error; `canLoadMoreAfter` remain mutually consistent |
| Cleanup | Refresh; Start; route switch; clear; cancel; old `finally` after successor |

The matrix MUST include at least these adversarial mutations:

- use live/merged payload maximum as the network cursor;
- use status/loading as owner identity;
- remove terminal/event compare-and-set;
- permit an old recovery id to write cache or error state;
- permit old terminal cleanup to clear a successor;
- extend the target for a duplicate/out-of-order event;
- assume a short page proves `cursor >= target`;
- hold the state mutex across network await.

## Part II — Realtime authority, grants, scopes, and sinks v1.2

### 11. Canonical model

The logical model is:

```text
AuthRevision = (generation, session?)
// session == null is a durable tombstone, not absence of a fact

ScopeLease = (scopeKey, scopeLeaseId, active)

SlotKey = (scopeLeaseId, serverId)

ConnectionWitness = (
  scopeLeaseId,
  principalId,
  authGeneration,
  connectionFamilyId
)

GrantRecord = (
  SlotKey,
  principalId,
  authGeneration,
  connectionFamilyId,
  connectionEpochId
)

RegistrySnapshot = (
  AuthRevision,
  activeLeases,
  currentFamilyByLease,
  currentGrantBySlot
)
```

`connectionEpochId` MUST be restart/ABA safe, for example a durable monotonic
counter or `(processInstanceId, counter)`. It is part of grant identity, but it
MUST NOT be part of `SlotKey`; otherwise one logical slot could have multiple
current grants. Each `SlotKey` has at most one current `GrantRecord`.

### 12. Auth revision is durable truth

`AuthRevision(generation, session?)` is the durable auth truth. A null session
is a durable tombstone and MUST survive restart.

When processing an auth event at the same authority linearization point:

- `g < lastGeneration`: ignore as stale;
- `g == lastGeneration`: the duplicate auth event is idempotent, with zero
  mint; this does not prohibit `register(scopeKey)` from creating a new lease
  under the unchanged current revision as defined in section 13;
- `g > lastGeneration`: reread durable auth and atomically build and publish
  one complete `RegistrySnapshot` across all active leases, grants, gates,
  shadows, and exposed flows.

No observer may see a half-published auth generation. Compose/UI code consumes
published flows and MUST NOT mint grants.

When `session == null`, the complete snapshot authorizes no current family or
grant. Gates, shadows, exposed flows, persistence callbacks, and queued sink
callbacks MUST all observe the tombstone as zero authority.

Same-principal reauthentication, same-token reauthentication, or token rotation
with a higher auth generation still creates a new authority generation, family,
and grant epochs. Principal equality does not keep old authority current.

### 13. Scope lease lifecycle

`register(scopeKey)` is an authority transition:

- registering an already active key returns the same active lease and performs
  zero mint;
- concurrent independent instances require different scope keys;
- after an old lease is released, registering the same key creates a new
  `scopeLeaseId`;
- when the current auth revision has a live session, the new lease atomically
  receives one connection family, all server-slot grants for the session's
  memberships, and flows at that same revision;
- under an auth tombstone, registration cannot mint a family or grant;
- the old lease's witnesses and sink handles remain invalid forever.

`release(scopeLeaseId)` affects only that lease's slots, sinks, and flows. It
MUST NOT revive or retarget an old lease by scope key.

Release and auth advance share one linearization domain:

- release first: the old lease is absent from the new auth snapshot;
- auth first: the lease receives new-generation authority, then release burns
  it;
- sibling leases are unaffected in either order.

### 14. Lease-wide connection family

The connection family is **lease-wide**, not per server:

- one active lease has exactly one current `connectionFamilyId`;
- all server-slot grants of that lease share the same family;
- a transport reconnect atomically replaces the lease family and all of its
  server-slot grants;
- sibling leases are unaffected;
- the currently selected server is a UI choice, not an authority boundary;
- server membership changes only through a complete `AuthRevision` snapshot.

A model that replaces only one server's family is a different contract and
requires a version bump and review. It cannot be introduced as an optimization.

### 15. Connection witness and cross-server resolution

An asynchronous callback captures a `ConnectionWitness`, not a selected-server
grant. Resolution takes `(witness, eventServerId)` and is lookup-only.

It returns a grant only when all of these are current:

- lease id;
- principal id;
- auth generation;
- lease connection family; and
- a current grant already exists for the event server's slot.

Old family, missing server, released lease, wrong principal, or stale auth
returns null. The resolver MUST NOT mint, fall back to the selected server, or
construct a server-local family. A fresh witness for selected server A may
lookup an existing grant for server B; an old family witness resolves null for
every server.

### 16. One authority gate for registry, persistence, shadow, and sinks

The following operations MUST use the same authority API/lock domain:

- auth snapshot publish;
- family mint/replace;
- lease/principal revoke;
- `runIfCurrentGrant` or equivalent mutation guard;
- the full SQLite `BEGIN -> facts/projection/cursor -> COMMIT` operation;
- shadow final apply;
- sink enqueue and final sink mutation validation.

An authority check followed by an unguarded database or projection mutation is
a time-of-check/time-of-use defect. Registry publication and the mutation gate
must not be independently observable.

StateFlow or other observer publication occurs after the atomic transition.
An old grant performs zero database, cache, shadow, cursor, projection, and sink
mutation.

### 17. Durable persistence identity

Durable facts, cursor, and dedup identity are keyed at least by:

```text
(serverId, principalId, consumerId)
```

Consequences:

- principal P2 MUST NOT inherit P1's cursor or private projection;
- reauth or token rotation of the same principal MUST reuse already committed
  durable facts/cursor/dedup, while the grant/family/epoch is new and every new
  write still requires that new grant;
- transient ingress dedup resets per connection family/epoch;
- each consumer owns an independent cursor;
- a consumer applies facts, projection, and cursor in one transaction;
- a scope-local UI ledger is distinct from the global durable ledger.

Server-only or scope-only cursor/dedup keys are non-conforming because they can
cross-contaminate principals or consumers.

### 18. Sink registration and routing

A sink registration has this logical identity:

```text
(scopeLeaseId, serverOrSurface, registrationId)
```

`registrationId` is opaque and ABA safe. An old handle may remove only the
registration with the same id; it cannot delete a successor registered at the
same logical key.

The router is targeted, not broadcast. It preserves per-grant sink ordering.
Both enqueue and final mutation perform current-grant validation. A queued
callback released before execution performs zero mutation.

### 19. Foundation acceptance matrix

| Axis | Required cases |
| --- | --- |
| Auth | stale; equal/idempotent; newer; null tombstone; restart; no half-generation visibility; tombstone leaves zero authority |
| Lease | register active key; release; same key gets new lease id; repeat new registration zero mint |
| Release/auth race | release first; auth first; sibling lease unaffected |
| Family | multiple servers and leases; reconnect replaces every slot of one lease; sibling lease unchanged |
| Resolver | fresh witness resolves B; old family resolves null for all servers; missing/wrong server fails closed |
| Principal | P1 -> P2 isolation; same-principal reauth reuses durable facts but gets a new grant |
| Consumer | at least two consumers with independent cursors and transactions |
| Persistence | registry publish races memory/SQLite/shadow barrier; old grant zero; new grant positive |
| Sink | ABA old handle; queued callback after release; per-grant order |
| Re-registration | L1 release -> L2 new id with immediate grants; repeat L2 zero mint; L1 witness/handle zero |

The foundation test suite MUST kill at least these mutations:

- include connection epoch in `SlotKey`;
- drop the durable null tombstone;
- publish one scope or grant before the complete auth generation;
- revive a released lease by scope key;
- resolve through selected server A or mint a missing B grant;
- key durable cursor/dedup by server only;
- remove a sink by logical key without registration id;
- check the registry and mutate DB/shadow/sink under separate authority domains;
- reconnect one server without replacing the lease-wide family;
- let equal-generation registration mint again;
- leave a family, grant, flow, shadow mutation, or queued sink authoritative
  after an auth tombstone or lease release.

## 20. Conformance and rollout gates

Definition review, test-oracle review, production-content review, hosted compile,
and rollout approval are separate gates:

1. **Definition PASS** means the model in this document is internally complete.
2. **Test-oracle PASS** means the adversarial matrix faithfully encodes the
   model and kills the named mutations.
3. **Production-content PASS** means an exact production head maps every
   transition and mutation boundary to the model.
4. **Mechanical PASS** means the exact head compiles and the complete intended
   test set is discovered and passes on hosted CI.
5. **Rollout PASS** additionally requires migration, shadow/no-apply,
   observability, and product activation gates.

No earlier gate implies a later one. Test fixtures, review notes, and passing
behavior from a mixed work-in-progress branch MUST NOT be reported as a merge or
rollout decision.

## 21. Non-goals

This contract does not:

- choose a socket transport or coroutine implementation;
- make UI selection an authority boundary;
- permit route/page lifecycle to own or disconnect the process transport;
- make projections, loading status, caches, or current server selection a
  replacement for owner identity;
- define a dense global message sequence;
- authorize a second persistence truth or a second realtime owner;
- authorize silent fallback when principal, lease, family, generation, route,
  recovery id, or registration id is missing or stale.
