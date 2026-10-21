package build.raft.designsystem

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * Regression for the 1.15.0 (11500007) launch crash: `ClassCastException: RaftButtonKt$$
 * ExternalSyntheticLambda9 cannot be cast to MutableState` in `collectIsPressedAsState` <-
 * `raftIsPressed` <- `RaftButtonBox`. The interaction-state reads sat behind `enabled &&`,
 * so the remembered slots changed as `enabled` flipped (the composer send button enabling
 * as you type). Flips `enabled` many times, with the sources pressed, hovered and focused,
 * and expects composition to keep going.
 */
@OptIn(ExperimentalTestApi::class)
class RaftConditionalInteractionStateTest {
    @Test
    fun togglingEnabledAcrossRecompositionsKeepsTheSlotTableAligned() =
        runComposeUiTest {
            var enabled by mutableStateOf(false)
            var loading by mutableStateOf(false)
            val iconSource = MutableInteractionSource()
            val buttonSource = MutableInteractionSource()
            val checkboxSource = MutableInteractionSource()
            val content: @Composable () -> Unit = {
                Column {
                    RaftIconButton(onClick = {}, tooltip = "Send", enabled = enabled, interactionSource = iconSource) {}
                    RaftIconButton(onClick = {}, tooltip = null, contentDescription = "Attach", enabled = enabled) {}
                    RaftButton("Save", onClick = {}, enabled = enabled, loading = loading, interactionSource = buttonSource)
                    RaftCheckbox(checked = enabled, onCheckedChange = {}, enabled = enabled, interactionSource = checkboxSource)
                    RaftTextField(value = if (enabled) "hi" else "", onValueChange = {}, enabled = enabled)
                }
            }
            setContent { RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content) }

            val press = PressInteraction.Press(Offset.Zero)
            val hover = HoverInteraction.Enter()
            val focus = FocusInteraction.Focus()
            repeat(24) { i ->
                enabled = !enabled
                loading = i % 3 == 0
                waitForIdle()
                when (i % 4) {
                    0 -> {
                        iconSource.tryEmit(press)
                        buttonSource.tryEmit(hover)
                        checkboxSource.tryEmit(focus)
                    }
                    2 -> {
                        iconSource.tryEmit(PressInteraction.Release(press))
                        buttonSource.tryEmit(HoverInteraction.Exit(hover))
                        checkboxSource.tryEmit(FocusInteraction.Unfocus(focus))
                    }
                }
                waitForIdle()
            }
            onNodeWithText("Save").assertExists()
        }
}
