package build.raft.app

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hands e0a5248d ("进channel 好闪烁，它弹来弹去的", Huawei CMU-AL10, 1.15.0): opening a channel or DM
 * cold showed the content, then moved it up and down by the offline stand-in's 40dp top inset
 * several times before it settled. The numbers below are the ones the feedback log recorded
 * (density 3.375): a reversed list 1941px tall with a 135px top inset, the 54px bottom padding row
 * and a 2118px newest message.
 *
 * The page's scroll owners are modelled frame by frame under virtual time against a fake reversed
 * raw list read through the production [ReversedThreadLayoutInfo]; the targets and the "anchored"
 * check are the production [threadRenderedBottomScrollOffset] / [threadItemAtRenderedBottom].
 * This pins the policy and geometry decisions; it is not a Compose layout or device test.
 */
class ThreadChannelOpenSettleTest {
    private data class RawItem(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
        override val key: Any = "k$index",
    ) : LazyListItemInfo

    private class RawInfo(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val totalItemsCount: Int,
        layoutSize: Int,
        override val afterContentPadding: Int,
    ) : LazyListLayoutInfo {
        override val beforeContentPadding: Int = 0
        override val viewportStartOffset: Int = 0
        override val viewportEndOffset: Int = layoutSize
        override val reverseLayout: Boolean = true
    }

    /**
     * A bottom-up list scrolled [rawScrollPx] toward older rows (0 = bottom padding row flush with the
     * bottom edge). Raw rows: 0 = bottom padding, 1 = newest message, 2 = the message above it.
     */
    private class FakeReversedList(var topInsetPx: Int, var layoutPx: Int) {
        var rawScrollPx: Int = 0
            private set
        var moves: Int = 0
            private set

        fun info(): LazyListLayoutInfo {
            val sizes = listOf(PaddingRowPx, NewestRowPx, OlderRowPx)
            var cursor = -rawScrollPx
            val raw = sizes.mapIndexed { index, size -> RawItem(index, cursor, size).also { cursor += size } }
            return ReversedThreadLayoutInfo(
                RawInfo(raw, totalItemsCount = sizes.size, layoutSize = layoutPx, afterContentPadding = topInsetPx)
            )
        }

        /** Forward `scrollToItem(index, scrollOffset)` for a laid-out row, as [ThreadListViewport] does it. */
        fun scrollToItem(index: Int, scrollOffset: Int) {
            val item = info().visibleItemsInfo.first { it.index == index }
            val delta = forwardScrollToItemDelta(itemOffset = item.offset, scrollOffset = scrollOffset)
            // Forward scrollBy(+d) reveals newer rows: the raw list scrolls back toward its bottom.
            val next = (rawScrollPx - delta).coerceAtLeast(0)
            if (next != rawScrollPx) moves += 1
            rawScrollPx = next
        }
    }

    private fun LazyListLayoutInfo.newestRowGapToRenderedBottom(): Int {
        val newest = visibleItemsInfo.first { it.index == NewestForwardIndex }
        return newest.offset + newest.size + PaddingRowPx - viewportEndOffset
    }

