package build.raft.designsystem

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.raftDialogSheetCentered
import build.raft.designsystem.components.raftDialogSheetConstraints
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** raft-ui Dialog "On a phone": a sheet on a phone, a centred dialog on a tablet and larger. */
class RaftDialogSheetLayoutTest {
    @Test
    fun phonesGetTheSheetTabletsAndLargeScreensTheCentredDialog() {
        // Phones in portrait, landscape-narrow windows and split-screen halves: the compact layout.
        listOf(320.dp, 390.dp, 430.dp, 600.dp, 743.dp).forEach { width ->
            assertFalse(raftDialogSheetCentered(width), "width=$width")
        }
        // The app's wide layout (an iPad mini upright at 744dp and up), a desktop-size window.
        listOf(744.dp, 768.dp, 840.dp, 1280.dp).forEach { width ->
            assertTrue(raftDialogSheetCentered(width), "width=$width")
        }
        assertTrue(RaftDialogSheetDefaults.CenteredWidth < RaftDialogSheetDefaults.CenteredMinWindowWidth)
    }

    @Test
    fun theDialogSwitchesWhereTheAppLayoutGoesWide() {
        assertEquals(RaftScale.WideMinWidth, RaftDialogSheetDefaults.CenteredMinWindowWidth)
    }

    @Test
    fun theSheetIsNinetyPercentOfTheWindow() {
        val sheet = raftDialogSheetConstraints(Constraints(maxWidth = 1170, maxHeight = 2000))
        assertEquals(1800, sheet.minHeight)
        assertEquals(1800, sheet.maxHeight)
        val unbounded = Constraints(maxWidth = 1170)
        assertEquals(unbounded, raftDialogSheetConstraints(unbounded))
    }

    @Test
    fun theStatusBarCapsTheSheetButDoesNotShrinkItsNinetyPercent() {
        // 844dp phone at 3x, a 40dp status bar: 90% of the whole window, not of what is below the bar.
        val phone = raftDialogSheetConstraints(Constraints(maxWidth = 1170, maxHeight = 2532), topInsetPx = 120)
        assertEquals(2278, phone.maxHeight)
        // A bar taller than the 10% margin caps the sheet at the window below it.
        val tallBar = raftDialogSheetConstraints(Constraints(maxWidth = 1170, maxHeight = 1000), topInsetPx = 200)
        assertEquals(800, tallBar.maxHeight)
    }
}
