package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation

/**
 * Task #69 (iOS Pinyin: the candidate bar empties and the pinyin is committed as letters). CMP
 * 1.12's iOS text input mirrors the field through `PlatformTextInputMethodRequest.state` and calls
 * UIKit's `textWillChange`/`textDidChange` - which resets the system keyboard's composition -
 * whenever that state differs from what the IME itself produced. The request and the legacy
 * `BasicTextField` adapter behind it are shared skiko code, so this drives the real field the way
 * the iOS keyboard does (marked text through `editText`) while the field moves every frame, as it
 * does while the keyboard height changes, and checks that the field never hands the input
 * connection a value the IME did not produce.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RaftTextFieldImeCompositionTest {
    private fun PlatformTextInputMethodRequest.snapshot() =
        TextFieldValue(state.text, state.selection, state.composition)

    private fun assertMarkedTextIsNotEchoed(field: @Composable (Modifier) -> Unit) = runComposeUiTest {
        var request: PlatformTextInputMethodRequest? = null
        var bottomInset by mutableStateOf(0.dp)
        setContent {
            InterceptPlatformTextInput({ req, _ ->
                request = req
                awaitCancellation()
            }) {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.padding(bottom = bottomInset)) { field(Modifier.testTag("field")) }
                }
            }
        }
        onNodeWithTag("field").performClick()
        waitForIdle()
        val session = checkNotNull(request) { "the focused field started no text input session" }
        mainClock.autoAdvance = false
        val echoes = mutableListOf<String>()
        for (marked in listOf("k", "k r", "k r yi")) {
            session.editText { setComposingText(marked, 1) }
            val ime = session.snapshot()
            bottomInset += 10.dp
            repeat(3) {
                mainClock.advanceTimeByFrame()
                val now = session.snapshot()
                if (now != ime) echoes += "after '$marked': field $now, IME $ime"
            }
        }
        assertEquals(emptyList(), echoes)
    }

    @Test
    fun stringFieldKeepsTheImeComposition() {
        var text by mutableStateOf("")
        assertMarkedTextIsNotEchoed { modifier ->
            RaftTextField(value = text, onValueChange = { text = it }, modifier = modifier)
        }
    }

    @Test
    fun valueFieldKeepsTheImeComposition() {
        var value by mutableStateOf(TextFieldValue(""))
        assertMarkedTextIsNotEchoed { modifier ->
            RaftTextField(value = value, onValueChange = { value = it }, modifier = modifier)
        }
    }
}
