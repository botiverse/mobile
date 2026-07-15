package build.raft.app

import com.tencent.kuikly.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun fullImageLoadKeepsThumbnailVisibleWithoutLoadingOverlay() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )

        assertEquals("https://cdn.example/thumb.jpg", presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun initialImageWithoutThumbnailStillShowsLoadingOverlay() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = null,
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )

        assertNull(presentation.fallbackUnderlaySource)
        assertTrue(presentation.showLoadingOverlay)
    }

    @Test
    fun successfulFullImageReplacesThumbnailWithoutOverlay() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = true,
                activeFailed = false
            )

        assertNull(presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun cachedFallbackReloadDoesNotFlashSpinnerButCanSurfaceTerminalFailure() {
        val loadingFallback =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/thumb.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )
        val failedFallback =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/thumb.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = true
            )

        assertNull(loadingFallback.fallbackUnderlaySource)
        assertFalse(loadingFallback.showLoadingOverlay)
        assertTrue(failedFallback.showLoadingOverlay)
    }
}
