package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerTextFieldValueTest {
    @Test
    fun duplicateGuardKeepsSelectionOnlyUpdatesForRemeasure() {
        val current = TextFieldValue(
            text = "hello world",
            selection = TextRange(5)
        )
        val next = TextFieldValue(
            text = "hello world",
            selection = TextRange(11)
        )

        assertFalse(
            shouldDropThreadComposerDuplicateValue(
                next = next,
                current = current,
                seedText = ""
            )
        )
    }

    @Test
    fun duplicateGuardDropsExactDuplicateAfterSeedChanges() {
        val current = TextFieldValue(
            text = "hello world",
            selection = TextRange(11)
        )

        assertTrue(
            shouldDropThreadComposerDuplicateValue(
                next = current,
                current = current,
                seedText = ""
            )
        )
    }
}
