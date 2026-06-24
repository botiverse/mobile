package ai.slock.compose.components

import com.tencent.kuikly.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockAnchoredMetaChipTest {
    @Test
    fun geometryAnchorCanUseBottomEndOfChip() {
        assertEquals(
            Offset(156f, 88f),
            slockAnchoredChipGeometryAnchor(
                root = Offset(120f, 64f),
                width = 36f,
                height = 24f,
                placement = SlockAnchorPlacement.BottomEnd
            )
        )
    }

    @Test
    fun geometryAnchorCanUseBottomStartOfChip() {
        assertEquals(
            Offset(120f, 88f),
            slockAnchoredChipGeometryAnchor(
                root = Offset(120f, 64f),
                width = 36f,
                height = 24f,
                placement = SlockAnchorPlacement.BottomStart
            )
        )
    }
}
