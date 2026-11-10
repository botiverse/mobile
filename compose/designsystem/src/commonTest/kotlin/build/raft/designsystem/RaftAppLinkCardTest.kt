package build.raft.designsystem

import build.raft.designsystem.components.RaftAppLinkCardDefaults
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.raftAppLinkCardButtonLayout
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
    fun refreshTakesTheRowBesideTheMenuThatHoldsTheItemsLinks() {
        // A card with no action of the App's: Refresh is a button of the row, Open in Stamp and Open Link in ⋯.
        val buttons =
            listOf(
                button("Refresh", RaftPreviewCardButtonKind.Read, frameless = true),
                button("Open in Stamp", RaftPreviewCardButtonKind.Link).copy(opensItem = true),
                button("Open Link", RaftPreviewCardButtonKind.Link).copy(opensItem = true),
            )
        val block = raftAppLinkCardButtonLayout(buttons)
        assertEquals(listOf("Refresh"), block.row.map { it.label })
        assertEquals(listOf("Open in Stamp", "Open Link"), block.more.map { it.label })
        // Only links to the item: the row stays empty, every link is in the menu.
        val links = raftAppLinkCardButtonLayout(buttons.drop(1))
        assertTrue(links.row.isEmpty())
        assertEquals(listOf("Open in Stamp", "Open Link"), links.more.map { it.label })
        // The hover card's rule is unchanged: Refresh is in its menu and its first link takes the empty row.
        val hover = raftPreviewCardButtonLayout(buttons)
        assertEquals(listOf("Open in Stamp"), hover.row.map { it.label })
        assertEquals(listOf("Refresh", "Open Link"), hover.more.map { it.label })
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
