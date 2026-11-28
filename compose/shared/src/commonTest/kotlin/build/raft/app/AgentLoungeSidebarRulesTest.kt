package build.raft.app

import build.raft.app.data.AgentLoungeSummary
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.HomeStandaloneLoungeRow
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ThreadId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Lounge v0: a Lounge's own sidebar row (web slock #13875 / #13876 / #13880,
 * features/sidebar/tests/agentLoungeSidebar.behavior.test.tsx): listed when joined or mentioned
 * whether or not its agent's DM row shows, sorted with the DMs (after them in Manual order),
 * only under Pinned once pinned, shown again while closed only when it has unread or an @, and a
 * DM row's menu.
 */
class AgentLoungeSidebarRulesTest {
    @AfterTest
    fun forgetLounges() = RaftAgentLounges.clear()

    private fun agentDm(agent: String, summary: AgentLoungeSummary?) =
        HomeDirectMessageRow(
            threadId = ThreadId("dm-$agent"),
            channelId = ChannelId("dm-$agent"),
            name = agent.replaceFirstChar { it.uppercase() },
            directMessagePeerType = "agent",
            presenceMemberId = MemberId("agent-$agent"),
            lounge = summary,
        )

    private fun items(dms: List<HomeDirectMessageRow>, markedUnreadIds: Set<String> = emptySet()) =
        homeSidebarAgentLoungeItems(
            enabled = true,
            directMessages = dms,
            known = RaftAgentLounges.lounges.value["server-1"].orEmpty(),
            markedUnreadIds = markedUnreadIds,
            title = { name -> name?.let { "$it's Lounge" } ?: "Lounge" },
        )

    private fun dmItem(id: String, name: String, lastMessageAt: String?) =
        RaftHomeConversationItem(id = id, name = name, kind = "dm", lastMessageAt = lastMessageAt)

    private fun loungeItem(id: String, name: String, lastMessageAt: String?, unread: Int = 0, mentioned: Boolean = false) =
        RaftHomeConversationItem(
            id = id,
            name = name,
            kind = "channel",
            lastMessageAt = lastMessageAt,
            unreadCount = unread,
            mentioned = mentioned,
            agentLounge = RaftHomeAgentLoungeRow(id, unread, mentioned),
        )

    @Test
    fun aJoinedLoungeKnownOnlyByIdIsListedWithoutItsAgentsDmRow() {
        // The first post joined it (no DM row carries it, e.g. the agent's DM was never opened).
        RaftAgentLounges.rememberRow(
            "server-1",
            HomeChannelRow(
                threadId = ThreadId("lounge-bugen"),
                channelId = ChannelId("lounge-bugen"),
                name = "bugen-lounge",
                joined = true,
                lastMessageAt = "2026-11-10T10:00:00.000Z",
                activityNotificationPreference = ChannelActivityNotificationPreference(activityMuted = true, activityMuteSupported = true),
                loungeAgentId = "agent-bugen",
            ),
        )
        val row = items(emptyList()).single()
        assertEquals("lounge-bugen", row.id)
        assertEquals("Lounge", row.name, "no DM row names the agent: the bare Lounge, never its channel name")
        assertEquals("2026-11-10T10:00:00.000Z", row.lastMessageAt)
        assertTrue(row.activityMuted)
        assertTrue(row.activityMuteSupported, "joined and loaded: the menu offers Unmute / Mute")
        assertTrue(items(emptyList(), markedUnreadIds = setOf("lounge-bugen")).single().markedUnread)
    }

