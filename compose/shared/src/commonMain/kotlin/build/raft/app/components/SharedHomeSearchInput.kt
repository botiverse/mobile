package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

private val SharedHomeSearchInputShadowOffset = 2.dp

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
