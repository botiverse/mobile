package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSearchTriggerDefaults
import build.raft.designsystem.components.RaftWorkspaceStripDefaults
import build.raft.designsystem.components.RaftWorkspaceStripItems
import kotlin.test.Test
import kotlin.test.assertEquals

/** The web top bar's layout rules (workspaceStripLayout.ts, raft-ui SearchTrigger's width). */
class RaftTopBarTest {
    @Test
    fun stripShowsAsManyAvatarsAsFitAndAtLeastTwo() {
        // span - 8 lead + 4 (n avatars need n slots less one gap) - 5 divider, in 36dp slots.
        assertEquals(5, RaftWorkspaceStripDefaults.capacity(200.dp, total = 9))
        assertEquals(3, RaftWorkspaceStripDefaults.capacity(200.dp, total = 3), "never more than there are")
        assertEquals(2, RaftWorkspaceStripDefaults.capacity(81.dp, total = 9))
        assertEquals(0, RaftWorkspaceStripDefaults.capacity(80.dp, total = 9), "one avatar reads as noise")
        assertEquals(0, RaftWorkspaceStripDefaults.capacity(500.dp, total = 1), "one server needs no strip")
        assertEquals(0, RaftWorkspaceStripDefaults.capacity(0.dp, total = 4))
    }

    @Test
    fun anOutOfRangeCurrentServerTakesTheLastSlot() {
        val servers = listOf("a", "b", "c", "d", "e")
        assertEquals(RaftWorkspaceStripItems(listOf("a", "b", "c"), false), RaftWorkspaceStripDefaults.items(servers, currentIndex = 1, shown = 3))
        assertEquals(RaftWorkspaceStripItems(listOf("a", "b", "e"), true), RaftWorkspaceStripDefaults.items(servers, currentIndex = 4, shown = 3))
        assertEquals(RaftWorkspaceStripItems(listOf("a", "b", "c"), false), RaftWorkspaceStripDefaults.items(servers, currentIndex = -1, shown = 3))
        assertEquals(RaftWorkspaceStripItems(emptyList(), false), RaftWorkspaceStripDefaults.items(servers, currentIndex = 4, shown = 0))
    }

    @Test
    fun searchTriggerWidthIsAThirdOfTheWindowWithinItsBounds() {
        assertEquals(360.dp, RaftSearchTriggerDefaults.width(900.dp))
        assertEquals(409.6f, RaftSearchTriggerDefaults.width(1280.dp).value, 0.01f)
        assertEquals(520.dp, RaftSearchTriggerDefaults.width(2000.dp))
    }
}
