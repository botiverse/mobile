package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftPickerTriggerButton
import dev.vcase.VCase

/**
 * raft-ui 0.106.2 "List popups shrink and scroll on their side before flipping": a twelve-row
 * menu under a trigger with about 230dp below it (more than five rows' 188dp) and less above
 * opens below, shrunk to that room, its rows scrolling under the faded bottom edge. Before, it
 * kept its whole height and was pushed up over its trigger.
 */
@VCase("ds.menu-popup.long", settleFrames = 4, frameIntervalMs = 0)
@Composable
fun MenuPopupLongGallery() {
    CompositionLocalProvider(LocalInputModeManager provides PlacementTouchMode) {
        Box(Modifier.size(360.dp, 440.dp)) {
            Box(Modifier.padding(start = 16.dp, top = 150.dp)) {
                RaftPickerTriggerButton("Move to", {})
                RaftDropdownMenu(expanded = true, onDismissRequest = {}) {
                    listOf(
                        "General", "Design", "Engineering", "Launch", "Marketing", "Mobile",
                        "Research", "Sales", "Support", "Visual Testing", "Web", "Writing",
                    ).forEachIndexed { i, row -> RaftMenuItem(row, {}, checked = i == 0) }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private object PlacementTouchMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Touch

    override fun requestInputMode(inputMode: InputMode): Boolean = false
}
