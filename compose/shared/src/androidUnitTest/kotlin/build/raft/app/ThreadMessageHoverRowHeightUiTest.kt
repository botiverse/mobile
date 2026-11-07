package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A mouse over a message row shows its hover toolbar (web MessageHoverToolbar) over the row's top
 * right corner without changing the row's height. A one-line continuation row (32dp) is shorter
 * than the toolbar (36dp); when the toolbar counted in the row's layout, the hovered row grew 4dp
 * and every row above it jumped in the bottom-anchored list (iPad app on a Mac, desktop).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageHoverRowHeightUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class ToolbarState {
        var shown = false
    }

    @Test
    fun hoverToolbarDoesNotGrowAOneLineContinuationRow() =
        runRobolectricComposeUiTest {
            val toolbar = ToolbarState()
            showRow(toolbar, continuation = true)
            assertRowKeepsItsHeightUnderTheMouse(toolbar)
        }

    @Test
    fun hoverToolbarDoesNotGrowAGroupsFirstRow() =
        runRobolectricComposeUiTest {
            val toolbar = ToolbarState()
            showRow(toolbar, continuation = false)
            assertRowKeepsItsHeightUnderTheMouse(toolbar)
        }

    private fun ComposeUiTest.assertRowKeepsItsHeightUnderTheMouse(toolbar: ToolbarState) {
        val rest = rowBounds()
        assertFalse(toolbar.shown, "no toolbar before the mouse comes")
        onNodeWithTag(ROW).performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(toolbar.shown, "the mouse over the row shows its hover toolbar")
        val hovered = rowBounds()
        assertEquals(rest.top, hovered.top, "the row's top under the mouse")
        assertEquals(rest.height, hovered.height, "the row's height under the mouse (px)")
        onNodeWithTag(ROW).performMouseInput { exit() }
        waitForIdle()
        assertFalse(toolbar.shown, "the toolbar goes with the mouse")
        assertEquals(rest.height, rowBounds().height, "the row's height after the mouse leaves (px)")
    }

    /** The row's layout box in root px (not clipped: the toolbar may draw past it). */
    private fun ComposeUiTest.rowBounds(): IntRect {
        val node = onNodeWithTag(ROW).fetchSemanticsNode()
        return IntRect(node.positionInRoot.round(), node.size)
    }

    private fun ComposeUiTest.showRow(toolbar: ToolbarState, continuation: Boolean) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = RaftAppearance.Light, pointerInput = RaftPointerInput.Fine) {
                    // Room above for the toolbar's upper half, as the list leaves it.
                    Box(Modifier.fillMaxWidth().padding(top = 24.dp).testTag(ROW)) {
                        ThreadMessageCard(
                            message =
                                RaftThreadMessageItem(
                                    id = "hover-height-1",
                                    authorId = "user-alice",
                                    authorName = "Alice",
                                    authorKind = "human",
                                    avatarLabel = "A",
                                    timestampLabel = "2026-10-21T16:40:00Z",
                                    body = "raft-design shipped a big update.",
                                ),
                            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                            compactConsecutiveMessages = continuation,
                            suppressRepeatedAuthor = continuation,
                            timestampLabel = "4:40 PM",
                            onShowActions = {},
                            onOpenMember = {},
                            onOpenThread = {},
                            onOpenTask = {},
                            onOpenTaskStatusMenu = {},
                            onToggleReaction = {},
                            onOpenAttachment = {},
                            onOpenMarkdownTag = { _, _ -> },
                            onOpenMarkdownUrl = {},
                            hoverToolbar =
                                ThreadMessageHoverToolbarActions(
                                    reaction = true,
                                    reply = true,
                                    forward = true,
                                    save = true,
                                    saved = false,
                                    more = true,
                                    onShowReactions = {},
                                    onForward = {},
                                    onToggleSave = {},
                                    onShownChange = { toolbar.shown = it },
                                ),
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private companion object {
        const val ROW = "hover-height-row"
    }
}