    @Test
    fun coldOpenSettlesOnceWhileTheStandInInsetAndTheComposerChange() = runTest {
        // First revealed frame: durable stand-in with its top inset, composer already mounted.
        val list = FakeReversedList(topInsetPx = TopInsetPx, layoutPx = LayoutWithComposerPx)
        val gapsPerFrame = mutableListOf<Int>()
        var anchorAttempts = 0
        var frame = 0
        val frames =
            launch {
                while (frame < SettleFrames) {
                    gapsPerFrame += list.info().newestRowGapToRenderedBottom()
                    // Network answer replaces the stand-in: the inset goes away.
                    if (frame == NetworkAnswerFrame) list.topInsetPx = 0
                    frame += 1
                    delay(FrameMs)
                }
            }
        // Owner 1: initial bottom anchor (FollowLatest until the newest message + padding is anchored).
        val initialAnchor =
            launch {
                while (!threadItemAtRenderedBottom(list.info(), NewestForwardIndex, PaddingRowPx)) {
                    anchorAttempts += 1
                    val info = list.info()
                    list.scrollToItem(NewestForwardIndex, assertNotNull(threadRenderedBottomScrollOffset(info, NewestForwardIndex, PaddingRowPx)))
                    delay(InitialAnchorRetryMs)
                }
            }
        // Owner 2: composer-inset relayout replaying FollowingLatest on every viewport change.
        val composerRelayout =
            launch {
                var lastViewport = -1 to -1
                while (frame < SettleFrames) {
                    val info = list.info()
                    val viewport = info.viewportStartOffset to info.viewportEndOffset
                    if (viewport != lastViewport) {
                        lastViewport = viewport
                        list.scrollToItem(PaddingForwardIndex, assertNotNull(threadRenderedBottomScrollOffset(info, PaddingForwardIndex)))
                    }
                    delay(FrameMs)
                }
            }
        frames.join()
        initialAnchor.join()
        composerRelayout.cancel()

        assertEquals(List(SettleFrames) { 0 }, gapsPerFrame, "the newest row never leaves the composer")
        assertEquals(0, list.moves, "no owner moves a list that is already at its final position")
        assertEquals(0, list.rawScrollPx)
        assertEquals(0, anchorAttempts, "the initial anchor sees it anchored on its first check")
    }

    @Test
    fun aDisplacedListIsAnchoredInOneCommandUnderTheInset() {
        // The feedback log's displaced state: raw scrolled 135px, newest row 135px below the composer.
        val list = FakeReversedList(topInsetPx = TopInsetPx, layoutPx = LayoutWithComposerPx)
        list.scrollToItem(NewestForwardIndex, scrollOffset = 366 - TopInsetPx)
        assertEquals(TopInsetPx, list.info().newestRowGapToRenderedBottom())
        assertFalse(threadItemAtRenderedBottom(list.info(), NewestForwardIndex, PaddingRowPx))

        list.scrollToItem(NewestForwardIndex, assertNotNull(threadRenderedBottomScrollOffset(list.info(), NewestForwardIndex, PaddingRowPx)))
        assertTrue(threadItemAtRenderedBottom(list.info(), NewestForwardIndex, PaddingRowPx))
        assertEquals(0, list.rawScrollPx)
    }

    @Test
    fun theOldFullViewportTargetDisagreedWithTheAnchoredCheckByTheInset() {
        // Negative control for the root cause: a target computed from the whole viewport height
        // (end - start) counts the top inset twice and parks the newest row one inset too low, which
        // the anchored check rejects, so the initial anchor and the composer relayout kept fighting.
        val list = FakeReversedList(topInsetPx = TopInsetPx, layoutPx = LayoutWithComposerPx)
        val info = list.info()
        val legacyOffset =
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = NewestRowPx,
                viewportHeight = info.viewportEndOffset - info.viewportStartOffset,
                visibleItemCount = info.visibleItemsInfo.size,
                totalItemCount = info.totalItemsCount,
                trailingPaddingSize = PaddingRowPx
            )
        assertEquals(TopInsetPx, assertNotNull(threadRenderedBottomScrollOffset(info, NewestForwardIndex, PaddingRowPx)) - legacyOffset)
        list.scrollToItem(NewestForwardIndex, legacyOffset)
        assertEquals(TopInsetPx, list.info().newestRowGapToRenderedBottom())
        assertFalse(threadItemAtRenderedBottom(list.info(), NewestForwardIndex, PaddingRowPx))

