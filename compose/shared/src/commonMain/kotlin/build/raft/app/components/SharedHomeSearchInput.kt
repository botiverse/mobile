package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.TextField
import build.raft.app.raftArgbHex
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
 * The Home search field ([raftField]) is web MessageSearchPage's `searchFieldInner`: a raft-ui
 * InputGroup 44dp tall with a 1dp `line-field` border on `layer-card` (control radius), an
 * 18dp `foreground-hint` search glyph 12dp in from the border and 8dp before the text
 * (`text-field` 16/24), and, while there is text, a 32dp ghost clear button (18dp X in
 * `foreground`) 12dp from the end. Focused, the focus ring (`shadow-focus`: 2dp gap, 2dp
 * `line-strong`) goes round the whole group.
 */
private val RaftSearchFieldHeight = 44.dp
private val RaftSearchFieldBorder = 1.dp
private val RaftSearchFieldAddonInset = 12.dp
private val RaftSearchFieldIconSize = 18.dp
private val RaftSearchFieldClearButtonSize = 32.dp

private fun raftSearchFieldTextStyle() = RaftType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = RaftColor.TextBlack)

private fun Modifier.raftSearchFieldFocusRing(focused: Boolean): Modifier =
    if (!focused) {
        this
    } else {
        drawBehind {
            val gap = 2.dp.toPx()
            val ring = 2.dp.toPx()
            val outset = gap + ring / 2f
            drawRoundRect(
                color = RaftColor.LineField,
                topLeft = Offset(-outset, -outset),
                size = Size(size.width + outset * 2f, size.height + outset * 2f),
                cornerRadius = CornerRadius(RaftAppRadius.Control.toPx() + outset),
                style = Stroke(ring)
            )
        }
    }

@Composable
private fun RaftSearchFieldGroup(
    focused: Boolean,
    showClear: Boolean,
    onClear: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Row(
        modifier =
            modifier
                .height(RaftSearchFieldHeight)
                .raftSearchFieldFocusRing(focused)
                .background(RaftColor.Card, shape)
                .border(RaftSearchFieldBorder, RaftColor.LineField, shape)
                .padding(horizontal = RaftSearchFieldBorder),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            RaftLucideIconName.Search,
            color = raftArgbHex(RaftColor.ForegroundHint),
            modifier = Modifier.padding(start = RaftSearchFieldAddonInset, end = 8.dp).size(RaftSearchFieldIconSize)
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            content()
        }
        if (showClear) {
            Box(
                modifier =
                    Modifier
                        .padding(end = RaftSearchFieldAddonInset)
                        .size(RaftSearchFieldClearButtonSize)
                        .clip(shape)
                        .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.X,
                    color = raftArgbHex(RaftColor.TextBlack),
                    modifier = Modifier.size(RaftSearchFieldIconSize)
                )
            }
        } else {
            Spacer(Modifier.width(RaftSearchFieldAddonInset))
        }
    }
}

@Composable
private fun RaftSearchFieldPreview(
    text: String,
    placeholder: String,
    focused: Boolean,
    modifier: Modifier
) {
    RaftSearchFieldGroup(focused = focused, showClear = text.isNotEmpty(), onClear = {}, modifier = modifier) {
        Text(
            text = text.ifBlank { placeholder },
            style = raftSearchFieldTextStyle(),
            color = if (text.isBlank()) RaftColor.ForegroundHint else RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RaftSearchFieldEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    keyboardActions: KeyboardActions,
    onClear: () -> Unit,
    modifier: Modifier
) {
    var focused by remember { mutableStateOf(false) }
    RaftSearchFieldGroup(focused = focused, showClear = value.text.isNotEmpty(), onClear = onClear, modifier = modifier) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            placeholderColor = RaftColor.ForegroundHint,
            autoFocus = autoFocus,
            onFocus = { focused = true },
            onBlur = { focused = false },
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = keyboardActions,
            textStyle = raftSearchFieldTextStyle(),
            cursorBrush = SolidColor(RaftColor.TextBlack),
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RaftSearchFieldTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    keyboardActions: KeyboardActions,
    onClear: () -> Unit,
    modifier: Modifier
) {
    var focused by remember { mutableStateOf(false) }
    RaftSearchFieldGroup(focused = focused, showClear = value.isNotEmpty(), onClear = onClear, modifier = modifier) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            placeholderColor = RaftColor.ForegroundHint,
            autoFocus = autoFocus,
            onFocus = { focused = true },
            onBlur = { focused = false },
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = keyboardActions,
            textStyle = raftSearchFieldTextStyle(),
            cursorBrush = SolidColor(RaftColor.TextBlack),
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth()
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
    previewFocused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val raft = raftField && raftActive()
    if (!interactive && raft) {
        RaftSearchFieldPreview(query, placeholder, previewFocused, modifier)
        return
    }
    if (interactive && raft) {
        RaftSearchFieldTextEditor(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            autoFocus = autoFocus,
            focusRequester = focusRequester ?: remember { FocusRequester() },
            keyboardActions =
                KeyboardActions(
                    onSearch = { submitSharedHomeSearchInput(query, loading, onSubmit) },
                    onDone = { submitSharedHomeSearchInput(query, loading, onSubmit) }
                ),
            onClear = onClear,
            modifier = modifier
        )
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
    previewFocused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val raft = raftField && raftActive()
    if (!interactive && raft) {
        RaftSearchFieldPreview(query.text, placeholder, previewFocused, modifier)
        return
    }
    if (interactive && raft) {
        RaftSearchFieldEditor(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            autoFocus = autoFocus,
            focusRequester = focusRequester ?: remember { FocusRequester() },
            keyboardActions =
                KeyboardActions(
                    onSearch = { submitSharedHomeSearchInput(query.text, loading, onSubmit) },
                    onDone = { submitSharedHomeSearchInput(query.text, loading, onSubmit) }
                ),
            onClear = onClear,
            modifier = modifier
        )
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
