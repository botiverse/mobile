package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
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
import androidx.compose.foundation.shape.RoundedCornerShape
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


/**
 * raft-ui Input / InputGroup metrics for the Home search field ([raftField]): 44dp tall,
 * 12dp side padding, `text-field` 16/24, `foreground-hint` placeholder; the read-only
 * stand-in draws the same control (card fill, 1.5dp `line-field` edge, control radius).
 */
private val RaftSearchFieldHeight = RaftMetrics.FieldHeight

private fun raftSearchFieldTextStyle() = RaftType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = RaftColor.TextBlack)

@Composable
private fun RaftSearchFieldPreview(
    text: String,
    placeholder: String,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Box(
        modifier =
            modifier
                .height(RaftSearchFieldHeight)
                .background(RaftColor.Card, shape)
                .border(RaftAppStroke.Control, RaftColor.LineField, shape)
                .padding(horizontal = RaftMetrics.FieldPaddingHorizontal),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text.ifBlank { placeholder },
            style = raftSearchFieldTextStyle(),
            color = if (text.isBlank()) RaftColor.ForegroundHint else RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

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
    raftField: Boolean = false,
    modifier: Modifier = Modifier
) {
    val raft = raftField && raftActive()
    if (!interactive && raft) {
        RaftSearchFieldPreview(query, placeholder, modifier)
        return
    }
    if (interactive) {
        RaftBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            maxLines = 1,
            textStyle = if (raft) raftSearchFieldTextStyle() else RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
            fieldMinHeight = if (raft) RaftSearchFieldHeight else 38.dp,
            horizontalPadding = if (raft) RaftMetrics.FieldPaddingHorizontal else 11.dp,
            verticalPadding = if (raft) 0.dp else 5.dp,
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
        ) {
            // raft field look (static placeholder): control radius, card fill, field ink.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .raftControlSurface(fill = RaftColor.Card, border = RaftColor.LineField)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 11.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = query.ifBlank { placeholder },
                        style = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = if (query.isBlank()) RaftColor.TextMutedGray else RaftColor.TextBlack,
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
    raftField: Boolean = false,
    modifier: Modifier = Modifier
) {
    val raft = raftField && raftActive()
    if (!interactive && raft) {
        RaftSearchFieldPreview(query.text, placeholder, modifier)
        return
    }
    if (interactive) {
        RaftBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            maxLines = 1,
            textStyle = if (raft) raftSearchFieldTextStyle() else RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
            fieldMinHeight = if (raft) RaftSearchFieldHeight else 38.dp,
            horizontalPadding = if (raft) RaftMetrics.FieldPaddingHorizontal else 11.dp,
            verticalPadding = if (raft) 0.dp else 5.dp,
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
        ) {
            // raft field look (static placeholder): control radius, card fill, field ink.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .raftControlSurface(fill = RaftColor.Card, border = RaftColor.LineField)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 11.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = query.text.ifBlank { placeholder },
                        style = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = if (query.text.isBlank()) RaftColor.TextMutedGray else RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
