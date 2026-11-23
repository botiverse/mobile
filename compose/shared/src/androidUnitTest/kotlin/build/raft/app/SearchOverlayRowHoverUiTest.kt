package build.raft.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftPointerInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ⌘K search panel under a mouse, as on the web (slock SearchOverlay; raft-ui SearchEntityResult
 * and SearchMessageResult are list cells, lib/list-cell.ts): the result under the pointer lays the
 * cell's hover fill, and only ↑ / ↓ move the keyboard cursor (the yellow row), so the mouse and the
 * arrow keys each keep their own row (web useSearchKeyboardSelection has no pointer handling). The
 * cursor's row keeps its yellow under the pointer (a selected item doesn't answer hover).
 *
 * Drives the real panel ([SearchOverlayPanelFixture]: query "android", the cursor on "Search for")
 * with a mouse on a real composition, and reads each row's cell shape from the rendered pixels: 12dp
 * in from the list's edge, inside the shape (8dp in) and before the content (20dp in).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1440dp-h900dp")
class SearchOverlayRowHoverUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theRowUnderTheMouseHighlightsAndTheArrowKeysKeepTheirOwnRow() =
        runRobolectricComposeUiTest {
            showPanel()
            onNodeWithText(SearchFor).assertIsSelected()
            val rest = cells()

            // A message under the mouse: its row lays the hover fill; the cursor stays on "Search for".
            onNodeWithText(Cindy).performMouseInput { moveTo(center) }
            val overCindy = cells()
            assertNotEquals(rest.cindy, overCindy.cindy, "the message under the mouse shows no hover")
            assertEquals(rest.searchFor, overCindy.searchFor, "a hover elsewhere changed the cursor's row")
            assertEquals(rest.channel, overCindy.channel, "a row the mouse isn't on changed")
            onNodeWithText(SearchFor).assertIsSelected()

            // The cursor's own row keeps its yellow under the mouse (a selected row doesn't answer
            // hover), and the message the mouse left is back to rest.
            onNodeWithText(SearchFor).performMouseInput { moveTo(center) }
            val overSearchFor = cells()
            assertEquals(rest.searchFor, overSearchFor.searchFor, "the cursor's row changed under the mouse")
            assertEquals(rest.cindy, overSearchFor.cindy, "the message kept its hover after the mouse left it")

            // A person (a destination row) hovers too.
            onNodeWithText(Agent).performMouseInput { moveTo(center) }
            assertNotEquals(rest.agent, cells().agent, "the person under the mouse shows no hover")

            // ↓ with the mouse on the person moves from the cursor's row, not from the hovered one:
            // to the channel under "Search for", not to the message under the person.
            field().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNode(isSelected() and hasAnyDescendant(hasText(Channel))).assertExists()
            onNode(isSelected() and hasText(SearchFor)).assertDoesNotExist()
        }

    @Test
    fun searchForHoversOnceTheCursorMovedOffIt() =
        runRobolectricComposeUiTest {
            showPanel()
            // The arrow keys first (ui-test drops the mouse's hover state on a key press, so the
            // mouse comes after them here).
            field().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNode(isSelected() and hasAnyDescendant(hasText(Channel))).assertExists()
            val rest = cells()

            // "Search for" (a design-system search row) under the mouse: the hover fill; the
            // cursor's row keeps its own; the mouse leaving takes the hover away.
            onNodeWithText(SearchFor).performMouseInput { moveTo(center) }
            val over = cells()
            assertNotEquals(rest.searchFor, over.searchFor, "\"Search for\" under the mouse shows no hover")
            assertEquals(rest.channel, over.channel, "the cursor's row changed under a hover elsewhere")
            onNodeWithText(SearchFor).performMouseInput { exit() }
            assertEquals(rest, cells(), "a hover stayed after the mouse left")
        }

    private fun ComposeUiTest.showPanel() {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent { SearchOverlayPanelFixture(searchOverlayResultsState, pointerInput = RaftPointerInput.Fine) }
        // The field takes focus as the panel opens, so the arrow keys work at once.
        mainClock.advanceTimeBy(200)
        waitForIdle()
        field().assertIsFocused()
    }

    private fun ComposeUiTest.field(): SemanticsNodeInteraction = onNode(hasSetTextAction())

    /** Each row's cell colour now, read from the rendered panel. */
    private fun ComposeUiTest.cells(): Cells {
        waitForIdle()
        val pixels = onRoot().captureToImage().toPixelMap()
        // Every row spans the list; "Search for" is one button, so its bounds are the row's.
        val listStart = onNodeWithText(SearchFor).fetchSemanticsNode().boundsInRoot.left
        val x = listStart + with(density) { 12.dp.toPx() }

        fun at(text: String): Color {
            val y = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center.y
            return pixels[x.toInt(), y.toInt()]
        }
        return Cells(searchFor = at(SearchFor), channel = at(Channel), agent = at(Agent), cindy = at(Cindy))
    }

    private data class Cells(val searchFor: Color, val channel: Color, val agent: Color, val cindy: Color)

    private companion object {
        const val SearchFor = "Search for “android”"
        const val Channel = "android-artifacts"
        const val Agent = "Android Developer 4"
        const val Cindy = "Cindy"
    }
}