    @Test
    fun muteIsRemovedFromLoungesButAlreadyMutedKeepsUnmute() {
        val mentionedNotJoined = AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, mentionCount = 1)
        // Listed (an @), never loaded: no Unmute.
        assertFalse(items(listOf(agentDm("bugen", mentionedNotJoined))).single().activityMuteSupported)
        RaftAgentLounges.rememberRow(
            "server-1",
            HomeChannelRow(
                threadId = ThreadId("lounge-bugen"),
                channelId = ChannelId("lounge-bugen"),
                name = "bugen-lounge",
                joined = true,
                activityNotificationPreference = ChannelActivityNotificationPreference(activityMuted = false, activityMuteSupported = true),
                loungeAgentId = "agent-bugen",
            ),
        )
        assertFalse(items(listOf(agentDm("bugen", mentionedNotJoined))).single().activityMuteSupported, "unmuted Lounge offers no Mute")
        RaftAgentLounges.applyActivityMute(
            "server-1",
            "lounge-bugen",
            ChannelActivityNotificationPreference(activityMuted = true, activityMuteSupported = true),
        )
        assertTrue(items(listOf(agentDm("bugen", mentionedNotJoined))).single().activityMuteSupported, "stored mute keeps Unmute")
    }

    @Test
    fun standaloneLoungesAndLiveFollowedThreadUnreadDriveSidebarRows() {
        val standalone =
            HomeStandaloneLoungeRow(
                agentId = "agent-solo",
                agentName = "Solo",
                avatarWireValue = "https://cdn.example/solo.png",
                canPost = true,
                lounge = AgentLoungeSummary(ChannelId("lounge-solo"), joined = false, unreadCount = 5, mentionCount = 1, listed = true),
            )
        // Before unread enrichment loads, the summary snapshot drives the badge.
        val initial =
            homeSidebarAgentLoungeItems(
                enabled = true,
                directMessages = emptyList(),
                known = emptyMap(),
                markedUnreadIds = emptySet(),
                title = { name -> name?.let { "$it's Lounge" } ?: "Lounge" },
                standaloneLounges = listOf(standalone),
                useLiveFollowedThreadUnread = false,
            ).single()
        assertEquals("lounge-solo", initial.id)
        assertEquals("Solo's Lounge", initial.name)
        assertEquals("https://cdn.example/solo.png", initial.avatarWireValue)
        assertEquals(5, initial.unreadCount)
        assertTrue(initial.mentioned)

        // Once unread enrichment is live, followedThreadUnread drives the non-member Lounge count directly.
        val live =
            homeSidebarAgentLoungeItems(
                enabled = true,
                directMessages = emptyList(),
                known = emptyMap(),
                markedUnreadIds = emptySet(),
                title = { name -> name?.let { "$it's Lounge" } ?: "Lounge" },
                standaloneLounges = listOf(standalone),
                followedThreadUnread =
                    mapOf(
                        "t-1" to HomeFollowedThreadUnread(ChannelId("lounge-solo"), unreadCount = 2, hasMention = false),
                    ),
                useLiveFollowedThreadUnread = true,
            ).single()
        assertEquals(2, live.unreadCount)
        assertFalse(live.mentioned)

        // Reading all followed threads drops live unread to 0 without waiting for GET /channels/dm.
        val afterRead =
            homeSidebarAgentLoungeItems(
                enabled = true,
                directMessages = emptyList(),
                known = emptyMap(),
                markedUnreadIds = emptySet(),
                title = { name -> name?.let { "$it's Lounge" } ?: "Lounge" },
                standaloneLounges = listOf(standalone),
                followedThreadUnread = emptyMap(),
                useLiveFollowedThreadUnread = true,
            ).single()
        assertEquals(0, afterRead.unreadCount)
        assertFalse(afterRead.mentioned)
    }

    @Test
    fun aClosedLoungeShowsAgainOnlyWhileItHasUnreadOrAnAt() {
        val read = loungeItem("lounge-a", "A's Lounge", null)
        val unread = loungeItem("lounge-b", "B's Lounge", null, unread = 2)
        val mentioned = loungeItem("lounge-c", "C's Lounge", null, mentioned = true)
        val open = loungeItem("lounge-d", "D's Lounge", null)
        val shown =
            homeSidebarVisibleAgentLounges(
                listOf(read, unread, mentioned, open),
                shownUnderPinned = emptySet(),
                hiddenDmIds = setOf("lounge-a", "lounge-b", "lounge-c"),
            )
        assertEquals(listOf("lounge-b", "lounge-c", "lounge-d"), shown.map { it.id })
    }

    @Test
    fun aPinnedLoungeShowsOnlyUnderPinned() {
        val lounge = loungeItem("lounge-bugen", "Bugen's Lounge", null)
        val pinnedRows =
            pinnedRowsForSidebar(
                channels = listOf(lounge),
                directMessages = emptyList(),
                pinnedIds = listOf("lounge-bugen"),
                pinnedOrder = listOf("lounge-bugen"),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "lounge-bugen")),
                mode = RaftSidebarSortMode.Manual,
            )
        assertEquals(listOf("lounge-bugen"), pinnedRows.mapNotNull { it.conversation?.id })
        assertTrue(
            homeSidebarVisibleAgentLounges(listOf(lounge), pinnedRows.sidebarPinnedConversationIds(), emptySet()).isEmpty(),
            "not in Direct Messages too",
        )
        // A pin whose Lounge is not listed here shows nothing, and hides nothing.
        assertEquals(listOf("lounge-bugen"), homeSidebarVisibleAgentLounges(listOf(lounge), emptySet(), emptySet()).map { it.id })
    }

    @Test
    fun loungesSortWithTheDmsAndFollowThemInManualOrder() {
        val dmOld = dmItem("dm-old", "Zed", "2026-11-01T00:00:00.000Z")
        val dmNew = dmItem("dm-new", "Amy", "2026-11-09T00:00:00.000Z")
        val loungeMid = loungeItem("lounge-mid", "Bugen's Lounge", "2026-11-05T00:00:00.000Z")
        val loungeLatest = loungeItem("lounge-latest", "Kirby's Lounge", "2026-11-10T00:00:00.000Z")
        val dms = listOf(dmOld, dmNew)
        val lounges = listOf(loungeMid, loungeLatest)
        assertEquals(
            listOf("dm-old", "dm-new", "lounge-latest", "lounge-mid"),
            homeSidebarDirectMessageSectionRows(dms, lounges, RaftSidebarSortMode.Manual).map { it.id },
            "Manual: the DMs' own order, then the Lounges by latest activity",
        )
        assertEquals(
            listOf("lounge-latest", "dm-new", "lounge-mid", "dm-old"),
            homeSidebarDirectMessageSectionRows(dms, lounges, RaftSidebarSortMode.Recent).map { it.id },
        )
        assertEquals(
            listOf("dm-new", "lounge-mid", "lounge-latest", "dm-old"),
            homeSidebarDirectMessageSectionRows(dms, lounges, RaftSidebarSortMode.Az).map { it.id },
        )
        assertEquals(dms, homeSidebarDirectMessageSectionRows(dms, emptyList(), RaftSidebarSortMode.Recent))
    }

    @Test
    fun theLoungeMenuDropsMarkUnreadAndMuteAndUsesConversationReadAll() {
        val entries = { item: RaftHomeConversationItem, pinned: Boolean, markAllRead: Boolean ->
            raftHomeAgentLoungeMenuItems(item, pinned = pinned, markAllReadEnabled = markAllRead).map { it.id to it.dividerBefore }
        }
        val unread = loungeItem("lounge-bugen", "Bugen's Lounge", null, unread = 1)
        assertEquals(
            listOf("mark-all-read" to false, "toggle-pin" to true, "close-dm" to true),
            entries(unread, false, true),
        )
        assertEquals(
            listOf("mark-read" to false, "toggle-pin" to true, "close-dm" to true),
            entries(unread, false, false),
        )
        assertEquals("Unpin", raftHomeAgentLoungeMenuItems(unread, pinned = true).single { it.id == "toggle-pin" }.label)
        // Nothing unread and unmuted: no Mark as Unread, no Mute; Pin starts cleanly without a leading divider.
        assertEquals(
            listOf("toggle-pin" to false, "close-dm" to true),
            entries(loungeItem("lounge-bugen", "Bugen's Lounge", null), false, true),
        )
        // A stored mute keeps its Unmute even when read (no Mark as Unread).
        assertEquals(
            listOf("unmute-channel" to false, "toggle-pin" to true, "close-dm" to true),
            entries(loungeItem("lounge-bugen", "Bugen's Lounge", null).copy(activityMuted = true), false, true),
        )
        // A mention with no count still reads as unread (Mark All as Read clears it).
        assertEquals("mark-all-read", entries(loungeItem("lounge-bugen", "Bugen's Lounge", null, mentioned = true), false, true).first().first)
    }

    @Test
    fun sidebarMergeCollapsesEligibleAgentDmAndLoungeIntoOneRowWithCombinedUnreadAndSelection() {
        val rawDm =
            agentDm(
                "bugen",
                AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true, unreadCount = 3, mentionCount = 1, listed = true),
            )
        val dmRow =
            RaftHomeConversationItem(
                id = "dm-bugen",
                name = "Bugen",
                kind = "dm",
                peerType = "agent",
                presenceMemberId = "agent-bugen",
                unreadCount = 2,
                lastMessageAt = "2026-11-10T09:00:00.000Z",
            )
        val loungeRow =
            RaftHomeConversationItem(
                id = "lounge-bugen",
                name = "Bugen's Lounge",
                kind = "channel",
                unreadCount = 3,
                mentioned = true,
                lastMessageAt = "2026-11-10T11:30:00.000Z",
                presenceMemberId = "agent-bugen",
                agentLounge = RaftHomeAgentLoungeRow("lounge-bugen", unreadCount = 3, mentioned = true, agentId = "agent-bugen", agentName = "Bugen", joined = true),
            )
        val standaloneLounge =
            RaftHomeConversationItem(
                id = "lounge-solo",
                name = "Solo's Lounge",
                kind = "channel",
                unreadCount = 1,
                presenceMemberId = "agent-solo",
                agentLounge = RaftHomeAgentLoungeRow("lounge-solo", unreadCount = 1, mentioned = false, agentId = "agent-solo", agentName = "Solo", joined = true),
            )
        val humanDm =
            RaftHomeConversationItem(
                id = "dm-alice",
                name = "Alice",
                kind = "dm",
                peerType = "human",
                presenceMemberId = "user-alice",
            )
        val assistantDm =
            RaftHomeConversationItem(
                id = "dm-assist",
                name = "Assistant",
                kind = "dm",
                peerType = "agent",
                presenceMemberId = "agent-assist",
            )
        val appBotDm =
            RaftHomeConversationItem(
                id = "dm-bot",
                name = "BuildBot",
                kind = "dm",
                peerType = "agent",
                presenceMemberId = "agent-bot",
            )
        val deletedAgentDm =
            RaftHomeConversationItem(
                id = "dm-gone",
                name = "OldAgent",
                kind = "dm",
                peerType = "agent",
                presenceMemberId = "agent-gone",
            )

        val merged =
            homeSidebarMergeAgentLounges(
                enabled = true,
                directMessages = listOf(dmRow, humanDm, assistantDm, appBotDm, deletedAgentDm),
                agentLounges = listOf(loungeRow, standaloneLounge),
                rawDirectMessages = listOf(rawDm),
                knownLounges = emptyMap(),
                markedUnreadIds = setOf("lounge-bugen"),
                personalAssistantAgentIds = setOf("agent-assist"),
                appBotAgentIds = setOf("agent-bot"),
                deletedAgentIds = setOf("agent-gone"),
            )

        // Standalone Lounge stays separate; merged agent Lounge is suppressed.
        assertEquals(listOf("lounge-solo"), merged.agentLounges.map { it.id })
        val mergedBugen = merged.directMessages.first { it.id == "dm-bugen" }
        assertEquals(5, mergedBugen.unreadCount, "DM 2 + Lounge 3")
        assertEquals(2, mergedBugen.dmSideUnread, "DM side unread preserved separately")
        assertEquals(3, mergedBugen.loungeSideUnread, "Lounge side unread preserved separately")
        assertTrue(mergedBugen.mentioned, "inherits Lounge mention")
        assertTrue(mergedBugen.markedUnread, "inherits Lounge marked-unread")
        assertEquals("2026-11-10T11:30:00.000Z", mergedBugen.lastMessageAt, "newer Lounge timestamp wins")
        assertEquals("lounge-bugen", mergedBugen.mergedAgentLounge?.channelId)
        assertTrue(mergedBugen.opensAgentLoungeOnClick)
        assertTrue(mergedBugen.matchesSidebarSelection("dm-bugen"))
        assertTrue(mergedBugen.matchesSidebarSelection("lounge-bugen"))
        assertEquals(
            RaftHomeConversationSection.DirectMessage,
            homeConversationSectionForSelection("lounge-bugen", emptyList(), merged.directMessages),
        )
        // Non-eligible DMs remain unmerged and carry no per-side split.
        assertFalse(merged.directMessages.first { it.id == "dm-alice" }.opensAgentLoungeOnClick)
        assertNull(merged.directMessages.first { it.id == "dm-alice" }.dmSideUnread)
        assertNull(merged.directMessages.first { it.id == "dm-alice" }.loungeSideUnread)
        assertFalse(merged.directMessages.first { it.id == "dm-assist" }.opensAgentLoungeOnClick)
        assertNull(merged.directMessages.first { it.id == "dm-assist" }.dmSideUnread)
        assertFalse(merged.directMessages.first { it.id == "dm-bot" }.opensAgentLoungeOnClick)
        assertNull(merged.directMessages.first { it.id == "dm-bot" }.dmSideUnread)
        assertFalse(merged.directMessages.first { it.id == "dm-gone" }.opensAgentLoungeOnClick)
        assertNull(merged.directMessages.first { it.id == "dm-gone" }.dmSideUnread)

        // Flag off leaves everything unmerged.
        val disabled =
            homeSidebarMergeAgentLounges(
                enabled = false,
                directMessages = listOf(dmRow),
                agentLounges = listOf(loungeRow),
                rawDirectMessages = listOf(rawDm),
                knownLounges = emptyMap(),
                markedUnreadIds = emptySet(),
            )
        assertEquals(listOf(dmRow), disabled.directMessages)
        assertEquals(listOf(loungeRow), disabled.agentLounges)
    }

    @Test
    fun sidebarMergeRespectsMutedLoungeAndPinnedEdgeCases() {
        val rawDm = agentDm("bugen", AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true, unreadCount = 4, mentionCount = 1, listed = true))
        val dmRow =
            RaftHomeConversationItem(
                id = "dm-bugen",
                name = "Bugen",
                kind = "dm",
                peerType = "agent",
                presenceMemberId = "agent-bugen",
                unreadCount = 1,
            )
        val loungeRow =
            RaftHomeConversationItem(
                id = "lounge-bugen",
                name = "Bugen's Lounge",
                kind = "channel",
                unreadCount = 4,
                mentioned = true,
                activityMuted = true,
                presenceMemberId = "agent-bugen",
                agentLounge = RaftHomeAgentLoungeRow("lounge-bugen", unreadCount = 4, mentioned = true, agentId = "agent-bugen", agentName = "Bugen", joined = true),
            )
        // Muted Lounge contributes 0 unread and no mention.
        val mutedMerged =
            homeSidebarMergeAgentLounges(
                enabled = true,
                directMessages = listOf(dmRow),
                agentLounges = listOf(loungeRow),
                rawDirectMessages = listOf(rawDm),
                knownLounges = emptyMap(),
                markedUnreadIds = emptySet(),
            )
        assertEquals(1, mutedMerged.directMessages.single().unreadCount)
        assertEquals(1, mutedMerged.directMessages.single().dmSideUnread)
        assertEquals(0, mutedMerged.directMessages.single().loungeSideUnread)
        assertFalse(mutedMerged.directMessages.single().mentioned)
        assertTrue(mutedMerged.agentLounges.isEmpty())

        // Both DM and Lounge pinned -> suppress duplicate Lounge row, single merged row highlights in Pinned.
        val bothPinned =
            homeSidebarMergeAgentLounges(
                enabled = true,
                directMessages = listOf(dmRow),
                agentLounges = listOf(loungeRow.copy(activityMuted = false)),
                rawDirectMessages = listOf(rawDm),
                knownLounges = emptyMap(),
                markedUnreadIds = emptySet(),
                pinnedChannelIds = setOf("lounge-bugen"),
                pinnedAgentIds = setOf("agent-bugen"),
            )
        assertTrue(bothPinned.agentLounges.isEmpty(), "no duplicate Lounge when DM is also pinned")
        assertEquals(5, bothPinned.directMessages.single().unreadCount)
        assertEquals(1, bothPinned.directMessages.single().dmSideUnread)
        assertEquals(4, bothPinned.directMessages.single().loungeSideUnread)
        assertEquals(
            RaftHomeConversationSection.Pinned,
            homeConversationSectionForSelection("lounge-bugen", emptyList(), bothPinned.directMessages, setOf("agent-bugen")),
        )

        // Only Lounge pinned (DM not pinned) -> keep pinned Lounge separate without double-counting unread on DM.
        val onlyLoungePinned =
            homeSidebarMergeAgentLounges(
                enabled = true,
                directMessages = listOf(dmRow),
                agentLounges = listOf(loungeRow.copy(activityMuted = false)),
                rawDirectMessages = listOf(rawDm),
                knownLounges = emptyMap(),
                markedUnreadIds = emptySet(),
                pinnedChannelIds = setOf("lounge-bugen"),
                pinnedAgentIds = emptySet(),
            )
        assertEquals(listOf("lounge-bugen"), onlyLoungePinned.agentLounges.map { it.id })
        assertEquals(1, onlyLoungePinned.directMessages.single().unreadCount, "DM does not double-count pinned Lounge unread")
        assertEquals(1, onlyLoungePinned.directMessages.single().dmSideUnread)
        assertEquals(0, onlyLoungePinned.directMessages.single().loungeSideUnread)
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.Mention, RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "5", hasDraft = true, isAgent = true, mentioned = true),
        )
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.Mention),
            raftHomeDmTrailingSlots(badge = null, hasDraft = true, isAgent = true, mentioned = true),
        )
    }

    @Test
    fun agentRowPerSideUnreadDisplaySplitsSidesRespectsMuteAndFallsBackWhenNeitherSideHoldsUnread() {
        // Plain non-merged row or zero unread -> null (keeps standard single-count path).
        assertNull(homeSidebarAgentUnreadDisplay(unreadCount = 4, dmSideUnread = null, loungeSideUnread = null, muted = false))
        assertNull(homeSidebarAgentUnreadDisplay(unreadCount = 4, dmSideUnread = 4, loungeSideUnread = null, muted = false))
        assertNull(homeSidebarAgentUnreadDisplay(unreadCount = 0, dmSideUnread = 0, loungeSideUnread = 0, muted = false))

        // DM-only unread.
        assertEquals(
            HomeSidebarAgentUnreadDisplay.Sides(dmCount = 3, dmQuiet = false, loungeCount = 0),
            homeSidebarAgentUnreadDisplay(unreadCount = 3, dmSideUnread = 3, loungeSideUnread = 0, muted = false),
        )

        // Lounge-only unread.
        assertEquals(
            HomeSidebarAgentUnreadDisplay.Sides(dmCount = 0, dmQuiet = false, loungeCount = 2),
            homeSidebarAgentUnreadDisplay(unreadCount = 2, dmSideUnread = 0, loungeSideUnread = 2, muted = false),
        )

        // Unread on both sides (unmuted).
        assertEquals(
            HomeSidebarAgentUnreadDisplay.Sides(dmCount = 1, dmQuiet = false, loungeCount = 4),
            homeSidebarAgentUnreadDisplay(unreadCount = 5, dmSideUnread = 1, loungeSideUnread = 4, muted = false),
        )

        // Muted DM with unread on both sides: DM count goes quiet, Lounge count remains loud.
        assertEquals(
            HomeSidebarAgentUnreadDisplay.Sides(dmCount = 3, dmQuiet = true, loungeCount = 2),
            homeSidebarAgentUnreadDisplay(unreadCount = 5, dmSideUnread = 3, loungeSideUnread = 2, muted = true),
        )

        // slock#17277: unread > 0 while both side counts are 0 -> plain total fallback, muted when muted.
        assertEquals(
            HomeSidebarAgentUnreadDisplay.TotalFallback(count = 3, quiet = false),
            homeSidebarAgentUnreadDisplay(unreadCount = 3, dmSideUnread = 0, loungeSideUnread = 0, muted = false),
        )
        assertEquals(
            HomeSidebarAgentUnreadDisplay.TotalFallback(count = 120, quiet = true),
            homeSidebarAgentUnreadDisplay(unreadCount = 120, dmSideUnread = 0, loungeSideUnread = 0, muted = true),
        )
        assertEquals(
            HomeSidebarAgentUnreadDisplay.TotalFallback(count = 2, quiet = false),
            homeSidebarAgentUnreadDisplay(unreadCount = 2, dmSideUnread = -1, loungeSideUnread = 0, muted = false),
        )
    }
}
