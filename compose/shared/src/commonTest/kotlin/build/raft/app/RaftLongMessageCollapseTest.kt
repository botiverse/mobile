package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftLongMessageCollapseDetailKind
import build.raft.app.data.RaftLongMessageCollapsePreferenceStore
import build.raft.app.data.raftLongMessageCollapseDetailKind
import build.raft.app.data.raftLongMessageCollapseEffective
import build.raft.app.data.raftLongMessageCollapseOverrideAfterSet
import build.raft.app.data.raftLongMessageCollapseOverrideKey
import build.raft.app.data.raftLongMessageCollapseParseOverride
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The viewport-fraction tests run without the fixed 320dp cap.
private const val NoCap = Int.MAX_VALUE

class RaftLongMessageCollapseTest {
    // --- threshold -------------------------------------------------------------------------

    @Test
    fun thresholdIsTheWebFixedHeightCappedAtEightyPercentOfViewport() {
        // 320dp at density 2.75 = 880px: a phone viewport clips at the web's fixed height.
        assertEquals(880, raftLongMessageCollapseThresholdPx(2000, maxThresholdPx = 880))
        // A short viewport (landscape, split screen) clips at 80% so the toggle stays on screen.
        assertEquals(720, raftLongMessageCollapseThresholdPx(900, maxThresholdPx = 880))
        assertEquals(1600, raftLongMessageCollapseThresholdPx(2000, maxThresholdPx = NoCap))
        assertEquals(0, raftLongMessageCollapseThresholdPx(0, maxThresholdPx = 880))
        assertEquals(0, raftLongMessageCollapseThresholdPx(-5, maxThresholdPx = 880))
    }

