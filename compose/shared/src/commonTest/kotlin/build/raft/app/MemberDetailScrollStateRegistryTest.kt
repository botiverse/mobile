package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * task #58: the session registry keeps one scroll snapshot per
 * (account, member, tab) so a detail destroyed by a pane transition resumes
 * at its offset, while logout/account switch and the entry cap bound memory
 * and prevent cross-account reuse.
 */
class MemberDetailScrollStateRegistryTest {

    @Test
    fun `saved position restores for same member key and tab`() {
        MemberDetailScrollStateRegistry.clear()
        val saved = MemberDetailScrollPosition(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 42)
        MemberDetailScrollStateRegistry.saveAgentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile, saved)
        assertEquals(saved, MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile))
    }

    @Test
    fun `different tab or member or account get distinct positions`() {
        MemberDetailScrollStateRegistry.clear()
        val saved = MemberDetailScrollPosition(firstVisibleItemIndex = 5)
        MemberDetailScrollStateRegistry.saveAgentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile, saved)
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Activity)
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m2", MembersAgentDetailTab.Profile)
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u2:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `clear drops remembered positions so a new session cannot inherit offsets`() {
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 9)
        )
        MemberDetailScrollStateRegistry.clear()
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `human list position is keyed per member`() {
        MemberDetailScrollStateRegistry.clear()
        val saved = MemberDetailScrollPosition(firstVisibleItemIndex = 3)
        MemberDetailScrollStateRegistry.saveHumanListPosition("u1:s1:human:h1", saved)
        assertEquals(saved, MemberDetailScrollStateRegistry.humanListPosition("u1:s1:human:h1"))
        assertEquals(MemberDetailScrollPosition(), MemberDetailScrollStateRegistry.humanListPosition("u1:s1:human:h2"))
    }

    @Test
    fun `entry cap evicts oldest member keys`() {
        MemberDetailScrollStateRegistry.clear()
        (0 until 70).forEach { i ->
            MemberDetailScrollStateRegistry.saveAgentTabPosition(
                "u1:s1:agent:m$i",
                MembersAgentDetailTab.Profile,
                MemberDetailScrollPosition(firstVisibleItemIndex = i)
            )
        }
        // Oldest entries are gone; the most recent still resolves.
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m0", MembersAgentDetailTab.Profile)
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 69),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m69", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `save issued before clear is retired and cannot resurrect the old position`() {
        MemberDetailScrollStateRegistry.clear()
        val generation = MemberDetailScrollStateRegistry.currentGeneration()
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 11),
            expectedGeneration = generation
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `save carrying the current generation still lands after a clear`() {
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveHumanListPosition(
            "u2:s1:human:h1",
            MemberDetailScrollPosition(firstVisibleItemIndex = 4),
            expectedGeneration = MemberDetailScrollStateRegistry.currentGeneration()
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 4),
            MemberDetailScrollStateRegistry.humanListPosition("u2:s1:human:h1")
        )
    }

    @Test
    fun `stale-epoch save cannot clobber a populated snapshot during page handoff`() {
        MemberDetailScrollStateRegistry.clear()
        val oldEpoch = MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 20),
            writeEpoch = oldEpoch
        )
        // The recreated page takes the next epoch; the old page's still-
        // Loading zero frame stamped with the retired epoch must not land.
        MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(),
            writeEpoch = oldEpoch
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 20),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `zero position still saves when the list is populated`() {
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveHumanListPosition(
            "u1:s1:human:h1",
            MemberDetailScrollPosition(firstVisibleItemIndex = 8)
        )
        MemberDetailScrollStateRegistry.saveHumanListPosition(
            "u1:s1:human:h1",
            MemberDetailScrollPosition()
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.humanListPosition("u1:s1:human:h1")
        )
    }

    @Test
    fun `only the newest write epoch for a member key lands`() {
        MemberDetailScrollStateRegistry.clear()
        val oldEpoch = MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        val newEpoch = MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        // New page writes first; the old page still has a populated list and
        // disposes late: its write is stamped with the retired epoch and must
        // not overwrite the new position.
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 3),
            writeEpoch = newEpoch
        )
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 30),
            writeEpoch = oldEpoch
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 3),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `write epochs are per member key`() {
        MemberDetailScrollStateRegistry.clear()
        val epochA = MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m2")
        // Acquiring an epoch for m2 does not retire m1's writer.
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 6),
            writeEpoch = epochA
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 6),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `epoch-less saves bypass ownership for non-page callers`() {
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 12)
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 12),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `acquiring an epoch for another member never retires an unsaved owner`() {
        MemberDetailScrollStateRegistry.clear()
        val ownerEpoch = MemberDetailScrollStateRegistry.acquireWriteEpoch("u1:s1:agent:m1")
        // Other members' owners and saves churn while m1's page is still in
        // Loading (zero-item saves dropped) — its epoch must survive.
        (0 until 10).forEach { i ->
            val key = "u1:s1:agent:other$i"
            val epoch = MemberDetailScrollStateRegistry.acquireWriteEpoch(key)
            MemberDetailScrollStateRegistry.saveAgentTabPosition(
                key,
                MembersAgentDetailTab.Profile,
                MemberDetailScrollPosition(firstVisibleItemIndex = i),
                writeEpoch = epoch
            )
        }
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 15),
            writeEpoch = ownerEpoch
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 15),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }
}

