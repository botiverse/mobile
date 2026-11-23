package build.raft.app.desktop

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftTopBarDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDesktopTitleBarTest {
    @Test fun `fullscreen hides the traffic lights, so no band and no inset`() {
        val none = RaftDesktopTitleBarMetrics(band = 0.dp, controlsStart = 0.dp)
        assertEquals(none, raftDesktopTitleBarMetrics(fullscreen = true, reportedControlsWidth = 80.dp))
        assertEquals(none, raftDesktopTitleBarMetrics(fullscreen = true, reportedControlsWidth = 0.dp))
    }

    @Test fun `a window's band is the top bar and the corner is the traffic lights' room`() {
        assertEquals(
            RaftDesktopTitleBarMetrics(band = RaftTopBarDefaults.FineHeight, controlsStart = 80.dp),
            raftDesktopTitleBarMetrics(fullscreen = false, reportedControlsWidth = 80.dp),
        )
        // Nothing reported (yet): the traffic lights' usual corner, never under them.
        assertEquals(
            RaftDesktopTitleBarMetrics(band = RaftTopBarDefaults.FineHeight, controlsStart = RaftDesktopDefaultControlsWidth),
            raftDesktopTitleBarMetrics(fullscreen = false, reportedControlsWidth = 0.dp),
        )
    }
}
