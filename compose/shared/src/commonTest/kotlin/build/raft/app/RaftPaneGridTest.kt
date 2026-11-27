package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.shell.RaftPaneGridStore
import build.raft.app.shell.decodeRaftPaneGridFlags
import build.raft.app.shell.decodeRaftPaneGridSplits
import build.raft.app.thread.conversationSideCardsBesideInGrid
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wide pane grid (RaftPaneGrid.kt): web ChannelSideArea, ColumnResizeHandle and lib/panelBounds.ts rules. */
class RaftPaneGridTest {
    private class MapPreferences : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @Test
    fun columnWidthsUseWebBoundsAndAreKeptPerDevice() {
        val prefs = MapPreferences()
        val store = RaftPaneGridStore { prefs }
        // Web COLUMN_BOUNDS: thread 400 (min 360), sideCards 360 (min 280).
        assertEquals(400f, store.widthDp(RaftPaneGridColumns.Thread))
        assertEquals(360f, store.widthDp(RaftPaneGridColumns.SideCards))
        store.setWidth(RaftPaneGridColumns.SideCards, 431.6f)
        assertEquals("432", prefs.values["raftPaneGrid:width:sideCards"])
        // Never below 160 (#189 rule 2: a seam takes any column there, its content keeps its minimum and clips).
        store.setWidth(RaftPaneGridColumns.Thread, 100f)
        assertEquals(160f, store.widthDp(RaftPaneGridColumns.Thread))
        // A restart reads them back; out-of-range or unreadable values are the default.
        val after = RaftPaneGridStore { prefs }
        assertEquals(432f, after.widthDp(RaftPaneGridColumns.SideCards))
        prefs.values["raftPaneGrid:width:thread"] = "9000"
        assertEquals(400f, RaftPaneGridStore { prefs }.widthDp(RaftPaneGridColumns.Thread))
        prefs.values["raftPaneGrid:width:thread"] = "wide"
        assertEquals(400f, RaftPaneGridStore { prefs }.widthDp(RaftPaneGridColumns.Thread))
    }

    @Test
    fun collapseAndSplitAreKeptInWebShapes() {
        val prefs = MapPreferences()
        val store = RaftPaneGridStore { prefs }
        assertFalse(store.isCollapsed("files"))
        store.setCollapsed("files", true)
        // Web `slock:channelSideCardsCollapsed` shape: flags by section.
        assertEquals(mapOf("files" to true), decodeRaftPaneGridFlags(prefs.values["raftPaneGrid:collapsed"]))
        store.setCollapsed("tasks", true)
        store.setCollapsed("tasks", false)
        assertEquals(mapOf("files" to true, "tasks" to false), decodeRaftPaneGridFlags(prefs.values["raftPaneGrid:collapsed"]))
        val defaults = listOf(0.6f, 0.4f)
        assertEquals(defaults, store.shares("tasks+files", defaults))
        store.setShares("tasks+files", listOf(0.3f, 0.7f))
        // Web `slock:channelSideSplits` shape: shares by stack.
        assertEquals(mapOf("tasks+files" to listOf(0.3f, 0.7f)), decodeRaftPaneGridSplits(prefs.values["raftPaneGrid:splits"]))
        val after = RaftPaneGridStore { prefs }
        assertTrue(after.isCollapsed("files"))
        assertEquals(listOf(0.3f, 0.7f), after.shares("tasks+files", defaults))
        // A saved split of another shape (a stack that changed) is not used.
        assertEquals(listOf(0.5f, 0.3f, 0.2f), after.shares("tasks+files", listOf(0.5f, 0.3f, 0.2f)))
        // Web readFlags: only a JSON true folds.
        assertEquals(mapOf("tasks" to false, "files" to true), decodeRaftPaneGridFlags("{\"tasks\":\"true\",\"files\":true}"))
        assertEquals(emptyMap(), decodeRaftPaneGridFlags("not json"))
    }

    @Test
    fun theConversationsWidthIsKeptOnlyOnceASeamIsLetGo() {
        val prefs = MapPreferences()
        val store = RaftPaneGridStore { prefs }
        // None kept: the row plan gives it what the columns leave, at least 400 (web CONVERSATION_DEFAULT_WIDTH).
        assertEquals(null, store.savedWidthDp(RaftPaneGridColumns.Conversation))
        assertEquals(400f, store.widthDp(RaftPaneGridColumns.Conversation))
        // Kept even where it equals the default, under web's slock:conversationWidth's place in the grid's keys.
        store.setWidth(RaftPaneGridColumns.Conversation, 400f)
        assertEquals("400", prefs.values["raftPaneGrid:width:conversation"])
        assertEquals(400f, store.savedWidthDp(RaftPaneGridColumns.Conversation))
        // At least 160 (its content keeps 360 and clips).
        store.setWidth(RaftPaneGridColumns.Conversation, 120f)
        assertEquals(160f, RaftPaneGridStore { prefs }.savedWidthDp(RaftPaneGridColumns.Conversation))
        store.setWidth(RaftPaneGridColumns.Conversation, 200f)
        assertEquals(200f, RaftPaneGridStore { prefs }.savedWidthDp(RaftPaneGridColumns.Conversation))
        prefs.values["raftPaneGrid:width:conversation"] = "100"
        assertEquals(null, RaftPaneGridStore { prefs }.savedWidthDp(RaftPaneGridColumns.Conversation))
        // The Tasks page's list keeps its own (PR #4661 review), so a task column's seam never sets the conversation's.
        store.setWidth(RaftPaneGridColumns.TasksList, 520f)
        assertEquals("520", prefs.values["raftPaneGrid:width:tasksList"])
        assertEquals(200f, store.savedWidthDp(RaftPaneGridColumns.Conversation))
    }

    @Test
    fun cardsAlwaysStandBesideInTheGridOnAWideWindow() {
        // Web placeSections (#189): on a desktop every column stands in the row; the plan makes room (give way,
        // clip, tabs), whatever the room.
        assertTrue(conversationSideCardsBesideInGrid(windowWidthDp = 1400f))
        assertTrue(conversationSideCardsBesideInGrid(windowWidthDp = DualPaneMinimumWidthDp))
        // A phone (or a window under the app's 744 breakpoint) keeps the cover.
        assertFalse(conversationSideCardsBesideInGrid(windowWidthDp = 700f))
    }
}
