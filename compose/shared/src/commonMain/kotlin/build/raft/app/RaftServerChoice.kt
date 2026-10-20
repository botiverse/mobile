package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Presentational server choice row for flows that must not mutate the app-wide selected server.
 * Ownership, authorization, and selection side effects remain with the caller.
 */
@Composable
internal fun <T> RaftServerChoice(
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
            style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = RaftColor.TextMutedGray
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options.size, key = { index -> optionId(options[index]) }) { index ->
                val option = options[index]
                val id = optionId(option)
                RaftModalActionButton(
                    label = optionLabel(option),
                    onClick = { if (id != selectedId) onSelect(option) },
                    color = if (id == selectedId) RaftColor.BrandYellow else RaftColor.SurfaceWhite,
                    // Keep the selected option visually enabled even while server switching is
                    // temporarily blocked; the generic disabled treatment would erase yellow.
                    enabled = enabled || id == selectedId
                )
            }
        }
    }
}
