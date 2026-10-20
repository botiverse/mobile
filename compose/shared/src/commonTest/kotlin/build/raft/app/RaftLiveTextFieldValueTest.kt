package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.ui.TextInputState
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftLiveTextFieldValueTest {
    @Test
    fun imeEditNotYetRecomposedIsWhatSendReads() {
        val live = RaftLiveTextFieldValue(TextFieldValue("hel", selection = TextRange(3)))
        // The IME commits "hello" (onValueChange) and Send fires before the next recomposition.
        val committed = TextFieldValue("hello", selection = TextRange(5), composition = TextRange(0, 5))
        live.recordEdit(committed)

        assertEquals(committed, live.value)
        val snapshot = live.value.toRaftTextInputState().raftWithFinishedComposition()
        assertEquals("hello", snapshot.text)
        assertEquals(TextInputState.NO_COMPOSITION, snapshot.compositionStart)
        assertEquals(TextInputState.NO_COMPOSITION, snapshot.compositionEnd)
    }

    @Test
    fun composedCallerValueWinsAfterTheEdit() {
        val live = RaftLiveTextFieldValue(TextFieldValue("draft"))
        live.recordEdit(TextFieldValue("draft!"))
        // The caller rejected the edit and recomposed with its own value.
        live.recordComposed(TextFieldValue("draft"))
        assertEquals("draft", live.value.text)
        // Programmatic clear after send.
        live.recordComposed(TextFieldValue(""))
        assertEquals("", live.value.text)
    }
}
