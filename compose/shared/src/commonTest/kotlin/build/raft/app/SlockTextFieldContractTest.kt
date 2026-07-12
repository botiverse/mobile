package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockTextFieldContractTest {
    @Test
    fun oneLineFieldUsesNativeSingleLineEditor() {
        assertTrue(slockTextFieldSingleLine(maxLines = 1))
    }

    @Test
    fun multilineFieldKeepsNativeTextAreaEditor() {
        assertFalse(slockTextFieldSingleLine(maxLines = 2))
        assertFalse(slockTextFieldSingleLine(maxLines = Int.MAX_VALUE))
    }
}
