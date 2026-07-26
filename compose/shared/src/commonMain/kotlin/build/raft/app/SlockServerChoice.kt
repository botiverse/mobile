package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

/**
 * Presentational server choice row for flows that must not mutate the app-wide selected server.
 * Ownership, authorization, and selection side effects remain with the caller.
 */
@Composable
internal fun <T> SlockServerChoice(
    title: String,
    options: List<T>,
    selectedId: String?,
    optionId: (T) -> String,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options.size, key = { index -> optionId(options[index]) }) { index ->
                val option = options[index]
                val id = optionId(option)
                SlockModalActionButton(
                    label = optionLabel(option),
                    onClick = { onSelect(option) },
                    color = if (id == selectedId) SlockColor.AccentPink else SlockColor.SurfaceWhite,
                    enabled = enabled && id != selectedId
                )
            }
        }
    }
}
