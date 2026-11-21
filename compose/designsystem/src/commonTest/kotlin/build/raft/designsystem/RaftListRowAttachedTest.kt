package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.raftListRowMinHeight
import build.raft.designsystem.components.raftListRowPaddingY
import build.raft.designsystem.components.raftListRowTopPadding
import build.raft.designsystem.theme.RaftPointerInput
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui 0.84.0 ListRow `attached`: a row that belongs to the setting above it. */
class RaftListRowAttachedTest {
    @Test
    fun anAttachedPlainRowHasNoTopPaddingAndOthersKeepTheirs() {
        assertEquals(0.dp, raftListRowTopPadding(RaftListRowVariant.Plain, attached = true))
        assertEquals(12.dp, raftListRowTopPadding(RaftListRowVariant.Plain, attached = false))
        assertEquals(8.dp, raftListRowTopPadding(RaftListRowVariant.Default, attached = true))
        assertEquals(12.dp, raftListRowTopPadding(RaftListRowVariant.Default, attached = true, pointer = RaftPointerInput.Fine))
    }

    @Test
    fun anAttachedInteractivePlainRowStaysAControlTallTarget() {
        assertEquals(44.dp, raftListRowMinHeight(RaftListRowVariant.Plain, attached = true, interactive = true, controlHeight = 44.dp))
        assertEquals(0.dp, raftListRowMinHeight(RaftListRowVariant.Plain, attached = true, interactive = false, controlHeight = 44.dp))
        assertEquals(0.dp, raftListRowMinHeight(RaftListRowVariant.Plain, attached = false, interactive = true, controlHeight = 44.dp))
        assertEquals(56.dp, raftListRowMinHeight(RaftListRowVariant.Default, attached = true, interactive = true, controlHeight = 44.dp))
    }

    /** raft-ui list-row.recipe.ts `default`: `min-h-16 pointer-coarse:min-h-14 pointer-coarse:py-2`. */
    @Test
    fun aDefaultRowIsDenserByTouch() {
        val touch = RaftPointerInput.Touch
        val fine = RaftPointerInput.Fine
        assertEquals(56.dp, raftListRowMinHeight(RaftListRowVariant.Default, attached = false, interactive = true, controlHeight = 44.dp, pointer = touch))
        assertEquals(64.dp, raftListRowMinHeight(RaftListRowVariant.Default, attached = false, interactive = true, controlHeight = 34.dp, pointer = fine))
        assertEquals(8.dp, raftListRowPaddingY(RaftListRowVariant.Default, touch))
        assertEquals(12.dp, raftListRowPaddingY(RaftListRowVariant.Default, fine))
        // Plain and entry have no pointer-coarse change: 12dp at every pointer.
        assertEquals(12.dp, raftListRowPaddingY(RaftListRowVariant.Plain, touch))
        assertEquals(12.dp, raftListRowPaddingY(RaftListRowVariant.Entry, touch))
        assertEquals(12.dp, raftListRowPaddingY(RaftListRowVariant.Plain, fine))
    }
}
