package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftImagePreviewSurfaceTest {
    @Test
    fun fullImageLoadKeepsThumbnailVisibleWithoutLoadingOverlay() {
        val presentation =
            raftImagePreviewLoadingPresentation(
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
    fun hydrationSwapBeforeFirstSuccessKeepsDepartedSourceAsUnderlay() {
        // task #287: thumbnail still loading when the signed full URL commits
        // (lastSucceededSource == null, single image so no fallback). Without
        // the departed-source underlay the spinner flashes a second time.
        val presentation =
            raftImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = null,
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false,
                previousRenderedSource = null,
                previousPresentableSource = "https://cdn.example/thumb.jpg"
            )

        assertEquals("https://cdn.example/thumb.jpg", presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun cachedFallbackReloadDoesNotFlashSpinnerButCanSurfaceTerminalFailure() {
        val loadingFallback =
            raftImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/thumb.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )
        val failedFallback =
            raftImagePreviewLoadingPresentation(
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
