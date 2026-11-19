package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer tray's tiles reordered on a real composition, wired as the composer wires them
 * (the order the tray reports goes back in as its attachments): a 500ms hold then a drag moves a
 * tile; a tap previews and a swipe before the hold moves nothing; the remove sticker still
 * removes; a screen reader gets Move left / Move right on each tile. mdpi: 1dp = 1px, so a file
 * tile's slot is 176 + 8.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h400dp-mdpi")
class ThreadComposerAttachmentReorderUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val commits = mutableListOf<List<String>>()
    private val events = mutableListOf<Pair<String, String>>()

    @Test
    fun aHoldThenADragMovesTheTileAndPreviewsNothing() =
        runRobolectricComposeUiTest {
            setContent { Tray() }

            tile("a").performTouchInput {
                down(center)
                advanceEventTime(600)
                moveBy(Offset(100f, 0f))
                moveBy(Offset(100f, 0f))
                up()
            }
            waitForIdle()

            assertEquals(listOf(listOf("b", "a", "c")), commits)
            assertEquals(emptyList(), events)
            assertTrue(tile("a").left() > tile("b").left(), "the dropped tile shows in its new slot")
        }

    @Test
    fun aTapPreviewsAndASwipeBeforeTheHoldMovesNothing() =
        runRobolectricComposeUiTest {
            setContent { Tray() }

            tile("b").performClick()
            tile("b").performTouchInput {
                down(center)
                moveBy(Offset(60f, 0f))
                moveBy(Offset(60f, 0f))
                up()
            }
            waitForIdle()

            assertEquals(listOf("previewAttachment" to "b"), events)
            assertEquals(emptyList(), commits)
        }

    @Test
    fun theRemoveStickerStillRemoves() =
        runRobolectricComposeUiTest {
            setContent { Tray() }

            onNodeWithContentDescription("Remove c.pdf").performClick()
            waitForIdle()

            assertEquals(listOf("removeAttachment" to "c"), events)
            assertEquals(emptyList(), commits)
        }

    @Test
    fun aScreenReaderMovesATileLeftOrRight() =
        runRobolectricComposeUiTest {
            setContent { Tray() }

            assertEquals(listOf("Move right"), actionLabels("a"), "the first tile only moves right")
            assertEquals(listOf("Move left", "Move right"), actionLabels("b"))
            assertEquals(listOf("Move left"), actionLabels("c"), "the last tile only moves left")

            runOnIdle { actionsOf("b").single { it.label == "Move left" }.action() }
            waitForIdle()
            assertEquals(listOf(listOf("b", "a", "c")), commits)
            assertEquals(listOf("Move right"), actionLabels("b"), "the actions follow the new order")

            runOnIdle { actionsOf("a").single { it.label == "Move right" }.action() }
            waitForIdle()
            assertEquals(listOf("b", "c", "a"), commits.last())
        }

    @Test
    fun aHostThatKeepsItsOwnListOffersNoReorder() =
        runRobolectricComposeUiTest {
            setContent { Tray(reorderEnabled = false) }

            assertEquals(emptyList(), actionLabels("b"))
            tile("a").performTouchInput {
                down(center)
                advanceEventTime(600)
                moveBy(Offset(200f, 0f))
                up()
            }
            waitForIdle()
            assertEquals(emptyList(), commits)
        }

    @Composable
    private fun Tray(reorderEnabled: Boolean = true) {
        var attachments by remember {
            mutableStateOf(
                listOf("a", "b", "c").map { id ->
                    RaftPendingAttachmentItem(id = id, fileName = "$id.pdf", mimeType = "application/pdf", sizeBytes = 1_000L)
                }
            )
        }
        Box(Modifier.width(600.dp)) {
            ComposerAttachmentTray(
                attachments = attachments,
                enabled = true,
                previewEnabled = true,
                reorderEnabled = reorderEnabled,
                onReorder = { order ->
                    commits += order
                    val byId = attachments.associateBy { it.id }
                    attachments = order.mapNotNull(byId::get)
                },
                onEvent = { action, id -> events += action to id },
            )
        }
    }

    private fun SemanticsNodeInteractionsProvider.tile(id: String): SemanticsNodeInteraction =
        onNodeWithTag(ComposerAttachmentTileTagPrefix + id)

    /** The node a screen reader focuses inside tile [id]: the one carrying its custom actions. */
    private fun SemanticsNodeInteractionsProvider.tileActionNodes(id: String): List<SemanticsNode> =
        onAllNodes(
            hasAnyAncestor(hasTestTag(ComposerAttachmentTileTagPrefix + id)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        ).fetchSemanticsNodes()

    private fun SemanticsNodeInteractionsProvider.actionsOf(id: String) =
        tileActionNodes(id).single().config[SemanticsActions.CustomActions]

    private fun SemanticsNodeInteractionsProvider.actionLabels(id: String): List<String> =
        tileActionNodes(id).flatMap { node -> node.config[SemanticsActions.CustomActions].map { it.label } }

    private fun SemanticsNodeInteraction.left(): Float = getBoundsInRoot().left.value
}