/**
 * task #58: frame-level replay of the Alpha94 device evidence — the narrow
 * pane seeded to 0:489 successfully, then a layout pass clamped it to 0:88.
 * These drive MemberDetailScrollTracker directly (no LazyListState needed).
 */
class MemberDetailScrollTrackerTest {

    private fun tracker(
        target: MemberDetailScrollPosition,
        saved: MutableList<MemberDetailScrollPosition>
    ): (Int, Int, Int, Boolean, (Int, Int) -> Unit) -> Unit =
        MemberDetailScrollTracker(target).let { tracker ->
            { index, offset, items, scrolling, request ->
                tracker.observe(
                    itemCount = items,
                    positionIndex = index,
                    positionOffset = offset,
                    scrollInProgress = scrolling,
                    requestScroll = request,
                    save = { saved += it }
                )
            }
        }

    @Test
    fun `alpha94 replay - seed lands intact then clamp is suppressed`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val frame = tracker(MemberDetailScrollPosition(0, 489), saved)
        // First populated frame already at the seeded offset (restore target).
        frame(0, 489, 40, false) { _, _ -> }
        assertEquals(listOf(MemberDetailScrollPosition(0, 489)), saved)
        // Next layout pass clamps down — must not be published.
        frame(0, 88, 40, false) { _, _ -> }
        assertEquals(listOf(MemberDetailScrollPosition(0, 489)), saved)
    }

    @Test
    fun `alpha94 replay - user scroll during settle publishes the landing`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val frame = tracker(MemberDetailScrollPosition(0, 489), saved)
        frame(0, 489, 40, false) { _, _ -> }
        // User scrolls up once: in-flight frames while scrolling...
        frame(0, 300, 40, true) { _, _ -> }
        // ...then the landing publishes and the window closes.
        frame(0, 300, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 300), saved.last())
    }

    @Test
    fun `restore request path suppresses the clamped landing`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var requested: Pair<Int, Int>? = null
        val frame = tracker(MemberDetailScrollPosition(0, 489), saved)
        // Seed landed at top; tracker issues the restore request.
        frame(0, 0, 40, false) { i, o -> requested = i to o }
        assertEquals(0 to 489, requested)
        // The seed-time top frame is already inside the settle window and is
        // suppressed (position 0:0 != target, no user scroll) — it must NOT
        // overwrite the 0:489 snapshot while the restore is still pending.
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
        // In-flight frames publish nothing; the clamped landing is ambiguous
        // (programmatic vs user scroll) so it is suppressed and the snapshot
        // is preserved.
        frame(0, 200, 40, true) { _, _ -> }
        frame(0, 88, 40, false) { _, _ -> }
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
    }

    @Test
    fun `empty frames never publish`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val frame = tracker(MemberDetailScrollPosition(0, 489), saved)
        frame(0, 0, 0, false) { _, _ -> }
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
    }

    @Test
    fun `after clamp suppression a user scroll landing still publishes`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val frame = tracker(MemberDetailScrollPosition(0, 489), saved)
        frame(0, 0, 40, false) { _, _ -> } // seed top -> request issued
        frame(0, 200, 40, true) { _, _ -> } // programmatic in-flight
        frame(0, 88, 40, false) { _, _ -> } // clamped landing suppressed
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
        // User then scrolls once and leaves: landing must publish.
        frame(0, 300, 40, true) { _, _ -> }
        frame(0, 300, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 300), saved.last())
    }
}
