package build.raft.app

import build.raft.app.thread.raftMessageBodyTextStyle
import build.raft.designsystem.theme.RaftScale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBodyTypographyTest {
    @AfterTest
    fun restorePhoneScale() {
        RaftAppTypeScale.scale = RaftScale.Compact
    }

    @Test
    fun unknownPreferenceFailsSafeToMedium() {
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle("unexpected"))
        assertEquals(raftMessageBodyTextStyle("md"), raftMessageBodyTextStyle(null))
    }
}
