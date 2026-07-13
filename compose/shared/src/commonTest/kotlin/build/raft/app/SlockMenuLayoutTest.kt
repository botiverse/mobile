package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockMenuLayoutTest {
    @Test
    fun taskStatusMenuFlipsAboveALowScrolledTrigger() {
        val viewport = SlockFloatingOverlayLayout(size = Size(width = 390f, height = 844f))

        assertEquals(
            SlockFloatingVerticalPlacement.Auto,
            SlockTaskStatusMenuVerticalPlacement
        )
        assertEquals(
            Offset(238f, 584f),
            viewport.positionContent(
                anchor = Offset(382f, 776f),
                contentSize = Size(width = 144f, height = 188f),
                viewportGap = 8f,
                horizontalOffset = 0f,
                verticalOffset = 4f,
                verticalPlacement = SlockTaskStatusMenuVerticalPlacement,
                horizontalAutoFlip = true
            )
        )
    }
}