    @Test
    fun aMessageOfAboutHalfAPhoneScreenCollapses() {
        // Regression (Android build 5): with the threshold at 80% of the viewport a body had to be
        // taller than ~1730px of a 2000px list before it collapsed, so ordinary long messages
        // rendered in full. At the web's 320dp a ~440dp body collapses.
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000, maxThresholdPx = 880)
        assertEquals(880, controller.thresholdPx)
        assertTrue(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 1200, minHiddenPx = 132))
        assertEquals(880, raftLongMessageVisibleBodyHeight(1200, controller.thresholdPx, collapsible = true, expanded = false))
        // A body only a little taller than 320dp still renders in full.
        assertFalse(controller.recordMeasurement("m2", "m2:a", contentHeightPx = 960, minHiddenPx = 132))
    }

    @Test
    fun keyboardDoesNotLowerTheThresholdBelowTheFixedHeight() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000, maxThresholdPx = 880)
        controller.onViewportMeasured(1080, 900, maxThresholdPx = 880) // IME open
        assertEquals(880, controller.thresholdPx)
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
    fun lateLoadingImageTurnsBodyCollapsibleAndSmallShrinksDoNotFlapIt() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000, maxThresholdPx = NoCap) // threshold 1600
        assertFalse(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 900, minHiddenPx = 48))
        assertFalse(controller.isCollapsible("m1:a"))
        // Image finishes loading: taller than the threshold.
        assertTrue(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 2400, minHiddenPx = 48))
        assertTrue(controller.isCollapsible("m1:a"))
        // A small shrink inside the hysteresis band (placeholder swap) keeps it collapsible...
        assertTrue(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 1620, minHiddenPx = 48))
        // ...while a fresh body of that height would not have collapsed.
        assertFalse(controller.recordMeasurement("m2", "m2:a", contentHeightPx = 1620, minHiddenPx = 48))
    }

    @Test
    fun toggleDisappearsWhenTheThresholdGrowsPastTheContent() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 1000, maxThresholdPx = NoCap) // threshold 800
        assertTrue(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 1200, minHiddenPx = 48))
        // Rotation to a taller viewport: threshold 1600 > content, nothing left to hide.
        controller.onViewportMeasured(1200, 2000, maxThresholdPx = NoCap)
        assertFalse(controller.recordMeasurement("m1", "m1:a", contentHeightPx = 1200, minHiddenPx = 48))
        assertFalse(controller.isCollapsible("m1:a"))
        assertEquals(1200, raftLongMessageVisibleBodyHeight(1200, 1600, collapsible = false, expanded = false))
    }

    @Test
    fun collapsibleOnlyWhileContentExceedsThreshold() {
        assertTrue(raftLongMessageCollapsible(true, contentHeightPx = 1601, thresholdPx = 1600, minHiddenPx = 48))
        assertFalse(raftLongMessageCollapsible(true, contentHeightPx = 1600, thresholdPx = 1600, minHiddenPx = 48))
        assertFalse(raftLongMessageCollapsible(true, contentHeightPx = 5000, thresholdPx = 0, minHiddenPx = 48))
    }

    @Test
    fun editedContentIsMeasuredAfresh() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000, maxThresholdPx = NoCap)
        assertTrue(controller.recordMeasurement("m1", "m1:long", contentHeightPx = 3000, minHiddenPx = 48))
        assertFalse(controller.recordMeasurement("m1", "m1:short", contentHeightPx = 300, minHiddenPx = 48))
    }

    @Test
    fun expandedStateIsPerMessageIdAndSurvivesRemeasurement() {
        val controller = RaftLongMessageCollapseController()
        controller.onViewportMeasured(1080, 2000, maxThresholdPx = NoCap)
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
        controller.onViewportMeasured(1080, 2000, maxThresholdPx = NoCap)
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
        assertEquals("off@0", kv.getString(raftLongMessageCollapseOverrideKey("srv-1", "ch-1")))
        // Other channel / other server follow the global switch.
        assertTrue(store.readConversation("srv-1", "ch-2").effective)
        assertTrue(store.readConversation("srv-2", "ch-1").effective)

        // Setting the channel back to the global value clears the override.
        val follow = store.setConversationEnabled("srv-1", "ch-1", enabled = true)
        assertTrue(follow.followsGlobal)
        assertNull(kv.getString(raftLongMessageCollapseOverrideKey("srv-1", "ch-1")))

        // Global off: the other channel follows; an ON override set now is live.
        chatLayout.saveLongMessageCollapseEnabled(false)
        assertFalse(store.readConversation("srv-1", "ch-2").effective)
        val on = store.setConversationEnabled("srv-1", "ch-1", enabled = true)
        assertEquals(true, on.channelOverride)
        assertTrue(on.effective)
    }

    @Test
    fun globalChangeVoidsOverridesAndTheyStayVoidWhenGlobalChangesBack() = runBlocking {
        val kv = InMemoryRaftKeyValueStore()
        val store = RaftLongMessageCollapsePreferenceStore(kv)
        val chatLayout = KeyValueChatLayoutPreferenceStore(kv)
        store.setConversationEnabled("srv", "ch", enabled = false) // global on, channel off

        chatLayout.saveLongMessageCollapseEnabled(false) // global now equals the override
        val afterOff = store.readConversation("srv", "ch")
        assertTrue(afterOff.followsGlobal)
        assertFalse(afterOff.effective)

        chatLayout.saveLongMessageCollapseEnabled(true) // and back: the channel follows, now on
        val afterOn = store.readConversation("srv", "ch")
        assertTrue(afterOn.followsGlobal)
        assertTrue(afterOn.effective)
        // Reads are pure; the void entry is removed by the explicit prune.
        assertEquals("off@0", kv.getString(raftLongMessageCollapseOverrideKey("srv", "ch")))
        store.pruneVoidOverride("srv", "ch")
        assertNull(kv.getString(raftLongMessageCollapseOverrideKey("srv", "ch")))
    }

    @Test
    fun bareOnOffOverridesFromTheFirstReleaseStayLiveAtRevisionZero() = runBlocking {
        val kv =
            InMemoryRaftKeyValueStore(
                mapOf(raftLongMessageCollapseOverrideKey("srv", "ch") to "off")
            )
        val store = RaftLongMessageCollapsePreferenceStore(kv)
        assertEquals(false, store.readChannelOverride("srv", "ch"))
        store.pruneVoidOverride("srv", "ch")
        assertEquals("off", kv.getString(raftLongMessageCollapseOverrideKey("srv", "ch")))
        // The next global change voids it like any other override.
        KeyValueChatLayoutPreferenceStore(kv).saveLongMessageCollapseEnabled(false)
        assertNull(store.readChannelOverride("srv", "ch"))
        assertNull(raftLongMessageCollapseParseOverride("maybe"))
        assertNull(raftLongMessageCollapseParseOverride("on@x"))
    }

    @Test
    fun voidedOverrideIsIgnoredEvenIfNeverReadBetweenTwoGlobalFlips() = runBlocking {
        val kv = InMemoryRaftKeyValueStore()
        val store = RaftLongMessageCollapsePreferenceStore(kv)
        val chatLayout = KeyValueChatLayoutPreferenceStore(kv)
        store.setConversationEnabled("srv", "ch", enabled = false)
        chatLayout.saveLongMessageCollapseEnabled(false)
        chatLayout.saveLongMessageCollapseEnabled(true)
        assertTrue(store.readConversation("srv", "ch").followsGlobal)
        assertTrue(store.readConversation("srv", "ch").effective)
    }

    @Test
    fun detailLineComesFromBothGlobalAndOverride() {
        assertEquals(RaftLongMessageCollapseDetailKind.FollowsGlobalOn, raftLongMessageCollapseDetailKind(true, null))
        assertEquals(RaftLongMessageCollapseDetailKind.FollowsGlobalOff, raftLongMessageCollapseDetailKind(false, null))
        assertEquals(RaftLongMessageCollapseDetailKind.OverrideOff, raftLongMessageCollapseDetailKind(true, false))
        assertEquals(RaftLongMessageCollapseDetailKind.OverrideOn, raftLongMessageCollapseDetailKind(false, true))
        // An override equal to the global switch is not an override.
        assertEquals(RaftLongMessageCollapseDetailKind.FollowsGlobalOn, raftLongMessageCollapseDetailKind(true, true))
        assertEquals(RaftLongMessageCollapseDetailKind.FollowsGlobalOff, raftLongMessageCollapseDetailKind(false, false))
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
