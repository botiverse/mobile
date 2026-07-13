/*
 * t4 — reactions concurrency fold gate (sole-apply prerequisite).
 *
 * Proves the KMP messages-domain fold keeps the REACTION aggregate server-equal under CONCURRENT
 * reactions (my unconfirmed add/remove interleaved with others' concurrent add/remove), and that a
 * client-optimistic reaction overlay NEVER enters the canonical fold.
 *
 * Why this holds — present-overwrite == server-truth LWW:
 *   Reactions is an OptionalAggregate with mergePolicy = present-overwrite (MessageSyncDomain.kt:
 *   `reactions = gate(Reactions, incoming.reactions, existing.reactions)`). Every server
 *   `message:updated` carries the FULL resolved reaction aggregate at that instant (all concurrent
 *   add/removes already reconciled server-side). Because the fold OVERWRITES the whole set whenever
 *   Reactions is present, folding a sequence of such frames is last-write-wins on the full set — the
 *   final canonical reaction set == the LAST-applied frame's set EXACTLY, independent of which reactor
 *   changed or the arrival order. There is no additive/merge accumulation and thus no residue.
 *
 * Optimistic-overlay exclusion:
 *   There is no reaction entity/table and no path for a client-optimistic reaction into
 *   MessageDomainState — canonical carries only server-resolved aggregates. Modeled here by simply not
 *   feeding the optimistic-only value to the fold and asserting it is absent from the canonical result.
 *
 * Deterministic: no wall-clock, no random. Style mirrors MessageSyncDomainTest.kt.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactionConcurrencyFoldTest {

    // Reactions is present on the enriched face; use it for every reaction-bearing frame so the fold
    // exercises the present-overwrite gate on `reactions`.
    private val REACTIONS_PRESENT = MessageDomainEvent.PRESENT_ENRICHED_UPDATED

    private fun row(
        id: String = "m1",
        channel: String = "c1",
        seq: Long = 5L,
        reactions: List<CanonicalReactionWire> = emptyList(),
    ): CanonicalMessageRow = CanonicalMessageRow(
        id = id,
        channelId = channel,
        seq = seq,
        senderType = "user",
        senderId = "u1",
        content = "b",
        messageType = "chat",
        createdAt = "t0",
        senderName = "U",
        reactions = reactions,
    )

    private fun reaction(emoji: String, reactors: List<Pair<String, String>>) =
        CanonicalReactionWire(
            emoji = emoji,
            count = reactors.size,
            reactorIds = reactors.map { it.first },
            reactorNames = reactors.map { it.second },
        )

    private val ME = "u-me" to "Me"
    private val OTHER = "u-other" to "Other"

    private fun new(s: MessageDomainState, r: CanonicalMessageRow) =
        applyMessageDomainEvent(s, MessageDomainEvent.New(r, MessageDomainEvent.PRESENT_MESSAGE_NEW))

    private fun updated(s: MessageDomainState, r: CanonicalMessageRow) =
        applyMessageDomainEvent(s, MessageDomainEvent.Updated(r, REACTIONS_PRESENT))

    private fun reactionsOf(s: MessageDomainState, channel: String = "c1") =
        s.channelMessages.getValue(channel).single().reactions

    /**
     * The concurrent story, each frame = the server's FULL resolved set at that instant:
     *   f0: (start, no reactions) -> f1: I add 👍 -> f2: other adds ❤️ -> f3: I remove 👍 -> f4: other removes ❤️
     */
    private val f0 = row(reactions = emptyList())
    private val f1 = row(reactions = listOf(reaction("👍", listOf(ME))))
    private val f2 = row(reactions = listOf(reaction("👍", listOf(ME)), reaction("❤️", listOf(OTHER))))
    private val f3 = row(reactions = listOf(reaction("❤️", listOf(OTHER))))
    private val f4 = row(reactions = emptyList())

    // --- 1. concurrent add/remove converges to the server's last full set ----------------------

    @Test
    fun concurrentAddRemoveFoldConvergesToLastServerFrame() {
        var s = createInitialMessageDomainState()
        s = new(s, f0)
        // fold the full concurrent sequence in arrival order
        for (frame in listOf(f1, f2, f3, f4)) s = updated(s, frame)
        // present-overwrite LWW: final == the LAST frame's set EXACTLY (per-emoji count + reactors).
        assertEquals(f4.reactions, reactionsOf(s))
        assertTrue(reactionsOf(s).isEmpty()) // last frame cleared to empty; no residue from f1/f2/f3
    }

    @Test
    fun midSequenceStateEqualsThatFrameExactly() {
        var s = createInitialMessageDomainState()
        s = new(s, f0)
        s = updated(s, f1)
        s = updated(s, f2) // both 👍(me) and ❤️(other) live server-side now
        // exact convergence to the applied frame — both reactions, correct counts + reactor identity.
        assertEquals(f2.reactions, reactionsOf(s))
        val byEmoji = reactionsOf(s).associateBy { it.emoji }
        assertEquals(CanonicalReactionWire("👍", 1, listOf("u-me"), listOf("Me")), byEmoji.getValue("👍"))
        assertEquals(CanonicalReactionWire("❤️", 1, listOf("u-other"), listOf("Other")), byEmoji.getValue("❤️"))
    }

    @Test
    fun reorderedSequenceConvergesToWhicheverFrameIsAppliedLast() {
        // present-overwrite is last-write-wins on the FULL set: reorder the concurrent frames and the
        // final canonical set == the last-APPLIED frame's set, not an accumulation of all of them.
        var s = createInitialMessageDomainState()
        s = new(s, f0)
        // deliberately out-of-"causal"-order arrival; f2 applied last here.
        for (frame in listOf(f4, f1, f3, f2)) s = updated(s, frame)
        assertEquals(f2.reactions, reactionsOf(s))
        assertEquals(2, reactionsOf(s).size) // exactly f2's two reactions, no leftovers from f4/f1/f3
    }

    // --- 2. others' cancel is free (one fewer reactor overwrites cleanly, no residue) -----------

    @Test
    fun othersCancelLeavesNoResidueOfEarlierReactors() {
        var s = createInitialMessageDomainState()
        s = new(s, f0)
        // two reactors on 👍, then a frame where OTHER has cancelled -> only ME remains.
        val both = row(reactions = listOf(reaction("👍", listOf(ME, OTHER))))
        val afterCancel = row(reactions = listOf(reaction("👍", listOf(ME))))
        s = updated(s, both)
        s = updated(s, afterCancel)
        val r = reactionsOf(s).single()
        // final == the cancel frame's set exactly: count 1, only ME, OTHER fully gone (no stale id/name).
        assertEquals(afterCancel.reactions, reactionsOf(s))
        assertEquals(1, r.count)
        assertEquals(listOf("u-me"), r.reactorIds)
        assertFalse("u-other" in r.reactorIds)
        assertFalse("Other" in r.reactorNames)
    }

    // --- 3. optimistic overlay never enters the canonical fold -----------------------------------

    @Test
    fun optimisticOverlayReactionNeverEntersCanonicalFold() {
        // Model an optimistic add (emoji 🎉 by ME) as a value that is NOT fed to the fold — there is no
        // reaction entity/table and no path for a client-optimistic reaction into MessageDomainState.
        val optimisticOnly = reaction("🎉", listOf(ME))

        var s = createInitialMessageDomainState()
        s = new(s, f0)
        // only server frames reach the fold; the optimistic-only reaction is never applied.
        s = updated(s, f1) // server: 👍(me)
        s = updated(s, f2) // server: 👍(me) + ❤️(other)

        val canonical = reactionsOf(s)
        assertEquals(f2.reactions, canonical) // canonical == server frames only
        assertFalse(canonical.any { it.emoji == "🎉" }) // optimistic-only emoji absent
        assertFalse(canonical.any { it == optimisticOnly })
        assertEquals(setOf("👍", "❤️"), canonical.map { it.emoji }.toSet())
    }
}
