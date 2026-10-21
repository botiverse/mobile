package build.raft.designsystem

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.raftDialogSheetCentered
import build.raft.designsystem.components.raftDialogSheetConstraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** raft-ui Dialog "On a phone": a sheet on a phone, a centred dialog on a tablet and larger. */
class RaftDialogSheetLayoutTest {
    @Test
    fun phonesGetTheSheetTabletsAndLargeScreensTheCentredDialog() {
        // Phones in portrait and landscape-narrow windows.
        listOf(320.dp, 390.dp, 430.dp, 599.dp).forEach { width ->
            assertFalse(raftDialogSheetCentered(width), "width=$width")
        }
        // A tablet in portrait (600..839dp), the two-pane layout (840dp+), a desktop-size window.
        listOf(600.dp, 800.dp, 840.dp, 1280.dp).forEach { width ->
            assertTrue(raftDialogSheetCentered(width), "width=$width")
        }
        assertTrue(RaftDialogSheetDefaults.CenteredWidth < RaftDialogSheetDefaults.CenteredMinWindowWidth)
    }

    @Test
    fun theSheetIsNinetyPercentOfTheWindow() {
        val sheet = raftDialogSheetConstraints(Constraints(maxWidth = 1170, maxHeight = 2000))
        assertEquals(1800, sheet.minHeight)
        assertEquals(1800, sheet.maxHeight)
        val unbounded = Constraints(maxWidth = 1170)
        assertEquals(unbounded, raftDialogSheetConstraints(unbounded))
    }
}
