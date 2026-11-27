package build.raft.app

import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardElement
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.thread.ThreadAppLinkCardActionHost
import build.raft.app.thread.ThreadAppLinkCardPress
import build.raft.app.thread.button
import build.raft.app.thread.chipCardButtons
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.raftPreviewCardButtonLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #proj-mobile:#218: the card a chip opens has the App's buttons, as the web's hover card does
 * (botiverse/slock `LinkCardActions.tsx` `linkCardRowLayout` without a block): the App's first two
 * actions on the row (frameless first), or its first link when it has no action; Refresh, the
 * other links and any further action in the More menu, in the App's order. Before, the card had
 * Raft's Open Link alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAppLinkChipCardButtonsTest {
    private val url = "https://stamp.example.test/botiverse/mobile/pull/218"
    private val now = 100 * 60_000L

    private val openInStamp = RaftLinkCardV2Element.Button("Open in Stamp", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Navigate(url))
    private val docs = RaftLinkCardV2Element.Button("Docs", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Navigate("https://stamp.example.test/docs"))
    private val refresh = RaftLinkCardV2Element.Button("Refresh", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Read)
    private val merge =
        RaftLinkCardV2Element.Button("Merge", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Execute("merge", confirm = true))
    private val approve =
        RaftLinkCardV2Element.Button("Approve", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Execute("approve", confirm = false, undoWindowMs = 1000))
    private val close =
        RaftLinkCardV2Element.Button("Close", RaftLinkCardButtonVariant.Ghost, RaftLinkCardButtonAction.Execute("close", confirm = true))

    private val openLink = RaftPreviewCardButton("Open Link", RaftPreviewCardButtonKind.Link, onClick = {})

    /** A chip's card (a version 3 `hover` card) with [buttons] in the App's order, and a handle for each of [signed]. */
    private fun card(vararg buttons: Pair<String, RaftLinkCardV2Element.Button>, signed: Set<String> = setOf("merge", "approve", "close")) =
        AppLinkResult.Card(
            url = url,
            app = AppLinkApp("Stamp", null),
            title = "PR 218",
            cardV2 =
                RaftAppLinkCardV2(
                    root = "root",
                    elements =
                        buildMap<String, CardElement> {
                            put("root", CardStack(children = listOf("buttons")))
                            put("buttons", CardStack(CardStackDirection.Row, children = buttons.map { it.first }))
                            buttons.forEach { (id, button) -> put(id, button) }
                        },
                ),
            handles = signed.associateWith { "h-$it" },
            readAtMillis = now,
            businessRevision = "br-hover",
        )

    private class Recorder {
        val prepares = mutableListOf<Pair<String, String>>()
        var rereads = 0
        val toasts = mutableListOf<String>()
    }

    private fun press(card: AppLinkResult.Card, scope: CoroutineScope, recorder: Recorder = Recorder(), acts: Boolean = true): ThreadAppLinkCardPress {
        val host =
            ThreadAppLinkCardActionHost(
                prepare = { handle, revision ->
                    recorder.prepares += handle to revision
                    AppLinkActionResult.Failed("refused", "forbidden", outOfDate = false)
                },
                execute = { _, _, _ -> error("nothing is executed here") },
                reread = {
                    recorder.rereads += 1
                    true
                },
                toast = { recorder.toasts += it },
                nowMillis = { now },
            )
        return ThreadAppLinkCardPress(card, host.takeIf { acts }, scope, "failed", "Undo", "refresh failed", "changed")
    }

    private fun AppLinkResult.Card.buttons(press: ThreadAppLinkCardPress, opened: MutableList<String> = mutableListOf()) =
        chipCardButtons(openUri = { opened += it }, openLink = openLink, read = press::readButton) { button, action -> press.button(button, action) }

    private fun List<RaftPreviewCardButton>.labels() = map { it.label }

    @Test
    fun theFirstTwoActionsTakeTheRowAndTheRestGoesInTheMoreMenu() = runTest {
        // The product lead's desktop screenshot: ⋯ [Open in Stamp ↗, Refresh], then [Approve] [Merge].
        val card = card("open" to openInStamp, "refresh" to refresh, "merge" to merge, "approve" to approve, "close" to close)
        val buttons = card.buttons(press(card, this))
        assertEquals(listOf("Open in Stamp", "Refresh", "Merge", "Approve", "Close"), buttons.labels(), "the App's order; no Open Link: Open in Stamp goes to the link")
        val layout = raftPreviewCardButtonLayout(buttons)
        assertEquals(listOf("Approve", "Merge"), layout.row.labels(), "frameless first, the bordered one at the end")
        assertEquals(listOf("Open in Stamp", "Refresh", "Close"), layout.more.labels())
        assertEquals(
            listOf(RaftPreviewCardButtonKind.Link, RaftPreviewCardButtonKind.Read, RaftPreviewCardButtonKind.Execute),
            layout.more.map { it.kind },
        )
    }

    @Test
    fun raftsOpenLinkIsAddedWhenNoneOfTheAppsLinksGoesToTheLink() = runTest {
        val acting = card("refresh" to refresh, "merge" to merge)
        val withAction = raftPreviewCardButtonLayout(acting.buttons(press(acting, this)))
        assertEquals(listOf("Merge"), withAction.row.labels())
        assertEquals(listOf("Refresh", "Open Link"), withAction.more.labels())
        val reading = card("refresh" to refresh)
        val withoutAction = raftPreviewCardButtonLayout(reading.buttons(press(reading, this)))
        assertEquals(listOf("Open Link"), withoutAction.row.labels())
        assertEquals(listOf("Refresh"), withoutAction.more.labels())
    }

    @Test
    fun whatCannotBePressedIsNotDrawn() = runTest {
        // No handle signed for Merge: not drawn. One with the App's reason is drawn, disabled.
        val blocked = close.copy(disabledReason = "Checks are failing")
        val card = card("open" to openInStamp, "refresh" to refresh, "merge" to merge, "approve" to approve, "close" to blocked, signed = setOf("approve"))
        val buttons = card.buttons(press(card, this))
        assertEquals(listOf("Open in Stamp", "Refresh", "Approve", "Close"), buttons.labels())
        assertEquals("Checks are failing", buttons.last().disabledReason)
        // A message read without the batched read has nothing to act through: the App's links only.
        assertEquals(listOf("Open in Stamp"), card.buttons(press(card, this, acts = false)).labels())
    }

    @Test
    fun aPreparedActionClaimsTheChipCardsBusinessRevision() = runTest {
        val recorder = Recorder()
        val card = card("merge" to merge)
        card.buttons(press(card, this, recorder)).single { it.label == "Merge" }.onClick()
        advanceUntilIdle()
        assertEquals(listOf("h-merge" to "br-hover"), recorder.prepares)
        assertEquals(listOf("refused"), recorder.toasts)
    }
}
