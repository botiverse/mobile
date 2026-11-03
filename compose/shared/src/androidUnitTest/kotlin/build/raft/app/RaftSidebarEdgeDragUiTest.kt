package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn
import build.raft.app.data.RaftListPaneKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sidebar's edge on a real composition, wired as SettingsHomePage wires it (web
 * ColumnResizeHandle with `snap` "collapse"): a finger drag on the seam resizes the list and saves
 * the width; a drag more than 40dp under the 200dp minimum collapses it through the rail's state,
 * dragging back while still held brings it back, and Expand restores the saved width. See
 * AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftSidebarEdgeDragUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val kind = RaftListPaneKind.Sidebar
    private val column = RaftCollapsibleColumn.Sidebar

    private fun ComposeUiTest.showSplit(values: InMemoryRaftKeyValueStore): RaftSidebarCollapseState {
        val preferences = KeyValueChatLayoutPreferenceStore(values)
        val collapse = RaftSidebarCollapseState(preferences)
        setContent {
            RaftAdaptiveListDetailSplit(
                kind = kind,
                widths = remember { RaftListPaneWidths(preferences) },
                dualPane = true,
                active = true,
                hasSelection = true,
                detailBottomInset = 0.dp,
                listPane = { Box(Modifier.fillMaxSize().testTag(LIST)) },
                detailPane = { modifier -> Box(modifier.testTag(DETAIL)) },
                collapsed = collapse.isHidden(column),
                onCollapsedChange = { collapse.setHidden(column, it) },
            )
        }
        waitForIdle()
        return collapse
    }

    // mdpi: a px is a dp.
    private fun ComposeUiTest.listWidth(): Float = onNodeWithTag(LIST).fetchSemanticsNode().size.width.toFloat()

    @Test
    fun draggingTheEdgeResizesTheSidebarAndSavesTheWidth() =
        runRobolectricComposeUiTest {
            val values = InMemoryRaftKeyValueStore()
            showSplit(values)
            assertEquals(240f, listWidth())

            onNodeWithContentDescription(HANDLE).performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(10f, 0f)) }
                up()
            }
            waitForIdle()
            // The drag moved 100; the touch slop it took to start is not part of it.
            val widened = listWidth()
            assertTrue(widened in 320f..340f, "width after a 100dp drag: $widened")
            assertEquals(widened.toInt().toString(), values.getString(kind.storageKey))

            // Far past the 400 maximum, the list stops there.
            onNodeWithContentDescription(HANDLE).performTouchInput {
                down(center)
                repeat(20) { moveBy(Offset(20f, 0f)) }
                up()
            }
            waitForIdle()
            assertEquals(400f, listWidth())
            assertEquals("400", values.getString(kind.storageKey))
        }

    @Test
    fun draggingPastTheMinimumCollapsesTheSidebarAndExpandRestoresItsWidth() =
        runRobolectricComposeUiTest {
            val values = InMemoryRaftKeyValueStore()
            values.putString(kind.storageKey, "300")
            val collapse = showSplit(values)
            assertEquals(300f, listWidth())

            // A 40dp drag left: still above the 200 minimum, so it only resizes, and saves.
            onNodeWithContentDescription(HANDLE).performTouchInput {
                down(center)
                repeat(4) { moveBy(Offset(-10f, 0f)) }
                up()
            }
            waitForIdle()
            val kept = listWidth()
            assertTrue(kept in 260f..280f, "width after a 40dp drag: $kept")
            assertEquals(kept.toInt().toString(), values.getString(kind.storageKey))
            assertFalse(collapse.isHidden(column))

            // More than 40 under the minimum: collapsed as the rail's Collapse Sidebar does, at
            // once; dragging back across the line while still held brings it back.
            onNodeWithContentDescription(HANDLE).performTouchInput {
                down(center)
                repeat(15) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            onNodeWithTag(LIST).assertDoesNotExist()
            onNodeWithTag(DETAIL).assertExists()
            assertTrue(collapse.isHidden(column))
            assertEquals("true", values.getString(column.storageKey))
            onNodeWithContentDescription(HANDLE).performTouchInput { repeat(6) { moveBy(Offset(10f, 0f)) } }
            waitForIdle()
            onNodeWithTag(LIST).assertExists()
            assertFalse(collapse.isHidden(column))

            // Past the line again and released: it stays collapsed, and the over-drag is not saved.
            onNodeWithContentDescription(HANDLE).performTouchInput {
                repeat(6) { moveBy(Offset(-10f, 0f)) }
                up()
            }
            waitForIdle()
            onNodeWithTag(LIST).assertDoesNotExist()
            assertTrue(collapse.isHidden(column))
            assertEquals(kept.toInt().toString(), values.getString(kind.storageKey))
            onNodeWithContentDescription(HANDLE).assertDoesNotExist()

            // Expand Sidebar: the list comes back at the width from before the drag.
            runOnIdle { collapse.setHidden(column, false) }
            waitForIdle()
            assertEquals(kept, listWidth())
        }

    private companion object {
        const val LIST = "sidebar-list"
        const val DETAIL = "detail"
        const val HANDLE = "Resize sidebar"
    }
}
