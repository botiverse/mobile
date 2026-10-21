# KT Sync-Core Persistence — Implementation Design Note (P1 / task #491)

**Owner:** HanXin · **Contract source of truth:** RFC 043 §9.9 + §10.1 amendment
(botiverse/slock PR #4445, staging `6c6229fcf`) · **§9.9 gate criteria:** 王润基 ·
**Android binding review + compile hard-gate:** Android-Developer-2 (task #494)

> Current V2 status is tracked in `docs/canonical-message-v2-implementation-tracker.md`.
> RFC 043 remains normative; this historical persistence note does not authorize envelope V2 or rollout.

**Rev 3** — folds two review rounds. Rev 1's parallel `sync_fact`/`sync_timeline`
tables are withdrawn (double-truth). Rev 3 reuses the existing SQLDelight
`RaftDatabase` and pins the constraints that table-reuse exposed (composite key,
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
The messages backend already exists (cross-platform SQLDelight `RaftDatabase`); we
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

**PK — RESOLVED (赵+AD2 verified server schema `packages/server/src/db/schema.ts:798`):**
the server's `messages.id` is a single-table UUID `PRIMARY KEY` (`randomUUID`), so
`messageId` is **globally unique by database contract** — being written into RFC 043
§10.1 via slock #4452 (not relied on as an incidental UUID, per AD2). Therefore
server-backed rows cannot collide cross-scope and we **keep `messageEntity.id`
single-column PK** — no high-risk composite-PK/FK/table rebuild. The only real
collision source is **client-generated pending/optimistic ids** (not in the server's
unique domain), fenced by a mandatory namespace:

- local/pending ids MUST carry a namespace prefix that can never overlap a server
  UUID; a **generator test forbids bare UUID / server-id** from the local id
  generator;
- conflict target stays `id`; child FKs (`attachment`/`reaction`/`saved`) unchanged;
- the `(serverId,channelId,seq,id)` index above supplies canonical timeline.

Order-of-magnitude cheaper than a composite-PK rebuild (赵); AD2's composite-PK
blocker is cleared.

## 4. Atomic apply — one ingest call = one transaction

| Ingest call | The single `RaftDatabase.transaction { }` contains |
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

### 4.4 FK-safe snapshot rebaseline (AD2 #4 + round-2)
Deleting a `messageEntity` row FK-cascades (`ON DELETE CASCADE`) to
`attachmentEntity`/`messageReactionEntity`/`savedMessageEntity` — so even a scoped
server-backed (`seq IS NOT NULL`) delete is **not automatically safe**:
- **`savedMessageEntity` is an independent receiver domain** the messages snapshot does
  not rebuild → rebaseline MUST NOT let a message delete adjudicate it. Rule: facts
  referenced by a saved marker are **excluded** from message-window prune/rebaseline
  delete, or the saved relation is **preserved and restored in the same transaction**;
  the messages snapshot never implicitly deletes the saved domain.
- **attachments/reactions** are safe only if the snapshot/projector **rebuilds them in
  the same transaction** as the message it replaces — a projector obligation, not left
  to cascade.
- pending/optimistic (`deliveryState`) + outbox rows are preserved (not server-backed).

## 5. Restore / warm start (T4–T5)

Per scope: read `syncCursorEntity` → `{latestSeq, epoch}` and the folded state from
`messageEntity` in `(seq, id)` order (server-backed only) → feed the core a
`SyncSnapshot(scopeId, watermark=latestSeq, epoch, state)` via the **existing**
`ingestSnapshot`. Then request difference since `latestSeq`; gap-over-threshold →
`Snapshot`. Cold full-fetch becomes incremental resume. Corrupt DB / epoch mismatch /
`snapshotRequired` → existing `Snapshot` route. Deleting `RaftDatabase` is a defined
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
2. keep `id` single-column PK (server messageId globally-unique by contract, slock
   #4452); client/pending ids namespaced (prefix that can't overlap a server UUID) +
   a generator test forbidding bare UUID / server-id in the local id generator.
3. `messageEntity_channelSeqId(serverId,channelId,seq,id)` index + canonical
   server-backed restore query (`seq IS NOT NULL ORDER BY seq,id`).
4. all upserts `ON CONFLICT DO UPDATE` (no `INSERT OR REPLACE`).
5. server-backed-only snapshot clear that **excludes saved-referenced facts** (or
   preserves+restores the saved relation same-txn) and preserves pending/outbox; the
   messages snapshot never deletes the independent saved domain; deleted messages'
   attachments/reactions are rebuilt same-txn by the projector.
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
| snapshot rebaseline preserves saved marker + pending row | commonTest | §4.4 |
| prune skips saved-referenced facts; deleted-msg attachments/reactions rebuilt | commonTest | §4.4 |
| migration forward-only (epoch / index / client-id namespace) | commonTest | schema |
| SQLite transaction/index binding | **androidUnitTest + SQLDelight driver** | T9 / AD2 |

Android compile & unit tests = the only hard CI gate (mobile policy); iOS
non-blocking + 30-min fix-forward. AD2 reviews the SQLDelight transaction/index +
PK-road implementation on the P1 first commit (task #494).
