package build.raft.designsystem

import build.raft.designsystem.components.RaftAppLinkCardDefaults
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.raftAppLinkCardHostText
import build.raft.designsystem.components.raftPreviewCardButtonLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [build.raft.designsystem.components.RaftAppLinkCard]'s rules that need no drawing (raft-design #1010). */
class RaftAppLinkCardTest {
    private fun button(label: String, kind: RaftPreviewCardButtonKind, frameless: Boolean = false) =
        RaftPreviewCardButton(label, kind, onClick = {}, frameless = frameless)

    @Test
    fun theCardIs400dpOnAWideLayoutAndTakesTheWholeMessageBelow768() {
        assertEquals(400f, RaftAppLinkCardDefaults.MaxWidth.value)
        assertEquals(768f, RaftAppLinkCardDefaults.PhoneBelow.value)
    }

    @Test
    fun aLinkNeverTakesTheRowButTheAppsFirstTwoActionsDoInItsOrderFramelessFirst() {
        val buttons =
            listOf(
                button("Open in Stamp", RaftPreviewCardButtonKind.Link),
                button("Merge", RaftPreviewCardButtonKind.Execute),
                button("Refresh", RaftPreviewCardButtonKind.Read, frameless = true),
                button("Approve", RaftPreviewCardButtonKind.Execute, frameless = true),
                button("Close", RaftPreviewCardButtonKind.Execute),
            )
        val layout = raftPreviewCardButtonLayout(buttons, linkTakesEmptyRow = false)
        assertEquals(listOf("Approve", "Merge"), layout.row.map { it.label })
        assertEquals(listOf("Open in Stamp", "Refresh", "Close"), layout.more.map { it.label })
    }

    @Test
    fun withNoActionTheRowStaysEmptyAndEveryLinkIsInTheMenu() {
        val buttons = listOf(button("Open in Stamp", RaftPreviewCardButtonKind.Link), button("Open Link", RaftPreviewCardButtonKind.Link))
        val block = raftPreviewCardButtonLayout(buttons, linkTakesEmptyRow = false)
        assertTrue(block.row.isEmpty())
        assertEquals(listOf("Open in Stamp", "Open Link"), block.more.map { it.label })
        // The hover card's rule is unchanged: its first link takes the empty row.
        assertEquals(listOf("Open in Stamp"), raftPreviewCardButtonLayout(buttons).row.map { it.label })
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
