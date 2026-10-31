package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * [build.raft.designsystem.components.RaftSaveStatus] (raft-ui 0.54.0 SaveStatus,
 * `save-status/compositions/preview` and `failed`), light and dark: under a select in a field
 * and under a settings row's description, idle (the empty reserved line), saved and failed.
 */
class SaveStatusScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)
    private val states =
        listOf(
            "Idle" to RaftSaveStatusState.Idle,
            "Saved" to RaftSaveStatusState.Saved,
            "Failed" to RaftSaveStatusState.Failed("Couldn't mute this server. Try again."),
        )

    @Test
    fun saveStatus() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("save_status_${appearance.name.lowercase()}", appearance) {
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    states.forEach { (name, state) ->
                        Caption("$name: field with a select")
                        Surface {
                            RaftField(label = "Time zone", description = "Used for times in messages.", saveStatus = state) {
                                RaftSelectTrigger(value = "Asia/Shanghai", onClick = {})
                            }
                        }
                        Caption("$name: settings row")
                        Surface(padded = false) {
                            RaftListRow(
                                title = "Mute this server",
                                variant = RaftListRowVariant.Plain,
                                description = "No notifications from its channels.",
                                saveStatus = state,
                                trailing = { RaftSwitch(checked = true, onCheckedChange = {}) },
                            )
                        }
                    }
                }
            }
        }

    @Composable
    private fun Surface(padded: Boolean = true, content: @Composable () -> Unit) {
        Column(Modifier.background(RaftTheme.colors.layerCanvas).then(if (padded) Modifier.padding(16.dp) else Modifier)) { content() }
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
