package build.raft.app

import build.raft.app.thread.ConversationSideCard
import build.raft.app.thread.icon
import build.raft.app.thread.threadFollowMenuIcon
import build.raft.designsystem.components.RaftSideActionHostColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS side-column host bridge: the column's wire format, taps, the capability switch. */
class RaftSideActionHostBridgeTest {
    private fun item(id: String, symbol: String, enabled: Boolean = true, attention: Boolean = false) =
        RaftSideActionHostColumn.Item(
            id = id,
            label = id.replaceFirstChar { it.uppercase() },
            systemSymbol = symbol,
            enabled = enabled,
            attention = attention,
            attentionDescription = if (attention) "2 new" else null,
        )

    private val channel =
        RaftSideActionHostColumn(
            primary = listOf(item("back", "chevron.backward")),
            actions = listOf(item("thread-search", "magnifyingglass"), item("thread-settings", "gearshape")),
            visibleActionIds = listOf("thread-search", "thread-settings"),
            overflowActionIds = emptyList(),
            overflowLabel = "More Actions",
        )

    private class Recorder : RaftSideActionHostListener {
        val events = mutableListOf<String?>()

        override fun onSideActionColumnChanged(json: String) {
            events += json
        }

        override fun onSideActionColumnCleared() {
            events += null
        }
    }

    @Test
    fun theColumnSerialisesToTheWireShape() {
        assertEquals(
            """{"version":1,""" +
                """"primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":true,"attention":false}],""" +
                """"actions":[{"id":"thread-search","label":"Thread-search","systemSymbol":"magnifyingglass","enabled":true,"attention":false},""" +
                """{"id":"thread-settings","label":"Thread-settings","systemSymbol":"gearshape","enabled":true,"attention":false}],""" +
                """"visibleActionIds":["thread-search","thread-settings"],"overflowActionIds":[],""" +
                """"overflowLabel":"More Actions","overflowAttention":false}""",
            raftSideActionHostJson(channel),
        )
    }

    @Test
    fun offByDefaultTheHostDeclinesAndShowsNothing() {
        val bridge = RaftSideActionHostBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        assertFalse(bridge.isAvailable)
        assertFalse(bridge.host.present(channel) {})
        assertNull(bridge.currentColumnJson)
        assertEquals(listOf<String?>(null), recorder.events, "only the attach replay")
        assertFalse(bridge.sideActionTapped("back"))
    }

    @Test
    fun switchingOffWithdrawsTheColumnAtOnce() {
        val bridge = RaftSideActionHostBridge()
        bridge.setAvailable(true)
        val recorder = Recorder()
        bridge.attachListener(recorder)
        bridge.host.present(channel) {}
        bridge.setAvailable(false)
        assertEquals(listOf(null, raftSideActionHostJson(channel), null), recorder.events)
        assertFalse(bridge.host.present(channel) {})
        assertEquals(3, recorder.events.size)
    }

    @Test
    fun aTapRunsOnlyAShownEnabledItem() {
        val bridge = RaftSideActionHostBridge()
        bridge.setAvailable(true)
        val tapped = mutableListOf<String>()
        val column = channel.copy(actions = channel.actions + item("off", "xmark", enabled = false))
        bridge.host.present(column) { tapped += it }
        assertTrue(bridge.sideActionTapped("back"))
        assertTrue(bridge.sideActionTapped("thread-settings"))
        assertFalse(bridge.sideActionTapped("off"))
        assertFalse(bridge.sideActionTapped("nope"))
        assertEquals(listOf("back", "thread-settings"), tapped)
    }

    @Test
    fun everyIconAConversationsColumnHoldsHasASystemSymbol() {
        // threadHeaderSideColumnActions and Home's bell: a reply thread's Timeline, focus mode and
        // follow included, so no conversation falls back to a column drawn by the page.
        val icons =
            listOf(
                RaftLucideIconName.Search,
                RaftLucideIconName.Users,
                RaftLucideIconName.Settings,
                RaftLucideIconName.List,
                RaftLucideIconName.MapPin,
                RaftLucideIconName.Maximize2,
                RaftLucideIconName.Square,
                RaftLucideIconName.MessageSquareDot,
                threadFollowMenuIcon(isFollowed = false),
                threadFollowMenuIcon(isFollowed = true),
                RaftLucideIconName.Trash2,
                RaftLucideIconName.Bell,
            ) + ConversationSideCard.entries.map { it.icon }
        for (icon in icons) {
            assertNotNull(raftSideColumnLucideAction("a", "A", icon, {}).systemSymbol, icon.wireName)
        }
        assertEquals("list.bullet", raftSideColumnLucideAction("timeline", "Timeline", RaftLucideIconName.List, {}).systemSymbol)
    }
}
