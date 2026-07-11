# T-wrapper — single-transaction fold persistence (KV-on-SQLite), implementation note

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

> **One frame apply = exactly one storage transaction.** When the commit count is 1, no torn window
> exists by construction — stronger and cheaper than any kill enumeration.

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

## 3. Adapter shape (KV-with-versioned-values, §10 — RULED HanXin 2026-07-08)

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

## 4. Schema (no migration — v4 fresh baseline)

Reuses the #603 foundation already in `SlockDatabase.sq`: `messageEntity` (+ child rows) for facts,
`syncCursorEntity` (already carries `epoch`) for cursor+epoch, and the `messageEntity_channelSeq*` indexes
for the timeline. No new table is required for the one-txn increment. Per the pre-release "扬了重来"
doctrine (unpublished cache = pure server mirror, scrap = zero loss), any schema delta rides the
`slock_cache_v4.db` fresh baseline (bumped by #706 for `attachmentEntity.sizeBytes`) — no `.sqm` migration
until the first published release. The generic `localSnapshotEntity` (scope/id/value KV) remains available
for versioned-value snapshots the projector/retention gates may need.

## 5. RED tests (TDD — written first, fail until the adapter lands)

Named per §9.9; all kill injection through the deterministic `PersistFaultHook`, never timing:

1. **`oneFrameApplyIsExactlyOneTransaction`** (primary count invariant): `CommitBoundaryRecorder` observes
   `applyFrame(frameWithFactsCursorEpoch)` and asserts commit count == 1.
2. **`killBeforeCommitRestoresCompletePreFrameState`**: fault at `BeforeCommit` ⇒ `restore` deepEquals the
   complete pre-frame state (no partial facts, cursor/epoch unmoved) — never a torn mix.
3. **`killAfterCommitRestoresCompletePostFrameState`**: fault at `AfterCommit` ⇒ `restore` deepEquals the
   complete post-frame state (all facts + advanced cursor/epoch).
4. **`stateRoundTripIdentity`** (§9.9): state → applyFrame → restore → state′ deepEqual.
5. **`persistRestoreContinuationEquivalence`** (canonical fixture line #541): a persisted-then-restored
   baseline fed back through the fold converges outcome-deepEqual with the never-persisted run — round-trip
   identity extended to identical continuation behavior.

The count invariant (#1) is the primary assertion; the two kill halves (#2/#3) are the behavioral twins of
the rollback disease. v1 is pinned to "exactly one transaction per apply"; any future multi-transaction
apply must amend §9.9 and cover every adjacent boundary (small-N exhaustive) in the same change.

## 6. Sequencing

- First increment = **one-txn** (this note + RED tests + adapter). Branch `hanxin/t-wrapper-persist`.
- Does not block #706; schema baseline is whoever lands the v4 bump first (coordinated with MingQi).
- projector / poison / retention gates follow, each with its own named gate test.
