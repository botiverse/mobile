package build.raft.designsystem

import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftPreviewCardButtonKind.Execute
import build.raft.designsystem.components.RaftPreviewCardButtonKind.Link
import build.raft.designsystem.components.RaftPreviewCardButtonKind.Read
import build.raft.designsystem.components.raftPreviewCardButtonLayout
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun labels(buttons: List<RaftPreviewCardButton>) = buttons.map { it.label }

    private fun assertLayout(buttons: List<RaftPreviewCardButton>, row: List<String>, more: List<String>, message: String) {
        val layout = raftPreviewCardButtonLayout(buttons)
        assertEquals(row, labels(layout.row), "row: $message")
        assertEquals(more, labels(layout.more), "more: $message")
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
    fun aThirdActionGoesInTheMenuNeverDropped() {
        val close = button("Close", Execute, disabledReason = "Only the author can close it")
        val layout = raftPreviewCardButtonLayout(listOf(merge, approve, close, refresh))
        assertEquals(listOf("Approve", "Merge"), labels(layout.row))
        assertEquals(listOf("Close", "Refresh"), labels(layout.more))
        assertEquals("Only the author can close it", layout.more.first().disabledReason, "a disabled item keeps its reason")
    }

    @Test
    fun buttonsAlikeAreToldApartByTheirPlace() {
        // Two equal buttons (data classes): the first takes the row, the second stays in the menu.
        val again = button("Open in Stamp", Link, frameless = true)
        val layout = raftPreviewCardButtonLayout(listOf(open, again))
        assertEquals(1, layout.row.size)
        assertEquals(1, layout.more.size)
    }
}
