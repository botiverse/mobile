package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SharedHomeSearchInputShadowOffset = 2.dp

/**
 * Owns the complete editing state synchronously for one mounted search field.
 *
 * Search results and their asynchronous store refreshes intentionally have no entry point into
 * this owner. The store receives a text projection after the local value has advanced, while the
 * editor keeps selection and composition in the exact native callback value.
 */
internal class SharedHomeSearchEditorOwner(initialText: String) {
    var value by mutableStateOf(
        TextFieldValue(initialText, selection = TextRange(initialText.length)),
        referentialEqualityPolicy()
    )
        private set

    fun acceptEditorValue(next: TextFieldValue, onTextChange: (String) -> Unit) {
        val textChanged = value.text != next.text
        value = next
        if (textChanged) onTextChange(next.text)
    }

    fun clear(onClear: () -> Unit): Boolean {
        if (value.text.isEmpty()) return false
        value = TextFieldValue("")
        onClear()
        return true
    }

    fun replaceText(nextText: String, onTextChange: (String) -> Unit) {
        value = TextFieldValue(nextText, selection = TextRange(nextText.length))
        onTextChange(nextText)
    }
}

private fun submitSharedHomeSearchInput(
    query: String,
    loading: Boolean,
    onSubmit: () -> Unit
) {
    if (query.isNotBlank() && !loading) {
        onSubmit()
    }
}

@Composable
internal fun SharedHomeSearchInput(
    query: String,
    loading: Boolean,
    interactive: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (interactive) {
        SlockBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            maxLines = 1,
            textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            fieldMinHeight = 38.dp,
            horizontalPadding = 11.dp,
            verticalPadding = 5.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = { submitSharedHomeSearchInput(query, loading, onSubmit) },
                    onDone = { submitSharedHomeSearchInput(query, loading, onSubmit) }
                ),
            showClearWhenNotEmpty = true,
            onClear = onClear,
            autoFocus = autoFocus,
            focusRequester = focusRequester ?: remember { FocusRequester() },
            modifier = modifier
        )
    } else {
        Box(
            modifier =
                modifier
                    .height(38.dp)
                    .padding(end = SharedHomeSearchInputShadowOffset, bottom = SharedHomeSearchInputShadowOffset)
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SharedHomeSearchInputShadowOffset, y = SharedHomeSearchInputShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 11.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = query.ifBlank { placeholder },
                        style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = if (query.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SharedHomeSearchInput(
    query: TextFieldValue,
    loading: Boolean,
    interactive: Boolean,
    onQueryChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (interactive) {
        SlockBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            maxLines = 1,
            textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            fieldMinHeight = 38.dp,
            horizontalPadding = 11.dp,
            verticalPadding = 5.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = { submitSharedHomeSearchInput(query.text, loading, onSubmit) },
                    onDone = { submitSharedHomeSearchInput(query.text, loading, onSubmit) }
                ),
            showClearWhenNotEmpty = true,
            onClear = onClear,
            autoFocus = autoFocus,
            focusRequester = focusRequester ?: remember { FocusRequester() },
            modifier = modifier
        )
    } else {
        Box(
            modifier =
                modifier
                    .height(38.dp)
                    .padding(end = SharedHomeSearchInputShadowOffset, bottom = SharedHomeSearchInputShadowOffset)
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SharedHomeSearchInputShadowOffset, y = SharedHomeSearchInputShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 11.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = query.text.ifBlank { placeholder },
                        style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = if (query.text.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
