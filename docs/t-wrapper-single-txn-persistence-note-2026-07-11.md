# T-wrapper — single-transaction fold persistence (SQLite), implementation note

> Naming note (artin): the mobile store is **pure SQLite / relational**, NOT a key-value store. "KV"
> appears below ONLY as the name of the sync-core's *backend-neutral* storage INTERFACE (RFC 043 §10) —
> the abstraction that lets web bind IndexedDB and mobile bind SQLite to one shared core. The mobile binding
> is form A: extend the existing relational tables (`messageEntity` + a `canonicalFactJson` column,
> `syncCursorEntity`). The literal KV-table option (a `localSnapshotEntity` blob store) was rejected.

Status: design note for the P1 persistence layer (RFC 043 §7.6 / §9.9), first increment = **one-txn gate**.
Owner: HanXin. Review: LiBai + AD2. Line: `fold → app-bind → wrapper` (third segment; fold + app-bind
landed on main 2026-07-11).

This note pins the implementation-level contract so the RED tests can be written first (TDD). It does not
add a migration — schema rides the pre-release `slock_cache_v4.db` fresh baseline (coordinated with #706).

## 1. Scope of the wrapper

The wrapper is the **host persistence adapter** that takes one applied fold frame (the output of
`messagesSyncDomain()` for a scope) and durably commits it. It is NOT the fold (that is pure, already on
main) and NOT the projection (app-bind, already on main). It is the boundary that makes the fold's
post-state survive process death, deterministically.

Four gates (LiBai's framing), delivered incrementally:

1. **one-txn** — atomic single-transaction apply (this increment).
2. **projector** — windowed reads off the timeline index (ordered-window gate).
3. **poison** — isolate a fact that cannot be applied/serialized without wedging the scope.
4. **retention** — bounded pruning of facts + index in the same atomic discipline.

## 2. The one-txn contract (RFC 043 §9.9, transaction-count invariant)

> **One ingest CALL = exactly one storage transaction.** When the commit count is 1, no torn window
> exists by construction — stronger and cheaper than any kill enumeration.

The unit of atomicity is one ingest call, NOT one wire frame (赵 note-review must-fix). Three shapes, each
exactly one transaction:
- **realtime frame** — 1 fact + cursor.
- **difference response** — N facts + cursor (never N per-fact transactions — that reintroduces the tear
  "cursor unmoved but first half of facts landed").
- **snapshot rebaseline** — clear the scope + rebuild from the snapshot facts + cursor/epoch. A torn
  rebaseline (scope emptied but not rebuilt) is the exact disease the single transaction prevents; there is
  no mid-batch commit point for a fault to land on, by construction.

**Corollary (pinned, §9.9 + §10 discriminator):** the message facts AND their scope cursor + epoch AND the
timeline index maintenance MUST commit in **one transaction on one backend**. Splitting any of them across
backends (e.g. facts in SQLite, watermark in MMKV) reintroduces the torn window and fails the count
invariant by construction. Only truly independent small values (auth/session, installation id, flags) may
live on a separate fast KV.

What a single applied frame writes, together, in one SQLite transaction:

- **facts** — `messageEntity` (+ `attachmentEntity` / `messageReactionEntity` child rows) upserted for the
  frame's canonical rows.
- **cursor + epoch** — `syncCursorEntity` (`latestSeq` / `oldest/newestLoadedSeq` / `epoch`) for the scope.
- **timeline index** — the `messageEntity_channelSeq*` ordering maintenance (fact key ≠ timeline key, AD2):
  the composite fact key locates facts; the `(serverId, scope, orderKey=seq, messageId)` index is what
  defines the stable timeline window. Index maintenance joins the SAME transaction — a separate index
  transaction is a torn window by another name.

## 3. Adapter shape (core INTERFACE = KV-with-versioned-values, §10 — RULED HanXin 2026-07-08; mobile BINDING = SQLite)

The backend-neutral interface stays narrow; SQLite is the binding where the transactional/scan requirements
apply (the messages domain). The wrapper exposes a single atomic entry point plus a fault hook:

```kotlin
/** One applied fold frame for a scope: the exact set of rows that must commit atomically. */
data class FoldPersistFrame(
    val scope: SyncScopeKey,                 // serverId + scopeId + scopeKind
    val facts: List<CanonicalMessageRow>,    // upserts (may be empty for a cursor-only advance)
    val cursor: SyncCursorState,             // latestSeq / loaded bounds / epoch
    val prunedFactIds: List<MessageId> = emptyList(), // retention gate (empty in the one-txn increment)
)

interface FoldPersistenceAdapter {
    /** Commit [frame] in EXACTLY ONE backend transaction (facts + cursor + epoch + timeline index). */
    suspend fun applyFrame(frame: FoldPersistFrame)

    /** Restore the durable post-state for [scope] (facts window + cursor + epoch). */
    suspend fun restore(scope: SyncScopeKey): RestoredFoldState
}

/**
 * Deterministic fault injection for the crash-window tests — NEVER wall-clock. The kill instant is the
 * test's entire semantics: BeforeCommit tears before the single commit, AfterCommit after it.
 */
enum class PersistFaultPoint { None, BeforeCommit, AfterCommit }
fun interface PersistFaultHook { fun shouldKillAt(point: PersistFaultPoint): Boolean }
```

The concrete SQLite adapter wraps the SqlDelight `transaction { }` once per `applyFrame`, consulting the
`PersistFaultHook` at `BeforeCommit` / `AfterCommit` and throwing a `PersistKilled` to model process death
at exactly that boundary. A **CommitBoundaryRecorder** test double counts `transaction { }` entries/exits so
the count invariant is asserted directly.

## 4. Schema + migration/retirement plan (the P1-critical section — 赵 ruling 2026-07-10)

This is the most important design decision: a parallel `sync_fact` table living alongside the existing
`messageEntity`/`syncCursorEntity` (which already run on all three platforms) would be **data-layer dual
truth** — the exact disease this whole line exists to kill. So:

- **EXTEND, do not fork.** The T-wrapper writes the fold post-state into the **existing**
  `messageEntity` (+ child rows) and `syncCursorEntity` (already carries `epoch`), and maintains the
  existing `messageEntity_channelSeq*` timeline index. There is no new `sync_fact` keyspace. The fold
  becomes the **single writer** of these rows for synced scopes.
- **orderKey = sparse server seq + messageId tie-break** (locked; NOT insertion order). Insertion order
  misplaces out-of-order difference repair and is non-canonical cross-platform (a guaranteed T8 red). The
  timeline index key is `(serverId, scope, orderKey=server seq, messageId)`; edits keep the original seq, so
  the window is stable and the ordering is identical on web and KMP for free.
- **Retirement path.** The existing ad-hoc realtime write paths (RealtimeEventMapper → store) are the
  incumbent writer. The rollout is: fold **shadow** (already live, `sync_core_messages_v0`) validates the
  fold produces the same rows → **sole-apply** makes the fold the only writer → the incumbent ad-hoc write
  paths are then retired. No period where both write the same rows as independent truths.
- **No migration pre-release.** Any schema delta rides the `slock_cache_v4.db` fresh baseline (#706 bumped
  it for `attachmentEntity.sizeBytes`); the whole cache is scrapped+rebuilt on schema change until the first
  published release, then `.sqm` migrations begin. The one-txn increment needs no new column.

## 5. Fail-stop (ruling 3, 2026-07-10): commit-before-complete

`applyFrame` returns success ONLY after the single transaction has committed. A commit failure throws out of
`applyFrame`, so the caller never marks the ingest complete and never advances its in-memory cursor past a
fact set that did not durably land. "Cursor ahead of facts" is thus **impossible by construction** — the
same philosophy as the transaction-count invariant (never rely on a write-ordering discipline anyone can
silently break). The `FoldPersistTransactionRunner` boundary is where this holds.

## 6. RED tests (TDD — written first, fail until the adapter lands)

Named per §9.9; all kill injection through the deterministic `PersistFaultHook`, never timing:

1. **`oneFrameApplyIsExactlyOneTransaction`** (primary count invariant): the counting runner observes
   `applyFrame(factsCursorEpoch)` and asserts commit count == 1.
2. **`differenceBatchAppliesNFactsAndCursorInOneTransaction`**: N facts + cursor = one transaction (赵's
   batch leg — the frame invariant generalized to the difference ingest call).
3. **`snapshotRebaselineClearsAndRebuildsInOneTransaction`** + **`...KillBeforeCommitKeepsCompletePreRebaselineState`**:
   clear + rebuild + cursor/epoch in one transaction; a kill-before-commit leaves the complete
   pre-rebaseline state (never an emptied scope).
4. **`killBeforeCommitRestoresCompletePreFrameState`**: fault at `BeforeCommit` ⇒ `restore` deepEquals the
   complete pre-frame state (no partial facts, cursor/epoch unmoved) — never a torn mix.
5. **`killAfterCommitRestoresCompletePostFrameState`**: fault at `AfterCommit` ⇒ `restore` deepEquals the
   complete post-frame state (all facts + advanced cursor/epoch).
6. **`stateRoundTripIdentity`** (§9.9): state → applyFrame → restore → state′ deepEqual.
7. **`persistRestoreContinuationEquivalence`** (canonical fixture line #541): a persisted-then-restored
   baseline fed back through the fold converges outcome-deepEqual with the never-persisted run — round-trip
   identity extended to identical continuation behavior.

The count invariant (#1) is the primary assertion; the two kill halves (#2/#3) are the behavioral twins of
the rollback disease. v1 is pinned to "exactly one transaction per apply"; any future multi-transaction
apply must amend §9.9 and cover every adjacent boundary (small-N exhaustive) in the same change.

## 7. Sequencing

- First increment = **one-txn** (this note + RED tests + adapter). Branch `hanxin/t-wrapper-persist`.
- Does not block #706; schema baseline is whoever lands the v4 bump first (coordinated with MingQi).
- projector / poison / retention gates follow, each with its own named gate test.
