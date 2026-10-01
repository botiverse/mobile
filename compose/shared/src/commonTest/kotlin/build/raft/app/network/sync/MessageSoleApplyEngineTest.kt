/*
 * Sole-apply slice-2 stage A — exhaustive gate tests for the pure per-scope apply-mode engine
 * (reduceSoleApply). One test per platform-review gate, named after it. The engine is DORMANT in
 * production (see gate 8: soleApplyLaneEligible is structurally false while
 * CANONICAL_FACT_SOLE_APPLY_ELIGIBLE is false); these tests are the whole activation surface.
 */
package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext

class MessageSoleApplyEngineTest {

    /** Drives the pure reducer and records every emitted command, in order. */
    private class Harness(capacity: Int = 4) {
        val config = SoleApplyConfig(holdQueueCapacity = capacity)
        var state = SoleApplyScopeState()
        val commands = mutableListOf<SoleApplyCommand>()

        fun send(input: SoleApplyInput): List<SoleApplyCommand> {
            val transition = reduceSoleApply(state, input, config)
            state = transition.state
            commands += transition.commands
            return transition.commands
        }

        fun activateToFold() {
            send(SoleApplyInput.ActivationRequested)
            send(SoleApplyInput.WarmStartSucceeded)
            check(state.mode == SoleApplyMode.FOLD)
        }

        fun fanOutSeqs(): List<Long> = commands.filterIsInstance<SoleApplyCommand.FanOut>().map { it.seq }
        fun rebaselineRequestCount(): Int = commands.count { it is SoleApplyCommand.RequestRebaseline }
        fun dropCount(): Int = commands.count { it is SoleApplyCommand.DropToLegacyAtBarrier }
        fun outstandingRebaselineGeneration(): Long =
            requireNotNull(state.rebaselineGeneration) { "no rebaseline request is outstanding" }
        fun rebaselineSucceeded(generation: Long = outstandingRebaselineGeneration()): List<SoleApplyCommand> =
            send(SoleApplyInput.RebaselineSucceeded(generation))
        fun rebaselineFailed(generation: Long = outstandingRebaselineGeneration()): List<SoleApplyCommand> =
            send(SoleApplyInput.RebaselineFailed(generation))
    }

    // ── gate 1: queue overflow → apply-failure semantics ────────────────────────────────────────────

    @Test
    fun gate1_queueOverflowIsApplyFailure_holdPlusSingleRebaseline_neverLegacyBypass() {
        val h = Harness(capacity = 2)
        h.activateToFold()
        h.send(SoleApplyInput.FrameArrived(1))
        h.send(SoleApplyInput.FrameArrived(2))
        // Queue is full — the third frame overflows.
        val overflow = h.send(SoleApplyInput.FrameArrived(3))

        assertTrue(SoleApplyCommand.Hold(3) in overflow, "the overflowing frame must HOLD, never bypass")
        assertTrue(overflow.any { it is SoleApplyCommand.RequestRebaseline }, "overflow is an apply failure: rebaseline")
        assertTrue(overflow.any { it is SoleApplyCommand.Diagnostic }, "overflow must emit a diagnostic")
        assertTrue(h.state.applyBlocked, "overflow blocks the scope until a rebaseline succeeds")

        // Further frames while blocked: still dark, still exactly ONE outstanding rebaseline.
        val next = h.send(SoleApplyInput.FrameArrived(4))
        assertEquals(listOf<SoleApplyCommand>(SoleApplyCommand.Hold(4)), next)
        assertEquals(1, h.rebaselineRequestCount(), "single-flight: no second request while outstanding")
        assertEquals(emptyList(), h.fanOutSeqs(), "nothing is ever fanned out around an overflow")
        assertEquals(SoleApplyMode.FOLD, h.state.mode, "overflow NEVER drops to legacy")
        assertEquals(0, h.dropCount())
    }

    // ── gate 2: post-commit-failure frames never leak ───────────────────────────────────────────────

