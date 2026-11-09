package build.raft.app

import build.raft.app.data.CardBadgeTone
import build.raft.app.data.CardDuration
import build.raft.app.data.CardRelativeTime
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardTextTone
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.raftPreviewCardDetailSeparators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a v2 card's row Stack draws in the details row (web `LinkCardElements.tsx`, botiverse/slock 8de484350). */
class AppLinkCardV2RowPartsTest {
    private val open = RaftLinkCardV2Element.Button("Open", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Navigate("https://stamp.example.test/x"))

    private val card =
        RaftAppLinkCardV2(
            root = "root",
            elements =
                mapOf(
                    "root" to CardStack(children = listOf("row", "buttons")),
                    "row" to CardStack(CardStackDirection.Row, children = listOf("state", "author", "open", "updated", "running", "lines")),
                    "state" to RaftLinkCardV2Element.Badge("Merged", CardBadgeTone.Done),
                    "author" to RaftLinkCardV2Element.Text("Martin", RaftLinkCardTextTone.Muted),
                    "open" to open,
                    "updated" to CardRelativeTime("2026-10-23T09:55:00Z"),
                    "running" to CardDuration("2026-10-23T09:58:55Z"),
                    "lines" to CardStack(CardStackDirection.Row, children = listOf("plus")),
                    "plus" to RaftLinkCardV2Element.Text("+803 -498", RaftLinkCardTextTone.Warning),
                    "buttons" to CardStack(CardStackDirection.Row, children = listOf("again")),
                    "again" to open.copy(label = "Again"),
                ),
        )

    @Test
    fun aRowGivesItsDetailsInOrderWithoutButtons() {
        val parts = appLinkCardV2RowParts(card, card.elements["row"] as CardStack, depth = 1)
        assertEquals(
            listOf(
                AppLinkCardV2RowPart.State("Merged"),
                AppLinkCardV2RowPart.Text("Martin", RaftLinkCardTextTone.Muted),
                AppLinkCardV2RowPart.Time("2026-10-23T09:55:00Z"),
                AppLinkCardV2RowPart.Duration(CardDuration("2026-10-23T09:58:55Z")),
                AppLinkCardV2RowPart.Text("+803 -498", RaftLinkCardTextTone.Warning),
            ),
            parts,
        )
        // The design system's separators: a "·" between details, none next to the state chip.
        val details = listOf(RaftPreviewCardDetail.State("Merged"), RaftPreviewCardDetail.Text("Martin"), RaftPreviewCardDetail.Text("5 min"))
        assertEquals(listOf(false, false, true), raftPreviewCardDetailSeparators(details))
    }

    @Test
    fun aStackOfOnlyButtonsDrawsNothing() {
        assertFalse(appLinkCardV2Draws(card, "buttons", depth = 1))
        assertFalse(appLinkCardV2Draws(card, "open", depth = 2))
        assertFalse(appLinkCardV2Draws(card, "missing", depth = 1))
        assertTrue(appLinkCardV2Draws(card, "row", depth = 1))
        assertTrue(appLinkCardV2Draws(card, "root", depth = 0))
    }
}
