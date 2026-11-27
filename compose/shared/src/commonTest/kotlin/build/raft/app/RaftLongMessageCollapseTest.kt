package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftLongMessageCollapsePreferenceStore
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.raftLongMessageCollapseEffective
import build.raft.app.data.raftLongMessageCollapseOverrideKey
import build.raft.app.data.raftLongMessageCollapseParseOverride
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftLongMessageCollapseController
import build.raft.app.thread.RaftLongMessageRawScrollRequest
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.longMessageCollapseConversationId
import build.raft.app.thread.raftForwardPreviewTruncatedText
import build.raft.app.thread.raftLongMessageCollapsible
import build.raft.app.thread.raftLongMessageSearchHitInBody
import build.raft.app.thread.raftLongMessageToggleScrollPlan
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
    fun bodyCollapsesOnlyWhenTallerThanThresholdPlusMinimumHiddenPart() {
        assertFalse(raftLongMessageCollapsible(false, contentHeightPx = 1600, thresholdPx = 1600, minHiddenPx = 100))
        assertFalse(raftLongMessageCollapsible(false, contentHeightPx = 1700, thresholdPx = 1600, minHiddenPx = 100))
        assertTrue(raftLongMessageCollapsible(false, contentHeightPx = 1701, thresholdPx = 1600, minHiddenPx = 100))
    }

    @Test
    fun nothingCollapsesBeforeTheViewportIsKnown() {
        assertFalse(raftLongMessageCollapsible(false, contentHeightPx = 99_999, thresholdPx = 0, minHiddenPx = 0))
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

    // --- effective setting -----------------------------------------------------------------

    @Test
    fun effectiveSettingIsOverrideElseGlobal() {
        assertTrue(raftLongMessageCollapseEffective(globalEnabled = true, channelOverride = null))
        assertFalse(raftLongMessageCollapseEffective(globalEnabled = false, channelOverride = null))
        assertFalse(raftLongMessageCollapseEffective(globalEnabled = true, channelOverride = false))
        assertTrue(raftLongMessageCollapseEffective(globalEnabled = false, channelOverride = true))
    }

    @Test
    fun globalDefaultsOffButAnExplicitChoiceIsKept() {
        // Web #10275 (artin 2026-10-27): long messages show in full unless a person turns collapsing on.
        val fresh = RaftLongMessageCollapsePreferenceStore(InMemoryRaftKeyValueStore())
        assertFalse(fresh.readGlobalEnabled())
        val optedIn = RaftLongMessageCollapsePreferenceStore(globalOnStore())
        assertTrue(optedIn.readGlobalEnabled())
        val optedOut =
            RaftLongMessageCollapsePreferenceStore(
                InMemoryRaftKeyValueStore(mapOf(GlobalCollapseKey to "false"))
            )
        assertFalse(optedOut.readGlobalEnabled())
    }

    @Test
    fun perConversationOverrideIsKeyedByServerAndChannel() = runBlocking {
        val kv = globalOnStore() // these cases start from a global ON switch
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
        val kv = globalOnStore() // these cases start from a global ON switch
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
                mapOf(GlobalCollapseKey to "true", raftLongMessageCollapseOverrideKey("srv", "ch") to "off")
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
    fun replyThreadUsesItsParentConversationOverride() {
        assertEquals(
            "parent",
            RaftThreadScreenPayload(
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread",
                parentChannelId = "parent"
            ).longMessageCollapseConversationId()
        )
        assertNull(
            RaftThreadScreenPayload(
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread",
                parentChannelId = null
            ).longMessageCollapseConversationId()
        )
        assertEquals(
            "chan",
            RaftThreadScreenPayload(status = "content", surfaceKind = RaftThreadSurfaceKind.Channel, sourceThreadId = "chan")
                .longMessageCollapseConversationId()
        )
        assertNull(
            RaftThreadScreenPayload(
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "unjoined-chan",
                joined = false
            ).longMessageCollapseConversationId()
        )
        assertEquals(
            "dm",
            RaftThreadScreenPayload(status = "content", surfaceKind = RaftThreadSurfaceKind.DirectMessage, sourceThreadId = "dm", joined = false)
                .longMessageCollapseConversationId()
        )
        // Loading routes fail closed when Home has not confirmed membership (except DMs).
        assertNull(
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "unknown-chan",
                joined = true,
            ).longMessageCollapseConversationId()
        )
        assertNull(
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread",
                parentChannelId = "unknown-parent",
                joined = true,
            ).longMessageCollapseConversationId()
        )
        assertEquals(
            "dm",
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                sourceThreadId = "dm",
                joined = false,
            ).longMessageCollapseConversationId()
        )
        // Denied / errored routes must never request display settings even when joined stayed at default true.
        assertNull(
            RaftThreadScreenPayload(
                status = "no_permission",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "denied-chan",
                joined = true,
            ).longMessageCollapseConversationId()
        )
        assertNull(
            RaftThreadScreenPayload(
                status = "error",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "errored-chan",
                joined = true,
            ).longMessageCollapseConversationId()
        )
    }

    @Test
    fun taskConversationNeverRequestsItsOwnIdAndUsesJoinedOrDmParent() {
        val loadingTaskRoute =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                sourceThreadId = "task-conv-1",
                streamChannelId = "task-conv-1",
            )
        val settledJoinedTaskRoute = loadingTaskRoute.copy(status = "content", joined = true)
        val settledUnjoinedTaskRoute = loadingTaskRoute.copy(status = "content", joined = false)
        // Before the task conversation's parent channel resolves, no display-settings request may fire.
        assertNull(loadingTaskRoute.longMessageCollapseConversationId())
        assertNull(settledJoinedTaskRoute.longMessageCollapseConversationId())
        assertNull(loadingTaskRoute.longMessageCollapseConversationId(homeContent = null, taskParentChannelId = null))

        // While loading, fail closed when Home has not confirmed parent membership.
        assertNull(loadingTaskRoute.longMessageCollapseConversationId(resolvedParentChannelId = "unknown-parent"))
        assertEquals(
            "parent-chan",
            loadingTaskRoute.longMessageCollapseConversationId(resolvedParentChannelId = "parent-chan", parentJoined = true)
        )
        assertNull(
            loadingTaskRoute.longMessageCollapseConversationId(resolvedParentChannelId = "unjoined-parent", parentJoined = false)
        )

        // Once settled, follow payload.joined (so live Join overrides stale Home joined=false) and always allow DM parents.
        assertEquals("parent-chan", settledJoinedTaskRoute.longMessageCollapseConversationId(resolvedParentChannelId = "parent-chan"))
        assertNull(settledUnjoinedTaskRoute.longMessageCollapseConversationId(resolvedParentChannelId = "unjoined-parent"))
        assertEquals(
            "unjoined-parent",
            settledJoinedTaskRoute.longMessageCollapseConversationId(resolvedParentChannelId = "unjoined-parent", parentJoined = false)
        )
        assertEquals(
            "dm-parent",
            settledUnjoinedTaskRoute.longMessageCollapseConversationId(
                resolvedParentChannelId = "dm-parent",
                parentJoined = false,
                parentIsDirectMessage = true
            )
        )

        val homeContent =
            SharedHomeContentState.Content(
                snapshot =
                    HomeSnapshot(
                        server = ServerSummary(id = ServerId("srv-1"), name = "Raft"),
                        currentMember =
                            MemberProfile(
                                id = MemberId("u1"),
                                displayName = "Artin",
                                handle = "artin",
                                kind = MemberKind.Human,
                                presence = Presence.Active,
                            ),
                        channels =
                            listOf(
                                HomeChannelRow(
                                    threadId = ThreadId("joined-ch"),
                                    channelId = ChannelId("joined-ch"),
                                    name = "joined",
                                    kind = ConversationKind.Channel,
                                    joined = true,
                                    permissions = ConversationPermissions(canRead = true, canSend = true),
                                ),
                                HomeChannelRow(
                                    threadId = ThreadId("unjoined-ch"),
                                    channelId = ChannelId("unjoined-ch"),
                                    name = "unjoined",
                                    kind = ConversationKind.Channel,
                                    joined = false,
                                    permissions = ConversationPermissions(canRead = true, canSend = false),
                                ),
                            ),
                        directMessages =
                            listOf(
                                HomeDirectMessageRow(
                                    threadId = ThreadId("dm-1"),
                                    channelId = ChannelId("dm-1"),
                                    name = "Lagoon",
                                    kind = ConversationKind.DirectMessage,
                                    joined = false,
                                    permissions = ConversationPermissions(canRead = true, canSend = true),
                                ),
                            ),
                        unreadTotal = 0,
                    ),
                session =
                    StoredAuthSession(
                        user = StoredAuthUser(id = "u1", email = "artin@slock.ai", displayName = "Artin"),
                        accessToken = "access",
                        refreshToken = "refresh",
                        selectedServerId = "srv-1",
                    ),
            )

        assertEquals(
            "joined-ch",
            loadingTaskRoute.longMessageCollapseConversationId(homeContent = homeContent, taskParentChannelId = ChannelId("joined-ch"))
        )
        assertNull(
            loadingTaskRoute.longMessageCollapseConversationId(homeContent = homeContent, taskParentChannelId = ChannelId("unjoined-ch"))
        )
        assertNull(
            loadingTaskRoute.longMessageCollapseConversationId(homeContent = homeContent, taskParentChannelId = ChannelId("missing-from-home"))
        )
        assertEquals(
            "dm-1",
            loadingTaskRoute.longMessageCollapseConversationId(homeContent = homeContent, taskParentChannelId = ChannelId("dm-1"))
        )
        // Live Join inside a task conversation overrides stale Home joined=false.
        assertEquals(
            "unjoined-ch",
            settledJoinedTaskRoute.longMessageCollapseConversationId(homeContent = homeContent, taskParentChannelId = ChannelId("unjoined-ch"))
        )
        // Top-level loading channel uses Home's joined flag and fails closed if absent from Home.
        assertEquals(
            "joined-ch",
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "joined-ch",
                status = "loading",
                joined = true,
            ).longMessageCollapseConversationId(homeContent = homeContent)
        )
        assertNull(
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "unjoined-ch",
                status = "loading",
                joined = true,
            ).longMessageCollapseConversationId(homeContent = homeContent)
        )
        assertNull(
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "missing-lounge",
                status = "loading",
                joined = true,
            ).longMessageCollapseConversationId(homeContent = homeContent)
        )
        // After a live Join updates payload to content+joined=true, stale Home joined=false does not block it.
        assertEquals(
            "unjoined-ch",
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "unjoined-ch",
                status = "content",
                joined = true,
            ).longMessageCollapseConversationId(homeContent = homeContent)
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

    /** The global switch stored ON (no revision bump), as a person who turned collapsing on has it. */
    private fun globalOnStore() = InMemoryRaftKeyValueStore(mapOf(GlobalCollapseKey to "true"))
}

/** Persisted key of the global switch (unchanged since the old implementation). */
private const val GlobalCollapseKey = "chatLayout:long_message_collapse_enabled"
