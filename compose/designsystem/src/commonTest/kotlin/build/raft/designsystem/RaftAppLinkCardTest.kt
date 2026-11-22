package build.raft.designsystem

import build.raft.designsystem.components.RaftAppLinkCardDefaults
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.raftAppLinkCardButtonLayout
import build.raft.designsystem.components.raftAppLinkCardHostText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [build.raft.designsystem.components.RaftAppLinkCard]'s rules that need no drawing (raft-design #1010). */
class RaftAppLinkCardTest {
    private fun button(label: String, kind: RaftPreviewCardButtonKind, frameless: Boolean = false) =
        RaftPreviewCardButton(label, kind, onClick = {}, frameless = frameless)

    @Test
    fun theRowTakesTheAppsFirstTwoButtonsWhateverTheyDoButNeverALinkToTheItem() {
        // The web's block card (botiverse/slock appLinkPreviewV3.behavior.test.tsx "a block card's buttons").
        val buttons =
            listOf(
                button("Open in Stamp", RaftPreviewCardButtonKind.Link, frameless = true).copy(opensItem = true),
                button("Approve", RaftPreviewCardButtonKind.Execute),
                button("View changes", RaftPreviewCardButtonKind.Link, frameless = true),
                button("Refresh", RaftPreviewCardButtonKind.Read, frameless = true),
            )
        val layout = raftAppLinkCardButtonLayout(buttons)
        assertEquals(listOf("View changes", "Approve"), layout.row.map { it.label })
        assertEquals(listOf("Open in Stamp", "Refresh"), layout.more.map { it.label })
    }

    @Test
    fun aHostTooLongForTheHeaderLosesItsStartNeverItsEnd() {
        assertEquals("stamp.build", raftAppLinkCardHostText("stamp.build"))
        val long = "login.review.preview.attacker-controlled-subdomain.example"
        val shown = raftAppLinkCardHostText(long)
        assertEquals(RaftAppLinkCardDefaults.HostMaxChars, shown.length)
        assertTrue(shown.startsWith("…"))
        assertTrue(long.endsWith(shown.drop(1)))
    }
}
