package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's drop zone on a real composition: a file drag over it shows the "Drop files to
 * attach" overlay, a drop takes it away and hands the files to the page; a composer that cannot
 * attach shows nothing. Robolectric cannot synthesise a platform DragEvent, so the test drives
 * [ThreadComposerFileDropState], which the zone's drop target forwards every drag callback to.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadComposerFileDropZoneUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aFileDragShowsTheOverlayAndADropHandsTheFilesOver() =
        runRobolectricComposeUiTest {
            val state = ThreadComposerFileDropState()
            val dropped = mutableListOf<ThreadComposerFileDrop>()
            setContent {
                RaftTheme {
                    ThreadComposerFileDropZone(enabled = true, onDropFiles = { dropped += it }, state = state) {
                        Box(Modifier.size(320.dp, 96.dp))
                    }
                }
            }
            onNodeWithTag(ThreadComposerFileDropOverlayTag).assertDoesNotExist()
            runOnIdle { state.onDragEntered() }
            onNodeWithTag(ThreadComposerFileDropOverlayTag).assertExists()
            runOnIdle { state.onDragLeft() }
            onNodeWithTag(ThreadComposerFileDropOverlayTag).assertDoesNotExist()
            runOnIdle { state.onDragEntered() }
            val accepted =
                runOnIdle {
                    state.onDropped(
                        threadComposerFileDrop(
                            listOf(
                                ThreadComposerDroppedItem("content://docs/1"),
                                ThreadComposerDroppedItem("content://docs/1"),
                                ThreadComposerDroppedItem("content://docs/2"),
                            )
                        )
                    ) { dropped += it }
                }
            onNodeWithTag(ThreadComposerFileDropOverlayTag).assertDoesNotExist()
            assertTrue(accepted)
            assertEquals(listOf(listOf("content://docs/1", "content://docs/2")), dropped.map { it.fileRefs })
        }

    @Test
    fun aComposerThatCannotAttachShowsNoOverlay() =
        runRobolectricComposeUiTest {
            val state = ThreadComposerFileDropState()
            var enabled by mutableStateOf(true)
            setContent {
                RaftTheme {
                    ThreadComposerFileDropZone(enabled = enabled, onDropFiles = {}, state = state) {
                        Box(Modifier.size(320.dp, 96.dp))
                    }
                }
            }
            runOnIdle { state.onDragEntered() }
            onNodeWithTag(ThreadComposerFileDropOverlayTag).assertExists()
            runOnIdle { enabled = false }
            onNodeWithTag(ThreadComposerFileDropOverlayTag).assertDoesNotExist()
            assertFalse(runOnIdle { state.dragging }, "a composer that turns read-only drops the drag state")
        }
}
