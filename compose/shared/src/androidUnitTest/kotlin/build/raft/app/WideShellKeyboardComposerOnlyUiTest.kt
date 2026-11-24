package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #338 step 3 (Kirby 640ad7a2, "通栏全宽"): on a phone held sideways (914 x 411) in the wide shell, with the keyboard
 * up only the composer fits above it; while the composer is what is typed in, the rail and the list column step aside
 * and it runs the window's full width. The step aside and back moves nothing in composition: the composer keeps its
 * focus and its draft. A keyboard up for something else (a search field in the list) leaves the rail and the list.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w914dp-h411dp-mdpi")
class WideShellKeyboardComposerOnlyUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun typedInWithOnlyItAboveTheKeyboardTheComposerRunsTheWindowWidthAndKeepsFocus() =
        runRobolectricComposeUiTest {
            val ime = RaftHostImeInsets()
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply {
                            pageData =
                                PageData(
                                    JSONObject().apply {
                                        put("slockWindowId", "test:wide-shell-keyboard-composer-only")
                                        put("statusBarHeight", "24")
                                    },
                                )
                        }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalRaftAppShellTopBar provides remember { RaftAppShellTopBarState() },
                    LocalRaftHostImeInsets provides ime,
                ) {
                    RaftDesignSystemTheme { Box(Modifier.fillMaxSize()) { WideShellWithConversation(914.dp) } }
                }
            }
            waitForIdle()
            assertTrue(shown(WIDE_SHELL_KEYBOARD_RAIL) && shown(WIDE_SHELL_KEYBOARD_LIST), "keyboard down: rail and list")

            // The keyboard up for something other than the composer (mdpi: a px is a dp): rail and list stay.
            runOnIdle { ime.imeBottomPx = 250 }
            waitForIdle()
            assertTrue(shown(WIDE_SHELL_KEYBOARD_RAIL) && shown(WIDE_SHELL_KEYBOARD_LIST), "not typing in the composer: rail and list stay")

            // Typing in the composer: rail and list step aside, the composer runs the window's width.
            val field = onNode(hasSetTextAction(), useUnmergedTree = true)
            field.performClick()
            field.performTextInput("draft")
            waitForIdle()
            field.assertIsFocused()
            assertFalse(shown(WIDE_SHELL_KEYBOARD_RAIL), "typing in the composer: the rail is aside")
            assertFalse(shown(WIDE_SHELL_KEYBOARD_LIST), "typing in the composer: the list is aside")
            val composer = onNodeWithTag(WIDE_SHELL_KEYBOARD_COMPOSER, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(composer.left < 20.dp, "the composer starts at the window's start edge: ${composer.left}")
            assertTrue(composer.right > 894.dp, "the composer ends at the window's end edge: ${composer.right}")
            field.assertTextContains("draft")

            // The keyboard down: everything back at once, the composer still focused with its draft.
            runOnIdle { ime.imeBottomPx = 0 }
            waitForIdle()
            assertTrue(shown(WIDE_SHELL_KEYBOARD_RAIL) && shown(WIDE_SHELL_KEYBOARD_LIST), "keyboard down: rail and list back")
            field.assertIsFocused()
            field.assertTextContains("draft")

            // And up again while it is typed in: aside again, still focused.
            runOnIdle { ime.imeBottomPx = 250 }
            waitForIdle()
            assertFalse(shown(WIDE_SHELL_KEYBOARD_RAIL), "keyboard up again: the rail is aside")
            field.assertIsFocused()
        }

    /** Whether a node with [tag] is placed and has a size: drawn, in reach. */
    private fun ComposeUiTest.shown(tag: String): Boolean =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
            node.layoutInfo.isPlaced && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
        }
}
