# KT Sync-Core Persistence — Implementation Design Note (P1 / task #491)

**Owner:** HanXin · **Contract source of truth:** RFC 043 §9.9 + §10.1 amendment
(botiverse/slock PR #4445, staging `6c6229fcf`) · **§9.9 gate criteria:** 王润基 ·
**Android binding review + compile hard-gate:** Android-Developer-2 (task #494)

**Rev 2** — incorporates the #4445 final read (赵: per-*ingest-call* transaction
scope) and AD2's T9 doc review (existing `SlockDatabase` is a hard constraint;
observe-outcome is insufficient; persist fail-stop; server-stable orderKey). Rev 1's
parallel `sync_fact`/`sync_timeline` tables are **withdrawn** — they were the
data-layer double-truth this note now explicitly avoids.

Scope of v1: **messages domain, KT only.** SyncLog (input-stream persistence) is the
§10-open-question-3 v2 candidate, out of scope. Web IndexedDB adapter is T10 (Aiden,
task #145).

---

## 1. Where persistence lives — and the single-writer wrapper

`SyncCore` is a pure deterministic fold (`SyncCoreTypes.kt`: no transport, no
persistence, no clock). It stays that way. But persistence **cannot** be driven by
the ingest *outcome* alone: `MaxAdvanced(scope, seq)` (sparse) and
`ingestDifference` (N events → final seq) do not carry the facts to write, and
`Applied(scope, seq)` must not be reverse-engineered into facts (AD2). So the host
layer is a **single apply-wrapper**, the one path through which sync ingest happens:

```
callers ─▶ SyncPersistenceWrapper.ingestX(input) {           // the ONLY host apply path
    val (outcome, delta) = core.ingestX(input)                // core stays pure
    db.transaction {                                          // ONE txn per ingest call
        applyDelta(delta)        // messageEntity upserts/tombstones for THIS call
        advanceCursor(outcome)   // syncCursorEntity latestSeq + epoch
    }                                                          // commit
    return outcome               // returns only AFTER commit succeeds (fail-stop, §4.3)
}
```

The wrapper holds **input + outcome + canonical post-delta** together — it can
persist `MaxAdvanced` and difference batches that the bare outcome cannot express.
The core is never fed backward: the wrapper only *reads* `core` output and *writes*
storage (AD2's no-second-apply-path — the coordinator must never re-inject into the
core). This is the mechanical form of the all-day guardrail: **UI/cache derive from
core applied state; storage is a projection, never a second source of truth.**

## 2. Backend split — decided by crash-atomicity (§10.1), on EXISTING tables

Per the #4445 discriminator (HanXin): anything that must be crash-consistent with
message facts lives on the same backend in the same transaction as those facts.
Crucially, the messages backend already exists — cross-platform SQLDelight
`SlockDatabase` (Android/iOS/OHOS drivers wired). **We reuse it; we do not add a
parallel store** (that is the double-truth):

| Datum | Storage (existing unless noted) | Same txn as facts? |
|---|---|---|
| message facts | `messageEntity` | — |
| timeline order | `messageEntity_channelSeq (serverId, channelId, seq)` index | (free, in-row) |
| per-scope watermark | `syncCursorEntity.latestSeq` | **yes** |
| per-scope **epoch** | `syncCursorEntity.epoch` **(new column, §3)** | **yes** |
| attachments/reactions/saved | existing FK-`messageEntity` tables | yes (same call) |
| auth/session token, installation id, flags | MMKV | n/a — independent |

"Watermark-on-MMKV made safe by write ordering" stays rejected (#4445). The
message+cursor+epoch commit is one SQLite transaction on `SlockDatabase`; MMKV holds
only data with no cross-atomicity to messages.

## 3. Schema — reuse + one migration (no new fact/timeline tables)

`messageEntity` (id PK = messageId; serverId, channelId, **seq**, body,
messageType, metadataJson, deliveryState, parentMessageId, …) already **is** the
fact table, and `messageEntity_channelSeq(serverId, channelId, seq)` already **is**
the ordered timeline. `syncCursorEntity(scopeId, serverId, latestSeq,
oldest/newestLoadedSeq, …)` already **is** the cursor. The only additions:

```sql
-- SQLDelight migration N.sqm (versioned; drivers already exist on all 3 platforms):
ALTER TABLE syncCursorEntity ADD COLUMN epoch TEXT;   -- per-scope epoch (nullable = pre-first-contact)
-- messageEntity.seq already carries the canonical ordering/version gate; no BLOB column,
-- no second fact table. (If a domain needs a body-hash for §9.9 round-trip, add a
-- nullable column here rather than a parallel row — reviewed with AD2.)
```

**orderKey = server sparse `seq` + `messageId` (id) tie-break** — RULED (#4445; 赵
withdrew the insertion-counter idea: a device-local counter misplaces out-of-order
difference-repaired messages and is non-canonical across devices/TS-KT → T8 red).
`seq` is server-stable, edits keep the original `seq` (edit-stability for free), and
snapshots carry+restore the same `seq`. Sparse `seq` (holes allowed) is used for
ordering/resume only — never to synthesize dense successors.

## 4. Atomic apply — one ingest call = one transaction

Per the #4445 final read (赵): the transaction-count invariant scopes to the
**ingest call**, not the frame.

| Ingest call | The single `SlockDatabase.transaction { }` contains |
|---|---|
| `ingestFrame` (1 event) | 1 `messageEntity` upsert + cursor(`latestSeq`,`epoch`) |
| `ingestDifference` (N events) | **N** upserts/tombstones (+FK rows) + **one** cursor advance to `toSeq` |
| `ingestSnapshot` (rebaseline) | atomic clear of scope's `messageEntity` rows + rebuild + cursor/epoch set |

### 4.1 No mid-batch commit
A difference batch has exactly one commit → "cursor advanced but half the batch's
facts missing" (and reverse) are unrepresentable, not merely untested.

### 4.2 Idempotent / version-gated
Upserts are gated by `seq` (canonical revision gate — **not** an RFC 041 receiver
version): skip if incoming `seq` ≤ stored for that id. Crash replay of a whole
ingest call is a no-op.

### 4.3 Persist fail-stop (AD2 #3, 赵-preferred form)
The wrapper **does not return ingest-complete until the transaction commits.** If the
commit fails, the call fails; no subsequent ingest/publish proceeds, and the scope
enters observable snapshot/restart recovery (`SyncSnapshotReason.SnapshotRequired`).
This makes "cursor leads facts" impossible **by construction** — same philosophy as
the transaction-count invariant. §7 carries the sequence diagram + failure matrix.

## 5. Restore / warm start (T4–T5)

On launch, per scope: read `syncCursorEntity` → `{latestSeq, epoch}` and the folded
state from `messageEntity` in `messageEntity_channelSeq` order → feed the core a
`SyncSnapshot(scopeId, watermark=latestSeq, epoch, state)` via the **existing**
`ingestSnapshot` (reuse, not a new core entrypoint). Then request **difference since
`latestSeq`** (existing `SyncRequestDescription.Difference`); a gap beyond threshold
escalates to `Snapshot`. Cold full-fetch becomes incremental resume.

Corrupt DB / `epoch` mismatch / difference `snapshotRequired` → the core already
routes to a `Snapshot` request. **Deleting `SlockDatabase` is a defined state**
(cold snapshot hydrate), never silent hole-skipping (T7).

## 6. §9.9 acceptance gates → canonical fixture line (#541) (P3, task #493)

All run on the merged #541 canonical fixture. 王润基 owns pass/fail; I write tests.

- **6.1 Round-trip identity** — `state → persist → restore → state'`,
  `deepEquals` after replaying the fixture (single serializer reused from the
  equivalence suite — 赵: one serializer = §9.9 cross-platform comparability free).
- **6.2 Crash-window torn-write (deterministic fault-hook)** — for each ingest call
  (frame **and** a multi-event difference batch): kill before commit (A) / after (B)
  / restore; restored state `deepEquals` the complete pre-call **or** post-call state,
  never torn. The fault-hook is a **synchronous** deterministic hook wrapping the
  real `transaction { }` (fires at a fixed logical step from the fixture) — it does
  **not** suspend *inside* the SQLite transaction.
- **6.3 Persist/restore equivalence (T8 joint-gate seed)** — persisted→restored
  baseline fed further frames converges outcome-`deepEqual` with the never-persisted
  run. KT half of the both-ends T8 release gate (TS/KT deepEqual, 赵/王润基 criteria).

## 7. Migration/retirement plan + test matrix (P1's most important section — AD2 #1)

**Single-writer migration.** Today various call sites write `messageEntity` directly.
v1 routes **all sync-domain message writes through `SyncPersistenceWrapper`** so the
core is the single source of truth; ad-hoc direct writes that bypass the core are
retired (or, transitionally, made no-ops that defer to the wrapper). Sequenced:

1. add `syncCursorEntity.epoch` (migration N.sqm);
2. land `SyncPersistenceWrapper` + wire the sync path (frame/difference/snapshot)
   through it, writing `messageEntity` + `syncCursorEntity` in one txn/call;
3. redirect existing message read paths to project from `messageEntity` (already the
   store — mostly a no-op) and **remove** any writer that mutates `messageEntity`
   outside the wrapper (enumerated in the PR; each removal is a diff hunk AD2 can
   verify — no long-lived second writer).

`outboxMessageEntity` (sends) and `localSnapshotEntity` (snapshots) already exist and
are **out of v1 scope** (outbox = the SyncLog-v2 / optimistic-send concern).

| Test | Layer | Gate |
|---|---|---|
| round-trip identity | commonTest | §9.9 6.1 |
| crash-window torn-write, frame **and** difference-batch (hook A/B) | commonTest | §9.9 6.2 |
| persist/restore equivalence | commonTest | §9.9 6.3 / T8 |
| ordered-window recent-N stable after edit + out-of-order difference repair | commonTest | T6 / orderKey |
| persist-fail → ingest not completed → snapshot recovery | commonTest | §4.3 |
| SQLDelight migration N.sqm (epoch column) forward-only | commonTest | schema |
| Android SQLite transaction/index binding | **androidUnitTest + SQLDelight driver** (not instrumentation) | T9 / AD2 |

Android compile & unit tests are the only hard CI gate (mobile policy); iOS/OHOS
non-blocking + 30-min fix-forward. AD2 reviews the SQLDelight transaction/index
implementation on the P1 first commit (task #494).

---

**Finalized (was "open") per AD2 review:** backend = existing SQLDelight
`SlockDatabase` (not raw); orderKey = server `seq` + `id`; fact store = `messageEntity`
(no BLOB table); version gate = `seq`. **Still for AD2 code review:** exact retirement
diff set (which current writers of `messageEntity` fold into the wrapper), and whether
`state` serialization for 6.1 reuses the equivalence-suite serializer directly.
