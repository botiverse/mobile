package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftLongMessageCollapsePreferenceStore
import build.raft.app.data.raftLongMessageCollapseEffective
import build.raft.app.data.raftLongMessageCollapseOverrideAfterSet
import build.raft.app.data.raftLongMessageCollapseOverrideKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftLongMessageCollapseTest {
    // --- threshold -------------------------------------------------------------------------

    @Test
    fun thresholdIsEightyPercentOfViewportAndZeroWithoutViewport() {
        assertEquals(1600, raftLongMessageCollapseThresholdPx(2000))
        assertEquals(0, raftLongMessageCollapseThresholdPx(0))
        assertEquals(0, raftLongMessageCollapseThresholdPx(-5))
    }

    @Test
    fun keyboardShrinkingViewportKeepsTallestHeightUntilWidthChanges() {
        val open = raftLongMessageStableViewport(null, RaftLongMessageViewport(1080, 2000))
        val withIme = raftLongMessageStableViewport(open, RaftLongMessageViewport(1080, 1100))
        assertEquals(2000, withIme.height)
        val rotated = raftLongMessageStableViewport(withIme, RaftLongMessageViewport(2000, 900))
        assertEquals(RaftLongMessageViewport(2000, 900), rotated)
    }

    @Test
    fun bodyCollapsesOnlyWhenTallerThanThresholdPlusMinimumHiddenPart() {
        assertFalse(raftLongMessageCollapsible(false, contentHeightPx = 1600, thresholdPx = 1600, minHiddenPx = 100))
        assertFalse(raftLongMessageCollapsible(false, contentHeightPx = 1700, thresholdPx = 1600, minHiddenPx = 100))
        assertTrue(raftLongMessageCollapsible(false, contentHeightPx = 1701, thresholdPx = 1600, minHiddenPx = 100))
    }

    @Test
    fun nothingCollapsesBeforeTheViewportIsKnown() {
        assertFalse(raftLongMessageCollapsible(false, contentHeightPx = 99_999, thresholdPx = 0, minHiddenPx = 0))
    }

    @Test
    fun visibleHeightIsClippedOnlyWhileCollapsed() {
        assertEquals(1600, raftLongMessageVisibleBodyHeight(4000, 1600, collapsible = true, expanded = false))
        assertEquals(4000, raftLongMessageVisibleBodyHeight(4000, 1600, collapsible = true, expanded = true))
        assertEquals(1500, raftLongMessageVisibleBodyHeight(1500, 1600, collapsible = false, expanded = false))
    }

    // --- one-way re-evaluation -------------------------------------------------------------

    @Test
    fun lateLoadingImageTurnsBodyCollapsibleAndShrinkingNeverFlapsItBack() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000) // threshold 1600
        assertFalse(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 900, minHiddenPx = 48))
        assertFalse(controller.isCollapsible("m1:a"))
        // Image finishes loading: taller than the threshold.
        assertTrue(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 2400, minHiddenPx = 48))
        assertTrue(controller.isCollapsible("m1:a"))
        // A later smaller measurement (placeholder swap) must not un-collapse it.
        assertTrue(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 1000, minHiddenPx = 48))
        assertTrue(controller.isCollapsible("m1:a"))
    }

    @Test
    fun editedContentIsMeasuredAfresh() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000)
        assertTrue(controller.recordMeasurement("m1", "m1:long", contentHeightPx = 3000, minHiddenPx = 48))
        assertFalse(controller.recordMeasurement("m1", "m1:short", contentHeightPx = 300, minHiddenPx = 48))
    }

    @Test
    fun expandedStateIsPerMessageIdAndSurvivesRemeasurement() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000)
        controller.recordMeasurement("m1", "m1:a", 3000, 48)
        controller.setExpanded("m1", true)
        // Row scrolled away and back: measured again, still expanded.
        controller.recordMeasurement("m1", "m1:a", 3000, 48)
        assertTrue(controller.isExpanded("m1"))
        assertFalse(controller.isExpanded("m2"))
        controller.setExpanded("m1", false)
        assertFalse(controller.isExpanded("m1"))
    }

    @Test
    fun toggleHeightDeltaIsTheHiddenPart() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000)
        controller.recordMeasurement("m1", "m1:a", 3000, 48)
        assertEquals(1400, controller.toggleHeightDelta("m1", expanding = true))
        assertEquals(-1400, controller.toggleHeightDelta("m1", expanding = false))
        assertEquals(0, controller.toggleHeightDelta("unknown", expanding = true))
    }

    // --- auto-expand -----------------------------------------------------------------------

    @Test
    fun jumpToMessageAutoExpands() {
        val controller = RaftLongMessageCollapseController()
        controller.autoExpand("m1")
        assertTrue(controller.isExpanded("m1"))
        controller.autoExpand(null)
        controller.autoExpand(" ")
        assertFalse(controller.isExpanded(" "))
    }

    @Test
    fun searchHitExpandsOnlyWhenTheBodyContainsTheQuery() {
        assertTrue(raftLongMessageSearchHitInBody("line 1\n...\nthe Needle is here", "needle"))
        assertFalse(raftLongMessageSearchHitInBody("nothing relevant", "needle"))
        assertFalse(raftLongMessageSearchHitInBody("anything", "   "))
    }

    // --- scroll anchoring ------------------------------------------------------------------

    @Test
    fun topDownExpandKeepsTopWithoutAnyRawRequest() {
        val plan =
            raftLongMessageToggleScrollPlan(
                reverseLayout = false,
                expanding = true,
                forwardIndex = 5,
                totalItemsCount = 10,
                itemTopBefore = 300,
                rawItemOffsetBefore = 300,
                heightDelta = 1400
            )
        assertEquals(300, plan.desiredTop)
        assertNull(plan.rawScrollRequest)
    }

    @Test
    fun topDownCollapseBringsAScrolledAwayTopBackToTheViewportTop() {
        val plan =
            raftLongMessageToggleScrollPlan(
                reverseLayout = false,
                expanding = false,
                forwardIndex = 5,
                totalItemsCount = 10,
                itemTopBefore = -900,
                rawItemOffsetBefore = -900,
                heightDelta = -1400
            )
        assertEquals(0, plan.desiredTop)
        assertEquals(RaftLongMessageRawScrollRequest(rawIndex = 5, scrollOffset = 0), plan.rawScrollRequest)
    }

    @Test
    fun bottomUpExpandMovesTheRowBottomDownSoTheTopStays() {
        // Reversed list of 10: forward 5 is raw 4. Its bottom sits 500px above the bottom edge.
        val plan =
            raftLongMessageToggleScrollPlan(
                reverseLayout = true,
                expanding = true,
                forwardIndex = 5,
                totalItemsCount = 10,
                itemTopBefore = 200,
                rawItemOffsetBefore = 500,
                heightDelta = 1400
            )
        assertEquals(200, plan.desiredTop)
        // New raw offset = 500 - 1400 = -900 -> requested as first visible scrolled 900px past the edge.
        assertEquals(RaftLongMessageRawScrollRequest(rawIndex = 4, scrollOffset = 900), plan.rawScrollRequest)
    }

    @Test
    fun bottomUpCollapseKeepsAVisibleTop() {
        val plan =
            raftLongMessageToggleScrollPlan(
                reverseLayout = true,
                expanding = false,
                forwardIndex = 9,
                totalItemsCount = 10,
                itemTopBefore = 100,
                rawItemOffsetBefore = -1300,
                heightDelta = -1400
            )
        assertEquals(100, plan.desiredTop)
        assertEquals(RaftLongMessageRawScrollRequest(rawIndex = 0, scrollOffset = -100), plan.rawScrollRequest)
    }

    @Test
    fun bottomUpCollapseWithTopAboveViewportRevealsTheTop() {
        val plan =
            raftLongMessageToggleScrollPlan(
                reverseLayout = true,
                expanding = false,
                forwardIndex = 9,
                totalItemsCount = 10,
                itemTopBefore = -600,
                rawItemOffsetBefore = -2000,
                heightDelta = -1400
            )
        assertEquals(0, plan.desiredTop)
        // bottom shift = -1400 + 600 = -800 -> raw offset -2000 + 800 = -1200.
        assertEquals(RaftLongMessageRawScrollRequest(rawIndex = 0, scrollOffset = 1200), plan.rawScrollRequest)
    }

    // --- effective setting -----------------------------------------------------------------

    @Test
    fun effectiveSettingIsOverrideElseGlobal() {
        assertTrue(raftLongMessageCollapseEffective(globalEnabled = true, channelOverride = null))
        assertFalse(raftLongMessageCollapseEffective(globalEnabled = false, channelOverride = null))
        assertFalse(raftLongMessageCollapseEffective(globalEnabled = true, channelOverride = false))
        assertTrue(raftLongMessageCollapseEffective(globalEnabled = false, channelOverride = true))
    }

    @Test
    fun choosingTheGlobalValueClearsTheOverride() {
        assertNull(raftLongMessageCollapseOverrideAfterSet(globalEnabled = true, requested = true))
        assertEquals(false, raftLongMessageCollapseOverrideAfterSet(globalEnabled = true, requested = false))
        assertEquals(true, raftLongMessageCollapseOverrideAfterSet(globalEnabled = false, requested = true))
    }

    @Test
    fun globalDefaultsOnButAnExplicitOffIsKept() {
        val fresh = RaftLongMessageCollapsePreferenceStore(InMemoryRaftKeyValueStore())
        assertTrue(fresh.readGlobalEnabled())
        val optedOut =
            RaftLongMessageCollapsePreferenceStore(
                InMemoryRaftKeyValueStore(
                    // Persisted key of the global switch (unchanged since the old implementation).
                    mapOf("chatLayout:long_message_collapse_enabled" to "false")
                )
            )
        assertFalse(optedOut.readGlobalEnabled())
    }

    @Test
    fun perConversationOverrideIsKeyedByServerAndChannel() = runBlocking {
        val kv = InMemoryRaftKeyValueStore()
        val store = RaftLongMessageCollapsePreferenceStore(kv)
        val chatLayout = KeyValueChatLayoutPreferenceStore(kv)

        val off = store.setConversationEnabled("srv-1", "ch-1", enabled = false)
        assertEquals(false, off.channelOverride)
        assertFalse(off.effective)
        assertEquals("off", kv.getString(raftLongMessageCollapseOverrideKey("srv-1", "ch-1")))
        // Other channel / other server follow the global switch.
        assertTrue(store.readConversation("srv-1", "ch-2").effective)
        assertTrue(store.readConversation("srv-2", "ch-1").effective)

        // Global off: the overridden channel stays off, another channel follows.
        chatLayout.saveLongMessageCollapseEnabled(false)
        assertFalse(store.readConversation("srv-1", "ch-2").effective)
        // Setting the channel to the global value clears the override.
        val follow = store.setConversationEnabled("srv-1", "ch-1", enabled = false)
        assertTrue(follow.followsGlobal)
        assertNull(kv.getString(raftLongMessageCollapseOverrideKey("srv-1", "ch-1")))
        val on = store.setConversationEnabled("srv-1", "ch-1", enabled = true)
        assertEquals(true, on.channelOverride)
        assertTrue(on.effective)
    }

    @Test
    fun conversationWithoutIdsHasNoOverride() {
        val store = RaftLongMessageCollapsePreferenceStore(InMemoryRaftKeyValueStore())
        val result = store.setConversationEnabled(null, "ch", enabled = false)
        assertNull(result.channelOverride)
        assertTrue(result.effective)
    }

    @Test
    fun replyThreadUsesItsParentConversationOverride() {
        assertEquals(
            "parent",
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread",
                parentChannelId = "parent"
            ).longMessageCollapseConversationId()
        )
        assertEquals(
            "chan",
            RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, sourceThreadId = "chan")
                .longMessageCollapseConversationId()
        )
        assertEquals(
            "dm",
            RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage, sourceThreadId = "dm")
                .longMessageCollapseConversationId()
        )
    }

    // --- forward bundle preview (kept from the old text preview) ---------------------------

    @Test
    fun forwardPreviewTruncationNeverSplitsSurrogatePairs() {
        val body = "a".repeat(9) + "😀" + "b".repeat(10)
        val preview = raftForwardPreviewTruncatedText(body, lineLimit = 8, charLimit = 10)
        assertEquals("a".repeat(9) + "…", preview)
        assertNull(raftForwardPreviewTruncatedText("short", lineLimit = 8, charLimit = 10))
    }
}
