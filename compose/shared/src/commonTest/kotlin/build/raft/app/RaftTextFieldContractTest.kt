package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftTextFieldContractTest {
    @Test
    fun oneLineFieldUsesNativeSingleLineEditor() {
        assertTrue(raftTextFieldSingleLine(maxLines = 1))
    }

    @Test
    fun multilineFieldKeepsNativeTextAreaEditor() {
        assertFalse(raftTextFieldSingleLine(maxLines = 2))
        assertFalse(raftTextFieldSingleLine(maxLines = Int.MAX_VALUE))
    }
}
