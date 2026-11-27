package build.raft.app

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ReversedThreadLayoutInfo
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.ThreadRouteMountPlan
import build.raft.app.thread.forwardScrollToItemDelta
import build.raft.app.thread.isThreadPrimaryFooterReady
import build.raft.app.thread.key
import build.raft.app.thread.threadBottomSpacerAndAnchorTrailingHeight
import build.raft.app.thread.threadItemAtRenderedBottom
import build.raft.app.thread.threadOfflineLastKnownChrome
import build.raft.app.thread.threadRenderedBottomScrollOffset
import build.raft.app.thread.threadReservesPendingComposerSlot
import build.raft.app.thread.threadRouteMountPlan
import build.raft.app.thread.toThreadTimelineSlice
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
 * (density 3.375): a reversed list 1941px tall with a 135px top inset, the bottom padding row
 * and a 2118px newest message. The padding row was 54px (16dp) then, as it is again while nobody
 * types or works there; it is the production [threadBottomSpacerAndAnchorTrailingHeight] here, the
 * one height the list's last row and the scroll owners' trailing padding share (a line already
 * showing at open grows both alike, so it changes none of this).
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

    /** One store emission as the route sees it. */
    private data class Emission(
        val access: ThreadAccessResolution,
        val homeLookupComplete: Boolean,
        val payload: RaftThreadScreenPayload,
    )

    /** The route's mount plan per emission (#171: no hold, ready content is shown). */
    private fun mountPlans(emissions: List<Emission>): List<ThreadRouteMountPlan> =
        emissions.map { emission ->
            emission.payload.threadRouteMountPlan(
                timelineSlice = emission.payload.toThreadTimelineSlice(),
                accessResolution = emission.access
            )
        }

    private fun reservesComposerSlot(emission: Emission): Boolean =
        threadReservesPendingComposerSlot(
            accessResolution = emission.access,
            payloadStatus = emission.payload.status,
            joined = emission.payload.joined,
            showComposer = emission.payload.showComposer,
            selectedTab = emission.payload.selectedTab,
            composerLocked = false
        )

    @Test
    fun aCachedStandInIsShownAtOnceWithTheComposerSlotReserved() {
        // #171: the stand-in is shown while the Home lookup is still running (it used to wait behind
        // "Loading"); the composer's slot keeps its resting height until access resolves, so the
        // bottom-pinned list is laid out at its final height on the first content frame.
        val standIn = contentPayload().copy(durableLastKnown = true)
        val emissions =
            listOf(
                Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = standIn),
                Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = standIn.copy(backgroundRefreshing = true)),
                Emission(ThreadAccessResolution.Resolved, homeLookupComplete = true, payload = standIn.copy(backgroundRefreshing = true)),
                Emission(ThreadAccessResolution.Resolved, homeLookupComplete = true, payload = contentPayload()),
                Emission(ThreadAccessResolution.Loading, homeLookupComplete = false, payload = contentPayload().copy(backgroundRefreshing = true)),
            )
        assertEquals(List(emissions.size) { ThreadRouteMountPlan.FeatureSurfaces }, mountPlans(emissions), "never Loading over ready content")
        val first = emissions.first()
        assertFalse(first.payload.isThreadPrimaryFooterReady(first.payload.toThreadTimelineSlice(), first.access), "access still resolving")
        assertTrue(reservesComposerSlot(first), "the composer slot is reserved on the first content frame")
        assertFalse(threadOfflineLastKnownChrome(first.payload).tipVisible, "no offline tip while online")
    }

    @Test
    fun anOfflineStandInIsShownWithoutWaitingForTheNetwork() {
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
        assertEquals(List(3) { ThreadRouteMountPlan.FeatureSurfaces }, plans)
        assertTrue(
            threadOfflineLastKnownChrome(standIn.copy(mainLoadFailed = true, errorCode = "network_timeout")).tipVisible,
            "the tip appears once the load really failed"
        )
    }

    @Test
    fun sparseTopAnchoredTaskConversationCountsAsAnchoredOnlyWhenWhollyVisible() {
        // Short task conversation laid out with reverseLayout = true and Arrangement.Top inside a
        // 1600px viewport: head (500px) + 1 reply (120px) + bottom padding (54px) = 674px total,
        // leaving 926px of blank space below the padding row (so raw index 0 starts at rawOffset = 926).
        val viewportPx = 1600
        val headPx = 500
        val replyPx = 120
        val padPx = 54
        val topSpaceBelow = viewportPx - (headPx + replyPx + padPx)
        val shortRaw =
            listOf(
                RawItem(index = 0, offset = topSpaceBelow, size = padPx),
                RawItem(index = 1, offset = topSpaceBelow + padPx, size = replyPx),
                RawItem(index = 2, offset = topSpaceBelow + padPx + replyPx, size = headPx),
            )
        val shortInfo = ReversedThreadLayoutInfo(RawInfo(shortRaw, totalItemsCount = 3, layoutSize = viewportPx, afterContentPadding = 0))
        assertEquals(0, shortInfo.visibleItemsInfo.first().offset, "head starts flush at visual y = 0")
        assertFalse(
            threadItemAtRenderedBottom(shortInfo, itemIndex = 1, trailingPaddingSize = padPx, allowSparseTopFit = false),
            "bottom-anchored channels still require newest item at viewport bottom"
        )
        assertTrue(
            threadItemAtRenderedBottom(shortInfo, itemIndex = 1, trailingPaddingSize = padPx, allowSparseTopFit = true),
            "sparse top-anchored task conversation settles immediately without stalling initialBottomAnchorReady"
        )

        // Tall conversation scrolled up away from the bottom (newest item bottom extends past viewport):
        // allowSparseTopFit must NOT treat a scrolled-up overflowing conversation as anchored at bottom.
        val tallOverflowRaw =
            listOf(
                RawItem(index = 0, offset = -300, size = padPx),
                RawItem(index = 1, offset = -300 + padPx, size = 400),
                RawItem(index = 2, offset = -300 + padPx + 400, size = 1600),
            )
        val tallInfo = ReversedThreadLayoutInfo(RawInfo(tallOverflowRaw, totalItemsCount = 3, layoutSize = viewportPx, afterContentPadding = 0))
        assertFalse(
            threadItemAtRenderedBottom(tallInfo, itemIndex = 1, trailingPaddingSize = padPx, allowSparseTopFit = true),
            "overflowing conversation scrolled away from bottom is not anchored"
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
        const val Density = 3.375f

        /** The list's last row, and the trailing padding the owners anchor the newest message with. */
        val PaddingRowPx = (threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = false).value * Density).toInt()
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
