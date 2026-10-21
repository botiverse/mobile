package build.raft.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.material3.Text
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * Regression for the 1.15.0 (11500007) launch crash: `ClassCastException: RaftButtonKt$$Lambda cannot be
 * cast to MutableState` in `collectIsPressedAsState`. The Kotlin 2.4.21 Compose compiler emits no
 * replace group for the first composable call inside `x && (raftIsPressed(s) || ...)`, so flipping
 * `enabled` / `loading` shifted the slot table. This test crashed 5/5 on 2f964bd5 and passes once the
 * `raftIs*` calls are unconditional (#3356).
 */
@OptIn(ExperimentalTestApi::class)
class RaftIconButtonToggleCrashTest {
    private fun toggle(withTooltip: Boolean, startEnabled: Boolean, startLoading: Boolean) =
        runComposeUiTest {
            var enabled by mutableStateOf(startEnabled)
            var loading by mutableStateOf(startLoading)
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    RaftIconButton(
                        onClick = {},
                        tooltip = if (withTooltip) "Send" else null,
                        contentDescription = "Send",
                        enabled = enabled,
                        loading = loading,
                    ) { Text(">") }
                }
            }
            waitForIdle()
            repeat(3) {
                enabled = !enabled
                waitForIdle()
                loading = !loading
                waitForIdle()
            }
        }

    @Test fun iconButtonWithTooltipDisabledToEnabled() = toggle(withTooltip = true, startEnabled = false, startLoading = false)

    @Test fun iconButtonWithTooltipEnabledToDisabled() = toggle(withTooltip = true, startEnabled = true, startLoading = false)

    @Test fun iconButtonNoTooltipDisabledToEnabled() = toggle(withTooltip = false, startEnabled = false, startLoading = false)

    @Test fun iconButtonLoadingToIdle() = toggle(withTooltip = true, startEnabled = true, startLoading = true)

    @Test
    fun textButtonDisabledToEnabled() =
        runComposeUiTest {
            var enabled by mutableStateOf(false)
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    RaftButton(text = "Send", onClick = {}, enabled = enabled)
                }
            }
            waitForIdle()
            repeat(3) {
                enabled = !enabled
                waitForIdle()
            }
        }
}
