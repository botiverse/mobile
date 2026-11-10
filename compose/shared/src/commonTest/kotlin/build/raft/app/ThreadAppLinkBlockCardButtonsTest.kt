package build.raft.app

import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.raftAppLinkCardButtonLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A block card's buttons ([blockCardButtons]) as the web draws them (botiverse/slock
 * `LinkCardActions.tsx` `linkCardRowLayout` with a block): the App's Refresh (`read`) is a button
 * of the card, not dropped (artin: "手机版卡片好像少了一个刷新按钮").
 */
class ThreadAppLinkBlockCardButtonsTest {
    private val url = "https://stamp.example.test/approvals/42"

    /** A Stamp approval card: Refresh (read, ghost), then Open in Stamp (navigate to the item). */
    private val card =
        RaftAppLinkCardV2(
            root = "root",
            elements =
                mapOf(
                    "root" to CardStack(children = listOf("buttons")),
                    "buttons" to CardStack(CardStackDirection.Row, children = listOf("refresh", "open")),
                    "refresh" to RaftLinkCardV2Element.Button("Refresh", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Read),
                    "open" to RaftLinkCardV2Element.Button("Open in Stamp", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Navigate(url)),
                ),
        )

    private val openLink = RaftPreviewCardButton("Open Link", RaftPreviewCardButtonKind.Link, onClick = {})

    @Test
    fun refreshIsOnTheRowAndTheItemsLinkInTheMenu() {
        var refreshed = 0
        val buttons =
            card.blockCardButtons(url, openUri = {}, openLink = openLink, read = { button ->
                RaftPreviewCardButton(button.label, RaftPreviewCardButtonKind.Read, onClick = { refreshed += 1 }, frameless = true)
            })
        assertEquals(listOf("Refresh", "Open in Stamp"), buttons.map { it.label }, "the App's order; no Open Link: Open in Stamp goes to the link")
        assertFalse(buttons[0].opensItem)
        assertTrue(buttons[1].opensItem)
        val layout = raftAppLinkCardButtonLayout(buttons)
        assertEquals(listOf("Refresh"), layout.row.map { it.label })
        assertEquals(listOf("Open in Stamp"), layout.more.map { it.label })
        layout.row.single().onClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun withoutAWayToReadTheCardAgainRefreshIsNotDrawnAndOpenLinkIsTheItemsLink() {
        val other = card.copy(elements = card.elements + ("open" to RaftLinkCardV2Element.Button("Docs", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Navigate("https://stamp.example.test/docs"))))
        val buttons = other.blockCardButtons(url, openUri = {}, openLink = openLink)
        assertEquals(listOf("Docs", "Open Link"), buttons.map { it.label })
        assertEquals(listOf(false, true), buttons.map { it.opensItem })
        assertEquals(listOf("Docs"), raftAppLinkCardButtonLayout(buttons).row.map { it.label })
    }
}
