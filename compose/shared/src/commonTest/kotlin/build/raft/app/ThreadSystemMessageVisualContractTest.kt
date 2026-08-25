package build.raft.app

import com.tencent.kuikly.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadSystemMessageVisualContractTest {
    @Test
    fun systemMessageRowCentersBodyAndShortTimestampGroup() {
        val policy = threadSystemMessageRowLayoutPolicy()

        assertEquals(TextAlign.Center, policy.bodyTextAlign)
        assertTrue(policy.centerShortMessageGroup)
        // The body may ellipsize against the remaining width, but must not claim all of it for a
        // short message; otherwise the timestamp/body group is visually left-biased.
        assertEquals(false, policy.bodyWeightFillsRemainingWidth)
    }
}
