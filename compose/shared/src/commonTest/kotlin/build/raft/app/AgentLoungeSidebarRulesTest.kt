package build.raft.app

import build.raft.app.data.AgentLoungeSummary
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.RaftAgentLounge
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
            title = { "$it's Lounge" },
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
        assertEquals("bugen's Lounge", row.name, "the channel name's agent part when no DM row names the agent")
        assertEquals("2026-11-10T10:00:00.000Z", row.lastMessageAt)
        assertTrue(row.activityMuted)
        assertTrue(row.activityMuteSupported, "joined and loaded: the menu offers Unmute / Mute")
        assertTrue(items(emptyList(), markedUnreadIds = setOf("lounge-bugen")).single().markedUnread)
    }

    @Test
    fun muteIsOfferedOnlyOnceJoinedAndLoaded() {
        val mentionedNotJoined = AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, mentionCount = 1)
        // Listed (an @), never loaded: no Mute (web #13876 loads it first).
        assertFalse(items(listOf(agentDm("bugen", mentionedNotJoined))).single().activityMuteSupported)
        RaftAgentLounges.rememberRow(
            "server-1",
            HomeChannelRow(
                threadId = ThreadId("lounge-bugen"),
                channelId = ChannelId("lounge-bugen"),
                name = "bugen-lounge",
                joined = false,
                activityNotificationPreference = ChannelActivityNotificationPreference(activityMuteSupported = true),
                loungeAgentId = "agent-bugen",
            ),
        )
        assertFalse(items(listOf(agentDm("bugen", mentionedNotJoined))).single().activityMuteSupported, "loaded, not joined")
        RaftAgentLounges.markJoined("server-1", "lounge-bugen")
        assertTrue(items(listOf(agentDm("bugen", mentionedNotJoined))).single().activityMuteSupported, "joined")
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
    fun theLoungeMenuIsADmRowsMenu() {
        val ids = { item: RaftHomeConversationItem, pinned: Boolean -> raftHomeAgentLoungeMenuItems(item, pinned = pinned).map { it.id } }
        val joinedLoaded = loungeItem("lounge-bugen", "Bugen's Lounge", null, unread = 1).copy(activityMuteSupported = true)
        assertEquals(listOf("mark-read", "toggle-pin", "mute-channel", "close-dm"), ids(joinedLoaded, false))
        assertEquals("Unpin", raftHomeAgentLoungeMenuItems(joinedLoaded, pinned = true)[1].label)
        // Not joined (or not loaded): no Mute; nothing unread: Mark as Unread. Never Move to section,
        // Notifications or Sort.
        assertEquals(listOf("mark-unread", "toggle-pin", "close-dm"), ids(loungeItem("lounge-bugen", "Bugen's Lounge", null), false))
        // A stored mute keeps its Unmute.
        assertEquals(
            listOf("mark-unread", "toggle-pin", "unmute-channel", "close-dm"),
            ids(loungeItem("lounge-bugen", "Bugen's Lounge", null).copy(activityMuted = true), false),
        )
        // A mention with no count still reads as unread (Mark as Read clears the @).
        assertEquals("mark-read", ids(loungeItem("lounge-bugen", "Bugen's Lounge", null, mentioned = true), false).first())
    }

    @Test
    fun theLoungePageMuteFollowsTheLoungesOwnRow() {
        val lounge = RaftAgentLounge(ChannelId("lounge-bugen"), "agent-bugen", joined = true)
        assertNull(lounge.agentLoungeActivityMuteState(), "its row not loaded")
        val loaded = lounge.copy(activityNotificationPreference = ChannelActivityNotificationPreference(activityMuted = false, activityMuteSupported = true))
        assertEquals(false, loaded.agentLoungeActivityMuteState()?.muted)
        assertNull(loaded.copy(joined = false).agentLoungeActivityMuteState(), "not joined")
        assertEquals(false, loaded.copy(joined = false).agentLoungeActivityMuteState(joinedHint = true)?.muted, "the page's row says joined")
        val muted = loaded.copy(joined = false, activityNotificationPreference = ChannelActivityNotificationPreference(activityMuted = true, activityMuteSupported = true))
        assertEquals(true, muted.agentLoungeActivityMuteState()?.muted, "a stored mute keeps Unmute")
    }
}
