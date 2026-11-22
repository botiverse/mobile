package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A filter whose picker the page draws ([RaftFitToolbarChoice.popup]: Tasks' searchable filters,
 * a popover under the chip on a wide window, a sheet on a phone) opens one picker, next to the
 * trigger the toolbar shows: never one more for each pass that measures the toolbar's forms,
 * which would stack sheets or popovers (#306).
 */
@OptIn(ExperimentalTestApi::class)
class RaftFitToolbarFilterPopupTest {
    @Test
    fun aFiltersOwnPickerOpensOnceFromItsTrigger() =
        runComposeUiTest {
            var open by mutableStateOf(false)
            var pickers = 0
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.width(1024.dp)) {
                        RaftFitToolbar(
                            filters =
                                listOf(
                                    RaftFitToolbarChoice(
                                        text = "Assignee",
                                        icon = { Spacer(Modifier.size(14.dp)) },
                                        options = emptyList(),
                                        selectedKey = null,
                                        onSelect = {},
                                        testTag = "assignee",
                                        onOpen = { open = true },
                                        popup = {
                                            if (open) {
                                                DisposableEffect(Unit) {
                                                    pickers++
                                                    onDispose { pickers-- }
                                                }
                                            }
                                        },
                                    ),
                                ),
                        )
                    }
                }
            }
            waitForIdle()
            assertEquals(0, pickers, "closed: no picker")

            onNodeWithTag("assignee").performClick()
            waitForIdle()
            assertEquals(1, pickers, "open: one picker, beside the trigger")

            open = false
            waitForIdle()
            assertEquals(0, pickers, "closed again")
        }
}