        // Without an inset both agree, which is why only cold (stand-in) opens bounced.
        val noInset = FakeReversedList(topInsetPx = 0, layoutPx = LayoutWithComposerPx).info()
        assertEquals(
            threadRenderedBottomScrollOffset(noInset, NewestForwardIndex, PaddingRowPx),
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = NewestRowPx,
                viewportHeight = noInset.viewportEndOffset - noInset.viewportStartOffset,
                visibleItemCount = noInset.visibleItemsInfo.size,
                totalItemCount = noInset.totalItemsCount,
                trailingPaddingSize = PaddingRowPx
            )
        )
    }

    /** One store emission as the route sees it. */
    private data class Emission(
        val access: ThreadAccessResolution,
        val homeLookupComplete: Boolean,
        val payload: RaftThreadScreenPayload,
    )

    /** Runs the route's reveal latch over [emissions]; returns, per emission, the mount plan. */
    private fun mountPlans(emissions: List<Emission>): List<ThreadRouteMountPlan> {
        var revealed = false
        return emissions.map { emission ->
            val hold = threadHoldsFirstContentReveal(revealed, emission.access, emission.homeLookupComplete)
            if (!hold) revealed = true
            emission.payload.threadRouteMountPlan(
                timelineSlice = emission.payload.toThreadTimelineSlice(),
                accessResolution = emission.access,
                holdFirstContentReveal = hold
            )
        }
    }

    @Test
    fun theStandInIsRevealedTogetherWithItsComposer() {
        // The logged sequence: stand-in published, refresh resets the Home lookup, the lookup resolves
        // access, the network answer replaces the stand-in, and a later refresh re-runs the lookup.
        val standIn = contentPayload().copy(durableLastKnown = true)
        val emissions =
            listOf(
                Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = standIn),
                Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = standIn.copy(backgroundRefreshing = true)),
                Emission(ThreadAccessResolution.Resolved, homeLookupComplete = true, payload = standIn.copy(backgroundRefreshing = true)),
                Emission(ThreadAccessResolution.Resolved, homeLookupComplete = true, payload = contentPayload()),
                Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = contentPayload().copy(backgroundRefreshing = true)),
            )
        val plans = mountPlans(emissions)
        assertEquals(
            listOf(
                ThreadRouteMountPlan.LoadingShell,
                ThreadRouteMountPlan.LoadingShell,
                ThreadRouteMountPlan.FeatureSurfaces,
                ThreadRouteMountPlan.FeatureSurfaces,
                ThreadRouteMountPlan.FeatureSurfaces,
            ),
            plans,
            "held until the lookup answers, never hidden again by a later refresh"
        )
        val firstRevealed = emissions[plans.indexOf(ThreadRouteMountPlan.FeatureSurfaces)]
        assertTrue(
            firstRevealed.payload.isThreadPrimaryFooterReady(firstRevealed.payload.toThreadTimelineSlice(), firstRevealed.access),
            "the first content frame already has its composer"
        )
        assertFalse(threadOfflineLastKnownChrome(firstRevealed.payload).tipVisible, "no offline tip while online")
    }

    @Test
    fun anOfflineStandInIsNotHeldForTheNetwork() {
        // Home has no access candidate: the lookup answers with access still Loading. The stand-in is
        // revealed then (bounded local lookup), not after the network times out.
        val standIn = contentPayload().copy(durableLastKnown = true)
        val plans =
            mountPlans(
                listOf(
                    Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = standIn),
                    Emission(ThreadAccessResolution.Loading, homeLookupComplete = true, payload = standIn),
                    Emission(
                        ThreadAccessResolution.Loading,
                        homeLookupComplete = true,
                        payload = standIn.copy(mainLoadFailed = true, errorCode = "network_timeout")
                    ),
                )
            )
        assertEquals(
            listOf(ThreadRouteMountPlan.LoadingShell, ThreadRouteMountPlan.FeatureSurfaces, ThreadRouteMountPlan.FeatureSurfaces),
            plans
        )
        assertTrue(
            threadOfflineLastKnownChrome(standIn.copy(mainLoadFailed = true, errorCode = "network_timeout")).tipVisible,
            "the tip appears once the load really failed"
        )
    }

    private fun contentPayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-dm-1",
            sourceThreadId = "dm-1",
            status = "content",
            messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u-a", authorName = "artin", body = "Hands feedback")),
        )

    private companion object {
        const val TopInsetPx = 135
        const val LayoutWithComposerPx = 1941
        const val PaddingRowPx = 54
        const val NewestRowPx = 2118
        const val OlderRowPx = 525

        /** Forward indices of the three rows: older message, newest message, bottom padding row. */
        const val NewestForwardIndex = 1
        const val PaddingForwardIndex = 2

        const val FrameMs = 16L
        const val InitialAnchorRetryMs = 32L
        const val SettleFrames = 12
        const val NetworkAnswerFrame = 6
    }
}
