package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftSwitch
import dev.vcase.VCase

/**
 * [build.raft.designsystem.components.RaftSaveStatus] (raft-ui 0.84.0 SaveStatus,
 * `save-status/compositions/preview` and `failed`): under a select in a field and under a
 * settings row's description, idle (no line, no room) and failed.
 */
@VCase("ds.save-status.gallery")
@Composable
fun SaveStatusGallery() {
    val states = listOf("Idle" to RaftSaveStatusState.Idle, "Failed" to null)
    Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        states.forEach { (name, idle) ->
            Caption("$name: field with a select")
            CanvasSurface(padded = true) {
                RaftField(
                    label = "Time zone",
                    description = "Used for times in messages.",
                    saveStatus = idle ?: RaftSaveStatusState.Failed("Couldn't change the time zone. Try again."),
                ) {
                    RaftSelectTrigger(value = "Asia/Shanghai", onClick = {})
                }
            }
            Caption("$name: settings row")
            CanvasSurface {
                RaftListRow(
                    title = "Mute this server",
                    variant = RaftListRowVariant.Plain,
                    description = "No notifications from its channels.",
                    saveStatus = idle ?: RaftSaveStatusState.Failed("Couldn't mute this server. Try again."),
                    trailing = { RaftSwitch(checked = true, onCheckedChange = {}) },
                )
            }
        }
    }
}
