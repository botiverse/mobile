package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

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
        t.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
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
        tracker.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
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
        t.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
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
        t.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
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
        t.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
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
    fun `pending restore stays visible until the gesture is reported`() {
        // Production ordering contract used by membersDetailUserDragProbe:
        // the probe must read hasPendingRestoreRequest BEFORE calling
        // onUserScrollStarted(), because reporting the gesture retires the
        // pending request. This test pins that contract at tracker level so
        // a refactor that moves the flag clear earlier breaks loudly.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 237, 40, false) { i, o -> requests += i to o } // re-issue
        assertEquals(listOf(0 to 489), requests)
        // While the re-issued request is still pending application, the
        // predicate must report it so the probe can supersede.
        assertEquals(true, t.hasPendingRestoreRequest)
        // The production probe path: gesture reported with no scroll in
        // flight must supersede the pending request with the last MEASURED
        // position (237 — the clamp landing the list actually sits at),
        // exactly once.
        t.onUserScrollStarted(scrollInFlight = false) { i, o ->
            requests += i to o
        }
        // After the gesture is reported, the request is retired.
        assertEquals(false, t.hasPendingRestoreRequest)
        assertEquals(listOf(0 to 489, 0 to 237), requests)
    }

    @Test
    fun `gesture during in-flight scroll never supersedes via requestScroll`() {
        // If a scroll is already running when the gesture is reported, the
        // supersede must NOT call requestScrollToItem — on the real list it
        // would cancel the user's own just-started drag.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 489, 40, false) { i, o -> requests += i to o }
        frame(0, 237, 40, false) { i, o -> requests += i to o } // re-issue
        assertEquals(true, t.hasPendingRestoreRequest)
        t.onUserScrollStarted(scrollInFlight = true) { i, o ->
            requests += i to o
        }
        assertEquals(false, t.hasPendingRestoreRequest)
        // No supersede request was issued — only the original re-issue.
        assertEquals(listOf(0 to 489), requests)
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
        t.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
        frame(0, 300, 40, true) { _, _ -> }
        frame(0, 300, 40, false) { _, _ -> }
        assertEquals(MemberDetailScrollPosition(0, 300), saved.last())
    }

    @Test
    fun `stall tick re-issues a pending restore request and is bounded`() {
        // Alpha102 + d7-iPad evidence: restore_first_items issued, then
        // silence — no settle frame, no save — until a real gesture
        // superseded 41-84s later. The frame pipeline alone cannot observe
        // a swallowed request (the snapshotFlow key only changes on
        // measured frames), so the composition drives onRestoreStalled on a
        // tick; each pending tick re-issues the target, bounded.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        // Seed lands at top -> tracker issues the restore request.
        frame(0, 0, 40, false) { i, o -> requests += i to o }
        assertEquals(listOf(0 to 489), requests)
        assertEquals(true, t.hasPendingRestoreRequest)
        // No frame ever arrives (dead request). Stall ticks must re-issue
        // the SAME target, bounded by MaxStallReissues — the tick returns
        // the target for the caller's suspend scrollToItem, or null when
        // nothing should be re-issued.
        repeat(5) {
            t.onRestoreStalled()?.let { requests += it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
        }
        assertEquals(
            listOf(0 to 489, 0 to 489, 0 to 489, 0 to 489),
            requests
        )
        // 3 retries max — the 4th and 5th ticks no-op.
        assertEquals(null, t.onRestoreStalled())
        assertEquals(4, requests.size)
    }

    @Test
    fun `stall tick is a no-op once the restore lands or the user takes over`() {
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 0, 40, false) { i, o -> requests += i to o }
        assertEquals(true, t.hasPendingRestoreRequest)
        // User gesture supersedes: request retired, ticks stop firing.
        t.onUserScrollStarted(scrollInFlight = false) { i, o -> requests += i to o }
        assertEquals(false, t.hasPendingRestoreRequest)
        assertEquals(null, t.onRestoreStalled())
    }

    @Test
    fun `stall re-issue uses the restore target not the clamped landing`() {
        // The pending request's supersede base is the measured position
        // (237 in Alpha101), but a stall retry must re-request the saved
        // TARGET — re-writing the clamped position would bake the failure
        // in as the new goal.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 237, 40, false) { i, o -> requests += i to o }
        assertEquals(listOf(0 to 489), requests)
        t.onRestoreStalled()?.let { requests += it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
        assertEquals(listOf(0 to 489, 0 to 489), requests)
    }

    @Test
    fun `target landing retires the issued request and fires the hook`() {
        // Every retirement — not only gesture supersede — must cancel the
        // watchdog's parked re-issue: a clamped landing or the achieved
        // landing also ends the request the parked job is still carrying.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var retired = 0
        t.onPendingRestoreRetired = { retired += 1 }
        val frame = frameOf(t, saved)
        frame(0, 0, 40, false) { _, _ -> } // request issued
        assertEquals(true, t.hasPendingRestoreRequest)
        frame(0, 489, 40, true) { _, _ -> }
        frame(0, 489, 40, false) { _, _ -> } // lands at target
        assertEquals(1, retired)
        assertEquals(false, t.hasPendingRestoreRequest)
    }

    @Test
    fun `post-achieved clamp re-issue retires the stale parked request first`() {
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var retired = 0
        t.onPendingRestoreRetired = { retired += 1 }
        val requests = mutableListOf<Pair<Int, Int>>()
        val frame = frameOf(t, saved)
        frame(0, 489, 40, false) { _, _ -> } // seed at target, no request
        frame(0, 489, 40, false) { _, _ -> } // achieved
        // Nothing was issued, so nothing could be retired.
        assertEquals(0, retired)
        // Clamped post-achieved landing -> re-issue is flagged; the hook
        // stays silent because no request was outstanding.
        frame(0, 237, 40, false) { i, o -> requests += i to o }
        assertEquals(listOf(0 to 489), requests)
        assertEquals(0, retired)
        assertEquals(true, t.hasPendingRestoreRequest)
        // The next non-target landing retires THIS request via the hook
        // before the second bounded re-issue is flagged.
        frame(0, 100, 40, false) { i, o -> requests += i to o }
        assertEquals(1, retired)
        assertEquals(listOf(0 to 489, 0 to 489), requests)
    }

    @Test
    fun `gesture retire of a pending request fires onPendingRestoreRetired once`() {
        // LiBai's interleave: watchdog tick already picked the target and
        // the suspend scrollToItem is still waiting on the scroll mutex
        // when the gesture lands. The composition cancels that in-flight
        // re-issue through this hook — it must fire exactly when the
        // pending request is retired, not on frames or unrelated clears.
        val t = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        val saved = mutableListOf<MemberDetailScrollPosition>()
        var retired = 0
        t.onPendingRestoreRetired = { retired += 1 }
        val frame = frameOf(t, saved)
        // Seed at target: settle opens but NO request was issued — no hook.
        frame(0, 489, 40, false) { _, _ -> }
        t.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
        assertEquals(0, retired)
        // Fresh tracker: seed at top -> real request issued -> gesture
        // supersedes -> hook fires exactly once.
        val t2 = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
        var retired2 = 0
        t2.onPendingRestoreRetired = { retired2 += 1 }
        val frame2 = frameOf(t2, saved)
        frame2(0, 0, 40, false) { _, _ -> }
        assertEquals(true, t2.hasPendingRestoreRequest)
        t2.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
        assertEquals(1, retired2)
        // A second gesture while nothing is pending must not re-fire.
        t2.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
        assertEquals(1, retired2)
    }

    @Test
    fun `watchdog cancels an in-flight stale re-issue when the user supersedes`() =
        runBlocking {
            // Production-shaped interleave: the watchdog tick selects 489
            // and launches the suspend issue (production: scrollToItem,
            // which waits on the scroll mutex / first layout). While that
            // job is parked, the user gesture retires the pending request —
            // the tracker hook must cancel the parked job so the stale
            // target never reaches the list.
            val tracker = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
            val saved = mutableListOf<MemberDetailScrollPosition>()
            val frame = frameOf(tracker, saved)
            // Seed lands at measured 237 (Alpha101-style clamp) so the
            // user's supersede base is distinguishable from the target.
            frame(0, 237, 40, false) { _, _ -> }
            assertEquals(true, tracker.hasPendingRestoreRequest)

            val issueStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val issueRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
            val issueJob = kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>()
            val issued = mutableListOf<MemberDetailScrollPosition>()
            val watchdog =
                launchMembersDetailRestoreStallWatchdog(
                    tracker,
                    tickMs = 1,
                ) { target ->
                    issueJob.complete(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
                    issueStarted.complete(Unit)
                    issueRelease.await()
                    issued += target
                }

            try {
                // Let the first tick park inside issue().
                kotlinx.coroutines.withTimeout(2_000) { issueStarted.await() }
                // User supersedes at the measured 237 while the stale
                // re-issue is still parked, then lands at 355 — that
                // landing must save.
                var superseded: Pair<Int, Int>? = null
                tracker.onUserScrollStarted(scrollInFlight = false) { i, o ->
                    superseded = i to o
                }
                assertEquals(0 to 237, superseded)
                assertEquals(false, tracker.hasPendingRestoreRequest)
                frame(0, 355, 40, true) { _, _ -> }
                frame(0, 355, 40, false) { _, _ -> }
                assertEquals(MemberDetailScrollPosition(0, 355), saved.last())

                // Join the issue's own child job with a hard deadline: a
                // cancelled job completes without appending; without the
                // retire hook the released gate would apply 489 and fail.
                kotlinx.coroutines.withTimeout(2_000) { issueJob.await().join() }
            } finally {
                // Release the gate and stop the loop no matter where we
                // died — a parked job or live watchdog must not leak past
                // the assertions.
                issueRelease.complete(Unit)
                watchdog.cancel()
            }
            assertEquals(emptyList<MemberDetailScrollPosition>(), issued)
        }

    @Test
    fun `watchdog cancels the previous re-issue before launching the next tick`() =
        runBlocking {
            // Each tick must cancel the still-parked previous re-issue before
            // launching its own job, and once the user gesture retires the
            // pending request no tick may apply its target — the watchdog is
            // dead weight after supersede (LiBai: the correct expectation is
            // zero applications, not "the latest 489 survives").
            val tracker = MemberDetailScrollTracker(MemberDetailScrollPosition(0, 489))
            val saved = mutableListOf<MemberDetailScrollPosition>()
            val frame = frameOf(tracker, saved)
            frame(0, 0, 40, false) { _, _ -> }

            val issued = mutableListOf<MemberDetailScrollPosition>()
            val releaseTick = kotlinx.coroutines.CompletableDeferred<Unit>()
            // Per-tick deferreds: a single CompletableDeferred would keep
            // handing out tick 1's job to every awaiter.
            val startedTicks =
                listOf(
                    kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>(),
                    kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>()
                )
            var tickIndex = 0
            val watchdog =
                launchMembersDetailRestoreStallWatchdog(
                    tracker,
                    tickMs = 1,
                ) { target ->
                    val n = tickIndex++
                    if (n < startedTicks.size) {
                        startedTicks[n].complete(
                            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        )
                    }
                    releaseTick.await()
                    issued += target
                }
            try {
                // Tick 1 parks at the release gate; tick 2 supersedes it,
                // cancels job1, and parks in turn.
                val job1 = kotlinx.coroutines.withTimeout(2_000) { startedTicks[0].await() }
                val job2 = kotlinx.coroutines.withTimeout(2_000) { startedTicks[1].await() }
                kotlinx.coroutines.withTimeout(2_000) { job1.join() }
                // User takes over while job2 is still parked: the retire hook
                // cancels it, and every later tick sees no pending request.
                tracker.onUserScrollStarted(scrollInFlight = false) { _, _ -> }
                kotlinx.coroutines.withTimeout(2_000) { job2.join() }
            } finally {
                // Whatever state the test died in, never leave the loop or a
                // parked issue job running past the assertions.
                releaseTick.complete(Unit)
                watchdog.cancel()
            }
            assertEquals(emptyList<MemberDetailScrollPosition>(), issued)
        }

    @Test
    fun `production frame key emits when measured position catches up to the getter`() =
        runBlocking {
            // LiBai's missed-frame race: requestScrollToItem rewrites the
            // scrollPosition getters synchronously (489), while the measured
            // visibleItemsInfo still reports the clamp (237) until the next
            // remeasure. A getter-only snapshot key sees getter=489 at the
            // request frame and again 489 after the remeasure — equal keys,
            // so the landing frame is never emitted and the tracker never
            // records the achieved position.
            //
            // The key is extracted through the SAME production function the
            // detail lists call (membersDetailScrollFrameKey): here the
            // state's measured surface is faked by mutableStateOf fields via
            // a minimal LazyListState shim is impossible, so the extractor is
            // tested through its primitive contract — production passes
            // visibleItemsInfo.first().index/offset; the test passes fake
            // measured values through a tiny state stand-in.
            val scrolling = mutableStateOf(false)
            val getterIndex = mutableStateOf(0)
            val getterOffset = mutableStateOf(0)
            val measuredIndex = mutableStateOf(0)
            val measuredOffset = mutableStateOf(0)
            val items = mutableStateOf(40)

            val emitted = mutableListOf<MemberDetailScrollFrameKey>()
            val job =
                launch {
                    snapshotFlow {
                        // Calls the SAME production primitive extractor the
                        // detail lists route through membersDetailScrollFrameKey(state);
                        // the "measured surface" (visibleItemsInfo) is faked by
                        // the measured* states, so a regression that swaps the
                        // production call back to getter-only keys breaks here.
                        membersDetailScrollFrameKey(
                            scrolling = scrolling.value,
                            measuredIndex = measuredIndex.value,
                            measuredOffset = measuredOffset.value,
                            fallbackIndex = getterIndex.value,
                            fallbackOffset = getterOffset.value,
                            items = items.value
                        )
                    }.collect { emitted += it }
                }
            // Give the collector a moment to subscribe, then force snapshot
            // notification so the first read is delivered.
            yield()

            // Frame 1: clamp landing at 237 (measured = 237).
            measuredIndex.value = 0
            measuredOffset.value = 237
            Snapshot.sendApplyNotifications()
            yield()

            // Frame 2: tracker re-issues; requestScrollToItem writes the
            // getter to 489 synchronously — measured still 237, so no new
            // emission from the measured key (same key), correct.
            getterIndex.value = 0
            getterOffset.value = 489
            Snapshot.sendApplyNotifications()
            yield()

            // Frame 3: remeasure lands — measured catches up to 489. The
            // key MUST differ from the clamp frame so this emits.
            measuredOffset.value = 489
            Snapshot.sendApplyNotifications()
            yield()

            job.cancel()
            // The critical assertion: the landing frame (measured 489) was
            // emitted. With a getter-only key, frame 3 equals frame 2 and
            // is lost — this is the regression the production extractor
            // guards against.
            assertEquals(
                listOf(
                    MemberDetailScrollFrameKey(false, 0, 0, 40),
                    MemberDetailScrollFrameKey(false, 0, 237, 40),
                    MemberDetailScrollFrameKey(false, 0, 489, 40)
                ),
                emitted
            )
        }
}
