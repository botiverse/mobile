package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue

internal fun textFieldValueAtEnd(text: String): TextFieldValue =
    TextFieldValue(text = text, selection = TextRange(text.length))

internal fun TextFieldValue.coerceTextLength(maxLength: Int): TextFieldValue {
    require(maxLength >= 0)
    // Applying a limit mid-composition can restart the input connection and discard IME state.
    if (text.length <= maxLength || composition != null) return this
    return copy(
        text = text.take(maxLength),
        selection =
            TextRange(
                selection.start.coerceIn(0, maxLength),
                selection.end.coerceIn(0, maxLength)
            ),
        composition = null
    )
}
