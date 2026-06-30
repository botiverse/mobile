package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockStroke
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp

@Composable
internal fun SlockSwitchButton(
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier =
            modifier
                .size(width = 42.dp, height = 24.dp)
                .background(
                    when {
                        !enabled -> SlockColor.EmptyStateGray
                        checked -> SlockColor.AccentLime
                        else -> SlockColor.SurfaceWhite
                    }
                )
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(3.dp),
        contentAlignment =
            if (checked) {
                Alignment.CenterEnd
            } else {
                Alignment.CenterStart
            }
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
        )
    }
}
