# KT Sync-Core Persistence — Implementation Design Note (P1 / task #491)

**Owner:** HanXin · **Contract source of truth:** RFC 043 §9.9 + §10.1 amendment
(botiverse/slock PR #4445, staging `6c6229fcf`) · **Verifier of §9.9 gates:** 王润基 ·
**Android binding review + compile hard-gate:** Android-Developer-2 (task #494)

This note is the first P1 deliverable. It fixes the KT-side implementation shape
so P1–P3 code (schema → adapter → wiring → §9.9 gates) can be built and reviewed
against a written design rather than discovered in the PR. It does **not** restate
the contract; where behaviour is contended, RFC 043 (as amended by #4445) wins.

Scope of v1: **messages domain, KT only.** SyncLog (input-stream persistence) is
the §10-open-question-3 v2 candidate and is explicitly out of scope here — v1 ships
the ruled folded-state+cursor form. web IndexedDB adapter is T10 (Aiden, task #145).

---

## 1. Where persistence lives

`SyncCore` is a pure deterministic fold (`SyncCoreTypes.kt`: "no transport, no
persistence, no ambient clock or randomness"). It stays that way. Persistence is a
**host concern** implemented behind a narrow adapter the core never sees:

```
SyncCore (pure fold) ──emits──▶ applied facts + SyncScopeState{appliedSeq, epoch}
        ▲                                     │
        │ restore(state, watermark, epoch)    │ persist(frame outcome)
        │                                     ▼
   SyncPersistenceCoordinator ──────▶ SyncStorageAdapter (§10.1 narrow KV)
                                              │
                                    ┌─────────┴─────────┐
                                 SQLite               MMKV
                          (message window +      (auth/session,
                           watermark + epoch:     installation id,
                           crash-atomic unit)     flags — independent)
```

The core is **not** modified to write storage inline. A thin
`SyncPersistenceCoordinator` observes each ingest outcome and drives the adapter;
on launch it reads the adapter and calls the core's existing hydrate path
(`ingestSnapshot`, see §5). This keeps the equivalence suite (kt/TS core) untouched
— persistence is tested as a *separate* layer plus the §9.9 gates.

## 2. Backend split — decided by crash-atomicity, not by store type (§10.1)

Per the #4445 discriminator (HanXin): **anything that must be crash-consistent with
message facts lives on the same backend in the same transaction as those facts.**

| Datum | Backend | Why |
|---|---|---|
| message facts (folded domain state) | SQLite | pageable window, range scan, prune |
| per-scope **watermark** (`appliedSeq`) | **SQLite, same txn as facts** | advances with apply, gates difference — torn = message loss |
| per-scope **epoch** | **SQLite, same txn as facts** | epoch/fact mismatch = the rollback disease |
| timeline index | **SQLite, same txn as facts** | index-in-separate-txn = renamed torn window (#4445 §9.9 count invariant) |
| auth/session token, installation id, feature flags | MMKV | truly independent of message facts |

"Watermark-on-MMKV made safe by write ordering" is **rejected** by contract —
correctness that depends on an ordering discipline is correctness anyone can
silently break. The crash-window gate (§6.2) exists so we never rely on it.

## 3. SQLite schema — two keyspaces, one narrow KV interface

Both keyspaces are plain KV rows; the interface stays `get/put/prefix/scanPrefix`
(no engine-specific semantics leak into the core — the tdlib-on-web fork is the
named cautionary precedent in §10.1).

```sql
-- Fact space: point lookup / update by message id. Editing a message rewrites here.
CREATE TABLE sync_fact (
  k     TEXT PRIMARY KEY,   -- 'fact:' || serverId || ':' || scope || ':' || messageId
  ver   INTEGER NOT NULL,   -- version-gated max-by-version apply (RFC 041)
  value BLOB    NOT NULL    -- serialized fact
);

-- Timeline index: ordered window. Prefix scan by (serverId,scope) yields orderKey order.
CREATE TABLE sync_timeline (
  k       TEXT PRIMARY KEY, -- 'tl:' || serverId || ':' || scope || ':' || orderKey || ':' || messageId
  fact_id TEXT NOT NULL     -- -> sync_fact.k
);

-- Per-scope sync cursor + epoch. One row per (serverId,scope). Committed WITH facts.
CREATE TABLE sync_scope_cursor (
  k         TEXT PRIMARY KEY, -- 'cur:' || serverId || ':' || scope
  watermark INTEGER NOT NULL, -- = SyncScopeState.appliedSeq
  epoch     TEXT               -- nullable = pre-first-contact
);
```

Key rules (from #4445 §10.1):
- **fact key** `serverId:scope:messageId` — locates a fact; scanning it sorts by
  *message id* (arbitrary), so it can NOT define a timeline window on its own.
- **timeline key** encodes `orderKey`: `serverId:scope:orderKey:messageId`.
  `orderKey` = the per-scope **monotonic insertion order** (≈ `scopeSeq` at first
  apply). Edits/deletes rewrite the *fact* or add a tombstone; they do **not** move
  the timeline position → the window is stable across edit/restore.
- **global seq is sparse** (has holes) → used only as a monotonic ordering/resume
  cursor, **never** to synthesize dense ranges. `orderKey` ordering is by-prefix-scan
  in key order, which tolerates holes.
- **read/prefs (receiver-family state)** keep an independent key namespace and are
  never merged into `sync_fact`.

`orderKey` byte-encoding must be order-preserving as a TEXT prefix (zero-padded
fixed-width, or a lexical-sortable varint). Exact encoding finalized in code and
reviewed by AD2 (Android collation/index) before P1 first commit lands.

## 4. Atomic apply — one ingest call = one transaction (P1 core, task #491)

The transaction-count invariant scopes to the **ingest call**, not the individual
frame (赵, #4445 final read): a single frame is just the 1-fact special case. Each
ingest applies its facts + timeline rows + cursor/epoch in **exactly one**
`withTransaction { }`, so there is no mid-batch commit point that a crash could
tear:

| Ingest call | One transaction contains |
|---|---|
| `ingestFrame` (1 event) | 1 fact upsert + 1 timeline upsert + cursor/epoch bump |
| `ingestDifference` (N events) | **N** fact upserts + N timeline upserts + **one** cursor advance to `toSeq` |
| `ingestSnapshot` (rebaseline) | clear scope facts/timeline + rebuild from snapshot state + cursor/epoch set |

Facts + timeline + cursor + epoch of a call commit or roll back **together**. A
difference batch never leaves "cursor advanced but half the facts missing" (or the
reverse) — the two failure shapes 赵 flagged are impossible because there is only
one commit for the whole batch. Fact upserts are version-gated (skip if incoming
`ver` ≤ stored); replay after a crash is idempotent by `messageId`/`ver`.
Duplicate/gap/violation outcomes drive **no** fact write (a cursor bump only where
the contract says the watermark advanced).

Prune (task #492/#493, T6): capacity/tombstone eviction deletes timeline rows in an
`orderKey` range **and** their referenced facts in the **same** transaction — never
a fact without its index entry or vice-versa.

## 5. Restore / warm start (T4–T5)

On launch, per persisted scope, the coordinator rebuilds the core via its existing
hydrate path — no new core entrypoint:

1. read `sync_scope_cursor` → `{watermark, epoch}` and the folded state assembled
   from `sync_fact` (in `sync_timeline` order);
2. feed the core a `SyncSnapshot(scopeId, watermark, epoch, state)` — the same type
   already consumed by `ingestSnapshot`. The core resumes as if freshly hydrated.
3. the sync layer then requests **difference since `watermark`** (existing
   `SyncRequestDescription.Difference`); a gap beyond threshold escalates to
   `Snapshot` (existing path). Cold full-fetch becomes incremental resume.

Corrupt DB / `epoch` mismatch / difference `snapshotRequired` → the core already
routes to a `Snapshot` request (`SyncSnapshotReason.EpochMismatch/SnapshotRequired`).
**Deleting the local DB is a defined state**, not a failure: it degrades to a cold
snapshot hydrate. No silent hole-skipping (T7).

## 6. §9.9 acceptance gates → canonical fixture line (#541) (P3, task #493)

All three run on the merged canonical fixture (#541) — the natural carrier; no new
harness. 王润基 owns the pass/fail criteria; I write the tests.

### 6.1 Round-trip identity
`state → persist(adapter) → restore → state'`; assert `state' deepEquals state`
after replaying the canonical fixture. A serializer divergence is the
dual-representation disease.

### 6.2 Crash-window torn-write (deterministic fault-hook)
For each ingest call (frame **and** a multi-event `ingestDifference` batch): kill
**before** the storage commit (run A) and **after** it (run B); restore. Assert the
restored state `deepEquals` **either** the complete pre-call state **or** the
complete post-call state — never a torn mix. The transaction **count invariant**:
exactly one commit per ingest call carries all of its facts + timeline + cursor +
epoch; a separate index/cursor commit — or a per-frame commit *inside* a difference
batch — is a renamed torn window and fails by construction. The difference-batch leg
is essential: a mid-batch commit point does not exist, so "cursor advanced but half
the batch's facts missing" (and its reverse) are unrepresentable, not merely
untested.

### 6.3 Persist/restore equivalence (T8 joint gate seed)
A persisted-then-restored baseline fed further frames converges
**outcome-deepEqual** with the never-persisted run. This is the KT half of the
both-ends T8 release gate (TS/KT outcome deepEqual, 赵/王润基 criteria).

### Fault-hook injection points (for 6.2, supplied by P1)
The coordinator's `withTransaction { }` boundary exposes two test-only suspension
points, injected via a `SyncStorageFaultHook` (no-op in production, deterministic in
tests — no timing/threads):
- `beforeCommit(frame)` — kill here ⇒ run A (nothing persisted for this frame);
- `afterCommit(frame)` — kill here ⇒ run B (full frame persisted).
Determinism requirement (§9.9): the hook fires at a fixed logical step, driven by the
fixture, not wall-clock — so the kill point is reproducible in CI.

## 7. Build / test matrix

| Test | Layer | Gate |
|---|---|---|
| round-trip identity | commonTest | §9.9 6.1 |
| crash-window torn-write (hook A/B) | commonTest | §9.9 6.2 |
| persist/restore equivalence | commonTest | §9.9 6.3 / T8 |
| ordered-window recent-N stable after edit/restore | commonTest | T6 |
| fact/index atomic prune | commonTest | T6 |
| Android SQLite transaction/index binding | androidTest | T9 / AD2 review |

Android compile & tests are the only hard CI gate (mobile gate policy); iOS/OHOS
non-blocking + 30-min fix-forward. AD2 reviews the Android SQLite transaction/index
implementation on the P1 first commit (task #494).

---

**Open for finalization in code (flagged for AD2 review):** exact `orderKey`
byte-encoding (order-preserving TEXT), SQLite driver/txn API on KMP (SQLDelight vs
raw), and whether the fact `value` BLOB reuses the equivalence-suite serializer
(preferred — one serializer, one §9.9 round-trip).
