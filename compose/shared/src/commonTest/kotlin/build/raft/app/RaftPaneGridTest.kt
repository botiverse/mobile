package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import kotlin.math.abs
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

    private fun assertNear(expected: Float, actual: Float, message: String? = null) =
        assertTrue(abs(expected - actual) < 0.01f, "${message ?: ""} expected $expected, was $actual")

    @Test
    fun columnWidthsUseWebBoundsAndAreKeptPerDevice() {
        val prefs = MapPreferences()
        val store = RaftPaneGridStore { prefs }
        // Web COLUMN_BOUNDS: thread 400 (min 360), sideCards 360 (min 280).
        assertEquals(400f, store.widthDp(RaftPaneGridColumns.Thread))
        assertEquals(360f, store.widthDp(RaftPaneGridColumns.SideCards))
        store.setWidth(RaftPaneGridColumns.SideCards, 431.6f)
        assertEquals("432", prefs.values["raftPaneGrid:width:sideCards"])
        // Never below the minimum.
        store.setWidth(RaftPaneGridColumns.Thread, 100f)
        assertEquals(360f, store.widthDp(RaftPaneGridColumns.Thread))
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
    fun closingACardKeepsHowTheColumnWasFoldedAndSized() {
        val prefs = MapPreferences()
        val grid = RaftPaneGridStore { prefs }
        val cards = ConversationSideCardsStore { prefs }
        cards.bindOwner("env|u1")
        cards.open(ConversationSideCard.Tasks)
        cards.open(ConversationSideCard.Files)
        grid.setCollapsed("tasks", true)
        grid.setWidth(RaftPaneGridColumns.SideCards, 400f)
        cards.close(ConversationSideCard.Files)
        assertEquals(listOf(ConversationSideCard.Tasks), cards.state.value.openCards)
        assertEquals(ConversationSideCardsState(tasks = true), decodeConversationSideCardsOpen(prefs.values["conversationSideCards:open:env|u1"]))
        // Files comes back to the same column: Tasks still folded, the width as dragged.
        cards.open(ConversationSideCard.Files)
        assertTrue(grid.isCollapsed("tasks"))
        assertEquals(400f, grid.widthDp(RaftPaneGridColumns.SideCards))
    }

    @Test
    fun columnsGiveWayRightmostFirstAndGrowUntilTheMainPaneIsAt360() {
        // Thread 400 + cards 360 beside a 360 main pane: 1120 fits all.
        assertEquals(listOf(400f, 360f), raftPaneGridFitWidths(1120f, listOf(400f to 360f, 360f to 280f)))
        // 1040: the rightmost gives way first (360 -> 280).
        assertEquals(listOf(400f, 280f), raftPaneGridFitWidths(1040f, listOf(400f to 360f, 360f to 280f)))
        // 1000: then the one before it.
        assertEquals(listOf(360f, 280f), raftPaneGridFitWidths(1000f, listOf(400f to 360f, 360f to 280f)))
        // Never under a minimum.
        assertEquals(listOf(280f), raftPaneGridFitWidths(500f, listOf(360f to 280f)))
        // Web growToMainMinMax: a 360 column beside a 600 main pane may grow to 600.
        assertEquals(600f, raftPaneGridDragMaxDp(280f, 360f, 600f))
        assertEquals(280f, raftPaneGridDragMaxDp(280f, 280f, 300f))
    }

    @Test
    fun cardsStandBesideInTheGridWhileTheir280Fits() {
        // The room the grid leaves with the conversation at 360 and the thread at its 360.
        assertTrue(conversationSideCardsBesideInGrid(windowWidthDp = 1400f, spareDp = 280f))
        assertFalse(conversationSideCardsBesideInGrid(windowWidthDp = 1400f, spareDp = 279f))
        // A phone (or a window under the app's 744 breakpoint) keeps the cover.
        assertFalse(conversationSideCardsBesideInGrid(windowWidthDp = 700f, spareDp = 400f))
    }

    @Test
    fun activitysListIsAtMostHalfTheRowWithAnItemOpen() {
        // Web shareCapWidth: half of a 1223 row is 611; never under the list's 240.
        assertEquals(611f, raftListPaneShareCapDp(1223f, 0.5f, 240f))
        assertEquals(240f, raftListPaneShareCapDp(400f, 0.5f, 240f))
    }

    @Test
    fun cardsStandBesideOnAWideWindowWithRoomForBoth() {
        assertTrue(conversationSideCardsBeside(windowWidthDp = 1280f, pageWidthDp = 640f))
        assertFalse(conversationSideCardsBeside(windowWidthDp = 1280f, pageWidthDp = 639f))
        // A phone (or a window under the app's 744 breakpoint) keeps the cover.
        assertFalse(conversationSideCardsBeside(windowWidthDp = 700f, pageWidthDp = 700f))
        val both = ConversationSideCardsState().toggled(ConversationSideCard.Tasks).toggled(ConversationSideCard.Files)
        assertTrue(sideCardShowing(both, beside = true, ConversationSideCard.Tasks))
        assertFalse(sideCardShowing(both, beside = false, ConversationSideCard.Tasks))
    }

    @Test
    fun aStackSplitsByShareAndFoldsToHeads() {
        // 600 tall, 60/40: the seam takes 1, Tasks 359.4, Files 239.6.
        val open = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(false, false))
        assertNear(359.4f, open.heights[0])
        assertNear(239.6f, open.heights[1])
        assertEquals(listOf(false, true), open.seamAbove)
        // Tasks folded: its head, no seam under it, Files the rest.
        val tasksFolded = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(true, false))
        assertEquals(listOf(56f, 544f), tasksFolded.heights)
        assertEquals(listOf(false, false), tasksFolded.seamAbove)
        // Files folded: its head at the bottom, under the seam.
        val filesFolded = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(false, true))
        assertEquals(listOf(543f, 56f), filesFolded.heights)
        // Both folded: two heads at the top.
        assertEquals(listOf(56f, 56f), raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(true, true)).heights)
        // Web sideCardsStackMinHeight: two cards need 321.
        assertEquals(321f, raftPaneGridStackMinHeightDp(2))
    }

    @Test
    fun aSeamDragKeepsEachCardAt160() {
        val none = listOf(false, false)
        // 601 tall: 600 to split, 160dp is 0.2667 of it.
        val up = raftPaneGridMoveSeam(listOf(0.6f, 0.4f), 0, 0f, 601f, none)
        assertNear(160f / 600f, up[0])
        assertNear(1f, up[0] + up[1], "the pair keeps its total")
        val down = raftPaneGridMoveSeam(listOf(0.6f, 0.4f), 0, 1f, 601f, none)
        assertNear(1f - 160f / 600f, down[0])
        // A short stack: 45% of the pair at least.
        val short = raftPaneGridMoveSeam(listOf(0.6f, 0.4f), 0, 0f, 301f, none)
        assertNear(0.45f, short[0])
        // A pixel is worth 1/600 of the open shares.
        assertNear(1f / 600f, raftPaneGridSharePerDp(601f, listOf(0.6f, 0.4f), none))
    }
}
