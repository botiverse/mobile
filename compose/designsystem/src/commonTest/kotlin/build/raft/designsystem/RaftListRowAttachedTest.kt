package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.raftListRowMinHeight
import build.raft.designsystem.components.raftListRowTopPadding
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui 0.84.0 ListRow `attached`: a row that belongs to the setting above it. */
class RaftListRowAttachedTest {
    @Test
    fun anAttachedPlainRowHasNoTopPaddingAndOthersKeepTheirs() {
        assertEquals(0.dp, raftListRowTopPadding(RaftListRowVariant.Plain, attached = true))
        assertEquals(12.dp, raftListRowTopPadding(RaftListRowVariant.Plain, attached = false))
        assertEquals(12.dp, raftListRowTopPadding(RaftListRowVariant.Default, attached = true))
    }

    @Test
    fun anAttachedInteractivePlainRowStaysAControlTallTarget() {
        assertEquals(44.dp, raftListRowMinHeight(RaftListRowVariant.Plain, attached = true, interactive = true, controlHeight = 44.dp))
        assertEquals(0.dp, raftListRowMinHeight(RaftListRowVariant.Plain, attached = true, interactive = false, controlHeight = 44.dp))
        assertEquals(0.dp, raftListRowMinHeight(RaftListRowVariant.Plain, attached = false, interactive = true, controlHeight = 44.dp))
        assertEquals(64.dp, raftListRowMinHeight(RaftListRowVariant.Default, attached = true, interactive = true, controlHeight = 44.dp))
    }
}
