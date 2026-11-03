package build.raft.designsystem

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftPreviewCardButtonKind.Execute
import build.raft.designsystem.components.RaftPreviewCardButtonKind.Link
import build.raft.designsystem.components.RaftPreviewCardButtonKind.Read
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.raftMenuPosition
import build.raft.designsystem.components.raftPreviewCardButtonLayout
import build.raft.designsystem.components.raftPreviewCardDetailSeparators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The item card (raft-ui 0.58 `PreviewCardContent size="item"`, raft-design #939), mirroring the
 * web's App link card v2 tests (slock #12361 `appLinkCardActions.behavior.test.tsx`): which
 * buttons take the one row and which go in the More menu, the "·" between details, the width.
 */
class RaftPreviewCardItemTest {
    private fun button(label: String, kind: RaftPreviewCardButtonKind, frameless: Boolean = false, disabledReason: String? = null) =
        RaftPreviewCardButton(label = label, kind = kind, onClick = {}, frameless = frameless, disabledReason = disabledReason)

    // The Stamp PR card's buttons: Merge (bordered), Approve (frameless), Refresh, Open in Stamp.
    private val merge = button("Merge", Execute)
    private val approve = button("Approve", Execute, frameless = true)
    private val refresh = button("Refresh", Read, frameless = true)
    private val open = button("Open in Stamp", Link, frameless = true)
    private val docs = button("Docs", Link, frameless = true)

    private fun labels(buttons: List<RaftPreviewCardButton>) = buttons.map { it.label }

    private fun assertLayout(buttons: List<RaftPreviewCardButton>, row: List<String>, more: List<String>, message: String) {
        val layout = raftPreviewCardButtonLayout(buttons)
        assertEquals(row, labels(layout.row), "row: $message")
        assertEquals(more, labels(layout.more), "more: $message")
    }

    @Test
    fun anItemCardIs400WideAProfileCardKeeps300() {
        assertEquals(400.dp, RaftPreviewCardDefaults.ItemWidth)
        assertEquals(300.dp, RaftPreviewCardDefaults.Width)
    }

    @Test
    fun theTitleWrapsToTwoLinesAndTheRowHoldsTwoButtons() {
        assertEquals(2, RaftPreviewCardDefaults.ItemTitleMaxLines)
        assertEquals(2, RaftPreviewCardDefaults.ItemRowButtons)
    }

    @Test
    fun theRowHasTheFirstTwoActionsFramelessFirstTheBorderedOneAtTheEnd() {
        assertLayout(
            listOf(merge, approve, refresh, open),
            row = listOf("Approve", "Merge"),
            more = listOf("Refresh", "Open in Stamp"),
            message = "frameless Approve, then the bordered Merge at the right end, though Merge is first; the read and the link in the menu, in order",
        )
    }

    @Test
    fun aLinkFirstInTheAppsOrderTakesNoPlaceInTheRowWhenThereAreActions() {
        assertLayout(
            listOf(open, merge, approve, refresh),
            row = listOf("Approve", "Merge"),
            more = listOf("Open in Stamp", "Refresh"),
            message = "the menu keeps the app's order",
        )
    }

    @Test
    fun aThirdActionGoesInTheMenuNeverDropped() {
        val close = button("Close", Execute, disabledReason = "Only the author can close it")
        val layout = raftPreviewCardButtonLayout(listOf(merge, approve, close, refresh))
        assertEquals(listOf("Approve", "Merge"), labels(layout.row))
        assertEquals(listOf("Close", "Refresh"), labels(layout.more))
        assertEquals("Only the author can close it", layout.more.first().disabledReason, "a disabled item keeps its reason")
    }

    @Test
    fun withoutAnActionTheFirstLinkTakesTheRow() {
        assertLayout(listOf(refresh, open), row = listOf("Open in Stamp"), more = listOf("Refresh"), message = "the read in the menu")
        assertLayout(listOf(docs, refresh, open), row = listOf("Docs"), more = listOf("Refresh", "Open in Stamp"), message = "the first link")
    }

    @Test
    fun aDisabledLinkStillTakesTheRowWhenItIsTheFirst() {
        val deleted = button("Open in Stamp", Link, disabledReason = "The pull request was deleted")
        val layout = raftPreviewCardButtonLayout(listOf(refresh, deleted))
        assertEquals(listOf(deleted), layout.row, "disabled as it is")
        assertEquals(listOf(refresh), layout.more)
    }

    @Test
    fun twoFramelessActionsKeepTheAppsOrderAndNeedNoMenu() {
        val ghostMerge = button("Merge", Execute, frameless = true)
        assertLayout(listOf(ghostMerge, approve), row = listOf("Merge", "Approve"), more = emptyList(), message = "no ⋯")
    }

    @Test
    fun onlyReadsLeaveTheRowToTheMenu() {
        val recheck = button("Check again", Read)
        assertLayout(listOf(refresh, recheck), row = emptyList(), more = listOf("Refresh", "Check again"), message = "the ⋯ alone")
    }

    @Test
    fun noButtonsNoRow() {
        assertLayout(emptyList(), row = emptyList(), more = emptyList(), message = "nothing")
    }

    @Test
    fun buttonsAlikeAreToldApartByTheirPlace() {
        // Two equal buttons (data classes): the first takes the row, the second stays in the menu.
        val again = button("Open in Stamp", Link, frameless = true)
        val layout = raftPreviewCardButtonLayout(listOf(open, again))
        assertEquals(1, layout.row.size)
        assertEquals(1, layout.more.size)
    }

    @Test
    fun aDotGoesBetweenTwoDetailsButNeverNextToTheStateChip() {
        val state = RaftPreviewCardDetail.State("Merged")
        val who = RaftPreviewCardDetail.Text("Martin")
        val diff = RaftPreviewCardDetail.Text("+803 −498")
        val checks = RaftPreviewCardDetail.Text("2 checks failing")
        assertEquals(listOf(false, false, true), raftPreviewCardDetailSeparators(listOf(state, who, diff)))
        assertEquals(listOf(false, false, false), raftPreviewCardDetailSeparators(listOf(who, state, diff)))
        assertEquals(listOf(false, true, true), raftPreviewCardDetailSeparators(listOf(who, diff, checks)))
        assertEquals(emptyList(), raftPreviewCardDetailSeparators(emptyList()))
    }

    @Test
    fun theMoreMenuOpensAlignedToItsButtonsEnd() {
        val noRoom = RaftPopupMargins(horizontal = 0, top = 0, bottom = 0)
        val window = IntSize(400, 800)
        val more = IntRect(300, 100, 332, 132)
        val atEnd = raftMenuPosition(more, window, IntSize(200, 100), gap = 6, windowMargin = 8, insets = noRoom, alignEnd = true)
        assertEquals(IntOffset(332 - 200, 138), atEnd, "its end edge on the ⋯'s: it stays over the card")
        val atStart = raftMenuPosition(more, window, IntSize(200, 100), gap = 6, windowMargin = 8, insets = noRoom)
        assertTrue(atStart.x > atEnd.x, "start aligned it would run past the card's end (here pushed back by the window)")
        assertFalse(atEnd.x + 200 > 332)
    }
}
