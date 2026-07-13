package build.raft.app

import com.tencent.kuikly.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SlockImagePreviewSurfaceTest {
    @Test
    fun aspectFitLetterboxUsesNeutralSurfaceInsteadOfBrandYellow() {
        assertEquals(SlockColor.SurfaceWhite, SlockImagePreviewLetterboxColor)
        assertFalse(SlockImagePreviewLetterboxColor == SlockColor.SurfaceCream)
        assertFalse(SlockImagePreviewLetterboxColor == SlockColor.BrandYellow)
    }

    @Test
    fun thumbnailPreservesRatioWhileFillingItsFrame() {
        assertEquals(ContentScale.Crop, SlockImageThumbnailContentScale)
    }
}