    @Test
    fun gate2_postCommitFailureFramesNeverLeak_untilRebaselineSucceeds() {
        val h = Harness()
        h.activateToFold()
        h.send(SoleApplyInput.FrameArrived(1))
        h.send(SoleApplyInput.CommitFailed(1))
        h.send(SoleApplyInput.FrameArrived(2))
        h.send(SoleApplyInput.FrameArrived(3))
        // Even a (stale/racy) success result after the failure must not leak a fan-out.
        h.send(SoleApplyInput.CommitSucceeded(2))

        assertEquals(emptyList(), h.fanOutSeqs(), "zero FanOut after a CommitFailed, for ANY frame")
        assertEquals(0, h.dropCount(), "failure never degrades to legacy")

        // The first snapshot flight was dirtied by seq2/3 arriving after the request, so its matching
        // completion installs a baseline but MUST rearm another flight instead of unlocking.
        h.rebaselineSucceeded()
        assertTrue(h.state.applyBlocked)
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, h.state.mode)
        // A clean retry snapshot supersedes everything held — still no fan-out of 1..3.
        h.rebaselineSucceeded()
        assertEquals(emptyList(), h.fanOutSeqs(), "held frames are superseded by the rebaseline, not replayed")
        assertEquals(SoleApplyMode.FOLD, h.state.mode)
        assertFalse(h.state.applyBlocked)

