package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A message body selected with the mouse shows the web's small "Quote | Copy" toolbar, not the
 * full Compact New Message Menu; a finger selection never shows it. Drives the production
 * [ThreadInPlaceSelectableBody] on a real composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadInPlaceSelectionToolbarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        val copied = mutableListOf<String>()
        val quoted = mutableListOf<String>()
    }

    private fun ComposeUiTest.showBody(calls: Calls, withComposer: Boolean = true) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = { calls.copied += it })
        host.quoteIntoComposer = if (withComposer) { quote -> calls.quoted += quote } else null
        host.selectionMenu.publish { _ -> Box(Modifier.size(40.dp).testTag(FULL_MENU)) }
        setContent {
            CompositionLocalProvider(LocalThreadInPlaceTextSelectionHost provides host) {
                Column(Modifier.fillMaxSize().padding(top = 120.dp, start = 24.dp)) {
                    val controller = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                    ThreadInPlaceSelectableBody(
                        controller = controller,
                        fullMessageText = BODY,
                        wholeMessageText = BODY,
                        messageMenuOpen = false,
                        diagnosticsLogger = {},
                    ) {
                        BasicText(BODY, Modifier.width(360.dp).testTag(BODY_TAG))
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.toolbarShown(): Boolean =
        onAllNodes(hasTestTag(ThreadInPlaceSelectionToolbarTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.fullMenuShown(): Boolean =
        onAllNodes(hasTestTag(FULL_MENU), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Press on the body's first word and drag to its middle with the primary mouse button. */
    private fun ComposeUiTest.mouseSelect() {
        onNodeWithTag(BODY_TAG).performMouseInput {
            moveTo(Offset(2f, centerY))
            press()
            moveTo(Offset(width * 0.3f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
            release()
        }
        waitForIdle()
    }

    @Test
    fun aMouseSelectionShowsTheSelectionToolbarAndItsCopyCopiesThePick() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showBody(calls)
            mouseSelect()
            assertTrue(toolbarShown(), "a mouse selection shows Quote | Copy")
            assertTrue(!fullMenuShown(), "not the full message menu")
            onNode(hasText("Copy"), useUnmergedTree = true).performMouseInput { click(center) }
            waitForIdle()
            val copied = calls.copied.single()
            // Robolectric's synthetic drag may cover the whole body; then the selection's copy text is
            // the whole message, as threadInPlaceSelectionCopyText defines. Either way, the selection.
            assertTrue(copied.isNotEmpty() && BODY.startsWith(copied), "copies the selection: '$copied'")
            assertTrue(!toolbarShown(), "Copy dismisses the toolbar")
        }

    @Test
    fun quoteQuotesTheSelectionIntoTheComposer() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showBody(calls)
            mouseSelect()
            onNode(hasText("Quote"), useUnmergedTree = true).performMouseInput { click(center) }
            waitForIdle()
            val quote = calls.quoted.single()
            assertTrue(quote.startsWith("> "), "a Markdown quote: '$quote'")
            assertEquals(formatThreadMessageQuote(quote.removePrefix("> ")), quote)
            assertTrue(calls.copied.isEmpty())
            assertTrue(!toolbarShown(), "Quote dismisses the toolbar")
        }

    @Test
    fun withoutAComposerTheToolbarOffersCopyOnly() =
        runRobolectricComposeUiTest {
            showBody(Calls(), withComposer = false)
            mouseSelect()
            assertTrue(toolbarShown())
            assertTrue(onAllNodes(hasText("Quote"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodes(hasText("Copy"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }

    // A finger long-press is covered by the pure policy tests (ThreadInPlaceTextSelectionTest): a
    // Robolectric long-press opens the platform text toolbar's window, whose teardown crashes the
    // harness (null Surface), not the app.

    private companion object {
        const val BODY = "Ship the release notes before the launch review tomorrow"
        const val BODY_TAG = "thread_in_place_selection_test_body"
        const val FULL_MENU = "thread_in_place_selection_test_full_menu"
    }
}
