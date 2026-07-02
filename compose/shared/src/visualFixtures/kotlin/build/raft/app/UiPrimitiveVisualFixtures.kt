package build.raft.app

import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp

@Composable
internal fun UiSegmentedControlVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SegmentedControl(
            selectedValue = "mentions",
            options =
                listOf(
                    SegmentedControlOption(value = "all", label = "All", count = 24),
                    SegmentedControlOption(value = "mentions", label = "Mentions", count = 3),
                    SegmentedControlOption(value = "unread", label = "Unread", count = 9)
                ),
            onValueSelected = {}
        )
    }
}
