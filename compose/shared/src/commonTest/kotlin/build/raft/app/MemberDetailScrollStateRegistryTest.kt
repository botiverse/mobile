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
        frameOf(MemberDetailScrollTracker(target), saved)

    private fun frameOf(
        tracker: MemberDetailScrollTracker,
        saved: MutableList<MemberDetailScrollPosition>
    ): (Int, Int, Int, Boolean, (Int, Int) -> Unit) -> Unit =
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

    @Test
    fun `alpha94 replay - seed lands intact then clamp is suppressed`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val frame = tracker(MemberDetailScrollPosition(0, 489), saved)
        // First populated frame already at the seeded offset (restore
        // target): it opens the settle window and is itself the seed frame,
        // not a landing — nothing publishes, but the snapshot already holds
        // 489 so nothing is lost either.
        frame(0, 489, 40, false) { _, _ -> }
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
        // Next layout pass clamps down — must not be published either.
        frame(0, 88, 40, false) { _, _ -> }
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
    }

    @Test
    fun `alpha94 replay - user scroll during settle publishes the landing`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { _, _ -> } // seed frame opens settle
        // User scrolls up once: the real gesture signal marks the in-flight
        // frames as user intent (positions alone cannot prove it)...
        t.onUserScrollStarted()
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
    fun `layout-delta first frame without gesture still restores the target`() {
        // iPad evidence on 634dd884: saved 2:378 reopened at 2:334 — a 22pt
        // layout reflow, not user input. A non-zero first frame is no longer
        // treated as "user already scrolled"; without isScrollInProgress the
        // tracker must issue the restore request and snap back to the target.
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var requested: Pair<Int, Int>? = null
        val frame = tracker(MemberDetailScrollPosition(2, 378), saved)
        frame(2, 334, 40, false) { i, o -> requested = i to o }
        assertEquals(2 to 378, requested)
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
        // Restore lands on target and publishes the restored position.
        frame(2, 378, 40, true) { _, _ -> }
        frame(2, 378, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(2, 378), saved.last())
    }

    @Test
    fun `in-flight scroll on first populated frame wins over the restore`() {
        // Live-intent path: content lands while a scroll is already in flight
        // (user gesture or a deliberate programmatic move). The tracker must
        // neither fight it with a restore request nor publish the transitory
        // mid-flight position — the landing publishes once the scroll rests.
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var requested: Pair<Int, Int>? = null
        val frame = tracker(MemberDetailScrollPosition(2, 378), saved)
        frame(2, 334, 40, true) { i, o -> requested = i to o }
        assertEquals(null, requested)
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
        frame(2, 300, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(2, 300), saved.last())
    }

    @Test
    fun `dispose after a user scroll landing still saves the final position`() {
        // Scroll once and leave immediately: the landing must be tracked as
        // lastPosition so the dispose write is not lost (covers the actual
        // save path used by the composition's DisposableEffect).
        val tracker = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        fun frame(index: Int, offset: Int, scrolling: Boolean) =
            tracker.observe(
                itemCount = 40,
                positionIndex = index,
                positionOffset = offset,
                scrollInProgress = scrolling,
                requestScroll = { _, _ -> },
                save = { saved += it }
            )
        frame(0, 489, false) // seed at target opens settle
        tracker.onUserScrollStarted()
        frame(0, 300, true)
        frame(0, 300, false)
        assertEquals(MemberDetailScrollPosition(0, 300), tracker.lastPosition)
        assertEquals(MemberDetailScrollPosition(0, 300), saved.last())
    }

    @Test
    fun `post-achieved clamp re-issues the restore once and suppresses the landing`() {
        // Alpha101 evidence: seed landed at 489, atTarget published, then a
        // second layout settle dropped the pane to 237 with NO user scroll —
        // suppressing the write alone left the UI at the profile top. The
        // tracker must re-scroll to the target.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var requested: Pair<Int, Int>? = null
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { _, _ -> } // seed at target opens settle
        frame(0, 489, 40, false) { _, _ -> } // atTarget publishes
        assertEquals(MemberDetailScrollPosition(0, 489), saved.last())
        requested = null
        // Post-settle clamp landing with no user scroll: suppressed AND the
        // tracker re-issues the restore so the pane snaps back.
        frame(0, 237, 40, false) { i, o -> requested = i to o }
        assertEquals(0 to 489, requested)
        assertEquals(MemberDetailScrollPosition(0, 489), saved.last())
        // Restore in flight; landing back at target settles.
        frame(0, 489, 40, true) { _, _ -> }
        frame(0, 489, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 489), saved.last())
    }

    @Test
    fun `post-achieved re-issue is bounded and never fights indefinitely`() {
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        // Three successive non-target landings with no user scroll: only
        // MaxPostSettleReissues re-requests fire, then the tracker stops
        // fighting (pane physically cannot hold the offset or user input is
        // racing the restore).
        frame(0, 237, 40, false) { i, o -> requests += i to o }
        frame(0, 200, 40, false) { i, o -> requests += i to o }
        frame(0, 88, 40, false) { i, o -> requests += i to o }
        assertEquals(2, requests.size) // only the two bounded re-issues
    }

    @Test
    fun `real drag during a re-issued restore wins and saves on exit`() {
        // SangLan's race: restore achieved (489), layout re-clamps (237), the
        // tracker re-issues scrollTo(489); the user starts a REAL drag while
        // the re-request is in flight. Position/direction alone can never
        // prove intent — a same-direction drag is identical to the
        // programmatic approach — so the tracker only trusts the real
        // gesture signal (DragInteraction.Start -> onUserScrollStarted).
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        assertEquals(MemberDetailScrollPosition(0, 489), saved.last())
        // Post-achieve clamp, no gesture: suppressed + one re-issue.
        frame(0, 237, 40, false) { i, o -> requests += i to o }
        assertEquals(listOf(0 to 489), requests)
        // Real drag toward the target (LiBai's 237->380->stop380 case): the
        // gesture signal must win even though positions look identical to a
        // programmatic restore approaching the target.
        t.onUserScrollStarted()
        frame(0, 380, 40, true) { _, _ -> }
        frame(0, 380, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 380), saved.last())
        // And no further re-issue fires: the settle window is closed on the
        // user's landing.
        assertEquals(1, requests.size)
    }

    @Test
    fun `real drag away from target during re-issue also wins`() {
        // Same setup, but the user drags AWAY from the target: the gesture
        // signal — not direction — is what proves intent.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 237, 40, false) { i, o -> requests += i to o }
        assertEquals(listOf(0 to 489), requests)
        t.onUserScrollStarted()
        frame(0, 120, 40, true) { _, _ -> }
        frame(0, 60, 40, true) { _, _ -> }
        frame(0, 60, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 60), saved.last())
        // And no further re-issue fires: the settle window is closed on the
        // user's landing.
        assertEquals(1, requests.size)
    }

    @Test
    fun `exit mid-drag during re-issue saves the live drag position`() {
        // Immediate exit while the drag is still in flight: there is no
        // settled landing frame, so the tracker must track the live user
        // position for the dispose save instead of keeping the stale
        // restore target.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { _, _ -> }
        frame(0, 489, 40, false) { _, _ -> }
        frame(0, 237, 40, false) { _, _ -> } // post-achieve clamp -> re-issue
        t.onUserScrollStarted()
        frame(0, 380, 40, true) { _, _ -> }
        frame(0, 355, 40, true) { _, _ -> }
        // Page disposed here without a settled frame: the dispose write
        // reads lastPosition and must carry the user's live offset.
        assertEquals(MemberDetailScrollPosition(0, 355), t.lastPosition)
        // If instead the drag settles, the landing must still publish even
        // though lastPosition already tracked the same value in flight —
        // live tracking and the published save are separate paths.
        frame(0, 355, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 355), saved.last())
    }

    @Test
    fun `re-issue still applies when no user gesture interleaves`() {
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 237, 40, false) { i, o -> requests += i to o }
        assertEquals(1, requests.size)
        // The re-issued restore approaches the target. With no real gesture
        // signal, in-flight frames are ours even at the same positions a
        // user drag could produce — source, not shape, decides.
        frame(0, 380, 40, true) { _, _ -> }
        frame(0, 489, 40, true) { _, _ -> }
        frame(0, 489, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 489), saved.last())
    }

    @Test
    fun `after clamp suppression a user scroll landing still publishes`() {
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val frame = frameOf(t, saved)
        frame(0, 0, 40, false) { _, _ -> } // seed top -> request issued
        frame(0, 200, 40, true) { _, _ -> } // programmatic in-flight
        frame(0, 88, 40, false) { _, _ -> } // clamped landing suppressed
        assertEquals(emptyList<MemberDetailScrollPosition>(), saved)
        // User then scrolls once and leaves: real gesture publishes.
        t.onUserScrollStarted()
        frame(0, 300, 40, true) { _, _ -> }
        frame(0, 300, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 300), saved.last())
    }
}
