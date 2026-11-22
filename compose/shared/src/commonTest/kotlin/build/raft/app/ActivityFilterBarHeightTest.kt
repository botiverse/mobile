package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Activity filter bar is the web's list toolbar line (slock packages/web/src/features/thread/
 * ThreadsInbox.tsx:530, `h-[calc(--spacing(12)+1px)] md:pointer-fine:h-(--header-height)`).
 */
class ActivityFilterBarHeightTest {
    @Test
    fun aPhoneTakesTheWebsLineOf48AndTheSeam() {
        for (pointer in RaftPointerInput.entries) {
            assertEquals(49.dp, activityFilterBarHeight(RaftScale.Compact, pointer), "a phone, $pointer")
        }
        assertEquals(49.dp, activityFilterBarHeight(RaftScale.Wide, RaftPointerInput.Touch), "wide, with a finger")
        assertEquals(44.dp, activityFilterBarHeight(RaftScale.Wide, RaftPointerInput.Fine), "wide, under a mouse: the head height")
    }
}