        // The scope is healthy again: a fresh frame flows commit-first as normal.
        h.send(SoleApplyInput.FrameArrived(4))
        h.send(SoleApplyInput.CommitSucceeded(4))
        assertEquals(listOf(4L), h.fanOutSeqs())
    }

    // ── gate 3: rebaseline single-flight ────────────────────────────────────────────────────────────

    @Test
    fun gate3_rebaselineSingleFlight_multipleFailuresWhileOutstanding_exactlyOneRequest() {
        val h = Harness()
        h.activateToFold()
        h.send(SoleApplyInput.FrameArrived(1))
        h.send(SoleApplyInput.CommitFailed(1))
        // More failures while the first request is outstanding — repeated failure escalates the mode
        // but must NOT re-emit the request.
        h.send(SoleApplyInput.FrameArrived(2))
        h.send(SoleApplyInput.CommitFailed(2))
        h.send(SoleApplyInput.CommitFailed(2))
        assertEquals(1, h.rebaselineRequestCount(), "exactly one RequestRebaseline while one is outstanding")

        // Only a RESOLUTION re-arms the flight slot: a failed rebaseline may re-emit exactly once.
        val firstGeneration = h.outstandingRebaselineGeneration()
        h.rebaselineFailed(firstGeneration)
        assertEquals(2, h.rebaselineRequestCount(), "re-emit is allowed only after the previous one resolves")
        assertTrue(h.state.rebaselineInFlight)
        assertTrue(h.outstandingRebaselineGeneration() > firstGeneration)
    }

    // ── gate 4: cursor-never-crosses (property-style) ───────────────────────────────────────────────

    @Test
    fun gate4_cursorNeverCrosses_noFanOutWithoutPriorCommitSucceeded() {
        // Deterministic LCG so the sequence is reproducible; several seeds for breadth.
        for (seed in listOf(1L, 7L, 42L, 20260713L)) {
            var rng = seed
            fun nextInt(bound: Int): Int {
                rng = (rng * 6364136223846793005L + 1442695040888963407L)
                return ((rng ushr 33) % bound).toInt()
            }

            val h = Harness(capacity = 3)
            h.activateToFold()
            var nextSeq = 1L
            val committed = mutableSetOf<Long>()

            repeat(2000) {
                val before = h.commands.size
                when (nextInt(6)) {
                    0, 1 -> h.send(SoleApplyInput.FrameArrived(nextSeq++))
                    2 -> {
                        // A commit result for the head, a held non-head, or an arbitrary seq.
                        val seq = when (nextInt(3)) {
                            0 -> h.state.heldSeqs.firstOrNull() ?: nextInt(20).toLong()
                            1 -> h.state.heldSeqs.lastOrNull() ?: nextInt(20).toLong()
                            else -> nextInt(20).toLong()
                        }
                        committed += seq
                        h.send(SoleApplyInput.CommitSucceeded(seq))
                    }
                    3 -> h.send(SoleApplyInput.CommitFailed(h.state.heldSeqs.firstOrNull() ?: 0L))
                    4 -> {
                        val generation = h.state.rebaselineGeneration ?: nextInt(20).toLong()
                        h.rebaselineSucceeded(generation)
                    }
                    else -> {
                        val generation = h.state.rebaselineGeneration ?: nextInt(20).toLong()
                        h.rebaselineFailed(generation)
                    }
                }
                // THE invariant: in FOLD/BROKEN a FanOut may exist only for a seq whose
                // CommitSucceeded has already been fed to the engine.
                h.commands.drop(before).filterIsInstance<SoleApplyCommand.FanOut>().forEach { fanOut ->
                    assertTrue(
                        fanOut.seq in committed,
                        "seed=$seed: FanOut(${fanOut.seq}) emitted without a prior CommitSucceeded",
                    )
                }
            }
            // And the engine never left the fold family (no legacy release in this input universe).
            assertTrue(h.state.mode == SoleApplyMode.FOLD || h.state.mode == SoleApplyMode.BROKEN_CANONICAL)
            assertEquals(0, h.dropCount())
        }
    }

    // ── gate 5: EligibilityRevoked from FOLD is barriered on rebaseline success ─────────────────────

    @Test
    fun gate5_eligibilityRevokedFromFold_noDropUntilRebaselineSucceeds_failureKeepsHolding() {
        val h = Harness()
        h.activateToFold()
        h.send(SoleApplyInput.FrameArrived(1))
        val revoke = h.send(SoleApplyInput.EligibilityRevoked)

        assertTrue(revoke.any { it is SoleApplyCommand.RequestRebaseline }, "revoke requires a rebaseline first")
        assertEquals(0, h.dropCount(), "no DropToLegacyAtBarrier before the rebaseline succeeds")

        // Rebaseline keeps failing: stay held + diagnostic — availability never crosses missing facts.
        h.rebaselineFailed()
        assertEquals(0, h.dropCount())
        val held = h.send(SoleApplyInput.FrameArrived(2))
        assertEquals(listOf<SoleApplyCommand>(SoleApplyCommand.Hold(2)), held)
        h.rebaselineFailed()
        assertEquals(0, h.dropCount())
        assertEquals(emptyList(), h.fanOutSeqs(), "revoking scope stays dark")

        // Only a SUCCESSFUL rebaseline passes the barrier.
        val resolved = h.rebaselineSucceeded()
        assertTrue(SoleApplyCommand.DropToLegacyAtBarrier in resolved)
        assertEquals(SoleApplyMode.LEGACY, h.state.mode)
        assertFalse(h.state.revokePending)
        assertEquals(emptyList(), h.fanOutSeqs(), "held frames are superseded at the barrier, never released")
    }

    // ── gate 6: switch race — frames during ACTIVATING ──────────────────────────────────────────────

    @Test
    fun gate6a_framesDuringActivating_replayInOrderThroughCommitPathAfterWarmStartSucceeded() {
        val h = Harness()
        h.send(SoleApplyInput.ActivationRequested)
        val holds = listOf(1L, 2L, 3L).flatMap { h.send(SoleApplyInput.FrameArrived(it)) }
        assertEquals(
            listOf<SoleApplyCommand>(SoleApplyCommand.Hold(1), SoleApplyCommand.Hold(2), SoleApplyCommand.Hold(3)),
            holds,
            "frames arriving during ACTIVATING are queued, not fanned out",
        )

        h.send(SoleApplyInput.WarmStartSucceeded)
        assertEquals(SoleApplyMode.FOLD, h.state.mode)
        assertEquals(listOf(1L, 2L, 3L), h.state.heldSeqs, "queue intact and ordered for the replay")
        assertEquals(emptyList(), h.fanOutSeqs(), "warm start alone fans nothing out — commit-first")

        // Replay: each queued frame fans out only on ITS commit, in exact arrival order.
        h.send(SoleApplyInput.CommitSucceeded(1))
        h.send(SoleApplyInput.CommitSucceeded(2))
        h.send(SoleApplyInput.CommitSucceeded(3))
        assertEquals(listOf(1L, 2L, 3L), h.fanOutSeqs(), "no drop, no double, exact seq order")
    }

    @Test
    fun gate6b_warmStartFailed_releasesQueuedFramesToLegacyInOrder() {
        val h = Harness()
        h.send(SoleApplyInput.ActivationRequested)
        h.send(SoleApplyInput.FrameArrived(1))
        h.send(SoleApplyInput.FrameArrived(2))
        h.send(SoleApplyInput.FrameArrived(3))
        val failed = h.send(SoleApplyInput.WarmStartFailed)

        // Activation never happened and no fold state was shown — the one legal legacy release.
        assertEquals(
            listOf(1L, 2L, 3L),
            failed.filterIsInstance<SoleApplyCommand.FanOut>().map { it.seq },
            "queued frames release to legacy fan-out IN ORDER",
        )
        assertTrue(failed.any { it is SoleApplyCommand.Diagnostic })
        assertEquals(SoleApplyMode.LEGACY, h.state.mode)
        assertEquals(emptyList(), h.state.heldSeqs)

        // Back in LEGACY, frames pass straight through.
        assertEquals(listOf<SoleApplyCommand>(SoleApplyCommand.FanOut(4)), h.send(SoleApplyInput.FrameArrived(4)))
    }

    // ── gate 7: BROKEN_CANONICAL keeps sinks dark ───────────────────────────────────────────────────

    @Test
    fun gate7_brokenCanonicalKeepsSinksDark_neverReleasesToLegacy() {
        val h = Harness()
        h.activateToFold()
        h.send(SoleApplyInput.FrameArrived(1))
        h.send(SoleApplyInput.CommitFailed(1))
        h.rebaselineFailed()
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, h.state.mode)

        val requestsSoFar = h.rebaselineRequestCount()
        h.send(SoleApplyInput.FrameArrived(2))
        h.send(SoleApplyInput.FrameArrived(3))
        h.send(SoleApplyInput.CommitFailed(2))
        h.send(SoleApplyInput.CommitSucceeded(3)) // stale/racy result in BROKEN: still dark

        assertEquals(emptyList(), h.fanOutSeqs(), "BROKEN scope emits no fan-out whatsoever")
        assertEquals(0, h.dropCount(), "BROKEN never releases to legacy")
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, h.state.mode)
        assertEquals(
            requestsSoFar,
            h.rebaselineRequestCount(),
            "single-flight preserved in BROKEN: no re-emit while the request is outstanding",
        )

        // The in-flight recovery was dirtied by seq2/3, so one completion rearms and a clean retry
        // completion is the only path back to FOLD.
        h.rebaselineSucceeded()
        assertTrue(h.state.applyBlocked)
        h.rebaselineSucceeded()
        assertEquals(SoleApplyMode.FOLD, h.state.mode)
        assertEquals(emptyList(), h.state.heldSeqs)
    }

    // ── gate 8: the mechanical dormancy proof ───────────────────────────────────────────────────────

    @Test
    fun gate8_soleApplyLaneEligible_isStructurallyFalseWhileCodecConstIsFalse() {
        assertFalse(
            soleApplyLaneEligible(perScopeControl = true),
            "even a fully-enabled per-scope control must be vetoed while CANONICAL_FACT_SOLE_APPLY_ELIGIBLE " +
                "is false — the codec KDoc's mechanical V1 protection",
        )
        assertFalse(soleApplyLaneEligible(perScopeControl = false))

        // And therefore the production construction path can never build a lane in slice-2.
        val lane = MessageSoleApplyLane.createIfEligible(
            perScopeControl = true,
            scopeKey = SyncScopeKey(
                partition = DurablePartitionKey("s", "p", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
                scopeId = "c",
                scopeKind = "channel",
            ),
            adapter = object : FoldPersistenceAdapter {
                override suspend fun applyFrame(frame: FoldPersistFrame) = Unit
                override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
                    RestoredFoldState(emptyList(), null)
            },
            fanOut = {},
            requestRebaseline = { _, _ -> },
            laneScope = CoroutineScope(EmptyCoroutineContext),
        )
        assertNull(lane, "no lane can exist in production while the eligibility gate is false")
    }

    // ── supplementary: generation barrier is monotonic across activation/rebaseline/drop ────────────

    @Test
    fun generationBarrierIsMonotonic() {
        val h = Harness()
        val g0 = h.state.generation
        h.send(SoleApplyInput.ActivationRequested)
        val g1 = h.state.generation
        h.send(SoleApplyInput.WarmStartSucceeded)
        h.send(SoleApplyInput.FrameArrived(1))
        h.send(SoleApplyInput.CommitFailed(1))
        h.rebaselineSucceeded()
        val g2 = h.state.generation
        h.send(SoleApplyInput.EligibilityRevoked)
        h.rebaselineSucceeded()
        val g3 = h.state.generation
        assertTrue(g0 < g1 && g1 < g2 && g2 < g3, "barrier/generation bumps monotonically: $g0 $g1 $g2 $g3")
    }

    @Test
    fun mailboxOverflowUsesApplyFailureSemantics_andStaleCompletionsCannotUnlock() {
        val h = Harness()
        h.activateToFold()

        val overflow = h.send(SoleApplyInput.MailboxOverflowed)
        val request = overflow.filterIsInstance<SoleApplyCommand.RequestRebaseline>().single()
        assertTrue(h.state.applyBlocked)
        assertEquals(request.generation, h.state.rebaselineGeneration)
        assertTrue(
            overflow.any { it == SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_MAILBOX_OVERFLOW) },
            "mailbox overflow must reach the pure engine's bounded telemetry",
        )

        // Wrong generation and a post-success duplicate are no-ops: neither unlocks early nor retries.
        h.rebaselineSucceeded(request.generation - 1)
        assertTrue(h.state.applyBlocked)
        assertEquals(request.generation, h.state.rebaselineGeneration)

        h.rebaselineSucceeded(request.generation)
        assertFalse(h.state.applyBlocked)
        assertNull(h.state.rebaselineGeneration)
        val generationAfterSuccess = h.state.generation

        h.rebaselineSucceeded(request.generation)
        assertFalse(h.state.applyBlocked)
        assertEquals(generationAfterSuccess, h.state.generation)
        assertEquals(1, h.rebaselineRequestCount(), "duplicate completion must not form a second flight")
    }

    @Test
    fun arrivalOrOverflowDuringFlight_makesMatchingSuccessRearmInsteadOfUnlocking() {
        for (dirtyInput in listOf<SoleApplyInput>(SoleApplyInput.FrameArrived(2), SoleApplyInput.MailboxOverflowed)) {
            val h = Harness()
            h.activateToFold()
            h.send(SoleApplyInput.FrameArrived(1))
            h.send(SoleApplyInput.CommitFailed(1))
            val firstGeneration = h.outstandingRebaselineGeneration()

            h.send(dirtyInput)
            assertTrue(h.state.rebaselineDirty)
            assertEquals(1, h.rebaselineRequestCount(), "dirtying an active flight must not create a concurrent request")

            val completion = h.rebaselineSucceeded(firstGeneration)
            val retry = completion.filterIsInstance<SoleApplyCommand.RequestRebaseline>().single()
            assertTrue(retry.generation > firstGeneration)
            assertTrue(h.state.applyBlocked)
            assertEquals(SoleApplyMode.BROKEN_CANONICAL, h.state.mode)
            assertEquals(retry.generation, h.state.rebaselineGeneration)
            assertFalse(h.state.rebaselineDirty, "the new flight starts clean after the dirty one is rearmed")
            assertTrue(
                completion.any { it == SoleApplyCommand.Diagnostic(SOLE_APPLY_DIAG_REBASELINE_DIRTY_REARMED) },
            )
            assertEquals(emptyList(), h.fanOutSeqs())
        }
    }
}
