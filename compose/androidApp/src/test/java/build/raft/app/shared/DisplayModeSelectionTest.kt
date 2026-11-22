package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModeSelectionTest {
    private val current60 =
        DisplayModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)

    @Test
    fun ignoresHigherRefreshModesAtOtherResolutions() {
        val lowerResolution120 =
            DisplayModeSpec(modeId = 5, physicalWidth = 810, physicalHeight = 1800, refreshRate = 120f)
        val sameResolution90 =
            DisplayModeSpec(modeId = 6, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 90f)

        assertEquals(
            sameResolution90,
            selectHighestRefreshSameResolutionMode(
                current60,
                listOf(current60, lowerResolution120, sameResolution90),
            ),
        )
    }
}
