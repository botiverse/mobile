# KT Sync-Core Persistence — Implementation Design Note (P1 / task #491)

**Owner:** HanXin · **Contract source of truth:** RFC 043 §9.9 + §10.1 amendment
(botiverse/slock PR #4445, staging `6c6229fcf`) · **§9.9 gate criteria:** 王润基 ·
**Android binding review + compile hard-gate:** Android-Developer-2 (task #494)

**Rev 3** — folds two review rounds. Rev 1's parallel `sync_fact`/`sync_timeline`
tables are withdrawn (double-truth). Rev 3 reuses the existing SQLDelight
`SlockDatabase` and pins the constraints that table-reuse exposed (composite key,
projector completeness, poisoned-core barrier, FK-safe snapshot). Passed and frozen
by prior rounds: per-ingest-call transaction scope, fail-stop, SQLDelight reuse,
canonical server orderKey.

Scope of v1: **messages domain, KT only.** SyncLog (input-stream) is §10-open-3 v2.
Web IndexedDB adapter is T10 (Aiden, task #145).

---

## 1. Single-writer wrapper — projector, not adjudicator

`SyncCore` is a pure fold (`SyncCoreTypes.kt`: no transport/persistence/clock) and
stays so. Persistence cannot be driven by the ingest *outcome* alone
(`MaxAdvanced(scope,seq)` / `ingestDifference` don't carry facts; `Applied(scope,seq)`
must not be reverse-engineered). So there is one **`SyncPersistenceWrapper`** — the
sole host apply path:

```
callers ─▶ wrapper.ingestX(input) {
    core.ingestX(input)                       // core adjudicates (RFC 041 version gates in fold)
    val rows = projector(core.state(dom,scp)) // deterministic state→rows; NO 2nd fold from input
    db.transaction { applyRows(rows); advanceCursor(latestSeq, epoch) }  // ONE txn / ingest call
    // returns ONLY after commit; on failure → §4.3 poison + barrier
}
```

- **Projector, not adjudicator (赵):** version adjudication lives in the fold; storage
  never re-compares `seq`. A second compare = a second adjudication point = the
  dual-representation disease. Storage projects the core's already-won post-state —
  the storage form of "UI reads == core applied, no second truth."
- **Projector completeness (AD2):** the state→rows projector must persist **all
  canonical state the fold needs to keep running** — if the messages fold carries
  revision/tombstone/version metadata, it is projected too, not dropped because
  storage doesn't compare it. §6.1 round-trip + edit-continuation proves this.
- **No reverse feed:** the wrapper only reads `core.state()` and writes rows; it never
  re-injects into the core (AD2 no-second-apply-path).

## 2. Backend split — crash-atomicity (§10.1), on EXISTING tables

Anything crash-consistent with message facts shares their backend and transaction.
The messages backend already exists (cross-platform SQLDelight `SlockDatabase`); we
reuse it, never a parallel store.

| Datum | Storage (existing unless noted) | Same txn as facts? |
|---|---|---|
| message facts (+ FK attachments/reactions/saved) | `messageEntity` (+ FK tables) | — |
| timeline order | `messageEntity(serverId,channelId,seq,id)` index (§3) | in-row |
| per-scope watermark | `syncCursorEntity.latestSeq` | **yes** |
| per-scope **epoch** | `syncCursorEntity.epoch` **(new column)** | **yes** |
| auth/session, installation id, flags | MMKV | n/a — independent |

## 3. Schema, key, and the PK migration (AD2 #1)

`syncCursorEntity` already holds the cursor; add one column:
```sql
ALTER TABLE syncCursorEntity ADD COLUMN epoch TEXT;   -- nullable = pre-first-contact
```

**orderKey = server sparse `seq` + `messageId` tie-break** (RULED #4445; a device-local
counter misplaces out-of-order difference-repair and is non-canonical across
devices/TS-KT → T8 red). `seq` is server-stable and edits keep it (edit-stability);
snapshots carry+restore it. Sparse `seq` orders only — never synthesizes dense
successors. Add the tie-break index and use it for restore reads:
```sql
CREATE INDEX messageEntity_channelSeqId ON messageEntity(serverId, channelId, seq, id);
-- restore/timeline read is canonical, server-backed only:
--   SELECT ... WHERE serverId=? AND channelId=? AND seq IS NOT NULL ORDER BY seq, id
-- (NOT the existing selectMessagesForChannel, which sorts by timestampLabel).
```

**PK migration (must actually remove the single-column PK — AD2):** `messageEntity.id`
is currently `TEXT PRIMARY KEY`, so the same `id` under two servers/scopes replaces
each other and `ON CONFLICT(serverId,channelId,id)` collides on `id` first. Two roads,
decided in the first migration PR:

- **(A) contract invariant:** if the server guarantees `messageId` is **globally
  unique** (UUID) — this must be stated explicitly in the RFC/type contract, not
  relied on as an incidental UUID (AD2). Then `id` PK is safe; we only add the
  composite index above and the conflict target is `id`. **Preferred if the invariant
  holds** (no FK churn).
- **(B) composite key migration:** otherwise migrate to a composite PK
  (`serverId, channelId, id`) or an internal surrogate + composite UNIQUE, and update
  the `attachmentEntity`/`messageReactionEntity`/`savedMessageEntity` FK references and
  every `ON CONFLICT` target accordingly.

Road choice is flagged for AD2/contract confirmation before the migration lands; the
note ships both so code doesn't re-open schema after LGTM.

## 4. Atomic apply — one ingest call = one transaction

| Ingest call | The single `SlockDatabase.transaction { }` contains |
|---|---|
| `ingestFrame` (1 event) | 1 `messageEntity` upsert + cursor(`latestSeq`,`epoch`) |
| `ingestDifference` (N events) | **N** projected upserts/tombstones + **one** cursor advance to `toSeq` |
| `ingestSnapshot` (rebaseline) | server-backed clear (§4.4) + rebuild + cursor/epoch set |

### 4.1 No mid-batch commit
One commit per batch → "cursor advanced but half the facts missing" (and reverse) is
unrepresentable.

### 4.2 Idempotency — cursor-level, not a persistence version gate
No per-fact `seq` comparison in storage (§1 projector-not-adjudicator). A crash-
replayed ingest call reproduces the same core post-state → same rows (`ON CONFLICT
DO UPDATE` no-op). Upserts use **`ON CONFLICT ... DO UPDATE`**, never `INSERT OR
REPLACE` (which fires `ON DELETE CASCADE` and silently drops attachments/reactions).

### 4.3 Fail-stop = poisoned core + read barrier (AD2 #3, 赵 complete form)
The wrapper returns ingest-complete only after commit. On commit failure the advanced
core instance is **permanently unreusable (poisoned)**; a barrier holds until the core
is **rebuilt from the last committed DB baseline**, and until then **no observer / UI /
next ingest may read the advanced state** (bare "don't return" is insufficient — a
concurrent `core.state()` would otherwise leak the uncommitted advance). "Cursor leads
facts" is impossible by construction.

### 4.4 FK-safe snapshot rebaseline (AD2 #4)
Rebaseline clears only **server-backed rows (`seq IS NOT NULL`)** for the scope,
**preserving** pending/optimistic (`deliveryState`) rows and outbox projection, then
rebuilds from the snapshot. Deletes are scoped (not a whole-scope wipe) so FK-cascade
never removes still-valid attachments/reactions/saved.

## 5. Restore / warm start (T4–T5)

Per scope: read `syncCursorEntity` → `{latestSeq, epoch}` and the folded state from
`messageEntity` in `(seq, id)` order (server-backed only) → feed the core a
`SyncSnapshot(scopeId, watermark=latestSeq, epoch, state)` via the **existing**
`ingestSnapshot`. Then request difference since `latestSeq`; gap-over-threshold →
`Snapshot`. Cold full-fetch becomes incremental resume. Corrupt DB / epoch mismatch /
`snapshotRequired` → existing `Snapshot` route. Deleting `SlockDatabase` is a defined
state (cold hydrate), never silent hole-skip (T7).

## 6. §9.9 acceptance gates → canonical fixture line (#541) (P3, task #493)

Run on the merged #541 fixture; 王润基 owns pass/fail; I write tests.

- **6.1 Round-trip identity + projector completeness** — `state → persist → restore →
  state'`, `deepEquals`; **plus** an edit/stale-continuation leg: after restore, feed
  the edit events the fixture carries and assert the outcome sequence matches the
  never-persisted run — proves the projector preserved the fold's revision/tombstone
  metadata (AD2 #2). Single serializer reused from the equivalence suite (赵).
- **6.2 Crash-window torn-write** — per ingest call (frame **and** difference batch):
  kill before/after commit / restore; restored state `deepEquals` complete pre- or
  post-call state, never torn. Fault-hook is **synchronous**, wraps the real
  `transaction { }`, fires at a fixed logical step — never suspends *inside* the txn.
- **6.3 Persist/restore equivalence (T8 seed)** — persisted→restored baseline fed
  further frames converges outcome-`deepEqual` with the never-persisted run (KT half
  of the both-ends T8 gate).

## 7. Migration/retirement plan + first checklist (AD2 #1 — most important)

**Single-writer migration.** Route **all** sync-domain `messageEntity` writes through
`SyncPersistenceWrapper` (core = single source of truth); ad-hoc direct writers are
retired or made no-ops deferring to the wrapper — each removal an enumerated diff hunk
AD2 can verify (no long-lived second writer).

**First migration/query/test checklist (must not re-open schema after LGTM):**
1. `syncCursorEntity.epoch` column (forward-only .sqm).
2. PK road (A contract-unique `id`, or B composite/surrogate + FK/conflict-target
   updates) — decided with AD2/contract before landing.
3. `messageEntity_channelSeqId(serverId,channelId,seq,id)` index + canonical
   server-backed restore query (`seq IS NOT NULL ORDER BY seq,id`).
4. all upserts `ON CONFLICT DO UPDATE` (no `INSERT OR REPLACE`).
5. server-backed-only snapshot clear preserving pending/outbox.
6. wrapper single-writer wiring + retirement diff set.
7. tests: round-trip + edit-continuation, crash-window (frame + difference batch),
   persist/restore equivalence, ordered-window recent-N after edit + out-of-order
   repair, persist-fail → poison/barrier → snapshot recovery, migration forward-only.

| Test | Layer | Gate |
|---|---|---|
| round-trip + edit-continuation | commonTest | §9.9 6.1 / AD2 #2 |
| crash-window (frame + difference-batch) | commonTest | §9.9 6.2 |
| persist/restore equivalence | commonTest | §9.9 6.3 / T8 |
| ordered-window stable (edit + out-of-order repair) | commonTest | T6 / orderKey |
| persist-fail → poison/barrier → snapshot recovery | commonTest | §4.3 |
| migration forward-only (epoch + PK road) | commonTest | schema |
| SQLite transaction/index binding | **androidUnitTest + SQLDelight driver** | T9 / AD2 |

Android compile & unit tests = the only hard CI gate (mobile policy); iOS/OHOS
non-blocking + 30-min fix-forward. AD2 reviews the SQLDelight transaction/index +
PK-road implementation on the P1 first commit (task #494).
